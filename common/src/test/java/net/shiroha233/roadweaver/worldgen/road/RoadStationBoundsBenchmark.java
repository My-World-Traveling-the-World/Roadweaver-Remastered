/* 文件职责：隔离计时纯区块编译；产物一致性由单独的冻结对照回归验证。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.core.model.RoadData;

public final class RoadStationBoundsBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var spread = CompilePreparationTest.rangeRoad(1024, false);
        var compact = CompilePreparationTest.rangeRoad(512, true);
        var shortRoad = CompilePreparationTest.rangeRoad(80, false);
        compare("long", spread, chunks(-8, 64, -2), 6);
        compare("compact", compact, chunks(-9, 3, -2), 36);
        compare("short", shortRoad, chunks(-8, 5, -2), 36);
    }

    private static ChunkPos[] chunks(int first, int count, int z) {
        var chunks = new ChunkPos[count];
        for (int i = 0; i < count; i++) chunks[i] = new ChunkPos(first + i, z);
        return chunks;
    }

    private static void compare(String name, RoadData road, ChunkPos[] chunks, int repetitions) {
        var list = List.of(road);
        var cache = new RoadChunkIntersection.GeometryCache();
        for (int i = 0; i < 6; i++) {
            run(list, chunks, repetitions, null);
            run(list, chunks, repetitions, cache);
        }
        long[] straight = new long[9], warm = new long[9], build = new long[9];
        for (int i = 0; i < straight.length; i++) {
            // 轮换先后，并另测每轮重建一次索引，防止只报告理想热命中。
            if (i % 2 == 0) straight[i] = run(list, chunks, repetitions, null);
            warm[i] = run(list, chunks, repetitions, cache);
            if (i % 2 != 0) straight[i] = run(list, chunks, repetitions, null);
            build[i] = run(list, chunks, repetitions, new RoadChunkIntersection.GeometryCache());
        }
        int total = 0;
        for (var chunk : chunks) {
            var selection = cache.near(road.roadSegmentList(), chunk, RoadChunkIntersection.queryMargin(3));
            for (int run = 0; run < RoadStationBounds.Selection.count(selection); run++) {
                total += RoadStationBounds.Selection.end(selection, run, road.roadSegmentList().size())
                        - RoadStationBounds.Selection.start(selection, run);
            }
        }
        System.out.printf("ROAD_STATION_BENCH %s chunks=%d stations=%d->%d full=%.3fms warm=%.3fms includesBuild=%.3fms%n",
                name, chunks.length, chunks.length * road.roadSegmentList().size(), total,
                median(straight), median(warm), median(build));
    }

    private static long run(List<RoadData> roads, ChunkPos[] chunks, int repetitions, RoadChunkIntersection.GeometryCache cache) {
        long value = 0L;
        long start = System.nanoTime();
        for (int n = 0; n < repetitions; n++) for (var chunk : chunks) {
            var plan = RoadChunkPlanCompiler.compile(chunk, roads, true, 7, 1, cache);
            value += plan.slices().size() + plan.densityStamp().targetY(8, 8);
        }
        sink = value;
        return System.nanoTime() - start;
    }

    private static double median(long[] samples) {
        Arrays.sort(samples);
        return samples[samples.length / 2] / 1_000_000.0;
    }
}
