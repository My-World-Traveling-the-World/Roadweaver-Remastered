/* 文件职责：协调首次世界加载时的道路规划、生成、进度与持久化。 */
package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.pathlogic.structures.EndpointFilterOutcome;
import net.shiroha233.roadweaver.planning.RouteRefinementOps;
import net.shiroha233.roadweaver.planning.ConflictWindowOps;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.GreatWallPrewarmCompat;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionService;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadPlanConsistencyService;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadPathCalculator;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.RoadPositionQuery;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.files.InitialGenerationCheckpointStore;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.structures.precompute.RoadsideStructurePrecomputer;
import net.shiroha233.roadweaver.structures.precompute.RoadsideVillagePrecomputer;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.planning.ConnectionFallbackService;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.structures.placement.SpawnCabinPlacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 初始道路生成管理器：服务器启动时阻塞生成初始规划范围内的道路，并提供进度统计。
 *
 * <h2>整体流水线（权威顺序，2026-11-12 按真机日志逐条核对）</h2>
 *
 * <p>下面每一行的 {@code 括号里}是真机 {@code latest.log} 里能看到的阶段标记，
 * 按<b>首次出现顺序</b>排列 —— 也就是说这就是实际执行顺序，不是设想顺序。
 * 改动任何一段之前先对照这张表，别让阶段顺序漂移。</p>
 *
 * <ol>
 *   <li><b>结构扫描·确定连线</b>（{@code exact structure batch timing} →
 *       {@code CoarseHeightAwarePlanner}/{@code StructureAvoidanceService}）：
 *       枚举候选结构起点，决定哪些结构之间连线。</li>
 *   <li><b>扫描全部结构·建快照</b>（{@code structure snapshot timing} →
 *       {@code StructureAvoidanceService}）：把候选结构解析成占用盒，供后续避让。</li>
 *   <li><b>粗采样</b>（{@code coarse terrain load timing} →
 *       {@code pathfinding.sampling}）：按 A* 步距采地形高度，供搜索用。</li>
 *   <li><b>规划</b>（{@code coarse planning} + {@code async planning timing} →
 *       {@code RoadPlanningService}）：A* 选线 → {@code PathPostProcessor} 把折线
 *       变成站序列 → {@code PathSpanExtractor} 判桥/隧道跨度 → 纵断面
 *       （{@code RoadGradePlanner}）。</li>
 *   <li><b>精细生成</b>（{@code detailed generation timing} → {@code RoadGenerationService}）：
 *       按站铺方块。</li>
 *   <li><b>精细重算</b>（{@code fine regenerate timing}，见
 *       {@code submitAndCollectAtStep(..., localOnly=true)}）：
 *       <b>这一阶段是「逐条路、按细步距重跑」</b>——某条路在粗步距下留下未解决的
 *       冲突窗口，就在 step=2 上重跑<b>那一条</b>，不是「全部生成完之后统一修」。
 *       所以它在日志里与「精细生成」交替出现，而不是排在后面。
 *       <p><b>命名注意</b>：这一阶段历史上叫 {@code local repair}，极易与
 *       {@code RoadPathCalculator} 里真正的「局部修复」——对单个冲突窗口按半径梯子
 *       （{@code structureDetourRadius*}，50/100/…）采样重试——混淆。后者才是兜底，
 *       本阶段是主路径。0.9.092 起日志改名为 {@code fine regenerate}。</p></li>
 *   <li><b>路网合并</b>（{@code merge repair} / {@code merge refine} /
 *       {@code network merge timing}）：桥共享、路口合并、跨路精修。
 *       这一段容易在阅读时被漏掉 —— 它不在生成之内，也不在后处理之内，是独立阶段。</li>
 *   <li><b>后处理</b>（{@code final road processing timing} →
 *       {@code postprocess.RoadSnapService}）：把后生成的路吸附到已有路网。</li>
 *   <li><b>收尾</b>（{@code initial post-processing} / {@code initial finalizing timing}）。</li>
 * </ol>
 *
 * <p><b>两条容易搞错的点</b>：①局部修复在精细生成<b>前后交替</b>（逐路精度重试），
 * 不是生成完再修；②路网合并是生成与后处理之间的<b>独立阶段</b>，不在后处理里。</p>
 */
public final class InitialGenManager {
    private static final int INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS = 4096;
    /** Topology fallback candidates are disabled; failures stay diagnosable. */
    private static final boolean TOPOLOGY_CANDIDATES_ENABLED = false;
    private InitialGenManager() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** Diagnostic heartbeat interval. Route cancellation is intentionally
     * disabled in this diagnostic build. */
    private static final long INITIAL_ROUTE_WATCHDOG_LOG_MS = 5_000L;

    private static volatile boolean active;
    private static volatile boolean roadGenerationWorkersActive;
    private static final AtomicInteger total = new AtomicInteger(0);
    private static final AtomicInteger done = new AtomicInteger(0);
    private static final AtomicInteger generating = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static boolean isActive() { return active; }

    /**
     * 0.8.345：**跨世界复位**（由 SERVER_STOPPING 调用）。
     *
     * <p>背景（审计 + 源码复核 2026-09-11）：{@code active} 此前**只在正常完成路径**被清
     * （{@code blockUntilDone} 的空列表分支与收尾、{@code update} 收尾），
     * 而 11 个停服清理点**无一复位它**。后果是：异常关机 / 强退后残留 {@code active=true}，
     * 下一个世界 {@code begin()} 会在 {@code if (active) return;} 处**静默早退**
     * —— 表现为「新世界道路完全不生成」，且**没有任何日志**。</p>
     *
     * <p>反向风险：{@code blockUntilDone} 在 {@code MinecraftServerMixin.prepareLevels}
     * 的 HEAD 同步调用（即加载界面），其中 {@code planning.join()} 无超时；
     * 若残留状态下真的进入该路径，加载界面可能长时间卡住。</p>
     *
     * <p>本方法同时清计数，避免新世界开局显示上一个世界的残留进度。</p>
     */
    public static void resetForServerStop() {
        active = false;
        roadGenerationWorkersActive = false;
        total.set(0);
        done.set(0);
        generating.set(0);
        failed.set(0);
        InitialGenerationProgressTracker.reset();
    }

    public static boolean isRoadGenerationWorkersActive() { return roadGenerationWorkersActive; }
    public static int getTotal() { return total.get(); }
    public static int getDone() { return done.get(); }
    public static int getGenerating() { return generating.get(); }
    public static int getFailed() { return failed.get(); }
    public static InitialGenerationProgressSnapshot getProgressSnapshot() {
        return InitialGenerationProgressTracker.snapshot(active);
    }

    public static void begin(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        if (active) return;
        if (!shouldRunInitialGeneration(level)) return;

        // 0.8.355：先取断点（必须在 begin() 覆写 checkpoint 之前读），
        // 再决定是「从头开始」还是「从断点续接」。
        java.util.Optional<InitialGenerationCheckpointStore.ResumePoint> resume = resolveResumePoint(level);
        InitialGenerationStage resumeStage = resume.map(point -> parseStage(point.phaseName())).orElse(null);

        active = true;
        InitialGenerationCheckpointStore.begin(level);
        total.set(0);
        done.set(0);
        generating.set(0);
        failed.set(0);
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.updateConnections(0, 0, 0, 0);
        ThreadPoolManager.prepareInitialGeneration();

        // One line that says which switches this session actually runs with. Every "why did it do
        // that" question in a log starts here: the same code produces visibly different roads
        // depending on these values, and a report without them cannot be compared with another run.
        try {
            ModConfig config = ConfigService.get();
            var appearance = config.roadAppearance();
            LOGGER.info("RoadWeaver config appearance: width={} clearHeight={} tunnelClearHeight={} "
                            + "tunnelDrySeal={} culvert={} culvertMaxStations={} culvertMaxWaterDepth={} "
                            + "slopeByArcLength={} slopeLimitEnabled={} maxSlopeStepPer2={} bridge={} "
                            + "planningAlgorithm={}",
                    appearance.roadWidth(), appearance.roadClearHeight(), appearance.tunnelClearHeight(),
                    appearance.tunnelDrySeal(), appearance.culvertEnabled(), appearance.culvertMaxStations(),
                    appearance.culvertMaxWaterDepth(), appearance.slopeByArcLength(),
                    appearance.slopeLimitEnabled(), appearance.maxSlopeStepPerTwoSegments(),
                    config.bridgeEnabled(), config.planning().planningAlgorithm());
        } catch (Throwable couldNotRead) {
            LOGGER.warn("RoadWeaver config appearance could not be read for the startup line", couldNotRead);
        }

        if (resumeStage != null) {
            InitialGenerationCheckpointStore.ResumePoint point = resume.orElseThrow();
            LOGGER.info("RoadWeaver initial generation RESUMING after an unclean shutdown: "
                            + "phase={} done={}/{} atLastSave. "
                            + "Persisted work (structure scan tiles, coarse terrain tiles, completed connections) "
                            + "is reused; the remaining connections continue from where the previous session stopped.",
                    resumeStage.name(), point.done(), point.total());
        } else if (resume.isPresent()) {
            LOGGER.info("RoadWeaver initial generation starting from the beginning: "
                    + "checkpoint has no usable phase (legacy schema or malformed file). "
                    + "Persisted caches are still reused where their keys match.");
        }
        // 崩溃残留的 GENERATING 必须降级，否则 filterPlanned() 永远看不到它们
        // （statusPriority: GENERATING=3 > PLANNED=2，既不做也不重置）。
        int downgraded = downgradeStaleGenerating(WorldDataProvider.getInstance(), level);
        if (downgraded > 0) {
            LOGGER.info("RoadWeaver initial resume: {} connection(s) left in GENERATING by the previous "
                    + "session were reset to PLANNED and will be regenerated.", downgraded);
        }

        try {
            net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingStats.reset();
            net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats.reset();
            DetailedRoadGenerationStats.reset();

            ModConfig cfg = ConfigService.get();
            if (cfg.roadAppearance().spawnCabinEnabled()) {
                SpawnCabinPlacer.ensurePlaced(level);
            }
            // Remove stale unknown placeholder endpoints before the first
            // topology pass. Known discovered structures remain intact; the
            // shared spawn is the only unknown endpoint we intentionally keep.
            StructureFileStorage.pruneUnknownNonSpawnEndpoints(level);

            var planning = RoadPlanningService.initialPlanAsync(level);
            var server = level.getServer();
            if (server == null) {
                throw new IllegalStateException("Server stopped during initial road planning");
            }
            // This is the deliberate world-entry gate. managedBlock keeps the
            // server executor pumping while the client remains on the loading
            // screen; runtime and dynamic planning never use this wait.
            if (server.isSameThread()) server.managedBlock(planning::isDone);
            planning.join();

            WorldDataProvider provider = WorldDataProvider.getInstance();
            List<StructureConnection> conns = provider.getStructureConnections(level);
            total.set(conns == null ? 0 : conns.size());
            // Deliberately NOT publishing the ORIGINAL route phase here: this
            // total is the raw topology connection count, while the actual
            // road task list is only known after filterVillageAndSpawn-
            // Connections + filterPlanned in blockUntilDone. Publishing both
            // made the HUD show "candidates 16" for a 9-road task list
            // (beginRoutePhase keeps the max on re-entry). begin() and
            // blockUntilDone() run back-to-back on the same thread, so there
            // is no loading-screen window that needs a placeholder total.
            InitialGenerationProgressTracker.updateConnections(
                    total.get(), generating.get(), done.get(), failed.get());
            update(level);
        } catch (Throwable failure) {
            abort("begin", failure);
        }
    }

    public static boolean shouldRunInitialGeneration(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return false;
        if (InitialGenerationCheckpointStore.hasCompleted(level)) return false;
        if (InitialGenerationCheckpointStore.isActive(level)) return true;
        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> conns = provider.getStructureConnections(level);
        if (conns != null && !conns.isEmpty()) return false;
        return !RoadShardStorage.hasAnyRoad(level);
    }

    /**
     * 0.8.355：解析崩溃断点。**只在上一个进程异常退出后才有意义** ——
     * 本进程正在跑时（{@link #active}）走的是正常流程，不能把内存里的进度
     * 误当断点，否则同一次会话内重入会错误跳阶段。
     *
     * @return 可用的断点；无断点 / 旧 schema / 本进程在跑时返回 {@code Optional.empty()}
     */
    static java.util.Optional<InitialGenerationCheckpointStore.ResumePoint> resolveResumePoint(ServerLevel level) {
        if (active) return java.util.Optional.empty();
        java.util.Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.readResumePoint(level);
        if (point.isEmpty()) return java.util.Optional.empty();
        // 只有 state=ACTIVE（= 上轮没跑完）才续接；COMPLETED 由 shouldRun 拦掉。
        if (!InitialGenerationCheckpointStore.isActive(level)) return java.util.Optional.empty();
        return point.filter(InitialGenerationCheckpointStore.ResumePoint::hasPhase);
    }

