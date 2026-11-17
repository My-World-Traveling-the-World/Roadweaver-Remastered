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
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.JavaDependencyScanTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.architecture.ChineseLogGuardTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.FileStorageLogTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.planning.ReadinessOrderingReuseTest"),
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
                        // 区域状态转换与统计独立于世界读写，防止计数优化绕过行为守卫。
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessRegionTableTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessIndexStateTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessCoverageStoreTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessCoverageFaultTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessPersistenceCompletionTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.ReadinessPersistenceWiringTest"),
                        // 真正跨瓦片对拍场值与落选边；新类必须显式登记，避免守卫从未运行。
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.bridge.TiledTerrainFieldTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.TiledTerrainFmmTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.impl.PipelinePlannerSamplingTest"),
                        DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathfinding.sampling.TiledSamplerIntegrationTest"),
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
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.JunctionOrchestrationTest"),
                        // 纯查询从路口编排拆出后仍保留跨度优先级，并且只建立一次站号索引。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.JunctionSpanIndexTest"),
                        // 批 4 补覆盖：路口合并核心判据（单 junction 选择 / 代价口径 / 候选预筛）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.junction.JunctionSelectionCoreTest"),
                        // 批 7 补覆盖：最终校验器的 5 个从未被测的失败分支
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidatorFailureModesTest"),
                        // P0-1 回归：单条道路读失败不许把索引永久改写
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.chunk.RoadChunkStoreReadFaultTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.RoadConstructionLedgerTest"),
                        // 批 5 量尺：perpendicularDistance / reinstateUntilDense 的契约 + 锯齿保真度实测
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.PostprocessMeasuringStickTest"),
                        // 批 3 补覆盖：桥模板纵向映射 mapTemplateX（含一个已修历史缺陷的守卫）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeTemplateMappingTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeTemplatePlacementTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.validation.FinalConstructionFootprintTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.validation.VolumeKindContractTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.VolumePlannerVisitTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.decoration.system.RoadsideLampSearchTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.CrossSectionFlattenTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.pathcalc.GeometryDriftObserverTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.pathcalc.GeometryProtectionProductTest"),
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
                        // 直接跑生产 BAND，防止适配器再次忘记读取走廊约束。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.BandPathfinderCorridorTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservationTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.BandPathfinderWaterCostTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.bridge.AbObservingPathfinderTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.loading.LoadingOverlayTextTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.map.MapSearchControllerTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.map.MapRoutePreloadEstimatorTest"),
                        // 地图折线对照独立于整合客户端，必须在无渲染的回归入口登记。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.client.map.MapRoadPolylineCacheTest"),
                        // 非阻塞调度必须显式登记，避免只编译未运行。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.route.RoutePreloadWorkTest"),
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
                                "net.shiroha233.roadweaver.postprocess.PathCurveRasterParityTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.PathWidthRasterizerContractTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.PostProcessedRoadProductTest"),
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
                        // 直接检查生产负载策略，防止 API 注册成功但消费端仍只认两个旧来源。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.runtime.RuntimeLoadSignalsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.grade.RoadCutFillAsymmetryTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.TunnelRunLengthCapTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.tuning.TerrainArchetypeTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicyTest"),
                        // 加载唤醒和轮询上限必须和生产选队共用同一套离线守卫。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.generation.DeferredChunkQueueTest"),
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
                        // 真实认领、取消和关闭必须能区分成功与丢失结果，不能只守缓存容量。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateChunkBatchTest"),
                        // 网格与逐列必须用混合请求验证，单独测两种缓存会漏掉共享认领错误。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateColumnBatchTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateTileClaimsTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSamplerLifecycleTest"),
                        // 只读查询仍可能读盘发布，关闭交错必须与采样入口同样受保护。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplerCacheLifecycleTest"),
                        // 旧未命中快照必须在相关发布后重查，冷请求不能无条件增加全扫描。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateSampleRecheckTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathfinding.sampling.AccurateSamplePublicationsTest"),
                        // 真实临时文件与共享锁覆盖缓存故障，避免 Fake 保存成功掩盖落盘失败。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.files.AccurateSampleStoreFileTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.persistence.files.AccurateSampleStoreCloseTest"),
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
                        // 双扫描合一后必须保持原几何判据，并验证实际点对访问次数。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeDistanceScanTest"),
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
                        // 回填计划只查一次的回归走同一生产编排。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.LoadedChunkReplayTest"),
                        // 密度光栅裁边仍要保留路肩、跨路禁切填与重叠优先级。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.worldgen.road.RoadChunkDensityRasterTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.runtime.ThreadPoolCapacityDebounceTest"),
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.compat.GreatWallReadinessApiTest"),
                        // 预热门必须在首次调度失败时结案，离线时钟直接运行生产入口。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.compat.PrewarmGateWorkTest"),
                        // 归属道路：缺长城仍等河流，有长城则按河流、长城顺序消费。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.compat.RoadStartupGateTest"),
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
                        // 封水实际落块与旧循环对拍，避免保护或洞口策略在优化中漂移。
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.tunnel.TunnelBoreSealerTest"),
                        // 0.9.066：相邻 BRIDGE 跨度甲板连续性（两段桥之间的台阶）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.pathlogic.bridge.BridgeSpanContinuityTest"),
                        // 0.9.067：急弯冻结（发卡弯顶点不参与 relax 平滑）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SharpTurnFreezeTest"),
                        // 0.9.068：急弯段用直线代替 Catmull-Rom（发卡弯不被样条外冲）
                        DiscoverySelectors.selectClass(
                                "net.shiroha233.roadweaver.postprocess.SharpTurnSplineTest"),
                // 求解异常必须保留与正常路径相同的端点、桥面及覆土约束。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.core.TargetProfileSolverTest"),
                // 观测必须连到真实候选和终态结果，不能只验证格式器。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.metrics.RoadBuildEvidenceTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.RouteEvidenceWiringTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pipeline.ConnectionEvidenceTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.core.TargetTerrainInputsTest"),
                // 保留模式必须直接跑编排；另用窄语法树守住世界端口接线。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.core.LegacyTargetProfileTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.core.LegacyTargetProfileWiringTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotActivationTest"),
                // 村庄端点过滤必须保留外来道路、阈值边界及整轮撤销原因。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.structures.VillageEndpointFilterTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathlogic.structures.StructureBoxIndexTest"),
                // 直接验证生产采样入口，避免只测执行器却漏掉实际三通道产物。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathfinding.sampling.CpuAccurateHeightBackendTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.pathfinding.sampling.RuntimeCpuSamplingWorkTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.planning.terrain.CoarsePathTasksTest"),
                // 停服终态必须随全量执行，避免只在定向任务中通过。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.runtime.TaskLifecycleTest"),
                // 分配前校验必须随全量运行，避免大范围异常被整型溢出伪装成小请求。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.GridLayoutTest"),
                // 压缩状态必须与冻结的整场版本对拍场值和终态拓扑。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.FmmWorkingSetTest"),
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.DirectionalUpwindCandidateTest"),
                // 方向候选只做离线实验，缓存等价与解析面细化趋势单独约束。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.DirectionalCandidateStudyTest"),
                // 分页场必须同时证明近目标少分配、远端不断路和稠密访问数值不漂移。
                DiscoverySelectors.selectClass("net.shiroha233.roadweaver.metric.FmmPagedWorkspaceTest"))
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
