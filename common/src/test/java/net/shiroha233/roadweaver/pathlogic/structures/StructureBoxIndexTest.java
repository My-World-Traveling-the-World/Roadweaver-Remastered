/* 文件职责：StructureBoxIndex 的等价性钉 —— 索引结果必须与线性扫描一致（不漏）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StructureBoxIndex} 的回归测试。
 *
 * <h2>钉什么</h2>
 * <ol>
 *   <li><b>不漏</b>：随机盒 + 随机查询矩形，索引返回的集合必须<b>包含</b>线性扫描
 *       判出的所有相交盒。这是索引唯一的正确性要求。</li>
 *   <li><b>超集可以更胖，但不许更瘦</b>：索引允许返回多余的候选（它只按桶粗筛），
 *       所以断言方向只能是「包含」，不能写成「相等」。</li>
 *   <li><b>边界</b>：空索引、null 输入、跨多个桶的长盒、负坐标。</li>
 * </ol>
 */
class StructureBoxIndexTest {

    /** 造一个带 owner 的结构盒。 */
    private static OwnedStructureBox box(int minX, int minZ, int maxX, int maxZ) {
        return new OwnedStructureBox(
                new StructureOwnerKey(0L, "test:probe"),
                BoundingBox.fromCorners(new BlockPos(minX, 0, minZ), new BlockPos(maxX, 63, maxZ)),
                (net.shiroha233.roadweaver.pathlogic.structures.PieceOccupancy) null);
    }

    @Test
    void mergeReusesNegativeBucketsButRecomputesEndpointExemption() {
        var owner = new StructureOwnerKey(11L, "test:remote");
        var building = new OwnedStructureBox(owner, new BoundingBox(-21, 60, -18, -17, 71, -15),
                java.util.Map.of(new BlockPos(-19, 65, -16), Blocks.OAK_PLANKS.defaultBlockState()));
        var far = new StructureConnection(new BlockPos(-100, 64, -20), new BlockPos(100, 64, -20));
        var base = PlanningSnapshot.forRoute(far, List.of(building), Set.of(), Set.of(), Set.of());
        assertSame(building, base.findCollision(-19, -16, 63, 68));
        assertNull(base.findCollision(-19, -16, 0, 5), "允许完全在地基下方的道路");
        var start = new PreparedStartKey("test", 4, ChunkPos.asLong(-2, -1), 0, null);
        var covered = Set.of(ChunkPos.asLong(-2, -1));
        var merged = PlanningSnapshot.merge(far, base, List.of(building), Set.of(start), covered, Set.of(owner));
        var full = PlanningSnapshot.forRoute(far, List.of(building), Set.of(start), covered, Set.of(owner));

        assertSame(base.boxesByChunk(), merged.boxesByChunk(), "仅更新解析状态和归属不应扫包围盒");
        assertEquals(full, merged);
        assertEquals(Set.of(start), merged.resolvedStarts());
        assertEquals(covered, merged.coveredCandidateChunks());
        assertTrue(merged.ownsEndpoint(owner));
        assertNull(merged.findCollision(-19, -16, 63, 68), "新端点归属立即豁免原建筑");
    }

    @Test
    void mergeReindexesChangedGeometryOrRadiusEvenWithoutNewCoverage() {
        var owner = new StructureOwnerKey(11L, "test:remote");
        var building = new OwnedStructureBox(owner, new BoundingBox(-20, 60, -20, -17, 71, -17),
                java.util.Map.of(new BlockPos(-19, 65, -19), Blocks.OAK_PLANKS.defaultBlockState()));
        var far = new StructureConnection(new BlockPos(-100, 64, -20), new BlockPos(100, 64, -20));
        var base = PlanningSnapshot.forRoute(far, List.of(building), Set.of(), Set.of(), Set.of());
        var wide = base.withAdditionalRoadRadius(32);
        assertTrue(wide.boxesByChunk().size() > base.boxesByChunk().size());
        var narrowed = PlanningSnapshot.merge(far, wide, List.of(), Set.of(), Set.of(), Set.of());
        assertEquals(base.roadRadius(), narrowed.roadRadius());
        assertNotSame(wide.boxesByChunk(), narrowed.boxesByChunk());
        assertEquals(base.boxesByChunk(), narrowed.boxesByChunk());

        var added = new OwnedStructureBox(owner, new BoundingBox(29, 60, -20, 34, 71, -17),
                java.util.Map.of(new BlockPos(32, 65, -19), Blocks.OAK_PLANKS.defaultBlockState()));
        var enlarged = PlanningSnapshot.merge(far, base, List.of(added), Set.of(), Set.of(), Set.of());
        assertNotSame(base.boxesByChunk(), enlarged.boxesByChunk());
        assertSame(added, enlarged.findCollision(32, -19, 63, 68));
        assertNull(base.findCollision(32, -19, 63, 68));
    }

