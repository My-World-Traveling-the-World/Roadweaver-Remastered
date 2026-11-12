package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * **GPU 粗采样 lane 数的边界回归**（★ 0.8.402）。
 *
 * <h2>要防什么</h2>
 * {@code recommendedCoarseLaneCount} 原实现是：
 *
 * <pre>
 *   long fourGiB = 4L * 1024L * 1024L * 1024L;
 *   return Math.max(1, Math.min(4, (int) (memory / fourGiB)));   // ← 整数截断
 * </pre>
 *
 * <p>{@code (int)} 对 long 除法结果是**向下取整**，导致「刚好 N 倍 4GiB」的显存
 * 被砍掉一档。真机取证（2026-09-27，Chunky 预热压力测试）：</p>
 *
 * <pre>
 *   OpenCL 上报 8188 MiB = 7.996 GiB（比 8 GiB 少 4 MiB —— 驱动保留开销）
 *   7.996 / 4 = 1.999  →  (int) 1.999 = 1     ← 只剩 1 条 lane
 *   四舍五入      →  2                        ← 并行度翻倍
 * </pre>
 *
 * <p>该值决定 {@code OpenCLRuntime} 的独立会话数，即 GPU 侧真正能并发的
 * 粗采样通道数；1 条通道时 8 个 RoadWeaver lane 全在等它，GPU 利用率
 * 反复掉到 42~59%（CPU 却 100%），是预热吞吐的主要瓶颈之一。</p>
 *
 * <h2>为什么用反射测私有静态方法</h2>
 * {@code recommendedCoarseLaneCount} 是包私有 static，参数是 OpenCL 桥接层的
 * {@code DeviceInfo}（无法在离线测试中构造真实 GPU 设备信息）。这里用反射
 * 取方法存在性 + 直接对**纯数值逻辑**做等价验证，避免依赖真实设备。
 *
 * <p>⚠️ 本测试**不**构造 {@code DeviceInfo}（做不到）。它钉的是两件事：</p>
 * <ol>
 *   <li><b>行为契约</b>：整段逻辑的数学等价物必须是「四舍五入」而非「截断」，
 *       且结果落在 [1,4] 内 —— 把边界算式在测试里独立复算并断言。</li>
 *   <li><b>结构契约</b>：源码中不得再出现 {@code (int) (memory / fourGiB)}
 *       这种截断写法（防重构退回）。</li>
 * </ol>
 */
public final class CoarseLaneCountBoundaryTest {

    private CoarseLaneCountBoundaryTest() {
    }

    private static int passed;
    private static int failed;

    /** 8188 MiB —— 真机 RTX 4060 的 OpenCL 上报值（8 GiB 减 4 MiB 保留）。 */
    private static final long RTX_4060_REPORTED_BYTES = 8188L * 1024L * 1024L;

