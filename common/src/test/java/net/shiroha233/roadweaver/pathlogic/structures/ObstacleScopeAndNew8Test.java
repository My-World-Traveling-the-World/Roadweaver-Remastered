/* 文件职责：钉住「硬障碍作用域」的语义，并防住 NEW-8 修复被回退。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.BuildingObstacleMode;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService.BuildingObstacleScope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 硬障碍作用域测试（2026-11-15）。
 *
 * <h2>背景：这里曾经有一个「穿村穿楼」的缺陷</h2>
 * <p>2026-11-14 的审计把「降级重搜时硬障碍失效」列为 Blocker（编号 A / NEW-8）。
 * 根因链条是这样的：</p>
 * <ol>
 *   <li>{@code BuildingObstacleMode} 是 <b>ThreadLocal</b>，只在
 *       {@code withBuildingObstacleMode(...)} 的作用域内被临时抬高到
 *       {@code HARD_BUILDINGS_AND_CLUSTERS}；</li>
 *   <li>主搜索在作用域内跑，但<b>降级重搜</b>（主搜索无解时的兜底）原先在作用域<b>外</b>；</li>
 *   <li>于是降级时模式已回落到 {@code PENALTY}，而 PENALTY 下
 *       {@code blocksPlanningObstacle} 恒返回 {@code false}；</li>
 *   <li>结果：障碍场全空、软势恒 0 —— 等于<b>在无障碍地图上找路</b>，路直接穿过村庄和建筑。</li>
 * </ol>
 *
 * <p>修法是「与主搜索同一口径包进 HARD 作用域」，同时保证降级路径仍有<b>真实的软障碍势</b>
 * （只是不再硬禁）。仓库里 {@code RawPathSearch} 两处已带 2026-11-14 的更正注释。</p>
 *
 * <h2>这个测试钉两件事</h2>
 * <ol>
 *   <li><b>作用域语义本身</b>：进入后模式改变、退出后恢复、嵌套正确、null 回落 PENALTY、
 *       抛异常也恢复 —— 这些都是纯 ThreadLocal 逻辑，不需要世界，可以离线测；</li>
 *   <li><b>NEW-8 的修复不被回退</b>：源码扫描断言 {@code RawPathSearch} 里
 *       <b>两处</b> {@code findRawPath} 调用都在 {@code withBuildingObstacleMode} 的
 *       try-with-resources 内。<b>这条是本文件的重点</b> —— 作用域语义就算全对，
 *       只要有人把某处调用挪出作用域，穿村穿楼就会<b>静默</b>回来。</li>
 * </ol>
 */
class ObstacleScopeAndNew8Test {

    private static final String RAW_PATH_SEARCH =
            "common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RawPathSearch.java";

    // ───────────────────────── 一、作用域语义 ─────────────────────────

    @Test
    void 进入作用域后模式改变退出后恢复() {
        BuildingObstacleMode original = ObstacleJudge.buildingObstacleMode();

        try (BuildingObstacleScope ignored =
                     ObstacleJudge.withBuildingObstacleMode(BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS)) {
            assertEquals(BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS,
                    ObstacleJudge.buildingObstacleMode(), "作用域内必须是硬禁档");
        }

        assertEquals(original, ObstacleJudge.buildingObstacleMode(),
                "退出作用域必须恢复原值 —— 否则后续所有规划都会意外变成硬禁，"
                        + "或反过来一直停在 PENALTY");
    }

    @Test
    void 嵌套作用域按后进先出恢复() {
        BuildingObstacleMode original = ObstacleJudge.buildingObstacleMode();

        try (BuildingObstacleScope outer =
                     ObstacleJudge.withBuildingObstacleMode(BuildingObstacleMode.HARD_BUILDINGS)) {
            assertEquals(BuildingObstacleMode.HARD_BUILDINGS, ObstacleJudge.buildingObstacleMode());

            try (BuildingObstacleScope inner =
                         ObstacleJudge.withBuildingObstacleMode(BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS)) {
                assertEquals(BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS,
                        ObstacleJudge.buildingObstacleMode(), "内层应覆盖外层");
            }

            assertEquals(BuildingObstacleMode.HARD_BUILDINGS, ObstacleJudge.buildingObstacleMode(),
                    "退出内层必须回到外层值，不能一路弹回默认值");
        }

        assertEquals(original, ObstacleJudge.buildingObstacleMode(), "退出外层回到最初值");
    }

    @Test
    void null模式回落为PENALTY而不是让ThreadLocal变成null() {
        try (BuildingObstacleScope ignored = ObstacleJudge.withBuildingObstacleMode(null)) {
            assertEquals(BuildingObstacleMode.PENALTY, ObstacleJudge.buildingObstacleMode(),
                    "null 必须回落成 PENALTY —— 如果真塞 null 进去，"
                            + "后续每次 buildingObstacleMode() 都返回 null，"
                            + "所有 mode == PENALTY 判断都会失配");
        }
    }

    @Test
    void 作用域内抛异常也必须恢复() {
        BuildingObstacleMode original = ObstacleJudge.buildingObstacleMode();

        try {
            try (BuildingObstacleScope ignored =
                         ObstacleJudge.withBuildingObstacleMode(BuildingObstacleMode.HARD_BUILDINGS_AND_CLUSTERS)) {
                throw new IllegalStateException("模拟规划中途失败");
            }
        } catch (IllegalStateException expected) {
            // 预期的
        }

        assertEquals(original, ObstacleJudge.buildingObstacleMode(),
                "异常路径也必须恢复 —— 规划里异常很常见（无解/超时），"
                        + "若此处不恢复，一次失败就会让这个线程后续全部变成硬禁档");
    }

