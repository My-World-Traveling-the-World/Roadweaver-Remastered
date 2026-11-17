/* 文件职责：在 Fabric 服务端关卡准备阶段触发 RoadWeaver 初始化。 */
package net.shiroha233.roadweaver.mixin.fabric;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.compat.GreatWallPrewarmCompat;
import net.shiroha233.roadweaver.compat.RoadStartupGate;
import net.shiroha233.roadweaver.apiimpl.LoadSignalBridge;
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
            // API 的就绪是前置条件，不能用 Mixin 的优先级或“没装长城”推断河流已完成。
            boolean riversInstalled = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isModLoaded("streamsreflowing");
            var prerequisites = RoadStartupGate.await(
                    () -> LoadSignalBridge.initialRiversReady(riversInstalled),
                    GreatWallPrewarmCompat::isPrewarmComplete,
                    () -> GreatWallPrewarmCompat.awaitPrewarmForInitialGeneration(level));
            if (prerequisites.isDone() && !prerequisites.isCompletedExceptionally()) {
                LOGGER.info("【河流已就绪且长城就绪或缺席，开始初始道路生成】 " + "ROAD_INITIAL_GEN_START path=server-thread rivers=ready wall=ready-or-absent");
                InitialGenManager.begin(level);
                InitialGenManager.blockUntilDone(level);
                return;
            }
            // 让当前注入返回，河流/长城自己的后续启动钩子才能运行，避免同线程相互等候。
            LOGGER.info("【初始道路异步等待前置条件，顺序为河流再长城】 " + "ROAD_INITIAL_GEN_WAIT path=async prerequisites=river-then-wall");
            prerequisites.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("【前置条件失败或取消，初始道路尚未启动】 " + "ROAD_INITIAL_GEN_NOT_STARTED reason=prerequisite-failed-or-cancelled", failure);
                    return;
                }
                server.execute(() -> {
                    // 完成通知可能排在停服之后，不能向旧服务端重新投放初始道路任务。
                    if (server.isStopped() || prerequisites.isCancelled()
                            || net.shiroha233.roadweaver.runtime.RoadLifecycle.isStopping()) return;
                    InitialGenManager.begin(level);
                    InitialGenManager.blockUntilDone(level);
                });
            });
        }
    }

}
