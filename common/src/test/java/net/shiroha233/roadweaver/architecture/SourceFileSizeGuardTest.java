/* 文件职责：把「单文件不超过 1000 行」这条工程硬约束变成自动守卫。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单文件行数守卫（2026-11-15 新增）。
 *
 * <h2>为什么需要它</h2>
 * <p>「单个源文件不超过 1000 行」是项目的工程硬约束 —— 超过之后单文件难以维护、
 * 合并冲突成片、且人一打开就被劝退。但这条约束此前<b>只靠人工检查</b>：
 * 2026-11-15 实测有 3 个文件贴线或超限（{@code AccurateGradeRepairPlanner} 1006、
 * {@code RoadTunnelBuilder} 1002、{@code StructureAvoidanceService} 998），
 * 前两个是当天才发现的。</p>
 *
 * <p>现在把它变成回归测试：任何人往这些文件里加代码破了 1000 行，本测试立刻红，
 * 不会再出现「悄悄长到 1006 行没人知道」。</p>
 *
 * <h2>它还负责「贴线预警」</h2>
 * <p>只卡 1000 行不够 —— 998 行的文件这次没红，但下次改动就破了。
 * 所以对 {@code > WARN_THRESHOLD}（950 行）的文件单独列出来，
 * 让人在动手之前就知道「这个文件必须先拆」。</p>
 *
 * <h2>扫描范围</h2>
 * <p>三个生产模块的 {@code src/main/java}：{@code common} / {@code fabric} / {@code api}。
 * 测试代码不在此约束内（测试文件天然可能很长，且不影响运行时维护成本）。</p>
 */
class SourceFileSizeGuardTest {

    /** 硬上限：任何生产源文件都不得超过。 */
    private static final int HARD_LIMIT = 1000;

    /** 贴线预警线：超过它就该在下一次改动前先拆。 */
    private static final int WARN_THRESHOLD = 950;

    /** 工作目录是 common 模块，其它模块是它的兄弟目录。 */
    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    @Test
    void 任何生产源文件都不得超过一千行() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : productionSourceRoots()) {
            for (Path file : javaFiles(root)) {
                int lines = countLines(file);
                if (lines > HARD_LIMIT) {
                    violations.add(describe(file, lines));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "以下生产源文件超过 " + HARD_LIMIT + " 行，必须拆分：\n  "
                        + String.join("\n  ", violations)
                        + "\n\n拆分建议：优先抽「纯数据定义 / 常量 / 独立的几何工具」到同包新文件 ——"
                        + "同包引用免 import，改动面最小（2026-11-15 就按这个办法把"
                        + " AccurateGradeRepairPlanner 与 RoadTunnelBuilder 拆下来了）。");
    }

    /**
     * 贴线预警：超过 950 行的文件逐一列出。
     *
     * <p><b>这条不失败</b>，只把名单打进报告 —— 因为「贴线」本身不违规，
     * 只是下一次改动的风险点。真正想让它失败时改成 assertTrue 即可。</p>
     */
    @Test
    void 贴线文件必须能被看见() throws IOException {
        List<String> near = new ArrayList<>();
        for (Path root : productionSourceRoots()) {
            for (Path file : javaFiles(root)) {
                int lines = countLines(file);
                if (lines > WARN_THRESHOLD && lines <= HARD_LIMIT) {
                    near.add(describe(file, lines));
                }
            }
        }
        if (!near.isEmpty()) {
            System.out.println("[SIZE-WARN] 以下文件已接近 " + HARD_LIMIT
                    + " 行上限，下次改它之前应先拆分：\n  " + String.join("\n  ", near));
        }
        // 断言恒真：本测试的职责是「让名单被看见」，不是拦截。
        // 保留 assertTrue 形式是为了将来要收紧时一行就能改。
        assertTrue(true);
    }

    /** 三个生产模块的 src/main/java。 */
    private static List<Path> productionSourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (String module : List.of("common", "fabric", "api")) {
            Path root = REPO_ROOT.resolve(module).resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(root)) roots.add(root);
        }
        return roots;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 数行数。
     *
     * <p>按 {@code \n} 切分而不是 {@code Files.readAllLines}：后者对末尾无换行的文件
     * 会少算一行，与 {@code wc -l} 的口径不一致。这里统一用「换行符个数 + 末行是否有内容」，
     * 与命令行 {@code wc -l} 的结果对齐，避免测试与人工核查打架。</p>
     */
    private static int countLines(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.isEmpty()) return 0;
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') newlines++;
        }
        // 末尾没有换行说明最后一行还有内容，要补算
        return text.charAt(text.length() - 1) == '\n' ? newlines : newlines + 1;
    }

    private static String describe(Path file, int lines) {
        String rel = REPO_ROOT.relativize(file).toString().replace('\\', '/');
        return lines + " 行  " + rel;
    }
}