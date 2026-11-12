package net.shiroha233.roadweaver.pathlogic.tunnel;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 隧道 span 端点反查站索引的兜底行为（0.9.080）。
 *
 * <p>真机取证：规划期定出 60 个 TUNNEL span（NBT 里确认存在），但建造期
 * `tunnel span` / `tunnel built` / `tunnel rejected` 三种日志全部 0 次 ——
 * 说明 `findSpan` 每次都返回 null，隧道一格没挖。根因是端点用精确 XZ 反查站索引，
 * 站序列被 merge/rebuild 重建过就失配，然后静默丢弃。</p>
 *
 * <p>本类钉住兜底的边界：≤4 格漂移必须匹配上；超过 4 格必须放弃（宁可丢不可错配，
 * 防隧道挖到别的路段上去）。</p>
 */
class TunnelStationIndexFallbackTest {

    private static List<BlockPos> line(int count, int stepX) {
        List<BlockPos> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new BlockPos(i * stepX, 64, 0));
        }
        return points;
    }

    @Test
    void exactCoordinateStillMatches() {
        List<BlockPos> centers = line(10, 8);
        assertEquals(3, PlannedSectionBuilder.indexOf(centers, new BlockPos(24, 64, 0)));
    }

    @Test
    void driftWithinToleranceMatchesNearestStation() {
        List<BlockPos> centers = line(10, 8);
        // 目标比第 3 个站偏 2 格：重建后坐标漂移就是这个量级
        assertEquals(3, PlannedSectionBuilder.indexOf(centers, new BlockPos(26, 64, 0)));
        // 偏 4 格仍在容差内
        assertEquals(3, PlannedSectionBuilder.indexOf(centers, new BlockPos(28, 64, 0)));
    }

    @Test
    void driftBeyondToleranceIsRejected() {
        List<BlockPos> centers = line(10, 8);
        // 偏 8 格：正好是下一个站的位置，会精确命中第 4 个；用 6 格制造「都够不到」的情形
        List<BlockPos> sparse = List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0));
        assertEquals(-1, PlannedSectionBuilder.indexOf(sparse, new BlockPos(20, 64, 0)),
                "偏离两端都超过 4 格时必须放弃，否则会把隧道端点错配到别的段上");
    }

    @Test
    void emptyOrNullInputIsSafe() {
        assertEquals(-1, PlannedSectionBuilder.indexOf(List.of(), new BlockPos(0, 64, 0)));
        assertEquals(-1, PlannedSectionBuilder.indexOf(line(5, 8), null));
    }

    @Test
    void yDifferenceDoesNotAffectMatching() {
        List<BlockPos> centers = line(10, 8);
        // 重建可能只漂 Y（0.9.036 桥那次就是 Y 漂一格），XZ 匹配不应受影响
        assertEquals(3, PlannedSectionBuilder.indexOf(centers, new BlockPos(24, 99, 0)));
    }
}
