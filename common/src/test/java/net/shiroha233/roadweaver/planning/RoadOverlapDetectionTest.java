package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.pathlogic.junction.RoadBraidDetector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划期并排检测（0.9.083 新增的 {@code comparePaths} 重载）。
 *
 * <p>这条重载让「规划期判」与「生成期判」共用同一份空间索引 + 局部走向 + 连续窗口逻辑，
 * 保证两者不会漂移。真机依据：{@code (1936,-400)->(2464,-672)} 与
 * {@code (2880,-1152)->(1936,-400)} 汇聚到同一村庄，足迹范围几乎完全重叠。</p>
 */
class RoadOverlapDetectionTest {

    private static List<BlockPos> line(int stations, int fromX, int z) {
        List<BlockPos> path = new ArrayList<>(stations);
        for (int i = 0; i < stations; i++) {
            path.add(new BlockPos(fromX + i * 8, 70, z));
        }
        return path;
    }

    private static List<BlockPos> concat(List<BlockPos> first, List<BlockPos> second) {
        List<BlockPos> out = new ArrayList<>(first.size() + second.size());
        out.addAll(first);
        out.addAll(second);
        return out;
    }

    @Test
    void identicalPathsAreBraidedForTheWholeRun() {
        List<BlockPos> a = line(40, 0, 0);
        List<BlockPos> b = line(40, 0, 0);
        RoadBraidDetector.Report report = RoadBraidDetector.comparePaths(a, b);
        assertTrue(report.braided(), "完全重合的两条路径必须判为并排");
        assertEquals(0, report.secondaryFrom());
        assertTrue(report.coversLastStation(40), "并行段应覆盖到 secondary 末端");
    }

    @Test
    void farApartPathsAreNotBraided() {
        List<BlockPos> a = line(40, 0, 0);
        List<BlockPos> b = line(40, 0, 200);   // 200 格外，远超 MAX_DISTANCE
        RoadBraidDetector.Report report = RoadBraidDetector.comparePaths(a, b);
        assertFalse(report.braided(), "相距 200 格的两条路不该判为并排");
    }

    @Test
    void tooShortPathsAreSkipped() {
        List<BlockPos> a = line(3, 0, 0);      // 短于 MIN_RUN = 6
        List<BlockPos> b = line(3, 0, 0);
        RoadBraidDetector.Report report = RoadBraidDetector.comparePaths(a, b);
        assertFalse(report.braided());
        assertEquals("too-short", report.detail());
    }

    @Test
    void onlyTheParallelTailIsReported() {
        // 真机形态：前 20 站各走各的，后 20 站并到一起
        List<BlockPos> a = line(40, 0, 0);
        List<BlockPos> separate = new ArrayList<>();
        for (int i = 0; i < 20; i++) separate.add(new BlockPos(i * 8, 70, 120));
        List<BlockPos> tail = new ArrayList<>();
        for (int i = 20; i < 40; i++) tail.add(new BlockPos(i * 8, 70, 0));
        List<BlockPos> b = concat(separate, tail);

        RoadBraidDetector.Report report = RoadBraidDetector.comparePaths(a, b);
        assertTrue(report.braided(), "末尾重合 20 站必须判出并排");
        assertTrue(report.secondaryFrom() >= 18 && report.secondaryFrom() <= 22,
                "并行段起点应落在第 20 站附近，实际 " + report.secondaryFrom());
        assertTrue(report.coversLastStation(40), "并行段覆盖到末端 —— 这是可截断的前提");
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertFalse(RoadBraidDetector.comparePaths(null, line(20, 0, 0)).braided());
        assertFalse(RoadBraidDetector.comparePaths(line(20, 0, 0), null).braided());
        assertFalse(RoadBraidDetector.comparePaths(List.of(), List.of()).braided());
    }
}
