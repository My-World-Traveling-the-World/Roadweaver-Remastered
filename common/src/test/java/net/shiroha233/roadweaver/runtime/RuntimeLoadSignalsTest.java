package net.shiroha233.roadweaver.runtime;

import net.shiroha233.roadweaver.api.outgoing.LoadKind;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.RuntimeSignals;
import net.shiroha233.roadweaver.config.sub.PerformanceConfig;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 直接走生产决策，防止只测注册表而漏掉“接口已注册、实际预算没消费”。 */
class RuntimeLoadSignalsTest {
    private List<RoadLoadSignals> savedSources;
    private int savedGameplayCap;
    private int savedSharedCap;
    private boolean savedPreflight;
    private boolean savedExclusive;
    private boolean savedMap;
    private boolean savedGui;

    @BeforeEach
    void isolate() throws Exception {
        assertFalse(InitialGenManager.isActive(), "离线夹具必须处于普通游玩状态");
        savedSources = RoadLoadSignalsRegistry.sources();
        clearSources();
        PerformanceConfig config = ConfigService.get().performance();
        savedGameplayCap = config.gameplayThreadCap();
        savedSharedCap = config.sharedWorkerThreads();
        config.setGameplayThreadCap(0);
        config.setSharedWorkerThreads(0);
        savedPreflight = RuntimeLoadPolicy.isChunkyPreflightActive();
        savedExclusive = RuntimeLoadPolicy.isChunkyExclusiveActive();
        savedMap = RuntimeLoadPolicy.isMapScreenOpen();
        savedGui = RuntimeLoadPolicy.isGuiScreenOpen();
        RuntimeLoadPolicy.setChunkyPreflightActive(false);
        RuntimeLoadPolicy.setChunkyExclusiveActive(false);
        RuntimeLoadPolicy.setMapScreenOpen(false);
        RuntimeLoadPolicy.setGuiScreenOpen(false);
        RuntimePlayerActivity.reset();
        expirePauseCache();
    }

    @AfterEach
    void restore() throws Exception {
        clearSources();
        savedSources.forEach(RoadLoadSignalsRegistry::register);
        ConfigService.get().performance().setGameplayThreadCap(savedGameplayCap);
        ConfigService.get().performance().setSharedWorkerThreads(savedSharedCap);
        RuntimeLoadPolicy.setChunkyPreflightActive(savedPreflight);
        RuntimeLoadPolicy.setChunkyExclusiveActive(savedExclusive);
        RuntimeLoadPolicy.setMapScreenOpen(savedMap);
        RuntimeLoadPolicy.setGuiScreenOpen(savedGui);
        expirePauseCache();
    }

    /** 来源名称不能决定预算是否生效，且必须走道路消费者的上下文重载。 */
    @Test
    void nonLegacyWorkerVotesReachProductionWithRoadContext() {
        List<String> seen = new ArrayList<>();
        register(new Source("other-budget-a") {
            @Override public int recommendedThreadsFor(String consumer, int current) {
                seen.add(consumer + ":" + current);
                return 3;
            }
        });
        register(new Source("other-budget-b") {
            @Override public int recommendedRoadWorkerThreads(int current) { return 1; }
        });
        assertEquals(1, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        assertEquals(List.of("road:" + RuntimeLoadPolicy.maximumRoadWorkerThreads()), seen);
    }

    /** 零 lane 是有效暂停，不能与负值弃权一起丢弃。 */
    @Test
    void nonLegacyLaneVotesReachProductionWithAllContextAndKeepZero() {
        List<List<Integer>> seen = new ArrayList<>();
        register(new Source("other-lanes-a") {
            @Override public int recommendedRoadPlanningLanes(int pending, int ahead, int current) {
                seen.add(List.of(pending, ahead, current));
                return 3;
            }
        });
        register(new Source("other-lanes-b") {
            @Override public int recommendedRoadPlanningLanes(int pending, int ahead, int current) { return 0; }
        });
        assertEquals(0, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 2, 4));
        assertEquals(List.of(List.of(12, 2, 4)), seen);
    }

