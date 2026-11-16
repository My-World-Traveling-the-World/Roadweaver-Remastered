/* 文件职责：为有限长度的路网选边枚举邻近空间桶。 */
package net.shiroha233.roadweaver.topology;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/** 按水平位置预筛；端点的三维限长仍由上层原判据决定。 */
final class TopologyCandidates {
    private TopologyCandidates() {}

    static int[][] nearby(List<RoadNode> nodes, int radius, MinimumSpanningTree.EdgeFilter allowed) {
        if (radius <= 0) return null;
        Map<Bucket, IntArrayList> buckets = new HashMap<>();
        Bucket[] locations = new Bucket[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            RoadNode node = nodes.get(i);
            // 负坐标必须向下取整，桶宽等于半径时有效边最多跨一桶。
            Bucket key = new Bucket(Math.floorDiv(node.x(), radius), Math.floorDiv(node.z(), radius));
            locations[i] = key;
            buckets.computeIfAbsent(key, unused -> new IntArrayList()).add(i);
        }

        // 密集时阵列访存更好，且避免建一份接近全量的邻接表再排序。
        long complete = (long) nodes.size() * (nodes.size() - 1) / 2;
        long lowerBound = 0;
        for (IntArrayList bucket : buckets.values()) {
            long size = bucket.size();
            lowerBound += size * (size - 1) / 2;
        }
        if (lowerBound > complete / 2) return null;
        long nearbyPairsTwice = 0;
        for (var entry : buckets.entrySet()) {
            Bucket key = entry.getKey();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    IntArrayList other = buckets.get(key.offset(dx, dz));
                    if (other != null) nearbyPairsTwice += (long) entry.getValue().size() * other.size();
                }
            }
        }
        if ((nearbyPairsTwice - nodes.size()) / 2 > complete / 2) return null;

        IntArrayList[] links = new IntArrayList[nodes.size()];
        for (int i = 0; i < links.length; i++) links[i] = new IntArrayList();
        for (int from = 0; from < nodes.size(); from++) {
            Bucket key = locations[from];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    IntArrayList other = buckets.get(key.offset(dx, dz));
                    if (other == null) continue;
                    for (int k = 0; k < other.size(); k++) {
                        int to = other.getInt(k);
                        if (to <= from || !allowed.allows(from, to)) continue;
                        links[from].add(to);
                        links[to].add(from);
                    }
                }
            }
        }
        int[][] result = new int[links.length][];
        for (int i = 0; i < links.length; i++) {
            // FMM 和同权 Kruskal 的枚举次序继续按节点序，不能依赖桶遍历顺序。
            links[i].sort(null);
            result[i] = links[i].toIntArray();
        }
        return result;
    }

    // 偏移先用 long，避免整数边缘的两个相隔很远的桶被当成相邻。
    private record Bucket(long x, long z) {
        Bucket offset(int dx, int dz) {
            return new Bucket(x + dx, z + dz);
        }
    }
}
