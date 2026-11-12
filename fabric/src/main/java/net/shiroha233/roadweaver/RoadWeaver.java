package net.shiroha233.roadweaver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.shiroha233.roadweaver.command.MapAccessCommand;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.features.config.RoadFeatureRegistry;
import net.shiroha233.roadweaver.network.fabric.MapNetworkFabric;
import net.shiroha233.roadweaver.persistence.attachments.WorldDataAttachment;
import net.shiroha233.roadweaver.planning.fabric.ServerPlanningHooks;
import net.shiroha233.roadweaver.structures.fabric.StructureRegistryFabric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.apiimpl.ApiBackendImpl;
import net.shiroha233.roadweaver.apiimpl.LoadSignalBridge;

/**
 * RoadWeaver 模组主类 (Fabric)
 */
public class RoadWeaver implements ModInitializer {
    public static final String MOD_ID = "roadweaver";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing RoadWeaver (Fabric)...");

        // 把实现注入 roadweaver-api 契约模块。必须**尽可能早**：
        // 外部 mod 在各自 onInitialize 里调 PlanningLifecycleApi.addListener 注册回调，
        // 若本行晚于它们的注册时机，backend 仍是 null，注册会返回 false。
        // fabric.mod.json 的 depends 保证 roadweaver_api 先于本 mod 加载。
        RoadWeaverApi.installBackend(ApiBackendImpl.INSTANCE);
        LOGGER.info("RoadWeaver api: {}", RoadWeaverApi.describe());

        // 跨 mod 信号注册表的解析结果：进入世界（集成/专用服务端启动完成）后再打一行。
        // 这里能看到的是**运行时真实读取路径** —— 走 API 还是退回反射。
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("RoadWeaver {}", LoadSignalBridge.describeResolution()));
        
        WorldDataAttachment.registerWorldDataAttachment();
        
        ConfigService.load();
        LOGGER.info("Configuration loaded");
        
        StructureRegistryFabric.register();
        LOGGER.info("Structure types registered");
        
        RoadFeatureRegistry.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MapAccessCommand.register(dispatcher));
        
        MapNetworkFabric.registerServerReceivers();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                MapNetworkFabric.syncMapAccess(handler.player));
        
        ServerPlanningHooks.register();
    }
}
