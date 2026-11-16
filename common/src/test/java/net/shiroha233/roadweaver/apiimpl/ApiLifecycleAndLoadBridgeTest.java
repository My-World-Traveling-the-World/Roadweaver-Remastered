package net.shiroha233.roadweaver.apiimpl;

import java.util.ArrayList;
import java.util.List;

import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleApi;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleListener;
import net.shiroha233.roadweaver.api.outgoing.LoadKind;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignals;
import net.shiroha233.roadweaver.api.outgoing.RoadLoadSignalsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期总线与「注册表优先」负载信号桥。
 *
 * <h2>为什么这两条必须钉住</h2>
 * <p>它们替代的是外部 mod 往道路内部打的两个 {@code @Pseudo @Mixin} 和 9 处硬编码反射。
 * 一旦这些路径静默失效，外部 mod 不会报错，只会「功能不生效」——
 * 这正是本次改造要根治的失效形态。所以这里必须有直接断言。</p>
 */
class ApiLifecycleAndLoadBridgeTest {

    @AfterEach
    void cleanup() {
        ApiBackendImpl.clearListenersForTest();
        RoadLoadSignalsRegistry.unregister(LoadSignalBridge.OWNER_CHUNKY);
        RoadLoadSignalsRegistry.unregister(LoadSignalBridge.OWNER_HYPERSPEED);
        // 注册表是进程级状态，未知来源夹具也必须清走，否则会污染后续多源聚合用例。
        RoadLoadSignalsRegistry.unregister("some-other-mod");
        RoadLoadSignalsRegistry.registerStreamsThreadControl(null);
    }

    // ── 生命周期总线 ─────────────────────────────────────────────────────

    /**
     * 回归测试：**后端还没注入时注册的监听器不能丢**。
     *
     * <h2>这条测试对应一个真机 bug（2026-11-12）</h2>
     * <p>Fabric 的 {@code depends} 只保证契约 mod（{@code roadweaver_api}）先加载，
     * <b>不保证 RoadWeaver 自己的 onInitialize 早于别人的</b>。实测日志：</p>
     * <pre>
     * 10:47:47  [GreatWall] Common entrypoint loaded      ← 长城先跑
     * 10:47:50  Initializing RoadWeaver (Fabric)          ← 道路 3 秒后才注入后端
     * 10:47:51  lifecycle listener registered (total=1)   ← 只有 streams 注册上
     * </pre>
     * <p>长城早 3 秒 → {@code backend == null} → 旧实现返回 false → 调用方吞掉返回值
     * → <b>回调静默丢失，河流预热再也不触发</b>。</p>
     *
     * <p>现在的契约：后端未就绪时先排队，{@code installBackend} 时补注册并<b>真的能触发</b>。
     * 这里断言的不只是队列长度，还要 fire 一次证明回调确实被送达 ——
     * 否则「队列清了但没注册」也能骗过前两条断言。</p>
     */
    @Test
    void listenerRegisteredBeforeBackendInjectionIsNotLost() {
        PlanningLifecycleApi.clearPendingForTest();
        ApiBackendImpl.clearListenersForTest();
        RoadWeaverApi.installBackend(null);
        try {
            boolean[] fired = {false};
            int before = ApiBackendImpl.listenerCount();

            // 后端不在时注册 —— 必须被接受（排队），而不是返回 false
            boolean accepted = PlanningLifecycleApi.addListener(new PlanningLifecycleListener() {
                @Override
                public void onRiversPrewarmed() {
                    fired[0] = true;
                }
            });
            assertTrue(accepted, "后端未就绪时注册也必须被接受（排队），不能返回 false");
            assertEquals(1, PlanningLifecycleApi.pendingCount(),
                    "后端未就绪时监听器应进排队队列");
            assertEquals(before, ApiBackendImpl.listenerCount(),
                    "后端未就绪时不应直接注册到实现里");

            // 注入后端 —— 排队项应被补注册
            RoadWeaverApi.installBackend(ApiBackendImpl.INSTANCE);
            assertEquals(0, PlanningLifecycleApi.pendingCount(),
                    "注入后端后排队队列必须清空");
            assertEquals(before + 1, ApiBackendImpl.listenerCount(),
                    "注入后端后排队项必须补注册进实现");

            // 真的能触发才算送达
            ApiBackendImpl.fireRiversPrewarmed();
            assertTrue(fired[0], "补注册的监听器必须能被真实事件触发");
        } finally {
            PlanningLifecycleApi.clearPendingForTest();
            ApiBackendImpl.clearListenersForTest();
            RoadWeaverApi.installBackend(ApiBackendImpl.INSTANCE);
        }
    }

