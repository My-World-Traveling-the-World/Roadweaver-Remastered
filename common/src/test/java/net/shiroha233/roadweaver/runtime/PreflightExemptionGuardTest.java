package net.shiroha233.roadweaver.runtime;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * **Chunky 预热期「不得被压力限速」的静态守卫**（★ 0.8.401）。
 *
 * <h2>要防什么</h2>
 * 「预热期豁免压力、全力加载」这个不变量**已被反复破坏三次**，每次形态不同：
 * <table border="1">
 *   <caption>历史</caption>
 *   <tr><th>版本</th><th>形态</th></tr>
 *   <tr><td>0.8.372</td>
 *       <td>预热期无条件开满 12 lanes → 抢空生成 worker、反而更慢（实测 completed=0 卡 3 分钟）</td></tr>
 *   <tr><td>0.8.373</td>
 *       <td>{@code readyAhead} 导致双方互锁（Chunky 等我规划 / 我又因"够用了"限速）</td></tr>
 *   <tr><td>0.8.399 及之前</td>
 *       <td><b>预热分支根本不存在</b>：{@code recommendedRoadWorkerThreads} 一路落到玩家移动 /
 *           界面分支，并被 {@code clampGameplayThreadCap}（默认 2）钳住 ——
 *           真机 worker capacity <b>只在 1↔2 之间振荡，从未到过 3+</b></td></tr>
 * </table>
 *
 * <p>三者根因同类：<b>预热期的「有压力」是必然且预期的（CPU/IO 满负荷干活），
 * 而系统却用压力去反向压制劳动者</b>，形成负反馈自杀。</p>
 *
 * <h2>为什么必须是静态守卫而不是行为测试</h2>
 * 这两处决策依赖 {@code ServerLevel}、反射探测（{@code CRUISE_ACTIVE} /
 * {@code CHUNKY_ACTIVE} 等）与运行时配置，<b>离线无法构造</b>。
 * 而缺陷的本质是<b>分支顺序</b>——"豁免必须排在其他分支之前"。
 * 顺序错了，代码看起来完全正常、也编译得过。所以这里读源码文本钉住顺序。
 *
 * <h2>本测试钉住什么</h2>
 * <ol>
 *   <li><b>worker：预热分支必须存在，且排在 {@code CRUISE_ACTIVE} 之前</b>
 *       —— 它是本方法的分支起点，排在它之前就等于能绕过它。</li>
 *   <li><b>worker：预热分支不得经过 {@code clampGameplayThreadCap}</b>
 *       —— 这正是"最好也只有 2"的直接原因。</li>
 *   <li><b>lanes：预热分支必须存在，且不得再咨询 {@code ROAD_COORDINATOR_LANES}</b>
 *       —— 后者无条件把 critical/pressured 映射为 1，且完全不看预热标志。</li>
 *   <li><b>lanes：必须保留 {@code heapPressureLaneCap} 作内存保险丝</b>
 *       —— 去掉压力咨询后它是唯一的防崩服闸门。</li>
 * </ol>
 */
public final class PreflightExemptionGuardTest {

    private PreflightExemptionGuardTest() {
    }

    private static final String POLICY_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/runtime/RuntimeLoadPolicy.java";

