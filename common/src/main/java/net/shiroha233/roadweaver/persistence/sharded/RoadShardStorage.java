/* 文件职责：提供道路 ChunkPos 分片存储的稳定门面，隔离调用方与具体文件实现。 */
package net.shiroha233.roadweaver.persistence.sharded;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.chunk.RoadFootprint;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 道路数据存储门面。 */
public final class RoadShardStorage {
    private RoadShardStorage() {}

    public static void addRoad(ServerLevel level, RoadData road) {
        if (!RoadLifecycle.allowsWrites()) return;
        if (!isOverworld(level) || road == null || road.roadSegmentList() == null
                || road.roadSegmentList().isEmpty()) return;
        long fingerprint = RoadFileStorage.computeFingerprint(road);
        RoadData previous = RoadFileStorage.loadByFingerprint(level, fingerprint);
        RoadFileStorage.addRoad(level, road);
        LinkedHashSet<Long> affected = new LinkedHashSet<>(affectedChunks(previous));
        affected.addAll(affectedChunks(road));
        RoadWorldgenPlanCache.invalidate(level, affected);
        // ★ 2026-09-26 修 WorkBuddy R-2：**同时解除"无需施工"终态标记**。
        //
        // 该标记的语义是"该区块编译出空计划 ⇒ 永久不用施工"，
        // 但那只在"确实没有路"时成立。现在**新的道路数据落盘了**，
        // 原来的结论就失效 —— 不解标的话回填器会永久跳过这个区块
        // （本会话内缺一段路，重进世界才恢复）。
        //
        // 放在 invalidate 之后、用同一个 affected 集合：两者都是
        // "这条路影响到的区块"，语义完全一致。
        net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService
                .unmarkNoConstructionRequired(level, affected);
    }

    public static void addRoads(ServerLevel level, Collection<RoadData> roads) {
        if (!RoadLifecycle.allowsWrites() || !isOverworld(level)
                || roads == null || roads.isEmpty()) return;
        LinkedHashSet<Long> affected = new LinkedHashSet<>();
        for (RoadData road : roads) {
            if (road != null) affected.addAll(affectedChunks(road));
        }
        RoadFileStorage.addRoads(level, roads);
        RoadWorldgenPlanCache.invalidate(level, affected);
        // ★ 同上（R-2）：批量落盘也要解标
        net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService
                .unmarkNoConstructionRequired(level, affected);
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
        RoadData previous = RoadFileStorage.loadByFingerprint(level, fingerprint);
        RoadFileStorage.deleteRoad(level, fingerprint);
        RoadWorldgenPlanCache.invalidate(level, affectedChunks(previous));
    }

    public static void replaceRoad(ServerLevel level, long oldFingerprint, RoadData newRoad) {
        replaceRoads(level, List.of(new RoadReplacement(oldFingerprint, newRoad)));
    }

    public static void replaceRoads(ServerLevel level, Collection<RoadReplacement> replacements) {
        if (!RoadLifecycle.allowsWrites()) return;
        if (!isOverworld(level) || replacements == null || replacements.isEmpty()) return;
        LinkedHashSet<Long> affected = new LinkedHashSet<>();
        for (RoadReplacement replacement : replacements) {
            if (replacement == null) continue;
            RoadData previous = RoadFileStorage.loadByFingerprint(level, replacement.oldFingerprint());
            affected.addAll(affectedChunks(previous));
            affected.addAll(affectedChunks(replacement.newRoad()));
        }
        RoadFileStorage.replaceRoads(level, replacements);
        RoadWorldgenPlanCache.invalidate(level, affected);
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

    private static Set<Long> affectedChunks(RoadData road) {
        return road == null ? Set.of() : RoadFootprint.from(road).worldChunkKeys();
    }
}
