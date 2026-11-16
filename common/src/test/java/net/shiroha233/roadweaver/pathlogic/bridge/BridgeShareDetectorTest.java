package net.shiroha233.roadweaver.pathlogic.bridge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨路桥「共享跨越」判定（0.9.029 / R-03）的三条不变量。
 *
 * <p>用户 2026-11-08 定的规则是**纯距离 + 重叠里程**，不判水体：
 * 「按照范围来算，一个桥梁距离另外一个多远没事」。
 * 三档用例对应真机上见过的三种形态：
 * <ol>
 *   <li>真机 `(279,66,-157)` 那两座：距离 ~6 格、重叠 60+ 站 ⇒ **必须吸附**</li>
 *   <li>两条路各过各的河、只在桥头擦肩 2 站 ⇒ **不许吸附**（否则制造无谓绕行）</li>
 *   <li>距离 40 格 ⇒ **两座都保留**</li>
 * </ol>
 */
class BridgeShareDetectorTest {

    private static List<BlockPos> line(int z, int fromX, int toX) {
        List<BlockPos> out = new ArrayList<>();
        // 反向桥也必须产生合法的非空夹具，否则“不共享”的断言会被空输入提前满足。
        int step = fromX <= toX ? 1 : -1;
        for (int x = fromX; ; x += step) {
            out.add(new BlockPos(x, 63, z));
            if (x == toX) break;
        }
        return out;
    }

    /** 真机形态：两条桥沿 x 走 60 站，z 只差 6 格 ⇒ 必须吸附成一条。 */
    @Test
    void nearbyBridgesWithLongOverlapMustShare() {
        List<BlockPos> a = line(-163, 206, 269);
        List<BlockPos> b = line(-159, 205, 267);

        BridgeShareDetector.Report report = BridgeShareDetector.detect(a, b);

        assertTrue(report.shares(), "距离 6 格 + 重叠 60+ 站必须判为同一跨越：" + report);
        assertTrue(report.minDistance() <= BridgeShareDetector.DEFAULT_RADIUS,
                "最短距离应 ≤ 半径：" + report.minDistance());
        assertTrue(report.overlapStations() >= BridgeShareDetector.DEFAULT_MIN_OVERLAP_STATIONS,
                "重叠站数应 ≥ 阈值：" + report.overlapStations());
    }

    /** 擦肩而过：两条桥只在桥头相遇 2 站 ⇒ 不许吸附，否则会白白绕路。 */
    @Test
    void crossingAtOneEndOnlyMustNotShare() {
        // a 沿 +x，b 沿 -x，只在 x≈206 处点一下，然后各自远离
        List<BlockPos> a = line(0, 206, 320);
        List<BlockPos> b = line(3, 206, 92);
        assertEquals(115, b.size(), "反向夹具必须覆盖整条桥");

        // 3 格半径只覆盖相对端头；默认 24 格会覆盖两边各 24 站，不能拿它声称只擦肩。
        BridgeShareDetector.Report report = BridgeShareDetector.detect(a, b, 3, 6);

        assertEquals(3, report.minDistance());
        assertEquals(1, report.overlapStations());
        assertFalse(report.shares(), "只在桥头擦肩的不许吸附：" + report);
    }

    /** 相距 40 格：两条桥各自成立，一律不动。 */
    @Test
    void farApartBridgesAreBothKept() {
        List<BlockPos> a = line(0, 0, 60);
        List<BlockPos> b = line(40, 0, 60);

        BridgeShareDetector.Report report = BridgeShareDetector.detect(a, b);

        assertFalse(report.shares(), "距离 40 格 > 半径 24，两座都保留：" + report);
        assertTrue(report.minDistance() >= 39, "最短距离应在 40 附近：" + report.minDistance());
    }

    /** 半径可配：把半径放大到 48 后，上面那对 40 格的桥也落进同一档。 */
    @Test
    void radiusIsConfigurable() {
        List<BlockPos> a = line(0, 0, 60);
        List<BlockPos> b = line(40, 0, 60);

        assertTrue(BridgeShareDetector.detect(a, b, 48, 6).shares(),
                "半径 48 时应判为同一跨越");
    }

    /**
     * 用户 2026-11-08：「只检测两个离得近的**并行**桥梁，不要弄错」——
     * 两条桥近且重叠很长、但**方向垂直**（两条路各自过河 / 立体交叉）时**不许吸附**。
     */
    @Test
    void perpendicularBridgesMustNotShareEvenWhenClose() {
        List<BlockPos> alongX = line(0, 0, 60);
        List<BlockPos> alongZ = new ArrayList<>();
        for (int z = -30; z <= 30; z++) alongZ.add(new BlockPos(30, 63, z));

        assertTrue(BridgeShareDetector.mayOverlap(alongX, alongZ, BridgeShareDetector.DEFAULT_RADIUS),
                "这两条在包围盒上是邻近的（预筛会放行），所以必须靠平行度挡住");
        assertTrue(BridgeShareDetector.parallelDot(alongX, alongZ) < 0.1D,
                "垂直方向的平行度应接近 0：" + BridgeShareDetector.parallelDot(alongX, alongZ));
        assertFalse(BridgeShareDetector.detect(alongX, alongZ).shares(),
                "垂直的两条桥不许并成一座");
    }

    /** 真机那一对必须判为平行（否则会被平行度条件漏掉）。 */
    @Test
    void realWorldPairIsParallel() {
        double dot = BridgeShareDetector.parallelDot(line(-163, 206, 269), line(-159, 205, 267));
        assertTrue(dot >= BridgeShareDetector.DEFAULT_MIN_PARALLEL_DOT,
                "真机那一对方向基本相同，平行度应 ≥ 0.90：" + dot);
    }

    /** 预筛必须挡住所有「不可能共享」的对，且不许漏掉真机那一对。 */
    @Test
    void cheapPrefilterRejectsFarPairsAndKeepsNearOnes() {
        List<BlockPos> near = line(-159, 205, 267);
        List<BlockPos> sameWater = line(-163, 206, 269);
        List<BlockPos> far = line(40, 0, 60);

        assertTrue(BridgeShareDetector.mayOverlap(near, sameWater, BridgeShareDetector.DEFAULT_RADIUS),
                "真机那一对必须在预筛里通过（否则会被漏掉）");
        assertFalse(BridgeShareDetector.mayOverlap(line(0, 0, 60), far, BridgeShareDetector.DEFAULT_RADIUS),
                "相距 40 格 > 半径 24，预筛必须直接排除，不进精确判定");
        assertFalse(BridgeShareDetector.mayOverlap(null, near, 24), "空输入不许抛");
        assertFalse(BridgeShareDetector.mayOverlap(List.of(), near, 24), "空输入不许抛");

        // 预筛与精确判定必须一致：被预筛排除的对，detect 也不得判为共享
        assertFalse(BridgeShareDetector.detect(line(0, 0, 60), far).shares());
    }

    /** 退化输入不许抛：空/单点直接判不共享。 */
    @Test
    void degenerateInputsAreSafe() {
        assertFalse(BridgeShareDetector.detect(null, null).shares());
        assertFalse(BridgeShareDetector.detect(List.of(), line(0, 0, 10)).shares());
        assertFalse(BridgeShareDetector.detect(List.of(new BlockPos(0, 63, 0)), line(0, 0, 10)).shares());
        BridgeShareDetector.Report empty = BridgeShareDetector.detect(List.of(), List.of());
        assertFalse(empty.shares());
        assertEquals(0, empty.overlapStations());
        assertEquals(Integer.MAX_VALUE, empty.minDistance(), "无站时最短距离应为 MAX_VALUE 哨兵");
    }
}
