/* 文件职责：让游玩期 CPU 精确采样复用现有预算，并在区块/列之间检查取消。 */
package net.shiroha233.roadweaver.runtime;


import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

public final class BackgroundCpuWork {
    private BackgroundCpuWork() {}

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
                RuntimeLoadPolicy.isCpuWarmup());
        check(scope);
        return scope;
    }

    private static void runUnit(Scope scope, int index, IntConsumer action) {
        check(scope);
        boolean acquired = false;
        try {
            if (!scope.warmup()) {
                acquired = RuntimeLoadPolicy.acquireCoarseUnit(false, () -> cancelled(scope));
                if (!acquired) throw new CancellationException("CPU accurate sampling cancelled while waiting");
            }
            check(scope);
            action.accept(index);
            // 最后一列读取期间换世界，也必须拒绝返回成功产物。
            check(scope);
        } finally {
            if (acquired) RuntimeLoadPolicy.releaseCoarseUnit(false);
        }
    }

    private static boolean cancelled(Scope scope) {
        return scope.owner().isInterrupted() || Thread.currentThread().isInterrupted()
                || RoadLifecycle.isStopping() || !ThreadPoolManager.isEpoch(scope.epoch());
    }

    private static void check(Scope scope) {
        if (cancelled(scope)) throw new CancellationException("CPU accurate sampling scope expired");
    }

    private record Scope(long epoch, Thread owner, boolean warmup) {}
}
