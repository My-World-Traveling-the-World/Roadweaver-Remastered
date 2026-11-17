/* 文件职责：在玩家空闲预算内调度并追踪延迟道路生成任务。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 闲时道路生成服务：
 * 1) 围绕玩家持续扩张规划半径；
 * 2) 将该半径内的 PLANNED 任务交给闲时线程专属生成；
 * 3) 玩家离开该半径后，任务自动释放给常规生成线程。
 */
public final class IdleRoadGenerationService {
    private IdleRoadGenerationService() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MAX_IDLE_RADIUS_CHUNKS = 16_384;

    private static final ConcurrentHashMap<UUID, IdleWindow> WINDOWS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlannedPathKey, Boolean> IDLE_OWNED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlannedPathKey, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static final Set<Future<?>> ALL_RUNNING = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<PlannedPathKey, RunningTask> RUNNING_TASKS = new ConcurrentHashMap<>();
    private static volatile boolean runtimePausedForDimension;

    private static final class IdleWindow {
        volatile int centerChunkX;
        volatile int centerChunkZ;
        volatile int radiusChunks;
        final ArrayDeque<RadialRegionOrder.Region> planningRegions = new ArrayDeque<>();
        RadialRegionOrder.Region activePlanningRegion;
        /**
         * 0.8.361：上一次执行「覆盖推进」（{@link #advancePlanningCoverage}）的时间。
         *
         * <p>该推进会查 region 状态、必要时向 {@code RoadPlanningReadinessIndex} 请求
         * 规划，是 tickPlayer 里真正有成本的部分。游玩期实测 RW-NativeStructure
         * 线程族合计约 1.07 核（与 Render thread 的 1.08 核相当），即「跑起来就掉帧」
         * 的主因。</p>
         *
         * <p>用户口径：动态生成以玩家为中心、<b>按时间</b>检查，期望 1 分钟一次。
         * 因此这里对覆盖推进做时间节流（间隔见
         * {@link #COVERAGE_ADVANCE_INTERVAL_NANOS}，取 50 秒以避开与外层 60 秒
         * 调用周期的双重节流）。</p>
         */
        volatile long lastCoverageAdvanceNanos;
        /** 最近一次重定中心/扩张后，是否还需要在下个周期立刻推进一次覆盖。 */
        volatile boolean coverageDirty = true;

        IdleWindow(int centerChunkX, int centerChunkZ, int radiusChunks) {
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.radiusChunks = radiusChunks;
        }
    }

    /**
     * 0.8.361：动态规划「覆盖推进」的最小间隔 —— 50 秒。
     *
     * <p><b>刻意比外层的调用周期（{@code ServerPlanningHooks} 的 1200 tick =
     * 60 秒）短 10 秒</b>，否则会形成「双重节流」：外层按 tick 计数计时、内层按
     * {@code System.nanoTime()} 计时，两者都取 60 秒时，内层只要差几毫秒未到阈值
     * 就会把本次调用判为「本分钟已推进过」而跳过，实际周期被拉长到 120 秒 ——
     * 相当于把用户要求的「1 分钟一次」变成「2 分钟一次」。</p>
     *
     * <p>取 50 秒可保证外层每次调用都必然放行（50 &lt; 60），同时内层仍作为护栏
     * 存在：将来若把外层周期调回更短的值（例如 1 秒），内层会自动把实际执行
     * 频率压到 50 秒一次，不会重新出现每秒推进。</p>
     */
    private static final long COVERAGE_ADVANCE_INTERVAL_NANOS = 50_000_000_000L;

    public static void onServerStarted() {
        WINDOWS.clear();
        IDLE_OWNED.clear();
        IN_FLIGHT.clear();
        RUNNING.set(0);
        ALL_RUNNING.clear();
        RUNNING_TASKS.clear();
        runtimePausedForDimension = false;
    }

    public static void onServerStopping() {
        ALL_RUNNING.forEach(f -> {
            try {
                if (f != null) f.cancel(true);
            } catch (Throwable ignored) {}
        });
        ALL_RUNNING.clear();
        RUNNING_TASKS.clear();
        WINDOWS.clear();
        IDLE_OWNED.clear();
        IN_FLIGHT.clear();
        RUNNING.set(0);
    }

