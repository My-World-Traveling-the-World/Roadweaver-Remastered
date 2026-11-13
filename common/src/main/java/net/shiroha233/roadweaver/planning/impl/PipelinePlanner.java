/* 文件职责：把新三层内核（度量→拓扑）接成路网规划器 —— 第六种 planningAlgorithm。 */
package net.shiroha233.roadweaver.planning.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pipeline.RoadNetworkPlan;
import net.shiroha233.roadweaver.pipeline.QualityGate;
import net.shiroha233.roadweaver.pipeline.RoadPipeline;
import net.shiroha233.roadweaver.planning.NetworkPlanner;
import net.shiroha233.roadweaver.topology.RoadNode;

/**
 * 用新三层内核做<b>路网拓扑</b>的规划器 —— 第六种 {@code planningAlgorithm}。
 *
 * <h2>它替换的是什么（以及不替换什么）</h2>
 * <p>现有六种规划器（KNN / Delaunay / RNG / MST / SNAP_BRANCH / ALPHA_MODEL）都用
 * <b>直线距离</b>决定「哪两个结构该连一条路」。本类改用<b>测地距离</b> ——
 * 也就是「绕开山和水，实际要走多远」。两座结构直线 200 格、中间隔着一道山脊，
 * 直线距离看不出问题，测地距离会说实话。</p>
 *
 * <p><b>它不替换单条路线的几何</b>：这里只输出拓扑（端点对），
 * 具体走线仍由后续 {@code Pathfinder} 算。理由见下面「为什么丢掉曲线」。</p>
 *
 * <h2>★ 为什么丢掉 RoadPipeline 算出来的曲线</h2>
 * <p>{@code RoadPipeline.run} 除了拓扑还给出每条边的弹性带平滑曲线
 * （{@code RoadEdge.curve}）。但生成链现在是「规划器给端点对 → A* 现算路径」，
 * <b>没有接收预计算曲线的入口</b>。要接就得改生成链，而改动的风险是：
 * 整张路网的形状会变，必须真机 A/B 才能定。</p>
 *
 * <p>所以这一版只取拓扑。曲线确实白算了 —— 但那点开销（回溯 + 曲率平滑，
 * 每条边几十毫秒）相对「先证明测地距离的拓扑更好」这个大前提来说是值得的。
 * 等真机确认拓扑方向对了，再决定要不要连几何一起接管。</p>
 *
 * <h2>★ 为什么不用避让势场</h2>
 * <p>{@code FinslerMetric} 接受 {@link ObstacleField#NONE}。网络级规划用它是<b>有意的</b>：</p>
 * <ul>
 *   <li>拓扑决策问的是「这两个结构之间大致有多远」，避让是<b>走线</b>层面的事，</li>
 *   <li>真要按结构避让，需要 {@code StructureAvoidanceService} 的规划快照 ——
 *       而那套是<b>按连接</b>预算的（{@code routeKey(level, connection)}），
 *       网络级此刻还没有连接，根本取不到快照，</li>
 *   <li>后续 A* 走线时避让照旧生效（判据一字不改）。</li>
 * </ul>
 * <p>代价是：结构密集区里测地距离可能略乐观。这属于「已知近似」，不是遗漏。</p>
 *
 * <h2>采样步长与规模</h2>
 * <p>规划区域动辄 1500×1500。逐格采样是 225 万列，采不完也存不下 ——
 * 所以用 {@link #DEFAULT_STEP} = 8（与旧 A* 的 {@code aStarStep} 同量级）。
 * 625×625 ≈ 39 万列，拓扑决策不需要方格精度；方格精度由单条路线自己的
 * 包围盒负责。</p>
 *
 * @see RoadPipeline
 * @see net.shiroha233.roadweaver.pipeline.QualityGate
 */
public final class PipelinePlanner implements NetworkPlanner {

    /** 拓扑决策的采样步长（格）。8 与旧 A* 的 {@code effectiveAStarStep()} 同量级。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");

    public static final int DEFAULT_STEP = 8;

    /** 包围盒在结构点外侧额外留的余量（格）—— 给绕行留空间。 */
    private static final int BOUNDS_PADDING = 64;

    private final ServerLevel level;
    private final int step;

    public PipelinePlanner(ServerLevel level) {
        this(level, DEFAULT_STEP);
    }

