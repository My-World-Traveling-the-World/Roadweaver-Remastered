/* 文件职责：暴露 ServerChunkCache 的私有方法供结构查询使用（@Invoker，不是行为改动）。 */
package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkResult;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Fabric映射非阻塞区块提交端口，避免公共层直接绑定私有平台实现。 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheFutureInvoker extends NonBlockingChunkFutureAccess {
    @Override
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> roadweaver$invokeGetChunkFutureMainThread(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
