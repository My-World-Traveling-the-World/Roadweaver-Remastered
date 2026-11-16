/* 文件职责：隔离两轮粗路径子任务的提交边界，保留独立工作池和原 Future 等待契约。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.BackgroundCpuWork;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class CoarsePathTasks {
    // 普通父任务在共享池，开局父任务也可能在 INITIAL；后者借用已有公共计算池，避免同池互等。
    static final CoarsePathTasks DEFAULT = new CoarsePathTasks(
            ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL),
            java.util.concurrent.ForkJoinPool.commonPool());
    private final ExecutorService workers;
    private final ExecutorService nestedWorkers;

    CoarsePathTasks(ExecutorService workers) {
        this(workers, workers);
    }

    private CoarsePathTasks(ExecutorService workers, ExecutorService nestedWorkers) {
        this.workers = java.util.Objects.requireNonNull(workers);
        this.nestedWorkers = java.util.Objects.requireNonNull(nestedWorkers);
    }

    <T> Future<T> submit(Callable<T> action) {
        // INITIAL 只决定物理执行池；游玩阶段的两轮搜索仍共用实际工作许可。
        ExecutorService executor = ThreadPoolManager.currentTaskRole() == ThreadPoolManager.TaskRole.INITIAL
                ? nestedWorkers : workers;
        return BackgroundCpuWork.submit(executor, action);
    }

    static void await(Future<?> future, long timeoutMs) throws java.lang.InterruptedException,
            java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        if (future instanceof BackgroundCpuWork.Task<?> task) {
            task.getForExecution(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else if (timeoutMs <= 0) {
            future.get();
        } else {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
