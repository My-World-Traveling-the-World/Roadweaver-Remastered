package net.shiroha233.roadweaver.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选余量相对化与范围保护的五档回归；4096 档只验几何，不冒充实际加载验收。
 *
 * <h2>为什么单独一个类</h2>
 * <p>0.9.059 的余量是<b>固定 256 格</b>，它只在「核心区 2048×2048」这一档上看起来是收窄；
 * 一旦预热范围变大（256 / 512 / 1024 / 2048 / 4096 区块），核心区边长是 8192 / 16384 / 32768 / 65536 / 131072，
 * 固定余量要么可以忽略、要么（在候选范围层面）比 0.9.058 之前的固定 6144 还大。
 * {@link AdaptiveCandidateBoundsTest} 钉的是「扩张机制与 admission 等价」，本类钉的是
 * <b>五个预热档位下的余量取值、面积守卫、以及 core 永不裁剪这条红线</b>。</p>
 *
 * <h2>五档的 core 边长是估算值（必须如实标注）</h2>
 * <p>表里的「core 边长」= 2 × 预热半径（256 区块 ⇒ 半径 4096 格 ⇒ 边长 8192），即
 * <b>预热矩形满覆盖时的上界</b>；真实 coreBounds 可能更小（例如预热只填满一部分时）。
 * 它们<b>不是真机实测的 coreBounds</b> —— 真机实测只有下面
 * {@link #realMachine2048CoreMarginStaysBetween512And1024()} 里的 2048×2048 那一档。</p>
 *
 * <h2>为什么五档全都 tripped</h2>
 * <p>五档的 core 自身面积已经 ≥ {@link AdaptiveCandidateBounds#MAX_CANDIDATE_AREA_BLOCKS}
 * （8192² = cap；更大的档位是 cap 的 4/16/64/256 倍），所以<b>几何上不可能</b>把候选压进 cap。
 * 此时 guard 只能把余量落到 {@link AdaptiveCandidateBounds#MARGIN_FLOOR}，候选面积仍然超 cap ——
 * 这是「core 必须完整枚举」这条红线的代价，不是缺陷。本类把它当成<b>已声明的边界</b>钉住：
 * 超 cap 可以，裁 core 不行。</p>
 */
final class AdaptiveCandidateBoundsScaleTest {

    /** 以 (0,0) 为原点、边长 {@code edge} 的正方形核心区（纯几何，世界坐标无关）。 */
    private static int[] squareCore(int edge) {
        return new int[]{0, 0, edge - 1, edge - 1};
    }

    private static long candidateArea(long coreEdge, int margin) {
        long edge = coreEdge + 2L * margin;
        return edge * edge;
    }

    @ParameterizedTest(name = "预热 {0} 区块（半径 {1} 格，core 边长 {2}）")
    @CsvSource({
            "256, 4096, 8192",
            "512, 8192, 16384",
            "1024, 16384, 32768",
            "2048, 32768, 65536",
            "4096, 65536, 131072"
    })
    void tierMarginAndGuardKeepCoreEnumerationIntact(int prewarmChunks,
                                                    int prewarmRadiusBlocks,
                                                    int coreEdge) {
        int[] core = squareCore(coreEdge);
        AdaptiveCandidateBounds.Rect coreRect = AdaptiveCandidateBounds.Rect.of(core);
        String where = "预热 " + prewarmChunks + " 区块/半径 " + prewarmRadiusBlocks + " 格/core 边长 " + coreEdge;

        // ① margin 落在 [MARGIN_FLOOR, MARGIN_CAP]，且对角线口径正确
        int margin = AdaptiveCandidateBounds.marginFor(core);
        assertTrue(margin >= AdaptiveCandidateBounds.MARGIN_FLOOR,
                where + "：margin=" + margin + " 低于 MARGIN_FLOOR");
        assertTrue(margin <= AdaptiveCandidateBounds.MARGIN_CAP,
                where + "：margin=" + margin + " 高于 MARGIN_CAP");
        assertEquals(Math.hypot(coreEdge, coreEdge), AdaptiveCandidateBounds.coreDiagonal(core), 1e-6,
                where + "：coreDiagonal 必须是 hypot(width, depth)");

        // ② candidate ⊇ core（红线）+ core 内四个角与中心都必须在候选矩形里
        AdaptiveCandidateBounds.Rect candidate = AdaptiveCandidateBounds.initial(core);
        assertTrue(candidate.containsAll(coreRect), where + "：候选范围必须完整包含 coreBounds");
        assertEquals(coreEdge + 2L * AdaptiveCandidateBounds.guard(core).finalMargin(),
                candidate.widthBlocks(), where + "：候选边长必须 = core 边长 + 2 × finalMargin");
        int last = coreEdge - 1;
        int[][] coreSamples = {{0, 0}, {0, last}, {last, 0}, {last, last}, {last / 2, last / 2}};
        for (int[] p : coreSamples) {
            assertTrue(candidate.contains(p[0], p[1]),
                    where + "：core 内坐标 " + p[0] + "," + p[1] + " 被候选范围裁掉了");
        }

        // ③ cap 触发时 finalMargin >= MARGIN_FLOOR，守卫后的候选范围仍包含 core
        AdaptiveCandidateBounds.CandidateGuard guard = AdaptiveCandidateBounds.guard(core);
        assertEquals(margin, guard.requestedMargin(), where + "：guard 的请求余量必须来自 marginFor");
        assertTrue(guard.finalMargin() >= AdaptiveCandidateBounds.MARGIN_FLOOR,
                where + "：finalMargin=" + guard.finalMargin() + " 低于 MARGIN_FLOOR");
        assertTrue(guard.finalMargin() <= Math.max(AdaptiveCandidateBounds.MARGIN_FLOOR, margin),
                where + "：守卫只许收窄余量，不许放大");
        assertTrue(AdaptiveCandidateBounds.boundsWithMargin(core, guard.finalMargin()).containsAll(coreRect),
                where + "：守卫后的候选范围仍必须完整包含 coreBounds（红线）");

        // ④ tripped 必须与「请求余量下的候选面积 > cap」逐档一致
        long requestedArea = candidateArea(coreEdge, margin);
        assertEquals(requestedArea > AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS, guard.tripped(),
                where + "：tripped 必须等于面积判断（请求余量下 " + candidateArea(coreEdge, margin) + " 格²）");
        assertEquals(requestedArea, guard.candidateWidth() * guard.candidateDepth(),
                where + "：guard 报的候选边长必须等于请求余量下的边长");

        // 本档已知边界：core 自身面积已 ≥ cap ⇒ 压不进 cap，余量只能落到 FLOOR，但 core 仍完整
        long coreArea = (long) coreEdge * coreEdge;
        assertTrue(coreArea >= AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS,
                where + "：本档 core 面积 " + coreArea + " 应已 ≥ cap");
        assertTrue(guard.tripped(), where + "：core 自身 ≥ cap 时请求余量必然 tripped");
        assertEquals(AdaptiveCandidateBounds.MARGIN_FLOOR, guard.finalMargin(),
                where + "：几何上压不进 cap 时，余量落到 MARGIN_FLOOR");
        assertTrue(guard.finalArea() > AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS,
                where + "：finalArea 仍超 cap 是红线代价（core 不裁剪优先），必须如实暴露");
    }

    /**
     * 回归基准：真机实测 {@code coreBounds=2048×2048}。
     *
     * <p>余量必须落在 [512, 1024] —— 既不能比 0.9.059 的固定 256 更小（那等于变激进、可能丢结构），
     * 也不能把收窄收益全吐回去（0.9.058 的 2048 外扩）。实际值 724。</p>
     */
    @Test
    void realMachine2048CoreMarginStaysBetween512And1024() {
        int[] core = squareCore(2048);
        int margin = AdaptiveCandidateBounds.marginFor(core);
        assertEquals(724, margin, "round(hypot(2048,2048) × 0.25) = round(724.08)");
        assertTrue(margin >= 512 && margin <= 1024,
                "真机 2048×2048 档的余量必须落在 [512, 1024]，实际 " + margin);
        assertTrue(margin > 256, "相对余量不得比 0.9.059 的固定 256 更小（收窄收益要保留但不能变激进）");

        AdaptiveCandidateBounds.CandidateGuard guard = AdaptiveCandidateBounds.guard(core);
        assertFalse(guard.tripped(),
                "2048×2048 档候选 3496²=12,222,016 格² 远小于 cap，不该触发守卫");
        assertEquals(margin, guard.finalMargin());
        assertEquals("coreDiag=2896.31 marginRatio=0.25 margin=724 guardTripped=false finalMargin=724",
                guard.diagnostics(), "诊断字段（0.9.060 ④）逐字口径");
        assertTrue(AdaptiveCandidateBounds.initial(core).containsAll(AdaptiveCandidateBounds.Rect.of(core)),
                "红线：候选范围必须包含 coreBounds");
    }

    /** 相对余量的三条定性性质：随核心区单调增、有下限、到上限停住。 */
    @Test
    void relativeMarginGrowsWithCoreAndStopsAtCap() {
        assertEquals(512, AdaptiveCandidateBounds.marginFor(squareCore(512)), "极小核心吃 MARGIN_FLOOR");
        assertEquals(724, AdaptiveCandidateBounds.marginFor(squareCore(2048)), "真机档");
        assertEquals(1448, AdaptiveCandidateBounds.marginFor(squareCore(4096)), "4 倍核心 ⇒ 4 倍余量");
        assertEquals(2896, AdaptiveCandidateBounds.marginFor(squareCore(8192)), "预热 256 区块档");
        assertEquals(4096, AdaptiveCandidateBounds.marginFor(squareCore(16384)), "8 倍核心触及 MARGIN_CAP");
        assertEquals(4096, AdaptiveCandidateBounds.marginFor(squareCore(65536)), "再大也不超过 MARGIN_CAP");

        int previous = 0;
        for (int edge = 256; edge <= 16384; edge *= 2) {
            int margin = AdaptiveCandidateBounds.marginFor(squareCore(edge));
            assertTrue(margin >= previous, "余量必须随核心区单调不减（edge=" + edge + "）");
            assertTrue(margin >= AdaptiveCandidateBounds.MARGIN_FLOOR
                            && margin <= AdaptiveCandidateBounds.MARGIN_CAP,
                    "余量必须恒在 [MARGIN_FLOOR, MARGIN_CAP] 内（edge=" + edge + "，margin=" + margin + "）");
            previous = margin;
        }
    }

    /**
     * 守卫的核心动作：把余量收窄到<b>能落进 cap 的最大整数</b>（不是直接砍到 FLOOR）。
     * 1000×1000 核心、请求 4096 格余量 ⇒ (1000+2m)² = 8192² ⇒ m = 3596（正好等于 cap，不多一格）。
     */
    @Test
    void guardShrinksRequestedMarginToLargestThatFitsCap() {
        int[] core = squareCore(1000);
        AdaptiveCandidateBounds.CandidateGuard guard =
                AdaptiveCandidateBounds.guard(core, AdaptiveCandidateBounds.MARGIN_CAP);
        assertTrue(guard.tripped(), "请求 4096 时候选 9192² 已超 cap");
        assertEquals(3596, guard.finalMargin(), "(1000+2m)² = 8192² ⇒ m = 3596");
        assertEquals(8192, guard.finalWidth(), "收窄后候选边长正好 8192");
        assertEquals(AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS, guard.finalArea(),
                "收窄后面积正好落在 cap 上");
        assertTrue(areaAt(1000, 3597) > AdaptiveCandidateBounds.MAX_CANDIDATE_AREA_BLOCKS,
                "再多一格余量就超 cap —— 3596 是最大可行值");

        // 边界：面积「等于」cap 不算 tripped（判据是严格大于）
        AdaptiveCandidateBounds.CandidateGuard atCap = AdaptiveCandidateBounds.guard(core, 3596);
        assertFalse(atCap.tripped(), "面积 == cap 不触发守卫（判据是 >）");
        assertEquals(3596, atCap.finalMargin());
    }

    private static long areaAt(long coreEdge, int margin) {
        long edge = coreEdge + 2L * margin;
        return edge * edge;
    }

    /** {@code [PLAN-BOUNDS-GUARD]} 一行的字段与顺序（0.9.060 ②④），含 tripped 时的实际取值。 */
    @Test
    void guardLogLineCarriesRequiredFields() {
        AdaptiveCandidateBounds.CandidateGuard guard =
                AdaptiveCandidateBounds.guard(squareCore(8192));
        assertEquals("[PLAN-BOUNDS-GUARD] coreW=8192 coreD=8192 coreDiag=11585.24 margin=2896 "
                        + "candidateW=13984 candidateD=13984 areaCap=67108864 tripped=true finalMargin=512",
                guard.describe());

        AdaptiveCandidateBounds.CandidateGuard small = AdaptiveCandidateBounds.guard(squareCore(1024));
        assertFalse(small.tripped());
        assertEquals(small.requestedMargin(), small.finalMargin(), "没触发时 finalMargin == margin");
        assertTrue(small.describe().contains("tripped=false"), small.describe());
    }

    /** 退化输入不炸：单格核心吃下限、请求余量被夹进 [FLOOR, CAP] 后仍单调收窄。 */
    @Test
    void degenerateCoresAreClampedNotExploded() {
        assertEquals(AdaptiveCandidateBounds.MARGIN_FLOOR,
                AdaptiveCandidateBounds.marginFor(new int[]{0, 0, 0, 0}), "1×1 核心吃 MARGIN_FLOOR");
        AdaptiveCandidateBounds.CandidateGuard floored =
                AdaptiveCandidateBounds.guard(new int[]{0, 0, 0, 0}, 0);
        assertEquals(AdaptiveCandidateBounds.MARGIN_FLOOR, floored.finalMargin(),
                "请求 0 被夹到 MARGIN_FLOOR（与 marginFor 同域）");
        assertFalse(floored.tripped());
        assertEquals(1025, floored.finalWidth());
        assertTrue(AdaptiveCandidateBounds.initial(new int[]{7, 7, 7, 7})
                .contains(7, 7), "单格核心必须在候选范围内");
    }
}