    /** 来源退出后不能残留限流；无人建议时仍由道路自己的策略决定。 */
    @Test
    void negativeVotesLeaveExistingFallbackUnchangedAndUnregisterReleasesBudget() {
        assertEquals(-1, RuntimeSignals.recommendedWorkers(7));
        assertEquals(-1, RuntimeSignals.recommendedLanes(12, 0, 4));
        int fallback = RuntimeLoadPolicy.recommendedRoadWorkerThreads();
        register(new Source("temporary-budget") {
            @Override public int recommendedRoadWorkerThreads(int current) { return 1; }
        });
        assertEquals(1, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        RoadLoadSignalsRegistry.unregister("temporary-budget");
        assertEquals(fallback, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        register(new Source("abstaining-budget") {});
        assertEquals(-1, RuntimeSignals.recommendedWorkers(7));
        assertEquals(4, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
    }

    /** 多个控制器并存时，允许更多并发的投票不能覆盖明确暂停。 */
    @Test
    void explicitCoordinatorPauseWinsOverAnotherProvidersPositiveLaneVote() {
        register(new Source("tick-pressure") {
            @Override public boolean shouldPauseRoadCoordinator() { return true; }
        });
        register(new Source("lane-advice") {
            @Override public int recommendedRoadPlanningLanes(int p, int a, int c) { return 4; }
        });
        assertTrue(RuntimeSignals.COORDINATOR_PAUSE.invoke());
        assertEquals(0, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
    }

    /** 不仅验证信号值，还检查生产入口的相位、预算、暂停及原恢复窗口。 */
    @Test
    void nonLegacyCpuProtectionControlsPhaseWorkersLanesAndPauseReason() throws Exception {
        register(new Source("other-cruise") {
            @Override public boolean cpuProtection() { return true; }
        });
        assertTrue(RuntimeLoadPolicy.isForegroundPressureActive());
        assertEquals(RuntimeLoadPolicy.RuntimePhase.GAMEPLAY_ACTIVE, RuntimeLoadPolicy.currentPhase());
        assertEquals(2, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        assertEquals(0, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
        expirePauseCache();
        assertEquals(RuntimeLoadPolicy.PauseKind.PLAYER_TRAVEL, RuntimeLoadPolicy.currentPauseKind());
        RoadLoadSignalsRegistry.unregister("other-cruise");
        // 仅强制下次刷新，不清恢复截止；必须保留原先的两秒恢复语义。
        resetClock("NEXT_REFRESH");
        assertEquals(RuntimeLoadPolicy.PauseKind.RECOVERY, RuntimeLoadPolicy.currentPauseKind());
    }

    /** 前台保护应按信号消费，不能要求每个输入控制器假冒 Chunky。 */
    @Test
    void interactionAndAuxiliarySignalsDoNotRequireChunkyIdentity() {
        register(new Source("other-input") {
            @Override public boolean foregroundInteraction() { return true; }
            @Override public boolean shouldPauseAuxiliaryWorldgen() { return true; }
        });
        assertTrue(RuntimeSignals.INTERACTION.invoke());
        assertTrue(RuntimeSignals.AUXILIARY_PAUSE.invoke());
        assertTrue(RuntimeSignals.booleanSignal("old-dispatch-key", "shouldPauseAuxiliaryWorldgen"));
        assertTrue(RuntimeLoadPolicy.isForegroundPressureActive());
        assertEquals(1, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
    }

    /** 接口明确区分移动和交互，聚合不能把两种活动悄悄合并。 */
    @Test
    void movementSignalIsNotSilentlyReinterpretedAsInteraction() {
        register(new Source("movement-only") {
            @Override public boolean foregroundActivity() { return true; }
        });
        assertFalse(RuntimeSignals.INTERACTION.invoke());
        assertFalse(RuntimeLoadPolicy.isForegroundPressureActive());
        assertEquals(RuntimeLoadPolicy.RuntimePhase.GAMEPLAY_IDLE, RuntimeLoadPolicy.currentPhase());
    }

    /** 传送 busy 只是类别状态，不能未经依赖分析便全停可能参与预热的道路。 */
    @Test
    void busyTeleportDoesNotImpersonateChunkyOrInventAnUnrequestedPause() {
        register(new Source("twilight-teleport") {
            @Override public LoadKind kind() { return LoadKind.TELEPORT; }
            @Override public boolean busy() { return true; }
        });
        assertTrue(RoadLoadSignalsRegistry.isBusy(LoadKind.TELEPORT));
        assertFalse(RuntimeLoadPolicy.isChunkyActive());
        assertFalse(RuntimeLoadPolicy.pauseScheduling());
        assertEquals(4, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
    }

    /** 按查询隔离故障，坏建议不应吞掉同源的健康保护或别的来源。 */
    @Test
    void brokenProviderCannotSuppressOtherProvidersOrOtherQueries() {
        register(new Source("broken-advice") {
            @Override public int recommendedRoadWorkerThreads(int current) { throw new IllegalStateException("fixture"); }
            @Override public int recommendedRoadPlanningLanes(int p, int a, int c) { throw new NoClassDefFoundError("fixture"); }
            @Override public boolean cpuProtection() { throw new IllegalStateException("fixture"); }
            @Override public boolean foregroundInteraction() { return true; }
        });
        register(new Source("healthy-advice") {
            @Override public int recommendedRoadWorkerThreads(int current) { return 1; }
            @Override public int recommendedRoadPlanningLanes(int p, int a, int c) { return 0; }
            @Override public boolean cpuProtection() { return true; }
        });
        assertEquals(1, RuntimeSignals.recommendedWorkers(8));
        assertEquals(0, RuntimeSignals.recommendedLanes(12, 0, 4));
        assertTrue(RuntimeSignals.CPU_PROTECTION.invoke());
        assertTrue(RuntimeSignals.INTERACTION.invoke());
    }

    /** 用会抛断言的来源验证真正没有咨询，防止只比较恰巧相同的返回值。 */
    @Test
    void preflightStillBypassesAllExternalPressureQueries() {
        register(new Source("must-not-be-consulted") {
            @Override public int recommendedThreadsFor(String consumer, int current) { throw new AssertionError("preflight queried workers"); }
            @Override public int recommendedRoadPlanningLanes(int p, int a, int c) { throw new AssertionError("preflight queried lanes"); }
            @Override public boolean cpuProtection() { throw new AssertionError("preflight queried protection"); }
            @Override public boolean shouldPauseRoadCoordinator() { throw new AssertionError("preflight queried pause"); }
        });
        RuntimeLoadPolicy.setChunkyPreflightActive(true);
        ConfigService.get().performance().setGameplayThreadCap(1);
        // 此夹具验证外部压力豁免，先确认没有落入另一条独立的堆保护分档。
        assertTrue(RuntimeLoadPolicy.currentHeapUsageRatio() < 0.70D, "测试需要正常堆余量");
        assertEquals(Math.min(8, RuntimeLoadPolicy.maximumRoadWorkerThreads()),
                RuntimeLoadPolicy.recommendedRoadWorkerThreads());
        assertEquals(8, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
        assertFalse(RuntimeLoadPolicy.pauseScheduling());
    }

    /** Chunky 等待道路 readiness，沿用专属进展通道避免双方相互等待。 */
    @Test
    void chunkyReadinessLaneStillProgressesDuringExclusiveOwnership() {
        register(new Source("other-pressure") {
            @Override public boolean shouldPauseRoadCoordinator() { return true; }
            @Override public int recommendedRoadPlanningLanes(int p, int a, int c) { return 0; }
        });
        RuntimeLoadPolicy.setChunkyExclusiveActive(true);
        assertEquals(1, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
        assertTrue(RuntimeLoadPolicy.isChunkyActive());
    }

    /** 多源建议只负责收紧预算，不能突破用户设置的游玩并发上限。 */
    @Test
    void apiAdviceStillRespectsTheGameplayCap() {
        register(new Source("other-budget") {
            @Override public int recommendedRoadWorkerThreads(int current) { return 8; }
        });
        ConfigService.get().performance().setGameplayThreadCap(1);
        assertEquals(1, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
    }

    /** worker 零建议仍沿用至少一线程的进展下限，不能误当成 lane 的全停语义。 */
    @Test
    void zeroWorkerAdviceRetainsTheExistingProgressFloor() {
        register(new Source("zero-budget") {
            @Override public int recommendedRoadWorkerThreads(int current) { return 0; }
        });
        assertEquals(0, RuntimeSignals.recommendedWorkers(8));
        assertEquals(1, RuntimeLoadPolicy.recommendedRoadWorkerThreads());
    }

    /** 兼容入口必须继续读取 Chunky 本人的活动，不能因普通保护改成聚合而丢失。 */
    @Test
    void originalChunkyOwnershipStillUsesItsRegisteredIdentity() throws Exception {
        register(new Source("chunky-autogen") {
            @Override public LoadKind kind() { return LoadKind.PREGENERATION; }
            @Override public boolean busy() { return true; }
        });
        assertTrue(RuntimeLoadPolicy.isChunkyActive());
        assertEquals(RuntimeLoadPolicy.RuntimePhase.CHUNKY_RUNNING, RuntimeLoadPolicy.currentPhase());
        assertEquals(1, RuntimeLoadPolicy.recommendedCoordinatorLanes(12, 0, 4));
        expirePauseCache();
        assertEquals(RuntimeLoadPolicy.PauseKind.CHUNKY, RuntimeLoadPolicy.currentPauseKind());
    }

    private static void register(RoadLoadSignals source) {
        RoadLoadSignalsRegistry.register(source);
    }

    private static void clearSources() {
        RoadLoadSignalsRegistry.sources().forEach(source -> RoadLoadSignalsRegistry.unregister(source.ownerId()));
    }

    private static void expirePauseCache() throws Exception {
        // 用受控刷新替代睡眠，测试暂停与恢复策略时不让前一用例的冷却污染下一用例。
        resetClock("NEXT_REFRESH");
        resetClock("NEXT_COORDINATOR_REFRESH");
        resetClock("RECOVERY_UNTIL");
    }

    private static void resetClock(String name) throws Exception {
        Field field = RuntimeLoadPolicy.class.getDeclaredField(name);
        field.setAccessible(true);
        ((AtomicLong) field.get(null)).set(0L);
    }

    private abstract static class Source implements RoadLoadSignals {
        private final String id;
        Source(String id) { this.id = id; }
        @Override public String ownerId() { return id; }
        @Override public LoadKind kind() { return LoadKind.OTHER; }
        @Override public boolean busy() { return false; }
    }
}
