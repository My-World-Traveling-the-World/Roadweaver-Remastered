/* 文件职责：离线合成地形寻路质量测量台主入口（M1 基线 / M2 参数搜索用）。 */
package net.shiroha233.roadweaver.tuning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig.PathfindingAlgorithm;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.PathfinderFactory;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 离线测量台：对每个种子构造合成地形，跑势场法 {@code findRawPath}（level/cache 传 null，
 * 走 PotentialFieldPathfinder 覆写的纯地形分支，不触发行号外的 Minecraft 世界逻辑），
 * 然后按 {@link RouteQualityMetrics} 打分并输出 CSV。
 *
 * <p>用法：{@code java RouteQualityHarness [--seed-from N] [--seed-to M]}（默认 0..15）。
 * 输出为 CSV：首行表头，之后每个种子一行；寻路失败的行 {@code success=false}。
 */
public final class RouteQualityHarness {

    /** 与生产真机 roadweaver.json 的 aStarStep 保持一致。 */
    private static final int A_STAR_STEP = 4;
    private static final int MAX_STEPS = 50_000;
    private static final int START_X = 64;
    private static final int START_Z = 64;
    private static final int END_X = 960;
    private static final int END_Z = 960;

    public static void main(String[] args) {
        long seedFrom = 0;
        long seedTo = 15;
        long dumpSeed = -1;
        Map<String, Double> params = new LinkedHashMap<>();
        boolean search = false;
        boolean learn = false;
        boolean profile = false;
        boolean corpus = false;
        boolean archetypes = false;
        String realCsv = null;
        String compare = null;
        String bench = null;
        String mapArch = null;
        int mapSeed = 0;
        String mapConfigs = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed-from" -> seedFrom = Long.parseLong(args[++i]);
                case "--seed-to" -> seedTo = Long.parseLong(args[++i]);
                case "--dump-seed" -> dumpSeed = Long.parseLong(args[++i]);
                case "--search" -> search = true;
                case "--learn" -> learn = true;
                case "--profile" -> profile = true;
                case "--corpus" -> corpus = true;
                case "--archetypes" -> archetypes = true;
                case "--compare" -> compare = args[++i];
                case "--bench" -> bench = args[++i];
                case "--map" -> {
                    mapArch = args[++i];
                    mapSeed = Integer.parseInt(args[++i]);
                    mapConfigs = args[++i];
                }
                case "--real" -> realCsv = args[++i];
                case "--param" -> {
                    String[] kv = args[++i].split("=", 2);
                    params.put(kv[0], Double.parseDouble(kv[1]));
                }
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        if (realCsv != null) {
            runReal(realCsv);
            return;
        }
        if (mapArch != null) {
            runMap(mapArch, mapSeed, mapConfigs);
            return;
        }
        if (archetypes) {
            runArchetypes(seedFrom, seedTo);
            return;
        }
        if (corpus) {
            runCorpus((int) seedFrom, (int) seedTo);
            return;
        }
        if (bench != null) {
            runBench((int) seedFrom, (int) seedTo, bench);
            return;
        }
        if (compare != null) {
            runCompare((int) seedFrom, (int) seedTo, compare);
            return;
        }
        if (profile) {
            runProfile(seedFrom, seedTo);
            return;
        }
        if (learn) {
            runLearn(seedFrom, seedTo);
            return;
        }
        if (search) {
            runSearch(seedFrom, seedTo);
            return;
        }

        System.out.println(RouteQualityMetrics.csvHeader());
        Summary agg = new Summary();
        for (long seed = seedFrom; seed <= seedTo; seed++) {
            RouteQualityMetrics m = runSeed(seed, seed == dumpSeed, params);
            if (m == null) {
                System.out.println(seed + ",false,,,,,,,,,,");
            } else {
                System.out.println(m.toCsv(seed));
                agg.add(m);
            }
        }
        System.out.println(agg.toString());
    }

    private static RouteQualityMetrics runSeed(long seed, boolean dump, Map<String, Double> params) {
        SyntheticTerrain terrain = new SyntheticTerrain(seed);
        SyntheticTerrainField field = new SyntheticTerrainField(terrain, A_STAR_STEP);
        BlockPos start = new BlockPos(START_X, terrain.surfaceY(START_X, START_Z), START_Z);
        BlockPos end = new BlockPos(END_X, terrain.surfaceY(END_X, END_Z), END_Z);

        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setAStarStep(A_STAR_STEP);
        for (Map.Entry<String, Double> e : params.entrySet()) {
            applyParam(cfg, e.getKey(), e.getValue());
        }

        Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);
        PathResult result = pathfinder.findRawPath(start, end, null, MAX_STEPS, null, field, cfg);