    @Test
    void emptyIndexReturnsNothing() {
        assertTrue(StructureBoxIndex.empty().query(-100, -100, 100, 100).isEmpty(), "case=empty");
        assertEquals(0, StructureBoxIndex.empty().size(), "case=empty-size");
    }

    @Test
    void nullAndDegenerateBoxesAreSkipped() {
        List<OwnedStructureBox> in = new ArrayList<>();
        in.add(null);
        StructureBoxIndex idx = StructureBoxIndex.of(in);
        assertEquals(0, idx.size(), "case=null-skipped");
        assertEquals(0, idx.cellCount(), "case=null-nocells");
    }

    @Test
    void singleBoxIsFound() {
        StructureBoxIndex idx = StructureBoxIndex.of(List.of(box(0, 0, 10, 10)));
        assertEquals(1, idx.size(), "case=one");
        assertEquals(1, idx.query(5, 5, 6, 6).size(), "case=one-hit");
        assertTrue(idx.query(100, 100, 110, 110).isEmpty(), "case=one-miss");
    }

    @Test
    void longBoxSpansManyCellsAndIsDeduped() {
        // 横跨 8 个桶（16 格/桶）的长盒
        StructureBoxIndex idx = StructureBoxIndex.of(List.of(box(0, 0, 127, 4)));
        assertTrue(idx.cellCount() >= 8, "case=span-cells, cells=" + idx.cellCount());
        // 查询覆盖整条长盒：结果里同一个盒只出现一次（去重）
        List<OwnedStructureBox> hits = idx.query(0, 0, 127, 4);
        assertEquals(1, hits.size(), "case=span-dedup, hits=" + hits.size());
    }

    @Test
    void negativeCoordinatesWork() {
        StructureBoxIndex idx = StructureBoxIndex.of(List.of(box(-40, -40, -20, -20)));
        assertEquals(1, idx.size(), "case=neg");
        assertEquals(1, idx.query(-30, -30, -25, -25).size(), "case=neg-hit");
        assertTrue(idx.query(30, 30, 40, 40).isEmpty(), "case=neg-miss");
    }

