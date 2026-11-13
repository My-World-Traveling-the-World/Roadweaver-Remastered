/* 文件职责：连通块补边 —— 把互不相连的路网碎片用尽可能短的边连起来。
 *
 * 从 KNNPlanner.connectComponents 抽出（2026-11-13）：
 * 旧 KNN 规划器已随「全用最新算法」退役，但「连通块补边」是生产链的独立功能，
 * 与「用 KNN 做拓扑」无关 —— 它只负责「这些点之间还没连通，补几条边」。
 */
package net.shiroha233.roadweaver.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;

/**
 * 连通块补边：把互不相连的路网碎片用尽可能短的边连起来。
 *
 * <h2>它与拓扑规划算法的关系</h2>
 * <p>拓扑规划（KNN/Delaunay/RNG/MST/SnapBranch/AlphaModel/PIPELINE）决定
 * 「谁连谁」；本类负责「这些点之间还没连通，补几条边」。
 * 两者是不同阶段：前者是主拓扑，后者是修补。</p>
 *
 * <h2>为什么从 KNNPlanner 抽出来</h2>
 * <p>2026-11-13：旧 KNN 规划器随「全用最新算法」退役，
 * 但本方法被 {@code AsyncPlanningOps} 与 {@code RoadPlanningService} 调用，
 * 是生产链的独立功能，与「用 KNN 做拓扑」无关。</p>
 */
public final class ComponentConnector {

    private ComponentConnector() {}

    /**
     * 连通块补边。
     *
     * @param points          所有结构点
     * @param base            已有的边（这些边已经把一些点连起来了）
     * @param maxJoinLenBlocks 补边的最大长度（格）
     * @param minAngleDeg     补边与已有边的最小夹角（度）
     * @param degreeCap       每个点的最大出度
     * @return 新增的边（不含已有的）
     */
    public static List<StructureConnection> connectComponents(List<BlockPos> points,
                                                               List<StructureConnection> base,
                                                               int maxJoinLenBlocks,
                                                               double minAngleDeg,
                                                               int degreeCap) {
        int n = points != null ? points.size() : 0;
        if (n < 2) return List.of();
        long maxD2 = maxJoinLenBlocks > 0 ? (long) maxJoinLenBlocks * (long) maxJoinLenBlocks : Long.MAX_VALUE;
        double minCos = Math.cos(Math.toRadians(Math.max(0.0, Math.min(89.0, minAngleDeg))));

        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        java.util.HashMap<BlockPos, Integer> index = new java.util.HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(points.get(i), i);

        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        };
        java.util.function.BiConsumer<Integer, Integer> unite = (a, b) -> {
            int ra = find.applyAsInt(a);
            int rb = find.applyAsInt(b);
            if (ra != rb) parent[rb] = ra;
        };

        Set<Long> keys = new HashSet<>();
        if (base != null) {
            for (StructureConnection c : base) {
                Integer ia = index.get(c.from());
                Integer ib = index.get(c.to());
                if (ia == null || ib == null) continue;
                int a = Math.min(ia, ib);
                int b = Math.max(ia, ib);
                long key = (((long) a) << 32) ^ (long) b;
                keys.add(key);
                unite.accept(ia, ib);
                adj.get(ia).add(ib);
                adj.get(ib).add(ia);
            }
        }

        class Pair { int a, b; long d2; Pair(int a, int b, long d2){this.a=a;this.b=b;this.d2=d2;} }
        List<Pair> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (find.applyAsInt(i) == find.applyAsInt(j)) continue;
                long d2 = dist2(points.get(i), points.get(j));
                if (d2 <= maxD2) cand.add(new Pair(i, j, d2));
            }
        }
        cand.sort((p, q) -> Long.compare(p.d2, q.d2));

        List<StructureConnection> added = new ArrayList<>();
        for (Pair p : cand) {
            int ra = find.applyAsInt(p.a);
            int rb = find.applyAsInt(p.b);
            if (ra == rb) continue;
            if (adj.get(p.a).size() >= degreeCap || adj.get(p.b).size() >= degreeCap) continue;
            if (!angleOk(points, adj.get(p.a), p.a, p.b, minCos)) continue;
            if (!angleOk(points, adj.get(p.b), p.b, p.a, minCos)) continue;
            int a = Math.min(p.a, p.b);
            int b = Math.max(p.a, p.b);
            long key = (((long) a) << 32) ^ (long) b;
            if (keys.add(key)) {
                added.add(new StructureConnection(points.get(a), points.get(b)));
                adj.get(p.a).add(p.b);
                adj.get(p.b).add(p.a);
                unite.accept(p.a, p.b);
            }
        }
        return added;
    }

    private static long dist2(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean angleOk(List<BlockPos> pts, List<Integer> neighbors, int i, int j, double minCos) {
        if (neighbors.isEmpty()) return true;
        BlockPos a = pts.get(i);
        BlockPos b = pts.get(j);
        long abx = (long) b.getX() - a.getX();
        long abz = (long) b.getZ() - a.getZ();
        double abLen = Math.hypot((double) abx, (double) abz);
        if (abLen == 0) return false;
        double abxN = abx / abLen;
        double abzN = abz / abLen;
        for (int nb : neighbors) {
            BlockPos c = pts.get(nb);
            long acx = (long) c.getX() - a.getX();
            long acz = (long) c.getZ() - a.getZ();
            double acLen = Math.hypot((double) acx, (double) acz);
            if (acLen == 0) continue;
            double acxN = acx / acLen;
            double aczN = acz / acLen;
            double cos = abxN * acxN + abzN * aczN;
            if (cos > minCos) return false;
        }
        return true;
    }
}
