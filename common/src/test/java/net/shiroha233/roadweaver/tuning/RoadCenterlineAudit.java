package net.shiroha233.roadweaver.tuning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.postprocess.SawtoothMetric;

/**
 * 离线道路中心线审计台（2026-11-08，0.9.027 起）。
 *
 * <p>读存档里 RoadWeaver 持久化的路面 NBT，把「一条路实际长成什么样」压成一组可比较的数字。
 * 在此之前所有后处理/选线改动都没有客观尺子：{@code route SHAPE} 只覆盖当次规划且只有 raw/final
 * 两行，{@code route QUALITY} 没有弯曲系数与横向抖动，唯一的角度判据 {@code SHARP_TURN_DEG=90}
 * 又是严格大于 90 而实际转向角恰好是 45/90 ⇒ 结构性抓不到。
 *
 * <p>这不是单测，不参与 {@code test} 聚合。跑法：
 * <pre>
 *   gradlew :common:roadCenterlineAudit -PrqArgs="--save &lt;存档目录&gt; --all"
 *   gradlew :common:roadCenterlineAudit -PrqArgs="--save &lt;存档目录&gt; --window -700,-560,180,340 --ascii"
 * </pre>
 *
 * <p>指标口径（与 {@code SawtoothMetric} 保持同一套语汇，但这里量的是**落盘后的最终中心线**）：
 * <ul>
 *   <li><b>弯曲系数</b> {@code length / chord}：路径长比首末直距。1.0 = 直线，2.3 = 走了两倍多的路。</li>
 *   <li><b>横向抖动</b> {@code |lat|}：相对前后各 {@value #LATERAL_HALF} 站的**局部弦**的横向偏移，
 *       与首末大弦无关（否则弯曲山路天然几十上百格，判据永不成立 —— 0.9.022 的教训）。</li>
 *   <li><b>符号翻转</b>：横向偏移符号变化次数，死区 {@value #FLIP_DEADBAND} 格。这是「来回摆动」的
 *       唯一可靠代理量；转向角不行，因为 1 格栅格上任何 45° 直线都会给出交替 0°/90°。</li>
 *   <li><b>DP 剥离</b>：对中心线做 Douglas-Peucker 到容差 1/2/4/8/12 后再量长度，
 *       用来把「宏观折返」与「微锯齿」分开算账。</li>
 *   <li><b>宏观反转</b>：按 8/16/24 站重采样后航向变化 &gt;60° / &gt;120° 的次数。</li>
 * </ul>
 *
 * <p>NBT 读取是自带的最小实现（不依赖 Minecraft 的 NbtIo），因此这个工具既能挂进 gradle 主回归，
 * 也能在只有 JDK 的机器上直接 {@code java} 跑，不需要启动游戏。
 */
public final class RoadCenterlineAudit {

    /** 横向偏移的局部弦半窗（站）。与 SawtoothMetric 的 LOCAL_CHORD_HALF_WINDOW 同值。 */
    private static final int LATERAL_HALF = 16;
    /** 横向偏移符号翻转的死区（格）。整数栅格 ±0.5 的取整噪声不算翻转。 */
    private static final double FLIP_DEADBAND = 0.5D;
    /** DP 剥离用的容差序列。 */
    private static final double[] DP_TOLERANCES = {1.0D, 2.0D, 4.0D, 8.0D, 12.0D};
    /** 宏观反转统计用的重采样步长（站）。 */
    private static final int[] HEADING_STEPS = {8, 16, 24};

