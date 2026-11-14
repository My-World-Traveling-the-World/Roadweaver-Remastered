package net.shiroha233.roadweaver.route;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * **预加载兜底链路的静态守卫**（★ 0.8.400）。
 *
 * <h2>要防什么</h2>
 * 「手绘路线提交后强制加载区块」在**同一处连出过三次 P1**：
 * <ol>
 *   <li><b>0.8.391</b> 过早预加载 ⇒ 拿到空计划 ⇒ 区块被永久标成"无需施工"。</li>
 *   <li><b>0.8.392</b> 等待上限永不可达（失败连接不触发）⇒ 永不预加载。</li>
 *   <li><b>0.8.399 及之前</b> 唯一触发点埋在一条 <b>4 跳异步链</b>里
 *       （worker → 主线程取 routeFor → snap future → whenComplete），
 *       最后一跳 {@code executeOnMainThread(level, epoch, ...)} 会在
 *       {@code ThreadPoolManager.isEpoch(epoch)} 失效时<b>静默丢弃</b>整个动作。</li>
 * </ol>
 *
 * <p>第三次的真机取证（2026-09-26）：路确实建好了
 * （{@code applied player-route overrides} ×4），但<b>全日志 0 条
 * 「手绘路线预加载开始」</b> —— 强制加载一次都没跑，玩家走过去仍是空地。</p>
 *
 * <h2>0.8.400 的修法</h2>
 * 不再依赖那条链：<b>提交时登记待办意图，由服务端 tick 驱动的
 * {@code RouteChunkPreloader.drainPending} 观察到"连接全部结算"后强制执行</b>。
 * 不碰 future、不碰 epoch —— 那条链无论哪里断掉，兜底都照样生效。
 *
 * <h2>本测试钉住什么</h2>
 * 修法由两条**容易在重构中被悄悄破坏**的架构事实支撑，且它们都无法靠
 * 纯运行时数据区分（错了照样"看起来能跑"）：
 * <ol>
 *   <li><b>提交必须登记待办</b>：{@code RouteBuildService.submit} 里必须有
 *       {@code enqueuePending} 调用。若被删掉，兜底永远不会被唤醒，
 *       且没有任何既有测试会红（这正是第三次翻车的形态）。</li>
 *   <li><b>兜底检测必须在暂停 return 之前</b>：{@code RoadGenerationService.tick}
 *       里 {@code drainPending} 必须出现在 {@code pauseScheduling()} 的
 *       {@code return} <b>之前</b>。否则玩家开着地图盯进度时兜底永不执行 ——
 *       与 WorkBuddy W-1「用暂停状态挡住本该推进的玩家操作」是同一个死锁形状。</li>
 *   <li><b>节流必须是 0.5 秒</b>：用户明确要求「改成 0.5 秒，不要给太多性能压力」。
 *       钉住 {@code DRAIN_INTERVAL_TICKS == 10}（1 tick = 50ms ⇒ 10 tick = 0.5s），
 *       防止有人图省事改回每 tick。</li>
 * </ol>
 */
public final class RoutePreloadFallbackGuardTest {

    private RoutePreloadFallbackGuardTest() {
    }

    private static final String PRELOADER_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/route/RouteChunkPreloader.java";
    private static final String SERVICE_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/route/RouteBuildService.java";
    // 2026-11-15：调度的那个 tick 在重构时从 RoadGenerationService 搬到了 RoadTaskExecutor
    // （RoadGenerationService 里已经连 tick 方法都没有了）。架构事实没变：
    // drainPending 必须仍在「pauseScheduling() 就 return」之前跑。
    private static final String TICK_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/generation/RoadTaskExecutor.java";

    /** 用户要求的检测间隔：0.5 秒。 */
    private static final long EXPECTED_INTERVAL_TICKS = 10L;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testSubmitEnqueuesFallback();
        testDrainRunsBeforePauseReturn();
        testDrainIntervalIsHalfSecond();

