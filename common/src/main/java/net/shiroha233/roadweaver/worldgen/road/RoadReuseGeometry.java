/* 文件职责：仅在一次物理归属计算中惰性复用不可变站点的方向和占地。 */
package net.shiroha233.roadweaver.worldgen.road;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

/** 候选站会被多条道路访问；不应为同一站反复开方和构造装箱集合。 */
final class RoadReuseGeometry {
    private final List<RoadSegmentPlacement> stations;
    private double[][] directions;
    private boolean[] directionReady;
    private LongOpenHashSet[] footprints;

    RoadReuseGeometry(List<RoadSegmentPlacement> stations) {
        this.stations = stations;
    }

    boolean directionCompatible(int index, RoadReuseGeometry other, int otherIndex) {
        double[] a = direction(index);
        double[] b = other.direction(otherIndex);
        // 保留原先无方向则兼容、双向绝对点积及包含0.75边界的判据。
        return a == null || b == null || Math.abs(a[0] * b[0] + a[1] * b[1]) >= 0.75D;
    }

    private double[] direction(int index) {
        // 小批次可能只查空桶；第一次真正比较方向时才分配，避免无匹配也付整路数组成本。
        if (directions == null) {
            directions = new double[stations.size()][];
            directionReady = new boolean[stations.size()];
        }
        if (!directionReady[index]) {
            directions[index] = localDirection(stations, index);
            directionReady[index] = true;
        }
        return directions[index];
    }

    boolean intersects(int index, RoadSegmentPlacement candidate) {
        if (footprints == null) footprints = new LongOpenHashSet[stations.size()];
        LongOpenHashSet footprint = footprints[index];
        if (footprint == null) footprints[index] = footprint = footprint(stations.get(index));
        return intersects(footprint, candidate);
    }

    /** 单次调用者共用同一精确实现，避免施工标记和归属匹配出现两套占地口径。 */
    static boolean intersects(RoadSegmentPlacement first, RoadSegmentPlacement second) {
        return intersects(footprint(first), second);
    }

    private static boolean intersects(LongOpenHashSet footprint, RoadSegmentPlacement candidate) {
        if (candidate.middlePos() != null && footprint.contains(key(candidate.middlePos()))) return true;
        if (candidate.positions() != null) {
            for (BlockPos pos : candidate.positions())
                if (pos != null && footprint.contains(key(pos))) return true;
        }
        return false;
    }

    private static LongOpenHashSet footprint(RoadSegmentPlacement station) {
        var footprint = new LongOpenHashSet();
        if (station.middlePos() != null) footprint.add(key(station.middlePos()));
        if (station.positions() != null)
            for (BlockPos pos : station.positions()) if (pos != null) footprint.add(key(pos));
        return footprint;
    }

    /** 算术和舍入保持原式，包括坐标先作int差再转double；本轮只复用计算结果。 */
    static double[] localDirection(List<RoadSegmentPlacement> segments, int index) {
        if (segments == null || segments.size() < 2) return null;
        BlockPos first = segments.get(Math.max(0, index - 1)).middlePos();
        BlockPos last = segments.get(Math.min(segments.size() - 1, index + 1)).middlePos();
        if (first == null || last == null) return null;
        double dx = last.getX() - first.getX();
        double dz = last.getZ() - first.getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[]{dx / length, dz / length};
    }

    private static long key(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
