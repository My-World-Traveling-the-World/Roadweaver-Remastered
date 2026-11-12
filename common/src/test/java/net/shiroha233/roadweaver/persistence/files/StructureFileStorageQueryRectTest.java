/* 文件职责：StructureFileStorage.queryRect 桶索引（0.8.467）与全表扫描的等价性回归。 */
package net.shiroha233.roadweaver.persistence.files;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.467 的 queryRect 从「全表扫描」改成「64×64 网格桶索引」，这里用无 ServerLevel 的方式
 * 直接驱动包内可见的 {@code buildBucketIndex} / {@code queryRectFromIndex}，与一份朴素的
 * 全表扫描参考实现比对，确保：
 * <ul>
 *   <li>任意矩形（1×1 点查询 / 长方形 / 覆盖全图的大矩形）命中集合完全一致；</li>
 *   <li>负坐标（{@code Math.floorDiv} 而非截断整除）不会漏桶或串桶；</li>
 *   <li>source 过滤、闭区间边界、min/max 传反 的语义正确。</li>
 * </ul>
 *
 * <p>顺序：桶索引按桶分组返回（零排序开销），因此断言统一比较「按 posKey 排序后的副本」，
 * 并顺带打印每个矩形档位的平均耗时（诊断用，非断言）。
 */
final class StructureFileStorageQueryRectTest {

    private static final Set<Integer> ALL_SOURCES = Set.of(
            StructureFileStorage.SOURCE_PREDICTED, StructureFileStorage.SOURCE_MANUAL);

    private static long posKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static StructureInfo infoAt(int x, int z) {
        return new StructureInfo(new BlockPos(x, 64, z), "test:village");
    }

    private static List<StructureInfo> sortedByPos(List<StructureInfo> infos) {
        List<StructureInfo> copy = new ArrayList<>(infos);
        copy.sort(Comparator.comparingLong(info -> posKey(info.pos().getX(), info.pos().getZ())));
        return copy;
    }

    /** 朴素参考实现：全表扫描（优化前的原始语义），不做排序。 */
    private static List<StructureInfo> naiveQuery(List<StructureInfo> infos, Map<Long, Integer> sources,
                                                  Set<Integer> filter,
                                                  int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        int minX = Math.min(minBlockX, maxBlockX);
        int maxX = Math.max(minBlockX, maxBlockX);
        int minZ = Math.min(minBlockZ, maxBlockZ);
        int maxZ = Math.max(minBlockZ, maxBlockZ);
        List<StructureInfo> out = new ArrayList<>();
        for (StructureInfo info : infos) {
            if (info == null || info.pos() == null) continue;
            int x = info.pos().getX();
            int z = info.pos().getZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            if (filter.contains(sources.getOrDefault(posKey(x, z), StructureFileStorage.SOURCE_PREDICTED))) {
                out.add(info);
            }
        }
        return out;
    }

    private static Map<Long, Integer> seededSources(List<StructureInfo> infos, Random random) {
        Map<Long, Integer> sources = new HashMap<>();
        for (StructureInfo info : infos) {
            int source = random.nextBoolean()
                    ? StructureFileStorage.SOURCE_PREDICTED
                    : StructureFileStorage.SOURCE_MANUAL;
            sources.put(posKey(info.pos().getX(), info.pos().getZ()), source);
        }
        return sources;
    }

    private static String describe(int minX, int minZ, int maxX, int maxZ) {
        return "rect=(" + minX + "," + minZ + ")..(" + maxX + "," + maxZ + ")";
    }

    @Test
    void bucketIndexMatchesNaiveScanForRandomRects() {
        Random random = new Random(20261028L);
        List<StructureInfo> infos = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        while (infos.size() < 2_000) {
            int x = random.nextInt(20_000) - 10_000;
            int z = random.nextInt(20_000) - 10_000;
            if (!used.add(posKey(x, z))) continue;
            infos.add(infoAt(x, z));
        }
        Map<Long, Integer> sources = seededSources(infos, random);
        Map<Long, List<StructureInfo>> index = StructureFileStorage.buildBucketIndex(infos);

        String[] labels = {"point 1x1", "strip 300x40", "window 4000x4000", "whole map"};
        long[] naiveNanos = new long[labels.length];
        long[] bucketedNanos = new long[labels.length];
        int[] counts = new int[labels.length];

        for (int i = 0; i < 800; i++) {
            int kind = i % labels.length;
            int minX;
            int minZ;
            int maxX;
            int maxZ;
            if (kind == 0) {
                StructureInfo pick = infos.get(random.nextInt(infos.size()));
                minX = pick.pos().getX();
                minZ = pick.pos().getZ();
                maxX = minX;
                maxZ = minZ;
            } else if (kind == 1) {
                minX = random.nextInt(20_000) - 10_000;
                minZ = random.nextInt(20_000) - 10_000;
                maxX = minX + random.nextInt(300);
                maxZ = minZ + random.nextInt(40);
            } else if (kind == 2) {
                minX = random.nextInt(20_000) - 10_000;
                minZ = random.nextInt(20_000) - 10_000;
                maxX = minX + random.nextInt(4_000);
                maxZ = minZ + random.nextInt(4_000);
            } else {
                minX = -10_500;
                minZ = -10_500;
                maxX = 10_500;
                maxZ = 10_500;
            }
            long t0 = System.nanoTime();
            List<StructureInfo> expected = naiveQuery(infos, sources, ALL_SOURCES, minX, minZ, maxX, maxZ);
            long t1 = System.nanoTime();
            List<StructureInfo> actual = StructureFileStorage.queryRectFromIndex(
                    index, infos, sources, ALL_SOURCES, minX, minZ, maxX, maxZ);
            long t2 = System.nanoTime();
            naiveNanos[kind] += t1 - t0;
            bucketedNanos[kind] += t2 - t1;
            counts[kind]++;
            assertEquals(sortedByPos(expected), sortedByPos(actual), describe(minX, minZ, maxX, maxZ));
        }

        System.out.printf("queryRect bench (structures=%d):%n", infos.size());
        for (int kind = 0; kind < labels.length; kind++) {
            if (counts[kind] == 0) continue;
            double naiveUs = naiveNanos[kind] / 1000.0 / counts[kind];
            double bucketedUs = bucketedNanos[kind] / 1000.0 / counts[kind];
            System.out.printf("  %-16s n=%3d  naive=%8.2f us  bucketed=%8.2f us  speedup=%6.1fx%n",
                    labels[kind], counts[kind], naiveUs, bucketedUs,
                    bucketedUs == 0.0 ? 0.0 : naiveUs / bucketedUs);
        }
    }

