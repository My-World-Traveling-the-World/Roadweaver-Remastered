/* 文件职责：暴露 StructureManager 的私有成员供结构解析使用（@Accessor）。 */
package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * StructureManager 字段访问器，用于获取 level 实例
 */
@Mixin(StructureManager.class)
public interface StructureManagerAccessor {
    @Accessor("level")
    LevelAccessor roadweaver$getLevel();
}
