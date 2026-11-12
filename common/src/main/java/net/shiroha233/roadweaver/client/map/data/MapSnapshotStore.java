package net.shiroha233.roadweaver.client.map.data;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.client.map.MapLoadPhase;
import net.shiroha233.roadweaver.client.map.MapViewportController;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.map.search.MapStructureSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端地图数据仓库。
 *
 * 负责把多次视口请求返回的数据沉淀为一个可复用快照，并记录每个加载阶段已经覆盖的区域。
 */
public final class MapSnapshotStore {
    private final LinkedHashMap<Long, BlockPos> structuresByPos = new LinkedHashMap<>();
    private final LinkedHashMap<Long, String> structureNamesByPos = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Integer> structureSourcesByPos = new LinkedHashMap<>();
    private final LinkedHashMap<ConnectionKey, StructureConnection> connectionsByKey = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<BlockPos>> roadsByKey = new LinkedHashMap<>();
    private final EnumMap<MapLoadPhase, List<MapViewportController.RequestRect>> loadedRects = new EnumMap<>(MapLoadPhase.class);

    public MapSnapshotStore() {
        for (MapLoadPhase phase : MapLoadPhase.values()) {
            loadedRects.put(phase, new ArrayList<>());
        }
    }

    public static MapSnapshotStore fromSnapshot(MapSnapshot snapshot) {
        MapSnapshotStore store = new MapSnapshotStore();
        store.mergeData(snapshot);
        return store;
    }

    public synchronized void merge(MapLoadPhase phase,
                                   MapViewportController.RequestRect loadedRect,
                                   MapSnapshot snapshot) {
        // A phase response is authoritative for its requested rectangle. Patches
        // can be missed while no player has the map open, so discard cached
        // phase data in the rectangle before applying the server snapshot.
        clearPhaseData(phase, loadedRect);
        mergeData(snapshot);
        markLoaded(phase, loadedRect);
    }

    public synchronized void apply(MapSnapshotPatch patch) {
        if (patch == null) return;
        mergeStructureInfos(patch.structures(), patch.structureSources());
        removeConnections(patch.removedConnections());
        removeRoadPatches(patch.removedRoadKeys());
        replaceConnections(patch.connections());
        mergeRoadPatches(patch.roads());
        for (MapSnapshotPatch.LoadedRect loadedRect : patch.loadedRects()) {
            if (loadedRect != null) {
                markLoaded(loadedRect.phase(), loadedRect.rect());
            }
        }
    }

    public synchronized void mergeData(MapSnapshot snapshot) {
        if (snapshot == null) return;
        mergeStructures(snapshot);
        mergeConnections(snapshot.connections());
        mergeRoadPatches(snapshot.roadPatches());
    }

