package net.shiroha233.roadweaver.mixin.fabric.tyg;

import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.shiroha233.roadweaver.persistence.RoadPositionQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents Oh The Trees You'll Grow V2 template trees from being placed on roads. */
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
