/* 文件职责：构建 Fabric 配置界面。 */
package net.shiroha233.roadweaver.client.fabric;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.config.sub.*;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.pathfinding.sampling.opencl.OpenCLDevicePreference;

import java.util.Locale;

/**
 * Fabric 平台配置屏幕工厂实现
 */
public class ConfigScreenFactoryImpl {

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.roadweaver.title"));

        PresetService.reload();
        ModConfig conf = ConfigService.get();
        ModConfig defaultConf = new ModConfig();
        builder.setSavingRunnable(ConfigService::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        buildStructurePredictionCategory(builder, eb, conf, defaultConf);
        
        buildPlanningCategory(builder, eb, conf, defaultConf);
        buildTerrainProfileCategory(builder, eb, conf, defaultConf);
        
        
        buildRoadGenerationCategory(builder, eb, conf, defaultConf);
        
        buildSurfaceSettingsCategory(builder, eb, conf, defaultConf);
        
        buildBridgeCategory(builder, eb, conf, defaultConf);
        
        buildRoadsideStructuresCategory(builder, eb, conf, defaultConf);

        buildRoadsideVillageCategory(builder, eb, conf, defaultConf);
        
        buildPerformanceCategory(builder, eb, conf, defaultConf);
        
        buildRuntimeGpuCategory(builder, eb, conf, defaultConf);
        
        buildPathfindingCostsCategory(builder, eb, conf, defaultConf);
        
        buildClientCategory(builder, eb, conf, defaultConf);

        return builder.build();
    }

    /**
     * 0.8.343：运行时 GPU 策略分类。
     *
     * <p>这些键是 0.8.340/0.8.341 新增的「四态运行时资源策略」参数，
     * 此前只能手改 JSON 或重新构建 jar。它们与「生成性能」里的线程数语义不同：
     * 后者是"开局/生成期用多少线程"，本类则是"游玩中/挂机时 GPU 怎么用"。</p>
     *
     * <p>全部为热读（每次 acquire / 每批采样现读配置），改完即时生效。</p>
     */
    private static void buildRuntimeGpuCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.runtime_gpu"));
        PerformanceConfig cfg = conf.performance();
        PerformanceConfig def = defaultConf.performance();
        PlanningConfig planningCfg = conf.planning();
        PlanningConfig planningDef = defaultConf.planning();

