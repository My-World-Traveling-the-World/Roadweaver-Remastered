/* 文件职责：按世界目录持久化道路聚合根和 ChunkPos 分片，并原子发布内存快照。 */
package net.shiroha233.roadweaver.persistence.chunk;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Dynamic;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

/**
 * 单个世界目录的道路存储。
 *
 * <p>第一次访问时将聚合 NBT 全量解码一次并构造不可变快照。之后查询只读取 AtomicReference，
 * 不持锁、不读取文件，也不解析 JSON/NBT。所有修改在写锁内先完整落盘，再一次性发布新快照。</p>
 */
public final class RoadChunkStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final boolean CONSTRUCTION_SHADOW =
            "on".equalsIgnoreCase(System.getProperty("roadweaver.constructionContentShadow"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROOT_SCHEMA = "roadweaver.roads";
    private static final String INDEX_SCHEMA = "roadweaver.roads.index";
    private static final String CHUNK_SCHEMA = "roadweaver.roads.chunk";
    private static final String SCHEMA_FILE = "schema.json";
    private static final String INDEX_FILE = "index.json";
    private static final String ROAD_DIRECTORY = "roads";
    private static final String CHUNK_DIRECTORY = "chunks";
    private final Path root;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final AtomicReference<RoadSnapshot> snapshot = new AtomicReference<>(RoadSnapshot.empty());
    private volatile boolean loaded;
    private long generation;
    // 修复失败不等于磁盘已一致；保留到下一次成功提交，不能靠内存引用相等跳过损坏分片。
    private boolean metadataRepairPending;
    private RoadShardVerifier.Result pendingShardRepair;

    /** 任一条路暂时读不到，只能查询已读部分。完整快照发布后才恢复写入。 */
    private volatile boolean readOnlyDegraded;

    public RoadChunkStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public void preload() {
        ensureLoaded();
    }

    /** 已发布的不可变内存视图；远景只能观察，不能借查询触发恢复读盘。 */
    public RoadSnapshot peekSnapshot() {return loaded&&!readOnlyDegraded?snapshot.get():null;}

    public List<RoadData> queryRect(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        ensureLoaded();
        return snapshot.get().queryRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public List<RoadData> queryChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return queryChunk(chunkPos.x, chunkPos.z);
    }

    public List<RoadData> queryChunk(int chunkX, int chunkZ) {
        ensureLoaded();
        return snapshot.get().queryChunk(chunkX, chunkZ);
    }

    /** 与查道路使用同一份快照，换线发布后便能看到本区块的新指纹。 */
    public boolean allRoadFingerprintsMatch(ChunkPos chunkPos, LongPredicate matches) {
        ensureLoaded();
        return snapshot.get().allRoadFingerprintsMatch(chunkPos.x, chunkPos.z, matches);
    }

    /** 恢复队列需要完整视图。普通查询仍能读已加载的部分；只有恢复入口允许限频重读。 */
    public boolean readyForRecovery(long now) {
        ensureLoaded();
        if (!readOnlyDegraded) return true;
        mutationLock.lock();
        try {
            if (readOnlyDegraded && now - lastRecoveryAttempt >= 5_000_000_000L) {
                lastRecoveryAttempt = now;
                loadSnapshotLocked();
            }
            return !readOnlyDegraded;
        } finally {
            mutationLock.unlock();
        }
    }

    private long lastRecoveryAttempt;

    public List<RoadData> loadAll() {
        ensureLoaded();
        return snapshot.get().all();
    }

    public RoadData loadByFingerprint(long fingerprint) {
        ensureLoaded();
        return snapshot.get().byFingerprint(fingerprint);
    }

    public boolean hasAnyRoad() {
        ensureLoaded();
        return !snapshot.get().isEmpty();
    }

    public boolean hasRoadInRect(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        ensureLoaded();
        return snapshot.get().hasRoadInRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public void addRoad(RoadData road) {
        if (road != null) addRoads(List.of(road));
    }

    /** 不变的道路仍可交给施工，但不刷新缓存；用最后保存的同指纹条目确定交付范围。 */
    public RoadChunkChanges addRoads(Collection<RoadData> roads) {
        if (roads == null || roads.isEmpty()) return RoadChunkChanges.EMPTY;
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            var entries = new LinkedHashMap<>(previous.entries());
            var accepted = new LinkedHashSet<Long>();
            var affected = new LinkedHashSet<Long>();
            long nextGeneration = nextGeneration();
            boolean changed = false;
            for (RoadData road : roads) {
                if (!isValidRoad(road)) continue;
                RoadData frozen = RoadSnapshot.freezeForStorage(road);
                long fingerprint = RoadFingerprint.compute(frozen);
                accepted.add(fingerprint);
                var existing = entries.get(fingerprint);
                if (existing != null && existing.road().equals(frozen)) continue;
                var created = newEntry(fingerprint, frozen, nextGeneration);
                if (existing != null) affected.addAll(existing.footprint().chunkKeys());
                affected.addAll(created.footprint().chunkKeys());
                entries.put(fingerprint, created);
                changed = true;
            }
            if (changed && !publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration, affected))
                return RoadChunkChanges.EMPTY;
            var delivered = new LinkedHashSet<Long>();
            for (long fingerprint : accepted) delivered.addAll(entries.get(fingerprint).footprint().chunkKeys());
            return RoadChunkChanges.committed(delivered, affected);
        } finally {
            mutationLock.unlock();
        }
    }

    /** 恢复时持有同一不可变快照的条目，复用首次读盘时就已建立的占地与指纹。 */
    public Collection<RoadSnapshot.Entry> storedEntries() {
        ensureLoaded();
        return snapshot.get().entries().values();
    }

    public void replaceRoad(long oldFingerprint, RoadData newRoad) {
        replaceRoads(List.of(new RoadReplacement(oldFingerprint, newRoad)));
    }

    public RoadChunkChanges replaceRoads(Collection<RoadReplacement> replacements) {
        if (replacements == null || replacements.isEmpty()) return RoadChunkChanges.EMPTY;
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>(previous.entries());
            long nextGeneration = nextGeneration();
            boolean changed = false;
            // A3：精确记录「引用真正发生变化的区块」，供 persistSnapshot 只遍历这些区块。
            // 依据：chunkReferences 完全由各 entry 的 footprint 派生（见 RoadSnapshot 构造函数），
            // 因此某个区块的引用数组会变化，当且仅当有 entry 被加入或移除、且其 footprint
            // 覆盖该区块。本循环中被移除/加入的 entry 就是全部变更来源，故该集合是完备的。
            // 复用 Entry 上已缓存的 footprint，不重算、不产生额外磁盘 IO。
            Set<Long> affectedChunks = new LinkedHashSet<>();
            for (RoadReplacement replacement : replacements) {
                if (replacement == null || !isValidRoad(replacement.newRoad())) continue;

                RoadData frozen = RoadSnapshot.freezeForStorage(replacement.newRoad());
                long newFingerprint = RoadFingerprint.compute(frozen);
                RoadSnapshot.Entry existing = entries.get(newFingerprint);
                if (replacement.oldFingerprint() == newFingerprint
                        && existing != null
                        && existing.road().equals(frozen)) {
                    continue;
                }

                RoadSnapshot.Entry removed = entries.remove(replacement.oldFingerprint());
                RoadSnapshot.Entry superseded = entries.remove(newFingerprint);
                RoadSnapshot.Entry created = newEntry(newFingerprint, frozen, nextGeneration);
                entries.put(newFingerprint, created);
                changed = true;

                if (removed != null) affectedChunks.addAll(removed.footprint().chunkKeys());
                // 指纹不含占地列；即使指纹相同，也要覆盖被替换条目的旧区块。
                if (superseded != null) affectedChunks.addAll(superseded.footprint().chunkKeys());
                affectedChunks.addAll(created.footprint().chunkKeys());
            }
            if (!changed || !publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration, affectedChunks))
                return RoadChunkChanges.EMPTY;
            return RoadChunkChanges.committed(Set.of(), affectedChunks);
        } finally {
            mutationLock.unlock();
        }
    }

    public Set<Long> deleteRoad(long fingerprint) {
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            var old = previous.entries().get(fingerprint);
            if (old == null) return Set.of();
            long nextGeneration = nextGeneration();
            LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>(previous.entries());
            entries.remove(fingerprint);
            return publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration, old.footprint().chunkKeys())
                    ? old.footprint().worldChunkKeys() : Set.of();
        } finally {
            mutationLock.unlock();
        }
    }

    /** 写入操作已经同步落盘；flush 用于确保首次加载和待修复元数据已经完成。 */
    public void flush() {
        ensureLoaded();
    }

    public void clear() {
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            FileStorageIO.deleteTree(root, LOGGER, "清理道路 ChunkPos 分片失败");
            snapshot.set(RoadSnapshot.empty());
            generation = 0L;
            loaded = true;
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public void close() {
        // 释放路径注册表的实例即可；仍被旧调用持有的部分视图不可解开写保护。
    }

    Path chunkPath(int chunkX, int chunkZ) {
        return root.resolve(CHUNK_DIRECTORY).resolve(new RoadChunkKey(chunkX, chunkZ).fileName());
    }

    private void ensureLoaded() {
        if (loaded) return;
        mutationLock.lock();
        try {
            ensureLoadedLocked();
        } finally {
            mutationLock.unlock();
        }
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        lastRecoveryAttempt = System.nanoTime();
        loadSnapshotLocked();
    }

    private void loadSnapshotLocked() {
        LoadedSnapshot loadedSnapshot;
        try {
            loadedSnapshot = readSnapshot();
            if (loadedSnapshot.readFailure()) LOGGER.warn("道路数据尚未读全，保留原索引并等待重读：{}", root);
        } catch (IOException e) {
            LOGGER.error("道路索引暂时无法读取，保留原文件并等待重读：{}", root, e);
            loadedSnapshot = new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, true, null);
        }
        snapshot.set(loadedSnapshot.snapshot());
        generation = loadedSnapshot.generation();
        if (loadedSnapshot.rewriteMetadata() && !loadedSnapshot.readFailure()) {
            long repairGeneration = generation == 0L ? 1L : generation;
            metadataRepairPending = true;
            pendingShardRepair = loadedSnapshot.shards();
            try {
                persistSnapshot(RoadSnapshot.empty(), loadedSnapshot.snapshot(), repairGeneration, true);
                generation = repairGeneration;
                metadataRepairPending = false;
                pendingShardRepair = null;
            } catch (IOException e) {
                // 查询用完整内存快照；保留修复意图，让下次实际修改先补全所有分片再发布。
                LOGGER.warn("修复道路 ChunkPos 分片元数据失败: {}", root, e);
            }
        }
        // 先发布完整快照，再让恢复线程看见可用状态。
        readOnlyDegraded = loadedSnapshot.readFailure();
        loaded = true;
    }

    /** 发布新旧快照时只刷新已知变化区块，未知范围必须遍历完整差集。
 *
     * @param affectedChunks A3：已知引用发生变化的区块集合；{@code null} 表示未知，
     *                       退化为 previous ∪ next 全量遍历（保持旧行为）。
     */
    private boolean publish(RoadSnapshot previous, RoadSnapshot next, long nextGeneration,
                         Set<Long> affectedChunks) {
        // 0.8.345：**补上写入闸门**。
        // 此前这里既不校验 RoadLifecycle.allowsWrites() 也不校验 epoch，
        // 而同类的 StructureFileStorage 在每一处写操作前都校验（共 19 处）。
        // 后果：关服瞬间处于"已过入口校验、落盘中途"的任务会把旧世界道路写进存档。
        // 与 StructureFileStorage 保持同一口径：停服即拒绝写入。
        if (!net.shiroha233.roadweaver.runtime.RoadLifecycle.allowsWrites()) {
            LOGGER.warn("【服务端正在停止，跳过当前代次道路发布】 " + "RoadWeaver road chunk publish skipped: server is stopping (generation {} dropped)", nextGeneration);
            return false;
        }
        // ★ 2026-09-26 修 WorkBuddy R-5：只读降级期间**拒绝一切写入**。
        //   否则会把"首载失败得到的空快照"全量写回 index.json，
        //   覆盖磁盘上可能完好的真实索引 ⇒ 玩家全部道路不可见（不可恢复）。
        //   抛异常而不是静默 return：让调用方（生成任务）知道失败并重试，
        //   而不是"看起来写成功了但其实没写"。
        if (readOnlyDegraded) {
            throw new IllegalStateException(
                    "road chunk store is in read-only degraded mode "
                            + "(initial load failed; refusing to overwrite the real index): " + root);
        }
        try {
            persistSnapshot(previous, next, nextGeneration, metadataRepairPending, affectedChunks);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist road ChunkPos snapshot", e);
        }
        metadataRepairPending = false;
        pendingShardRepair = null;
        generation = nextGeneration;
        snapshot.set(next);
        if (CONSTRUCTION_SHADOW) {
            try {
                reportConstructionChanges(previous, next);
            } catch (RuntimeException failure) {
                // 道路已经写完；取证错误不能让上层以为保存失败而重试。
                LOGGER.warn("【施工内容变化影子检查失败】 " + "RoadWeaver construction content shadow failed at generation {}", nextGeneration, failure);
            }
        }
        return true;
    }

    private static void reportConstructionChanges(RoadSnapshot previous, RoadSnapshot next) {
        int changed = 0;
        for (var current : next.entries().values()) {
            var old = previous.entries().get(current.fingerprint());
            if (old == null || old == current) continue;
            var delta = RoadFingerprint.constructionChanges(old.road(), current.road());
            if (delta.isEmpty()) continue;
            // 完工仍从旧台账读取。日志只指出同键内容变化，不能据此认为已有区块应重新铺设。
            if (++changed <= 8) {
                LOGGER.info("【同一道路身份的施工内容变化对照】 " + "[CONSTRUCTION-CONTENT-SHADOW] sameFingerprint={} changes={} oldChunks={} newChunks={}",
                        current.fingerprint(), delta, old.footprint().chunkKeys().size(),
                        current.footprint().chunkKeys().size());
            }
        }
        if (changed > 8) LOGGER.info("【其余施工内容变化未逐项列出】 " + "[CONSTRUCTION-CONTENT-SHADOW] {} other changes not listed", changed - 8);
    }

    private long nextGeneration() {
        return generation == Long.MAX_VALUE ? 1L : generation + 1L;
    }

    private static RoadSnapshot.Entry newEntry(long fingerprint, RoadData road, long generation) {
        String fileName = Long.toUnsignedString(fingerprint)
                + ".g" + Long.toUnsignedString(generation) + ".nbt";
        return new RoadSnapshot.Entry(fingerprint, road, RoadFootprint.from(road), fileName);
    }

    private LoadedSnapshot readSnapshot() throws IOException {
        Path indexPath = root.resolve(INDEX_FILE);
        // 文件读取在JSON解析之外：Gson会把Reader的IOException包装成RuntimeException，不能因此误隔离。
        String indexContents;
        try {
            indexContents = Files.readString(indexPath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException newWorld) {
            // 新存档尚无索引。权限错误、目录占位等故障必须走上层的只读降级。
            return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false, null);
        }
        IndexDocument document;
        try {
            document = GSON.fromJson(indexContents, IndexDocument.class);
        } catch (RuntimeException e) {
            FileStorageIO.quarantineCorrupt(indexPath, LOGGER, "道路聚合索引损坏，已隔离");
            return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false, null);
        }
        if (document == null || document.roads == null) {
            FileStorageIO.quarantineCorrupt(indexPath, LOGGER, "道路聚合索引缺少 roads，已隔离");
            return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false, null);
        }

        LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>();
        boolean skippedEntry = false;
        // ★ 与 skippedEntry 分开：只有「这一刻读不到」才置真。
        //   skippedEntry 里的其余 6 种都是「已知这条不可用」（索引项残缺、越界引用、
        //   数据结构性无效、指纹退化、指纹不符…）—— 那些拿部分快照修元数据是安全的；
        //   惟独 IO 读失败不能用部分快照去改写索引。
        boolean readFailure = false;
        for (IndexEntry indexEntry : document.roads) {
            if (indexEntry == null || indexEntry.file == null) {
                skippedEntry = true;
                continue;
            }
            Path roadPath = safeRoadPath(indexEntry.file);
            if (roadPath == null) {
                skippedEntry = true;
                LOGGER.warn("忽略越界道路文件引用: {}", indexEntry.file);
                continue;
            }
            RoadData road;
            try {
                road = readRoadFile(roadPath);
            } catch (IOException failure) {
                // ★ P0（2026-11-15 外部独立审计）：这里**不能只**记 skippedEntry。
                //   读失败 ≠ 数据不存在。标上 readFailure 让上层进只读降级，
                //   否则稍后的「修元数据」会用剔掉这一条的部分快照覆盖 index.json，
                //   那条路就永久从索引消失（而它的 .nbt 还好端端在磁盘上）。
                skippedEntry = true;
                readFailure = true;
                LOGGER.warn("读取道路聚合文件失败（本条**不代表数据丢失**，本次载入将禁止改写索引）: {}",
                        roadPath, failure);
                continue;
            }
            if (road == null) {
                skippedEntry = true;
                continue;
            }
            try {
                RoadData normalized = RoadDataValidator.normalizeLegacyRoad(road);
                if (normalized == null) {
                    skippedEntry = true;
                    LOGGER.warn("【旧道路数据结构不可用，忽略该项】 " + "RoadWeaver ignored structurally unusable legacy road: {}", roadPath);
                    continue;
                }
                RoadData frozen = RoadSnapshot.freezeForStorage(normalized);
                long fingerprint = RoadFingerprint.compute(frozen);
                if (fingerprint == 0L) {
                    skippedEntry = true;
                    continue;
                }
                if (fingerprint != indexEntry.fingerprint || entries.containsKey(fingerprint)) {
                    skippedEntry = true;
                }
                entries.put(fingerprint,
                        new RoadSnapshot.Entry(fingerprint, frozen, RoadFootprint.from(frozen), indexEntry.file));
            } catch (RuntimeException malformedRoad) {
                skippedEntry = true;
                LOGGER.warn("道路聚合数据结构无效，已跳过: {}", roadPath, malformedRoad);
            }
        }
        RoadSnapshot loadedSnapshot = RoadSnapshot.fromEntryMap(entries);
        boolean currentIndex = INDEX_SCHEMA.equals(document.schema) && document.version == SCHEMA_VERSION;
        boolean currentRootSchema = hasCurrentRootSchema();
        // 聚合根读失败时不再校验/修复部分快照的派生分片，完整读取是任何磁盘修改的前提。
        RoadShardVerifier.Result shards = readFailure ? null : new RoadShardVerifier(root).verify(loadedSnapshot);
        long loadedGeneration = Math.max(0L, document.generation);
        return new LoadedSnapshot(
                loadedSnapshot,
                loadedGeneration,
                skippedEntry || !currentIndex || !currentRootSchema || (shards != null && !shards.matches()),
                readFailure || (shards != null && shards.readFailure()), shards);
    }

    private boolean hasCurrentRootSchema() {
        Path schemaPath = root.resolve(SCHEMA_FILE);
        if (!Files.isRegularFile(schemaPath)) return false;
        try (Reader reader = Files.newBufferedReader(schemaPath, StandardCharsets.UTF_8)) {
            SchemaDocument schema = GSON.fromJson(reader, SchemaDocument.class);
            return schema != null && ROOT_SCHEMA.equals(schema.schema) && schema.version == SCHEMA_VERSION;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private void persistSnapshot(RoadSnapshot previous,
                                 RoadSnapshot next,
                                 long targetGeneration,
                                 boolean forceMetadata) throws IOException {
        persistSnapshot(previous, next, targetGeneration, forceMetadata, null);
    }

    /** 持久化的变化区块集合必须完整，不能为减少扫描漏掉旧分片引用。
 *
     * @param affectedChunks A3 优化：调用方已知引用发生变化的区块集合。
     *                       <p>为 {@code null} 时退化为 {@code previous ∪ next} 全量遍历（旧行为）。
     *                       非 null 时只遍历该集合——其完备性由调用方保证：只有 entry 增删
     *                       才会改变某区块的引用数组，而集合已包含所有被增删 entry 的 footprint
     *                       覆盖区块。**注意**：当 {@code forceMetadata} 为 true（元数据修复）
     *                       时忽略该参数，因为此时必须重写全部分片。</p>
     */
    private void persistSnapshot(RoadSnapshot previous,
                                 RoadSnapshot next,
                                 long targetGeneration,
                                 boolean forceMetadata,
                                 Set<Long> affectedChunks) throws IOException {
        Files.createDirectories(root);
        if (forceMetadata && pendingShardRepair != null) {
            // 只有完整读取且无IO故障的验证结果能进入此处；失败时保留列表供下次提交重试。
            for (Path corrupt : pendingShardRepair.corrupt()) FileStorageIO.quarantineCorrupt(corrupt, LOGGER, "道路派生分片解析损坏，按完整聚合根重建");
            for (Path unexpected : pendingShardRepair.unexpected()) Files.deleteIfExists(unexpected);
        }
        writeSchema();

        for (RoadSnapshot.Entry entry : next.entries().values()) {
            RoadSnapshot.Entry old = previous.entries().get(entry.fingerprint());
            Path existingPath = safeRoadPath(entry.fileName());
            if (forceMetadata && old == null && existingPath != null && Files.isRegularFile(existingPath)) continue;
            if (old == null
                    || !old.road().equals(entry.road())
                    || !old.fileName().equals(entry.fileName())) {
                writeRoadFile(requireSafeRoadPath(entry.fileName()), entry.road());
            }
        }

        Set<Long> chunksToScan;
        if (forceMetadata || affectedChunks == null) {
            // 全量路径：首载修复、clear、以及未提供 affected 的旧调用方。
            chunksToScan = new LinkedHashSet<>(previous.chunkReferences().keySet());
            chunksToScan.addAll(next.chunkReferences().keySet());
        } else {
            // A3 收窄路径：只处理引用真正变化的区块。
            // 未列入集合的分片：其引用数组与旧值相同，无需重写也不必删除
            // （newReferences 非空且等于 oldReferences），因此跳过是安全的。
            chunksToScan = affectedChunks;
        }
        for (long packedChunk : chunksToScan) {
            long[] oldReferences = previous.chunkReferences().get(packedChunk);
            long[] newReferences = next.chunkReferences().get(packedChunk);
            if (!forceMetadata && Arrays.equals(oldReferences, newReferences)) continue;
            RoadChunkKey key = RoadChunkKey.fromPacked(packedChunk);
            Path shardPath = chunkPath(key.x(), key.z());
            if (newReferences == null || newReferences.length == 0) {
                Files.deleteIfExists(shardPath);
            } else {
                writeChunkShard(key, newReferences, targetGeneration);
            }
        }

        // index.json 是提交点：它只会引用前面已经成功写入的不可变道路文件。
        writeIndex(next, targetGeneration);

        for (RoadSnapshot.Entry old : previous.entries().values()) {
            RoadSnapshot.Entry current = next.entries().get(old.fingerprint());
            if (current != null && old.fileName().equals(current.fileName())) continue;
            Path oldPath = safeRoadPath(old.fileName());
            if (oldPath == null) continue;
            try {
                Files.deleteIfExists(oldPath);
            } catch (IOException e) {
                LOGGER.warn("清理旧道路聚合文件失败: {}", oldPath, e);
            }
        }
    }

    private void writeSchema() throws IOException {
        SchemaDocument document = new SchemaDocument();
        document.schema = ROOT_SCHEMA;
        document.version = SCHEMA_VERSION;
        document.index = INDEX_FILE;
        document.roadDirectory = ROAD_DIRECTORY;
        document.chunkDirectory = CHUNK_DIRECTORY;
        FileStorageIO.writeStringAtomic(root.resolve(SCHEMA_FILE), GSON.toJson(document));
    }

    private void writeIndex(RoadSnapshot next, long targetGeneration) throws IOException {
        IndexDocument document = new IndexDocument();
        document.schema = INDEX_SCHEMA;
        document.version = SCHEMA_VERSION;
        document.generation = targetGeneration;
        document.roads = new ArrayList<>(next.entries().size());
        for (RoadSnapshot.Entry entry : next.entries().values()) {
            RoadFootprint footprint = entry.footprint();
            IndexEntry indexEntry = new IndexEntry();
            indexEntry.fingerprint = entry.fingerprint();
            indexEntry.width = entry.road().width();
            indexEntry.roadType = entry.road().roadType();
            indexEntry.minX = footprint.isEmpty() ? 0 : footprint.minX();
            indexEntry.minZ = footprint.isEmpty() ? 0 : footprint.minZ();
            indexEntry.maxX = footprint.isEmpty() ? 0 : footprint.maxX();
            indexEntry.maxZ = footprint.isEmpty() ? 0 : footprint.maxZ();
            indexEntry.file = entry.fileName();
            document.roads.add(indexEntry);
        }
        FileStorageIO.writeStringAtomic(root.resolve(INDEX_FILE), GSON.toJson(document));
    }

    private void writeChunkShard(RoadChunkKey key, long[] references, long targetGeneration) throws IOException {
        RoadShardVerifier.ChunkDocument document = new RoadShardVerifier.ChunkDocument();
        document.schema = CHUNK_SCHEMA;
        document.version = SCHEMA_VERSION;
        document.generation = targetGeneration;
        document.chunkX = key.x();
        document.chunkZ = key.z();
        document.roads = new ArrayList<>(references.length);
        for (long fingerprint : references) document.roads.add(fingerprint);
        FileStorageIO.writeStringAtomic(chunkPath(key.x(), key.z()), GSON.toJson(document));
    }

    private Path safeRoadPath(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Path roadDirectory = root.resolve(ROAD_DIRECTORY).normalize();
        Path candidate = roadDirectory.resolve(fileName).normalize();
        if (!candidate.getParent().equals(roadDirectory)
                || !candidate.getFileName().toString().equals(fileName)
                || !fileName.endsWith(".nbt")) {
            return null;
        }
        return candidate;
    }

    private Path requireSafeRoadPath(String fileName) {
        Path path = safeRoadPath(fileName);
        if (path == null) throw new IllegalArgumentException("invalid road file name: " + fileName);
        return path;
    }

    private static void writeRoadFile(Path file, RoadData road) throws IOException {
        CompoundTag compound = new CompoundTag();
        Tag encoded = RoadData.CODEC.encodeStart(NbtOps.INSTANCE, road)
                .result()
                .orElseThrow(() -> new IllegalStateException("road codec encode failed"));
        compound.put("road", encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            NbtIo.write(compound, data);
        }
        FileStorageIO.writeBytesAtomic(file, output.toByteArray());
    }

    private static RoadData readRoadFile(Path file) throws IOException {
        // 索引已经引用这个文件。暂时缺失也必须保留索引，恢复后才能找回原路。
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            FileStorageIO.quarantineCorrupt(file, LOGGER, "道路 NBT 文件为空，已隔离");
            return null;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag compound = NbtIo.read(input);
            if (compound == null || !compound.contains("road")) {
                FileStorageIO.quarantineCorrupt(file, LOGGER, "道路 NBT 缺少聚合根，已隔离");
                return null;
            }
            Tag tag = compound.get("road");
            RoadData road = RoadData.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, tag)).result().orElse(null);
            if (road == null) FileStorageIO.quarantineCorrupt(file, LOGGER, "道路 NBT 解码失败，已隔离");
            return road;
        } catch (RuntimeException e) {
            FileStorageIO.quarantineCorrupt(file, LOGGER, "道路 NBT 损坏，已隔离");
            return null;
        }
    }

    private static boolean isValidRoad(RoadData road) {
        return RoadDataValidator.isValidFinalRoad(road);
    }

    /**
     * 载入结果。
     *
     * <p>{@code readFailure} 是 2026-11-15 外部独立审计抓出的 P0 修复点：
     * 单条道路 NBT 的 <b>IO 读失败</b>与「这条索引确实指向一个不存在的文件」是两件事 ——
     * 前者只说明「这一刻读不到」，<b>不代表数据没了</b>（磁盘抖动 / 文件被占用 / 权限）。
     * 旧实现把两者都算成 {@code skippedEntry}，于是拿「剔掉失败条目」的部分快照
     * 去 {@code persistSnapshot} <b>改写 index.json</b> ⇒ 那份 NBT 永久从索引消失，
     * 而文件本身还在磁盘上。</p>
     *
     * <p>所以这里必须把「读失败」单独带出来，让上层进入只读降级（拒绝写），
     * 而不是把它混进「元数据需要修」那个标志里。</p>
     */
    private record LoadedSnapshot(RoadSnapshot snapshot, long generation,
                                  boolean rewriteMetadata, boolean readFailure, RoadShardVerifier.Result shards) {}

    private static final class SchemaDocument {
        String schema;
        int version;
        String index;
        String roadDirectory;
        String chunkDirectory;
    }

    private static final class IndexDocument {
        String schema;
        int version;
        long generation;
        List<IndexEntry> roads = new ArrayList<>();
    }

    private static final class IndexEntry {
        long fingerprint;
        int width;
        int roadType;
        int minX;
        int minZ;
        int maxX;
        int maxZ;
        String file;
    }


}
