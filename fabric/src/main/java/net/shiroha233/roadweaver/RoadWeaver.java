/* 文件职责：RoadWeaver 模组主类（Fabric）：注册入口，把平台实现挂到 common 的契约上。 */
package net.shiroha233.roadweaver;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
        LOGGER.info("【初始化云游四海道路模组】 " + "Initializing RoadWeaver (Fabric)...");

        // 把实现注入 roadweaver-api 契约模块。必须**尽可能早**：
        // 外部 mod 在各自 onInitialize 里调 PlanningLifecycleApi.addListener 注册回调，
        // 若本行晚于它们的注册时机，backend 仍是 null，注册会返回 false。
        // fabric.mod.json 的 depends 保证 roadweaver_api 先于本 mod 加载。
        RoadWeaverApi.installBackend(ApiBackendImpl.INSTANCE);
        net.shiroha233.roadweaver.worldgen.road.RoadPreviewProvider.register();
        net.shiroha233.roadweaver.map.RoadOverlayProvider.register();
        LOGGER.info("【道路公共接口状态】 " + "RoadWeaver api: {}", RoadWeaverApi.describe());

        // 跨 mod 信号注册表的解析结果：进入世界（集成/专用服务端启动完成）后再打一行。
        // 这里能看到的是**运行时真实读取路径** —— 走 API 还是退回反射。
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("【服务端跨模组负载信号接入状态】 " + "RoadWeaver {}", LoadSignalBridge.describeResolution()));
        
        WorldDataAttachment.registerWorldDataAttachment();
        
        ConfigService.load();
        LOGGER.info("【道路配置加载完成】 " + "Configuration loaded");
        
        StructureRegistryFabric.register();
        LOGGER.info("【道路结构类型注册完成】 " + "Structure types registered");
        
        RoadFeatureRegistry.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MapAccessCommand.register(dispatcher));
        
        MapNetworkFabric.registerServerReceivers();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            MapNetworkFabric.syncMapAccess(handler.player);
            MapNetworkFabric.syncMapWorld(handler.player);
        });
        
        ServerPlanningHooks.register();
    }
}
