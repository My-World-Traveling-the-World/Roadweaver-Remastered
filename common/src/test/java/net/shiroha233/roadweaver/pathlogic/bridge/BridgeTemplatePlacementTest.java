package net.shiroha233.roadweaver.pathlogic.bridge;

import java.util.HashMap;
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
    private static final BlockPos PREVIOUS = new BlockPos(14, DECK, 8);
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final RoadSegmentPlacement SEGMENT = new RoadSegmentPlacement(MOUTH, List.of(MOUTH));
    private static final RoadData ROAD = new RoadData(3, 0, List.of(), List.of(), List.of(SEGMENT),
            List.of(), List.of(DECK), 1L, 2L);

    @Test
    void 陆侧桥头补出甲板且只写本区块() {
        Site site = new Site(MOUTH.below());
        var plan = BridgeSegmentPlannerNew.plannedBridgeWithAccess(site, LINE, SEGMENT, MOUTH, PREVIOUS, DECK, true);
        assertTrue(plan.centerChunkHandles());
        assertTrue(plan.blocks().contains(MOUTH));
        assertTrue(plan.blocks().contains(MOUTH.east()), "跨区块列应存在于规划，否则施工边界断言会空转");
        assertTrue(build(site));
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), site.written.get(MOUTH),
                "M2：桥返回已处理时，桥口中心实际必须有模板甲板");
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), site.written.get(MOUTH.north()));
        assertFalse(site.written.containsKey(MOUTH.east()));
        assertFalse(site.written.isEmpty());
        assertTrue(site.written.keySet().stream().allMatch(pos -> BridgeSegmentPlannerNew.ownsTarget(CHUNK, pos)));
    }

    @Test
    void 模板甲板撞保护块时拒绝且不落块() {
        // 水上桥口直接用桥身列；确保保护 veto 落在实际访问的体素上。
        Site site = new Site(MOUTH);
        site.water = true;
        assertFalse(build(site));
        assertTrue(site.protectedHits > 0);
        assertTrue(site.written.isEmpty());
    }

    private static boolean build(Site site) {
        return BridgeSegmentPlannerNew.processSegment(site, CHUNK, ROAD, LINE, SEGMENT, MOUTH, PREVIOUS, DECK, true);
    }

    private static final class Site implements BridgeSegmentPlannerNew.PlacementAccess {
        // 桥台没有甲板，才能区分直接放置和生产代码的桥头重试。
        private final BridgeTemplate template = new BridgeTemplate(
                ResourceLocation.parse("roadweaver:test_bridge"), null, null, null) {
            @Override public int getStartLength() { return 4; }
            @Override public int getEndLength() { return 4; }
            @Override public int getDeckLength() { return 4; }
            @Override public int getTotalLength() { return 12; }
            @Override public boolean isInVoxelGrid(double x, double y, double z) { return Math.abs(z) <= 1; }
            @Override public BlockState getBlock(double x, double y, double z) {
                return x >= 4 && x < 8 && Math.abs(y) < 0.1 && Math.abs(z) <= 1
                        ? Blocks.OAK_PLANKS.defaultBlockState() : null;
            }
        };
        private final Map<BlockPos, BlockState> written = new HashMap<>();
        private final BlockPos protectedBlock;
        private boolean water;
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
        public boolean isFullBlock(BlockState state, BlockPos pos) { return false; }
        public BlockState updateFromNeighbourShapes(BlockState state, BlockPos pos) { return state; }
        public void setBlock(BlockPos pos, BlockState state) { written.put(pos, state); }
    }
}
