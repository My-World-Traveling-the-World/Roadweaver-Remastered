/* 文件职责：道路按河流、长城的依赖顺序等待，不替其他模组宣布完成。 */
package net.shiroha233.roadweaver.compat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 只管理道路消费方的前置条件；河流发布和长城自身预热分别由各自仓库负责。 */
public final class RoadStartupGate {
    private static final java.util.Set<CompletableFuture<Void>> PENDING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "roadweaver-startup-gate");
        thread.setDaemon(true);
        return thread;
    });

    @FunctionalInterface
    interface Scheduler { void schedule(Runnable task, long delayMillis); }

    private final BooleanSupplier riversReady;
    private final BooleanSupplier wallReady;
    private final Supplier<CompletableFuture<Void>> awaitWall;
    private final Scheduler scheduler;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private volatile CompletableFuture<Void> wallWait;

    private RoadStartupGate(BooleanSupplier riversReady, BooleanSupplier wallReady,
                            Supplier<CompletableFuture<Void>> awaitWall, Scheduler scheduler) {
        this.riversReady = riversReady;
        this.wallReady = wallReady;
        this.awaitWall = awaitWall;
        this.scheduler = scheduler;
        PENDING.add(result);
        result.whenComplete((ignored, failure) -> {
            PENDING.remove(result);
            // 这里只取消本任务创建的长城轮询门，不取消引擎或其他消费者的共享 Future。
            CompletableFuture<Void> waiting = wallWait;
            if (result.isCancelled() && waiting != null) waiting.cancel(false);
        });
    }

    /** 停服撤销道路自己的等待，旧河流通知不能在退出后启动本次道路。 */
    public static void cancelPending() {
        for (CompletableFuture<Void> pending : PENDING) pending.cancel(false);
    }

    public static CompletableFuture<Void> await(BooleanSupplier riversReady, BooleanSupplier wallReady,
                                                 Supplier<CompletableFuture<Void>> awaitWall) {
        return await(riversReady, wallReady, awaitWall,
                (task, delay) -> POLLER.schedule(task, delay, TimeUnit.MILLISECONDS));
    }

    static CompletableFuture<Void> await(BooleanSupplier riversReady, BooleanSupplier wallReady,
                                          Supplier<CompletableFuture<Void>> awaitWall, Scheduler scheduler) {
        RoadStartupGate gate = new RoadStartupGate(riversReady, wallReady, awaitWall, scheduler);
        // 已完成的前置条件同步返回，正常加载仍能在加载界面内完成道路。
        gate.pollRivers();
        return gate.result;
    }

    private void pollRivers() {
        if (result.isDone()) return;
        try {
            if (!riversReady.getAsBoolean()) {
                // 河流未完成时不能因为长城缺席或已就绪就开工，也不占住服务端线程。
                scheduler.schedule(this::pollRivers, 250L);
                return;
            }
            if (wallReady.getAsBoolean()) {
                result.complete(null);
                return;
            }
            wallWait = awaitWall.get();
            if (result.isCancelled()) {
                wallWait.cancel(false);
                return;
            }
            wallWait.whenComplete((ignored, failure) -> {
                try {
                    if (result.isDone()) return;
                    if (failure != null) result.completeExceptionally(failure);
                    // 等长城期间河流或本世界可能已经停止；旧的 ready 不得启动新一轮道路。
                    else if (!riversReady.getAsBoolean())
                        result.completeExceptionally(new IllegalStateException("河流在等待长城期间失去就绪状态"));
                    else if (wallReady.getAsBoolean()) result.complete(null);
                    else result.completeExceptionally(new IllegalStateException("Great Wall gate released before readiness"));
                } catch (RuntimeException | LinkageError probeFailure) {
                    // 完成回调内抛错也必须传给道路，不能只让 whenComplete 的衍生 Future 失败。
                    result.completeExceptionally(probeFailure);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            // 自己的轮询器坏掉也不能跳过河流前置条件，留下明确失败供调用方记录。
            result.completeExceptionally(failure);
        }
    }
}