    @Test
    void bucketIndexMatchesNaiveScanForSingleSourceFilter() {
        Random random = new Random(20261029L);
        List<StructureInfo> infos = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            infos.add(infoAt(random.nextInt(4_000) - 2_000, random.nextInt(4_000) - 2_000));
        }
        Map<Long, Integer> sources = seededSources(infos, random);
        Map<Long, List<StructureInfo>> index = StructureFileStorage.buildBucketIndex(infos);
        Set<Integer> onlyManual = Set.of(StructureFileStorage.SOURCE_MANUAL);

        List<StructureInfo> expected = naiveQuery(infos, sources, onlyManual, -2_000, -2_000, 2_000, 2_000);
        List<StructureInfo> actual = StructureFileStorage.queryRectFromIndex(
                index, infos, sources, onlyManual, -2_000, -2_000, 2_000, 2_000);
        assertEquals(sortedByPos(expected), sortedByPos(actual));
        assertTrue(actual.size() < infos.size(), "manual-only 过滤应当少于总数");
    }

    @Test
    void bucketKeysUseFloorDivisionAcrossZero() {
        // -1 与 0 必须落在不同桶；-64 与 63 也必须不同（floorDiv 而非截断整除）
        assertTrue(StructureFileStorage.bucketKey(-1, 0) != StructureFileStorage.bucketKey(0, 0));
        assertTrue(StructureFileStorage.bucketKey(-64, -64) != StructureFileStorage.bucketKey(63, 63));
        assertEquals(StructureFileStorage.bucketKey(-1, -1), StructureFileStorage.bucketKey(-63, -63));
        assertEquals(StructureFileStorage.bucketKey(0, 0), StructureFileStorage.bucketKey(63, 63));

        StructureInfo origin = infoAt(-1, -1);
        StructureInfo far = infoAt(-64, 5);
        StructureInfo zero = infoAt(0, 0);
        StructureInfo positive = infoAt(63, 63);
        List<StructureInfo> infos = List.of(origin, far, zero, positive);
        Map<Long, List<StructureInfo>> index = StructureFileStorage.buildBucketIndex(infos);
        Map<Long, Integer> sources = Map.of();

        // 跨 0 的 1×1：只命中 (0,0)
        assertEquals(List.of(zero), StructureFileStorage.queryRectFromIndex(
                index, infos, sources, ALL_SOURCES, 0, 0, 0, 0));
        // 桶 (-1,-1) 覆盖 x/z ∈ -64..-1：只有 (-1,-1)；( -64,5) 在桶 (-1,0)
        assertEquals(List.of(origin), StructureFileStorage.queryRectFromIndex(
                index, infos, sources, ALL_SOURCES, -64, -64, -1, -1));
        assertEquals(List.of(far), StructureFileStorage.queryRectFromIndex(
                index, infos, sources, ALL_SOURCES, -64, 5, -64, 5));
    }

    @Test
    void emptyIndexAndReversedRangeAreHandled() {
        assertEquals(List.of(), StructureFileStorage.queryRectFromIndex(
                Map.of(), List.of(), Map.of(), ALL_SOURCES, -100, -100, 100, 100));

        List<StructureInfo> infos = List.of(infoAt(10, 20), infoAt(11, 21));
        Map<Long, List<StructureInfo>> index = StructureFileStorage.buildBucketIndex(infos);
        // min/max 传反也按规范化后的矩形处理（旧实现直接比较会返回空；这里语义放宽但一致）
        List<StructureInfo> forward = StructureFileStorage.queryRectFromIndex(
                index, infos, Map.of(), ALL_SOURCES, 0, 0, 100, 100);
        List<StructureInfo> reversed = StructureFileStorage.queryRectFromIndex(
                index, infos, Map.of(), ALL_SOURCES, 100, 100, 0, 0);
        assertEquals(sortedByPos(forward), sortedByPos(reversed));
        assertEquals(2, forward.size());
    }
}
