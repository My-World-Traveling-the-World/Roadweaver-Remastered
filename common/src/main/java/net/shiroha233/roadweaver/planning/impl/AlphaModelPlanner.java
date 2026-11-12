package net.shiroha233.roadweaver.planning.impl;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.NetworkPlanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Alpha Model 路网拓扑规划器（Molinero &amp; Hernando 2020, arXiv:2001.08180）。
 *
 * <p><b>核心思想</b>：不是每条路独立选线，而是整个路网一起长出来。每对城市求最短路径时，
 * <b>已有路打折</b>（{@code alpha &lt; 1}）⇒ 新路主动共用旧路 ⇒ 主干 + 分支自然涌现，
 * 不出现两三条路互相拧麻花。</p>
 *
 * <p><b>输出契约（0.9.076 修正）</b>：输出的是 <b>所有路径的边的并集</b>，只保留
 * 「路径上直接相邻的两个城市节点」这一层，虚拟节点只作跳板。这一点是 Alpha Model 与
 * 「给每对城市各画一条直线」的根本区别 —— 后者是完全图，交给下游过滤后必然退化成树。</p>
 *
 * <p><b>性能</b>（0.9.076）：Dijkstra 改优先队列 A*；虚拟节点上限按节点总数自适应；
 * 城市对只取交通量最高的若干条（{@code 3 × 城市数}，下限 32）。旧实现是
 * O((城市+dummy)²) × 全部城市对，4000 dummy 时要跑 4.5 亿次比较。</p>
 */
