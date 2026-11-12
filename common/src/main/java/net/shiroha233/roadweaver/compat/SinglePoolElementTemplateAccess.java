package net.shiroha233.roadweaver.compat;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public interface SinglePoolElementTemplateAccess {
    StructureTemplate roadweaver$getTemplate(StructureTemplateManager manager);
}
