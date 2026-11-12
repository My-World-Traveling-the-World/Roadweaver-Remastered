/* 文件职责：平台桥：提交区块请求而不在服务端线程上等待。 */
package net.shiroha233.roadweaver.runtime;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ChunkResult;

/** Platform bridge for submitting a chunk request without waiting on the server thread. */
public interface NonBlockingChunkFutureAccess {
    CompletableFuture<ChunkResult<ChunkAccess>> roadweaver$invokeGetChunkFutureMainThread(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
