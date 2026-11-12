package net.shiroha233.roadweaver.tuning;

import com.google.gson.JsonArray;
import net.shiroha233.roadweaver.planning.PlanningBoundsOps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PlanningConfig.PlanningAlgorithm;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.NetworkPlanner;
import net.shiroha233.roadweaver.planning.NetworkPlannerFactory;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.impl.KNNPlanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 0.8.420：路网拓扑离线测量台（test-only，不依赖 Minecraft 世界）。
 *
 * <p>「拓扑」= 哪些结构之间连路，位于寻路代价（{@code PotentialFieldPathfinder}，决定一条
 * 已定连线怎么走）之上。本测量台因此只看 2D 点位，不碰地形，可以直接吃真实存档里持久化的
 * 结构点位：{@code saves/<世界>/data/roadweaver/persistence/structures/<dim>/state.json}
 * 的 {@code structureLocations.structureInfos[].pos}。
 *
 * <p>生产链路（{@code RoadPlanningService.planRect}）原样复刻为三步：
 * <ol>
 *   <li>矩形内全部结构点 → {@code planPrimaryEdges}（按 {@code planningAlgorithm} 选规划器，
 *       上限 {@link RoadConstants#DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS}）</li>
 *   <li>{@code KNNPlanner.connectComponents(points, primary, 1536, 35.0, 3)} 补连通</li>
 *   <li>primary + bridges 即最终连边</li>
 * </ol>
 *
 * <p>跑法示例：
 * <pre>
 *   :common:topologyHarness -PrqArgs="--corpus &lt;state.json&gt; --list"
 *   :common:topologyHarness -PrqArgs="--corpus &lt;state.json&gt; --compare"
 *   :common:topologyHarness -PrqArgs="--corpus &lt;dir&gt; --compare --knn 3;2.5;30;3;35;3 --out build/tuning/topo.csv"
 * </pre>
 *
 * <p>参数：
 * <ul>
 *   <li>{@code --corpus <path>}（可重复）state.json 文件，或包含若干存档的目录（递归搜 state.json）</li>
 *   <li>{@code --window <blocks>} 切片边长，默认 2048（对齐 initialPlanRadiusChunks=64 ⇒ 64*2*16）</li>
 *   <li>{@code --window-step <blocks>} 切片步长，默认 = window/2（50% 重叠，样本更多）</li>
 *   <li>{@code --min-points <n>} 切片最少点数，默认 3</li>
 *   <li>{@code --synth <n>} 追加 n 个合成点场（3 种排布原型），默认 0</li>
 *   <li>{@code --synth-span <blocks>} 合成点场边长，默认 8192</li>
 *   <li>{@code --compare} 跑全部 5 个规划器（出厂参数）</li>
 *   <li>{@code --knn "k;alpha;minAngle;degCap;compMinAngle;compDegCap"}（可重复）追加一个 KNN 配置</li>
 *   <li>{@code --snap "minProjT;maxProjT;maxStretch;edgeBias[;pointLimit]"}（可重复）追加一个
 *       SNAP_BRANCH 变体；走 {@link SnapBranchModel} 副本，因为出厂常量是 private static final</li>
 *   <li>{@code --verify-replica} 只跑副本与真实规划器的逐边对拍（改副本后必须先跑通）</li>
 *   <li>{@code --list} 只列出语料规模</li>
 *   <li>{@code --out <csv>} 同时把明细写成 CSV</li>
 * </ul>
 */
public final class TopologyHarness {

    /** 与 {@code RoadPlanningService.planPrimaryEdges} 一致。 */
    private static final int MAX_EDGE_BLOCKS = RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;

    /** 诊断开关：跳过桥梁阶段，只看规划器本体产出的拓扑。 */
    private static boolean primaryOnly = false;
    /** 与 {@code RoadPlanningService.planRect} 的补连通调用一致。 */
    private static final int BRIDGE_JOIN_BLOCKS = RoadConstants.DEFAULT_BRIDGE_JOIN_LEN_BLOCKS;

    /** 真实存档实测结构密度（个/格²）：57 点 / 10000×10000 ⇒ 5.7e-7。 */
    private static final double REAL_DENSITY = 5.7e-7;

    /** 交汇角小于该值算「发夹/锐角分叉」。 */
    private static final double HAIRPIN_DEG = 30.0;

    public static void main(String[] args) throws IOException {
        List<String> corpusArgs = new ArrayList<>();
        int window = 2048;
        int windowStep = -1;
        int minPoints = 3;
        int synth = 0;
        int synthSpan = 8192;
        boolean compare = false;
        boolean listOnly = false;
        boolean verifyReplica = false;
        String out = null;
        List<String> knnSpecs = new ArrayList<>();
        List<String> snapSpecs = new ArrayList<>();
        String compSpec = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus" -> corpusArgs.add(args[++i]);
                case "--window" -> window = Integer.parseInt(args[++i]);
                case "--window-step" -> windowStep = Integer.parseInt(args[++i]);
                case "--min-points" -> minPoints = Integer.parseInt(args[++i]);
                case "--synth" -> synth = Integer.parseInt(args[++i]);
                case "--synth-span" -> synthSpan = Integer.parseInt(args[++i]);
                case "--compare" -> compare = true;
                case "--list" -> listOnly = true;
                case "--verify-replica" -> verifyReplica = true;
                case "--out" -> out = args[++i];
                case "--knn" -> knnSpecs.add(args[++i]);
                case "--snap" -> snapSpecs.add(args[++i]);
                case "--comp" -> compSpec = args[++i];
                case "--primary-only" -> primaryOnly = true;
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        if (windowStep <= 0) windowStep = Math.max(1, window / 2);

        List<Case> cases = new ArrayList<>();
        for (String spec : corpusArgs) {
            Path p = Path.of(spec);
            for (Path stateFile : discoverStateFiles(p)) {
                List<BlockPos> pts = loadStructures(stateFile);
                if (pts.size() < 2) continue;
                String label = stateFile.getParent() == null ? stateFile.toString() : stateFile.getParent().toString();
                label = shortLabel(label);
                cases.addAll(slice(label, pts, window, windowStep, minPoints));
            }
        }
        if (synth > 0) {
            cases.addAll(synthetic(synth, synthSpan, minPoints));
        }

        if (cases.isEmpty()) {
            System.out.println("no corpus cases; pass --corpus <state.json> or --synth <n>");
            return;
        }

        if (listOnly) {
            System.out.println("CASE,points");
            for (Case c : cases) {
                System.out.printf(Locale.ROOT, "%s,%d%n", c.name(), c.points().size());
            }
            System.out.printf(Locale.ROOT, "TOTAL cases=%d points=%d%n", cases.size(), totalPoints(cases));
            return;
        }

        if (verifyReplica) {
            List<List<BlockPos>> corpus = new ArrayList<>();
            for (Case c : cases) corpus.add(c.points());
            System.out.printf(Locale.ROOT,
                    "=== SnapBranchModel vs SnapBranchPlanner replica check (cases=%d, maxEdge=%d) ===%n",
                    corpus.size(), MAX_EDGE_BLOCKS);
            int bad = SnapBranchModel.verifyAgainstProduction(corpus, MAX_EDGE_BLOCKS);
            System.out.printf(Locale.ROOT,
                    bad == 0 ? "REPLICA OK: all %d cases byte-identical to production%n"
                             : "REPLICA BAD: %d/%d cases differ%n",
                    bad == 0 ? corpus.size() : bad, corpus.size());
            return;
        }

        List<PlannerConfig> configs = new ArrayList<>();
        if (compare) {
            for (PlanningAlgorithm a : PlanningAlgorithm.values()) {
                configs.add(PlannerConfig.catalog(a));
            }
        }
        for (String spec : knnSpecs) {
            configs.add(PlannerConfig.knn(spec));
        }
        for (String spec : snapSpecs) {
            configs.add(PlannerConfig.snap(spec, compSpec));
        }
        if (configs.isEmpty()) {
            // 默认跑「当前出厂值」两个：KNN 出厂参数 + SNAP_BRANCH（历史默认算法）。
            configs.add(PlannerConfig.knn("2;1.8;40;2;35;3"));
            configs.add(PlannerConfig.catalog(PlanningAlgorithm.SNAP_BRANCH));
        }

        List<Row> rows = new ArrayList<>();
        for (Case c : cases) {
            for (PlannerConfig pc : configs) {
                Metrics m;
                try {
                    List<StructureConnection> edges = runPipeline(pc, c.points());
                    m = Metrics.measure(c.points(), edges);
                } catch (RuntimeException ex) {
                    System.out.printf(Locale.ROOT, "FAILED case=%s config=%s : %s%n",
                            c.name(), pc.name(), ex);
                    continue;
                }
                rows.add(new Row(c, pc, m));
            }
        }

        StringBuilder csv = new StringBuilder();
        csv.append("case,points,planner,k,alpha,minAngle,degCap,compMinAngle,compDegCap,"
                + "snapMinProjT,snapMaxProjT,snapStretch,snapBias,snapPointLimit,snapMinForkAngleDeg,snapMinNodeAngleDeg,"
                + "vertices,edges,components,isolated,connectedInput,coveragePct,maxDeg,junctions,bends,"
                + "sharpJunctions,sharpBends,cycles,"
                + "totalLen,meanLen,p95Len,maxLen,mstLen,mstRatio,minJunctionAngleMean,minBendAngleMean\n");
        for (Row r : rows) {
            csv.append(r.toCsv()).append('\n');
        }
        if (out != null) {
            Path target = Path.of(out);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, csv.toString(), StandardCharsets.UTF_8);
            System.out.println("csv written: " + target.toAbsolutePath());
        }

        System.out.println();
        System.out.printf(Locale.ROOT,
                "nodeAngleGuard: checks=%d fires=%d%n",
                SnapBranchModel.nodeGuardChecks, SnapBranchModel.nodeGuardFires);
        System.out.println("=== per-config aggregate (mean over cases, 结构点加权不适用——各 case 等权) ===");
        System.out.printf(Locale.ROOT,
                "%-40s %6s %6s %7s %7s %7s %7s %8s %8s %8s %8s%n",
                "config", "edge/P", "iso%", "junc%", "cyc/P", "len/P", "mstRat", "sharpJ%", "sharpB%", "minJuncAng", "minBendAng");
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row r : rows) grouped.computeIfAbsent(r.config().name(), k -> new ArrayList<>()).add(r);
        for (Map.Entry<String, List<Row>> e : grouped.entrySet()) {
            List<Row> rs = e.getValue();
            int n = rs.size();
            double edgePerPoint = 0, iso = 0, junc = 0, cyc = 0, lenPerPoint = 0;
            double mstRatio = 0, sharpJ = 0, sharpB = 0;
            double juncAngleSum = 0, bendAngleSum = 0;
            int juncCases = 0, bendCases = 0;
            for (Row r : rs) {
                Metrics m = r.metrics();
                double pts = Math.max(1, r.metrics().nInput);
                edgePerPoint += m.edges / pts;
                iso += 100.0 * m.isolated / pts;
                junc += 100.0 * m.junctions / Math.max(1, m.vertices);
                cyc += m.cycles / pts;
                lenPerPoint += m.totalLen / pts;
                mstRatio += m.mstRatio;
                sharpJ += m.sharpJunctionShare() * 100.0;
                sharpB += m.sharpBendShare() * 100.0;
                // 角度均值只在**真有分叉口/拐点**的 case 上平均：没有分叉的 case 该贡献 0，
                // 否则会被稀释（早前一版就因此把 KNN 的 27.7° 误报成 25.4°）。
                if (m.junctions > 0) {
                    juncAngleSum += m.minJunctionAngleMean;
                    juncCases++;
                }
                if (m.bends > 0) {
                    bendAngleSum += m.minBendAngleMean;
                    bendCases++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "%-40s %6.2f %6.1f %7.1f %7.3f %7.0f %8.2f %8.1f %8.1f %10.1f %10.1f%n",
                    e.getKey(), edgePerPoint / n, iso / n, junc / n, cyc / n, lenPerPoint / n,
                    mstRatio / n, sharpJ / n, sharpB / n,
                    juncCases == 0 ? 0 : juncAngleSum / juncCases,
                    bendCases == 0 ? 0 : bendAngleSum / bendCases);
        }
        System.out.printf(Locale.ROOT, "cases=%d configs=%d rows=%d%n", cases.size(), configs.size(), rows.size());
    }

    // ------------------------------------------------------------------ corpus

    private record Case(String name, List<BlockPos> points) {}

    private static List<Path> discoverStateFiles(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            System.out.println("corpus path does not exist: " + root);
            return List.of();
        }
        if (Files.isRegularFile(root)) return List.of(root);
        List<Path> all;
        try (var stream = Files.walk(root)) {
            all = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("state.json"))
                    .sorted()
                    .toList();
        }
        // 指到 saves/ 这种大目录时，优先只认 roadweaver 自己的持久化目录；
        // 若一个都没有（用户直接指了一个只放 state.json 的语料目录），则全收。
        List<Path> canonical = all.stream()
                .filter(p -> p.toString().replace('\\', '/').contains("persistence/structures"))
                .toList();
        return canonical.isEmpty() ? all : canonical;
    }

    private static List<BlockPos> loadStructures(Path stateFile) throws IOException {
        String text = Files.readString(stateFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonObject locations = root.getAsJsonObject("structureLocations");
        if (locations == null) return List.of();
        JsonArray infos = locations.getAsJsonArray("structureInfos");
        if (infos == null) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (JsonElement element : infos) {
            JsonObject entry = element.getAsJsonObject();
            JsonObject pos = entry.getAsJsonObject("pos");
            if (pos == null) continue;
            out.add(new BlockPos(pos.get("x").getAsInt(), 0, pos.get("z").getAsInt()));
        }
        return dedup2d(out);
    }

    private static List<BlockPos> dedup2d(List<BlockPos> in) {
        Map<Long, BlockPos> unique = new LinkedHashMap<>();
        for (BlockPos p : in) {
            unique.putIfAbsent(key2d(p.getX(), p.getZ()), new BlockPos(p.getX(), 0, p.getZ()));
        }
        return new ArrayList<>(unique.values());
    }

    private static long key2d(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static String shortLabel(String path) {
        String norm = path.replace('\\', '/');
        int idx = norm.indexOf("/saves/");
        return idx >= 0 ? norm.substring(idx + 7) : norm;
    }

    /** 按生产矩形边长切片；整份点集也单独作为一个 case（跨矩形全景，压力测试用）。 */
    private static List<Case> slice(String label, List<BlockPos> pts, int window, int step, int minPoints) {
        List<Case> out = new ArrayList<>();
        out.add(new Case(label + "|ALL", pts));
        if (window <= 0 || pts.size() <= minPoints) return out;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : pts) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        Set<String> seen = new HashSet<>();
        for (int x0 = minX; x0 <= maxX; x0 += step) {
            for (int z0 = minZ; z0 <= maxZ; z0 += step) {
                List<BlockPos> sub = new ArrayList<>();
                for (BlockPos p : pts) {
                    if (p.getX() >= x0 && p.getX() < x0 + window && p.getZ() >= z0 && p.getZ() < z0 + window) {
                        sub.add(p);
                    }
                }
                if (sub.size() < minPoints) continue;
                if (!seen.add(fingerprint(sub))) continue;
                out.add(new Case(label + "|W" + x0 + "_" + z0, sub));
            }
        }
        return out;
    }

    private static String fingerprint(List<BlockPos> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 12);
        for (BlockPos p : pts) sb.append(p.getX()).append(',').append(p.getZ()).append(';');
        return sb.toString();
    }

    private static List<Case> synthetic(int count, int span, int minPoints) {
        List<Case> out = new ArrayList<>();
        Random rnd = new Random(20261015L);
        String[] kinds = {"UNIFORM", "CLUSTERED", "GRID"};
        for (int i = 0; i < count; i++) {
            String kind = kinds[i % kinds.length];
            int want = Math.max(minPoints, (int) Math.round(REAL_DENSITY * (double) span * (double) span));
            List<BlockPos> pts = switch (kind) {
                case "CLUSTERED" -> clustered(rnd, span, want);
                case "GRID" -> grid(rnd, span, want);
                default -> uniform(rnd, span, want);
            };
            if (pts.size() >= 2) out.add(new Case("SYNTH|" + kind + i, pts));
        }
        return out;
    }

    private static List<BlockPos> uniform(Random rnd, int span, int want) {
        List<BlockPos> out = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            out.add(new BlockPos(rnd.nextInt(span), 0, rnd.nextInt(span)));
        }
        return dedup2d(out);
    }

    private static List<BlockPos> clustered(Random rnd, int span, int want) {
        int centers = Math.max(2, want / 6);
        double[][] c = new double[centers][2];
        for (int i = 0; i < centers; i++) {
            c[i][0] = rnd.nextDouble() * span;
            c[i][1] = rnd.nextDouble() * span;
        }
        double sigma = span / 22.0;
        List<BlockPos> out = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            double[] base = c[rnd.nextInt(centers)];
            int x = (int) Math.round(base[0] + rnd.nextGaussian() * sigma);
            int z = (int) Math.round(base[1] + rnd.nextGaussian() * sigma);
            out.add(new BlockPos(clamp(x, 0, span - 1), 0, clamp(z, 0, span - 1)));
        }
        return dedup2d(out);
    }

    private static List<BlockPos> grid(Random rnd, int span, int want) {
        int perSide = Math.max(2, (int) Math.round(Math.sqrt(want)));
        double step = (double) span / perSide;
        int jitter = (int) Math.max(1, step / 3);
        List<BlockPos> out = new ArrayList<>(perSide * perSide);
        for (int ix = 0; ix < perSide; ix++) {
            for (int iz = 0; iz < perSide; iz++) {
                int x = (int) Math.round(ix * step) + rnd.nextInt(jitter * 2 + 1) - jitter;
                int z = (int) Math.round(iz * step) + rnd.nextInt(jitter * 2 + 1) - jitter;
                out.add(new BlockPos(clamp(x, 0, span - 1), 0, clamp(z, 0, span - 1)));
            }
        }
        return dedup2d(out);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int totalPoints(List<Case> cases) {
        int sum = 0;
        for (Case c : cases) sum += c.points().size();
        return sum;
    }

    // ------------------------------------------------------------------ pipeline

    private record PlannerConfig(String name, PlanningAlgorithm algorithm, double[] knn,
                                 SnapBranchModel.Params snap) {

        static PlannerConfig catalog(PlanningAlgorithm algorithm) {
            return new PlannerConfig(algorithm.name(), algorithm, null, null);
        }

        static PlannerConfig knn(String spec) {
            String[] parts = spec.split(";");
            if (parts.length != 6) {
                throw new IllegalArgumentException(
                        "--knn needs k;alpha;minAngle;degCap;compMinAngle;compDegCap but got: " + spec);
            }
            double[] v = new double[6];
            for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(parts[i].trim());
            String name = String.format(Locale.ROOT, "KNN|k=%.0f a=%.2f ang=%.0f cap=%.0f | comp ang=%.0f cap=%.0f",
                    v[0], v[1], v[2], v[3], v[4], v[5]);
            return new PlannerConfig(name, PlanningAlgorithm.KNN, v, null);
        }

        /**
         * {@code --snap "minProjT;maxProjT;maxStretch;edgeBias[;fallbackPointLimit[;minForkAngleDeg[;minNodeAngleDeg]]]"}
         *
         * <p>{@code compSpec}（来自 {@code --comp "minAngle;degCap"}）覆盖桥梁阶段的
         * {@code connectComponents} 夹角/度数上限——它无条件生效，是 SNAP_BRANCH 也走的那条路。
         */
        static PlannerConfig snap(String spec, String compSpec) {
            String[] parts = spec.split(";");
            if (parts.length < 4 || parts.length > 7) {
                throw new IllegalArgumentException(
                        "--snap needs minProjT;maxProjT;maxStretch;edgeBias[;fallbackPointLimit[;minForkAngleDeg[;minNodeAngleDeg]]] but got: " + spec);
            }
            SnapBranchModel.Params p = new SnapBranchModel.Params(
                    parts.length >= 5 ? Integer.parseInt(parts[4].trim()) : 700,
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    parts.length >= 6 ? Double.parseDouble(parts[5].trim()) : 0.0,
                    parts.length >= 7 ? Double.parseDouble(parts[6].trim()) : 0.0);
            double compMin = RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG;
            double compDeg = RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP;
            if (compSpec != null) {
                String[] c = compSpec.split(";");
                if (c.length != 2) throw new IllegalArgumentException("--comp needs minAngle;degCap but got: " + compSpec);
                compMin = Double.parseDouble(c[0].trim());
                compDeg = Double.parseDouble(c[1].trim());
            }
            double[] comp = new double[]{0, 0, 0, 0, compMin, compDeg};
            String name = "SNAP|" + p.label()
                    + (compSpec == null ? "" : String.format(Locale.ROOT, " comp>=%.0f/d%d", compMin, (int) compDeg));
            return new PlannerConfig(name, PlanningAlgorithm.SNAP_BRANCH, comp, p);
        }
    }

    private static List<StructureConnection> runPipeline(PlannerConfig pc, List<BlockPos> points) {
        List<StructureConnection> primary;
        if (pc.snap() != null) {
            primary = SnapBranchModel.plan(points, MAX_EDGE_BLOCKS, pc.snap());
        } else {
            NetworkPlanner planner = NetworkPlannerFactory.create(pc.algorithm());
            if (pc.knn() != null && planner instanceof KNNPlanner knn) {
                primary = knn.plan(points, MAX_EDGE_BLOCKS,
                        (int) pc.knn()[0], pc.knn()[1], pc.knn()[2], (int) pc.knn()[3]);
            } else {
                primary = planner.plan(points, MAX_EDGE_BLOCKS);
            }
        }
        if (primary.isEmpty()) return primary;
        if (primaryOnly) return primary;
        double compMinAngle = pc.knn() != null ? pc.knn()[4] : RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG;
        int compDegCap = pc.knn() != null ? (int) pc.knn()[5] : RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP;
        // 生产传的是 collectComponentPoints(...)（全部点 ∪ 所有边端点，Y 一律归一为 0），不是原始点集：
        // connectComponents 用 HashMap<BlockPos,Integer> 建索引，Y 不归一 ⇒ 每条 base 边都查不到端点而被静默丢弃。
        List<BlockPos> componentPoints = collectComponentPoints(points, primary);
        List<StructureConnection> bridges = KNNPlanner.connectComponents(
                componentPoints, primary, BRIDGE_JOIN_BLOCKS, compMinAngle, compDegCap);
        List<StructureConnection> all = new ArrayList<>(primary.size() + bridges.size());
        all.addAll(primary);
        all.addAll(bridges);
        return all;
    }

    /**
     * 与生产 {@code PlanningBoundsOps.collectComponentPoints} 同构：seed ∪ 所有边端点，按 2D 去重、
     * Y 归一为 0（顺序：先 seed，后边端点——{@code addUnique2d} 保留首次出现的 Y，归一后 Y 恒为 0）。
     */
    private static List<BlockPos> collectComponentPoints(List<BlockPos> seed, List<StructureConnection> edges) {
        List<BlockPos> out = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        if (seed != null) {
            for (BlockPos p : seed) addUnique2d(out, seen, p);
        }
        if (edges != null) {
            for (StructureConnection c : edges) {
                if (c == null) continue;
                addUnique2d(out, seen, c.from());
                addUnique2d(out, seen, c.to());
            }
        }
        return out;
    }

    private static void addUnique2d(List<BlockPos> out, java.util.Set<Long> seen, BlockPos p) {
        if (p == null) return;
        BlockPos q = new BlockPos(p.getX(), 0, p.getZ());
        if (seen.add(PlanningUtils.pos2dKey(q))) out.add(q);
    }

    // ------------------------------------------------------------------ metrics

    private record Row(Case corpus, PlannerConfig config, Metrics metrics) {

        String toCsv() {
            double[] k = config.knn();
            SnapBranchModel.Params s = config.snap();
            // SNAP 配置里 knn 数组只用来携带桥梁阶段的 [4]/[5]；前四个格子是占位零，不能当 KNN 参数报出去。
            boolean kPrimary = s == null;
            return String.join(",",
                    quote(corpus.name()),
                    String.valueOf(corpus.points().size()),
                    quote(config.algorithm().name()),
                    !kPrimary || k == null ? "" : String.valueOf((int) k[0]),
                    !kPrimary || k == null ? "" : trim(k[1]),
                    !kPrimary || k == null ? "" : trim(k[2]),
                    !kPrimary || k == null ? "" : String.valueOf((int) k[3]),
                    k == null ? "" : trim(k[4]),
                    k == null ? "" : String.valueOf((int) k[5]),
                    s == null ? "" : trim(s.minProjectionT()),
                    s == null ? "" : trim(s.maxProjectionT()),
                    s == null ? "" : trim(s.maxSplitStretch()),
                    s == null ? "" : trim(s.edgeAttachBias()),
                    s == null ? "" : String.valueOf(s.fallbackPointLimit()),
                    s == null ? "" : trim(s.minForkAngleDeg()),
                    s == null ? "" : trim(s.minNodeAngleDeg()),
                    String.valueOf(metrics.vertices),
                    String.valueOf(metrics.edges),
                    String.valueOf(metrics.components),
                    String.valueOf(metrics.isolated),
                    String.valueOf(metrics.connectedInput),
                    trim(metrics.coveragePct()),
                    String.valueOf(metrics.maxDeg),
                    String.valueOf(metrics.junctions),
                    String.valueOf(metrics.bends),
                    String.valueOf(metrics.sharpJunctions),
                    String.valueOf(metrics.sharpBends),
                    String.valueOf(metrics.cycles),
                    trim(metrics.totalLen),
                    trim(metrics.meanLen),
                    trim(metrics.p95Len),
                    trim(metrics.maxLen),
                    trim(metrics.mstLen),
                    trim(metrics.mstRatio),
                    trim(metrics.minJunctionAngleMean),
                    trim(metrics.minBendAngleMean));
        }

        private static String trim(double v) {
            return String.format(Locale.ROOT, "%.3f", v);
        }

        private static String quote(String s) {
            return '"' + s.replace('"', '\'') + '"';
        }
    }

    private static final class Metrics {
        int nInput;
        int vertices;
        int edges;
        int components;
        int isolated;
        int connectedInput;
        int maxDeg;
        /** 度 ≥3 的顶点（分叉口）。 */
        int junctions;
        /** 度 ==2 的顶点（路的拐点）。 */
        int bends;
        /** 最小邻边夹角 < {@link #HAIRPIN_DEG} 的分叉口。 */
        int sharpJunctions;
        /** 夹角 < {@link #HAIRPIN_DEG} 的拐点（发夹弯）。 */
        int sharpBends;
        int cycles;
        double totalLen;
        double meanLen;
        double p95Len;
        double maxLen;
        double mstLen;
        double mstRatio;
        double minJunctionAngleMean;
        double minBendAngleMean;

        double coveragePct() {
            return nInput == 0 ? 0 : 100.0 * connectedInput / nInput;
        }

        /** 锐角分叉占比（分母是分叉口数）。 */
        double sharpJunctionShare() {
            return junctions == 0 ? 0 : (double) sharpJunctions / junctions;
        }

        /** 发夹弯占比（分母是拐点数）。 */
        double sharpBendShare() {
            return bends == 0 ? 0 : (double) sharpBends / bends;
        }

        static Metrics measure(List<BlockPos> input, List<StructureConnection> rawEdges) {
            Metrics m = new Metrics();
            m.nInput = input.size();

            Map<Long, Integer> vertexIndex = new HashMap<>();
            List<BlockPos> vertices = new ArrayList<>();
            List<int[]> edges = new ArrayList<>();
            Set<Long> edgeKeys = new HashSet<>();

            for (StructureConnection c : rawEdges) {
                if (c == null) continue;
                int a = vertexOf(vertexIndex, vertices, c.from());
                int b = vertexOf(vertexIndex, vertices, c.to());
                if (a == b) continue;
                long key = a < b ? (((long) a) << 32) ^ b : (((long) b) << 32) ^ a;
                if (!edgeKeys.add(key)) continue;
                edges.add(new int[]{a, b});
            }

            int v = vertices.size();
            m.vertices = v;
            m.edges = edges.size();

            List<List<Integer>> adj = new ArrayList<>(v);
            for (int i = 0; i < v; i++) adj.add(new ArrayList<>());
            double[] lengths = new double[edges.size()];
            for (int i = 0; i < edges.size(); i++) {
                int[] e = edges.get(i);
                adj.get(e[0]).add(e[1]);
                adj.get(e[1]).add(e[0]);
                lengths[i] = Math.hypot(
                        (double) vertices.get(e[0]).getX() - vertices.get(e[1]).getX(),
                        (double) vertices.get(e[0]).getZ() - vertices.get(e[1]).getZ());
                m.totalLen += lengths[i];
            }
            m.meanLen = lengths.length == 0 ? 0 : m.totalLen / lengths.length;
            double[] sorted = lengths.clone();
            Arrays.sort(sorted);
            m.maxLen = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
            m.p95Len = sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];

            for (int i = 0; i < v; i++) {
                int deg = adj.get(i).size();
                m.maxDeg = Math.max(m.maxDeg, deg);
                if (deg >= 3) m.junctions++;
                else if (deg == 2) m.bends++;
            }

            // 连通分量（含完全孤立的输入点）。
            int[] parent = new int[v];
            for (int i = 0; i < v; i++) parent[i] = i;
            for (int[] e : edges) union(parent, e[0], e[1]);
            Set<Integer> roots = new HashSet<>();
            for (int i = 0; i < v; i++) roots.add(find(parent, i));
            int graphComponents = roots.size();
            m.cycles = m.edges - v + graphComponents;
            if (m.cycles < 0) m.cycles = 0;

            Set<Long> vertexKeys = new HashSet<>();
            for (BlockPos p : vertices) vertexKeys.add(key2d(p.getX(), p.getZ()));
            for (BlockPos p : input) {
                if (vertexKeys.contains(key2d(p.getX(), p.getZ()))) m.connectedInput++;
                else m.isolated++;
            }
            m.components = graphComponents + m.isolated;

            // 夹角：分叉口（度≥3）与拐点（度=2）分开统计——两者分母不同，混在一起算比率没意义。
            double junctionAngleSum = 0;
            double bendAngleSum = 0;
            for (int i = 0; i < v; i++) {
                List<Integer> nb = adj.get(i);
                if (nb.size() < 2) continue;
                BlockPos a = vertices.get(i);
                double minAngle = Double.MAX_VALUE;
                for (int x = 0; x < nb.size(); x++) {
                    for (int y = x + 1; y < nb.size(); y++) {
                        BlockPos b = vertices.get(nb.get(x));
                        BlockPos c = vertices.get(nb.get(y));
                        double v1x = b.getX() - a.getX();
                        double v1z = b.getZ() - a.getZ();
                        double v2x = c.getX() - a.getX();
                        double v2z = c.getZ() - a.getZ();
                        double norm = Math.hypot(v1x, v1z) * Math.hypot(v2x, v2z);
                        if (norm <= 0) continue;
                        double cos = Math.max(-1.0, Math.min(1.0, (v1x * v2x + v1z * v2z) / norm));
                        minAngle = Math.min(minAngle, Math.toDegrees(Math.acos(cos)));
                    }
                }
                if (minAngle == Double.MAX_VALUE) continue;
                if (nb.size() >= 3) {
                    junctionAngleSum += minAngle;
                    if (minAngle < HAIRPIN_DEG) m.sharpJunctions++;
                } else {
                    bendAngleSum += minAngle;
                    if (minAngle < HAIRPIN_DEG) m.sharpBends++;
                }
            }
            m.minJunctionAngleMean = m.junctions == 0 ? 0 : junctionAngleSum / m.junctions;
            m.minBendAngleMean = m.bends == 0 ? 0 : bendAngleSum / m.bends;

            m.mstLen = mstLength(input);
            m.mstRatio = m.mstLen <= 0 ? 0 : m.totalLen / m.mstLen;
            return m;
        }

        private static int vertexOf(Map<Long, Integer> index, List<BlockPos> vertices, BlockPos p) {
            BlockPos flat = new BlockPos(p.getX(), 0, p.getZ());
            long key = key2d(flat.getX(), flat.getZ());
            Integer existing = index.get(key);
            if (existing != null) return existing;
            int id = vertices.size();
            index.put(key, id);
            vertices.add(flat);
            return id;
        }

        private static int find(int[] parent, int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        private static void union(int[] parent, int a, int b) {
            int ra = find(parent, a);
            int rb = find(parent, b);
            if (ra != rb) parent[rb] = ra;
        }

        private static double mstLength(List<BlockPos> pts) {
            int n = pts.size();
            if (n < 2) return 0;
            boolean[] used = new boolean[n];
            double[] best = new double[n];
            Arrays.fill(best, Double.MAX_VALUE);
            best[0] = 0;
            double total = 0;
            for (int it = 0; it < n; it++) {
                int pick = -1;
                for (int i = 0; i < n; i++) {
                    if (!used[i] && (pick < 0 || best[i] < best[pick])) pick = i;
                }
                if (pick < 0 || best[pick] == Double.MAX_VALUE) break;
                used[pick] = true;
                total += best[pick];
                for (int i = 0; i < n; i++) {
                    if (used[i]) continue;
                    double d = Math.hypot(
                            (double) pts.get(pick).getX() - pts.get(i).getX(),
                            (double) pts.get(pick).getZ() - pts.get(i).getZ());
                    if (d < best[i]) best[i] = d;
                }
            }
            return total;
        }
    }

    private TopologyHarness() {}
}
