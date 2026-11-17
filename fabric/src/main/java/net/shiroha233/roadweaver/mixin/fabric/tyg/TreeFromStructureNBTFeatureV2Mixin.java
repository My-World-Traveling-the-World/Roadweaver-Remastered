/* 文件职责：同上 V2 版本（该 mod 有两个版本的同类特征，需分别拦）。 */
package net.shiroha233.roadweaver.mixin.fabric.tyg;

import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.shiroha233.roadweaver.persistence.RoadPositionQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 兼容V2模板树放置时检查道路占地，避免树模板覆盖已规划道路。 */
@Pseudo
@Mixin(targets = "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeatureV2")
public class TreeFromStructureNBTFeatureV2Mixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void roadweaver$blockTYGV2TreeOnRoad(FeaturePlaceContext<?> context,
                                                  CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RoadPositionQuery.isOnRoad(context.level(), context.origin())) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
