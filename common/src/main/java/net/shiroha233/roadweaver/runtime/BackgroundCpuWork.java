/* 文件职责：让游玩期 CPU 精确采样复用现有预算，并在区块/列之间检查取消。 */
package net.shiroha233.roadweaver.runtime;


import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public final class BackgroundCpuWork {
    private static final ThreadLocal<Boolean> HOLDING = ThreadLocal.withInitial(() -> false);
    private BackgroundCpuWork() {}

    public static <T> Task<T> submit(ExecutorService workers, Callable<T> action) {
        java.util.Objects.requireNonNull(action);
        // 在提交方捕获代次、角色与预热阶段；排队期间换世界的任务不得在新代次重新获准。
        Scope scope = scope();
        AtomicBoolean workerStarted = new AtomicBoolean();
        AtomicLong searchStarted = new AtomicLong();
        // 自有 FutureTask 才能把 cancel(true) 送到实际线程；公共池的 ForkJoinTask 只改取消状态。
        FutureTask<T> future = new FutureTask<>(() -> {
            workerStarted.set(true);
            return callUnit(scope, () -> {
                searchStarted.set(System.nanoTime());
                return action.call();
            });
        });
        workers.execute(future);
        return new Task<>(scope, future, workerStarted, searchStarted);
    }

    public static void parallel(int count, IntConsumer action) {
        Scope scope = scope();
        if (scope.warmup()) {
            // 预热仍保留原来的并行流；普通游玩只让实际采样单元持许可，不让父任务占位等子任务。
            java.util.stream.IntStream.range(0, count).parallel().forEach(index -> runUnit(scope, index, action));
        } else {
            for (int index = 0; index < count; index++) runUnit(scope, index, action);
        }
        check(scope);
    }

    public static void sequential(int count, IntConsumer action) {
        Scope scope = scope();
        // 非噪声生成器维持原来的逐列顺序，也不能因为没有并行流而绕过保护。
        for (int index = 0; index < count; index++) runUnit(scope, index, action);
        check(scope);
    }

    private static Scope scope() {
        Scope scope = new Scope(ThreadPoolManager.currentEpoch(), Thread.currentThread(),
                RuntimeLoadPolicy.isCpuWarmup(), ThreadPoolManager.currentTaskRole());
        check(scope);
        return scope;
    }

    private static void runUnit(Scope scope, int index, IntConsumer action) {
        try {
            callUnit(scope, () -> { action.accept(index); return null; });
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception impossible) {
            // IntConsumer 不声明受检异常，这里只为复用 Callable 边界，不改变原始运行时故障。
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> T callUnit(Scope scope, Callable<T> action) throws Exception {
        check(scope);
        boolean acquired = false;
        try {
            // 同线程的嵌套计算仍只占一个实际 worker，重复认领会在上限为 1 时自锁。
            if (!scope.warmup() && !HOLDING.get()) {
                acquired = RuntimeLoadPolicy.acquireCoarseUnit(false, () -> cancelled(scope), scope.originRole());
                if (!acquired) throw new CancellationException("background CPU work cancelled while waiting");
                HOLDING.set(true);
            }
            check(scope);
            T result = action.call();
            // 最后一列读取期间换世界，也必须拒绝返回成功产物。
            check(scope);
            return result;
        } finally {
            if (acquired) {
                HOLDING.remove();
                RuntimeLoadPolicy.releaseCoarseUnit(false);
            }
        }
    }

    private static boolean cancelled(Scope scope) {
        return scope.owner().isInterrupted() || Thread.currentThread().isInterrupted()
                || RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(scope.epoch());
    }

    private static void check(Scope scope) {
        if (cancelled(scope)) throw new CancellationException("background CPU work scope expired");
    }

    private record Scope(long epoch, Thread owner, boolean warmup, ThreadPoolManager.TaskRole originRole) {}

    /** 普通 Future 限时语义保持原样；规划入口另用执行期限，主动让路不消耗搜索预算。 */
    public static final class Task<T> implements Future<T> {
        private final Scope scope;
        private final Future<T> future;
        private final AtomicBoolean workerStarted;
        private final AtomicLong searchStarted;

        private Task(Scope scope, Future<T> future, AtomicBoolean workerStarted, AtomicLong searchStarted) {
            this.scope = scope; this.future = future; this.workerStarted = workerStarted; this.searchStarted = searchStarted;
        }

        public T getForExecution(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long budget = unit.toNanos(timeout), waitingSince = System.nanoTime();
            while (true) {
                if (cancelled(scope)) {
                    future.cancel(true);
                    throw new CancellationException("background CPU task scope expired");
                }
                if (future.isDone()) return future.get();
                long slice = TimeUnit.MILLISECONDS.toNanos(25);
                if (timeout > 0) {
                    long started = searchStarted.get();
                    // 物理队列仍有原来的等待看门狗；取得 worker 后等许可则只响应取消，不超时重试。
                    long elapsed = started != 0 ? System.nanoTime() - started
                            : workerStarted.get() ? 0 : System.nanoTime() - waitingSince;
                    long remaining = budget - elapsed;
                    if (remaining <= 0) throw new TimeoutException("background CPU execution/queue timeout");
                    slice = Math.min(slice, remaining);
                }
                try { return future.get(slice, TimeUnit.NANOSECONDS); }
                catch (TimeoutException pending) { /* 短轮询保证停服或换世界能取消未执行的 Future。 */ }
            }
        }

        @Override public boolean cancel(boolean interrupt) { return future.cancel(interrupt); }
        @Override public boolean isCancelled() { return future.isCancelled(); }
        @Override public boolean isDone() { return future.isDone(); }
        @Override public T get() throws InterruptedException, ExecutionException { return future.get(); }
        @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }
    }
}
