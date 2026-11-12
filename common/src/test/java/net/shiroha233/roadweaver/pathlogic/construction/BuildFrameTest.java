/* 文件职责：BuildFrame 缓存的正确性测试 —— 重点是「发出去的副本不许和缓存串味」。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
}