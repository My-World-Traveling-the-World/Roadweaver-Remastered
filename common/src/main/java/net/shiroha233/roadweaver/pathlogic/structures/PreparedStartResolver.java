/* 文件职责：把结构起点解析成「已准备」的方块盒 —— 只解析与缓存，不做避让判定。 */
package net.shiroha233.roadweaver.pathlogic.structures;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import net.shiroha233.roadweaver.search.StructurePredictor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

// 仍留在避让服务里的常量与工具（包内可见），静态导入后方法体不用改 —— 纯搬迁。
import static net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;

/**
 * 结构起点解析器：把「某个区块可能有哪个结构」翻译成「这个结构占哪些方块」。
 *
 * <p>结构解析要读区块、展开模板，单次成本毫秒到秒级，所以这里全是
 * {@code CompletableFuture} —— 规划侧拿不到就先用空快照继续，解析完再补。
 * 「native direct lane」是给少数必须同步拿到的路径留的窄通道。</p>
 *
 * <p>本类是从 3542 行的 {@code StructureAvoidanceService} 里<b>纯搬迁</b>出来的，
 * 逻辑未改。为让两侧调用点零改动，用了双向静态导入（本类导入服务、服务反向导入本类），
 * 代价是相关成员从 private 放宽到包内可见。</p>
 */
