/* 文件职责：隔离 READY 覆盖文件的同步读写，保证文件格式可在没有世界对象时直接验证。 */
package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    boolean contains(long region) { return ready.contains(region); }
    int size() { return ready.size(); }

    boolean markReady(long region) {
        boolean added = ready.add(region);
        if (added) persist();
        return added;
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
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
        } catch (RuntimeException | IOException failure) {
            LOGGER.warn("RoadWeaver could not read Chunky readiness state {}.", path, failure);
        }
    }

    private void persist() {
        // 先保持原来的构造/写入计时口径，避免端口搬迁混入落盘节奏改变。
        long buildStart = TIMINGS ? System.nanoTime() : 0L;
        List<Long> ordered = new ArrayList<>(ready);
        ordered.sort(Long::compare);
        StringBuilder output = new StringBuilder("# RoadWeaver Chunky planning coverage\n");
        output.append("schema=").append(SCHEMA).append('\n');
        output.append("fingerprint=").append(fingerprint).append('\n');
        for (long packed : ordered) {
            output.append("region=").append((int) (packed >> 32)).append(',').append((int) packed).append('\n');
        }
        String contents = output.toString();
        long writing = TIMINGS ? System.nanoTime() : 0L;
        IOException writeFailure = null;
        try {
            writer.write(path, contents);
        } catch (IOException failure) {
            writeFailure = failure;
        }
        long finished = TIMINGS ? System.nanoTime() : 0L;
        if (writeFailure != null) LOGGER.error("RoadWeaver could not persist Chunky readiness state.", writeFailure);
        if (TIMINGS) {
            LOGGER.info("[CHUNKY-READINESS-TIMING] phase=write regions={} written={} buildUs={} writeUs={}",
                    ordered.size(), writeFailure == null, (writing - buildStart) / 1000, (finished - writing) / 1000);
        }
    }
}