        System.out.println("RoutePreloadFallbackGuardTest: PASS");
    }

    /**
     * **提交必须登记 tick 兜底**。
     *
     * <p>{@code submit} 是唯一知道"玩家刚提交了哪条路线"的地方。
     * 若这里不登记，{@code drainPending} 永远没有待办可处理 ——
     * 而施工本身仍然正常（路会建好），所以这个缺陷<b>不会以"报错"形式暴露</b>，
     * 只会表现为"玩家走过去还是空地"。第三次 P1 正是这个形态。</p>
     */
    private static void testSubmitEnqueuesFallback() throws Exception {
        String body = readMethodBody(SERVICE_SRC, "public static int submit");
        require(body != null, "找不到 RouteBuildService.submit 的方法体（被改名/删除？）");

        require(body.contains("enqueuePending"),
                "RouteBuildService.submit 里没有 enqueuePending —— 预加载兜底永远不会被唤醒。\n"
                        + "  ⚠️ 这正是 0.8.400 修复前的形态：路能建好（施工链路正常），\n"
                        + "     但区块强制加载一次都不跑，玩家走过去仍是空地，\n"
                        + "     且日志里不会有任何错误，只有「全日志 0 条预加载记录」。");

        // 与此同时，快速路径的去重标记清理必须保留（重新编辑后再提交要能重跑）
        require(body.contains("forgetPreloaded"),
                "RouteBuildService.submit 里缺少 forgetPreloaded —— "
                        + "玩家「施工 → 重新编辑 → 再提交」时新区域不会被强制加载（R-2 回归）。");
    }

    /**
     * **兜底检测必须排在暂停 return 之前**。
     *
     * <p>{@code RoadGenerationService.tick} 在
     * {@code pauseScheduling() && !exemptPause} 时会提前 {@code return}。
     * 玩家点完施工常常开着地图盯进度，此时 {@code pauseScheduling()} 为真。
     * 若 {@code drainPending} 落在该 {@code return} 之后，
     * 兜底就永远不会执行。</p>
     *
     * <p>这与 W-1 是<b>同一个死锁形状</b>：用"暂停状态"挡住了本该在暂停期
     * 也要推进的玩家主动操作。</p>
     */
    private static void testDrainRunsBeforePauseReturn() throws Exception {
        var path = locate(TICK_SRC);
        require(path != null, "找不到 " + TICK_SRC);
        var lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);

        int drainLine = -1;
        int pauseReturnLine = -1;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String t = raw.trim();

            // ⚠️ 必须先排除注释行。
            //    本方法的 javadoc 里就写着「`RouteChunkPreloader.drainPending` 负责…」——
            //    若不跳过注释，扫描会命中那句说明文字，于是**真实调用被删掉后本测试仍然绿**
            //    （变异测试实测确认过这个假阴性）。守卫必须只看真实代码。
            boolean isComment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");

            if (drainLine < 0 && !isComment && t.contains("drainPending")) {
                drainLine = i;
            }
            // 找「暂停就直接 return」的那一处（方法体里的第一个）
            if (pauseReturnLine < 0 && !isComment
                    && t.contains("pauseScheduling()")
                    && t.contains("!exemptPause")) {
                // 该 if 之后的第一个 return 才是真正的提前退出点
                for (int j = i; j < Math.min(lines.size(), i + 5); j++) {
                    if (lines.get(j).trim().startsWith("return")) {
                        pauseReturnLine = j;
                        break;
                    }
                }
            }
        }

        require(drainLine >= 0,
                "RoadGenerationService.tick 里找不到 drainPending 调用 —— 预加载兜底没有被 tick 驱动。");
        require(pauseReturnLine >= 0,
                "找不到 pauseScheduling() 后的提前 return —— 本守卫的判定前提失效，请复核 tick 结构。");

        require(drainLine < pauseReturnLine,
                "预加载兜底（第 " + (drainLine + 1) + " 行）排在了暂停 return（第 "
                        + (pauseReturnLine + 1) + " 行）之后。\n"
                        + "  ⚠️ 这会让兜底在玩家开着地图/背包时永不执行 ——\n"
                        + "     而那恰恰是玩家点完施工盯进度最常见的状态。\n"
                        + "     与 WorkBuddy W-1 的死锁形状相同（用暂停挡住玩家操作）。\n"
                        + "     请把 drainPending 移到 `if (pauseScheduling() && !exemptPause) return;` 之前。");
    }

    /**
     * **检测间隔必须是 0.5 秒（10 tick）**。
     *
     * <p>用户 2026-09-26 明确要求：「可以改成 0.5 秒不要给他太多的性能压力检测」。</p>
     *
     * <p>1 tick = 50ms（20 TPS），所以 0.5 秒 = 10 tick。</p>
     */
    private static void testDrainIntervalIsHalfSecond() throws Exception {
        var path = locate(PRELOADER_SRC);
        require(path != null, "找不到 " + PRELOADER_SRC);
        String src = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);

        String needle = "DRAIN_INTERVAL_TICKS = " + EXPECTED_INTERVAL_TICKS;
        require(src.contains(needle),
                "预加载兜底的检测间隔应固定为 " + EXPECTED_INTERVAL_TICKS + " tick（= 0.5 秒），"
                        + "但没找到 `" + needle + "`。\n"
                        + "  ⚠️ 用户明确要求「改成 0.5 秒，不要给太多性能压力」——\n"
                        + "     每 tick 检测会让结算扫描（遍历全部连接 + 登记表查找）\n"
                        + "     以 20 次/秒的频率运行。");

        // 节流表必须存在，否则 DRAIN_INTERVAL_TICKS 只是个没人读的常量
        require(src.contains("NEXT_DRAIN"),
                "RouteChunkPreloader 里没有 NEXT_DRAIN 节流表 —— "
                        + "DRAIN_INTERVAL_TICKS 将无人读取，等于每 tick 都在检测。");

        // 待办队列本身必须存在（drainPending 的处理对象）
        require(src.contains("PENDING") && src.contains("enqueuePending"),
                "RouteChunkPreloader 里缺少待办队列（PENDING / enqueuePending）—— "
                        + "tick 兜底机制不完整。");
    }

    // ─────────────────── 源码读取辅助（与 PlayerRoutePendingCountTest 同风格）───────────────────

    /** 读取某方法的方法体（从签名行到其配对花括号结束）。找不到返回 null。 */
    private static String readMethodBody(String relativePath, String methodMarker) throws Exception {
        var path = locate(relativePath);
        if (path == null) {
            return null;
        }
        var lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
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
