/* 文件职责：道路最终校验器的失败分支契约 —— 每个 reason 都要能被真的触发出来。 */
package net.shiroha233.roadweaver.pathlogic.validation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RoadDataValidator.validateFinalRoad} 的<b>失败分支</b>契约（批 7 补覆盖）。
 *
 * <h2>为什么补这个</h2>
 * <p>2026-11-15 实测：这个校验器有 7 个失败 {@code reason}，但
 * {@code target-height-size} / {@code invalid-station} / {@code null-spans} /
 * {@code invalid-span-endpoint} / {@code invalid-height-step} <b>五个从未在任何测试里出现过</b>
 * —— 也就是说这五条拒绝规则是否真的生效，没有任何证据。
 * 而它们是「一条不合法的路不许进世界」的最后一道闸。</p>
 *
 * <h2>夹具口径（必须与生产一致，否则测的是假话）</h2>
 * <ul>
 *   <li>相邻站中心 max-norm 步长 ≤ {@code RoadConstants.MAX_CENTER_STEP = 2}
 *       ⇒ 本类夹具站距 <b>2 格</b>；</li>
 *   <li>相邻 {@code targetY} 差 ≤ {@code MAX_HEIGHT_STEP = 1}，最多允许 3 次例外；</li>
 *   <li>每站 {@code middlePos} 非空、{@code positions} 非空、{@code targetY} 等长且非空；</li>
 *   <li>{@code spans} 不许为 {@code null}（可以是空表）。</li>
 * </ul>
 * <p>★ 「正向基线」用例（{@link #合法路必须通过校验()}）放在最前面：
 * 它先证明夹具是合法的，否则下面每条「应当被拒绝」的用例都可能因为别的原因失败，
 * 变成「看起来在测 A 其实测的是 B」。</p>
 */
class RoadDataValidatorFailureModesTest {

    /** 造一条 n 站的直路：X 每站 +2，Y 恒 64，Z 固定。 */
    private static RoadData road(int n) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos center = new BlockPos(i * 2, 64, 0);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights, 1L, 2L);
    }

    private static RoadData withSegments(RoadData src, List<RoadSegmentPlacement> segments,
                                        List<Integer> targetY, List<RoadSpan> spans) {
        return new RoadData(src.width(), src.roadType(), src.materials(), src.slabMaterials(),
                segments, spans, targetY, src.ownerA2dKey(), src.ownerB2dKey());
    }

    // ══════════ 零、正向基线 ══════════

    @Test
    void 合法路必须通过校验() {
        RoadData road = road(11);
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(road);
        assertTrue(r.valid(),
                "★ 正向基线：本类夹具必须合法，否则下面每条「应当被拒绝」的用例都失去意义。"
                        + "实际 reason=" + r.reason() + " index=" + r.index() + " detail=" + r.detail());
        assertEquals("ok", r.reason(), "合法路的 reason 必须是 ok");
        assertEquals(-1, r.index(), "合法路不该带失败下标");
    }

    // ══════════ 一、target-height-size：targetY 与站点数不一致 ══════════

    @Test
    void targetY_比站点数少时必须拒绝() {
        RoadData base = road(11);
        List<Integer> shorter = new ArrayList<>(base.targetY().subList(0, 10));
        RoadData broken = withSegments(base, base.roadSegmentList(), shorter, List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "targetY 少一格必须被拒绝 —— 否则下游按索引取高度会越界");
        assertEquals("target-height-size", r.reason(),
                "reason 必须精确是 target-height-size（下游据此分流修复），实际 " + r.reason());
    }

    @Test
    void targetY_为_null时必须拒绝() {
        RoadData base = road(11);
        RoadData broken = withSegments(base, base.roadSegmentList(), null, List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "targetY 为 null 必须被拒绝");
        assertEquals("target-height-size", r.reason(),
                "null 与长度不符共用同一个 reason，实际 " + r.reason());
    }

    // ══════════ 二、invalid-station：站点缺中心或缺 footprint ══════════

    @Test
    void 站点没有_footprint_时必须拒绝() {
        RoadData base = road(11);
        List<RoadSegmentPlacement> segments = new ArrayList<>(base.roadSegmentList());
        BlockPos center = segments.get(5).middlePos();
        // positions 传空表 ⇒ 没有 footprint
        segments.set(5, new RoadSegmentPlacement(center, List.of()));
        RoadData broken = withSegments(base, segments, base.targetY(), List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "站点 footprint 为空必须被拒绝");
        assertEquals("invalid-station", r.reason(), "实际 " + r.reason());
        assertEquals(5, r.index(), "失败下标必须指向那一站，实际 " + r.index());
    }

    // ══════════ 三、invalid-center-step：相邻站跨太远 ══════════

    @Test
    void 相邻站中心跨三格时必须拒绝() {
        RoadData base = road(11);
        List<RoadSegmentPlacement> segments = new ArrayList<>(base.roadSegmentList());
        // 把第 6 站推到 +5 格（原始是 +2 ⇒ 相邻步长 5 > MAX_CENTER_STEP=2）
        BlockPos far = new BlockPos(12 + 5, 64, 0);
        segments.set(6, new RoadSegmentPlacement(far, List.of(far)));
        RoadData broken = withSegments(base, segments, base.targetY(), List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "相邻站跨 5 格（上限 2）必须被拒绝 —— 否则路会撕开");
        assertEquals("invalid-center-step", r.reason(), "实际 " + r.reason());
    }

    // ══════════ 四、null-spans：spans 为 null ══════════

    @Test
    void spans_为_null_会被构造器归一成空表_校验器的_null_span_分支不可达() {
        RoadData base = road(11);
        RoadData built = withSegments(base, base.roadSegmentList(), base.targetY(), null);

        // ★ 2026-11-15 实测结论：`RoadData` 的构造器第一行就是
        //     spans = List.copyOf(spans == null ? List.of() : spans);
        //   ⇒ 通过公开构造函数**造不出** spans() == null 的 RoadData。
        //   所以校验器里的 `if (road.spans() == null) return failure("null-spans", ...)`
        //   是一条**不可达的防御分支**（防的是序列化/反射等旁路），不是可触发规则。
        assertTrue(built.spans() != null,
                "构造器必须把 null spans 归一成非 null（这是真实契约）");
        assertTrue(built.spans().isEmpty(), "归一后的空表必须为空");
        assertTrue(RoadDataValidator.validateFinalRoad(built).valid(),
                "既然 spans 已被归一，这条路的其余部分合法 ⇒ 整体必须通过。"
                        + "★ 若这里红了，说明有人把这层归一删了，"
                        + "那么序列化出来的老数据会突然开始被拒");
    }

    // ══════════ 五、invalid-span-endpoint：span 端点不在站点上 ══════════

    @Test
    void span_端点不在任何站点上时必须拒绝() {
        RoadData base = road(11);
        // 端点故意用一个不存在的坐标
        RoadSpan stray = new RoadSpan(new BlockPos(999, 64, 999),
                base.roadSegmentList().get(9).middlePos(), SpanType.BRIDGE);
        RoadData broken = withSegments(base, base.roadSegmentList(), base.targetY(), List.of(stray));
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "span 端点找不到站索引必须被拒绝");
        assertEquals("invalid-span-endpoint", r.reason(), "实际 " + r.reason());
    }

    @Test
    void 单站保护缺口是合法的例外() {
        // 校验器明确允许「只占一个站」的 PROTECTED_GAP（窄建筑可能只压到一个采样站）
        RoadData base = road(11);
        RoadSpan oneStation = new RoadSpan(
                base.roadSegmentList().get(4).middlePos(),
                base.roadSegmentList().get(4).middlePos(), SpanType.PROTECTED_GAP);
        RoadData ok = withSegments(base, base.roadSegmentList(), base.targetY(),
                List.of(oneStation));
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(ok);
        assertTrue(r.valid(),
                "★ 单站 PROTECTED_GAP 是**明确允许**的例外（注释：窄建筑可能只占一个采样站）。"
                        + "若这里红了，说明那个例外被删掉了 —— 窄建筑的路会被整条丢弃。"
                        + "实际 reason=" + r.reason() + " detail=" + r.detail());
    }

    // ══════════ 六、invalid-height-step：相邻 targetY 差太大 ══════════

    @Test
    void 连续高度跳变超过例外次数时必须拒绝() {
        RoadData base = road(20);
        List<Integer> heights = new ArrayList<>(base.targetY());
        // 让第 1..8 站高度每隔一站跳 2 格 ⇒ 产生 4 次以上 >MAX_HEIGHT_STEP 的跳变，
        // 超过 MAX_HEIGHT_STEP_EXCEPTIONS(3) 的容忍上限
        for (int i = 1; i < heights.size(); i++) {
            heights.set(i, 64 + (i % 2 == 0 ? 0 : 2));
        }
        RoadData broken = withSegments(base, base.roadSegmentList(), heights, List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(broken);
        assertFalse(r.valid(), "连续高度跳变超过容忍次数必须被拒绝 —— 否则会挖出断崖");
        assertEquals("invalid-height-step", r.reason(), "实际 " + r.reason());
    }

    @Test
    void 少量高度例外是被容忍的() {
        RoadData base = road(11);
        List<Integer> heights = new ArrayList<>(base.targetY());
        heights.set(5, 66);   // 单次跳 2 格，在 MAX_HEIGHT_STEP_EXCEPTIONS(3) 之内
        RoadData ok = withSegments(base, base.roadSegmentList(), heights, List.of());
        RoadDataValidator.ValidationResult r = RoadDataValidator.validateFinalRoad(ok);
        assertTrue(r.valid(),
                "★ 少量（≤3 次）高度例外是**明确容忍**的 —— 合并/修复会让相邻剖面差一格。"
                        + "若这里红了，说明容忍窗口被收掉，正常合并出来的路会被整条丢弃。"
                        + "实际 reason=" + r.reason() + " detail=" + r.detail());
    }
}