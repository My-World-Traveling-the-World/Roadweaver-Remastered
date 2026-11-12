package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F 阶段回归：{@link AccurateGradeRepairPlanner#findFirstViolation} 从两层循环
 * O(n·W) 改成一次滑窗 O(n)（单调队列求窗口极值），语义必须与旧实现逐字段一致。
 *
 * <p>测试用固定种子的随机路径把新实现与"逐 start 线性扫描"的参考实现逐条比对：
 * 违反窗口的 start/end/rise/length 必须完全相同，包括"无违反"的情形。</p>
 */
final class AccurateGradeRepairPlannerFindViolationTest {

    private static final int LOCAL_WINDOW_BLOCKS = 12;
    private static final int LOCAL_WINDOW_RISE = 10;

    /**
     * 参考实现与生产实现共用同一个阈值来源（{@code localWindowRise()} 读配置），
     * 这里显式钉住配置值，避免配置默认值变化让两边悄悄分叉。
     */
    @BeforeEach
    void pinWindowRise() {
        ConfigService.get().planning().setHeightTunnelThresholdBlocks(LOCAL_WINDOW_RISE);
    }

    /** 旧实现的等价参考版（直接按 start 逐段累加）。 */
    private static AccurateGradeRepairPlanner.Violation reference(List<BlockPos> path) {
        if (path == null || path.size() < 2) return null;
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            double length = Math.hypot(current.getX() - previous.getX(), current.getZ() - previous.getZ());
            int rise = Math.abs(current.getY() - previous.getY());
            if (length > 0.001D && rise > LOCAL_WINDOW_RISE) {
                return new AccurateGradeRepairPlanner.Violation(i - 1, i, rise, length);
            }
        }
        for (int start = 0; start < path.size() - 2; start++) {
            double length = 0.0D;
            for (int end = start + 1; end < path.size(); end++) {
                length += Math.hypot(path.get(end).getX() - path.get(end - 1).getX(),
                        path.get(end).getZ() - path.get(end - 1).getZ());
                if (length + 0.001D < LOCAL_WINDOW_BLOCKS) continue;
                int rise = Math.abs(path.get(end).getY() - path.get(start).getY());
                if (rise > LOCAL_WINDOW_RISE) {
                    return new AccurateGradeRepairPlanner.Violation(start, end, rise, length);
                }
                break;
            }
        }
        return null;
    }

    private static void assertSame(String label, List<BlockPos> path) {
        AccurateGradeRepairPlanner.Violation expected = reference(path);
        AccurateGradeRepairPlanner.Violation actual = AccurateGradeRepairPlanner.findFirstViolation(path);
        if (expected == null) {
            assertNull(actual, label + ": expected no violation");
            return;
        }
        assertNotNull(actual, label + ": expected a violation");
        assertEquals(expected.start(), actual.start(), label + ": start");
        assertEquals(expected.end(), actual.end(), label + ": end");
        assertEquals(expected.rise(), actual.rise(), label + ": rise");
        assertEquals(expected.length(), actual.length(), 1.0E-9D, label + ": length");
    }

    @Test
    void adjacentStepViolationIsDetectedFirst() {
        List<BlockPos> path = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 80, 0),
                new BlockPos(3, 80, 0));
        assertSame("adjacent-step", path);
    }

    @Test
    void longWindowViolationMatchesReference() {
        List<BlockPos> path = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            path.add(new BlockPos(i, 64 + (i >= 20 ? 14 : 0), 0));
        }
        assertSame("long-window", path);
    }

    @Test
    void gentleSlopeHasNoViolation() {
        List<BlockPos> path = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            path.add(new BlockPos(i, 64 + i / 8, 0));
        }
        assertSame("gentle-slope", path);
    }

    @Test
    void shortPathAndEmptyPathAreSafe() {
        assertNull(AccurateGradeRepairPlanner.findFirstViolation(null));
        assertNull(AccurateGradeRepairPlanner.findFirstViolation(List.of()));
        assertNull(AccurateGradeRepairPlanner.findFirstViolation(List.of(new BlockPos(0, 64, 0))));
        assertNull(AccurateGradeRepairPlanner.findFirstViolation(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0))));
    }

    @Test
    void randomPathsMatchReferenceExactly() {
        Random random = new Random(20260909L);
        for (int round = 0; round < 400; round++) {
            int size = 3 + random.nextInt(80);
            List<BlockPos> path = new ArrayList<>(size);
            int x = 0;
            int z = 0;
            int y = 64 + random.nextInt(30);
            for (int i = 0; i < size; i++) {
                x += random.nextInt(3);
                z += random.nextInt(3);
                y += random.nextInt(21) - 10;
                path.add(new BlockPos(x, y, z));
            }
            assertSame("random-round-" + round, path);
        }
    }

    @Test
    void manySmallStepsMatchReference() {
        // 大量 1 格步长：窗口内元素多，专测滑窗边界与队列淘汰。
        Random random = new Random(424242L);
        for (int round = 0; round < 200; round++) {
            int size = 20 + random.nextInt(60);
            List<BlockPos> path = new ArrayList<>(size);
            int y = 70 + random.nextInt(10);
            for (int i = 0; i < size; i++) {
                y += random.nextInt(5) - 2;
                path.add(new BlockPos(i, y, 0));
            }
            assertSame("small-steps-" + round, path);
        }
    }
}
