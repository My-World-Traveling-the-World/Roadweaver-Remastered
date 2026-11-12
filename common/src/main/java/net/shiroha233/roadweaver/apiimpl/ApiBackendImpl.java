package net.shiroha233.roadweaver.apiimpl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.RoadWeaverBackend;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleListener;
import net.shiroha233.roadweaver.api.incoming.ReadinessState;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.StructureLocationData;
import net.shiroha233.roadweaver.generation.ConnectionDeletionService;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RoadWeaverBackend} 在 RoadWeaver 侧的唯一实现。
 *
 * <h2>职责</h2>
 * <p>把 {@code roadweaver-api} 模块的门面调用转发到本仓的真实实现。api 模块<b>不认识</b>
 * 这里的任何类 —— 它只认识 {@link RoadWeaverBackend} 接口，本类在 mod 初始化时注入
 * （见 fabric 模块 {@code RoadWeaver#onInitialize}）。</p>
 *
 * <h2>网络面逻辑的来源</h2>
 * <p>{@link #registerStructureEndpoint} / {@link #ensureConnection} / {@link #planRegion}
 * 的方法体<b>逐行搬自</b>原先 {@code common/.../api/RoadNetworkApi.java}（该文件已删除）。
 * 搬动的目的只是让契约与实现分家：那个类原本直接 import 了 12 个内部实现类，
 * 是「契约与实现交织」的典型，也是历史上 {@code api.interop} 被整体删除时
 * 连带外部 mod 一起失效的结构性原因。行为没有任何变化。</p>
 */
public final class ApiBackendImpl implements RoadWeaverBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/api");

    /** 单例。由 mod 初始化注入到 {@link RoadWeaverApi#installBackend}。 */
    public static final ApiBackendImpl INSTANCE = new ApiBackendImpl();

    private static final Set<String> CAPABILITIES = java.util.Set.of(
            RoadWeaverApi.CAP_STRUCTURE_RESERVATION,
            RoadWeaverApi.CAP_PLANNING_LIFECYCLE,
            RoadWeaverApi.CAP_PLANNING_READINESS,
            RoadWeaverApi.CAP_LOAD_SIGNALS);

    private final CopyOnWriteArrayList<PlanningLifecycleListener> listeners = new CopyOnWriteArrayList<>();

    private ApiBackendImpl() {
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    // ── 结构预留 ─────────────────────────────────────────────────────────

    @Override
    public boolean reserveStructure(ServerLevel level, String structureId, BlockPos center,
                                    int[] rotationMirrored, int fallenDirection) {
        if (level == null || center == null) {
            return false;
        }
        HugeTreeReservation.reserve(level, structureId, center, rotationMirrored, fallenDirection);
        return true;
    }

    @Override
    public boolean isReserved(ServerLevel level, BlockPos pos) {
        return level != null && pos != null && HugeTreeReservation.isReserved(level, pos);
    }

    @Override
    public void releaseChunk(ServerLevel level, ChunkPos chunk) {
        if (level != null && chunk != null) {
            HugeTreeReservation.releaseChunk(level, chunk);
        }
    }

    @Override
    public void clearReservationsForLevel(ServerLevel level) {
        if (level != null) {
            HugeTreeReservation.clearForLevel(level);
        }
    }

    @Override
    public void clearAllReservations() {
        HugeTreeReservation.clearAll();
    }

    // ── 规划生命周期 ─────────────────────────────────────────────────────

    @Override
    public void addLifecycleListener(PlanningLifecycleListener listener) {
        if (listener != null) {
            listeners.add(listener);
            LOGGER.info("RoadWeaver api: lifecycle listener registered (total={})",
                    listeners.size());
        }
    }

    @Override
    public void removeLifecycleListener(PlanningLifecycleListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 触发「河流预热完成」。由道路内部的河流预热阶段在结束时调用。
     *
     * <p>单个监听器抛异常只记日志并继续 —— 外部 mod 的 bug 不能拖垮道路生成。</p>
     */
    public static void fireRiversPrewarmed() {
        for (PlanningLifecycleListener l : INSTANCE.listeners) {
            try {
                l.onRiversPrewarmed();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("RoadWeaver api: onRiversPrewarmed listener failed: {}", e.toString());
            }
        }
    }

    /** 触发「初始生成开始」。 */
    public static void fireInitialGenerationStart() {
        for (PlanningLifecycleListener l : INSTANCE.listeners) {
            try {
                l.onInitialGenerationStart();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("RoadWeaver api: onInitialGenerationStart listener failed: {}", e.toString());
            }
        }
    }

    /** 触发「初始生成结束」。 */
    public static void fireInitialGenerationEnd(int requested, int built, int failed, long elapsedMillis) {
        PlanningLifecycleListener.InitialGenerationSummary summary =
                new PlanningLifecycleListener.InitialGenerationSummary(requested, built, failed, elapsedMillis);
        for (PlanningLifecycleListener l : INSTANCE.listeners) {
            try {
                l.onInitialGenerationEnd(summary);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("RoadWeaver api: onInitialGenerationEnd listener failed: {}", e.toString());
            }
        }
    }

    // ── 规划就绪 ─────────────────────────────────────────────────────────

    @Override
    public ReadinessState requestRegion(ServerLevel level, int regionX, int regionZ) {
        return mapState(RoadPlanningReadinessIndex.requestRegion(level, regionX, regionZ));
    }

    @Override
    public ReadinessState regionState(ServerLevel level, int regionX, int regionZ) {
        return mapState(RoadPlanningReadinessIndex.regionState(level, regionX, regionZ));
    }

    @Override
    public String progressSummary(ServerLevel level) {
        return RoadPlanningReadinessIndex.progressSummary(level);
    }

    @Override
    public void setChunkyPreflightActive(boolean active) {
        RoadPlanningReadinessIndex.setChunkyPreflightActive(active);
    }

    @Override
    public void setChunkyExclusiveActive(boolean active) {
        RoadPlanningReadinessIndex.setChunkyExclusiveActive(active);
    }

    @Override
    public void markRegionDispatched(ServerLevel level, int regionX, int regionZ) {
        RoadPlanningReadinessIndex.markRegionDispatched(level, regionX, regionZ);
    }

    /**
     * 内部 {@code State} 字符串 → {@link ReadinessState}。
     *
     * <p><b>FAILED 映射成 UNKNOWN 而不是 BLOCKED</b>：BLOCKED 会让协调器无限等待，
     * 而 UNKNOWN 的语义是「按不阻塞处理」。一条路规划失败不应该把整个区域的
     * 区块预生成永久卡住。</p>
     */
    private static ReadinessState mapState(String state) {
        if (state == null || state.isEmpty()) {
            return ReadinessState.UNKNOWN;
        }
        return switch (state) {
            case "READY" -> ReadinessState.READY;
            case "QUEUED", "UNPLANNED" -> ReadinessState.NOT_STARTED;
            case "PLANNING" -> ReadinessState.IN_PROGRESS;
            default -> ReadinessState.UNKNOWN;
        };
    }

    // ── 路网操作（逻辑逐行搬自原 RoadNetworkApi）──────────────────────────

    @Override
    public void registerStructureEndpoint(ServerLevel level, BlockPos pos, String structureId,
                                          boolean autoConnect) {
        if (level == null || pos == null || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        WorldDataProvider provider = WorldDataProvider.getInstance();
        StructureLocationData existing = provider.getStructureLocations(level);
        List<BlockPos> locations = existing != null ? new ArrayList<>(existing.structureLocations())
                : new ArrayList<>();
        List<StructureInfo> infos = existing != null ? new ArrayList<>(existing.structureInfos())
                : new ArrayList<>();

        if (structureId != null && !structureId.isEmpty()) {
            StructureInfo info = new StructureInfo(pos, structureId);
            infos.add(info);
            if (!locations.contains(pos)) {
                locations.add(pos);
            }
        } else {
            if (!locations.contains(pos)) {
                locations.add(pos);
            }
        }

        StructureLocationData updated = new StructureLocationData(locations, infos);
        provider.setStructureLocations(level, updated);

        String id = (structureId != null && !structureId.isEmpty()) ? structureId : "unknown";
        StructureFileStorage.addStructures(
                level,
                java.util.List.of(new StructureInfo(new BlockPos(pos.getX(), 0, pos.getZ()), id)),
                StructureFileStorage.SOURCE_MANUAL
        );
    }

    @Override
    public void ensureConnection(ServerLevel level, BlockPos from, BlockPos to, boolean generateImmediately) {
        if (level == null || from == null || to == null || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        if (from.equals(to)) {
            return;
        }

        ConnectionDeletionService.allow(level, from, to);

        WorldDataProvider provider = WorldDataProvider.getInstance();
        StructureLocationData existing = provider.getStructureLocations(level);
        List<BlockPos> locations = existing != null ? new ArrayList<>(existing.structureLocations())
                : new ArrayList<>();
        List<StructureInfo> infos = existing != null ? new ArrayList<>(existing.structureInfos())
                : new ArrayList<>();

        boolean changed = false;
        if (!locations.contains(from)) {
            locations.add(from);
            changed = true;
        }
        if (!locations.contains(to)) {
            locations.add(to);
            changed = true;
        }
        if (changed) {
            StructureLocationData updated = new StructureLocationData(locations, infos);
            provider.setStructureLocations(level, updated);
        }

        StructureFileStorage.addStructures(
                level,
                java.util.List.of(
                        new StructureInfo(new BlockPos(from.getX(), 0, from.getZ()), "unknown"),
                        new StructureInfo(new BlockPos(to.getX(), 0, to.getZ()), "unknown")
                ),
                StructureFileStorage.SOURCE_MANUAL
        );

        List<StructureConnection> existingConns = provider.getStructureConnections(level);
        List<StructureConnection> list = existingConns != null ? new ArrayList<>(existingConns)
                : new ArrayList<>();
        boolean exists = false;
        for (StructureConnection c : list) {
            if (sameEdge(c, from, to)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            StructureConnection connection = new StructureConnection(from, to, ConnectionStatus.PLANNED);
            list.add(connection);
            provider.setStructureConnections(level, list);
            MapPatchService.publishConnection(level, connection);
        }

        if (generateImmediately) {
            StructureConnection conn = new StructureConnection(from, to, ConnectionStatus.GENERATING);
            List<StructureConnection> currentList = provider.getStructureConnections(level);
            List<StructureConnection> all = currentList != null ? new ArrayList<>(currentList)
                    : new ArrayList<>();
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                if (sameEdge(all.get(i), from, to)) {
                    all.set(i, conn);
                    found = true;
                    break;
                }
            }
            if (!found) {
                all.add(conn);
            }
            provider.setStructureConnections(level, all);
            RoadGenerationService.GenerationResult result = RoadGenerationService.generateTaskResult(level, conn);
            ConnectionStatus finalStatus = result.finalStatus();
            StructureConnection effective = result.effectiveConnection() == null ? conn
                    : result.effectiveConnection();
            StructureConnection finalConn = new StructureConnection(effective.from(), effective.to(), finalStatus);

            currentList = provider.getStructureConnections(level);
            all = currentList != null ? new ArrayList<>(currentList) : new ArrayList<>();
            found = false;
            for (int i = 0; i < all.size(); i++) {
                if (sameEdge(all.get(i), from, to)) {
                    all.set(i, finalConn);
                    found = true;
                    break;
                }
            }
            if (!found) {
                all.add(finalConn);
            }
            provider.setStructureConnections(level, all);
            if (sameEdge(finalConn, from, to)) {
                MapPatchService.publishConnection(level, finalConn);
            } else {
                MapPatchService.publishConnectionReplacement(level, conn, finalConn);
            }
            if (finalStatus == ConnectionStatus.COMPLETED || finalStatus == ConnectionStatus.PROTECTED_GAP) {
                RoadSnapService.snapAroundConnection(level, finalConn.from(), finalConn.to());
                MapPatchService.publishRoadForConnectionAsync(level, finalConn);
                if (result.road() != null) {
                    LoadedChunkRoadBackfill.placeLoadedRoad(level, result.road());
                }
            }
        }
    }

    @Override
    public void planRegion(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        RoadPlanningService.planRectAsync(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    private static boolean sameEdge(StructureConnection c, BlockPos a, BlockPos b) {
        BlockPos af = c.from();
        BlockPos at = c.to();
        return (af.equals(a) && at.equals(b)) || (af.equals(b) && at.equals(a));
    }

    /** 供诊断：外部注册了几个生命周期监听器。 */
    public static int listenerCount() {
        return INSTANCE.listeners.size();
    }

    /** 已注册监听器快照，供测试断言。 */
    public static List<PlanningLifecycleListener> listenersSnapshot() {
        return List.copyOf(INSTANCE.listeners);
    }

    /** 供测试重置。 */
    public static void clearListenersForTest() {
        INSTANCE.listeners.clear();
    }
}
