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
import net.shiroha233.roadweaver.config.meta.ConfigKey;
import net.shiroha233.roadweaver.config.meta.ConfigKeyTable;
import net.shiroha233.roadweaver.config.meta.ConfigStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * Fabric 平台配置屏幕工厂实现
 */
public class ConfigScreenFactoryImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.roadweaver.title"));

        PresetService.reload();
        ModConfig conf = ConfigService.get();
        ModConfig defaultConf = new ModConfig();
        builder.setSavingRunnable(ConfigService::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        StructurePredictionSection.build(builder, eb, conf, defaultConf);
        
        PlanningSection.build(builder, eb, conf, defaultConf);
        TerrainProfileSection.build(builder, eb, conf, defaultConf);
        
        
        RoadGenerationSection.build(builder, eb, conf, defaultConf);
        
        SurfaceSettingsSection.build(builder, eb, conf, defaultConf);
        
        BridgeSection.build(builder, eb, conf, defaultConf);
        
        RoadsideStructuresSection.build(builder, eb, conf, defaultConf);

        RoadsideVillageSection.build(builder, eb, conf, defaultConf);
        
        PerformanceSection.build(builder, eb, conf, defaultConf);
        
        RuntimeGpuSection.build(builder, eb, conf, defaultConf);
        
        PathfindingCostsSection.build(builder, eb, conf, defaultConf);
        
        ClientSection.build(builder, eb, conf, defaultConf);

        logMetadataCoverage();
        return builder.build();
    }

    /**
     * 打一行配置元数据的自证日志。
     *
     * <h2>为什么需要这一行（2026-11-13）</h2>
     * <p>{@code config/meta} 那套元数据有 2000 多行、设计得挺完整
     * （每个键带 {@code ConfigStatus}，能区分「已接线 / 未接线 / 已废弃 / 实验性」），
     * 但在此之前<b>生产代码里零引用</b> —— 只有测试在用，等于一份没人读的说明书。</p>
     *
     * <p>「谁提供，谁自报」是这个项目早就定下的原则（见 {@code LoadSignalBridge}
     * 那几行 api-signals 自证）。这里同理：打开配置界面时把元数据的真实规模与
     * 接线状况打出来，这样：</p>
     * <ul>
     *   <li>元数据表被真的读到了（不是摆设）；</li>
     *   <li>「有几个键其实不生效」这件事在日志里可见，
     *       与界面上的文案、以及 {@code ConfigUiHonestyTest} 的守卫互相印证；</li>
     *   <li>以后加了新键却没填元数据，这里的分母会立刻对不上。</li>
     * </ul>
     *
     * <p>日志级别用 INFO 但只在<b>打开配置界面</b>时打一次 —— 不是热路径，不刷屏。</p>
     */
    private static void logMetadataCoverage() {
        try {
            List<ConfigKey> keys = ConfigKeyTable.all();
            int unwired = 0;
            int deprecated = 0;
            int experimental = 0;
            for (int i = 0; i < keys.size(); i++) {
                ConfigStatus status = keys.get(i).status();
                if (status == ConfigStatus.UNWIRED) {
                    unwired++;
                } else if (status == ConfigStatus.DEPRECATED) {
                    deprecated++;
                } else if (status == ConfigStatus.EXPERIMENTAL) {
                    experimental++;
                }
            }
            LOGGER.info("RoadWeaver config ui: categories={} keys={} active={} unwired={} "
                            + "deprecated={} experimental={}",
                    ConfigKeyTable.byCategory().size(), keys.size(),
                    keys.size() - unwired - deprecated - experimental,
                    unwired, deprecated, experimental);
        } catch (Throwable t) {
            // 自证日志绝不能影响界面打开
            LOGGER.warn("RoadWeaver config ui: metadata self-report failed: {}", t.toString());
        }
    }

}
