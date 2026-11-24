/* 文件职责：为最终道路路径选择可复用的精采地形高度或批量精采后端高度。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * 最终道路路径精确高度解析器。
 */
public final class AccuratePathHeightResolver {
    /**
     * 0.8.318：删除 D 阶段的 {@code ResolveMemo} 记忆（已证从未命中）。
     *
     * <p>它要求"同一线程 + 同一采样器 + 同一 terrain + 请求列表完全相同"才复用，
     * 但同一条路的第二次调用传的是 construction centers 子集且 {@code terrain=null}，
     * 列表与 terrain 都对不上，真机一次都没命中；收益 0、代价是每次 O(n) 列表比较。
     * 地形高度是世界不变量，删除记忆不改变任何取值。</p>
     */
    private AccuratePathHeightResolver() {}

    public static List<BlockPos> resolve(List<BlockPos> path,
                                         PathTerrainField terrain,
                                         AccurateHeightSampler sampler) {
        return resolve(path, terrain, sampler, false);
    }

    /** 走廊中心还未加密时必须按真实距离限坡；不能把采样精度误当最终站距。 */
    public static List<BlockPos> resolve(List<BlockPos> path, PathTerrainField terrain,
            AccurateHeightSampler sampler, boolean sparsePath) {
        return resolve(path, terrain, sampler, sparsePath, null, null);
    }

    /** 由生成入口收集耗时，采样层不反向依赖调度或日志。 */
    public static List<BlockPos> resolve(List<BlockPos> path, PathTerrainField terrain,
            AccurateHeightSampler sampler, boolean sparsePath, LongConsumer columnTiming, LongConsumer gradeTiming) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        long columnsStarted = columnTiming == null ? 0L : System.nanoTime();
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos position : path) {
            if (!hasAccurateTerrainSample(terrain, position)) {
                missing.add(position);
            }
        }
        // D 阶段：按 chunk 批量取样，而不是逐点请求。
        Map<Long, AccurateHeightSample> sampled = missing.isEmpty()
                ? Map.of()
                : sampler.sampleByChunk(missing);

        // Preserve a sustained lower profile emitted by the coarse terrain
        // mode precheck. A single lowered point is never trusted; at least
        // three consecutive tunnel-hint stations are required. This prevents
        // an exact terrain refresh from lifting a planned mountain tunnel back
        // onto the surface while still discarding accidental one-point drops.
        int[] exactHeights = new int[path.size()];
        boolean[] tunnelHints = new boolean[path.size()];
        for (int i = 0; i < path.size(); i++) {
            BlockPos position = path.get(i);
            AccurateHeightSample sample = sampled.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            exactHeights[i] = hasAccurateTerrainSample(terrain, position)
                    ? terrain.height(position.getX(), position.getZ())
                    : sample != null ? sampler.surfaceHeight(sample)
                    : sampler.surfaceHeight(position.getX(), position.getZ());
        }
        if (columnTiming != null) columnTiming.accept(System.nanoTime() - columnsStarted);
        int runStart = -1;
        for (int i = 0; i <= path.size(); i++) {
            boolean hint = i < path.size()
                    && exactHeights[i] - path.get(i).getY() >= 7;
            if (hint && runStart < 0) runStart = i;
            if ((!hint || i == path.size()) && runStart >= 0) {
                if (i - runStart >= 3) {
                    for (int j = runStart; j < i; j++) tunnelHints[j] = true;
                }
                runStart = -1;
            }
        }

        // 陡壁在规划期就规划好：把"逐列贴地表"换成"合法坡度剖面"，
        // 否则粗采样阶段算好的坡度约束会被精确高度刷新丢掉，陡壁只能靠
        // 局部修复兜底（修不动就反复触发）。
        //
        // 期望高度不只是地表：已判定隧道的站用洞身弦高度作期望，求解器就会在洞口
        // 把地表段与洞身弦用合法坡度连起来（洞口坡道），不再留下 1 站跳 11~12 格的台阶。
        // 水面（桥面剖面另有规划）是硬边界：钉在期望高度上并断开求解。
        boolean[] splitMask = new boolean[path.size()];
        int[] targetY = exactHeights.clone();
        for (int i = 0; i < path.size(); i++) {
            BlockPos position = path.get(i);
            splitMask[i] = terrain != null
                    && terrain.contains(position.getX(), position.getZ())
                    && terrain.isColumnWater(position.getX(), position.getZ());
            if (tunnelHints[i]) {
                targetY[i] = Math.min(position.getY(), exactHeights[i]);
            }
        }
        // 精采阶段也会求一次坡面，单列计时以免把算法时间误归给地形查询。
        long gradeStarted = gradeTiming == null ? 0L : System.nanoTime();
        int[] gradeProfile = RoadGradePlanner.planAccurateGradeProfile(
                targetY, splitMask, slopeStep(), slopeLimitEnabled(), tunnelHints,
                sparsePath ? SlopeContinuityEnforcer.stationSpacing(path)
                        : SlopeContinuityEnforcer.configuredStationSpacing(path), sparsePath);
        if (gradeTiming != null) gradeTiming.accept(System.nanoTime() - gradeStarted);

        List<BlockPos> resolved = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            BlockPos position = path.get(i);
            int y = tunnelHints[i]
                    ? Math.min(targetY[i], gradeProfile[i])
                    : gradeProfile[i];
            resolved.add(new BlockPos(position.getX(), y, position.getZ()));
        }
        return resolved;
    }

    private static boolean slopeLimitEnabled() {
        try {
            return ConfigService.get().slopeLimitEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int slopeStep() {
        try {
            return ConfigService.get().maxSlopeStepPerTwoSegments();
        } catch (Throwable ignored) {
            return 2;
        }
    }

    private static boolean hasAccurateTerrainSample(PathTerrainField terrain, BlockPos position) {
        return terrain != null && terrain.hasAccurateSample(position.getX(), position.getZ());
    }
}
