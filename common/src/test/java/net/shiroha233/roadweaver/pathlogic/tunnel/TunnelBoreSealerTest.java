package net.shiroha233.roadweaver.pathlogic.tunnel;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 用可写的世界夹具对拍封水，避免只验证空产物而漏掉实际落块。 */
class TunnelBoreSealerTest {
    private static final BlockPos BASE = new BlockPos(-2, 64, 15);
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sealedBoreDriesInteriorAndSealsNeighborsInOriginalOrder() {
        Fake world = new Fake();
        BlockPos wet = BASE.above();
        BlockPos dry = BASE.above(2);
        world.put(wet, Blocks.WATER.defaultBlockState());
        world.put(dry, Blocks.AIR.defaultBlockState());
        for (int[] side : SIDES) world.put(dry.offset(side[0], 0, side[1]), Blocks.WATER.defaultBlockState());
        assertArrayEquals(new int[]{1, 4}, TunnelBoreSealer.seal(plan(false, BASE), 2, world));
        assertEquals(5, world.writes.size());
        assertEquals(new Write(wet, Blocks.AIR.defaultBlockState()), world.writes.get(0));
        for (int i = 0; i < SIDES.length; i++) {
            int[] side = SIDES[i];
            assertEquals(new Write(dry.offset(side[0], 0, side[1]), Blocks.STONE_BRICKS.defaultBlockState()),
                    world.writes.get(i + 1));
        }
    }

    @Test
    void portalOnlyDriesWaterAndPreservesOtherFluidsAndShell() {
        Fake world = new Fake();
        world.put(BASE.above(), Blocks.WATER.defaultBlockState());
        world.put(BASE.above(2), Blocks.LAVA.defaultBlockState());
        world.put(BASE.above(3), Blocks.AIR.defaultBlockState());
        BlockPos shell = BASE.above(3).east();
        world.put(shell, Blocks.WATER.defaultBlockState());
        assertArrayEquals(new int[]{1, 0}, TunnelBoreSealer.seal(plan(true, BASE), 3, world));
        assertEquals(List.of(new Write(BASE.above(), Blocks.AIR.defaultBlockState())), world.writes);
        assertEquals(Blocks.LAVA.defaultBlockState(), world.block(BASE.above(2)));
        assertEquals(Blocks.WATER.defaultBlockState(), world.block(shell));
        assertEquals(List.of(), world.protectionChecks);
    }

    @Test
    void bothProtectionsRetainWaterAndPassItsOriginalStateToGuard() {
        Fake world = new Fake();
        BlockPos dry = BASE.above(2);
        world.put(BASE.above(), Blocks.WATER.defaultBlockState());
        world.owned.add(BASE.above());
        world.put(dry, Blocks.AIR.defaultBlockState());
        for (int[] side : SIDES) world.put(dry.offset(side[0], 0, side[1]), Blocks.WATER.defaultBlockState());
        world.protectedPositions.add(dry.east());
        world.owned.add(dry.west());
        assertArrayEquals(new int[]{0, 2}, TunnelBoreSealer.seal(plan(false, BASE), 2, world));
        assertEquals(List.of(new Write(dry.south(), Blocks.STONE_BRICKS.defaultBlockState()),
                new Write(dry.north(), Blocks.STONE_BRICKS.defaultBlockState())), world.writes);
        assertEquals(4, world.protectionChecks.size());
        for (Check check : world.protectionChecks) {
            assertEquals(Blocks.WATER.defaultBlockState(), check.state());
            assertEquals(80, check.terrain());
        }
        assertEquals(Blocks.WATER.defaultBlockState(), world.block(dry.east()));
        assertEquals(Blocks.WATER.defaultBlockState(), world.block(dry.west()));
    }

