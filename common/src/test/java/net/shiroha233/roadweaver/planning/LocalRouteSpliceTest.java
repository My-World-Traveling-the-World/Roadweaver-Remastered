package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 局部拼接调用真实选窗和合并函数，外段被改写或窗口跨出去时必须报红。 */
final class LocalRouteSpliceTest {
    @Test
    void reroutesOnlyTheSelectedSpanAndPreservesBothTails() {
        List<BlockPos> original = IntStream.rangeClosed(-24, 24).mapToObj(x -> point(x, 0)).toList();
        var slice = RoadPlanningService.localRouteSlices(original, point(0, 0), 16).stream()
                .filter(candidate -> candidate.start().equals(point(-8, 0)) && candidate.end().equals(point(8, 0)))
                .findFirst().orElseThrow();
        // 两锚点接回原路线，中间确实走不同的列；只返回原路也必须失败。
        List<BlockPos> detour = IntStream.rangeClosed(-8, 8)
                .mapToObj(x -> point(x, Math.abs(x) == 8 ? 0 : 1)).toList();
        List<BlockPos> expected = IntStream.rangeClosed(-24, 24)
                .mapToObj(x -> point(x, Math.abs(x) < 8 ? 1 : 0)).toList();
        assertEquals(expected,
                RoadPlanningService.spliceRouteSlice(original, slice.startIndex(), slice.endIndex(), detour));
    }

    @Test
    void leavingAndReenteringTheWindowDoesNotMergeTheTwoSlices() {
        // 一条连续的回折路两次经过冲突圆；远处的折返不能夹在同一个局部候选里。
        List<BlockPos> original = new ArrayList<>();
        for (int x = -24; x <= 24; x++) original.add(point(x, 0));
        for (int z = 1; z <= 4; z++) original.add(point(24, z));
        for (int x = 23; x >= -24; x--) original.add(point(x, 4));
        var slices = RoadPlanningService.localRouteSlices(original, point(0, 0), 16);
        assertTrue(slices.stream().anyMatch(slice -> slice.start().getZ() == 0 && slice.end().getZ() == 0));
        assertTrue(slices.stream().anyMatch(slice -> slice.start().getZ() == 4 && slice.end().getZ() == 4));
        for (var slice : slices) {
            assertEquals(slice.start().getZ(), slice.end().getZ(), "圆窗外的折返不能被替换");
        }
        assertTrue(RoadPlanningService.localRouteSlices(original, point(0, 40), 16).isEmpty());
    }

    private static BlockPos point(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    @Test
    void readsOriginalGridOnlyWhenTheRepairedGridHasNoPath() {
        var source = new PathfindingCostConfig().withPathGridStep(4);
        var repair = source.withPathGridStep(2);
        var original = List.of(point(0, 0), point(4, 0), point(8, 0));
        var patched = List.of(point(0, 0), point(4, 2), point(8, 0));
        var lookups = new ArrayList<Integer>();
        assertEquals(original, RouteRefinementOps.repairSourcePath(source, repair, config -> {
            int step = config.effectivePathGridStep();
            lookups.add(step);
            return step == 4 ? Optional.of(original) : Optional.empty();
        }));
        assertEquals(List.of(2, 4), lookups, "查不到此档时只读原档，不按新档假造底稿");
        assertEquals(patched, RouteRefinementOps.repairSourcePath(source, repair, config -> {
            assertEquals(2, config.effectivePathGridStep(), "不能用原路线覆盖已修好的走廊");
            return Optional.of(patched);
        }));
    }

    @Test
    void exactProfileConflictDoesNotTriggerRoughSlopeScansElsewhere() {
        var path = List.of(point(0, 0), new BlockPos(16, 120, 0), point(320, 0), point(360, 0));
        var route = new StructureConnection(path.getFirst(), path.getLast(), ConnectionStatus.PLANNED);
        var center = point(300, 2);
        // 有精确见证时不需要查询世界，也不该把远方粗网格的假陡点变成精采任务。
        var windows = ConflictWindowOps.conflictSamplingWindows(null, route, 40, path, center);
        assertEquals(1, windows.size());
        assertEquals(center, windows.getFirst().center());
        assertEquals(40, windows.getFirst().radius());
    }
}
