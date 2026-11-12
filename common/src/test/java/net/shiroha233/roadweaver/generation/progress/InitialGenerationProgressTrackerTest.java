package net.shiroha233.roadweaver.generation.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InitialGenerationProgressTrackerTest {
    /**
     * 0.8.355：此用例原先叫 {@code overallProgressDoesNotKeepAStalePreviousStageMaximum}，
     * 断言「从 POST_PROCESSING 切到 COARSE_SAMPLING 后总进度从 99 掉回 17」——
     * 即**把进度条回退固化成了预期行为**。真机反馈（玩家：「大阶段进度统计好像有问题，
     * 有时候进度条会回退」）证明这是缺陷而非特性：{@link InitialGenerationStage} 的
     * {@code basePercent} 本身非单调（EXACT_PATHING=45 → COARSE_SAMPLING=15，
     * 而 COARSE_SAMPLING 在真机上要跑 40 分钟），阶段切换必然导致暴跌。
     * 现改为断言**单调不回退**：切换后保持已达成的 99，直到真实进度追上。
     */
    @Test
    void overallProgressNeverGoesBackwardsWhenStagesSwitch() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.POST_PROCESSING, "post");
        InitialGenerationProgressTracker.setConstructionChunkPlan(1, "post");
        InitialGenerationProgressTracker.recordConstructionChunksDone(1);
        assertEquals(99, InitialGenerationProgressTracker.snapshot(true).overallPercent());

        // 切到一个 basePercent 更低的阶段：阶段百分比确实回到 7，但总进度不得回退。
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.COARSE_SAMPLING, "coarse");
        InitialGenerationProgressTracker.setTilePlan(100, 0L, "coarse");
        for (int i = 0; i < 7; i++) {
            InitialGenerationProgressTracker.recordTileLoaded();
        }

        assertEquals(7, InitialGenerationProgressTracker.snapshot(true).stagePercent());
        assertEquals(99, InitialGenerationProgressTracker.snapshot(true).overallPercent());
    }

    /**
     * 0.8.355：修复前，阶段刚进入时 {@code percent()} 因 {@code total<=0} 返回 100，
     * 总进度先冲到该阶段满值，等真实总数统计出来再掉回 —— 这是「回退」的第二个来源。
     */
    @Test
    void overallProgressDoesNotDropAfterStageTotalBecomesKnown() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.EXACT_SAMPLING, "exact");
        // 总数未知时的瞬时读数。
        int beforeTotalKnown = InitialGenerationProgressTracker.snapshot(true).overallPercent();
        // 真实总数到位、尚未完成任何一条。
        InitialGenerationProgressTracker.setExactSamplingPlan(1000L, "exact");
        int afterTotalKnown = InitialGenerationProgressTracker.snapshot(true).overallPercent();

        assertTrue(afterTotalKnown >= beforeTotalKnown,
                "overall progress must not drop when the stage total becomes known: "
                        + beforeTotalKnown + " -> " + afterTotalKnown);
    }

    /**
     * 0.8.422：阶段刚进入、总数尚未公布时不得报满值。
     *
     * <p>修复前 {@code percent(total<=0)} 返回 100，配合峰值锁存会让进度条在进入
     * COARSE_SAMPLING 的瞬间跳到该阶段终点并长时间不动 —— 这正是真机里
     * 「加载好长时间然后一下蹦满」的机制。</p>
     */
    @Test
    void aStageWithNoPublishedTotalDoesNotClaimToBeFinished() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.COARSE_SAMPLING, "coarse");

        var unknown = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(0, unknown.stagePercent(), "总数未知时阶段内进度必须是 0，而不是 100");
        assertEquals(InitialGenerationStage.COARSE_SAMPLING.basePercent(), unknown.overallPercent(),
                "总数未知时总进度只能停在该阶段起点，不得跳到终点");

        // 总数公布后，真的没活干才报 100（旧行为保留）。
        InitialGenerationProgressTracker.setTilePlan(0, 0L, "coarse");
        assertEquals(100, InitialGenerationProgressTracker.snapshot(true).stagePercent(),
                "总数已公布且为 0 = 无活可干 = 已完成");
    }

    /** 跨任务重置时峰值必须清零，否则上一局的高进度会污染下一局。 */
    @Test
    void peakIsClearedByReset() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.POST_PROCESSING, "post");
        InitialGenerationProgressTracker.setConstructionChunkPlan(1, "post");
        InitialGenerationProgressTracker.recordConstructionChunksDone(1);
        assertEquals(99, InitialGenerationProgressTracker.snapshot(true).overallPercent());

        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.PLANNING, "planning");
        assertEquals(0, InitialGenerationProgressTracker.snapshot(true).overallPercent());
    }

    @Test
    void routeStageIncludesActiveRouteWorkAndRetryStates() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.enterStage(InitialGenerationStage.ROAD_GENERATION, "roads");
        InitialGenerationProgressTracker.updateConnections(5, 1, 0, 0);
        InitialGenerationProgressTracker.updateRetryStates(2, 0);
        InitialGenerationProgressTracker.beginRoadTask("road");
        InitialGenerationProgressTracker.advanceCurrentRoadTask(500, "detour");

        var snapshot = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(2, snapshot.connectionsLocalSampling());
        assertEquals(0, snapshot.connectionsFallbackCandidates());
        assertEquals(10, snapshot.stagePercent());

        InitialGenerationProgressTracker.finishRoadTask();
        InitialGenerationProgressTracker.updateRetryStates(0, 2);
        snapshot = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(2, snapshot.connectionsFallbackCandidates());
    }

    @Test
    void routePhaseCountersArePublishedIndependently() {
        InitialGenerationProgressTracker.reset();
        InitialGenerationProgressTracker.beginRoutePhase(
                InitialGenerationProgressTracker.RoutePhase.ORIGINAL, 3);
        InitialGenerationProgressTracker.routePhaseWorkerStarted();
        InitialGenerationProgressTracker.routePhaseAttemptCompleted(true);
        InitialGenerationProgressTracker.routePhaseWorkerFinished();

        InitialGenerationProgressTracker.beginRoutePhase(
                InitialGenerationProgressTracker.RoutePhase.LOCAL_REPAIR, 2);
        InitialGenerationProgressTracker.routePhaseWorkerStarted();
        InitialGenerationProgressTracker.routePhaseAttemptCompleted(false);
        InitialGenerationProgressTracker.routePhaseWorkerFinished();

        var snapshot = InitialGenerationProgressTracker.snapshot(true);
        assertEquals(3, snapshot.originalRoutes().total());
        assertEquals(1, snapshot.originalRoutes().done());
        assertEquals(2, snapshot.localRepairRoutes().total());
        assertEquals(1, snapshot.localRepairRoutes().failed());
        assertEquals(0, snapshot.candidateRoutes().total());
    }
}