        if (!result.hasRawPath()) {
            return null;
        }
        if (dump) {
            for (BlockPos p : result.rawPath()) {
                System.err.println(seed + " " + p.getX() + " " + p.getZ() + " " + p.getY()
                        + (terrain.isWater(p.getX(), p.getZ()) ? " W" : ""));
            }
        }
        return RouteQualityMetrics.of(result.rawPath(), terrain);
    }

    /**
     * 蜿蜒目标搜索：对一组候选参数配置（含基线）在 seedFrom..seedTo 上评估，
     * 按目标函数升序排序输出。目标 = 蜿蜒带（sharpTurns 靠近 {@link #MEANDER_TARGET}）
     * + 涉水 + 突兀 + 回头弯 + 过绕惩罚。所有项按基线归一。
     *
     * <p>「蜿蜒」用 sharpTurns（&gt;90° 急转弯数）度量：A* 步长 4 的 8 方向网格只在
     * 对角线↔正交间产生 45° 锯齿，90°+ 转弯才是真实拐点，不受网格量化污染。
     * 太直（sharpTurns≈0）或太乱（sharpTurns 远超目标）都罚，回头弯（&gt;150°）单独罚。
     */
    private static final double MEANDER_TARGET = 8.0;

    private static void runSearch(long seedFrom, long seedTo) {
        record Config(String name, Map<String, Double> params) {}

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("baseline(sw15)", Map.of()));
        configs.add(new Config("sw=10", Map.of("stability_weight", 10.0)));
        configs.add(new Config("sw=20", Map.of("stability_weight", 20.0)));
        configs.add(new Config("sw=30", Map.of("stability_weight", 30.0)));
        configs.add(new Config("swamp=25", Map.of("biome_cost_swamp", 25.0)));
        configs.add(new Config("swamp=50", Map.of("biome_cost_swamp", 50.0)));
        configs.add(new Config("swamp=100", Map.of("biome_cost_swamp", 100.0)));
        configs.add(new Config("mountain=25", Map.of("biome_cost_mountain", 25.0)));
        configs.add(new Config("mountain=50", Map.of("biome_cost_mountain", 50.0)));
        configs.add(new Config("forest=25", Map.of("biome_cost_forest", 25.0)));
        configs.add(new Config("forest=50", Map.of("biome_cost_forest", 50.0)));
        configs.add(new Config("desert=25", Map.of("biome_cost_desert", 25.0)));
        configs.add(new Config("water=72", Map.of("biome_cost_water", 72.0)));
        configs.add(new Config("swamp50+water72", Map.of("biome_cost_swamp", 50.0, "biome_cost_water", 72.0)));
        configs.add(new Config("swamp50+mnt50", Map.of("biome_cost_swamp", 50.0, "biome_cost_mountain", 50.0)));
        configs.add(new Config("swamp50+forest25", Map.of("biome_cost_swamp", 50.0, "biome_cost_forest", 25.0)));
        configs.add(new Config("cfw=4", Map.of("contour_follow_weight", 4.0)));
        configs.add(new Config("cfw=8", Map.of("contour_follow_weight", 8.0)));
        configs.add(new Config("cfw4+swamp50", Map.of("contour_follow_weight", 4.0, "biome_cost_swamp", 50.0)));
        configs.add(new Config("swamp50+for25+des25", Map.of("biome_cost_swamp", 50.0, "biome_cost_forest", 25.0, "biome_cost_desert", 25.0)));
        // Q1 分段坡度代价扫参（elevationGentleMax/Weight + elevationSteepWeight + elevationCliffThreshold/Weight）+ 当前预设 hgp=30000
        configs.add(new Config("hgp=30000", Map.of("hard_grade_penalty", 30000.0)));
        configs.add(new Config("gw=15", Map.of("elevation_gentle_weight", 15.0)));
        configs.add(new Config("gw=60", Map.of("elevation_gentle_weight", 60.0)));
        configs.add(new Config("esw=60", Map.of("elevation_steep_weight", 60.0)));
        configs.add(new Config("esw=240", Map.of("elevation_steep_weight", 240.0)));
        configs.add(new Config("cw=200", Map.of("elevation_cliff_weight", 200.0)));
        configs.add(new Config("cw=800", Map.of("elevation_cliff_weight", 800.0)));
        configs.add(new Config("gm=4", Map.of("elevation_gentle_max", 4.0)));
        configs.add(new Config("ct=8", Map.of("elevation_cliff_threshold", 8.0)));
        configs.add(new Config("gm4+ct8", Map.of("elevation_gentle_max", 4.0, "elevation_cliff_threshold", 8.0)));
        configs.add(new Config("agg-cliff", Map.of("elevation_cliff_weight", 800.0, "elevation_steep_weight", 240.0, "elevation_cliff_threshold", 8.0)));
        configs.add(new Config("hgp30k+cw800", Map.of("hard_grade_penalty", 30000.0, "elevation_cliff_weight", 800.0)));
        configs.add(new Config("hgp30k+gm4", Map.of("hard_grade_penalty", 30000.0, "elevation_gentle_max", 4.0)));

        // 先跑基线，取归一化参考。
        Summary base = evaluate(configs.get(0).params, seedFrom, seedTo);
        double baseWater = Math.max(1.0, base.waterNodes / base.n);
        double baseGrade = Math.max(1e-4, base.avgGrade / base.n);

        record Ranked(Config c, Summary s, double obj) {}

        List<Ranked> ranked = new ArrayList<>();
        for (Config c : configs) {
            Summary s = c == configs.get(0) ? base : evaluate(c.params, seedFrom, seedTo);
            double sharpTurns = s.sharpTurns / s.n;
            double hairpins = s.hairpins / s.n;
            double water = s.waterNodes / s.n;
            double grade = s.avgGrade / s.n;
            double straightness = s.straightness / s.n;

            double meanderTerm = Math.abs(sharpTurns - MEANDER_TARGET) / MEANDER_TARGET;
            double indirectTerm = straightness < 0.50 ? (0.50 - straightness) * 20.0 : 0.0;
            double obj = water / baseWater
                    + grade / baseGrade
                    + 0.5 * hairpins
                    + meanderTerm
                    + indirectTerm;
            ranked.add(new Ranked(c, s, obj));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> r.obj));
        System.out.println("SEARCH ranked (ascending objective; baseline reference waterNodes="
                + f(baseWater) + " avgGrade=" + f(baseGrade) + "):");
        for (int i = 0; i < ranked.size(); i++) {
            Ranked r = ranked.get(i);
            System.out.printf(java.util.Locale.ROOT, "  #%d obj=%.3f %-22s => %s%n",
                    i + 1, r.obj, r.c.name, r.s.toString().substring("SUMMARY ".length()));
        }
    }

    private static Summary evaluate(Map<String, Double> params, long seedFrom, long seedTo) {
        int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<RouteQualityMetrics>> futures = new ArrayList<>();
        for (long seed = seedFrom; seed <= seedTo; seed++) {
            final long s = seed;
            futures.add(pool.submit(() -> runSeed(s, false, params)));
        }
        Summary agg = new Summary();
        for (Future<RouteQualityMetrics> f : futures) {
            try {
                RouteQualityMetrics m = f.get();
                if (m != null) {
                    agg.add(m);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        pool.shutdown();
        return agg;
    }

    /**
     * 预设学习模式：对每个种子计算地形画像，并在固定候选配置集上网格搜索「地形质量」最优配置，
     * 输出 seed+画像+最优配置 的 CSV（每批 1000 种子 checkpoint 落盘，供长时间后台跑批）。
     * 目标函数只含地形质量（涉水/贴地/回头弯/突兀 + 过绕惩罚），不含蜿蜒——蜿蜒是用户风格，另行处理。
     */
    private record NamedParams(String name, Map<String, Double> params) {}

    private static List<NamedParams> learnConfigs() {
        List<NamedParams> configs = new ArrayList<>();
        configs.add(new NamedParams("baseline", Map.of()));
        configs.add(new NamedParams("sw=10", Map.of("stability_weight", 10.0)));
        configs.add(new NamedParams("sw=20", Map.of("stability_weight", 20.0)));
        configs.add(new NamedParams("sw=30", Map.of("stability_weight", 30.0)));
        configs.add(new NamedParams("sw=50", Map.of("stability_weight", 50.0)));
        configs.add(new NamedParams("sw=75", Map.of("stability_weight", 75.0)));
        configs.add(new NamedParams("swamp=25", Map.of("biome_cost_swamp", 25.0)));
        configs.add(new NamedParams("swamp=50", Map.of("biome_cost_swamp", 50.0)));
        configs.add(new NamedParams("swamp=100", Map.of("biome_cost_swamp", 100.0)));
        configs.add(new NamedParams("mountain=25", Map.of("biome_cost_mountain", 25.0)));
        configs.add(new NamedParams("mountain=50", Map.of("biome_cost_mountain", 50.0)));
        configs.add(new NamedParams("mountain=100", Map.of("biome_cost_mountain", 100.0)));
        configs.add(new NamedParams("water=72", Map.of("biome_cost_water", 72.0)));
        // hard_grade_limit 是 1000 地形语料扫参的主导杠杆（0.07 全局最优 +1.82%），这里做细扫
        configs.add(new NamedParams("hgl=0.05", Map.of("hard_grade_limit", 0.05)));
        configs.add(new NamedParams("hgl=0.06", Map.of("hard_grade_limit", 0.06)));
        configs.add(new NamedParams("hgl=0.07", Map.of("hard_grade_limit", 0.07)));
        configs.add(new NamedParams("hgl=0.08", Map.of("hard_grade_limit", 0.08)));
        configs.add(new NamedParams("hgl=0.10", Map.of("hard_grade_limit", 0.10)));
        configs.add(new NamedParams("hgl=0.12", Map.of("hard_grade_limit", 0.12)));
        // 注意: hard_grade_penalty 的配置默认值 30000 是 0.8.414~0.9.062 的事；**0.9.063 起默认 39000**
    // （坡罚刻度整体 ×1.30，见 RoadConstants.DEFAULT_HARD_GRADE_PENALTY），所以 30000 现在是一个"更松"的档。
        // 这里用 6000（0.8.414 之前的旧默认）/20000/40000/100000 量真实敏感度。
        configs.add(new NamedParams("hgp=6000", Map.of("hard_grade_penalty", 6000.0)));
        configs.add(new NamedParams("hgp=20000", Map.of("hard_grade_penalty", 20000.0)));
        configs.add(new NamedParams("hgp=40000", Map.of("hard_grade_penalty", 40000.0)));
        configs.add(new NamedParams("hgp=100000", Map.of("hard_grade_penalty", 100000.0)));
        // elevation_weight 在 Q1 之后对势场法已无消费者（二次式被分段代价替换）——这两项应当与 baseline 完全一致，作为 Q1 落地自检。
        configs.add(new NamedParams("ew=0", Map.of("elevation_weight", 0.0)));
        configs.add(new NamedParams("ew=160", Map.of("elevation_weight", 160.0)));
        configs.add(new NamedParams("sgl=0.04", Map.of("soft_grade_limit", 0.04)));
        configs.add(new NamedParams("wprox=150", Map.of("water_proximity_cost", 150.0)));
        configs.add(new NamedParams("wprox=300", Map.of("water_proximity_cost", 300.0)));
        configs.add(new NamedParams("nearw=300", Map.of("near_water_cost", 300.0)));
        configs.add(new NamedParams("nearw=500", Map.of("near_water_cost", 500.0)));
        configs.add(new NamedParams("swamp50+mnt50", Map.of("biome_cost_swamp", 50.0, "biome_cost_mountain", 50.0)));
        configs.add(new NamedParams("swamp50+wprox300", Map.of("biome_cost_swamp", 50.0, "water_proximity_cost", 300.0)));
        configs.add(new NamedParams("hgl0.07+sgl0.04", Map.of("hard_grade_limit", 0.07, "soft_grade_limit", 0.04)));
        configs.add(new NamedParams("cfw=4", Map.of("contour_follow_weight", 4.0)));
        // Q1 分段坡度代价扫参（elevationGentleMax/Weight + elevationSteepWeight + elevationCliffThreshold/Weight）
        configs.add(new NamedParams("gw=15", Map.of("elevation_gentle_weight", 15.0)));
        configs.add(new NamedParams("gw=60", Map.of("elevation_gentle_weight", 60.0)));
        configs.add(new NamedParams("esw=60", Map.of("elevation_steep_weight", 60.0)));
        configs.add(new NamedParams("esw=240", Map.of("elevation_steep_weight", 240.0)));
        configs.add(new NamedParams("cw=200", Map.of("elevation_cliff_weight", 200.0)));
        configs.add(new NamedParams("cw=800", Map.of("elevation_cliff_weight", 800.0)));
        configs.add(new NamedParams("gm=4", Map.of("elevation_gentle_max", 4.0)));
        configs.add(new NamedParams("ct=8", Map.of("elevation_cliff_threshold", 8.0)));
        configs.add(new NamedParams("gm4+ct8", Map.of("elevation_gentle_max", 4.0, "elevation_cliff_threshold", 8.0)));
        configs.add(new NamedParams("agg-cliff", Map.of("elevation_cliff_weight", 800.0, "elevation_steep_weight", 240.0, "elevation_cliff_threshold", 8.0)));
        configs.add(new NamedParams("hgl0.07+sw10", Map.of("hard_grade_limit", 0.07, "stability_weight", 10.0)));
        configs.add(new NamedParams("hgl0.07+sw30", Map.of("hard_grade_limit", 0.07, "stability_weight", 30.0)));
        configs.add(new NamedParams("hgl0.07+mnt100", Map.of("hard_grade_limit", 0.07, "biome_cost_mountain", 100.0)));
        configs.add(new NamedParams("hgl0.07+nearw500", Map.of("hard_grade_limit", 0.07, "near_water_cost", 500.0)));

        // 组合候选（第二轮：把各自最稳的几项叠起来，验证可加性——swamp50+mnt50 已证明组合可能互相打架）
        configs.add(new NamedParams("hgl=0.13", Map.of("hard_grade_limit", 0.13)));
        configs.add(new NamedParams("hgl=0.14", Map.of("hard_grade_limit", 0.14)));
        configs.add(new NamedParams("hgl0.12+mnt100", Map.of("hard_grade_limit", 0.12, "biome_cost_mountain", 100.0)));
        configs.add(new NamedParams("hgl0.12+sgl0.04", Map.of("hard_grade_limit", 0.12, "soft_grade_limit", 0.04)));
        configs.add(new NamedParams("hgl0.12+mnt100+sgl0.04", Map.of("hard_grade_limit", 0.12, "biome_cost_mountain", 100.0, "soft_grade_limit", 0.04)));
        configs.add(new NamedParams("hgl0.12+mnt100+hgp40000", Map.of("hard_grade_limit", 0.12, "biome_cost_mountain", 100.0, "hard_grade_penalty", 40000.0)));
        configs.add(new NamedParams("hgl0.12+hgp40000", Map.of("hard_grade_limit", 0.12, "hard_grade_penalty", 40000.0)));
        configs.add(new NamedParams("hgl0.12+sw30", Map.of("hard_grade_limit", 0.12, "stability_weight", 30.0)));
        configs.add(new NamedParams("mnt100+hgp40000", Map.of("biome_cost_mountain", 100.0, "hard_grade_penalty", 40000.0)));
        configs.add(new NamedParams("mnt100+sgl0.04", Map.of("biome_cost_mountain", 100.0, "soft_grade_limit", 0.04)));

        // 蜿蜒专项（用户诉求：要麻花状蜿蜒、太直也不好）——这几项是理论上能"加弯"的杠杆：
        // heuristicWeight 越小越不贪心（更容易绕路）、contourFollowWeight 越大越贴等高线（横着走=弯多）、
        // 近水/水邻代价越低越容易贴着水岸走（水岸本身就是弯的）。
        configs.add(new NamedParams("hw=0.5", Map.of("heuristic_weight", 0.5)));
        configs.add(new NamedParams("hw=1.0", Map.of("heuristic_weight", 1.0)));
        configs.add(new NamedParams("hw=1.5", Map.of("heuristic_weight", 1.5)));
        configs.add(new NamedParams("hw=3.0", Map.of("heuristic_weight", 3.0)));
        configs.add(new NamedParams("hw=4.0", Map.of("heuristic_weight", 4.0)));
        configs.add(new NamedParams("cfw=2", Map.of("contour_follow_weight", 2.0)));
        configs.add(new NamedParams("cfw=8", Map.of("contour_follow_weight", 8.0)));
        configs.add(new NamedParams("cfw=16", Map.of("contour_follow_weight", 16.0)));
        configs.add(new NamedParams("sw=5", Map.of("stability_weight", 5.0)));
        configs.add(new NamedParams("sw=0", Map.of("stability_weight", 0.0)));
        configs.add(new NamedParams("wprox=0", Map.of("water_proximity_cost", 0.0)));
        configs.add(new NamedParams("nearw=0", Map.of("near_water_cost", 0.0)));
        configs.add(new NamedParams("nearw0+wprox0", Map.of("near_water_cost", 0.0, "water_proximity_cost", 0.0)));
        configs.add(new NamedParams("sw0+nearw0", Map.of("stability_weight", 0.0, "near_water_cost", 0.0)));
        configs.add(new NamedParams("hgl=0.30", Map.of("hard_grade_limit", 0.30)));
        configs.add(new NamedParams("hgl=0.30+sw0", Map.of("hard_grade_limit", 0.30, "stability_weight", 0.0)));
        // 「蜿蜒优先」组合：用户诉求是「有蜿蜒/麻花状、太直也不好」，而 |sharpTurns-8| 目标恰恰奖励拉直。
        // 这三条把「去掉近水罚（贴水岸走=天然弯曲）」与「轻微收紧硬坡限」组合起来试。
        configs.add(new NamedParams("hgl0.12+nearw0+wprox0",
                Map.of("hard_grade_limit", 0.12, "near_water_cost", 0.0, "water_proximity_cost", 0.0)));
        configs.add(new NamedParams("sw5+nearw0", Map.of("stability_weight", 5.0, "near_water_cost", 0.0)));
        configs.add(new NamedParams("hgl0.12+sw5", Map.of("hard_grade_limit", 0.12, "stability_weight", 5.0)));
        // 诊断组：分段竖向代价（Q1 接入）与坡度罚款同量级放大——用来判定「分段系统是不是被
        // hardGradePenalty 彻底淹没」。hgp=30000 时，Δy=1（grade=0.25）的硬坡罚款约 20000，
        // 而分段代价只有 30，相差约 667 倍，所以在语料里 cw/gm/ct 全是 0.000 变化。
        configs.add(new NamedParams("gw=3000", Map.of("elevation_gentle_weight", 3000.0)));
        configs.add(new NamedParams("esw=3000", Map.of("elevation_steep_weight", 3000.0)));
        configs.add(new NamedParams("cw=8000", Map.of("elevation_cliff_weight", 8000.0)));
        configs.add(new NamedParams("hgl=0.5", Map.of("hard_grade_limit", 0.5)));
        configs.add(new NamedParams("hgl=1.0", Map.of("hard_grade_limit", 1.0)));
        configs.add(new NamedParams("hgp=3000", Map.of("hard_grade_penalty", 3000.0)));
        configs.add(new NamedParams("hgp=300000", Map.of("hard_grade_penalty", 300000.0)));
        // 决赛组：语料里表现最好的 sw=30 与其它"零代价"项的交叉，外加一条更激进的 sw=40。
        configs.add(new NamedParams("sw=40", Map.of("stability_weight", 40.0)));
        configs.add(new NamedParams("sw30+hgl0.10",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.10)));
        configs.add(new NamedParams("sw30+mnt25",
                Map.of("stability_weight", 30.0, "biome_cost_mountain", 25.0)));
        configs.add(new NamedParams("sw30+mnt50",
                Map.of("stability_weight", 30.0, "biome_cost_mountain", 50.0)));
        configs.add(new NamedParams("sw30+sgl0.04",
                Map.of("stability_weight", 30.0, "soft_grade_limit", 0.04)));
        configs.add(new NamedParams("sw30+nearw0",
                Map.of("stability_weight", 30.0, "near_water_cost", 0.0)));
        configs.add(new NamedParams("sw30+hgl0.10+mnt25",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.10, "biome_cost_mountain", 25.0)));
        configs.add(new NamedParams("sw30+hgl0.10+mnt25+nearw0",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.10,
                        "biome_cost_mountain", 25.0, "near_water_cost", 0.0)));
        // ── 0.8.418：补齐参数（偏差家族接进势场法）+ 蜿蜒旋钮 ────────────────────
        // 1) 偏差家族敏感度。注意：接线后 deviationWeight 默认 0.2 会真实生效，
        //    所以这里的 baseline 已含该罚；dev0 = 接线前的旧行为（对照是否该保留默认）。
        configs.add(new NamedParams("dev0", Map.of("deviation_weight", 0.0)));
        configs.add(new NamedParams("dev0.01", Map.of("deviation_weight", 0.01)));
        configs.add(new NamedParams("dev0.05", Map.of("deviation_weight", 0.05)));
        configs.add(new NamedParams("dev1.0", Map.of("deviation_weight", 1.0)));
        configs.add(new NamedParams("devL", Map.of("deviation_weight", 0.05, "deviation_squared", 0.0)));
        configs.add(new NamedParams("devR256",
                Map.of("deviation_weight", 0.05, "deviation_free_radius", 256.0)));
        // 2) 等高线折扣地板（原硬编码 0.3）。注意：它只在「等高线折扣」被调高时才咬得住
        //    ——折扣 0.55 时 contourFactor 最低 0.45，地板 0.2 永远不生效（实测逐位不变），
        //    所以这里必须成对扫「折扣 + 地板」。
        configs.add(new NamedParams("cd0.90", Map.of("contour_discount", 0.90)));
        configs.add(new NamedParams("cd0.90+cff0.15",
                Map.of("contour_discount", 0.90, "contour_factor_floor", 0.15)));
        configs.add(new NamedParams("cd0.90+cff0.05",
                Map.of("contour_discount", 0.90, "contour_factor_floor", 0.05)));
        configs.add(new NamedParams("cff=0.20", Map.of("contour_factor_floor", 0.20)));
        // 3) 竖向代价模型切换验证（关闭分段 ⇒ 回到二次式，elevationWeight 复活）。
        configs.add(new NamedParams("quad", Map.of("elevation_segmented_cost", 0.0)));
        configs.add(new NamedParams("quad+ew4000",
                Map.of("elevation_segmented_cost", 0.0, "elevation_weight", 4000.0)));
        // 5) 决赛预设 C 与蜿蜒旋钮的交叉。
        configs.add(new NamedParams("C+cff0.15",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.10,
                        "biome_cost_mountain", 25.0, "near_water_cost", 0.0,
                        "contour_factor_floor", 0.15)));
        configs.add(new NamedParams("C+dev0",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.10,
                        "biome_cost_mountain", 25.0, "near_water_cost", 0.0,
                        "deviation_weight", 0.0)));
        // ⚠️ 0.8.418 之后 `baseline`（空参数）已经等于「新出厂默认」，它和 C+dev0 是同一套值。
        // 要对照「改动之前的行为」，必须用下面这条把 0.8.417 的五个旧默认显式写回来。
        configs.add(new NamedParams("old-default",
                Map.of("stability_weight", 15.0, "hard_grade_limit", 0.15,
                        "biome_cost_mountain", 0.0, "near_water_cost", 100.0,
                        "deviation_weight", 0.2)));
        // 0.8.418 单变量分解：从「0.8.417 五个旧默认」出发，每次只把一个值换成新默认。
        // 必要性：`baseline` 与 `C+dev0` 现在是同一套值，只对比它们会让每个新参数看起来
        // 都毫无影响；而 `sw=30` 这类只写一个键的配置，其余四个键取的是【新】默认，
        // 于是它们其实全都等于新默认（实测 nodes/pathLength 逐位相同）。要知道 +38%
        // 的寻路耗时是谁造成的，必须一次只动一个。
        configs.add(new NamedParams("old+sw30",
                Map.of("stability_weight", 30.0, "hard_grade_limit", 0.15,
                        "biome_cost_mountain", 0.0, "near_water_cost", 100.0,
                        "deviation_weight", 0.2)));
        configs.add(new NamedParams("old+hgl0.10",
                Map.of("stability_weight", 15.0, "hard_grade_limit", 0.10,
                        "biome_cost_mountain", 0.0, "near_water_cost", 100.0,
                        "deviation_weight", 0.2)));
        configs.add(new NamedParams("old+mnt25",
                Map.of("stability_weight", 15.0, "hard_grade_limit", 0.15,
                        "biome_cost_mountain", 25.0, "near_water_cost", 100.0,
                        "deviation_weight", 0.2)));
        configs.add(new NamedParams("old+nearw0",
                Map.of("stability_weight", 15.0, "hard_grade_limit", 0.15,
                        "biome_cost_mountain", 0.0, "near_water_cost", 0.0,
                        "deviation_weight", 0.2)));
        configs.add(new NamedParams("old+dev0",
                Map.of("stability_weight", 15.0, "hard_grade_limit", 0.15,
                        "biome_cost_mountain", 0.0, "near_water_cost", 100.0,
                        "deviation_weight", 0.0)));
        // ── 0.9.030 根治批标定矩阵 ──
        // 基准开关逐位关 = 线上现状；逐档打开看方向。命名带 sb/hf/sl 前缀，别和既有键重名。
        configs.add(new NamedParams("sb=1", Map.of("slope_penalty_by_length", 1.0)));
        configs.add(new NamedParams("hf=4", Map.of("heading_flip_cost", 4.0)));
        configs.add(new NamedParams("hf=8", Map.of("heading_flip_cost", 8.0)));
        configs.add(new NamedParams("hf=16", Map.of("heading_flip_cost", 16.0)));
        configs.add(new NamedParams("hf=8+hc=1", Map.of("heading_flip_cost", 8.0, "heading_curve_cost", 1.0)));
        configs.add(new NamedParams("hf=8+sl=24", Map.of("heading_flip_cost", 8.0, "heading_short_leg_blocks", 24.0)));
        configs.add(new NamedParams("hf=8+sl=96", Map.of("heading_flip_cost", 8.0, "heading_short_leg_blocks", 96.0)));
        configs.add(new NamedParams("sb=1+hf=8", Map.of("slope_penalty_by_length", 1.0, "heading_flip_cost", 8.0)));
        return configs;
    }

    /**
     * 画像模式：只算地形画像（6 特征），不跑寻路——用于快速覆盖海量种子（百万级），
     * 产出 (seed, 6 特征) CSV，供聚类划分「地形类型」后再逐类做寻路调参。
     */
    private static void runProfile(long seedFrom, long seedTo) {
        System.out.println("seed,waterFraction,elevMean,elevStddev,mountainFraction,biomeEntropy,nearWaterFraction");
        int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long total = seedTo - seedFrom + 1;
        long done = 0;
        int batch = 10000;
        for (long bs = seedFrom; bs <= seedTo; bs += batch) {
            long be = Math.min(seedTo, bs + batch - 1);
            List<Future<String>> futures = new ArrayList<>();
            for (long seed = bs; seed <= be; seed++) {
                final long s = seed;
                futures.add(pool.submit(() -> new SyntheticTerrain(s).profile().csv(s)));
            }
            for (Future<String> fu : futures) {
                try {
                    System.out.println(fu.get());
                } catch (Exception e) {
                    System.err.println("profile seed error: " + e);
                }
            }
            done += be - bs + 1;
            System.out.flush();
            System.err.println("[profile] " + done + "/" + total + " done");
            System.err.flush();
        }
        pool.shutdown();
    }

    private static void runLearn(long seedFrom, long seedTo) {
        List<NamedParams> configs = learnConfigs();
        StringBuilder header = new StringBuilder("seed,waterFraction,elevMean,elevStddev,mountainFraction,biomeEntropy,nearWaterFraction");
        for (NamedParams np : configs) {
            header.append(',').append(np.name);
        }
        System.out.println(header);
        int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long total = seedTo - seedFrom + 1;
        long done = 0;
        int batch = 1000;
        for (long bs = seedFrom; bs <= seedTo; bs += batch) {
            long be = Math.min(seedTo, bs + batch - 1);
            List<Future<String>> futures = new ArrayList<>();
            for (long seed = bs; seed <= be; seed++) {
                final long s = seed;
                futures.add(pool.submit(() -> learnOneSeed(s, configs)));
            }
            for (Future<String> fu : futures) {
                try {
                    System.out.println(fu.get());
                } catch (Exception e) {
                    System.err.println("learn seed error: " + e);
                }
            }
            done += be - bs + 1;
            System.out.flush();
            System.err.println("[learn] " + done + "/" + total + " done");
            System.err.flush();
        }
        pool.shutdown();
    }

    /**
     * 地形语料模式：把「原型 × 种子」整体当作调参语料，每个地形跑全部候选配置，
     * 输出 `archetype,seed,<画像 6 列>,<每配置目标值>` 全矩阵 CSV。
     * 用途：①找「在所有地形原型上都好」的鲁棒配置；②找「不同地形该用不同配置」的自适应规律。
     */
    private static void runCorpus(int seedFrom, int seedTo) {
        List<NamedParams> configs = learnConfigs();
        StringBuilder header = new StringBuilder("archetype,seed,waterFraction,elevMean,elevStddev,mountainFraction,biomeEntropy,nearWaterFraction");
        for (NamedParams np : configs) {
            header.append(',').append(np.name);
        }
        System.out.println(header);
        System.out.flush();
        int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
                long t0 = System.nanoTime();
                List<Future<String>> rows = new ArrayList<>();
                for (int seed = seedFrom; seed <= seedTo; seed++) {
                    final int s = seed;
                    rows.add(pool.submit(() -> a.name() + "," + learnOneSeed(s, a, configs)));
                }
                int written = 0;
                for (Future<String> row : rows) {
                    try {
                        System.out.println(row.get());
                        written++;
                    } catch (Exception e) {
                        System.err.println("corpus row error archetype=" + a + ": " + e);
                    }
                }
                System.out.flush();
                System.err.println("[corpus] " + a + " rows=" + written
                        + " elapsedMs=" + (System.nanoTime() - t0) / 1_000_000L);
                System.err.flush();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 渲染用输出：原型地形高度网格 + 若干配置的路径坐标（纯文本行协议，便于 Python 画对比图）。
     * 用法：--map CLIFF_PLATEAU 3 baseline,hgl=0.12,sw=5
     */
    private static void runMap(String archetypeName, int seed, String configNames) {
        SyntheticTerrain.Archetype arch;
        try {
            arch = SyntheticTerrain.Archetype.valueOf(archetypeName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--map unknown archetype: " + archetypeName);
        }
        SyntheticTerrain terrain = new SyntheticTerrain(seed, arch);
        SyntheticTerrainField field = new SyntheticTerrainField(terrain, A_STAR_STEP);
        int grid = 8;
        StringBuilder sb = new StringBuilder();
        sb.append("MAP ").append(arch.name()).append(' ').append(seed).append(' ').append(grid).append('\n');
        sb.append("GRID\n");
        for (int z = SyntheticTerrain.WORLD_MIN_Z; z <= SyntheticTerrain.WORLD_MAX_Z; z += grid) {
            for (int x = SyntheticTerrain.WORLD_MIN_X; x <= SyntheticTerrain.WORLD_MAX_X; x += grid) {
                if (x > SyntheticTerrain.WORLD_MIN_X) {
                    sb.append(',');
                }
                sb.append(terrain.groundY(x, z));
            }
            sb.append('\n');
        }
        sb.append("ENDGRID\n");

        List<NamedParams> all = learnConfigs();
        BlockPos start = new BlockPos(START_X, terrain.surfaceY(START_X, START_Z), START_Z);
        BlockPos end = new BlockPos(END_X, terrain.surfaceY(END_X, END_Z), END_Z);
        for (String raw : configNames.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            NamedParams hit = null;
            for (NamedParams np : all) {
                if (np.name.equals(name)) {
                    hit = np;
                    break;
                }
            }
            if (hit == null) {
                throw new IllegalArgumentException("--map unknown config name: " + name);
            }
            PathfindingCostConfig cfg = new PathfindingCostConfig();
            cfg.setAStarStep(A_STAR_STEP);
            for (Map.Entry<String, Double> e : hit.params.entrySet()) {
                applyParam(cfg, e.getKey(), e.getValue());
            }
            Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);
            PathResult result = pathfinder.findRawPath(start, end, null, MAX_STEPS, null, field, cfg);
            sb.append("PATH ").append(hit.name).append('\n');
            if (result != null && result.hasRawPath()) {
                List<BlockPos> path = result.rawPath();
                for (int i = 0; i < path.size(); i++) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    sb.append(path.get(i).getX()).append(',').append(path.get(i).getZ());
                }
            }
            sb.append('\n').append("ENDPATH\n");
        }
        System.out.print(sb);
        System.out.flush();
    }

    /**
     * 形态对比模式：对一组命名配置逐地形跑，输出**原始形态指标**全矩阵（不是复合目标值），
     * 用来回答「换参数后道路到底变成什么样」（更蜿蜒还是更直、平均/最大坡度、涉水、发夹弯），
     * 而不只是复合目标的涨跌——真机看到的是形态，不是目标值。
     * 输出 `archetype,seed,<每配置>:straightness,avgGrade,maxGrade,sharpTurns,hairpins,waterNodes,steepEdges`。
     * 用法：--compare baseline,hgl=0.07,hgl0.07+sw10 --seed-from 0 --seed-to 99
     */
    /** 把 `--compare`/`--bench`/`--map` 的名字列表解析成配置（只认 {@link #learnConfigs()} 里的名字）。 */
    private static List<NamedParams> resolveConfigs(String names) {
        List<NamedParams> all = learnConfigs();
        List<NamedParams> configs = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            NamedParams hit = null;
            for (NamedParams np : all) {
                if (np.name.equals(name)) {
                    hit = np;
                    break;
                }
            }
            if (hit == null) {
                throw new IllegalArgumentException("unknown config name: " + name);
            }
            configs.add(hit);
        }
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("got no valid config name");
        }
        return configs;
    }

    /**
     * 计时模式：**单线程**跑固定种子集，报告每次寻路的墙钟耗时与扩展规模。
     *
     * <p>用途：回答「新增/调整参数是否拖慢了寻路」这类问题。刻意不用线程池——多线程会让各配置
     * 互相抢内存带宽，把差异淹掉。先跑一轮预热再计时，避免把 JIT 编译算进去。
     */
    private static void runBench(int seedFrom, int seedTo, String names) {
        List<NamedParams> configs = resolveConfigs(names);
        SyntheticTerrain.Archetype[] archetypes = SyntheticTerrain.Archetype.values();
        System.out.println("BENCH config,runs,failed,totalMs,msPerRun,avgNodes,avgPathLength,avgWaterNodes");
        System.out.flush();
        // 预热：C2 需要上千次迭代才把寻路热路径编译完，只跑十次等于没预热——第一条配置会替
        // 后面的配置背 JIT 的锅。这里先空跑 200 次（用第一条配置），再开始计时。
        for (int i = 0; i < 200; i++) {
            benchOne(seedFrom + i % 8, archetypes[i % archetypes.length], configs.get(0).params);
        }
        for (NamedParams np : configs) {
            long t0 = System.nanoTime();
            long nodes = 0;
            long water = 0;
            double length = 0;
            int runs = 0;
            int failed = 0;
            for (SyntheticTerrain.Archetype a : archetypes) {
                for (int seed = seedFrom; seed <= seedTo; seed++) {
                    RouteQualityMetrics m = benchOne(seed, a, np.params);
                    if (m == null) {
                        failed++;
                        continue;
                    }
                    nodes += m.nodeCount;
                    water += m.waterNodeCount;
                    length += m.pathLength;
                    runs++;
                }
            }
            double totalMs = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf("BENCH %s,%d,%d,%.1f,%.3f,%.1f,%.1f,%.1f%n",
                    np.name, runs, failed, totalMs, runs == 0 ? 0.0 : totalMs / runs,
                    runs == 0 ? 0.0 : (double) nodes / runs,
                    runs == 0 ? 0.0 : length / runs,
                    runs == 0 ? 0.0 : (double) water / runs);
            System.out.flush();
        }
    }

    private static RouteQualityMetrics benchOne(int seed, SyntheticTerrain.Archetype archetype,
                                                Map<String, Double> params) {
        SyntheticTerrain terrain = new SyntheticTerrain(seed, archetype);
        SyntheticTerrainField field = new SyntheticTerrainField(terrain, A_STAR_STEP);
        BlockPos start = new BlockPos(START_X, terrain.surfaceY(START_X, START_Z), START_Z);
        BlockPos end = new BlockPos(END_X, terrain.surfaceY(END_X, END_Z), END_Z);
        Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);
        return runOne(pathfinder, start, end, terrain, field, params);
    }

    private static void runCompare(int seedFrom, int seedTo, String names) {
        List<NamedParams> configs = resolveConfigs(names);
        String[] metrics = {"straightness", "avgGrade", "maxGrade", "sharpTurns", "hairpins",
                "gentleTurns", "totalTurnDeg", "waterNodes", "steepEdges"};
        StringBuilder header = new StringBuilder("archetype,seed");
        for (NamedParams np : configs) {
            for (String metric : metrics) {
                header.append(',').append(np.name).append(':').append(metric);
            }
        }
        System.out.println(header);
        System.out.flush();
        int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
                List<Future<String>> rows = new ArrayList<>();
                for (int seed = seedFrom; seed <= seedTo; seed++) {
                    final int s = seed;
                    rows.add(pool.submit(() -> compareOneSeed(s, a, configs, metrics)));
                }
                int written = 0;
                for (Future<String> row : rows) {
                    try {
                        System.out.println(row.get());
                        written++;
                    } catch (Exception e) {
                        System.err.println("compare row error archetype=" + a + ": " + e);
                    }
                }
                System.out.flush();
                System.err.println("[compare] " + a + " rows=" + written);
                System.err.flush();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String compareOneSeed(long seed, SyntheticTerrain.Archetype archetype,
                                         List<NamedParams> configs, String[] metrics) {
        SyntheticTerrain terrain = new SyntheticTerrain(seed, archetype);
        SyntheticTerrainField field = new SyntheticTerrainField(terrain, A_STAR_STEP);
        BlockPos start = new BlockPos(START_X, terrain.surfaceY(START_X, START_Z), START_Z);
        BlockPos end = new BlockPos(END_X, terrain.surfaceY(END_X, END_Z), END_Z);
        Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);
        StringBuilder sb = new StringBuilder(archetype.name()).append(',').append(seed);
        for (NamedParams np : configs) {
            RouteQualityMetrics m = runOne(pathfinder, start, end, terrain, field, np.params);
            for (String metric : metrics) {
                sb.append(',');
                if (m == null) {
                    sb.append("NA");
                    continue;
                }
                double v = switch (metric) {
                    case "straightness" -> m.straightness;
                    case "avgGrade" -> m.avgGrade;
                    case "maxGrade" -> m.maxGrade;
                    case "sharpTurns" -> m.sharpTurnCount;
                    case "hairpins" -> m.hairpinCount;
                    case "gentleTurns" -> m.gentleTurnCount;
                    case "totalTurnDeg" -> m.totalTurnDeg;
                    case "waterNodes" -> m.waterNodeCount;
                    case "steepEdges" -> m.steepEdgeCount;
                    default -> throw new IllegalArgumentException("unknown metric: " + metric);
                };
                sb.append(String.format(java.util.Locale.ROOT, "%.4f", v));
            }
        }
        return sb.toString();
    }

    /**
     * 原型诊断模式：打印每种地形原型的相邻采样点高差分布（8 格抽样），
     * 确认分段坡度代价三档（缓坡 ≤2 / 中坡 3~5 / 陡坡 >5）在地形里都被覆盖。
     */
    private static void runArchetypes(long seedFrom, long seedTo) {
        System.out.println("archetype,seed,maxStepDelta,shareMid(>2)%,shareCliff(>5)%");
        for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
            for (long seed = seedFrom; seed <= seedTo; seed++) {
                SyntheticTerrain t = new SyntheticTerrain(seed, a);
                int max = 0;
                int total = 0;
                int mid = 0;
                int cliff = 0;
                for (int z = 0; z <= SyntheticTerrain.WORLD_MAX_Z; z += 8) {
                    for (int x = 0; x + A_STAR_STEP <= SyntheticTerrain.WORLD_MAX_X; x += 8) {
                        int d = Math.abs(t.groundY(x + A_STAR_STEP, z) - t.groundY(x, z));
                        max = Math.max(max, d);
                        total++;
                        if (d > 2) {
                            mid++;
                        }
                        if (d > 5) {
                            cliff++;
                        }
                    }
                }
                System.out.printf(java.util.Locale.ROOT, "%s,%d,%d,%.2f,%.2f%n", a.name(), seed, max,
                        total == 0 ? 0.0 : mid * 100.0 / total,
                        total == 0 ? 0.0 : cliff * 100.0 / total);
            }
        }
    }

    private static String learnOneSeed(long seed, List<NamedParams> configs) {
        return learnOneSeed(seed, SyntheticTerrain.Archetype.GENTLE_HILLS, configs);
    }

    /** 单地形（原型 + 种子）× 全配置的目标函数矩阵行；第 0 个配置恒为 baseline（归一化基准）。 */
    private static String learnOneSeed(long seed, SyntheticTerrain.Archetype archetype, List<NamedParams> configs) {
        SyntheticTerrain terrain = new SyntheticTerrain(seed, archetype);
        SyntheticTerrainField field = new SyntheticTerrainField(terrain, A_STAR_STEP);
        BlockPos start = new BlockPos(START_X, terrain.surfaceY(START_X, START_Z), START_Z);
        BlockPos end = new BlockPos(END_X, terrain.surfaceY(END_X, END_Z), END_Z);
        Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);

        RouteQualityMetrics base = runOne(pathfinder, start, end, terrain, field, configs.get(0).params);
        StringBuilder sb = new StringBuilder(terrain.profile().csv(seed));
        if (base == null) {
            for (int i = 0; i < configs.size(); i++) {
                sb.append(",NA");
            }
            return sb.toString();
        }
        double baseWater = Math.max(1.0, base.waterNodeCount);
        double baseGrade = Math.max(1e-4, base.avgGrade);
        // 全矩阵输出：每个配置该种子的目标函数值（聚类后按簇对每配置求均值 argmin，比「逐种子投票」更稳）。
        for (int i = 0; i < configs.size(); i++) {
            RouteQualityMetrics m = i == 0 ? base
                    : runOne(pathfinder, start, end, terrain, field, configs.get(i).params);
            if (m == null) {
                sb.append(",NA");
            } else {
                sb.append(',').append(String.format(java.util.Locale.ROOT, "%.3f",
                        objective(m, baseWater, baseGrade)));
            }
        }
        return sb.toString();
    }

    /**
     * 真实地形模式：从 region 提取的 CSV 加载真实地形，在多条起终点对（窗口四角/中点，对角线+轴线穿越）
     * 上跑固定候选配置集，逐配置聚合打印 SUMMARY。用于在真实种子（Tectonic 高山）上做调参。
     */
    private static void runReal(String csvPath) {
        RealTerrain terrain;
        try {
            terrain = RealTerrain.load(csvPath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("load real terrain failed: " + csvPath, e);
        }
        RealTerrainField field = new RealTerrainField(terrain, A_STAR_STEP);
        List<NamedParams> configs = learnConfigs();

        int minX = terrain.minX() + 8, maxX = terrain.maxX() - 8;
        int minZ = terrain.minZ() + 8, maxZ = terrain.maxZ() - 8;
        int midX = (minX + maxX) / 2, midZ = (minZ + maxZ) / 2;
        List<BlockPos[]> pairs = new ArrayList<>();
        pairs.add(new BlockPos[]{land(terrain, minX, minZ), land(terrain, maxX, maxZ)});
        pairs.add(new BlockPos[]{land(terrain, minX, maxZ), land(terrain, maxX, minZ)});
        pairs.add(new BlockPos[]{land(terrain, minX, midZ), land(terrain, maxX, midZ)});
        pairs.add(new BlockPos[]{land(terrain, midX, minZ), land(terrain, midX, maxZ)});

        System.err.println("[real] bbox blockX " + terrain.minX() + ".." + terrain.maxX()
                + " blockZ " + terrain.minZ() + ".." + terrain.maxZ()
                + " pairs=" + pairs.size() + " configs=" + configs.size());
        Pathfinder pathfinder = PathfinderFactory.create(PathfindingAlgorithm.POTENTIAL_FIELD);
        for (NamedParams np : configs) {
            Summary agg = new Summary();
            for (BlockPos[] pair : pairs) {
                RouteQualityMetrics m = runOne(pathfinder, pair[0], pair[1], terrain, field, np.params);
                if (m != null) {
                    agg.add(m);
                }
            }
            System.out.println(np.name + " => " + agg);
        }
    }

    /** 找起终点：若给定列为水，则按环向外的顺序找最近陆地列（有限搜索）。 */
    private static BlockPos land(RealTerrain terrain, int x, int z) {
        for (int r = 0; r < 64; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int nx = x + dx, nz = z + dz;
                    if (terrain.contains(nx, nz) && !terrain.isWater(nx, nz)) {
                        return new BlockPos(nx, terrain.surfaceY(nx, nz), nz);
                    }
                }
            }
        }
        return new BlockPos(x, terrain.surfaceY(x, z), z);
    }

    private static RouteQualityMetrics runOne(Pathfinder pathfinder, BlockPos start, BlockPos end,
                                              RouteQualityMetrics.WaterProbe probe, PathTerrainField field,
                                              Map<String, Double> params) {
        PathfindingCostConfig cfg = new PathfindingCostConfig();
        cfg.setAStarStep(A_STAR_STEP);
        for (Map.Entry<String, Double> e : params.entrySet()) {
            applyParam(cfg, e.getKey(), e.getValue());
        }
        PathResult result = pathfinder.findRawPath(start, end, null, MAX_STEPS, null, field, cfg);
        if (!result.hasRawPath()) {
            return null;
        }
        return RouteQualityMetrics.of(result.rawPath(), probe);
    }

    private static double objective(RouteQualityMetrics m, double baseWater, double baseGrade) {
        double water = m.waterNodeCount / baseWater;
        double grade = m.avgGrade / baseGrade;
        double hairpins = 0.5 * m.hairpinCount;
        double steep = 0.2 * m.steepEdgeCount;
        double meander = Math.abs(m.sharpTurnCount - MEANDER_TARGET) / MEANDER_TARGET;
        double indirect = m.straightness < 0.50 ? (0.50 - m.straightness) * 20.0 : 0.0;
        return water + grade + hairpins + steep + meander + indirect;
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 跨种子聚合：输出一行 SUMMARY 均值，供敏感度扫描/搜索对比。 */
    private static final class Summary {
        int n = 0;
        double straightness = 0.0;
        double avgGrade = 0.0;
        double maxGrade = 0.0;
        double steepEdges = 0.0;
        double waterNodes = 0.0;
        double sharpTurns = 0.0;
        double hairpins = 0.0;

        void add(RouteQualityMetrics m) {
            n++;
            straightness += m.straightness;
            avgGrade += m.avgGrade;
            maxGrade += m.maxGrade;
            steepEdges += m.steepEdgeCount;
            waterNodes += m.waterNodeCount;
            sharpTurns += m.sharpTurnCount;
            hairpins += m.hairpinCount;
        }

        @Override
        public String toString() {
            if (n == 0) {
                return "SUMMARY seeds=0";
            }
            return "SUMMARY seeds=" + n
                    + " straightness=" + f3(straightness / n)
                    + " avgGrade=" + f3(avgGrade / n)
                    + " maxGrade=" + f3(maxGrade / n)
                    + " steepEdges=" + f1(steepEdges / n)
                    + " waterNodes=" + f1(waterNodes / n)
                    + " sharpTurns=" + f1(sharpTurns / n)
                    + " hairpins=" + f1(hairpins / n);
        }

        private static String f3(double v) {
            return String.format(java.util.Locale.ROOT, "%.3f", v);
        }

        private static String f1(double v) {
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }
    }

    /** 把 --param 键映射到 PathfindingCostConfig 的 setter（int 字段四舍五入）。 */
    private static void applyParam(PathfindingCostConfig cfg, String key, double v) {
        switch (key) {
            case "ortho_step_cost" -> cfg.setOrthoStepCost(v);
            case "diag_step_cost" -> cfg.setDiagStepCost(v);
            case "elevation_weight" -> cfg.setElevationWeight((int) Math.round(v));
            case "biome_weight" -> cfg.setBiomeWeight((int) Math.round(v));
            case "stability_weight" -> cfg.setStabilityWeight((int) Math.round(v));
            case "water_depth_weight" -> cfg.setWaterDepthWeight((int) Math.round(v));
            case "near_water_cost" -> cfg.setNearWaterCost((int) Math.round(v));
            case "water_proximity_cost" -> cfg.setWaterProximityCost((int) Math.round(v));
            case "heuristic_weight" -> cfg.setHeuristicWeight(v);
            case "deviation_weight" -> cfg.setDeviationWeight(v);
            case "contour_discount" -> cfg.setContourDiscount(v);
            case "soft_grade_limit" -> cfg.setSoftGradeLimit(v);
            case "hard_grade_limit" -> cfg.setHardGradeLimit(v);
            case "soft_grade_penalty" -> cfg.setSoftGradePenalty(v);
            case "hard_grade_penalty" -> cfg.setHardGradePenalty(v);
            case "gradient_align_penalty" -> cfg.setGradientAlignPenalty(v);
            case "pf_water_base_penalty" -> cfg.setPfWaterBasePenalty(v);
            case "biome_cost_water" -> cfg.setBiomeCostWater(v);
            case "biome_cost_plains" -> cfg.setBiomeCostPlains(v);
            case "biome_cost_forest" -> cfg.setBiomeCostForest(v);
            case "biome_cost_mountain" -> cfg.setBiomeCostMountain(v);
            case "biome_cost_swamp" -> cfg.setBiomeCostSwamp(v);
            case "biome_cost_desert" -> cfg.setBiomeCostDesert(v);
            case "biome_cost_other" -> cfg.setBiomeCostOther(v);
            case "contour_follow_weight" -> cfg.setContourFollowWeight(v);
            case "contour_factor_floor" -> cfg.setContourFactorFloor(v);
            case "deviation_free_radius" -> cfg.setDeviationFreeRadius((int) Math.round(v));
            case "deviation_squared" -> cfg.setDeviationSquared(v != 0.0D);
            case "deviation_hard_cap" -> cfg.setDeviationHardCap((int) Math.round(v));
            case "elevation_segmented_cost" -> cfg.setElevationSegmentedCost(v != 0.0D);
            case "elevation_gentle_max" -> cfg.setElevationGentleMax((int) Math.round(v));
            case "elevation_gentle_weight" -> cfg.setElevationGentleWeight((int) Math.round(v));
            case "elevation_steep_weight" -> cfg.setElevationSteepWeight((int) Math.round(v));
            case "elevation_cliff_threshold" -> cfg.setElevationCliffThreshold((int) Math.round(v));
            case "elevation_cliff_weight" -> cfg.setElevationCliffWeight((int) Math.round(v));
            // 0.9.030：根治批的两个开关 + 四个航向权重（默认关 ⇒ 不传参即旧行为）
            case "slope_penalty_by_length" -> cfg.setSlopePenaltyByLength(v != 0.0D);
            case "heading_flip_cost" -> cfg.setHeadingFlipCost(v);
            case "heading_curve_cost" -> cfg.setHeadingCurveCost(v);
            case "heading_short_leg_blocks" -> cfg.setHeadingShortLegBlocks(v);
            case "heading_short_leg_multiplier" -> cfg.setHeadingShortLegMultiplier(v);
            default -> throw new IllegalArgumentException("unknown param: " + key);
        }
    }
}