    @Test
    void overlappingColumnsMatchLegacyIncludingWritesVisibleToFollowingVisits() {
        Random random = new Random(718293L);
        BlockState[] states = {Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), Blocks.STONE.defaultBlockState()};
        int nonEmpty = 0;
        for (int sample = 0; sample < 256; sample++) {
            Fake original = new Fake();
            for (int x = -1; x <= 2; x++) for (int z = -1; z <= 1; z++) for (int y = 1; y <= 4; y++) {
                BlockPos pos = BASE.offset(x, y, z);
                original.put(pos, states[random.nextInt(states.length)]);
                if (random.nextInt(7) == 0) original.protectedPositions.add(pos);
                if (random.nextInt(9) == 0) original.owned.add(pos);
            }
            Fake expected = original.copy();
            Fake actual = original.copy();
            TunnelPlan plan = plan(sample % 2 == 0, BASE, BASE.east(), BASE);
            assertArrayEquals(legacy(plan, 4, expected), TunnelBoreSealer.seal(plan, 4, actual), "sample=" + sample);
            assertEquals(expected.writes, actual.writes, "写入顺序必须一致 sample=" + sample);
            assertEquals(expected.blocks, actual.blocks, "最终方块必须一致 sample=" + sample);
            assertEquals(expected.protectionChecks, actual.protectionChecks);
            if (!actual.writes.isEmpty()) nonEmpty++;
        }
        assertTrue(nonEmpty > 200, "夹具必须实际产生足够多的非空施工结果，不能空转对拍");
    }

    private static TunnelPlan plan(boolean portal, BlockPos... columns) {
        return new TunnelPlan(List.of(columns), List.of(), null, false, null, portal, false, false, false, false);
    }

    // 固定旧循环作独立参照，不调用生产封水内核，防止拿新实现验证自身。
    private static int[] legacy(TunnelPlan plan, int roof, Fake world) {
        int dried = 0, sealed = 0;
        for (BlockPos column : plan.interior()) {
            int terrain = world.terrain(column);
            for (int dy = 1; dy <= roof; dy++) {
                BlockPos inside = column.above(dy);
                if (world.water(world.block(inside))) {
                    if (!world.wallOwned(inside)) {
                        world.write(inside, Blocks.AIR.defaultBlockState());
                        dried++;
                    }
                    continue;
                }
                if (plan.portal()) continue;
                if (!world.block(inside).isAir()) continue;
                for (int[] side : SIDES) {
                    BlockPos shell = inside.offset(side[0], 0, side[1]);
                    if (!world.water(world.block(shell))) continue;
                    if (world.protectedVolume(shell, world.block(shell), terrain) || world.wallOwned(shell)) continue;
                    world.write(shell, Blocks.STONE_BRICKS.defaultBlockState());
                    sealed++;
                }
            }
        }
        return new int[]{dried, sealed};
    }

    private record Write(BlockPos position, BlockState state) {}
    private record Check(BlockPos position, BlockState state, int terrain) {}

    private static final class Fake implements TunnelBoreSealer.Port {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> protectedPositions = new HashSet<>();
        final Set<BlockPos> owned = new HashSet<>();
        final List<Write> writes = new ArrayList<>();
        final List<Check> protectionChecks = new ArrayList<>();
        int reads;

        void put(BlockPos position, BlockState state) { blocks.put(position, state); }
        Fake copy() {
            Fake result = new Fake();
            result.blocks.putAll(blocks);
            result.protectedPositions.addAll(protectedPositions);
            result.owned.addAll(owned);
            return result;
        }
        @Override public BlockState block(BlockPos position) {
            reads++;
            return blocks.getOrDefault(position, Blocks.STONE.defaultBlockState());
        }
        @Override public int terrain(BlockPos column) { return 80; }
        @Override public boolean protectedVolume(BlockPos position, BlockState state, int terrain) {
            protectionChecks.add(new Check(position, state, terrain));
            return protectedPositions.contains(position);
        }
        @Override public boolean wallOwned(BlockPos position) { return owned.contains(position); }
        @Override public void write(BlockPos position, BlockState state) {
            writes.add(new Write(position, state));
            blocks.put(position, state);
        }
        // 离线没有服务端的数据包标签绑定，夹具明确提供水和非水分类。
        @Override public boolean water(BlockState state) { return state.is(Blocks.WATER); }
    }
}
