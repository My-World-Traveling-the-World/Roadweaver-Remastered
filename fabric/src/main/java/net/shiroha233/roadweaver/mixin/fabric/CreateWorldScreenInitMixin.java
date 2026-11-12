package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.shiroha233.roadweaver.config.structure.StructureDiscoveryService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 用于在创建世界界面初始化时获取 RegistryAccess 并发现结构
 * 
 * 这是解决 Forge/Fabric 版结构选择列表无法提取的关键 Mixin。
 * 通过在创建世界界面初始化时获取 RegistryAccess，可以提前发现所有可用的结构。
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenInitMixin extends Screen {

    @Shadow
    @Final
    WorldCreationUiState uiState;

    protected CreateWorldScreenInitMixin() {
        super(Component.empty());
    }

    /**
     * 在 CreateWorldScreen 初始化完成后注入，避免 1.21.1 构造器签名变化导致注入失效。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void onInitEnd(CallbackInfo ci) {
        try {
            WorldCreationContext settings = uiState != null ? uiState.getSettings() : null;
            if (settings != null) {
                RegistryAccess.Frozen registryAccess = settings.worldgenLoadContext();
                if (registryAccess == null) return;

                Registry<Structure> structureRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE);
                Registry<LevelStem> levelStemRegistry;
                try {
                    levelStemRegistry = registryAccess.registryOrThrow(Registries.LEVEL_STEM);
                } catch (Exception missingLevelStem) {
                    // 0.8.314：创建世界界面的 worldgenLoadContext 只有静态注册表，没有
                    // dimension（LEVEL_STEM）动态注册表。旧代码在这里转调
                    // StructureDiscoveryService.discoverFromRegistryAccess，而它同样
                    // registryOrThrow(LEVEL_STEM)，于是每局必抛
                    // "结构发现失败 / Missing registry: minecraft:dimension" 并打整段堆栈。
                    // 该分支永远拿不到数据：结构发现会在世界加载后由服务端重新做一遍
                    // （真机 "发现 877 个结构和 84 个标签"），这里直接跳过即可。
                    return;
                }
                StructureDiscoveryService.discoverFromRegistries(structureRegistry, levelStemRegistry);
            }
        } catch (Exception e) {
            // 忽略错误，不影响正常流程
        }
    }
}