    /** 玩家不在主世界时暂停道路后台施工，不暂停其他模组。 */
    public static void setRuntimePausedForDimension(boolean paused) {
        if (runtimePausedForDimension == paused) return;
        runtimePausedForDimension = paused;
        if (!paused) {
            LOGGER.info("【恢复主世界空闲道路任务】 " + "RoadWeaver idle Overworld road work resumed.");
            return;
        }
        ALL_RUNNING.forEach(f -> { try { if (f != null) f.cancel(true); } catch (Throwable ignored) {} });
        ALL_RUNNING.clear();
        RUNNING_TASKS.values().forEach(task -> { try { if (task.future != null) task.future.cancel(true); } catch (Throwable ignored) {} });
        RUNNING_TASKS.clear();
        WINDOWS.clear();
        IDLE_OWNED.clear();
        IN_FLIGHT.clear();
        RUNNING.set(0);
        LOGGER.info("【离开主世界，暂停空闲道路任务】 " + "RoadWeaver idle roads paused outside minecraft:overworld.");
    }

    public static void tickPlayer(ServerPlayer player) {
        if (player == null) return;
        if (runtimePausedForDimension) return;
        if (RuntimeLoadPolicy.pauseScheduling()) return;

        ServerLevel level = player.serverLevel();
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        ModConfig cfg = ConfigService.get();
        if (!cfg.roadAppearance().roadsEnabled()) return;

        if (!cfg.performance().idleGenerationEnabled() && !cfg.planning().dynamicPlanEnabled()) return;

        // 0.8.372：前台压力期不推进「空闲铺路窗口」。
        //
        // 真机取证（20260919 08:38~08:53）：玩家跑图时 RoadWeaver 仍在推进
        // radial 规划窗口（dynamicPlanRadiusChunks=256），日志显示
        //   RoadWeaver radial runtime planning queued nearest region [...]; remaining=287
        // 并伴随 lanes=12 / streams=12 的线程爆发，把 c2me 生成 worker 抢空
        // （14 个 worker 全部 park，游戏总 CPU 仅 27%）。
        //
        // 本服务的方法名就叫 **Idle**RoadGenerationService —— 它的语义本就是
        // 「玩家空闲时铺路」，因此前台压力期（跑图/飞行/交互）直接返回是正确的，
        // 与设计意图一致，且不丢路：
        //   · 玩家停下后 tickPlayer 会照常推进，窗口继续跟随；
        //   · RoadPlanningService 的 backfill 机制会在检测到「该区域有路但未规划」时补回；
        //   · dynamicPlanEnabled / dynamicPlanRadiusChunks 均保持不变。
        if (RuntimeLoadPolicy.isForegroundPressureActive()) return;

        int startingRadius = resolveStartingRadiusChunks(cfg);
        int expandStep = resolveExpandStepChunks(cfg);
        int followStep = resolveFollowStepChunks(cfg);
        int pcx = player.chunkPosition().x;
        int pcz = player.chunkPosition().z;

        ConcurrentHashMap<UUID, IdleWindow> perLevel = WINDOWS;
        UUID playerId = player.getUUID();
        IdleWindow win = perLevel.get(playerId);
        if (win == null) {
            IdleWindow created = new IdleWindow(pcx, pcz, startingRadius);
            IdleWindow prev = perLevel.putIfAbsent(playerId, created);
            win = prev != null ? prev : created;
            if (prev == null) {
                resetPlanningRegions(win, -1, startingRadius);
                win.coverageDirty = true;
                advancePlanningCoverageThrottled(level, win);
                return;
            }
        }

        int dist = chebyshev(pcx, pcz, win.centerChunkX, win.centerChunkZ);
        if (dist > win.radiusChunks || dist >= followStep) {
            int oldCenterX = win.centerChunkX;
            int oldCenterZ = win.centerChunkZ;
            int oldRadius = win.radiusChunks;
            int targetRadius = dist > oldRadius ? startingRadius : oldRadius;

            win.centerChunkX = pcx;
            win.centerChunkZ = pcz;
            win.radiusChunks = targetRadius;
            resetPlanningRegions(win, -1, targetRadius);
            // 重定中心属于「玩家跑远了」的响应性动作，不节流：立刻排一次覆盖推进。
            win.coverageDirty = true;
            LOGGER.info("【游玩期径向规划中心已迁移】 " + "RoadWeaver radial runtime planning recentered: oldCenter=({},{}), center=({},{}), radius={} chunks.",
                    oldCenterX, oldCenterZ, pcx, pcz, targetRadius);
            advancePlanningCoverageThrottled(level, win);
            return;
        }

        // 0.8.361：站在原地的「覆盖推进」按 60 秒节流（见 COVERAGE_ADVANCE_INTERVAL_NANOS）。
        // tickPlayer 每秒被调一次，而这一步要查 region 状态并可能请求规划，是真正的开销来源。
        if (!advancePlanningCoverageThrottled(level, win)) return;
        if (!isWindowFinished(level, win)) {
            return;
        }

        int oldRadius = win.radiusChunks;
        int nextRadius = Math.min(MAX_IDLE_RADIUS_CHUNKS, oldRadius + expandStep);
        if (nextRadius <= oldRadius) return;
        win.radiusChunks = nextRadius;
        appendPlanningRegions(win, oldRadius, nextRadius);
        win.coverageDirty = true;
        LOGGER.info("【游玩期径向规划半径已扩大】 " + "RoadWeaver radial runtime planning expanded: center=({},{}), radius={} -> {} chunks, regions={}",
                win.centerChunkX, win.centerChunkZ, oldRadius, nextRadius, win.planningRegions.size());
        advancePlanningCoverageThrottled(level, win);
    }

