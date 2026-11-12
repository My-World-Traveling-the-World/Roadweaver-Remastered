/* 文件职责：维护性指标报告 —— 把「可维护性」变成每轮都能看的数字。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维护性指标报告。
 *
 * <h2>为什么需要它</h2>
 * <p>「可维护性」如果只是口头判断，每一轮都会退化成凭感觉排期。
 * 这里把它压成几个可计算、可回归的数字，每轮跑一遍、写进
 * {@code build/maintainability-report.md}，谁都能自己验证进度。</p>
 *
 * <h2>指标与阈值</h2>
 * <table>
 *   <tr><th>指标</th><th>阈值</th><th>为什么</th></tr>
 *   <tr><td>有效代码行 &gt; 800 的文件数</td><td>≤ 3</td>
 *       <td>千行硬约束之外再加一档，逼着别把文件堆到 900+</td></tr>
 *   <tr><td>有效代码行 &gt; 400 的文件数</td><td>只报告不卡</td>
 *       <td>这是「偏长」信号，不是红线</td></tr>
 *   <tr><td>有「文件职责」头的文件数</td><td>≥ 298</td>
 *       <td>ratchet：只许升不许降</td></tr>
 *   <tr><td>长参数表方法数</td><td>只报告</td>
 *       <td>参数对象化的靶点，先看见再改</td></tr>
 *   <tr><td>分支密度</td><td>只报告</td>
 *       <td>难测难改的地方，优先抽状态机 / 表驱动</td></tr>
 * </table>
 *
 * <p><b>只报告不卡的指标是故意的</b>：一上来就设硬阈值只会逼人绕过守卫。
 * 先把数字晒出来，等某条明显恶化时再收紧。</p>
 */
public final class MaintainabilityReportTest {

    /** 「偏长」信号档（有效代码行）。 */
    private static final int LONG_FILE = 400;
    /** 接近千行硬约束的预警档。 */
    private static final int VERY_LONG_FILE = 800;
    /** 职责头 ratchet 底线（2026-11-13 实测 298）。 */
    private static final int DUTY_HEADER_FLOOR = 298;

    /** 长参数表判定：括号内超过 120 个字符。 */
    private static final Pattern LONG_PARAMS = Pattern.compile("\\w+\\s*\\([^)]{120,}\\)");
    private static final Pattern METHOD = Pattern.compile(
            "^\\s+(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(",
            Pattern.MULTILINE);
    private static final Pattern BRANCH = Pattern.compile("\\b(if|for|while|case|catch)\\b|&&|\\|\\|");

    private static Path srcRoot;
    private static Path reportFile;

