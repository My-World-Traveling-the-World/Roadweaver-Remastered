/* 文件职责：配置界面「桥梁」分区 —— 只构造控件与绑定，不做业务判断。 */
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
 * 桥梁配置分区。
 *
 * <p>桥模板、桥墩与跨水判定。</p>
 *
 * <p>从 {@code ConfigScreenFactoryImpl} 抽出的一个分区：原文件 1791 行、12 个分区挤在一起，
 * 改一个分区要翻过另外 11 个。抽取是<b>纯搬运，零逻辑改动</b>。</p>
 */
public final class BridgeSection {

    private BridgeSection() {
    }

    /** 构建该分区（参数与其余分区一致：builder / 条目构造器 / 当前配置 / 默认配置）。 */
    public static void build(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig conf, ModConfig defaultConf) {
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
}