    private RoadCenterlineAudit() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> opt = parseArgs(args);
        Path roadsDir = resolveRoadsDir(opt);
        if (roadsDir == null || !Files.isDirectory(roadsDir)) {
            System.out.println("找不到路面目录。用 --save <存档目录> 或 --roads <.../persistence/roads/minecraft_overworld/roads>");
            return;
        }
        int[] window = parseWindow(opt.get("window"));
        boolean ascii = opt.containsKey("ascii");
        int limit = opt.containsKey("limit") ? Integer.parseInt(opt.get("limit")) : Integer.MAX_VALUE;
        Path out = opt.containsKey("out") ? Paths.get(opt.get("out")) : null;

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(roadsDir, "*.nbt")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);

        List<String> csv = new ArrayList<>();
        csv.add("file,stations,xMin,xMax,zMin,zMax,yMin,yMax,length,chord,detour,"
                + "legP50,legP95,turnP50,turnP90,turnMax,turnGt45,turnGt90,"
                + "latP50,latP95,latMax,flips,flipsPer100,dyP50,dyP95,"
                + "dp1Len,dp2Len,dp4Len,dp8Len,dp12Len,rev8,rev16,rev24");

        int printed = 0;
        for (Path f : files) {
            if (printed >= limit) break;
            List<int[]> stations;
            int[] targetY;
            try {
                Map<String, Object> root = readNbt(f);
                @SuppressWarnings("unchecked")
                Map<String, Object> road = (Map<String, Object>) root.get("road");
                if (road == null) continue;
                targetY = (int[]) road.get("target_y");
                @SuppressWarnings("unchecked")
                List<Object> placements = (List<Object>) road.get("placements");
                if (placements == null || placements.isEmpty()) continue;
                stations = new ArrayList<>(placements.size());
                for (Object o : placements) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> placement = (Map<String, Object>) o;
                    int[] mid = (int[]) placement.get("middle_pos");
                    if (mid != null && mid.length >= 3) stations.add(new int[]{mid[0], mid[1], mid[2]});
                }
            } catch (RuntimeException | IOException ex) {
                System.out.printf("跳过 %s：%s%n", f.getFileName(), ex);
                continue;
            }
            if (stations.size() < 2) continue;

            int lo = 0;
            int hi = stations.size() - 1;
            if (window != null) {
                int first = -1;
                int last = -1;
                for (int i = 0; i < stations.size(); i++) {
                    int[] s = stations.get(i);
                    if (s[0] >= window[0] && s[0] <= window[1] && s[2] >= window[2] && s[2] <= window[3]) {
                        if (first < 0) first = i;
                        last = i;
                    }
                }
                if (first < 0) continue;
                lo = first;
                hi = last;
            }
            List<int[]> seq = stations.subList(lo, hi + 1);

            // ── 基础范围 ──
            int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
            int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
            int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
            for (int[] s : seq) {
                xMin = Math.min(xMin, s[0]); xMax = Math.max(xMax, s[0]);
                zMin = Math.min(zMin, s[2]); zMax = Math.max(zMax, s[2]);
            }
            if (targetY != null) {
                for (int i = Math.max(0, lo); i <= Math.min(targetY.length - 1, hi); i++) {
                    yMin = Math.min(yMin, targetY[i]); yMax = Math.max(yMax, targetY[i]);
                }
            }
            if (yMin == Integer.MAX_VALUE) { yMin = 0; yMax = 0; }

            double length = 0.0D;
            double[] legs = new double[seq.size() - 1];
            for (int i = 1; i < seq.size(); i++) {
                legs[i - 1] = Math.hypot(seq.get(i)[0] - seq.get(i - 1)[0], seq.get(i)[2] - seq.get(i - 1)[2]);
                length += legs[i - 1];
            }
            double chord = Math.hypot(seq.get(seq.size() - 1)[0] - seq.get(0)[0],
                    seq.get(seq.size() - 1)[2] - seq.get(0)[2]);
            double detour = chord > 0.001D ? length / chord : Double.NaN;

            List<Double> turnList = new ArrayList<>();
            int turnGt45 = 0;
            int turnGt90 = 0;
            for (int i = 1; i < seq.size() - 1; i++) {
                double ax = seq.get(i)[0] - seq.get(i - 1)[0];
                double az = seq.get(i)[2] - seq.get(i - 1)[2];
                double bx = seq.get(i + 1)[0] - seq.get(i)[0];
                double bz = seq.get(i + 1)[2] - seq.get(i)[2];
                double na = Math.hypot(ax, az);
                double nb = Math.hypot(bx, bz);
                if (na < 1e-6D || nb < 1e-6D) continue;
                double cosine = Math.max(-1.0D, Math.min(1.0D, (ax * bx + az * bz) / (na * nb)));
                double deg = Math.toDegrees(Math.acos(cosine));
                turnList.add(deg);
                if (deg > 45.0D) turnGt45++;
                if (deg > 90.0D) turnGt90++;
            }

            double[] lat = lateralOffsets(seq);
            int flips = 0;
            int sign = 0;
            for (double v : lat) {
                int s = Math.abs(v) < FLIP_DEADBAND ? 0 : (v > 0 ? 1 : -1);
                if (s == 0) continue;
                if (sign != 0 && s != sign) flips++;
                sign = s;
            }
            // 分位数必须取 |lat| 的分布，不能取带符号分布（带符号的中位数恒 ≈0，会把抖动读成 0）
            double latMax = 0.0D;
            double[] absLat = new double[lat.length];
            for (int i = 0; i < lat.length; i++) {
                absLat[i] = Math.abs(lat[i]);
                latMax = Math.max(latMax, absLat[i]);
            }

            double[] dy = new double[Math.max(0, seq.size() - 1)];
            for (int i = 1; i < seq.size(); i++) dy[i - 1] = Math.abs(seq.get(i)[1] - seq.get(i - 1)[1]);

            double[] dpLen = new double[DP_TOLERANCES.length];
            int[] dpPts = new int[DP_TOLERANCES.length];
            for (int t = 0; t < DP_TOLERANCES.length; t++) {
                int[] r = dpSimplify(seq, DP_TOLERANCES[t]);
                dpLen[t] = r[0] / 1000.0D;
                dpPts[t] = r[1];
            }

            int[] rev60 = new int[HEADING_STEPS.length];
            int[] rev120 = new int[HEADING_STEPS.length];
            for (int t = 0; t < HEADING_STEPS.length; t++) {
                double[] deltas = headingDeltas(seq, HEADING_STEPS[t]);
                for (double d : deltas) {
                    if (Math.abs(d) > 60.0D) rev60[t]++;
                    if (Math.abs(d) > 120.0D) rev120[t]++;
                }
            }

            String file = f.getFileName().toString();
            System.out.printf(Locale.ROOT,
                    "%n── %s ──%n  站数 %d（索引 %d..%d）  x %d..%d  z %d..%d  y %d..%d%n"
                            + "  弯曲系数 %.2f（长 %.1f / 直距 %.1f）   站间距 p50 %.2f / p95 %.2f%n"
                            + "  转向角 p50 %.1f° / p90 %.1f° / max %.1f°   >45° %d 站（%.0f%%）  >90° %d 站%n"
                            + "  横向抖动 |lat| p50 %.2f / p95 %.2f / max %.2f 格   符号翻转 %d 次（每百站 %.1f）%n"
                            + "  纵断面 |Δy| p50 %.1f / p95 %.1f / max %.1f%n"
                            + "  DP 剥离：",
                    file, seq.size(), lo, hi, xMin, xMax, zMin, zMax, yMin, yMax,
                    detour, length, chord,
                    pct(legs, 0.5D), pct(legs, 0.95D),
                    pct(turnList, 0.5D), pct(turnList, 0.9D),
                    turnList.isEmpty() ? 0.0D : Collections.max(turnList),
                    turnGt45, 100.0D * turnGt45 / Math.max(1, turnList.size()), turnGt90,
                    pct(absLat, 0.5D), pct(absLat, 0.95D), latMax,
                    flips, 100.0D * flips / Math.max(1, seq.size()),
                    pct(dy, 0.5D), pct(dy, 0.95D), dy.length == 0 ? 0.0D : max(dy));
            for (int t = 0; t < DP_TOLERANCES.length; t++) {
                System.out.printf(Locale.ROOT, "     tol %-4s → %3d 点 / %.1f 格 / %.2f×   ",
                        trim(DP_TOLERANCES[t]), dpPts[t], dpLen[t], chord > 0.001D ? dpLen[t] / chord : Double.NaN);
                if (t % 2 == 1) System.out.println();
            }
            System.out.println();
            System.out.print("  宏观反转：");
            for (int t = 0; t < HEADING_STEPS.length; t++) {
                System.out.printf(Locale.ROOT, "%d站采样 %d次>60° / %d次>120°   ",
                        HEADING_STEPS[t], rev60[t], rev120[t]);
            }
            System.out.println();

            if (ascii) printAscii(seq);

            csv.add(String.format(Locale.ROOT,
                    "%s,%d,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%.3f,%.2f,%.2f,%.1f,%.1f,%.1f,%d,%d,"
                            + "%.2f,%.2f,%.2f,%d,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d",
                    file, seq.size(), xMin, xMax, zMin, zMax, yMin, yMax, length, chord, detour,
                    pct(legs, 0.5D), pct(legs, 0.95D),
                    pct(turnList, 0.5D), pct(turnList, 0.9D),
                    turnList.isEmpty() ? 0.0D : Collections.max(turnList), turnGt45, turnGt90,
                    pct(absLat, 0.5D), pct(absLat, 0.95D), latMax, flips,
                    100.0D * flips / Math.max(1, seq.size()),
                    pct(dy, 0.5D), pct(dy, 0.95D),
                    dpLen[0], dpLen[1], dpLen[2], dpLen[3], dpLen[4],
                    rev120[0], rev120[1], rev120[2]));
            printed++;
        }

        if (printed == 0) System.out.println("没有匹配的路面文件（窗口内 0 站）。");
        if (out != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.write(out, csv, StandardCharsets.UTF_8);
            System.out.printf("%nCSV 已写 %s（%d 行）%n", out.toAbsolutePath(), csv.size());
        }
    }

    // ══════════════════════════ 指标 ══════════════════════════

    /** 相对前后各 LATERAL_HALF 站的局部弦的横向偏移（带符号，格）。 */
    private static double[] lateralOffsets(List<int[]> seq) {
        double[] out = new double[seq.size()];
        for (int i = 0; i < seq.size(); i++) {
            int[] a = seq.get(Math.max(0, i - LATERAL_HALF));
            int[] b = seq.get(Math.min(seq.size() - 1, i + LATERAL_HALF));
            double dx = b[0] - a[0];
            double dz = b[2] - a[2];
            double len = Math.hypot(dx, dz);
            if (len < 1e-6D) { out[i] = 0.0D; continue; }
            out[i] = ((seq.get(i)[0] - a[0]) * dz - (seq.get(i)[2] - a[2]) * dx) / len;
        }
        return out;
    }

    /** 按 step 站重采样后的航向变化（度，展开到 ±180）。 */
    private static double[] headingDeltas(List<int[]> seq, int step) {
        List<double[]> sample = new ArrayList<>();
        for (int i = 0; i < seq.size(); i += step) sample.add(new double[]{seq.get(i)[0], seq.get(i)[2]});
        if (sample.isEmpty()) return new double[0];
        if ((seq.size() - 1) % step != 0) {
            sample.add(new double[]{seq.get(seq.size() - 1)[0], seq.get(seq.size() - 1)[2]});
        }
        double[] headings = new double[Math.max(0, sample.size() - 1)];
        for (int i = 1; i < sample.size(); i++) {
            headings[i - 1] = Math.toDegrees(Math.atan2(sample.get(i)[1] - sample.get(i - 1)[1],
                    sample.get(i)[0] - sample.get(i - 1)[0]));
        }
        double[] deltas = new double[Math.max(0, headings.length - 1)];
        for (int i = 1; i < headings.length; i++) {
            double d = (headings[i] - headings[i - 1] + 180.0D) % 360.0D;
            if (d < 0) d += 360.0D;
            deltas[i - 1] = d - 180.0D;
        }
        return deltas;
    }

    /**
     * Douglas-Peucker（迭代栈实现，与 {@code PathPostProcessor.simplifyPath} 同算法）。
     *
     * @return {@code [长度 * 1000 取整, 保留点数]}
     */
    private static int[] dpSimplify(List<int[]> seq, double tolerance) {
        int n = seq.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        int[] stack = new int[2 * n + 4];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = n - 1;
        while (top >= 2) {
            int b = stack[--top];
            int a = stack[--top];
            if (b <= a + 1) continue;
            double ax = seq.get(a)[0], az = seq.get(a)[2];
            double dx = seq.get(b)[0] - ax, dz = seq.get(b)[2] - az;
            double len = Math.hypot(dx, dz);
            if (len < 1e-6D) len = 1.0D;
            double best = -1.0D;
            int bestIndex = -1;
            for (int i = a + 1; i < b; i++) {
                double d = Math.abs((seq.get(i)[0] - ax) * dz - (seq.get(i)[2] - az) * dx) / len;
                if (d > best) { best = d; bestIndex = i; }
            }
            if (best > tolerance && bestIndex > 0) {
                keep[bestIndex] = true;
                stack[top++] = a;
                stack[top++] = bestIndex;
                stack[top++] = bestIndex;
                stack[top++] = b;
            }
        }
        double total = 0.0D;
        int count = 0;
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            if (prev >= 0) {
                total += Math.hypot(seq.get(i)[0] - seq.get(prev)[0], seq.get(i)[2] - seq.get(prev)[2]);
            }
            prev = i;
            count++;
        }
        return new int[]{(int) Math.round(total * 1000.0D), count};
    }

    private static void printAscii(List<int[]> seq) {
        final int w = 96;
        final int h = 34;
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (int[] s : seq) {
            xMin = Math.min(xMin, s[0]); xMax = Math.max(xMax, s[0]);
            zMin = Math.min(zMin, s[2]); zMax = Math.max(zMax, s[2]);
        }
        int sx = Math.max(1, xMax - xMin);
        int sz = Math.max(1, zMax - zMin);
        char[][] grid = new char[h][w];
        for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        for (int i = 0; i < seq.size(); i++) {
            int cx = (int) ((long) (seq.get(i)[0] - xMin) * (w - 1) / sx);
            int cz = (int) ((long) (seq.get(i)[2] - zMin) * (h - 1) / sz);
            char c = '#';
            if (i == 0) c = 'S';
            else if (i == seq.size() - 1) c = 'E';
            grid[h - 1 - cz][cx] = c;
        }
        System.out.println("  ── 俯视（x 向右增大，z 向上增大）──");
        for (char[] row : grid) {
            String line = new String(row).stripTrailing();
            if (!line.isEmpty()) System.out.println("  |" + line);
        }
    }

    private static double pct(double[] values, double p) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int k = (int) Math.round((copy.length - 1) * p);
        k = Math.max(0, Math.min(copy.length - 1, k));
        return copy[k];
    }

    private static double pct(List<Double> values, double p) {
        if (values == null || values.isEmpty()) return Double.NaN;
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int k = (int) Math.round((copy.size() - 1) * p);
        k = Math.max(0, Math.min(copy.size() - 1, k));
        return copy.get(k);
    }

    private static double max(double[] values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) m = Math.max(m, v);
        return m;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ══════════════════════════ 参数 ══════════════════════════

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            if (key.equals("ascii") || key.equals("all")) { opt.put(key, "true"); continue; }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) opt.put(key, args[++i]);
            else opt.put(key, "true");
        }
        return opt;
    }

    private static int[] parseWindow(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            System.out.println("--window 要 4 个数：x0,x1,z0,z1");
            return null;
        }
        int[] w = new int[4];
        for (int i = 0; i < 4; i++) w[i] = Integer.parseInt(parts[i].trim());
        return w;
    }

    private static Path resolveRoadsDir(Map<String, String> opt) {
        if (opt.containsKey("roads")) return Paths.get(opt.get("roads"));
        String save = opt.get("save");
        if (save == null) return null;
        return Paths.get(save, "data", "roadweaver", "persistence", "roads", "minecraft_overworld", "roads");
    }

    // ══════════════════════════ 最小 NBT 读取（无 MC 依赖） ══════════════════════════

    private static Map<String, Object> readNbt(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                in.transferTo(bos);
                raw = bos.toByteArray();
            }
        }
        Cursor c = new Cursor(raw);
        int type = c.u1();
        c.str(); // root name，通常为空
        Object root = c.payload(type);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        return map;
    }

    private static final class Cursor {
        private final byte[] b;
        private int i;

        Cursor(byte[] data) { this.b = data; }

        int u1() { return b[i++] & 0xFF; }

        int strLen() { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

        String str() {
            int n = strLen();
            i += 2;
            String s = new String(b, i, n, StandardCharsets.UTF_8);
            i += n;
            return s;
        }

        int i4() {
            int v = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 4;
            return v;
        }

        long i8() {
            long v = 0L;
            for (int k = 0; k < 8; k++) v = (v << 8) | (b[i + k] & 0xFFL);
            i += 8;
            return v;
        }

        Object payload(int t) {
            switch (t) {
                case 1: return b[i++];
                case 2: { int v = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); i += 2; return (short) v; }
                case 3: return i4();
                case 4: return i8();
                case 5: { int v = i4(); return Float.intBitsToFloat(v); }
                case 6: return Double.longBitsToDouble(i8());
                case 7: { int n = i4(); i += n; return new int[]{n}; }
                case 8: return str();
                case 9: {
                    int et = u1();
                    int n = i4();
                    List<Object> list = new ArrayList<>(n);
                    for (int k = 0; k < n; k++) list.add(payload(et));
                    return list;
                }
                case 10: {
                    Map<String, Object> map = new LinkedHashMap<>();
                    while (true) {
                        int tt = u1();
                        if (tt == 0) break;
                        String name = str();
                        map.put(name, payload(tt));
                    }
                    return map;
                }
                case 11: {
                    int n = i4();
                    int[] arr = new int[n];
                    for (int k = 0; k < n; k++) arr[k] = i4();
                    return arr;
                }
                case 12: {
                    int n = i4();
                    long[] arr = new long[n];
                    for (int k = 0; k < n; k++) arr[k] = i8();
                    return arr;
                }
                case 0: return null;
                default: throw new IllegalStateException("未知 NBT tag " + t + " @ " + i);
            }
        }
    }
}
