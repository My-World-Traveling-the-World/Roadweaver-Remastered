/* 文件职责：按世界路径路由道路 ChunkPos 存储，并保留旧文件存储门面的对外语义。 */
package net.shiroha233.roadweaver.persistence.files;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.chunk.RoadChunkStore;
import net.shiroha233.roadweaver.persistence.chunk.RoadChunkChanges;
import net.shiroha233.roadweaver.persistence.chunk.RoadSnapshot;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 道路文件存储门面。
 *
 * <p>道路数据本身由 {@link RoadChunkStore} 管理；这里唯一的静态状态是按规范化世界路径隔离的
 * 实例路由表，不共享任何道路快照。世界关闭时由 {@link #close(ServerLevel)} 移除对应实例。</p>
 */
public final class RoadFileStorage {
    private static final String CATEGORY = "roads";
    private static final ConcurrentMap<Path, RoadChunkStore> STORES = new ConcurrentHashMap<>();

    private RoadFileStorage() {}

    public static void addRoad(ServerLevel level, RoadData road) {
        if (!isOverworld(level)) return;
        store(level).addRoad(road);
    }

    public static RoadChunkChanges addRoads(ServerLevel level, Collection<RoadData> roads) {
        return isOverworld(level) ? store(level).addRoads(roads) : RoadChunkChanges.EMPTY;
    }

    public static void preload(ServerLevel level) {
        if (isOverworld(level)) store(level).preload();
    }

    public static List<RoadData> queryRect(ServerLevel level,
                                           int minBlockX, int minBlockZ,
                                           int maxBlockX, int maxBlockZ) {
        return isOverworld(level)
                ? store(level).queryRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                : List.of();
    }

    public static CompletableFuture<List<RoadData>> queryRectAsync(ServerLevel level,
                                                                     int minBlockX, int minBlockZ,
                                                                     int maxBlockX, int maxBlockZ) {
        if (!isOverworld(level) || RoadLifecycle.isStopping()) {
            return CompletableFuture.completedFuture(List.of());
        }
        long epoch = ThreadPoolManager.currentEpoch();
        return ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP_READ, () ->
                ThreadPoolManager.isEpoch(epoch)
                        ? queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                        : List.of());
    }

    public static List<RoadData> queryChunk(ServerLevel level, ChunkPos chunkPos) {
        return isOverworld(level) && chunkPos != null
                ? store(level).queryChunk(chunkPos)
                : List.of();
    }

    public static List<RoadData> queryChunk(ServerLevel level, int chunkX, int chunkZ) {
        return isOverworld(level) ? store(level).queryChunk(chunkX, chunkZ) : List.of();
    }

    public static boolean readyForRecovery(ServerLevel level) {
        return store(level).readyForRecovery(System.nanoTime());
    }

    public static Collection<RoadSnapshot.Entry> storedEntries(ServerLevel level) {
        return isOverworld(level) ? store(level).storedEntries() : List.of();
    }

    public static List<RoadData> loadAll(ServerLevel level) {
        return isOverworld(level) ? store(level).loadAll() : List.of();
    }

    public static RoadData loadByFingerprint(ServerLevel level, long fingerprint) {
        return isOverworld(level) ? store(level).loadByFingerprint(fingerprint) : null;
    }

    public static java.util.Set<Long> deleteRoad(ServerLevel level, long fingerprint) {
        return isOverworld(level) ? store(level).deleteRoad(fingerprint) : java.util.Set.of();
    }

    public static void replaceRoad(ServerLevel level, long oldFingerprint, RoadData newRoad) {
        if (isOverworld(level)) store(level).replaceRoad(oldFingerprint, newRoad);
    }

    public static RoadChunkChanges replaceRoads(ServerLevel level, Collection<RoadReplacement> replacements) {
        return isOverworld(level) ? store(level).replaceRoads(replacements) : RoadChunkChanges.EMPTY;
    }

    public static boolean hasAnyRoad(ServerLevel level) {
        return isOverworld(level) && store(level).hasAnyRoad();
    }

    public static boolean hasRoadInRect(ServerLevel level,
                                        int minBlockX, int minBlockZ,
                                        int maxBlockX, int maxBlockZ) {
        return isOverworld(level)
                && store(level).hasRoadInRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public static void flush(ServerLevel level) {
        if (isOverworld(level)) store(level).flush();
    }

    public static void clearAll(ServerLevel level) {
        if (isOverworld(level)) store(level).clear();
    }

    public static void close(ServerLevel level) {
        if (!isOverworld(level)) return;
        Path key = storagePath(level);
        RoadChunkStore removed = STORES.remove(key);
        if (removed != null) removed.close();
    }

    public static void shutdown() {
        for (RoadChunkStore store : STORES.values()) store.close();
        STORES.clear();
    }

    public static long computeFingerprint(RoadData road) {
        return RoadFingerprint.compute(road);
    }

    private static RoadChunkStore store(ServerLevel level) {
        Path path = storagePath(level);
        return STORES.computeIfAbsent(path, RoadChunkStore::new);
    }

    private static Path storagePath(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, CATEGORY).toAbsolutePath().normalize();
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }
}
