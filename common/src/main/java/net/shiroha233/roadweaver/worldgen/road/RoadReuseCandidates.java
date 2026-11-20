/* 文件职责：单次物理归一化内按中心站空间桶筛道路对，保留原道路序。 */
package net.shiroha233.roadweaver.worldgen.road;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.shiroha233.roadweaver.core.model.RoadData;

/** 精配只考虑64格内中心；宽阶段多留候选，但不能按路面宽度误删宽桥/隧道。 */
final class RoadReuseCandidates {
    private final int distance;
    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private final Map<RoadData, int[]> prepared = new IdentityHashMap<>();
    private final int[] direct;

    RoadReuseCandidates(List<RoadData> roads, int distance) {
        this.distance = distance;
        // 小批次准备空间索引的成本超过全对；策略只选枚举方式，不改变精配判据。
        if (roads.size() < 16) {
            direct = java.util.stream.IntStream.range(0, roads.size()).toArray();
            return;
        }
        boolean dense = false;
        for (int i = 0; i < roads.size(); i++) {
            RoadData road = roads.get(i);
            if (road == null) continue;
            var visited = new LongOpenHashSet();
            for (var station : road.roadSegmentList()) {
                var pos = station.middlePos();
                if (pos == null) continue;
                long key = key(pos.getX() >> 6, pos.getZ() >> 6);
                if (visited.add(key)) {
                    IntArrayList values = cells.computeIfAbsent(key, ignored -> new IntArrayList());
                    values.add(i);
                    dense |= values.size() * 2L >= roads.size();
                }
            }
        }
        // 同桶已有半数道路时输出本身趋于全对，避免反复建候选集合及排序；不是失败回退。
        direct = dense ? java.util.stream.IntStream.range(0, roads.size()).toArray() : null;
        if (dense) cells.clear();
    }

    int[] nearby(RoadData road) {
        if (direct != null) return direct;
        return prepared.computeIfAbsent(road, this::find);
    }

    boolean usesDirectEnumeration() { return direct != null; }

    private int[] find(RoadData road) {
        var found = new IntOpenHashSet();
        var visited = new LongOpenHashSet();
        for (var station : road.roadSegmentList()) {
            var pos = station.middlePos();
            if (pos == null) continue;
            int minX = (int) (((long) pos.getX() - distance) >> 6);
            int maxX = (int) (((long) pos.getX() + distance) >> 6);
            int minZ = (int) (((long) pos.getZ() - distance) >> 6);
            int maxZ = (int) (((long) pos.getZ() + distance) >> 6);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                long key = key(x, z);
                if (!visited.add(key)) continue;
                IntArrayList candidates = cells.get(key);
                if (candidates != null) found.addAll(candidates);
            }
        }
        int[] result = found.toIntArray();
        // 原指纹排序后的道路号决定选主；散列表顺序不能流入几何结果。
        java.util.Arrays.sort(result);
        return result;
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
