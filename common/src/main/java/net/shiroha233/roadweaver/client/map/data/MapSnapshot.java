package net.shiroha233.roadweaver.client.map.data;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.map.search.MapStructureSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 地图快照数据 - 不可变数据结构
 */
public class MapSnapshot {
    private final List<BlockPos> structures;
    private final List<StructureConnection> connections;
    private final Map<BlockPos, String> structureNames;
    private final Map<BlockPos, Integer> structureSources;
    private final List<MapSnapshotPatch.RoadPolylinePatch> roadPatches;
    private final List<List<BlockPos>> roadPolylines;

    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    public MapSnapshot(List<BlockPos> structures,
                       List<StructureConnection> connections,
                       List<StructureInfo> structureInfos,
                       List<List<BlockPos>> roadPolylines) {
        this(structures, connections, structureInfos, roadPolylines, Map.of());
    }

    public MapSnapshot(List<BlockPos> structures,
                       List<StructureConnection> connections,
                       List<StructureInfo> structureInfos,
                       List<List<BlockPos>> roadPolylines,
                       Map<BlockPos, Integer> structureSources) {
        this(structures, connections, structureInfos, toRoadPatches(roadPolylines), structureSources, true);
    }

    private MapSnapshot(List<BlockPos> structures,
                        List<StructureConnection> connections,
                        List<StructureInfo> structureInfos,
                        List<MapSnapshotPatch.RoadPolylinePatch> roadPatches,
                        Map<BlockPos, Integer> structureSources,
                        boolean keyedRoads) {
        this.structures = Collections.unmodifiableList(new ArrayList<>(structures != null ? structures : List.of()));
        this.connections = Collections.unmodifiableList(new ArrayList<>(connections != null ? connections : List.of()));
        Map<BlockPos, String> nm = new HashMap<>();
        if (structureInfos != null) {
            for (StructureInfo info : structureInfos) {
                if (info != null && info.pos() != null) {
                    nm.put(normalize(info.pos()), info.structureId());
                }
            }
        }
        this.structureNames = Collections.unmodifiableMap(nm);
        Map<BlockPos, Integer> src = new HashMap<>();
        if (structureSources != null) {
            for (Map.Entry<BlockPos, Integer> entry : structureSources.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    src.put(normalize(entry.getKey()), entry.getValue());
                }
            }
        }
        this.structureSources = Collections.unmodifiableMap(src);
        List<MapSnapshotPatch.RoadPolylinePatch> keyed = new ArrayList<>();
        List<List<BlockPos>> rp = new ArrayList<>();
        if (roadPatches != null) {
            for (MapSnapshotPatch.RoadPolylinePatch patch : roadPatches) {
                if (patch == null || patch.points() == null || patch.points().size() < 2) continue;
                List<BlockPos> points = Collections.unmodifiableList(new ArrayList<>(patch.points()));
                keyed.add(new MapSnapshotPatch.RoadPolylinePatch(patch.key(), points));
                rp.add(points);
            }
        }
        this.roadPatches = Collections.unmodifiableList(keyed);
        this.roadPolylines = Collections.unmodifiableList(rp);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : this.structures) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        for (StructureConnection c : this.connections) {
            BlockPos a = c.from();
            BlockPos b = c.to();
            if (a.getX() < minX) minX = a.getX();
            if (a.getZ() < minZ) minZ = a.getZ();
            if (a.getX() > maxX) maxX = a.getX();
            if (a.getZ() > maxZ) maxZ = a.getZ();
            if (b.getX() < minX) minX = b.getX();
            if (b.getZ() < minZ) minZ = b.getZ();
            if (b.getX() > maxX) maxX = b.getX();
            if (b.getZ() > maxZ) maxZ = b.getZ();
        }
        for (List<BlockPos> pl : this.roadPolylines) {
            for (BlockPos p : pl) {
                if (p.getX() < minX) minX = p.getX();
                if (p.getZ() < minZ) minZ = p.getZ();
                if (p.getX() > maxX) maxX = p.getX();
                if (p.getZ() > maxZ) maxZ = p.getZ();
            }
        }
        if (minX == Integer.MAX_VALUE) {
            minX = minZ = 0;
            maxX = maxZ = 1;
        }
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    public List<BlockPos> structures() { return structures; }
    public List<StructureConnection> connections() { return connections; }
    public String structureName(BlockPos pos) { return structureNames.get(normalize(pos)); }
    public int structureSource(BlockPos pos) {
        return structureSources.getOrDefault(normalize(pos), MapStructureSource.UNKNOWN.id());
    }
    public MapStructureSource structureSourceType(BlockPos pos) {
        return MapStructureSource.fromId(structureSource(pos));
    }
    public List<List<BlockPos>> roadPolylines() { return roadPolylines; }
    public List<MapSnapshotPatch.RoadPolylinePatch> roadPatches() { return roadPatches; }

    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxZ() { return maxZ; }

    public int structuresCount() { return structures.size(); }

    public int plannedCount() {
        return (int) connections.stream().filter(c -> c.status() == ConnectionStatus.PLANNED).count();
    }
    public int generatingCount() {
        return (int) connections.stream().filter(c -> c.status() == ConnectionStatus.GENERATING).count();
    }
    public int completedCount() {
        return (int) connections.stream().filter(c -> c.status() == ConnectionStatus.COMPLETED
                || c.status() == ConnectionStatus.PROTECTED_GAP).count();
    }
    public int failedCount() {
        return (int) connections.stream().filter(c -> c.status() == ConnectionStatus.FAILED).count();
    }

    public static MapSnapshot empty() {
        return new MapSnapshot(List.of(), List.of(), List.of(), List.of());
    }

    public static MapSnapshot withRoadPatches(List<BlockPos> structures,
                                              List<StructureConnection> connections,
                                              List<StructureInfo> structureInfos,
                                              List<MapSnapshotPatch.RoadPolylinePatch> roadPatches,
                                              Map<BlockPos, Integer> structureSources) {
        return new MapSnapshot(structures, connections, structureInfos, roadPatches, structureSources, true);
    }

    private static List<MapSnapshotPatch.RoadPolylinePatch> toRoadPatches(List<List<BlockPos>> roads) {
        if (roads == null || roads.isEmpty()) return List.of();
        ArrayList<MapSnapshotPatch.RoadPolylinePatch> patches = new ArrayList<>(roads.size());
        for (List<BlockPos> road : roads) {
            if (road == null || road.size() < 2) continue;
            patches.add(new MapSnapshotPatch.RoadPolylinePatch(roadKey(road), road));
        }
        return patches;
    }

    private static long roadKey(List<BlockPos> road) {
        long hash = 0xcbf29ce484222325L;
        for (BlockPos pos : road) {
            if (pos == null) continue;
            hash ^= pos.getX();
            hash *= 0x100000001b3L;
            hash ^= pos.getZ();
            hash *= 0x100000001b3L;
        }
        hash ^= road.size();
        return hash * 0x100000001b3L;
    }

    private static BlockPos normalize(BlockPos pos) {
        return pos == null ? BlockPos.ZERO : new BlockPos(pos.getX(), 0, pos.getZ());
    }
}