    @BeforeAll
    static void locate() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java")
                    .resolve("net").resolve("shiroha233").resolve("roadweaver");
            if (Files.isDirectory(cand)) {
                srcRoot = cand;
                reportFile = dir.resolve("common").resolve("build").resolve("maintainability-report.md");
                return;
            }
            dir = dir.getParent();
        }
    }

    private static final class Row {
        String rel;
        int physical;
        int code;
        int methods;
        int branches;
        int longParams;
        boolean hasHeader;
    }

    private static int effectiveCodeLines(String text) {
        int count = 0;
        boolean inBlock = false;
        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            String s = raw.trim();
            if (inBlock) {
                if (s.contains("*/")) inBlock = false;
                continue;
            }
            if (s.startsWith("/*")) {
                if (!s.contains("*/")) inBlock = true;
                continue;
            }
            if (s.isEmpty() || s.startsWith("//") || s.startsWith("*")) continue;
            count++;
        }
        return count;
    }

    @Test
    void 维护性指标不得退化() throws IOException {
        Assumptions.assumeTrue(srcRoot != null, "找不到源码根，跳过");

        List<Row> rows = new ArrayList<>();
        try (Stream<Path> s = Files.walk(srcRoot)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Row r = new Row();
                r.rel = srcRoot.relativize(p).toString();
                r.physical = text.replace("\r\n", "\n").split("\n").length;
                r.code = effectiveCodeLines(text);
                r.hasHeader = text.lines().findFirst().orElse("").contains("文件职责");
                int methods = 0;
                Matcher mm = METHOD.matcher(text);
                while (mm.find()) methods++;
                r.methods = methods;
                int branches = 0;
                Matcher bm = BRANCH.matcher(text);
                while (bm.find()) branches++;
                r.branches = branches;
                int lp = 0;
                Matcher lm = LONG_PARAMS.matcher(text);
                while (lm.find()) lp++;
                r.longParams = lp;
                rows.add(r);
            }
        }

        int total = rows.size();
        int duty = (int) rows.stream().filter(r -> r.hasHeader).count();
        int longFiles = (int) rows.stream().filter(r -> r.code > LONG_FILE).count();
        int veryLong = (int) rows.stream().filter(r -> r.code > VERY_LONG_FILE).count();
        int totalCode = rows.stream().mapToInt(r -> r.code).sum();
        int longParams = rows.stream().mapToInt(r -> r.longParams).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("# 维护性指标报告\n\n");
        sb.append("| 指标 | 当前值 | 阈值 |\n|---|---|---|\n");
        sb.append("| 文件数 | ").append(total).append(" | — |\n");
        sb.append("| 有效代码行合计 | ").append(totalCode).append(" | — |\n");
        sb.append("| 有效代码行 > ").append(LONG_FILE).append(" 的文件 | ").append(longFiles)
                .append(" | 只报告 |\n");
        sb.append("| 有效代码行 > ").append(VERY_LONG_FILE).append(" 的文件 | ").append(veryLong)
                .append(" | ≤ 3 |\n");
        sb.append("| 有「文件职责」头 | ").append(duty)
                .append(" | ≥ ").append(DUTY_HEADER_FLOOR).append(" |\n");
        sb.append("| 长参数表方法 | ").append(longParams).append(" | 只报告 |\n\n");

        sb.append("## 分支密度 Top 10（if/for/case/&& 每百有效行）\n\n");
        rows.stream().filter(r -> r.code >= 80)
                .sorted(Comparator.comparingDouble((Row r) -> -(100.0 * r.branches / r.code)))
                .limit(10).forEach(r -> sb.append(String.format("- %.1f  %s（%d 行 / %d 分支）%n",
                        100.0 * r.branches / r.code, r.rel, r.code, r.branches)));

        sb.append("\n## 长参数表 Top 10\n\n");
        rows.stream().filter(r -> r.longParams > 0)
                .sorted(Comparator.comparingInt((Row r) -> -r.longParams))
                .limit(10).forEach(r -> sb.append(String.format("- %d 处  %s%n", r.longParams, r.rel)));

        sb.append("\n## 方法数 Top 10\n\n");
        rows.stream().sorted(Comparator.comparingInt((Row r) -> -r.methods))
                .limit(10).forEach(r -> sb.append(String.format("- %d 个方法  %s（%d 行）%n",
                        r.methods, r.rel, r.code)));

        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("MaintainabilityReport: " + total + " 文件 / " + totalCode
                + " 有效代码行 / 职责头 " + duty + " / >" + VERY_LONG_FILE + " 行 " + veryLong
                + " / 长参数表 " + longParams + " → " + reportFile);

        assertTrue(duty >= DUTY_HEADER_FLOOR,
                "有「文件职责」头的文件从 " + DUTY_HEADER_FLOOR + " 掉到 " + duty + " —— 注释不许倒退");
        assertTrue(veryLong <= 3,
                "有效代码行超过 " + VERY_LONG_FILE + " 的文件有 " + veryLong + " 个（上限 3）—— 请拆分");
        int over1000 = (int) rows.stream().filter(r -> r.code > 1000).count();
        assertTrue(over1000 == 0, "有 " + over1000 + " 个文件有效代码行超过 1000（硬约束）");
    }
}