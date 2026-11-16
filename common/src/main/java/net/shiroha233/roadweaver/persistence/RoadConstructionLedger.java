/* 文件职责：持久化「哪些路指纹已在哪些整区块施工完成」，避免重复铺装。 */
package net.shiroha233.roadweaver.persistence;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Persists which road fingerprints have already been constructed in each full chunk. */
public final class RoadConstructionLedger {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int SCHEMA = 3;
    private static final Consumer<FlushTiming> FLUSH_TIMING =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.constructionLedgerTimings", "off"))
                    ? timing -> LOGGER.info("[CONSTRUCTION-LEDGER-TIMING] entries={} bytes={} written={} gatherUs={} sortUs={} serializeUs={} writeUs={}",
                            timing.entries, timing.bytes, timing.written, timing.gatherNanos / 1000,
                            timing.sortNanos / 1000, timing.serializeNanos / 1000, timing.writeNanos / 1000)
                    : null;
    private static final String FILE_NAME = "constructed-chunks-v3.txt";
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();

    private RoadConstructionLedger() {
    }

    public static void preload(ServerLevel level) {
        if (isOverworld(level)) state(level);
    }

    public static boolean isComplete(ServerLevel level, ChunkPos chunkPos, long roadFingerprint) {
        if (!isOverworld(level) || chunkPos == null) return false;
        return state(level).isComplete(chunkPos, roadFingerprint);
    }

    public static void markComplete(ServerLevel level, ChunkPos chunkPos, long roadFingerprint) {
        if (!RoadLifecycle.allowsWrites() || !isOverworld(level) || chunkPos == null) return;
        state(level).markComplete(chunkPos, roadFingerprint);
    }

    public static void flush(ServerLevel level) {
        if (!isOverworld(level)) return;
        State state = STATES.get(level);
        if (state != null) flush(state, () -> path(level));
    }

    // 保持路径按需解析：干净台账直接返回，写盘失败仍保留待写标记。
    static void flush(State state, Supplier<Path> path) {
        flush(state, path, FLUSH_TIMING);
    }

    // 只给脏快照计时；不让收集、排序和原子替换混成一个无法归因的数字。
    static void flush(State state, Supplier<Path> path, Consumer<FlushTiming> timing) {
        if (!state.dirty.compareAndSet(true, false)) return;
        long started = timing == null ? 0L : System.nanoTime();

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> chunk : state.completed.entrySet()) {
            for (long roadFingerprint : chunk.getValue()) {
                entries.add(new Entry(chunk.getKey(), roadFingerprint));
            }
        }
        long gathered = timing == null ? 0L : System.nanoTime();
        entries.sort(Comparator.comparingLong(Entry::chunkKey)
                .thenComparingLong(Entry::roadFingerprint));
        long sorted = timing == null ? 0L : System.nanoTime();

        StringBuilder output = new StringBuilder("# RoadWeaver constructed road chunks\n");
        output.append("schema=").append(SCHEMA).append('\n');
        for (Entry entry : entries) {
            output.append("entry=")
                    .append(ChunkPos.getX(entry.chunkKey())).append(',')
                    .append(ChunkPos.getZ(entry.chunkKey())).append(',')
                    .append(entry.roadFingerprint()).append('\n');
        }
        Path target = path.get();
        String contents = output.toString();
        long writing = timing == null ? 0L : System.nanoTime();
        IOException writeFailure = null;
        try {
            FileStorageIO.writeStringAtomic(target, contents);
        } catch (IOException failure) {
            // 写盘失败是待重试，不算干净快照；先标脏再报告。
            state.dirty.set(true);
            writeFailure = failure;
        }
        long finished = timing == null ? 0L : System.nanoTime();
        if (writeFailure != null) {
            LOGGER.warn("RoadWeaver could not persist constructed chunk ledger {}.", target, writeFailure);
        }
        if (timing != null) {
            // schema 3 的标题和数值均为 ASCII，字符长度就是文件的 UTF-8 字节数。
            timing.accept(new FlushTiming(entries.size(), contents.length(), writeFailure == null,
                    gathered - started, sorted - gathered, writing - sorted, finished - writing));
        }
    }

    record FlushTiming(int entries, int bytes, boolean written, long gatherNanos,
                       long sortNanos, long serializeNanos, long writeNanos) {
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
        return STATES.computeIfAbsent(level, world -> load(path(world)));
    }

    static State load(Path path) {
        State state = new State();
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

    static final class State {
        private final ConcurrentHashMap<Long, Set<Long>> completed = new ConcurrentHashMap<>();
        private final AtomicBoolean dirty = new AtomicBoolean();

        boolean isComplete(ChunkPos chunkPos, long roadFingerprint) {
            Set<Long> roads = completed.get(chunkPos.toLong());
            return roads != null && roads.contains(roadFingerprint);
        }

        void markComplete(ChunkPos chunkPos, long roadFingerprint) {
            Set<Long> roads = completed.computeIfAbsent(
                    chunkPos.toLong(), ignored -> ConcurrentHashMap.newKeySet());
            // 重复完成不能触发重复写盘；只有新增条目才改变磁盘快照。
            if (roads.add(roadFingerprint)) dirty.set(true);
        }
    }
}
