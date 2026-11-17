/* 文件职责：平台桥：提交区块请求而不在服务端线程上等待。 */
package net.shiroha233.roadweaver.runtime;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ChunkResult;

/** 平台端口异步提交区块请求，调用方不得在服务端线程同步阻塞其完成。 */
public interface NonBlockingChunkFutureAccess {
    CompletableFuture<ChunkResult<ChunkAccess>> roadweaver$invokeGetChunkFutureMainThread(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