public final class PreparedStartResolver {
    public static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level, List<PreparedStartKey> starts, boolean trackInitialProgress) {
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, (PrepareTiming) null);
    }

    /** 0.9.059（①）：{@code timing} 非 null 时记录并发列宽与真实 worker 线程数（纯观测）。 */
    public static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level, List<PreparedStartKey> starts, boolean trackInitialProgress,
            PrepareTiming timing) {
        ThreadPoolManager.TaskRole role = trackInitialProgress && InitialGenManager.isActive()
                ? ThreadPoolManager.TaskRole.INITIAL
                : ThreadPoolManager.TaskRole.STRUCTURE;
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, role, timing);
    }

    public static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole resolutionRole) {
        return resolvePreparedStartsAsync(level, starts, trackInitialProgress, resolutionRole, null);
    }

    public static CompletableFuture<Map<PreparedStartKey, List<OwnedStructureBox>>> resolvePreparedStartsAsync(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            ThreadPoolManager.TaskRole resolutionRole,
            PrepareTiming timing) {
        if (starts == null || starts.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        // E 阶段护栏：第一次解析时验证"预筛前后 exactPieceBoxes 一致"，不一致自动关闭预筛。
        verifyPrefilterExactness(level, starts);
        Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts = new ConcurrentHashMap<>();
        List<PreparedStartKey> unresolved = new ArrayList<>();
        for (PreparedStartKey start : starts) {
            List<OwnedStructureBox> cached = cachedPreparedStart(start);
            if (cached != null) {
                StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.incrementAndGet();
                resolvedStarts.put(start, cached);
                if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningStartDone(cached.isEmpty());
            } else {
                unresolved.add(start);
            }
        }
        int batchWindow = structureStartBatchWindow(resolutionRole);
        if (timing != null) {
            timing.setColumns(STRUCTURE_START_BATCH_SIZE * batchWindow);
            timing.observeWorkerThread();
        }
        LOGGER.info("【精确结构起点解析计划】 " + "RoadWeaver exact structure start plan: dimension={} total={} cached={} unresolved={} batchSize={} windowBatches={}",
                level.dimension().location(), starts.size(), starts.size() - unresolved.size(),
                unresolved.size(), STRUCTURE_START_BATCH_SIZE, batchWindow);
        return resolvePreparedStartWindow(
                        level, unresolved, trackInitialProgress, resolvedStarts, resolutionRole, batchWindow, timing)
                .thenApply(ignored -> Map.copyOf(resolvedStarts))
                .whenComplete((ignored, failure) -> {
                    if (trackInitialProgress) InitialGenerationProgressTracker.setPlanningActiveWorkers(0);
                });
    }

    public static CompletableFuture<Void> resolvePreparedStartWindow(
            ServerLevel level,
            List<PreparedStartKey> starts,
            boolean trackInitialProgress,
            Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts,
            ThreadPoolManager.TaskRole resolutionRole,
            int batchWindow,
            PrepareTiming timing) {
        if (starts.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Object pumpLock = new Object();
        java.util.concurrent.atomic.AtomicInteger nextOffset = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger activeBatches = new java.util.concurrent.atomic.AtomicInteger();
        long windowStartedAt = System.nanoTime();

        Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            List<Integer> offsets = new ArrayList<>(batchWindow);
            synchronized (pumpLock) {
                if (completed.isDone()) return;
                while (activeBatches.get() < batchWindow) {
                    int offset = nextOffset.getAndAdd(STRUCTURE_START_BATCH_SIZE);
                    if (offset >= starts.size()) break;
                    activeBatches.incrementAndGet();
                    offsets.add(offset);
                }
                if (trackInitialProgress) {
                    int estimatedActiveThreads = Math.min(
                            activeBatches.get() * STRUCTURE_START_BATCH_SIZE,
                            resolutionRole == ThreadPoolManager.TaskRole.INITIAL
                                    ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                                    : ThreadPoolManager.maxRuntimeStructureThreads());
                    InitialGenerationProgressTracker.setPlanningActiveWorkers(
                            Math.min(starts.size(), estimatedActiveThreads));
                }
            }
            for (int offset : offsets) {
                resolvePreparedStartBatchOnce(
                                level, starts, offset, trackInitialProgress, 0, resolvedStarts, resolutionRole,
                                timing)
                        .whenComplete((ignored, failure) -> {
                            boolean finished;
                            if (timing != null) timing.observeWorkerThread();
                            synchronized (pumpLock) {
                                int remaining = activeBatches.decrementAndGet();
                                if (trackInitialProgress) {
                                    int estimatedActiveThreads = Math.min(
                                            remaining * STRUCTURE_START_BATCH_SIZE,
                                            resolutionRole == ThreadPoolManager.TaskRole.INITIAL
                                                    ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                                                    : ThreadPoolManager.maxRuntimeStructureThreads());
                                    InitialGenerationProgressTracker.setPlanningActiveWorkers(
                                            Math.min(starts.size(), estimatedActiveThreads));
                                }
                                if (failure != null) {
                                    completed.completeExceptionally(unwrapCompletionFailure(failure));
                                    return;
                                }
                                finished = nextOffset.get() >= starts.size() && remaining == 0;
                                if (finished) {
                                    long elapsedMs = elapsedMillis(windowStartedAt);
                                    double startsPerSecond = elapsedMs <= 0L
                                            ? starts.size()
                                            : starts.size() * 1000.0D / elapsedMs;
                                    LOGGER.info(
                                            "【精确结构起点窗口吞吐与耗时】 " + "RoadWeaver exact structure window timing: dimension={} starts={} windowBatches={} elapsedMs={} startsPerSecond={}",
                                            level.dimension().location(), starts.size(),
                                            batchWindow, elapsedMs,
                                            String.format(java.util.Locale.ROOT, "%.2f", startsPerSecond));
                                    completed.complete(null);
                                }
                            }
                            if (!finished) {
                                CompletableFuture.delayedExecutor(0L, TimeUnit.MILLISECONDS).execute(pump[0]);
                            }
                        });
            }
        };
        pump[0].run();
        return completed;
    }

    public static int structureStartBatchWindow(ThreadPoolManager.TaskRole role) {
        if (role == ThreadPoolManager.TaskRole.INITIAL && InitialGenManager.isActive()) {
            return Math.max(STRUCTURE_START_BATCH_WINDOW,
                    Math.min(16, ThreadPoolManager.startupNativeStructureThreadCapacity()));
        }
        return STRUCTURE_START_BATCH_WINDOW;
    }

    public static CompletableFuture<Void> resolvePreparedStartBatchOnce(
            ServerLevel level, List<PreparedStartKey> starts, int offset,
            boolean trackInitialProgress, int batchAttempt,
            Map<PreparedStartKey, List<OwnedStructureBox>> resolvedStarts,
            ThreadPoolManager.TaskRole resolutionRole,
            PrepareTiming timing) {
        if (offset >= starts.size()) return CompletableFuture.completedFuture(null);
        int end = Math.min(starts.size(), offset + STRUCTURE_START_BATCH_SIZE);
        List<PreparedStartKey> batch = List.copyOf(starts.subList(offset, end));
        long batchStartedAt = System.nanoTime();
        if (timing != null) timing.observeWorkerThread();
        List<CompletableFuture<List<OwnedStructureBox>>> futures = new ArrayList<>(batch.size());
        for (PreparedStartKey start : batch) {
            CompletableFuture<List<OwnedStructureBox>> future = resolvePreparedStartAsync(
                    level, start, resolutionRole);
            future.whenComplete((ignored, failure) -> {
                if (failure != null) StructureAvoidanceCache.IN_FLIGHT_STARTS.remove(start, future);
            });
            futures.add(future);
        }
        CompletableFuture<?>[] pending = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending)
                .handle((ignored, failure) -> {
                    boolean milestone = failure != null
                            || offset == 0
                            || end == starts.size()
                            || offset % (STRUCTURE_START_BATCH_SIZE * 64) == 0;
                    if (milestone) {
                        LOGGER.info("【精确结构起点批次结果与耗时】 " + "RoadWeaver exact structure batch timing: dimension={} offset={} size={} attempt={} elapsedMs={} success={}",
                                level.dimension().location(), offset, batch.size(), batchAttempt + 1,
                                elapsedMillis(batchStartedAt), failure == null);
                    } else {
                        LOGGER.debug("【精确结构起点批次结果与耗时】 " + "RoadWeaver exact structure batch timing: dimension={} offset={} size={} attempt={} elapsedMs={} success={}",
                                level.dimension().location(), offset, batch.size(), batchAttempt + 1,
                                elapsedMillis(batchStartedAt), failure == null);
                    }
                    return failure;
                })
                .thenCompose(failure -> {
                    Throwable cause = unwrapCompletionFailure(failure);
                    if (cause != null) return CompletableFuture.failedFuture(cause);
                    for (int i = 0; i < batch.size(); i++) {
                        PreparedStartKey start = batch.get(i);
                        List<OwnedStructureBox> accepted = storePreparedStart(
                                start, futures.get(i).join(), trackInitialProgress);
                        resolvedStarts.put(start, accepted);
                        probeBiomePrefilter(level, start, accepted);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    public static CompletableFuture<List<OwnedStructureBox>> resolvePreparedStartAsync(
            ServerLevel level,
            PreparedStartKey start,
            ThreadPoolManager.TaskRole resolutionRole) {
        List<OwnedStructureBox> cached = cachedPreparedStart(start);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return StructureAvoidanceCache.IN_FLIGHT_STARTS.computeIfAbsent(
                start, ignored -> createPreparedStartFuture(level, start, resolutionRole));
    }

    public static CompletableFuture<List<OwnedStructureBox>> createPreparedStartFuture(
            ServerLevel level,
            PreparedStartKey start,
            ThreadPoolManager.TaskRole resolutionRole) {
        ChunkPos pos = new ChunkPos(start.chunkKey());
        boolean startupServerThreadBlocked = InitialGenManager.isRoadGenerationWorkersActive();
        if (start.source() == StructureFileStorage.SOURCE_MANUAL) {
            // Manual endpoints (including the spawn cabin) use the same
            // bounded structure-reference lookup during startup.  The batch
            // collector pumps the server executor, so rejecting an uncached
            // endpoint here would incorrectly fail the spawn route.
            return resolveManualPointBoxesAsync(level, pos.x, pos.z);
        }
        StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.incrementAndGet();
        if (start.structureSet() != null) {
            if (!startupServerThreadBlocked && StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) {
                return resolvePredictedChunkBoxesAsync(level, pos);
            }
            long epoch = ThreadPoolManager.currentEpoch();
            StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.incrementAndGet();
            Supplier<List<OwnedStructureBox>> nativeResolution = () -> {
                if (!ThreadPoolManager.isEpoch(epoch)) {
                    throw new CancellationException("RoadWeaver world epoch changed before direct structure generation");
                }
                if (!startupServerThreadBlocked && StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) {
                    throw new NativeDirectCircuitOpenException();
                }
                if (!startupServerThreadBlocked) acquireNativeDirectLane();
                try {
                    return resolvePredictedStructureSetBoxes(level, pos, start.structureSet());
                } finally {
                    if (!startupServerThreadBlocked) StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.decrementAndGet();
                }
            };
            CompletableFuture<List<OwnedStructureBox>> nativeFuture = startupServerThreadBlocked
                    ? ThreadPoolManager.supplyAsync(resolutionRole, nativeResolution)
                    : CompletableFuture.supplyAsync(nativeResolution, nativeDirectExecutor());
            return nativeFuture.exceptionallyCompose(failure -> {
                Throwable cause = unwrapCompletionFailure(failure);
                if (cause instanceof CancellationException || cause instanceof Error) {
                    return CompletableFuture.failedFuture(cause);
                }
                if (startupServerThreadBlocked) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Native structure prediction failed during startup corridor refresh at " + pos,
                            cause));
                }
                long fallback = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.incrementAndGet();
                if (fallback <= 8 || fallback % 1000 == 0) {
                    LOGGER.warn("【原生结构预测失败，改查真实结构起点区块】 " + "RoadWeaver native structure prediction failed at {}; falling back to real STRUCTURE_STARTS chunk (count={}): {}",
                            pos, fallback, cause.toString());
                }
                return resolvePredictedChunkBoxesAsync(level, pos);
            });
        }
        // A persisted predicted structure does not retain its StructureSet
        // holder, so it must be resolved from the real STRUCTURE_STARTS
        // chunk. Initial road batches use managedBlock while collecting
        // worker futures, allowing this bounded server task to run instead
        // of turning an uncached endpoint into a route failure.
        return resolvePredictedChunkBoxesAsync(level, pos);
    }

    /**
     * Runs the same Structure.generate path used by ChunkGenerator.createStructures,
     * but without allocating or scheduling a real chunk. This preserves modded
     * Structure implementations while removing the startup chunk-executor cycle.
     */
    public static List<OwnedStructureBox> resolvePredictedStructureSetBoxes(
            ServerLevel level, ChunkPos pos, Holder<StructureSet> structureSetHolder) {
        StructureSet structureSet = structureSetHolder.value();
        var generatorState = level.getChunkSource().getGeneratorState();
        if (!structureSet.placement().isStructureChunk(generatorState, pos.x, pos.z)) return List.of();
        List<StructureSet.StructureSelectionEntry> entries = structureSet.structures();
        if (entries.isEmpty()) return List.of();
        ArrayList<StructureSet.StructureSelectionEntry> remaining = entries.size() == 1
                ? null
                : new ArrayList<>(entries);
        WorldgenRandom random = StructureAvoidanceCache.STRUCTURE_SELECTION_RANDOM.get();
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        random.setLargeFeatureSeed(seed, pos.x, pos.z);
        var generator = level.getChunkSource().getGenerator();
        while (remaining == null || !remaining.isEmpty()) {
            int totalWeight = 0;
            List<StructureSet.StructureSelectionEntry> candidates = remaining == null ? entries : remaining;
            for (StructureSet.StructureSelectionEntry entry : candidates) {
                totalWeight += entry.weight();
            }
            int selected = random.nextInt(totalWeight);
            int selectedIndex = 0;
            for (int i = 0; i < candidates.size(); i++) {
                selected -= candidates.get(i).weight();
                if (selected < 0) {
                    selectedIndex = i;
                    break;
                }
            }
            StructureSet.StructureSelectionEntry entry = candidates.get(selectedIndex);
            Structure structure = entry.structure().value();
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    generatorState.randomState(),
                    level.getStructureManager(),
                    seed,
                    pos,
                    0,
                    level,
                    structure.biomes()::contains);
            if (start != null && start.isValid()) {
                Set<OwnedStructureBox> collected = new HashSet<>();
                collectPieces(level, levelOwner(level, structure, pos.toLong()), start, collected);
                return List.copyOf(collected);
            }
            if (remaining == null) return List.of();
            remaining.remove(selectedIndex);
        }
        return List.of();
    }

    public static void acquireNativeDirectLane() {
        while (!Thread.currentThread().isInterrupted()) {
            if (StructureAvoidanceDiagnostics.NATIVE_DIRECT_DISABLED.get()) throw new NativeDirectCircuitOpenException();
            net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.beforeBackgroundUnit(false);
            int configuredLimit = InitialGenManager.isActive()
                    ? nativeDirectWorkerMaximum()
                    : net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy.recommendedRoadWorkerThreads();
            int limit = Math.max(1, Math.min(nativeDirectWorkerMaximum(), configuredLimit));
            int active = StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.get();
            if (active < limit && StructureAvoidanceDiagnostics.ACTIVE_NATIVE_DIRECT.compareAndSet(active, active + 1)) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new CancellationException("Interrupted while waiting for native structure capacity");
    }

    public static ExecutorService nativeDirectExecutor() {
        ExecutorService existing = StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.get();
        if (existing != null && !existing.isShutdown()) return existing;
        synchronized (StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR) {
            existing = StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.get();
            if (existing != null && !existing.isShutdown()) return existing;
            AtomicLong nextThread = new AtomicLong(1L);
            ExecutorService created = Executors.newFixedThreadPool(nativeDirectWorkerMaximum(), task -> {
                Thread thread = new Thread(task, "RW-NativeStructure-" + nextThread.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
            StructureAvoidanceCache.NATIVE_DIRECT_EXECUTOR.set(created);
            return created;
        }
    }

    public static int nativeDirectWorkerMaximum() {
        // The enlarged lane is for startup prewarm only. Runtime structure
        // prediction keeps the normal player-safe structure budget.
        return InitialGenManager.isActive()
                ? ThreadPoolManager.startupNativeStructureThreadCapacity()
                : ThreadPoolManager.maxRuntimeStructureThreads();
    }

    public static CompletableFuture<List<OwnedStructureBox>> resolvePredictedChunkBoxesAsync(
            ServerLevel level, ChunkPos pos) {
        var chunkFuture = requestChunkAsync(level, pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS);
        return chunkFuture.thenCompose(result -> callOnServerThread(level, () -> {
            ChunkAccess chunk = result.orElse(null);
            if (chunk == null) {
                throw new IllegalStateException("Unable to load predicted structure start chunk " + pos);
            }
            Set<OwnedStructureBox> collected = new HashSet<>();
            for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                collectPieces(level, levelOwner(level, entry.getKey(), pos.toLong()), entry.getValue(), collected);
            }
            return List.copyOf(collected);
        }));
    }

    public static List<OwnedStructureBox> storePreparedStart(PreparedStartKey startKey,
                                                        List<OwnedStructureBox> resolved,
                                                        boolean trackInitialProgress) {
        if (resolved == null) {
            throw new IllegalStateException("Unable to resolve structure start " + new ChunkPos(startKey.chunkKey()));
        }
        if (resolved.isEmpty()) StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.incrementAndGet();
        else StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.addAndGet(resolved.size());
        List<OwnedStructureBox> existing;
        if (InitialGenManager.isActive()
                && (startKey.source() == StructureFileStorage.SOURCE_MANUAL
                || startKey.structureSet() == null)) {
            existing = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.putIfAbsent(startKey, resolved);
        } else {
            existing = putIfAbsentBounded(
                    StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER, startKey, resolved,
                    PREPARED_START_CACHE_CAPACITY);
        }
        List<OwnedStructureBox> accepted = existing == null ? resolved : existing;
        StructureAvoidanceCache.IN_FLIGHT_STARTS.remove(startKey);
        if (trackInitialProgress) InitialGenerationProgressTracker.recordPlanningStartDone(accepted.isEmpty());
        return accepted;
    }

    /**
     * 0.9.034（R-07 计数模式）：**只读**探针 —— 记录「如果开了群系预筛，这个起点会不会被挡掉」，
     * 以及「被挡掉的起点里有多少真的产出了方块」。
     *
     * <p>真机实测（0.9.028 那局）：`exact structure start plan total=4374`、71 批共 57,831 ms、
     * `structure snapshot timing resolutionMs=74,408`（占 async planning 140,502 ms 的 53%），
     * 而 `emptyResults=3997 / 4374 = 91.4%` 解析完是空的。候选由
     * {@code predictAvoidanceStructuresInRect} 枚举，那条路**没有任何群系预筛**；现有的
     * `isPotentialStructureChunk` 是数学 placement 判定，对预测器枚举出来的候选恒真
     * （所以日志里 `prefilterSkipped` 一直是 0）。</p>
     *
     * <p>本方法**不改变任何结果**，只是把判据先量出来。判定为可安全开启的条件是
     * {@code wouldRejectNonEmpty == 0}。</p>
     */
    public static void probeBiomePrefilter(ServerLevel level, PreparedStartKey start,
                                            List<OwnedStructureBox> accepted) {
        if (start == null || start.structureSet() == null) return;
        if (start.source() != StructureFileStorage.SOURCE_PREDICTED) return;
        if (!probeEnabled()) return;
        Boolean wouldReject;
        try {
            wouldReject = StructurePredictor.biomeWouldReject(
                    level, start.structureSet(), new ChunkPos(start.chunkKey()));
        } catch (Throwable unavailable) {
            return;
        }
        if (wouldReject == null) return;
        StructureAvoidanceDiagnostics.BIOME_PROBE_PROBED.incrementAndGet();
        if (!wouldReject.booleanValue()) return;
        StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED.incrementAndGet();
        if (accepted != null && !accepted.isEmpty()) StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED_NONEMPTY.incrementAndGet();
    }

    public static boolean probeEnabled() {
        String flag = System.getProperty(BIOME_PROBE_PROPERTY);
        return flag == null || !"false".equalsIgnoreCase(flag.trim());
    }

    public static List<OwnedStructureBox> cachedPreparedStart(PreparedStartKey start) {
        List<OwnedStructureBox> pinned = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.get(start);
        if (pinned != null) {
            DIAG_PINNED_HITS.incrementAndGet();
            return pinned;
        }
        List<OwnedStructureBox> main = StructureAvoidanceCache.PREPARED_START_BOXES.get(start);
        if (main != null) {
            DIAG_MAIN_HITS.incrementAndGet();
        } else {
            DIAG_MAIN_MISSES.incrementAndGet();
            // ★ 0.8.402 诊断：**探测「同语义 key 但不同 identity」的漏命中**。
            //
            //  假设：PreparedStartKey 含 Holder<StructureSet> 字段，而
            //  Minecraft 的 Holder.Reference 未重写 equals ⇒ 按 identity 比较。
            //  若该假设成立，则「同一 (dimension, seed, chunkKey, source, 结构集语义)」
            //  的两次调用会产生**两个不同 key**，主缓存必然 miss。
            //
            //  本探测刻意**不假设假设成立**：它按 (dimension, seed, chunkKey, source)
            //  这 4 个稳定字段做一次线性扫描，直接数出「有多少个已缓存的 entry
            //  仅因 structureSet 不同而未被命中」。命中数 > 0 即坐实 Holder 假设。
            if (DIAG_ENABLED.compareAndSet(false, true)) {
                LOGGER.info("RoadWeaver prepared-start 诊断启动：开始探测 Holder<StructureSet> identity 漏命中");
            }
            probeIdentityLeak(start);
        }
        return main;
    }

    /**
     * 扫描主缓存，统计「除 structureSet 外其余字段全同」的条目数。
     *
     * <p>若该计数持续 &gt; 0，说明缓存键被 {@code Holder} identity 污染：
     * 语义相同的 start 因 Holder 实例不同而被当成不同键，导致重复解析。
     * 反之若恒为 0，则 miss 是真实的新 start（缓存本身没问题）。</p>
     *
     * <p>为控制开销，每 {@value #DIAG_PROBE_INTERVAL} 次 miss 才扫一次。</p>
     */
    /**
     * 0.9.010：prepared-start 命中诊断。**默认关闭**，用 {@code -Droadweaver.preparedStartDiag=true} 打开。
     *
     * <p>为什么默认关：旧实现只要 dimension/seed/chunkKey/source 四个稳定字段相同就计入"泄漏"，
     * 但同一 chunk 里本来就有多个不同 start、不同 structureSet 的 key ⇒ 大量误计（真机日志刷屏），
     * 反而让人以为存在真实的 Holder identity 问题。这是纯诊断，误报只污染日志。</p>
     *
     * <p>为什么改成"语义比较"：真正的"Holder identity 漏命中"必须连 structureSet 值也相等
     * （那就说明 record 的 equals 本该命中却没命中）。而结构上这几乎不可能发生——所以打开后
     * 数量应为 0；不为 0 才是真 bug。其余"同 chunk 不同 structureSet"的 miss 属正常，单独计数、
     * 不再冒充泄漏。</p>
     */
    public static void probeIdentityLeak(PreparedStartKey missKey) {
        if (!PREPARED_START_DIAG) return;
        long n = DIAG_MAIN_MISSES.get();
        if (n % DIAG_PROBE_INTERVAL != 0L) return;
        int sameStableFields = 0;
        int sameEverything = 0;
        int sourceDiffers = 0;
        for (PreparedStartKey cached : StructureAvoidanceCache.PREPARED_START_BOXES.keySet()) {
            if (!cached.dimension().equals(missKey.dimension())) continue;
            if (cached.seed() != missKey.seed()) continue;
            if (cached.chunkKey() != missKey.chunkKey()) continue;
            if (cached.source() != missKey.source()) {
                // 0.9.013：只差 source。原来这里直接 continue，于是这一类 miss 完全不可见 ——
                // 而真机那 0.1% 的命中率最可能就来自它。
                sourceDiffers++;
                continue;
            }
            sameStableFields++;
            if (Objects.equals(cached.structureSet(), missKey.structureSet())) sameEverything++;
        }
        if (sourceDiffers > 0) DIAG_SOURCE_DIFFERS.addAndGet(sourceDiffers);
        if (sameStableFields > 0) {
            DIAG_SAME_CHUNK_MISSES.addAndGet(sameStableFields);
            // 只有 structureSet 也相等才算"Holder identity 漏命中"；其余是同 chunk 不同 start，属正常。
            if (sameEverything > 0) {
                DIAG_IDENTITY_LEAKS.addAndGet(sameEverything);
            }
            long leaks = DIAG_IDENTITY_LEAKS.get();
            if (leaks > 0 && (leaks <= 20 || leaks % 500 == 0)) {
                LOGGER.warn("RoadWeaver prepared-start 诊断：**结构集相同却 miss（疑似 Holder identity 漏命中）** "
                                + "dim={} seed={} chunkKey={} source={} "
                                + "structureSetEqual={} sameChunkDifferentSet={} (累计真泄漏={})",
                        missKey.dimension(), missKey.seed(), missKey.chunkKey(), missKey.source(),
                        sameEverything, sameStableFields - sameEverything, leaks);
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RoadWeaver prepared-start 诊断：同 chunk 但不同 structureSet（正常 miss） "
                                + "dim={} seed={} chunkKey={} source={} sameStableFields={} "
                                + "sameChunkDifferentSet={} (累计={})",
                        missKey.dimension(), missKey.seed(), missKey.chunkKey(), missKey.source(),
                        sameStableFields, sameStableFields - sameEverything,
                        DIAG_SAME_CHUNK_MISSES.get());
            }
        }
    }

    /** 每 64 次 miss 扫描一次缓存（控制开销）。 */
    static final long DIAG_PROBE_INTERVAL = 64L;

    /** 诊断计数器（0.8.402，验证 Holder identity 假设）。 */
    static final AtomicLong DIAG_PINNED_HITS = new AtomicLong();
    static final AtomicLong DIAG_MAIN_HITS = new AtomicLong();
    static final AtomicLong DIAG_MAIN_MISSES = new AtomicLong();
    static final AtomicLong DIAG_IDENTITY_LEAKS = new AtomicLong();
    /** 0.9.010：prepared-start 诊断开关，默认关（纯诊断，误报只会污染日志）。 */
    static final boolean PREPARED_START_DIAG =
            Boolean.getBoolean("roadweaver.preparedStartDiag");
    /** 0.9.010：同 chunk 但 structureSet 不同（正常 miss）的累计数，与真泄漏分开计。 */
    static final AtomicLong DIAG_SAME_CHUNK_MISSES = new AtomicLong();
    /**
     * 0.9.013：同 (dim, seed, chunkKey) 但 **source 不同** 的累计数。
     *
     * <p>为什么单列：原来的探针在 {@code cached.source() != missKey.source()} 时直接 continue，
     * 于是「只差 source」这一类 miss 完全不可见。真机一局 {@code size=7668 / hits=18 /
     * misses=15336 / 淘汰=0 / identity泄漏探测=0} —— 缓存装得下、key 也没在 Holder 身份上跑偏，
     * 那 0.1% 的命中率就只能来自「同一个起点被两轮用不同 source 记了两次」。
     * 这个计数器就是下一个要看的数。</p>
     */
    static final AtomicLong DIAG_SOURCE_DIFFERS = new AtomicLong();
    static final AtomicBoolean DIAG_ENABLED = new AtomicBoolean();

    /** 诊断快照（限频日志用）。 */
    public static void logPreparedStartDiagnostics() {
        long pinned = DIAG_PINNED_HITS.get();
        long hits = DIAG_MAIN_HITS.get();
        long misses = DIAG_MAIN_MISSES.get();
        long total = pinned + hits + misses;
        double hitRate = total <= 0 ? 0.0 : (pinned + hits) * 100.0 / total;
        LOGGER.info("RoadWeaver prepared-start 诊断汇总：pinned={} mainHits={} misses={} "
                        + "总命中率={}% identity泄漏探测={} 同chunk同source却miss={} 同chunk仅source不同={} "
                        + "主缓存size={} evictions={}",
                pinned, hits, misses,
                String.format(java.util.Locale.ROOT, "%.1f", hitRate),
                DIAG_IDENTITY_LEAKS.get(), DIAG_SAME_CHUNK_MISSES.get(), DIAG_SOURCE_DIFFERS.get(),
                StructureAvoidanceCache.PREPARED_START_BOXES.size(),
                PREPARED_CACHE_EVICTIONS.get());
    }

    /**
     * Initial generation reuses exact starts while grade repair and structure
     * rerouting refine the same roads. Compact only after all startup workers
     * have joined so an early start is never evicted and regenerated mid-run.
     */
    public static void compactPreparedStartCacheAfterInitialGeneration() {
        int before = StructureAvoidanceCache.PREPARED_START_BOXES.size();
        int pinned = StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.size();
        for (Map.Entry<PreparedStartKey, List<OwnedStructureBox>> entry : StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.entrySet()) {
            putIfAbsentBounded(StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER,
                    entry.getKey(), entry.getValue(), PREPARED_START_CACHE_CAPACITY);
        }
        StructureAvoidanceCache.STARTUP_PINNED_START_BOXES.clear();
        trimBounded(StructureAvoidanceCache.PREPARED_START_BOXES, StructureAvoidanceCache.PREPARED_START_ORDER, PREPARED_START_CACHE_CAPACITY);
        LOGGER.info("【初始生成结束后精确结构缓存已压缩】 " + "RoadWeaver exact structure cache compacted after initial generation: before={} pinned={} after={} capacity={}",
                before, pinned, StructureAvoidanceCache.PREPARED_START_BOXES.size(), PREPARED_START_CACHE_CAPACITY);
        // ★ 2026-09-26：把"是否有重复解析"的实测一并打出来。
        //   WorkBuddy §6 的 O-1/O-2（加大缓存 / 持久化解析结果）只有在这条
        //   显示"淘汰数显著"时才值得做 —— 否则纯属改动红线却零收益。
        LOGGER.info("【结构起点缓存命中与驱逐统计】 " + "RoadWeaver prepared-start cache stats: hits={} evictions={} capacity={} size={}",
                StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get(), PREPARED_CACHE_EVICTIONS.get(),
                PREPARED_START_CACHE_CAPACITY, StructureAvoidanceCache.PREPARED_START_BOXES.size());
        // ★ 0.8.402：同时输出诊断汇总（Holder identity 漏命中探测结果）。
        logPreparedStartDiagnostics();
    }

    /** Manual endpoints may reference starts in other chunks, so resolve both levels without blocking ticks. */
    public static CompletableFuture<List<OwnedStructureBox>> resolveManualPointBoxesAsync(
            ServerLevel level, int cx, int cz) {
        StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.incrementAndGet();
        return resolveReferenceChunkBoxesAsync(level, cx, cz);
    }

    public static CompletableFuture<List<OwnedStructureBox>> resolveReferenceChunkBoxesAsync(
            ServerLevel level, int cx, int cz) {
        var referencesFuture = requestChunkAsync(level, cx, cz, ChunkStatus.STRUCTURE_REFERENCES);
        return referencesFuture.thenCompose(result -> callOnServerThread(level, () -> {
            ChunkAccess referenceChunk = result.orElse(null);
            if (referenceChunk == null) {
                throw new IllegalStateException(
                        "Unable to load manual structure reference chunk [" + cx + "," + cz + "]");
            }
            List<ManualStartReference> references = new ArrayList<>();
            for (Map.Entry<Structure, LongSet> entry : referenceChunk.getAllReferences().entrySet()) {
                for (long startChunkLong : entry.getValue()) {
                    references.add(new ManualStartReference(entry.getKey(), startChunkLong));
                }
            }
            return List.copyOf(references);
        })).thenCompose(references -> requestManualStartChunks(level, references));
    }

    public static CompletableFuture<List<OwnedStructureBox>> requestManualStartChunks(
            ServerLevel level, List<ManualStartReference> references) {
        if (references.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return callOnServerThread(level, () -> {
            List<CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> futures =
                    new ArrayList<>(references.size());
            for (ManualStartReference reference : references) {
                ChunkPos pos = new ChunkPos(reference.chunkKey());
                futures.add(requestChunkOnServerThread(
                        level, pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS));
            }
            return new ManualStartBatch(references, futures);
        }).thenCompose(batch -> {
            CompletableFuture<?>[] pending = batch.futures().toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(pending).thenCompose(ignored -> callOnServerThread(level, () -> {
                Set<OwnedStructureBox> collected = new HashSet<>();
                for (int i = 0; i < batch.references().size(); i++) {
                    ManualStartReference reference = batch.references().get(i);
                    ChunkAccess chunk = batch.futures().get(i).join().orElse(null);
                    if (chunk == null) {
                        throw new IllegalStateException("Unable to load manual structure start chunk "
                                + new ChunkPos(reference.chunkKey()));
                    }
                    collectPieces(level, levelOwner(level, reference.structure(), reference.chunkKey()),
                            chunk.getStartForStructure(reference.structure()), collected);
                }
                return List.copyOf(collected);
            }));
        });
    }

    public static CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> requestChunkAsync(
            ServerLevel level, int cx, int cz, ChunkStatus status) {
        return callOnServerThread(level, () -> requestChunkOnServerThread(level, cx, cz, status))
                .thenCompose(future -> future);
    }

    public static CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> requestChunkOnServerThread(
            ServerLevel level, int cx, int cz, ChunkStatus status) {
        var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Chunk futures must be submitted on the server thread"));
        }
        if (InitialGenManager.isActive()) {
            try {
                // During prepareLevels there is no normal server tick yet.
                // ServerChunkCache#getChunk performs its own managed wait on
                // the chunk main-thread executor, unlike MinecraftServer#managedBlock.
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, status, true);
                return CompletableFuture.completedFuture(
                        net.minecraft.server.level.ChunkResult.of(chunk));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (!(level.getChunkSource() instanceof NonBlockingChunkFutureAccess invoker)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("RoadWeaver non-blocking chunk future invoker is unavailable"));
        }
        ChunkRequestKey requestKey = new ChunkRequestKey(
                level.dimension().location().toString(), level.getSeed(), ChunkPos.asLong(cx, cz), status);
        CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> existing =
                StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.get(requestKey);
        if (existing != null) return existing;

        CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> shared = new CompletableFuture<>();
        existing = StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.putIfAbsent(requestKey, shared);
        if (existing != null) return existing;

        long epoch = ThreadPoolManager.currentEpoch();
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        chunkSource.addRegionTicket(STRUCTURE_SNAPSHOT_TICKET, chunkPos, 0, Unit.INSTANCE);
        invoker.roadweaver$invokeGetChunkFutureMainThread(cx, cz, status, true)
                .whenComplete((result, failure) -> {
                    try {
                        if (failure != null) shared.completeExceptionally(failure);
                        else if (!ThreadPoolManager.isEpoch(epoch)) {
                            shared.completeExceptionally(new java.util.concurrent.CancellationException(
                                    "RoadWeaver world epoch changed while loading " + new ChunkPos(cx, cz)));
                        } else {
                            shared.complete(result);
                        }
                    } finally {
                        StructureAvoidanceCache.IN_FLIGHT_CHUNK_REQUESTS.remove(requestKey, shared);
                    }
                });
        shared.whenCompleteAsync((ignored, failure) ->
                chunkSource.removeRegionTicket(
                        STRUCTURE_SNAPSHOT_TICKET, chunkPos, 0, Unit.INSTANCE), server);
        return shared;
    }
}
