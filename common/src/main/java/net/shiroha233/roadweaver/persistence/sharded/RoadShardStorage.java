/* 文件职责：提供道路 ChunkPos 分片存储的稳定门面，隔离调用方与具体文件实现。 */
package net.shiroha233.roadweaver.persistence.sharded;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;

import java.util.Collection;
import java.util.List;
import net.shiroha233.roadweaver.persistence.chunk.RoadSnapshot;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 道路数据存储门面。 */
public final class RoadShardStorage {
    private RoadShardStorage() {}

    public static void addRoad(ServerLevel level, RoadData road) {
        if (road != null) addRoads(level, List.of(road));
    }

    public static Set<Long> addRoads(ServerLevel level, Collection<RoadData> roads) {
        if (!RoadLifecycle.allowsWrites() || !isOverworld(level)) return Set.of();
        var committed = RoadFileStorage.addRoads(level, roads);
        refreshConstruction(level, committed.changed());
        return committed.delivered();
    }

    /** 索引提交成功后一起作废两处旧结论；否则回填会继续相信“这个区块没有路”。 */
    static void refreshConstruction(ServerLevel level, Set<Long> changed) {
        if (changed.isEmpty()) return;
        RoadWorldgenPlanCache.invalidate(level, changed);
        net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService
                .unmarkNoConstructionRequired(level, changed);
    }

    public static Collection<RoadSnapshot.Entry> storedEntries(ServerLevel level) {
        return RoadFileStorage.storedEntries(level);
    }

    public static void preload(ServerLevel level) {
        if (isOverworld(level)) RoadFileStorage.preload(level);
    }

    public static List<RoadData> queryRect(ServerLevel level,
                                           int minBlockX, int minBlockZ,
                                           int maxBlockX, int maxBlockZ) {
        return isOverworld(level)
                ? RoadFileStorage.queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                : List.of();
    }

    public static List<RoadData> queryChunk(ServerLevel level, ChunkPos chunkPos) {
        return isOverworld(level) ? RoadFileStorage.queryChunk(level, chunkPos) : List.of();
    }

    public static List<RoadData> queryChunk(ServerLevel level, int chunkX, int chunkZ) {
        return isOverworld(level) ? RoadFileStorage.queryChunk(level, chunkX, chunkZ) : List.of();
    }

    public static CompletableFuture<List<RoadData>> queryRectAsync(ServerLevel level,
                                                                    int minBlockX, int minBlockZ,
                                                                    int maxBlockX, int maxBlockZ) {
        if (!isOverworld(level)) return CompletableFuture.completedFuture(List.of());
        return RoadFileStorage.queryRectAsync(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public static boolean readyForRecovery(ServerLevel level) {
        return RoadFileStorage.readyForRecovery(level);
    }

    /** 之前可能从部分视图编译过空计划；重读后让实际道路参与下一次编译。 */
    public static void refreshRecoveredPlans(ServerLevel level) {
        RoadWorldgenPlanCache.invalidate(level);
    }

    public static List<RoadData> loadAll(ServerLevel level) {
        return isOverworld(level) ? RoadFileStorage.loadAll(level) : List.of();
    }

    public static RoadData loadByFingerprint(ServerLevel level, long fingerprint) {
        return isOverworld(level) ? RoadFileStorage.loadByFingerprint(level, fingerprint) : null;
    }

    public static void deleteRoad(ServerLevel level, long fingerprint) {
        if (!RoadLifecycle.allowsWrites()) return;
        if (!isOverworld(level)) return;
        // 删除只需刷新旧占地；没有新增路面需要唤醒。
        RoadWorldgenPlanCache.invalidate(level, RoadFileStorage.deleteRoad(level, fingerprint));
    }

    public static void replaceRoad(ServerLevel level, long oldFingerprint, RoadData newRoad) {
        replaceRoads(level, List.of(new RoadReplacement(oldFingerprint, newRoad)));
    }

    public static void replaceRoads(ServerLevel level, Collection<RoadReplacement> replacements) {
        if (!RoadLifecycle.allowsWrites()) return;
        if (!isOverworld(level) || replacements == null || replacements.isEmpty()) return;
        var committed = RoadFileStorage.replaceRoads(level, replacements);
        refreshConstruction(level, committed.changed());
    }

    public static long computeFingerprint(RoadData road) {
        return RoadFileStorage.computeFingerprint(road);
    }

    public static boolean hasAnyRoad(ServerLevel level) {
        return isOverworld(level) && RoadFileStorage.hasAnyRoad(level);
    }

    public static boolean hasRoadInRect(ServerLevel level,
                                        int minBlockX, int minBlockZ,
                                        int maxBlockX, int maxBlockZ) {
        return isOverworld(level)
                && RoadFileStorage.hasRoadInRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public static void flushAll(ServerLevel level) {
        if (isOverworld(level)) RoadFileStorage.flush(level);
    }

    public static void clearAll(ServerLevel level) {
        if (!RoadLifecycle.allowsWrites()) return;
        if (!isOverworld(level)) return;
        RoadFileStorage.clearAll(level);
        RoadWorldgenPlanCache.invalidate(level);
    }

    public static void shutdown() {
        RoadFileStorage.shutdown();
        RoadWorldgenPlanCache.clearAll();
    }

    public static void closeConnection(ServerLevel level) {
        if (!isOverworld(level)) return;
        RoadWorldgenPlanCache.clear(level);
        RoadFileStorage.close(level);
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

}
