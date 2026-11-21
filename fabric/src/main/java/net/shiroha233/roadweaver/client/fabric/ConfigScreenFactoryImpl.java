/* 文件职责：配置菜单的Fabric入口；持久配置只在确认保存后整份发布。 */
package net.shiroha233.roadweaver.client.fabric;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.PresetService;
import net.shiroha233.roadweaver.config.meta.ConfigKeyTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigScreenFactoryImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");
    private ConfigScreenFactoryImpl() {}

    public static Screen createConfigScreen(Screen parent) {
        PresetService.reload();
        var draft = ConfigService.get().snapshot();
        draft.sanitize();
        var builder = ConfigBuilder.create().setParentScreen(parent)
                .setTitle(Component.translatable("config.roadweaver.title"))
                .setSavingRunnable(() -> ConfigService.applyAndSave(draft));
        MetadataConfigScreen.populate(builder, draft);
        // 数量来自实际菜单清单，失败直接暴露，不能以自证日志掩盖控件漏项。
        LOGGER.info("【配置菜单已按功能与层级构建】 categories={} keys={}",
                ConfigKeyTable.byCategory().size(), ConfigKeyTable.all().size());
        return builder.build();
    }
}