    @Test
    void indexNeverLosesABoxThatLinearScanFinds() {
        Random rnd = new Random(20261112L);
        List<OwnedStructureBox> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int x = rnd.nextInt(512) - 256;
            int z = rnd.nextInt(512) - 256;
            int w = 1 + rnd.nextInt(48);
            int d = 1 + rnd.nextInt(48);
            all.add(box(x, z, x + w, z + d));
        }
        for (int cellSize : new int[] {8, 16, 32, 64}) {
            StructureBoxIndex idx = StructureBoxIndex.of(all, cellSize);
            for (int q = 0; q < 100; q++) {
                int x0 = rnd.nextInt(560) - 280;
                int z0 = rnd.nextInt(560) - 280;
                int x1 = x0 + rnd.nextInt(64);
                int z1 = z0 + rnd.nextInt(64);
                Set<OwnedStructureBox> expected = new LinkedHashSet<>();
                for (OwnedStructureBox owned : all) {
                    BoundingBox b = owned.box();
                    if (b.maxX() < x0 || b.minX() > x1 || b.maxZ() < z0 || b.minZ() > z1) continue;
                    expected.add(owned);
                }
                List<OwnedStructureBox> got = idx.query(x0, z0, x1, z1);
                assertTrue(got.containsAll(expected),
                        "case=never-lose cellSize=" + cellSize
                                + " rect=[" + x0 + "," + z0 + "," + x1 + "," + z1 + "]"
                                + " expected=" + expected.size() + " got=" + got.size());
            }
        }
    }

    @Test
    void nonPositiveCellSizeFallsBackToDefault() {
        assertEquals(StructureBoxIndex.DEFAULT_CELL_SIZE,
                StructureBoxIndex.of(List.of(), 0).cellSize(), "case=zero-cell");
        assertEquals(StructureBoxIndex.DEFAULT_CELL_SIZE,
                StructureBoxIndex.of(List.of(), -5).cellSize(), "case=neg-cell");
    }

    @Test
    void reversedQueryRectIsNormalized() {
        StructureBoxIndex idx = StructureBoxIndex.of(List.of(box(0, 0, 10, 10)));
        assertEquals(1, idx.query(10, 10, 0, 0).size(), "case=reversed");
    }

    @Test
    void indexIsUsableAfterBuildOnAnotherThread() throws Exception {
        StructureBoxIndex idx = StructureBoxIndex.of(List.of(box(0, 0, 100, 100)));
        final int[] hits = new int[1];
        Thread t = new Thread(() -> hits[0] = idx.query(10, 10, 20, 20).size());
        t.start();
        t.join();
        assertEquals(1, hits[0], "case=cross-thread");
    }
    // ---- fromChunkBuckets：零拷贝复用「区块 → 盒」桶表 ----

    /** 桶键必须与 ChunkPos.asLong 同布局，否则复用现成映射会全部查不到。 */
    @Test
    void chunkBucketKeysMatchChunkPosLayout() {
        OwnedStructureBox owned = box(100, 100, 110, 110);
        long chunkKey = net.minecraft.world.level.ChunkPos.asLong(100 >> 4, 100 >> 4);
        java.util.Map<Long, java.util.List<OwnedStructureBox>> buckets = new java.util.HashMap<>();
        buckets.put(chunkKey, java.util.List.of(owned));
        StructureBoxIndex idx = StructureBoxIndex.fromChunkBuckets(buckets);
        assertEquals(1, idx.size(), "case=chunkKey-size");
        assertEquals(1, idx.query(100, 100, 101, 101).size(), "case=chunkKey-hit");
        assertTrue(idx.query(0, 0, 10, 10).isEmpty(), "case=chunkKey-miss");
    }

    /**
     * 核心不变量：以点为中心查 ±reach 的矩形，必须覆盖所有「到该点水平距离 ≤ reach」的盒。
     * 这条成立 ⇒ 把「全表扫描 + 距离判据」换成「索引查询 + 同一判据」是等价的。
     */
    @Test
    void reachQueryCoversEveryBoxWithinReach() {
        Random rnd = new Random(424242L);
        for (int trial = 0; trial < 50; trial++) {
            List<OwnedStructureBox> all = new ArrayList<>();
            java.util.Map<Long, java.util.List<OwnedStructureBox>> buckets = new java.util.HashMap<>();
            for (int i = 0; i < 120; i++) {
                int x = rnd.nextInt(400) - 200;
                int z = rnd.nextInt(400) - 200;
                int w = rnd.nextInt(24);
                int d = rnd.nextInt(24);
                OwnedStructureBox b = box(x, z, x + w, z + d);
                all.add(b);
                BoundingBox bb = b.box();
                for (int cx = bb.minX() >> 4; cx <= bb.maxX() >> 4; cx++) {
                    for (int cz = bb.minZ() >> 4; cz <= bb.maxZ() >> 4; cz++) {
                        buckets.computeIfAbsent(
                                net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                                k -> new ArrayList<>()).add(b);
                    }
                }
            }
            StructureBoxIndex idx = StructureBoxIndex.fromChunkBuckets(buckets);
            int reach = 16;
            for (int q = 0; q < 40; q++) {
                int px = rnd.nextInt(440) - 220;
                int pz = rnd.nextInt(440) - 220;
                Set<OwnedStructureBox> mustContain = new LinkedHashSet<>();
                for (OwnedStructureBox owned : all) {
                    BoundingBox bb = owned.box();
                    int dx = Math.max(0, Math.max(bb.minX() - px, px - bb.maxX()));
                    int dz = Math.max(0, Math.max(bb.minZ() - pz, pz - bb.maxZ()));
                    if ((long) dx * dx + (long) dz * dz <= (long) reach * reach) mustContain.add(owned);
                }
                List<OwnedStructureBox> got = idx.query(px - reach, pz - reach, px + reach, pz + reach);
                assertTrue(got.containsAll(mustContain),
                        "case=reach-cover trial=" + trial + " px=" + px + " pz=" + pz
                                + " need=" + mustContain.size() + " got=" + got.size());
            }
        }
    }

    @Test
    void emptyChunkBucketsYieldEmptyIndex() {
        assertTrue(StructureBoxIndex.fromChunkBuckets(null).query(0, 0, 10, 10).isEmpty(), "case=null-buckets");
        assertTrue(StructureBoxIndex.fromChunkBuckets(java.util.Map.of()).query(0, 0, 10, 10).isEmpty(), "case=empty-buckets");
    }
}
