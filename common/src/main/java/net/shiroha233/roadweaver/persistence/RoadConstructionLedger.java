package net.shiroha233.roadweaver.persistence;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import net.shiroha233.roadweaver.persistence.files.FileStoragePathResolver;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persists which road fingerprints have already been constructed in each full chunk. */
public final class RoadConstructionLedger {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int SCHEMA = 3;
    private static final String FILE_NAME = "constructed-chunks-v3.txt";
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();

    private RoadConstructionLedger() {
    }

    public static void preload(ServerLevel level) {
        if (isOverworld(level)) state(level);
    }

    public static boolean isComplete(ServerLevel level, ChunkPos chunkPos, long roadFingerprint) {
        if (!isOverworld(level) || chunkPos == null) return false;
        Set<Long> roads = state(level).completed.get(chunkPos.toLong());
        return roads != null && roads.contains(roadFingerprint);
    }

    public static void markComplete(ServerLevel level, ChunkPos chunkPos, long roadFingerprint) {
        if (!RoadLifecycle.allowsWrites() || !isOverworld(level) || chunkPos == null) return;
        State state = state(level);
        Set<Long> roads = state.completed.computeIfAbsent(
                chunkPos.toLong(), ignored -> ConcurrentHashMap.newKeySet());
        if (roads.add(roadFingerprint)) state.dirty.set(true);
    }

    public static void flush(ServerLevel level) {
        if (!isOverworld(level)) return;
        State state = STATES.get(level);
        if (state == null || !state.dirty.compareAndSet(true, false)) return;

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> chunk : state.completed.entrySet()) {
            for (long roadFingerprint : chunk.getValue()) {
                entries.add(new Entry(chunk.getKey(), roadFingerprint));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::chunkKey)
                .thenComparingLong(Entry::roadFingerprint));

        StringBuilder output = new StringBuilder("# RoadWeaver constructed road chunks\n");
        output.append("schema=").append(SCHEMA).append('\n');
        for (Entry entry : entries) {
            output.append("entry=")
                    .append(ChunkPos.getX(entry.chunkKey())).append(',')
                    .append(ChunkPos.getZ(entry.chunkKey())).append(',')
                    .append(entry.roadFingerprint()).append('\n');
        }
        try {
            FileStorageIO.writeStringAtomic(path(level), output.toString());
        } catch (IOException failure) {
            state.dirty.set(true);
            LOGGER.warn("RoadWeaver could not persist constructed chunk ledger {}.", path(level), failure);
        }
    }

    public static void close(ServerLevel level) {
        if (level == null) return;
        flush(level);
        STATES.remove(level);
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static State state(ServerLevel level) {
        return STATES.computeIfAbsent(level, RoadConstructionLedger::load);
    }

    private static State load(ServerLevel level) {
        State state = new State();
        Path path = path(level);
        if (!Files.isRegularFile(path)) return state;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int schema = 0;
            List<Entry> entries = new ArrayList<>();
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (value.startsWith("schema=")) {
                    schema = Integer.parseInt(value.substring("schema=".length()).trim());
                } else if (value.startsWith("entry=")) {
                    String[] parts = value.substring("entry=".length()).split(",", 3);
                    if (parts.length != 3) continue;
                    int chunkX = Integer.parseInt(parts[0].trim());
                    int chunkZ = Integer.parseInt(parts[1].trim());
                    long roadFingerprint = Long.parseLong(parts[2].trim());
                    entries.add(new Entry(ChunkPos.asLong(chunkX, chunkZ), roadFingerprint));
                }
            }
            if (schema != SCHEMA) {
                LOGGER.info("RoadWeaver ignored stale constructed chunk ledger schema={}.", schema);
                return state;
            }
            for (Entry entry : entries) {
                state.completed.computeIfAbsent(
                        entry.chunkKey(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(entry.roadFingerprint());
            }
        } catch (RuntimeException | IOException failure) {
            LOGGER.warn("RoadWeaver could not read constructed chunk ledger {}.", path, failure);
        }
        return state;
    }

    private static Path path(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, "construction-ledger").resolve(FILE_NAME);
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }

    private record Entry(long chunkKey, long roadFingerprint) {
    }

    private static final class State {
        private final ConcurrentHashMap<Long, Set<Long>> completed = new ConcurrentHashMap<>();
        private final AtomicBoolean dirty = new AtomicBoolean();
    }
}