    @Test
    void lifecycleEventsReachRegisteredListeners() {
        List<String> seen = new ArrayList<>();
        ApiBackendImpl.INSTANCE.addLifecycleListener(new PlanningLifecycleListener() {
            @Override
            public void onRiversPrewarmed() {
                seen.add("rivers");
            }

            @Override
            public void onInitialGenerationStart() {
                seen.add("start");
            }

            @Override
            public void onInitialGenerationEnd(InitialGenerationSummary summary) {
                seen.add("end:" + summary.roadsRequested() + "/" + summary.roadsBuilt()
                        + "/" + summary.roadsFailed() + "/" + summary.elapsedMillis());
            }
        });

        ApiBackendImpl.fireRiversPrewarmed();
        ApiBackendImpl.fireInitialGenerationStart();
        ApiBackendImpl.fireInitialGenerationEnd(7, 5, 2, 1234L);

        assertEquals(List.of("rivers", "start", "end:7/5/2/1234"), seen,
                "三个事件必须按触发顺序、带正确摘要送达");
    }

    @Test
    void aThrowingListenerDoesNotBreakTheOthers() {
        List<String> seen = new ArrayList<>();
        ApiBackendImpl.INSTANCE.addLifecycleListener(new PlanningLifecycleListener() {
            @Override
            public void onRiversPrewarmed() {
                throw new IllegalStateException("外部 mod 的 bug");
            }
        });
        ApiBackendImpl.INSTANCE.addLifecycleListener(new PlanningLifecycleListener() {
            @Override
            public void onRiversPrewarmed() {
                seen.add("survivor");
            }
        });

        ApiBackendImpl.fireRiversPrewarmed();

        assertEquals(List.of("survivor"), seen,
                "一个监听器抛异常不能阻断后面的监听器（更不能中断道路生成）");
    }

    @Test
    void removedListenerStopsReceivingEvents() {
        List<String> seen = new ArrayList<>();
        PlanningLifecycleListener listener = new PlanningLifecycleListener() {
            @Override
            public void onInitialGenerationStart() {
                seen.add("start");
            }
        };
        ApiBackendImpl.INSTANCE.addLifecycleListener(listener);
        ApiBackendImpl.fireInitialGenerationStart();
        ApiBackendImpl.INSTANCE.removeLifecycleListener(listener);
        ApiBackendImpl.fireInitialGenerationStart();

        assertEquals(1, seen.size(), "注销后不应再收到事件");
    }

    // ── 注册表优先的负载信号桥 ───────────────────────────────────────────

    @Test
    void withoutProviderTheBridgeFallsBackToReflection() {
        // 没有任何 provider 注册时，桥必须返回 null 让调用方回退反射（= 改造前行为）
        assertNull(LoadSignalBridge.booleanSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "hasActiveTasks"));
        assertNull(LoadSignalBridge.intSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadPlanningLanes", 2, 1, 4));
        assertNull(LoadSignalBridge.booleanSignal(
                "local.plaincraft.hyperspeed.CruiseState", "isCpuProtectionActive"));
        assertFalse(LoadSignalBridge.voidIntSignal(
                "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkySupplyThreads", 4));
    }

