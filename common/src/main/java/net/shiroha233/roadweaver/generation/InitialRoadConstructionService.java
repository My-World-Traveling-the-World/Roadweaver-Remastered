package net.shiroha233.roadweaver.generation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.shiroha233.roadweaver.generation.LoadedChunkRoadBackfill;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Forces every chunk covered by the finalized initial roads to FULL, then paves it. */
public final class InitialRoadConstructionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MIN_BATCH_SIZE = 4;
    private static final int MAX_BATCH_SIZE = 32;
    private static final int MAX_CHUNK_ATTEMPTS = 3;

    private InitialRoadConstructionService() {
    }

    public static void construct(ServerLevel level, Set<Long> candidateChunkKeys) {
        if (level == null || candidateChunkKeys == null || candidateChunkKeys.isEmpty()) return;
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("initial road construction must run on the server thread");
        }

        List<Long> ordered = new ArrayList<>(candidateChunkKeys);
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        ordered.sort(Comparator.comparingLong(key -> distanceSquared(spawn, key)));
        int batchSize = Math.max(MIN_BATCH_SIZE,
                Math.min(MAX_BATCH_SIZE, Math.max(1, Runtime.getRuntime().availableProcessors())));
        int completedChunks = 0;
        InitialGenerationProgressTracker.enterStage(
                InitialGenerationStage.POST_PROCESSING, "constructing_initial_roads");
        InitialGenerationProgressTracker.setConstructionChunkPlan(ordered.size(), "constructing_initial_roads");

        for (int offset = 0; offset < ordered.size(); offset += batchSize) {
            int end = Math.min(ordered.size(), offset + batchSize);
            List<Long> batch = ordered.subList(offset, end);
            Set<Long> ready = new LinkedHashSet<>();
            Set<Long> pending = new LinkedHashSet<>(batch);
            Map<Long, RuntimeException> failures = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= MAX_CHUNK_ATTEMPTS && !pending.isEmpty(); attempt++) {
                Map<Long, CompletableFuture<?>> futures = new LinkedHashMap<>();
                for (long key : pending) {
                    futures.put(key, level.getChunkSource().getChunkFuture(
                            ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, true));
                }
                CompletableFuture<?> all = CompletableFuture.allOf(
                        futures.values().toArray(CompletableFuture[]::new));
                server.managedBlock(all::isDone);
                Set<Long> nextPending = new LinkedHashSet<>();
                for (Map.Entry<Long, CompletableFuture<?>> entry : futures.entrySet()) {
                    long key = entry.getKey();
                    int chunkX = ChunkPos.getX(key);
                    int chunkZ = ChunkPos.getZ(key);
                    try {
                        entry.getValue().join();
                        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                            ready.add(key);
                            failures.remove(key);
                        } else {
                            nextPending.add(key);
                            failures.put(key, new IllegalStateException(
                                    "FULL chunk was not available after completion"));
                        }
                    } catch (RuntimeException failure) {
                        nextPending.add(key);
                        failures.put(key, failure);
                        LOGGER.warn("RoadWeaver initial road chunk generation attempt failed: chunk={},{} attempt={}/{}",
                                chunkX, chunkZ, attempt, MAX_CHUNK_ATTEMPTS, failure);
                    }
                }
                pending = nextPending;
            }
            if (!pending.isEmpty()) {
                long key = pending.iterator().next();
                throw new IllegalStateException("initial road chunk could not reach FULL: "
                        + ChunkPos.getX(key) + "," + ChunkPos.getZ(key), failures.get(key));
            }
            if (!ready.isEmpty()) {
                LoadedChunkRoadBackfill.placeLoadedChunks(level, ready);
                completedChunks += ready.size();
                InitialGenerationProgressTracker.recordConstructionChunksDone(completedChunks);
                RoadConstructionLedger.flush(level);
            }
            LOGGER.info("RoadWeaver initial road construction progress: chunks={}/{}, batch={}",
                    completedChunks, ordered.size(), batch.size());
        }
        verifyConstruction(level, candidateChunkKeys);
        RoadConstructionLedger.flush(level);
        LOGGER.info("RoadWeaver initial road construction finished: chunks={}", completedChunks);
    }

    private static void verifyConstruction(ServerLevel level, Set<Long> candidateChunkKeys) {
        for (long key : candidateChunkKeys) {
            ChunkPos chunk = new ChunkPos(key);
            for (var road : RoadShardStorage.queryChunk(level, chunk)) {
                long fingerprint = RoadShardStorage.computeFingerprint(road);
                if (!RoadConstructionLedger.isComplete(level, chunk, fingerprint)) {
                    throw new IllegalStateException("initial road construction incomplete: chunk="
                            + chunk.x + "," + chunk.z + " fingerprint=" + fingerprint);
                }
            }
        }
    }

    private static long distanceSquared(ChunkPos origin, long key) {
        long dx = (long) ChunkPos.getX(key) - origin.x;
        long dz = (long) ChunkPos.getZ(key) - origin.z;
        return dx * dx + dz * dz;
    }
}