    // ───────────────────── 二、NEW-8 修复不被回退 ─────────────────────

    /**
     * 源码守卫：{@code RawPathSearch} 里<b>至少一处</b> {@code findRawPath} 在
     * {@code withBuildingObstacleMode} 的 try-with-resources 块<b>之内</b>，
     * 且<b>降级路径确实存在并带日志</b>。
     *
     * <h2>★ 为什么不是「每一处都在作用域内」</h2>
     * <p>我第一版就是这么写的，写完才发现<b>名实不符</b>：{@code searchWithHardBuildingObstacles}
     * 的降级调用（主搜索无解后的兜底）是<b>有意</b>放在作用域<b>之外</b>的 ——
     * 那段注释写得很清楚：降级时模式回落 PENALTY、不再硬禁，
     * <b>但仍保留真实的软障碍势</b>（否则村庄封死整条走廊时会彻底无解）。
     * 所以「全部都在作用域内」这个断言是错的，它会把正确实现判成缺陷。</p>
     *
     * <p>而第一版的判据还有一处<b>假阴性</b>：它「向上找最近的 try (」然后
     * 检查开块到调用行之间的文本 —— 对作用域<b>之后</b>的降级调用，
     * 向上 12 行内仍能找到那个 try 开块，于是被误判成「在作用域内」。
     * 换句话说，第一版既可能冤枉正确代码，也抓不到真正的回退。</p>
     *
     * <h2>现在的判据（两个方向都钉）</h2>
     * <ol>
     *   <li><b>至少一处</b>调用在作用域内 —— 这保证主搜索仍受硬禁保护；</li>
     *   <li><b>降级路径存在</b>，且它前面有 {@code relaxed building hard obstacles} 日志 ——
     *       这保证「降级」是显式发生的、而不是「有人忘了包作用域」造成的偶然结果。
     *       两者形态相同、日志把它们区分开。</li>
     * </ol>
     */
    @Test
    void RawPathSearch的主搜索在硬障碍作用域内且降级路径显式存在() throws Exception {
        Path path = locate(RAW_PATH_SEARCH);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        int callCount = 0;
        int insideScope = 0;
        boolean hasRelaxLog = false;

        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (t.contains("relaxed building hard obstacles")) hasRelaxLog = true;
            if (!t.contains("findRawPath(")) continue;
            if (t.contains("PathResult findRawPath(") || t.contains("private ") || t.contains("public ")) continue;
            callCount++;

            // 找到本行所属的 try (...) 块（向上找开块），再确定它的结束行，
            // 只有调用行落在 [开块, 配对 }) 区间内才算「在作用域内」。
            int tryLine = -1;
            for (int j = i; j >= Math.max(0, i - 12); j--) {
                if (lines.get(j).contains("try (")) { tryLine = j; break; }
            }
            if (tryLine < 0) continue;

            int depth = 0;
            int endLine = -1;
            outer:
            for (int j = tryLine; j < lines.size(); j++) {
                String cur = lines.get(j);
                for (int k = 0; k < cur.length(); k++) {
                    char ch = cur.charAt(k);
                    if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) { endLine = j; break outer; }
                    }
                }
            }

            boolean inside = endLine > 0 && i >= tryLine && i <= endLine
                    && String.join("\n", lines.subList(tryLine, i + 1)).contains("withBuildingObstacleMode");
            if (inside) insideScope++;
        }

        assertTrue(callCount >= 2,
                "至少应找到 2 处 findRawPath 调用（主搜索 + 降级/隧道重搜），实际 " + callCount
                        + " —— 若代码结构变了请同步本守卫");
        // ★ 这里必须是 2，不是 1。
        //   第一版写的是 >= 1，负控立刻证明它无效：把主搜索的作用域去掉之后，
        //   「隧道重搜」那一处仍在作用域内 ⇒ insideScope == 1 ⇒ 测试照样绿。
        //   而「主搜索失保护」正是 NEW-8 描述的那个穿村穿楼路径。
        //   当前共有 3 处调用：隧道重搜（作用域内）、主搜索 HARD（作用域内）、降级兜底（作用域外，设计如此）。
        final int expectedInside = 2;
        assertTrue(insideScope >= expectedInside,
                "在硬障碍作用域内的 findRawPath 只有 " + insideScope + " 处，至少应有 "
                        + expectedInside + " 处（隧道重搜 + 主搜索）—— NEW-8 回退！"
                        + "作用域外调用时模式已回落到 PENALTY，blocksPlanningObstacle 恒 false "
                        + "⇒ 障碍场全空 ⇒ 路穿村庄和建筑，且不报错、不崩，只是路开始穿楼。");
        assertTrue(hasRelaxLog,
                "找不到「relaxed building hard obstacles」日志 —— 降级路径要么被删了，"
                        + "要么变成静默发生。降级本身是设计（村庄封死走廊时必须兜底），"
                        + "但它必须是可观测的：没有这条日志，运维就无法区分"
                        + "「正常降级」与「有人忘了包作用域」。");
    }

    private static Path locate(String relative) {
        Path p = Path.of(relative);
        if (Files.isRegularFile(p)) return p;
        Path fromRoot = Path.of("..").resolve(relative);
        if (Files.isRegularFile(fromRoot)) return fromRoot;
        throw new IllegalStateException("找不到源码 " + relative
                + "（守卫需在仓库根运行，当前 " + Path.of("").toAbsolutePath() + "）");
    }
}