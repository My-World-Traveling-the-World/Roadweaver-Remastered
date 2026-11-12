/* 文件职责：只算一次守卫 —— 统计「重算热点被多少个模块触发」，只许减少不许新增。 */
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
 * 而是每次改动顺手多调一次 —— 所以必须有个东西盯着。</p>
 *
 * <h2>★ 指标口径的演进（第一版是错的，记在这里免得再走回头路）</h2>
 * <p>第一版数的是「源码里 {@code 符号(} 出现的次数」。实测发现它没有意义：
 * 声明、同族重载委托、以及收敛后新加的入口方法<b>全都会被算进去</b>，
 * 于是把两处重复调用收成一个入口时，数字只降 1，甚至可能不降。</p>
 *
 * <p>现在改成数<b>「有多少个文件在触发它」</b>（排除定义它的那个文件）。
 * 这个口径直接对应「重算散落在几个模块里」，收敛一处就少一个文件，语义清晰、不会被
 * 声明和委托污染。它仍然抓不住「同一文件里循环调十次」—— 而那件事由 S4 的
 * 「一次 DP 同时出剖面与跨度」从根上解决。</p>
 *
 * <h2>怎么降下来</h2>
 * <p>每把一处调用改成读 {@code RoadSpec}（或并进那条唯一入口），就把基线文件里
 * 对应数字改小。数字归 1 说明只剩唯一触发方。</p>
 */
public final class SingleSourceOfTruthTest {

    /**
     * 受监控的重算热点：{符号, 定义它的文件, 中文说明}。
     *
     * <p>定义文件本身不计入 —— 它总是在调自己。</p>
     */
    private static final String[][] TRACKED = {
            {"extractSpans(", "PathSpanExtractor.java", "区间提取"},
            {"computeTargetY(", "TargetYComputation.java", "剖面计算"},
            {"refineTunnelSpans(", "PathSpanExtractor.java", "隧道区间细化"},
            {"repairFinalHeightConflict(", "RoadPathCalculator.java", "高度冲突修复"},
            // ── 施工侧（S2 判定/施工解耦的基线）──────────────────
            // PathFeature:406 每区块都调一次 BridgeRangeCalculator.compute，而它遍历整条中心线；
            // 这是「施工期自己再判一次」的典型，S2 要把它挪出施工路径。
            {"BridgeRangeCalculator.compute(", "BridgeRangeCalculator.java", "桥范围计算"},
            {"promoteRuntimeBridges(", "RoadConstructionSafety.java", "运行期桥提升"},
            {"SegmentPaver.planGrade(", "SegmentPaver.java", "施工期坡度重插值"},
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

    /** 触发某热点的文件（排除定义它的文件）。 */
    private static List<String> triggeringFiles(String symbol, String ownerFileName) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(srcRoot)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals(ownerFileName)) continue;
                if (Files.readString(p, StandardCharsets.UTF_8).contains(symbol)) {
                    out.add(srcRoot.relativize(p).toString());
                }
            }
        }
        return out;
    }

    private static Path baselineFile() {
        return repoRoot.resolve("common").resolve("src").resolve("test")
                .resolve("resources").resolve("architecture").resolve("recompute-sites-baseline.txt");
    }

    @Test
    void 重算热点只许减少不许新增() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到源码根，跳过");

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
            List<String> files = triggeringFiles(symbol, t[1]);
            Integer cap = limit.get(symbol);
            if (cap == null) {
                problems.add(symbol + "（" + t[2] + "）不在基线里 —— 新增受监控热点必须显式登记");
                continue;
            }
            report.append("  ").append(t[2]).append(" ").append(symbol)
                    .append(" 触发文件 ").append(files.size()).append(" / 上限 ").append(cap);
            if (files.size() > cap) {
                report.append("  ★超限 → ").append(files);
                problems.add(t[2] + " 的触发方从 " + cap + " 个涨到 " + files.size()
                        + " 个：" + files + " —— 又有人多算了一遍；请改为读 RoadSpec 或并入唯一入口");
            } else {
                report.append("  ").append(files);
            }
            report.append('\n');
        }
        System.out.print("SingleSourceOfTruthTest 重算热点：\n" + report);
        assertTrue(problems.isEmpty(),
                "单一行性被破坏（重算热点只许减少不许新增）：\n  " + String.join("\n  ", problems));
    }
}