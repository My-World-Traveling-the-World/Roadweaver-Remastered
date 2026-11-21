/* 文件职责：新菜单必须消费完整限定配置清单，禁止遗留无效旋钮或第二套手写菜单。 */
package net.shiroha233.roadweaver.architecture;

import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.meta.ConfigKeyTable;
import net.shiroha233.roadweaver.config.meta.ConfigOption;
import net.shiroha233.roadweaver.config.meta.ConfigStatus;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public final class ConfigUiHonestyTest {
    @Test
    void 界面必须从完整限定清单生成且不得暴露无效项() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root, "找不到仓库根不能静默跳过菜单守卫");
        Path ui = root.resolve("fabric/src/main/java/net/shiroha233/roadweaver/client/fabric");
        String factory = Files.readString(ui.resolve("ConfigScreenFactoryImpl.java"));
        String renderer = Files.readString(ui.resolve("MetadataConfigScreen.java"));
        assertTrue(factory.contains("MetadataConfigScreen.populate(builder, draft)"));
        assertTrue(factory.contains("ConfigService.get().snapshot()"));
        assertTrue(factory.contains("ConfigService.applyAndSave(draft)"));
        assertTrue(renderer.contains("ConfigOption.bind(draft)"));
        assertTrue(renderer.contains("option::save"), "控件必须调用受测的真实保存绑定");
        assertTrue(renderer.contains(".setExpanded(false)"));
        assertTrue(renderer.contains("new OpenStructureSelectionEntry()"));
        assertTrue(renderer.contains("new OpenPresetEditorEntry()"));
        assertFalse(renderer.contains("openclAccurateSamplingEnabled"));
        assertTrue(renderer.contains("config.roadweaver.accurate_cpu_fixed"));
        try (var files = Files.list(ui)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith("Section.java")),
                    "不能保留另一套范围和绑定会漂移的手写Section");
        }
        var options = ConfigOption.bind(new ModConfig());
        assertEquals(ConfigKeyTable.all().size(), options.size());
        assertTrue(options.size() > 190, "空清单不能证明菜单覆盖");
        for (var option : options) {
            assertNotEquals(ConfigStatus.UNWIRED, option.key().status(), option.id());
            assertNotEquals(ConfigStatus.DEPRECATED, option.key().status(), option.id());
        }
    }
}
