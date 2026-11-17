/* 文件职责：隔离 READY 覆盖文件的同步读写，保证文件格式可在没有世界对象时直接验证。 */
package net.shiroha233.roadweaver.planning;

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
import java.util.concurrent.ConcurrentHashMap;

final class ReadinessCoverageStore {
    static final int SCHEMA = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final boolean TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.chunkyReadinessTimings", "off"));

    @FunctionalInterface
    interface Writer { void write(Path path, String contents) throws IOException; }

    private final Path path;
    private final String fingerprint;
    private final Writer writer;
    private final Set<Long> ready = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private IOException readFailure;

    static ReadinessCoverageStore open(Path path, String fingerprint) {
        return open(path, fingerprint, FileStorageIO::writeStringAtomic);
    }

    static ReadinessCoverageStore open(Path path, String fingerprint, Writer writer) {
        ReadinessCoverageStore store = new ReadinessCoverageStore(path, fingerprint, writer);
        store.load();
        return store;
    }

    private ReadinessCoverageStore(Path path, String fingerprint, Writer writer) {
        this.path = path;
        this.fingerprint = fingerprint;
        this.writer = writer;
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
        persist(region);
        // 提交成功才对查询发布；关闭期间完成的旧写入也不能复活旧会话的 READY。
        ensureOpen();
        ready.add(region);
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
                LOGGER.info("RoadWeaver ignored stale Chunky readiness coverage: schema={} fingerprintMatch={}.",
                        schema, fingerprint.equals(storedFingerprint));
            }
        } catch (NoSuchFileException missing) {
            // 只有确认文件不存在时才能按空覆盖开始，权限/共享锁失败不能走这里。
        } catch (RuntimeException | IOException failure) {
            readFailure = failure instanceof IOException io ? io : new IOException("Invalid readiness coverage: " + path, failure);
            LOGGER.warn("RoadWeaver could not read Chunky readiness state {}.", path, failure);
        }
    }

    private void persist(long region) throws IOException {
        // 保持原排序、编码与同步写入节奏；失败交回规划重试，不能只记录日志。
        long buildStart = TIMINGS ? System.nanoTime() : 0L;
        List<Long> ordered = new ArrayList<>(ready);
        ordered.add(region);
        ordered.sort(Long::compare);
        StringBuilder output = new StringBuilder("# RoadWeaver Chunky planning coverage\n");
        output.append("schema=").append(SCHEMA).append('\n');
        output.append("fingerprint=").append(fingerprint).append('\n');
        for (long packed : ordered) {
            output.append("region=").append((int) (packed >> 32)).append(',').append((int) packed).append('\n');
        }
        String contents = output.toString();
        long writing = TIMINGS ? System.nanoTime() : 0L;
        boolean written = false;
        try {
            ensureOpen();
            writer.write(path, contents);
            written = true;
        } finally {
            if (TIMINGS) {
                LOGGER.info("[CHUNKY-READINESS-TIMING] phase=write regions={} written={} buildUs={} writeUs={}",
                        ordered.size(), written, (writing - buildStart) / 1000, (System.nanoTime() - writing) / 1000);
            }
        }
    }
}
