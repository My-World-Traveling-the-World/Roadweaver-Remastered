/* 文件职责：使用 OpenCL 执行粗高度批量采样并提供逐程序校验与 CPU 回退。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.CoarseHeightBatchSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.CpuCoarseHeightBatchSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.FastHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.bridge.OpenCLBridge;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenCL 粗高度批量采样器。
 */
public final class OpenCLCoarseHeightBatchSampler implements CoarseHeightBatchSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final AtomicBoolean ENABLED_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean KERNEL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean DISABLED_FALLBACK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean RETRYABLE_COMPILE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean RUNTIME_SLICING_LOGGED = new AtomicBoolean();
    /** 0.8.340：单批 scratch 超预算转为 CPU 的日志闸（只详细记一次，避免刷屏）。 */
    private static final AtomicBoolean BUDGET_DIVERT_LOGGED = new AtomicBoolean();
    private static final Object COARSE_LANE_LOCK = new Object();
    private static final List<OpenCLRuntime> COARSE_LANE_RUNTIMES = new ArrayList<>();
    private static final AtomicInteger NEXT_COARSE_LANE = new AtomicInteger();
    private static final AtomicLong INITIAL_GPU_REQUESTS = new AtomicLong();
    private static final AtomicLong INITIAL_GPU_SAMPLES = new AtomicLong();
    private static final AtomicLong INITIAL_CPU_DIVERTED_REQUESTS = new AtomicLong();
    private static final AtomicLong INITIAL_CPU_DIVERTED_SAMPLES = new AtomicLong();
    private static final int RUNTIME_MAX_OPENCL_SAMPLES = 128;
    private static final Object SESSION_VALIDATION_LOCK = new Object();
    private static final Object PROGRAM_CACHE_LOCK = new Object();
    private static final IdentityHashMap<Object, CachedDensityProgram> PROGRAM_CACHE = new IdentityHashMap<>();
    private static boolean sessionValidationStarted;
    private static boolean sessionValidationFinished;

    private final ServerLevel level;
    private final OpenCLRuntime runtime;
    private final CachedDensityProgram cachedProgram;
    private final DensityGraphProgram program;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private CpuCoarseHeightBatchSampler fallback;

    private OpenCLCoarseHeightBatchSampler(ServerLevel level,
                                            OpenCLRuntime runtime,
                                            CachedDensityProgram cachedProgram,
                                            NoiseSettings settings) {
        this.level = level;
        this.runtime = runtime;
        this.cachedProgram = cachedProgram;
        this.program = cachedProgram.program();
        this.minY = settings.minY();
        this.maxY = settings.minY() + settings.height();
        this.cellHeight = settings.getCellHeight();
    }

    public static CoarseHeightBatchSampler tryCreate(ServerLevel level,
                                                     OpenCLDevicePreference devicePreference) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return null;
        }
        if (OpenCLWorldSupport.isUnsupported()) {
            return null;
        }
        CachedCompileResult graph = compileInitialDensity(level);
        if (!graph.supported()) {
            if (graph.retryable() && RETRYABLE_COMPILE_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("OpenCL 粗采样暂时不可用，主世界本次回退到 CPU，后续继续重试: {}",
                        graph.unsupportedReason());
            }
            return null;
        }
        OpenCLRuntime runtime = acquireCoarseLane(devicePreference);
        if (runtime == null) {
            return null;
        }
        try {
            OpenCLCoarseHeightBatchSampler sampler = new OpenCLCoarseHeightBatchSampler(
                    level,
                    runtime,
                    graph.cachedProgram(),
                    getNoiseSettings(level));
            InitialGenerationProgressTracker.setBackend("OPENCL", runtime.deviceName(), null);
            if (ENABLED_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("OpenCL 粗采样已启用 preference={} nodes={} normalNoises={} splines={}",
                        runtime.devicePreference(),
                        graph.cachedProgram().program().nodes().size(),
                        graph.cachedProgram().program().noiseTables().normalNoises().size(),
                        graph.cachedProgram().program().splines().size());
            }
            return sampler;
        } catch (Throwable t) {
            String reason = "OpenCL 粗采样器创建失败: " + t.getClass().getSimpleName();
            if (OpenCLWorldSupport.markUnsupported(reason)) {
                LOGGER.info("OpenCL 粗采样器创建失败，主世界回退到 CPU: {}", t.getMessage(), t);
            }
            return null;
        }
    }

    public static void clearProgramCache() {
        synchronized (PROGRAM_CACHE_LOCK) {
            for (CachedDensityProgram program : PROGRAM_CACHE.values()) {
                program.close();
            }
            PROGRAM_CACHE.clear();
        }
        synchronized (SESSION_VALIDATION_LOCK) {
            sessionValidationStarted = false;
            sessionValidationFinished = false;
            SESSION_VALIDATION_LOCK.notifyAll();
        }
    }

    private static CachedCompileResult compileInitialDensity(ServerLevel level) {
        try {
            var randomState = level.getChunkSource().getGeneratorState().randomState();
            synchronized (PROGRAM_CACHE_LOCK) {
                if (OpenCLWorldSupport.isUnsupported()) {
                    return CachedCompileResult.unsupported(OpenCLWorldSupport.unsupportedReason());
                }
                CachedDensityProgram cached = PROGRAM_CACHE.get(randomState);
                if (cached != null) {
                    return CachedCompileResult.supported(cached);
                }
                var router = randomState.router();
                DensityGraphCompileResult result = DensityGraphCompiler.compile(router.initialDensityWithoutJaggedness());
                if (!result.supported()) {
                    CachedCompileResult failed = CachedCompileResult.from(result);
                    if (!failed.retryable() && OpenCLWorldSupport.markUnsupported(failed.unsupportedReason())) {
                        LOGGER.info("OpenCL 粗采样暂不支持主世界，回退到 CPU: {}", failed.unsupportedReason());
                    }
                    return failed;
                }
                cached = new CachedDensityProgram(result.program());
                PROGRAM_CACHE.put(randomState, cached);
                return CachedCompileResult.supported(cached);
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            String message = t.getMessage();
            return CachedCompileResult.retryable("initial density graph unavailable: "
                    + t.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message));
        }
    }

    @Override
    public int[] sampleHeights(CoarseHeightBatchRequest request) {
        if (request == null) {
            return null;
        }
        if (OpenCLWorldSupport.isUnsupported()) {
            InitialGenerationProgressTracker.setBackend("CPU", "CPU",
                    OpenCLWorldSupport.unsupportedReason());
            return fallback().sampleHeights(request);
        }
        if (program.isEmpty()) {
            InitialGenerationProgressTracker.setBackend("CPU", "CPU", "opencl_program_empty");
            return fallback().sampleHeights(request);
        }
        if (!OpenCLAvailability.isAvailable()) {
            if (DISABLED_FALLBACK_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("OpenCL 粗采样已禁用，当前请求直接使用 CPU: {}", OpenCLAvailability.disabledReason());
            }
            InitialGenerationProgressTracker.setBackend("CPU", "CPU", OpenCLAvailability.disabledReason());
            return fallback().sampleHeights(request);
        }
        boolean initialGeneration = InitialGenManager.isActive()
                || RuntimeLoadPolicy.isChunkyPreflightActive();
        if (initialGeneration) {
            // Startup samplers are distributed across independent OpenCL
            // sessions. Do not funnel them through the old global one-permit
            // semaphore or divert them to a single CPU fallback lane.
            INITIAL_GPU_REQUESTS.incrementAndGet();
            INITIAL_GPU_SAMPLES.addAndGet(request.sampleCount());
        }
        long startedAt = System.currentTimeMillis();
        try {
            ValidationMode validationMode = validationMode();
            if (validationMode == ValidationMode.NONE && OpenCLWorldSupport.isUnsupported()) {
                InitialGenerationProgressTracker.setBackend("CPU", "CPU",
                        OpenCLWorldSupport.unsupportedReason());
                return fallback().sampleHeights(request);
            }
            int[] heights = sampleHeightsOpenCL(request);
            try {
                if (validationMode.shouldValidate()) {
                    ValidationResult validation = validationMode == ValidationMode.CONFIG
                            ? validateFullRequest(request, heights)
                            : validateSessionSpotCheck(request, heights);
                    if (!validation.matches()) {
                        String mismatch = validation.detail();
                        String reason = "OpenCL coarse sampling validation failed: " + mismatch;
                        if (OpenCLWorldSupport.markUnsupported(reason)) {
                            LOGGER.info("OpenCL coarse sampling validation failed; falling back to CPU: {}", mismatch);
                        }
                        InitialGenerationProgressTracker.setBackend("CPU", "CPU", mismatch);
                        return fallback().sampleHeights(request);
                    }
                    if (validationMode == ValidationMode.SESSION) {
                        LOGGER.info("OpenCL coarse sampling session spot check passed: samples={} checked={}",
                                request.sampleCount(), validation.checkedSamples());
                    }
                }
            } finally {
                finishSessionValidation(validationMode);
            }
            if (KERNEL_LOGGED.compareAndSet(false, true)) {
                long scratchBytes = Math.multiplyExact(
                        Math.multiplyExact((long) request.sampleCount(), (long) Math.max(1, program.nodes().size())),
                        (long) Double.BYTES);
                LOGGER.info("OpenCL coarse sampling kernel executed: samples={} nodes={} scratchMiB={} reusableBuffers=true",
                        request.sampleCount(),
                        program.nodes().size(),
                        scratchBytes / (1024L * 1024L));
            }
            InitialGenerationProgressTracker.recordSampleBatch("OPENCL", request.sampleCount(),
                    System.currentTimeMillis() - startedAt, runtime.deviceName(), null);
            return heights;
        } catch (CancellationException cancelled) {
            // 0.8.354 修复：**取消/中断不是 OpenCL 故障，必须原样穿透，绝不能禁用 OpenCL。**
            //
            // 真机事故（20260913 07:28:22）：玩家关闭存档时，
            // RuntimeLoadPolicy.beforeBackgroundUnit → cancelIfStopping 抛出
            // CancellationException("RoadWeaver server is stopping")。
            // 该异常从 CoarseTerrainTileSampler.sampleHeightsBanded（带间让步点）
            // 一路冒泡到这里，被下面的 catch (Throwable) 吞掉，
            // 于是 OpenCLAvailability.disable("OpenCL kernel 执行失败") 把 OpenCL
            // **整场会话永久禁用**，后端静默退化为 CPU —— 开局预热从 GPU 掉到 CPU，
            // 玩家在进度条上直接看到「回退原因: OpenCL kernel 执行失败」。
            //
            // 为什么以前没暴露：beforeBackgroundUnit 此前只在**瓦片之间**（外层
            // CoarseTerrainRegionSampler.loadTiles*）调用，取消异常在那层就被处理，
            // 从不进入本方法。0.8.350 的分带把让步点移进了本方法的调用栈内，
            // 才把这个既有的过宽 catch 暴露出来。
            throw cancelled;
        } catch (Throwable t) {
            OpenCLAvailability.disable("OpenCL kernel 执行失败", t);
            InitialGenerationProgressTracker.setBackend("CPU", "CPU", OpenCLAvailability.disabledReason());
            return fallback().sampleHeights(request);
        }
    }

    private static OpenCLRuntime acquireCoarseLane(OpenCLDevicePreference preference) {
        synchronized (COARSE_LANE_LOCK) {
            if (COARSE_LANE_RUNTIMES.isEmpty()) {
                // Never reuse the accurate-sampling runtime here. Its command
                // queue is intentionally serialized for the precise pipeline;
                // coarse startup lanes must have independent sessions.
                OpenCLRuntime first = OpenCLRuntime.tryCreateIndependent(preference);
                if (first == null) return null;
                COARSE_LANE_RUNTIMES.add(first);
                int desired = recommendedCoarseLaneCount(first.deviceInfo());
                for (int index = 1; index < desired; index++) {
                    OpenCLRuntime lane = OpenCLRuntime.tryCreateIndependent(preference);
                    if (lane == null) break;
                    COARSE_LANE_RUNTIMES.add(lane);
                }
                LOGGER.info("RoadWeaver OpenCL coarse lanes prepared: lanes={} device={} memoryMiB={}",
                        COARSE_LANE_RUNTIMES.size(), first.deviceName(),
                        first.deviceInfo().globalMemoryBytes() / (1024L * 1024L));
            }
            int index = Math.floorMod(NEXT_COARSE_LANE.getAndIncrement(), COARSE_LANE_RUNTIMES.size());
            OpenCLRuntime selected = COARSE_LANE_RUNTIMES.get(index);
            return selected.isUsable() ? selected : null;
        }
    }

    static int recommendedCoarseLaneCount(OpenCLBridge.DeviceInfo info) {
        if (info == null || !info.gpu()) return 1;
        long memory = info.globalMemoryBytes();
        if (memory <= 0L) return 2;
        long fourGiB = 4L * 1024L * 1024L * 1024L;
        // ★ 0.8.402：修整数截断导致的边界损失。
        //
        // 原实现 `(int) (memory / fourGiB)` 是**向下取整**，导致「刚好 2/3/4 倍」
        // 的显存被砍掉一档。真机取证（2026-09-27，RTX 4060）：
        //   OpenCL 上报 8188 MiB = 7.996 GiB（比 8 GiB 少 4 MiB，驱动保留）
        //   → 7.996 / 4 = 1.999 → (int) = 1     ← 只剩 1 条 lane
        //   若按四舍五入 → 2 条 lane            ← 并行度直接翻倍
        //
        // 该值决定 OpenCLRuntime 独立会话数，即 GPU 侧真正能并发的粗采样通道数。
        // 它同时被 openclCoarseGpuConcurrencyCap 二次封顶（默认 0=不封顶），
        // 所以这里放宽不会绕过用户配置。
        long lanes = Math.round((double) memory / (double) fourGiB);
        return (int) Math.max(1L, Math.min(4L, lanes));
    }

    static void clearCoarseLanePool() {
        synchronized (COARSE_LANE_LOCK) {
            COARSE_LANE_RUNTIMES.clear();
            NEXT_COARSE_LANE.set(0);
        }
    }

    static boolean shouldDivertInitialRequestToCpu(int gpuWaiters, boolean cpuLaneBusy) {
        return false;
    }

    private static void recordInitialCpuDiversion(int samples) {
        INITIAL_CPU_DIVERTED_REQUESTS.incrementAndGet();
        INITIAL_CPU_DIVERTED_SAMPLES.addAndGet(Math.max(0, samples));
    }

    @Override
    public boolean isAccelerated() {
        return OpenCLAvailability.isAvailable() && !OpenCLWorldSupport.isUnsupported();
    }

    @Override
    public void close() {
        if (fallback != null) {
            fallback.close();
        }
    }

    private CpuCoarseHeightBatchSampler fallback() {
        if (fallback == null) {
            fallback = CpuCoarseHeightBatchSampler.create(level);
        }
        return fallback;
    }

    private int[] sampleHeightsOpenCL(CoarseHeightBatchRequest request) {
        boolean runtimePhase = !InitialGenManager.isActive()
                && !RuntimeLoadPolicy.isChunkyPreflightActive();
        RuntimeCoarseGpuBudget.Permit permit = runtimePhase
                ? RuntimeCoarseGpuBudget.acquire(runtime)
                : null;
        if (runtimePhase && permit == null) {
            // A null permit means the OpenCL session was already unavailable;
            // normal quota pressure waits above instead of using CPU sampling.
            return fallback().sampleHeights(request);
        }
        try {
            return runtime.submit(OpenCLSubmissionPriority.COARSE,
                    () -> sampleHeightsOpenCLLocked(request));
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    private int[] sampleHeightsOpenCLLocked(CoarseHeightBatchRequest request) {
        synchronized (cachedProgram) {
            return sampleHeightsOpenCLWithProgram(request);
        }
    }

    private int[] sampleHeightsOpenCLWithProgram(CoarseHeightBatchRequest request) {
        RequestKernelPayload payload = RequestKernelPayload.from(program, request, minY, maxY, cellHeight);
        long requiredScratchBytes = payload.scratchBytes();
        long budget = coarseScratchBudgetBytes();
        if (budget > 0L && requiredScratchBytes > budget) {
            // 0.8.340：单批 scratch 超过显存预算时本批走 CPU 兜底（结果完全一致，只是慢），
            // 而不是向驱动申请一块过大的缓冲区。
            //
            // ⚠️ 0.8.342 修正（独立审计 A3 已实测证伪，见 RoadWeaver-0.8.340-0.8.341-独立审计报告.md）：
            // 本闸门在**当前整合包参数下基本不触发**，近似形同虚设。原注释声称
            // 「20 万样本的瓦片就要约 1 GiB，这正是吃满显存的根因」是**错误推论**：
            //   - scratch = samples × nodes × 8，本包 nodes=677 → 约 5.3 KiB/样本；
            //   - 但单批样本数有硬上限：tileSizeChunks=32 固定 → 每瓦片
            //     expandedSamplesPerTile = 18769（真机日志实测），对应 scratch ≈ 96.9 MiB；
            //   - 而闸门额度 ≈ 4096 MiB（第 2 层 min(8188×0.70, 4096)），是单批需求的 **42 倍**；
            //   - 反解触发条件：需 free < 96.9/0.70 ≈ 138 MiB，即 8GB 卡上显存占用 **>98.3%**。
            // 真正有用的条件只有把 aStarStep 从 4 降到 1（需求 → 约 1533 MiB，占用 >73% 即触发），
            // 或把 gpuBudgetHardCapMiB 压到 ~128。
            //
            // 保留理由：①对本包「step 调小」以及 nodes 更大的整合包是真实保险；
            // ②若要在本包生效，应改为比较「单批实际申请量（scratch+heights+params）」
            // 与「当前剩余空闲」，而不是拿总预算去比单批需求（两者量纲不一致，天然不触发）。
            if (BUDGET_DIVERT_LOGGED.compareAndSet(false, true)) {
                LOGGER.info("OpenCL coarse sampling diverted to CPU: batch scratch {} MiB exceeds GPU budget {} MiB "
                                + "(samples={} nodes={}); further diversions are logged at debug level",
                        requiredScratchBytes / (1024L * 1024L), budget / (1024L * 1024L),
                        request.sampleCount(), program.nodes().size());
            } else {
                LOGGER.debug("OpenCL coarse sampling diverted to CPU: scratch={} MiB budget={} MiB samples={}",
                        requiredScratchBytes / (1024L * 1024L), budget / (1024L * 1024L),
                        request.sampleCount());
            }
            InitialGenerationProgressTracker.setBackend("CPU", "CPU", "gpu_budget_exceeded");
            return fallback().sampleHeights(request);
        }
        OpenCLDensityProgramBuffers staticBuffers = cachedProgram.buffers(runtime);
        OpenCLCoarseWorkspace.BatchBuffers batchBuffers = cachedProgram.workspace(runtime).prepare(
                runtime,
                payload.params(),
                requiredScratchBytes,
                intBytes(request.sampleCount()));
        List<OpenCLBridge.DeviceBuffer> arguments = new ArrayList<>(17);
        arguments.add(batchBuffers.params());
        arguments.addAll(staticBuffers.arguments());
        arguments.add(batchBuffers.scratch());
        arguments.add(batchBuffers.heights());
        runtime.execute(OpenCLRuntime.COARSE_HEIGHT_KERNEL, arguments, request.sampleCount());
        return runtime.readInts(batchBuffers.heights(), request.sampleCount());
    }

    /**
     * 0.8.340：本批允许占用的 GPU 显存上限（字节）；&lt;=0 表示不限制。
     *
     * <p>走 {@link GpuMemoryProbe#configBudgetBytes(long)} —— 优先用 PDH 实时空闲显存
     * （总显存 - 全卡已用，含游戏本体），读不到时退化为「总显存 x 配置占比」，
     * 再由配置的硬上限封顶。</p>
     */
    private long coarseScratchBudgetBytes() {
        try {
            OpenCLBridge.DeviceInfo info = runtime.deviceInfo();
            if (info == null || !info.gpu()) return 0L;
            return GpuMemoryProbe.configBudgetBytes(info.globalMemoryBytes());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long intBytes(int count) {
        return Math.multiplyExact((long) Math.max(1, count), (long) Integer.BYTES);
    }

    private static ValidationMode validationMode() {
        if (shouldValidateSamples()) {
            return ValidationMode.CONFIG;
        }
        synchronized (SESSION_VALIDATION_LOCK) {
            while (sessionValidationStarted && !sessionValidationFinished) {
                try {
                    SESSION_VALIDATION_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ValidationMode.NONE;
                }
            }
            if (sessionValidationFinished) {
                return ValidationMode.NONE;
            }
            sessionValidationStarted = true;
            return ValidationMode.SESSION;
        }
    }

    private static void finishSessionValidation(ValidationMode mode) {
        if (mode != ValidationMode.SESSION) {
            return;
        }
        synchronized (SESSION_VALIDATION_LOCK) {
            sessionValidationFinished = true;
            SESSION_VALIDATION_LOCK.notifyAll();
        }
    }

    private static boolean shouldValidateSamples() {
        try {
            return ConfigService.get().performance().openclValidateSamples();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ValidationResult validateFullRequest(CoarseHeightBatchRequest request, int[] actual) {
        int[] expected = fallback().sampleHeights(request);
        if (expected == null || actual == null) {
            return ValidationResult.failed("cpu or gpu result was null", 0);
        }
        if (Arrays.equals(expected, actual)) {
            return ValidationResult.matched(expected.length);
        }
        return ValidationResult.failed(firstMismatch(expected, actual), expected.length);
    }

    /**
     * Full CPU validation duplicated an entire tile before the accelerated
     * path could be used. A fixed, evenly distributed spot check keeps the
     * first session bounded while still rejecting an invalid GPU program.
     */
    private ValidationResult validateSessionSpotCheck(CoarseHeightBatchRequest request, int[] actual) {
        if (actual == null || actual.length != request.sampleCount()) {
            return ValidationResult.failed("gpu result length=" + (actual == null ? -1 : actual.length), 0);
        }
        int checks = Math.min(32, actual.length);
        FastHeightSampler cpu = FastHeightSampler.create(level);
        try {
            for (int check = 0; check < checks; check++) {
                int index = checks == 1 ? 0 : (int) (((long) check * (actual.length - 1)) / (checks - 1));
                int x = request.blockXAt(index % request.sampleWidth());
                int z = request.blockZAt(index / request.sampleWidth());
                int expected = cpu.sampleHeight(x, z);
                if (expected != actual[index]) {
                    return ValidationResult.failed(
                            "index=" + index + " cpu=" + expected + " gpu=" + actual[index], check + 1);
                }
            }
            return ValidationResult.matched(checks);
        } finally {
            cpu.clearCache();
        }
    }

    private enum ValidationMode {
        NONE,
        SESSION,
        CONFIG;

        private boolean shouldValidate() {
            return this != NONE;
        }
    }

    private record ValidationResult(boolean matches, String detail, int checkedSamples) {
        private static ValidationResult matched(int checkedSamples) {
            return new ValidationResult(true, "", checkedSamples);
        }

        private static ValidationResult failed(String detail, int checkedSamples) {
            return new ValidationResult(false, detail == null ? "unknown" : detail, checkedSamples);
        }
    }

    private static String firstMismatch(int[] expected, int[] actual) {
        int limit = Math.min(expected.length, actual.length);
        for (int i = 0; i < limit; i++) {
            if (expected[i] != actual[i]) {
                return "index=" + i + " cpu=" + expected[i] + " gpu=" + actual[i];
            }
        }
        return "length cpu=" + expected.length + " gpu=" + actual.length;
    }

    private static String heightSummary(int[] expected, int[] actual) {
        return "cpuRange=" + range(expected) + " gpuRange=" + range(actual);
    }

    private static String range(int[] values) {
        if (values == null || values.length == 0) {
            return "empty";
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return min + ".." + max;
    }

    private static NoiseSettings getNoiseSettings(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
            return noiseGen.generatorSettings().value().noiseSettings();
        }
        return NoiseSettings.create(-64, 384, 1, 2);
    }

    private record CachedCompileResult(
            CachedDensityProgram cachedProgram,
            String unsupportedReason,
            boolean retryable
    ) {
        private static CachedCompileResult supported(CachedDensityProgram program) {
            return new CachedCompileResult(program, null, false);
        }

        private static CachedCompileResult unsupported(String reason) {
            return new CachedCompileResult(null, reason == null || reason.isBlank() ? "unknown" : reason, false);
        }

        private static CachedCompileResult retryable(String reason) {
            return new CachedCompileResult(null, reason == null || reason.isBlank() ? "unknown" : reason, true);
        }

        private static CachedCompileResult from(DensityGraphCompileResult result) {
            if (result.retryable()) {
                return retryable(result.unsupportedReason());
            }
            return unsupported(result.unsupportedReason());
        }

        private boolean supported() {
            return cachedProgram != null && cachedProgram.program() != null && !cachedProgram.program().isEmpty() && unsupportedReason == null;
        }
    }

    private static final class CachedDensityProgram {
        private final DensityGraphProgram program;
        private final OpenCLDensityProgramPayload payload;
        private final IdentityHashMap<OpenCLRuntime, OpenCLDensityProgramBuffers> buffers = new IdentityHashMap<>();
        private final IdentityHashMap<OpenCLRuntime, OpenCLCoarseWorkspace> workspaces = new IdentityHashMap<>();
        private boolean closed;

        private CachedDensityProgram(DensityGraphProgram program) {
            this.program = program;
            this.payload = OpenCLDensityProgramPayload.from(program);
        }

        private DensityGraphProgram program() {
            return program;
        }

        private synchronized OpenCLDensityProgramBuffers buffers(OpenCLRuntime runtime) {
            if (closed) {
                throw new IllegalStateException("coarse OpenCL program is closed");
            }
            return buffers.computeIfAbsent(runtime, key -> OpenCLDensityProgramBuffers.upload(key, payload));
        }

        private synchronized OpenCLCoarseWorkspace workspace(OpenCLRuntime runtime) {
            if (closed) {
                throw new IllegalStateException("coarse OpenCL program is closed");
            }
            return workspaces.computeIfAbsent(runtime, ignored -> new OpenCLCoarseWorkspace());
        }

        private void close() {
            IdentityHashMap<OpenCLRuntime, OpenCLDensityProgramBuffers> closing;
            IdentityHashMap<OpenCLRuntime, OpenCLCoarseWorkspace> closingWorkspaces;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                closing = new IdentityHashMap<>(buffers);
                buffers.clear();
                closingWorkspaces = new IdentityHashMap<>(workspaces);
                workspaces.clear();
            }
            for (var entry : closing.entrySet()) {
                synchronized (entry.getKey().operationLock()) {
                    entry.getValue().close();
                }
            }
            for (var entry : closingWorkspaces.entrySet()) {
                synchronized (entry.getKey().operationLock()) {
                    entry.getValue().close();
                }
            }
        }
    }

    private record RequestKernelPayload(
            int[] params,
            long scratchBytes
    ) {
        private static RequestKernelPayload from(DensityGraphProgram program,
                                                CoarseHeightBatchRequest request,
                                                int minY,
                                                int maxY,
                                                int cellHeight) {
            int sampleCount = request.sampleCount();
            int nodeCount = program.nodes().size();
            int[] params = {
                    sampleCount,
                    minY,
                    maxY,
                    cellHeight,
                    request.minBlockX(),
                    request.minBlockZ(),
                    request.step(),
                    request.sampleWidth(),
                    program.rootNode(),
                    nodeCount,
                    program.noiseTables().normalNoises().size(),
                    program.noiseTables().perlinNoises().size(),
                    program.noiseTables().improvedNoises().size(),
                    program.splines().size()
            };
            long scratchBytes = Math.multiplyExact(
                    Math.multiplyExact((long) sampleCount, (long) Math.max(1, nodeCount)),
                    (long) Double.BYTES);
            return new RequestKernelPayload(params, scratchBytes);
        }
    }

}
