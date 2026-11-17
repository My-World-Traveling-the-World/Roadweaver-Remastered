/* 文件职责：隔离 READY 覆盖文件的同步读写，保证文件格式可在没有世界对象时直接验证。 */
package net.shiroha233.roadweaver.planning;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

final class ReadinessCoverageStore {
    static final int SCHEMA = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final boolean TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.chunkyReadinessTimings", "off"));

    // 关闭开关时不采时钟；端口用于实盘探针，不改变提交时序和READY发布条件。
    private static final Consumer<WriteTiming> LOG_TIMING = TIMINGS ? timing -> LOGGER.info(
            "【READY覆盖收集、排序、编码和写盘耗时】 "
                    + "[CHUNKY-READINESS-TIMING] phase=write regions={} written={} buildUs={} writeUs={} bytes={} gatherUs={} sortUs={} serializeUs={}",
            timing.regions(), timing.written(), timing.buildNanos() / 1000, timing.writeNanos() / 1000,
            timing.bytes(), timing.gatherNanos() / 1000, timing.sortNanos() / 1000, timing.serializeNanos() / 1000) : null;

    record WriteTiming(int regions, int bytes, boolean written, long gatherNanos, long sortNanos,
                       long serializeNanos, long writeNanos) {
        long buildNanos() { return gatherNanos + sortNanos + serializeNanos; }
    }

    @FunctionalInterface
    interface Writer { void write(Path path, String contents) throws IOException; }

    private final Path path;
    private final String fingerprint;
    private final Writer writer;
    private final Consumer<WriteTiming> timing;
    private final Set<Long> ready = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private IOException readFailure;
    // 只保留成功提交的排序结果；查询仍走原并发集合，重开时不额外建立树索引。
    private List<Long> committedOrder;

    static ReadinessCoverageStore open(Path path, String fingerprint) {
        return open(path, fingerprint, FileStorageIO::writeStringAtomic);
    }

    static ReadinessCoverageStore open(Path path, String fingerprint, Writer writer) {
        return open(path, fingerprint, writer, LOG_TIMING);
    }

    static ReadinessCoverageStore open(Path path, String fingerprint, Writer writer, Consumer<WriteTiming> timing) {
        ReadinessCoverageStore store = new ReadinessCoverageStore(path, fingerprint, writer, timing);
        store.load();
        return store;
    }

    private ReadinessCoverageStore(Path path, String fingerprint, Writer writer, Consumer<WriteTiming> timing) {
        this.path = path;
        this.fingerprint = fingerprint;
        this.writer = writer;
        this.timing = timing;
    }

    boolean contains(long region) { return !closed && ready.contains(region); }
    int size() { return closed ? 0 : ready.size(); }

    synchronized boolean markReady(long region) throws IOException {
        ensureOpen();
        // 读不到旧索引不是空索引；先重读，避免用不完整覆盖表替换旧文件。
        if (readFailure != null) {
            load();
            if (readFailure != null) throw new IOException("Cannot safely update unreadable readiness coverage: " + path, readFailure);
        }
        if (ready.contains(region)) return false;
        Long boxedRegion = region;
        List<Long> nextOrder = persist(boxedRegion);
        // 提交成功才同时发布快照和READY；失败不得污染下一次重试的排序输入。
        ensureOpen();
        committedOrder = nextOrder;
        ready.add(boxedRegion);
        return true;
    }

    void close() {
        closed = true;
        // 拒绝新提交并排空当前同步写入；返回后旧实例不能再覆盖重开世界的文件。
        synchronized (this) { }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Readiness coverage is closed: " + path);
    }

    private void load() {
        readFailure = null;
        committedOrder = null;
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("Readiness coverage is not a regular file: " + path);
            }
            int schema = 0;
            String storedFingerprint = "";
            List<Long> storedRegions = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (value.startsWith("schema=")) {
                    schema = Integer.parseInt(value.substring("schema=".length()).trim());
                } else if (value.startsWith("fingerprint=")) {
                    storedFingerprint = value.substring("fingerprint=".length()).trim();
                } else if (value.startsWith("region=")) {
                    String[] parts = value.substring("region=".length()).split(",", 2);
                    if (parts.length == 2) {
                        storedRegions.add(((long) Integer.parseInt(parts[0].trim()) << 32)
                                ^ (Integer.parseInt(parts[1].trim()) & 0xffffffffL));
                    }
                }
            }
            if (schema == SCHEMA && fingerprint.equals(storedFingerprint)) {
                ready.addAll(storedRegions);
            } else {
                LOGGER.info("【READY覆盖版本或世界指纹不匹配，忽略旧覆盖】 " + "RoadWeaver ignored stale Chunky readiness coverage: schema={} fingerprintMatch={}.",
                        schema, fingerprint.equals(storedFingerprint));
            }
        } catch (NoSuchFileException missing) {
            // 只有确认文件不存在时才能按空覆盖开始，权限/共享锁失败不能走这里。
        } catch (RuntimeException | IOException failure) {
            readFailure = failure instanceof IOException io ? io : new IOException("Invalid readiness coverage: " + path, failure);
            LOGGER.warn("【READY覆盖状态读取失败】 " + "RoadWeaver could not read Chunky readiness state {}.", path, failure);
        }
    }

    private List<Long> persist(Long region) throws IOException {
        // 首次写入仍按原规则排序；后续复制已提交快照并二分插入，避免每个区域重复全表排序。
        long buildStart = timing != null ? System.nanoTime() : 0L;
        List<Long> previous = committedOrder;
        List<Long> ordered = new ArrayList<>(previous == null ? ready : previous);
        long gathered = timing != null ? System.nanoTime() : 0L;
        if (previous == null) {
            ordered.add(region);
            ordered.sort(Long::compare);
        } else {
            int index = java.util.Collections.binarySearch(ordered, region);
            if (index >= 0) throw new IllegalStateException("READY有序快照与查询集合不一致");
            ordered.add(-index - 1, region);
        }
        long sorted = timing != null ? System.nanoTime() : 0L;
        StringBuilder output = new StringBuilder("# RoadWeaver Chunky planning coverage\n");
        output.append("schema=").append(SCHEMA).append('\n');
        output.append("fingerprint=").append(fingerprint).append('\n');
        for (long packed : ordered) {
            output.append("region=").append((int) (packed >> 32)).append(',').append((int) packed).append('\n');
        }
        String contents = output.toString();
        // 字节数只在诊断开启时计算，包含UTF-8指纹；普通提交不额外创建字节数组。
        int bytes = timing == null ? 0 : contents.getBytes(StandardCharsets.UTF_8).length;
        long writing = timing != null ? System.nanoTime() : 0L;
        boolean written = false;
        try {
            ensureOpen();
            writer.write(path, contents);
            written = true;
        } finally {
            if (timing != null) {
                timing.accept(new WriteTiming(ordered.size(), bytes, written, gathered - buildStart,
                        sorted - gathered, writing - sorted, System.nanoTime() - writing));
            }
        }
        return ordered;
    }
}
