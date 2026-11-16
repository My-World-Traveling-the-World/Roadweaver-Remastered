/* 文件职责：统一逐列与网格的瓦片认领、重入保护、等待、关闭和条件释放。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 每个世界采样器独有一份；普通和加速请求各有在途表，沿用原模式隔离。 */
final class AccurateTileClaims {
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> normal = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> accelerated = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<Long>> held = ThreadLocal.withInitial(HashSet::new);
    private final Runnable ensureOpen;
    private final LongSupplier timeoutMs;
    private volatile IllegalStateException closed;

    AccurateTileClaims(Runnable ensureOpen, LongSupplier timeoutMs) {
        this.ensureOpen = ensureOpen;
        this.timeoutMs = timeoutMs;
    }

    Lease claim(Collection<Long> keys, boolean acceleratedOnly) {
        ConcurrentHashMap<Long, CompletableFuture<Void>> lane = acceleratedOnly ? accelerated : normal;
        Map<Long, CompletableFuture<Void>> owned = new LinkedHashMap<>();
        Map<Long, CompletableFuture<Void>> waiting = new LinkedHashMap<>();
        synchronized (lane) {
            if (closed != null) throw closed;
            ensureOpen.run();
            // 先检查整批，防止后面的重入错误留下前面已写入的孤儿认领。
            for (long key : keys) {
                if (held.get().contains(key)) {
                    throw new IllegalStateException("recursive accurate tile request on the same thread: " + key);
                }
                CompletableFuture<Void> existing = lane.get(key);
                if (existing != null) waiting.put(key, existing);
            }
            // 保留原整批策略：有冲突时不持有任何新认领，避免持有并等待。
            if (waiting.isEmpty()) {
                for (long key : keys) {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    lane.put(key, future);
                    owned.put(key, future);
                }
                held.get().addAll(owned.keySet());
            }
        }
        return new Lease(lane, owned, waiting);
    }

    void close(IllegalStateException failure) {
        // 标志先于清表发布，关闭与认领共用表锁，防止清表后又登记无人完成的 Future。
        closed = failure;
        closeLane(normal, failure);
        closeLane(accelerated, failure);
    }

    private static void closeLane(ConcurrentHashMap<Long, CompletableFuture<Void>> lane,
                                  IllegalStateException failure) {
        synchronized (lane) {
            lane.values().forEach(future -> future.completeExceptionally(failure));
            lane.clear();
        }
    }

    /** 只管理认领；结果完整性、保存与缓存发布仍由批次编排负责。 */
    final class Lease implements AutoCloseable {
        private final ConcurrentHashMap<Long, CompletableFuture<Void>> lane;
        private final Map<Long, CompletableFuture<Void>> owned;
        private final Map<Long, CompletableFuture<Void>> waiting;
        private boolean released;

        private Lease(ConcurrentHashMap<Long, CompletableFuture<Void>> lane,
                      Map<Long, CompletableFuture<Void>> owned, Map<Long, CompletableFuture<Void>> waiting) {
            this.lane = lane;
            this.owned = owned;
            this.waiting = waiting;
        }

        boolean isOwner() { return !owned.isEmpty(); }

        Set<Long> keys() { return Collections.unmodifiableSet(owned.keySet()); }

        void await() {
            if (!waiting.isEmpty()) SamplerStatics.joinWaitingTiles(waiting, lane, timeoutMs.getAsLong());
        }

        void succeed() { owned.values().forEach(future -> future.complete(null)); }

        void fail(Throwable failure) { owned.values().forEach(future -> future.completeExceptionally(failure)); }

        @Override
        public void close() {
            if (released) return;
            released = true;
            held.get().removeAll(owned.keySet());
            // 超时后可能已由别人接管；旧所有者只能释放与自己 Future 相同的认领。
            owned.forEach((key, future) -> lane.remove(key, future));
        }
    }
}