    @Test
    void withProviderTheBridgeUsesTheStandardApi() {
        RoadLoadSignalsRegistry.register(new RoadLoadSignals() {
            @Override
            public String ownerId() {
                return LoadSignalBridge.OWNER_CHUNKY;
            }

            @Override
            public LoadKind kind() {
                return LoadKind.PREGENERATION;
            }

            @Override
            public boolean busy() {
                return true;
            }

            @Override
            public boolean shouldPauseRoadCoordinator() {
                return true;
            }

            @Override
            public int recommendedRoadPlanningLanes(int pending, int readyAhead, int currentLanes) {
                return 3;
            }

            @Override
            public boolean shouldPauseAuxiliaryWorldgen() {
                return true;
            }
        });

        assertEquals(Boolean.TRUE, LoadSignalBridge.booleanSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "hasActiveTasks"));
        assertEquals(Boolean.TRUE, LoadSignalBridge.booleanSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "shouldPauseRoadCoordinator"));
        assertEquals(Integer.valueOf(3), LoadSignalBridge.intSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadPlanningLanes", 2, 1, 4));
        // 提供方没给的信号（-1 无建议）仍然回退反射
        assertNull(LoadSignalBridge.intOneArgSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "recommendedRoadWorkerThreads", 8));
    }

    @Test
    void unmetOwnerIdIsIgnored() {
        // 注册了 provider 但 id 不是约定的那两个 → 桥认不出来，仍回退反射
        RoadLoadSignalsRegistry.register(new RoadLoadSignals() {
            @Override
            public String ownerId() {
                return "some-other-mod";
            }

            @Override
            public LoadKind kind() {
                return LoadKind.OTHER;
            }

            @Override
            public boolean busy() {
                return true;
            }
        });

        assertNull(LoadSignalBridge.booleanSignal(
                "local.plaincraftchunkyautogen.AdaptiveChunkController", "hasActiveTasks"),
                "ownerId 不匹配时必须回退反射，不能误用别人的信号");
    }

    @Test
    void streamsCommandGoesThroughTheApiWhenRegistered() {
        List<Integer> threads = new ArrayList<>();
        List<Boolean> exclusive = new ArrayList<>();
        RoadLoadSignalsRegistry.registerStreamsThreadControl(
                new net.shiroha233.roadweaver.api.outgoing.StreamsThreadControl() {
                    @Override
                    public void setChunkySupplyThreads(int value) {
                        threads.add(value);
                    }

                    @Override
                    public void setChunkyExclusive(boolean value) {
                        exclusive.add(value);
                    }
                });

        assertTrue(LoadSignalBridge.voidIntSignal(
                "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkySupplyThreads", 6));
        assertTrue(LoadSignalBridge.booleanOneArgSignal(
                "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkyExclusive", true));

        assertEquals(List.of(6), threads);
        assertEquals(List.of(true), exclusive);
    }

    /** 启动日志只需要类别，不应借诊断触发外部控制器有状态的预算推进。 */
    @Test
    void describeResolutionDoesNotAdvanceAdaptiveBudgets() {
        int[] budget = {6};
        int[] queries = {0};
        RoadLoadSignalsRegistry.register(new RoadLoadSignals() {
            @Override public String ownerId() { return LoadSignalBridge.OWNER_CHUNKY; }
            @Override public LoadKind kind() { return LoadKind.PREGENERATION; }
            @Override public boolean busy() { return true; }
            @Override public int recommendedRoadWorkerThreads(int current) {
                // 复刻当前 Chunky 的未知参数夹取及写回，避免用完全无副作用的 Fake 掩盖接线。
                queries[0]++;
                budget[0] = Math.max(1, current);
                return budget[0];
            }
            @Override public int recommendedRoadPlanningLanes(int pending, int ahead, int current) {
                queries[0]++;
                return 3;
            }
        });

        String diagnostics = LoadSignalBridge.describeResolution();

        assertTrue(diagnostics.contains("chunky-autogen"));
        assertTrue(diagnostics.contains("kind-busy=PREGENERATION"));
        assertEquals(6, budget[0], "打印诊断不能把自适应预算改成未知参数对应的 1");
        assertEquals(0, queries[0], "worker 和 lane 决策都不应为打印身份而被调用");
    }

    @Test
    void describeResolutionTellsWhichSideIsOnTheApi() {
        // 只断言"这一行把每个注册表都报出来了"，不断言具体为空 ——
        // 注册表是进程级静态的，别的测试可能已经注册过东西。
        String before = LoadSignalBridge.describeResolution();
        for (String field : new String[]{"api-signals:",
                "load-signals=", "kind-busy=", "streams-control=",
                "chunky-runtime=", "cruise-signals=", "great-wall-readiness=",
                "river-ready=", "river-listeners="}) {
            assertTrue(before.contains(field), "诊断行缺字段 " + field + ": " + before);
        }

        RoadLoadSignalsRegistry.register(new RoadLoadSignals() {
            @Override
            public String ownerId() {
                return LoadSignalBridge.OWNER_CHUNKY;
            }

            @Override
            public LoadKind kind() {
                return LoadKind.PREGENERATION;
            }

            @Override
            public boolean busy() {
                return false;
            }
        });

        String after = LoadSignalBridge.describeResolution();
        assertTrue(after.contains("load-signals=[") && after.contains(LoadSignalBridge.OWNER_CHUNKY),
                "注册后应把该 id 列在 load-signals 里: " + after);
        assertNotNull(after);
    }
}
