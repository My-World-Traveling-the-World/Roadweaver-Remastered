/* 文件职责：S5 台账守卫 —— 保证「后处理哪些要保留、哪些等 DP 取代」有据可查且不漂移。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5（后处理降级为网络缝合器）的台账守卫。
 *
 * <h2>它守什么</h2>
 * <p>S5 要「把事后修剖面的 pass 降级/移除」，但直接删会立刻改变世界生成结果 ——
 * 而新的全局 DP 还没切默认。所以正确顺序是：<b>先分类、再等数据、最后删</b>。
 * 这个分类如果只写在文档里，很快就会和代码漂移（有人删了文件、文档还写着它）。</p>
 *
 * <p>三条机器校验：</p>
 * <ol>
 *   <li><b>覆盖面</b>：受管目录下每个 {@code .java} 都必须在台账里出现<b>恰好一次</b>
 *       —— 新增文件忘了登记会红；</li>
 *   <li><b>存在性</b>：台账里每一行的文件必须真实存在 —— <b>删了文件就必须删台账行</b>；</li>
 *   <li><b>词汇表封闭</b>：类别只能是
 *       {@code KEEP_SEAM / KEEP_INPUT / REPLACE_BY_DP / DELETE_LATER}，
 *       且 {@code REPLACE_BY_DP} 必须写明由谁取代 —— 不许出现「将来再说」。</li>
 * </ol>
 *
 * <h2>为什么「替换者」这一列必须是强制非空</h2>
 * <p>「这个文件以后会被取代」是一句没有信息量的话。写清「被谁取代」才让人能判断
 * 那东西到底做完了没有 —— 这是把可维护性落到可执行层面的最小手段。</p>
 */
public final class PostprocessLedgerTest {

    /** 受管目录（相对 {@code common/src/main/java/net/shiroha233/roadweaver}）。 */
    private static final String[] COVERED_DIRS = {
            "postprocess",
            "pathlogic/grade",
    };

    /** 受管目录之外、但同样纳入台账的单文件。 */
    private static final String[] COVERED_FILES = {
            "planning/terrain/AccurateGradeRepairPlanner.java",
            "pathlogic/pathcalc/RoadPathCalculator.java",
    };

    private static final Set<String> CATEGORIES = Set.of(
            "KEEP_SEAM", "KEEP_INPUT", "REPLACE_BY_DP", "DELETE_LATER");

    private static Path srcRoot;
    private static Path ledger;

    @BeforeAll
    static void locate() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java")
                    .resolve("net").resolve("shiroha233").resolve("roadweaver");
            if (Files.isDirectory(cand)) {
                srcRoot = cand;
                ledger = dir.resolve("common/src/test/resources/architecture/postprocess-files-baseline.txt");
                return;
            }
            dir = dir.getParent();
        }
    }

    @Test
    void 台账必须与代码同步() throws IOException {
        Assumptions.assumeTrue(srcRoot != null && Files.isRegularFile(ledger),
                "找不到源码根或 S5 台账，跳过");

        String text = Files.readString(ledger, StandardCharsets.UTF_8);
        int from = text.indexOf("<!-- S5:BEGIN -->");
        int to = text.indexOf("<!-- S5:END -->");
        assertTrue(from >= 0 && to > from, "S5 台账缺少 S5:BEGIN / S5:END 标记块");

        List<String> rows = new ArrayList<>();
        for (String line : text.substring(from, to).split("\n")) {
            String s = line.trim();
            if (s.startsWith("S5|")) rows.add(s);
        }
        assertTrue(!rows.isEmpty(), "S5 台账为空");

        Set<String> listed = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();

        for (String row : rows) {
            String[] parts = row.split("\\|", -1);
            if (parts.length < 5) {
                problems.add("台账行字段不足（需要 5 段 S5|路径|类别|为什么|谁接替）：" + row);
                continue;
            }
            String rel = parts[1].trim();
            String cat = parts[2].trim();
            String why = parts[3].trim();
            String replacement = parts[4].trim();

            if (!listed.add(rel)) {
                problems.add("台账里重复登记：" + rel);
                continue;
            }
            if (!CATEGORIES.contains(cat)) {
                problems.add("未知类别 " + cat + "（" + rel + "）；只允许 " + CATEGORIES);
            }
            if (why.isEmpty()) {
                problems.add("缺「为什么」：" + rel);
            }
            if ("REPLACE_BY_DP".equals(cat) && (replacement.isEmpty() || "—".equals(replacement))) {
                problems.add("REPLACE_BY_DP 必须写明由谁取代：" + rel);
            }
            if (!Files.isRegularFile(srcRoot.resolve(rel))) {
                problems.add("台账登记的文件不存在（删文件必须同步删台账行）：" + rel);
            }
        }

        // 覆盖面：受管目录下每个 .java 都要登记
        for (String dir : COVERED_DIRS) {
            Path d = srcRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String rel = srcRoot.relativize(p).toString().replace('\\', '/');
                    if (!listed.contains(rel)) problems.add("未登记进 S5 台账：" + rel);
                }
            }
        }
        for (String f : COVERED_FILES) {
            if (!listed.contains(f)) problems.add("未登记进 S5 台账：" + f);
        }

        assertTrue(problems.isEmpty(),
                "S5 台账与代码不同步（删文件要删行，加文件要登记）：\n  "
                        + String.join("\n  ", problems)
                        + "\n台账文件：" + ledger);

        long replace = rows.stream().filter(r -> r.contains("|REPLACE_BY_DP|")).count();
        long deleteLater = rows.stream().filter(r -> r.contains("|DELETE_LATER|")).count();
        long keep = rows.size() - replace - deleteLater;
        System.out.println("PostprocessLedgerTest: 共 " + rows.size() + " 个文件分类 —— "
                + "保留 " + keep + " / 等 DP 取代 " + replace + " / 待删 " + deleteLater);
    }
}