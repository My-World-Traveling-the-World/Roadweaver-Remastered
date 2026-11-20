/* 文件职责：一次同步河流校验内复用有界查询工作任务，不预取未请求的列。 */
package net.shiroha233.roadweaver.compat;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** 每次请求仍有独立超时；只合并执行器提交，不合并结果或改变湖泊/河道查询顺序。 */
final class StreamsQueryBatch {
    private static final int LIMIT = 64;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private StreamsQueryBatch() {}

    static <T> T run(ExecutorService executor, long timeoutMillis, Supplier<T> work) {
        if (CURRENT.get() != null) return work.get();
        try (var scope = new Scope(executor, timeoutMillis)) {
            CURRENT.set(scope);
            T result = work.get();
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("河流批次已中断");
            return result;
        } finally {
            CURRENT.remove();
        }
    }

    static boolean active() { return CURRENT.get() != null; }

    static Object query(Callable<Object> action) throws Exception {
        Scope scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("河流查询不在批次内");
        return scope.query(action);
    }

    private static final class Scope implements AutoCloseable {
        private final ExecutorService executor;
        private final long timeoutMillis;
        // 同步校验每次只允许一个在途请求；复用两个信号，避免每列再分配Future和队列节点。
        private final Semaphore requestReady = new Semaphore(0);
        private final Semaphore responseReady = new Semaphore(0);
        private Callable<Object> action;
        private Object answer;
        private Throwable failure;
        private Future<?> worker;
        private int used;
        private volatile boolean closed;

        Scope(ExecutorService executor, long timeoutMillis) {
            this.executor = executor;
            this.timeoutMillis = timeoutMillis;
        }

        Object query(Callable<Object> action) throws Exception {
            synchronized (this) {
                if (closed || Thread.currentThread().isInterrupted())
                    throw new CancellationException("河流批次已关闭或中断");
                this.action = action;
                requestReady.release();
                if (worker == null) {
                    // 只在真正未命中缓存时占线程；64次后归还执行器，避免长期独占一个lane。
                    try { worker = executor.submit(this::serve); }
                    catch (RuntimeException failure) { close(); throw failure; }
                    used = 0;
                }
            }
            try {
                if (!responseReady.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) throw new TimeoutException();
                if (++used == LIMIT) worker = null;
                if (failure != null) throw new ExecutionException(failure);
                Object result = answer;
                return result;
            } catch (TimeoutException failure) {
                close();
                throw failure;
            } catch (InterruptedException failure) {
                close();
                Thread.currentThread().interrupt();
                throw failure;
            } finally {
                answer = null;
                failure = null;
            }
        }

        private void serve() {
            for (int i = 0; i < LIMIT; i++) {
                try {
                    while (!requestReady.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                        // 调用方在计算地形时不能空占lane；有排队同伴就退让，下一请求重新申请。
                        if (executor instanceof ThreadPoolExecutor pool && !pool.getQueue().isEmpty()) {
                            synchronized (this) {
                                // 与新请求认领同锁，避免刚发来的请求被交给已经退出的线程。
                                if (requestReady.tryAcquire()) break;
                                worker = null;
                                return;
                            }
                        }
                        if (closed) return;
                    }
                    if (closed) return;
                    Callable<Object> current = action;
                    action = null;
                    Object value = null;
                    Throwable problem = null;
                    try { value = current.call(); }
                    catch (Throwable failure) { problem = failure; }
                    if (closed) return;
                    boolean yield = executor instanceof ThreadPoolExecutor pool && !pool.getQueue().isEmpty();
                    if (yield) {
                        // 有排队同伴时每次完成就归还lane，不能让64个慢查询把其他道路串行挡住。
                        synchronized (this) { worker = null; }
                    }
                    answer = value;
                    failure = problem;
                    // Semaphore提供结果发布屏障；失败也要唤醒并消耗一个槽，不能卡住下一请求。
                    responseReady.release();
                    if (yield) return;
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed || Thread.currentThread().isInterrupted()) return;
            }
        }

        @Override public synchronized void close() {
            // 正常返回也必须释放等待下一请求的线程；超时/取消不能留孤儿工作任务。
            closed = true;
            if (worker != null) worker.cancel(true);
            worker = null;
            action = null;
        }
    }
}