    public PipelinePlanner(ServerLevel level, int step) {
        this.level = level;
        this.step = Math.max(1, step);
    }

    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points == null || points.size() < 2) {
            return List.of();
        }
        if (level == null) {
            // 没有世界就建不了地形场。退回直线距离的 MST —— 宁可退化也不抛异常：
            // 规划器挂掉会让整局一条路都不生成，那比「用旧算法」严重得多。
            return MSTPlanner.planMST(points, maxEdgeLenBlocks);
        }

        // ① 去重并记录每点的代表坐标（节点只有 x/z，y 要从原 BlockPos 取回）
        List<RoadNode> nodes = new ArrayList<>(points.size());
        List<BlockPos> representatives = new ArrayList<>(points.size());
        HashSet<Long> seen = new HashSet<>();
        for (BlockPos p : points) {
            if (p == null) {
                continue;
            }
            long key = xzKey(p.getX(), p.getZ());
            if (!seen.add(key)) {
                continue;
            }
            nodes.add(new RoadNode("s" + nodes.size(), p.getX(), p.getZ()));
            representatives.add(p);
        }
        if (nodes.size() < 2) {
            return List.of();
        }

        // ② 包围盒
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : representatives) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        minX -= BOUNDS_PADDING;
        maxX += BOUNDS_PADDING;
        minZ -= BOUNDS_PADDING;
        maxZ += BOUNDS_PADDING;
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;

        // ③ 地形场（带步长）+ 度量（无避让，理由见类注释）
        TerrainField terrain;
        try {
            terrain = SampledTerrainField.fromAccurateSampler(
                    AccurateHeightSampler.create(level), minX, minZ, width, depth, step);
        } catch (RuntimeException samplingFailure) {
            // 采样失败（区域过大 / 维度不支持等）时退回直线距离，不让规划整体失败
            return MSTPlanner.planMST(points, maxEdgeLenBlocks);
        }
        FinslerMetric metric = FinslerMetric.of(terrain, ObstacleField.NONE);

        // ④ 跑两层（度量 + 拓扑）。几何部分（ElasticBandSolver）只是让
        //    RoadPipeline 能构造出来，本类不消费它算出的曲线。
        RoadNetworkPlan plan;
        try {
            plan = new RoadPipeline(metric, ElasticBandSolver.defaults()).run(nodes);
        } catch (RuntimeException pipelineFailure) {
            return MSTPlanner.planMST(points, maxEdgeLenBlocks);
        }

        // ⑤ 质量门 —— 只测不拦
        //
        // QualityGate 的设计初衷是「把质量指标接到接受/拒绝的决策上，而不是只打日志」
        // （它的类注释举了真机例子：fine regenerate 把 17 条路里的 8 条变长、9 条 dupXz
        // 增加，却无条件全部接受）。但那套判据的阈值来自**旧链**的真机基线，
        // 而本规划器走的是测地距离，绕行比的分布本来就不同 —— 直接拿旧阈值去拦，
        // 很可能把整张路网判成不合格、然后一条路都不生成。
        //
        // 所以第一版**只测不拦**：把 Report 打进日志，让真实数据先出来。
        // 等看过几局 nodes/edges/isolated/weightedDetour 的实际分布，再决定要不要拦、
        // 以及阈值该定多少。用 lenient()（绕行比 3.0、允许孤立、不查曲率）而不是
        // defaults()（1.5、不许孤立、曲率不增）就是为了让日志有信息量而不是满屏 FAIL。
        QualityGate.Report report = QualityGate.evaluate(plan, QualityGate.Thresholds.lenient());
        if (report.passed()) {
            LOGGER.info("RoadWeaver network quality: {}", report.describe());
        } else {
            LOGGER.warn("RoadWeaver network quality below gate (REPORT-ONLY, result still adopted): {}",
                    report.describe());
        }

        // ⑥ 拓扑 → StructureConnection（长度上限沿用配置口径）
        long maxD2 = maxEdgeLenBlocks > 0
                ? (long) maxEdgeLenBlocks * (long) maxEdgeLenBlocks
                : Long.MAX_VALUE;
        List<StructureConnection> out = new ArrayList<>(plan.edges().size());
        for (RoadNetworkPlan.RoadEdge edge : plan.edges()) {
            if (edge.from() < 0 || edge.from() >= representatives.size()
                    || edge.to() < 0 || edge.to() >= representatives.size()) {
                continue;                                  // 防御：索引错位绝不能铺路
            }
            BlockPos from = representatives.get(edge.from());
            BlockPos to = representatives.get(edge.to());
            if (maxEdgeLenBlocks > 0 && from.distSqr(to) > maxD2) {
                continue;                                  // 超长边与其它规划器同口径丢弃
            }
            out.add(new StructureConnection(from, to, ConnectionStatus.PLANNED));
        }
        return out;
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}