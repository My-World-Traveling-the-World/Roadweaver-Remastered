/* 文件职责：异步计算服务，封装共享工作池的提交操作。 */
package net.shiroha233.roadweaver.util;

import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 异步计算服务，封装共享工作池的提交操作
 */
public final class ComputeService {

    private ComputeService() {}

    public static Executor executor() {
        return ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.PLANNING);
    }

    public static Executor executor(ThreadPoolManager.TaskRole role) {
        return ThreadPoolManager.roleExecutor(role);
    }

    public static Executor mapExecutor() {
        return ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.MAP);
    }

    public static Executor mapReadExecutor() {
        return ThreadPoolManager.roleExecutor(ThreadPoolManager.TaskRole.MAP_READ);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyAsync(ThreadPoolManager.TaskRole.PLANNING, supplier);
    }

    public static <T> CompletableFuture<T> supplyAsync(ThreadPoolManager.TaskRole role, Supplier<T> supplier) {
        return ThreadPoolManager.supplyAsync(role, supplier);
    }

    /** 地图读取也必须返回与实际执行绑定的结果，否则退出世界后界面请求可能一直 pending。 */
    public static <T> CompletableFuture<T> supplyMapReadAsync(Supplier<T> supplier) {
        return supplyAsync(ThreadPoolManager.TaskRole.MAP_READ, supplier);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(ThreadPoolManager.TaskRole.PLANNING, runnable);
    }

    public static CompletableFuture<Void> runAsync(ThreadPoolManager.TaskRole role, Runnable runnable) {
        return ThreadPoolManager.runAsync(role, runnable);
    }

    public static CompletableFuture<Void> runMapAsync(Runnable runnable) {
        return runAsync(ThreadPoolManager.TaskRole.MAP, runnable);
    }

    public static void shutdownNow() {
        ThreadPoolManager.onServerStopping();
    }
}
