/* 文件职责：仅供离线比较的方向三角迎风原型，禁止作为生产求解器或回退。 */
package net.shiroha233.roadweaver.metric;

import java.util.*;

public final class DirectionalUpwindCandidate {
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private record Entry(int index, double time) {}
    private DirectionalUpwindCandidate() {}

    public static ArrivalTimeField solve(FinslerMetric metric, int sx, int sz) {
        TerrainField terrain = metric.terrain();
        GridLayout grid = new GridLayout(terrain.minX(), terrain.minZ(), terrain.columns(), terrain.rows(), terrain.step());
        // 原型限定小场，防止误用到4096预热；生产分页和早停仍由现有FMM负责。
        if (grid.arrayLength() > 16_384) throw new IllegalArgumentException("offline small-grid candidate only");
        double[] times = new double[grid.arrayLength()];
        Arrays.fill(times, Double.POSITIVE_INFINITY);
        boolean[] frozen = new boolean[times.length];
        // 同一格的方向目标会被反复求值；局部二次型只取一次，缓存仅在本次小场内有效。
        DirectionalLocalCost[] localCosts = new DirectionalLocalCost[times.length];
        if (!terrain.contains(sx, sz) || !metric.isPassable(sx, sz)) return field(metric, times);
        int source = grid.snapX(sx) + grid.snapZ(sz) * grid.columns();
        int snappedX = terrain.minX() + source % grid.columns() * terrain.step();
        int snappedZ = terrain.minZ() + source / grid.columns() * terrain.step();
        if (!metric.isPassable(snappedX, snappedZ)) return field(metric, times);
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::time).thenComparingInt(Entry::index));
        times[source] = 0; queue.add(new Entry(source, 0));
        while (!queue.isEmpty()) {
            Entry entry = queue.remove(); int index = entry.index();
            if (frozen[index] || entry.time() != times[index]) continue;
            frozen[index] = true;
            int ix = index % grid.columns(), iz = index / grid.columns();
            for (int dir = 0; dir < 8; dir++) {
                int nx = ix + DX[dir], nz = iz + DZ[dir];
                if (nx < 0 || nz < 0 || nx >= grid.columns() || nz >= grid.rows()) continue;
                int target = nx + nz * grid.columns();
                if (frozen[target]) continue;
                int x = terrain.minX() + nx * terrain.step(), z = terrain.minZ() + nz * terrain.step();
                if (!metric.isPassable(x, z)) continue;
                if (localCosts[target] == null) localCosts[target] = DirectionalLocalCost.sample(metric, x, z);
                double value = update(metric, nx, nz, times, frozen, localCosts[target]);
                if (value < times[target]) { times[target] = value; queue.add(new Entry(target, value)); }
            }
        }
        return field(metric, times);
    }

    private static ArrivalTimeField field(FinslerMetric metric, double[] times) {
        var t = metric.terrain();
        return new ArrivalTimeField(times, t.minX(), t.minZ(), t.columns(), t.rows(), t.step(), metric);
    }

    private static double update(FinslerMetric metric, int ix, int iz, double[] times, boolean[] frozen, DirectionalLocalCost local) {
        var terrain = metric.terrain(); int w = terrain.columns(), d = terrain.rows(), step = terrain.step();
        int x = terrain.minX() + ix * step, z = terrain.minZ() + iz * step;
        double best = Double.POSITIVE_INFINITY;
        for (int dir = 0; dir < 8; dir++) {
            int ax = ix + DX[dir], az = iz + DZ[dir];
            if (!accepted(ax, az, w, d, frozen)) continue;
            int a = ax + az * w;
            if (!safe(metric, x, z, x + DX[dir] * step, z + DZ[dir] * step)) continue;
            best = Math.min(best, times[a] + objective(local, step, dir, dir, 0, 0, 0));
            int next = (dir + 1) % 8, bx = ix + DX[next], bz = iz + DZ[next];
            if (!accepted(bx, bz, w, d, frozen)
                    || !safe(metric, x, z, x + DX[next] * step, z + DZ[next] * step)) continue;
            int b = bx + bz * w;
            // 先取有限分段的最佳区间再局部细化；这是候选实验，不能宣称对任意非凸度量精确。
            double position = 0, value = Double.POSITIVE_INFINITY;
            for (int j = 0; j <= 16; j++) {
                double at = j / 16.0;
                double cost = objective(local, step, dir, next, at, times[a], times[b]);
                if (cost < value) { value = cost; position = at; }
            }
            double lo = Math.max(0, position - 1.0 / 16), hi = Math.min(1, position + 1.0 / 16);
            for (int iteration = 0; iteration < 24; iteration++) {
                double left = lo + (hi - lo) / 3, right = hi - (hi - lo) / 3;
                if (objective(local, step, dir, next, left, times[a], times[b])
                        < objective(local, step, dir, next, right, times[a], times[b])) hi = right;
                else lo = left;
            }
            value = Math.min(value, objective(local, step, dir, next, (lo + hi) / 2, times[a], times[b]));
            // 只接受不早于两个前驱的迎风更新，保持按到达时间冻结的因果顺序。
            if (value >= Math.max(times[a], times[b])) best = Math.min(best, value);
        }
        return best;
    }

    private static boolean accepted(int x, int z, int w, int d, boolean[] frozen) {
        return x >= 0 && z >= 0 && x < w && z < d && frozen[x + z * w];
    }

    private static double objective(DirectionalLocalCost local, int step, int a, int b, double t, double ta, double tb) {
        double dx = (DX[a] * (1 - t) + DX[b] * t) * step;
        double dz = (DZ[a] * (1 - t) + DZ[b] * t) * step;
        return ta * (1 - t) + tb * t + local.travel(dx, dz);
    }

    private static boolean safe(FinslerMetric metric, int x, int z, int nx, int nz) {
        if (metric.blocksEdge(x, z, nx, nz) || metric.blocksEdge(nx, nz, x, z)) return false;
        // 原型对三角扇周围整格保守检查；宁可丢候选，也不能用对角线穿过窄禁区。
        for (int px = Math.min(x, nx); px <= Math.max(x, nx); px++)
            for (int pz = Math.min(z, nz); pz <= Math.max(z, nz); pz++)
                if (!metric.isPassable(px, pz)) return false;
        return true;
    }
}
