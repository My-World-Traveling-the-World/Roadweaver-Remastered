package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.shiroha233.roadweaver.client.loading.LoadingGenerationOverlayRenderer;
import net.shiroha233.roadweaver.client.tips.LoadingTipsRenderer;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void roadweaver$renderProgress(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        LoadingGenerationOverlayRenderer.render(graphics);
    }
}
