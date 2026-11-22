/* 文件职责：协调首次世界加载时的道路规划、生成、进度与持久化。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
import static net.shiroha233.roadweaver.generation.InitialRouteSubmitOps.*;
import static net.shiroha233.roadweaver.generation.InitialRoutePersistOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanLifecycleOps.*;
import static net.shiroha233.roadweaver.generation.InitialPlanWaiter.*;

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
    static final int INITIAL_HEIGHT_PREFETCH_BATCH_COLUMNS = 4096;
    /** 禁用拓扑替代候选以保留原边失败原因，不默默改换连接。 */
    static final boolean TOPOLOGY_CANDIDATES_ENABLED = false;
    private InitialGenManager() {}

    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 此间隔只控制诊断心跳，不应误读为任务超时或取消保证。 */
    static final long INITIAL_ROUTE_WATCHDOG_LOG_MS = 5_000L;

    static volatile boolean active;
    static volatile boolean roadGenerationWorkersActive;
    static final AtomicInteger total = new AtomicInteger(0);
    static final AtomicInteger done = new AtomicInteger(0);
    static final AtomicInteger generating = new AtomicInteger(0);
    static final AtomicInteger failed = new AtomicInteger(0);

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
            LOGGER.info("【启动时实际读取的道路外观与规划配置】 " + "RoadWeaver config appearance: width={} clearHeight={} tunnelClearHeight={} "
                            + "tunnelDrySeal={} culvert={} culvertMaxStations={} culvertMaxWaterDepth={} "
                            + "slopeByArcLength={} slopeLimitEnabled={} maxSlopeStepPer2={} bridge={} "
                            + "planningAlgorithm={}",
                    appearance.roadWidth(), appearance.roadClearHeight(), appearance.tunnelClearHeight(),
                    appearance.tunnelDrySeal(), appearance.culvertEnabled(), appearance.culvertMaxStations(),
                    appearance.culvertMaxWaterDepth(), appearance.slopeByArcLength(),
                    appearance.slopeLimitEnabled(), appearance.maxSlopeStepPerTwoSegments(),
                    config.bridgeEnabled(), config.planning().planningAlgorithm());
        } catch (Throwable couldNotRead) {
            LOGGER.warn("【无法读取启动配置摘要】 " + "RoadWeaver config appearance could not be read for the startup line", couldNotRead);
        }

        if (resumeStage != null) {
            InitialGenerationCheckpointStore.ResumePoint point = resume.orElseThrow();
            LOGGER.info("【检测到上次异常退出，复用持久缓存并继续未完成道路】 " + "RoadWeaver initial generation RESUMING after an unclean shutdown: "
                            + "phase={} done={}/{} atLastSave. "
                            + "Persisted work (structure scan tiles, coarse terrain tiles, completed connections) "
                            + "is reused; the remaining connections continue from where the previous session stopped.",
                    resumeStage.name(), point.done(), point.total());
        } else if (resume.isPresent()) {
            LOGGER.info("【没有可用阶段检查点，从头调度并复用匹配缓存】 " + "RoadWeaver initial generation starting from the beginning: "
                    + "checkpoint has no usable phase (legacy schema or malformed file). "
                    + "Persisted caches are still reused where their keys match.");
        }
        // 崩溃残留的 GENERATING 必须降级，否则 filterPlanned() 永远看不到它们
        // （statusPriority: GENERATING=3 > PLANNED=2，既不做也不重置）。
        int downgraded = downgradeStaleGenerating(WorldDataProvider.getInstance(), level);
        if (downgraded > 0) {
            LOGGER.info("【上次未结束的生成任务已重置为待规划】 " + "RoadWeaver initial resume: {} connection(s) left in GENERATING by the previous "
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

            // 中断后重进也必须从干净的开局交接开始。
            TerrainSamplingSessions.forLevel(level).clearInitialBand();
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
            TerrainSamplingSessions.forLevel(level).clearInitialBand();
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
    static InitialGenerationStage parseStage(String phaseName) {
        if (phaseName == null || phaseName.isBlank()) return null;
        try {
            return InitialGenerationStage.valueOf(phaseName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOGGER.warn("【检查点阶段未知，从初始阶段重新调度】 " + "RoadWeaver checkpoint contains unknown phase '{}'; restarting initial generation from the beginning",
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
    static int downgradeStaleGenerating(WorldDataProvider provider, ServerLevel level) {
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
            LOGGER.info("【遗留生成状态已回退为待规划以便重试】 " + "RoadWeaver initial resume: downgraded stale GENERATING connection to PLANNED {} -> {}",
                    connection.from(), connection.to());
        }
        return downgraded.size();
    }

    /**
     * 0.8.355：把当前阶段与进度写进 checkpoint。每条连接完成/失败后调用，
     * 使崩溃最多只丢失「最后一条连接」的进度。
     */
    static void recordCheckpoint(ServerLevel level, InitialGenerationStage stage, int done, int total) {
        if (level == null || stage == null) return;
        InitialGenerationCheckpointStore.recordProgress(level, stage.name(), done, total);
    }

    /** 统计某状态的连接数，仅用于日志可读性。 */
    static int countByStatus(List<StructureConnection> connections, ConnectionStatus status) {
        if (connections == null || status == null) return 0;
        int count = 0;
        for (StructureConnection connection : connections) {
            if (connection != null && connection.status() == status) count++;
        }
        return count;
    }

    /** 路线的站点数（{@code targetY} 是每条路的施工契约，长度即站数），仅用于日志。 */
    static int roadStationCount(RoadData road) {
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
    static void logPersistedReuse(ServerLevel level, int plannedRoutes) {
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
            LOGGER.info("【已恢复存档连接状态，以下计数不代表本次重新生成】 " + "RoadWeaver initial persisted-state reuse: connections total={} "
                            + "(completed={} protectedGap={} failed={} filtered={} stillPending={}). "
                            + "These are loaded from state.json and are NOT regenerated.",
                    completed + protectedGap + failed + filtered + pending,
                    completed, protectedGap, failed, filtered, pending);
            LOGGER.info("【本次仍需规划或生成的道路数量】 " + "RoadWeaver initial work to do: {} route(s) need planning/generation this session "
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

    // ==================== 提取的通用辅助方法 ====================

    static List<StructureConnection> filterPlanned(List<StructureConnection> list) {
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
    static void markFilteredConnections(WorldDataProvider provider,
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


    /** 该兼容等待仅显示长城等待阶段；入口严格前置门负责确认就绪，兼容层放行或异常不能作为长城完成证据。 */
    static void awaitGreatWallPrewarm(ServerLevel level) {
        // ⑤ 2026-10-15：把等待说清楚。长城预热期间道路什么都不做，卡面若还写着
        // "generating_roads"，玩家看到的就是「进度条卡住了」。enterStage 只改阶段与
        // operation 文本（不动任何计数器），等待结束后立刻恢复。
        InitialGenerationProgressTracker.enterStage(
                InitialGenerationStage.ROAD_GENERATION, "waiting_for_wall_prewarm");
        try {
            GreatWallPrewarmCompat.awaitPrewarmForInitialGeneration(level).join();
        } catch (Throwable failure) {
            // 严格入口之后仍可能收到终态失败，交给初始流程统一结案，不能继续施工。
            if (failure.getCause() instanceof GreatWallPrewarmCompat.WallPrewarmFailedException terminal) {
                throw terminal;
            }
            LOGGER.warn("【长城兼容等待失败，恢复当前流程；本条未确认长城就绪】 " + "RoadWeaver Great Wall compatibility wait failed; resuming this flow without confirming wall readiness.", failure);
        } finally {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.ROAD_GENERATION, "generating_roads");
        }
    }

    /** 河流预热和后续精确预取复用一次收集的粗路线坐标；不可用返回null，让调用方走自己的收集路径。 */
    static List<BlockPos> prewarmInitialRiverPlates(ServerLevel level,
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
        LOGGER.info("【初始道路全局河流预热规模】 " + "RoadWeaver initial global Streams prewarm: plannedPaths={} positions={}",
                plannedPaths, allPlannedPositions.size());
        StreamsReflowingCompat.prewarmInitialRoutes(level, allPlannedPositions);
        return allPlannedPositions;
    }

    record GenResult(
            PlannedPathKey originalKey,
            StructureConnection effectiveConnection,
            RoadGenerationService.InitialTaskResult result,
            RoadData road) {}

    record BatchGenerationResult(
            Map<PlannedPathKey, GenResult> results,
            List<RoadData> roads) {}

    record PersistedBatch(Set<PlannedPathKey> edges, Set<Long> chunkKeys) {}


    /** 服务端主线程等待时继续处理受管理任务，避免区块请求与等待形成死锁。 */
    static <T> T awaitInitialFuture(ServerLevel level, Future<T> future)
            throws InterruptedException, ExecutionException {
        if (future == null) return null;
        MinecraftServer server = level == null ? null : level.getServer();
        if (server != null && server.isSameThread()) {
            server.managedBlock(future::isDone);
        }
        return future.get();
    }



    /**
     * 第一波缺口少于这个"断口数"的路不再移交局部修复，直接保留。
     *
     * <p>真机数据（2026-09-08 新存档，16 条路）：第一波只有 1 个断口的两条路，局部修复后
     * 变成 4 个和 7 个断口（更差）；而第一波 6/12/17/24 个断口的四条路都显著改善
     * （其中三条修到 0）。所以 1 个断口不值得再花一次 step=2 局部修复。
     * 设为 0 可恢复"所有缺口路都移交"的旧行为。</p>
     */
    static final int MIN_LOCAL_REPAIR_GAP_RUNS = 2;





    /** 逐路计时：最终结构/施工体积校验耗时计入 route timing（不改变任何判定）。 */
    /** 携带最终待验证道路及原连接键，避免改线后丢失任务归属。 */
    record FinalRoadWork(PlannedPathKey owner, RoadData road) {}

    record ConnectionChange(StructureConnection original, StructureConnection updated) {
    }


}
