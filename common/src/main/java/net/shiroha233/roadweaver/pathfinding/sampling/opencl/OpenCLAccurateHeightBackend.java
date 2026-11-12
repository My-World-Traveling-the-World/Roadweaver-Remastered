/* 文件职责：执行 OpenCL 精确高度批次、逐程序校验与可选的严格 GPU-only 调用。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightBackend;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightChunk;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingProgress;
import net.shiroha233.roadweaver.pathfinding.sampling.AcceleratedSamplingUnavailableException;
import net.shiroha233.roadweaver.pathfinding.sampling.CpuAccurateHeightBackend;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 精确高度 OpenCL 后端。
 */
public final class OpenCLAccurateHeightBackend implements AccurateHeightBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** GPU float noise can round a surface height one block differently from the
     *  CPU sampler. A one-block height difference is harmless for road carving,
     *  so it must not invalidate the whole OpenCL backend. */
    private static final int VALIDATION_TOLERANCE = 1;

    private final CpuAccurateHeightBackend cpu;
    private final OpenCLRuntime runtime;
    private final OpenCLAccurateProgramCache.ProgramState programState;

    private OpenCLAccurateHeightBackend(CpuAccurateHeightBackend cpu,
                                        OpenCLRuntime runtime,
                                        OpenCLAccurateProgramCache.ProgramState programState) {
        this.cpu = cpu;
        this.runtime = runtime;
        this.programState = programState;
    }

    public static AccurateHeightBackend tryCreate(ServerLevel level, CpuAccurateHeightBackend cpu) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return null;
        }
        OpenCLRuntime runtime = OpenCLRuntime.tryCreateAccurateGpu();
        if (runtime == null || !runtime.deviceInfo().gpu() || !runtime.deviceInfo().fp64()) {
            return null;
        }
        RandomState randomState = level.getChunkSource().getGeneratorState().randomState();
        OpenCLAccurateProgramCache.ProgramState state =
                OpenCLAccurateProgramCache.getOrCompile(level, generator, randomState, runtime.deviceInfo());
        if (!state.supported()) {
            LOGGER.info("OpenCL 精采样不支持当前生成设置，使用 CPU: {}", state.unsupportedReason());
            return null;
        }
        LOGGER.info("OpenCL 精采样已启用 dimension={} device={}", level.dimension().location(), runtime.deviceName());
        return new OpenCLAccurateHeightBackend(cpu, runtime, state);
    }

    @Override
    public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return Map.of();
        }
        if (Thread.currentThread().isInterrupted()) {
            AccurateSamplingStats.recordFallback("thread_interrupted");
            return cpu.sampleChunks(chunkKeys);
        }
        if (!programState.supported()) {
            AccurateSamplingStats.recordFallback(programState.unsupportedReason());
            return cpu.sampleChunks(chunkKeys);
        }
        if (!runtime.isUsable()) {
            AccurateSamplingStats.recordFallback("device_session_unavailable");
            return cpu.sampleChunks(chunkKeys);
        }
        long columns = (long) chunkKeys.size() * AccurateHeightChunk.COLUMN_COUNT;
        if (!acquireNormalGpuLane(columns)) {
            return cpu.sampleChunks(chunkKeys);
        }

        OpenCLAccurateProgramCache.ValidationMode validationMode =
                programState.acquireValidation(validateEveryBatch());
        boolean validationCompleted = false;
        try {
            if (Thread.currentThread().isInterrupted()) {
                AccurateSamplingStats.recordFallback("thread_interrupted");
                return cpu.sampleChunks(chunkKeys);
            }
            if (!programState.supported()) {
                AccurateSamplingStats.recordFallback(programState.unsupportedReason());
                return cpu.sampleChunks(chunkKeys);
            }
            Map<Long, AccurateHeightChunk> gpu = programState.program().sample(runtime, chunkKeys);
            if (validationMode != OpenCLAccurateProgramCache.ValidationMode.NONE) {
                AccurateSamplingStats.recordValidationStart();
                Collection<Long> validationKeys = selectValidationKeys(chunkKeys);
                Map<Long, AccurateHeightChunk> expected = cpu.sampleChunks(validationKeys);
                String mismatch = firstMismatch(validationKeys, expected, gpu);
                if (mismatch != null) {
                    String reason = "OpenCL 精采样结果不一致: " + mismatch;
                    programState.markUnsupported(reason);
                    AccurateSamplingStats.recordValidationFailure();
                    AccurateSamplingStats.recordFallback(reason);
                    LOGGER.warn("{} device={}", reason, runtime.deviceName());
                    validationCompleted = true;
                    return cpu.sampleChunks(chunkKeys);
                }
                AccurateSamplingStats.recordValidationPass();
                validationCompleted = true;
                LOGGER.info("OpenCL 精采样校验通过 chunks={} device={}", validationKeys.size(), runtime.deviceName());
            }
            return gpu;
        } catch (Throwable failure) {
            String reason = "OpenCL 精采样执行失败: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            // A recoverable per-batch allocation rejection must not poison
            // the device session. The caller may retry a smaller batch on the
            // next request; invalidating here permanently disabled GPU after
            // one oversized local repair window.
            if (programState.supported() && !isRecoverableAllocationFailure(failure)) {
                runtime.invalidate(failure);
            }
            AccurateSamplingStats.recordFallback(reason);
            LOGGER.warn("{}，使用 CPU", reason, failure);
            return cpu.sampleChunks(chunkKeys);
        } finally {
            programState.finishValidation(validationMode, validationCompleted);
            runtime.releaseAccurateGpuLane();
        }
    }

    @Override
    public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions) {
        return samplePositions(positions, AccurateSamplingProgress.NONE);
    }

    @Override
    public Map<Long, AccurateHeightSample> samplePositions(Collection<BlockPos> positions,
                                                            AccurateSamplingProgress progress) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        if (Thread.currentThread().isInterrupted()) {
            AccurateSamplingStats.recordFallback("thread_interrupted");
            return cpu.samplePositions(positions, progress);
        }
        if (!programState.supported()) {
            AccurateSamplingStats.recordFallback(programState.unsupportedReason());
            return cpu.samplePositions(positions, progress);
        }
        if (!runtime.isUsable()) {
            AccurateSamplingStats.recordFallback("device_session_unavailable");
            return cpu.samplePositions(positions, progress);
        }
        long columns = positions.size();
        if (!acquireNormalGpuLane(columns)) {
            return cpu.samplePositions(positions, progress);
        }

        OpenCLAccurateProgramCache.ValidationMode validationMode =
                programState.acquireValidation(validateEveryBatch());
        boolean validationCompleted = false;
        try {
            if (Thread.currentThread().isInterrupted()) {
                AccurateSamplingStats.recordFallback("thread_interrupted");
                return cpu.samplePositions(positions, progress);
            }
            if (!programState.supported()) {
                AccurateSamplingStats.recordFallback(programState.unsupportedReason());
                return cpu.samplePositions(positions, progress);
            }
            Map<Long, AccurateHeightSample> gpu = programState.program().samplePositions(runtime, positions, progress);
            if (validationMode != OpenCLAccurateProgramCache.ValidationMode.NONE) {
                validationCompleted = true;
                if (!validatePositionHeights(positions, gpu)) {
                    return cpu.samplePositions(positions, progress);
                }
            }
            return gpu;
        } catch (Throwable failure) {
            String reason = "OpenCL 精采样执行失败: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (programState.supported() && !isRecoverableAllocationFailure(failure)) {
                runtime.invalidate(failure);
            }
            AccurateSamplingStats.recordFallback(reason);
            LOGGER.warn("{}，使用 CPU", reason, failure);
            return cpu.samplePositions(positions, progress);
        } finally {
            programState.finishValidation(validationMode, validationCompleted);
            runtime.releaseAccurateGpuLane();
        }
    }

    @Override
    public AccurateHeightGrid sampleGrid(AccurateHeightGridRequest request,
                                         AccurateSamplingProgress progress) {
        if (Thread.currentThread().isInterrupted()) {
            AccurateSamplingStats.recordFallback("thread_interrupted");
            return cpu.sampleGrid(request, progress);
        }
        if (!programState.supported()) {
            AccurateSamplingStats.recordFallback(programState.unsupportedReason());
            return cpu.sampleGrid(request, progress);
        }
        if (!runtime.isUsable()) {
            AccurateSamplingStats.recordFallback("device_session_unavailable");
            return cpu.sampleGrid(request, progress);
        }
        long columns = request.sampleCount();
        if (!acquireNormalGpuLane(columns)) {
            return cpu.sampleGrid(request, progress);
        }

        OpenCLAccurateProgramCache.ValidationMode validationMode =
                programState.acquireValidation(validateEveryBatch());
        boolean validationCompleted = false;
        try {
            AccurateHeightGrid gpu = programState.program().sampleGrid(runtime, request, progress);
            if (validationMode != OpenCLAccurateProgramCache.ValidationMode.NONE) {
                validationCompleted = true;
                if (!validateGridHeights(request, gpu)) {
                    return cpu.sampleGrid(request, progress);
                }
            }
            return gpu;
        } catch (Throwable failure) {
            String reason = "OpenCL 精采样执行失败: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (programState.supported() && !isRecoverableAllocationFailure(failure)) {
                runtime.invalidate(failure);
            }
            AccurateSamplingStats.recordFallback(reason);
            LOGGER.warn("{}，使用 CPU", reason, failure);
            return cpu.sampleGrid(request, progress);
        } finally {
            programState.finishValidation(validationMode, validationCompleted);
            runtime.releaseAccurateGpuLane();
        }
    }

    @Override
    public boolean supportsAcceleratedSampling() {
        return programState.supported() && runtime.isUsable();
    }

    private static boolean isRecoverableAllocationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("clCreateBuffer failed: -61")
                    || message.contains("CL_INVALID_BUFFER_SIZE")
                    || message.contains("CL_INVALID_MEM_OBJECT"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public Map<Long, AccurateHeightSample> sampleAcceleratedPositions(Collection<BlockPos> positions,
                                                                       AccurateSamplingProgress progress) {
        if (positions == null || positions.isEmpty()) return Map.of();
        ensureAcceleratedAvailable();
        AccurateSamplingProgress sink = progress == null ? AccurateSamplingProgress.NONE : progress;
        boolean ownsGpuLane = false;
        OpenCLAccurateProgramCache.ValidationMode validationMode = OpenCLAccurateProgramCache.ValidationMode.NONE;
        boolean validationCompleted = false;
        try {
            runtime.acquireAccurateGpuLane();
            ownsGpuLane = true;
            ensureAcceleratedAvailable();
            validationMode = programState.acquireValidation(validateEveryBatch());
            Map<Long, AccurateHeightSample> gpu = programState.program().samplePositions(runtime, positions, sink);
            if (validationMode != OpenCLAccurateProgramCache.ValidationMode.NONE) {
                validationCompleted = true;
                if (!validatePositionHeights(positions, gpu)) {
                    throw new AcceleratedSamplingUnavailableException(programState.unsupportedReason());
                }
            }
            return gpu;
        } catch (CancellationException | AcceleratedSamplingUnavailableException expected) {
            throw expected;
        } catch (Throwable failure) {
            throw acceleratedFailure(failure);
        } finally {
            programState.finishValidation(validationMode, validationCompleted);
            if (ownsGpuLane) runtime.releaseAccurateGpuLane();
        }
    }

    @Override
    public AccurateHeightGrid sampleAcceleratedGrid(AccurateHeightGridRequest request,
                                                     AccurateSamplingProgress progress) {
        ensureAcceleratedAvailable();
        AccurateSamplingProgress sink = progress == null ? AccurateSamplingProgress.NONE : progress;
        boolean ownsGpuLane = false;
        OpenCLAccurateProgramCache.ValidationMode validationMode = OpenCLAccurateProgramCache.ValidationMode.NONE;
        boolean validationCompleted = false;
        try {
            runtime.acquireAccurateGpuLane();
            ownsGpuLane = true;
            ensureAcceleratedAvailable();
            validationMode = programState.acquireValidation(validateEveryBatch());
            AccurateHeightGrid gpu = programState.program().sampleGrid(runtime, request, sink);
            if (validationMode != OpenCLAccurateProgramCache.ValidationMode.NONE) {
                validationCompleted = true;
                if (!validateGridHeights(request, gpu)) {
                    throw new AcceleratedSamplingUnavailableException(programState.unsupportedReason());
                }
            }
            return gpu;
        } catch (CancellationException | AcceleratedSamplingUnavailableException expected) {
            throw expected;
        } catch (Throwable failure) {
            throw acceleratedFailure(failure);
        } finally {
            programState.finishValidation(validationMode, validationCompleted);
            if (ownsGpuLane) runtime.releaseAccurateGpuLane();
        }
    }

    @Override
    public String backendName() {
        return supportsAcceleratedSampling() ? "OPENCL_ACCURATE" : "CPU";
    }

    @Override
    public String deviceName() {
        return supportsAcceleratedSampling() ? runtime.deviceName() : "CPU";
    }

    private void ensureAcceleratedAvailable() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("accelerated accurate sampling interrupted");
        }
        if (!programState.supported()) {
            throw new AcceleratedSamplingUnavailableException(programState.unsupportedReason());
        }
        if (!runtime.isUsable()) {
            throw new AcceleratedSamplingUnavailableException("OpenCL device session unavailable");
        }
    }

    private AcceleratedSamplingUnavailableException acceleratedFailure(Throwable failure) {
        String reason = "OpenCL accurate sampling failed: " + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        if (programState.supported()) runtime.invalidate(failure);
        AccurateSamplingStats.recordFallback(reason);
        LOGGER.warn("{}; full-region CPU fallback is disabled", reason, failure);
        return new AcceleratedSamplingUnavailableException(reason, failure);
    }

    @Override
    public void close() {
        cpu.close();
    }

    private static boolean validateEveryBatch() {
        try {
            return ConfigService.get().performance().openclValidateSamples();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean acquireNormalGpuLane(long columns) {
        if (InitialGenManager.isActive() || RuntimeLoadPolicy.isChunkyPreflightActive()) {
            if (!runtime.tryAcquireAccurateGpuLanePreferGpu()) {
                recordCpuDiversion(columns);
                return false;
            }
        } else {
            runtime.acquireAccurateGpuLane();
        }
        AccurateSamplingStats.recordHybridGpuDispatch(columns);
        return true;
    }

    private static void recordCpuDiversion(long columns) {
        long divertedRequests = AccurateSamplingStats.recordHybridCpuDiversion(columns);
        if (divertedRequests == 1L || divertedRequests % 32L == 0L) {
            AccurateSamplingStats.BackendSnapshot stats = AccurateSamplingStats.backendSnapshot();
            LOGGER.info("RoadWeaver startup hybrid accurate sampling: gpuRequests={}, gpuColumns={}, "
                            + "cpuDivertedRequests={}, cpuDivertedColumns={}.",
                    stats.hybridGpuRequests(), stats.hybridGpuColumns(),
                    stats.hybridCpuDivertedRequests(), stats.hybridCpuDivertedColumns());
        }
    }

    private boolean validatePositionHeights(Collection<BlockPos> positions,
                                            Map<Long, AccurateHeightSample> gpu) {
        List<BlockPos> validationPositions = selectValidationPositions(positions);
        AccurateSamplingStats.recordValidationStart();
        Map<Long, AccurateHeightSample> expected = cpu.samplePositions(validationPositions);
        for (BlockPos position : validationPositions) {
            long key = AccurateHeightSample.key(position.getX(), position.getZ());
            String mismatch = firstSampleMismatch(
                    position.getX(), position.getZ(), expected.get(key), gpu.get(key));
            if (mismatch != null) {
                return failValidation(mismatch);
            }
        }
        AccurateSamplingStats.recordValidationPass();
        LOGGER.info("OpenCL 精采样稀疏列校验通过 columns={} device={}",
                validationPositions.size(), runtime.deviceName());
        return true;
    }

    private boolean validateGridHeights(AccurateHeightGridRequest request, AccurateHeightGrid gpu) {
        Collection<Long> validationChunks = selectValidationKeys(chunkKeysFor(request));
        LinkedHashSet<Long> selected = new LinkedHashSet<>(validationChunks);
        List<BlockPos> validationPositions = new ArrayList<>();
        for (int index = 0; index < request.sampleCount(); index++) {
            int blockX = request.blockX(index);
            int blockZ = request.blockZ(index);
            if (selected.contains(ChunkPos.asLong(blockX >> 4, blockZ >> 4))) {
                validationPositions.add(new BlockPos(blockX, 0, blockZ));
            }
        }

        AccurateSamplingStats.recordValidationStart();
        Map<Long, AccurateHeightSample> expected = cpu.samplePositions(validationPositions);
        for (int index = 0; index < request.sampleCount(); index++) {
            int blockX = request.blockX(index);
            int blockZ = request.blockZ(index);
            if (!selected.contains(ChunkPos.asLong(blockX >> 4, blockZ >> 4))) {
                continue;
            }
            AccurateHeightSample actual = new AccurateHeightSample(
                    gpu.worldSurface()[index], gpu.oceanFloor()[index], gpu.motionBlocking()[index]);
            String mismatch = firstSampleMismatch(
                    blockX, blockZ, expected.get(AccurateHeightSample.key(blockX, blockZ)), actual);
            if (mismatch != null) {
                return failValidation(mismatch);
            }
        }
        AccurateSamplingStats.recordValidationPass();
        LOGGER.info("OpenCL 精采样网格校验通过 columns={} device={}",
                validationPositions.size(), runtime.deviceName());
        return true;
    }

    private boolean failValidation(String mismatch) {
        String reason = "OpenCL 精采样结果不一致: " + mismatch;
        programState.markUnsupported(reason);
        AccurateSamplingStats.recordValidationFailure();
        AccurateSamplingStats.recordFallback(reason);
        LOGGER.warn("{} device={}", reason, runtime.deviceName());
        return false;
    }

    private static Collection<Long> chunkKeysFor(Collection<BlockPos> positions) {
        LinkedHashSet<Long> chunkKeys = new LinkedHashSet<>();
        for (BlockPos position : positions) {
            if (position != null) {
                chunkKeys.add(ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4));
            }
        }
        return chunkKeys;
    }

    private static Collection<Long> chunkKeysFor(AccurateHeightGridRequest request) {
        LinkedHashSet<Long> chunkKeys = new LinkedHashSet<>();
        for (int index = 0; index < request.sampleCount(); index++) {
            chunkKeys.add(ChunkPos.asLong(request.blockX(index) >> 4, request.blockZ(index) >> 4));
        }
        return chunkKeys;
    }

    private static List<BlockPos> selectValidationPositions(Collection<BlockPos> positions) {
        Collection<Long> validationChunks = selectValidationKeys(chunkKeysFor(positions));
        LinkedHashSet<Long> selected = new LinkedHashSet<>(validationChunks);
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos position : positions) {
            if (position == null) {
                continue;
            }
            long chunkKey = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
            long columnKey = AccurateHeightSample.key(position.getX(), position.getZ());
            if (selected.contains(chunkKey) && seen.add(columnKey)) {
                result.add(position);
            }
        }
        return result;
    }

    static Collection<Long> selectValidationKeys(Collection<Long> keys) {
        List<Long> ordered = List.copyOf(keys);
        if (ordered.size() <= 4) {
            return ordered;
        }
        LinkedHashSet<Long> selected = new LinkedHashSet<>(4);
        selected.add(ordered.get(0));
        while (selected.size() < 4) {
            long bestKey = 0L;
            long bestDistance = Long.MIN_VALUE;
            boolean found = false;
            for (long candidate : ordered) {
                if (selected.contains(candidate)) {
                    continue;
                }
                long nearest = Long.MAX_VALUE;
                for (long existing : selected) {
                    long dx = (long) ChunkPos.getX(candidate) - ChunkPos.getX(existing);
                    long dz = (long) ChunkPos.getZ(candidate) - ChunkPos.getZ(existing);
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                if (!found || nearest > bestDistance) {
                    bestKey = candidate;
                    bestDistance = nearest;
                    found = true;
                }
            }
            if (!found) {
                break;
            }
            selected.add(bestKey);
        }
        return selected;
    }

    private static String firstMismatch(Collection<Long> keys,
                                        Map<Long, AccurateHeightChunk> expected,
                                        Map<Long, AccurateHeightChunk> actual) {
        for (long key : keys) {
            AccurateHeightChunk cpu = expected.get(key);
            AccurateHeightChunk gpu = actual.get(key);
            if (cpu == null || gpu == null) {
                return "missing chunk [" + ChunkPos.getX(key) + "," + ChunkPos.getZ(key) + "]";
            }
            for (int index = 0; index < AccurateHeightChunk.COLUMN_COUNT; index++) {
                if (Math.abs(cpu.worldSurfaceWgAt(index) - gpu.worldSurfaceWgAt(index)) > VALIDATION_TOLERANCE) {
                    return mismatch(key, index, "WORLD_SURFACE_WG", cpu.worldSurfaceWgAt(index), gpu.worldSurfaceWgAt(index));
                }
                if (Math.abs(cpu.oceanFloorWgAt(index) - gpu.oceanFloorWgAt(index)) > VALIDATION_TOLERANCE) {
                    return mismatch(key, index, "OCEAN_FLOOR_WG", cpu.oceanFloorWgAt(index), gpu.oceanFloorWgAt(index));
                }
                if (Math.abs(cpu.motionBlockingNoLeavesAt(index) - gpu.motionBlockingNoLeavesAt(index)) > VALIDATION_TOLERANCE) {
                    return mismatch(key, index, "MOTION_BLOCKING_NO_LEAVES",
                            cpu.motionBlockingNoLeavesAt(index), gpu.motionBlockingNoLeavesAt(index));
                }
            }
        }
        return null;
    }

    private static String mismatch(long key, int index, String type, int cpu, int gpu) {
        int x = (ChunkPos.getX(key) << 4) + (index & 15);
        int z = (ChunkPos.getZ(key) << 4) + (index >> 4);
        return "type=" + type + " pos=[" + x + "," + z + "] CPU=" + cpu + " GPU=" + gpu;
    }

    private static String firstSampleMismatch(int x,
                                              int z,
                                              AccurateHeightSample cpu,
                                              AccurateHeightSample gpu) {
        if (cpu == null || gpu == null) {
            return "missing column [" + x + "," + z + "]";
        }
        if (Math.abs(cpu.worldSurfaceWg() - gpu.worldSurfaceWg()) > VALIDATION_TOLERANCE) {
            return "type=WORLD_SURFACE_WG pos=[" + x + "," + z + "] CPU="
                    + cpu.worldSurfaceWg() + " GPU=" + gpu.worldSurfaceWg();
        }
        if (Math.abs(cpu.oceanFloorWg() - gpu.oceanFloorWg()) > VALIDATION_TOLERANCE) {
            return "type=OCEAN_FLOOR_WG pos=[" + x + "," + z + "] CPU="
                    + cpu.oceanFloorWg() + " GPU=" + gpu.oceanFloorWg();
        }
        if (Math.abs(cpu.motionBlockingNoLeaves() - gpu.motionBlockingNoLeaves()) > VALIDATION_TOLERANCE) {
            return "type=MOTION_BLOCKING_NO_LEAVES pos=[" + x + "," + z + "] CPU="
                    + cpu.motionBlockingNoLeaves() + " GPU=" + gpu.motionBlockingNoLeaves();
        }
        return null;
    }
}
