/* 文件职责：地图局部更新包。 */
package net.shiroha233.roadweaver.client.map.data;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.client.map.MapLoadPhase;
import net.shiroha233.roadweaver.client.map.MapViewportController;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;

import java.util.List;
import java.util.Map;

/**
 * 地图局部更新包。
 */
public record MapSnapshotPatch(
        List<StructureInfo> structures,
        Map<BlockPos, Integer> structureSources,
        List<StructureConnection> connections,
        List<RoadPolylinePatch> roads,
        List<LoadedRect> loadedRects,
        List<StructureConnection> removedConnections,
        List<Long> removedRoadKeys
) {
    public MapSnapshotPatch {
        structures = structures != null ? List.copyOf(structures) : List.of();
        structureSources = structureSources != null ? Map.copyOf(structureSources) : Map.of();
        connections = connections != null ? List.copyOf(connections) : List.of();
        roads = roads != null ? List.copyOf(roads) : List.of();
        loadedRects = loadedRects != null ? List.copyOf(loadedRects) : List.of();
        removedConnections = removedConnections != null ? List.copyOf(removedConnections) : List.of();
        removedRoadKeys = removedRoadKeys != null ? List.copyOf(removedRoadKeys) : List.of();
    }

    public MapSnapshotPatch(List<StructureInfo> structures,
                            Map<BlockPos, Integer> structureSources,
                            List<StructureConnection> connections,
                            List<RoadPolylinePatch> roads,
                            List<LoadedRect> loadedRects) {
        this(structures, structureSources, connections, roads, loadedRects, List.of(), List.of());
    }

    public MapSnapshotPatch(List<StructureInfo> structures,
                            Map<BlockPos, Integer> structureSources,
                            List<StructureConnection> connections,
                            List<RoadPolylinePatch> roads,
                            List<LoadedRect> loadedRects,
                            List<StructureConnection> removedConnections) {
        this(structures, structureSources, connections, roads, loadedRects, removedConnections, List.of());
    }

    public static MapSnapshotPatch empty() {
        return new MapSnapshotPatch(List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return structures.isEmpty() && connections.isEmpty() && roads.isEmpty()
                && loadedRects.isEmpty() && removedConnections.isEmpty() && removedRoadKeys.isEmpty();
    }

    public record RoadPolylinePatch(long key, List<BlockPos> points) {
        public RoadPolylinePatch {
            points = points != null ? List.copyOf(points) : List.of();
        }
    }

    public record LoadedRect(MapLoadPhase phase, MapViewportController.RequestRect rect) {}
}
