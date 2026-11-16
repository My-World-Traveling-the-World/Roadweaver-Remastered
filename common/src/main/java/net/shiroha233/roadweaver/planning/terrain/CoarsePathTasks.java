/* 文件职责：隔离两轮粗路径子任务的提交边界，保留独立工作池和原 Future 等待契约。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class CoarsePathTasks {
    // 父规划任务仍在共享池等待；子任务保持独立 INITIAL 池，避免收紧共享池后互等。
    static final CoarsePathTasks DEFAULT = new CoarsePathTasks(
            ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.INITIAL));
    private final ExecutorService workers;

    CoarsePathTasks(ExecutorService workers) {
        this.workers = java.util.Objects.requireNonNull(workers);
    }

    <T> Future<T> submit(Callable<T> action) {
        return workers.submit(action);
    }
}