public final class AlphaModelPlanner implements NetworkPlanner {
    /** 已有路折扣：alpha &lt; 1 表示共用旧路更便宜。0.55 = 打 55 折。 */
    private static final double ALPHA = 0.55D;
    /** 虚拟节点密度：每 200×200 格撒一个。 */
    private static final int DUMMY_SPACING = 200;
    /** 虚拟节点硬上限（图规模 = 城市数 + dummy，直接影响 A* 耗时）。 */
    private static final int MAX_DUMMY = 600;
    /** 每个城市取最近几个城市作为规划目标；决定路的覆盖率（每个城市至少被连这么多次）。 */
    private static final int NEAREST_TRIPS_PER_CITY = 4;
    /** 每点最近邻数；Delaunay 的平均连通度约 6。 */
    private static final int NEIGHBORS = 6;
    /** 交通量加权里距离的归一化尺度（格）。 */
    private static final double TRAFFIC_SCALE = 1000.0D;

    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points == null || points.size() < 2) return List.of();
        List<BlockPos> cities = unique2d(points);
        int cityCount = cities.size();
        if (cityCount < 2) return List.of();
        int maxEdge = maxEdgeLenBlocks > 0 ? maxEdgeLenBlocks : 2048;

        // 1. 撒虚拟节点（Halton 均匀分布），数量随包络面积自适应但设硬上限
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos c : cities) {
            minX = Math.min(minX, c.getX());
            maxX = Math.max(maxX, c.getX());
            minZ = Math.min(minZ, c.getZ());
            maxZ = Math.max(maxZ, c.getZ());
        }
        int pad = Math.max(DUMMY_SPACING, maxEdge / 4);
        long width = (long) (maxX - minX) + 2L * pad;
        long height = (long) (maxZ - minZ) + 2L * pad;
        long area = width * height;
        int dummyTarget = (int) Math.min(MAX_DUMMY,
                Math.max(0, area / ((long) DUMMY_SPACING * DUMMY_SPACING)));
        // 城市本身已够密时不再补 dummy，避免图膨胀
        int nDummy = Math.min(dummyTarget, Math.max(0, MAX_DUMMY - cityCount));

        List<BlockPos> all = new ArrayList<>(cityCount + nDummy);
        all.addAll(cities);
        int startX = minX - pad;
        int startZ = minZ - pad;
        for (int i = 1; i <= nDummy; i++) {
            int x = startX + (int) (halton(i, 2) * width);
            int z = startZ + (int) (halton(i, 3) * height);
            all.add(new BlockPos(x, 0, z));
        }
        int n = all.size();
        int[] ax = new int[n];
        int[] az = new int[n];
        for (int i = 0; i < n; i++) {
            ax[i] = all.get(i).getX();
            az[i] = all.get(i).getZ();
        }

        // 2. 建邻接表：每点连最近的 NEIGHBORS 个邻居（双向去重）
        int[] nbrStart = new int[n + 1];
        List<int[]> nbrList = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nbrList.add(topKNearest(i, ax, az, n, NEIGHBORS, maxEdge));
        }
        int totalEdges = 0;
        for (int[] a : nbrList) totalEdges += a.length;
        int[] edgeTo = new int[totalEdges];
        double[] edgeLen = new double[totalEdges];
        int cursor = 0;
        for (int i = 0; i < n; i++) {
            nbrStart[i] = cursor;
            for (int j : nbrList.get(i)) {
                edgeTo[cursor] = j;
                edgeLen[cursor] = Math.hypot(ax[i] - ax[j], az[i] - az[j]);
                cursor++;
            }
        }
        nbrStart[n] = cursor;

        // 3. 城市对选择：**每个城市各取最近 K 个**，而不是全局 top-N。
        //
        // 0.9.077 真机取证：半径 128 时 structures=36 connections=23 —— 23 条边
        // 连不成 36 个点（树要 35 条），说明存在孤立分量。根因是全局 top-N 按交通量
        // 排序时近距离对被高权重垄断，边都挤在同一片区域，外围城市一条边都拿不到。
        // 改成「每城市最近 K 个」后，每个城市至少被连 K 次 ⇒ 覆盖面由构造保证。
        int kNear = Math.max(2, NEAREST_TRIPS_PER_CITY);
        Set<Long> picked = new HashSet<>();
        List<double[]> trips = new ArrayList<>();
        for (int i = 0; i < cityCount; i++) {
            int[] idx = new int[kNear];
            double[] best = new double[kNear];
            Arrays.fill(idx, -1);
            Arrays.fill(best, Double.MAX_VALUE);
            for (int j = 0; j < cityCount; j++) {
                if (i == j) continue;
                double dx = ax[i] - ax[j];
                double dz = az[i] - az[j];
                double d = Math.hypot(dx, dz);
                if (d > maxEdge) continue;
                for (int m = 0; m < kNear; m++) {
                    if (d < best[m]) {
                        for (int q = kNear - 1; q > m; q--) {
                            best[q] = best[q - 1];
                            idx[q] = idx[q - 1];
                        }
                        best[m] = d;
                        idx[m] = j;
                        break;
                    }
                }
            }
            for (int m = 0; m < kNear; m++) {
                if (idx[m] < 0) continue;
                int a = Math.min(i, idx[m]);
                int b = Math.max(i, idx[m]);
                long key = edgeKey(a, b);
                if (!picked.add(key)) continue;
                double weight = 1.0D / (1.0D + best[m] * best[m] / (TRAFFIC_SCALE * TRAFFIC_SCALE));
                trips.add(new double[]{a, b, weight});
            }
        }
        trips.sort((x, y) -> Double.compare(y[2], x[2]));
        int tripLimit = trips.size();
        Map<Long, Boolean> usedEdges = new HashMap<>();
        Set<Long> emitted = new LinkedHashSet<>();
        List<StructureConnection> out = new ArrayList<>();
        int[] prev = new int[n];
        double[] dist = new double[n];
        boolean[] closed = new boolean[n];
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));

        for (int ti = 0; ti < tripLimit; ti++) {
            double[] trip = trips.get(ti);
            int src = (int) trip[0];
            int dst = (int) trip[1];
            if (src >= cityCount || dst >= cityCount) continue;
            Arrays.fill(dist, Double.MAX_VALUE);
            Arrays.fill(prev, -1);
            Arrays.fill(closed, false);
            open.clear();
            dist[src] = 0.0D;
            open.add(new double[]{src, 0.0D});
            boolean reached = false;
            while (!open.isEmpty()) {
                double[] top = open.poll();
                int u = (int) top[0];
                if (closed[u]) continue;
                closed[u] = true;
                if (u == dst) { reached = true; break; }
                for (int e = nbrStart[u]; e < nbrStart[u + 1]; e++) {
                    int v = edgeTo[e];
                    if (closed[v]) continue;
                    long key = edgeKey(u, v);
                    double cost = edgeLen[e] * (usedEdges.containsKey(key) ? ALPHA : 1.0D);
                    double nd = dist[u] + cost;
                    if (nd < dist[v]) {
                        dist[v] = nd;
                        prev[v] = u;
                        open.add(new double[]{v, nd});
                    }
                }
            }
            if (!reached) continue;

            // 回溯并累计已用边（对所有经过的边打折，包括 dummy 之间的）
            int walk = dst;
            while (walk >= 0) {
                int p = prev[walk];
                if (p < 0) break;
                usedEdges.put(edgeKey(p, walk), Boolean.TRUE);
                walk = p;
            }

            // 关键：把整条路径压缩成「城市序列」，相邻两城之间输出一条边。
            // 中间的 dummy 只是跳板 —— 若只认「直接相邻」，跨 dummy 的段会被整段丢掉，
            // 输出就会退化成空或残缺。这一步是 Alpha Model 与「每对各画一条线」的分界。
            List<Integer> path = new ArrayList<>();
            walk = dst;
            while (walk >= 0) {
                path.add(walk);
                int p = prev[walk];
                if (p < 0) break;
                walk = p;
            }
            int lastCity = -1;
            for (int i = path.size() - 1; i >= 0; i--) {
                int node = path.get(i);
                if (node >= cityCount) continue;
                if (lastCity >= 0 && lastCity != node) {
                    long id = edgeKey(lastCity, node);
                    if (emitted.add(id)) {
                        out.add(new StructureConnection(cities.get(lastCity), cities.get(node)));
                    }
                }
                lastCity = node;
            }
        }

        // 5. 兜底：把还没被连到的城市用最近邻链接进来，保证不产生孤立点。
        //    覆盖率是硬要求 —— 孤立端点会让「村庄连路网」这件事在游戏里表现为某些村子永远没路。
        Set<Integer> covered = new HashSet<>();
        for (StructureConnection c : out) {
            for (int i = 0; i < cityCount; i++) {
                BlockPos city = cities.get(i);
                if ((city.getX() == c.from().getX() && city.getZ() == c.from().getZ())
                        || (city.getX() == c.to().getX() && city.getZ() == c.to().getZ())) {
                    covered.add(i);
                }
            }
        }
        if (covered.size() < cityCount) {
            out.addAll(connectOrphans(cities, covered, maxEdge));
        }
        if (out.isEmpty() && cityCount >= 2) {
            out.addAll(fallbackChain(cities, maxEdge));
        }
        return out;
    }

    /**
     * 把还没有任何边相连的城市链接进已覆盖集合（每次挑最近的一对）。
     *
     * <p>为什么必须做：孤立端点在游戏里表现为「某个村子永远没有路」，而玩家看到的是
     * 「路网漏了一块」。覆盖率是硬要求，不能只靠 trip 采样碰运气。</p>
     */
    private static List<StructureConnection> connectOrphans(
            List<BlockPos> cities, Set<Integer> covered, int maxEdge) {
        int n = cities.size();
        boolean[] linked = new boolean[n];
        for (int k : covered) {
            if (k >= 0 && k < n) linked[k] = true;
        }
        List<StructureConnection> extra = new ArrayList<>();
        while (true) {
            int bestFrom = -1;
            int bestTo = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!linked[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (linked[j]) continue;
                    double d = Math.hypot(cities.get(i).getX() - cities.get(j).getX(),
                            cities.get(i).getZ() - cities.get(j).getZ());
                    if (d <= maxEdge && d < bestDist) {
                        bestDist = d;
                        bestFrom = i;
                        bestTo = j;
                    }
                }
            }
            if (bestTo < 0) break;
            linked[bestTo] = true;
            extra.add(new StructureConnection(cities.get(bestFrom), cities.get(bestTo)));
        }
        return extra;
    }

    private static List<StructureConnection> fallbackChain(List<BlockPos> cities, int maxEdge) {
        int n = cities.size();
        boolean[] linked = new boolean[n];
        List<StructureConnection> chain = new ArrayList<>();
        linked[0] = true;
        for (int step = 1; step < n; step++) {
            int bestFrom = -1;
            int bestTo = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!linked[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (linked[j]) continue;
                    double d = Math.hypot(cities.get(i).getX() - cities.get(j).getX(),
                            cities.get(i).getZ() - cities.get(j).getZ());
                    if (d <= maxEdge && d < bestDist) {
                        bestDist = d;
                        bestFrom = i;
                        bestTo = j;
                    }
                }
            }
            if (bestTo < 0) break;
            linked[bestTo] = true;
            chain.add(new StructureConnection(cities.get(bestFrom), cities.get(bestTo)));
        }
        return chain;
    }

    /** 返回距离最小的 k 个邻居索引；无合格边时返回空数组。 */
    private static int[] topKNearest(int i, int[] ax, int[] az, int n, int k, int maxEdge) {
        int[] idx = new int[k];
        double[] best = new double[k];
        Arrays.fill(idx, -1);
        Arrays.fill(best, Double.MAX_VALUE);
        for (int j = 0; j < n; j++) {
            if (i == j) continue;
            double d = Math.hypot(ax[i] - ax[j], az[i] - az[j]);
            if (d > maxEdge) continue;
            for (int s = 0; s < k; s++) {
                if (d < best[s]) {
                    for (int m = k - 1; m > s; m--) {
                        best[m] = best[m - 1];
                        idx[m] = idx[m - 1];
                    }
                    best[s] = d;
                    idx[s] = j;
                    break;
                }
            }
        }
        int count = 0;
        for (int s = 0; s < k; s++) {
            if (idx[s] >= 0) count++;
        }
        if (count == 0) return new int[0];
        int[] out = new int[count];
        int c = 0;
        for (int s = 0; s < k; s++) {
            if (idx[s] >= 0) out[c++] = idx[s];
        }
        return out;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static List<BlockPos> unique2d(List<BlockPos> points) {
        Map<Long, BlockPos> map = new LinkedHashSet<BlockPos>(points).stream()
                .collect(java.util.stream.Collectors.toMap(
                        p -> BlockPos.asLong(p.getX(), 0, p.getZ()),
                        p -> p,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
        return new ArrayList<>(map.values());
    }

    private static double halton(int index, int base) {
        double result = 0.0D;
        double f = 1.0D / base;
        int i = index;
        while (i > 0) {
            result += f * (i % base);
            i /= base;
            f /= base;
        }
        return result;
    }
}
