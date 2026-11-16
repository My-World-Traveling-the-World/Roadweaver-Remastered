/* 文件职责：追踪物理池已接收的任务，停服和重建不能只丢弃队列而遗留未完成的 Future。 */
package net.shiroha233.roadweaver.runtime;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

final class LifecycleWorkerPool extends ThreadPoolExecutor {
    private final Set<PrioritizedTask> accepted = ConcurrentHashMap.newKeySet();
    private final Set<CreatedFuture<?>> created = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    <T> RunnableFuture<T> createTask(Callable<T> action) {
        // CompletionService 会在真实结果外再包 QueueingFuture；因此结果也要独立登记。
        var task = new CreatedFuture<>(action); created.add(task);
        if (isShutdown()) task.cancel(true);
        return task;
    }

    void cancelExpiredTasks() {
        created.forEach(task -> { if (task.expired()) task.cancel(true); });
        accepted.forEach(PrioritizedTask::cancelIfExpired);
    }

    private final class CreatedFuture<T> extends FutureTask<T> {
        private final long epoch = ThreadPoolManager.currentEpoch();
        private CreatedFuture(Callable<T> action) { super(action); }
        private boolean expired() { return closing || RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(epoch); }
        @Override public void run() {
            if (expired()) cancel(false); else super.run();
        }
        @Override protected void done() { created.remove(this); }
    }


    LifecycleWorkerPool(int core, int maximum, long keepAlive, TimeUnit unit,
                        BlockingQueue<Runnable> queue, ThreadFactory factory) {
        super(core, maximum, keepAlive, unit, queue, factory);
    }

    @Override public void execute(Runnable command) {
        PrioritizedTask task = command instanceof PrioritizedTask tracked ? tracked
                : new PrioritizedTask(ThreadPoolManager.TaskRole.PLANNING, command);
        task.attachPool(() -> closing);
        // 先登记再提交；与 shutdownNow 交错的拒收分支也负责终结，不留下注册项。
        accepted.add(task);
        try {
            if (closing) throw new RejectedExecutionException("RoadWeaver worker pool is closing");
            super.execute(task);
        }
        catch (RuntimeException | Error rejected) {
            accepted.remove(task); task.cancelTask(); throw rejected;
        }
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try { super.afterExecute(task, failure); }
        finally { accepted.remove(task); }
    }

    @Override public List<Runnable> shutdownNow() {
        // 先拒收，再取消结果，最后强停；反序中断可能让运行中的任务抢先发布普通成功。
        // 取消活动 Future 会先释放 worker；先关执行闸门，避免它趁此消费尚未取消的旧队列。
        closing = true;
        super.shutdown();
        created.forEach(task -> task.cancel(true));
        // 队列外还包括已被 worker 取走、正在前置闸门等待的任务，两者都必须结束结果。
        accepted.forEach(PrioritizedTask::cancelTask);
        List<Runnable> pending = super.shutdownNow();
        accepted.removeAll(pending);
        return pending;
    }
}
