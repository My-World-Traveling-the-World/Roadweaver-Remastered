/* 文件职责：三段式的第①③段与②之间那层胶水——抽过河点、发起协商、按簇给每条路配点吸引。 */
package net.shiroha233.roadweaver.planning.terrain;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiator;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.9.057「并行 → 协商 → 并行」三段式里夹在两遍并行规划之间的那层胶水。
 *
 * <p>三个地形规划适配器（粗走廊 / 全区域 / 旧直连）都用同一份逻辑，区别只在
 * 「第一遍 / 第二遍怎么提交任务」（粗走廊是并行提交，另外两个本来就是串行）：</p>
 * <ol>
 *   <li>{@link #agree} 拿第一遍的结果，按「水上跨度」抽出每条路的
 *       {@link CrossingNegotiator.CrossingPoint}（取不到就跳过该条），交给
 *       {@link CrossingNegotiator#negotiate} 协商；</li>
 *   <li>每个成簇的共享过河点造一个 {@link CorridorAttract#aroundPoint}，回填给簇内的每条路；</li>
 *   <li>调用方只对「拿到了吸引」的路跑第二遍，没拿到保持第一遍结果不变。</li>
 * </ol>
 *
 * <p><b>关掉时整条新路径跳过</b>：{@code corridorAttractRadius <= 0} 或
 * {@code corridorAttractFactor >= 1.0} 时 {@link #agree} 返回 {@code null}，
 * 调用方不得进入第二遍 —— 这就是 0.9.055 的行为，逐位相同。</p>
 */
final class CrossingNegotiation {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private CrossingNegotiation() {}

    /**
     * 协商结果。
     *
     * @param byRoad       每条路自己的点吸引（不在任何簇里的路<b>不在这个表里</b>）
     * @param clusterByRoad 每条路所属的簇（0.9.058：第二遍要按 {@code sharedDeckY} 压纵断面）
     * @param clusters     协商出的簇数
     * @param sharedPoints 被并进共享过河点的过河点总数（Σ 簇成员）
     */
    record Agreement(Map<StructureConnection, CorridorAttract> byRoad,
                     Map<StructureConnection, CrossingNegotiator.Cluster> clusterByRoad,
                     int clusters,
                     int sharedPoints) {
        Agreement {
            byRoad = Map.copyOf(byRoad == null ? Map.of() : byRoad);
            clusterByRoad = Map.copyOf(clusterByRoad == null ? Map.of() : clusterByRoad);
        }

        CorridorAttract attractFor(StructureConnection connection) {
            return connection == null ? null : byRoad.get(connection);
        }

        /** 0.9.058：这条路要压到的共享甲板簇；没有则为 {@code null}（保持第一遍剖面）。 */
        CrossingNegotiator.Cluster clusterFor(StructureConnection connection) {
            return connection == null ? null : clusterByRoad.get(connection);
        }

        boolean isEmpty() {
            return byRoad.isEmpty();
        }
    }

    /** {@code [PATH-REUSE]} 一行日志需要的全部数字。 */
    record Stats(int clusters,
                 int sharedPoints,
                 int sharedDeckMin,
                 int sharedDeckMax,
                 int roads,
                 long attractedStations,
                 long totalStations) {
        double ratio() {
            return totalStations <= 0L ? 0.0D : (double) attractedStations / (double) totalStations;
        }

        /** {@code sharedDeckY(min..max)} 的值；全簇甲板都未知时写 {@code n/a}。 */
        String sharedDeckRange() {
            if (sharedDeckMin == Integer.MIN_VALUE || sharedDeckMax == Integer.MIN_VALUE) return "n/a";
            return sharedDeckMin + ".." + sharedDeckMax;
        }
    }

    /**
     * 抽过河点 → 协商 → 给每条路配点吸引。
     *
     * @return 关闭时 {@code null}（调用方必须整条新路径跳过）；开启时总是非 {@code null}，
     *         没有可协商的冲突就是空 {@link Agreement}
     */
    static Agreement agree(List<StructureConnection> connections,
                           Map<StructureConnection, List<BlockPos>> firstPass,
                           PathTerrainField terrain,
                           PathfindingCostConfig config) {
        if (config == null) return null;
        int radius = config.corridorAttractRadius();
        double factor = config.corridorAttractFactor();
        // 总开关：与 CorridorAttract.forPlanning 同一条判据，关掉即整条新路径跳过。
        if (radius <= 0 || !(factor < 1.0D)) return null;
        if (connections == null || connections.isEmpty() || firstPass == null || firstPass.isEmpty()) {
            return new Agreement(Map.of(), Map.of(), 0, 0);
        }

        Map<String, StructureConnection> ownerToConnection = new LinkedHashMap<>();
        List<CrossingNegotiator.CrossingPoint> points = new ArrayList<>(firstPass.size());
        // 0.9.058（缺陷 B）：一条路**交出全部连续湿段**，不再只留最长的一段。
        //
        // 改前每条路只交一个点（最长的那段水）。一条路多次过水、而冲突那次不是最长
        // 段时，该路在协商里**静默缺席** —— 真机「两条并行桥甲板 48/65」里就有一条
        // 是这么漏掉的。现在全交，由 negotiate 按簇去选；同一条路出现在多个簇时，
        // 取**簇序最前**的那个（簇序由簇内最优点的全局序号决定 ⇒ 与输入顺序无关）。
        for (StructureConnection connection : connections) {
            List<BlockPos> path = firstPass.get(connection);
            List<CrossingNegotiator.CrossingPoint> extracted = extract(connection, path, terrain);
            // 取不到过河点就跳过该条：它没有水上跨度，也就没有可协商的东西。
            if (extracted.isEmpty()) continue;
            for (CrossingNegotiator.CrossingPoint point : extracted) {
                ownerToConnection.put(point.ownerKey(), connection);
                points.add(point);
            }
        }
        if (points.size() < 2) {
            // 不到两条有过河点的路 ⇒ 谈不上「共享」，也就不该有任何吸引。
            return new Agreement(Map.of(), Map.of(), 0, 0);
        }

        List<CrossingNegotiator.Cluster> clusters = CrossingNegotiator.negotiate(
                points, RoadConstants.DEFAULT_CROSSING_CLUSTER_RADIUS);
        if (clusters.isEmpty()) {
            return new Agreement(Map.of(), Map.of(), 0, 0);
        }

        Map<StructureConnection, CorridorAttract> byRoad = new LinkedHashMap<>();
        Map<StructureConnection, CrossingNegotiator.Cluster> clusterByRoad = new LinkedHashMap<>();
        int sharedPoints = 0;
        for (CrossingNegotiator.Cluster cluster : clusters) {
            CorridorAttract attract = CorridorAttract.aroundPoint(
                    cluster.sharedX(), cluster.sharedZ(), radius, factor);
            if (attract.isEmpty()) continue;
            for (CrossingNegotiator.CrossingPoint member : cluster.members()) {
                StructureConnection owner = ownerToConnection.get(member.ownerKey());
                if (owner == null) continue;
                // putIfAbsent：一条路可以同时落在几簇里（它交出了多个湿段），
                // 这时固定取簇序最前的那个 —— 顺序来自全序排序，与输入顺序无关。
                byRoad.putIfAbsent(owner, attract);
                clusterByRoad.putIfAbsent(owner, cluster);
                sharedPoints++;
            }
        }
        return new Agreement(byRoad, clusterByRoad, clusters.size(), sharedPoints);
    }

    /** 第二遍「重算一条被吸引的路」的具体做法（各适配器自带地形场与缓存）。 */
    @FunctionalInterface
    interface RouteSearch {
        List<BlockPos> find(StructureConnection connection, CorridorAttract attract);
    }

    /**
     * 串行版第二遍（全区域 / 旧直连两个适配器用；它们本来是串行的，不在这里改并行度）。
     *
     * <p>只重算「拿到共享过河点」的路；没拿到的<b>一个字节都不动</b>。
     * 某条路重算失败只丢这一次优化尝试、保留第一遍产出，<b>但中断照常向上传播</b>。</p>
     *
     * <p>0.9.058（缺陷 B）：重算出来的路径还要按所属簇的 {@code sharedDeckY}
     * 压一次纵断面（{@link #pressToSharedDeck}）—— 只统一 XZ 不统一甲板，
     * 两条并行桥还是会各架各的（真机 48 vs 65）。</p>
     */
    static void rerouteSerially(List<StructureConnection> connections,
                                Agreement agreement,
                                RouteSearch search,
                                Map<StructureConnection, List<BlockPos>> paths,
                                int terrainStep) {
        if (connections == null || agreement == null || agreement.isEmpty()
                || search == null || paths == null) {
            return;
        }
        for (StructureConnection connection : connections) {
            CorridorAttract attract = agreement.attractFor(connection);
            if (attract == null || attract.isEmpty()) continue;
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("crossing re-plan interrupted");
            }
            try {
                List<BlockPos> path = search.find(connection, attract);
                if (path != null && !path.isEmpty()) {
                    paths.put(connection, pressToSharedDeck(
                            path, agreement.clusterFor(connection), terrainStep));
                }
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                LOGGER.warn("【道路复用第二轮失败，保留该路首轮路径】 " + "[PATH-REUSE] second pass failed for {}, keeping first-pass path",
                        ownerKey(connection), failure);
            }
        }
    }

    /** 单侧引道最多压多少站（限幅；喇叭形约束每站最多 1 格，所以也等于最多压多少格）。 */
    static final int DECK_PRESS_MAX_RAMP_STATIONS = 32;
    /** 平整核最多几站（再宽就只是把整条路抹平，不是「共享甲板」了）。 */
    static final int DECK_PRESS_MAX_FLAT_STATIONS = 8;

    /**
     * 0.9.058（缺陷 B）：按簇的 {@code sharedDeckY} 压一条路的纵断面。
     *
     * <p>平整核半径由「簇内最优成员的湿段宽度 ÷ 地形步长 ÷ 2」推出来 —— 湿段有多宽，
     * 甲板就得平多宽；两侧再用每站 1 格的喇叭形引道汇入原剖面。簇没有甲板
     * （{@code sharedDeckY == Integer.MIN_VALUE}，例如旧调用方交的是 5 参数过河点）
     * 时原样返回，逐位保持第一遍产出。</p>
     */
    static List<BlockPos> pressToSharedDeck(List<BlockPos> path,
                                            CrossingNegotiator.Cluster cluster,
                                            int terrainStep) {
        if (path == null || path.isEmpty() || cluster == null) return path;
        if (cluster.sharedDeckY() == Integer.MIN_VALUE) return path;
        int step = Math.max(1, terrainStep);
        int widestRunBlocks = 0;
        for (CrossingNegotiator.CrossingPoint member : cluster.members()) {
            widestRunBlocks = Math.max(widestRunBlocks, member.widthBlocks());
        }
        int flat = Math.max(1, Math.min(DECK_PRESS_MAX_FLAT_STATIONS, widestRunBlocks / (2 * step)));
        return CrossingNegotiator.pressProfileToSharedDeck(
                path, cluster.sharedX(), cluster.sharedZ(), cluster.sharedDeckY(),
                flat, DECK_PRESS_MAX_RAMP_STATIONS);
    }

    /**
     * 从一条路的原始路径里抽出**全部**连续湿段（0.9.058，缺陷 B）。
     *
     * <p>规划阶段还没有 {@code RoadSpan}（那些是生成阶段才落盘的），所以按<b>湿地采样</b>自己数：
     * 沿路径找连续的 {@code isBridgeWater} 节点段，<b>每一段都交出一个过河点</b>，
     * 中心取该段中间那个节点，宽度 = 段内节点数 × 网格步长，水深取段内最大值。</p>
     *
     * <h2>为什么不再只留「最长的一段」</h2>
     * <p>0.9.057 每条路只交最长的一段。于是一条路多次过水、而<b>真正和邻居冲突的那次
     * 不是最长段</b>时，这条路在协商里静默缺席 —— 真机「两条并行桥甲板 48/65」里有
     * 一条就是这样漏掉的。现在全交，由 {@link CrossingNegotiator#negotiate} 按簇去选；
     * 同一条路落在多簇时由 {@link #agree} 取簇序最前的那个（簇序来自全序排序，
     * 与输入顺序无关）。</p>
     *
     * <p>每段还带上它<b>自己的甲板高度</b>（{@code deckY}）：有水 ⇒ 水面 + clearance，
     * 无水 ⇒ 两岸站 Y 的较大者 + clearance，与生成期
     * {@code BridgeDeckProfile.minimumDeckY} 同一套口径。协商层取簇内最大值，
     * 于是簇里每条路都压到同一个甲板。</p>
     *
     * @return 按路径顺序（起点→终点）排列的过河点；没有水上跨度时返回空列表
     */
    static List<CrossingNegotiator.CrossingPoint> extract(StructureConnection connection,
                                                          List<BlockPos> path,
                                                          PathTerrainField terrain) {
        if (connection == null || path == null || path.isEmpty() || terrain == null) return List.of();
        int step = Math.max(1, terrain.step());
        int minWaterDepth = bridgeMinWaterDepth();
        int clearance = bridgeDeckClearance();
        List<CrossingNegotiator.CrossingPoint> points = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i <= path.size(); i++) {
            boolean inWater = false;
            if (i < path.size()) {
                BlockPos node = path.get(i);
                inWater = node != null && terrain.isBridgeWater(node.getX(), node.getZ(), minWaterDepth);
            }
            if (inWater) {
                if (runStart < 0) runStart = i;
                continue;
            }
            if (runStart < 0) continue;
            CrossingNegotiator.CrossingPoint point = crossingPointForRun(
                    connection, path, terrain, runStart, i - 1, step, minWaterDepth, clearance);
            if (point != null) points.add(point);
            runStart = -1;
        }
        return List.copyOf(points);
    }

    /** 一个连续湿段 → 一个过河点（中心 / 宽度 / 水深 / 甲板高度）。 */
    private static CrossingNegotiator.CrossingPoint crossingPointForRun(StructureConnection connection,
                                                                       List<BlockPos> path,
                                                                       PathTerrainField terrain,
                                                                       int runStart,
                                                                       int runEnd,
                                                                       int step,
                                                                       int minWaterDepth,
                                                                       int clearance) {
        int centerIndex = (runStart + runEnd) >>> 1;
        BlockPos center = path.get(centerIndex);
        if (center == null) center = path.get(runStart);
        if (center == null) return null;
        int depth = 0;
        int waterSurface = Integer.MIN_VALUE;
        for (int k = runStart; k <= runEnd; k++) {
            BlockPos node = path.get(k);
            if (node == null) continue;
            depth = Math.max(depth, terrain.waterDepth(node.getX(), node.getZ()));
            if (!terrain.isBridgeWater(node.getX(), node.getZ(), minWaterDepth)) continue;
            // 与 PathSpanExtractor.bridgeWaterSample 的 terrain 分支同一个换算：
            // 水面 Y = SurfaceWaterClassifier.waterSurfaceY(高度图, 海底, 水群系, 海平面, 有水列)。
            PathTerrainField.SampleBundle bundle = terrain.sampleBundle(node.getX(), node.getZ());
            int surface = SurfaceWaterClassifier.waterSurfaceY(bundle.height(), bundle.oceanFloor(),
                    bundle.waterBiome(), terrain.seaLevel(), bundle.columnWater());
            if (surface != Integer.MIN_VALUE) waterSurface = Math.max(waterSurface, surface);
        }
        BlockPos leftBank = runStart > 0 ? path.get(runStart - 1) : null;
        BlockPos rightBank = runEnd + 1 < path.size() ? path.get(runEnd + 1) : null;
        int leftY = leftBank != null ? leftBank.getY() : path.get(runStart).getY();
        int rightY = rightBank != null ? rightBank.getY() : path.get(runEnd).getY();
        int deckY = BridgeDeckProfile.minimumDeckY(waterSurface, leftY, rightY, clearance);
        return new CrossingNegotiator.CrossingPoint(ownerKey(connection),
                center.getX(), center.getZ(), (runEnd - runStart + 1) * step, depth, deckY);
    }

    /** 每条路在协商里的稳定标识：几何坐标，与批内顺序无关。 */
    static String ownerKey(StructureConnection connection) {
        BlockPos from = connection.from();
        BlockPos to = connection.to();
        return "(" + from.getX() + "," + from.getZ() + ")->(" + to.getX() + "," + to.getZ() + ")";
    }

    /** 统计最终产出的路径有多少站落在被吸引的走廊里（{@code [PATH-REUSE]} 用）。 */
    static Stats stats(Agreement agreement, Map<StructureConnection, List<BlockPos>> paths) {
        int clusters = agreement == null ? 0 : agreement.clusters();
        int sharedPoints = agreement == null ? 0 : agreement.sharedPoints();
        int sharedDeckMin = Integer.MIN_VALUE;
        int sharedDeckMax = Integer.MIN_VALUE;
        if (agreement != null) {
            for (CrossingNegotiator.Cluster cluster : agreement.clusterByRoad().values()) {
                int deck = cluster == null ? Integer.MIN_VALUE : cluster.sharedDeckY();
                if (deck == Integer.MIN_VALUE) continue;
                sharedDeckMin = sharedDeckMin == Integer.MIN_VALUE ? deck : Math.min(sharedDeckMin, deck);
                sharedDeckMax = sharedDeckMax == Integer.MIN_VALUE ? deck : Math.max(sharedDeckMax, deck);
            }
        }
        int roads = paths == null ? 0 : paths.size();
        long attractedStations = 0L;
        long totalStations = 0L;
        if (paths != null) {
            for (Map.Entry<StructureConnection, List<BlockPos>> entry : paths.entrySet()) {
                List<BlockPos> path = entry.getValue();
                if (path == null || path.isEmpty()) continue;
                CorridorAttract attract = agreement == null ? null : agreement.attractFor(entry.getKey());
                for (BlockPos node : path) {
                    totalStations++;
                    if (attract != null && node != null
                            && attract.hits(node.getX(), node.getZ())) {
                        attractedStations++;
                    }
                }
            }
        }
        return new Stats(clusters, sharedPoints, sharedDeckMin, sharedDeckMax,
                roads, attractedStations, totalStations);
    }

    /**
     * 与 {@code RawPathSearch#globalBridgeMinDepth()} 同口径的水深门槛：
     * 全局 {@code BRIDGE_FIRST} 模式下水深门槛是 1，否则读配置 {@code bridge.minWaterDepth}。
     * 读配置失败时退回 1（宁可多认出一次跨越，也不要因为配置异常把整条机制静默关掉）。
     */
    private static int bridgeMinWaterDepth() {
        try {
            boolean bridgeFirst = ConfigService.get().planning().terrainProfileMode()
                    == PlanningConfig.TerrainProfileMode.BRIDGE_FIRST;
            if (bridgeFirst) return 1;
            return Math.max(1, ConfigService.get().bridge().minWaterDepth());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /**
     * 与生成期 {@code RoadSpanProfileOps.applyCrossingProfiles} 同一条甲板余量（{@code cfg.bridgeDeckClearance()}
     * 经 {@code Math.max(1, ...)}）。**不是新配置面** —— 用的就是既有的 bridge.deckClearance。
     * 读配置失败时退回 1（宁可甲板低一格，也不要因为配置异常把协商整段关掉）。
     */
    private static int bridgeDeckClearance() {
        try {
            return Math.max(1, ConfigService.get().bridgeDeckClearance());
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
