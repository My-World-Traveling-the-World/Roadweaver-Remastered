/* 文件职责：管理可复用的 OpenCL 设备会话、kernel 资源与串行提交边界。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import net.shiroha233.roadweaver.pathfinding.sampling.opencl.bridge.OpenCLBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * OpenCL 高层运行时。所有 native 操作由 bridge 执行。
 */
public final class OpenCLRuntime implements AutoCloseable {
    public static final String COARSE_HEIGHT_KERNEL = "roadweaver_coarse_height_sample";
    public static final String ACCURATE_LATTICE_KERNEL = "roadweaver_accurate_lattice";
    public static final String ACCURATE_PRELIMINARY_INIT_KERNEL = "roadweaver_accurate_preliminary_init";
    public static final String ACCURATE_PRELIMINARY_KERNEL = "roadweaver_accurate_preliminary";
    public static final String ACCURATE_AQUIFER_KERNEL = "roadweaver_accurate_aquifer";
    public static final String ACCURATE_HEIGHT_INIT_KERNEL = "roadweaver_accurate_height_init";
    public static final String ACCURATE_HEIGHT_PARALLEL_KERNEL = "roadweaver_accurate_height_parallel";
    public static final String ACCURATE_HEIGHT_KERNEL = "roadweaver_accurate_height";

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String KERNEL_RESOURCE = "/assets/roadweaver/opencl/OpenCLCoarseHeightKernel.cl";
    private static final Object RUNTIME_LOCK = new Object();
    private static final Map<RuntimeKey, OpenCLRuntime> RUNTIMES = new LinkedHashMap<>();
    /** Extra independent sessions used by coarse startup sampling lanes. */
    private static final Set<OpenCLRuntime> EXTRA_RUNTIMES = ConcurrentHashMap.newKeySet();

    private final Object operationLock = new Object();
    private final OpenCLSubmissionGate submissionGate = new OpenCLSubmissionGate();
    private final OpenCLAccurateGpuLane accurateGpuLane = new OpenCLAccurateGpuLane();
    private final RuntimeKey key;
    private final OpenCLBridge.Session session;
    private final AtomicBoolean failed = new AtomicBoolean();

    private OpenCLRuntime(RuntimeKey key, OpenCLBridge.Session session) {
        this.key = key;
        this.session = session;
    }

    public static OpenCLRuntime tryCreate(OpenCLDevicePreference preference) {
        if (!OpenCLAvailability.isAvailable()) {
            return null;
        }
        OpenCLDevicePreference safePreference = preference == null ? OpenCLDevicePreference.AUTO : preference;
        RuntimeKey key = new RuntimeKey(safePreference, false, true);
        synchronized (RUNTIME_LOCK) {
            OpenCLRuntime current = findReusable(safePreference, false);
            if (current != null) {
                return current;
            }
            try {
                OpenCLRuntime runtime = open(key);
                if (runtime == null) {
                    OpenCLAvailability.disable("未发现支持 FP64 的匹配 OpenCL 设备");
                    OpenCLAvailability.logDisabledOnce(null);
                    return null;
                }
                RUNTIMES.put(key, runtime);
                LOGGER.info("OpenCL 地形采样运行时已初始化 preference={} device={} fp64={}",
                        safePreference,
                        runtime.deviceName(),
                        runtime.deviceInfo().fp64());
                return runtime;
            } catch (Throwable failure) {
                OpenCLAvailability.disable(runtimeFailureReason(failure), failure);
                return null;
            }
        }
    }

    public static OpenCLRuntime tryCreateAccurateGpu() {
        RuntimeKey key = new RuntimeKey(OpenCLDevicePreference.GPU, true, true);
        synchronized (RUNTIME_LOCK) {
            OpenCLRuntime current = findReusable(OpenCLDevicePreference.GPU, true);
            if (current != null) {
                return current;
            }
            try {
                OpenCLRuntime runtime = open(key);
                if (runtime != null) {
                    RUNTIMES.put(key, runtime);
                }
                return runtime;
            } catch (Throwable failure) {
                LOGGER.info("OpenCL 精采样 GPU 运行时不可用: {}", failure.getMessage());
                return null;
            }
        }
    }

