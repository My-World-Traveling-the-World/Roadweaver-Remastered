/* 文件职责：使用粗地形高度场规划道路，避免在线阶段重复构造原版 NoiseChunk。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationCoarseSamplingProgress;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainRegion;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainRegionSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 粗地形道路规划器。
 *
 * <p>粗高度场与原版 preliminary surface 使用相同的噪声语义。路径的最终切填高度由
 * RoadChunkPlan 在区块生成前编译，不在规划线程重新执行一次完整区块噪声生成。</p>
 */
final class CoarseCorridorPlanningAdapter implements RoadTerrainPlanningPort {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    @Override
    public Result plan(Request request) {
        if (request.connections().isEmpty()) {
            return Result.empty(TerrainSamplingMode.COARSE_CORRIDOR);
        }

        final TerrainSamplingCache cache = new TerrainSamplingCache();
        CoarseTerrainRegion terrain = null;
        try {
            Bounds bounds = request.bounds();
            int step = request.pathfinding().effectiveAStarStep();
            long startedAt = System.nanoTime();
            terrain = CoarseTerrainRegionSampler.sample(
                    request.level(), bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), step,
                    InitialGenerationCoarseSamplingProgress.INSTANCE);
            long samplingMillis = elapsedMillis(startedAt);
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.COARSE_PATHING, "computing_coarse_paths");
            InitialGenerationProgressTracker.setCoarsePathPlan(
                    request.connections().size(), "computing_coarse_paths");

