package net.shiroha233.roadweaver.pathlogic.construction;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HugeTreeReservationTest {
    /** 寻路用例复用真实索引和投影，只替换外部模组的形状注册入口。 */
    public static HugeTreeReservation.PlanningMask mask(int minX, int minZ, int maxX, int maxZ, int radius, BlockPos... trees) {
        var reservations = new HugeTreeReservation.LevelReservations();
        reservations.reserveAll(columns(trees));
        return reservations.planningMask(minX, minZ, maxX, maxZ, radius);
    }

    @Test
    void projectedRoadRadiusIncludesReservationAcrossNegativeChunkBorder() {
        var mask = mask(-16, -16, 0, 0, 4, new BlockPos(1, 64, -1));
        // 树在搜索窗外、相邻区块内，其路宽影响仍必须进入搜索窗。
        assertTrue(mask.blocks(-3, -1));
        assertTrue(mask.blocks(-1, -4));
        assertFalse(mask.blocks(-3, -2));
        assertFalse(mask.blocks(1, -1), "只保留搜索窗内的候选中心");
        assertTrue(mask(-16, -16, 0, 0, 4, new BlockPos(16, 64, 16)).isEmpty());
    }

    @Test
    void segmentDetectsSingleColumnOnCornerAndInBothDirections() {
        var mask = mask(-32, -32, 32, 32, 0, new BlockPos(-1, 64, 0), new BlockPos(17, 64, 7));
        assertFalse(mask.blocks(-4, -4));
        assertFalse(mask.blocks(4, 4));
        assertTrue(mask.intersects(-4, -4, 4, 4));
        assertTrue(mask.intersects(4, 4, -4, -4));
        assertTrue(mask.intersects(16, 7, 24, 7));
        assertFalse(mask.intersects(16, 8, 24, 8));
    }

    @Test
    void releasingOneChunkKeepsItsNeighborAndDoesNotMutateAnInFlightSnapshot() {
        var index = new HugeTreeReservation.LevelReservations();
        index.reserveAll(columns(new BlockPos(-1, 64, 0), new BlockPos(0, 64, 0)));
        var snapshot = index.planningMask(-16, -16, 16, 16, 0);
        index.release(ChunkPos.asLong(-1, 0));
        assertFalse(index.contains(-1, 0));
        assertTrue(index.contains(0, 0));
        assertTrue(snapshot.blocks(-1, 0));
        assertFalse(index.planningMask(-16, -16, 16, 16, 0).blocks(-1, 0));
    }

    @Test
    void evictionDoesNotConfuseAReleasedAndReinsertedChunkWithItsOldQueuePosition() {
        var index = new HugeTreeReservation.LevelReservations();
        index.reserveAll(columns(BlockPos.ZERO, new BlockPos(16, 0, 0)));
        index.release(ChunkPos.asLong(0, 0));
        index.reserveAll(columns(new BlockPos(1, 0, 0)));
        var rest = new LongArrayList();
        for (int i = 2; i <= 4096; i++) rest.add(BlockPos.asLong(i * 16, 0, 0));
        index.reserveAll(rest);
        assertFalse(index.contains(16, 0));
        assertFalse(index.contains(0, 0));
        assertTrue(index.contains(1, 0));
        assertTrue(index.contains(4096 * 16, 0));
    }

    private static LongArrayList columns(BlockPos... positions) {
        var result = new LongArrayList();
        for (BlockPos pos : positions) result.add(pos.asLong());
        return result;
    }
}
