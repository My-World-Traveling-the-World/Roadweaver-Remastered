/* 文件职责：把新三层内核（度量→拓扑）接成路网规划器 —— 第六种 planningAlgorithm。 */
package net.shiroha233.roadweaver.planning.impl;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.bridge.TiledTerrainField;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pipeline.QualityGate;
import net.shiroha233.roadweaver.pipeline.RoadPipeline;
import net.shiroha233.roadweaver.planning.NetworkPlanner;
import net.shiroha233.roadweaver.planning.RouteEvidenceId;
import net.shiroha233.roadweaver.topology.RoadNode;
import net.shiroha233.roadweaver.topology.TopologyPlan;

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
 * <h2>★ 为什么只取拓扑（2026-11-15 更正旧说法）</h2>
 * <p>本类现在调用的是 {@code RoadPipeline.planTopology}，<b>不会先跑完整 run()
 * 再丢掉曲线</b>；完整 run() 也没有 main 调用。单路几何由后续 BAND 链现算。</p>
 *
 * <p>早期版本确实曾先算曲线再丢弃；那段已经由「只跑拓扑轮」修掉。
 * 若将来要让全网预计算曲线接管单路几何，属于另一项会改变路网形状的改动，
 * 仍需真机 A/B，不能靠注释默认它已发生。</p>
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
 * 所以用 {@link #DEFAULT_STEP} = 8（与旧 A* 的 {@code pathGridStep} 同量级）。
 * 625×625 ≈ 39 万列，拓扑决策不需要方格精度；方格精度由单条路线自己的
 * 包围盒负责。</p>
 *
 * @see RoadPipeline
 * @see net.shiroha233.roadweaver.pipeline.QualityGate
 */
public final class PipelinePlanner implements NetworkPlanner {

    /** 拓扑决策的采样步长（格）。8 与旧 A* 的 {@code effectivePathGridStep()} 同量级。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");
    private static final boolean BUILDABILITY_SHADOW =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.buildabilityShadow"));

    public static final int DEFAULT_STEP = 8;

    /** 包围盒在结构点外侧额外留的余量（格）—— 给绕行留空间。 */
    private static final int BOUNDS_PADDING = 64;

    private final java.util.function.Supplier<TiledTerrainField.GridSampler> samplerFactory;
    private final int step;
    private final String evidenceDimension;
    private final long evidenceSeed;

    public PipelinePlanner(ServerLevel level) {
        this(level, DEFAULT_STEP);
    }

    public PipelinePlanner(ServerLevel level, int step) {
        // 世界读入留在这一处，离线测试注入同一规划入口，不复制拓扑算法。
        this(step, level == null ? null : () -> {
            var sampler = AccurateHeightSampler.create(level);
            return sampler::sampleGrid;
        }, level == null ? "offline" : level.dimension().location().toString(), level == null ? 0 : level.getSeed());
    }

    PipelinePlanner(int step, java.util.function.Supplier<TiledTerrainField.GridSampler> samplerFactory) {
        this(step, samplerFactory, "offline", 0);
    }

    private PipelinePlanner(int step, java.util.function.Supplier<TiledTerrainField.GridSampler> samplerFactory,
                            String evidenceDimension, long evidenceSeed) {
        this.samplerFactory = samplerFactory;
        this.step = Math.max(1, step);
        this.evidenceDimension = evidenceDimension;
        this.evidenceSeed = evidenceSeed;
    }

    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points == null || points.size() < 2) {
            return List.of();
        }
        checkInterrupted();

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
        if (samplerFactory == null) {
            // 缺世界读取器是前置失败，不能静默改用直线算法并持久化一张不同的路网。
            throw new IllegalStateException("测地规划缺少世界读取器，未生成替代路网");
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
        // 原始边界先保留；加余量、算点数和估算载荷都在读世界之前完成。
        long width = (long) maxX - minX + 1;
        long depth = (long) maxZ - minZ + 1;

        // ③ 地形场（带步长）+ 度量（无避让，理由见类注释）
        TerrainField terrain;
        SampledTerrainField.SurfaceGrid shadowSurface;
        try {
            var layout = SampledTerrainField.planningLayout(minX, minZ, maxX, maxZ, BOUNDS_PADDING, step);
            var work = SampledTerrainField.estimate(layout, BUILDABILITY_SHADOW, nodes.size());
            // 分页访问比例事前未知，同时给出稠密参考和全访问上界，不能冒充当前堆占用。
            LOGGER.info("【规划工作集估计，不包含其他任务及完整对象开销】 " + "[PLANNING-WORKSET] grid={}x{} step={} cells={} samplingDenseHeightBytes={} "
                            + "retainedDenseHeightBytes={} solverDenseReferenceBytes={} solverPagedFullVisitBytes={} "
                            + "targetBytesUpper={} heapMaxBytes={} excludes=pageDirectories,frontier,objects,samplerCaches,otherTasks",
                    layout.columns(), layout.rows(), step, work.cells(), work.samplingHeightBytes(),
                    work.retainedHeightBytes(), work.solverFixedBytes(), work.pagedSolverFullVisitPayloadBytes(),
                    work.targetBytesUpperBound(),
                    Runtime.getRuntime().maxMemory());
            layout.arrayLength();
            minX = layout.minX(); minZ = layout.minZ();
            width = layout.blockWidth(); depth = layout.blockDepth();
            // 小域仍一次采完；大域复用按需三通道瓦片，真实采样继续走原预算和生命周期。
            var surface = SampledTerrainField.planningSurface(layout, samplerFactory.get());
            terrain = surface.terrain();
            shadowSurface = BUILDABILITY_SHADOW ? surface : null;
        } catch (RuntimeException samplingFailure) {
            // 取消保留原类型和中断位；其他失败也向上游传播，交由区域规划原重试协议处理。
            if (samplingFailure instanceof java.util.concurrent.CancellationException) throw samplingFailure;
            LOGGER.warn("测地规划地形采样失败，保留原故障且未改用其他算法（区域 {}x{} step={} points={}）",
                    width, depth, step, points.size(), samplingFailure);
            throw samplingFailure;
        }
        checkInterrupted();
        FinslerMetric metric = FinslerMetric.of(terrain, ObstacleField.NONE);

        // ④ 跑拓扑层（测地矩阵 + MST）
        //
        // 2026-11-14 修复（审计 NEW-3）：这里原来调 RoadPipeline.run，它会先在拓扑层
        // 跑 N 次场求解得到距离矩阵、求出 MST，然后再对 MST 用到的每个源**重新求解一次**
        // 场、回溯曲线、跑弹性带平滑 —— 但本类只用端点对，全部曲线算完即弃。
        // 而下游的单条路线又会自己重建局部网格、自己跑场求解。
        // 现在改调 planTopology：边集与原来**完全相同**（同一个 GeodesicMatrix + MST），
        // 避免白算第二轮几何；真实收益受采样等阶段影响，须按现场计时判断。
        TopologyPlan topology;
        try {
            // 空间桶只剪掉水平距离必然超限的对；高度仍由原端点判据决定。
            // 无上限时所有节点都合法，无需另外建候选掩码。
            var pipeline = new RoadPipeline(metric, null);
            if (shadowSurface != null) {
                topology = pipeline.planTopologyWithShadow(nodes, maxEdgeLenBlocks,
                        maxEdgeLenBlocks <= 0 ? null : (from, to) ->
                                withinMaxEdge(representatives.get(from), representatives.get(to), maxEdgeLenBlocks),
                        shadowSurface::waterProxy, evidence -> LOGGER.info(
                                "【粗候选路网评分证据，尚未确认保护与可施工性】 " + "[ROUTE-EVIDENCE] id={} stage=coarse selected={} geodesic={} protection=unprepared detail={}",
                                RouteEvidenceId.of(evidenceDimension, evidenceSeed,
                                        RouteEvidenceId.endpoint(evidence.fromX(), evidence.fromZ()),
                                        RouteEvidenceId.endpoint(evidence.toX(), evidence.toZ())),
                                evidence.selected(), evidence.geodesic(), evidence.detail()));
            } else if (maxEdgeLenBlocks <= 0) {
                topology = pipeline.planTopology(nodes);
            } else {
                topology = pipeline.planTopologyWithin(nodes, maxEdgeLenBlocks, (from, to) ->
                        withinMaxEdge(representatives.get(from), representatives.get(to), maxEdgeLenBlocks));
            }
        } catch (RuntimeException pipelineFailure) {
            // 大域按需采样在求解期间发生，不能只修上面的密集采样分支。
            if (pipelineFailure instanceof java.util.concurrent.CancellationException) throw pipelineFailure;
            LOGGER.warn("测地拓扑求解失败，保留原故障且未改用其他算法（nodes={}）", nodes.size(), pipelineFailure);
            throw pipelineFailure;
        }
        checkInterrupted();
        if (terrain instanceof TiledTerrainField tiled) {
            // 统计实际请求与驱逐，不把 3 MiB 瓦片缓存冒充总内存上限。
            var stats = tiled.stats();
            LOGGER.info("【规划高度瓦片读取、驱逐及保留载荷】 " + "[PLANNING-HEIGHT-TILES] attempts={} loaded={} columns={} evictions={} "
                            + "retainedTiles={} retainedHeightPayloadBytes={} excludes=samplerCaches,objects,temporaryWork",
                    stats.loadAttempts(), stats.loadedTiles(), stats.loadedColumns(), stats.evictions(),
                    stats.retainedTiles(), stats.retainedHeightPayloadBytes());
        }

        // ⑤ 拓扑质量门 —— 只测不拦
        //
        // 原来是 QualityGate.evaluate(plan, lenient())，它需要每边的曲线来查端点与曲率；
        // 拓扑轮没有曲线，硬套会得到一堆「曲线少于 2 点」的假失败。改为
        // QualityGate.evaluateTopology：保留能判的两项（连通性 + 加权绕行比），
        // 曲线两项显式标 skipped。阈值仍用 lenient()（绕行比 3.0、允许孤立）——
        // 这套阈值来自旧链真机基线，而本规划器走测地距离，分布本来就不同，
        // 直接拿 defaults()（1.5、不许孤立）去拦很可能把整张路网判成不合格。
        QualityGate.Report report = QualityGate.evaluateTopology(
                topology, nodes, QualityGate.Thresholds.lenient());
        if (report.passed()) {
            LOGGER.info("【实际下发路网的拓扑指标】 " + "RoadWeaver network topology: {}", report.describe());
        } else {
            LOGGER.warn("【实际路网低于质量门限，仅报告并仍采用】 " + "RoadWeaver network topology below gate (REPORT-ONLY, still adopted): {}",
                    report.describe());
        }

        // ⑥ 原样下发已评估的森林；此处不再按长度删边。
        // 与 RoadPipeline.run 的第二轮同序（按 from 升序），保证下游拿到的连接顺序不变。
        List<TopologyPlan.Edge> ordered = new ArrayList<>(topology.edges());
        ordered.sort((a, b) -> Integer.compare(a.from(), b.from()));
        List<StructureConnection> out = new ArrayList<>(ordered.size());
        for (TopologyPlan.Edge edge : ordered) {
            if (edge.from() < 0 || edge.from() >= representatives.size()
                    || edge.to() < 0 || edge.to() >= representatives.size()) {
                continue;                                  // 防御：索引错位绝不能铺路
            }
            BlockPos from = representatives.get(edge.from());
            BlockPos to = representatives.get(edge.to());
            out.add(new StructureConnection(from, to, ConnectionStatus.PLANNED));
        }
        checkInterrupted();
        return out;
    }

    static boolean withinMaxEdge(BlockPos from, BlockPos to, int maxEdgeLenBlocks) {
        return maxEdgeLenBlocks <= 0 || from.distSqr(to) <= (long) maxEdgeLenBlocks * maxEdgeLenBlocks;
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** 不清除中断位，调用方才能把终止与正常无候选区分；也不让已取消请求启动新采样。 */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("测地规划已中断，未发布路网");
        }
    }
}
