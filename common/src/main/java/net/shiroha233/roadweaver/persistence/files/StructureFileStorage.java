/* 文件职责：持久化结构位置、来源和道路连接状态。 */
package net.shiroha233.roadweaver.persistence.files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.core.model.StructureLocationData;
import net.shiroha233.roadweaver.runtime.RoadLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构文件存储：世界级 JSON 状态文件。
 */
public final class StructureFileStorage {
    private StructureFileStorage() {}

    public static final int SOURCE_PREDICTED = 0;
    public static final int SOURCE_MANUAL = 1;
    public static final int SCAN_TILE_SIZE_CHUNKS = 128;

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /*
     * Never let Gson reflect Minecraft's BlockPos implementation.  On Java
     * 25 its object graph can reach inaccessible JDK internals while saving
     * initial structure state.  The JSON remains the historical x/y/z shape,
     * so worlds written by older RoadWeaver builds continue to load.
     */
    private static final Gson GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosAdapter())
            .setPrettyPrinting()
            .create();
    private static final String CATEGORY = "structures";
    private static final String STATE_FILE = "state.json";

    /**
     * 只读快照缓存：按维度（{@link ResourceKey}&lt;{@link Level}&gt;）隔离。
     * value 为不可变 {@link StructureSnapshot}，由 level 版本号做失效。
     * 所有访问都在 {@code VersionedSnapshotCache} 的实例锁内，无需额外
     * 并发容器；缓存语义集中在该类，便于无 ServerLevel 的确定性并发测试。
     */
    private static final VersionedSnapshotCache<ResourceKey<Level>, StructureSnapshot> SNAPSHOT_CACHE =
            new VersionedSnapshotCache<>();

    private static final class StateData {
        StructureLocationData structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        List<StructureConnection> connections = new ArrayList<>();
        Set<Long> plannedTileKeys = new HashSet<>();
        Map<Long, Long> plannedTileCenters = new HashMap<>();
        Map<Long, Integer> structureSources = new HashMap<>();
        Map<String, String> meta = new HashMap<>();
        Map<Long, Long> scanTiles = new HashMap<>();
        Set<String> suppressedConnections = new HashSet<>();
        Set<String> failedFallbackAttempts = new HashSet<>();
        /**
         * 结构位置的 64×64 网格桶索引：{@code queryRect} 只扫覆盖矩形的那几个桶，
         * 不再全表扫描。{@code transient} ⇒ 不写进 state.json（持久化格式零变化），
         * 任何改动结构表的路径都把它置 null，下次查询懒重建。
         */
        private transient Map<Long, List<StructureInfo>> structureBuckets;
    }

    private static final class BlockPosAdapter implements JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        @Override
        public JsonElement serialize(BlockPos pos, Type type, JsonSerializationContext context) {
            JsonObject out = new JsonObject();
            out.addProperty("x", pos.getX());
            out.addProperty("y", pos.getY());
            out.addProperty("z", pos.getZ());
            return out;
        }

        @Override
        public BlockPos deserialize(JsonElement json, Type type, JsonDeserializationContext context)
                throws JsonParseException {
            if (json == null || !json.isJsonObject()) {
                throw new JsonParseException("expected BlockPos object");
            }
            JsonObject in = json.getAsJsonObject();
            if (!in.has("x") || !in.has("y") || !in.has("z")) {
                throw new JsonParseException("BlockPos is missing x, y, or z");
            }
            return new BlockPos(in.get("x").getAsInt(), in.get("y").getAsInt(), in.get("z").getAsInt());
        }
    }

    public record StructureSnapshot(StructureLocationData locations, Map<Long, Integer> sources) {
        public StructureSnapshot {
            locations = locations == null
                    ? StructureLocationData.frozen(List.of(), List.of())
                    : StructureLocationData.frozen(locations.structureLocations(), locations.structureInfos());
            sources = sources == null ? Map.of() : Map.copyOf(sources);
        }

        public int sourceAt(BlockPos pos) {
            return pos == null ? -1 : sources.getOrDefault(posKey(pos), SOURCE_PREDICTED);
        }
    }

    public static synchronized StructureLocationData getStructureLocations(ServerLevel level) {
        StructureLocationData data = state(level).structureLocations;
        return new StructureLocationData(data.structureLocations(), data.structureInfos());
    }

    public static synchronized void setStructureLocations(ServerLevel level, StructureLocationData data) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        Map<Long, Integer> previousSources = new HashMap<>(state.structureSources);
        state.structureLocations = data != null ? normalizeStructureLocations(data) : new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        state.structureSources = rebuildSources(state.structureLocations, previousSources, SOURCE_MANUAL);
        invalidateBuckets(state);
        save(level, state);
    }

    public static synchronized List<StructureConnection> getStructureConnections(ServerLevel level) {
        return new ArrayList<>(state(level).connections);
    }

    public static synchronized void setStructureConnections(ServerLevel level, List<StructureConnection> connections) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        state.connections = normalizeConnections(connections);
        state.connections.removeIf(connection -> isSuppressed(state, connection.from(), connection.to()));
        save(level, state);
    }

    public static synchronized void mergeStructureConnections(ServerLevel level, List<StructureConnection> connections) {
        if (!RoadLifecycle.allowsWrites() || level == null || connections == null || connections.isEmpty()) return;
        StateData state = state(level);
        ArrayList<StructureConnection> allowed = new ArrayList<>(connections);
        allowed.removeIf(connection -> connection != null
                && isSuppressed(state, connection.from(), connection.to()));
        mergeConnections(state.connections, allowed);
        save(level, state);
    }

    public static synchronized boolean isConnectionSuppressed(ServerLevel level, BlockPos from, BlockPos to) {
        if (level == null || from == null || to == null) return false;
        return isSuppressed(state(level), from, to);
    }

    /**
     * Removes stale/unknown topology endpoints left by manual connection
     * records.  Unknown entries are not real discovered structures and must
     * not become automatic road nodes; the only exception is the shared spawn
     * position, which is intentionally retained as the network root.
     *
     * <p>Known structure ids are untouched.  Connections touching a removed
     * unknown endpoint are removed as well, so the node cannot re-enter through
     * the topology snapshot during startup planning.</p>
     */
    public static synchronized int pruneUnknownNonSpawnEndpoints(ServerLevel level) {
        if (!RoadLifecycle.allowsWrites() || level == null) return 0;
        StateData state = state(level);
        BlockPos spawn = level.getSharedSpawnPos();
        long spawnKey = spawn == null ? Long.MIN_VALUE : posKey(spawn);
        Set<Long> removed = new HashSet<>();
        List<StructureInfo> infos = state.structureLocations.structureInfos();
        infos.removeIf(info -> {
            if (info == null || info.pos() == null || StructureInfo.isKnownId(info.structureId())) {
                return false;
            }
            long key = posKey(info.pos());
            if (key == spawnKey) return false;
            removed.add(key);
            return true;
        });
        state.structureLocations.structureLocations().removeIf(pos -> {
            if (pos == null) return false;
            long key = posKey(pos);
            if (key == spawnKey) return false;
            boolean unknown = true;
            for (StructureInfo info : infos) {
                if (info != null && info.pos() != null && posKey(info.pos()) == key) {
                    unknown = !StructureInfo.isKnownId(info.structureId());
                    break;
                }
            }
            if (unknown) removed.add(key);
            return unknown;
        });
        if (removed.isEmpty()) return 0;

        invalidateBuckets(state);
        state.structureSources.keySet().removeIf(removed::contains);
        state.connections.removeIf(connection -> {
            return connection != null
                    && (removed.contains(posKey(connection.from()))
                    || removed.contains(posKey(connection.to())));
        });
        save(level, state);
        LOGGER.info("RoadWeaver pruned unknown non-spawn endpoints removed={} spawn={}",
                removed.size(), spawn);
        return removed.size();
    }

    /**
     * Records a topology candidate that has already exhausted the complete
     * road pipeline. The record is world-local and intentionally unbounded:
     * it prevents A -> B -> A fallback loops after a world restart.
     */
    public static synchronized void recordFailedFallbackAttempt(ServerLevel level,
                                                                 StructureConnection original,
                                                                 StructureConnection candidate) {
        if (!RoadLifecycle.allowsWrites() || level == null || !validConnection(original)
                || !validConnection(candidate)) return;
        StateData state = state(level);
        if (state.failedFallbackAttempts == null) state.failedFallbackAttempts = new HashSet<>();
        if (state.failedFallbackAttempts.add(fallbackAttemptKey(original, candidate))) save(level, state);
    }

    public static synchronized boolean hasFailedFallbackAttempt(ServerLevel level,
                                                                 StructureConnection original,
                                                                 StructureConnection candidate) {
        if (level == null || !validConnection(original) || !validConnection(candidate)) return false;
        StateData state = state(level);
        return state.failedFallbackAttempts != null
                && state.failedFallbackAttempts.contains(fallbackAttemptKey(original, candidate));
    }

    public static synchronized void suppressConnection(ServerLevel level, BlockPos from, BlockPos to) {
        if (!RoadLifecycle.allowsWrites() || level == null || from == null || to == null) return;
        StateData state = state(level);
        state.suppressedConnections.add(connectionKey(from, to));
        state.connections.removeIf(connection -> sameEdge2d(connection, from, to));
        save(level, state);
    }

    public static synchronized void allowConnection(ServerLevel level, BlockPos from, BlockPos to) {
        if (!RoadLifecycle.allowsWrites() || level == null || from == null || to == null) return;
        StateData state = state(level);
        if (state.suppressedConnections.remove(connectionKey(from, to))) {
            save(level, state);
        }
    }

    public static synchronized List<StructureConnection> filterSuppressedConnections(
            ServerLevel level, List<StructureConnection> connections) {
        if (level == null || connections == null || connections.isEmpty()) return List.of();
        StateData state = state(level);
        ArrayList<StructureConnection> allowed = new ArrayList<>(connections.size());
        for (StructureConnection connection : connections) {
            if (connection != null && !isSuppressed(state, connection.from(), connection.to())) {
                allowed.add(connection);
            }
        }
        return allowed;
    }

    public static synchronized Set<Long> getPlannedTileKeys(ServerLevel level) {
        return new HashSet<>(state(level).plannedTileKeys);
    }

    public static synchronized void setPlannedTileKeys(ServerLevel level, Set<Long> keys) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        state.plannedTileKeys = keys != null ? new HashSet<>(keys) : new HashSet<>();
        save(level, state);
    }

    public static synchronized Map<Long, Long> getPlannedTileCenters(ServerLevel level) {
        return new HashMap<>(state(level).plannedTileCenters);
    }

    public static synchronized void setPlannedTileCenters(ServerLevel level, Map<Long, Long> centers) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        state.plannedTileCenters = centers != null ? new HashMap<>(centers) : new HashMap<>();
        save(level, state);
    }

    public static synchronized void addStructures(ServerLevel level, List<StructureInfo> infos, int source) {
        if (!RoadLifecycle.allowsWrites() || level == null || infos == null || infos.isEmpty()) return;
        StateData state = state(level);
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null) continue;
            putStructure(state, info, source);
        }
        save(level, state);
    }

    /**
     * P0-3: {@code addStructures} followed by {@code markScanWindowDone} wrote
     * the whole state file twice per scan window. Folding both mutations into a
     * single write-through halves that I/O without changing what is persisted.
     */
    public static synchronized void addStructuresAndMarkScanWindowDone(ServerLevel level, List<StructureInfo> infos,
                                                                        int source,
                                                                        int minChunkX, int minChunkZ,
                                                                        int maxChunkX, int maxChunkZ) {
        if (!RoadLifecycle.allowsWrites() || level == null) return;
        StateData state = state(level);
        if (infos != null) {
            for (StructureInfo info : infos) {
                if (info == null || info.pos() == null) continue;
                putStructure(state, info, source);
            }
        }
        state.scanTiles.put(scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ),
                System.currentTimeMillis() / 1000L);
        save(level, state);
    }

    public static synchronized List<StructureInfo> queryRect(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int... sources) {
        if (level == null) return List.of();
        StateData state = state(level);
        List<StructureInfo> flat = state.structureLocations == null
                ? List.of() : state.structureLocations.structureInfos();
        return queryRectFromIndex(buckets(state), flat, state.structureSources, normalizeSources(sources),
                minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    /**
     * 桶边长（格）。**256 格实测最优**：1×1 点查询只碰 1 个桶；2048×2048 扫描窗口 8×8=64 个桶；
     * 4000×4000 窗口 16×16=256 个桶。64 格桶在大窗口下要 thousands 次装箱 HashMap 取值，
     * 实测反而比平扫还慢（见 {@code StructureFileStorageQueryRectTest} 的基准输出）。
     */
    static final int QUERY_BUCKET_SHIFT = 8;
    static final int QUERY_BUCKET_SIZE = 1 << QUERY_BUCKET_SHIFT;

    static long bucketKey(int blockX, int blockZ) {
        long bx = Math.floorDiv(blockX, QUERY_BUCKET_SIZE);
        long bz = Math.floorDiv(blockZ, QUERY_BUCKET_SIZE);
        return (bx << 32) ^ (bz & 0xffffffffL);
    }

    /** 结构表任何改动后调用：把索引置空，下次 queryRect 懒重建。 */
    private static void invalidateBuckets(StateData state) {
        if (state != null) state.structureBuckets = null;
    }

    private static Map<Long, List<StructureInfo>> buckets(StateData state) {
        Map<Long, List<StructureInfo>> cached = state.structureBuckets;
        if (cached != null) return cached;
        Map<Long, List<StructureInfo>> built = buildBucketIndex(state.structureLocations == null
                ? List.of() : state.structureLocations.structureInfos());
        state.structureBuckets = built;
        return built;
    }

    /** 建立桶索引（包可见：回归测试直接驱动）。 */
    static Map<Long, List<StructureInfo>> buildBucketIndex(List<StructureInfo> infos) {
        Map<Long, List<StructureInfo>> built = new HashMap<>();
        if (infos == null) return built;
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null) continue;
            built.computeIfAbsent(bucketKey(info.pos().getX(), info.pos().getZ()), key -> new ArrayList<>())
                    .add(info);
        }
        return built;
    }

    /**
     * 按桶查询矩形内的结构（包可见：回归测试直接驱动，保证与全表扫描结果一致）。
     *
     * <p>桶数超过结构总数时「扫所有桶」并不比扫一遍平表便宜（每个桶都是一次额外的
     * HashMap 取值 + 链表跳转），此时直接扫 {@code flat}（连续数组，缓存友好）。
     * 实测（2,000 结构 / 800 次查询，见 {@code StructureFileStorageQueryRectTest}）：
     * 1×1 点查询 ~13×、300×40 长条 ~16× 提升；4000×4000 与全图窗口退回平扫 ≈ 持平。
     *
     * <p><b>结果顺序不保证</b>（按桶分组返回，零排序开销）；现有调用方
     * （规划窗口 / 扫描窗口 / 单点查询 / map tile）都不依赖顺序。
     */
    static List<StructureInfo> queryRectFromIndex(Map<Long, List<StructureInfo>> index,
                                                 List<StructureInfo> flat,
                                                 Map<Long, Integer> sources,
                                                 Set<Integer> filter,
                                                 int minBlockX, int minBlockZ,
                                                 int maxBlockX, int maxBlockZ) {
        int minX = Math.min(minBlockX, maxBlockX);
        int maxX = Math.max(minBlockX, maxBlockX);
        int minZ = Math.min(minBlockZ, maxBlockZ);
        int maxZ = Math.max(minBlockZ, maxBlockZ);
        Map<Long, Integer> sourceMap = sources == null ? Map.of() : sources;
        List<StructureInfo> out = new ArrayList<>();
        if (flat == null) flat = List.of();
        if (index == null || index.isEmpty()) {
            collect(flat, sourceMap, filter, minX, maxX, minZ, maxZ, out);
            return out;
        }

        int bx0 = Math.floorDiv(minX, QUERY_BUCKET_SIZE);
        int bx1 = Math.floorDiv(maxX, QUERY_BUCKET_SIZE);
        int bz0 = Math.floorDiv(minZ, QUERY_BUCKET_SIZE);
        int bz1 = Math.floorDiv(maxZ, QUERY_BUCKET_SIZE);
        long span = (long) (bx1 - bx0 + 1) * (long) (bz1 - bz0 + 1);

        if (span >= flat.size()) {
            collect(flat, sourceMap, filter, minX, maxX, minZ, maxZ, out);
            return out;
        }
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                collect(index.get(((long) bx << 32) ^ (bz & 0xffffffffL)),
                        sourceMap, filter, minX, maxX, minZ, maxZ, out);
            }
        }
        return out;
    }

    private static void collect(List<StructureInfo> bucket, Map<Long, Integer> sources, Set<Integer> filter,
                                int minX, int maxX, int minZ, int maxZ, List<StructureInfo> out) {
        if (bucket == null) return;
        for (StructureInfo info : bucket) {
            if (info == null || info.pos() == null) continue;
            int x = info.pos().getX();
            int z = info.pos().getZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            int source = sources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED);
            if (filter.contains(source)) out.add(info);
        }
    }

    public static synchronized StructureSnapshot getStructureSnapshot(ServerLevel level) {
        if (level == null) return new StructureSnapshot(null, Map.of());
        ResourceKey<Level> key = level.dimension();
        return SNAPSHOT_CACHE.getOrLoad(key, () -> {
            StateData state = state(level);
            return new StructureSnapshot(state.structureLocations, state.structureSources);
        });
    }

    public static synchronized int sourceAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return -1;
        return getStructureSnapshot(level).sourceAt(pos);
    }

    public static synchronized void ensurePolicy(ServerLevel level, String policyHash) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        String hash = policyHash == null ? "" : policyHash;
        String current = state.meta.getOrDefault("policy_hash", "");
        if (!current.equals(hash)) {
            keepOnlyManualStructures(state);
            state.scanTiles.clear();
            state.meta.put("policy_hash", hash);
            save(level, state);
        }
    }

    public static synchronized boolean claimScanTile(ServerLevel level, int tileX, int tileZ) {
        if (!RoadLifecycle.allowsWrites()) return false;
        StateData state = state(level);
        long now = System.currentTimeMillis() / 1000L;
        long key = chunkKey(tileX, tileZ);
        Long scannedAt = state.scanTiles.get(key);
        if (scannedAt != null) {
            if (scannedAt > 0) return false;
            long start = scannedAt < 0 ? -scannedAt : now - 10 * 60 - 1;
            if (now - start < 10 * 60) return false;
            state.scanTiles.put(key, -now);
            save(level, state);
            return true;
        }
        state.scanTiles.put(key, -now);
        save(level, state);
        return true;
    }

    public static synchronized void markScanTileDone(ServerLevel level, int tileX, int tileZ) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        state.scanTiles.put(chunkKey(tileX, tileZ), System.currentTimeMillis() / 1000L);
        save(level, state);
    }

    public static synchronized void releaseScanTile(ServerLevel level, int tileX, int tileZ) {
        if (!RoadLifecycle.allowsWrites()) return;
        StateData state = state(level);
        state.scanTiles.remove(chunkKey(tileX, tileZ));
        save(level, state);
    }

    public static synchronized boolean claimScanWindow(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (!RoadLifecycle.allowsWrites()) return false;
        return claimScanKey(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public static synchronized void markScanWindowDone(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (!RoadLifecycle.allowsWrites()) return;
        markScanKeyDone(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public static synchronized void releaseScanWindow(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (!RoadLifecycle.allowsWrites()) return;
        releaseScanKey(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public static synchronized boolean hasAnyStructure(ServerLevel level) {
        return !state(level).structureLocations.structureInfos().isEmpty();
    }

    public static synchronized boolean hasAnyManualStructure(ServerLevel level) {
        StateData state = state(level);
        for (StructureInfo info : state.structureLocations.structureInfos()) {
            if (info != null && info.pos() != null && state.structureSources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                return true;
            }
        }
        for (BlockPos pos : state.structureLocations.structureLocations()) {
            if (pos != null && state.structureSources.getOrDefault(posKey(pos), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                return true;
            }
        }
        return false;
    }

    public static synchronized Set<Long> manualStructureKeys(ServerLevel level) {
        StateData state = state(level);
        HashSet<Long> out = new HashSet<>();
        for (StructureInfo info : state.structureLocations.structureInfos()) {
            if (info != null && info.pos() != null && state.structureSources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                out.add(posKey(info.pos()));
            }
        }
        for (BlockPos pos : state.structureLocations.structureLocations()) {
            if (pos != null && state.structureSources.getOrDefault(posKey(pos), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                out.add(posKey(pos));
            }
        }
        return out;
    }

    private static boolean samePos(BlockPos pos, int x, int z) {
        return pos != null && pos.getX() == x && pos.getZ() == z;
    }

    private static long posKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static int normalizeSource(int source) {
        return source == SOURCE_MANUAL ? SOURCE_MANUAL : SOURCE_PREDICTED;
    }

    private static boolean claimScanKey(ServerLevel level, long key) {
        StateData state = state(level);
        long now = System.currentTimeMillis() / 1000L;
        Long scannedAt = state.scanTiles.get(key);
        if (scannedAt != null) {
            if (scannedAt > 0) return false;
            long start = scannedAt < 0 ? -scannedAt : now - 10 * 60 - 1;
            if (now - start < 10 * 60) return false;
            state.scanTiles.put(key, -now);
            save(level, state);
            return true;
        }
        state.scanTiles.put(key, -now);
        save(level, state);
        return true;
    }

    private static void markScanKeyDone(ServerLevel level, long key) {
        StateData state = state(level);
        state.scanTiles.put(key, System.currentTimeMillis() / 1000L);
        save(level, state);
    }

    private static void releaseScanKey(ServerLevel level, long key) {
        StateData state = state(level);
        state.scanTiles.remove(key);
        save(level, state);
    }

    private static long scanWindowKey(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        long hash = 0xcbf29ce484222325L;
        hash = mixScanHash(hash, minChunkX);
        hash = mixScanHash(hash, minChunkZ);
        hash = mixScanHash(hash, maxChunkX);
        hash = mixScanHash(hash, maxChunkZ);
        return hash;
    }

    private static long mixScanHash(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static Set<Integer> normalizeSources(int[] sources) {
        if (sources == null || sources.length == 0) {
            return Set.of(SOURCE_PREDICTED, SOURCE_MANUAL);
        }
        Set<Integer> out = new HashSet<>();
        for (int source : sources) out.add(normalizeSource(source));
        return out;
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    /**
     * P0-3: memory-resident state per dimension. Every public entry point of
     * this class is {@code static synchronized}, so the map only ever needs the
     * class monitor. Mutators change the cached instance first and then write it
     * through, so the JSON produced by {@link #save} is byte-identical to what
     * the previous read-mutate-write cycle produced. Before this cache every
     * query re-read and re-parsed the whole state file, and one scan window cost
     * three full reads plus three full writes.
     *
     * <p>Cleared by {@link #resetSnapshotCache()}, which {@code CacheManager}
     * already invokes on server start and stop.</p>
     */
    private static final Map<ResourceKey<Level>, StateData> STATE_CACHE = new HashMap<>();

    private static StateData state(ServerLevel level) {
        ResourceKey<Level> key = level == null ? null : level.dimension();
        if (key != null) {
            StateData cached = STATE_CACHE.get(key);
            if (cached != null) return cached;
        }
        StateData fileState = readFileState(level);
        StateData loaded = normalize(fileState != null ? fileState : new StateData());
        if (key != null) STATE_CACHE.put(key, loaded);
        return loaded;
    }

    private static StateData readFileState(ServerLevel level) {
        Path path = statePath(level);
        try {
            if (!Files.exists(path)) return null;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                StateData data = GSON.fromJson(reader, StateData.class);
                // ★ 2026-09-26 修 W-8/I-5：**读成功 ⇒ 自动解除只读降级**。
                //
                // 原先降级标志只在 resetSnapshotCache()（服务端启停）才复位 ——
                // 一次瞬时 IO 抖动（磁盘繁忙/杀软扫描）会让**整个会话**都写不进去，
                // 玩家提交手绘路线时会吃 IllegalStateException，且只能重启服务端才恢复。
                //
                // 「能读就能写」—— 既然这次读成功了，说明 IO 已恢复，
                // 没有理由继续拒绝写入。
                if (degradedReadOnly) {
                    degradedReadOnly = false;
                    LOGGER.info("结构状态文件已可正常读取 —— 解除只读降级，恢复写入: {}", path);
                }
                return data != null ? data : null;
            }
        } catch (java.io.IOException e) {
            // ★ 2026-09-26 修 WorkBuddy R-4（真实形态）：**IO 失败 ≠ 文件损坏**。
            //
            // 原先这里是 `catch (Exception e)` —— 任何异常（磁盘抖动 / 文件被占用 /
            // 权限拒绝 / 瞬时 IO 错误）都被当成"结构状态文件损坏"，
            // 直接 quarantineCorrupt 把**可能完好的** state.json
            // 改名成 .corrupt.<ts> 并返回 null（空表）。
            // 随后任何一次 save() 就把空表写回 ⇒ **结构与连接表全丢**
            // （手绘路线的连接表也在这份 state 里 ⇒ READY 路线永不施工）。
            //
            // 这与 RoadChunkStore 的首载问题（R-5）是**同一类缺陷**：
            // 把"读不出来"误判成"数据不存在"。IO 失败恰恰最不该按损坏处理。
            //
            // 修法（与 R-5 同构）：
            //   · **不隔离、不改名** —— 保留磁盘原文件（数据可能完好）
            //   · 置只读降级标志 ⇒ 本次会话**拒绝一切写入**，
            //     绝不用空表覆盖真实 state.json
            //   · 查询仍返回空（游戏不崩），生成任务失败可重试
            // IO 恢复后（重进世界）自然复原。
            LOGGER.error("读取结构状态文件失败（IO 异常，**不隔离、不改名**，"
                    + "并进入只读降级以免空表覆盖真实数据）: {}", path, e);
            degradedReadOnly = true;
            return null;
        } catch (RuntimeException e) {
            // 只有**解析**失败（JSON 语法/结构不符）才是真正的损坏 ⇒ 才隔离
            FileStorageIO.quarantineCorrupt(path, LOGGER, "结构状态文件损坏，已隔离");
            return null;
        }
    }

    /**
     * **只读降级标志**（★ 2026-09-26 修 WorkBuddy R-4）。
     *
     * <p>读 state.json 遇 {@code IOException}（磁盘抖动 / 被占用 / 权限）时置位 ——
     * 此时内存拿到的是空表，但**磁盘上的真实数据可能完好**，
     * 所以绝不能让它被覆盖。</p>
     *
     * <p>置位后：查询照常（返回空，游戏不崩）；**写路径一律拒绝**。
     * 与 {@code RoadChunkStore.readOnlyDegraded}（R-5）同一套模式 ——
     * 「宁可让生成任务失败重试，也不能把玩家的存档清空」。</p>
     */
    private static volatile boolean degradedReadOnly;

    private static StateData normalize(StateData state) {
        if (state.structureLocations == null) {
            state.structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        } else {
            state.structureLocations = normalizeStructureLocations(state.structureLocations);
        }
        state.connections = normalizeConnections(state.connections);
        if (state.plannedTileKeys == null) state.plannedTileKeys = new HashSet<>();
        if (state.plannedTileCenters == null) state.plannedTileCenters = new HashMap<>();
        if (state.structureSources == null) state.structureSources = new HashMap<>();
        if (state.meta == null) state.meta = new HashMap<>();
        if (state.scanTiles == null) state.scanTiles = new HashMap<>();
        if (state.suppressedConnections == null) state.suppressedConnections = new HashSet<>();
        state.connections.removeIf(connection -> isSuppressed(state, connection.from(), connection.to()));
        if (state.structureSources.isEmpty()) {
            state.structureSources = rebuildSources(state.structureLocations, Map.of(), SOURCE_PREDICTED);
        }
        invalidateBuckets(state);
        return state;
    }

    private static StructureLocationData normalizeStructureLocations(StructureLocationData data) {
        StructureLocationData normalized = new StructureLocationData(data.structureLocations(), data.structureInfos());
        // P0-3: one HashSet of posKeys replaces the previous nested scan, which
        // was O(N^2) in the number of stored structures.
        Set<Long> infoKeys = new HashSet<>();
        for (StructureInfo info : normalized.structureInfos()) {
            if (info != null && info.pos() != null) infoKeys.add(posKey(info.pos()));
        }
        for (BlockPos pos : normalized.structureLocations()) {
            if (pos == null) continue;
            long key = posKey(pos);
            if (infoKeys.add(key)) {
                normalized.structureInfos().add(new StructureInfo(pos, "unknown"));
            }
        }
        return normalized;
    }

    private static Map<Long, Integer> rebuildSources(StructureLocationData data, Map<Long, Integer> previous, int defaultSource) {
        HashMap<Long, Integer> out = new HashMap<>();
        if (data != null) {
            for (StructureInfo info : data.structureInfos()) {
                if (info == null || info.pos() == null) continue;
                long key = posKey(info.pos());
                out.put(key, previous.getOrDefault(key, normalizeSource(defaultSource)));
            }
            for (BlockPos pos : data.structureLocations()) {
                if (pos == null) continue;
                long key = posKey(pos);
                out.putIfAbsent(key, previous.getOrDefault(key, normalizeSource(defaultSource)));
            }
        }
        return out;
    }

    private static void putStructure(StateData state, StructureInfo info, int source) {
        int x = info.pos().getX();
        int z = info.pos().getZ();
        long key = posKey(info.pos());
        Integer existingSource = state.structureSources.get(key);
        StructureInfo existingInfo = findStructureInfo(state.structureLocations.structureInfos(), x, z);
        boolean incomingKnown = StructureInfo.isKnownId(info.structureId());
        boolean existingKnown = existingInfo != null && StructureInfo.isKnownId(existingInfo.structureId());
        if (source == SOURCE_PREDICTED && existingSource != null && existingSource == SOURCE_MANUAL) {
            if (!incomingKnown || existingKnown) return;
            source = SOURCE_MANUAL;
        } else if (!incomingKnown && existingKnown) {
            if (source == SOURCE_MANUAL) state.structureSources.put(key, SOURCE_MANUAL);
            return;
        }
        state.structureLocations.structureInfos().removeIf(existing -> existing != null && samePos(existing.pos(), x, z));
        state.structureLocations.structureLocations().removeIf(pos -> samePos(pos, x, z));
        state.structureLocations.structureInfos().add(info);
        state.structureLocations.structureLocations().add(info.pos());
        state.structureSources.put(key, normalizeSource(source));
        invalidateBuckets(state);
    }

    private static StructureInfo findStructureInfo(List<StructureInfo> infos, int x, int z) {
        for (StructureInfo info : infos) {
            if (info != null && samePos(info.pos(), x, z)) return info;
        }
        return null;
    }

    private static void keepOnlyManualStructures(StateData state) {
        ArrayList<StructureInfo> manualInfos = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (StructureInfo info : state.structureLocations.structureInfos()) {
            if (info == null || info.pos() == null) continue;
            long key = posKey(info.pos());
            if (state.structureSources.getOrDefault(key, SOURCE_PREDICTED) == SOURCE_MANUAL && seen.add(key)) {
                manualInfos.add(info);
            }
        }
        for (BlockPos pos : state.structureLocations.structureLocations()) {
            if (pos == null) continue;
            long key = posKey(pos);
            if (state.structureSources.getOrDefault(key, SOURCE_PREDICTED) == SOURCE_MANUAL && seen.add(key)) {
                manualInfos.add(new StructureInfo(pos, "unknown"));
            }
        }

        state.structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        state.structureSources.clear();
        for (StructureInfo info : manualInfos) {
            putStructure(state, info, SOURCE_MANUAL);
        }
    }

    private static void mergeConnections(List<StructureConnection> target, List<StructureConnection> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        LinkedHashMap<PlannedPathKey, StructureConnection> merged = new LinkedHashMap<>();
        for (StructureConnection connection : target) {
            if (validConnection(connection)) merged.put(PlannedPathKey.of(connection), connection);
        }
        for (StructureConnection connection : incoming) {
            if (!validConnection(connection)) continue;
            PlannedPathKey key = PlannedPathKey.of(connection);
            StructureConnection previous = merged.get(key);
            if (previous == null || statusPriority(connection.status()) >= statusPriority(previous.status())) {
                merged.put(key, connection);
            }
        }
        target.clear();
        target.addAll(merged.values());
    }

    private static List<StructureConnection> normalizeConnections(List<StructureConnection> connections) {
        if (connections == null || connections.isEmpty()) return new ArrayList<>();
        LinkedHashMap<PlannedPathKey, StructureConnection> normalized = new LinkedHashMap<>();
        for (StructureConnection connection : connections) {
            if (!validConnection(connection)) continue;
            ConnectionStatus status = connection.status() == null ? ConnectionStatus.PLANNED : connection.status();
            StructureConnection safe = new StructureConnection(connection.from(), connection.to(), status);
            PlannedPathKey key = PlannedPathKey.of(safe);
            StructureConnection previous = normalized.get(key);
            if (previous == null || statusPriority(status) >= statusPriority(previous.status())) {
                normalized.put(key, safe);
            }
        }
        return new ArrayList<>(normalized.values());
    }

    private static boolean validConnection(StructureConnection connection) {
        return connection != null && connection.from() != null && connection.to() != null;
    }

    private static boolean isSuppressed(StateData state, BlockPos from, BlockPos to) {
        return state != null && state.suppressedConnections != null
                && state.suppressedConnections.contains(connectionKey(from, to));
    }

    private static boolean sameEdge2d(StructureConnection connection, BlockPos from, BlockPos to) {
        if (!validConnection(connection) || from == null || to == null) return false;
        return (samePos(connection.from(), from.getX(), from.getZ())
                && samePos(connection.to(), to.getX(), to.getZ()))
                || (samePos(connection.from(), to.getX(), to.getZ())
                && samePos(connection.to(), from.getX(), from.getZ()));
    }

    private static String connectionKey(BlockPos from, BlockPos to) {
        String first = from.getX() + "," + from.getZ();
        String second = to.getX() + "," + to.getZ();
        return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
    }

    private static String fallbackAttemptKey(StructureConnection original, StructureConnection candidate) {
        return connectionKey(original.from(), original.to()) + "=>"
                + connectionKey(candidate.from(), candidate.to());
    }

    private static int statusPriority(ConnectionStatus status) {
        if (status == null) return 0;
        return switch (status) {
            case COMPLETED -> 4;
            case PROTECTED_GAP -> 3;
            case GENERATING -> 3;
            case PLANNED -> 2;
            case FAILED -> 1;
            // 规划前过滤的连接不再有任何待办工作，优先级等同失败（最低）。
            case FILTERED -> 0;
        };
    }

    private static void save(ServerLevel level, StateData state) {
        if (!RoadLifecycle.allowsWrites()) return;
        // ★ 2026-09-26 修 WorkBuddy R-4：只读降级期间**拒绝一切写入**。
        //   否则会把"读失败得到的空表"写回 state.json，
        //   覆盖磁盘上可能完好的真实数据（结构 + 连接表）⇒ 不可恢复。
        //   抛异常而非静默 return：让调用方知道失败，而不是"看起来保存成功了"。
        if (degradedReadOnly) {
            throw new IllegalStateException(
                    "structure state is in read-only degraded mode "
                            + "(read failed; refusing to overwrite the real state file): "
                            + statePath(level));
        }
        try {
            Path path = statePath(level);
            FileStorageIO.writeStringAtomic(path, GSON.toJson(state));
            bumpSnapshotVersion(level);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save structure state", e);
        }
    }

    /**
     * 每个维度的只读快照版本号。所有落盘路径都经
     * {@link #save(ServerLevel, StateData)} 汇聚，落盘成功后 +1，
     * 使下一次 {@link #getStructureSnapshot(ServerLevel)} 因版本不匹配而
     * 重新读盘并重建不可变快照。
     */
    private static void bumpSnapshotVersion(ServerLevel level) {
        if (level == null) return;
        SNAPSHOT_CACHE.bump(level.dimension());
    }

    /**
     * 清理全部只读快照缓存与版本计数。由 {@code CacheManager} 在服务端
     * 停止/启动时调用，避免跨世界残留 stale 快照（版本号可能因新世界重复
     * 使用相同维度 key 而偶然命中）。
     */
    public static synchronized void resetSnapshotCache() {
        SNAPSHOT_CACHE.reset();
        STATE_CACHE.clear();
        // ★ 2026-09-26：只读降级标志也必须复位。
        //   它是"本次会话读失败"的临时状态，跨会话必须清零 ——
        //   否则一次瞬时 IO 抖动会让该会话之后**再也无法保存**结构表。
        //   resetSnapshotCache 由 CacheManager 在服务端启停时调用，
        //   正是"新会话开始"的时刻。
        degradedReadOnly = false;
    }

    private static Path statePath(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, CATEGORY).resolve(STATE_FILE);
    }
}
