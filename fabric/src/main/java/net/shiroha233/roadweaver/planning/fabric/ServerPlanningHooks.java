/* 文件职责：注册 Fabric 服务端生命周期与 Tick 钩子。 */
package net.shiroha233.roadweaver.planning.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.shiroha233.roadweaver.config.structure.StructureDiscoveryService;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.decoration.text.SignTextService;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.generation.IdleRoadGenerationService;
import net.shiroha233.roadweaver.planning.PlanningBoundsOps;
import net.shiroha233.roadweaver.generation.RoadTaskExecutor;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.shiroha233.roadweaver.map.search.MapStructureSearchService;
import net.shiroha233.roadweaver.network.fabric.MapNetworkFabric;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;
import net.shiroha233.roadweaver.route.RouteChunkPreloader;
import net.shiroha233.roadweaver.runtime.CacheManager;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import net.shiroha233.roadweaver.runtime.RuntimePlayerActivity;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;

import java.util.List;

/**
 * Fabric 服务端规划钩子
 */
public final class ServerPlanningHooks {
    private ServerPlanningHooks() {}

    private static int tick;

    private static final TicketType<Unit> ROUTE_PRELOAD_TICKET =
            TicketType.create("roadweaver_route_preload", (left, right) -> 0);

    private static RouteChunkPreloader.ChunkRequest requestHandDrawnChunk(ServerLevel level, ChunkPos pos) {
        var source = level.getChunkSource();
        // 公共 getChunkFuture 在服务端线程会等待；复用已有 Mixin 端口直接提交请求。
        if (!(source instanceof NonBlockingChunkFutureAccess nonBlocking)) {
            throw new IllegalStateException("Nonblocking ServerChunkCache port unavailable");
        }
        source.addRegionTicket(ROUTE_PRELOAD_TICKET, pos, 0, Unit.INSTANCE);
        Runnable release = () -> source.removeRegionTicket(ROUTE_PRELOAD_TICKET, pos, 0, Unit.INSTANCE);
        try {
            return new RouteChunkPreloader.ChunkRequest(nonBlocking.roadweaver$invokeGetChunkFutureMainThread(
                    pos.x, pos.z, ChunkStatus.FULL, true), release);
        } catch (RuntimeException | LinkageError failure) {
            release.run();
            throw failure;
        }
    }

    /** 0.8.346：Chunky 独占转正时是否已释放过缓存（上升沿检测，避免每 tick 重复清理）。 */
    private static boolean chunkyCacheReleased;

    public static void register() {
        // 只有手绘路线走这个主动加载端口，延迟施工保持只消费已加载区块。
        RouteChunkPreloader.installChunkRequester(ServerPlanningHooks::requestHandDrawnChunk);
        // 玩家和预生成均可唤醒待施工区块；实际施工仍交给服务端 Tick 的预算。
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                DeferredInitialRoadConstructionService.onChunkLoaded(level, chunk.getPos().toLong()));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            RoadLifecycle.beginStarting();
            StreamsReflowingCompat.clearPrewarmState();
            MapNetworkFabric.clearRuntime();
            MapStructureSearchService.clearRuntime();
            RoadGenerationService.onServerStarting();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoadLifecycle.onServerStarted();
            CacheManager.onServerStarted();
            ThreadPoolManager.onServerStarted(server);
            RuntimePlayerActivity.reset();
            SignTextService.clearPending();
            DeferredInitialRoadConstructionService.clearAll();
            IdleRoadGenerationService.onServerStarted();
            
            ServerLevel level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return;

