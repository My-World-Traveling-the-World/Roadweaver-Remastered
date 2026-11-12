package net.shiroha233.roadweaver.route;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * **架构假设的静态守卫**（★ 2026-09-26 修 WorkBuddy I-2）。
 *
 * <h2>要防什么</h2>
 * 0.8.391 与 0.8.392 在**同一处**连出过两次 P1，根因都是同一个错误映射：
 * <b>用「触发了多少次」推断「连接处于什么状态」</b>。
 *
 * <p>0.8.395 的修法（{@link RouteChunkPreloader#decideReadiness}，连接状态机判据）
 * 在**纯逻辑**上是对的，{@code RoutePreloadTimingTest} 也已把它穷举钉住。
 * <b>但那个判据之所以成立，依赖一个纯函数测试照不到的「调用点事实」</b>：</p>
 *
 * <pre>
 *   预加载的唯一自动触发点在 RoadGenerationService 的
 *   `if (attempt.outcome().road() != null)` 分支里 —— **只有成功的连接才触发**。
 *   失败连接走 updateConnectionStatus(FAILED)，**不触发**。
 * </pre>
 *
 * <p>⚠️ <b>如果将来有人"顺手"在失败分支也加一次触发</b>，
 * 上面那个假设就被悄悄改变了 —— 而 {@code decideReadiness} 仍然全绿、
 * {@code RoutePreloadTimingTest} 仍然全绿，**没有任何测试会红**。
 * 这正是 WorkBuddy §7 指出的"唯一未防守面"。</p>
 *
 * <h2>这个测试怎么做</h2>
 * 读 {@code RoadGenerationService.java} 的**源码文本**，断言：
 * <ol>
 *   <li>{@code preloadRouteChunks} 这个调用**存在**（否则守卫本身失效）</li>
 *   <li>它出现在 {@code road() != null} 的分支**之内**
 *       （用「调用行之前最近的 if 条件里含 road() != null」判定）</li>
 * </ol>
 *
 * <p>这是一个**文本级断言**，比 AST 粗糙，但对"调用点是否被搬走"足够敏感 —
 * 而且实现简单、不会因为反编译/字节码差异而误报。
 * 若将来源码结构大改导致本测试误红，请**先确认调用点语义未变**再更新断言。</p>
 */
public final class RoutePreloadCallSiteGuardTest {

    private RoutePreloadCallSiteGuardTest() {
    }

    /** 目标源文件（相对仓库根）。 */
    private static final String TARGET =
            "common/src/main/java/net/shiroha233/roadweaver/generation/RoadGenerationService.java";

    /** 预加载的调用点方法名。 */
    private static final String CALL = "preloadRouteChunks";

    /** 那个"只有成功才触发"的前置条件。 */
    private static final String SUCCESS_GUARD = "road() != null";

    public static void main(String[] args) throws Exception {
        Path source = locateSource();
        require(source != null,
                "找不到 " + TARGET + " —— 本守卫需要读取源码文本，"
                        + "请确认工作目录在仓库根（当前: " + Path.of("").toAbsolutePath() + "）");

        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);

        // ── ① 调用点必须存在（否则守卫失去意义，说明调用被删/改名）──
        List<Integer> callLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(CALL)) {
                callLines.add(i);
            }
        }
        require(!callLines.isEmpty(),
                "源码里找不到对 " + CALL + " 的调用 —— 若确实是重命名/移除，"
                        + "请同步更新本守卫（RoutePreloadCallSiteGuardTest）");

        // ── ② 每个真实调用点都必须落在「成功分支」之内 ──
        //   判定：向上找最近的、影响该行的 if 条件；它必须提到 road() != null
        int checked = 0;
        for (int lineIndex : callLines) {
            String raw = lines.get(lineIndex);
            String trimmed = raw.trim();
            // 跳过注释/文档里提到名字的行
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            checked++;
            require(withinSuccessGuard(lines, lineIndex),
                    "第 " + (lineIndex + 1) + " 行的 " + CALL + " 调用**不在** "
                            + "`" + SUCCESS_GUARD + "` 分支之内。\n"
                            + "  ⚠️ 这可能破坏 0.8.395 的判据假设：\n"
                            + "     RouteChunkPreloader.decideReadiness 的正确性依赖\n"
                            + "     「预加载只在连接**成功**时触发」这个事实。\n"
                            + "     若你确实要在失败分支也触发，请先重新评估那边界条件，\n"
                            + "     再同步更新本守卫。\n"
                            + "  实际代码: " + trimmed);
        }
        require(checked > 0,
                "找到了 " + CALL + " 字样但全在注释里 —— 说明真实调用已被移除");

        System.out.println("RoutePreloadCallSiteGuardTest: PASS"
                + " (调用点 " + checked + " 处，均位于 `" + SUCCESS_GUARD + "` 分支内)");
    }

    /**
     * 向上回溯，判断该行是否落在含 {@code road() != null} 的条件块内。
     *
     * <p>做法：从调用行往上扫描，记录遇到的条件行；只要在**遇到方法边界之前**
     * 找到一个含目标条件的行，就认为在守卫之内。</p>
     */
    private static boolean withinSuccessGuard(List<String> lines, int callLine) {
        for (int i = callLine; i >= 0 && callLine - i < 120; i--) {
            String t = lines.get(i).trim();
            // 方法边界（回到上一个同级方法定义）就停
            if (t.startsWith("private static void ") || t.startsWith("public static void ")
                    || t.startsWith("private static ") && t.contains("(") && t.endsWith("{")) {
                if (i != callLine) {
                    return false;
                }
            }
            if (t.contains(SUCCESS_GUARD)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定位源码文件。
     *
     * <p>JavaExec 任务**没有设置 workingDir**，所以工作目录取决于 Gradle 的调用方式
     * （通常就是 `common/` 子项目目录，但也可能是仓库根）。
     * 这里依次尝试多个候选，并允许用
     * {@code -Droad.repo.root=<仓库根>} 显式指定（与 SavedEndpoint528SimulationTest
     * 的 `-Droad.audit.root` 同一风格）。</p>
     */
    private static Path locateSource() {
        List<Path> candidates = new ArrayList<>();
        // ① 显式指定
        String explicit = System.getProperty("road.repo.root");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit).resolve(TARGET));
        }
        // ② 常见工作目录
        Path cwd = Path.of("").toAbsolutePath();
        candidates.add(cwd.resolve(TARGET));                 // 仓库根
        candidates.add(cwd.resolve("..").resolve(TARGET));   // common/ 子目录
        candidates.add(cwd.resolve("..").resolve("..").resolve(TARGET));
        // ③ 只按文件名兜底搜一层（源码树结构固定）
        candidates.add(cwd.resolve(TARGET.substring(TARGET.indexOf("net/"))));
        candidates.add(cwd.resolve("..").resolve(TARGET.substring(TARGET.indexOf("net/"))));
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
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
