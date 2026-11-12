/* 文件职责：使用 FP64 GPU 全区域精采地形计算道路原始路径。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AcceleratedSamplingUnavailableException;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract;
import net.shiroha233.roadweaver.pathfinding.terrain.region.AccurateTerrainRegion;
import net.shiroha233.roadweaver.pathfinding.terrain.region.AccurateTerrainRegionSampler;
import net.shiroha233.roadweaver.pathfinding.terrain.region.AccurateRegionLimitExceededException;

import java.util.LinkedHashMap;
import java.util.List;

final class FullRegionPlanningAdapter implements RoadTerrainPlanningPort {
    @Override
    public Result plan(Request request) {
        if (request.connections().isEmpty()) {
            return Result.empty(TerrainSamplingMode.FULL_REGION);
        }
        TerrainSamplingCache cache = new TerrainSamplingCache();
        AccurateTerrainRegion region = null;
        try {
            AccurateHeightSampler sampler = cache.getAccurateSampler(request.level());
            // Local conflict repairs always keep their circular mask even when
            // OpenCL is temporarily unavailable; samplePositions() then uses
            // the CPU backend for the same circle instead of falling back to
            // an unmasked rectangular corridor. Full startup-region planning
            // still requires accelerated sampling as before.
            if (!request.hasCircularSamplingMask() && !sampler.supportsAcceleratedSampling()) {
                throw new FullRegionUnavailableException("FP64 OpenCL GPU is unavailable");
            }
            Bounds bounds = request.bounds();
            StructureConnection firstConnection = request.connections().isEmpty()
                    ? null : request.connections().getFirst();
            region = request.hasCircularSamplingMask()
                    ? AccurateTerrainRegionSampler.sampleAcceleratedMasked(
                    request.level(), cache, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
                    request.pathfinding().effectiveAStarStep(), request.samplingCenter(), request.samplingRadius(),
                    firstConnection == null ? null : firstConnection.from(),
                    firstConnection == null ? null : firstConnection.to())
                    : AccurateTerrainRegionSampler.sampleAccelerated(
                    request.level(), cache, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(),
                    request.pathfinding().effectiveAStarStep());

            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.EXACT_PATHING, "computing_accurate_paths");
            InitialGenerationProgressTracker.setExactPathPlan(
                    request.connections().size(), "computing_accurate_paths");
            LinkedHashMap<StructureConnection, List<BlockPos>> paths = new LinkedHashMap<>();
            final AccurateTerrainRegion finalRegion = region;
            // 0.9.057：① 第一遍。本适配器本来就是串行的（精度来自 GPU 全区域精采，
            // 并行度不在这里动），所以第一遍保持逐条算完只收路径 —— 不再像 0.9.056 那样
            // 把前面的路的中心线累积成优惠走廊（那样会引入「谁先算完谁影响后面」的顺序依赖）。
            for (StructureConnection connection : request.connections()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("full-region planning interrupted");
                }
                try {
                    List<BlockPos> path = RawPathSearch.find(
                            request.level(), connection, request.pathfinding(), cache, finalRegion);
                    if (path != null && !path.isEmpty()) {
                        paths.put(connection, path);
                    }
                } finally {
                    InitialGenerationProgressTracker.recordExactPathDone();
                }
            }
            // ② 协商（纯函数）→ ③ 第二遍：只重算协商后拿到共享过河点的路。
            // 关掉时（radius<=0 / factor>=1.0）agree() 返回 null，第二遍不跑、日志不打，
            // 行为与 0.9.055 逐位相同。
            CrossingNegotiation.Agreement agreement = CrossingNegotiation.agree(
                    request.connections(), paths, finalRegion, request.pathfinding());
            if (agreement != null) {
                CrossingNegotiation.rerouteSerially(request.connections(), agreement,
                        (connection, attract) -> {
                            // 逐路新快照 + 显式挂吸引：Request 的紧凑构造器已经快照过一份
                            // （那份里 corridorAttract 恒 null，因为协商发生在第一遍之后）。
                            PathfindingCostConfig routeConfig = request.pathfinding().snapshot();
                            routeConfig.setCorridorAttract(attract);
                            return CorridorAttract.withScoped(attract, () -> RawPathSearch.find(
                                    request.level(), connection, routeConfig, cache, finalRegion));
                        }, paths, finalRegion.step());
                CoarseCorridorPlanningAdapter.logPathReuse(
                        CrossingNegotiation.stats(agreement, paths));
            }
            return new Result(TerrainSamplingMode.FULL_REGION, paths);
        } catch (java.util.concurrent.CancellationException interrupted) {
            throw interrupted;
        } catch (FullRegionUnavailableException unavailable) {
            throw unavailable;
        } catch (AcceleratedSamplingUnavailableException | AccurateRegionLimitExceededException failure) {
            throw new FullRegionUnavailableException(
                    "full-region accurate sampling failed: " + failure.getMessage(), failure);
        } catch (OutOfMemoryError insufficientMemory) {
            throw new FullRegionUnavailableException("insufficient memory for full-region accurate sampling", insufficientMemory);
        } finally {
            if (region != null && !region.isDisposed()) {
                region.dispose();
            }
            cache.clear();
        }
    }
}