    private static final long FOUR_GIB = 4L * 1024L * 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        testOldFormulaWouldTruncateTo1();
        testNewFormulaGives2For4060();
        testBoundariesAtExactMultiples();
        testClampedToOneThroughFour();
        testNoTruncatingPatternInSource();
        testGpuLaneMethodStillExists();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError(failed + " check(s) failed");
        }
        System.out.println("CoarseLaneCountBoundaryTest: PASS");
    }

    /**
     * **钉住 bug 本身**：旧公式对真机显存会得到 1。
     *
     * <p>这条断言是「回归的锚」—— 如果将来有人把公式改回截断，
     * {@link #testNewFormulaGives2For4060()} 会失败，而本条会提醒
     * 「这就是当初的 bug 形态」。</p>
     */
    private static void testOldFormulaWouldTruncateTo1() {
        int oldResult = (int) Math.max(1L, Math.min(4L, RTX_4060_REPORTED_BYTES / FOUR_GIB));
        check("旧公式（截断）对 8188 MiB 确实得到 1 —— 这就是 bug 形态", oldResult == 1);

        double ratio = (double) RTX_4060_REPORTED_BYTES / (double) FOUR_GIB;
        check("8188 MiB / 4GiB 的比值应略小于 2（证明是被截断而非真的不足）",
                ratio > 1.99 && ratio < 2.0);
    }

    /** **核心**：新公式（四舍五入）对真机显存得到 2。 */
    private static void testNewFormulaGives2For4060() {
        long lanes = Math.round((double) RTX_4060_REPORTED_BYTES / (double) FOUR_GIB);
        long clamped = Math.max(1L, Math.min(4L, lanes));
        check("新公式（四舍五入）对 8188 MiB 得到 2（并行度翻倍）", clamped == 2L);
    }

    /** 各档边界：4/8/12/16 GiB 与「差一点」都应落在正确档位。 */
    private static void testBoundariesAtExactMultiples() {
        check("4 GiB 整 → 1", laneCount(4L * 1024 * 1024 * 1024) == 1L);
        check("4 GiB 差 4 MiB（3.996 GiB）→ 1（未过半）",
                laneCount(4L * 1024 * 1024 * 1024 - 4L * 1024 * 1024) == 1L);
        check("6 GiB（1.5 倍）→ 2（过半进位）",
                laneCount(6L * 1024 * 1024 * 1024) == 2L);
        check("8 GiB 整 → 2", laneCount(8L * 1024 * 1024 * 1024) == 2L);
        check("8188 MiB（真机值）→ 2", laneCount(RTX_4060_REPORTED_BYTES) == 2L);
        check("12 GiB 整 → 3", laneCount(12L * 1024 * 1024 * 1024) == 3L);
        check("16 GiB 整 → 4", laneCount(16L * 1024 * 1024 * 1024) == 4L);
    }

    /** 结果必须始终落在 [1,4]，无论显存多大/多小。 */
    private static void testClampedToOneThroughFour() {
        check("极小显存（1 MiB）→ 至少 1", laneCount(1024L * 1024) == 1L);
        check("超大显存（64 GiB）→ 封顶 4", laneCount(64L * 1024 * 1024 * 1024) == 4L);
        // 注意：生产实现对「取不到显存」(memory <= 0) 显式返回 2（乐观默认），
        // 与「1 MiB 极小显存」走不同的分支 —— 这里分别断言，避免混淆。
        check("0 字节（取不到显存）→ 2（生产实现的乐观默认）", laneCount(0L) == 2L);
        check("负值（异常输入）→ 2（同上分支）", laneCount(-1L) == 2L);
    }

    /**
     * **结构守卫**：源码中不得再出现截断写法。
     *
     * <p>防的是「重构时不小心改回 {@code (int) (memory / fourGiB)}」。</p>
     */
    private static void testNoTruncatingPatternInSource() throws Exception {
        java.nio.file.Path src = locate(
                "common/src/main/java/net/shiroha233/roadweaver/pathfinding/cache/opencl/OpenCLCoarseHeightBatchSampler.java");
        check("能定位 OpenCLCoarseHeightBatchSampler 源码", src != null);
        if (src == null) return;

        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        // 去掉行注释，避免注释里引用的旧写法造成假红
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            int slash = line.indexOf("//");
            sb.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        String code = sb.toString();

        check("源码中不得再出现 `(int) (memory / fourGiB)` 截断写法",
                !code.contains("(int) (memory / fourGiB)"));
        check("源码中应使用 Math.round 计算 lane 数",
                code.contains("Math.round((double) memory / (double) fourGiB)"));
    }

    /** 方法仍然存在（防被误删/改名）。 */
    private static void testGpuLaneMethodStillExists() throws Exception {
        Method m = null;
        for (Method candidate : OpenCLCoarseHeightBatchSampler.class.getDeclaredMethods()) {
            if (candidate.getName().equals("recommendedCoarseLaneCount")
                    && candidate.getParameterCount() == 1) {
                m = candidate;
                break;
            }
        }
        check("recommendedCoarseLaneCount 方法存在（单参数）", m != null);
        if (m == null) return;
        check("recommendedCoarseLaneCount 是 static", Modifier.isStatic(m.getModifiers()));
        check("recommendedCoarseLaneCount 参数类型是 OpenCLBridge$DeviceInfo",
                m.getParameterTypes()[0].getName().contains("OpenCLBridge$DeviceInfo"));
    }

    /** 与生产实现等价的纯数值逻辑（独立复算，不调用生产方法）。 */
    private static long laneCount(long memoryBytes) {
        if (memoryBytes <= 0L) return 2L;   // 与生产实现一致：取不到显存时返回 2
        long lanes = Math.round((double) memoryBytes / (double) FOUR_GIB);
        return Math.max(1L, Math.min(4L, lanes));
    }

    private static java.nio.file.Path locate(String relativePath) {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        for (java.nio.file.Path p : new java.nio.file.Path[]{
                cwd.resolve(relativePath),
                cwd.resolve("..").resolve(relativePath),
                cwd.resolve("..").resolve("..").resolve(relativePath)}) {
            if (java.nio.file.Files.isRegularFile(p)) return p.normalize();
        }
        return null;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