    /**
     * Creates an independent device session for a coarse sampling lane. A
     * normal cached runtime must remain single-owner because accurate sampling
     * and coarse sampling may otherwise serialize behind the same queue.
     */
    static OpenCLRuntime tryCreateIndependent(OpenCLDevicePreference preference) {
        if (!OpenCLAvailability.isAvailable()) return null;
        OpenCLDevicePreference safePreference = preference == null
                ? OpenCLDevicePreference.AUTO : preference;
        try {
            OpenCLRuntime runtime = open(new RuntimeKey(safePreference, false, true));
            if (runtime != null) EXTRA_RUNTIMES.add(runtime);
            return runtime;
        } catch (Throwable failure) {
            // 0.8.371 修复：**失败必须落进 OpenCLAvailability，否则会无限重试刷日志。**
            //
            // 真机事故（Linux 共创者机器，2026-09-18）：该发行版只装了
            // libOpenCL.so.1（运行时库），没有 libOpenCL.so（开发符号链接），
            // 于是 LWJGL 加载 org.lwjgl.opencl 时抛
            // "Failed to locate library: libOpenCL.so"，并且**它自己会打 9 行堆栈**。
            //
            // 本方法此前只 catch 住异常、打印一行 info 就返回 null，**没有调用
            // OpenCLAvailability.disable(...)**，导致 isAvailable() 始终为 true，
            // 于是每次粗采样请求都会再次走到这里、再次触发那一整坨 LWJGL 堆栈 ——
            // 日志里表现为每 2 分钟重复一轮（实测 21:37:17 / 21:39:08 / 21:41:29），
            // 累计 323 errors + 507 warns 绝大部分都是它。
            //
            // 修复方式与其他失败路径保持一致（如 tryCreate 的 catch 分支）：
            // 记录禁用原因 + 只打一次汇总日志，后续请求在 isAvailable() 处直接短路，
            // 不再触碰 LWJGL，噪音彻底消失。功能不受影响：粗采样会自动回落到 CPU。
            OpenCLAvailability.disable(runtimeFailureReason(failure), failure);
            return null;
        }
    }

    private static OpenCLRuntime open(RuntimeKey key) throws IOException {
        OpenCLBridge.Session session = OpenCLBridge.open(new OpenCLBridge.OpenRequest(
                key.preference(),
                key.gpuOnly(),
                key.requireFp64(),
                readKernelSource(),
                List.of(COARSE_HEIGHT_KERNEL, ACCURATE_LATTICE_KERNEL, ACCURATE_PRELIMINARY_INIT_KERNEL,
                        ACCURATE_PRELIMINARY_KERNEL,
                        ACCURATE_AQUIFER_KERNEL, ACCURATE_HEIGHT_INIT_KERNEL,
                        ACCURATE_HEIGHT_PARALLEL_KERNEL, ACCURATE_HEIGHT_KERNEL)));
        return session == null ? null : new OpenCLRuntime(key, session);
    }

    private static OpenCLRuntime findReusable(OpenCLDevicePreference preference, boolean gpuOnly) {
        if (!gpuOnly && preference == OpenCLDevicePreference.AUTO) {
            OpenCLRuntime gpu = findReusable(OpenCLDevicePreference.GPU, false);
            if (gpu != null) {
                return gpu;
            }
            return findReusable(OpenCLDevicePreference.CPU, false);
        }
        for (OpenCLRuntime runtime : RUNTIMES.values()) {
            if (!runtime.isUsable()) {
                continue;
            }
            boolean gpu = runtime.deviceInfo().gpu();
            if (gpuOnly && !gpu) {
                continue;
            }
            if (preference == OpenCLDevicePreference.GPU && !gpu) {
                continue;
            }
            if (preference == OpenCLDevicePreference.CPU && gpu) {
                continue;
            }
            return runtime;
        }
        return null;
    }

    private static String runtimeFailureReason(Throwable failure) {
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        if (message.startsWith("clBuildProgram failed")) {
            return "OpenCL kernel 编译失败";
        }
        if (message.startsWith("clCreateKernel")) {
            return "OpenCL kernel 创建失败";
        }
        // 0.8.371：Linux 上最常见的 OpenCL 不可用原因，单独给出可操作的提示。
        // 发行版通常只装运行时库 libOpenCL.so.1（由 ocl-icd 提供），而 LWJGL 按
        // SONAME 之外的名字 libOpenCL.so 查找，于是加载失败。装 ocl-icd-opencl-dev
        // （或手工建一个指向 libOpenCL.so.1 的软链接）即可解决。
        if (message.contains("Failed to locate library") || message.contains("libOpenCL")) {
            return "OpenCL 运行库未找到（Linux 需安装 libOpenCL.so，如 ocl-icd-opencl-dev）";
        }
        return "OpenCL 初始化失败";
    }

    public OpenCLDevicePreference devicePreference() {
        return key.preference();
    }

    public String deviceName() {
        return session.deviceInfo().name();
    }

