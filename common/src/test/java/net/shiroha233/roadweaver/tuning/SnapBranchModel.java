package net.shiroha233.roadweaver.tuning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.planning.impl.RNGPlanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code SnapBranchPlanner} 的**逐行参数化副本**（test-only），只为调参而存在。
 *
 * <p>为什么要有副本：{@code SnapBranchPlanner} 的 5 个质量旋钮（{@code MIN_PROJECTION_T} /
 * {@code MAX_PROJECTION_T} / {@code MAX_SPLIT_STRETCH} / {@code EDGE_ATTACH_BIAS} /
 * {@code FALLBACK_POINT_LIMIT}）是 {@code private static final}，反射改不了（javac 会在使用点
 * 内联常量、且 final 字段不可 set），而调参又不该先改产品代码（用户铁律：先测量、后拍板）。
 * 因此这里把算法原样抄一遍，只把那 5 个常量换成 {@link Params} 字段。
 *
 * <p><b>保真性</b>：必须与真实规划器输出**逐边一致**。为达成这一点，这里刻意保留原实现的
 * 全部数据结构与运算顺序——尤其 {@code HashSet<Integer> activeVertices()} 与
 * {@code HashSet<Long> edges} 的**迭代顺序**（原实现里 {@code bestForPoint} 用严格小于比较，
 * 平局保留先遇到的候选 ⇒ 迭代顺序会决定结果）。{@link #verifyAgainstProduction} 在语料上
 * 逐条断言这一点，调参前必须先跑通。
 *
 * <p>与真实类的差异**只有**：5 个常量来自参数；其余（含 {@code guard = n*3}、平局规则、
 * 兜底 RNG）完全一致。
 */
public final class SnapBranchModel {

    /** 诊断：NODE 夹角守卫改变了最近邻选择的次数（0 = 守卫从不触发）。 */
    public static long nodeGuardFires = 0;
    /** 诊断：NODE 夹角守卫开启时被求值的次数。 */
    public static long nodeGuardChecks = 0;

    /**
     * {@code SnapBranchPlanner} 的 5 个旋钮（出厂值见 {@link #PRODUCTION}），外加一个**尚未存在于产品里**的
     * 候选旋钮 {@code minForkAngleDeg}。
     *
     * <p>{@code minForkAngleDeg > 0} 时，EDGE 吸附被要求「新三岔口 j 处两条新枝的夹角不低于该值」，
     * 否则该候选直接作废、退回 NODE 吸附。{@code 0} = 关闭 = 与出厂行为逐位相同（用于对拍）。
     */
    public record Params(int fallbackPointLimit,
                         double minProjectionT,
                         double maxProjectionT,
                         double maxSplitStretch,
                         double edgeAttachBias,
                         double minForkAngleDeg,
                         double minNodeAngleDeg) {

        /** 与 {@code SnapBranchPlanner.java:15-19} 的硬编码值逐位相同（两个夹角守卫都关闭）。 */
        public static final Params PRODUCTION =
                new Params(700, 0.10, 0.90, 1.20, -6.0, 0.0, 0.0);

        public String label() {
            String base = String.format(java.util.Locale.ROOT, "proj=%.2f..%.2f st=%.2f bias=%.1f",
                    minProjectionT, maxProjectionT, maxSplitStretch, edgeAttachBias);
            if (minForkAngleDeg > 0) {
                base += String.format(java.util.Locale.ROOT, " fork>=%.0f", minForkAngleDeg);
            }
            if (minNodeAngleDeg > 0) {
                base += String.format(java.util.Locale.ROOT, " nodeAng>=%.0f", minNodeAngleDeg);
            }
            return base;
        }
    }

    // ------------------------------------------------------------------ 入口

    public static List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks, Params params) {
        if (points == null || points.size() < 2) return List.of();
        List<BlockPos> unique = unique2d(points);
        if (unique.size() < 2) return List.of();

        long maxD2 = maxEdgeLenBlocks > 0
                ? (long) maxEdgeLenBlocks * (long) maxEdgeLenBlocks
                : Long.MAX_VALUE;
        if (unique.size() > params.fallbackPointLimit()) {
            return planScalable(unique, maxD2, params);
        }

        MutableGraph graph = new MutableGraph(unique);
        Pair seed = findNearestPair(unique, maxD2);
        if (seed == null) {
            return RNGPlanner.planRNG(unique, maxEdgeLenBlocks);
        }
        graph.addEdge(seed.a, seed.b);

        boolean[] connected = new boolean[unique.size()];
        connected[seed.a] = true;
        connected[seed.b] = true;
        int connectedCount = (seed.a == seed.b) ? 1 : 2;

        int guard = unique.size() * 3;
        while (connectedCount < unique.size() && guard-- > 0) {
            Insertion best = findBestInsertion(graph, unique.size(), connected, maxD2, params);
            if (best == null) break;
            applyInsertion(graph, best, maxD2);
            if (!connected[best.pointIndex]) {
                connected[best.pointIndex] = true;
                connectedCount++;
            }
        }

        if (connectedCount < unique.size()) {
            greedyConnectRemaining(graph, connected, maxD2);
        }

        List<StructureConnection> out = graph.toConnections();
        if (out.isEmpty()) {
            return RNGPlanner.planRNG(unique, maxEdgeLenBlocks);
        }
        return out;
    }

    private static List<StructureConnection> planScalable(List<BlockPos> points, long maxD2, Params params) {
        MutableGraph graph = new MutableGraph(points);
        boolean[] connected = new boolean[points.size()];
        long[] nearestNetworkD2 = new long[points.size()];
        Arrays.fill(nearestNetworkD2, Long.MAX_VALUE);

        Pair seed = findNearestPair(points, maxD2);
        if (seed == null) return List.of();
        graph.addEdge(seed.a, seed.b);
        connected[seed.a] = true;
        connected[seed.b] = true;
        int connectedCount = 2;
        updateNearestNetworkDistances(graph, connected, nearestNetworkD2, seed.a, seed.b);

        while (connectedCount < points.size()) {
            int next = nearestPending(connected, nearestNetworkD2, maxD2);
            if (next < 0) {
                Pair componentSeed = findNearestPair(points, connected, maxD2);
                if (componentSeed == null) break;
                graph.addEdge(componentSeed.a, componentSeed.b);
                connected[componentSeed.a] = true;
                connected[componentSeed.b] = true;
                connectedCount += 2;
                updateNearestNetworkDistances(graph, connected, nearestNetworkD2,
                        componentSeed.a, componentSeed.b);
                continue;
            }

            int verticesBefore = graph.vertexCount();
            Insertion insertion = bestForPoint(graph, next, maxD2, params);
            if (insertion == null) {
                nearestNetworkD2[next] = Long.MAX_VALUE;
                continue;
            }
            applyInsertion(graph, insertion, maxD2);
            connected[next] = true;
            connectedCount++;

            int verticesAfter = graph.vertexCount();
            int[] activated = new int[1 + Math.max(0, verticesAfter - verticesBefore)];
            activated[0] = next;
            for (int i = verticesBefore; i < verticesAfter; i++) {
                activated[1 + i - verticesBefore] = i;
            }
            updateNearestNetworkDistances(graph, connected, nearestNetworkD2, activated);
        }

        return graph.toConnections();
    }

    // ------------------------------------------------------------------ 原样抄录的辅助方法

    private static int nearestPending(boolean[] connected, long[] nearestNetworkD2, long maxD2) {
        int best = -1;
        long bestD2 = Long.MAX_VALUE;
        for (int i = 0; i < connected.length; i++) {
            if (connected[i]) continue;
            long distance = nearestNetworkD2[i];
            if (distance <= maxD2 && distance < bestD2) {
                best = i;
                bestD2 = distance;
            }
        }
        return best;
    }

    private static void updateNearestNetworkDistances(MutableGraph graph,
                                                      boolean[] connected,
                                                      long[] nearestNetworkD2,
                                                      int... activatedVertices) {
        for (int i = 0; i < connected.length; i++) {
            if (connected[i]) continue;
            BlockPos pending = graph.vertex(i);
            long best = nearestNetworkD2[i];
            for (int activated : activatedVertices) {
                if (activated < 0 || activated >= graph.vertexCount()) continue;
                best = Math.min(best, dist2(pending, graph.vertex(activated)));
            }
            nearestNetworkD2[i] = best;
        }
    }

    private static Pair findNearestPair(List<BlockPos> points, boolean[] excluded, long maxD2) {
        Pair bestPair = null;
        long bestD2 = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            if (excluded[i]) continue;
            for (int j = i + 1; j < points.size(); j++) {
                if (excluded[j]) continue;
                long d2 = dist2(points.get(i), points.get(j));
                if (d2 <= maxD2 && d2 < bestD2) {
                    bestD2 = d2;
                    bestPair = new Pair(i, j);
                }
            }
        }
        return bestPair;
    }

    private static Insertion findBestInsertion(MutableGraph graph,
                                               int baseCount,
                                               boolean[] connected,
                                               long maxD2,
                                               Params params) {
        Insertion best = null;
        for (int i = 0; i < baseCount; i++) {
            if (connected[i]) continue;
            Insertion cand = bestForPoint(graph, i, maxD2, params);
            if (cand == null) continue;
            if (best == null || cand.cost < best.cost
                    || (cand.cost == best.cost && cand.type == InsertionType.EDGE && best.type != InsertionType.EDGE)) {
                best = cand;
            }
        }
        return best;
    }

    private static Insertion bestForPoint(MutableGraph graph, int pointIndex, long maxD2, Params params) {
        BlockPos p = graph.vertex(pointIndex);
        Insertion best = null;

        int nearestNode = -1;
        long nearestNodeD2 = Long.MAX_VALUE;
        int wideNode = -1;
        long wideNodeD2 = Long.MAX_VALUE;
        double nodeCosLimit = Math.cos(Math.toRadians(params.minNodeAngleDeg()));
        for (int v : graph.activeVertices()) {
            if (v == pointIndex) continue;
            long d2 = dist2(p, graph.vertex(v));
            if (d2 > maxD2) continue;
            if (d2 < nearestNodeD2) {
                nearestNodeD2 = d2;
                nearestNode = v;
            }
            // 候选旋钮：接上去以后在 v 处不能出现太窄的夹角（EDGE 吸附恒为 90°，窄角全来自 NODE 吸附）。
            if (params.minNodeAngleDeg() > 0 && d2 < wideNodeD2 && angleAtNodeOk(graph, v, p, nodeCosLimit)) {
                wideNodeD2 = d2;
                wideNode = v;
            }
        }
        int chosenNode = (params.minNodeAngleDeg() > 0 && wideNode >= 0) ? wideNode : nearestNode;
        if (params.minNodeAngleDeg() > 0 && chosenNode != nearestNode) nodeGuardFires++;
        if (params.minNodeAngleDeg() > 0 && chosenNode == nearestNode) nodeGuardChecks++;
        if (chosenNode >= 0) {
            best = new Insertion(InsertionType.NODE, pointIndex, chosenNode, -1, null,
                    Math.sqrt(dist2(p, graph.vertex(chosenNode))));
        }

        for (long ek : graph.edgeKeys()) {
            int a = (int) (ek >> 32);
            int b = (int) (ek & 0xffffffffL);
            if (a == pointIndex || b == pointIndex) continue;
            BlockPos pa = graph.vertex(a);
            BlockPos pb = graph.vertex(b);

            Projection pr = projectToSegment(p, pa, pb);
            if (pr == null) continue;
            if (pr.t <= params.minProjectionT() || pr.t >= params.maxProjectionT()) continue;

            BlockPos j = new BlockPos((int) Math.round(pr.x), 0, (int) Math.round(pr.z));
            if (same2d(j, pa) || same2d(j, pb) || same2d(j, p)) continue;

            long pj2 = dist2(p, j);
            long aj2 = dist2(pa, j);
            long bj2 = dist2(pb, j);
            if (pj2 > maxD2 || aj2 > maxD2 || bj2 > maxD2) continue;

            double oldLen = Math.sqrt(dist2(pa, pb));
            if (oldLen < 1e-6) continue;
            double splitLen = Math.sqrt(aj2) + Math.sqrt(bj2);
            double stretch = splitLen / oldLen;
            if (stretch > params.maxSplitStretch()) continue;

            double delta = (splitLen - oldLen) + Math.sqrt(pj2) + params.edgeAttachBias();

            // 候选旋钮：新三岔口 j 处两条新枝（j→pa、j→pb）与接入枝（j→p）的夹角都要够宽。
            // j 落在线段 ab 上 ⇒ 那两个夹角互补，取余弦较大者即较小角。
            if (params.minForkAngleDeg() > 0) {
                double cosLimit = Math.cos(Math.toRadians(params.minForkAngleDeg()));
                double cosA = cosAtVertex(j, pa, p);
                double cosB = cosAtVertex(j, pb, p);
                if (Math.max(cosA, cosB) > cosLimit) continue;
            }

            Insertion cand = new Insertion(InsertionType.EDGE, pointIndex, a, b, j, delta);
            if (best == null || cand.cost < best.cost
                    || (cand.cost == best.cost && cand.type == InsertionType.EDGE && best.type != InsertionType.EDGE)) {
                best = cand;
            }
        }
        return best;
    }

    private static void applyInsertion(MutableGraph graph, Insertion insertion, long maxD2) {
        int p = insertion.pointIndex;
        if (insertion.type == InsertionType.NODE) {
            if (insertion.a >= 0 && dist2(graph.vertex(p), graph.vertex(insertion.a)) <= maxD2) {
                graph.addEdge(p, insertion.a);
            }
            return;
        }

        int a = insertion.a;
        int b = insertion.b;
        if (a < 0 || b < 0 || insertion.junction == null) {
            return;
        }
        if (!graph.hasEdge(a, b)) {
            if (dist2(graph.vertex(p), graph.vertex(a)) <= maxD2) graph.addEdge(p, a);
            return;
        }

        int j = graph.addVertex(insertion.junction);
        if (j == p || j == a || j == b) {
            int fallback = (dist2(graph.vertex(p), graph.vertex(a)) <= dist2(graph.vertex(p), graph.vertex(b))) ? a : b;
            if (dist2(graph.vertex(p), graph.vertex(fallback)) <= maxD2) graph.addEdge(p, fallback);
            return;
        }

        if (dist2(graph.vertex(p), graph.vertex(j)) > maxD2
                || dist2(graph.vertex(a), graph.vertex(j)) > maxD2
                || dist2(graph.vertex(b), graph.vertex(j)) > maxD2) {
            int fallback = (dist2(graph.vertex(p), graph.vertex(a)) <= dist2(graph.vertex(p), graph.vertex(b))) ? a : b;
            if (dist2(graph.vertex(p), graph.vertex(fallback)) <= maxD2) graph.addEdge(p, fallback);
            return;
        }

        graph.removeEdge(a, b);
        graph.addEdge(a, j);
        graph.addEdge(j, b);
        graph.addEdge(p, j);
    }

    private static void greedyConnectRemaining(MutableGraph graph, boolean[] connected, long maxD2) {
        for (int i = 0; i < connected.length; i++) {
            if (connected[i]) continue;
            int nearest = -1;
            long best = Long.MAX_VALUE;
            for (int v : graph.activeVertices()) {
                if (v == i) continue;
                long d2 = dist2(graph.vertex(i), graph.vertex(v));
                if (d2 > maxD2) continue;
                if (d2 < best) {
                    best = d2;
                    nearest = v;
                }
            }
            if (nearest >= 0) {
                graph.addEdge(i, nearest);
                connected[i] = true;
            }
        }
    }

    private static Pair findNearestPair(List<BlockPos> points, long maxD2) {
        Pair bestPair = null;
        long bestD2 = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                long d2 = dist2(points.get(i), points.get(j));
                if (d2 > maxD2) continue;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestPair = new Pair(i, j);
                }
            }
        }
        return bestPair;
    }

    /** 把 {@code p} 接到顶点 {@code v} 上时，{@code v} 处所有夹角是否都不小于阈值（无邻边则恒真）。 */
    private static boolean angleAtNodeOk(MutableGraph graph, int v, BlockPos p, double cosLimit) {
        BlockPos pv = graph.vertex(v);
        for (int u : graph.neighbors(v)) {
            if (cosAtVertex(pv, graph.vertex(u), p) > cosLimit) return false;
        }
        return true;
    }

    /** 顶点 {@code vertex} 处 {@code u}、{@code v} 两条腿的夹角余弦；任一腿退化时返回 1（视为最窄）。 */
    private static double cosAtVertex(BlockPos vertex, BlockPos u, BlockPos v) {
        double ux = u.getX() - vertex.getX();
        double uz = u.getZ() - vertex.getZ();
        double vx = v.getX() - vertex.getX();
        double vz = v.getZ() - vertex.getZ();
        double lu = Math.sqrt(ux * ux + uz * uz);
        double lv = Math.sqrt(vx * vx + vz * vz);
        if (lu < 1e-9 || lv < 1e-9) return 1.0;
        return (ux * vx + uz * vz) / (lu * lv);
    }

    private static Projection projectToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double ax = a.getX();
        double az = a.getZ();
        double bx = b.getX();
        double bz = b.getZ();
        double px = p.getX();
        double pz = p.getZ();
        double vx = bx - ax;
        double vz = bz - az;
        double len2 = vx * vx + vz * vz;
        if (len2 <= 1e-9) return null;
        double t = ((px - ax) * vx + (pz - az) * vz) / len2;
        return new Projection(ax + t * vx, az + t * vz, t);
    }

    private static List<BlockPos> unique2d(List<BlockPos> points) {
        ArrayList<BlockPos> out = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (BlockPos p : points) {
            if (p == null) continue;
            BlockPos q = new BlockPos(p.getX(), 0, p.getZ());
            long k = PlanningUtils.pos2dKey(q);
            if (seen.add(k)) out.add(q);
        }
        return out;
    }

    private static long dist2(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean same2d(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    // ------------------------------------------------------------------ 保真性自检

    /**
     * 在给定语料上把本副本（用 {@link Params#PRODUCTION}）与真实
     * {@code SnapBranchPlanner} 对拍，要求**逐边集合完全相同**。
     *
     * @return 不一致的 case 数（0 表示副本保真，可以放心用来扫参）
     */
    public static int verifyAgainstProduction(List<List<BlockPos>> corpus, int maxEdgeLenBlocks) {
        int mismatches = 0;
        for (int i = 0; i < corpus.size(); i++) {
            List<BlockPos> pts = corpus.get(i);
            Set<String> real = canonical(new net.shiroha233.roadweaver.planning.impl.SnapBranchPlanner()
                    .plan(pts, maxEdgeLenBlocks));
            Set<String> replica = canonical(plan(pts, maxEdgeLenBlocks, Params.PRODUCTION));
            if (!real.equals(replica)) {
                mismatches++;
                System.out.printf(java.util.Locale.ROOT,
                        "REPLICA MISMATCH case=%d points=%d real=%d replica=%d%n",
                        i, pts.size(), real.size(), replica.size());
            }
        }
        return mismatches;
    }

    private static Set<String> canonical(List<StructureConnection> connections) {
        Set<String> out = new HashSet<>();
        for (StructureConnection c : connections) {
            if (c == null) continue;
            String a = c.from().getX() + "," + c.from().getZ();
            String b = c.to().getX() + "," + c.to().getZ();
            out.add(a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部类型（原样抄录）

    private record Projection(double x, double z, double t) {}

    private record Pair(int a, int b) {}

    private enum InsertionType {
        NODE,
        EDGE
    }

    private static final class Insertion {
        private final InsertionType type;
        private final int pointIndex;
        private final int a;
        private final int b;
        private final BlockPos junction;
        private final double cost;

        private Insertion(InsertionType type, int pointIndex, int a, int b, BlockPos junction, double cost) {
            this.type = type;
            this.pointIndex = pointIndex;
            this.a = a;
            this.b = b;
            this.junction = junction;
            this.cost = cost;
        }
    }

    private static final class MutableGraph {
        private final ArrayList<BlockPos> vertices = new ArrayList<>();
        private final ArrayList<HashSet<Integer>> adj = new ArrayList<>();
        private final HashSet<Long> edges = new HashSet<>();
        private final HashMap<Long, Integer> posToIndex = new HashMap<>();

        MutableGraph(List<BlockPos> points) {
            for (BlockPos p : points) addVertex(p);
        }

        BlockPos vertex(int i) {
            return vertices.get(i);
        }

        int addVertex(BlockPos p) {
            BlockPos q = new BlockPos(p.getX(), 0, p.getZ());
            long key = PlanningUtils.pos2dKey(q);
            Integer old = posToIndex.get(key);
            if (old != null) return old;
            int idx = vertices.size();
            vertices.add(q);
            adj.add(new HashSet<>());
            posToIndex.put(key, idx);
            return idx;
        }

        void addEdge(int a, int b) {
            if (a == b) return;
            long k = edgeKey(a, b);
            if (!edges.add(k)) return;
            adj.get(a).add(b);
            adj.get(b).add(a);
        }

        void removeEdge(int a, int b) {
            if (a == b) return;
            long k = edgeKey(a, b);
            if (!edges.remove(k)) return;
            adj.get(a).remove(b);
            adj.get(b).remove(a);
        }

        boolean hasEdge(int a, int b) {
            return edges.contains(edgeKey(a, b));
        }

        Set<Integer> neighbors(int v) {
            return adj.get(v);
        }

        Set<Long> edgeKeys() {
            return edges;
        }

        Set<Integer> activeVertices() {
            HashSet<Integer> out = new HashSet<>();
            for (int i = 0; i < adj.size(); i++) {
                if (!adj.get(i).isEmpty()) out.add(i);
            }
            return out;
        }

        int vertexCount() {
            return vertices.size();
        }

        List<StructureConnection> toConnections() {
            ArrayList<StructureConnection> out = new ArrayList<>(edges.size());
            for (long k : edges) {
                int a = (int) (k >> 32);
                int b = (int) (k & 0xffffffffL);
                out.add(new StructureConnection(vertices.get(a), vertices.get(b)));
            }
            return out;
        }

        private static long edgeKey(int a, int b) {
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            return (((long) lo) << 32) ^ (hi & 0xffffffffL);
        }
    }

    private SnapBranchModel() {}
}
