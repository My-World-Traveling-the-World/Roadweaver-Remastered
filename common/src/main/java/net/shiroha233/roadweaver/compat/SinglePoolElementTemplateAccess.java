/* 文件职责：访问 SinglePoolElement 模板的兼容层（隔离版本差异）。 */
package net.shiroha233.roadweaver.compat;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public interface SinglePoolElementTemplateAccess {
    StructureTemplate roadweaver$getTemplate(StructureTemplateManager manager);
}