    public OpenCLBridge.DeviceInfo deviceInfo() {
        return session.deviceInfo();
    }

    public Object operationLock() {
        return operationLock;
    }

    public <T> T submit(OpenCLSubmissionPriority priority, Supplier<T> operation) {
        if (!isUsable()) {
            throw new IllegalStateException("OpenCL device session is unavailable");
        }
        return submissionGate.submit(priority, () -> {
            synchronized (operationLock) {
                if (!isUsable()) {
                    throw new IllegalStateException("OpenCL device session became unavailable while waiting");
                }
                return operation.get();
            }
        });
    }

    boolean tryAcquireAccurateGpuLane() {
        return accurateGpuLane.tryAcquire();
    }

    boolean tryAcquireAccurateGpuLanePreferGpu() {
        return accurateGpuLane.tryAcquirePreferGpu();
    }

    void acquireAccurateGpuLane() {
        accurateGpuLane.acquire();
    }

    void releaseAccurateGpuLane() {
        accurateGpuLane.release();
    }

    public OpenCLBridge.DeviceBuffer upload(int[] values) {
        return session.upload(values);
    }

    public void writeInts(OpenCLBridge.DeviceBuffer buffer, int[] values) {
        session.writeInts(buffer, values);
    }

    public OpenCLBridge.DeviceBuffer upload(double[] values) {
        return session.upload(values);
    }

    public OpenCLBridge.DeviceBuffer allocate(OpenCLBridge.BufferAccess access, long bytes) {
        try {
            return session.allocate(access, bytes);
        } catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("clCreateBuffer failed: -61")) {
                LOGGER.warn("RoadWeaver OpenCL -61 allocation rejected access={} requestedMiB={} deviceMaxMiB={}",
                        access, bytes / (1024L * 1024L),
                        session.deviceInfo().maxAllocationBytes() / (1024L * 1024L));
            }
            throw failure;
        }
    }

    public long execute(String kernelName, List<OpenCLBridge.DeviceBuffer> arguments, long workItems) {
        if (!isUsable()) {
            throw new IllegalStateException("OpenCL device session is unavailable");
        }
        return session.execute(kernelName, arguments, workItems);
    }

    public OpenCLBridge.CommandEvent enqueue(String kernelName,
                                             List<OpenCLBridge.DeviceBuffer> arguments,
                                             long workItems) {
        if (!isUsable()) {
            throw new IllegalStateException("OpenCL device session is unavailable");
        }
        return session.enqueue(kernelName, arguments, workItems);
    }

    public int[] readInts(OpenCLBridge.DeviceBuffer buffer, int count) {
        return session.readInts(buffer, count);
    }

    public boolean isUsable() {
        return !failed.get();
    }

    public void invalidate(Throwable failure) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("OpenCL 设备会话失效 device={} reason={}",
                deviceName(), failure == null ? "unknown" : failure.getMessage());
        submissionGate.submit(OpenCLSubmissionPriority.MAINTENANCE, () -> {
            session.close();
            return null;
        });
    }

    @Override
    public void close() {
        // 会话由服务器级生命周期统一释放，单个 sampler 不拥有它。
    }

    public static void closeSharedRuntimeForTests() {
        closeAll();
    }

    public static void closeAll() {
        synchronized (RUNTIME_LOCK) {
            OpenCLCoarseHeightBatchSampler.clearCoarseLanePool();
            OpenCLCoarseHeightBatchSampler.clearProgramCache();
            OpenCLAccurateProgramCache.clear();
            Set<OpenCLRuntime> uniqueRuntimes = new LinkedHashSet<>(RUNTIMES.values());
            for (OpenCLRuntime runtime : uniqueRuntimes) {
                runtime.closeSession();
            }
            for (OpenCLRuntime runtime : EXTRA_RUNTIMES) {
                if (!uniqueRuntimes.contains(runtime)) runtime.closeSession();
            }
            EXTRA_RUNTIMES.clear();
            RUNTIMES.clear();
        }
    }

    private static String readKernelSource() throws IOException {
        try (InputStream stream = OpenCLRuntime.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing kernel resource: " + KERNEL_RESOURCE);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return LithostitchedKernelSupport.inject(source);
        }
    }

    private void closeSession() {
        if (failed.compareAndSet(false, true)) {
            submissionGate.submit(OpenCLSubmissionPriority.MAINTENANCE, () -> {
                session.close();
                return null;
            });
        }
        submissionGate.close();
    }

    private record RuntimeKey(OpenCLDevicePreference preference, boolean gpuOnly, boolean requireFp64) {}
}