        // 游玩期 GPU 总开关：热读（CoarseHeightBatchSamplers.tryCreateOpenCL 每次建采样器时判断）。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.runtime_coarse_use_cpu_in_gameplay"),
                        cfg.runtimeCoarseUseCpuInGameplay())
                .setDefaultValue(def.runtimeCoarseUseCpuInGameplay())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_use_cpu_in_gameplay.tooltip"))
                .setSaveConsumer(cfg::setRuntimeCoarseUseCpuInGameplay)
                .build());

        // 0.8.344：开地图时让 GPU 粗采样让路（默认开启）。热读，改完即时生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pause_coarse_gpu_while_map_open"),
                        cfg.pauseCoarseGpuWhileMapOpen())
                .setDefaultValue(def.pauseCoarseGpuWhileMapOpen())
                .setTooltip(Component.translatable("config.roadweaver.pause_coarse_gpu_while_map_open.tooltip"))
                .setSaveConsumer(cfg::setPauseCoarseGpuWhileMapOpen)
                .build());

        // 0.8.348：开任意界面（背包/ESC/熔炉/工作台/设置）时让 GPU 粗采样让路（默认开启）。
        // 真机取证：开 GUI 不发服务端交互包，既有的"交互让路"信号不亮；0.8.344 的地图闸
        // 又只覆盖地图，故背包/ESC/熔炉这些界面此前完全没有让路。热读，改完即时生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pause_coarse_gpu_while_gui_open"),
                        cfg.pauseCoarseGpuWhileGuiOpen())
                .setDefaultValue(def.pauseCoarseGpuWhileGuiOpen())
                .setTooltip(Component.translatable("config.roadweaver.pause_coarse_gpu_while_gui_open.tooltip"))
                .setSaveConsumer(cfg::setPauseCoarseGpuWhileGuiOpen)
                .build());

        // 0.8.344：游玩期总线程上限（结构解析 + 粗采样共用）。热读（每 tick 的预算刷新会读到）。
        // 只作用于普通游玩；Chunky/开局不受限（用户："别的时候打满打满没事"）。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.gameplay_thread_cap"),
                        cfg.gameplayThreadCap())
                .setDefaultValue(def.gameplayThreadCap())
                .setTooltip(Component.translatable("config.roadweaver.gameplay_thread_cap.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setGameplayThreadCap)
                .build());

        // 活跃态提交间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_gpu_active_min_interval_ms"),
                        cfg.runtimeCoarseGpuActiveMinIntervalMs())
                .setDefaultValue(def.runtimeCoarseGpuActiveMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_gpu_active_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeCoarseGpuActiveMinIntervalMs)
                .build());

        // 挂机态提交间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_gpu_min_interval_ms"),
                        cfg.runtimeCoarseGpuMinIntervalMs())
                .setDefaultValue(def.runtimeCoarseGpuMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_gpu_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeCoarseGpuMinIntervalMs)
                .build());

        // 挂机态并发上限：sanitizeGpuConcurrencyCap = 0..16。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_max_threads"),
                        cfg.runtimeCoarseMaxThreads())
                .setDefaultValue(def.runtimeCoarseMaxThreads())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_max_threads.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setRuntimeCoarseMaxThreads)
                .build());

        // 压力态并发下限：sanitize = 1..16。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_coarse_min_threads"),
                        cfg.runtimeCoarseMinThreads())
                .setDefaultValue(def.runtimeCoarseMinThreads())
                .setTooltip(Component.translatable("config.roadweaver.runtime_coarse_min_threads.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(cfg::setRuntimeCoarseMinThreads)
                .build());

        // 道路完成后的规划间隔：sanitize 钳制 0..60000。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.runtime_road_completion_min_interval_ms"),
                        cfg.runtimeRoadCompletionMinIntervalMs())
                .setDefaultValue(def.runtimeRoadCompletionMinIntervalMs())
                .setTooltip(Component.translatable("config.roadweaver.runtime_road_completion_min_interval_ms.tooltip"))
                .setMin(0).setMax(60_000)
                .setSaveConsumer(cfg::setRuntimeRoadCompletionMinIntervalMs)
                .build());
    }

    private static void buildStructurePredictionCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.structure_filters"));
        StructurePredictionConfig cfg = conf.structurePrediction();
        StructurePredictionConfig def = defaultConf.structurePrediction();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.enable_prediction"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.enable_prediction.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.radius_chunks"), cfg.predictRadiusChunks())
                .setDefaultValue(def.predictRadiusChunks())
                .setTooltip(Component.translatable("config.roadweaver.radius_chunks.tooltip"))
                .setMin(1).setMax(4096)
                .setSaveConsumer(cfg::setPredictRadiusChunks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.biome_prefilter"), cfg.biomePrefilter())
                .setDefaultValue(def.biomePrefilter())
                .setTooltip(Component.translatable("config.roadweaver.biome_prefilter.tooltip"))
                .setSaveConsumer(cfg::setBiomePrefilter)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.structure_avoidance_enabled"), cfg.structureAvoidanceEnabled())
                .setDefaultValue(def.structureAvoidanceEnabled())
                .setTooltip(Component.translatable("config.roadweaver.structure_avoidance_enabled.tooltip"))
                .setSaveConsumer(cfg::setStructureAvoidanceEnabled)
                .build());

        // 0.8.343 补齐：villageRoadOffset（StructurePredictionConfig）。
        // 该键 sanitize() 只设了下界（>=0），无上界；参考同族键
        // other_structure_road_offset 在菜单中的既有口径（0..64），此处同样取 0..64。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.village_road_offset"), cfg.villageRoadOffset())
                .setDefaultValue(def.villageRoadOffset())
                .setTooltip(Component.translatable("config.roadweaver.village_road_offset.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(cfg::setVillageRoadOffset)
                .build());

        category.addEntry(new OpenStructureSelectionEntry());
    }

    /**
     * 0.8.421：给「仅 KNN 算法读取」的条目加标注。当前算法就是 KNN 时返回原样标题
     * （既有观感逐字不变）；否则包一层「当前不生效」的前缀，避免玩家白调参数。
     */
    private static Component knnOnlyTitle(String key, boolean knnActive) {
        Component title = Component.translatable(key);
        return knnActive ? title : Component.translatable("config.roadweaver.knn_inactive_marker", title);
    }

    private static void buildPlanningCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.road_planning"));
        PlanningConfig cfg = conf.planning();
        PlanningConfig def = defaultConf.planning();

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.initial_plan_radius_chunks"), cfg.initialPlanRadiusChunks())
                .setDefaultValue(def.initialPlanRadiusChunks())
                .setTooltip(Component.translatable("config.roadweaver.initial_plan_radius_chunks.tooltip"))
                .setMin(1).setMax(4096)
                .setSaveConsumer(cfg::setInitialPlanRadiusChunks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.dynamic_plan_enabled"), cfg.dynamicPlanEnabled())
                .setDefaultValue(def.dynamicPlanEnabled())
                .setTooltip(Component.translatable("config.roadweaver.dynamic_plan_enabled.tooltip"))
                .setSaveConsumer(cfg::setDynamicPlanEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.dynamic_plan_radius_chunks"), cfg.dynamicPlanRadiusChunks())
                .setDefaultValue(def.dynamicPlanRadiusChunks())
                .setTooltip(Component.translatable("config.roadweaver.dynamic_plan_radius_chunks.tooltip"))
                .setMin(1).setMax(4096)
                .setSaveConsumer(cfg::setDynamicPlanRadiusChunks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.dynamic_plan_stride_chunks"), cfg.dynamicPlanStrideChunks())
                .setDefaultValue(def.dynamicPlanStrideChunks())
                .setTooltip(Component.translatable("config.roadweaver.dynamic_plan_stride_chunks.tooltip"))
                .setMin(1).setMax(256)
                .setSaveConsumer(cfg::setDynamicPlanStrideChunks)
                .build());

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.planning_algorithm"),
                        PlanningConfig.PlanningAlgorithm.class, cfg.planningAlgorithm())
                .setDefaultValue(def.planningAlgorithm())
                .setTooltip(Component.translatable("config.roadweaver.planning_algorithm.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.planning_algorithm.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(cfg::setPlanningAlgorithm)
                .build());

        // 0.8.420：KNN 路网拓扑（改任一值会改规划指纹、令已有路线重算）
        // 0.8.421：界面诚实化 —— k / alpha / minAngleDeg / degreeCap 这四项**只在**
        // 「路网规划算法」= KNN 时才被 RoadPlanningService 读取（见 planPrimaryEdges 的
        // `planner instanceof KNNPlanner` 分支）；其余四种算法下它们完全不生效。
        // 因此在标题上直接标注「当前不生效」，并加一行提示，避免玩家白调。
        boolean knnActive = cfg.planningAlgorithm() == PlanningConfig.PlanningAlgorithm.KNN;
        if (!knnActive) {
            category.addEntry(eb
                    .startTextDescription(Component.translatable(
                            "config.roadweaver.knn_inactive_notice",
                            Component.translatable("config.roadweaver.planning_algorithm.option."
                                    + cfg.planningAlgorithm().name().toLowerCase(Locale.ROOT))))
                    .setColor(0xFFFFAA00)
                    .build());
        }
        category.addEntry(eb
                .startIntField(knnOnlyTitle("config.roadweaver.knn_k", knnActive), cfg.knnK())
                .setDefaultValue(def.knnK())
                .setTooltip(Component.translatable("config.roadweaver.knn_k.tooltip"))
                .setMin(1).setMax(8)
                .setSaveConsumer(cfg::setKnnK)
                .build());
        category.addEntry(eb
                .startDoubleField(knnOnlyTitle("config.roadweaver.knn_alpha", knnActive), cfg.knnAlpha())
                .setDefaultValue(def.knnAlpha())
                .setTooltip(Component.translatable("config.roadweaver.knn_alpha.tooltip"))
                .setMin(0.0).setMax(5.0)
                .setSaveConsumer(cfg::setKnnAlpha)
                .build());
        category.addEntry(eb
                .startDoubleField(knnOnlyTitle("config.roadweaver.knn_min_angle_deg", knnActive), cfg.knnMinAngleDeg())
                .setDefaultValue(def.knnMinAngleDeg())
                .setTooltip(Component.translatable("config.roadweaver.knn_min_angle_deg.tooltip"))
                .setMin(0.0).setMax(89.0)
                .setSaveConsumer(cfg::setKnnMinAngleDeg)
                .build());
        category.addEntry(eb
                .startIntField(knnOnlyTitle("config.roadweaver.knn_degree_cap", knnActive), cfg.knnDegreeCap())
                .setDefaultValue(def.knnDegreeCap())
                .setTooltip(Component.translatable("config.roadweaver.knn_degree_cap.tooltip"))
                .setMin(1).setMax(8)
                .setSaveConsumer(cfg::setKnnDegreeCap)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.component_min_angle_deg"), cfg.componentMinAngleDeg())
                .setDefaultValue(def.componentMinAngleDeg())
                .setTooltip(Component.translatable("config.roadweaver.component_min_angle_deg.tooltip"))
                .setMin(0.0).setMax(89.0)
                .setSaveConsumer(cfg::setComponentMinAngleDeg)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.component_degree_cap"), cfg.componentDegreeCap())
                .setDefaultValue(def.componentDegreeCap())
                .setTooltip(Component.translatable("config.roadweaver.component_degree_cap.tooltip"))
                .setMin(1).setMax(8)
                .setSaveConsumer(cfg::setComponentDegreeCap)
                .build());

        // 0.8.421：这两项是**真正决定「哪些结构连得上」**的旋钮（原先写死在
        // RoadConstants）。planMaxEdgeLenBlocks = 单条主连边的最长距离；
        // bridgeJoinLenBlocks = 两个互不相连的连通块之间，最近点相距不超过多少格
        // 才补一条桥接道路。调大 bridgeJoinLenBlocks ⇒ 远处的村子也能连上路网。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.plan_max_edge_len_blocks"),
                        cfg.planMaxEdgeLenBlocks())
                .setDefaultValue(def.planMaxEdgeLenBlocks())
                .setTooltip(Component.translatable("config.roadweaver.plan_max_edge_len_blocks.tooltip"))
                .setMin(128).setMax(16384)
                .setSaveConsumer(cfg::setPlanMaxEdgeLenBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_join_len_blocks"),
                        cfg.bridgeJoinLenBlocks())
                .setDefaultValue(def.bridgeJoinLenBlocks())
                .setTooltip(Component.translatable("config.roadweaver.bridge_join_len_blocks.tooltip"))
                .setMin(64).setMax(16384)
                .setSaveConsumer(cfg::setBridgeJoinLenBlocks)
                .build());
        // 0.9.079：A* 彻底失败后，把端点就近改接到已有道路再生成一次。
        // 0 = 关闭（保持旧行为：失败就是失败）。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.attach_to_existing_road_radius"),
                        cfg.attachToExistingRoadRadiusBlocks())
                .setDefaultValue(def.attachToExistingRoadRadiusBlocks())
                .setTooltip(Component.translatable("config.roadweaver.attach_to_existing_road_radius.tooltip"))
                .setMin(0).setMax(1024)
                .setSaveConsumer(cfg::setAttachToExistingRoadRadiusBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.local_conflict_radius_blocks"),
                        cfg.localConflictRadiusBlocks())
                .setDefaultValue(def.localConflictRadiusBlocks())
                .setTooltip(Component.translatable("config.roadweaver.local_conflict_radius_blocks.tooltip"))
                .setMin(16).setMax(512)
                .setSaveConsumer(cfg::setLocalConflictRadiusBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.structure_detour_radius_start_blocks"),
                        cfg.structureDetourRadiusStartBlocks())
                .setDefaultValue(def.structureDetourRadiusStartBlocks())
                .setTooltip(Component.translatable("config.roadweaver.structure_detour_radius_start_blocks.tooltip"))
                .setMin(16).setMax(512)
                .setSaveConsumer(cfg::setStructureDetourRadiusStartBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.structure_detour_radius_step_blocks"),
                        cfg.structureDetourRadiusStepBlocks())
                .setDefaultValue(def.structureDetourRadiusStepBlocks())
                .setTooltip(Component.translatable("config.roadweaver.structure_detour_radius_step_blocks.tooltip"))
                .setMin(8).setMax(128)
                .setSaveConsumer(cfg::setStructureDetourRadiusStepBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.structure_detour_radius_max_blocks"),
                        cfg.structureDetourRadiusMaxBlocks())
                .setDefaultValue(def.structureDetourRadiusMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.structure_detour_radius_max_blocks.tooltip"))
                .setMin(16).setMax(1024)
                .setSaveConsumer(cfg::setStructureDetourRadiusMaxBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.lake_detour_radius_max_blocks"),
                        cfg.lakeDetourRadiusMaxBlocks())
                .setDefaultValue(def.lakeDetourRadiusMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.lake_detour_radius_max_blocks.tooltip"))
                .setMin(16).setMax(1024)
                .setSaveConsumer(cfg::setLakeDetourRadiusMaxBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.height_tunnel_threshold_blocks"),
                        cfg.heightTunnelThresholdBlocks())
                .setDefaultValue(def.heightTunnelThresholdBlocks())
                .setTooltip(Component.translatable("config.roadweaver.height_tunnel_threshold_blocks.tooltip"))
                .setMin(2).setMax(32)
                .setSaveConsumer(cfg::setHeightTunnelThresholdBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.height_repair_context_blocks"),
                        cfg.heightRepairContextBlocks())
                .setDefaultValue(def.heightRepairContextBlocks())
                .setTooltip(Component.translatable("config.roadweaver.height_repair_context_blocks.tooltip"))
                .setMin(16).setMax(256)
                .setSaveConsumer(cfg::setHeightRepairContextBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.tunnel_depth_blocks"),
                        cfg.tunnelDepthBlocks())
                .setDefaultValue(def.tunnelDepthBlocks())
                .setTooltip(Component.translatable("config.roadweaver.tunnel_depth_blocks.tooltip"))
                .setMin(4).setMax(64)
                .setSaveConsumer(cfg::setTunnelDepthBlocks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.coarse_height_aware_enabled"),
                        cfg.coarseHeightAwareEnabled())
                .setDefaultValue(def.coarseHeightAwareEnabled())
                .setTooltip(Component.translatable("config.roadweaver.coarse_height_aware_enabled.tooltip"))
                .setSaveConsumer(cfg::setCoarseHeightAwareEnabled)
                .build());

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.terrain_profile_mode"),
                        PlanningConfig.TerrainProfileMode.class, cfg.terrainProfileMode())
                .setDefaultValue(def.terrainProfileMode())
                .setTooltip(Component.translatable("config.roadweaver.terrain_profile_mode.tooltip"))
                .setEnumNameProvider(v -> Component.translatable(
                        "config.roadweaver.terrain_profile_mode.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(cfg::setTerrainProfileMode)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.surface_cut_max_blocks"),
                        cfg.surfaceCutMaxBlocks())
                .setDefaultValue(def.surfaceCutMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.surface_cut_max_blocks.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setSurfaceCutMaxBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.surface_fill_max_blocks"),
                        cfg.surfaceFillMaxBlocks())
                .setDefaultValue(def.surfaceFillMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.surface_fill_max_blocks.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setSurfaceFillMaxBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.mountain_overburden_blocks"),
                        cfg.mountainOverburdenBlocks())
                .setDefaultValue(def.mountainOverburdenBlocks())
                .setTooltip(Component.translatable("config.roadweaver.mountain_overburden_blocks.tooltip"))
                .setMin(2).setMax(32)
                .setSaveConsumer(cfg::setMountainOverburdenBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.tunnel_min_run"),
                        cfg.tunnelMinRun())
                .setDefaultValue(def.tunnelMinRun())
                .setTooltip(Component.translatable("config.roadweaver.tunnel_min_run.tooltip"))
                .setMin(2).setMax(16)
                .setSaveConsumer(cfg::setTunnelMinRun)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.dry_depression_bridge_depth"),
                        cfg.dryDepressionBridgeDepth())
                .setDefaultValue(def.dryDepressionBridgeDepth())
                .setTooltip(Component.translatable("config.roadweaver.dry_depression_bridge_depth.tooltip"))
                .setMin(2).setMax(16)
                .setSaveConsumer(cfg::setDryDepressionBridgeDepth)
                .build());

        category.addEntry(eb
                .startIntSlider(Component.translatable("config.roadweaver.switchback_strength"),
                        cfg.switchbackStrength(), 0, 100)
                .setDefaultValue(def.switchbackStrength())
                .setTooltip(Component.translatable("config.roadweaver.switchback_strength.tooltip"))
                .setSaveConsumer(cfg::setSwitchbackStrength)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.height_aware_state_limit"),
                        cfg.heightAwareStateLimit())
                .setDefaultValue(def.heightAwareStateLimit())
                .setTooltip(Component.translatable("config.roadweaver.height_aware_state_limit.tooltip"))
                .setMin(50000).setMax(1000000)
                .setSaveConsumer(cfg::setHeightAwareStateLimit)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.sampling_in_flight_timeout_ms"),
                        cfg.samplingInFlightTimeoutMs())
                .setDefaultValue(def.samplingInFlightTimeoutMs())
                .setTooltip(Component.translatable("config.roadweaver.sampling_in_flight_timeout_ms.tooltip"))
                .setMin(100).setMax(30000)
                .setSaveConsumer(cfg::setSamplingInFlightTimeoutMs)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.auto_mountain_threshold_fraction"),
                        cfg.autoMountainThresholdFraction())
                .setDefaultValue(def.autoMountainThresholdFraction())
                .setTooltip(Component.translatable("config.roadweaver.auto_mountain_threshold_fraction.tooltip"))
                .setMin(0.1D).setMax(0.9D)
                .setSaveConsumer(cfg::setAutoMountainThresholdFraction)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.auto_water_threshold_fraction"),
                        cfg.autoWaterThresholdFraction())
                .setDefaultValue(def.autoWaterThresholdFraction())
                .setTooltip(Component.translatable("config.roadweaver.auto_water_threshold_fraction.tooltip"))
                .setMin(0.1D).setMax(0.9D)
                .setSaveConsumer(cfg::setAutoWaterThresholdFraction)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.grade_repair_window_state_limit"),
                        cfg.gradeRepairWindowStateLimit())
                .setDefaultValue(def.gradeRepairWindowStateLimit())
                .setTooltip(Component.translatable("config.roadweaver.grade_repair_window_state_limit.tooltip"))
                .setMin(20000).setMax(500000)
                .setSaveConsumer(cfg::setGradeRepairWindowStateLimit)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.grade_repair_parallelism"),
                        cfg.gradeRepairParallelism())
                .setDefaultValue(def.gradeRepairParallelism())
                .setTooltip(Component.translatable("config.roadweaver.grade_repair_parallelism.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setGradeRepairParallelism)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.local_repair_only_on_unavoidable"),
                        cfg.localRepairOnlyOnUnavoidable())
                .setDefaultValue(def.localRepairOnlyOnUnavoidable())
                .setTooltip(Component.translatable("config.roadweaver.local_repair_only_on_unavoidable.tooltip"))
                .setSaveConsumer(cfg::setLocalRepairOnlyOnUnavoidable)
                .build());

        // ===== 0.8.343 补齐：以下 9 项此前不在菜单里，只能手改 JSON =====

        // 粗规划等待方式：热读（CoarseCorridorPlanningAdapter.parallelWaitEnabled 每次规划读）。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pathfinding_parallel_wait"),
                        cfg.pathfindingParallelWait())
                .setDefaultValue(def.pathfindingParallelWait())
                .setTooltip(Component.translatable("config.roadweaver.pathfinding_parallel_wait.tooltip"))
                .setSaveConsumer(cfg::setPathfindingParallelWait)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.pass_through_village_clipping"),
                        cfg.passThroughVillageClipping())
                .setDefaultValue(def.passThroughVillageClipping())
                .setTooltip(Component.translatable("config.roadweaver.pass_through_village_clipping.tooltip"))
                .setSaveConsumer(cfg::setPassThroughVillageClipping)
                .build());

        // maxGradeFraction：sanitize 为 finiteFraction 0.1..0.9。
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.max_grade_fraction"), cfg.maxGradeFraction())
                .setDefaultValue(def.maxGradeFraction())
                .setTooltip(Component.translatable("config.roadweaver.max_grade_fraction.tooltip"))
                .setMin(0.1D).setMax(0.9D)
                .setSaveConsumer(cfg::setMaxGradeFraction)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.filter_unknown_endpoint_inside_village"),
                        cfg.filterUnknownEndpointInsideVillage())
                .setDefaultValue(def.filterUnknownEndpointInsideVillage())
                .setTooltip(Component.translatable("config.roadweaver.filter_unknown_endpoint_inside_village.tooltip"))
                .setSaveConsumer(cfg::setFilterUnknownEndpointInsideVillage)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.prefilter_structure_starts"),
                        cfg.prefilterStructureStarts())
                .setDefaultValue(def.prefilterStructureStarts())
                .setTooltip(Component.translatable("config.roadweaver.prefilter_structure_starts.tooltip"))
                .setSaveConsumer(cfg::setPrefilterStructureStarts)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.river_repair_bypass_precheck"),
                        cfg.riverRepairBypassPrecheck())
                .setDefaultValue(def.riverRepairBypassPrecheck())
                .setTooltip(Component.translatable("config.roadweaver.river_repair_bypass_precheck.tooltip"))
                .setSaveConsumer(cfg::setRiverRepairBypassPrecheck)
                .build());

        // localRepairWindowClusterRadiusBlocks：sanitize 为 0..128（0 = 回退旧的 padding/4 行为）。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.local_repair_window_cluster_radius_blocks"),
                        cfg.localRepairWindowClusterRadiusBlocks())
                .setDefaultValue(def.localRepairWindowClusterRadiusBlocks())
                .setTooltip(Component.translatable("config.roadweaver.local_repair_window_cluster_radius_blocks.tooltip"))
                .setMin(0).setMax(128)
                .setSaveConsumer(cfg::setLocalRepairWindowClusterRadiusBlocks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.prefer_unsmoothed_on_protection_gap"),
                        cfg.preferUnsmoothedOnProtectionGap())
                .setDefaultValue(def.preferUnsmoothedOnProtectionGap())
                .setTooltip(Component.translatable("config.roadweaver.prefer_unsmoothed_on_protection_gap.tooltip"))
                .setSaveConsumer(cfg::setPreferUnsmoothedOnProtectionGap)
                .build());

        // heightAnomalyDetourMaxBlocks：sanitize 为 16..512。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.height_anomaly_detour_max_blocks"),
                        cfg.heightAnomalyDetourMaxBlocks())
                .setDefaultValue(def.heightAnomalyDetourMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.height_anomaly_detour_max_blocks.tooltip"))
                .setMin(16).setMax(512)
                .setSaveConsumer(cfg::setHeightAnomalyDetourMaxBlocks)
                .build());

    }

    private static void buildTerrainProfileCategory(ConfigBuilder builder, ConfigEntryBuilder eb,
                                                    ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(
                Component.translatable("config.roadweaver.category.terrain_profile"));
        TerrainProfileConfig cfg = conf.terrainProfile();
        TerrainProfileConfig def = defaultConf.terrainProfile();

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.mountain_surface_cut"),
                        cfg.mountainSurfaceCutMaxBlocks())
                .setDefaultValue(def.mountainSurfaceCutMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.mountain_surface_cut.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setMountainSurfaceCutMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.mountain_surface_fill"),
                        cfg.mountainSurfaceFillMaxBlocks())
                .setDefaultValue(def.mountainSurfaceFillMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.mountain_surface_fill.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setMountainSurfaceFillMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.plain_surface_cut"),
                        cfg.plainSurfaceCutMaxBlocks())
                .setDefaultValue(def.plainSurfaceCutMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.plain_surface_cut.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setPlainSurfaceCutMaxBlocks)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.plain_surface_fill"),
                        cfg.plainSurfaceFillMaxBlocks())
                .setDefaultValue(def.plainSurfaceFillMaxBlocks())
                .setTooltip(Component.translatable("config.roadweaver.plain_surface_fill.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setPlainSurfaceFillMaxBlocks)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.mountain_detour_multiplier"),
                        cfg.mountainDetourMultiplier())
                .setDefaultValue(def.mountainDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.mountain_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setMountainDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.coast_detour_multiplier"),
                        cfg.coastDetourMultiplier())
                .setDefaultValue(def.coastDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.coast_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setCoastDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.river_detour_multiplier"),
                        cfg.riverDetourMultiplier())
                .setDefaultValue(def.riverDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.river_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setRiverDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.lake_detour_multiplier"),
                        cfg.lakeDetourMultiplier())
                .setDefaultValue(def.lakeDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.lake_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setLakeDetourMultiplier)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ocean_detour_multiplier"),
                        cfg.oceanDetourMultiplier())
                .setDefaultValue(def.oceanDetourMultiplier())
                .setTooltip(Component.translatable("config.roadweaver.ocean_detour_multiplier.tooltip"))
                .setMin(0.25D).setMax(4.0D)
                .setSaveConsumer(cfg::setOceanDetourMultiplier)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.ocean_bridge_min_depth"),
                        cfg.oceanBridgeMinDepth())
                .setDefaultValue(def.oceanBridgeMinDepth())
                .setTooltip(Component.translatable("config.roadweaver.ocean_bridge_min_depth.tooltip"))
                .setMin(1).setMax(10)
                .setSaveConsumer(cfg::setOceanBridgeMinDepth)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ocean_undersea_chance"),
                        cfg.oceanUnderseaChance())
                .setDefaultValue(def.oceanUnderseaChance())
                .setTooltip(Component.translatable("config.roadweaver.ocean_undersea_chance.tooltip"))
                .setMin(0.0D).setMax(1.0D)
                .setSaveConsumer(cfg::setOceanUnderseaChance)
                .build());
    }

    private static void buildRoadGenerationCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.road_generation"));
        RoadAppearanceConfig cfg = conf.roadAppearance();
        RoadAppearanceConfig def = defaultConf.roadAppearance();
        StructurePredictionConfig structureCfg = conf.structurePrediction();
        StructurePredictionConfig structureDef = defaultConf.structurePrediction();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roads_enabled"), cfg.roadsEnabled())
                .setDefaultValue(def.roadsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.roads_enabled.tooltip"))
                .setSaveConsumer(cfg::setRoadsEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.allow_artificial"), cfg.allowArtificial())
                .setDefaultValue(def.allowArtificial())
                .setTooltip(Component.translatable("config.roadweaver.allow_artificial.tooltip"))
                .setSaveConsumer(cfg::setAllowArtificial)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.allow_natural"), cfg.allowNatural())
                .setDefaultValue(def.allowNatural())
                .setTooltip(Component.translatable("config.roadweaver.allow_natural.tooltip"))
                .setSaveConsumer(cfg::setAllowNatural)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.place_waypoints"), cfg.placeWaypoints())
                .setDefaultValue(def.placeWaypoints())
                .setTooltip(Component.translatable("config.roadweaver.place_waypoints.tooltip"))
                .setSaveConsumer(cfg::setPlaceWaypoints)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.spawn_cabin_enabled"), cfg.spawnCabinEnabled())
                .setDefaultValue(def.spawnCabinEnabled())
                .setTooltip(Component.translatable("config.roadweaver.spawn_cabin_enabled.tooltip"))
                .setSaveConsumer(cfg::setSpawnCabinEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.road_width"), cfg.roadWidth())
                .setDefaultValue(def.roadWidth())
                .setTooltip(Component.translatable("config.roadweaver.road_width.tooltip"))
                .setMin(0).setMax(15)
                .setSaveConsumer(cfg::setRoadWidth)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.other_structure_road_offset"), structureCfg.otherStructureRoadOffset())
                .setDefaultValue(structureDef.otherStructureRoadOffset())
                .setTooltip(Component.translatable("config.roadweaver.other_structure_road_offset.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(structureCfg::setOtherStructureRoadOffset)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.road_signs_enabled"), cfg.roadSignsEnabled())
                .setDefaultValue(def.roadSignsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.road_signs_enabled.tooltip"))
                .setSaveConsumer(cfg::setRoadSignsEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.lamp_interval"), cfg.lampInterval())
                .setDefaultValue(def.lampInterval())
                .setTooltip(Component.translatable("config.roadweaver.lamp_interval.tooltip"))
                .setMin(1).setMax(2048)
                .setSaveConsumer(cfg::setLampInterval)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.road_clear_height"), cfg.roadClearHeight())
                .setDefaultValue(def.roadClearHeight())
                .setTooltip(Component.translatable("config.roadweaver.road_clear_height.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(cfg::setRoadClearHeight)
                .build());

        category.addEntry(new OpenPresetEditorEntry());
    }

    private static void buildSurfaceSettingsCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.gen_surface"));
        RoadAppearanceConfig cfg = conf.roadAppearance();
        RoadAppearanceConfig def = defaultConf.roadAppearance();

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.averaging_radius"), cfg.averagingRadius())
                .setDefaultValue(def.averagingRadius())
                .setTooltip(Component.translatable("config.roadweaver.averaging_radius.tooltip"))
                .setMin(0).setMax(64)
                .setSaveConsumer(cfg::setAveragingRadius)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_slope_step_per_two_segments"), cfg.maxSlopeStepPerTwoSegments())
                .setDefaultValue(def.maxSlopeStepPerTwoSegments())
                .setTooltip(Component.translatable("config.roadweaver.max_slope_step_per_two_segments.tooltip"))
                .setMin(0).setMax(8)
                .setSaveConsumer(cfg::setMaxSlopeStepPerTwoSegments)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.slope_limit_enabled"), cfg.slopeLimitEnabled())
                .setDefaultValue(def.slopeLimitEnabled())
                .setTooltip(Component.translatable("config.roadweaver.slope_limit_enabled.tooltip"))
                .setSaveConsumer(cfg::setSlopeLimitEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.prevent_trees_on_road"), cfg.preventTreesOnRoad())
                .setDefaultValue(def.preventTreesOnRoad())
                .setTooltip(Component.translatable("config.roadweaver.prevent_trees_on_road.tooltip"))
                .setSaveConsumer(cfg::setPreventTreesOnRoad)
                .build());
    }

    private static void buildBridgeCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.bridge"));
        BridgeConfig cfg = conf.bridge();
        BridgeConfig def = defaultConf.bridge();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.bridge_enabled"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.bridge_enabled.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_deck_clearance"), cfg.deckClearance())
                .setDefaultValue(def.deckClearance())
                .setTooltip(Component.translatable("config.roadweaver.bridge_deck_clearance.tooltip"))
                .setMin(1).setMax(8)
                .setSaveConsumer(cfg::setDeckClearance)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_max_length_blocks"), cfg.maxLengthBlocks())
                .setDefaultValue(def.maxLengthBlocks())
                .setTooltip(Component.translatable("config.roadweaver.bridge_max_length_blocks.tooltip"))
                .setMin(0).setMax(10000)
                .setSaveConsumer(cfg::setMaxLengthBlocks)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.bridge_use_buoys_instead"), cfg.useBuoysInstead())
                .setDefaultValue(def.useBuoysInstead())
                .setTooltip(Component.translatable("config.roadweaver.bridge_use_buoys_instead.tooltip"))
                .setSaveConsumer(cfg::setUseBuoysInstead)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.bridge_use_buoys_when_skipped"), cfg.useBuoysWhenSkipped())
                .setDefaultValue(def.useBuoysWhenSkipped())
                .setTooltip(Component.translatable("config.roadweaver.bridge_use_buoys_when_skipped.tooltip"))
                .setSaveConsumer(cfg::setUseBuoysWhenSkipped)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.buoy_interval_blocks"), cfg.buoyIntervalBlocks())
                .setDefaultValue(def.buoyIntervalBlocks())
                .setTooltip(Component.translatable("config.roadweaver.buoy_interval_blocks.tooltip"))
                .setMin(4).setMax(256)
                .setSaveConsumer(cfg::setBuoyIntervalBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_pier_interval"), cfg.pierInterval())
                .setDefaultValue(def.pierInterval())
                .setTooltip(Component.translatable("config.roadweaver.bridge_pier_interval.tooltip"))
                .setMin(3).setMax(32)
                .setSaveConsumer(cfg::setPierInterval)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_pier_width"), cfg.pierWidth())
                .setDefaultValue(def.pierWidth())
                .setTooltip(Component.translatable("config.roadweaver.bridge_pier_width.tooltip"))
                .setMin(1).setMax(3)
                .setSaveConsumer(cfg::setPierWidth)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_pier_max_height"), cfg.pierMaxHeight())
                .setDefaultValue(def.pierMaxHeight())
                .setTooltip(Component.translatable("config.roadweaver.bridge_pier_max_height.tooltip"))
                .setMin(6).setMax(64)
                .setSaveConsumer(cfg::setPierMaxHeight)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.bridge_keep_lamps"), cfg.keepLamps())
                .setDefaultValue(def.keepLamps())
                .setTooltip(Component.translatable("config.roadweaver.bridge_keep_lamps.tooltip"))
                .setSaveConsumer(cfg::setKeepLamps)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_ramp_segments"), cfg.rampSegments())
                .setDefaultValue(def.rampSegments())
                .setTooltip(Component.translatable("config.roadweaver.bridge_ramp_segments.tooltip"))
                .setMin(0).setMax(12)
                .setSaveConsumer(cfg::setRampSegments)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_min_water_depth"), cfg.minWaterDepth())
                .setDefaultValue(def.minWaterDepth())
                .setTooltip(Component.translatable("config.roadweaver.bridge_min_water_depth.tooltip"))
                .setMin(1).setMax(10)
                .setSaveConsumer(cfg::setMinWaterDepth)
                .build());

        // 0.9.032：R-03「共享跨越」的唯一旋钮。字段与 clamp 早在 0.9.029 就进了
        // BridgeConfig（[0,96]，0 = 关闭），但一直没有界面入口，玩家只能手改 JSON。
        // 消费点 ExistingRoadJunctionService:149 -> BridgeShareService.applyInPlace。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_share_radius"), cfg.shareRadius())
                .setDefaultValue(def.shareRadius())
                .setTooltip(Component.translatable("config.roadweaver.bridge_share_radius.tooltip"))
                .setMin(0).setMax(96)
                .setSaveConsumer(cfg::setShareRadius)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_min_length"), cfg.minLength())
                .setDefaultValue(def.minLength())
                .setTooltip(Component.translatable("config.roadweaver.bridge_min_length.tooltip"))
                .setMin(1).setMax(32)
                .setSaveConsumer(cfg::setMinLength)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.bridge_merge_gap"), cfg.mergeGap())
                .setDefaultValue(def.mergeGap())
                .setTooltip(Component.translatable("config.roadweaver.bridge_merge_gap.tooltip"))
                .setMin(1).setMax(32)
                .setSaveConsumer(cfg::setMergeGap)
                .build());

        // 0.8.343 补齐：隧道净空两项（RoadAppearanceConfig）。
        // 消费点 AboveColumnClearer:21 / AboveColumnClearer:58；tunnelClearHeight 的
        // sanitize 区间来自 RoadConstants.TUNNEL_CLEAR_HEIGHT_MIN(2)..MAX(16)。
        RoadAppearanceConfig appearanceCfg = conf.roadAppearance();
        RoadAppearanceConfig appearanceDef = defaultConf.roadAppearance();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.tunnel_enabled"), appearanceCfg.tunnelEnabled())
                .setDefaultValue(appearanceDef.tunnelEnabled())
                .setTooltip(Component.translatable("config.roadweaver.tunnel_enabled.tooltip"))
                .setSaveConsumer(appearanceCfg::setTunnelEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.tunnel_clear_height"), appearanceCfg.tunnelClearHeight())
                .setDefaultValue(appearanceDef.tunnelClearHeight())
                .setTooltip(Component.translatable("config.roadweaver.tunnel_clear_height.tooltip"))
                .setMin(RoadConstants.TUNNEL_CLEAR_HEIGHT_MIN).setMax(RoadConstants.TUNNEL_CLEAR_HEIGHT_MAX)
                .setSaveConsumer(appearanceCfg::setTunnelClearHeight)
                .build());

        // 0.8.435 水门三件套 + 公里坡度：四项都在 RoadAppearanceConfig，消费点分别是
        // RoadTunnelBuilder.sealBore（干隧道）、CulvertPlanner（涵洞宽度/水深）、
        // RoadGradePlanner.configuredStationSpacing（弧长坡度）。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.tunnel_dry_seal"), appearanceCfg.tunnelDrySeal())
                .setDefaultValue(appearanceDef.tunnelDrySeal())
                .setTooltip(Component.translatable("config.roadweaver.tunnel_dry_seal.tooltip"))
                .setSaveConsumer(appearanceCfg::setTunnelDrySeal)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.culvert_enabled"), appearanceCfg.culvertEnabled())
                .setDefaultValue(appearanceDef.culvertEnabled())
                .setTooltip(Component.translatable("config.roadweaver.culvert_enabled.tooltip"))
                .setSaveConsumer(appearanceCfg::setCulvertEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.culvert_max_stations"), appearanceCfg.culvertMaxStations())
                .setDefaultValue(appearanceDef.culvertMaxStations())
                .setTooltip(Component.translatable("config.roadweaver.culvert_max_stations.tooltip"))
                .setMin(1).setMax(16)
                .setSaveConsumer(appearanceCfg::setCulvertMaxStations)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.culvert_max_water_depth"), appearanceCfg.culvertMaxWaterDepth())
                .setDefaultValue(appearanceDef.culvertMaxWaterDepth())
                .setTooltip(Component.translatable("config.roadweaver.culvert_max_water_depth.tooltip"))
                .setMin(1).setMax(4)
                .setSaveConsumer(appearanceCfg::setCulvertMaxWaterDepth)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.slope_by_arc_length"), appearanceCfg.slopeByArcLength())
                .setDefaultValue(appearanceDef.slopeByArcLength())
                .setTooltip(Component.translatable("config.roadweaver.slope_by_arc_length.tooltip"))
                .setSaveConsumer(appearanceCfg::setSlopeByArcLength)
                .build());
    }

    private static void buildRoadsideStructuresCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.roadside_structures"));
        RoadsideStructureConfig cfg = conf.roadsideStructure();
        RoadsideStructureConfig def = defaultConf.roadsideStructure();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roadside_structures_enabled"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.roadside_structures_enabled.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_structures_per_road"), cfg.maxStructuresPerRoad())
                .setDefaultValue(def.maxStructuresPerRoad())
                .setTooltip(Component.translatable("config.roadweaver.max_structures_per_road.tooltip"))
                .setMin(0).setMax(20)
                .setSaveConsumer(cfg::setMaxStructuresPerRoad)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.small_structure_offset"), cfg.smallStructureOffset())
                .setDefaultValue(def.smallStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.small_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setSmallStructureOffset)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.medium_structure_offset"), cfg.mediumStructureOffset())
                .setDefaultValue(def.mediumStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.medium_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setMediumStructureOffset)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.large_structure_offset"), cfg.largeStructureOffset())
                .setDefaultValue(def.largeStructureOffset())
                .setTooltip(Component.translatable("config.roadweaver.large_structure_offset.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setLargeStructureOffset)
                .build());
    }

    private static void buildRoadsideVillageCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.roadside_village"));
        RoadsideVillageConfig cfg = conf.roadsideVillage();
        RoadsideVillageConfig def = defaultConf.roadsideVillage();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.roadside_village_enabled"), cfg.enabled())
                .setDefaultValue(def.enabled())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_enabled.tooltip"))
                .setSaveConsumer(cfg::setEnabled)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_spawn_chance"), cfg.spawnChance())
                .setDefaultValue(def.spawnChance())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_spawn_chance.tooltip"))
                .setMin(0.0D).setMax(1.0D)
                .setSaveConsumer(cfg::setSpawnChance)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_per_road"), cfg.maxVillagesPerRoad())
                .setDefaultValue(def.maxVillagesPerRoad())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_per_road.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_PER_ROAD_MAX)
                .setSaveConsumer(cfg::setMaxVillagesPerRoad)
                .build());

        // ===== 0.8.343 补齐：路边村庄几何组 12 项（此前整个几何组都不在菜单里）=====
        // 所有 setMin/setMax 一律取自 RoadsideVillageConfig.sanitize() 的钳制区间
        // （不是 setter 区间 —— 两者不同，例如 setMinRoadSegments 只 max(1,v) 而
        //  sanitize 强制 >= 32，若按 setter 写会静默改回 32 造成"输了没用"）。

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_min_road_segments"),
                        cfg.minRoadSegments())
                .setDefaultValue(def.minRoadSegments())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_min_road_segments.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MAX)
                .setSaveConsumer(cfg::setMinRoadSegments)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_window_segments"),
                        cfg.windowSegments())
                .setDefaultValue(def.windowSegments())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_window_segments.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MAX)
                .setSaveConsumer(cfg::setWindowSegments)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_node_count_min"),
                        cfg.targetNodeCountMin())
                .setDefaultValue(def.targetNodeCountMin())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_node_count_min.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MAX)
                .setSaveConsumer(cfg::setTargetNodeCountMin)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_node_count_max"),
                        cfg.targetNodeCountMax())
                .setDefaultValue(def.targetNodeCountMax())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_node_count_max.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_NODE_COUNT_MAX)
                .setSaveConsumer(cfg::setTargetNodeCountMax)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_height_diff"),
                        cfg.maxHeightDiff())
                .setDefaultValue(def.maxHeightDiff())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_height_diff.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_HEIGHT_DIFF_MAX)
                .setSaveConsumer(cfg::setMaxHeightDiff)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_local_slope"),
                        cfg.maxLocalSlope())
                .setDefaultValue(def.maxLocalSlope())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_local_slope.tooltip"))
                .setMin(0).setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_LOCAL_SLOPE_MAX)
                .setSaveConsumer(cfg::setMaxLocalSlope)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_min_curve_angle"),
                        cfg.minCurveAngle())
                .setDefaultValue(def.minCurveAngle())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_min_curve_angle.tooltip"))
                .setMin(0.0D).setMax(180.0D)
                .setSaveConsumer(cfg::setMinCurveAngle)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.roadside_village_max_curve_angle"),
                        cfg.maxCurveAngle())
                .setDefaultValue(def.maxCurveAngle())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_curve_angle.tooltip"))
                .setMin(0.0D).setMax(180.0D)
                .setSaveConsumer(cfg::setMaxCurveAngle)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_road_buffer_blocks"),
                        cfg.roadBufferBlocks())
                .setDefaultValue(def.roadBufferBlocks())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_road_buffer_blocks.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_ROAD_BUFFER_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_ROAD_BUFFER_MAX)
                .setSaveConsumer(cfg::setRoadBufferBlocks)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_building_gap_interval"),
                        cfg.buildingGapInterval())
                .setDefaultValue(def.buildingGapInterval())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_building_gap_interval.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MAX)
                .setSaveConsumer(cfg::setBuildingGapInterval)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_step_height"),
                        cfg.maxStepHeight())
                .setDefaultValue(def.maxStepHeight())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_step_height.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MAX)
                .setSaveConsumer(cfg::setMaxStepHeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.roadside_village_max_distance_from_center"),
                        cfg.maxDistanceFromCenter())
                .setDefaultValue(def.maxDistanceFromCenter())
                .setTooltip(Component.translatable("config.roadweaver.roadside_village_max_distance_from_center.tooltip"))
                .setMin(RoadConstants.ROADSIDE_VILLAGE_MAX_DISTANCE_MIN)
                .setMax(RoadConstants.ROADSIDE_VILLAGE_MAX_DISTANCE_MAX)
                .setSaveConsumer(cfg::setMaxDistanceFromCenter)
                .build());

    }

    private static void buildPerformanceCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.gen_performance"));
        PerformanceConfig cfg = conf.performance();
        PerformanceConfig def = defaultConf.performance();
        PlanningConfig planning = conf.planning();
        PlanningConfig planningDef = defaultConf.planning();

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.terrain_sampling_mode"),
                        TerrainSamplingMode.class, planning.terrainSamplingMode())
                .setDefaultValue(planningDef.terrainSamplingMode())
                .setTooltip(Component.translatable("config.roadweaver.terrain_sampling_mode.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.terrain_sampling_mode.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(planning::setTerrainSamplingMode)
                .build());

        // 0.8.343：三项上限从 128 改为与实际生效值一致。
        // initialGenerationThreads 实际封顶 RoadConstants.COMPUTE_THREADS_MAX；
        // sharedWorkerThreads / chunkyRoadThreadCap 的运行时上限是
        // ThreadPoolManager.maxRuntimeRoadThreads() = max(1, min(12, 核数-2))。
        // 原来写 128 会让玩家填一个永远达不到的值、体感"写了没反应"。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.initial_generation_threads"), cfg.initialGenerationThreads())
                .setDefaultValue(def.initialGenerationThreads())
                .setTooltip(Component.translatable("config.roadweaver.initial_generation_threads.tooltip"))
                .setMin(0).setMax(RoadConstants.COMPUTE_THREADS_MAX)
                .setSaveConsumer(cfg::setInitialGenerationThreads)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.shared_worker_threads"), cfg.sharedWorkerThreads())
                .setDefaultValue(def.sharedWorkerThreads())
                .setTooltip(Component.translatable("config.roadweaver.shared_worker_threads.tooltip"))
                .setMin(0).setMax(RoadConstants.COMPUTE_THREADS_MAX)
                .setSaveConsumer(cfg::setSharedWorkerThreads)
                .build());

        // 0.8.343：chunkyRoadThreadCap 是**死键**。0.9.027 曾一度把它从死键名单里划掉，
        // 理由是「被 ThreadPoolManager:113 消费」—— 那条结论是错的：:113 只是
        // LOGGER.info("… chunkyRoadCap={}", …) 的**实参列表**，值只被打进启动那行日志，
        // 没有任何分支拿它去限制 Chunky 期间的线程（chunkySupplyThreads 的增减也不读它）。
        // 全仓读取点只有：PerformanceConfig 的字段/sanitize/copy/存取器、本界面、
        // PerformanceSchedulingConfigTest。tooltip 已注明不生效。
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.chunky_road_thread_cap"), cfg.chunkyRoadThreadCap())
                .setDefaultValue(def.chunkyRoadThreadCap())
                .setTooltip(Component.translatable("config.roadweaver.chunky_road_thread_cap.tooltip"))
                .setMin(0).setMax(RoadConstants.COMPUTE_THREADS_MAX)
                .setSaveConsumer(cfg::setChunkyRoadThreadCap)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.idle_generation_enabled"), cfg.idleGenerationEnabled())
                .setDefaultValue(def.idleGenerationEnabled())
                .setTooltip(Component.translatable("config.roadweaver.idle_generation_enabled.tooltip"))
                .setSaveConsumer(cfg::setIdleGenerationEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.max_concurrent_generations"), cfg.maxConcurrentGenerations())
                .setDefaultValue(def.maxConcurrentGenerations())
                .setTooltip(Component.translatable("config.roadweaver.max_concurrent_generations.tooltip"))
                .setMin(1).setMax(128)
                .setSaveConsumer(cfg::setMaxConcurrentGenerations)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.idle_concurrent_generations"), cfg.idleConcurrentGenerations())
                .setDefaultValue(def.idleConcurrentGenerations())
                .setTooltip(Component.translatable("config.roadweaver.idle_concurrent_generations.tooltip"))
                .setMin(1).setMax(128)
                .setSaveConsumer(cfg::setIdleConcurrentGenerations)
                .build());

        category.addEntry(eb
                .startIntSlider(Component.translatable("config.roadweaver.thread_duty_cycle"), cfg.threadDutyCycle(), 1, 100)
                .setDefaultValue(def.threadDutyCycle())
                .setTooltip(Component.translatable("config.roadweaver.thread_duty_cycle.tooltip"))
                .setSaveConsumer(cfg::setThreadDutyCycle)
                .build());

        category.addEntry(eb
                .startIntSlider(Component.translatable("config.roadweaver.idle_thread_duty_cycle"),
                        cfg.idleThreadDutyCycle(), 1, 100)
                .setDefaultValue(def.idleThreadDutyCycle())
                .setTooltip(Component.translatable("config.roadweaver.idle_thread_duty_cycle.tooltip"))
                .setSaveConsumer(cfg::setIdleThreadDutyCycle)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_coarse_sampling_enabled"), cfg.openclCoarseSamplingEnabled())
                .setDefaultValue(def.openclCoarseSamplingEnabled())
                .setTooltip(Component.translatable("config.roadweaver.opencl_coarse_sampling_enabled.tooltip"))
                .setSaveConsumer(cfg::setOpenclCoarseSamplingEnabled)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.opencl_coarse_gpu_concurrency_cap"),
                        cfg.openclCoarseGpuConcurrencyCap())
                .setDefaultValue(def.openclCoarseGpuConcurrencyCap())
                .setTooltip(Component.translatable("config.roadweaver.opencl_coarse_gpu_concurrency_cap.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setOpenclCoarseGpuConcurrencyCap)
                .build());

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.opencl_device_preference"),
                        OpenCLDevicePreference.class, OpenCLDevicePreference.valueOf(cfg.openclDevicePreference()))
                .setDefaultValue(OpenCLDevicePreference.valueOf(def.openclDevicePreference()))
                .setTooltip(Component.translatable("config.roadweaver.opencl_device_preference.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.opencl_device_preference.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(v -> cfg.setOpenclDevicePreference(v.name()))
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.opencl_min_samples"), cfg.openclMinSamples())
                .setDefaultValue(def.openclMinSamples())
                .setTooltip(Component.translatable("config.roadweaver.opencl_min_samples.tooltip"))
                .setMin(0).setMax(RoadConstants.COARSE_REGION_MAX_SAMPLES)
                .setSaveConsumer(cfg::setOpenclMinSamples)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_validate_samples"), cfg.openclValidateSamples())
                .setDefaultValue(def.openclValidateSamples())
                .setTooltip(Component.translatable("config.roadweaver.opencl_validate_samples.tooltip"))
                .setSaveConsumer(cfg::setOpenclValidateSamples)
                .build());

        // 0.8.343：显存预算两项。热读（每批采样前读），但**可观测效果很弱** ——
        // 见 OpenCLCoarseHeightBatchSampler 里 0.8.342 审计修正的注释：
        // 当前整合包参数下（nodes=677、tileSizeChunks=32）单批 scratch 仅约 96.9 MiB，
        // 而闸门额度默认约 4096 MiB，需显存占用 >98.3% 才触发。
        // 因此 tooltip 明确写"需小到 128~256 量级才会真正限流"，避免玩家以为没生效。
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.gpu_budget_free_fraction"),
                        cfg.gpuBudgetFreeFraction())
                .setDefaultValue(def.gpuBudgetFreeFraction())
                .setTooltip(Component.translatable("config.roadweaver.gpu_budget_free_fraction.tooltip"))
                .setMin(0.05D).setMax(1.0D)
                .setSaveConsumer(cfg::setGpuBudgetFreeFraction)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.gpu_budget_hard_cap_mib"),
                        cfg.gpuBudgetHardCapMiB())
                .setDefaultValue(def.gpuBudgetHardCapMiB())
                .setTooltip(Component.translatable("config.roadweaver.gpu_budget_hard_cap_mib.tooltip"))
                .setMin(0).setMax(65_536)
                .setSaveConsumer(cfg::setGpuBudgetHardCapMiB)
                .build());

        // 0.8.343：死键标注。该开关全项目**无任何读取点**
        // （grep openclAccurateSamplingEnabled：只有 ConfigService 迁移里赋值 + 本菜单），
        // 因为精采样自 0.8.340 起就是刻意 CPU-only（AccurateHeightBackends.create 无条件 return cpu）。
        // 保留条目以免玩家以为选项丢失，但 tooltip 必须写明当前版本不生效。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.opencl_accurate_sampling_enabled"),
                        cfg.openclAccurateSamplingEnabled())
                .setDefaultValue(def.openclAccurateSamplingEnabled())
                .setTooltip(Component.translatable("config.roadweaver.opencl_accurate_sampling_enabled.tooltip"))
                .setSaveConsumer(cfg::setOpenclAccurateSamplingEnabled)
                .build());
    }

    private static void buildPathfindingCostsCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.pathfinding_costs"));
        PathfindingCostConfig cfg = conf.pathfindingCost();
        PathfindingCostConfig def = defaultConf.pathfindingCost();

        category.addEntry(eb
                .startEnumSelector(Component.translatable("config.roadweaver.pathfinding_algorithm"),
                        PathfindingCostConfig.PathfindingAlgorithm.class, cfg.pathfindingAlgorithm())
                .setDefaultValue(def.pathfindingAlgorithm())
                .setTooltip(Component.translatable("config.roadweaver.pathfinding_algorithm.tooltip"))
                .setEnumNameProvider(v -> Component.translatable("config.roadweaver.pathfinding_algorithm.option." + v.name().toLowerCase(Locale.ROOT)))
                .setSaveConsumer(cfg::setPathfindingAlgorithm)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.a_star_step"), cfg.aStarStep())
                .setDefaultValue(def.aStarStep())
                .setTooltip(Component.translatable("config.roadweaver.a_star_step.tooltip"))
                .setMin(RoadConstants.ASTAR_STEP_MIN).setMax(RoadConstants.ASTAR_STEP_MAX)
                .setSaveConsumer(cfg::setAStarStep)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.a_star_max_steps"), cfg.aStarMaxSteps())
                .setDefaultValue(def.aStarMaxSteps())
                .setTooltip(Component.translatable("config.roadweaver.a_star_max_steps.tooltip"))
                .setMin(100).setMax(RoadConstants.ASTAR_MAX_STEPS_MAX)
                .setSaveConsumer(cfg::setAStarMaxSteps)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.ortho_step_cost"), cfg.orthoStepCost())
                .setDefaultValue(def.orthoStepCost())
                .setTooltip(Component.translatable("config.roadweaver.ortho_step_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setOrthoStepCost)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.diag_step_cost"), cfg.diagStepCost())
                .setDefaultValue(def.diagStepCost())
                .setTooltip(Component.translatable("config.roadweaver.diag_step_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setDiagStepCost)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_weight"), cfg.elevationWeight())
                .setDefaultValue(def.elevationWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.biome_weight"), cfg.biomeWeight())
                .setDefaultValue(def.biomeWeight())
                .setTooltip(Component.translatable("config.roadweaver.biome_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setBiomeWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.stability_weight"), cfg.stabilityWeight())
                .setDefaultValue(def.stabilityWeight())
                .setTooltip(Component.translatable("config.roadweaver.stability_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setStabilityWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.water_depth_weight"), cfg.waterDepthWeight())
                .setDefaultValue(def.waterDepthWeight())
                .setTooltip(Component.translatable("config.roadweaver.water_depth_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setWaterDepthWeight)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.near_water_cost"), cfg.nearWaterCost())
                .setDefaultValue(def.nearWaterCost())
                .setTooltip(Component.translatable("config.roadweaver.near_water_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setNearWaterCost)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.water_proximity_cost"), cfg.waterProximityCost())
                .setDefaultValue(def.waterProximityCost())
                .setTooltip(Component.translatable("config.roadweaver.water_proximity_cost.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setWaterProximityCost)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.heuristic_weight"), cfg.heuristicWeight())
                .setDefaultValue(def.heuristicWeight())
                .setTooltip(Component.translatable("config.roadweaver.heuristic_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setHeuristicWeight)
                .build());

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.deviation_weight"), cfg.deviationWeight())
                .setDefaultValue(def.deviationWeight())
                .setTooltip(Component.translatable("config.roadweaver.deviation_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setDeviationWeight)
                .build());

        // ===== 势场法（POTENTIAL_FIELD）晋升常量（0.8.411 起可调）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_discount"), cfg.contourDiscount())
                .setDefaultValue(def.contourDiscount())
                .setTooltip(Component.translatable("config.roadweaver.contour_discount.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setContourDiscount)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.soft_grade_limit"), cfg.softGradeLimit())
                .setDefaultValue(def.softGradeLimit())
                .setTooltip(Component.translatable("config.roadweaver.soft_grade_limit.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setSoftGradeLimit)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.hard_grade_limit"), cfg.hardGradeLimit())
                .setDefaultValue(def.hardGradeLimit())
                .setTooltip(Component.translatable("config.roadweaver.hard_grade_limit.tooltip"))
                .setMin(0).setMax(1)
                .setSaveConsumer(cfg::setHardGradeLimit)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.soft_grade_penalty"), cfg.softGradePenalty())
                .setDefaultValue(def.softGradePenalty())
                .setTooltip(Component.translatable("config.roadweaver.soft_grade_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setSoftGradePenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.hard_grade_penalty"), cfg.hardGradePenalty())
                .setDefaultValue(def.hardGradePenalty())
                .setTooltip(Component.translatable("config.roadweaver.hard_grade_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setHardGradePenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.gradient_align_penalty"), cfg.gradientAlignPenalty())
                .setDefaultValue(def.gradientAlignPenalty())
                .setTooltip(Component.translatable("config.roadweaver.gradient_align_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setGradientAlignPenalty)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.pf_water_base_penalty"), cfg.pfWaterBasePenalty())
                .setDefaultValue(def.pfWaterBasePenalty())
                .setTooltip(Component.translatable("config.roadweaver.pf_water_base_penalty.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setPfWaterBasePenalty)
                .build());

        // ===== 蜿蜒 / 群系走向（0.8.413 起可调）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_water"), cfg.biomeCostWater())
                .setDefaultValue(def.biomeCostWater())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_water.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostWater)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_plains"), cfg.biomeCostPlains())
                .setDefaultValue(def.biomeCostPlains())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_plains.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostPlains)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_forest"), cfg.biomeCostForest())
                .setDefaultValue(def.biomeCostForest())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_forest.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostForest)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_mountain"), cfg.biomeCostMountain())
                .setDefaultValue(def.biomeCostMountain())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_mountain.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostMountain)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_swamp"), cfg.biomeCostSwamp())
                .setDefaultValue(def.biomeCostSwamp())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_swamp.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostSwamp)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_desert"), cfg.biomeCostDesert())
                .setDefaultValue(def.biomeCostDesert())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_desert.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostDesert)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.biome_cost_other"), cfg.biomeCostOther())
                .setDefaultValue(def.biomeCostOther())
                .setTooltip(Component.translatable("config.roadweaver.biome_cost_other.tooltip"))
                .setMin(-1000).setMax(1000)
                .setSaveConsumer(cfg::setBiomeCostOther)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_follow_weight"), cfg.contourFollowWeight())
                .setDefaultValue(def.contourFollowWeight())
                .setTooltip(Component.translatable("config.roadweaver.contour_follow_weight.tooltip"))
                .setMin(0).setMax(20)
                .setSaveConsumer(cfg::setContourFollowWeight)
                .build());

        // ===== 0.8.409 分段/软惩罚（最细粒度奖励惩罚体系）=====

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_gentle_max"), cfg.elevationGentleMax())
                .setDefaultValue(def.elevationGentleMax())
                .setTooltip(Component.translatable("config.roadweaver.elevation_gentle_max.tooltip"))
                .setMin(0).setMax(16)
                .setSaveConsumer(cfg::setElevationGentleMax)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_gentle_weight"), cfg.elevationGentleWeight())
                .setDefaultValue(def.elevationGentleWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_gentle_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationGentleWeight)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_steep_weight"), cfg.elevationSteepWeight())
                .setDefaultValue(def.elevationSteepWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_steep_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationSteepWeight)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_cliff_threshold"), cfg.elevationCliffThreshold())
                .setDefaultValue(def.elevationCliffThreshold())
                .setTooltip(Component.translatable("config.roadweaver.elevation_cliff_threshold.tooltip"))
                .setMin(1).setMax(32)
                .setSaveConsumer(cfg::setElevationCliffThreshold)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.elevation_cliff_weight"), cfg.elevationCliffWeight())
                .setDefaultValue(def.elevationCliffWeight())
                .setTooltip(Component.translatable("config.roadweaver.elevation_cliff_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setElevationCliffWeight)
                .build());
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.elevation_segmented_cost"), cfg.elevationSegmentedCost())
                .setDefaultValue(def.elevationSegmentedCost())
                .setTooltip(Component.translatable("config.roadweaver.elevation_segmented_cost.tooltip"))
                .setSaveConsumer(cfg::setElevationSegmentedCost)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.deviation_free_radius"), cfg.deviationFreeRadius())
                .setDefaultValue(def.deviationFreeRadius())
                .setTooltip(Component.translatable("config.roadweaver.deviation_free_radius.tooltip"))
                .setMin(0).setMax(2048)
                .setSaveConsumer(cfg::setDeviationFreeRadius)
                .build());
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.deviation_squared"), cfg.deviationSquared())
                .setDefaultValue(def.deviationSquared())
                .setTooltip(Component.translatable("config.roadweaver.deviation_squared.tooltip"))
                .setSaveConsumer(cfg::setDeviationSquared)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.deviation_hard_cap"), cfg.deviationHardCap())
                .setDefaultValue(def.deviationHardCap())
                .setTooltip(Component.translatable("config.roadweaver.deviation_hard_cap.tooltip"))
                .setMin(0).setMax(8192)
                .setSaveConsumer(cfg::setDeviationHardCap)
                .build());

        // ── 0.8.418 奖励惩罚补全：蜿蜒旋钮（对抗「道路过直」）──────────────────
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.contour_factor_floor"), cfg.contourFactorFloor())
                .setDefaultValue(def.contourFactorFloor())
                .setTooltip(Component.translatable("config.roadweaver.contour_factor_floor.tooltip"))
                .setMin(0.02).setMax(1.0)
                .setSaveConsumer(cfg::setContourFactorFloor)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.near_water_decay_radius"), cfg.nearWaterDecayRadius())
                .setDefaultValue(def.nearWaterDecayRadius())
                .setTooltip(Component.translatable("config.roadweaver.near_water_decay_radius.tooltip"))
                .setMin(1).setMax(64)
                .setSaveConsumer(cfg::setNearWaterDecayRadius)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.near_water_decay_rate"), cfg.nearWaterDecayRate())
                .setDefaultValue(def.nearWaterDecayRate())
                .setTooltip(Component.translatable("config.roadweaver.near_water_decay_rate.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setNearWaterDecayRate)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.contour_bonus"), cfg.contourBonus())
                .setDefaultValue(def.contourBonus())
                .setTooltip(Component.translatable("config.roadweaver.contour_bonus.tooltip"))
                .setMin(-100).setMax(0)
                .setSaveConsumer(cfg::setContourBonus)
                .build());

        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.accumulated_slope_window"), cfg.accumulatedSlopeWindow())
                .setDefaultValue(def.accumulatedSlopeWindow())
                .setTooltip(Component.translatable("config.roadweaver.accumulated_slope_window.tooltip"))
                .setMin(2).setMax(16)
                .setSaveConsumer(cfg::setAccumulatedSlopeWindow)
                .build());
        category.addEntry(eb
                .startIntField(Component.translatable("config.roadweaver.accumulated_slope_weight"), cfg.accumulatedSlopeWeight())
                .setDefaultValue(def.accumulatedSlopeWeight())
                .setTooltip(Component.translatable("config.roadweaver.accumulated_slope_weight.tooltip"))
                .setMin(0)
                .setSaveConsumer(cfg::setAccumulatedSlopeWeight)
                .build());

        // ===== 0.8.409 麻花/重叠判定（禁麻花 + 允许丁字/十字）=====

        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.braid_min_direction_dot"), cfg.braidMinDirectionDot())
                .setDefaultValue(def.braidMinDirectionDot())
                .setTooltip(Component.translatable("config.roadweaver.braid_min_direction_dot.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setBraidMinDirectionDot)
                .build());
        category.addEntry(eb
                .startDoubleField(Component.translatable("config.roadweaver.braid_near_parallel_dot"), cfg.braidNearParallelDot())
                .setDefaultValue(def.braidNearParallelDot())
                .setTooltip(Component.translatable("config.roadweaver.braid_near_parallel_dot.tooltip"))
                .setMin(0).setMax(1.0)
                .setSaveConsumer(cfg::setBraidNearParallelDot)
                .build());
        // 0.8.418 起移除「允许丁字/十字路口」条目：这个开关在全仓库没有任何读取方
        // （RoadSnapService 只用 braidMinDirectionDot / braidNearParallelDot /
        // braidAllowParallelOverlap 三个），留在界面上等于骗玩家去调一个死参数。
        // 字段与语言键保留，以免破坏已有配置文件、也便于将来真接上时一键恢复。
        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.braid_allow_parallel_overlap"), cfg.braidAllowParallelOverlap())
                .setDefaultValue(def.braidAllowParallelOverlap())
                .setTooltip(Component.translatable("config.roadweaver.braid_allow_parallel_overlap.tooltip"))
                .setSaveConsumer(cfg::setBraidAllowParallelOverlap)
                .build());
    }

    private static void buildClientCategory(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable("config.roadweaver.category.test"));
        ClientConfig cfg = conf.client();
        ClientConfig def = defaultConf.client();

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.loading_tips_enabled"), cfg.loadingTipsEnabled())
                .setDefaultValue(def.loadingTipsEnabled())
                .setTooltip(Component.translatable("config.roadweaver.loading_tips_enabled.tooltip"))
                .setSaveConsumer(cfg::setLoadingTipsEnabled)
                .build());

        category.addEntry(eb
                .startBooleanToggle(Component.translatable("config.roadweaver.loading_progress_enabled"), cfg.loadingProgressEnabled())
                .setDefaultValue(def.loadingProgressEnabled())
                .setTooltip(Component.translatable("config.roadweaver.loading_progress_enabled.tooltip"))
                .setSaveConsumer(cfg::setLoadingProgressEnabled)
                .build());
    }
}
