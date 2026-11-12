/* 文件职责：RoadSpec 的不变量与负控 —— 钉死「三套事实必须一致」。 */
package net.shiroha233.roadweaver.spec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoadSpec} 的不变量测试。
 *
 * <h2>为什么负控比正控重要</h2>
 * <p>这个类的全部价值在于「不一致就当场失败」。如果负控写不出来，
 * 就等于它只会在一切顺利时沉默 —— 那和没有它是一样的。
 * 下面几个负控各自对应一种历史上<b>真实发生过</b>的坏数据形态。</p>
 *
 * <h2>★ 为什么不构造 RoadData</h2>
 * <p>{@code RoadData} 的静态 {@code CODEC} 在类初始化时会碰 {@code BlockState}，
 * 而纯 JUnit 环境里没有方块注册表 —— 一旦构造就炸。所以测试只走
 * {@link RoadSpec#of}（真实解析逻辑所在），{@link RoadSpec#from} 只是它的适配器。
 * 这条限制本身也说明「解析逻辑必须能脱离数据容器单独存在」，正是拆出 {@code of} 的理由。</p>
 */
final class RoadSpecTest {

    /** 造一条直线路：站 i 的中间站是 (i, 64, 0)。 */
    private static List<RoadSegmentPlacement> straight(int n) {
        List<RoadSegmentPlacement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos p = new BlockPos(i, 64, 0);
            out.add(new RoadSegmentPlacement(p, List.of(p)));
        }
        return out;
    }

    private static List<Integer> flat(int n, int y) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(y);
        return out;
    }

    private static RoadSpec spec(List<RoadSegmentPlacement> stations, List<RoadSpan> spans, List<Integer> y) {
        return RoadSpec.of(stations, y, spans, RoadData.NO_OWNER_2D, RoadData.NO_OWNER_2D);
    }

    @Test
    void 区间被一次性解析成站索引() {
        List<RoadSegmentPlacement> stations = straight(10);
        List<RoadSpan> spans = List.of(new RoadSpan(
                new BlockPos(2, 64, 0), new BlockPos(5, 64, 0), SpanType.BRIDGE));
        RoadSpec spec = spec(stations, spans, flat(10, 64));

        assertEquals(10, spec.stationCount(), "case=stations");
        assertEquals(1, spec.sections().size(), "case=one-section");
        RoadSpec.Section s = spec.sections().get(0);
        assertEquals(2, s.fromStation(), "case=from-index");
        assertEquals(5, s.toStation(), "case=to-index");
        assertEquals(4, s.length(), "case=length");
        assertEquals(SpanType.BRIDGE, s.type(), "case=type");

        assertNotNull(spec.sectionAt(3), "case=inside");
        assertNull(spec.sectionAt(1), "case=outside");
        assertEquals(6, spec.uncoveredStations(), "case=uncovered");
        assertEquals(1, spec.sectionsOf(SpanType.BRIDGE).size(), "case=by-type");
        assertEquals(0, spec.sectionsOf(SpanType.TUNNEL).size(), "case=by-other-type");
        assertEquals(64, spec.heightAt(7), "case=height");
    }

    @Test
    void 负控_剖面与站数不一致必须失败() {
        RoadSpec.SpecViolationException e = assertThrows(RoadSpec.SpecViolationException.class,
                () -> spec(straight(10), List.of(), flat(9, 64)),
                "case=profile-size-mismatch");
        assertTrue(e.getMessage().contains("剖面长度与站数不一致"), "case=message: " + e.getMessage());
    }

    @Test
    void 负控_区间端点找不到站必须失败() {
        List<RoadSpan> spans = List.of(new RoadSpan(
                new BlockPos(99, 64, 0), new BlockPos(5, 64, 0), SpanType.BRIDGE));
        RoadSpec.SpecViolationException e = assertThrows(RoadSpec.SpecViolationException.class,
                () -> spec(straight(10), spans, flat(10, 64)),
                "case=unresolvable-endpoint");
        assertTrue(e.getMessage().contains("找不到对应站"), "case=message: " + e.getMessage());
    }

    @Test
    void 负控_同XZ多个候选必须失败而不是任选一个() {
        // 站 4 与站 3 的 XZ 相同、Y 不同 —— 这正是路网合并重建站序列后的形态。
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int y = (i == 4) ? 65 : 64;
            int x = (i == 4) ? 3 : i;
            BlockPos p = new BlockPos(x, y, 0);
            stations.add(new RoadSegmentPlacement(p, List.of(p)));
        }
        // 起点 Y=70：两个候选都不精确命中，只能走「同 XZ」兜底，而它有歧义。
        List<RoadSpan> spans = List.of(new RoadSpan(
                new BlockPos(3, 70, 0), new BlockPos(5, 64, 0), SpanType.BRIDGE));
        RoadSpec.SpecViolationException e = assertThrows(RoadSpec.SpecViolationException.class,
                () -> spec(stations, spans, flat(10, 64)),
                "case=ambiguous-same-xz");
        assertTrue(e.getMessage().contains("歧义"), "case=message: " + e.getMessage());
    }

    @Test
    void 负控_区间重叠必须失败() {
        List<RoadSpan> spans = List.of(
                new RoadSpan(new BlockPos(1, 64, 0), new BlockPos(5, 64, 0), SpanType.BRIDGE),
                new RoadSpan(new BlockPos(4, 64, 0), new BlockPos(8, 64, 0), SpanType.TUNNEL));
        RoadSpec.SpecViolationException e = assertThrows(RoadSpec.SpecViolationException.class,
                () -> spec(straight(10), spans, flat(10, 64)),
                "case=overlapping-sections");
        assertTrue(e.getMessage().contains("重叠"), "case=message: " + e.getMessage());
    }

    @Test
    void 负控_剖面空洞必须失败() {
        List<Integer> y = flat(5, 64);
        y.set(2, null);
        assertThrows(RoadSpec.SpecViolationException.class,
                () -> spec(straight(5), List.of(), y),
                "case=null-in-profile");
    }

    @Test
    void 没有区间时也成立() {
        RoadSpec sp = spec(straight(4), List.of(), flat(4, 70));
        assertTrue(sp.sections().isEmpty(), "case=no-sections");
        assertEquals(4, sp.uncoveredStations(), "case=all-uncovered");
        assertFalse(sp.stationCount() == 0, "case=non-empty");
        assertEquals(70, sp.heightAt(0), "case=height");
    }
}