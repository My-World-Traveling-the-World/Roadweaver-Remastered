/* 文件职责：把调用方拿到的 CompletableFuture 与实际任务绑定，避免只取消 JDK 内部包装。 */
package net.shiroha233.roadweaver.runtime;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

final class LifecycleCompletableTask<T> extends FutureTask<T> {
    private final CompletableFuture<T> result = new CompletableFuture<>() {
        @Override public boolean cancel(boolean interrupt) {
            if (isDone()) return isCancelled();
            // 先打断真实计算，再发布取消回调；回调可能等待计算退出，反序会互等。
            // FutureTask.done 会重入这里，但任务已是终态，不会再次触发 done。
            LifecycleCompletableTask.this.cancel(interrupt);
            return super.cancel(interrupt);
        }
    };

    private LifecycleCompletableTask(Supplier<T> action) {
        super(Objects.requireNonNull(action)::get);
    }

    static <T> CompletableFuture<T> submit(Executor executor, Supplier<T> action) {
        var task = new LifecycleCompletableTask<>(action);
        executor.execute(task);
        return task.result;
    }

    @Override public void run() {
        // 调用方提前 complete 的语义保持一致：尚未开始的 supplier 不再执行。
        if (result.isDone()) cancel(false);
        else super.run();
    }

    @Override protected void done() {
        if (isCancelled()) { result.cancel(false); return; }
        try { result.complete(get()); }
        catch (ExecutionException failure) { result.completeExceptionally(failure.getCause()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); result.completeExceptionally(interrupted);
        }
    }
}
