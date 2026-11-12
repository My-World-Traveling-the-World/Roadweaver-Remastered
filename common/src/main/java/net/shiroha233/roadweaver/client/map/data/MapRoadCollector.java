/* 文件职责：道路折线快照收集。 */
package net.shiroha233.roadweaver.client.map.data;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.map.RoadMapGeometry;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlanCompiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 道路折线快照收集。
 */
public final class MapRoadCollector {
    private MapRoadCollector() {}

    public static List<List<BlockPos>> collect(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        return collectPatches(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ).stream()
                .map(MapSnapshotPatch.RoadPolylinePatch::points)
                .toList();
    }

    public static List<MapSnapshotPatch.RoadPolylinePatch> collectPatches(
            ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return List.of();
        List<RoadData> roadDataList = RoadShardStorage.queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        if (roadDataList == null || roadDataList.isEmpty()) return List.of();
        roadDataList = new ArrayList<>(RoadChunkPlanCompiler.normalizeForPhysicalOwnership(roadDataList));
        roadDataList.sort(Comparator.comparingLong(MapRoadCollector::stableRoadKey));
        ArrayList<MapSnapshotPatch.RoadPolylinePatch> roads = new ArrayList<>();
        Set<EdgeKey> emittedEdges = new HashSet<>();
        for (RoadData rd : roadDataList) {
            List<RoadSegmentPlacement> segs = rd.roadSegmentList();
            if (segs == null || segs.isEmpty()) continue;
            long key = stableRoadKey(rd);
            int part = 0;
            for (List<BlockPos> visible : RoadMapGeometry.visiblePolylines(rd)) {
                ArrayList<BlockPos> run = new ArrayList<>();
                for (BlockPos point : visible) {
                    if (run.isEmpty()) {
                        run.add(point);
                        continue;
                    }
                    EdgeKey edge = EdgeKey.of(run.get(run.size() - 1), point);
                    if (!emittedEdges.add(edge)) {
                        part = appendVisibleRun(roads, run, key, part);
                        run.clear();
                        run.add(point);
                    } else {
                        run.add(point);
                    }
                }
                part = appendVisibleRun(roads, run, key, part);
            }
        }
        return roads;
    }

    private static int appendVisibleRun(List<MapSnapshotPatch.RoadPolylinePatch> output,
                                        List<BlockPos> run, long key, int part) {
        if (run != null && run.size() >= 2) {
            long patchKey = part == 0 ? key : mixKey(key, part);
            output.add(new MapSnapshotPatch.RoadPolylinePatch(patchKey, List.copyOf(run)));
            return part + 1;
        }
        return part;
    }

    private static long stableRoadKey(RoadData road) {
        return road.hasOwnerPair()
                ? PlanningUtils.edgeKey(road.ownerA2dKey(), road.ownerB2dKey())
                : RoadShardStorage.computeFingerprint(road);
    }

    private static long mixKey(long key, int part) {
        long mixed = key ^ (0x9E3779B97F4A7C15L * (part + 1L));
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        return mixed ^ (mixed >>> 31);
    }

    private record EdgeKey(int ax, int az, int bx, int bz) {
        private static EdgeKey of(BlockPos first, BlockPos second) {
            int ax = first.getX();
            int az = first.getZ();
            int bx = second.getX();
            int bz = second.getZ();
            if (ax < bx || (ax == bx && az <= bz)) return new EdgeKey(ax, az, bx, bz);
            return new EdgeKey(bx, bz, ax, az);
        }
    }
}
