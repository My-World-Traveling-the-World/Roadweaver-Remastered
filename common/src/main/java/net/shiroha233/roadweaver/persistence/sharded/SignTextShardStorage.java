/* 文件职责：路牌文本延迟写入存储门面。 */
package net.shiroha233.roadweaver.persistence.sharded;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.persistence.files.SignTextFileStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * 路牌文本延迟写入存储门面
 */
public final class SignTextShardStorage {
    private SignTextShardStorage() {}

    public static final int TYPE_DISTANCE = SignTextFileStorage.TYPE_DISTANCE;
    public static final int TYPE_SEA_QUESTION = SignTextFileStorage.TYPE_SEA_QUESTION;
    private static final int MAX_PENDING_PER_CHUNK = 256;
    private static final int MAX_PENDING = 10_000;
    // 相同主世界区块坐标在不同存档中会重复，队列与该世界的生命周期绑定。
    private static final Map<ServerLevel, PendingQueue> BY_WORLD = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static PendingQueue queue(ServerLevel level) {
        return BY_WORLD.computeIfAbsent(level, ignored -> new PendingQueue());
    }

    public record PendingSignWrite(BlockPos pos, int signType, String payload, int retryCount, long firstQueuedAtMs) {
        public PendingSignWrite withRetry() {
            return new PendingSignWrite(pos, signType, payload, retryCount + 1, firstQueuedAtMs);
        }

        private SignTextFileStorage.PendingSignWrite toPersistentWrite() {
            return new SignTextFileStorage.PendingSignWrite(pos, signType, payload);
        }
    }

    public static void upsert(ServerLevel level, BlockPos pos, int signType, String payload) {
        if (!isOverworld(level) || pos == null) return;
        putMemory(level, new PendingSignWrite(pos, signType, payload, 0, System.currentTimeMillis()));
    }

    public static void upsertBatch(ServerLevel level, Collection<PendingSignWrite> writes) {
        if (!isOverworld(level) || writes == null || writes.isEmpty()) return;
        ArrayList<SignTextFileStorage.PendingSignWrite> batch = new ArrayList<>(writes.size());
        for (PendingSignWrite write : writes) {
            if (write == null || write.pos() == null) continue;
            batch.add(write.toPersistentWrite());
        }
        SignTextFileStorage.upsertBatch(level, batch);
    }

    public static List<SignTextFileStorage.PendingSignText> queryByChunk(ServerLevel level, int chunkX, int chunkZ, int limit) {
        return isOverworld(level) ? SignTextFileStorage.queryByChunk(level, chunkX, chunkZ, limit) : List.of();
    }

    public static void deleteByIds(ServerLevel level, List<Long> ids) {
        if (!isOverworld(level)) return;
        SignTextFileStorage.deleteByIds(level, ids);
    }

    public static List<PendingSignWrite> takeMemoryByChunk(ServerLevel level, int chunkX, int chunkZ, int limit) {
        if (!isOverworld(level) || limit <= 0) return List.of();
        var q = BY_WORLD.get(level);
        return q == null ? List.of() : q.take(ChunkPos.asLong(chunkX, chunkZ), limit);
    }

    public static void requeue(ServerLevel level, PendingSignWrite write) {
        if (isOverworld(level) && write != null && write.pos() != null)
            queue(level).put(write.withRetry(), true);
    }

    public static boolean hasMemoryPending(ServerLevel level, int chunkX, int chunkZ) {
        if (!isOverworld(level)) return false;
        var q = BY_WORLD.get(level);
        return q != null && q.has(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void flushPending(ServerLevel level) {
        if (!isOverworld(level)) return;
        var q = BY_WORLD.get(level);
        if (q != null) q.flush(batch -> upsertBatch(level, batch));
    }

    public static void clearLevel(ServerLevel level) {
        BY_WORLD.remove(level);
    }

    public static void clearAllMemory() {
        BY_WORLD.clear();
    }

    private static void putMemory(ServerLevel level, PendingSignWrite write) {
        var q = queue(level);
        if (q.put(write, false)) q.flush(batch -> upsertBatch(level, batch));
    }

    static final class PendingQueue {
        private final Map<Long, Map<Long, PendingSignWrite>> buckets = new HashMap<>();
        private int size;

        synchronized boolean put(PendingSignWrite write, boolean retry) {
            var pos = write.pos();
            var bucket = buckets.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new HashMap<>());
            var previous = retry ? bucket.putIfAbsent(pos.asLong(), write) : bucket.put(pos.asLong(), write);
            if (previous == null) size++;
            return bucket.size() > MAX_PENDING_PER_CHUNK || size > MAX_PENDING;
        }

        synchronized boolean has(long key) { return buckets.containsKey(key); }

        synchronized List<PendingSignWrite> take(long key, int limit) {
            var bucket = buckets.get(key);
            if (bucket == null) return List.of();
            var out = new ArrayList<PendingSignWrite>(Math.min(limit, bucket.size()));
            var it = bucket.values().iterator();
            while (out.size() < limit && it.hasNext()) {
                out.add(it.next());
                it.remove();
            }
            size -= out.size();
            if (bucket.isEmpty()) buckets.remove(key);
            return out;
        }

        synchronized void flush(Consumer<List<PendingSignWrite>> store) {
            if (size == 0) return;
            var batch = new ArrayList<PendingSignWrite>(size);
            for (var bucket : buckets.values()) batch.addAll(bucket.values());
            // 写盘成功后才能清内存；同一世界的取走和替换不能与写旧快照交错。
            store.accept(batch);
            buckets.clear();
            size = 0;
        }
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }


}
