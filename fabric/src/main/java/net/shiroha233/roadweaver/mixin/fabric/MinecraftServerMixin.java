/* 文件职责：在 Fabric 服务端关卡准备阶段触发 RoadWeaver 初始化。 */
package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.GreatWallPrewarmCompat;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinecraftServer Mixin
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver");
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void roadweaver$preloadBeforePrepareLevels(ChunkProgressListener listener, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        ServerLevel level = server.overworld();
        if (level == null) return;
        RoadShardStorage.preload(level);
        RoadConstructionLedger.preload(level);
        if (InitialGenManager.shouldRunInitialGeneration(level)) {
            // The Great Wall owns this same hook at prepareLevels HEAD with priority 950; a HEAD
            // injection applied later is inserted in front, so the wall's handler runs first and
            // hands the thread over only after its backfill has been written to the world. In that
            // normal case the gate is already open here and the roads can be laid synchronously, on
            // the server thread, while the loading screen is still up - which is what the player
            // expects and what keeps every road write on the owning thread.
            //
            // The order is not guaranteed by contract, though, and a synchronous wait here would
            // deadlock if this handler ever ran first: the wall compiles inside its own handler, so
            // parking this thread before that handler runs parks the wall too. So the fast path is
            // taken only when the gate is provably open; otherwise the wait goes back off the server
            // thread, exactly as before. Two markers, so a log can tell which path was taken.
            if (GreatWallPrewarmCompat.isPrewarmComplete()) {
                LOGGER.info("ROAD_INITIAL_GEN_START path=server-thread gate=open");
                InitialGenManager.begin(level);
                InitialGenManager.blockUntilDone(level);
                return;
            }
            LOGGER.info("ROAD_INITIAL_GEN_WAIT path=off-thread gate=closed");
            Thread gate = new Thread(() -> {
                try {
                    GreatWallPrewarmCompat.awaitPrewarmForInitialGeneration(level).join();
                } catch (Throwable failure) {
                    LOGGER.warn("RoadWeaver could not wait for the Great Wall; starting initial "
                            + "generation anyway.", failure);
                }
                server.execute(() -> {
                    InitialGenManager.begin(level);
                    InitialGenManager.blockUntilDone(level);
                });
            }, "RoadWeaver-WallGate");
            gate.setDaemon(true);
            gate.start();
        }
    }

}