    public synchronized MapSnapshot snapshot() {
        ArrayList<BlockPos> structures = new ArrayList<>(structuresByPos.values());
        ArrayList<StructureConnection> connections = new ArrayList<>(connectionsByKey.values());
        ArrayList<StructureInfo> infos = new ArrayList<>(structureNamesByPos.size());
        LinkedHashMap<BlockPos, Integer> sources = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : structureNamesByPos.entrySet()) {
            BlockPos pos = structuresByPos.get(entry.getKey());
            if (pos != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                infos.add(new StructureInfo(pos, entry.getValue()));
            }
        }
        for (Map.Entry<Long, BlockPos> entry : structuresByPos.entrySet()) {
            Integer source = structureSourcesByPos.get(entry.getKey());
            if (source != null) sources.put(entry.getValue(), source);
        }
        ArrayList<MapSnapshotPatch.RoadPolylinePatch> roads = new ArrayList<>(roadsByKey.size());
        roadsByKey.forEach((key, points) -> roads.add(new MapSnapshotPatch.RoadPolylinePatch(key, points)));
        return MapSnapshot.withRoadPatches(structures, connections, infos, roads, sources);
    }

    public synchronized List<MapViewportController.RequestRect> loadedRects(MapLoadPhase phase) {
        List<MapViewportController.RequestRect> rects = loadedRects.get(phase);
        if (rects == null || rects.isEmpty()) return List.of();
        return new ArrayList<>(rects);
    }

    public synchronized void invalidate(MapLoadPhase phase, MapViewportController.RequestRect dirtyRect) {
        if (phase == null || dirtyRect == null) return;
        List<MapViewportController.RequestRect> rects = loadedRects.get(phase);
        if (rects == null || rects.isEmpty()) return;
        ArrayList<MapViewportController.RequestRect> next = new ArrayList<>();
        for (MapViewportController.RequestRect rect : rects) {
            next.addAll(MapViewportController.subtract(rect, dirtyRect));
        }
        loadedRects.put(phase, next);
    }

    public synchronized void clearRect(MapLoadPhase phase, MapViewportController.RequestRect rect) {
        if (phase == null || rect == null) return;
        invalidate(phase, rect);
        clearPhaseData(phase, rect);
    }

    private void clearPhaseData(MapLoadPhase phase, MapViewportController.RequestRect rect) {
        if (phase == null || rect == null) return;
        switch (phase) {
            case STRUCTURES -> {
                var iterator = structuresByPos.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, BlockPos> entry = iterator.next();
                    if (inside(entry.getValue(), rect)) {
                        iterator.remove();
                        structureNamesByPos.remove(entry.getKey());
                        structureSourcesByPos.remove(entry.getKey());
                    }
                }
            }
            case CONNECTIONS -> connectionsByKey.entrySet().removeIf(entry -> intersects(entry.getValue(), rect));
            case ROADS -> roadsByKey.entrySet().removeIf(entry -> intersects(entry.getValue(), rect));
        }
    }

    public synchronized void invalidateAllLoadedRects() {
        for (MapLoadPhase phase : MapLoadPhase.values()) {
            loadedRects.get(phase).clear();
        }
    }

    private void markLoaded(MapLoadPhase phase, MapViewportController.RequestRect loadedRect) {
        if (phase == null || loadedRect == null) return;
        List<MapViewportController.RequestRect> rects = loadedRects.computeIfAbsent(phase, ignored -> new ArrayList<>());
        rects.add(loadedRect);
    }

    private void mergeStructures(MapSnapshot snapshot) {
        for (BlockPos pos : snapshot.structures()) {
            if (pos == null) continue;
            BlockPos normalized = normalize(pos);
            long key = posKey(normalized);
            structuresByPos.putIfAbsent(key, normalized);
            mergeStructureSource(key, snapshot.structureSource(pos));
            String name = snapshot.structureName(pos);
            mergeStructureName(key, name);
        }
    }

    private void mergeStructureInfos(List<StructureInfo> infos, Map<BlockPos, Integer> sources) {
        if (infos == null || infos.isEmpty()) return;
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null) continue;
            BlockPos normalized = normalize(info.pos());
            long key = posKey(normalized);
            structuresByPos.putIfAbsent(key, normalized);
            int source = sources == null
                    ? MapStructureSource.UNKNOWN.id()
                    : sources.getOrDefault(normalized, MapStructureSource.UNKNOWN.id());
            mergeStructureSource(key, source);
            String name = info.structureId();
            mergeStructureName(key, name);
        }
    }

    private void mergeStructureName(long key, String name) {
        if (name == null || name.isBlank()) return;
        String previous = structureNamesByPos.get(key);
        if (previous == null || previous.isBlank()
                || (!StructureInfo.isKnownId(previous) && StructureInfo.isKnownId(name))) {
            structureNamesByPos.put(key, name);
        }
    }

    private void mergeStructureSource(long key, int source) {
        Integer previous = structureSourcesByPos.get(key);
        if (previous == null || source != MapStructureSource.UNKNOWN.id()) {
            structureSourcesByPos.put(key, source);
        }
    }

    private void mergeConnections(List<StructureConnection> connections) {
        if (connections == null || connections.isEmpty()) return;
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            StructureConnection normalized = normalize(connection);
            // ★ 2026-09-26（任务 A）：端点登记改由开关控制（默认关）。
            //   见 ensureStructurePresent 的说明 —— 连接线本身照常绘制，
            //   这里只是不再凭空造出 `?` 结构图标。
            ensureStructurePresent(normalized.from());
            ensureStructurePresent(normalized.to());
            ConnectionKey key = ConnectionKey.of(normalized);
            StructureConnection previous = connectionsByKey.get(key);
            if (previous == null || statusPriority(normalized.status()) >= statusPriority(previous.status())) {
                connectionsByKey.put(key, normalized);
            }
        }
    }

    private void replaceConnections(List<StructureConnection> connections) {
        if (connections == null || connections.isEmpty()) return;
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            StructureConnection normalized = normalize(connection);
            // ★ 同上（任务 A）
            ensureStructurePresent(normalized.from());
            ensureStructurePresent(normalized.to());
            connectionsByKey.put(ConnectionKey.of(normalized), normalized);
        }
    }

    private void removeConnections(List<StructureConnection> connections) {
        if (connections == null || connections.isEmpty()) return;
        for (StructureConnection connection : connections) {
            if (connection == null || connection.from() == null || connection.to() == null) continue;
            connectionsByKey.remove(ConnectionKey.of(normalize(connection)));
            roadsByKey.remove(PlanningUtils.edgeKey(connection.from(), connection.to()));
        }
    }

    private void removeRoadPatches(List<Long> keys) {
        if (keys == null || keys.isEmpty()) return;
        for (Long key : keys) {
            if (key != null) roadsByKey.remove(key);
        }
    }

    /**
     * ~~把连接端点当作结构点登记~~ —— **★ 2026-09-26（任务 A）已停用**。
     *
     * <h2>原来的问题</h2>
     * 本方法无条件把端点写进 {@code structuresByPos} 并标成
     * {@link MapStructureSource#UNKNOWN}，然后由 {@code mergeConnections} /
     * {@code replaceConnections} 对**每一条连接**调用。于是：
     * <ul>
     *   <li>自动生成的路连的是真实村庄 ⇒ 端点本来就有真实结构记录，
     *       {@code putIfAbsent} 不覆盖，看不出问题。</li>
     *   <li><b>手绘路线</b>的连接端点**没有**对应结构 ⇒ 被标成 UNKNOWN，
     *       地图上画成 `?`。一条 5 点路线 = 4 条连接 = 最多 8 个端点，
     *       全都在地图上显示成 `?`，看着很乱。</li>
     * </ul>
     *
     * <h2>为什么这是"第三条路径"</h2>
     * 0.8.390 只堵了 {@code MapStructureCollector.addConnectionEndpoint}
     * 那一条；{@code MapPatchService} 本来就过滤了 unknown；
     * 而本方法走的是**快照实时增量**这条路 —— 地图打开后连接状态一变
     * （GENERATING → COMPLETED）就会再次触发，所以它才是**活的主路径**，
     * 表现为"施工完成后 `?` 又出现了"。
     *
     * <h2>现在怎么办</h2>
     * 完全不再由连接推导结构点。真实结构依旧走
     * {@link #mergeStructures} / {@link #mergeStructureInfos}（村庄、出生小屋等），
     * **连接线本身照常绘制**（{@code connectionsByKey} 完全不受影响）——
     * 只是不再凭空造出结构图标。
     *
     * <p>用户 2026-09-26 要求：「所有的端点默认全部隐藏了这样更省事，
     * 玩家开关控制开启关闭」。所以端点标记统一由
     * {@code MapMarkersVisible} 那个开关控制（默认关）。</p>
     */
    @Deprecated
    private void ensureStructurePresent(BlockPos pos) {
        // 保留方法签名以便回溯历史（本仓库的变更历史会引用它），但不再登记结构点。
        if (pos == null) return;
        if (!net.shiroha233.roadweaver.client.map.data.MapMarkersVisible.enabled()) {
            return;
        }
        // ★ 2026-09-26 修 W-7：**开关打开时也要跳手绘端点**，与
        //   MapStructureCollector 的语义保持对称。
        //
        //   原先这里只判开关、不判手绘 ⇒ 开关打开时手绘端点经**增量快照路径**
        //   回归成 `?`（而 Collector 那条路径仍跳手绘）⇒ 两条路径不一致，
        //   表现为「打开开关后手绘 ? 闪烁/时有时无」。
        //
        //   现在两条路径统一为：
        //     开关关  ⇒ 谁都不登记（默认，画面最干净）
        //     开关开  ⇒ 只登记**非手绘**连接的端点（手绘端点永不当结构点）
        if (isPlayerRouteEndpoint(pos)) {
            return;
        }
        // 玩家显式打开开关时才恢复旧行为（便于对照/排查）
        BlockPos normalized = normalize(pos);
        structuresByPos.putIfAbsent(posKey(normalized), normalized);
        structureSourcesByPos.putIfAbsent(posKey(normalized), MapStructureSource.UNKNOWN.id());
    }

    /**
     * 该位置是否是**手绘路线**的连接端点（★ 2026-09-26 修 W-7）。
     *
     * <p>复用 {@code MapStructureCollector} 的判定口径 ——
     * 连接表的 from/to 是否与某条手绘路线的相邻控制点对重合（只比 XZ）。</p>
     */
    private static boolean isPlayerRouteEndpoint(BlockPos pos) {
        try {
            var level = currentLevelOrNull();
            if (level == null) {
                return false;
            }
            for (var conn : net.shiroha233.roadweaver.persistence.files.StructureFileStorage
                    .getStructureConnections(level)) {
                if (conn == null || conn.from() == null || conn.to() == null) continue;
                if (!sameXZ(conn.from(), pos) && !sameXZ(conn.to(), pos)) continue;
                // 端点命中这条连接 —— 再看它是不是手绘的
                if (net.shiroha233.roadweaver.route.RouteBuildRegistry
                        .isPlayerRoute(level, conn)) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 判定失败按"非手绘"处理（保守：宁可多显示一个标记，也不要漏掉真实结构）
        }
        return false;
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a != null && b != null && a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /** 取当前服务端主世界（拿不到返回 null）。 */
    private static net.minecraft.server.level.ServerLevel currentLevelOrNull() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            return server == null ? null : server.overworld();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private void mergeRoads(List<List<BlockPos>> roads) {
        if (roads == null || roads.isEmpty()) return;
        for (List<BlockPos> road : roads) {
            if (road == null || road.size() < 2) continue;
            ArrayList<BlockPos> normalized = new ArrayList<>(road.size());
            for (BlockPos pos : road) {
                if (pos != null) normalized.add(normalize(pos));
            }
            if (normalized.size() < 2) continue;
            roadsByKey.put(roadKey(normalized), List.copyOf(normalized));
        }
    }

    private void mergeRoadPatches(List<MapSnapshotPatch.RoadPolylinePatch> roads) {
        if (roads == null || roads.isEmpty()) return;
        for (MapSnapshotPatch.RoadPolylinePatch road : roads) {
            if (road == null || road.points().size() < 2) continue;
            ArrayList<BlockPos> normalized = new ArrayList<>(road.points().size());
            for (BlockPos pos : road.points()) {
                if (pos != null) normalized.add(normalize(pos));
            }
            if (normalized.size() < 2) continue;
            roadsByKey.put(road.key(), List.copyOf(normalized));
        }
    }

    private static BlockPos normalize(BlockPos pos) {
        return new BlockPos(pos.getX(), 0, pos.getZ());
    }

    private static StructureConnection normalize(StructureConnection connection) {
        return new StructureConnection(normalize(connection.from()), normalize(connection.to()), connection.status());
    }

    private static long posKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static long roadKey(List<BlockPos> road) {
        long hash = 0xcbf29ce484222325L;
        for (BlockPos pos : road) {
            hash ^= pos.getX();
            hash *= 0x100000001b3L;
            hash ^= pos.getZ();
            hash *= 0x100000001b3L;
        }
        hash ^= road.size();
        hash *= 0x100000001b3L;
        return hash;
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

    private static boolean inside(BlockPos pos, MapViewportController.RequestRect rect) {
        return pos != null
                && pos.getX() >= rect.minX() && pos.getX() <= rect.maxX()
                && pos.getZ() >= rect.minZ() && pos.getZ() <= rect.maxZ();
    }

    private static boolean intersects(StructureConnection connection, MapViewportController.RequestRect rect) {
        if (connection == null || connection.from() == null || connection.to() == null) return false;
        int minX = Math.min(connection.from().getX(), connection.to().getX());
        int maxX = Math.max(connection.from().getX(), connection.to().getX());
        int minZ = Math.min(connection.from().getZ(), connection.to().getZ());
        int maxZ = Math.max(connection.from().getZ(), connection.to().getZ());
        return maxX >= rect.minX() && minX <= rect.maxX()
                && maxZ >= rect.minZ() && minZ <= rect.maxZ();
    }

    private static boolean intersects(List<BlockPos> road, MapViewportController.RequestRect rect) {
        if (road == null || road.isEmpty()) return false;
        for (BlockPos pos : road) {
            if (inside(pos, rect)) return true;
        }
        for (int i = 1; i < road.size(); i++) {
            BlockPos previous = road.get(i - 1);
            BlockPos current = road.get(i);
            if (previous == null || current == null) continue;
            int minX = Math.min(previous.getX(), current.getX());
            int maxX = Math.max(previous.getX(), current.getX());
            int minZ = Math.min(previous.getZ(), current.getZ());
            int maxZ = Math.max(previous.getZ(), current.getZ());
            if (maxX >= rect.minX() && minX <= rect.maxX()
                    && maxZ >= rect.minZ() && minZ <= rect.maxZ()) return true;
        }
        return false;
    }

    private record ConnectionKey(long a, long b) {
        private static ConnectionKey of(StructureConnection connection) {
            long from = posKey(connection.from());
            long to = posKey(connection.to());
            return from <= to ? new ConnectionKey(from, to) : new ConnectionKey(to, from);
        }
    }
}