    /**
     * 0.8.361：带时间节流的覆盖推进。
     *
     * <p>首次（或刚重定中心/刚扩张，{@code coverageDirty}）立即执行；其余情况每
     * {@link #COVERAGE_ADVANCE_INTERVAL_NANOS} 最多执行一次。语义与直接调用
     * {@link #advancePlanningCoverage} 一致 —— 只是把高频调用降为约每分钟一次。</p>
     */
    private static boolean advancePlanningCoverageThrottled(ServerLevel level, IdleWindow window) {
        if (level == null || window == null) return false;
        long now = System.nanoTime();
        if (!window.coverageDirty
                && now - window.lastCoverageAdvanceNanos < COVERAGE_ADVANCE_INTERVAL_NANOS) {
            // 本周期已经推进过：报告「未完成」，让调用方保持待命而不做任何工作。
            return false;
        }
        window.lastCoverageAdvanceNanos = now;
        window.coverageDirty = false;
        return advancePlanningCoverage(level, window);
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        if (runtimePausedForDimension) return;

        ModConfig cfg = ConfigService.get();
        cleanupWindows(level);
        ALL_RUNNING.removeIf(f -> f == null || f.isDone() || f.isCancelled());
        RoadPlanningReadinessIndex.tick(level);

        AtomicInteger running = RUNNING;
        int duty = cfg.performance().idleThreadDutyCycle();
        if (running.get() < 1 && scheduleCoordinatedRegion(level, duty)) {
            return;
        }

        if (!cfg.performance().idleGenerationEnabled()) {
            IDLE_OWNED.clear();
            return;
        }

        reservePlannedEdges(level);

        List<ServerPlayer> players = collectPlayers(level);

        int limit = Math.max(1, Math.min(128, cfg.performance().idleConcurrentGenerations()));

        while (running.get() < limit) {
            if (!RoadGenerationService.tryAcquireRuntimeSlot()) break;
            StructureConnection conn = pollNearestOwnedPlanned(level, players);
            if (conn == null) {
                RoadGenerationService.releaseRuntimeSlot();
                break;
            }

            PlannedPathKey key = PlannedPathKey.of(conn);
            ConcurrentHashMap<PlannedPathKey, Boolean> inFlight = IN_FLIGHT;
            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
                RoadGenerationService.releaseRuntimeSlot();
                continue;
            }
            if (!updateStatus(level, conn, ConnectionStatus.GENERATING, ConnectionStatus.PLANNED)) {
                inFlight.remove(key);
                RoadGenerationService.releaseRuntimeSlot();
                continue;
            }

            running.incrementAndGet();
            long epoch = ThreadPoolManager.currentEpoch();
            final StructureConnection task = conn;
            final RunningTask runningTask = new RunningTask();
            try {
                Future<?> future = ThreadPoolManager.submit(
                        ThreadPoolManager.TaskRole.IDLE,
                        () -> runIdleTask(level, task, key, duty, epoch, runningTask));
                runningTask.future = future;
                RUNNING_TASKS.put(key, runningTask);
                if (runningTask.released.get()) RUNNING_TASKS.remove(key, runningTask);
                if (ConnectionDeletionService.shouldAbort(level, task)) {
                    future.cancel(true);
                    finishRunningTask(key, runningTask);
                }
                ALL_RUNNING.add(future);
            } catch (Throwable submitFailure) {
                running.decrementAndGet();
                inFlight.remove(key);
                updateStatus(level, conn, ConnectionStatus.PLANNED, ConnectionStatus.GENERATING);
                RoadGenerationService.releaseRuntimeSlot();
                throw submitFailure;
            }
        }
    }

    private static boolean scheduleCoordinatedRegion(ServerLevel level, int duty) {
        StructureConnection conn = RoadPlanningReadinessIndex.nextPlannedConnection(level);
        if (conn == null) return false;
        if (!RoadGenerationService.tryAcquireRuntimeSlot()) return true;
        PlannedPathKey key = PlannedPathKey.of(conn);
        if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) != null) {
            RoadGenerationService.releaseRuntimeSlot();
            return true;
        }
        if (!updateStatus(level, conn, ConnectionStatus.GENERATING, ConnectionStatus.PLANNED)) {
            IN_FLIGHT.remove(key);
            RoadGenerationService.releaseRuntimeSlot();
            return false;
        }

        RUNNING.incrementAndGet();
        long epoch = ThreadPoolManager.currentEpoch();
        RunningTask runningTask = new RunningTask();
        try {
            Future<?> future = ThreadPoolManager.submit(
                    ThreadPoolManager.TaskRole.IDLE,
                    () -> runIdleTask(level, conn, key, duty, epoch, runningTask));
            runningTask.future = future;
            RUNNING_TASKS.put(key, runningTask);
            if (runningTask.released.get()) RUNNING_TASKS.remove(key, runningTask);
            if (ConnectionDeletionService.shouldAbort(level, conn)) {
                future.cancel(true);
                finishRunningTask(key, runningTask);
            }
            ALL_RUNNING.add(future);
        } catch (Throwable submitFailure) {
            RUNNING.decrementAndGet();
            IN_FLIGHT.remove(key);
            updateStatus(level, conn, ConnectionStatus.PLANNED, ConnectionStatus.GENERATING);
            RoadGenerationService.releaseRuntimeSlot();
            throw submitFailure;
        }
        return true;
    }

    public static boolean shouldReserveForIdle(ServerLevel level, StructureConnection conn) {
        if (level == null || conn == null || !Level.OVERWORLD.equals(level.dimension())) return false;
        if (!ConfigService.get().performance().idleGenerationEnabled()) return false;

        ConcurrentHashMap<PlannedPathKey, Boolean> owned = IDLE_OWNED;
        if (owned.isEmpty()) return false;

        PlannedPathKey key = PlannedPathKey.of(conn);
        if (!owned.containsKey(key)) return false;
        if (isInsideAnyWindow(level, conn)) return true;

        owned.remove(key);
        return false;
    }

    public static boolean isManagedByIdle(ServerLevel level, StructureConnection conn) {
        if (level == null || conn == null || !Level.OVERWORLD.equals(level.dimension())) return false;
        if (!ConfigService.get().performance().idleGenerationEnabled()) return false;
        PlannedPathKey key = PlannedPathKey.of(conn);
        if (IN_FLIGHT.containsKey(key)) return true;
        return IDLE_OWNED.containsKey(key);
    }

    public static void cancelConnection(StructureConnection connection) {
        if (connection == null) return;
        PlannedPathKey key = PlannedPathKey.of(connection);
        IDLE_OWNED.remove(key);
        IN_FLIGHT.remove(key);
        RunningTask task = RUNNING_TASKS.remove(key);
        if (task != null) {
            Future<?> future = task.future;
            if (future != null) future.cancel(true);
            finishRunningTask(key, task);
        }
    }

    private static void runIdleTask(ServerLevel level,
                                    StructureConnection task,
                                    PlannedPathKey key,
                                    int duty,
                                    long epoch,
                                    RunningTask runningTask) {
        ThreadPoolManager.resetThrottle();
        try {
            if (runtimePausedForDimension) return;
            if (Thread.currentThread().isInterrupted()) return;
            if (!ThreadPoolManager.isEpoch(epoch)) return;

            RoadGenerationService.GenerationResult result = RoadGenerationService.generateTaskResult(level, task);
            StructureConnection effective = result.effectiveConnection() == null ? task : result.effectiveConnection();
            ConnectionStatus st = result.finalStatus();
            if (ConnectionDeletionService.shouldAbort(level, task)) {
                executeOnMain(level, epoch, () -> removeOwnership(level, key));
                return;
            }
            executeOnMain(level, epoch, () -> {
                if (ConnectionDeletionService.shouldAbort(level, task)) {
                    removeOwnership(level, key);
                    return;
                }
                updateGenerationResult(level, task, effective, st,
                        ConnectionStatus.GENERATING, ConnectionStatus.PLANNED);
                removeOwnership(level, key);
            });
        } catch (Throwable t) {
            if (ConnectionDeletionService.shouldAbort(level, task)) {
                executeOnMain(level, epoch, () -> removeOwnership(level, key));
                return;
            }
            LOGGER.warn("【空闲道路生成失败】 " + "IdleRoad: generate failed {} -> {}", task.from(), task.to(), t);
            executeOnMain(level, epoch, () -> {
                if (ConnectionDeletionService.shouldAbort(level, task)) {
                    removeOwnership(level, key);
                    return;
                }
                updateStatus(level, task, ConnectionStatus.FAILED, ConnectionStatus.GENERATING, ConnectionStatus.PLANNED);
                removeOwnership(level, key);
            });
        } finally {
            try {
                ThreadPoolManager.throttle(duty);
            } catch (Throwable ignored) {}
            ThreadPoolManager.clearThrottle();
            finishRunningTask(key, runningTask);
        }
    }

    private static void finishRunningTask(PlannedPathKey key, RunningTask task) {
        if (task == null || !task.released.compareAndSet(false, true)) return;
        RUNNING_TASKS.remove(key, task);
        RUNNING.updateAndGet(value -> Math.max(0, value - 1));
        RoadGenerationService.releaseRuntimeSlot();
    }

    private static void removeOwnership(ServerLevel level, PlannedPathKey key) {
        IDLE_OWNED.remove(key);
        IN_FLIGHT.remove(key);
    }

    private static void reservePlannedEdges(ServerLevel level) {
        List<StructureConnection> conns = WorldDataProvider.getInstance().getStructureConnections(level);
        if (conns == null || conns.isEmpty()) return;
        if (!hasWindows(level)) return;

        ConcurrentHashMap<PlannedPathKey, Boolean> owned = IDLE_OWNED;
        for (StructureConnection c : conns) {
            if (c == null || c.status() != ConnectionStatus.PLANNED) continue;
            if (RoadPlanningReadinessIndex.shouldReserveForCoordinator(level, c)) continue;
            if (!isInsideAnyWindow(level, c)) continue;
            owned.putIfAbsent(PlannedPathKey.of(c), Boolean.TRUE);
        }
    }

    private static StructureConnection pollNearestOwnedPlanned(ServerLevel level, List<ServerPlayer> players) {
        List<StructureConnection> conns = WorldDataProvider.getInstance().getStructureConnections(level);
        if (conns == null || conns.isEmpty()) return null;

        ConcurrentHashMap<PlannedPathKey, Boolean> owned = IDLE_OWNED;
        if (owned.isEmpty()) return null;
        ConcurrentHashMap<PlannedPathKey, Boolean> inFlight = IN_FLIGHT;

        ConcurrentHashMap<UUID, IdleWindow> perLevel = WINDOWS;
        if (perLevel.isEmpty()) return null;

        StructureConnection best = null;
        long bestDist = Long.MAX_VALUE;

        for (Map.Entry<UUID, IdleWindow> windowEntry : perLevel.entrySet()) {
            IdleWindow w = windowEntry.getValue();
            if (w == null) continue;
            int wcCx = w.centerChunkX;
            int wcCz = w.centerChunkZ;
            int wRadius = w.radiusChunks;

            for (StructureConnection c : conns) {
                if (c == null || c.status() != ConnectionStatus.PLANNED) continue;
                if (RoadPlanningReadinessIndex.shouldReserveForCoordinator(level, c)) {
                    owned.remove(PlannedPathKey.of(c));
                    continue;
                }
                PlannedPathKey key = PlannedPathKey.of(c);
                if (!owned.containsKey(key)) continue;
                int mx = (c.from().getX() + c.to().getX()) >> 1;
                int mz = (c.from().getZ() + c.to().getZ()) >> 1;
                int mcx = mx >> 4;
                int mcz = mz >> 4;
                if (Math.abs(mcx - wcCx) > wRadius || Math.abs(mcz - wcCz) > wRadius) {
                    owned.remove(key);
                    continue;
                }
                if (inFlight.containsKey(key)) continue;
                long d = playerDistance2(c, players);
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    private static boolean updateStatus(ServerLevel level, StructureConnection conn, ConnectionStatus to, ConnectionStatus... allowedFrom) {
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> origin = provider.getStructureConnections(level);
        if (origin == null || origin.isEmpty()) return false;

        Set<ConnectionStatus> allowed = new HashSet<>();
        if (allowedFrom != null) {
            for (ConnectionStatus st : allowedFrom) {
                if (st != null) allowed.add(st);
            }
        }

        List<StructureConnection> all = new ArrayList<>(origin);
        for (int i = 0; i < all.size(); i++) {
            StructureConnection c = all.get(i);
            if (!PlanningUtils.sameEdge(c, conn)) continue;
            if (!allowed.isEmpty() && !allowed.contains(c.status())) return false;
            StructureConnection updated = new StructureConnection(c.from(), c.to(), to);
            all.set(i, updated);
            provider.setStructureConnections(level, all);
            MapPatchService.publishConnection(level, updated);
            if (to == ConnectionStatus.COMPLETED) {
                MapPatchService.publishRoadForConnectionAsync(level, updated);
            }
            return true;
        }
        return false;
    }

    private static boolean updateGenerationResult(ServerLevel level,
                                                  StructureConnection original,
                                                  StructureConnection effective,
                                                  ConnectionStatus status,
                                                  ConnectionStatus... allowedFrom) {
        if (level == null || original == null || effective == null) return false;
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> origin = provider.getStructureConnections(level);
        if (origin == null || origin.isEmpty()) return false;
        Set<ConnectionStatus> allowed = new HashSet<>();
        if (allowedFrom != null) {
            for (ConnectionStatus from : allowedFrom) if (from != null) allowed.add(from);
        }
        List<StructureConnection> updated = new ArrayList<>(origin);
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection current = updated.get(i);
            if (!PlanningUtils.sameEdge(current, original)) continue;
            if (!allowed.isEmpty() && !allowed.contains(current.status())) return false;
            StructureConnection replacement = new StructureConnection(
                    effective.from(), effective.to(), status);
            updated.set(i, replacement);
            provider.setStructureConnections(level, updated);
            if (PlanningUtils.sameEdge(original, replacement)) {
                MapPatchService.publishConnection(level, replacement);
            } else {
                MapPatchService.publishConnectionReplacement(level, original, replacement);
            }
            if (status == ConnectionStatus.COMPLETED || status == ConnectionStatus.PROTECTED_GAP) {
                MapPatchService.publishRoadForConnectionAsync(level, replacement);
            }
            return true;
        }
        return false;
    }

    private static void cleanupWindows(ServerLevel level) {
        ConcurrentHashMap<UUID, IdleWindow> perLevel = WINDOWS;
        if (perLevel.isEmpty()) return;

        Set<UUID> alive = new HashSet<>();
        for (ServerPlayer p : collectPlayers(level)) {
            alive.add(p.getUUID());
        }
        perLevel.keySet().removeIf(id -> !alive.contains(id));

        if (perLevel.isEmpty()) {
            IDLE_OWNED.clear();
        }
    }

    private static boolean hasWindows(ServerLevel level) {
        return !WINDOWS.isEmpty();
    }

    private static boolean isInsideAnyWindow(ServerLevel level, StructureConnection conn) {
        ConcurrentHashMap<UUID, IdleWindow> perLevel = WINDOWS;
        if (perLevel.isEmpty() || conn == null) return false;

        int mx = (conn.from().getX() + conn.to().getX()) >> 1;
        int mz = (conn.from().getZ() + conn.to().getZ()) >> 1;
        int mcx = mx >> 4;
        int mcz = mz >> 4;

        for (IdleWindow w : perLevel.values()) {
            if (w == null) continue;
            if (Math.abs(mcx - w.centerChunkX) <= w.radiusChunks
                    && Math.abs(mcz - w.centerChunkZ) <= w.radiusChunks) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideWindow(IdleWindow window, StructureConnection conn) {
        if (window == null || conn == null) return false;
        int mx = (conn.from().getX() + conn.to().getX()) >> 1;
        int mz = (conn.from().getZ() + conn.to().getZ()) >> 1;
        int mcx = mx >> 4;
        int mcz = mz >> 4;
        return Math.abs(mcx - window.centerChunkX) <= window.radiusChunks
                && Math.abs(mcz - window.centerChunkZ) <= window.radiusChunks;
    }

    private static boolean isWindowFinished(ServerLevel level, IdleWindow window) {
        if (level == null || window == null) return false;

        List<StructureConnection> conns = WorldDataProvider.getInstance().getStructureConnections(level);
        if (conns == null || conns.isEmpty()) return true;

        for (StructureConnection c : conns) {
            if (c == null) continue;
            if (!isInsideWindow(window, c)) continue;
            if (c.status() == ConnectionStatus.PLANNED || c.status() == ConnectionStatus.GENERATING) {
                return false;
            }
        }
        return true;
    }

    private static boolean advancePlanningCoverage(ServerLevel level, IdleWindow window) {
        if (level == null || window == null) return false;

        RadialRegionOrder.Region active = window.activePlanningRegion;
        if (active != null) {
            String state = RoadPlanningReadinessIndex.regionState(level, active.x(), active.z());
            if (!"READY".equals(state)) {
                if ("FAILED".equals(state)) {
                    RoadPlanningReadinessIndex.requestRegion(level, active.x(), active.z());
                }
                return false;
            }
            window.activePlanningRegion = null;
        }

        while (!window.planningRegions.isEmpty()) {
            RadialRegionOrder.Region region = window.planningRegions.removeFirst();
            String state = RoadPlanningReadinessIndex.regionState(level, region.x(), region.z());
            if ("READY".equals(state)) continue;

            // 四态 · GAMEPLAY_IDLE 节流:一条道路完成后,下一次低优先级区域
            // 规划至少间隔 runtimeRoadCompletionMinIntervalMs。闸关闭时把
            // region 放回队首并保持待命(不丢弃,region 状态机幂等)。
            if (!RuntimeLoadPolicy.idlePlanningGateOpen()) {
                window.planningRegions.addFirst(region);
                return false;
            }

            String requested = RoadPlanningReadinessIndex.requestRegion(level, region.x(), region.z());
            if ("READY".equals(requested)) continue;
            window.activePlanningRegion = region;
            LOGGER.info("【最近规划区域已入队】 " + "RoadWeaver radial runtime planning queued nearest region [{},{}]; center=({},{}), radius={} chunks, remaining={}",
                    region.x(), region.z(), window.centerChunkX, window.centerChunkZ,
                    window.radiusChunks, window.planningRegions.size());
            return false;
        }
        return true;
    }

    private static void resetPlanningRegions(IdleWindow window, int previousRadiusChunks, int radiusChunks) {
        window.planningRegions.clear();
        window.activePlanningRegion = null;
        appendPlanningRegions(window, previousRadiusChunks, radiusChunks);
    }

    private static void appendPlanningRegions(IdleWindow window, int previousRadiusChunks, int radiusChunks) {
        window.planningRegions.addAll(RadialRegionOrder.betweenRadii(
                window.centerChunkX,
                window.centerChunkZ,
                previousRadiusChunks,
                radiusChunks));
    }

    private static int resolveStartingRadiusChunks(ModConfig cfg) {
        if (cfg == null) return 1;
        int initial = Math.max(1, cfg.planning().initialPlanRadiusChunks());
        if (!cfg.planning().dynamicPlanEnabled()) return initial;
        // The runtime window is independently configurable from the startup
        // window. Clamping it to the startup radius silently ignored a larger
        // dynamic radius such as 256 chunks.
        return Math.min(MAX_IDLE_RADIUS_CHUNKS,
                Math.max(1, cfg.planning().dynamicPlanRadiusChunks()));
    }

    private static int resolveExpandStepChunks(ModConfig cfg) {
        if (cfg == null) return 1;
        return Math.min(MAX_IDLE_RADIUS_CHUNKS,
                Math.max(1, cfg.planning().dynamicPlanStrideChunks()));
    }

    /**
     * 0.8.361：移动触发的距离阈值（单位：区块）。
     *
     * <p>原来直接复用 {@code dynamicPlanStrideChunks}（默认 32）—— 那个键的本意是
     * 「窗口扩张的步长」，被同时当成「跟随步长」后导致玩家每移动 32 区块（512 方块）
     * 就重定中心并重建 planningRegions。游玩中这个距离太短，几乎一直在重算。</p>
     *
     * <p>现在改为取「扩张步长的 4 倍」，并保证不小于 128 区块（2048 方块）：</p>
     * <ul>
     *   <li>stride=32（默认）→ followStep=128 区块，比原来远 4 倍；</li>
     *   <li>配合 60 秒的检查周期，跑动时不会频繁触发重定中心；</li>
     *   <li>窗口半径仍是 dynamicPlanRadiusChunks（默认 256），跟随变远也不会
     *       让玩家跑出已规划范围 —— 256 半径远大于 128 的步长。</li>
     * </ul>
     */
    private static int resolveFollowStepChunks(ModConfig cfg) {
        if (cfg == null) return 1;
        int stride = Math.max(1, cfg.planning().dynamicPlanStrideChunks());
        long enlarged = (long) stride * 4L;
        int follow = (int) Math.min(MAX_IDLE_RADIUS_CHUNKS, Math.max(128L, enlarged));
        return Math.min(follow, resolveStartingRadiusChunks(cfg));
    }

    private static int chebyshev(int x0, int z0, int x1, int z1) {
        return Math.max(Math.abs(x0 - x1), Math.abs(z0 - z1));
    }

    private static List<ServerPlayer> collectPlayers(ServerLevel level) {
        List<ServerPlayer> out = new ArrayList<>();
        var server = level.getServer();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != null && p.serverLevel() == level) out.add(p);
        }
        return out;
    }

    private static long playerDistance2(StructureConnection c, List<ServerPlayer> players) {
        if (players == null || players.isEmpty()) return Long.MAX_VALUE;
        long best = Long.MAX_VALUE;
        int mx = (c.from().getX() + c.to().getX()) >> 1;
        int mz = (c.from().getZ() + c.to().getZ()) >> 1;
        BlockPos mid = new BlockPos(mx, 0, mz);
        for (ServerPlayer p : players) {
            BlockPos pb = p.blockPosition();
            best = Math.min(best, dist2XZ(pb, c.from()));
            best = Math.min(best, dist2XZ(pb, c.to()));
            best = Math.min(best, dist2XZ(pb, mid));
        }
        return best;
    }

    private static final class RunningTask {
        final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile Future<?> future;
    }

    private static long dist2XZ(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static void executeOnMain(ServerLevel level, long epoch, Runnable action) {
        var server = level.getServer();
        if (server == null) {
            if (!ThreadPoolManager.isEpoch(epoch)) return;
            action.run();
            return;
        }
        server.execute(() -> {
            if (!ThreadPoolManager.isEpoch(epoch)) return;
            action.run();
        });
    }
}
