package net.shiroha233.roadweaver.pathfinding;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.323：重建路径 1:1 成段后，profile 里相邻 3 格的跳变会被
 * RoadDataValidator 的 MAX_CENTER_STEP=2 判成 invalid-center-step、整条路失败
 * （真机 20260909 16.15 存档：(0,0)-&gt;(97,281) 与 (-1104,304)-&gt;(-813,716)）。
 * 这里钉住两条不变量：补插只加不动的中间站，且补插后的路必须通过最终校验。
 */
final class StationGapDensifyTest {

    /** 复刻真机失败样本：横向 3 格跳变 + 带高度差的 3 格跳变。 */
    private static List<BlockPos> stationsWithThreeBlockGaps() {
        List<BlockPos> stations = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            stations.add(new BlockPos(i * 2, 184, 41));
        }
        // 真机 (13,184,41) -> (12,184,44)：横向 3 格
        stations.add(new BlockPos(78, 184, 44));
        for (int i = 0; i < 40; i++) {
            stations.add(new BlockPos(80 + i * 2, 184, 44));
        }
        // 真机 (-1173,212,321) -> (-1170,214,320)：横向 3 格且高差 2
        stations.add(new BlockPos(161, 186, 44));
        for (int i = 0; i < 40; i++) {
            stations.add(new BlockPos(163 + i * 2, 186, 44));
        }
        return stations;
    }

    private static RoadData roadFrom(List<BlockPos> stations) {
        List<RoadSegmentPlacement> segments = PathPostProcessor.rasterizeStations(stations, 3);
        assertEquals(stations.size(), segments.size(), "重建必须与站点序列 1:1");
        List<Integer> heights = new ArrayList<>(stations.size());
        for (BlockPos station : stations) heights.add(station.getY());
        return new RoadData(3, 0, List.of(), List.of(), segments, List.of(), heights, 1L, 2L);
    }

    @Test
    void densifyOnlyInsertsStationsAndBoundsEveryStep() {
        List<BlockPos> original = stationsWithThreeBlockGaps();
        List<BlockPos> dense = PathPostProcessor.densifyStationGaps(original, 2);

        assertEquals(original.get(0), dense.get(0), "起点不得移动");
        assertEquals(original.get(original.size() - 1), dense.get(dense.size() - 1), "终点不得移动");
        assertEquals(original.size() + 2, dense.size(), "每个 3 格跳变只补一个中间站");

        for (int i = 1; i < dense.size(); i++) {
            BlockPos previous = dense.get(i - 1);
            BlockPos current = dense.get(i);
            int step = Math.max(Math.abs(current.getX() - previous.getX()),
                    Math.abs(current.getZ() - previous.getZ()));
            assertTrue(step <= 2, "补插后仍存在横向 " + step + " 格跳变 at index=" + i);
        }

        // 原站点必须原样保留（顺序子序列），否则路线形状就变了
        int cursor = 0;
        for (BlockPos station : original) {
            while (cursor < dense.size() && !dense.get(cursor).equals(station)) cursor++;
            assertTrue(cursor < dense.size(), "原站点 " + station + " 在补插后丢失");
            cursor++;
        }
    }

    @Test
    void rebuildAcceptsThreeBlockGapRoadsOnlyAfterDensify() {
        List<BlockPos> original = stationsWithThreeBlockGaps();
        RoadDataValidator.ValidationResult raw = RoadDataValidator.validateFinalRoad(roadFrom(original));
        assertFalse(raw.valid(), "未补插的 3 格跳变本应被拒（0.8.320 起真机就是这样失败的）");
        assertEquals("invalid-center-step", raw.reason(), "拒绝原因必须仍是中心步长");

        List<BlockPos> dense = PathPostProcessor.densifyStationGaps(original, 2);
        RoadDataValidator.ValidationResult fixed = RoadDataValidator.validateFinalRoad(roadFrom(dense));
        assertTrue(fixed.valid(), "补插中间站后应通过最终校验，实际=" + fixed.reason());
    }
}
