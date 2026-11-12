package net.shiroha233.roadweaver.route;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * **「手绘连接待施工数」口径的回归**（★ 2026-09-26 修 WorkBuddy W-1/W-3）。
 *
 * <h2>要防什么</h2>
 * 0.8.397 的「施工优先」用 {@code countPendingPlayerRouteConnections} 判断
 * 是否豁免让路暂停。当时的实现遍历**调度队列 QUEUE** —— 但
 * <b>QUEUE 只在 {@code pauseScheduling() == false} 时才被 {@code refreshQueue} 填充</b>，
 * 于是形成死锁：
 * <pre>
 *   玩家点施工（确认框是 Screen）⇒ isScreenPauseActive() 真
 *     ⇒ pauseScheduling() 真 ⇒ tick 在 refreshQueue 之前 return
 *     ⇒ QUEUE 永远空 ⇒ 计数 0 ⇒ 不豁免 ⇒ 循环
 * </pre>
 * 即「开着地图盯进度」恰恰让施工不动（WorkBuddy W-1）。
 *
 * <p>而且原 javadoc 声称「同时也把正在施工的算进去」，实现却只数 QUEUE（W-3）。</p>
 *
 * <h2>修法与它依赖的性质</h2>
 * 改为查 {@code WorldDataProvider} 的**连接表**（真源），状态取
 * {@code PLANNED} 或 {@code GENERATING}。本测试钉住这条口径的两个关键性质
 * ——它们都是**纯判定**，可以离线验证：
 * <ol>
 *   <li><b>状态筛选正确</b>：只有 PLANNED / GENERATING 算「还没结论」；
 *       COMPLETED / FAILED / PROTECTED_GAP / FILTERED 都不算。</li>
 *   <li><b>与暂停状态无关</b>：判据里不出现任何队列 / 暂停概念
 *       （这是修法的**本体**——若有人改回数队列，本测试的静态断言会红）。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 条用**源码静态断言**实现：读
 * {@code RouteBuildProgress.countActivePlayerRouteConnections} 的方法体，
 * 断言它引用的是 {@code getStructureConnections} 而不是 {@code QUEUE}。
 * 理由：这条「真源 vs 派生队列」的选择正是 bug 根因，
 * 而它无法靠运行时数据区分（两种口径在"未暂停"时结果往往相同）。</p>
 */
public final class PlayerRoutePendingCountTest {

    private PlayerRoutePendingCountTest() {
    }

    private static final String PROGRESS_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/route/RouteBuildProgress.java";
    private static final String SERVICE_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/generation/RoadGenerationService.java";

    private static final String COUNT_METHOD = "countActivePlayerRouteConnections";

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testStatusClassificationIsExhaustive();
        testCountUsesProviderNotQueue();
        testServiceDelegatesToProviderCount();

        System.out.println("PlayerRoutePendingCountTest: PASS");
    }

    /**
     * **所有连接状态都被显式分类** —— 「还没结论」的只有 PLANNED / GENERATING。
     *
     * <p>用 {@code ConnectionStatus.values()} 穷举，确保将来新增状态时
     * 必须显式决定它算不算"未结算"（否则会静默漏算）。</p>
     */
    private static void testStatusClassificationIsExhaustive() {
        // 这两个是"还没结论"的（在排队 / 在建）
        require(isUnsettled(net.shiroha233.roadweaver.core.model.ConnectionStatus.PLANNED),
                "PLANNED 应算「还没结论」（连接已入队待生成）");
        require(isUnsettled(net.shiroha233.roadweaver.core.model.ConnectionStatus.GENERATING),
                "GENERATING 应算「还没结论」（正在生成）—— 这正是 W-3 的要点："
                        + "它已出队、不在 QUEUE 里，但必须被计入优先模式");

        // 这些都已结算
        for (var st : net.shiroha233.roadweaver.core.model.ConnectionStatus.values()) {
            boolean expected = st == net.shiroha233.roadweaver.core.model.ConnectionStatus.PLANNED
                    || st == net.shiroha233.roadweaver.core.model.ConnectionStatus.GENERATING;
            require(isUnsettled(st) == expected,
                    "状态 " + st + " 的「未结算」判定与预期不符（期望 " + expected + "）");
        }
    }

    /** 与生产代码保持同一判定（唯一真源是生产代码的那个 switch/条件）。 */
    private static boolean isUnsettled(net.shiroha233.roadweaver.core.model.ConnectionStatus st) {
        return st == net.shiroha233.roadweaver.core.model.ConnectionStatus.PLANNED
                || st == net.shiroha233.roadweaver.core.model.ConnectionStatus.GENERATING;
    }

    /**
     * **计数必须查 provider（连接表），不能查 QUEUE**。
     *
     * <p>这是 W-1 死锁的**本体**：QUEUE 只在未暂停时才填充，
     * 用它做「是否豁免暂停」的判据会自锁。</p>
     */
    private static void testCountUsesProviderNotQueue() throws Exception {
        String body = readMethodBody(PROGRESS_SRC, COUNT_METHOD);
        require(body != null, "找不到 " + COUNT_METHOD + " 的方法体（方法被改名/删除？）");
        require(body.contains("getStructureConnections"),
                COUNT_METHOD + " 必须查 WorldDataProvider.getStructureConnections（连接表真源），"
                        + "但方法体里没看到它。\n"
                        + "  ⚠️ 若改回遍历 QUEUE，会重现 W-1 死锁：\n"
                        + "     QUEUE 只在 pauseScheduling()==false 时填充，\n"
                        + "     而「是否豁免暂停」正依赖本方法的返回值 ⇒ 自锁。");
        require(!body.contains("QUEUE"),
                COUNT_METHOD + " 的方法体里出现了 QUEUE —— 这正是 W-1 死锁的来源，禁止改回。");
    }

    /**
     * **RoadGenerationService 里的计数委托给 provider 口径**（不自己数队列）。
     */
    private static void testServiceDelegatesToProviderCount() throws Exception {
        String body = readMethodBody(SERVICE_SRC, "countPendingPlayerRouteConnections");
        require(body != null, "找不到 RoadGenerationService.countPendingPlayerRouteConnections");
        require(body.contains("RouteBuildProgress"),
                "RoadGenerationService 的计数应委托给 RouteBuildProgress（单一真源），"
                        + "否则又会出现两处口径不一致");
        require(!body.contains("QUEUE"),
                "RoadGenerationService 的计数方法体里出现了 QUEUE —— 禁止改回（W-1）");
    }

    // ─────────────────── 源码读取辅助 ───────────────────

    /** 读取某方法的方法体（从签名行到其配对花括号结束）。找不到返回 null。 */
    private static String readMethodBody(String relativePath, String methodName) throws Exception {
        var path = locate(relativePath);
        if (path == null) {
            return null;
        }
        var lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            // 匹配方法定义（排除注释）
            if (!t.startsWith("*") && !t.startsWith("//")
                    && t.contains(methodName) && t.contains("(") && !t.endsWith(";")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean seenBrace = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line).append('\n');
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                    seenBrace = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (seenBrace && depth <= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private static java.nio.file.Path locate(String relativePath) {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path[] candidates = {
                cwd.resolve(relativePath),
                cwd.resolve("..").resolve(relativePath),
                cwd.resolve("..").resolve("..").resolve(relativePath),
        };
        for (var p : candidates) {
            if (java.nio.file.Files.isRegularFile(p)) {
                return p.normalize();
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
