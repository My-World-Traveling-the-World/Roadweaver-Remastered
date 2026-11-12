/* 文件职责：访问结构模板调色板的兼容层（隔离版本差异）。 */
package net.shiroha233.roadweaver.compat;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public interface StructureTemplatePaletteAccess {
    List<StructureTemplate.Palette> roadweaver$getPalettes();
}
