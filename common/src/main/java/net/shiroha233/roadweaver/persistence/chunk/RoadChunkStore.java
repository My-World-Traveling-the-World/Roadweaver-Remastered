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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
    /**
     * 0.8.358：分片校验戳缓存。记录上次全量校验时「文件数 / 最新 mtime / 总字节数」，
     * 三项命中沿用历史快速通道，但统计相同不证明内容相同（见 {@code chunkShardsMatch}）。
     */
    private static final String SHARD_VERIFY_FILE = "shard-verify.json";

    private final Path root;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final AtomicReference<RoadSnapshot> snapshot = new AtomicReference<>(RoadSnapshot.empty());
    private volatile boolean loaded;
    private long generation;

    /**
     * **只读降级标志**（★ 2026-09-26 修 WorkBuddy R-5）。
     *
     * <p>首载遇 {@code IOException}（磁盘抖动 / 文件被占用 / 权限）时置位 ——
     * 此时内存快照是空的，但**磁盘上的真索引可能完好**，
     * 所以绝不能让后续写入用空快照把它覆盖掉。</p>
     *
     * <p>置位后：查询照常（读空快照，不崩）；**写路径一律拒绝**。
     * 生成任务会因此 FAILED 并可重试，重进世界（IO 恢复正常）后自然恢复 ——
     * 这远好于把玩家全部道路索引清成空。</p>
     */
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
        if (!isValidRoad(road)) return;
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            RoadData frozen = RoadSnapshot.freezeForStorage(road);
            long fingerprint = RoadFingerprint.compute(frozen);
            RoadSnapshot.Entry existing = previous.entries().get(fingerprint);
            if (existing != null && existing.road().equals(frozen)) return;

            long nextGeneration = nextGeneration();
            LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>(previous.entries());
            entries.put(fingerprint, newEntry(fingerprint, frozen, nextGeneration));
            publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration);
        } finally {
            mutationLock.unlock();
        }
    }

    /** 批量加入道路后仅重建发布一次不可变快照，避免每条路重复全量索引。 */
    public void addRoads(Collection<RoadData> roads) {
        if (roads == null || roads.isEmpty()) return;
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>(previous.entries());
            long nextGeneration = nextGeneration();
            boolean changed = false;
            for (RoadData road : roads) {
                if (!isValidRoad(road)) continue;
                RoadData frozen = RoadSnapshot.freezeForStorage(road);
                long fingerprint = RoadFingerprint.compute(frozen);
                RoadSnapshot.Entry existing = entries.get(fingerprint);
                if (existing != null && existing.road().equals(frozen)) continue;
                entries.put(fingerprint, newEntry(fingerprint, frozen, nextGeneration));
                changed = true;
            }
            if (changed) publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration);
        } finally {
            mutationLock.unlock();
        }
    }

    public void replaceRoad(long oldFingerprint, RoadData newRoad) {
        replaceRoads(List.of(new RoadReplacement(oldFingerprint, newRoad)));
    }

    public void replaceRoads(Collection<RoadReplacement> replacements) {
        if (replacements == null || replacements.isEmpty()) return;
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
            if (changed) {
                publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration, affectedChunks);
            }
        } finally {
            mutationLock.unlock();
        }
    }

    public void deleteRoad(long fingerprint) {
        mutationLock.lock();
        try {
            ensureLoadedLocked();
            RoadSnapshot previous = snapshot.get();
            if (!previous.entries().containsKey(fingerprint)) return;
            long nextGeneration = nextGeneration();
            LinkedHashMap<Long, RoadSnapshot.Entry> entries = new LinkedHashMap<>(previous.entries());
            entries.remove(fingerprint);
            publish(previous, RoadSnapshot.fromEntryMap(entries), nextGeneration);
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
        // 当前实现没有后台线程或打开的文件句柄；实例由世界路径注册表负责释放。
        //
        // ★ 2026-09-26（R-5）：只读降级标志也在这里复位 —— 实例被关闭即
        //   "该次访问结束"，下次重新创建/加载时应重新尝试真实读取，
        //   而不是把上次的瞬时 IO 失败永久带下去。
        readOnlyDegraded = false;
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
        LoadedSnapshot loadedSnapshot;
        try {
            loadedSnapshot = readSnapshot();
            // ★ 2026-09-26 修 W-8/I-5：**读成功 ⇒ 自动解除只读降级**。
            //   原先只有在 close()（实例释放）时才复位，一次瞬时 IO 抖动会让
            //   整个会话都写不进去。「能读就能写」—— 读成功说明 IO 已恢复。
            if (readOnlyDegraded) {
                readOnlyDegraded = false;
                LOGGER.info("道路 ChunkPos 存储已可正常读取 —— 解除只读降级，恢复写入: {}", root);
            }
        } catch (IOException e) {
            // ★ 2026-09-26 修 WorkBuddy R-5：**进入只读降级，绝不拿空快照去覆盖索引**。
            //
            // 原先这里只是 warn + 用一个空快照继续 —— 但随后**任何一次道路写入**
            // 都会经 persistSnapshot 把这个空快照**全量写回 index.json**，
            // 于是真索引被覆盖 ⇒ 全部道路"不可见"
            // （.nbt 实体文件还在，但不施工、不显示，自动规划还可能重复造路）。
            //
            // ⚠️ 同一文件里存在**两套不对称的处理**（正是缺陷根源）：
            //   · JSON **解析**失败 → quarantineCorrupt 隔离原文件（安全）
            //   · IO **读**失败     → 静默空快照（危险）
            // IO 失败（磁盘抖动 / 文件被占用 / 权限）**不代表数据不存在**，
            // 恰恰最不该把它当成空库。
            //
            // 修法：置 readOnly 降级标志 —— 查询仍用空快照（只读不崩），
            // 但**所有写路径直接拒绝**。宁可让生成任务 FAILED 重试（可恢复），
            // 也不能把玩家的道路索引清掉（不可恢复）。
            LOGGER.error("首次载入道路 ChunkPos 存储失败 —— 已进入**只读降级**，"
                    + "本次会话不会写入道路索引（避免用空快照覆盖真实索引）: {}", root, e);
            readOnlyDegraded = true;
            loadedSnapshot = new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false);
        }
        snapshot.set(loadedSnapshot.snapshot());
        generation = loadedSnapshot.generation();
        loaded = true;

        if (loadedSnapshot.readFailure() && !readOnlyDegraded) {
            // ★ P0（2026-11-15 外部独立审计）：单条读失败也进只读降级。
            //
            // 旧实现的保护是**不对称**的：`readSnapshot()` 整体抛 IOException 时
            // 会进只读降级（下面 catch 那一支，注释写得很好：「IO 失败不代表数据不存在」），
            // 但**单条 NBT 读失败**只是在条目循环里 `continue`，被当成「这条没了」。
            // 于是 `rewriteMetadata` 变真 ⇒ 用剔掉失败条目的部分快照改写 index.json
            // ⇒ 那条路永久从索引消失（NBT 仍在磁盘上，只能人工恢复）。
            // 实测：写两条合法道路、临时锁住一份 NBT ⇒ 首次打开索引变一条，解锁后仍一条。
            //
            // 这里不改读路径（查询继续用这份部分快照，不崩），只**拒绝一切写入**：
            // 索引保持原样，等下一次能完整读出时自然恢复。
            readOnlyDegraded = true;
            LOGGER.error("道路 ChunkPos 存储的索引里有道路文件本次读取失败 —— 已进入**只读降级**，"
                    + "本次会话不会改写索引（避免把读不到的道路永久从索引剔除；"
                    + "其 .nbt 文件仍在 {} 下，可重试或人工恢复）", root);
        }
        if (loadedSnapshot.rewriteMetadata() && !readOnlyDegraded) {
            long repairGeneration = generation == 0L ? 1L : generation;
            try {
                persistSnapshot(RoadSnapshot.empty(), loadedSnapshot.snapshot(), repairGeneration, true);
                generation = repairGeneration;
            } catch (IOException e) {
                // 查询仍使用已完成的一次性内存快照；下次实际修改会重新写完整元数据。
                LOGGER.warn("修复道路 ChunkPos 分片元数据失败: {}", root, e);
            }
        }
    }

    private void publish(RoadSnapshot previous, RoadSnapshot next, long nextGeneration) {
        publish(previous, next, nextGeneration, null);
    }

    /** 发布新旧快照时只刷新已知变化区块，未知范围必须遍历完整差集。
 *
     * @param affectedChunks A3：已知引用发生变化的区块集合；{@code null} 表示未知，
     *                       退化为 previous ∪ next 全量遍历（保持旧行为）。
     */
    private void publish(RoadSnapshot previous, RoadSnapshot next, long nextGeneration,
                         Set<Long> affectedChunks) {
        // 0.8.345：**补上写入闸门**。
        // 此前这里既不校验 RoadLifecycle.allowsWrites() 也不校验 epoch，
        // 而同类的 StructureFileStorage 在每一处写操作前都校验（共 19 处）。
        // 后果：关服瞬间处于"已过入口校验、落盘中途"的任务会把旧世界道路写进存档。
        // 与 StructureFileStorage 保持同一口径：停服即拒绝写入。
        if (!net.shiroha233.roadweaver.runtime.RoadLifecycle.allowsWrites()) {
            LOGGER.warn("【服务端正在停止，跳过当前代次道路发布】 " + "RoadWeaver road chunk publish skipped: server is stopping (generation {} dropped)", nextGeneration);
            return;
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
            persistSnapshot(previous, next, nextGeneration, false, affectedChunks);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist road ChunkPos snapshot", e);
        }
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
        if (!Files.isRegularFile(indexPath)) return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false);

        IndexDocument document;
        try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            document = GSON.fromJson(reader, IndexDocument.class);
        } catch (RuntimeException | IOException e) {
            FileStorageIO.quarantineCorrupt(indexPath, LOGGER, "道路聚合索引损坏，已隔离");
            return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false);
        }
        if (document == null || document.roads == null) {
            FileStorageIO.quarantineCorrupt(indexPath, LOGGER, "道路聚合索引缺少 roads，已隔离");
            return new LoadedSnapshot(RoadSnapshot.empty(), 0L, false, false);
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
        boolean currentShards = chunkShardsMatch(loadedSnapshot);
        long loadedGeneration = Math.max(0L, document.generation);
        return new LoadedSnapshot(
                loadedSnapshot,
                loadedGeneration,
                skippedEntry || !currentIndex || !currentRootSchema || !currentShards,
                readFailure);
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
    private boolean chunkShardsMatch(RoadSnapshot expected) {
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
            if (Arrays.equals(oldReferences, newReferences)) continue;
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
        ChunkDocument document = new ChunkDocument();
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
        if (!Files.isRegularFile(file)) return null;
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
                                  boolean rewriteMetadata, boolean readFailure) {}

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

    private static final class ChunkDocument {
        String schema;
        int version;
        long generation;
        int chunkX;
        int chunkZ;
        List<Long> roads = new ArrayList<>();
    }
}