    /**
     * 把断点里的阶段名解析成枚举。无法识别时返回 {@code null}（调用方按从头开始处理）。
     */
    private static InitialGenerationStage parseStage(String phaseName) {
        if (phaseName == null || phaseName.isBlank()) return null;
        try {
            return InitialGenerationStage.valueOf(phaseName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOGGER.warn("RoadWeaver checkpoint contains unknown phase '{}'; restarting initial generation from the beginning",
                    phaseName);
            return null;
        }
    }

    /**
     * 0.8.355：把崩溃瞬间残留的 {@code GENERATING} 连接降级为 {@code PLANNED}。
     *
     * <p>为什么必须做：{@code filterPlanned()} 只挑 {@code PLANNED}，而
     * {@code ConnectionTopologyReconciler.statusPriority} 给 {@code GENERATING}=3（高于
     * PLANNED=2），所以残留的 GENERATING **既不会被生成、也不会被重置**，会永久卡住。</p>
     *
     * @return 被降级的连接数
     */
    private static int downgradeStaleGenerating(WorldDataProvider provider, ServerLevel level) {
        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null || current.isEmpty()) return 0;
        List<StructureConnection> updated = new ArrayList<>(current);
        List<StructureConnection> downgraded = new ArrayList<>();
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection connection = updated.get(i);
            if (connection == null || connection.status() != ConnectionStatus.GENERATING) continue;
            StructureConnection planned = new StructureConnection(
                    connection.from(), connection.to(), ConnectionStatus.PLANNED);
            updated.set(i, planned);
            downgraded.add(planned);
        }
        if (downgraded.isEmpty()) return 0;
        provider.setStructureConnections(level, updated);
        for (StructureConnection connection : downgraded) {
            LOGGER.info("RoadWeaver initial resume: downgraded stale GENERATING connection to PLANNED {} -> {}",
                    connection.from(), connection.to());
        }
        return downgraded.size();
    }

    /**
     * 0.8.355：把当前阶段与进度写进 checkpoint。每条连接完成/失败后调用，
     * 使崩溃最多只丢失「最后一条连接」的进度。
     */
    private static void recordCheckpoint(ServerLevel level, InitialGenerationStage stage, int done, int total) {
        if (level == null || stage == null) return;
        InitialGenerationCheckpointStore.recordProgress(level, stage.name(), done, total);
    }

    /** 统计某状态的连接数，仅用于日志可读性。 */
    private static int countByStatus(List<StructureConnection> connections, ConnectionStatus status) {
        if (connections == null || status == null) return 0;
        int count = 0;
        for (StructureConnection connection : connections) {
            if (connection != null && connection.status() == status) count++;
        }
        return count;
    }

    /** 路线的站点数（{@code targetY} 是每条路的施工契约，长度即站数），仅用于日志。 */
    private static int roadStationCount(RoadData road) {
        if (road == null || road.targetY() == null) return 0;
        return road.targetY().size();
    }

    /**
     * 0.8.355：把「哪些阶段的成果直接从磁盘复用了」明确写进日志。
     *
     * <p>背景：这条路原本只打 `memoryHits/diskHits` 的聚合数字，玩家看到
     * `diskHits=0` 会误以为「全部重算」。实际上结构扫描（{@code scanTiles}）、
     * 粗地形瓦片（{@code terrain/}）、已完成连接（{@code state.json}）都是跨会话复用的，
     * 需要在进入各阶段之前把它讲清楚。</p>
     */
    private static void logPersistedReuse(ServerLevel level, int plannedRoutes) {
        try {
            int completed = 0;
            int protectedGap = 0;
            int failed = 0;
            int filtered = 0;
            int pending = 0;
            List<StructureConnection> all = WorldDataProvider.getInstance().getStructureConnections(level);
            if (all != null) {
                for (StructureConnection connection : all) {
                    if (connection == null || connection.status() == null) continue;
                    switch (connection.status()) {
                        case COMPLETED -> completed++;
                        case PROTECTED_GAP -> protectedGap++;
                        case FAILED -> failed++;
                        case FILTERED -> filtered++;
                        case PLANNED, GENERATING -> pending++;
                    }
                }
            }
            LOGGER.info("RoadWeaver initial persisted-state reuse: connections total={} "
                            + "(completed={} protectedGap={} failed={} filtered={} stillPending={}). "
                            + "These are loaded from state.json and are NOT regenerated.",
                    completed + protectedGap + failed + filtered + pending,
                    completed, protectedGap, failed, filtered, pending);
            LOGGER.info("RoadWeaver initial work to do: {} route(s) need planning/generation this session "
                            + "(structure-scan tiles and coarse terrain tiles already on disk will be reused "
                            + "automatically; watch 'memoryHits='/'diskHits=' in the coarse terrain load timing line).",
                    plannedRoutes);
        } catch (Throwable ignored) {
            // 诊断日志失败绝不能影响生成流程。
        }
    }

    /**
     * 阻塞到初始生成结束。
     *
     * <p><b>本方法是 {@code roadweaver-api} 的生命周期回调触发点</b>，替代原先
     * streams-reflowing 往这里打的 {@code @Pseudo @Mixin}：</p>
     * <pre>
     * streams  RoadWeaverInitialGenerationStreamsBoostMixin
     *   &#64;Inject(method = "blockUntilDone", at = &#64;At("HEAD"))    → 进入采样加强档
     *   &#64;Inject(method = "blockUntilDone", at = &#64;At("RETURN"))  → 退出采样加强档
     * </pre>
     *
     * <p>行为等价性：原 mixin 的 HEAD + RETURN 与这里的 try/finally 一一对应 ——
     * 无论 body 从哪个 {@code return} 退出，{@code onInitialGenerationEnd} 都恰好触发一次。</p>
     *
     * <p>body 原样保留在 {@link #blockUntilDoneInternal} 里（609 行，不做重缩进）。</p>
     */
    public static void blockUntilDone(ServerLevel level) {
        net.shiroha233.roadweaver.apiimpl.ApiBackendImpl.fireInitialGenerationStart();
        long startedAt = System.nanoTime();
        try {
            blockUntilDoneInternal(level);
        } finally {
            net.shiroha233.roadweaver.apiimpl.ApiBackendImpl.fireInitialGenerationEnd(
                    total.get(), done.get(), failed.get(), elapsedMs(startedAt));
        }
    }

    private static void blockUntilDoneInternal(ServerLevel level) {
        if (!active || level == null || !Level.OVERWORLD.equals(level.dimension())) return;
        try {
            WorldDataProvider provider = WorldDataProvider.getInstance();

            List<StructureConnection> list = provider.getStructureConnections(level);
            if (list == null || list.isEmpty()) {
                active = false;
                InitialGenerationProgressTracker.finish();
                return;
            }
            List<StructureConnection> roadTasks = filterPlanned(list);
            // B 阶段：规划快照已就绪（planRectAsync 已为 incoming 连接准备），在规划波次
            // 开始之前过滤"村庄 V ↔ V 结构盒内的未知端点"连接。被过滤的连接不进规划、
            // 不进施工、不进局部修复；安全阀触发时整体放弃并只打告警。
            EndpointFilterOutcome endpointFilter =
                    StructureAvoidanceService.filterUnknownEndpointInsideVillage(level, roadTasks);
            if (endpointFilter.filteredCount() > 0) {
                roadTasks = endpointFilter.kept();
                markFilteredConnections(provider, level, endpointFilter.filtered());
            }
            final int filteredRouteCount = endpointFilter.filteredCount();
            if (filteredRouteCount > 0) {
                LOGGER.info("RoadWeaver initial route filter summary: filtered={} kept={}",
                        filteredRouteCount, roadTasks.size());
            }
            total.set(roadTasks.size());
            // 0.8.355：把「哪些阶段靠持久化数据跳过了」明确打出来。玩家跑 1024 区块时
            // 单是粗采样就 40 分钟、河流预热 30 分钟，必须能一眼确认没有重算。
            logPersistedReuse(level, roadTasks.size());

            InitialGenerationProgressTracker.enterStage(InitialGenerationStage.ROAD_GENERATION, "generating_roads");
            InitialGenerationProgressTracker.updateRetryStates(0, 0);
            InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
            // 0.8.355：进入道路生成阶段即落一次断点，重启后能定位到该阶段。
            recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION, 0, roadTasks.size());
            LOGGER.info("RoadWeaver initial road generation phase starting: plannedRoutes={} (alreadyCompleted={} "
                            + "failed={} filtered={} protectedGap={}) - a checkpoint is written after every route "
                            + "so an unclean shutdown can resume instead of restarting.",
                    roadTasks.size(), countByStatus(list, ConnectionStatus.COMPLETED),
                    countByStatus(list, ConnectionStatus.FAILED), countByStatus(list, ConnectionStatus.FILTERED),
                    countByStatus(list, ConnectionStatus.PROTECTED_GAP));

            Set<Long> initialConstructionChunks = Set.of();
            if (!roadTasks.isEmpty()) {
                // Publish the original route phase before any startup-wide
                // river/height prewarm.  Otherwise the HUD shows the aggregate
                // route total while all three phase rows are still zero.
                InitialGenerationProgressTracker.beginRoutePhase(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL, roadTasks.size(),
                        "waiting_for_initial_routes");
                // 河流预热 -> 长城预热 -> 道路预热. The order is the whole point, and the wall has to
                // come before the road's own prewarm, not between that prewarm and the generation: with
                // the gate after prewarmInitialRiverPlates the road had already read the world and
                // started loading structure starts (6331 of them in a measured session) while the wall
                // was still sampling, so the two fought for the same cores and the wall's own stages
                // crawled. Nothing of the road's runs until the wall publishes.
                awaitGreatWallPrewarm(level);
                List<BlockPos> prewarmedRoutePositions = prewarmInitialRiverPlates(level, roadTasks);
        // 河流预热完成 —— 替代 great-wall 往 prewarmInitialRiverPlates 上打的
        // @Pseudo @Mixin(require = 1)（那条 require=1 意味着本方法一改名，长城就类加载崩溃）。
        net.shiroha233.roadweaver.apiimpl.ApiBackendImpl.fireRiversPrewarmed();
                DetailedRoadGenerationStats.reset();
                long detailedGenerationStartedAt = System.nanoTime();
                InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL, "generating_original_routes");

                Map<PlannedPathKey, GenResult> resolved = new HashMap<>();
                // A protected-gap route is a valid last resort, but it must
                // still get one chance to use a replacement structure first.
                Map<PlannedPathKey, GenResult> protectedGapFallbacks = new HashMap<>();
                List<RoadData> generatedRoads = new ArrayList<>();
                List<StructureConnection> remaining = new ArrayList<>(roadTasks);
                int configuredStep = ConfigService.get().pathfindingCost().effectiveAStarStep();
                int[] startupBounds = ConflictWindowOps.initialSamplingBounds(level, roadTasks);
                List<Integer> tiers = RoutePrecisionRetryPolicy.initialBatchSteps(configuredStep);
                int candidateCompleted = 0;
                int candidateRetained = 0;

                for (int tierIndex = 0; tierIndex < tiers.size() && !remaining.isEmpty(); tierIndex++) {
                    int step = tiers.get(tierIndex);
                    // A route that survived the first attempt is now in the
                    // local precision-retry queue. Publish this before the
                    // workers start so the HUD does not look idle between
                    // retry waves.
                    // initialBatchSteps() always yields exactly one tier, so
                    // the old tierIndex > 0 local-repair branch here was
                    // unreachable and has been removed; local repair runs as
                    // its own explicit wave below.
                    InitialGenerationProgressTracker.updateRetryStates(
                            tierIndex == 0 ? 0 : remaining.size(), 0);
                    if (tierIndex == 0) {
                        InitialGenerationProgressTracker.beginRoutePhase(
                                InitialGenerationProgressTracker.RoutePhase.ORIGINAL, remaining.size(),
                                "sampling");
                    }
                    // Batch barrier: finish terrain sampling/path planning for
                    // every route in this precision wave before any road
                    // generation worker starts. Without this barrier each
                    // route enters Road.step while still loading its own
                    // coarse tiles, causing shared sampler contention and
                    // silent waits at progress=0.
                    if (tierIndex == 0) {
                        await(level, RouteRefinementOps.refineInitialBatchAsync(
                                level, startupBounds, remaining, step));
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL,
                        "planning");
                    }
                    // Pre-plan every unknown endpoint's agreed height from all
                    // incoming routes before any road is generated, so roads
                    // into the same unknown node share one height.
                    if (tierIndex == 0) {
                        RoadPlanningService.preplanUnknownEndpointHeights(level, remaining);
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            tierIndex == 0
                                    ? InitialGenerationProgressTracker.RoutePhase.ORIGINAL
                                    : InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            "generating");
                    InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                    BatchGenerationResult batch = submitAndCollectAtStep(level, remaining, step, tierIndex > 0,
                            tierIndex == 0 ? prewarmedRoutePositions : null);
                    generatedRoads.addAll(batch.roads());
                    List<StructureConnection> next = new ArrayList<>();
                    // 0.8.355：逐连接细粒度日志 + 断点。玩家跑 1024 区块时道路阶段以小时计，
                    // 需要能一眼看出「现在做到第几条、每条成没成、失败原因是什么」，
                    // 并且每条处理完立刻落盘，崩溃最多丢最后一条。
                    int processedInRound = 0;
                    for (StructureConnection task : remaining) {
                        GenResult result = batch.results().get(PlannedPathKey.of(task));
                        processedInRound++;
                        String outcome;
                        if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                && result.road() != null) {
                            boolean endpointOnlyGap = hasOnlyEndpointProtectedGaps(
                                    level, task, result.road());
                            if (!result.road().hasProtectedGap() || endpointOnlyGap) {
                                outcome = "COMPLETED" + (endpointOnlyGap ? "(endpoint-only gap)" : "");
                            } else {
                                outcome = "COMPLETED(protected gap, handed to local repair)";
                            }
                            if (result.road().hasProtectedGap() && !endpointOnlyGap) {
                                if (tierIndex == 0) {
                                    if (!ConfigService.get().planning().localRepairOnlyOnUnavoidable()) {
                                        // Config switch: keep the protected-gap
                                        // route as-is right after the initial
                                        // batch and skip the local-repair wave
                                        // entirely (wall-clock saving on
                                        // gap-heavy batches). Reverting the
                                        // config restores the exact prior
                                        // local-repair handoff.
                                        resolved.put(result.originalKey(), result);
                                        InitialGenerationProgressTracker.routePhaseRetained();
                                        LOGGER.info("RoadWeaver retained protected-gap route without "
                                                        + "local repair (localRepairOnlyOnUnavoidable=false) "
                                                        + "connection={}->{}",
                                                task.from(), task.to());
                                    } else {
                                        int gapRuns = protectedGapRuns(result.road());
                                        if (gapRuns < MIN_LOCAL_REPAIR_GAP_RUNS) {
                                            // 只有 1 个断口的短缺口不值得再跑一次 step=2 局部修复
                                            // （真机数据：这种路修完断口反而从 1 变 4/7）。
                                            resolved.put(result.originalKey(), result);
                                            InitialGenerationProgressTracker.routePhaseRetained();
                                            LOGGER.info("RoadWeaver kept small protected-gap route without local repair "
                                                            + "gapRuns={} connection={}->{}",
                                                    gapRuns, task.from(), task.to());
                                        } else {
                                            RoadPlanningService.rememberProtectedGapHints(task, result.road());
                                            RoadPlanningService.rebindGeneratedRouteForRetry(level, task, result.road(),
                                                    RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep).get(0));
                                            protectedGapFallbacks.put(result.originalKey(), result);
                                            removeGeneratedRoad(generatedRoads, result.road());
                                            next.add(task);
                                            outcome = "PROTECTED_GAP(h" + gapRuns + " runs -> fine regenerate)";
                                            InitialGenerationProgressTracker.routePhaseRetained();
                                            LOGGER.info("RoadWeaver initial protected-gap route handed to fine regenerate connection={}->{}",
                                                    task.from(), task.to());
                                        }
                                    }
                                } else {
                                    resolved.put(result.originalKey(), result);
                                    outcome = "PROTECTED_GAP(retained after local repair)";
                                    LOGGER.info("RoadWeaver local repair retained usable protected-gap route connection={}->{}",
                                            task.from(), task.to());
                                }
                            } else {
                                if (endpointOnlyGap) {
                                    LOGGER.info("RoadWeaver retained endpoint-only protected gap without topology replacement connection={}->{}",
                                            task.from(), task.to());
                                }
                                resolved.put(result.originalKey(), result);
                            }
                        } else {
                            next.add(task);
                            outcome = result == null ? "PENDING(no result)" : "PENDING(retry)";
                        }
                        // 每条连接一行：序号 / 端点 / 结果 / 累计进度。
                        LOGGER.info("RoadWeaver initial connection [{}/{}] step={} {} -> {} : {} (totalDone={} pending={})",
                                processedInRound, remaining.size(), step,
                                task.from(), task.to(), outcome,
                                resolved.size(), next.size());
                        // 每条都更新断点：崩溃最多丢“最后一条”。
                        recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION,
                                resolved.size(), roadTasks.size());
                    }
                    remaining = next;
                    done.set(resolved.size());
                    // Remaining routes are pending retries, not terminal failures.
                    failed.set(0);
                    InitialGenerationProgressTracker.updateRetryStates(
                            tierIndex + 1 < tiers.size() ? remaining.size() : 0, 0);
                    InitialGenerationProgressTracker.updateConnections(
                            roadTasks.size(), 0, done.get(), 0);
                    LOGGER.info("RoadWeaver initial generation round: step={} requested={} completed={} remaining={}",
                            step, batch.results().size(), resolved.size(), remaining.size());
                }

                InitialGenerationProgressTracker.settleRoutePhase(
                        InitialGenerationProgressTracker.RoutePhase.ORIGINAL,
                        resolved.size(), 0, 0, remaining.size(),
                        remaining.isEmpty() ? "finished" : "handed_to_local");
                int waveClean = 0;
                int waveProtectedGap = 0;
                for (GenResult waveResult : resolved.values()) {
                    if (waveResult.road() == null) continue;
                    if (waveResult.road().hasProtectedGap()) waveProtectedGap++;
                    else waveClean++;
                }
                LOGGER.info("RoadWeaver initial route wave summary: step={} requested={} resolved={} clean={} "
                                + "protectedGap={} handedToLocalRepair={} filtered={} wallMs={}",
                        configuredStep, roadTasks.size(), resolved.size(), waveClean, waveProtectedGap,
                        remaining.size(), filteredRouteCount, elapsedMs(detailedGenerationStartedAt));

                // The first batch is now complete.  Unresolved routes get one
                // explicit local repair wave before any topology candidate is
                // considered.  This phase is deliberately separate from the
                // full-route precision tiers so its HUD counters describe the
                // actual failed-route repair work.
                if (!remaining.isEmpty() && tiers.size() == 1) {
                    int localInput = remaining.size();
                    int localResolvedBefore = resolved.size();
                    int[] localRetained = {0};
                    int[] localCompleted = {0};
                    Set<PlannedPathKey> buildingConflictRoutes = new HashSet<>();
                    // The rebound path cache entry lives under the local-repair
                    // step, so the conflict probe must query the same step or
                    // the fingerprint check silently misses it.
                    int localProbeStep =
                            RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep).get(0);
                    for (StructureConnection task : remaining) {
                        PlannedPathKey key = PlannedPathKey.of(task);
                        if (protectedGapFallbacks.containsKey(key)
                                || RoadPlanningService.hasBuildingConflict(level, task, localProbeStep)) {
                            buildingConflictRoutes.add(key);
                        }
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            InitialGenerationProgressTracker.RoutePhase.ORIGINAL, "finished");
                    InitialGenerationProgressTracker.beginRoutePhase(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, remaining.size(),
                            "sampling");
                    InitialGenerationProgressTracker.updateRetryStates(remaining.size(), 0);
                    long localRepairWaveStartedAt = System.nanoTime();
                    for (int step : RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep)) {
                        InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                        // 0.8.318：把局部修复波次拆成"splice 阶段"与"整条重生成阶段"两段计时。
                        // 真机该波次 wallMs=224621，但 24 次 splice 本身只花 14s、统一预取 21s，
                        // 剩余约 190s 都在按 step=2 重生成被交接的路。纯观测。
                        long splicePhaseStartedAt = System.nanoTime();
                        await(level, RouteRefinementOps.refineInitialRoutesAsync(
                                level, remaining, step, 100));
                        long splicePhaseMs = elapsedMs(splicePhaseStartedAt);
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                                InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, "planning");
                        InitialGenerationProgressTracker.enterStage(
                                InitialGenerationStage.ROAD_GENERATION,
                                "local_repair_step_" + step);
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                                InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, "generating");
                        long regenerateStartedAt = System.nanoTime();
                        // 0.8.325：局部 splice 没产出 rebound 路径的路，重生成必然立刻失败
                        // （旧代码会照排一次任务再在 worker 里拒收）。这里提交前就剔掉，
                        // 并把"剔了几条 / 实际重生成几条"写进拆分日志，省掉纯白跑的调度。
                        List<StructureConnection> regenerable = new ArrayList<>(remaining.size());
                        int skippedNoRebound = 0;
                        for (StructureConnection task : remaining) {
                            if (RoadGenerationService.hasLocalRepairReboundPath(level, task, step)) {
                                regenerable.add(task);
                            } else {
                                skippedNoRebound++;
                                LOGGER.info("RoadWeaver local repair regenerate skipped: no rebound path "
                                                + "step={} connection={}->{}", step, task.from(), task.to());
                            }
                        }
                        BatchGenerationResult batch = regenerable.isEmpty()
                                ? new BatchGenerationResult(Map.of(), List.of())
                                : submitAndCollectAtStep(level, regenerable, step, true);
                        long regenerateMs = elapsedMs(regenerateStartedAt);
                        LOGGER.info("RoadWeaver local repair step split: step={} routes={} regenerated={} "
                                        + "skippedNoRebound={} spliceMs={} regenerateMs={}",
                                step, remaining.size(), regenerable.size(), skippedNoRebound,
                                splicePhaseMs, regenerateMs);
                        generatedRoads.addAll(batch.roads());
                        List<StructureConnection> next = new ArrayList<>();
                        int localProcessed = 0;
                        for (StructureConnection task : remaining) {
                            PlannedPathKey taskKey = PlannedPathKey.of(task);
                            GenResult result = batch.results().get(PlannedPathKey.of(task));
                            localProcessed++;
                            String localOutcome;
                            if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                localOutcome = result.road().hasProtectedGap()
                                        ? "COMPLETED(protected gap retained)" : "COMPLETED";
                            } else {
                                localOutcome = "PENDING(after local repair)";
                            }
                            if (result != null && result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                if (result.road().hasProtectedGap()) {
                                    // Building conflicts never enter topology
                                    // candidates. A usable locally repaired
                                    // route with a protected gap is terminal —
                                    // but only when it is not worse than the
                                    // first-wave route we still hold: the
                                    // finer step can find a shorter corridor
                                    // that crosses more buildings (real-machine
                                    // data: 1 run -> 4 and 1 run -> 7 runs).
                                    GenResult fallback = protectedGapFallbacks.get(taskKey);
                                    GenResult chosen = result;
                                    if (fallback != null && fallback.road() != null
                                            && leavesFewerGaps(fallback.road(), result.road())) {
                                        chosen = fallback;
                                        removeGeneratedRoad(generatedRoads, result.road());
                                        generatedRoads.add(fallback.road());
                                        LOGGER.info("RoadWeaver kept first-wave gap route over local repair result "
                                                        + "firstRuns={} firstBlocks={} repairedRuns={} repairedBlocks={} connection={}->{}",
                                                protectedGapRuns(fallback.road()),
                                                Math.round(protectedGapBlocks(fallback.road())),
                                                protectedGapRuns(result.road()),
                                                Math.round(protectedGapBlocks(result.road())),
                                                task.from(), task.to());
                                    }
                                    resolved.put(taskKey, chosen);
                                    InitialGenerationProgressTracker.routePhaseRetained();
                                    localRetained[0]++;
                                    LOGGER.info("RoadWeaver local building repair succeeded with retained gap; candidate handoff skipped connection={}->{}",
                                            task.from(), task.to());
                                } else {
                                    resolved.put(result.originalKey(), result);
                                    localCompleted[0]++;
                                }
                            } else {
                                GenResult protectedFallback = protectedGapFallbacks.get(taskKey);
                                if (protectedFallback != null && protectedFallback.road() != null) {
                                    // The building detour failed. Keep the
                                    // original route and its explicit gap;
                                    // never try a different topology candidate.
                                    generatedRoads.add(protectedFallback.road());
                                    resolved.put(taskKey, protectedFallback);
                                    InitialGenerationProgressTracker.routePhaseRetained();
                                    localRetained[0]++;
                                    LOGGER.warn("RoadWeaver building local repair failed; retained protected gap and skipped candidates connection={}->{} reason=see_previous_local_repair_diagnostics",
                                            task.from(), task.to());
                                } else if (!buildingConflictRoutes.contains(taskKey)) {
                                    next.add(task);
                                } else {
                                    LOGGER.error("RoadWeaver building local repair failed without retainable route; candidates skipped connection={}->{}",
                                            task.from(), task.to());
                                    resolved.put(taskKey, new GenResult(taskKey, task,
                                            RoadGenerationService.InitialTaskResult.FAILED, null));
                                }
                            }
                            // 0.8.355：精细重算波次同样逐条打日志 + 落断点。
                            LOGGER.info("RoadWeaver fine regenerate connection [{}/{}] step={} {} -> {} : {} "
                                            + "(totalDone={} remaining={})",
                                    localProcessed, remaining.size(), step,
                                    task.from(), task.to(), localOutcome,
                                    resolved.size(), next.size());
                            recordCheckpoint(level, InitialGenerationStage.ROAD_GENERATION,
                                    resolved.size(), roadTasks.size());
                        }
                        remaining = next;
                    }
                    InitialGenerationProgressTracker.setRoutePhaseOperation(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            "finished");
                    done.set(resolved.size());
                    failed.set(remaining.size());
                    InitialGenerationProgressTracker.updateRetryStates(0, 0);
                    InitialGenerationProgressTracker.updateConnections(
                            roadTasks.size(), 0, done.get(), 0);
                    LOGGER.info("RoadWeaver local repair wave finished: completed={} remaining={} wallMs={}",
                            resolved.size(), remaining.size(),
                            (System.nanoTime() - localRepairWaveStartedAt) / 1_000_000L);
                    InitialGenerationProgressTracker.settleRoutePhase(
                            InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR,
                            localCompleted[0],
                            remaining.size(), localRetained[0], 0,
                            "finished");
                }

                List<StructureConnection> supplementalConnections = new ArrayList<>();
                if (TOPOLOGY_CANDIDATES_ENABLED) {
                // Topology candidates are tried in bounded waves. A wave shares
                // one terrain batch, while each original edge is removed as soon
                // as one candidate succeeds.
                Map<PlannedPathKey, List<StructureConnection>> candidateLists = new HashMap<>();
                int candidateInput = remaining.size();
                InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, "finished");
                InitialGenerationProgressTracker.beginRoutePhase(
                        InitialGenerationProgressTracker.RoutePhase.CANDIDATE, remaining.size(), "planning");
                for (StructureConnection original : remaining) {
                    candidateLists.put(PlannedPathKey.of(original),
                            ConnectionFallbackService.candidates(level, original));
                }
                InitialGenerationProgressTracker.updateRetryStates(0, remaining.size());
                for (int candidateIndex = 0; candidateIndex < ConnectionFallbackService.MAX_CANDIDATES
                        && !remaining.isEmpty(); candidateIndex++) {
                    Map<PlannedPathKey, StructureConnection> candidateOwners = new HashMap<>();
                    Set<Long> candidateEndpointOwners = new HashSet<>();
                    for (StructureConnection original : remaining) {
                        List<StructureConnection> candidates = candidateLists.get(PlannedPathKey.of(original));
                        if (candidates != null && candidateIndex < candidates.size()) {
                            StructureConnection candidate = candidates.get(candidateIndex);
                            PlannedPathKey candidateKey = PlannedPathKey.of(candidate);
                            long fromKey = PlanningUtils.pos2dKey(candidate.from());
                            long toKey = PlanningUtils.pos2dKey(candidate.to());
                            // Reserve both endpoints for this wave. This is
                            // stronger than edge de-duplication: two distinct
                            // fallback edges must not race on one structure.
                            if (candidateEndpointOwners.contains(fromKey)
                                    || candidateEndpointOwners.contains(toKey)) {
                                LOGGER.warn("RoadWeaver skipped conflicting fallback candidate original={}->{} candidate={}->{} reason=endpoint_reserved",
                                        original.from(), original.to(), candidate.from(), candidate.to());
                                continue;
                            }
                            if (candidateOwners.putIfAbsent(candidateKey, original) == null) {
                                candidateEndpointOwners.add(fromKey);
                                candidateEndpointOwners.add(toKey);
                            }
                        }
                    }
                    if (candidateOwners.isEmpty()) break;
                    List<StructureConnection> candidateRemaining = new ArrayList<>(candidateOwners.keySet().size());
                    for (StructureConnection original : remaining) {
                        List<StructureConnection> candidates = candidateLists.get(PlannedPathKey.of(original));
                        if (candidates == null || candidateIndex >= candidates.size()) continue;
                        StructureConnection candidate = candidates.get(candidateIndex);
                        if (candidateOwners.get(PlannedPathKey.of(candidate)) == original) {
                            candidateRemaining.add(candidate);
                        }
                    }
                    List<StructureConnection> candidateFailed = candidateRemaining;
                    for (int step : RoutePrecisionRetryPolicy.candidateBatchSteps(configuredStep)) {
                        if (candidateFailed.isEmpty()) break;
                        InitialGenerationProgressTracker.beginRoutePhase(
                                InitialGenerationProgressTracker.RoutePhase.CANDIDATE, candidateFailed.size(),
                                "sampling");
                        InitialGenerationProgressTracker.updateRetryStates(0, remaining.size());
                        InitialGenerationProgressTracker.enterStage(
                                InitialGenerationStage.ROAD_GENERATION,
                                "candidate_refinement_step_" + step);
                        InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                        await(level, RouteRefinementOps.refineInitialRoutesAsync(
                                level, candidateFailed, step, 100));
                        InitialGenerationProgressTracker.setRoutePhaseOperation(
                                InitialGenerationProgressTracker.RoutePhase.CANDIDATE, "generating");
                        BatchGenerationResult batch = submitAndCollectAtStep(level, candidateFailed, step);
                        List<StructureConnection> nextCandidates = new ArrayList<>();
                        for (StructureConnection candidate : candidateFailed) {
                            GenResult raw = batch.results().get(PlannedPathKey.of(candidate));
                            StructureConnection original = candidateOwners.get(PlannedPathKey.of(candidate));
                            if (original == null) continue;
                            if (raw != null && raw.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && raw.road() != null) {
                                candidateCompleted++;
                                RoadData candidateRoad = raw.road();
                                RoadData originalRoad = protectedGapFallbacks.get(PlannedPathKey.of(original)) == null
                                        ? null
                                        : protectedGapFallbacks.get(PlannedPathKey.of(original)).road();
                                boolean connected = candidateConnectsToExistingNetwork(candidateRoad, generatedRoads);
                                boolean spawnConnected = originalRoad == null
                                        || SpawnConnectivityValidator.replacementRemainsConnected(
                                        level, candidateRoad, originalRoad, generatedRoads);
                                boolean originalSpawnConnected = originalRoad != null
                                        && SpawnConnectivityValidator.routeRemainsConnected(
                                        level, originalRoad, generatedRoads);
                                if (originalRoad != null) {
                                    boolean sameDirection = SpawnConnectivityValidator.sameOverallDirection(
                                            originalRoad, candidateRoad);
                                    if (!sameDirection) {
                                        // Different-direction branches are a
                                        // valid fork, so keep both routes.
                                        removeGeneratedRoad(generatedRoads, originalRoad);
                                        generatedRoads.add(originalRoad);
                                        generatedRoads.add(candidateRoad);
                                        GenResult retainedOriginal = new GenResult(
                                                PlannedPathKey.of(original), original,
                                                RoadGenerationService.InitialTaskResult.COMPLETED, originalRoad);
                                        resolved.put(retainedOriginal.originalKey(), retainedOriginal);
                                        addSupplementalConnection(supplementalConnections, candidate);
                                        LOGGER.info("RoadWeaver retained different-direction original and candidate original={}->{} candidate={}->{} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                                original.from(), original.to(), candidate.from(), candidate.to(),
                                                originalSpawnConnected, spawnConnected);
                                    } else {
                                        // Same-direction candidates replace the
                                        // failed original. A different-direction
                                        // candidate is kept with the original
                                        // whenever both are spawn-rooted,
                                        // regardless of existing-road contact.
                                        generatedRoads.add(candidateRoad);
                                        GenResult mapped = new GenResult(
                                                PlannedPathKey.of(original), raw.effectiveConnection(), raw.result(), candidateRoad);
                                        resolved.put(mapped.originalKey(), mapped);
                                        LOGGER.info("RoadWeaver accepted same-direction candidate replacement original={}->{} candidate={}->{} sameDirection={} originalConnectedToSpawn={} candidateConnectedToSpawn={}",
                                                original.from(), original.to(), candidate.from(), candidate.to(),
                                                sameDirection, originalSpawnConnected, spawnConnected);
                                    }
                                } else {
                                    // No protected original exists: use the
                                    // successful candidate as the only route.
                                    generatedRoads.add(candidateRoad);
                                    GenResult mapped = new GenResult(
                                            PlannedPathKey.of(original), raw.effectiveConnection(), raw.result(), candidateRoad);
                                    resolved.put(mapped.originalKey(), mapped);
                                    LOGGER.info("RoadWeaver accepted candidate replacement original={}->{} candidate={}->{} connected={}",
                                            original.from(), original.to(), candidate.from(), candidate.to(), connected);
                                }
                            } else {
                                if (raw != null && raw.road() != null) {
                                    removeGeneratedRoad(generatedRoads, raw.road());
                                }
                                StructureFileStorage.recordFailedFallbackAttempt(level, original, candidate);
                                nextCandidates.add(candidate);
                            }
                        }
                        candidateFailed = nextCandidates;
                    }
                    List<StructureConnection> nextRemaining = new ArrayList<>();
                    for (StructureConnection original : remaining) {
                        if (!resolved.containsKey(PlannedPathKey.of(original))) nextRemaining.add(original);
                    }
                    remaining = nextRemaining;
                    done.set(resolved.size());
                    // Candidate waves also leave unresolved originals pending.
                    failed.set(0);
                    InitialGenerationProgressTracker.updateRetryStates(0, remaining.size());
                    InitialGenerationProgressTracker.updateConnections(
                            roadTasks.size(), 0, done.get(), 0);
                    LOGGER.info("RoadWeaver candidate generation wave: candidateIndex={} completed={} remaining={}",
                            candidateIndex + 1, resolved.size(), remaining.size());
                }

                InitialGenerationProgressTracker.setRoutePhaseOperation(
                        InitialGenerationProgressTracker.RoutePhase.CANDIDATE, "finished");
                }

                for (StructureConnection unresolved : remaining) {
                    PlannedPathKey key = PlannedPathKey.of(unresolved);
                    GenResult protectedFallback = protectedGapFallbacks.get(key);
                    if (protectedFallback != null) {
                        resolved.putIfAbsent(key, protectedFallback);
                        generatedRoads.add(protectedFallback.road());
                        LOGGER.warn("RoadWeaver retained original protected-gap route after local repair exhausted connection={}->{}",
                                unresolved.from(), unresolved.to());
                    } else {
                        resolved.putIfAbsent(key,
                                new GenResult(key, unresolved,
                                        RoadGenerationService.InitialTaskResult.FAILED, null));
                    }
                }
                InitialGenerationProgressTracker.updateRetryStates(0, 0);
                int terminalFailures = 0;
                for (GenResult result : resolved.values()) {
                    if (result.result() == RoadGenerationService.InitialTaskResult.FAILED) {
                        terminalFailures++;
                    }
                }
                done.set(Math.max(0, resolved.size() - terminalFailures));
                failed.set(terminalFailures);
                InitialGenerationProgressTracker.updateConnections(
                        roadTasks.size(), 0, done.get(), failed.get());
                BatchGenerationResult finalBatch = new BatchGenerationResult(
                        Map.copyOf(resolved), List.copyOf(generatedRoads));
                DetailedRoadGenerationStats.logSummary(System.nanoTime() - detailedGenerationStartedAt);
                PersistedBatch persisted = persistInitialRoadBatch(level, finalBatch.roads());
                initialConstructionChunks = persisted.chunkKeys();
                batchUpdateConnectionStatus(provider, level, finalBatch.results(), persisted.edges(),
                        supplementalConnections);
            }
            ensureInitialConnectionsTerminal(provider, level);
            // Planning is complete, so publish every affected chunk to the
            // deferred construction queue. Construction still happens only
            // when Chunky or a player has already loaded the chunk, but the
            // queue must exist or those planned tunnel chunks are never
            // revisited after world entry.
            DeferredInitialRoadConstructionService.enqueue(level, initialConstructionChunks);
            // Planning, topology reconciliation, validation, and persistence
            // are the world-entry gate. Physical chunk loading and construction
            // remain owned by Chunky or real player-loaded chunks; startup must
            // never force-load the road footprint.
            LOGGER.info("RoadWeaver initial planning ready; physical construction deferred to Chunky/player-loaded chunks: chunks={}",
                    initialConstructionChunks.size());
            flushAndFinish(level);
        } catch (Throwable failure) {
            abort("blockUntilDone", failure);
        }
    }

    // ==================== 提取的通用辅助方法 ====================

    private static List<StructureConnection> filterPlanned(List<StructureConnection> list) {
        List<StructureConnection> out = new ArrayList<>();
        for (StructureConnection c : list) {
            if (c.status() == ConnectionStatus.PLANNED) out.add(c);
        }
        return out;
    }

    /**
     * 把规划前过滤掉的连接落到终态 FILTERED（内存 + 落库 + 地图补丁）。
     * FILTERED 不是 PLANNED/GENERATING，因此不会被运行期规划、施工或局部修复重新拾起。
     */
    private static void markFilteredConnections(WorldDataProvider provider,
                                                ServerLevel level,
                                                List<StructureConnection> filtered) {
        if (filtered == null || filtered.isEmpty()) return;
        Set<PlannedPathKey> keys = new HashSet<>();
        for (StructureConnection connection : filtered) {
            if (connection != null) keys.add(PlannedPathKey.of(connection));
        }
        if (keys.isEmpty()) return;
        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null || current.isEmpty()) return;
        List<StructureConnection> updated = new ArrayList<>(current);
        boolean changed = false;
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection connection = updated.get(i);
            if (connection == null || !keys.contains(PlannedPathKey.of(connection))) continue;
            StructureConnection filteredConnection = new StructureConnection(
                    connection.from(), connection.to(), ConnectionStatus.FILTERED);
            updated.set(i, filteredConnection);
            MapPatchService.publishConnection(level, filteredConnection);
            changed = true;
        }
        if (changed) provider.setStructureConnections(level, updated);
    }

    /**
     * P0-4: walks the planned-path cache once and returns the concatenated route
     * positions so the height prefetch of the first precision wave can reuse the
     * same set instead of walking {@code PlannedPathCache} a second time.
     * Returns {@code null} when Streams prewarming is unavailable, in which case
     * the prefetch falls back to its own walk.
     */
    /**
     * Holds the road's initial generation until the Great Wall has published its prewarm.
     *
     * <p>The gate itself is asynchronous, soft and bounded ({@link GreatWallPrewarmCompat}); this call
     * site joins it because the startup step it follows - the river plate prewarm - already blocks here
     * by design. A gate that could stop the road would be worse than the contention it removes, so any
     * failure is logged and the road continues.
     */
    private static void awaitGreatWallPrewarm(ServerLevel level) {
        // ⑤ 2026-10-15：把等待说清楚。长城预热期间道路什么都不做，卡面若还写着
        // "generating_roads"，玩家看到的就是「进度条卡住了」。enterStage 只改阶段与
        // operation 文本（不动任何计数器），等待结束后立刻恢复。
        InitialGenerationProgressTracker.enterStage(
                InitialGenerationStage.ROAD_GENERATION, "waiting_for_wall_prewarm");
        try {
            GreatWallPrewarmCompat.awaitPrewarmForInitialGeneration(level).join();
        } catch (Throwable failure) {
            LOGGER.warn("RoadWeaver Great Wall gate failed; continuing with initial generation.", failure);
        } finally {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.ROAD_GENERATION, "generating_roads");
        }
    }

    private static List<BlockPos> prewarmInitialRiverPlates(ServerLevel level,
                                                  List<StructureConnection> tasks) {
        if (!StreamsReflowingCompat.isAvailable() || tasks == null || tasks.isEmpty()) return null;
        ModConfig config = ConfigService.get();
        List<BlockPos> allPlannedPositions = new ArrayList<>();
        int plannedPaths = 0;
        for (StructureConnection task : tasks) {
            List<BlockPos> path = PlannedPathCache.find(
                    level,
                    task,
                    TerrainSamplingSessions.forLevel(level).effectiveMode(),
                    config.pathfindingCost()).orElse(null);
            if (path == null || path.isEmpty()) continue;
            allPlannedPositions.addAll(path);
            plannedPaths++;
        }
        if (allPlannedPositions.isEmpty()) return null;
        LOGGER.info("RoadWeaver initial global Streams prewarm: plannedPaths={} positions={}",
                plannedPaths, allPlannedPositions.size());
        StreamsReflowingCompat.prewarmInitialRoutes(level, allPlannedPositions);
        return allPlannedPositions;
    }

    private record GenResult(
            PlannedPathKey originalKey,
            StructureConnection effectiveConnection,
            RoadGenerationService.InitialTaskResult result,
            RoadData road) {}

    private record BatchGenerationResult(
            Map<PlannedPathKey, GenResult> results,
            List<RoadData> roads) {}

    private record PersistedBatch(Set<PlannedPathKey> edges, Set<Long> chunkKeys) {}

    private static void removeGeneratedRoad(List<RoadData> roads, RoadData target) {
        if (roads == null || target == null || !target.hasOwnerPair()) return;
        roads.removeIf(road -> road != null
                && road.hasOwnerPair()
                && road.ownerA2dKey() == target.ownerA2dKey()
                && road.ownerB2dKey() == target.ownerB2dKey());
    }

    private static void addSupplementalConnection(List<StructureConnection> supplemental,
                                                   StructureConnection candidate) {
        if (supplemental == null || candidate == null) return;
        PlannedPathKey key = PlannedPathKey.of(candidate);
        for (StructureConnection existing : supplemental) {
            if (existing != null && PlannedPathKey.of(existing).equals(key)) return;
        }
        supplemental.add(new StructureConnection(candidate.from(), candidate.to(), ConnectionStatus.COMPLETED));
    }

    /** Checks a candidate against already accepted roads, not the deferred original. */
    private static boolean candidateConnectsToExistingNetwork(
            RoadData candidateRoad, List<RoadData> generatedRoads) {
        if (candidateRoad == null || candidateRoad.roadSegmentList().isEmpty()
                || generatedRoads == null) return false;
        for (RoadData existing : generatedRoads) {
            if (existing != null && roadsGeometricallyConnected(existing, candidateRoad)) return true;
        }
        return false;
    }

    private static boolean roadsGeometricallyConnected(RoadData first, RoadData second) {
        if (first == null || second == null) return false;
        int horizontal = Math.max(8, (first.width() + second.width()) / 2 + 4);
        long maxHorizontalSq = (long) horizontal * horizontal;
        for (var a : first.roadSegmentList()) {
            if (a == null || a.middlePos() == null) continue;
            BlockPos ap = a.middlePos();
            for (var b : second.roadSegmentList()) {
                if (b == null || b.middlePos() == null) continue;
                BlockPos bp = b.middlePos();
                long dx = (long) ap.getX() - bp.getX();
                long dz = (long) ap.getZ() - bp.getZ();
                if (dx * dx + dz * dz <= maxHorizontalSq
                        && Math.abs(ap.getY() - bp.getY()) <= 8) return true;
            }
        }
        return false;
    }

    private static void await(ServerLevel level, CompletableFuture<?> future) {
        if (future == null) return;
        MinecraftServer server = level == null ? null : level.getServer();
        if (server != null && server.isSameThread()) {
            server.managedBlock(future::isDone);
        }
        future.join();
    }

    /** Keep the server executor available for bounded startup chunk requests. */
    private static <T> T awaitInitialFuture(ServerLevel level, Future<T> future)
            throws InterruptedException, ExecutionException {
        if (future == null) return null;
        MinecraftServer server = level == null ? null : level.getServer();
        if (server != null && server.isSameThread()) {
            server.managedBlock(future::isDone);
        }
        return future.get();
    }

    private static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step) {
        return submitAndCollectAtStep(level, tasks, step, false, null);
    }

    private static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step, boolean localOnly) {
        return submitAndCollectAtStep(level, tasks, step, localOnly, null);
    }

    private static BatchGenerationResult submitAndCollectAtStep(
            ServerLevel level, List<StructureConnection> tasks, int step, boolean localOnly,
            List<BlockPos> prefetchedRoutePositions) {
        roadGenerationWorkersActive = true;
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            prefetchInitialRoadHeights(level, tasks, step, prefetchedRoutePositions);
            List<Future<GenResult>> futures = new ArrayList<>();
            for (StructureConnection task : tasks) {
                futures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                    generating.incrementAndGet();
                    InitialGenerationProgressTracker.routePhaseWorkerStarted();
                    InitialGenerationProgressTracker.beginRoadTask("road_step_" + step, task, step);
                    InitialGenerationProgressTracker.setCurrentSamplingStep(step);
                    InitialGenerationProgressTracker.updateConnections(
                            total.get(), generating.get(), done.get(), failed.get());
                    try {
                        long taskStartedAt = System.nanoTime();
                        RoadGenerationService.InitialGenerationResult generated =
                                RoadGenerationService.generateInitialRoadAtStep(level, task, step, localOnly);
                        RoadGenerationService.InitialTaskResult result = generated.result();
                        if (localOnly) {
                            // 0.8.325：精细重算逐路计时（纯观测）。真机该阶段
                            // 91s/7 条，只有这里能看出每条实际花了多少、谁在拖。
                            // 0.9.092：改名 fine regenerate —— 原 "local repair" 与
                            // RoadPathCalculator 里真正的冲突窗口半径采样修复同名。
                            LOGGER.info("RoadWeaver fine regenerate timing: step={} wallMs={} result={} "
                                            + "stations={} connection={}->{}",
                                    step, elapsedMs(taskStartedAt), result,
                                    generated.road() == null ? -1
                                            : generated.road().roadSegmentList().size(),
                                    task.from(), task.to());
                        }
                        InitialGenerationProgressTracker.updateConnections(
                                total.get(), generating.get(), done.get(), failed.get());
                        StructureConnection effective = generated.connection() != null
                                ? generated.connection() : task;
                        return new GenResult(PlannedPathKey.of(task), effective, result, generated.road());
                    } finally {
                        InitialGenerationProgressTracker.finishRoadTask();
                        InitialGenerationProgressTracker.routePhaseWorkerFinished();
                        generating.decrementAndGet();
                        InitialGenerationProgressTracker.updateConnections(
                                total.get(), generating.get(), done.get(), failed.get());
                    }
                }));
            }

            return collectInitialRouteBatch(level, tasks, step, futures);
        } finally {
            roadGenerationWorkersActive = false;
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    private static BatchGenerationResult submitAndCollect(ServerLevel level,
                                                           List<StructureConnection> tasks) {
        roadGenerationWorkersActive = true;
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            prefetchInitialRoadHeights(level, tasks, null, null);
            List<Future<GenResult>> futures = new ArrayList<>();
            for (StructureConnection task : tasks) {
                futures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                    generating.incrementAndGet();
                    InitialGenerationProgressTracker.routePhaseWorkerStarted();
                    InitialGenerationProgressTracker.beginRoadTask("road_preparing", task, -1);
                    InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                    try {
                        RoadGenerationService.InitialGenerationResult generated =
                                RoadGenerationService.generateInitialRoad(level, task);
                        RoadGenerationService.InitialTaskResult result = generated.result();
                        // Initial roads are fully planned in memory; persistence is committed
                        // after the complete batch has formed its early junctions.
                        if (result == RoadGenerationService.InitialTaskResult.COMPLETED) {
                            done.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                        StructureConnection effective = generated.connection() != null ? generated.connection() : task;
                        return new GenResult(PlannedPathKey.of(task), effective, result, generated.road());
                    } finally {
                        InitialGenerationProgressTracker.finishRoadTask();
                        InitialGenerationProgressTracker.routePhaseWorkerFinished();
                        generating.decrementAndGet();
                        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
                    }
                }));
            }

            return collectInitialRouteBatch(level, tasks, -1, futures);
        } finally {
            roadGenerationWorkersActive = false;
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    /**
     * Resolve the shared exact-height cache once for a generation wave before
     * route workers start their per-road repair and segment construction. The
     * sampler owns in-flight tile coalescing, so overlapping roads are merged
     * without changing route topology or precision.
     *
     * <p>P0-4: the first precision wave reuses the positions the river prewarm
     * already collected for the same task list, and the per-wave barrier is gone
     * — every batch is submitted at once and awaited as a whole, so a slow batch
     * no longer idles the other workers until the next wave.</p>
     */
    private static void prefetchInitialRoadHeights(
            ServerLevel level, List<StructureConnection> tasks, Integer step,
            List<BlockPos> prefetchedRoutePositions) {
        if (level == null) return;
        boolean reusePrewarm = prefetchedRoutePositions != null && !prefetchedRoutePositions.isEmpty();
        if (!reusePrewarm && (tasks == null || tasks.isEmpty())) return;
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<BlockPos> positions = new ArrayList<>();
        int routes = 0;
        if (reusePrewarm) {
            routes = tasks == null ? 0 : tasks.size();
            for (BlockPos position : prefetchedRoutePositions) {
                if (position == null) continue;
                long key = net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample
                        .key(position.getX(), position.getZ());
                if (seen.add(key)) positions.add(new BlockPos(position.getX(), 0, position.getZ()));
            }
        } else {
            ModConfig config = ConfigService.get();
            var mode = TerrainSamplingSessions.forLevel(level).effectiveMode();
            var pathConfig = config.pathfindingCost();
            for (StructureConnection task : tasks) {
                if (task == null) continue;
                List<BlockPos> path = PlannedPathCache.find(level, task, mode, pathConfig).orElse(null);
                if (path == null || path.isEmpty()) continue;
                routes++;
                for (BlockPos position : path) {
                    if (position == null) continue;
                    long key = net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample
                            .key(position.getX(), position.getZ());
                    if (seen.add(key)) positions.add(new BlockPos(position.getX(), 0, position.getZ()));
                }
            }
        }
        if (positions.isEmpty()) return;

        final int routeCount = routes;
        final List<BlockPos> prefetchPositions = List.copyOf(positions);
        final String prefetchStep = step == null ? "default" : step.toString();
        long startedAt = System.nanoTime();
        AccurateHeightSampler sampler = AccurateHeightSampler.create(level);
        final int batchCount = (prefetchPositions.size() + INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS - 1)
                / INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS;
        LOGGER.info("RoadWeaver initial precise height prefetch start: step={} routes={} uniqueColumns={} "
                        + "batches={} batchColumns={} reusedPrewarm={} backend={} device={}",
                prefetchStep, routeCount, prefetchPositions.size(), batchCount,
                INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS, reusePrewarm,
                sampler.backendName(), sampler.deviceName());
        List<Future<?>> prefetchFutures = new ArrayList<>(batchCount);
        for (int offset = 0, batch = 1; offset < prefetchPositions.size();
             offset += INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS, batch++) {
            final int batchNumber = batch;
            int end = Math.min(prefetchPositions.size(), offset + INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS);
            List<BlockPos> slice = prefetchPositions.subList(offset, end);
            prefetchFutures.add(ThreadPoolManager.submit(ThreadPoolManager.TaskRole.INITIAL, () -> {
                long batchStarted = System.nanoTime();
                sampler.samplePositions(slice, progress -> LOGGER.info(
                        "RoadWeaver initial precise height prefetch batch progress: step={} batch={}/{} columns={} "
                                + "elapsedMs={} backend={} device={}",
                        prefetchStep, batchNumber, batchCount, progress.completedColumns(),
                        (System.nanoTime() - batchStarted) / 1_000_000L,
                        sampler.backendName(), sampler.deviceName()));
                LOGGER.info("RoadWeaver initial precise height prefetch batch complete: step={} batch={}/{} "
                                + "batchColumns={} elapsedMs={} backend={} device={}",
                        prefetchStep, batchNumber, batchCount, slice.size(),
                        (System.nanoTime() - batchStarted) / 1_000_000L,
                        sampler.backendName(), sampler.deviceName());
            }));
        }
        for (Future<?> future : prefetchFutures) {
            try {
                awaitInitialFuture(level, future);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during initial precise height prefetch", interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Initial precise height prefetch batch failed", failure.getCause());
            }
        }
        LOGGER.info("RoadWeaver initial precise height prefetch complete: step={} routes={} uniqueColumns={} elapsedMs={} backend={} device={}",
                prefetchStep, routeCount, prefetchPositions.size(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                sampler.backendName(), sampler.deviceName());
    }

    /**
     * Collects route futures independently. The old implementation waited on
     * futures in submission order, so one silent route blocked every route
     * behind it and prevented the world-entry gate from ever completing.
     */
    private static BatchGenerationResult collectInitialRouteBatch(
            ServerLevel level, List<StructureConnection> tasks, int step,
            List<Future<GenResult>> futures) {
        Map<PlannedPathKey, GenResult> results = new HashMap<>();
        List<RoadData> roads = new ArrayList<>();
        Set<Future<GenResult>> pending = new HashSet<>(futures);
        long startedAt = System.nanoTime();
        long lastLogAt = 0L;
        while (!pending.isEmpty()) {
            boolean madeProgress = false;
            for (int i = 0; i < futures.size(); i++) {
                Future<GenResult> future = futures.get(i);
                if (!pending.contains(future) || !future.isDone()) continue;
                StructureConnection task = i < tasks.size() ? tasks.get(i) : null;
                pending.remove(future);
                madeProgress = true;
                try {
                    GenResult result = future.get();
                    if (result != null) {
                        results.put(result.originalKey(), result);
                        if (result.road() != null) roads.add(result.road());
                        // At-step batches do not update the aggregate route
                        // counters inside the worker. Publish each finished
                        // route here, immediately when its future completes,
                        // so the loading HUD never appears stuck at zero.
                        if (step != -1) {
                            boolean retryPhase = InitialGenerationProgressTracker.currentRoutePhase()
                                    != InitialGenerationProgressTracker.RoutePhase.ORIGINAL;
                            boolean protectedGapNeedsRetry = result.result()
                                    == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null
                                    && result.road().hasProtectedGap()
                                    && task != null
                                    && !hasOnlyEndpointProtectedGaps(
                                    level, task, result.road());
                            if (result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                                    && result.road() != null) {
                                if (protectedGapNeedsRetry) {
                                    // A usable protected-gap route is not a
                                    // failure: it is either handed to local
                                    // repair or retained for final fallback.
                                    // 修复 HUD 冻结：ORIGINAL 波里带 gap 的路
                                    // 保持"尚未完成"（要交给 local repair），
                                    // 但 LOCAL_REPAIR 波里它已经处理完毕（
                                    // 无论 retain 还是后续 handoff），实时
                                    // 递增 done，让主进度条随逐条完成前进；
                                    // 波末 settleRoutePhase 用 resolved.size()
                                    // 精确覆盖，实时近似值不会污染终值。
                                    if (retryPhase) {
                                        done.incrementAndGet();
                                        InitialGenerationProgressTracker.routePhaseRetained();
                                    }
                                } else {
                                    done.incrementAndGet();
                                    InitialGenerationProgressTracker.routePhaseAttemptCompleted(true);
                                }
                            } else {
                                // A local-repair/candidate miss is still
                                // retryable work. Keep it out of the aggregate
                                // terminal-failure count until all candidates
                                // have been exhausted.
                                if (!retryPhase) failed.incrementAndGet();
                                InitialGenerationProgressTracker.routePhaseAttemptCompleted(false);
                            }
                            InitialGenerationProgressTracker.updateConnections(
                                    total.get(), generating.get(), done.get(), failed.get());
                        }
                    }
                } catch (CancellationException cancelled) {
                    recordInitialRouteFailure(results, task, step, "cancelled", cancelled);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    recordInitialRouteFailure(results, task, step, "interrupted", interrupted);
                } catch (ExecutionException failedTask) {
                    Throwable cause = failedTask.getCause() == null ? failedTask : failedTask.getCause();
                    recordInitialRouteFailure(results, task, step, "worker_exception", cause);
                }
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastLogAt >= INITIAL_ROUTE_WATCHDOG_LOG_MS) {
                List<StructureConnection> pendingTasks = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    if (pending.contains(futures.get(i)) && i < tasks.size()) pendingTasks.add(tasks.get(i));
                }
                InitialRouteTaskDiagnostics.logBatch(step, pendingTasks);
                lastLogAt = nowMs;
            }

            if (!madeProgress && !pending.isEmpty()) {
                // P0-4: 250ms was a coarse quantum on top of every wave; 10ms
                // keeps the completion check responsive without busy-spinning.
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Initial road batch interrupted while monitoring routes");
                }
            }
        }
        return new BatchGenerationResult(Map.copyOf(results), List.copyOf(roads));
    }

    private static void recordInitialRouteFailure(
            Map<PlannedPathKey, GenResult> results, StructureConnection task,
            int step, String reason, Throwable failure) {
        if (task == null) return;
        PlannedPathKey key = PlannedPathKey.of(task);
        results.putIfAbsent(key, new GenResult(key, task,
                RoadGenerationService.InitialTaskResult.FAILED, null));
        if (step != -1) {
            if (InitialGenerationProgressTracker.currentRoutePhase()
                    == InitialGenerationProgressTracker.RoutePhase.ORIGINAL) {
                failed.incrementAndGet();
            }
            InitialGenerationProgressTracker.routePhaseAttemptCompleted(false);
            InitialGenerationProgressTracker.updateConnections(
                    Math.max(0, total.get()), generating.get(), done.get(), failed.get());
        }
        if (failure == null) {
            LOGGER.warn("RoadWeaver initial route marked retryable failure: step={} route={}->{} reason={}",
                    step, task.from(), task.to(), reason);
        } else {
            LOGGER.error("RoadWeaver initial route marked retryable failure: step={} route={}->{} reason={}",
                    step, task.from(), task.to(), reason, failure);
        }
    }

    private static PersistedBatch persistInitialRoadBatch(ServerLevel level, List<RoadData> roads) {
        if (roads == null || roads.isEmpty()) return new PersistedBatch(Set.of(), Set.of());
        // P0-post C: the merge refine and the per-road finalization both run on the
        // shared runtime pool, which the precision waves have already restored to
        // the configured size (3 by default). Post-processing is the last startup
        // phase and nothing else competes for CPU, so lend it the startup lane
        // budget. Real-machine log (16 routes, 6 merged) showed refineMs=77410 on
        // 3 threads versus donorSelectMs=1069 after the caching fix: the pool size,
        // not the algorithm, was the remaining bottleneck.
        ThreadPoolManager.setSharedRuntimeThreads(Math.max(
                ThreadPoolManager.configuredSharedWorkerThreads(),
                ThreadPoolManager.initialGenerationThreadCapacity()));
        try {
            return persistInitialRoadBatchInternal(level, roads);
        } finally {
            ThreadPoolManager.setSharedRuntimeThreads(ThreadPoolManager.configuredSharedWorkerThreads());
        }
    }

    private static PersistedBatch persistInitialRoadBatchInternal(ServerLevel level, List<RoadData> roads) {
        long postProcessStartedAt = System.nanoTime();
        InitialGenerationProgressTracker.enterStage(
                InitialGenerationStage.POST_PROCESSING, "merging_final_network");
        InitialGenerationProgressTracker.setConstructionChunkPlan(
                Math.max(1, roads.size()), "merging_final_network");
        Map<PlannedPathKey, RoadData> originalsByEdge = new HashMap<>();
        for (RoadData road : roads) {
            if (road != null && road.hasOwnerPair()) {
                originalsByEdge.put(ownerKey(road), road);
            }
        }
        // Candidate retries are already resolved by the caller. Merge the final
        // successful routes as one immutable topology before writing any road
        // data or starting construction.
        ExistingRoadJunctionService.MergeBatchResult mergeBatch =
                ExistingRoadJunctionService.connectPlannedBatchDetailed(level, roads);
        List<RoadData> connected = mergeBatch.roads();
        Set<Long> refinedRoadFingerprints = mergeBatch.refinedRoadFingerprints();
        long mergeMs = elapsedMs(postProcessStartedAt);
        // msPerRoad is the number E3 needs: nine roads and 22 seconds is either a fixed geometry cost
        // per road or something quadratic, and the ratio separates them without a profiler.
        LOGGER.info("RoadWeaver initial final network ready before persistence: inputRoads={} finalRoads={} "
                        + "refined={} mergeMs={} msPerRoad={}",
                roads.size(), connected.size(), refinedRoadFingerprints.size(), mergeMs,
                roads.isEmpty() ? mergeMs : mergeMs / roads.size());
        // Re-baseline the progress plan after network merge: merging can
        // produce more final roads than the input batch, otherwise the
        // percentage overshoots 100 and jumps back down.
        InitialGenerationProgressTracker.setConstructionChunkPlan(
                Math.max(1, connected.size()), "processing_final_roads");
        StreamsReflowingCompat.SamplingSession streams = StreamsReflowingCompat.openSession(level);
        Set<PlannedPathKey> persistedEdges = new HashSet<>();
        Set<Long> constructionChunks = new HashSet<>();
        Object constructionLock = new Object();
        int processedFinalRoads = 0;
        List<RoadData> roadsToPersist = new ArrayList<>();
        AtomicReference<Throwable> firstFinalRoadFailure = new AtomicReference<>();
        AtomicLong finalRoadTotalNanos = new AtomicLong();
        AtomicLong finalRoadSlowestNanos = new AtomicLong();
        AtomicInteger finalRoadChanged = new AtomicInteger();
        AtomicInteger finalRoadRejected = new AtomicInteger();
        long finalRoadsStartedAt = System.nanoTime();
        List<CompletableFuture<FinalRoadWork>> finalRoadFutures = new ArrayList<>();
        for (RoadData connectedRoad : connected) {
            final RoadData finalConnectedRoad = connectedRoad;
            finalRoadFutures.add(CompletableFuture.supplyAsync(() -> {
                long roadStartedAt = System.nanoTime();
                try {
                    FinalRoadWork work = processFinalRoad(level, finalConnectedRoad, originalsByEdge, streams,
                            refinedRoadFingerprints);
                    if (work.road() == null) {
                        finalRoadRejected.incrementAndGet();
                    } else if (work.road() != finalConnectedRoad) {
                        finalRoadChanged.incrementAndGet();
                    }
                    return work;
                } finally {
                    long spent = System.nanoTime() - roadStartedAt;
                    finalRoadTotalNanos.addAndGet(spent);
                    finalRoadSlowestNanos.accumulateAndGet(spent, Math::max);
                }
            }, ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.POSTPROCESS)));
        }
        for (CompletableFuture<FinalRoadWork> future : finalRoadFutures) {
            try {
                FinalRoadWork work = future.join();
                processedFinalRoads++;
                InitialGenerationProgressTracker.recordFinalRoadDone(processedFinalRoads);
                if (work.road() != null) {
                    synchronized (constructionLock) {
                        roadsToPersist.add(work.road());
                        constructionChunks.addAll(RoadFootprint.from(work.road()).chunkKeys());
                        persistedEdges.add(work.owner());
                    }
                }
            } catch (Throwable failure) {
                firstFinalRoadFailure.compareAndSet(null, failure);
            }
        }
        if (firstFinalRoadFailure.get() != null) {
            throw new IllegalStateException("initial final-road processing failed", firstFinalRoadFailure.get());
        }
        long finalRoadsMs = elapsedMs(finalRoadsStartedAt);
        int roadCount = Math.max(1, connected.size());
        LOGGER.info("RoadWeaver final road processing timing: roads={} changed={} rejected={} wallMs={} "
                        + "avgMs={} slowestMs={} threads={}",
                connected.size(), finalRoadChanged.get(), finalRoadRejected.get(), finalRoadsMs,
                (finalRoadTotalNanos.get() / roadCount) / 1_000_000L,
                finalRoadSlowestNanos.get() / 1_000_000L,
                ThreadPoolManager.currentSharedRuntimeThreads());
        InitialGenerationProgressTracker.recordConstructionChunksDone(connected.size());
        long persistStartedAt = System.nanoTime();
        RoadShardStorage.addRoads(level, roadsToPersist);
        long persistMs = elapsedMs(persistStartedAt);
        LOGGER.info("RoadWeaver initial post-processing timing: mergeMs={} finalRoadsMs={} persistMs={} "
                        + "totalMs={} persistedRoads={} constructionChunks={}",
                mergeMs, finalRoadsMs, persistMs, elapsedMs(postProcessStartedAt),
                roadsToPersist.size(), constructionChunks.size());
        return new PersistedBatch(Set.copyOf(persistedEdges), Set.copyOf(constructionChunks));
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 第一波缺口少于这个"断口数"的路不再移交局部修复，直接保留。
     *
     * <p>真机数据（2026-09-08 新存档，16 条路）：第一波只有 1 个断口的两条路，局部修复后
     * 变成 4 个和 7 个断口（更差）；而第一波 6/12/17/24 个断口的四条路都显著改善
     * （其中三条修到 0）。所以 1 个断口不值得再花一次 step=2 局部修复。
     * 设为 0 可恢复"所有缺口路都移交"的旧行为。</p>
     */
    private static final int MIN_LOCAL_REPAIR_GAP_RUNS = 2;

    /** 保护缺口断口数（PROTECTED_GAP span 个数）。 */
    private static int protectedGapRuns(RoadData road) {
        if (road == null || road.spans() == null) return 0;
        int runs = 0;
        for (RoadSpan span : road.spans()) {
            if (span != null && span.type() == SpanType.PROTECTED_GAP) runs++;
        }
        return runs;
    }

    /** 保护缺口总块长（各断口首末站的水平距离之和）。 */
    private static double protectedGapBlocks(RoadData road) {
        if (road == null || road.spans() == null) return 0.0D;
        double blocks = 0.0D;
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            if (span.start() == null || span.end() == null) continue;
            blocks += Math.hypot(span.end().getX() - span.start().getX(),
                    span.end().getZ() - span.start().getZ());
        }
        return blocks;
    }

    /** 候选路是否比参考路留下更少的洞：先比断口数，并列再比总块长。 */
    private static boolean leavesFewerGaps(RoadData candidate, RoadData reference) {
        if (candidate == null) return false;
        if (reference == null) return true;
        int candidateRuns = protectedGapRuns(candidate);
        int referenceRuns = protectedGapRuns(reference);
        if (candidateRuns != referenceRuns) return candidateRuns < referenceRuns;
        return protectedGapBlocks(candidate) < protectedGapBlocks(reference) - 0.5D;
    }

    private static FinalRoadWork processFinalRoad(ServerLevel level,
                                                   RoadData connectedRoad,
                                                   Map<PlannedPathKey, RoadData> originalsByEdge,
                                                   StreamsReflowingCompat.SamplingSession streams,
                                                   Set<Long> refinedRoadFingerprints) {
        TerrainSamplingCache roadsideCache = new TerrainSamplingCache();
        DetailedRoadGenerationStats.beginRouteTiming(connectedRoad.ownerA2dKey() + "->" + connectedRoad.ownerB2dKey());
        try {
            RoadData road = RoadDataValidator.normalizeDuplicateJunctions(connectedRoad);
                // T-19 一致性边界:junction 合并/normalize 未改变道路时,Road.generateRoad
                // 产出的最终快照(已经过 continuity 修复、validateFinalRoad、水/结构校验)
                // 就是施工与最终校验的唯一输入。阶段二不得用相同阈值重测重改——
                // 旧的 postJunctionRepair 无条件重跑 continuity 扫描,对已被规划接受
                // 的中心切/填(maxCutDepth/maxFillDepth ≤ buildable 上限)反复触发
                // "final validation filling missing terrain columns" 与高度二次修改。
                // 只有合并/normalize 真正改变了道路时才允许阶段二修复。
                boolean alreadyRefined = refinedRoadFingerprints.contains(
                        RoadShardStorage.computeFingerprint(connectedRoad));
                if (road != null && road.hasOwnerPair() && road != connectedRoad && !alreadyRefined) {
                    RoadData postJunctionRepair = RoadPathCalculator.repairAfterNetworkMerge(
                            road, level, roadsideCache,
                            new StructureConnection(
                                    PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                                    PlanningUtils.posFrom2dKey(road.ownerB2dKey()),
                                    ConnectionStatus.GENERATING));
                    if (postJunctionRepair == null) {
                        LOGGER.error("RoadWeaver rejected initial merged road because construction continuity repair failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        LOGGER.warn("RoadWeaver route filtered: reason=merged_construction_continuity_repair_failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                    road = postJunctionRepair;
                }
                // Junction merging and height repair can change the physical
                // water/tunnel/bridge spans. Reconcile spans once more before
                // the final validators inspect or persist the merged road.
                if (road != null && road != connectedRoad) {
                    RoadData reconciled = RoadPlanConsistencyService.reconcile(
                            level, road, roadsideCache, null, streams,
                            ConfigService.get().bridgeMinWaterDepth());
                    if (reconciled == null) {
                        LOGGER.error("RoadWeaver rejected initial merged road during final span reconciliation owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        LOGGER.warn("RoadWeaver route filtered: reason=final_span_reconcile_failed owners={} -> {}",
                                road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                    road = reconciled;
                }
                RoadDataValidator.ValidationResult validation = RoadDataValidator.validateFinalRoad(road);
                if (!validation.valid() && "invalid-height-step".equals(validation.reason())) {
                    int repairedIndex = validation.index();
                    RoadData smoothed = RoadDataValidator.repairFinalHeightSteps(road);
                    if (smoothed != road) {
                        road = smoothed;
                        validation = RoadDataValidator.validateFinalRoad(road);
                        LOGGER.info("RoadWeaver repaired finalized local height profile owners={} -> {} index={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), repairedIndex);
                    }
                }
                if (!validation.valid()) {
                    LOGGER.error("RoadWeaver rejected invalid finalized initial road owners={} -> {} "
                                    + "reason={} index={} pos={} detail={}",
                            road == null ? "null" : road.ownerA2dKey(),
                            road == null ? "null" : road.ownerB2dKey(),
                            validation.reason(), validation.index(),
                            validation.position(), validation.detail());
                    LOGGER.warn("RoadWeaver route filtered: reason=invalid_finalized_initial_road:{} owners={} -> {}",
                            validation.reason(), road == null ? "null" : road.ownerA2dKey(),
                            road == null ? "null" : road.ownerB2dKey());
                    return new FinalRoadWork(ownerKey(connectedRoad), null);
                }
                FinalRoadWaterValidator.ValidationResult waterValidation =
                        road == connectedRoad ? FinalRoadWaterValidator.ValidationResult.success()
                                : timedFinalWaterValidation(level, road, roadsideCache, streams);
                if (!waterValidation.safe()) {
                    PlannedPathKey edge = ownerKey(road);
                    RoadData original = originalsByEdge.get(edge);
                    FinalRoadWaterValidator.ValidationResult originalWater = original != null && original != road
                            ? FinalRoadWaterValidator.validate(level, original, roadsideCache, streams)
                            : null;
                    if (originalWater != null && originalWater.safe()) {
                        LOGGER.warn("RoadWeaver discarded underwater initial junction rewrite and retained the "
                                        + "validated original road owners={} -> {} collision={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), waterValidation.collision());
                        road = original;
                    } else {
                        FinalRoadWaterValidator.Collision collision = waterValidation.collision();
                        // 0.8.322：循环标记保护缺口（旧实现只标一格，多个水洼时整条路仍被拒）。
                        RoadData gapped = collision != null
                                && "ordinary_construction_in_water".equals(waterValidation.reason())
                                ? FinalRoadWaterValidator.retainProtectedGaps(
                                        road, FinalRoadWaterValidator.MAX_PROTECTED_GAP_FALLBACK,
                                        candidate -> FinalRoadWaterValidator.validate(
                                                level, candidate, roadsideCache, streams))
                                : null;
                        if (gapped != null) {
                            road = gapped;
                            LOGGER.warn("RoadWeaver retained initial river protection gaps owners={} -> {} "
                                            + "firstSegment={} pos={} source={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(),
                                    collision == null ? -1 : collision.segmentIndex(),
                                    collision == null ? null : collision.position(),
                                    collision == null ? null : collision.source());
                        } else {
                            LOGGER.error("RoadWeaver rejected finalized initial road in water owners={} -> {} reason={} "
                                            + "segment={} pos={} targetY={} waterSurfaceY={} bedY={} span={} source={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), waterValidation.reason(),
                                    collision == null ? -1 : collision.segmentIndex(),
                                    collision == null ? null : collision.position(),
                                    collision == null ? null : collision.targetY(),
                                    collision == null ? null : collision.waterSurfaceY(),
                                    collision == null ? null : collision.bedY(),
                                    collision == null ? null : collision.activeSpan(),
                                    collision == null ? null : collision.source());
                            LOGGER.warn("RoadWeaver route filtered: reason=finalized_initial_road_in_water:{} owners={} -> {}",
                                    waterValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                            return new FinalRoadWork(ownerKey(connectedRoad), null);
                        }
                    }
                }
                FinalRoadStructureValidator.ValidationResult structureValidation =
                        road == connectedRoad ? FinalRoadStructureValidator.ValidationResult.success()
                                : timedFinalStructureValidation(level, road, roadsideCache, streams);
                if (!structureValidation.safe()) {
                    RoadData gapped = FinalRoadStructureValidator.retainVerifiedBuildingGaps(
                            level, new StructureConnection(
                                    PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                                    PlanningUtils.posFrom2dKey(road.ownerB2dKey()),
                                    ConnectionStatus.GENERATING),
                            road, roadsideCache, streams);
                    if (gapped != null) {
                        FinalRoadStructureValidator.ValidationResult gapValidation =
                                FinalRoadStructureValidator.validate(level, gapped, roadsideCache, streams);
                        if (gapValidation.safe()) {
                            LOGGER.warn("RoadWeaver retained verified-building protection gaps after initial network junction validation owners={} -> {}",
                                    road.ownerA2dKey(), road.ownerB2dKey());
                            road = gapped;
                            structureValidation = gapValidation;
                        }
                    }
                }
                if (!structureValidation.safe()) {
                    PlannedPathKey edge = ownerKey(road);
                    RoadData original = originalsByEdge.get(edge);
                    if (original != null && original != road) {
                        FinalRoadStructureValidator.ValidationResult originalValidation =
                                FinalRoadStructureValidator.validate(level, original, roadsideCache, streams);
                        if (originalValidation.safe()) {
                            LOGGER.warn("RoadWeaver discarded unsafe initial junction rewrite and retained the "
                                            + "validated original road owners={} -> {} collision={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                            road = original;
                        } else {
                            LOGGER.error("RoadWeaver rejected initial road after final construction-volume validation "
                                            + "owners={} -> {} collision={}",
                                    road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                            LOGGER.warn("RoadWeaver route filtered: reason=initial_construction_volume_intersection:{} owners={} -> {}",
                                    structureValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                            return new FinalRoadWork(ownerKey(connectedRoad), null);
                        }
                    } else {
                        LOGGER.error("RoadWeaver rejected initial road after final construction-volume validation "
                                        + "owners={} -> {} collision={}",
                                road.ownerA2dKey(), road.ownerB2dKey(), structureValidation.collision());
                        LOGGER.warn("RoadWeaver route filtered: reason=initial_construction_volume_intersection:{} owners={} -> {}",
                                structureValidation.reason(), road.ownerA2dKey(), road.ownerB2dKey());
                        return new FinalRoadWork(ownerKey(connectedRoad), null);
                    }
                }
                markRoadCorridorPrepared(road.roadSegmentList());
                try {
                    RandomSource random = RandomSource.create(level.getSeed()
                            ^ RoadShardStorage.computeFingerprint(road));
                    long roadsideStructuresStartedAt = System.nanoTime();
                    RoadsideStructurePrecomputer.precomputeStructures(level, road.roadSegmentList(), road.spans(),
                            road.width(), roadsideCache, null, random, road.targetY());
                    DetailedRoadGenerationStats.recordRoadsideStructure(System.nanoTime() - roadsideStructuresStartedAt);
                    long roadsideVillagesStartedAt = System.nanoTime();
                    RoadsideVillagePrecomputer.precomputeVillages(level, road.roadSegmentList(), road.spans(),
                            road.width(), roadsideCache, null, random, road.targetY());
                    DetailedRoadGenerationStats.recordRoadsideVillage(System.nanoTime() - roadsideVillagesStartedAt);
                } catch (RuntimeException roadsideFailure) {
                    LOGGER.error("RoadWeaver finalized initial road but skipped failed roadside content owners={} -> {}",
                            road.ownerA2dKey(), road.ownerB2dKey(), roadsideFailure);
                }
            return new FinalRoadWork(ownerKey(road), road);
        } finally {
            roadsideCache.clear();
            DetailedRoadGenerationStats.logRouteTiming();
        }
    }

    /** 逐路计时：最终水校验耗时计入 route timing（不改变任何判定）。 */
    private static FinalRoadWaterValidator.ValidationResult timedFinalWaterValidation(
            ServerLevel level, RoadData road, TerrainSamplingCache cache,
            StreamsReflowingCompat.SamplingSession streams) {
        long startedAt = System.nanoTime();
        try {
            return FinalRoadWaterValidator.validate(level, road, cache, streams);
        } finally {
            DetailedRoadGenerationStats.RouteTiming timing = DetailedRoadGenerationStats.routeTiming();
            if (timing != null) timing.addValidation(System.nanoTime() - startedAt);
        }
    }

    /** 逐路计时：最终结构/施工体积校验耗时计入 route timing（不改变任何判定）。 */
    private static FinalRoadStructureValidator.ValidationResult timedFinalStructureValidation(
            ServerLevel level, RoadData road, TerrainSamplingCache cache,
            StreamsReflowingCompat.SamplingSession streams) {
        long startedAt = System.nanoTime();
        try {
            return FinalRoadStructureValidator.validate(level, road, cache, streams);
        } finally {
            DetailedRoadGenerationStats.RouteTiming timing = DetailedRoadGenerationStats.routeTiming();
            if (timing != null) timing.addValidation(System.nanoTime() - startedAt);
        }
    }

    private record FinalRoadWork(PlannedPathKey owner, RoadData road) {}

    private static void batchUpdateConnectionStatus(WorldDataProvider provider,
                                                    ServerLevel level,
                                                    Map<PlannedPathKey, GenResult> results,
                                                    Set<PlannedPathKey> persistedEdges,
                                                    List<StructureConnection> supplementalConnections) {
        if ((results == null || results.isEmpty())
                && (supplementalConnections == null || supplementalConnections.isEmpty())) return;

        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null) return;

        List<StructureConnection> updated = new ArrayList<>(current);
        List<ConnectionChange> changedConnections = new ArrayList<>();
        boolean changed = false;
        if (supplementalConnections != null) {
            for (StructureConnection supplemental : supplementalConnections) {
                if (supplemental == null) continue;
                PlannedPathKey key = PlannedPathKey.of(supplemental);
                boolean alreadyPresent = updated.stream().anyMatch(existing ->
                        existing != null && PlannedPathKey.of(existing).equals(key));
                if (!alreadyPresent && persistedEdges.contains(key)) {
                    StructureConnection completed = new StructureConnection(
                            supplemental.from(), supplemental.to(), ConnectionStatus.COMPLETED);
                    updated.add(completed);
                    changedConnections.add(new ConnectionChange(null, completed));
                    changed = true;
                    LOGGER.info("RoadWeaver added retained candidate network edge {} -> {}",
                            completed.from(), completed.to());
                }
            }
        }
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection original = updated.get(i);
            PlannedPathKey k = PlannedPathKey.of(original);
            GenResult result = results == null ? null : results.get(k);
            if (result == null) continue;
            StructureConnection effective = result.effectiveConnection() != null ? result.effectiveConnection() : original;
            PlannedPathKey effectiveKey = PlannedPathKey.of(effective);
            boolean completed = result.result() == RoadGenerationService.InitialTaskResult.COMPLETED
                    && persistedEdges.contains(effectiveKey);
            ConnectionStatus newStatus = !completed ? ConnectionStatus.FAILED
                    : result.road() != null && result.road().hasProtectedGap()
                    ? ConnectionStatus.PROTECTED_GAP : ConnectionStatus.COMPLETED;
            StructureConnection changedConnection = new StructureConnection(
                    completed ? effective.from() : original.from(),
                    completed ? effective.to() : original.to(),
                    newStatus);
            updated.set(i, changedConnection);
            changedConnections.add(new ConnectionChange(original, changedConnection));
            changed = true;
            // 0.8.355：每条连接的最终状态单独一行，便于玩家/我们按端点定位到具体哪条路失败。
            if (newStatus == ConnectionStatus.FAILED) {
                if (result.road() == null) {
                    LOGGER.warn("RoadWeaver initial connection FAILED (no usable route): {} -> {} "
                                    + "[all precision tiers, local repair and fallbacks were exhausted]",
                            original.from(), original.to());
                } else {
                    LOGGER.warn("RoadWeaver initial connection FAILED (route computed but not persisted, "
                                    + "likely failed a final validator): {} -> {}",
                            original.from(), original.to());
                }
            } else {
                LOGGER.info("RoadWeaver initial connection {} : {} -> {} (stations={})",
                        newStatus, changedConnection.from(), changedConnection.to(),
                        result.road() == null ? -1 : roadStationCount(result.road()));
            }
        }
        if (changed) {
            provider.setStructureConnections(level, updated);
            for (ConnectionChange change : changedConnections) {
                if (change.original() == null) {
                    MapPatchService.publishConnection(level, change.updated());
                } else if (!PlanningUtils.sameEdge(change.original(), change.updated())) {
                    MapPatchService.publishConnectionReplacement(level, change.original(), change.updated());
                } else {
                    MapPatchService.publishConnection(level, change.updated());
                }
                if (change.updated().status() == ConnectionStatus.COMPLETED
                        || change.updated().status() == ConnectionStatus.PROTECTED_GAP) {
                    MapPatchService.publishRoadForConnectionAsync(level, change.updated());
                }
            }
        }
    }

    private static void ensureInitialConnectionsTerminal(WorldDataProvider provider, ServerLevel level) {
        List<StructureConnection> current = provider.getStructureConnections(level);
        if (current == null) return;
        List<StructureConnection> pending = new ArrayList<>();
        for (StructureConnection connection : current) {
            if (connection == null) continue;
            if (connection.status() == ConnectionStatus.PLANNED
                    || connection.status() == ConnectionStatus.GENERATING) {
                pending.add(connection);
            }
        }
        if (pending.isEmpty()) return;
        // Task 2: never abort world entry over non-terminal connections. The
        // generation waves above have already run local repair and candidate
        // fallbacks, so anything still PLANNED/GENERATING here has no pending
        // work — push it to a reliable terminal state (FAILED) instead of
        // throwing, which used to skip flushAndFinish and the completion
        // checkpoint and forced a full restart re-run.
        String endpoints = pending.stream()
                .limit(8)
                .map(connection -> connection.from() + "->" + connection.to())
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown");
        LOGGER.error("RoadWeaver forcing initial connections to terminal FAILED state before world entry: "
                + pending.size() + " endpoints=" + endpoints);
        List<StructureConnection> updated = new ArrayList<>(current);
        for (int i = 0; i < updated.size(); i++) {
            StructureConnection connection = updated.get(i);
            if (connection == null) continue;
            if (connection.status() == ConnectionStatus.PLANNED
                    || connection.status() == ConnectionStatus.GENERATING) {
                StructureConnection terminal = new StructureConnection(
                        connection.from(), connection.to(), ConnectionStatus.FAILED);
                updated.set(i, terminal);
                MapPatchService.publishConnection(level, terminal);
            }
        }
        provider.setStructureConnections(level, updated);
    }

    private record ConnectionChange(StructureConnection original, StructureConnection updated) {
    }

    private static PlannedPathKey ownerKey(RoadData road) {
        return PlannedPathKey.of(
                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerA2dKey()),
                net.shiroha233.roadweaver.planning.PlanningUtils.posFrom2dKey(road.ownerB2dKey()));
    }

    private static void flushAndFinish(ServerLevel level) {
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.POST_PROCESSING, "finalizing_network");
        long startedAt = System.nanoTime();
        RoadShardStorage.flushAll(level);
        long flushMs = elapsedMs(startedAt);
        RoadPositionQuery.clearCache(level);
        RoadPlanningReadinessIndex.markInitialCoverageReady(level);
        compactPreparedStartCacheAfterInitialGeneration();
        InitialGenerationCheckpointStore.markCompleted(level);
        LOGGER.info("RoadWeaver initial finalizing timing: flushAllMs={} totalMs={}",
                flushMs, elapsedMs(startedAt));
        // 0.8.355：收尾汇总 —— 明确写出「本轮一共做了什么、哪些是复用的」，
        // 这是玩家判断「这次有没有白跑」的唯一依据。
        logCompletionSummary(level);
        active = false;
        InitialGenerationProgressTracker.updateConnections(total.get(), generating.get(), done.get(), failed.get());
        InitialGenerationProgressTracker.finish();
    }

    /** 0.8.355：开局流程收尾汇总日志。 */
    private static void logCompletionSummary(ServerLevel level) {
        try {
            List<StructureConnection> all = WorldDataProvider.getInstance().getStructureConnections(level);
            int completed = countByStatus(all, ConnectionStatus.COMPLETED);
            int protectedGap = countByStatus(all, ConnectionStatus.PROTECTED_GAP);
            int failed = countByStatus(all, ConnectionStatus.FAILED);
            int filtered = countByStatus(all, ConnectionStatus.FILTERED);
            int stillPending = countByStatus(all, ConnectionStatus.PLANNED)
                    + countByStatus(all, ConnectionStatus.GENERATING);
            LOGGER.info("RoadWeaver initial generation COMPLETED. Network status: total={} completed={} "
                            + "protectedGap={} failed={} filtered={} pending={}. "
                            + "checkpoint.txt is now state=COMPLETED, so the next world load skips initial "
                            + "generation entirely; only failed routes stay eligible for later repair waves.",
                    completed + protectedGap + failed + filtered + stillPending,
                    completed, protectedGap, failed, filtered, stillPending);
        } catch (Throwable ignored) {
            // 汇总日志失败不能影响收尾。
        }
    }

    private static void abort(String phase, Throwable failure) {
        LOGGER.error("Initial RoadWeaver generation aborted during {}", phase, failure);
        // 0.8.355：中止时保留 checkpoint（不 markCompleted），下一次世界加载会按
        // phase/done/total 续接，而不是从头重跑。
        LOGGER.warn("RoadWeaver initial generation aborted; checkpoint kept at the last recorded progress "
                + "so the next world load can resume instead of restarting. phase={} done={}/{}",
                phase, done.get(), total.get());
        roadGenerationWorkersActive = false;
        compactPreparedStartCacheAfterInitialGeneration();
        active = false;
        generating.set(0);
        failed.incrementAndGet();
        InitialGenerationProgressTracker.updateRetryStates(0, 0);
        InitialGenerationProgressTracker.updateConnections(total.get(), 0, done.get(), failed.get());
        InitialGenerationProgressTracker.finish();
    }

    public static void update(ServerLevel level) {
        if (active) return;

        WorldDataProvider provider = WorldDataProvider.getInstance();
        List<StructureConnection> conns = provider.getStructureConnections(level);

        if (conns == null || conns.isEmpty()) {
            total.set(0);
            generating.set(0);
            done.set(0);
            failed.set(0);
            return;
        }

        int g = 0, c = 0, f = 0;
        for (StructureConnection sc : conns) {
            ConnectionStatus s = sc.status();
            if (s == ConnectionStatus.GENERATING) g++;
            else if (s == ConnectionStatus.COMPLETED || s == ConnectionStatus.PROTECTED_GAP) c++;
            else if (s == ConnectionStatus.FAILED) f++;
        }
        total.set(conns.size());
        generating.set(g);
        done.set(c);
        failed.set(f);
    }
}
