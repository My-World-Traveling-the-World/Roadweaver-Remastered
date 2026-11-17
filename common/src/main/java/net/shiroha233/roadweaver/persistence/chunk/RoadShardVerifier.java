/* 文件职责：独立承担区块分片验证；先等价迁出旧算法，再单独修验证判据。 */
package net.shiroha233.roadweaver.persistence.chunk;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
/** 分片是道路聚合根的派生索引，验证边界不承担道路读写和快照发布。 */
final class RoadShardVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CHUNK_SCHEMA = "roadweaver.roads.chunk";
    private static final int SCHEMA_VERSION = RoadChunkStore.SCHEMA_VERSION;
    private static final String CHUNK_DIRECTORY = "chunks";
    private static final String SHARD_VERIFY_FILE = "shard-verify.json";
    private final Path root;
    RoadShardVerifier(Path root) { this.root = root; }
    /**
     * 0.8.358：分片校验改为「先查缓存戳、再并行读」。
     *
     * <p>背景（真机）：玩家的 1024 区块存档有 60192 个 chunk 分片。旧实现在
     * Server thread 上<b>单线程顺序</b>遍历全部文件（每个都要一次 CreateFile
     * 系统调用 + GSON 解析），冷缓存 + Defter 实时扫描下实测阻塞启动
     * <b>237 秒</b>，玩家体感为"游戏卡死"。</p>
     *
     * <p>本方法现在分两步：</p>
     * <ol>
     *   <li><b>快速通道</b>：若 {@code shard-verify.json} 记录的（文件数、最新
     *       mtime、总字节数）与磁盘目录一致，则直接判定为匹配，不再逐个读文件。
     *       这解决"第二次及以后启动"的耗时。</li>
     *   <li><b>并行校验</b>：缓存未命中时，用临时RW-ShardVerify专用池读分片，
     *       校验通过后写缓存戳；冷启动耗时须量现场磁盘，不能用历史样本承诺。</li>
     * </ol>
     *
     * <p>已知边界：统计戳不是内容证明；非最新文件同长改写可保持三项不变。
     * ShardStampProbe已复现盲点；聚合道路仍从NBT加载，分片修复不能把此命中当完整验证。
     * 后续须单独补逐文件证据与强制修复契约，不能为优化启动而宣称任意篡改可检出。</p>
     */
    boolean matches(RoadSnapshot expected) {
        Path chunkDirectory = root.resolve(CHUNK_DIRECTORY);
        if (!Files.isDirectory(chunkDirectory)) return expected.chunkReferences().isEmpty();

        // ---------- 快速通道：缓存戳命中则直接信任 ----------
        Path stampPath = root.resolve(SHARD_VERIFY_FILE);
        ShardStamp stamp = readShardStamp(stampPath);
        ShardStamp current = null;
        try {
            current = computeShardStamp(chunkDirectory);
        } catch (IOException e) {
            LOGGER.warn("统计道路区块分片目录失败: {}", chunkDirectory, e);
            return false;
        }
        if (stamp != null && current != null && stamp.matches(current)
                && stamp.references() == expected.chunkReferences().size()) {
            return true;
        }

        // ---------- 慢速通道：并行校验 ----------
        List<Path> shardFiles;
        try (var files = Files.list(chunkDirectory)) {
            shardFiles = files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            LOGGER.warn("读取道路区块分片目录失败: {}", chunkDirectory, e);
            return false;
        }

        long verifyStartNanos = System.nanoTime();
        Map<Long, long[]> actual = new ConcurrentHashMap<>();
        AtomicBoolean valid = new AtomicBoolean(true);

        // 专用池而非 ForkJoinPool.commonPool：真机日志显示 commonPool
        // （ForkJoinPool-1-worker-*）被模组加载与其它系统占用，借用它会排队。
        // 这里是一次性批处理，用独立小池即可，结束后立即关闭。
        int parallelism = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService shardPool =
                java.util.concurrent.Executors.newFixedThreadPool(parallelism, r -> {
                    Thread t = new Thread(r, "RW-ShardVerify");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>(shardFiles.size());
            for (Path file : shardFiles) {
                tasks.add(() -> {
                    RoadChunkKey key = RoadChunkKey.parseFileName(file.getFileName().toString());
                    if (key == null) return null;
                    ChunkDocument document;
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        document = GSON.fromJson(reader, ChunkDocument.class);
                    } catch (RuntimeException | IOException e) {
                        FileStorageIO.quarantineCorrupt(file, LOGGER, "道路区块分片损坏，已隔离");
                        valid.set(false);
                        return null;
                    }
                    if (document == null
                            || !CHUNK_SCHEMA.equals(document.schema)
                            || document.version != SCHEMA_VERSION
                            || document.chunkX != key.x()
                            || document.chunkZ != key.z()
                            || document.roads == null) {
                        valid.set(false);
                        return null;
                    }
                    long[] references = new long[document.roads.size()];
                    for (int index = 0; index < references.length; index++) {
                        Long value = document.roads.get(index);
                        references[index] = value == null ? 0L : value;
                    }
                    actual.put(key.packed(), references);
                    return null;
                });
            }
            for (var future : shardPool.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    valid.set(false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    valid.set(false);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            valid.set(false);
        } finally {
            shardPool.shutdownNow();
        }

        long verifyMillis = (System.nanoTime() - verifyStartNanos) / 1_000_000L;
        LOGGER.info("【道路区块分片并行校验完成】 " + "RoadWeaver verified {} chunk shard(s) in {} ms (parallel).",
                shardFiles.size(), verifyMillis);

        if (!valid.get() || actual.size() != expected.chunkReferences().size()) return false;
        for (Map.Entry<Long, long[]> entry : expected.chunkReferences().entrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) return false;
        }

        // 校验通过：写入缓存戳，供后续启动走快速通道。
        writeShardStamp(stampPath, current, expected.chunkReferences().size());
        return true;
    }

    /** 目录统计提示：文件数、最大mtime和总大小；同长修改非最新文件会碰撞。 */
    private record ShardStamp(long fileCount, long latestMillis, long totalBytes, int references) {
        boolean matches(ShardStamp other) {
            return other != null
                    && fileCount == other.fileCount
                    && latestMillis == other.latestMillis
                    && totalBytes == other.totalBytes;
        }
    }

    private ShardStamp computeShardStamp(Path chunkDirectory) throws IOException {
        long count = 0L;
        long latest = 0L;
        long total = 0L;
        try (var files = Files.list(chunkDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (RoadChunkKey.parseFileName(file.getFileName().toString()) == null) continue;
                count++;
                total += Files.size(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified > latest) latest = modified;
            }
        }
        return new ShardStamp(count, latest, total, 0);
    }

    private ShardStamp readShardStamp(Path stampPath) {
        if (!Files.isRegularFile(stampPath)) return null;
        try (Reader reader = Files.newBufferedReader(stampPath, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, ShardStamp.class);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private void writeShardStamp(Path stampPath, ShardStamp stamp, int references) {
        if (stamp == null) return;
        try {
            FileStorageIO.writeStringAtomic(stampPath,
                    GSON.toJson(new ShardStamp(stamp.fileCount(), stamp.latestMillis(),
                            stamp.totalBytes(), references)));
        } catch (IOException e) {
            LOGGER.warn("写入道路区块分片校验戳失败（不影响正确性）: {}", stampPath, e);
        }
    }

    static final class ChunkDocument {
        String schema;
        int version;
        long generation;
        int chunkX;
        int chunkZ;
        List<Long> roads = new ArrayList<>();
    }
}
