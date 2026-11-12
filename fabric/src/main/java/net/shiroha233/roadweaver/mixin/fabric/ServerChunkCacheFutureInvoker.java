package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkResult;
import net.shiroha233.roadweaver.runtime.NonBlockingChunkFutureAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Fabric production mapping for the non-blocking main-thread chunk submission method. */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheFutureInvoker extends NonBlockingChunkFutureAccess {
    @Override
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> roadweaver$invokeGetChunkFutureMainThread(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
