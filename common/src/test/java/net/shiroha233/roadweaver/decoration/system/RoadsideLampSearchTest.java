/* 文件职责：对拍排序前后的灯座搜索，守住落点与世界探测量。 */
package net.shiroha233.roadweaver.decoration.system;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RoadsideLampSearchTest {
    @Test
    void flatStationFindsSameBaseWithOneSupportCheckInsteadOfEightyFour() {
        var road = new BlockPos(8, 64, 8);
        var side = new Vec3i(0, 0, 1);
        var exhaustiveReads = new AtomicInteger();
        var rankedReads = new AtomicInteger();
        var expected = exhaustive(road, side, 2, true, pos -> {
            exhaustiveReads.incrementAndGet();
            return true;
        });
        var actual = DecorationPlanner.findRoadsideLampBase(road, side, 2, true, pos -> {
            rankedReads.incrementAndGet();
            return true;
        });
        assertEquals(road.south(2), expected);
        assertEquals(expected, actual);
        assertEquals(84, exhaustiveReads.get());
        assertEquals(1, rankedReads.get());
    }

    @Test
    void matchesExhaustiveAtTiesAndAtNegativeChunkBoundaries() {
        var road = new BlockPos(-8, 64, -8);
        var side = new Vec3i(0, 0, 1);
        var first = road.offset(-2, -1, 2);
        var tied = Set.of(first, road.offset(-2, 1, 2), road.offset(2, -1, 2), road.offset(2, 1, 2));
        assertEquals(first, exhaustive(road, side, 2, true, tied::contains));
        assertEquals(first, DecorationPlanner.findRoadsideLampBase(road, side, 2, true, tied::contains));
        assertNull(DecorationPlanner.findRoadsideLampBase(road, side, 2, true, p -> false));
        // 走多个区块边角、宽度和方向；支撑图固定，避免按调用次序生成“地形”。
        for (var origin : List.of(road, new BlockPos(-17, 70, 0), new BlockPos(-16, 70, -1),
                new BlockPos(15, 71, 16), new BlockPos(16, 72, 15))) {
            for (var ortho : List.of(side, new Vec3i(-1, 0, 1), new Vec3i(1, 0, 0))) {
                for (int outward : new int[]{2, 3, 5, 8}) {
                    for (boolean left : new boolean[]{false, true}) {
                        for (int seed = 0; seed < 17; seed++) {
                            int salt = seed;
                            Predicate<BlockPos> support = pos -> Math.floorMod(pos.hashCode() ^ salt * 47, 13) == 0;
                            assertEquals(exhaustive(origin, ortho, outward, left, support),
                                    DecorationPlanner.findRoadsideLampBase(origin, ortho, outward, left, support));
                        }
                    }
                }
            }
        }
    }

    private static BlockPos exhaustive(BlockPos road, Vec3i ortho, int side, boolean left,
                                       Predicate<BlockPos> supported) {
        // 保留旧枚举和严格小于判据作对照，不借用新偏移排序。
        BlockPos best = null;
        int score = Integer.MAX_VALUE, signed = left ? 1 : -1;
        for (int lateral = Math.max(1, side); lateral <= side + 3; lateral++) {
            for (int tangent = -3; tangent <= 3; tangent++) {
                int x = road.getX() + signed * ortho.getX() * lateral + ortho.getZ() * tangent;
                int z = road.getZ() + signed * ortho.getZ() * lateral - ortho.getX() * tangent;
                if (Math.floorDiv(x, 16) != Math.floorDiv(road.getX(), 16)
                        || Math.floorDiv(z, 16) != Math.floorDiv(road.getZ(), 16)) continue;
                for (int dy : new int[]{0, -1, 1}) {
                    var candidate = new BlockPos(x, road.getY() + dy, z);
                    if (!supported.test(candidate)) continue;
                    int cost = lateral * 100 + Math.abs(tangent) * 10 + Math.abs(dy) * 3;
                    if (cost < score) {
                        best = candidate;
                        score = cost;
                    }
                }
            }
        }
        return best;
    }
}
