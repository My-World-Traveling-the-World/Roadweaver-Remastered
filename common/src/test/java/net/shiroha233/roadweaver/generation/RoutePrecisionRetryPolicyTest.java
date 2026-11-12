package net.shiroha233.roadweaver.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoutePrecisionRetryPolicyTest {
    @Test
    void usesOnlyConfiguredStep() {
        assertEquals(List.of(8), RoutePrecisionRetryPolicy.fullRouteSteps(8));
        assertEquals(List.of(16), RoutePrecisionRetryPolicy.fullRouteSteps(16));
        assertEquals(List.of(3), RoutePrecisionRetryPolicy.fullRouteSteps(3));
        assertEquals(List.of(2), RoutePrecisionRetryPolicy.fullRouteSteps(2));
        assertEquals(List.of(1), RoutePrecisionRetryPolicy.fullRouteSteps(1));
    }

    @Test
    void clampsUnsupportedConfiguredValues() {
        assertEquals(List.of(1), RoutePrecisionRetryPolicy.fullRouteSteps(0));
        assertEquals(List.of(128), RoutePrecisionRetryPolicy.fullRouteSteps(999));
    }

    @Test
    void allowsLocalDetoursAtEverySupportedPrecision() {
        assertTrue(RoutePrecisionRetryPolicy.allowsLocalDetour(8));
        assertTrue(RoutePrecisionRetryPolicy.allowsLocalDetour(1));
        assertEquals(8, RoutePrecisionRetryPolicy.finestStep(8));
        assertEquals(1, RoutePrecisionRetryPolicy.finestStep(1));
        // 2026-09-08 用户要求把建筑绕行上限 200 → 400（村庄成片时 200 格绕不开）。
        // 注意：这是原先钉死绕行预算的红线断言，改动会改变已有路线的绕行结果。
        assertEquals(List.of(50, 100, 150, 200, 250, 300, 350, 400), java.util.Arrays.stream(
                RoutePrecisionRetryPolicy.structureDetourBlocks()).boxed().toList());
        assertEquals(List.of(10, 20, 50, 100), java.util.Arrays.stream(
                RoutePrecisionRetryPolicy.lakeDetourBlocks()).boxed().toList());
    }

    @Test
    void candidatesUseConfiguredStep() {
        assertEquals(List.of(8), RoutePrecisionRetryPolicy.candidateBatchSteps(8));
        assertEquals(List.of(4), RoutePrecisionRetryPolicy.candidateBatchSteps(4));
    }

    @Test
    void localRepairIsPublishedAsItsOwnRetryWave() {
        // Local conflict repair uses a 2-block sampling level; it patches only
        // the recorded conflict windows, so the finest 1-block pass is skipped.
        assertEquals(List.of(2), RoutePrecisionRetryPolicy.localRepairBatchSteps(8));
        assertEquals(List.of(2), RoutePrecisionRetryPolicy.localRepairBatchSteps(4));
        assertEquals(List.of(2), RoutePrecisionRetryPolicy.localRepairBatchSteps(2));
        assertEquals(List.of(2), RoutePrecisionRetryPolicy.localRepairBatchSteps(1));
    }

    @Test
    void nonEndpointConflictStopsAtTwoIdenticalCenters() {
        // 0.8.340 行为不变：非端点逼近只要连续两级同中心就停。
        assertFalse(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(1, false));
        assertTrue(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(2, false));
        assertTrue(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(3, false));
    }

    @Test
    void endpointApproachToleratesUpToFourIdenticalCenters() {
        // 2026-11-12 真机：端点逼近曾把 50→800 十六级梯子全跑满（271 秒），
        // 而每级冲突中心完全相同 ⇒ 几何死局。上限 4 级。
        assertFalse(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(1, true));
        assertFalse(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(2, true));
        assertFalse(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(3, true));
        assertTrue(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(4, true));
        assertTrue(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(5, true));
        assertEquals(4, RoutePrecisionRetryPolicy.ENDPOINT_IDENTICAL_CONFLICT_LIMIT);
    }

    @Test
    void endpointApproachNegativeControlStillEscalatesFirstThree() {
        // 负控：合法的「沿结构边界盘旋进村庄」前三级必须继续升级，不能被提前砍掉。
        for (int streak = 1; streak <= 3; streak++) {
            assertFalse(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(streak, true),
                    "端点逼近第 " + streak + " 级不应停止");
        }
    }

    @Test
    void nonEndpointNegativeControlStillStopsAtTwo() {
        // 负控：非端点逼近不能被放宽到 4 级，否则 0.8.340 的早停保护失效。
        assertTrue(RoutePrecisionRetryPolicy.shouldStopIdenticalConflictStreak(2, false),
                "非端点第 2 级仍必须停止");
    }
}
