/* 文件职责：持久化「哪些路指纹已在哪些整区块施工完成」，避免重复铺装。 */
package net.shiroha233.roadweaver.persistence;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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

/** 施工台账记录区块内已施工道路版本，规划READY与台账完成是不同状态。 */
public final class RoadConstructionLedger {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int SCHEMA = 3;
    private static final Consumer<FlushTiming> FLUSH_TIMING =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.constructionLedgerTimings", "off"))
                    ? timing -> LOGGER.info("【施工完工台账收集、排序、编码和写盘耗时】 " + "[CONSTRUCTION-LEDGER-TIMING] entries={} bytes={} written={} gatherUs={} sortUs={} serializeUs={} writeUs={}",
                            timing.entries, timing.bytes, timing.written, timing.gatherNanos / 1000,
                            timing.sortNanos / 1000, timing.serializeNanos / 1000, timing.writeNanos / 1000)
                    : null;
    private static final String FILE_NAME = "constructed-chunks-v3.txt";
    // 文件被占用时不能让每一站都重读；暂停施工，最多每五秒完整重试一次。
    private static final long READ_RETRY_NANOS = 5_000_000_000L;
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();

    private RoadConstructionLedger() {
    }

    public static void preload(ServerLevel level) {
        if (isOverworld(level)) state(level);
    }

    /** 未知完工状态禁止施工，不能仅禁止落盘而让恢复队列重铺旧路。 */
    public static boolean isAvailable(ServerLevel level) {
        return isOverworld(level) && ensureAvailable(state(level), () -> path(level), System.nanoTime());
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
        synchronized (state) {
            flushLocked(state, path, timing);
        }
    }

    private static void flushLocked(State state, Supplier<Path> path, Consumer<FlushTiming> timing) {
        // 读取失败的状态不是空台账；即使调用方错误请求 flush，也不允许覆盖原文件。
        if (state.readFailure) return;
        // 状态锁已串行提交；路径解析/编码异常也必须保留脏标记，不能提前假称成功。
        if (!state.dirty.get()) return;
        long started = timing == null ? 0L : System.nanoTime();

        // 首次提交按旧路径收集；之后只收集新增项，复用上次成功提交的不可变顺序。
        List<Entry> additions = new ArrayList<>();
        if (state.ordered == null) {
            for (Map.Entry<Long, Set<Long>> chunk : state.completed.entrySet()) {
                for (long fingerprint : chunk.getValue()) additions.add(new Entry(chunk.getKey(), fingerprint));
            }
        } else additions.addAll(state.pending);
        long gathered = timing == null ? 0L : System.nanoTime();
        additions.sort(ENTRY_ORDER);
        List<Entry> entries = merge(state.ordered, additions);
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
            // 只有原子写成功才推进复用快照；失败时新增项保留，下一次仍提交完整台账。
            state.ordered = entries;
            state.pending.clear();
            state.dirty.set(false);
        } catch (IOException failure) {
            // 写盘失败是待重试，不算干净快照；先标脏再报告。
            state.dirty.set(true);
            writeFailure = failure;
        }
        long finished = timing == null ? 0L : System.nanoTime();
        if (writeFailure != null) {
            LOGGER.warn("【施工完工台账写盘失败，保留待重试状态】 " + "RoadWeaver could not persist constructed chunk ledger {}.", target, writeFailure);
        }
        if (timing != null) {
            // schema 3 的标题和数值均为 ASCII，字符长度就是文件的 UTF-8 字节数。
            timing.accept(new FlushTiming(entries.size(), contents.length(), writeFailure == null,
                    gathered - started, sorted - gathered, writing - sorted, finished - writing));
        }
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparingLong(Entry::chunkKey)
            .thenComparingLong(Entry::roadFingerprint);

