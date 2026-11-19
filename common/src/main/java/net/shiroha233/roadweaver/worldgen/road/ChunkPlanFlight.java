/* 文件职责：共享同一版本的在途编译，等待者中断不取消其他请求。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** 只有拥有者执行编译；不能在会话锁内等待，否则完成发布无法取得同一把锁。 */
final class ChunkPlanFlight {
    record Key(long chunk, RoadWorldgenPlanCache.PlanConfig config, long revision) {}
    final Thread owner = Thread.currentThread();
    final CompletableFuture<Void> completed = new CompletableFuture<>();
    int waiters;

    void await() {
        // 重入不能等待自己，明确失败比永久卡住区块生成更容易定位。
        if (owner == Thread.currentThread()) throw new IllegalStateException("道路区块计划编译发生同键重入");
        try {
            completed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RoadChunkPlan.Deferred();
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("道路区块计划共享编译失败", cause);
        }
    }
}
