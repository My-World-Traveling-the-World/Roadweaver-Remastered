package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.shiroha233.roadweaver.compat.SinglePoolElementTemplateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor extends SinglePoolElementTemplateAccess {
    @Override
    @Invoker("getTemplate")
    StructureTemplate roadweaver$getTemplate(StructureTemplateManager manager);
}
