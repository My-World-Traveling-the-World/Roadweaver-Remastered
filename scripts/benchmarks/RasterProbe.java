package net.shiroha233.roadweaver.worldgen.road;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

public final class RasterProbe {
    record Input(ChunkPos chunk, List<RoadData> roads, boolean include, int clear) {}
    static volatile long sink;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 固定样本独立于计时；先逐列比对，再测完整编译，避免把检查开销算进去。
        var random = new Random(20261115);
        int nonEmpty = 0;
        for (int i = 0; i < 96; i++) {
            var input = i % 4 == 3 ? parallel(i, 36 + random.nextInt(65))
                    : fixture(i, random, i % 3, 36 + random.nextInt(65));
            var actual = compile(input, false);
            compare(compile(input, true), actual);
            if (!actual.densityStamp().isEmpty()) nonEmpty++;
        }
        check(nonEmpty > 48); // 防止所有用例因夹具错误全返回空计划。
        System.out.printf("RASTER_EQUIVALENCE cases=96 nonEmpty=%d columns=24576 densityY=-32..192%n", nonEmpty);
        run("ordinary_64_stations", fixture(1, new Random(20261115), 0, 64), 1600);
        run("crossing_256_stations", fixture(1, new Random(20261115), 2, 256), 1600);
        run("parallel_640_stations", parallel(1, 640), 320);
    }

    static Input fixture(int n, Random r, int crossings, int size) {
        var chunk = new ChunkPos(-9 + n % 19, -7 + n % 15);
        int minX = chunk.getMinBlockX(), minZ = chunk.getMinBlockZ();
        List<RoadData> roads = new ArrayList<>();
        int shift = r.nextInt(8);
        roads.add(road(n + 10, n % 10 + 1, size, minX, minZ, shift, n % 2 == 1, false, n));
        for (int i = 0; i < crossings; i++) {
            roads.add(road(n + 70 + i * 30, 3 + i * 4, size, minX, minZ, i * 10 - 3, true, true, n));
        }
        return new Input(chunk, roads, n % 2 == 0, n % 17);
    }

    private static Input parallel(int n, int size) {
        var chunk = new ChunkPos(-9 + n % 19, -7 + n % 15);
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        // 两条路长短匹配但不是整条精确重合，能验证归属竞争和分桶断点。
        return new Input(chunk, List.of(
                road(100, 5, size, x, z, 7, true, false, 1),
                road(200, 4, size, x + 4, z, n % 10 + 5, n % 2 == 1, false, 1)),
                false, 6);
    }

    static RoadData road(long id, int width, int n, int x, int z, int shift, boolean curve, boolean cross, int variant) {
        var stations = new ArrayList<RoadSegmentPlacement>();
        var heights = new ArrayList<Integer>();
        for (int j = 0; j < n; j++) {
            int along = (j - n / 2) * 2;
            int across = shift + (curve ? j % 8 / 2 : 0);
            var p = cross ? new BlockPos(x + across, 92 + variant % 7, z + along)
                        : new BlockPos(x + along, 64 + j % 9, z + across);
            stations.add(new RoadSegmentPlacement(p, List.of(p, p.east())));
            heights.add(p.getY());
        }
        var spans = new ArrayList<RoadSpan>();
        if (cross) {
            spans.add(new RoadSpan(stations.getFirst().middlePos(), stations.getLast().middlePos(), SpanType.ROAD_CROSSING));
        } else if (variant % 4 == 0) {
            spans.add(new RoadSpan(stations.get(n/2 + 5).middlePos(), stations.get(n/2 + 12).middlePos(), SpanType.BRIDGE));
        } else if (variant % 4 == 2) {
            spans.add(new RoadSpan(stations.get(n/2 - 7).middlePos(), stations.get(n/2 - 1).middlePos(), SpanType.PROTECTED_GAP));
        }
        return new RoadData(width, 1, List.of(), List.of(), stations, spans, heights, id, id + 1);
    }

    static RoadChunkPlan compile(Input f, boolean old) {
        return old ? BaselineRoadChunkPlanCompiler.compile(f.chunk, f.roads, f.include, f.clear, 99L)
                   : RoadChunkPlanCompiler.compile(f.chunk, f.roads, f.include, f.clear, 99L);
    }

    static void compare(RoadChunkPlan a, RoadChunkPlan b) {
        check(a.slices().size() == b.slices().size());
        for (int i = 0; i < a.slices().size(); i++) {
            var lhs = a.slices().get(i);
            var rhs = b.slices().get(i);
            check(lhs.road().equals(rhs.road()) && lhs.segmentCount() == rhs.segmentCount());
            for (int j = 0; j < lhs.segmentCount(); j++) check(lhs.segmentIndexAt(j) == rhs.segmentIndexAt(j));
            for (int j = 0; j < lhs.road().roadSegmentList().size(); j++) check(lhs.isTunnelJunction(j) == rhs.isTunnelJunction(j));
        }
        var lhs = a.densityStamp();
        var rhs = b.densityStamp();
        check(lhs.isEmpty() == rhs.isEmpty());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            check(lhs.isOccupied(x, z) == rhs.isOccupied(x, z)
                    && lhs.targetY(x, z) == rhs.targetY(x, z)
                    && Float.floatToIntBits(lhs.fillLateral(x, z)) == Float.floatToIntBits(rhs.fillLateral(x, z))
                    && Float.floatToIntBits(lhs.carveLateral(x, z)) == Float.floatToIntBits(rhs.carveLateral(x, z)));
            for (int y = -32; y <= 192; y++) {
                int bx = lhs.chunkPos().getMinBlockX() + x, bz = lhs.chunkPos().getMinBlockZ() + z;
                check(Double.doubleToLongBits(lhs.density(bx,y,bz)) == Double.doubleToLongBits(rhs.density(bx,y,bz)));
            }
        }
    }

    static void check(boolean condition) {
        if (!condition) throw new AssertionError("worldgen plans differ");
    }

    static double time(Input input, boolean baseline, int samples) {
        long start = System.nanoTime(), value = 0;
        for (int i = 0; i < samples; i++) {
            var plan = compile(input, baseline);
            value += plan.slices().size() + plan.densityStamp().targetY(i & 15, (i >> 4) & 15);
        }
        sink = value;
        return (System.nanoTime() - start) / 1000.0 / samples;
    }

    static void run(String label, Input fixture, int samples) {
        // 交换两版的执行次序并多批取中位数，减小同机预热和顺序偏差。
        for (int i = 0; i < 5; i++) {
            time(fixture, true, samples);
            time(fixture, false, samples);
        }
        var baseline = new double[9];
        var candidate = new double[9];
        for (int i = 0; i < baseline.length; i++) {
            if (i % 2 == 0) {
                baseline[i] = time(fixture, true, samples);
                candidate[i] = time(fixture, false, samples);
            } else {
                candidate[i] = time(fixture, false, samples);
                baseline[i] = time(fixture, true, samples);
            }
        }
        Arrays.sort(baseline); Arrays.sort(candidate);
        System.out.printf(Locale.ROOT, "RASTER_BENCH %s old_us=%.2f new_us=%.2f old_range=%.2f..%.2f new_range=%.2f..%.2f%n",
                label, baseline[4], candidate[4], baseline[0], baseline[8], candidate[0], candidate[8]);
    }
}
