/* 文件职责：探测显卡显存的真实空闲量，供粗采样 GPU 预算自适应使用。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.8.340：GPU 显存空闲量探测（**三层降级**）。
 *
 * <p>背景：原实现只用 {@code CL_DEVICE_GLOBAL_MEM_SIZE}（显卡总显存）推算预算
 * （{@code 总显存 x 80% / 3}）。它**不知道游戏本体和其他 mod 已经占用了多少**，
 * 于是在小显存卡上「游戏 3.5GB + RoadWeaver 2.1GB」容易撞车，表现为主线程
 * 突然卡顿（用户真机反馈：「采样无论限制多少都会突然吃满显存导致卡顿一小会」）。</p>
 *
 * <p>三层降级，按顺序尝试，任一层失败即落到下一层：</p>
 * <ol>
 *   <li><b>PDH 实时读取</b>（Windows 性能计数器，即任务管理器那套指标）
 *       —— 经 JNA 反射读 {@code \GPU Adapter Memory(*)\Dedicated Usage}，
 *       得到「全卡已用专用显存」，空闲 = 总显存 - 已用。</li>
 *   <li><b>保守比例</b>：读不到实时值时，用「总显存 x 70%」当作可用额度。</li>
 *   <li><b>保底</b>：连总显存都读不到，返回 {@code 0} 表示"不干预"，
 *       由调用方沿用原有逻辑。</li>
 * </ol>
 *
 * <h2>第 1 层为什么必须"按实例名逐个读"，而不是通配符</h2>
 * <p>实测（20260911，本机 RTX 4060 / 8GB）：</p>
 * <ul>
 *   <li>{@code \GPU Adapter Memory(*)\Dedicated Usage} 用通配符 + {@code PdhAddEnglishCounter}
 *       能 {@code rc=0} 添加成功，但 {@code PdhGetRawCounterValue} 恒返回 0
 *       —— 通配符计数器的取值必须配合 {@code PdhGetFormattedCounterArray}，
 *       而 <b>JNA 5.14 并未封装该函数</b>（已用 {@code javap} 核实其 17 个方法里
 *       只有单值版 {@code PdhGetRawCounterValue}）。</li>
 *   <li>改用 {@code PdhEnumObjectItems} 枚举出真实实例名（如
 *       {@code luid_0x00000000_0x0000AC6E_phys_0}），再对**每个具名实例**单独
 *       {@code PdhAddEnglishCounter}，{@code PdhGetRawCounterValue.FirstValue}
 *       就能读到真实字节数（独立复现：Java 879 MiB，同刻 PowerShell
 *       {@code Get-Counter} 883 MiB，一致）。</li>
 * </ul>
 * <p>因此本类走「枚举实例 → 逐个具名读取 → 求和」的路线，绕开 JNA 缺失的数组 API。</p>
 *
 * <h2>0.8.342 修正的两个实测缺陷（独立审计发现，已真机复现）</h2>
 * <ol>
 *   <li><b>缓冲区尺寸单位错误</b>：{@code PdhEnumObjectItems} 的 size 参数是
 *       <b>TCHAR 个数</b>，而 JNA {@code Memory} 构造参数是<b>字节数</b>。
 *       原实现分配 65536 字节却声明 65536 TCHAR，把可用容量虚报了一倍 ——
 *       PDH 完全信任声明值，会写出超过实际缓冲区的数据。
 *       受控实验证实：给 64 字节缓冲、声明 69 TCHAR，{@code rc=0x0} 且 PDH 写出 138 字节
 *       （越界 74 字节）。本机真实需求仅 69 TCHAR，故此前未崩，属真实潜伏越界。
 *       现在改为「分配 chars*2 字节 + 声明 chars 个 TCHAR」。</li>
 *   <li><b>MULTI_SZ 只读出第 1 项</b>：实例表是 MULTI_SZ（项间单 NUL、整体双 NUL 结束），
 *       而 {@code Memory.getWideString(0)} <b>遇首个 NUL 即停</b>，
 *       于是"逐个具名实例求和"退化为"只读第 1 个适配器"。
 *       本机实测真实有 2 个实例（独显 + 核显），原实现只覆盖第 1 个 →
 *       多适配器机器会<b>低估</b>已用显存。现在改为按字节读取后手工走 MULTI_SZ
 *       （实测已能正确返回 2 个实例）。</li>
 * </ol>
 *
 * <p><b>已知局限</b>：{@code PDH_UNAVAILABLE} 是一次性永久熔断 —— 一旦某次探测失败就
 * 永久关闭该层（仅日志说明"PDH unavailable"）。为降低误熔断风险，
 * 只在 {@code ClassNotFoundException}（JNA 确实不在）时熔断，
 * 其余运行时异常允许按 {@link #FAILURE_RETRY_NANOS} 重试。</p>
 *
 * <p>为什么用反射而不是直接依赖 JNA：RoadWeaver 目前没有 JNA 依赖，直接引入会带来
 * 跨平台与版本冲突风险。反射调用使得「JNA 在就精确、不在就降级」，绝不因为缺少
 * native 库而崩溃。所有异常都被吞掉并永久关闭该层，只记录一次日志。</p>
 */
