/* 文件职责：统一管理服务端、维度与地形采样相关缓存的生命周期。 */
package net.shiroha233.roadweaver.runtime;

import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.decoration.text.SignTextService;
import net.shiroha233.roadweaver.decoration.system.DecorationPlanner;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingStats;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLAccurateProgramCache;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLAvailability;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLCoarseHeightBatchSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLRuntime;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLWorldSupport;
import net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileCache;
import net.shiroha233.roadweaver.persistence.RoadSpatialIndex;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprintService;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;
import net.shiroha233.roadweaver.structures.precompute.PendingRoadsideVillageStorage;
import net.shiroha233.roadweaver.structures.precompute.PendingStructureStorage;
import net.shiroha233.roadweaver.structures.registry.BridgeTemplateStructureRegistry;
import net.shiroha233.roadweaver.structures.registry.RoadsideStructureRegistry;
import net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一缓存生命周期管理器。
 */
public final class CacheManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private CacheManager() {}

    /**
     * 服务端启动时初始化缓存状态。
     */
    public static void onServerStarted() {
        RoadsideStructureRegistry.clearCache();
        BridgeTemplateStructureRegistry.clearCache();
        RoadSpatialIndex.clearAllCache();
        TerrainSamplingStats.reset();
        AccurateSamplingStats.reset();
        OpenCLAvailability.reset();
        OpenCLCoarseHeightBatchSampler.clearProgramCache();
        OpenCLWorldSupport.clear();
        TerrainSamplingSessions.clearAll();
        AutomaticPlanningSamplingActivities.clearAll();
        RoadWorldgenPlanCache.clearAll();
        RoadTunnelBuilder.clearAll();
        RoadConstructionSafety.clearRuntimeBridges();
        HugeTreeReservation.clearAll();
        StructureFileStorage.resetSnapshotCache();
        LOGGER.debug("CacheManager: 缓存已初始化");
    }

    /**
     * Chunky 独占世界生成开始时，释放「可重建」的缓存，把内存与 CPU 让给 Chunky。
     *
     * <p>背景（2026-09-12 真机事故）：Chunky 启动时堆已达 78%（6664/8500 MiB），
     * 4 分钟后贴顶并触发硬内存闸门，cps 由 300+ 衰减到 0.9，卡死 29 分钟后 OOM。
     * 此前 Chunky 期只做了「暂停计算」，但暂停不等于释放 —— 已算出的缓存仍常驻。</p>
     *
     * <p><b>只清「有磁盘备份、能廉价重建」的规划侧缓存</b>；下列缓存一律<b>保持常驻</b>：</p>
     * <ul>
     *   <li>{@code RoadPlanningReadinessIndex} 的 ready 覆盖集 —— Chunky 施工依据，且体积极小；</li>
     *   <li>{@code StructureAvoidanceService} 的逐区块 bbox —— 重建必须加载区块
     *       （{@code ChunkStatus.STRUCTURE_REFERENCES}），会与 Chunky 抢资源，
     *       且取不到时保守返回「有结构」导致道路绕行降质；</li>
     *   <li>{@code RoadChunkStore} / 施工账本 / 结构位置 / 世界指纹 —— Chunky 期 worldgen 钩子
     *       仍在施工（实测 320 次调用、其中 71 次真施工），这些是它的数据源。</li>
     * </ul>
     *
     * @param level 当前维度；仅对主世界生效
     */
    public static void onChunkyBegin(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return;

        // 1) 先把在飞/待写的规划落盘，避免释放内存时丢掉尚未持久化的路径。
        //    PlannedPathCache.clearAll() 内含 5 秒有界等待，语义正是「先落盘再清」。
        try {
            PlannedPathCache.clearAll();
        } catch (Exception e) {
            LOGGER.warn("Chunky 让位：清理规划路径缓存失败: {}", e.getMessage());
        }

        try {
            CoarseTerrainTileCache.clearAll();
        } catch (Exception e) {
            LOGGER.warn("Chunky 让位：清理粗采样瓦片缓存失败: {}", e.getMessage());
        }

        try {
            AccurateHeightSampler.clearForLevel(level);
        } catch (Exception e) {
            LOGGER.warn("Chunky 让位：清理精采样缓存失败: {}", e.getMessage());
        }

        try {
            RoadTunnelBuilder.clearForLevel(level);
        } catch (Exception e) {
            LOGGER.warn("Chunky 让位：清理隧道/桥梁临时状态失败: {}", e.getMessage());
        }

        try {
            RoadWorldgenPlanCache.clear(level);
        } catch (Exception e) {
            LOGGER.warn("Chunky 让位：清理 worldgen 计划缓存失败: {}", e.getMessage());
        }

        // 2) 让 Streams 河流桥接的预热状态一并释放（它同样可从磁盘/引擎重建）。
        try {
            StreamsReflowingCompat.clearPrewarmState();
        } catch (Throwable e) {
            LOGGER.warn("Chunky 让位：清理 Streams 预热状态失败: {}", e.getMessage());
        }

        LOGGER.info("RoadWeaver Chunky 让位：已释放可重建缓存（规划路径/粗采样/精采样/隧道桥梁/worldgen 计划/Streams 预热）；"
                + "结构避让与施工账本保持常驻。");
    }

    /**
     * 服务端停止时清理所有缓存。
     */
    public static void onServerStopping(Iterable<ServerLevel> levels) {
        ServerLevel overworld = null;
        for (ServerLevel level : levels) {
            if (level != null && Level.OVERWORLD.equals(level.dimension())) {
                overworld = level;
                break;
            }
        }
        if (overworld != null) {
            try {
                SignTextService.flushPersistentFallback(overworld);
            } catch (Exception e) {
                LOGGER.warn("刷新 SignTextService 失败: {}", e.getMessage());
            }
            try {
                RoadShardStorage.flushAll(overworld);
            } catch (Exception e) {
                LOGGER.warn("刷新 RoadShardStorage 失败: {}", e.getMessage());
            }
            try {
                RoadConstructionLedger.flush(overworld);
            } catch (Exception e) {
                LOGGER.warn("刷新道路区块施工账本失败: {}", e.getMessage());
            }
        }

        RoadShardStorage.shutdown();
        RoadConstructionLedger.clearAll();
        net.shiroha233.roadweaver.compat.GreatWallRoadGuard.resetDiagnostics();
        WorldgenFingerprintService.clearAll();
        DecorationPlanner.clearPlacedLamps();
        RoadWorldgenPlanCache.clearAll();
        RoadTunnelBuilder.clearAll();
        RoadConstructionSafety.clearRuntimeBridges();
        HugeTreeReservation.clearAll();

        RoadsideStructureRegistry.clearCache();
        BridgeTemplateStructureRegistry.clearCache();
        PendingStructureStorage.clearAll();
        PendingRoadsideVillageStorage.clearAll();
        RoadSpatialIndex.clearAllCache();
        RoadPlanningService.resetAll();
        SignTextService.clearPending();
        TerrainSamplingStats.reset();
        TerrainSamplingSessions.clearAll();
        AccurateHeightSampler.clearAll();
        PlannedPathCache.clearAll();
        CoarseTerrainTileCache.clearAll();
        StructureFileStorage.resetSnapshotCache();
        OpenCLRuntime.closeAll();
        OpenCLWorldSupport.clear();

        LOGGER.debug("CacheManager: 所有缓存已清理");
    }

    /**
     * 维度卸载时清理该维度关联缓存。
     */
    public static void onDimensionUnload(ServerLevel level) {
        if (level == null) return;

        TerrainSamplingSessions.clear(level);
        AutomaticPlanningSamplingActivities.clear(level);
        AccurateHeightSampler.clearForLevel(level);
        WorldgenFingerprintService.clear(level);
        PlannedPathCache.clear(level);
        RoadWorldgenPlanCache.clear(level);
        RoadTunnelBuilder.clearForLevel(level);
        RoadConstructionSafety.clearRuntimeBridges();
        HugeTreeReservation.clearForLevel(level);
        try {
            OpenCLAccurateProgramCache.clear(level.getChunkSource().getGeneratorState().randomState());
        } catch (Throwable failure) {
            LOGGER.debug("清理维度 {} OpenCL 精采样程序失败", level.dimension().location(), failure);
        }
        if (!Level.OVERWORLD.equals(level.dimension())) return;

        try {
            SignTextService.flushPersistentFallback(level);
        } catch (Exception e) {
            LOGGER.warn("刷新维度 {} 路牌文本失败: {}",
                    level.dimension().location(), e.getMessage());
        }

        try {
            RoadConstructionLedger.close(level);
        } catch (Exception e) {
            LOGGER.warn("关闭维度 {} 道路区块施工账本失败: {}",
                    level.dimension().location(), e.getMessage());
        }

        try {
            RoadShardStorage.closeConnection(level);
        } catch (Exception e) {
            LOGGER.warn("关闭维度 {} 数据库连接失败: {}",
                    level.dimension().location(), e.getMessage());
        }

        RoadSpatialIndex.clearCache(level);
        PendingStructureStorage.clearAll();
        PendingRoadsideVillageStorage.clearAll();
        RoadsideStructureRegistry.clearCache();
        BridgeTemplateStructureRegistry.clearCache();
        SignTextService.onDimensionUnload(level);

        LOGGER.debug("CacheManager: 维度 {} 缓存已清理", level.dimension().location());
    }

    /**
     * 道路数据变更后使对应区块的空间索引缓存失效。
     */
    public static void invalidateRoadCache(ServerLevel level, int chunkX, int chunkZ) {
        RoadSpatialIndex.invalidateChunk(level, chunkX, chunkZ);
    }
}