            final CoarseTerrainRegion finalTerrain = terrain;
            LinkedHashMap<StructureConnection, List<BlockPos>> paths = new LinkedHashMap<>();
            long pathfindingStartedAt = System.nanoTime();
            // ════════════════════════════════════════════════════════════════════
            // ① 第一遍并行：提交方式与 0.9.055 一字未改（每条连接一个独立任务，
            //    全部丢给 INITIAL 池，主线程只负责等）。0.9.056 曾为了让「后算的路
            //    被先算完的路吸过去」把这里改成串行 —— 那是拿吞吐换联动，本版撤掉。
            // ════════════════════════════════════════════════════════════════════
            List<java.util.concurrent.Future<List<BlockPos>>> pathFutures = new ArrayList<>();
            java.util.Map<StructureConnection, List<BlockPos>> found =
                    new java.util.concurrent.ConcurrentHashMap<>();
            for (StructureConnection connection : request.connections()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("coarse planning interrupted");
                }
                final StructureConnection routeConnection = connection;
                pathFutures.add(CoarsePathTasks.DEFAULT.submit(() -> {
                    List<BlockPos> path = RawPathSearch.find(
                            request.level(), routeConnection, request.pathfinding(), cache, finalTerrain);
                    if (path != null && !path.isEmpty()) {
                        found.put(routeConnection, List.copyOf(path));
                    }
                    InitialGenerationProgressTracker.recordCoarsePathDone();
                    return path;
                }));
            }
            // 0.8.340：等待方式可切换。任务**提交**方式完全不变（同样是 16 个独立任务
            // 交给同一个线程池），只改「主线程怎么等」：原实现逐个 future.get() 串行等待，
            // 第一个任务拖多久后面就白等多久；开启并行等待后改为同时等全部完成。
            //
            // 语义严格保持：任一任务失败立即抛出（ExecutionException → IllegalStateException），
            // 线程被中断立即取消剩余任务并抛 CancellationException。因此产出与旧实现逐位一致。
            if (parallelWaitEnabled()) {
                awaitAllPathsParallel(pathFutures);
            } else {
                for (java.util.concurrent.Future<List<BlockPos>> future : pathFutures) {
                    awaitOnePath(future, pathFutures);
                }
            }
            for (StructureConnection connection : request.connections()) {
                List<BlockPos> path = found.get(connection);
                if (path != null) paths.put(connection, path);
            }
            // ════════════════════════════════════════════════════════════════════
            // ② 协商（单线程纯函数，微秒级）→ ③ 第二遍并行（只重算被吸引的路）
            //
            // 真机 (236,-170)：出生点 (32,-64) 分出两条连接各去一个村庄，两条路各自
            // 看见水就各自架桥（甲板 Y=48 / Y=65，差 17 格，生成后处理合不回来）。
            // 现在两条路先各自跑完第一遍，把「过河点」交上来，由
            // CrossingNegotiator 聚簇选出共享过河点，再让簇内的路朝那个点打折重算 ——
            // 全程没有一条路等另一条路算完，第一遍与第二遍都是并行提交。
            //
            // 关掉时（corridorAttractRadius<=0 / corridorAttractFactor>=1.0）
            // agree() 返回 null：第二遍不跑、日志不打，行为与 0.9.055 逐位相同。
            // ════════════════════════════════════════════════════════════════════
            CrossingNegotiation.Agreement agreement = CrossingNegotiation.agree(
                    request.connections(), paths, finalTerrain, request.pathfinding());
            if (agreement != null) {
                rerouteAttracted(request, cache, finalTerrain, agreement, paths);
                logPathReuse(CrossingNegotiation.stats(agreement, paths));
            }
            long pathfindingMillis = elapsedMillis(pathfindingStartedAt);
            LOGGER.info("RoadWeaver coarse planning timing: dimension={} step={} samples={} connections={} resolvedPaths={} samplingMs={} pathfindingMs={} totalMs={}",
                    request.level().dimension().location(),
                    step,
                    terrain.bounds().sampleCount(),
                    request.connections().size(),
                    paths.size(),
                    samplingMillis,
                    pathfindingMillis,
                    elapsedMillis(startedAt));
            return new Result(TerrainSamplingMode.COARSE_CORRIDOR, paths);
        } finally {
            if (terrain != null) terrain.dispose();
            cache.clear();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 0.9.057：③ 第二遍并行规划 —— 只重算「协商后拿到共享过河点」的路。
     *
     * <p>与第一遍<b>完全同一套提交方式</b>：每条被吸引的连接一个独立任务丢进 INITIAL 池，
     * 主线程只负责等。路与路之间不共享任何状态（吸引器是按路造好的不可变实例，
     * 不从别的路的产出里累积），所以第二遍并行与第一遍并行是同一个并行度 ——
     * 这正是 0.9.056 的串行实现做不到的那件事。</p>
     *
     * <p>三条质量约束：</p>
     * <ul>
     *   <li>没拿到吸引的路<b>一个字节都不动</b>（保持第一遍的产出）；</li>
     *   <li>第二遍某条路算不出路径时<b>保留第一遍的结果</b>，绝不把一条已经能建的路弄丢；</li>
     *   <li>第二遍整体失败（任务抛错）只吞掉这一次「优化尝试」并保留第一遍产出，
     *       但<b>中断照常向上传播</b> —— 取消语义不能被降级成警告。</li>
     * </ul>
     *
     * <p>进度计数只在第一遍记（{@code recordCoarsePathDone}）；第二遍是同一条路的
     * 第二次尝试，再记一次会把规划进度推到 200%。</p>
     */
    private static void rerouteAttracted(Request request,
                                         TerrainSamplingCache cache,
                                         CoarseTerrainRegion terrain,
                                         CrossingNegotiation.Agreement agreement,
                                         LinkedHashMap<StructureConnection, List<BlockPos>> paths) {
        List<java.util.concurrent.Future<List<BlockPos>>> futures = new ArrayList<>();
        java.util.Map<StructureConnection, List<BlockPos>> replanned =
                new java.util.concurrent.ConcurrentHashMap<>();
        for (StructureConnection connection : request.connections()) {
            CorridorAttract attract = agreement.attractFor(connection);
            if (attract == null || attract.isEmpty()) continue;
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("coarse planning interrupted");
            }
            final StructureConnection routeConnection = connection;
            final CorridorAttract routeAttract = attract;
            // 逐路快照 + 显式挂吸引：Request 的紧凑构造器已经快照过一份配置，那份里
            // corridorAttract 恒 null（吸引器是第一遍之后才协商出来的）；显式赋值管
            // 「这一次请求自己带过去的快照」，withScoped 管「这次规划里所有读 ThreadLocal
            // 的代码」，两条都给上，缺一条都会静默丢吸引。
            final PathfindingCostConfig routeConfig = request.pathfinding().snapshot();
            routeConfig.setCorridorAttract(routeAttract);
            futures.add(CoarsePathTasks.DEFAULT.submit(() -> {
                List<BlockPos> path = CorridorAttract.withScoped(routeAttract, () -> RawPathSearch.find(
                        request.level(), routeConnection, routeConfig, cache, terrain));
                if (path != null && !path.isEmpty()) {
                    replanned.put(routeConnection, List.copyOf(path));
                }
                return path;
            }));
        }
        if (futures.isEmpty()) return;
        try {
            if (parallelWaitEnabled()) {
                awaitAllPathsParallel(futures);
            } else {
                for (java.util.concurrent.Future<List<BlockPos>> future : futures) {
                    awaitOnePath(future, futures);
                }
            }
        } catch (IllegalStateException failure) {
            // 第二遍只是「优化尝试」：它失败不该把一整批已经能建的路拖垮。
            // CancellationException 不走这里，中断仍然向上传播。
            cancelAll(futures);
            LOGGER.warn("[PATH-REUSE] second pass aborted, keeping first-pass paths", failure);
            return;
        }
        for (StructureConnection connection : request.connections()) {
            List<BlockPos> path = replanned.get(connection);
            if (path != null && !path.isEmpty()) {
                // 0.9.058（缺陷 B）：第二遍产出按所属簇的 sharedDeckY 压一次纵断面。
                // 只统一 XZ 不统一甲板，两条并行桥还是会各架各的（真机 48 vs 65）。
                paths.put(connection, CrossingNegotiation.pressToSharedDeck(
                        path, agreement.clusterFor(connection), terrain.step()));
            }
        }
    }

    /**
     * {@code [PATH-REUSE]} 诊断行（0.9.056 起）。
     *
     * <p>0.9.057 起扩展成三段式的全套指标：{@code clusters}（协商出几簇）、
     * {@code sharedPoints}（被并进共享点的过河点总数）、{@code roads}（最终产出几条路）、
     * {@code attractedStations / totalStations / ratio}（有多少站真的落在优惠带里）。</p>
     *
     * <p>0.9.058 再加 {@code sharedDeckY(min..max)}：协商出来的共享甲板高度区间。
     * 全部成簇但甲板未知时打 {@code n/a}。这一格是缺陷 B 的验收位 ——
     * 两个不同的数字说明两条路<b>不会</b>再各架各的桥。</p>
     */
    static void logPathReuse(CrossingNegotiation.Stats stats) {
        LOGGER.info("[PATH-REUSE] clusters={} sharedPoints={} sharedDeckY(min..max)={} roads={} "
                        + "attractedStations={} totalStations={} ratio={}",
                stats.clusters(), stats.sharedPoints(), stats.sharedDeckRange(),
                stats.roads(), stats.attractedStations(), stats.totalStations(),
                String.format("%.3f", stats.ratio()));
    }

    /**
     * 0.8.340：是否用「一起等」代替「逐个等」。配置 {@code planning.pathfindingParallelWait}，
     * 默认 <b>false</b>（保留旧行为），出问题改配置即可回退，无需换 jar。
     */
    private static boolean parallelWaitEnabled() {
        try {
            return net.shiroha233.roadweaver.config.ConfigService.get()
                    .planning().pathfindingParallelWait();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 0.8.340：并发等待全部路径任务。语义与逐个 {@code get()} 严格等价：
     * <ul>
     *   <li>任一任务抛出 → 立即取消其余任务并抛 {@link IllegalStateException}（与旧实现同为
     *       "coarse path planning failed"）；</li>
     *   <li>线程被中断 → 取消其余任务、恢复中断位并抛 {@link java.util.concurrent.CancellationException}；</li>
     *   <li>全部成功 → 正常返回，结果由各任务自行写入 {@code found}（{@code ConcurrentHashMap}）。</li>
     * </ul>
     * 不改变任务数量、提交顺序、输入与产出，只缩短主线程的空等时间。
     */
    private static void awaitAllPathsParallel(
            List<java.util.concurrent.Future<List<BlockPos>>> futures) {
        boolean interrupted = false;
        for (java.util.concurrent.Future<List<BlockPos>> future : futures) {
            try {
                getBounded(future);
            } catch (java.util.concurrent.ExecutionException execution) {
                cancelAll(futures);
                throw new IllegalStateException("coarse path planning failed", execution.getCause());
            } catch (java.util.concurrent.TimeoutException timedOut) {
                // ★ 审计 P1-1：超时同样取消其余任务。
                // 不能只放过这一条 —— 同一个 region 的其它路径任务多半也在等同一批队列，
                // 留着它们只会让后续 get() 继续超时，白耗时间。
                cancelAll(futures);
                throw new java.util.concurrent.CancellationException(
                        "coarse path planning timed out after " + coarsePathWaitTimeoutMs()
                                + "ms (waiting for " + futures.size() + " path task(s))");
            } catch (InterruptedException e) {
                interrupted = true;
                cancelAll(futures);
                break;
            } catch (java.util.concurrent.CancellationException cancelled) {
                // A cancelled sibling only happens on shutdown/cancel paths; treat
                // it exactly like an interruption so callers keep their contract.
                cancelAll(futures);
                interrupted = true;
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("coarse planning interrupted");
        }
    }

    /**
     * 逐个等待路径任务（{@code pathfindingParallelWait=false} 时的旧路径）。
     *
     * <p>审计 P1-1：原实现直接 {@code future.get()} 无超时。真机转储显示
     * {@code RW-Worker-*} 曾在同一个 {@code FutureTask} 上等 ≥14 分钟，
     * 而 INITIAL 池大部分线程空闲 —— 子任务排在队列后轮不到（护航效应），
     * 观感等同死锁。这里改为有界等待，超时就走该 region 的 attempt=2/3 重试。</p>
     */
    private static void awaitOnePath(java.util.concurrent.Future<List<BlockPos>> future,
                                     List<java.util.concurrent.Future<List<BlockPos>>> all) {
        try {
            getBounded(future);
        } catch (java.util.concurrent.ExecutionException execution) {
            throw new IllegalStateException("coarse path planning failed", execution.getCause());
        } catch (java.util.concurrent.TimeoutException timedOut) {
            cancelAll(all);
            throw new java.util.concurrent.CancellationException(
                    "coarse path planning timed out after " + coarsePathWaitTimeoutMs() + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("coarse planning interrupted");
        }
    }

    /**
     * 有界等待单个 future。
     *
     * <p>超时阈值来自 {@code planning.coarsePathWaitTimeoutMs}，
     * {@code 0} 表示**不限时**（完全恢复旧行为，供出问题时回退）。
     * 语义与 {@code RoadPathCalculator#awaitPlanningSnapshot} 对齐：
     * 超时 → 抛 {@code TimeoutException}，由调用方 {@code cancel(true)} 并转成
     * {@code CancellationException}。</p>
     */
    private static void getBounded(java.util.concurrent.Future<List<BlockPos>> future)
            throws java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException,
            InterruptedException {
        long timeoutMs = coarsePathWaitTimeoutMs();
        if (timeoutMs <= 0L) {
            future.get();
        } else {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 粗规划路径等待超时（毫秒）。读配置失败时给一个保守默认值
     * （3 分钟 ≈ 正常耗时 2 倍余量），绝不因为读配置异常而退化成"无限等"。
     */
    private static long coarsePathWaitTimeoutMs() {
        try {
            return net.shiroha233.roadweaver.config.ConfigService.get()
                    .planning().coarsePathWaitTimeoutMs();
        } catch (Throwable ignored) {
            return 180_000L;
        }
    }

    private static void cancelAll(List<java.util.concurrent.Future<List<BlockPos>>> futures) {
        for (java.util.concurrent.Future<List<BlockPos>> future : futures) {
            if (future != null) future.cancel(true);
        }
    }
}
