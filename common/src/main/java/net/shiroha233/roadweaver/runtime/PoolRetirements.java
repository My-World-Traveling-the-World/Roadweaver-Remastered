/* 文件职责：池替换先在管理锁内发布新引用，旧结果的取消回调等最外层管理操作退出后再运行。 */
package net.shiroha233.roadweaver.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PoolRetirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final ThreadLocal<ArrayDeque<ExecutorService>> PENDING = new ThreadLocal<>();
    private PoolRetirements() {}

    static void retire(ExecutorService pool) {
        if (pool == null) return;
        if (!Thread.holdsLock(ThreadPoolManager.class)) { close(pool); return; }
        var pending = PENDING.get();
        if (pending == null) { pending = new ArrayDeque<>(); PENDING.set(pending); }
        pending.addLast(pool);
    }

    static void drain() {
        if (Thread.holdsLock(ThreadPoolManager.class)) return;
        var pending = PENDING.get();
        if (pending == null) return;
        // 先移除线程局部状态；取消回调重入并替换其他池时，仍会有自己的成对清理。
        PENDING.remove();
        while (!pending.isEmpty()) close(pending.removeFirst());
    }

    private static void close(ExecutorService pool) {
        try { pool.shutdownNow(); }
        catch (Throwable failure) { LOGGER.warn("RoadWeaver could not stop a retired worker pool", failure); }
    }
}
