/* 文件职责：只算一次守卫 —— 统计「重算热点」的调用点数量，只许减少不许新增。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只算一次」守卫。
 *
 * <h2>它守的是什么</h2>
 * <p>旧版最大的结构病是<b>没有唯一权威中间表示</b>：剖面被算 6 次、区间被提取 14 次，
 * 三套事实各自能被改写，收敛方式变成「跑完再校验再修」。这类退化不是一次大爆炸，
 * 而是每次改动顺手多调一次 —— 所以必须有个东西盯着调用点数量。</p>
 *
 * <h2>为什么用「调用点计数」而不是运行时计数</h2>
 * <p>运行时计数看起来更准，但它要求测试能驱动完整规划链（需要真实世界 + 采样），
 * 一旦某个分支没被覆盖就静默失效。<b>调用点计数是静态的、恒真的、零依赖的</b>，
 * 它抓不住「循环里调了十次」，但抓得住「又有人加了一处重算」——
 * 而后者才是历史上实际发生的退化方式。</p>
 *
 * <h2>怎么降下来</h2>
 * <p>每把一处调用改成读 {@code RoadSpec}，就把基线文件里那个数字改小。
 * 数字归零时说明这个热点已经彻底收敛到单一来源。</p>
 */
public final class SingleSourceOfTruthTest {

    /**
     * 受监控的重算热点。
     *
     * <p>第一列是源码里出现的字面量（含左括号，避免命中变量名），
     * 第二列是它的中文说明 —— 报错时直接告诉人「这是什么」。</p>
     */
    private static final String[][] TRACKED = {
            {"extractSpans(", "区间提取"},
            {"computeTargetY(", "剖面计算"},
            {"refineTunnelSpans(", "隧道区间细化"},
            {"computeBridgeRanges(", "桥范围计算"},
            {"repairFinalHeightConflict(", "高度冲突修复"},
    };

    private static Path repoRoot;
    private static Path srcRoot;

    @BeforeAll
    static void locate() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path cand = dir.resolve("common").resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(cand)) {
                repoRoot = dir;
                srcRoot = cand;
                return;
            }
            dir = dir.getParent();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while (true) {
            int i = haystack.indexOf(needle, at);
            if (i < 0) return n;
            n++;
            at = i + needle.length();
        }
    }

    private static Path baselineFile() {
        return repoRoot.resolve("common").resolve("src").resolve("test")
                .resolve("resources").resolve("architecture").resolve("recompute-sites-baseline.txt");
    }

    @Test
    void 重算热点只许减少不许新增() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到源码根，跳过");

        StringBuilder corpus = new StringBuilder();
        try (Stream<Path> s = Files.walk(srcRoot)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                corpus.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        String all = corpus.toString();

        Map<String, Integer> current = new LinkedHashMap<>();
        for (String[] t : TRACKED) {
            current.put(t[0], countOccurrences(all, t[0]));
        }

        Path baseline = baselineFile();
        assertTrue(Files.isRegularFile(baseline), "缺重算热点基线: " + baseline);
        Map<String, Integer> limit = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            limit.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
        }

        List<String> problems = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (String[] t : TRACKED) {
            String symbol = t[0];
            int now = current.getOrDefault(symbol, 0);
            Integer cap = limit.get(symbol);
            if (cap == null) {
                problems.add(symbol + "（" + t[1] + "）不在基线里 —— 新增受监控热点必须显式登记");
                continue;
            }
            report.append("  ").append(t[1]).append(" ").append(symbol)
                    .append(" 当前 ").append(now).append(" / 上限 ").append(cap).append('\n');
            if (now > cap) {
                problems.add(t[1] + " " + symbol + " 从 " + cap + " 涨到 " + now
                        + " —— 又有人加了一处重算；请改为读 RoadSpec，而不是再算一遍");
            }
        }
        System.out.print("SingleSourceOfTruthTest 重算热点：\n" + report);
        assertTrue(problems.isEmpty(),
                "单一行性被破坏（重算热点只许减少不许新增）：\n  " + String.join("\n  ", problems));
    }
}