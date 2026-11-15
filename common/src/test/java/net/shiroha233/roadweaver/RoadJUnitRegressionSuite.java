package net.shiroha233.roadweaver;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import java.util.ArrayList;
import java.util.List;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs JUnit checks in the same Minecraft-aware runtime used by model regressions. */
public final class RoadJUnitRegressionSuite {
    private RoadJUnitRegressionSuite() {}

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(
                        // 架构守卫（2026-11-12 重构起）：钉死层依赖单向、类规模、
                        // 中文注释规范、文档与代码同源。ratchet 机制 —— 只许降不许升。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.ArchitectureGuardTest"),
                        // 配置元数据表 ↔ 配置类双向一致性（M1 起）：
                        // 表里的键必须在类里有字段、类里的字段必须在表里有条目，
                        // 且类型与默认值逐项相等。已废弃/未接线的键必须写明原因。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.meta.ConfigKeyTableConsistencyTest"),
                        // 度量层数学性质（M2 起）：正交/对角代价对称性、
                        // 方向性坡度、Fast Marching 逼近欧氏距离、绕障不穿障碍。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.metric.FinslerMetricTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.metric.ObstaclePotentialTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.metric.GridTerrainFieldTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.SampledTerrainFieldTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.topology.MinimumSpanningTreeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.band.ElasticBandSolverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.band.ElasticBandObstacleProjectionTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pipeline.RoadPipelineTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.AvoidanceObstacleFieldTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pipeline.ab.AbRunnerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.SolverModeWiringTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.LegacyLedgerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.path.SharedEndpointPathReuseTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.RegressionRegistrationTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.GradleTestEntryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.ConfigStatusHonestyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.ConfigUiHonestyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.metric.GridTerrainFieldStepTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.metric.ArrivalTimeFieldTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.AvoidanceObstacleFieldStepTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.DeadCodeGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.MaintainabilityReportTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.PostprocessLedgerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.S5MethodMapTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.UiFeatureGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.BuildFrameTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.SingleSourceOfTruthTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.spec.RoadSpecTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSolverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSourceTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ModeDpSolverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.CostModelPreferCutTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileFallbackTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileFallbackConstraintTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOpsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ModeDpBridgeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.GroundBanSourcesTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ExistingRoadProbeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ModeDpMergeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceBlindQueryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.FallbackBoxQueryCountTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerUnresolvedCountTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.structures.precompute.StructureInjectorDropCountTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.search.StructureVerificationObservabilityTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.DutyHeaderGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.RoadBoundsGapTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.AlignJunctionProfileTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.BraidRunExtractionTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.SelfTestRunnerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.ObstacleScopeAndNew8Test"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelBelowWaterBedTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.CorridorGeometryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.CulvertGeometryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.SpanWritebackTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.SourceLineEndingGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.BridgeDeckYConsistencyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.GuardPreconditionTest"),
                        // 批 4 补覆盖：并行走廊预筛与连接还原的纯判定面（386 行原本零覆盖）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.ParallelOverlapResolverTest"),
                        // 批 4 补覆盖：842 行主入口 connectPlannedBatch 的早退契约（探边界）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.ExistingRoadJunctionBatchEntryTest"),
                        // 批 5 量尺：perpendicularDistance / reinstateUntilDense 的契约 + 锯齿保真度实测
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.PostprocessMeasuringStickTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.validation.VolumeKindContractTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.VolumePlannerVisitTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.CrossSectionFlattenTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.pathcalc.GeometryDriftObserverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.SourceFileSizeGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileAbStudyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.profile.ProfileTerrainReplayTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.StridedTerrainFieldTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.BandPathfinderEdgeSamplingTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.AbObservingPathfinderTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.loading.LoadingOverlayTextTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.map.MapSearchControllerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.map.MapRoutePreloadEstimatorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.sub.PerformanceSchedulingConfigTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.sub.PathfindingCostConfigTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.sub.PlanningConfigTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.ExistingRoadAttachmentTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.EndpointReuseStatusTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.RoadOverlapDetectionTest"),
                                                                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.VillageEndpointMergeTest"),
                                                                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.tunnel.TunnelStationIndexFallbackTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.PathPostProcessorCharacterizationTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfileCharacterizationTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.GradeConstantSingleSourceTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.api.ExternalReflectionContractTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.apiimpl.ApiModulePurityTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.apiimpl.ApiLifecycleAndLoadBridgeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.RoadCutFillAsymmetryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelRunLengthCapTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.tuning.TerrainArchetypeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTrackerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TerrainProfileModeGradeLimitsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.AccurateGradeProfileSolverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.TerrainProfileModeSearchBehaviorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileCacheConcurrencyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseTerrainTileSubmitIntervalBillingTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSamplerCacheTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.AccurateGradeRepairPlannerFindViolationTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.TerrainProfileModeAUTOBehaviorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.path.PlannedPathCacheBatchWriteTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.RouteLevelAutoTendencyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.RoadSnapServiceTailTrimTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.DensifyControlPointsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SimplifyDensityTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.RelaxCapInvariantTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SawtoothMetricTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.impl.HeadingContinuityCostTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.RoadBraidDetectorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareDetectorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareAbsorberTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.validation.ProtectedGapHeightContinuityTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.UnknownEndpointHeightConsistencyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.RoadSnapServiceReuseExtendTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.StationGapDensifyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.files.StructureFileStorageSnapshotConcurrencyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.files.StructureFileStorageQueryRectTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.files.InitialGenerationCheckpointStoreTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.opencl.GpuMemoryProbeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.worldgen.road.RoadChunkPlanCompilerReuseOwnershipTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.runtime.ThreadPoolCapacityDebounceTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.compat.GreatWallReadinessApiTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.tunnel.RoadDeckAndTunnelGeometryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.RoadGradeArcLengthTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.CulvertPlannerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.metrics.RoadQualityMetricsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.sub.RoadAppearanceWaterAndSlopeConfigTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.WaterCrossingGateTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.config.ConfigKnobHonestyTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.ProtectedColumnActionTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.WaterTierAlignmentTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareServiceStatsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeShareEndpointFallbackTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckGeometryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.CrossingNegotiatorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.CrossingSharedDeckTest"),
                        // 0.9.059：拆解快照耗时（①）+ 自适应候选范围等价性（②）+ 长度界收紧（③）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.structures.PrepareTimingSplitTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.AdaptiveCandidateBoundsTest"),
                        // 0.9.060：候选余量相对化 + 预热规模保护（256/512/1024/2048 区块四档）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.AdaptiveCandidateBoundsScaleTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.LengthCapRegressionTest"),
                        // 0.9.061：最大坡度三档（tight/normal/loose + maxRise 纯函数 + 窗口约束）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.core.constants.GradeCapTest"),
                        // 0.9.061：盘山路专用度量（发卡弯 / 半径 / 腿长 / 坡度分位 / 斜着硬爬长度）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.metrics.SwitchbackMetricsTest"),
                        // 0.9.062：①长度界策略感知（steep→2.6 / 缓地形 1.6）+ ②坡罚按长度收
                        //        + ③默认档 loose + ④findLocalRoute 返回 null 时保留原候选
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.SteepLengthCapTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.core.constants.GradeCapDefaultLooseTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.terrain.RepairNullKeepsCandidateTest"),
                        // 0.9.063：②隧道判据重写（下包络突起度纯函数 / 窄脊必须判出）
                        //        + ③why 四个分支 + elected 各一条
                        //   （①坡罚刻度重标定的 GradePenaltyCalibrationTest 随
                        //     旧 A* 系寻路器于 2026-11-13 一并删除）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelProminencePureFunctionTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelCandidateWhyBranchTest"),
                        // 0.9.063：管线级证据 —— 生产入口 refineTunnelSpans 真的会吐出 TUNNEL span
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelPipelineIntegrationTest"),
                        // 0.9.064：洞口段长改按坡度推（平缓→短 / 陡→长 / 上限 32 站 / 无地形退回 14 带 reason）
                        //        + 0.9.063 管线用例真实产出的 16 站隧道作负控（openCutOnly 必须为 false）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPortalAdvanceTest"),
                        // 0.9.065：洞身站禁止向上清空（洞身 / 普通路段 / 洞口明挖段三档 + [TUNNEL-CLEAR] 台账）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.tunnel.TunnelBodyClearScopeTest"),
                        // 0.9.066：相邻 BRIDGE 跨度甲板连续性（两段桥之间的台阶）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeSpanContinuityTest"),
                        // 0.9.067：急弯冻结（发卡弯顶点不参与 relax 平滑）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SharpTurnFreezeTest"),
                        // 0.9.068：急弯段用直线代替 Catmull-Rom（发卡弯不被样条外冲）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SharpTurnSplineTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.structures.StructureBoxIndexTest"))
                .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        // ★ 2026-11-15：收集「被 Assumptions 静默跳过」的用例。
        //   JUnit 里被跳过的测试算「通过」，而本套件原先只打印 PASS tests=N ——
        //   architecture/ 下 10 个守卫一旦根解析失效就会集体静默跳过，构建仍然全绿。
        //   这里把跳过者点名，并让「守卫跳过」直接判失败（见下面的断言）。
        List<String> skipped = new ArrayList<>();
        TestExecutionListener skipCollector = new TestExecutionListener() {
            @Override
            public void executionSkipped(TestIdentifier id, String reason) {
                if (!id.isTest()) return;
                String cls = id.getSource()
                        .map(src -> src.toString())
                        .orElse(id.getDisplayName());
                skipped.add(cls + "   ← " + reason);
            }
        };
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summary, skipCollector);
        launcher.execute(request);
        if (summary.getSummary().getTotalFailureCount() > 0) {
            summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.err, true));
            throw new AssertionError("JUnit regressions failed: " + summary.getSummary().getTotalFailureCount());
        }
        long succeeded = summary.getSummary().getTestsSucceededCount();
        long failed = summary.getSummary().getTestsFailedCount();
        long aborted = summary.getSummary().getTestsAbortedCount();
        System.out.println("RoadJUnitRegressionSuite: PASS tests=" + succeeded
                + " skipped=" + skipped.size()
                + " aborted=" + aborted
                + " failed=" + failed);
        for (String line : skipped) {
            System.out.println("  [SKIPPED] " + line);
        }
        // ★ 守卫绝不允许静默跳过。
        //   为什么单独对 architecture 下这条：那 10 个守卫用
        //   `Assumptions.assumeTrue(根 != null)`，跳过时 JUnit 记「通过」，
        //   于是「守卫根本没跑」和「守卫跑过且通过」在输出上完全同形。
        //   GuardPreconditionTest 已经断言「根必须能解析」，这里再加一道：
        //   即使将来出现别的跳过原因（守卫被 @Disabled、条件变了），也会立刻红。
        List<String> skippedGuards = new ArrayList<>();
        for (String line : skipped) {
            if (line.contains(".architecture.")) skippedGuards.add(line);
        }
        if (!skippedGuards.isEmpty()) {
            throw new AssertionError("架构守卫被静默跳过（守卫必须永远真跑）：\n  "
                    + String.join("\n  ", skippedGuards));
        }
    }
}
