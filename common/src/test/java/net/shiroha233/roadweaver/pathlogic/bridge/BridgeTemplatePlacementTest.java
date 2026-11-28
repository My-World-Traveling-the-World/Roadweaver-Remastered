package net.shiroha233.roadweaver.pathlogic.bridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.structures.registry.BridgeTemplateStructureRegistry.BridgeTemplate;
import net.shiroha233.roadweaver.util.Line;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class BridgeTemplatePlacementTest {
    private static final int DECK = 64;
    private static final Line LINE = new Line(new Vec3(15, DECK, 8), new Vec3(39, DECK, 8));
    private static final BlockPos MOUTH = new BlockPos(15, DECK, 8);
    private static final BlockPos FLOOR = MOUTH.below();
    private static final BlockPos PREVIOUS = new BlockPos(14, DECK, 8);
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final RoadSegmentPlacement SEGMENT = new RoadSegmentPlacement(MOUTH, List.of(MOUTH));
    private static final RoadData ROAD = new RoadData(3, 0, List.of(), List.of(), List.of(SEGMENT),
            List.of(), List.of(DECK), 1L, 2L);

    @Test
    void configuredBridgeLightsRespectBlockSpacingAndZeroDisablesAddedLights() {
        var bridge = net.shiroha233.roadweaver.config.ConfigService.get().bridge();
        int saved = bridge.lightIntervalBlocks();
        try {
            var line = new Line(new Vec3(0, DECK, 8), new Vec3(24, DECK, 8));
            var middle = new BlockPos(10, DECK, 8);
            var previous = middle.west();
            var segment = new RoadSegmentPlacement(middle, List.of(middle));
            for (int interval : new int[]{0, 4, 16}) {
                bridge.setLightIntervalBlocks(interval);
                var site = new Site(null);
                site.water = true;
                site.fullBlocks = true;
                assertTrue(BridgeSegmentPlannerNew.processSegment(site, CHUNK, ROAD, line,
                        segment, middle, previous, DECK, false));
                assertFalse(site.written.isEmpty(), "关灯也必须实际放出模板");
                var lights = site.written.entrySet().stream().filter(e -> e.getValue().is(Blocks.SEA_LANTERN))
                        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                // 原模板按当前区块收集整列；跨度16的灯可落在相邻的最后一列15，保留既有贴边规则。
                var expected = interval == 4 ? Set.of(new BlockPos(0, DECK - 1, 8), new BlockPos(4, DECK - 1, 8),
                        new BlockPos(8, DECK - 1, 8), new BlockPos(12, DECK - 1, 8), new BlockPos(15, DECK - 1, 8))
                        : interval == 16 ? Set.of(new BlockPos(0, DECK - 1, 8), new BlockPos(15, DECK - 1, 8)) : Set.<BlockPos>of();
                assertEquals(expected, lights, "必须按跨度格距而不是陆路站号放灯");
            }
        } finally {
            bridge.setLightIntervalBlocks(saved);
        }
    }

    @Test
    void landingReplacesLowAbutmentWithWalkableDeckOnlyInCurrentChunk() {
        Site site = new Site(null);
        site.lowAbutment = true;
        var plan = BridgeSegmentPlannerNew.plannedBridgeWithAccess(site, LINE, SEGMENT, MOUTH, PREVIOUS, DECK, true);
        assertTrue(plan.centerChunkHandles());
        assertTrue(plan.blocks().contains(FLOOR));
        assertTrue(plan.blocks().contains(FLOOR.east()), "跨区块列应存在于规划，否则施工边界断言会空转");
        assertTrue(build(site));
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), site.written.get(FLOOR),
                "M2：桥返回已处理时，桥口中心实际必须有模板甲板");
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), site.written.get(FLOOR.north()));
        assertFalse(site.written.containsKey(FLOOR.below()), "低半砖不能当作接岸甲板");
        assertFalse(site.written.containsKey(FLOOR.east()));
        assertFalse(site.written.isEmpty());
        assertTrue(site.written.keySet().stream().allMatch(pos -> BridgeSegmentPlannerNew.ownsTarget(CHUNK, pos)));
    }

    @Test
    void 模板甲板撞保护块时拒绝且不落块() {
        // 水上桥口直接用桥身列；确保保护 veto 落在实际访问的体素上。
        Site site = new Site(FLOOR);
        site.water = true;
        assertFalse(build(site));
        assertTrue(site.protectedHits > 0);
        assertTrue(site.written.isEmpty());
    }

    @Test
    void curvedTemplateKeepsOrderedPredictionAndWritesSameShapeAcrossAdjacentChunks() {
        // 长弦 + 正弦横移在区块边缘穿过；选取弯顶，既能比较符号，也能看到相邻块的落点。
        Line line = Line.withHorizontalBend(new Vec3(-72, 63, 0), new Vec3(72, 65, 0), 5);
        BlockPos middle = new BlockPos(0, 64, 5);
        BlockPos previous = new BlockPos(-1, 64, 5);
        var segment = new RoadSegmentPlacement(middle, List.of(middle, middle.north(), middle.south()));
        var access = new Site(null);
        access.water = true;
        access.asymmetric = true;
        var expected = legacyCurvedProjection(access.template, line, middle, previous);
        var prediction = BridgeSegmentPlannerNew.plannedBridgeWithAccess(
                access, line, segment, middle, previous, DECK, false);
        assertTrue(prediction.centerChunkHandles());
        assertEquals(List.copyOf(expected.keySet()), prediction.blocks(), "预报保持原有点序和曲线栅格边界");
        assertTrue(expected.keySet().stream().anyMatch(p -> p.getX() < 0));
        assertTrue(expected.keySet().stream().anyMatch(p -> p.getX() >= 0));
        assertTrue(expected.containsValue(Blocks.BIRCH_PLANKS.defaultBlockState()), "夹具必须能区分曲线两侧");
        assertTrue(expected.containsValue(Blocks.OAK_PLANKS.defaultBlockState()));

        ChunkPos left = new ChunkPos(-1, 0), right = new ChunkPos(0, 0);
        Map<BlockPos, BlockState> first = buildChunks(access, line, segment, middle, previous, List.of(left, right));
        var reversed = new Site(null);
        reversed.water = true;
        reversed.asymmetric = true;
        assertEquals(expected, first);
        assertEquals(expected, buildChunks(reversed, line, segment, middle, previous, List.of(right, left)));
    }

    @Test
    void oceanLengthSinusoidalBridgeWritesEveryCenterColumnAcrossChunks() {
        // 1408 格接近实机断续跨度，16 格整段桥不足以暴露无量纲的投影误差。
        var line = Line.withHorizontalBend(new Vec3(0, DECK, 0), new Vec3(0, DECK, 1408), 5);
        var access = new Site(null);
        access.water = true;
        for (int z = 656; z < 752; z += 16) {
            var middle = new BlockPos(-5, DECK, z + 7);
            var previous = middle.north();
            var segment = new RoadSegmentPlacement(middle, List.of(middle));
            assertTrue(BridgeSegmentPlannerNew.processSegment(access, new ChunkPos(middle), ROAD,
                    line, segment, middle, previous, DECK, false), "必须铺出区块 " + z);
        }
        // 弯顶真实中心在 x=-5 附近；逐列检查不是用总写块数代替桥面的连续性。
        assertEquals(-5.0D, line.pointAt(0.5D).x, 1.0E-9D);
        for (int z = 656; z < 752; z++) {
            assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), access.written.get(new BlockPos(-5, DECK - 1, z)),
                    "断桥列 z=" + z);
        }
    }

    private static Map<BlockPos, BlockState> buildChunks(Site access, Line line, RoadSegmentPlacement segment,
            BlockPos middle, BlockPos previous, List<ChunkPos> order) {
        for (ChunkPos chunk : order) {
            Set<BlockPos> existing = new HashSet<>(access.written.keySet());
            assertTrue(BridgeSegmentPlannerNew.processSegment(access, chunk, ROAD, line,
                    segment, middle, previous, DECK, false));
            Set<BlockPos> added = new HashSet<>(access.written.keySet());
            added.removeAll(existing);
            assertFalse(added.isEmpty(), "两个区块都必须真正写出一段");
            assertTrue(added.stream().allMatch(p -> chunk.equals(new ChunkPos(p))));
        }
        return access.written;
    }

    private static Map<BlockPos, BlockState> legacyCurvedProjection(BridgeTemplate bridge, Line line,
            BlockPos middle, BlockPos previous) {
        // 直接保留旧逐块投影作独立对照，测试不从新列缓存的字段恢复期望。
        Map<BlockPos, BlockState> expected = new java.util.LinkedHashMap<>();
        double length = line.getTotalLength();
        for (int x = previous.getX() - 10; x <= middle.getX() + 10; x++) {
            for (int z = middle.getZ() - 10; z <= middle.getZ() + 10; z++) {
                Vec3 point = new Vec3(x, DECK, z);
                Line.Frame frame = line.getFrame(point);
                Vec3 offset = point.subtract(frame.closestPoint);
                if (Math.abs(offset.dot(frame.tangent0)) > 2
                        || !bridge.isInVoxelGrid(0, 0, offset.dot(frame.binormal0))) continue;
                double mapped = BridgeSegmentPlannerNew.mapTemplateX(
                        Math.min(Math.max(0, frame.globalT), 1) * length, length, 4, 4, 4, 12);
                for (int y = DECK + 20; y >= DECK - 60; y--) {
                    Vec3 local = new Vec3(x, y, z).subtract(frame.closestPoint);
                    BlockState state = bridge.getBlock(Math.max(4, Math.min(7.9999, mapped)),
                            local.dot(frame.normal0), local.dot(frame.binormal0));
                    if (state != null) expected.put(new BlockPos(x, y, z), state);
                }
            }
        }
        return expected;
    }

    private static boolean build(Site site) {
        return BridgeSegmentPlannerNew.processSegment(site, CHUNK, ROAD, LINE, SEGMENT, MOUTH, PREVIOUS, DECK, true);
    }

    private static final class Site implements BridgeSegmentPlannerNew.PlacementAccess {
        // 低桥台保留模板体素，但不能冒充和普通道路齐平的桥面。
        private final BridgeTemplate template = new BridgeTemplate(
                ResourceLocation.parse("roadweaver:test_bridge"), null, null, null) {
            @Override public int getStartLength() { return 4; }
            @Override public int getEndLength() { return 4; }
            @Override public int getDeckLength() { return 4; }
            @Override public int getTotalLength() { return 12; }
            @Override public boolean isInVoxelGrid(double x, double y, double z) { return Math.abs(z) <= 1; }
            @Override public BlockState getBlock(double x, double y, double z) {
                if (Math.abs(z) > 1) return null;
                if (lowAbutment && x < 4 && Math.abs(y + 2) < 0.15) return Blocks.OAK_SLAB.defaultBlockState();
                if (!(x >= 4 && x < 8 && Math.abs(y + 1) < 0.1)) return null;
                return asymmetric && z < -0.3 ? Blocks.BIRCH_PLANKS.defaultBlockState()
                        : Blocks.OAK_PLANKS.defaultBlockState();
            }
        };
        private final Map<BlockPos, BlockState> written = new HashMap<>();
        private final BlockPos protectedBlock;
        private boolean water;
        private boolean fullBlocks;
        private boolean lowAbutment;
        // 横向坐标反号时不能靠同一材质把错误藏过去。
        private boolean asymmetric;
        private int protectedHits;

        private Site(BlockPos protectedBlock) { this.protectedBlock = protectedBlock; }
        public BridgeTemplate template(int seed) { return template; }
        public boolean isOpenWater(BlockPos pos) { return water; }
        public String dimensionId() { return "roadweaver:test"; }
        public boolean isProtected(BlockPos pos, RoadData road) {
            if (!pos.equals(protectedBlock)) return false;
            protectedHits++;
            return true;
        }
        public void clearHeadroom(RoadSegmentPlacement segment, int deckY, RoadData road) {}
        // 地面紧贴甲板，不让桥墩或灯光替代模板甲板，从而冒充模板落块。
        public int minBuildHeight() { return DECK - 1; }
        public BlockState getBlockState(BlockPos pos) {
            return written.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        public boolean isFullBlock(BlockState state, BlockPos pos) { return fullBlocks; }
        public BlockState updateFromNeighbourShapes(BlockState state, BlockPos pos) { return state; }
        public void setBlock(BlockPos pos, BlockState state) { written.put(pos, state); }
    }
}
