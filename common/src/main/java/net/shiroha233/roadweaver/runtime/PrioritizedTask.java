/* 文件职责：保留角色优先级及 FIFO，并将闸门拒绝、换世界和停服传递到真实任务终态。 */
package net.shiroha233.roadweaver.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final ThreadPoolManager.TaskRole role;
    private final Runnable delegate;
    private final long sequence = SEQUENCE.getAndIncrement();
    private final long epoch = ThreadPoolManager.currentEpoch();
    private final BiConsumer<ThreadPoolManager.TaskRole, BooleanSupplier> gate;
    private volatile boolean cancelled;
    private volatile BooleanSupplier poolClosing = () -> false;
    private Thread runner;

    PrioritizedTask(ThreadPoolManager.TaskRole role, Runnable delegate) {
        this(role, delegate, RuntimeLoadPolicy::beforeTask);
    }

    PrioritizedTask(ThreadPoolManager.TaskRole role, Runnable delegate,
                    BiConsumer<ThreadPoolManager.TaskRole, BooleanSupplier> gate) {
        this.role = role == null ? ThreadPoolManager.TaskRole.PLANNING : role;
        this.delegate = Objects.requireNonNull(delegate); this.gate = Objects.requireNonNull(gate);
    }

    // 物理池开始重建时即关闭旧任务入口，不等待逐个 Future 的取消回调结束。
    void attachPool(BooleanSupplier closing) { this.poolClosing = closing; }

    private boolean expired() {
        return cancelled || poolClosing.getAsBoolean() || RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(epoch)
                || delegate instanceof Future<?> future && future.isCancelled();
    }

    void cancelIfExpired() {
        if (expired()) cancelTask();
    }

    void cancelTask() {
        cancelled = true;
        // 先让 Future 进入取消态，再打断闸门线程，避免中断让计算先以普通结果完成。
        if (delegate instanceof Future<?> future) future.cancel(true);
        synchronized (this) {
            // 与 run 的清理互斥，避免把刚复用来执行下一个任务的线程误打断。
            if (runner != null && runner != Thread.currentThread()) runner.interrupt();
        }
    }

    @Override public void run() {
        synchronized (this) { runner = Thread.currentThread(); }
        var previous = ThreadPoolManager.enterTaskRole(role);
        try {
            if (expired()) { cancelTask(); return; }
            boolean allowed = true;
            try {
                gate.accept(role, this::expired);
            } catch (CancellationException stopping) {
                allowed = false;
            } catch (Throwable gateFailure) {
                // 保留旧契约：非取消的闸门故障记录后放行，不能把 Future 静默丢掉。
                LOGGER.warn("RoadWeaver admission gate failed on role={}; running task anyway", role, gateFailure);
            }
            // 闸门退出和执行之间再核对；换代、停服、取消都不能被失败开放分支绕过。
            if (allowed && !expired() && !Thread.currentThread().isInterrupted()) delegate.run();
            else cancelTask();
        } catch (Throwable failure) {
            cancelTask();
            LOGGER.warn("RoadWeaver task failed on role={} (not re-queued)", role, failure);
        } finally {
            ThreadPoolManager.restoreTaskRole(previous);
            synchronized (this) { runner = null; }
        }
    }

    @Override public int compareTo(PrioritizedTask other) {
        int priority = Integer.compare(role.priority(), other.role.priority());
        return priority != 0 ? priority : Long.compare(sequence, other.sequence);
    }
}
