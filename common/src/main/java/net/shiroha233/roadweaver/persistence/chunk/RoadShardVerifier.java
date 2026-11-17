/* 文件职责：用有界并行校验道路派生分片，分开不一致、解析损坏和临时读失败。 */
package net.shiroha233.roadweaver.persistence.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证只读磁盘；统计戳不能证明内容相等，任何修复必须等完整聚合根和验证结果就绪。 */
final class RoadShardVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private final Path root;
    RoadShardVerifier(Path root) { this.root = root; }

    record Result(boolean matches, boolean readFailure, int checked, int mismatches, int malformed,
                  int ioFailures, List<Path> unexpected, List<Path> corrupt) {
        Result {
            unexpected = List.copyOf(unexpected);
            corrupt = List.copyOf(corrupt);
        }
    }

    Result verify(RoadSnapshot snapshot) {
        return verify(snapshot.chunkReferences());
    }

    Result verify(Map<Long, long[]> expected) {
        if (Thread.currentThread().isInterrupted()) return new Result(false,true,0,0,0,1,List.of(),List.of());
        Path directory = root.resolve("chunks");
        List<Path> files;
        try (var stream = Files.list(directory)) {
            // 不按isRegularFile静默滤掉读不到的路径；合法分片名若变成目录也必须报告IO失败。
            files = stream.filter(p -> RoadChunkKey.parseFileName(p.getFileName().toString()) != null).toList();
        } catch (NoSuchFileException absent) {
            return new Result(expected.isEmpty(), false, 0, expected.size(), 0, 0, List.of(), List.of());
        } catch (IOException | DirectoryIteratorException failure) {
            LOGGER.warn("【道路分片目录读取失败，禁止修复和道路写入】 directory={}", directory, failure);
            return new Result(false, true, 0, 0, 0, 1, List.of(), List.of());
        }
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        var unexpected = new ConcurrentLinkedQueue<Path>();
        var corrupt = new ConcurrentLinkedQueue<Path>();
        var cursor = new AtomicInteger();
        var checked = new AtomicInteger();
        var mismatches = new AtomicInteger();
        var malformed = new AtomicInteger();
        var ioFailures = new AtomicInteger();
        int workers = Math.min(files.size(), Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()-1)));
        long start = System.nanoTime();
        if (workers > 0) {
            // 每个worker循环认领文件；仅保留至多8个任务/Future，不按数万分片创建同量级任务图。
            var pool = Executors.newFixedThreadPool(workers, task -> {
                var thread = new Thread(task, "RW-ShardVerify"); thread.setDaemon(true); return thread;
            });
            boolean interrupted = false;
            try {
                var tasks = new ArrayList<Callable<Void>>(workers);
                for (int worker=0;worker<workers;worker++) tasks.add(() -> {
                    int index;
                    while (!Thread.currentThread().isInterrupted() && (index=cursor.getAndIncrement())<files.size()) {
                        Path file=files.get(index);
                        RoadChunkKey key=RoadChunkKey.parseFileName(file.getFileName().toString());
                        long[] references=expected.get(key.packed());
                        if (references==null || !file.getFileName().toString().equals(key.fileName())) unexpected.add(file);
                        if (!seen.add(key.packed())) mismatches.incrementAndGet();
                        try {
                            if (!RoadShardDocumentReader.matches(file,key,references) && mismatches.incrementAndGet()<=8)
                                LOGGER.warn("【道路分片字段或有序引用不一致，完整读取后重建】 file={} chunk={},{} expectedReferences={}",file,key.x(),key.z(),references==null?0:references.length);
                        } catch (com.google.gson.stream.MalformedJsonException | IllegalStateException | NumberFormatException failure) {
                            corrupt.add(file);malformed.incrementAndGet();
                            if (malformed.get()<=8) LOGGER.warn("【道路分片解析损坏，完整验证后才隔离修复】 file={}",file,failure);
                        } catch (IOException failure) {
                            if (failure instanceof java.io.EOFException) {
                                corrupt.add(file);malformed.incrementAndGet();
                            } else {
                                if (ioFailures.incrementAndGet()<=8) LOGGER.warn("【道路分片读取失败，不按损坏隔离】 file={}",file,failure);
                            }
                        }
                        checked.incrementAndGet();
                    }
                    return null;
                });
                for (var future:pool.invokeAll(tasks)) {
                    try { future.get(); }
                    catch (ExecutionException failure) {
                        ioFailures.incrementAndGet();
                        LOGGER.warn("【道路分片验证任务异常，禁止写入并等待重开恢复】",failure.getCause());
                    }
                }
            } catch (InterruptedException failure) {
                interrupted=true;ioFailures.incrementAndGet();
            } finally {
                pool.shutdownNow();
                // 工作者只读；最多等5秒释放文件句柄，超时/取消一律只读降级，不能因存储阻塞无限等停服。
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean terminated=pool.isTerminated();
                while (!terminated && System.nanoTime()<deadline) {
                    try { terminated=pool.awaitTermination(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                    catch (InterruptedException failure) { interrupted=true;ioFailures.incrementAndGet(); }
                }
                if (!terminated) ioFailures.incrementAndGet();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        int missing=0;for(long key:expected.keySet())if(!seen.contains(key))missing++;
        int bad=mismatches.get()+missing;
        boolean readFailure=ioFailures.get()>0 || checked.get()!=files.size();
        boolean matches=!readFailure && bad==0 && malformed.get()==0 && unexpected.isEmpty();
        LOGGER.info("【道路分片内容验证完成】 [SHARD-VERIFY] files={} checked={} mismatches={} malformed={} ioFailures={} workers={} elapsedMs={} matches={}",
                files.size(),checked.get(),bad,malformed.get(),ioFailures.get(),workers,(System.nanoTime()-start)/1_000_000L,matches);
        return new Result(matches,readFailure,checked.get(),bad,malformed.get(),ioFailures.get(),
                new ArrayList<>(unexpected),new ArrayList<>(corrupt));
    }

    // 字段名与schema保持原样；只从RoadChunkStore迁出伴随类型供现有写入器使用。
    static final class ChunkDocument {
        String schema;
        int version;
        long generation;
        int chunkX;
        int chunkZ;
        List<Long> roads = new ArrayList<>();
    }
}
