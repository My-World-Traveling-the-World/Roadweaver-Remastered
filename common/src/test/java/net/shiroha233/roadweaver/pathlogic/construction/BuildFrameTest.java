/* 文件职责：BuildFrame 缓存的正确性测试 —— 重点是「发出去的副本不许和缓存串味」。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildFrame} 的测试。
 *
 * <h2>为什么这条「串味」测试是重点</h2>
 * <p>缓存的收益是省一次 O(站数) 计算；风险却是<b>把可变数组共享出去</b>。
 * {@code RangeResult} 里是 {@code boolean[]} 与 {@code List<int[]>}，
 * 区块生成会就地改这些掩码（比如按运行期水位把某段提升成桥）。</p>
 *
 * <p>如果缓存把同一份数组发两次，第二个区块就会读到被第一个区块改过的掩码 ——
 * 这种 bug 只在「同一片区域生成顺序特殊」时出现，几乎不可能靠试玩复现。
 * 所以这里必须显式验证：<b>改副本不影响缓存</b>。</p>
 */
final class BuildFrameTest {

    @BeforeEach
    void reset() {
        BuildFrame.clear();
    }

    private static List<RoadSegmentPlacement> stations(int n) {
        List<RoadSegmentPlacement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos p = new BlockPos(i, 64, 0);
            out.add(new RoadSegmentPlacement(p, List.of(p)));
        }
        return out;
    }

    @Test
    void 同一条路只算一次() {
        BuildFrame.Frames a = BuildFrame.of(1234L, stations(10), List.of(), null);
        BuildFrame.Frames b = BuildFrame.of(1234L, stations(10), List.of(), null);
        assertSame(a, b, "case=cache-hit-same-instance");
        assertEquals(1, BuildFrame.size(), "case=one-entry");
        assertEquals(10, a.middlePositions().size(), "case=stations");
    }

    @Test
    void 发出去的副本可以随便改且不影响缓存() {
        BuildFrame.Frames frames = BuildFrame.of(77L, stations(8), List.of(), null);

        BridgeRangeCalculator.RangeResult copy1 = frames.copyForUser();
        assertFalse(copy1.isBridge()[0], "case=initial-false");
        copy1.isBridge()[0] = true;                     // 模拟区块生成就地改掩码
        copy1.skipSegments()[3] = true;

        BridgeRangeCalculator.RangeResult copy2 = BuildFrame.of(77L, stations(8), List.of(), null)
                .copyForUser();
        assertFalse(copy2.isBridge()[0], "case=cache-not-polluted-bridge");
        assertFalse(copy2.skipSegments()[3], "case=cache-not-polluted-skip");
        assertNotSame(copy1.isBridge(), copy2.isBridge(), "case=distinct-arrays");
    }

    @Test
    void 不同指纹是不同的条目() {
        BuildFrame.Frames a = BuildFrame.of(1L, stations(5), List.of(), null);
        BuildFrame.Frames b = BuildFrame.of(2L, stations(5), List.of(), null);
        assertNotSame(a, b, "case=distinct-fingerprint");
        assertEquals(2, BuildFrame.size(), "case=two-entries");
    }

    @Test
    void 清空后重新计算() {
        BuildFrame.Frames a = BuildFrame.of(9L, stations(4), List.of(), null);
        BuildFrame.clear();
        assertEquals(0, BuildFrame.size(), "case=cleared");
        BuildFrame.Frames b = BuildFrame.of(9L, stations(4), List.of(), null);
        assertNotSame(a, b, "case=recomputed");
        assertTrue(b.middlePositions().size() == 4, "case=content-intact");
    }

    @Test
    void 中心线列表是不可变的() {
        // PathFeature 全程只读它，但「只读」应当是**被强制**的，不能靠约定。
        BuildFrame.Frames frames = BuildFrame.of(555L, stations(6), List.of(), null);
        List<net.minecraft.core.BlockPos> centers = frames.middlePositions();
        assertEquals(6, centers.size(), "case=size");
        assertThrows(UnsupportedOperationException.class,
                () -> centers.add(new net.minecraft.core.BlockPos(99, 64, 99)),
                "case=unmodifiable-add");
        assertThrows(UnsupportedOperationException.class,
                () -> centers.set(0, new net.minecraft.core.BlockPos(99, 64, 99)),
                "case=unmodifiable-set");
    }

    @Test
    void 桥区间里的int数组也是深拷贝() {
        // 打开桥配置并先断言区间非空，才能实际污染一份副本，避免空循环放过浅拷贝。
        var cfg = new ModConfig();
        var spans = List.of(span(2, 5, SpanType.BRIDGE));
        var frames = BuildFrame.of(777L, stations(8), spans, cfg);
        var first = frames.copyForUser();
        assertEquals(1, first.mergedRanges().size());
        first.mergedRanges().getFirst()[0] = 999;
        var second = BuildFrame.of(777L, stations(8), spans, cfg).copyForUser();
        assertEquals(1, second.mergedRanges().size());
        assertEquals(2, second.mergedRanges().getFirst()[0]);
    }

    @Test
    void 同旧标量的两套桥参数不能串掩码() {
        // 两个值都合法；旧乘 31 键将 (100,32) 和 (101,1) 压成同一数。
        ModConfig joined = new ModConfig();
        joined.bridge().setMaxLengthBlocks(100);
        joined.bridge().setMergeGap(32);
        ModConfig separate = new ModConfig();
        separate.bridge().setMaxLengthBlocks(101);
        separate.bridge().setMergeGap(1);
        var spans = List.of(span(0, 2, SpanType.BRIDGE), span(6, 8, SpanType.BRIDGE));
        var road = stations(12);
        assertEquals(1, BuildFrame.of(90L, road, spans, joined).copyForUser().mergedRanges().size());
        var hot = BuildFrame.of(90L, road, spans, separate).copyForUser();
        assertEquals(2, hot.mergedRanges().size());
        assertFalse(hot.isBridge()[4], "中间的陆地不能继承已合并成桥的缓存");
    }

    @Test
    void 无方向道路键的反向站序必须重映射桥下标() {
        var road = stations(12);
        var spans = List.of(span(0, 2, SpanType.BRIDGE));
        var cfg = new ModConfig();
        assertTrue(BuildFrame.of(91L, road, spans, cfg).copyForUser().isBridge()[0]);

        // 模拟存储同一个无方向指纹，掩码应该随正反站序反转。
        var reverse = BuildFrame.of(91L, road.reversed(), spans, cfg);
        assertEquals(road.getLast().middlePos(), reverse.middlePositions().getFirst());
        assertFalse(reverse.copyForUser().isBridge()[0]);
        assertTrue(reverse.copyForUser().isBridge()[11]);
    }

    private static RoadSpan span(int from, int to, SpanType type) {
        return new RoadSpan(new BlockPos(from, 64, 0), new BlockPos(to, 64, 0), type);
    }

    @Test
    void 同中心线桥改隧道不得复用旧桥掩码() {
        // 回归钉子：RoadFingerprint 只看中心线；若 BuildFrame 的键不额外纳入 spans，
        // 两次调用会拿到同一 Frames，第二条的 isBridge 会被第一条污染。
        ModConfig cfg = new ModConfig();
        BuildFrame.Frames bridge = BuildFrame.of(888L, stations(8),
                List.of(span(2, 5, SpanType.BRIDGE)), cfg);
        BuildFrame.Frames tunnel = BuildFrame.of(888L, stations(8),
                List.of(span(2, 5, SpanType.TUNNEL)), cfg);

        assertNotSame(bridge, tunnel, "case=span-type-is-part-of-key");
        assertTrue(bridge.copyForUser().isBridge()[2], "case=bridge-mask-present");
        assertFalse(tunnel.copyForUser().isBridge()[2], "case=tunnel-must-not-inherit-bridge");
        assertEquals(2, BuildFrame.size(), "case=distinct-span-content");
    }

    @Test
    void 同类型跨度改起止坐标也必须换缓存条目() {
        ModConfig cfg = new ModConfig();
        BuildFrame.Frames first = BuildFrame.of(889L, stations(10),
                List.of(span(2, 4, SpanType.BRIDGE)), cfg);
        BuildFrame.Frames moved = BuildFrame.of(889L, stations(10),
                List.of(span(6, 8, SpanType.BRIDGE)), cfg);

        assertNotSame(first, moved, "case=span-coordinates-are-part-of-key");
        assertTrue(first.copyForUser().isBridge()[2], "case=old-range-present");
        assertFalse(moved.copyForUser().isBridge()[2], "case=moved-range-dropped-old-station");
        assertTrue(moved.copyForUser().isBridge()[6], "case=moved-range-present");
    }
}
