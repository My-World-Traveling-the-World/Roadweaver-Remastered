package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.shiroha233.roadweaver.compat.StructureTemplatePaletteAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor extends StructureTemplatePaletteAccess {
    @Override
    @Accessor("palettes")
    List<StructureTemplate.Palette> roadweaver$getPalettes();
}
