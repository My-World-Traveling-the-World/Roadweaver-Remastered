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
    /** 职责头 ratchet 底线（2026-11-13 实测 320）。 */

    // ── 方法级注释的 ratchet 底线（2026-11-13 实测）──────────────
    // 这三层是 depth-D 的剩余部分，规模远大于文件头（合计 4,887 个元素），
    // 短期做不完。先锁成「只许升不许降」，让进度可见、不许倒退；
    // 具体推进节奏与取舍见 REFACTOR.md。
    /** 有注释的 public 方法数（实测 906 / 3,338）。 */
    private static final int PUBLIC_METHOD_DOC_FLOOR = 906;
    /** 有注释的 private 方法数（实测 271 / 1,549）。 */
    private static final int PRIVATE_METHOD_DOC_FLOOR = 271;
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
                .append(" | = ").append(total).append("（100% 覆盖，硬要求） |\n");
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

        // 2026-11-13：注释债已清零，ratchet 升级为硬要求 —— 新文件必须带「文件职责」头。
        //
        // 同一天还删掉了原来那条 `duty >= DUTY_HEADER_FLOOR`（绝对数量下限）。
        // 理由：它**完全被下面这条覆盖** —— 若 duty == total 成立，则
        // duty == total >= 任何历史下限，那条断言永不可能单独触发。
        // 而它的副作用是真实的：**删死代码（好事）会让绝对数量下降，于是守卫红**。
        // 100% 覆盖既更严，又不会误伤删除。
        assertTrue(duty == total,
                "有 " + (total - duty) + " 个文件缺「文件职责」头注（现在要求 100% 覆盖）："
                        + "新增 .java 文件请在第一行写 /* 文件职责：<做什么、不做什么> */");
        assertTrue(veryLong <= 3,
                "有效代码行超过 " + VERY_LONG_FILE + " 的文件有 " + veryLong + " 个（上限 3）—— 请拆分");
        int over1000 = (int) rows.stream().filter(r -> r.code > 1000).count();
        assertTrue(over1000 == 0, "有 " + over1000 + " 个文件有效代码行超过 1000（硬约束）");

        // ── 方法级注释覆盖率（depth-D 剩余三层）────────────────────
        int[] docStats = countMethodDocs(rows);
        System.out.println("MaintainabilityReport 方法级注释：public " + docStats[1] + "/" + docStats[0]
                + "，private " + docStats[3] + "/" + docStats[2]);
        assertTrue(docStats[1] >= PUBLIC_METHOD_DOC_FLOOR,
                "有注释的 public 方法从 " + PUBLIC_METHOD_DOC_FLOOR + " 掉到 " + docStats[1] + " —— 注释不许倒退");
        assertTrue(docStats[3] >= PRIVATE_METHOD_DOC_FLOOR,
                "有注释的 private 方法从 " + PRIVATE_METHOD_DOC_FLOOR + " 掉到 " + docStats[3] + " —— 注释不许倒退");
    }

    /**
     * 统计「紧邻上方有注释」的方法数。
     *
     * <p>口径刻意宽松：上方有 javadoc 或 {@code //} 就算有注释 ——
     * 衡量的是「读代码时能否立刻知道它干什么」，不是格式是否规范。</p>
     *
     * @return {@code [public总数, 有注释public, private总数, 有注释private]}
     */
    private static int[] countMethodDocs(List<Row> rows) throws IOException {
        java.util.regex.Pattern pub = java.util.regex.Pattern.compile(
                "^    (?:public|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+|default\\s+)*"
                        + "[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(");
        java.util.regex.Pattern priv = java.util.regex.Pattern.compile(
                "^    private\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
                        + "[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(");
        int pubTotal = 0;
        int pubDoc = 0;
        int privTotal = 0;
        int privDoc = 0;
        for (Row row : rows) {
            List<String> lines = Files.readAllLines(srcRoot.resolve(row.rel), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean isPub = pub.matcher(line).find();
                boolean isPriv = !isPub && priv.matcher(line).find();
                if (!isPub && !isPriv) continue;
                boolean doc = false;
                for (int j = i - 1; j >= 0 && j >= i - 6; j--) {
                    String s = lines.get(j).trim();
                    if (s.startsWith("@")) continue;
                    if (s.endsWith("*/") || s.startsWith("//")) doc = true;
                    break;
                }
                if (isPub) {
                    pubTotal++;
                    if (doc) pubDoc++;
                } else {
                    privTotal++;
                    if (doc) privDoc++;
                }
            }
        }
        return new int[]{pubTotal, pubDoc, privTotal, privDoc};
    }
}
