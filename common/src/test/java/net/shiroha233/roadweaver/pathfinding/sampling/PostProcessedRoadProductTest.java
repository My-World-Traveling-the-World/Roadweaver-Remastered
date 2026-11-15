package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegment;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import net.shiroha233.roadweaver.postprocess.SplineHelper.CurveMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostProcessedRoadProductTest {
    private static final int Y = 64;

    @Test
    void 短输入在四个公开入口均不取世界也不出路() {
        // 没有世界仍能安全拒绝不足两站的输入；合法输入在下面直接跑生产的完整重载。
        List<BlockPos> single = List.of(pos(0, 0));
        assertTrue(PathPostProcessor.process(single, 5, null, null, 1).isEmpty());
        assertTrue(PathPostProcessor.process(single, 5, null, null, 1, null).isEmpty());
        assertTrue(PathPostProcessor.process(single, 5, null, null, 1, CurveMode.CATMULL_ROM, null).isEmpty());
        assertTrue(PathPostProcessor.process(single, 5, null, null, null, 1, CurveMode.CATMULL_ROM, null).isEmpty());
    }

    @Test
    void 陆地直路保留端点和宽度且不触碰紧邻的保护列() {
        List<BlockPos> input = List.of(pos(0, 0), pos(8, 0), pos(16, 0), pos(24, 0), pos(32, 0));
        var wide = process(input, 5, new Terrain(false));
        var narrow = process(input, 1, new Terrain(false));
        assertContinuous(input, wide);
        assertContinuous(input, narrow);

        // 取中段截面，避免把圆形端帽计成道路宽度。保护列紧邻预期铺装的最外一列。
        Set<Integer> wideSection = section(wide, 16);
        assertEquals(Set.of(-2, -1, 0, 1, 2), wideSection);
        assertEquals(Set.of(0), section(narrow, 16));
        Set<Integer> protectedColumns = Set.of(-3, 3);
        for (int x = 4; x <= 28; x++) {
            Set<Integer> columns = section(wide, x);
            assertTrue(columns.contains(0), "缺少中心铺装列 x=" + x);
            assertTrue(java.util.Collections.disjoint(protectedColumns, columns), "越进保护列 x=" + x);
        }
    }

    @Test
    void 水上线桥头连续且施工桥线保留五格正弦() {
        Terrain water = new Terrain(true);
        List<BlockPos> input = List.of(pos(0, 0), pos(32, 0), pos(64, 0), pos(96, 0), pos(128, 0));
        var road = process(input, 5, water);
        assertContinuous(input, road);
        assertTrue(section(road, 0).contains(0), "入桥口有实际铺装");
        assertTrue(section(road, 128).contains(0), "出桥口有实际铺装");
        assertTrue(water.depthReads >= 2, "保证走过桥掩码的取样分支");

        // ±5 格在施工的 BridgeSegment 中定义；process 只输出站序，不能把两者混为一谈。
        boolean[] bridgeMask = new boolean[road.size()];
        Arrays.fill(bridgeMask, true);
        BridgeSegment spans = new BridgeSegment(bridgeMask, road);
        var line = spans.getLine(road.size() / 2);
        assertNotNull(line);
        assertTrue(spans.isLanding(0));
        assertTrue(spans.isLanding(road.size() - 1));
        assertEquals(5.0, Math.abs(line.pointAt(0.5).z - line.pointAt(0).z), 1e-6);
        assertEquals(line.pointAt(0).z, line.pointAt(1).z, 1e-6);
    }

    private static List<RoadSegmentPlacement> process(List<BlockPos> input, int width, Terrain terrain) {
        var sampler = new AccurateHeightSampler(null, new FlatHeight(), AccurateSampleStore.noop());
        return PathPostProcessor.process(input, width, null, null, terrain, 1, CurveMode.CATMULL_ROM, sampler);
    }

    private static void assertContinuous(List<BlockPos> input, List<RoadSegmentPlacement> output) {
        assertTrue(output.size() >= 2, "M1：合法非空路径必须产出道路");
        assertEquals(input.getFirst(), output.getFirst().middlePos());
        assertEquals(input.getLast(), output.getLast().middlePos());
        for (int i = 1; i < output.size(); i++) {
            BlockPos a = output.get(i - 1).middlePos();
            BlockPos b = output.get(i).middlePos();
            int step = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
            assertTrue(step > 0 && step <= RoadConstants.MAX_CENTER_STEP, "站距越界: " + a + " -> " + b);
        }
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos(x, Y, z);
    }

    private static Set<Integer> section(List<RoadSegmentPlacement> road, int x) {
        Set<Integer> z = new HashSet<>();
        for (RoadSegmentPlacement station : road) {
            for (BlockPos pos : station.positions()) {
                if (pos.getX() == x) z.add(pos.getZ());
            }
        }
        return z;
    }

    private static final class Terrain implements PathTerrainField {
        private final boolean water;
        private int depthReads;

        private Terrain(boolean water) { this.water = water; }
        public int seaLevel() { return Y; }
        public int height(int x, int z) { return Y; }
        public int oceanFloor(int x, int z) { return water ? Y - 12 : Y; }
        public boolean isColumnWater(int x, int z) { return water; }
        public boolean isNearWater(int x, int z, int distance) { return water; }
        public Holder<Biome> biome(int x, int z) { return null; }
        public boolean contains(int x, int z) { return true; }
        public int step() { return 1; }
        public boolean hasAccurateSample(int x, int z) { return true; }
        public int waterDepth(int x, int z) {
            depthReads++;
            return height(x, z) - oceanFloor(x, z);
        }
    }

    private static final class FlatHeight implements AccurateHeightBackend {
        // 真实采样器从各区块拿平地高度；避免引入真世界或把桥分支短路。
        public Map<Long, AccurateHeightChunk> sampleChunks(Collection<Long> keys) {
            Map<Long, AccurateHeightChunk> samples = new HashMap<>();
            for (long key : keys) {
                int[] heights = new int[AccurateHeightChunk.COLUMN_COUNT];
                Arrays.fill(heights, Y);
                samples.put(key, new AccurateHeightChunk(ChunkPos.getX(key), ChunkPos.getZ(key),
                        heights, heights, heights));
            }
            return samples;
        }

        public String backendName() { return "flat-test"; }
    }
}