    private static final String WORKER_METHOD = "public static int recommendedRoadWorkerThreads";
    private static final String LANES_METHOD = "public static int recommendedCoordinatorLanes";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testWorkerHasPreflightBranchBeforeCruise();
        testWorkerPreflightBypassesGameplayCap();
        testLanesPreflightDoesNotConsultChunkyAutogen();
        testLanesPreflightKeepsHeapFuse();
        testPreflightGuardNotRegressedToOldForm();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError(failed + " check(s) failed");
        }
        System.out.println("PreflightExemptionGuardTest: PASS");
    }

    /**
     * worker 的预热分支必须存在，且排在 {@code CRUISE_ACTIVE} 之前。
     *
     * <p>{@code CRUISE_ACTIVE} 原本是本方法的第一个分支（会覆盖 Chunky 路径），
     * 因此只要预热分支排在它之前，就意味着"不会被任何守卫抢先返回"。</p>
     */
    private static void testWorkerHasPreflightBranchBeforeCruise() throws Exception {
        String body = readMethodBody(POLICY_SRC, WORKER_METHOD);
        check("能找到 recommendedRoadWorkerThreads 方法体", body != null);
        if (body == null) return;

        int preflight = body.indexOf("chunkyPreflightActive");
        int cruise = body.indexOf("CRUISE_ACTIVE.invoke()");

        check("worker 方法内出现 chunkyPreflightActive（预热分支存在）", preflight >= 0);
        check("worker 方法内出现 CRUISE_ACTIVE 分支", cruise >= 0);

        if (preflight >= 0 && cruise >= 0) {
            check("预热分支排在 CRUISE_ACTIVE **之前**（否则可被抢先前置返回绕过）",
                    preflight < cruise);
        }

        // 必须真的 return，而不是只判断没动作
        check("预热分支内有 return（不是空判断）", body.contains("return"));
    }

    /**
     * **预热分支不得经过 {@code clampGameplayThreadCap}**。
     *
     * <p>这是"零压力时预热也只能到 2"的直接原因：该方法把值钳到
     * {@code gameplayThreadCap}（默认 2）。预热段一旦调用它，豁免就失效。</p>
     */
    private static void testWorkerPreflightBypassesGameplayCap() throws Exception {
        String body = readMethodBody(POLICY_SRC, WORKER_METHOD);
        if (body == null) {
            check("方法体可读", false);
            return;
        }

        int preflightIdx = body.indexOf("chunkyPreflightActive");
        check("预热分支存在", preflightIdx >= 0);
        if (preflightIdx < 0) return;

        String preflightSegment = extractPreflightBranch(body);
        check("预热分支体可提取", !preflightSegment.isEmpty());

        check("预热分支的 return 语句**不含** clampGameplayThreadCap"
                        + "（含则被钳到 gameplayThreadCap=2，豁免失效）",
                !preflightSegment.contains("clampGameplayThreadCap"));

        // 反过来：必须能给出高于 2 的值，否则"豁免"没有意义
        check("预热分支给出了上限 8（而非固定 2）",
                preflightSegment.contains("8"));
    }

    /**
     * lanes 的预热分支必须存在，且**不得再咨询 {@code ROAD_COORDINATOR_LANES}**。
     *
     * <p>chunky-autogen 的 {@code recommendedRoadPlanningLanes} 无条件把
     * {@code critical / pressured} 映射为 1，且**完全不引用预热标志**。
     * 预热期「有压力」是必然的 ⇒ 咨询它必然得到 1 ⇒ 3190 个 region 永远做不完。</p>
     */
    private static void testLanesPreflightDoesNotConsultChunkyAutogen() throws Exception {
        String body = readMethodBody(POLICY_SRC, LANES_METHOD);
        check("能找到 recommendedCoordinatorLanes 方法体", body != null);
        if (body == null) return;

        int preflightIdx = body.indexOf("chunkyPreflightActive");
        check("lanes 预热分支存在", preflightIdx >= 0);
        if (preflightIdx < 0) return;

        String segment = extractPreflightBranch(body);
        check("lanes 预热分支体可提取", !segment.isEmpty());

        check("lanes 预热分支**不再咨询** ROAD_COORDINATOR_LANES"
                        + "（它会无条件把 critical/pressured 压成 1，导致 3190 个 region 做不完）",
                !segment.contains("ROAD_COORDINATOR_LANES"));

        check("lanes 预热分支仍是自决的（直接计算并 return）",
                segment.contains("return"));
    }

    /**
     * lanes 预热分支必须保留 {@code heapPressureLaneCap}。
     *
     * <p>删掉压力咨询后，它是**唯一**的防崩服闸门。
     * 真机事故：905 region 一次性入队 → 堆贴顶 12GB → 11 处 {@code OutOfMemoryError} 崩服。</p>
     */
    private static void testLanesPreflightKeepsHeapFuse() throws Exception {
        String body = readMethodBody(POLICY_SRC, LANES_METHOD);
        if (body == null) return;

        String segment = extractPreflightBranch(body);
        check("lanes 预热分支体可提取", !segment.isEmpty());
        check("lanes 预热分支保留 heapPressureLaneCap（防堆爆崩服的保险丝）",
                segment.contains("heapPressureLaneCap"));
    }

    /**
     * 反向守卫：确认**没有**退回旧形态。
     *
     * <p>旧的 lanes 预热分支形如：
     * {@code Math.min(heapCap, Math.min(8, preflightRecommendation))} ——
     * 它把最终决定权交回 chunky-autogen。本测试钉住这种写法不得复现。</p>
     */
    private static void testPreflightGuardNotRegressedToOldForm() throws Exception {
        String body = readMethodBody(POLICY_SRC, LANES_METHOD);
        if (body == null) return;

        check("不得再出现 preflightRecommendation（旧形态的中间变量）",
                !body.contains("preflightRecommendation"));
    }

    // ─────────────────── 源码读取辅助 ───────────────────

    /**
     * 截出「预热分支自己的那段代码」——从 {@code chunkyPreflightActive} 所在的
     * {@code if} 开始，到它配对的右花括号结束。
     *
     * <p>⚠️ 早先版本用 {@code indexOf("return")} 截断，把 {@code 8} 排除在切片之外，
     * 导致**假红**。这里改为按花括号配对，取完整分支体。</p>
     */
    private static String extractPreflightBranch(String body) {
        int idx = body.indexOf("chunkyPreflightActive");
        if (idx < 0) return "";
        int braceStart = body.indexOf('{', idx);
        if (braceStart < 0) return body.substring(idx);
        int depth = 0;
        for (int i = braceStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return body.substring(idx, i + 1);
                }
            }
        }
        return body.substring(idx);
    }

    /** 读取某方法的方法体（从签名行到其配对花括号结束）。找不到返回 null。 */
    private static String readMethodBody(String relativePath, String methodMarker) throws Exception {
        Path path = locate(relativePath);
        if (path == null) {
            // 2026-11-15：原来这里 return null，而 6 个调用点都不判空 ⇒ 等到
            // body.indexOf(...) 才抛 NPE，报错是「Cannot invoke String.indexOf because body is null」，
            // 完全看不出是「源码文件找不到」还是「方法被改名」。这里直接给出可读原因。
            throw new IllegalStateException("守卫需要读取的源码不存在：" + relativePath
                    + " —— 若文件被移动请更新本守卫；当前工作目录 "
                    + Path.of("").toAbsolutePath());
        }
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (!t.startsWith("*") && !t.startsWith("//")
                    && t.contains(methodMarker) && t.contains("(") && !t.endsWith(";")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            // 同上：方法被改名/删除时给明确原因，而不是让调用方 NPE。
            throw new IllegalStateException("在 " + relativePath + " 里找不到方法体 "
                    + methodMarker + " —— 方法被改名或删除了？请同步更新本守卫");
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean seen = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            // 去掉行注释，避免注释里的示例代码污染判定
            int slash = line.indexOf("//");
            String effective = slash >= 0 ? line.substring(0, slash) : line;
            sb.append(effective).append('\n');
            for (char c : effective.toCharArray()) {
                if (c == '{') {
                    depth++;
                    seen = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (seen && depth <= 0) break;
        }
        return sb.toString();
    }

    private static Path locate(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve(relativePath),
                cwd.resolve("..").resolve(relativePath),
                cwd.resolve("..").resolve("..").resolve(relativePath),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p.normalize();
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

    static {
        // 避免未使用告警（Arrays 保留以示意可扩展）
        Arrays.asList().size();
    }
}