public final class GpuMemoryProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 探测结果的缓存有效期：显存占用是动态的，但没必要每次采样都查。 */
    private static final long CACHE_NANOS = 2_000_000_000L;   // 2 秒
    /** 读不到数据时的重试间隔（避免每 2 秒都去做一次注定失败的反射）。 */
    private static final long FAILURE_RETRY_NANOS = 30_000_000_000L;  // 30 秒

    private static final AtomicLong CACHED_USED_BYTES = new AtomicLong(-1L);
    private static final AtomicLong NEXT_PROBE_NANOS = new AtomicLong(0L);
    private static final AtomicBoolean PDH_UNAVAILABLE = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_SUCCESS = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_FALLBACK = new AtomicBoolean(false);
    /** 反射句柄只在首次成功时解析一次。 */
    private static volatile PdhReflection reflection;

    private GpuMemoryProbe() {}

    /** 探测层枚举，供日志与测试断言。 */
    public enum Layer {
        /** 第 1 层：PDH 实时读取成功。 */
        PDH_LIVE,
        /** 第 2 层：保守比例（总显存 x 70%）。 */
        CONSERVATIVE_RATIO,
        /** 第 3 层：读不到总显存，不干预。 */
        UNAVAILABLE
    }

    /**
     * 估算「可供 RoadWeaver 使用的显存字节数」。返回值 &lt;= 0 表示无法判断，
     * 调用方应沿用原逻辑。
     *
     * @param totalDeviceBytes 显卡总显存（来自 OpenCL {@code CL_DEVICE_GLOBAL_MEM_SIZE}），
     *                         &lt;= 0 表示未知
     * @param freeFraction     空闲部分允许使用的比例（如 0.70）
     * @param hardCapBytes     绝对上限（防止大显存卡单批过大，如 4GB）；&lt;= 0 表示不设上限
     */
    public static long budgetBytes(long totalDeviceBytes, double freeFraction, long hardCapBytes) {
        double fraction = clampFraction(freeFraction);
        long used = usedVramBytes();
        if (used >= 0L && totalDeviceBytes > 0L) {
            // 第 1 层：实时空闲 = 总显存 - 全卡已用（含游戏本体与其他 mod）。
            long free = Math.max(0L, totalDeviceBytes - used);
            long usable = (long) (free * fraction);
            if (LOGGED_SUCCESS.compareAndSet(false, true)) {
                LOGGER.info("【GPU显存探测结果与预算】 " + "RoadWeaver GPU memory probe: layer={} total={} MiB used={} MiB free={} MiB "
                                + "fraction={} budget={} MiB",
                        Layer.PDH_LIVE, totalDeviceBytes / 1048576L, used / 1048576L, free / 1048576L,
                        String.format("%.2f", fraction), clamp(usable, hardCapBytes) / 1048576L);
            }
            return clamp(usable, hardCapBytes);
        }
        if (totalDeviceBytes > 0L) {
            // 第 2 层：读不到实时占用，退回到"按总显存打折"的保守比例。
            if (LOGGED_FALLBACK.compareAndSet(false, true)) {
                LOGGER.info("【显存占用探测不可用，采用容量比例预算】 " + "RoadWeaver GPU memory probe: layer={} (PDH unavailable) total={} MiB fraction={}",
                        Layer.CONSERVATIVE_RATIO, totalDeviceBytes / 1048576L,
                        String.format("%.2f", fraction));
            }
            long usable = (long) (totalDeviceBytes * fraction);
            return clamp(usable, hardCapBytes);
        }
        // 第 3 层：连总显存都不知道，交给调用方沿用原逻辑。
        return 0L;
    }

    /**
     * 按配置计算预算：占比取 {@code performance.gpuBudgetFreeFraction}（默认 0.70），
     * 上限取 {@code performance.gpuBudgetHardCapMiB}（默认 4096）。
     *
     * <p>这是给各 GPU 预算消费点的统一入口 —— 读配置失败时回落到内置默认值，
     * 绝不因为配置层异常而中断采样。</p>
     *
     * @param totalDeviceBytes 显卡总显存；&lt;=0 表示未知
     * @return 可用字节数；&lt;=0 表示调用方应沿用原逻辑
     */
    public static long configBudgetBytes(long totalDeviceBytes) {
        double fraction = 0.70D;
        long hardCap = 4L * 1024L * 1024L * 1024L;
        try {
            var performance = net.shiroha233.roadweaver.config.ConfigService.get().performance();
            fraction = performance.gpuBudgetFreeFraction();
            hardCap = performance.gpuBudgetHardCapBytes();
        } catch (Throwable ignored) {
            // 配置不可用时沿用内置默认（0.70 / 4GiB）。
        }
        return budgetBytes(totalDeviceBytes, fraction, hardCap);
    }

    /**
     * 最近一次探测到的「全卡已用专用显存字节数」。
     *
     * @return &gt;=0 表示读到（0 是合法的"没占用"）；-1 表示不可用
     */
    public static long usedVramBytes() {
        if (PDH_UNAVAILABLE.get()) {
            return -1L;
        }
        long now = System.nanoTime();
        if (now < NEXT_PROBE_NANOS.get()) {
            return CACHED_USED_BYTES.get();
        }
        long probed = probePdhUsedBytes();
        CACHED_USED_BYTES.set(probed);
        NEXT_PROBE_NANOS.set(now + (probed >= 0L ? CACHE_NANOS : FAILURE_RETRY_NANOS));
        return probed;
    }

    /**
     * 经 JNA 反射，按实例名逐个读取 PDH 的
     * {@code \GPU Adapter Memory(<luid>)\Dedicated Usage} 并求和。
     *
     * @return 全卡已用专用显存字节数；失败返回 -1。
     *         0.8.342 起：仅 {@code ClassNotFoundException}（JNA/非 Windows）永久熔断，
     *         其余失败按 {@link #FAILURE_RETRY_NANOS} 重试。
     */
    private static long probePdhUsedBytes() {
        try {
            PdhReflection pdh = reflection;
            if (pdh == null) {
                pdh = PdhReflection.create();
                if (pdh == null) {
                    // 反射句柄解析失败（非 CNFE，例如类结构变化）→ 瞬时，可重试。
                    return -1L;
                }
                reflection = pdh;
            }
            long used = pdh.readDedicatedUsageBytes();
            if (used < 0L) {
                // 读到 -1 = 本次调用失败（如 PDH 实例暂时不存在），
                // 不永久熔断，交给 FAILURE_RETRY_NANOS 之后重试。
                return -1L;
            }
            return used;
        } catch (ClassNotFoundException jnaMissing) {
            // JNA 确实不在 classpath（或非 Windows）—— 这是**确定性**事实，永久关闭该层。
            PDH_UNAVAILABLE.set(true);
            LOGGER.info("【GPU显存探测永久停用，采用保守比例】 " + "RoadWeaver GPU memory probe permanently disabled ({}); falling back to conservative ratio",
                    jnaMissing.getClass().getSimpleName());
            return -1L;
        } catch (Throwable transientFailure) {
            // 0.8.342：其余瞬时失败（含 JNA 原生层偶发 Invalid memory access）**不再永久熔断**。
            // 独立审计观察到 PdhEnumObjectItems 在本环境存在非确定性崩溃（3 次运行 2 次抛
            // java.lang.Error: Invalid memory access，崩点漂移）；若一次性熔断，
            // 一次偶发失败就会让整个 PDH 层永久降级。现改为按 FAILURE_RETRY_NANOS 重试。
            LOGGER.info("【GPU显存探测暂时失败，稍后重试】 " + "RoadWeaver GPU memory probe transient failure ({}); will retry after {}s",
                    transientFailure.getClass().getSimpleName(), FAILURE_RETRY_NANOS / 1_000_000_000L);
            return -1L;
        }
    }

    private static double clampFraction(double fraction) {
        if (Double.isNaN(fraction) || fraction <= 0.0D) return 0.70D;
        return Math.min(1.0D, fraction);
    }

    private static long clamp(long value, long hardCapBytes) {
        long positive = Math.max(1L, value);
        return hardCapBytes > 0L ? Math.min(positive, hardCapBytes) : positive;
    }

    /** 仅供测试：清空探测缓存。 */
    static void resetForTest() {
        CACHED_USED_BYTES.set(-1L);
        NEXT_PROBE_NANOS.set(0L);
        PDH_UNAVAILABLE.set(false);
        LOGGED_SUCCESS.set(false);
        LOGGED_FALLBACK.set(false);
        reflection = null;
    }

    /**
     * JNA 反射句柄集合。所有成员都在 {@link #create()} 里一次性解析，
     * 之后的每次探测只是普通调用 —— 不再做 {@code getMethod}。
     */
    private static final class PdhReflection {
        private static final int PERF_DETAIL_WIZARD = 100;
        private static final int ENUM_BUFFER_CHARS = 65536;
        /** PDH_MORE_DATA (0x800007D2)：缓冲区不足，需按回写尺寸重试。 */
        private static final int PDH_MORE_DATA = 0x800007D2;

        private final Object pdhInstance;
        private final Method openQuery;
        private final Method addEnglishCounter;
        private final Method collectQueryData;
        private final Method getRawCounterValue;
        private final Method closeQuery;
        private final Method enumObjectItems;
        private final java.lang.reflect.Constructor<?> memoryCtor;
        private final java.lang.reflect.Constructor<?> dwordByRefCtor;
        private final java.lang.reflect.Constructor<?> handleByRefCtor;
        private final java.lang.reflect.Constructor<?> rawCounterCtor;
        private final java.lang.reflect.Constructor<?> dwordCtor;
        private final Method memoryGetWideString;
        private final Method memoryGetByteArray;
        private final Field rawFirstValue;
        private final Method handleByRefGetValue;
        private final Method dwordByRefSetValue;
        private final Method dwordByRefGetValue;
        private final Method dwordGetValue;

        private PdhReflection(Object pdhInstance,
                              Method openQuery,
                              Method addEnglishCounter,
                              Method collectQueryData,
                              Method getRawCounterValue,
                              Method closeQuery,
                              Method enumObjectItems,
                              java.lang.reflect.Constructor<?> memoryCtor,
                              java.lang.reflect.Constructor<?> dwordByRefCtor,
                              java.lang.reflect.Constructor<?> handleByRefCtor,
                              java.lang.reflect.Constructor<?> rawCounterCtor,
                              java.lang.reflect.Constructor<?> dwordCtor,
                              Method memoryGetWideString,
                              Method memoryGetByteArray,
                              Field rawFirstValue,
                              Method handleByRefGetValue,
                              Method dwordByRefSetValue,
                              Method dwordByRefGetValue,
                              Method dwordGetValue) {
            this.pdhInstance = pdhInstance;
            this.openQuery = openQuery;
            this.addEnglishCounter = addEnglishCounter;
            this.collectQueryData = collectQueryData;
            this.getRawCounterValue = getRawCounterValue;
            this.closeQuery = closeQuery;
            this.enumObjectItems = enumObjectItems;
            this.memoryCtor = memoryCtor;
            this.dwordByRefCtor = dwordByRefCtor;
            this.handleByRefCtor = handleByRefCtor;
            this.rawCounterCtor = rawCounterCtor;
            this.dwordCtor = dwordCtor;
            this.memoryGetWideString = memoryGetWideString;
            this.memoryGetByteArray = memoryGetByteArray;
            this.rawFirstValue = rawFirstValue;
            this.handleByRefGetValue = handleByRefGetValue;
            this.dwordByRefSetValue = dwordByRefSetValue;
            this.dwordByRefGetValue = dwordByRefGetValue;
            this.dwordGetValue = dwordGetValue;
        }

        /**
         * @return 可用句柄集合；JNA 不在 classpath（或非 Windows）时返回 {@code null}
         * @throws ClassNotFoundException JNA 确实不可用 —— 调用方据此**永久熔断**
         *         （确定性事实，重试无意义）
         */
        static PdhReflection create() throws ClassNotFoundException {
            try {
                Class<?> pdhClass = Class.forName("com.sun.jna.platform.win32.Pdh");
                Class<?> memoryClass = Class.forName("com.sun.jna.Memory");
                Class<?> dwordByRefClass = Class.forName("com.sun.jna.platform.win32.WinDef$DWORDByReference");
                Class<?> handleByRefClass = Class.forName("com.sun.jna.platform.win32.WinNT$HANDLEByReference");
                Class<?> rawCounterClass = Class.forName("com.sun.jna.platform.win32.Pdh$PDH_RAW_COUNTER");
                Object instance = pdhClass.getField("INSTANCE").get(null);
                if (instance == null) return null;
                Class<?> handleClass = Class.forName("com.sun.jna.platform.win32.WinNT$HANDLE");
                Class<?> pointerClass = Class.forName("com.sun.jna.Pointer");
                Class<?> dwordPtrClass = Class.forName("com.sun.jna.platform.win32.BaseTSD$DWORD_PTR");
                Class<?> dwordClass = Class.forName("com.sun.jna.platform.win32.WinDef$DWORD");
                return new PdhReflection(
                        instance,
                        pdhClass.getMethod("PdhOpenQuery", String.class, dwordPtrClass, handleByRefClass),
                        pdhClass.getMethod("PdhAddEnglishCounter", handleClass, String.class,
                                dwordPtrClass, handleByRefClass),
                        pdhClass.getMethod("PdhCollectQueryData", handleClass),
                        pdhClass.getMethod("PdhGetRawCounterValue", handleClass,
                                dwordByRefClass, rawCounterClass),
                        pdhClass.getMethod("PdhCloseQuery", handleClass),
                        pdhClass.getMethod("PdhEnumObjectItems", String.class, String.class, String.class,
                                pointerClass, dwordByRefClass, pointerClass, dwordByRefClass,
                                int.class, int.class),
                        memoryClass.getConstructor(long.class),
                        dwordByRefClass.getConstructor(),
                        handleByRefClass.getConstructor(),
                        rawCounterClass.getConstructor(),
                        dwordClass.getConstructor(long.class),
                        memoryClass.getMethod("getWideString", long.class),
                        memoryClass.getMethod("getByteArray", long.class, int.class),
                        rawCounterClass.getField("FirstValue"),
                        handleByRefClass.getMethod("getValue"),
                        dwordByRefClass.getMethod("setValue", dwordClass),
                        dwordByRefClass.getMethod("getValue"),
                        dwordClass.getMethod("intValue"));
            } catch (ClassNotFoundException jnaMissing) {
                // JNA 真的不在 classpath —— 向上抛，让调用方永久熔断。
                throw jnaMissing;
            } catch (Throwable incompatible) {
                // 类在但结构不符（例如 JNA 版本差异）—— 返回 null 表示"暂时用不了"，
                // 不永久熔断，留给后续重试。
                return null;
            }
        }

        /**
         * 枚举实例 → 逐个具名添加计数器 → 采集 → 读取求和 → 关闭查询。
         *
         * @return 已用字节数；读不到返回 -1
         */
        long readDedicatedUsageBytes() throws Exception {
            List<String> instances = enumerateAdapterInstances();
            if (instances.isEmpty()) return -1L;

            Object queryRef = handleByRefCtor.newInstance();
            // PdhOpenQuery(datasource=null, userData=null, queryHandleOut) —— 必须先建查询，
            // 后续 PdhAddEnglishCounter / PdhCollectQueryData 都挂在这个查询句柄上。
            int openRc = (Integer) openQuery.invoke(pdhInstance, null, null, queryRef);
            if (openRc != 0) return -1L;
            Object queryHandle = handleByRefGetValue.invoke(queryRef);
            if (queryHandle == null) return -1L;
            try {
                List<Object> counterRefs = new ArrayList<>();
                for (String instance : instances) {
                    Object counterRef = handleByRefCtor.newInstance();
                    int rc = (Integer) addEnglishCounter.invoke(pdhInstance, queryHandle,
                            "\\GPU Adapter Memory(" + instance + ")\\Dedicated Usage", null, counterRef);
                    if (rc == 0) counterRefs.add(counterRef);
                }
                if (counterRefs.isEmpty()) return -1L;

                collectQueryData.invoke(pdhInstance, queryHandle);

                long sum = 0L;
                boolean readAny = false;
                Object typeRef = dwordByRefCtor.newInstance();
                for (Object counterRef : counterRefs) {
                    Object counterHandle = handleByRefGetValue.invoke(counterRef);
                    if (counterHandle == null) continue;
                    Object raw = rawCounterCtor.newInstance();
                    int rc = (Integer) getRawCounterValue.invoke(pdhInstance, counterHandle, typeRef, raw);
                    if (rc != 0) continue;
                    long value = rawFirstValue.getLong(raw);
                    if (value >= 0L) {
                        sum += value;
                        readAny = true;
                    }
                }
                return readAny ? sum : -1L;
            } finally {
                try {
                    closeQuery.invoke(pdhInstance, queryHandle);
                } catch (Throwable ignored) {
                    // 查询句柄泄漏一次也不影响后续探测（每次探测都会新建）。
                }
            }
        }

        private List<String> enumerateAdapterInstances() throws Exception {
            // 单位注意：PdhEnumObjectItems 的 size 参数是 **TCHAR 个数**（wide char），
            // 而 JNA Memory 构造参数是 **字节数**。UTF-16LE 下 1 TCHAR = 2 字节，
            // 因此必须分配 chars*2 字节、同时声明 chars 个 TCHAR。
            // 0.8.342 修：原实现分配 65536 字节却声明 65536 TCHAR，
            // 把可用容量虚报了一倍 —— PDH 完全信任声明值，会写出超过实际缓冲区的数据。
            // 本机实测（20260911）：真实实例表仅需 69 TCHAR，所以此前未触发崩溃，
            // 但这是真实的潜伏越界，必须修掉。
            long instanceBytes = (long) ENUM_BUFFER_CHARS * 2L;
            Object nameBuffer = memoryCtor.newInstance(instanceBytes);
            Object instanceBuffer = memoryCtor.newInstance(instanceBytes);
            Object nameSize = dwordByRefCtor.newInstance();
            Object instanceSize = dwordByRefCtor.newInstance();
            setDword(nameSize, ENUM_BUFFER_CHARS);
            setDword(instanceSize, ENUM_BUFFER_CHARS);
            int rc = (Integer) enumObjectItems.invoke(pdhInstance, null, null, "GPU Adapter Memory",
                    nameBuffer, nameSize, instanceBuffer, instanceSize,
                    PERF_DETAIL_WIZARD, 0);
            if (rc == PDH_MORE_DATA) {
                // 缓冲区不够大：按回写的所需 TCHAR 数重试一次。
                int neededChars = getDword(instanceSize);
                if (neededChars <= ENUM_BUFFER_CHARS) {
                    // 尺寸没有增长说明不是"缓冲太小"，避免死循环。
                    return List.of();
                }
                long neededBytes = (long) neededChars * 2L;
                instanceBuffer = memoryCtor.newInstance(neededBytes);
                nameBuffer = memoryCtor.newInstance(neededBytes);
                setDword(nameSize, neededChars);
                setDword(instanceSize, neededChars);
                rc = (Integer) enumObjectItems.invoke(pdhInstance, null, null, "GPU Adapter Memory",
                        nameBuffer, nameSize, instanceBuffer, instanceSize,
                        PERF_DETAIL_WIZARD, 0);
            }
            if (rc != 0) return List.of();

            // 0.8.342 修：PDH 返回的实例列表是 **MULTI_SZ**（多项以单个 NUL 分隔、
            // 整体以双 NUL 结束）。原实现用 `Memory.getWideString(0)` 读取 ——
            // 该 API 遇到**第一个 NUL 就停**，所以永远只拿到第 1 个实例名，
            // 使"逐个具名实例求和"退化成"只读第 1 个适配器"。
            // 本机实测（20260911）真实有 2 个实例（独显 + 核显），
            // 声明里"逐个实例求和"当时并不成立；多适配器机器会低估已用显存。
            // 正确做法：按字节读取整段，再按 UTF-16LE 手工走 MULTI_SZ。
            int charsToRead = getDword(instanceSize);
            if (charsToRead <= 0) charsToRead = ENUM_BUFFER_CHARS;
            byte[] raw = (byte[]) memoryGetByteArray.invoke(instanceBuffer, 0L, charsToRead * 2);
            List<String> instances = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int i = 0; i + 1 < raw.length; i += 2) {
                char c = (char) ((raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8));
                if (c == 0) {
                    if (current.length() == 0) {
                        // 空项 = MULTI_SZ 的结束标记（双 NUL）。列表已完整。
                        break;
                    }
                    instances.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            if (!current.isEmpty()) instances.add(current.toString());
            if (!instances.isEmpty()) return instances;

            // 兜底：万一按字节解析失败，退回旧的 getWideString 读法（至少拿到第 1 个），
            // 保证不会因为解析方式变更而把"读得到"变成"读不到"。
            String single = (String) memoryGetWideString.invoke(instanceBuffer, 0L);
            if (single == null || single.isEmpty()) return List.of();
            for (String part : single.split("\u0000")) {
                if (part != null && !part.isEmpty()) instances.add(part);
            }
            return instances;
        }

        private void setDword(Object dwordByRef, int value) throws Exception {
            dwordByRefSetValue.invoke(dwordByRef,
                    dwordCtor.newInstance(Long.valueOf(value)));
        }

        private int getDword(Object dwordByRef) throws Exception {
            Object dword = dwordByRefGetValue.invoke(dwordByRef);
            return ((Number) dwordGetValue.invoke(dword)).intValue();
        }
    }
}
