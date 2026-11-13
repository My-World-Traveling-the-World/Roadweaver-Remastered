/* 文件职责：配置界面「规划（含 KNN 度数）」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 规划（含 KNN 度数）配置分区。
 *
 * <p>网络规划器选择与 KNN 参数；knnOnlyTitle 只在 KNN 生效时高亮，所以它跟着本分区走。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class PlanningSection {

    private PlanningSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
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

        // 2026-11-13：旧规划算法全部退役（用户拍板「全用最新的，旧的全部删除」）。
        // 算法选择器改为固定说明 —— 只剩 PIPELINE（测地距离 MST）一种。
        category.addEntry(eb
                .startTextDescription(Component.translatable(
                        "config.roadweaver.planning_algorithm.fixed_pipeline"))
                .setColor(0xFF88FF88)
                .build());

        // 0.8.420：KNN 路网拓扑参数已随旧规划器一并删除，不再出现在界面上。
        // （k / alpha / minAngleDeg / degreeCap 这四个字段仍在 PlanningConfig 里，
        //  但只被 ComponentConnector.connectComponents 读取，与「KNN 拓扑」无关。）
        boolean knnActive = false;
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

    private static Component knnOnlyTitle(String key, boolean knnActive) {
        Component title = Component.translatable(key);
        return knnActive ? title : Component.translatable("config.roadweaver.knn_inactive_marker", title);
    }}
