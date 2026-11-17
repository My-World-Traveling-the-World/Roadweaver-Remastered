/* 文件职责：独立测量三通道瓦片的分配、重读和密集代价，不把假地形当作真机性能。 */
package net.shiroha233.roadweaver.metric;

import net.shiroha233.roadweaver.bridge.TiledTerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class TiledTerrainProbe {
    private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile double sink;
    private record Run(ArrivalTimeField field, long requests, long columns, TiledTerrainField.Stats stats) {}
    private record Sample(long nanos, long bytes, Run run) {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        if (!BEAN.isThreadAllocatedMemorySupported()) throw new AssertionError("allocation counters unavailable");
        if (!BEAN.isThreadAllocatedMemoryEnabled()) BEAN.setThreadAllocatedMemoryEnabled(true);
        var huge = new GridLayout(-65536, -65536, 16384, 16384, 8);
        for (int i = 0; i < 8; i++) solve(huge, true, false, false);
        long[] nanos = new long[11], bytes = new long[11];
        for (int i = 0; i < nanos.length; i++) {
            var sample = measure(() -> solve(huge, true, false, false));
            nanos[i] = sample.nanos(); bytes[i] = sample.bytes();
            if (sample.run().requests() != 1 || sample.run().columns() != 4096) throw new AssertionError("eager huge sampling");
        }
        System.out.printf("TERRAIN_HUGE_NEAR_MATCH cells=%d heapMax=%d requests=1 sampledColumns=4096 denseHeightReference=%d allocationP50=%d p50=%.3fms p95=%.3fms%n",
                huge.cellCount(), Runtime.getRuntime().maxMemory(), huge.payloadBytes(12), percentile(bytes, .5),
                percentile(nanos, .5) / 1e6, percentile(nanos, .95) / 1e6);
        fullCorridor(huge);
        enclosed(huge);
        if (args.length == 0 || !args[0].equals("huge-only")) {
            compare("cold-near-public-threshold", new GridLayout(-8192, -8192, 2048, 2048, 8), false);
            // 此组在百万格强制分页以隔离采样策略；生产该规模仍同时选择稠密采样和传播。
            compare("cold-dense-forced-paging", new GridLayout(-4096, -4096, 1025, 1025, 8), true);
        }
        System.out.println("TERRAIN_PROBE_PASS sink=" + sink);
    }

    private static void compare(String name, GridLayout grid, boolean far) {
        var whole = solve(grid, false, far, far);
        var tiled = solve(grid, true, far, far);
        int tx = far ? grid.maxX() : grid.minX() + 24, tz = far ? grid.maxZ() : grid.minZ() + 24;
        if (Double.doubleToLongBits(whole.field().at(tx, tz)) != Double.doubleToLongBits(tiled.field().at(tx, tz)))
            throw new AssertionError("target weight mismatch");
        if (far) for (int iz = 0; iz < grid.rows(); iz++) for (int ix = 0; ix < grid.columns(); ix++) {
            int x = grid.minX() + ix * grid.step(), z = grid.minZ() + iz * grid.step();
            if (Double.doubleToLongBits(whole.field().at(x, z)) != Double.doubleToLongBits(tiled.field().at(x, z)))
                throw new AssertionError("field mismatch " + x + "," + z);
        }
        var beforePath = whole.field().pathTo(tx, tz); var afterPath = tiled.field().pathTo(tx, tz);
        if (beforePath.isEmpty() || beforePath.size() != afterPath.size()) throw new AssertionError("empty/different route");
        for (int i = 0; i < beforePath.size(); i++) if (!Arrays.equals(beforePath.get(i), afterPath.get(i))) throw new AssertionError("route mismatch");
        System.out.printf("TERRAIN_MATCH scenario=%s cells=%d routePoints=%d wholeColumns=%d tiledColumns=%d stats=%s%n",
                name, grid.cellCount(), beforePath.size(), whole.columns(), tiled.columns(), tiled.stats());
        for (int i = 0; i < 3; i++) { solve(grid, false, far, far); solve(grid, true, far, far); }
        long[][] nanos = new long[2][7], bytes = new long[2][7];
        for (int i = 0; i < 7; i++) for (int turn = 0; turn < 2; turn++) {
            int mode = (i + turn) & 1;
            var sample = measure(() -> solve(grid, mode == 1, far, far));
            nanos[mode][i] = sample.nanos(); bytes[mode][i] = sample.bytes();
        }
        System.out.printf("TERRAIN_COMPARE scenario=%s allocationP50=%d->%d p50=%.3f->%.3fms p95=%.3f->%.3fms%n",
                name, percentile(bytes[0], .5), percentile(bytes[1], .5),
                percentile(nanos[0], .5) / 1e6, percentile(nanos[1], .5) / 1e6,
                percentile(nanos[0], .95) / 1e6, percentile(nanos[1], .95) / 1e6);
    }

    private static Run solve(GridLayout grid, boolean paged, boolean far, boolean forcePaging) {
        long[] sampling = new long[2];
        TiledTerrainField.GridSampler sampler = request -> {
            sampling[0]++; sampling[1] += request.sampleCount(); return flat(request);
        };
        TerrainField terrain = paged ? new TiledTerrainField(grid, sampler) : new GridTerrainField(
                grid.minX(), grid.minZ(), grid.columns(), grid.rows(), grid.step(),
                sampler.sample(new AccurateHeightGridRequest(grid.minX(), grid.minZ(), grid.columns(), grid.rows(), grid.step())).motionBlocking());
        var metric = FinslerMetric.of(terrain, ObstacleField.NONE);
        var solver = forcePaging ? new FastMarchingSolver(metric, 0) : new FastMarchingSolver(metric);
        int tx = far ? grid.maxX() : grid.minX() + 24, tz = far ? grid.maxZ() : grid.minZ() + 24;
        var field = solver.solve(grid.minX(), grid.minZ(), tx, tz);
        if (!field.reachable(tx, tz)) throw new AssertionError("near/far target lost");
        sink = field.at(tx, tz);
        return new Run(field, sampling[0], sampling[1], terrain instanceof TiledTerrainField t ? t.stats() : null);
    }

    private static void fullCorridor(GridLayout grid) {
        var terrain = new TiledTerrainField(grid, TiledTerrainProbe::flat);
        var obstacles = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return z != grid.minZ(); }
        };
        var field = new FastMarchingSolver(FinslerMetric.of(terrain, obstacles))
                .solve(grid.minX(), grid.minZ(), grid.maxX(), grid.minZ());
        var path = field.pathTo(grid.maxX(), grid.minZ());
        if (path.size() != 16384 || field.at(grid.maxX(), grid.minZ()) != 131064.0) throw new AssertionError("global corridor clipped");
        if (terrain.stats().retainedHeightPayloadBytes() != 3145728 || terrain.stats().evictions() != 192) throw new AssertionError("unbounded tile cache");
        System.out.println("TERRAIN_GLOBAL_CORRIDOR_MATCH routePoints=" + path.size() + " stats=" + terrain.stats());
    }

    private static void enclosed(GridLayout grid) {
        var terrain = new TiledTerrainField(grid, TiledTerrainProbe::flat);
        var metric = FinslerMetric.of(terrain, new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == grid.minX() + 12 * 8 || z == grid.minZ() + 12 * 8; }
        });
        var field = new FastMarchingSolver(metric).solve(grid.minX(), grid.minZ(), grid.maxX(), grid.maxZ());
        if (field.reachable(grid.maxX(), grid.maxZ()) || !field.reachable(grid.minX() + 8, grid.minZ() + 8)
                || terrain.stats().loadedTiles() != 1) throw new AssertionError("enclosed component not preserved");
        System.out.println("TERRAIN_ENCLOSED_MATCH stats=" + terrain.stats());
    }

    private static AccurateHeightGrid flat(AccurateHeightGridRequest request) {
        int[] motion = new int[request.sampleCount()], surface = new int[motion.length], floor = new int[motion.length];
        Arrays.fill(motion, 64); Arrays.fill(surface, 71); Arrays.fill(floor, 64);
        return new AccurateHeightGrid(request, surface, floor, motion);
    }

    private static Sample measure(java.util.function.Supplier<Run> work) {
        long before = BEAN.getCurrentThreadAllocatedBytes(), began = System.nanoTime();
        var run = work.get();
        return new Sample(System.nanoTime() - began, BEAN.getCurrentThreadAllocatedBytes() - before, run);
    }

    private static long percentile(long[] values, double percentile) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1];
    }
}
