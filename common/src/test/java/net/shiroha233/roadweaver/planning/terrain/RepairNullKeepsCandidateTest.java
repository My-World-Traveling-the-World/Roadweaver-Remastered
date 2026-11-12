package net.shiroha233.roadweaver.planning.terrain;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.062（④）：局部搜索返回 {@code null} 时保留原候选。
 *
 * <p><b>病灶。</b>{@code AccurateGradeRepairPlanner} 里 {@code candidate = findLocalRoute(...)} 把
 * {@code null} 直接盖上去 —— 调用前那条候选被静默吞掉。随后只剩 {@code forceSmoothHeightWindow}
 * 兜底，兜不住就整窗 {@code return null}：坡度/结构冲突原样留在路上，日志里一个字都没有。
 * 表现是"这条路就是没修好"，而排查时连"试过哪几档、哪一档没找到路"都查不到。</p>
 *
 * <p><b>本用例钉三件事：</b>①{@code null} 时返回的是<b>同一个对象</b>（不是复制、不是空列表），
 * 逐元素 {@code assertSame}；②计数（日志）+1；③真的找到路时照旧替换、且不计数
 * —— 不许把这条保护做成"永远不会被覆盖"。</p>
 */
final class RepairNullKeepsCandidateTest {

    /** 一条 5 站、每站间隔 8 格的候选走廊。 */
    private static List<BlockPos> candidate() {
        List<BlockPos> path = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            path.add(new BlockPos(i * 8, 64 + i, 0));
        }
        return path;
    }

    @Test
    void nullSearchKeepsThePreviousCandidateBitForBit() {
        List<BlockPos> previous = candidate();
        long before = AccurateGradeRepairPlanner.repairNullCount();

        List<BlockPos> kept = AccurateGradeRepairPlanner.keepCandidateOnNull(
                previous, null, "grade:12-40", "surface");

        assertSame(previous, kept,
                "必须原样返回同一个对象 —— 复制一份或返回空列表都算把原路吞掉了");
        assertEquals(previous.size(), kept.size(), "站数必须一模一样");
        for (int i = 0; i < previous.size(); i++) {
            assertSame(previous.get(i), kept.get(i), "第 " + i + " 站必须是同一个 BlockPos 实例");
        }
        assertEquals(before + 1, AccurateGradeRepairPlanner.repairNullCount(),
                "[REPAIR-NULL] 计数必须 +1（这是唯一能被验收看到的证据）");
    }

    @Test
    void tooShortSearchCountsAsNoPath() {
        List<BlockPos> previous = candidate();
        long before = AccurateGradeRepairPlanner.repairNullCount();

        List<BlockPos> onlyOne = List.of(new BlockPos(0, 64, 0));
        assertSame(previous, AccurateGradeRepairPlanner.keepCandidateOnNull(
                previous, onlyOne, "grade:40-60", "tunnel"), "单点不算找到路");
        List<BlockPos> empty = List.of();
        assertSame(previous, AccurateGradeRepairPlanner.keepCandidateOnNull(
                previous, empty, "grade:40-60", "tunnel"), "空列表不算找到路");

        assertEquals(before + 2, AccurateGradeRepairPlanner.repairNullCount());
    }

    @Test
    void acceptedCandidateReplacesThePreviousOneWithoutCounting() {
        List<BlockPos> previous = candidate();
        List<BlockPos> searched = candidate();
        long before = AccurateGradeRepairPlanner.repairNullCount();

        assertSame(searched, AccurateGradeRepairPlanner.keepCandidateOnNull(
                previous, searched, "grade:12-40", "surface"),
                "真的找到路时必须用搜索结果替换（否则整窗永远修不好）");
        assertEquals(before, AccurateGradeRepairPlanner.repairNullCount(),
                "找到路时不该记 [REPAIR-NULL]");
    }

    @Test
    void switchOffRestoresTheOldSwallowingBehaviour() {
        String key = net.shiroha233.roadweaver.core.constants.RoadConstants.KEEP_NULL_CANDIDATE_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertTrue(AccurateGradeRepairPlanner.keepNullCandidateEnabled(), "默认开");
            System.setProperty(key, "false");
            assertFalse(AccurateGradeRepairPlanner.keepNullCandidateEnabled(), "false = 回到旧行为");

            List<BlockPos> candidate = candidate();
            long before = AccurateGradeRepairPlanner.repairNullCount();
            assertSame(null, AccurateGradeRepairPlanner.keepCandidateOnNull(
                            candidate, null, "grade:0-8", "surface"),
                    "关掉之后必须回到「搜索结果直接覆盖」—— 也就是 null 覆盖掉原候选");
            assertEquals(before, AccurateGradeRepairPlanner.repairNullCount(),
                    "关掉之后也不许再计数/打日志（逐位回到 0.9.061）");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void nothingToKeepStillReturnsNull() {
        long before = AccurateGradeRepairPlanner.repairNullCount();
        assertSame(null, AccurateGradeRepairPlanner.keepCandidateOnNull(
                null, null, "lake:8-24", "lake"), "本来就没有候选时只能返回 null，不许凭空造一条路");
        assertEquals(before + 1, AccurateGradeRepairPlanner.repairNullCount());
    }
}
