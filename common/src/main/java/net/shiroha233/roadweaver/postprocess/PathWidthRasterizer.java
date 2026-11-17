/* 文件职责：将水平折线按给定路宽落列，保持每站归属、并列提示和空站锚点。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.postprocess.SplineHelper.Vec2d;
import java.util.*;

import static net.shiroha233.roadweaver.postprocess.SplineHelper.distToSegmentSq;

// 公共入口和曲线入口共用这一套落列；不在拆分时改变半宽、闭区间或列遍历顺序。
final class PathWidthRasterizer {
    private PathWidthRasterizer() { }

    public static List<RoadSegmentPlacement> rasterizeStations(List<BlockPos> stations, int width) {
        if (stations == null || stations.size() < 2) return new ArrayList<>();
        List<Vec2d> points = new ArrayList<>(stations.size());
        List<Double> centerDists = new ArrayList<>(stations.size());
        double distance = 0.0D;
        for (int index = 0; index < stations.size(); index++) {
            BlockPos station = stations.get(index);
            points.add(new Vec2d(station.getX(), station.getZ()));
            if (index > 0) distance += points.get(index).dist(points.get(index - 1));
            centerDists.add(distance);
        }
        return rasterizeSegments(points, stations, centerDists, width);
    }

    static List<RoadSegmentPlacement> rasterizeSegments(List<Vec2d> splinePoints,
                                                                 List<BlockPos> centers,
                                                                 List<Double> centerDists,
                                                                 int width) {
        // 站号连续且按顺序访问，用列表避免每个落列查询的整数装箱和散列表节点。
        List<Set<BlockPos>> segmentedBlocks = new ArrayList<>(centers.size());
        for (int i = 0; i < centers.size(); i++) segmentedBlocks.add(new HashSet<>());

        double halfWidth = width / 2.0;
        double halfWidthSq = halfWidth * halfWidth;
        double pathDist = 0;
        int currentCenterIdx = 0;

        for (int i = 0; i < splinePoints.size() - 1; i++) {
            Vec2d pStart = splinePoints.get(i), pEnd = splinePoints.get(i + 1);
            pathDist += pStart.dist(pEnd);
            while (currentCenterIdx < centerDists.size() - 1 && centerDists.get(currentCenterIdx + 1) < pathDist) {
                currentCenterIdx++;
            }

            int minX = (int) Math.floor(Math.min(pStart.x(), pEnd.x()) - halfWidth - 1);
            int maxX = (int) Math.ceil(Math.max(pStart.x(), pEnd.x()) + halfWidth + 1);
            int minZ = (int) Math.floor(Math.min(pStart.z(), pEnd.z()) - halfWidth - 1);
            int maxZ = (int) Math.ceil(Math.max(pStart.z(), pEnd.z()) + halfWidth + 1);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (distToSegmentSq(x, z, pStart, pEnd) <= halfWidthSq) {
                        int station = nearestCenter(x, z, centers, currentCenterIdx);
                        segmentedBlocks.get(station).add(new BlockPos(x, 0, z));
                    }
                }
            }
        }

        List<RoadSegmentPlacement> out = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            Set<BlockPos> blocks = segmentedBlocks.get(i);
            // 重合站也要保持一站一段；无列归属时保留中心锚点，避免重建段数改变。
            if (blocks.isEmpty()) blocks.add(new BlockPos(center.getX(), 0, center.getZ()));
            out.add(new RoadSegmentPlacement(center, new ArrayList<>(blocks)));
        }
        return out;
    }

    static void assignToNearestCenter(int x, int z, List<BlockPos> centers,
                                               int hint, Map<Integer, Set<BlockPos>> segmentedBlocks) {
        // 保留原包内入口，外部夹具和调用方继续使用相同的归属算法。
        segmentedBlocks.get(nearestCenter(x, z, centers, hint)).add(new BlockPos(x, 0, z));
    }

    private static int nearestCenter(int x, int z, List<BlockPos> centers, int hint) {
        int bestIdx = hint;
        double bestDistSq = Double.MAX_VALUE;
        int searchStart = Math.max(0, hint - 2);
        int searchEnd = Math.min(centers.size() - 2, hint + 2);

        for (int seg = searchStart; seg <= searchEnd; seg++) {
            BlockPos a = centers.get(seg), b = centers.get(seg + 1);
            double ax = a.getX(), az = a.getZ(), dx = b.getX() - ax, dz = b.getZ() - az;
            double lenSq = dx * dx + dz * dz;
            double t = (lenSq < 1e-9) ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / lenSq));
            double projX = ax + t * dx, projZ = az + t * dz;
            double dSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
            int candidateIndex = (t < 0.5) ? seg : Math.min(seg + 1, centers.size() - 1);
            int currentDistance = Math.abs(bestIdx - hint);
            int candidateDistance = Math.abs(candidateIndex - hint);
            // 等距交叉优先当前站序附近的归属，不能按全局索引抢走另一遍经过的路面。
            if (dSq < bestDistSq - 1.0E-9D
                    || (Math.abs(dSq - bestDistSq) <= 1.0E-9D && candidateDistance < currentDistance)) {
                bestDistSq = dSq;
                bestIdx = candidateIndex;
            }
        }
        return bestIdx;
    }

}
