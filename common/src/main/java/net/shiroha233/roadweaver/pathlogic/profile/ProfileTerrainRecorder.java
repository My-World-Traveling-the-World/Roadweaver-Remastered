/* 文件职责：把运行中每条路的地形剖面录成一行文本 —— 让真实地形能进单元测试重放。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 剖面地形录制器：把真实运行里的地形存成可重放的行。
 *
 * <h2>为什么需要它</h2>
 * <p>离线研究（{@code ProfileAbStudyTest}）已经证明新 DP 在合成地形上明显更省土方，
 * 但那条结论有一条硬局限：<b>地形是合成的，不代表真实存档</b>。</p>
 *
 * <p>要消掉这条局限，最直觉的做法是「多做几个合成场景」—— 但那永远只是猜。
 * 正确做法是<b>把真实地形录下来</b>：玩家开一次游戏、跑一批路线，
 * 地形就被存成几十行文本，之后所有 AB 实验都在 CI 里重放，
 * <b>不需要再开游戏</b>。</p>
 *
 * <h2>记录什么、不记录什么</h2>
 * <ul>
 *   <li><b>记</b>：每站的 {@code terrainY}、每站是否跨水（{@code crossing}）、以及一个 tag（端点）；</li>
 *   <li><b>不记</b>：旧剖面本身。因为一旦录了旧解，重放就变成"和旧解比"，
 *       而我们要比的是「同样的地形，两种算法各自算出的解」。</li>
 * </ul>
 *
 * <h2>格式</h2>
 * <pre>
 *   tag \t terrainY 逗号分隔 \t crossing 用 0/1 逗号分隔
 * </pre>
 * <p>选 TSV 而不是 JSON：这条路径在服务端线程上跑，每站一个数字，
 * 手写序列化比任何库都快且零依赖。</p>
 *
 * <h2>★ 三条保护（绝不影响主流程）</h2>
 * <ol>
 *   <li>只在 {@code -Droadweaver.profileAb=on} 时工作；</li>
 *   <li>单次会话最多录 {@value #MAX_RECORDS} 条、每条最多 {@value #MAX_STATIONS} 站 ——
 *       否则一条 2000 站的路 × 几百条会写出几十 MB；</li>
 *   <li>任何 IO/解析异常都吞掉。观测设施绝不能让玩家掉帧或崩游戏。</li>
 * </ol>
 */
public final class ProfileTerrainRecorder {

    /** 单次会话最多录多少条路。 */
    static final int MAX_RECORDS = 120;
    /** 每条最多录多少站（超长路截断，够用且不至于写爆磁盘）。 */
    static final int MAX_STATIONS = 2048;

    /** 默认输出路径（相对游戏根目录）。 */
    private static final Path DEFAULT_OUT =
            Paths.get("logs", "roadweaver-terrain-ab.tsv");

    private static int recorded = 0;
    private static boolean disabled = false;

    private ProfileTerrainRecorder() {
    }

    /** 一个可重放的样本。 */
    public record Sample(String tag, int[] terrain, boolean[] crossing) {
        public Sample {
            terrain = terrain.clone();
            crossing = crossing == null ? new boolean[terrain.length] : crossing.clone();
        }

        @Override
        public int[] terrain() {
            return terrain.clone();
        }

        @Override
        public boolean[] crossing() {
            return crossing.clone();
        }
    }

    /** 是否启用（与 AB 观察器同一个开关）。 */
    public static boolean enabled() {
        return ProfileAbObserver.enabled();
    }

    /**
     * 记录一条路。失败静默 —— 观测设施不许影响主流程。
     */
    public static void record(String tag, int[] terrainY, boolean[] crossing) {
        recordTo(DEFAULT_OUT, tag, terrainY, crossing);
    }

    /** 记录到指定文件（测试用；顺带避免测试污染游戏目录）。 */
    public static void recordTo(Path file, String tag, int[] terrainY, boolean[] crossing) {
        if (!enabled() || disabled) return;
        if (terrainY == null || terrainY.length == 0) return;
        if (recorded >= MAX_RECORDS) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder(terrainY.length * 4);
            sb.append(tag == null ? "?" : tag).append('\t');
            int n = Math.min(terrainY.length, MAX_STATIONS);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(terrainY[i]);
            }
            sb.append('\t');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(crossing != null && i < crossing.length && crossing[i] ? '1' : '0');
            }
            sb.append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            recorded++;
        } catch (Throwable t) {
            // 磁盘满 / 只读目录 / 编码问题 —— 一律放弃录制，不再重试
            disabled = true;
        }
    }

    /** 已录条数（诊断/测试用）。 */
    public static int recordedCount() {
        return recorded;
    }

    /** 重置计数（测试用）。 */
    public static void resetForTest() {
        recorded = 0;
        disabled = false;
    }

    // ── 解析（CI 重放用）────────────────────────────────────────

    /** 解析一行；格式不对返回 {@code null} 而不是抛异常。 */
    public static Sample parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\t", -1);
        if (parts.length < 3) return null;
        try {
            int[] terrain = parseInts(parts[1]);
            boolean[] crossing = parseBools(parts[2]);
            if (terrain.length == 0) return null;
            if (crossing.length != terrain.length) {
                // 容错：长度不一致时按「不跨水」补齐，避免一条坏行废掉整个重放
                boolean[] fixed = new boolean[terrain.length];
                System.arraycopy(crossing, 0, fixed, 0, Math.min(crossing.length, terrain.length));
                crossing = fixed;
            }
            return new Sample(parts[0], terrain, crossing);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读整个文件；坏行跳过并计数。 */
    public static List<Sample> readAll(Path file) throws IOException {
        List<Sample> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Sample s = parse(line);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static int[] parseInts(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static boolean[] parseBools(String s) {
        if (s.isEmpty()) return new boolean[0];
        String[] parts = s.split(",");
        boolean[] out = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = "1".equals(parts[i].trim());
        return out;
    }
}