    /** 已提交部分有序且去重，新增部分来自Set.add成功，无须重新比较全部旧项。 */
    private static List<Entry> merge(List<Entry> previous, List<Entry> added) {
        if (previous == null || previous.isEmpty()) return added;
        var merged = new ArrayList<Entry>(previous.size() + added.size());
        int old = 0, next = 0;
        while (old < previous.size() && next < added.size()) {
            if (ENTRY_ORDER.compare(previous.get(old), added.get(next)) < 0) merged.add(previous.get(old++));
            else merged.add(added.get(next++));
        }
        merged.addAll(previous.subList(old, previous.size()));
        merged.addAll(added.subList(next, added.size()));
        return merged;
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
        // notExists 为 false 还可能是权限未知，必须真实读取后才能决定是否降级。
        if (Files.notExists(path)) return state;
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
                    if (parts.length != 3) throw new IllegalArgumentException("施工台账条目不完整");
                    int chunkX = Integer.parseInt(parts[0].trim());
                    int chunkZ = Integer.parseInt(parts[1].trim());
                    long roadFingerprint = Long.parseLong(parts[2].trim());
                    entries.add(new Entry(ChunkPos.asLong(chunkX, chunkZ), roadFingerprint));
                }
            }
            if (schema != SCHEMA) {
                LOGGER.info("【施工完工台账版本过旧，未加载旧格式内容】 " + "RoadWeaver ignored stale constructed chunk ledger schema={}.", schema);
                return state;
            }
            for (Entry entry : entries) {
                state.completed.computeIfAbsent(
                        entry.chunkKey(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(entry.roadFingerprint());
            }
        } catch (RuntimeException | IOException failure) {
            state.readFailure = true;
            state.lastRetryAtNanos = System.nanoTime();
            LOGGER.warn("【施工完工台账读取失败，暂停施工和写盘，等待完整重读】 " + "RoadWeaver could not read constructed chunk ledger {}.", path, failure);
        }
        return state;
    }

    static boolean ensureAvailable(State state, Supplier<Path> path, long now) {
        synchronized (state) {
            if (!state.readFailure) return true;
            if (now - state.lastRetryAtNanos < READ_RETRY_NANOS) return false;
            state.lastRetryAtNanos = now;
            State reloaded = load(path.get());
            if (reloaded.readFailure) return false;
            // 降级期间不接受新的完工项，成功快照可以一次替换而不合并未知旧内容。
            state.completed.clear();
            state.completed.putAll(reloaded.completed);
            state.ordered = null;
            state.pending.clear();
            state.readFailure = false;
            LOGGER.info("【施工完工台账完整重读成功，恢复施工和写盘】 chunks={}", state.completed.size());
            return true;
        }
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
        private volatile boolean readFailure;
        private long lastRetryAtNanos;
        // 延迟到首次脏flush才保留排序表，纯读取旧台账不额外持有Entry对象。
        private List<Entry> ordered;
        private final List<Entry> pending = new ArrayList<>();

        synchronized boolean isComplete(ChunkPos chunkPos, long roadFingerprint) {
            // 禁止把未知悄悄降成 false，遗漏入口守卫时也应有明确失败而非错误重铺。
            if (readFailure) throw new IllegalStateException("施工完工台账未完整读取");
            Set<Long> roads = completed.get(chunkPos.toLong());
            return roads != null && roads.contains(roadFingerprint);
        }

        synchronized void markComplete(ChunkPos chunkPos, long roadFingerprint) {
            if (readFailure) throw new IllegalStateException("施工完工台账未完整读取，禁止记账");
            Set<Long> roads = completed.computeIfAbsent(
                    chunkPos.toLong(), ignored -> ConcurrentHashMap.newKeySet());
            // 重复完成不能触发重复写盘；只有新增条目才改变磁盘快照。
            if (roads.add(roadFingerprint)) {
                if (ordered != null) pending.add(new Entry(chunkPos.toLong(), roadFingerprint));
                dirty.set(true);
            }
        }
    }
}
