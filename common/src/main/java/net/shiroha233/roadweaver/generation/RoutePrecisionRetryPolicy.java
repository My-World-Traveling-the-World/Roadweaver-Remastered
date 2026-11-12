/* 文件职责：让每次重试都停留在玩家选定的精度档，不因重试而偷偷降精度。 */
package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

import java.util.ArrayList;
import java.util.List;

/** Keeps every route attempt at the precision selected by the player. */
public final class RoutePrecisionRetryPolicy {
    private RoutePrecisionRetryPolicy() {
    }

    public static List<Integer> fullRouteSteps(int configuredStep) {
        int safeConfigured = Math.max(RoadConstants.ASTAR_STEP_MIN,
                Math.min(RoadConstants.ASTAR_STEP_MAX, configuredStep));
        return List.of(safeConfigured);
    }

    /** Startup refinement tiers; each tier is sampled once for the whole batch. */
    public static List<Integer> initialBatchSteps(int configuredStep) {
        return fullRouteSteps(configuredStep);
    }

    /**
     * Local repair is a separate retry phase, even when the configured route
     * precision has no lower tier.  Re-running the unresolved corridors after
     * the first batch gives the planner a chance to refresh local terrain and
     * obstacle snapshots before topology candidates are considered.
     */
    public static List<Integer> localRepairBatchSteps(int configuredStep) {
        // Local conflict repair uses a 2-block level. It is only patching the
        // recorded conflict windows, so the finest 1-block pass is unnecessary
        // and made the local repair the dominant startup cost.
        return List.of(Math.max(2, RoadConstants.ASTAR_STEP_MIN));
    }

    /** Candidate topology replacements use the same player-selected precision. */
    public static List<Integer> candidateBatchSteps(int configuredStep) {
        return List.of(Math.max(RoadConstants.ASTAR_STEP_MIN,
                Math.min(RoadConstants.ASTAR_STEP_MAX, configuredStep)));
    }

    public static int finestStep(int configuredStep) {
        return Math.max(RoadConstants.ASTAR_STEP_MIN,
                Math.min(RoadConstants.ASTAR_STEP_MAX, configuredStep));
    }

    public static boolean allowsLocalDetour(int step) {
        return step >= RoadConstants.ASTAR_STEP_MIN;
    }

    /**
     * 端点逼近冲突允许的「同一冲突中心连续重复级数」上限。
     *
     * <p>真机取证（20261112，存档 {@code 20261112  1122}，连接
     * {@code (2880,-1152)->(1936,-400)}）：冲突中心 {@code (1938,67,-398)} 距终点仅 2.8 格，
     * 于是 {@code RoadPathCalculator.ENDPOINT_APPROACH_STATIONS} 的早停豁免生效，把半径梯子
     * 50→800 整整 16 级跑满（单条 271 秒），而每一级回报的 {@code lastRejectedCenter}
     * <b>完全相同</b> —— 这说明是「终点压在结构上」的几何死局，不是「沿结构边界盘旋进村庄」。
     *
     * <p>判据：真正的盘旋每一级都会<b>移动</b>冲突中心；原地不动且连续多级同中心
     * 就说明放大圆不会收敛。上限取 4 级（50/100/150/200）既给足盘旋机会，又把死局成本
     * 从跑满整条梯子压到约 1/7。
     */
    public static final int ENDPOINT_IDENTICAL_CONFLICT_LIMIT = 4;

    /**
     * 同一冲突中心连续出现多少级就该停止升级。
     *
     * <p>非端点逼近沿用 0.8.340 的「两级同中心即停」（等价于上限 2）；端点逼近给到
     * {@link #ENDPOINT_IDENTICAL_CONFLICT_LIMIT} 级。
     */
    public static boolean shouldStopIdenticalConflictStreak(int consecutiveIdentical, boolean endpointApproach) {
        return consecutiveIdentical >= (endpointApproach ? ENDPOINT_IDENTICAL_CONFLICT_LIMIT : 2);
    }

    public static int[] localDetourBlocks() {
        return structureDetourBlocks();
    }

    public static int[] structureDetourBlocks() {
        PlanningConfig cfg = ConfigService.get().planning();
        List<Integer> blocks = new ArrayList<>();
        for (int radius = cfg.structureDetourRadiusStartBlocks();
             radius <= cfg.structureDetourRadiusMaxBlocks();
             radius += cfg.structureDetourRadiusStepBlocks()) {
            blocks.add(radius);
        }
        if (blocks.isEmpty()) blocks.add(cfg.structureDetourRadiusStartBlocks());
        return blocks.stream().mapToInt(Integer::intValue).toArray();
    }

    public static int[] lakeDetourBlocks() {
        PlanningConfig cfg = ConfigService.get().planning();
        int max = cfg.lakeDetourRadiusMaxBlocks();
        int[] base = new int[]{10, 20, 50, max};
        return java.util.Arrays.stream(base).distinct().toArray();
    }
}
