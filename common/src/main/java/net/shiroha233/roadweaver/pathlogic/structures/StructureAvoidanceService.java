/* 文件职责：缓存已加载区块的结构边界，让路面铺设 worker 线程无需同步触发主线程 chunk 加载。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;

public final class StructureAvoidanceService {
    private StructureAvoidanceService() {}

    /**
     * Buildings belonging to the selected village endpoint can be emitted as
     * separate verified pieces. Keep the endpoint protection group bounded so
     * those pieces do not become ordinary mid-route obstacles.
     */
    static final int ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS = 96;
    static final int ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS = 32;

    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    static final int CONSTRUCTION_BELOW = 2;
    static final int CONSTRUCTION_ABOVE = 6;
    /**
     * T-35: 过路村庄掩码的 Y 相交窗口下方容忍。
     *
     * <p><b>⚠️ 全仓零引用 —— 改这个数值不会有任何效果。</b>原 javadoc 写的是
     * <b>设计意图</b>（山坡村庄空地比最低建筑低十余格 ⇒ 施工带与聚合盒不相交 ⇒
     * 掩码不点亮 ⇒ 路整片穿村；原意是放宽下方容忍让这些站成缺口），**从未实现**。
     * 保留是对旧版的忠实继承（旧版同样只声明不用，见 {@code docs/archive/AUDIT-20261113.md} §5）；
     * 要做需加读取点 + 真机验证，属独立改动。</p>
     */
    static final int VILLAGE_GAP_BELOW_TOLERANCE = 16;
    static final int PLANNING_CORRIDOR_RADIUS = 192;
    /** 路径走廊结构 start 采集半径（chunk）。与 PLANNING_CORRIDOR_RADIUS
     *  (192 格 = 12 chunk) 对齐：旧值 8（+margin=9 chunk=144 格）小于规划走廊，
     *  U 型弯路绕开的沿途村庄 structure start chunk 距路径 >9 chunk 时被漏采，
     *  导致 T-23 过路村庄避让失效、道路切村。 */
    static final int STRUCTURE_START_REACH_CHUNKS = 11;
    static final int PATH_CORRIDOR_MARGIN_CHUNKS = 1;
    static final int AVOIDANCE_PREDICTION_TILE_CHUNKS = 16;
    static final int STRUCTURE_START_BATCH_SIZE = 16;
    /** Runtime keeps a small window; startup may use the prepared structure lanes. */
    static final int STRUCTURE_START_BATCH_WINDOW = 2;
    static final int MAX_PENDING_REQUESTS = 32;
    static final TicketType<Unit> STRUCTURE_SNAPSHOT_TICKET =
            TicketType.create("roadweaver_structure_snapshot", (left, right) -> 0);
    static final int PLANNING_SNAPSHOT_CACHE_CAPACITY = 2048;
    static final int PREPARED_START_CACHE_CAPACITY = 32768;
    /** Preserve the old cached world area after reducing prediction tiles from 64x64 to 16x16. */
    static final int OBSTACLE_TILE_CACHE_CAPACITY = 8192;
    /** Maximum displacement between a selected structure origin and its actual replacement start. */
    static final int ENDPOINT_REPLACEMENT_MATCH_BLOCKS = 24;
    /**
     * A generated template piece can sit several blocks inside the selected
     * structure origin. Keep this local and require a unique verified owner;
     * this is not the broad candidate/replacement radius.
     */
    static final int ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS = 16;
    /** 关掉探针的口子（默认开）：探针每起点多一次群系取样，量完可以关。 */
    static final String BIOME_PROBE_PROPERTY = "roadweaver.biomePrefilterProbe";


    /**
     * 0.9.059（①）：{@code terrainSnapshotPrepMs} 的子段计时（纯观测，<b>零行为</b>）。
     *
     * <p>真机一局 {@code terrainSnapshotPrepMs=72,670}（占 terrainPlanning 的 61%，也是
     * async planning 的最大单项），而它内部一直只有一个总数。这里把同一个时间窗口按
     * <b>串行、互不重叠</b>的段落拆开，逐段累加到本对象，最后由
     * {@code RoadPlanningService} 打一行 {@code [PLAN-SNAPSHOT-BREAKDOWN]}。</p>
     *
     * <h2>四段的真实归属（照代码写，不按名字猜）</h2>
     * <ul>
     *   <li>{@code tileSampleMs} —— {@code predictObstacleCandidates(...)}（本文件 2252 行）。
     *       它按 {@code AVOIDANCE_PREDICTION_TILE_CHUNKS=16} 个区块为一格瓦片，把走廊
     *       覆盖到的瓦片一次性预测出结构候选（{@code StructurePredictor.predictAvoidanceStructuresInRect}）。
     *       <b>注意口径</b>：这是"结构预测瓦片"，不是地形采样；地形采样在
     *       {@code terrainCoreMs} 里（{@code TERRAIN_PLANNING.plan}）。</li>
     *   <li>{@code structureOverlayMs} —— {@code resolvePreparedStartsAsync(...)} 整段
     *       （逐起点解析精确结构盒 = 结构占用叠加），也就是真机
     *       {@code resolutionMs=64,709} 的那一段。</li>
     *   <li>{@code cacheWriteMs} —— 解析结果并进 {@code StructureAvoidanceCache.PLANNING_SNAPSHOTS} +
     *       {@code indexConstructionBoxes} + {@code putBounded} 的收尾段。</li>
     *   <li>{@code corridorSnapshotMs} —— <b>本窗口恒为 0</b>：真机口径的
     *       {@code preparePathCorridorSnapshotsNow}(833 行) <b>不在这条调用路径上</b>，
     *       它由 {@code preparePlanningSnapshotsForPathsAsync} 在地形规划之后调用，
     *       记在 {@code structureSnapshotsMs}(18,167) 里。这里如实写 0，不硬塞。</li>
     *   <li>{@code serverInitMs} / {@code startKeyMs} —— 额外两段：前者是服务器线程上
     *       取结构快照与计数器的前缀，后者是逐连接枚举候选起点的循环。</li>
     * </ul>
     *
     * <p>{@code threads=} 是这段窗口里真实观察到的 worker 线程名个数，{@code columns=} 是
     * 结构起点解析提交的并发列宽（{@code STRUCTURE_START_BATCH_SIZE × windowBatches}）——
     * 两个数一起用来判断"是不是单线程在扛"。</p>
     */

    /**
     * Captures real structure-piece boxes on the server thread before a route
     * is searched by workers. Selected structures remain connection nodes;
     * every other same-dimension structure candidate near the route is only
     * an obstacle. Starts load no further than STRUCTURE_STARTS, so workers
     * never schedule a server task or request a chunk while pathfinding.
     */
    /** 规划前过滤结果：kept 保留参与规划，filtered 被标记为不参与规划/施工/局部修复。 */
    /** 一轮过滤超过这个条数就整体放弃（只告警），避免一次砍掉太多路。 */
    static final int UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND = 4;

    // ==================== E 阶段：结构起点预筛 ====================

    /** 预筛开关：放置数学判定失败的候选不再进入精确解析。 */
    static final AtomicBoolean STRUCTURE_PREFILTER_ENABLED = new AtomicBoolean(true);
    /** 预筛统计：被跳过的候选数 / 判定为可能的候选数。 */
    static final AtomicLong PREFILTERED_CANDIDATES = new AtomicLong(0);
    static final AtomicLong PREFILTER_KEPT_CANDIDATES = new AtomicLong(0);
    /** 一致性护栏是否已经跑过（每次进程一次，失败即永久关闭预筛）。 */
    static final AtomicBoolean PREFILTER_GUARD_RUN = new AtomicBoolean(false);
    /** 护栏最多核对多少个"被跳过"的候选（全量双跑代价太大）。 */
    static final int PREFILTER_GUARD_SAMPLE = 64;

    /**
     * E 阶段：放置数学预筛。
     *
     * <p>用 {@link RandomSpreadStructurePlacement#getPotentialStructureChunk(long, int, int)}
     * 判断候选区块是否可能是该结构集的起点：候选区块必须正好等于它所属 region 的
     * 潜在起点区块。不等则 {@code isStructureChunk} 必然为 false（
     * {@code isPlacementChunk} 就是这条判定），精确解析一定返回空盒子，可以直接跳过。</p>
     *
     * <p>非随机散布的放置类型（ConcentricRings / 自定义）一律<b>保守放行</b>，
     * 不做数学判定。</p>
     */
    static boolean isPotentialStructureChunk(ServerLevel level,
                                                      Holder<StructureSet> structureSetHolder,
                                                      int chunkX, int chunkZ) {
        if (!STRUCTURE_PREFILTER_ENABLED.get()) return true;
        if (level == null || structureSetHolder == null) return true;
        try {
            if (!ConfigService.get().planning().prefilterStructureStarts()) return true;
        } catch (Throwable ignored) {
            return true;
        }
        StructurePlacement placement = structureSetHolder.value().placement();
        if (!(placement instanceof RandomSpreadStructurePlacement randomSpread)) return true;
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        ChunkPos potential = randomSpread.getPotentialStructureChunk(seed, chunkX, chunkZ);
        return potential.x == chunkX && potential.z == chunkZ;
    }

    /**
     * E 阶段护栏：预筛前后 exactPieceBoxes 必须一致。
     *
     * <p>做法：对同一批候选起点分别按"预筛后"和"不预筛"精确解析一遍，比较盒子数量与
     * owner 集合。不一致就<b>自动关闭预筛</b>并打警告
     * {@code structure prefilter disabled: box count mismatch}，宁可慢，不可丢村庄。</p>
     *
     * <p>只在进程内第一次解析时跑一遍（{@link #PREFILTER_GUARD_RUN}），失败后永久关闭，
     * 不进入常规路径。</p>
     */
    static void verifyPrefilterExactness(ServerLevel level, List<PreparedStartKey> starts) {
        if (!STRUCTURE_PREFILTER_ENABLED.get() || starts == null || starts.isEmpty()) return;
        if (!PREFILTER_GUARD_RUN.compareAndSet(false, true)) return;
        try {
            List<PreparedStartKey> skipped = new ArrayList<>();
            for (PreparedStartKey start : starts) {
                if (start.source() != StructureFileStorage.SOURCE_PREDICTED) continue;
                if (start.structureSet() == null) continue;
                ChunkPos pos = new ChunkPos(start.chunkKey());
                if (!isPotentialStructureChunk(level, start.structureSet(), pos.x, pos.z)) {
                    skipped.add(start);
                }
            }
            if (skipped.isEmpty()) {
                LOGGER.info("RoadWeaver structure prefilter guard: skipped=0 checked=0 boxes=0 verdict=match");
                return;
            }
            // 只核对"被预筛跳过"的候选：预筛的风险正是"跳掉了本来有盒子的候选"。
            // 全量双跑代价太大，这里按固定采样核对，任何一次出现盒子就判不一致。
            int checked = 0;
            long boxes = 0L;
            for (PreparedStartKey start : skipped) {
                if (checked >= PREFILTER_GUARD_SAMPLE) break;
                checked++;
                boxes += resolvePredictedStructureSetBoxes(
                        level, new ChunkPos(start.chunkKey()), start.structureSet()).size();
            }
            boolean match = boxes == 0L;
            LOGGER.info("RoadWeaver structure prefilter guard: skipped={} checked={} boxes={} verdict={}",
                    skipped.size(), checked, boxes, match ? "match" : "mismatch");
            if (!match) {
                STRUCTURE_PREFILTER_ENABLED.set(false);
                LOGGER.warn("structure prefilter disabled: box count mismatch skipped={} checked={} boxes={}",
                        skipped.size(), checked, boxes);
            }
        } catch (Throwable failure) {
            STRUCTURE_PREFILTER_ENABLED.set(false);
            LOGGER.warn("structure prefilter disabled: box count mismatch (guard failed: {})", failure.toString());
        }
    }

    /**
     * B 阶段：规划前过滤"村庄 V ↔ V 结构盒内的未知端点"。
     *
     * <p>判定（两条同时成立才过滤）：</p>
     * <ol>
     *   <li>连接某一端点是<b>未知端点</b>——既不在结构库的已核验结构位点里，
     *       也无法从附近已核验村庄建筑推断出 owner；</li>
     *   <li>该端点 XZ 落在<b>某个村庄 V 的结构聚合盒</b>内，且另一端点恰好就是
     *       那个村庄 V。</li>
     * </ol>
     *
     * <p>动作：该连接不进规划、不进施工、不进局部修复。</p>
     *
     * <p>安全阀：一轮过滤超过 {@value #UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND} 条，
     * 或过滤后某端点变成 0 连接，则整体放弃过滤（只告警），宁可不优化也不砍路。</p>
     *
     * <p>0.8.315：距离不是判据。早先复用 {@code ENDPOINT_REPLACEMENT_MATCH_BLOCKS}(24)
     * 限制两端点距离，导致大村庄（聚合盒 60+ 格）的"村庄 ↔ 盒内未知端点"永远不触发。
     * 现在只看"未知端点在该村庄聚合盒内 + 另一端点就是该村庄"，两条安全阀兜底。</p>
     */
    public static EndpointFilterOutcome filterUnknownEndpointInsideVillage(
            ServerLevel level, List<StructureConnection> connections) {
        if (level == null || connections == null || connections.isEmpty()) {
            return new EndpointFilterOutcome(connections == null ? List.of() : connections, List.of(), false, "");
        }
        try {
            if (!ConfigService.get().planning().filterUnknownEndpointInsideVillage()) {
                return new EndpointFilterOutcome(connections, List.of(), false, "");
            }
        } catch (Throwable ignored) {
            return new EndpointFilterOutcome(connections, List.of(), false, "");
        }

        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        List<StructureInfo> knownInfos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        List<StructureConnection> kept = new ArrayList<>(connections.size());
        List<StructureConnection> filtered = new ArrayList<>();
        // 0.8.331：两端点都在同一个村庄盒内的"村内连接"（真机 village_iberian
        // (-864,752)↔(-813,716)，14 格）不算"这个村庄的路"，因此不参与安全阀二。
        // 否则阀门会把它保下来，而它必然规划失败 → 每局固定 failed=1。
        Set<StructureConnection> villageInternal = new HashSet<>();
        for (StructureConnection connection : connections) {
            if (connection == null) continue;
            RouteKey key = routeKey(level, connection);
            PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
            if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
                kept.add(connection);
                continue;
            }
            boolean fromUnknown = isUnknownEndpoint(level, connection.from(), storageSnapshot, snapshot);
            boolean toUnknown = isUnknownEndpoint(level, connection.to(), storageSnapshot, snapshot);
            StructureConnection matched = null;
            // 0.8.315：不再要求两端点距离 ≤ ENDPOINT_REPLACEMENT_MATCH_BLOCKS(24)。
            // villageOwnerAt(unknown) 已经证明未知端点落在该村庄的建筑聚合盒内；距离不提供
            // 额外信息，而大村庄的盒子动辄 60+ 格（真机 village_iberian：(-864,752)↔(-813,716)
            // 相距 62.4 格），24 格闸门会让这条过滤对大村庄永远失效。
            //
            // 0.8.317：另一端必须**确实就是那个村庄**。旧代码用 ownsEndpoint(village)，
            // 而 endpointOwners 是两个端点 owner 的并集——未知端点自己也会被
            // "从附近已核验建筑推断 owner"（真机日志 inferred endpoint owner ... (-813,716)
            // owner=village_iberian）而进入该集合。于是**别的村庄**通往这个未知点的正常路
            // （真机 (-1104,304)->(-813,716)、(-480,480)->(-813,716)，两条都是 COMPLETED）
            // 也会被判为"村庄 V ↔ V 盒内未知点"而被过滤。现在改为按已核验结构位点核对
            // 另一端点的区块与结构 id，必须与村庄 owner 完全一致。
            if (fromUnknown && !toUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.from().getX(), connection.from().getZ());
                if (village != null && endpointMatchesStructure(connection.to(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.from(), connection.to(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.to().getX(), connection.to().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            } else if (toUnknown && !fromUnknown) {
                StructureOwnerKey village = snapshot.villageOwnerAt(
                        connection.to().getX(), connection.to().getZ());
                if (village != null && endpointMatchesStructure(connection.from(), village, knownInfos)) {
                    matched = connection;
                    logFilteredVillageEndpoint(village, connection.to(), connection.from(), connection);
                    if (village.equals(snapshot.villageOwnerAt(
                            connection.from().getX(), connection.from().getZ()))) {
                        villageInternal.add(connection);
                    }
                }
            }
            if (matched == null) kept.add(connection);
            else filtered.add(matched);
        }
        if (filtered.isEmpty()) {
            return new EndpointFilterOutcome(kept, List.of(), false, "");
        }
        // 安全阀一：一轮过滤太多就整体放弃。
        if (filtered.size() > UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=too_many_per_round",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "too_many_per_round");
        }
        // 安全阀二：过滤后某端点变成 0 连接就整体放弃。村内连接（两端同一村庄盒）
        // 不参与该判定 —— 它本来就不是"这个村庄通往别处的路"。
        if (leavesEndpointWithoutConnection(kept, filtered, villageInternal)) {
            LOGGER.warn("RoadWeaver route filter held back: threshold exceeded filtered={} kept={} reason=endpoint_left_without_connection",
                    filtered.size(), kept.size());
            return new EndpointFilterOutcome(connections, List.of(), true, "endpoint_left_without_connection");
        }
        return new EndpointFilterOutcome(kept, List.copyOf(filtered), false, "");
    }

    static void logFilteredVillageEndpoint(StructureOwnerKey village,
                                                   BlockPos unknown,
                                                   BlockPos villageEndpoint,
                                                   StructureConnection connection) {
        LOGGER.info("RoadWeaver route filtered: village {} owns endpoint, unknown endpoint inside its box "
                        + "village={} unknown={} connection={}->{}",
                village.structureId(), villageEndpoint, unknown, connection.from(), connection.to());
    }

    /**
     * 端点是否"未知"：不在已核验结构位点。
     *
     * <p>0.8.312 修正：旧实现还要求 {@code villageOwnerAt(endpoint) == null}，于是
     * "落在村庄盒内的未知端点"——正是本过滤要处理的那一类——被判成"已知"，
     * 过滤器因此永不触发（真机 0.8.311 日志 {@code filtered=0}，失败路仍在）。
     * 未知的判定只看它是不是已核验结构位点；是否落在村庄盒内由调用方单独判定。</p>
     */
    static boolean isUnknownEndpoint(ServerLevel level,
                                              BlockPos endpoint,
                                              StructureFileStorage.StructureSnapshot storageSnapshot,
                                              PlanningSnapshot snapshot) {
        if (endpoint == null) return false;
        List<StructureInfo> infos = storageSnapshot == null || storageSnapshot.locations() == null
                ? List.of()
                : storageSnapshot.locations().structureInfos();
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() == endpoint.getX() && info.pos().getZ() == endpoint.getZ()) return false;
        }
        return true;
    }

    /**
     * 0.8.317：端点是否**就是**该 owner 对应的已核验结构位点。
     *
     * <p>比 {@code snapshot.ownsEndpoint(owner)} 严格：后者是"两个端点 owner 的并集里
     * 有它就返回 true"，而未知端点自己也会因为"从附近建筑推断 owner"而进入并集，于是
     * 别的村庄通往同一个未知点的正常路会被误判。这里直接核对端点区块与结构 id。</p>
     */
    static boolean endpointMatchesStructure(BlockPos endpoint,
                                                     StructureOwnerKey owner,
                                                     List<StructureInfo> knownInfos) {
        if (endpoint == null || owner == null || knownInfos == null || knownInfos.isEmpty()) return false;
        if (ChunkPos.asLong(endpoint.getX() >> 4, endpoint.getZ() >> 4) != owner.startChunkKey()) return false;
        for (StructureInfo info : knownInfos) {
            if (info == null || info.pos() == null || !StructureInfo.isKnownId(info.structureId())) continue;
            if (info.pos().getX() != endpoint.getX() || info.pos().getZ() != endpoint.getZ()) continue;
            return owner.structureId().equals(info.structureId());
        }
        return false;
    }

    /** 过滤后是否有端点从"有连接"变成"0 连接"。 */
    static boolean leavesEndpointWithoutConnection(List<StructureConnection> kept,
                                                           List<StructureConnection> filtered,
                                                           Set<StructureConnection> exempt) {
        Map<Long, Integer> keptCounts = new HashMap<>();
        for (StructureConnection connection : kept) {
            if (connection == null) continue;
            keptCounts.merge(PlanningUtils.pos2dKey(connection.from()), 1, Integer::sum);
            keptCounts.merge(PlanningUtils.pos2dKey(connection.to()), 1, Integer::sum);
        }
        for (StructureConnection connection : filtered) {
            if (connection == null) continue;
            if (exempt != null && exempt.contains(connection)) continue;
            for (BlockPos endpoint : List.of(connection.from(), connection.to())) {
                if (endpoint == null) continue;
                if (keptCounts.getOrDefault(PlanningUtils.pos2dKey(endpoint), 0) == 0) return true;
            }
        }
        return false;
    }

    static CompletableFuture<Void> preparePlanningSnapshotsOnServerThread(
            ServerLevel level, Collection<StructureConnection> connections,
            PrepareTiming timing) {
        var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Structure snapshots must be submitted on the server thread"));
        }
        if (timing != null) timing.markRan();

        long snapshotStartedAt = System.nanoTime();
        long predictedBefore = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get();
        long manualBefore = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get();
        long cacheBefore = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get();
        long emptyBefore = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get();
        long boxesBefore = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get();
        long tilesBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get();
        long predictionChunksBefore = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get();
        long tileCacheBefore = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get();
        long candidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get();
        long corridorCandidatesBefore = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get();
        long directStartsBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get();
        long directFallbacksBefore = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get();
        boolean trackInitialProgress = InitialGenManager.isActive();
        if (trackInitialProgress) {
            InitialGenerationProgressTracker.enterStage(
                    InitialGenerationStage.PLANNING, "predicting_structure_obstacles");
        }
        StructureFileStorage.StructureSnapshot storageSnapshot = StructureFileStorage.getStructureSnapshot(level);
        long epoch = ThreadPoolManager.currentEpoch();
        ThreadPoolManager.TaskRole predictionRole = trackInitialProgress
                ? ThreadPoolManager.TaskRole.INITIAL
                : ThreadPoolManager.TaskRole.STRUCTURE;
        // 0.9.059（①）：服务器线程上的前缀段（取快照 + 取 epoch），不计入下面任何子段。
        if (timing != null) timing.addServerInit(System.nanoTime() - snapshotStartedAt);
        return ThreadPoolManager.supplyAsync(predictionRole, () -> {
            long predictionStartedAt = System.nanoTime();
            if (!ThreadPoolManager.isEpoch(epoch)) {
                throw new java.util.concurrent.CancellationException("RoadWeaver world changed before structure prediction");
            }
            long tileSampleStartedAt = System.nanoTime();
            Map<RouteKey, List<StructurePredictor.AvoidanceStructureCandidate>> obstacleCandidates =
                    predictObstacleCandidates(level, connections);
            if (timing != null) timing.addTileSample(System.nanoTime() - tileSampleStartedAt);
            long startKeyStartedAt = System.nanoTime();
            Map<RouteKey, RouteStartPlan> routePlans = new LinkedHashMap<>();
            Set<PreparedStartKey> uniqueStarts = new LinkedHashSet<>();
            for (StructureConnection connection : connections) {
                if (connection == null) continue;
                RouteKey key = routeKey(level, connection);
                if (StructureAvoidanceCache.PLANNING_SNAPSHOTS.containsKey(key)) continue;
                Set<PreparedStartKey> starts = collectPlanningStartKeys(
                        level, connection, storageSnapshot, obstacleCandidates.getOrDefault(key, List.of()));
                routePlans.put(key, new RouteStartPlan(connection, starts, Set.of()));
                uniqueStarts.addAll(starts);
            }
            if (timing != null) {
                timing.addStartKeys(System.nanoTime() - startKeyStartedAt);
                timing.setRoutes(routePlans.size());
                timing.setStarts(uniqueStarts.size());
            }
            SnapshotPreparation preparation = new SnapshotPreparation(routePlans, List.copyOf(uniqueStarts));
            LOGGER.debug("RoadWeaver structure prediction timing: dimension={} routes={} uniqueStarts={} elapsedMs={}",
                    level.dimension().location(), routePlans.size(), uniqueStarts.size(),
                    elapsedMillis(predictionStartedAt));
            return preparation;
        }).thenCompose(preparation -> {
            if (!ThreadPoolManager.isEpoch(epoch)) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                        "RoadWeaver world changed before structure snapshot loading"));
            }
            if (trackInitialProgress) {
                InitialGenerationProgressTracker.setPlanningStartPlan(
                        preparation.uniqueStarts().size(), "loading_exact_structure_starts");
            }
            long resolutionStartedAt = System.nanoTime();
            return resolvePreparedStartsAsync(level, preparation.uniqueStarts(), trackInitialProgress, timing)
                    .thenAccept(resolvedStarts -> {
                if (!ThreadPoolManager.isEpoch(epoch)) return;
                // 0.9.059（①）：结构占用叠加段 = 精确起点解析整段。
                if (timing != null) timing.addStructureOverlay(System.nanoTime() - resolutionStartedAt);
                long cacheWriteStartedAt = System.nanoTime();
                for (Map.Entry<RouteKey, RouteStartPlan> entry : preparation.routePlans().entrySet()) {
                    Set<OwnedStructureBox> boxes = new HashSet<>();
                    for (PreparedStartKey start : entry.getValue().starts()) {
                        List<OwnedStructureBox> prepared = resolvedStarts.get(start);
                        if (prepared != null) boxes.addAll(prepared);
                    }
                    indexConstructionBoxes(boxes);
                    Set<StructureOwnerKey> endpointOwners = resolveEndpointOwners(
                            entry.getValue().connection(), storageSnapshot, boxes);
                    PlanningSnapshot snapshot = PlanningSnapshot.forRoute(
                            entry.getValue().connection(), boxes, entry.getValue().starts(), Set.of(), endpointOwners);
                    StructureAvoidanceCache.ROUTE_ENDPOINT_OWNERS.put(entry.getKey(), snapshot.endpointOwners());
                    putBounded(StructureAvoidanceCache.PLANNING_SNAPSHOTS, StructureAvoidanceCache.PLANNING_SNAPSHOT_ORDER, entry.getKey(), snapshot,
                            PLANNING_SNAPSHOT_CACHE_CAPACITY);
                }
                if (trackInitialProgress) {
                    InitialGenerationProgressTracker.completePlanningSnapshots("structure_snapshots_ready");
                }
                // 0.9.059（①）：写缓存段 = 合并进 StructureAvoidanceCache.PLANNING_SNAPSHOTS + 施工盒索引 + 有界淘汰。
                if (timing != null) {
                    timing.addCacheWrite(System.nanoTime() - cacheWriteStartedAt);
                    timing.observeWorkerThread();
                }

                long predicted = StructureAvoidanceDiagnostics.PREDICTED_DIRECT_LOOKUPS.get() - predictedBefore;
                long manual = StructureAvoidanceDiagnostics.MANUAL_REFERENCE_LOOKUPS.get() - manualBefore;
                long cacheHits = StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS.get() - cacheBefore;
                long empty = StructureAvoidanceDiagnostics.PREPARED_EMPTY_RESULTS.get() - emptyBefore;
                long boxes = StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES.get() - boxesBefore;
                long tiles = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_TILES.get() - tilesBefore;
                long predictionChunks = StructureAvoidanceDiagnostics.OBSTACLE_PREDICTION_CHUNKS.get() - predictionChunksBefore;
                long tileCacheHits = StructureAvoidanceDiagnostics.OBSTACLE_TILE_CACHE_HITS.get() - tileCacheBefore;
                long candidates = StructureAvoidanceDiagnostics.OBSTACLE_CANDIDATES.get() - candidatesBefore;
                long corridorCandidates = StructureAvoidanceDiagnostics.OBSTACLE_CORRIDOR_CANDIDATES.get() - corridorCandidatesBefore;
                long directStarts = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_STARTS.get() - directStartsBefore;
                long directFallbacks = StructureAvoidanceDiagnostics.DIRECT_STRUCTURE_FALLBACKS.get() - directFallbacksBefore;
                if (timing != null) {
                    timing.setObstacleTiles((int) Math.min(Integer.MAX_VALUE, tiles));
                    timing.setResolvedBoxes((int) Math.min(Integer.MAX_VALUE, boxes));
                }
                if (predicted + manual + cacheHits + tiles + tileCacheHits > 0L) {
                    LOGGER.info("RoadWeaver structure snapshots: obstacleTiles={} predictionChunks={} obstacleTileCacheHits={} obstacleCandidates={} corridorCandidates={} predictedDirect={} nativeDirectStarts={} realChunkFallbacks={} manualReferenceFallback={} cacheHits={} emptyResults={} exactPieceBoxes={} prefilterSkipped={} prefilterKept={} prefilterEnabled={}",
                            tiles, predictionChunks, tileCacheHits, candidates, corridorCandidates,
                            predicted, directStarts, directFallbacks,
                            manual, cacheHits, empty, boxes,
                            PREFILTERED_CANDIDATES.get(), PREFILTER_KEPT_CANDIDATES.get(),
                            STRUCTURE_PREFILTER_ENABLED.get());
                }
                LOGGER.info("RoadWeaver structure snapshot timing: dimension={} routes={} uniqueStarts={} resolutionMs={} totalMs={}",
                        level.dimension().location(), preparation.routePlans().size(),
                        preparation.uniqueStarts().size(), elapsedMillis(resolutionStartedAt),
                        elapsedMillis(snapshotStartedAt));
                // 0.9.034（R-07 计数模式）：累计值（进程内），不改任何结果。
                // wouldRejectNonEmpty 就是「开群系预筛会丢掉的真实结构数」—— 必须是 0 才算可证明安全。
                LOGGER.info("RoadWeaver structure biome-prefilter probe (count only, results unchanged): "
                                + "probed={} wouldReject={} wouldRejectNonEmpty={} emptyResults={}",
                        StructureAvoidanceDiagnostics.BIOME_PROBE_PROBED.get(), StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED.get(),
                        StructureAvoidanceDiagnostics.BIOME_PROBE_REJECTED_NONEMPTY.get(), empty);
            });
        });
    }

    /** Activates the immutable snapshot for the current pathfinding worker. */
    public static PlanningScope activatePlanningSnapshot(ServerLevel level, StructureConnection connection) {
        return activatePlanningSnapshot(level, connection, 0);
    }

    /**
     * Activates the immutable route snapshot with a temporary extra footprint
     * radius. This is used only after exact post-processing proves that the
     * rasterized road is one column wider than the center-path approximation.
     */
    public static PlanningScope activatePlanningSnapshot(ServerLevel level,
                                                         StructureConnection connection,
                                                         int additionalRoadRadius) {
        PlanningSnapshot previous = StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.get();
        PlanningSnapshot selected = level == null || connection == null
                ? PlanningSnapshot.EMPTY
                : StructureAvoidanceCache.PLANNING_SNAPSHOTS.getOrDefault(routeKey(level, connection), PlanningSnapshot.EMPTY);
        if (additionalRoadRadius > 0) {
            selected = selected.withAdditionalRoadRadius(additionalRoadRadius);
        }
        StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(selected);
        return () -> {
            if (previous == null) StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.remove();
            else StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(previous);
        };
    }

    /** Returns true only for a verified structure piece intersecting the road construction band.
     * <p>2026-11-15：实现搬到 {@link StructureAvoidanceCache}（本类贴线）；快照缺失会记警告。 */
    public static boolean shouldAvoidPlanning(BlockPos pos) {
        return StructureAvoidanceCache.shouldAvoidPlanning(pos);
    }

    /** Checks a planning position with an explicit horizontal safety margin. */
    public static boolean shouldAvoidPlanning(BlockPos pos, int horizontalClearance) {
        return StructureAvoidanceCache.shouldAvoidPlanning(pos, horizontalClearance);
    }

    /**
     * 规划期建筑障碍强度。
     *
     * <p>{@link #PENALTY} 是历史行为：已验证建筑在 A* 里只是高代价，可以穿过，
     * 剩下的冲突交给局部绕行/保护缺口兜底。{@link #HARD_BUILDINGS} 下 A* 不允许
     * 穿过已验证建筑；{@link #HARD_BUILDINGS_AND_CLUSTERS} 还会把"村庄群"
     * （同一结构的多栋建筑聚合成一个包围盒）整片挡掉。</p>
     *
     * <p>硬障碍必须配合"搜索失败自动降级"使用，否则可能整条路无解。</p>
     */
    /** 已打过「端点 owner 重映射/推断」日志的端点（0.9.023 / N-01 同族去重）。 */
    static final java.util.Set<String> ENDPOINT_OWNER_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public enum BuildingObstacleMode { PENALTY, HARD_BUILDINGS, HARD_BUILDINGS_AND_CLUSTERS }

    static final ThreadLocal<BuildingObstacleMode> BUILDING_OBSTACLE_MODE =
            ThreadLocal.withInitial(() -> BuildingObstacleMode.PENALTY);

    /** 可 try-with-resources 的作用域句柄。 */
    public interface BuildingObstacleScope extends AutoCloseable {
        @Override
        void close();
    }

    /** Validates spline/raster post-processing against the same immutable obstacle snapshot. */
    public static PlanningSnapshot requirePlanningSnapshot(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null) {
            throw new PlanningSnapshotUnavailableException("Missing level or structure connection");
        }
        RouteKey key = routeKey(level, connection);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
            throw new PlanningSnapshotUnavailableException(
                    "No complete structure snapshot for route " + connection.from() + " -> " + connection.to());
        }
        return snapshot;
    }

    public static PlanningSnapshot requirePlanningSnapshot(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) {
            throw new PlanningSnapshotUnavailableException("Road has no persistent endpoint owners");
        }
        RouteKey key = routeKey(level, road);
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(key);
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) {
            throw new PlanningSnapshotUnavailableException(
                    "No complete structure snapshot for finalized road owners="
                            + road.ownerA2dKey() + " -> " + road.ownerB2dKey());
        }
        return snapshot;
    }

    public static void forceVerticalObstacle(ServerLevel level,
                                      StructureConnection connection,
                                      StructureOwnerKey owner) {
        if (level == null || connection == null || owner == null) return;
        RouteKey key = routeKey(level, connection);
        StructureAvoidanceCache.PLANNING_SNAPSHOTS.computeIfPresent(key, (ignored, snapshot) -> snapshot.forceVerticalOwner(owner));
    }

    /**
     * Final construction validation uses the full road shoulder volume, so it
     * can find a building block that the centre-line pass did not sample. The
     * caller may convert that internal station into a protected gap, but only
     * for a verified template building and never for a fallback box or an
     * endpoint target.
     */
    public static boolean isVerifiedBuildingCollision(ServerLevel level,
                                                      StructureConnection connection,
                                                      StructureOwnerKey owner,
                                                      BlockPos position,
                                                      int minY,
                                                      int maxY) {
        if (level == null || connection == null || owner == null || position == null) return false;
        PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
        if (snapshot == null || snapshot == PlanningSnapshot.EMPTY) return false;
        OwnedStructureBox owned = snapshot.findCollision(position.getX(), position.getZ(), minY, maxY);
        return owned != null
                && owner.equals(owned.owner())
                && isVerifiedBuilding(owned)
                && !snapshot.ownsEndpoint(owner);
    }


    static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static <T> CompletableFuture<T> callOnServerThread(ServerLevel level, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        var server = level == null ? null : level.getServer();
        if (server == null) {
            result.completeExceptionally(new IllegalStateException("Missing server while preparing structure starts"));
            return result;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        Runnable task = () -> {
            try {
                if (!ThreadPoolManager.isEpoch(epoch)) {
                    result.completeExceptionally(new CancellationException(
                            "RoadWeaver world epoch changed before structure main-thread work"));
                    return;
                }
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (server.isSameThread()) task.run();
        else server.execute(task);
        return result;
    }

    static int normalizeSource(int source) {
        return source == StructureFileStorage.SOURCE_MANUAL
                ? StructureFileStorage.SOURCE_MANUAL
                : StructureFileStorage.SOURCE_PREDICTED;
    }

    static PreparedStartKey preparedStartKey(ServerLevel level,
                                                     int cx,
                                                     int cz,
                                                     int source,
                                                     Holder<StructureSet> structureSet) {
        return new PreparedStartKey(
                level.dimension().location().toString(),
                level.getSeed(),
                ChunkPos.asLong(cx, cz),
                normalizeSource(source),
                structureSet);
    }

    static void indexConstructionBoxes(Collection<OwnedStructureBox> boxes) {
        if (boxes == null || boxes.isEmpty()) return;
        for (OwnedStructureBox owned : boxes) {
            if (owned == null || owned.box() == null) continue;
            BoundingBox box = owned.box();
            int minChunkX = box.minX() >> 4;
            int maxChunkX = box.maxX() >> 4;
            int minChunkZ = box.minZ() >> 4;
            int maxChunkZ = box.maxZ() >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    synchronized (StructureAvoidanceCache.CHUNK_BBOX_CACHE) {
                        ChunkEntry existing = StructureAvoidanceCache.CHUNK_BBOX_CACHE.get(key);
                        Set<OwnedStructureBox> merged = new HashSet<>();
                        if (existing != null && existing.ready) merged.addAll(existing.boxes);
                        merged.add(owned);
                        StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Set.copyOf(merged), true));
                    }
                }
            }
        }
    }

    static long distanceSquaredToSegment(int px, int pz, BlockPos start, BlockPos end) {
        long dx = (long) end.getX() - start.getX();
        long dz = (long) end.getZ() - start.getZ();
        if (dx == 0L && dz == 0L) {
            long ox = px - start.getX(), oz = pz - start.getZ();
            return ox * ox + oz * oz;
        }
        double t = ((px - start.getX()) * (double) dx + (pz - start.getZ()) * (double) dz)
                / (double) (dx * dx + dz * dz);
        t = Math.max(0D, Math.min(1D, t));
        double ox = px - (start.getX() + t * dx);
        double oz = pz - (start.getZ() + t * dz);
        return (long) Math.ceil(ox * ox + oz * oz);
    }

    static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    static RouteKey routeKey(ServerLevel level, StructureConnection connection) {
        long from = new BlockPos(connection.from().getX(), 0, connection.from().getZ()).asLong();
        long to = new BlockPos(connection.to().getX(), 0, connection.to().getZ()).asLong();
        return from <= to
                ? new RouteKey(level.dimension().location().toString(), level.getSeed(), from, to)
                : new RouteKey(level.dimension().location().toString(), level.getSeed(), to, from);
    }

    static RouteKey routeKey(ServerLevel level, RoadData road) {
        if (level == null || road == null || !road.hasOwnerPair()) {
            return new RouteKey("", 0L, Long.MIN_VALUE, Long.MIN_VALUE);
        }
        BlockPos from = PlanningUtils.posFrom2dKey(road.ownerA2dKey());
        BlockPos to = PlanningUtils.posFrom2dKey(road.ownerB2dKey());
        long first = new BlockPos(from.getX(), 0, from.getZ()).asLong();
        long second = new BlockPos(to.getX(), 0, to.getZ()).asLong();
        return first <= second
                ? new RouteKey(level.dimension().location().toString(), level.getSeed(), first, second)
                : new RouteKey(level.dimension().location().toString(), level.getSeed(), second, first);
    }


    static BoundingBox encapsulate(BoundingBox left, BoundingBox right) {
        return new BoundingBox(
                Math.min(left.minX(), right.minX()),
                Math.min(left.minY(), right.minY()),
                Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()),
                Math.max(left.maxY(), right.maxY()),
                Math.max(left.maxZ(), right.maxZ()));
    }

    static boolean containsHorizontal(BoundingBox box, BlockPos pos) {
        return box != null && pos != null
                && pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
    }

    static String endpointStructureFamily(String structureId) {
        if (structureId == null || structureId.isBlank()) return "";
        int namespace = structureId.indexOf(':');
        String path = namespace >= 0 ? structureId.substring(namespace + 1) : structureId;
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    static boolean isVillageStructure(String structureId) {
        return structureId != null && structureId.toLowerCase(java.util.Locale.ROOT).contains("village");
    }

    static long horizontalDistanceSquared(BoundingBox box, BlockPos pos) {
        if (box == null || pos == null) return Long.MAX_VALUE;
        long dx = pos.getX() < box.minX() ? (long) box.minX() - pos.getX()
                : pos.getX() > box.maxX() ? (long) pos.getX() - box.maxX() : 0L;
        long dz = pos.getZ() < box.minZ() ? (long) box.minZ() - pos.getZ()
                : pos.getZ() > box.maxZ() ? (long) pos.getZ() - box.maxZ() : 0L;
        return dx * dx + dz * dz;
    }

    static long horizontalDistanceSquared(BlockPos left, BlockPos right) {
        if (left == null || right == null) return Long.MAX_VALUE;
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    public interface PlanningScope extends AutoCloseable {
        @Override
        void close();
    }



    static void requestMainThreadPreload(ServerLevel level, int cx, int cz, long key) {
        StructureAvoidanceCache.CHUNK_BBOX_CACHE.putIfAbsent(key, ChunkEntry.UNREADY);
        RuntimePreloadKey preloadKey = new RuntimePreloadKey(
                level.dimension().location().toString(), level.getSeed(), key);
        if (StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.containsKey(preloadKey)) return;
        if (StructureAvoidanceDiagnostics.PENDING_REQUESTS.incrementAndGet() > MAX_PENDING_REQUESTS) {
            decrementPendingRequests();
            return;
        }
        var server = level.getServer();
        if (server == null) {
            decrementPendingRequests();
            return;
        }
        CompletableFuture<List<OwnedStructureBox>> shared = new CompletableFuture<>();
        CompletableFuture<List<OwnedStructureBox>> existing = StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.putIfAbsent(preloadKey, shared);
        if (existing != null) {
            decrementPendingRequests();
            return;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        resolveReferenceChunkBoxesAsync(level, cx, cz)
                .whenComplete((boxes, failure) -> {
                    try {
                        if (!ThreadPoolManager.isEpoch(epoch) || failure != null) {
                            StructureAvoidanceCache.CHUNK_BBOX_CACHE.remove(key);
                            if (failure != null) shared.completeExceptionally(failure);
                            else shared.completeExceptionally(new java.util.concurrent.CancellationException(
                                    "RoadWeaver world epoch changed while preloading " + new ChunkPos(cx, cz)));
                            return;
                        }
                        StructureAvoidanceCache.CHUNK_BBOX_CACHE.put(key, new ChunkEntry(Set.copyOf(boxes), true));
                        shared.complete(boxes);
                    } finally {
                        StructureAvoidanceCache.IN_FLIGHT_RUNTIME_PRELOADS.remove(preloadKey, shared);
                        decrementPendingRequests();
                    }
                });
    }

    static void decrementPendingRequests() {
        StructureAvoidanceDiagnostics.PENDING_REQUESTS.updateAndGet(value -> value <= 0L ? 0L : value - 1L);
    }

    static <K, V> V putIfAbsentBounded(ConcurrentMap<K, V> map,
                                                ConcurrentLinkedQueue<K> order,
                                                K key,
                                                V value,
                                                int capacity) {
        V existing = map.putIfAbsent(key, value);
        if (existing == null) {
            order.add(key);
            trimBounded(map, order, capacity);
        }
        return existing;
    }

    static <K, V> void putBounded(ConcurrentMap<K, V> map,
                                          ConcurrentLinkedQueue<K> order,
                                          K key,
                                          V value,
                                          int capacity) {
        V previous = map.put(key, value);
        if (previous == null) {
            order.add(key);
            trimBounded(map, order, capacity);
        }
    }

    static <K, V> void trimBounded(ConcurrentMap<K, V> map,
                                           ConcurrentLinkedQueue<K> order,
                                           int capacity) {
        while (map.size() > capacity) {
            K eldest = order.poll();
            if (eldest == null) return;
            map.remove(eldest);
            // ★ 2026-09-26（WorkBuddy 报告 §6 的建议：先拿实测再定优化）：
            //   记录淘汰次数。**这是判断"是否需要更大缓存/持久化"的唯一直接依据** ——
            //   若淘汰数远小于解析数，说明容量充足、没有"反复重解析"，
            //   任何"加大缓存/持久化"的改动都不会有收益（反而有红线风险）。
            PREPARED_CACHE_EVICTIONS.incrementAndGet();
        }
    }

    /**
     * **缓存淘汰计数**（★ 2026-09-26 新增，用于回答 WorkBuddy 的
     * 「是否有重复解析」这个问题）。
     *
     * <p>WorkBuddy 报告 §6 建议"先做重复解析计数拿实测，再定 O-1/O-2"。
     * 本计数器与既有的 {@code StructureAvoidanceDiagnostics.PREPARED_CACHE_HITS}（命中）/
     * {@code StructureAvoidanceDiagnostics.PREPARED_EXACT_BOXES}（解析出的 box 数）配成一对：</p>
     * <pre>
     *   命中高 + 淘汰低  ⇒ 容量充足，缓存有效，无需优化
     *   淘汰高          ⇒ 容量不足导致"解析 → 淘汰 → 再解析"，才值得加大/持久化
     * </pre>
     */
    static final AtomicLong PREPARED_CACHE_EVICTIONS = new AtomicLong(0);

    /**
     * A road only avoids a structure when its actual construction corridor
     * overlaps the piece.  Underground rooms and sky structures at the same
     * X/Z no longer create a false road gap.
     */
    static boolean intersectsConstructionBand(OwnedStructureBox owned,
                                                      int x, int z, int minY, int maxY) {
        return owned != null && owned.intersects(x, z, minY, maxY, 0);
    }

    static boolean intersectsPlanningBand(OwnedStructureBox owned,
                                                  int x,
                                                  int z,
                                                  int minY,
                                                  int maxY,
                                                  int horizontalMargin) {
        return owned != null && owned.intersects(x, z, minY, maxY, horizontalMargin);
    }
}