            StructureDiscoveryService.discoverFromLevel(level);
            // Roads and the construction ledger survive a restart, while the
            // in-memory deferred queue does not. Rebuild it after world load so
            // player-loaded or Chunky-loaded tunnel chunks are retried.
            DeferredInitialRoadConstructionService.recover(level);
            // Initial generation is owned by the prepareLevels mixin. Resetting
            // here would erase deferred paths and exact structure snapshots.
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RuntimePlayerActivity.observe(server);
            RuntimeLoadPolicy.tickGameplayPolicy();
            // ★ 审计 P1-3：Chunky 独占标志泄漏看门狗。
            // 该标志只有停服/重载世界时才被清除（RoadGenerationService:106/153），
            // Chunky 异常退出而未回调会让它永久残留 → 运行时规划无限期停摆。
            // 放在 tick 最前面：一旦自愈，本 tick 后续的规划判断就能立刻用上正确状态。
            RuntimeLoadPolicy.watchChunkyExclusiveLeak();
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            boolean pauseOverworldRuntime = overworld != null
                    && overworld.players().isEmpty()
                    && !InitialGenManager.isActive()
                    && !RuntimeLoadPolicy.isChunkyActive();
            RoadPlanningReadinessIndex.setRuntimePausedForDimension(pauseOverworldRuntime);
            RoadGenerationService.setRuntimePausedForDimension(pauseOverworldRuntime);
            IdleRoadGenerationService.setRuntimePausedForDimension(pauseOverworldRuntime);
            StreamsReflowingCompat.setPrewarmPausedForDimension(pauseOverworldRuntime);
            // 0.8.361：本块【整体】由每 20 tick（1 秒）放宽到每 1200 tick（60 秒）。
            //
            // 用户要求：「移动触发频率不要了这个 1 秒一次不要了」—— 保留
            // 「覆盖推进 60 秒节流」和「移动触发距离放大 4 倍」两项即可，
            // 这里连「每秒检查坐标」也一并去掉，彻底消除这条件里的周期开销。
            //
            // 代价与取舍（已知并接受）：玩家跑远后「立即重定规划中心」的响应
            // 由最坏 1 秒变为最坏 60 秒。窗口半径 256 区块远大于 followStep
            // 128 区块，因此跑远后仍处在已规划范围内，只是新区域的规划排队
            // 最多晚 1 分钟。
            //
            // 注：同块内的 SignTextService.onChunkReady 只是标记 chunk 脏位、
            // LoadedChunkRoadBackfill.placeLoadedPlayerChunks 自带「同区块 +
            // 重试窗口」短路（见其第 166 行），两者降到 60 秒后仍能正常工作。
            if ((tick++ % 1200) == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (Level.OVERWORLD.equals(p.serverLevel().dimension())) {
                        SignTextService.onChunkReady(p.serverLevel(), p.chunkPosition());
                        // T-20D: Chunky 批量渲染期间暂停主线程玩家回填(区块
                        // 预算与终态逻辑见 LoadedChunkRoadBackfill)。Chunky
                        // 前置规划(RoadPlanningReadinessIndex)与初始开局不经过
                        // 此处,不受影响;Chunky 结束后下一个周期自动恢复,仅
                        // 处理台账未完成的真正待施工 chunk。
                        if (!RuntimeLoadPolicy.isChunkyActive()) {
                            LoadedChunkRoadBackfill.placeLoadedPlayerChunks(p, 8);
                        }
                        IdleRoadGenerationService.tickPlayer(p);
                    }
                }
            }

            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                IdleRoadGenerationService.tick(level);
                RoadTaskExecutor.tick(level);
                SignTextService.tick(level);
                // ★ 2026-10-15：延迟施工队列的排水口（此前 pending 只写不读）。
                // 开局规划落盘时区块往往还没生成（世界生成路径编译出空计划），而
                // 上面的玩家回填只在【玩家周围 8 区块】内找候选 —— 预生成器
                // (Chunky) 推进的区域没有玩家，永远轮不到，用户看到的就是「地图上
                // 有蓝线、世界里没有路」。这里按施工台账幂等地补铺【已加载】区块：
                // 绝不请求/强制加载区块，单次样本 8 个、每 10 tick 一次。
                DeferredInitialRoadConstructionService.tick(level);
                // 0.8.346：Chunky 独占转正的那一刻释放可重建缓存（只做一次，
                // 用上升沿检测）。此前 Chunky 期只「暂停计算」不「释放内存」，
                // 导致 Chunky 启动时堆已 78%、4 分钟后贴顶并触发硬闸门死锁。
                boolean chunkyNow = RuntimeLoadPolicy.isChunkyActive();
                if (chunkyNow && !chunkyCacheReleased) {
                    chunkyCacheReleased = true;
                    RuntimeLoadPolicy.onChunkyOwnershipStarted(level);
                } else if (!chunkyNow) {
                    chunkyCacheReleased = false;
                }
                // New and manually added roads already receive bounded local
                // reconciliation around their own connection. A whole-save
                // reconciliation here used to enqueue its heavy geometry pass
                // onto the integrated-server thread shortly after login,
                // blocking player interaction on large road networks.
                // Keep historical routes untouched until an explicit offline
                // maintenance run is provided.
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RoadLifecycle.beginStopping();
            // 等待只属于当前服务端，退出后不再消费旧河流或长城的迟到完成。
            net.shiroha233.roadweaver.compat.RoadStartupGate.cancelPending();
            // 服务端关停前释放手绘预加载票据，迟到 Future 不再持有施工状态。
            net.shiroha233.roadweaver.route.RouteBuildService.clearPreloadState(server.overworld());
            StreamsReflowingCompat.clearPrewarmState();
            MapNetworkFabric.clearRuntime();
            MapStructureSearchService.clearRuntime();
            RoadGenerationService.onServerStopping();
            RoadPlanningReadinessIndex.clearRuntime();
            PlanningBoundsOps.resetAll();
            RuntimePlayerActivity.reset();
            // 0.8.345：复位 InitialGenManager 的跨世界静态状态。
            // 此前 active 只在正常完成路径被清，异常关机后残留 true，
            // 会让下一个世界 begin() 静默早退 → 新世界道路完全不生成（且无日志）。
            net.shiroha233.roadweaver.generation.InitialGenManager.resetForServerStop();
            ThreadPoolManager.onServerStopping();
            CacheManager.onServerStopping(server.getAllLevels());
            SignTextService.clearPending();
            DeferredInitialRoadConstructionService.clearAll();
        });

        ServerWorldEvents.UNLOAD.register((server, level) -> {
            // 世界卸载时不能留下持有该区块源的手绘加载任务。
            net.shiroha233.roadweaver.route.RouteBuildService.clearPreloadState(level);
            DeferredInitialRoadConstructionService.clear(level);
            CacheManager.onDimensionUnload(level);
        });
    }
}
