/* 文件职责：按 tick 推进一条手绘路线的区块请求；不依赖 Minecraft 世界。 */
package net.shiroha233.roadweaver.route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class RoutePreloadWork<C> {
    /** 加载窗口与施工额度分离，避免同时就绪的请求一次占满服务端 tick。 */
    static final int DEFAULT_PLACEMENT_BUDGET = 8;

    /** 票据由本任务持有，Future 仍属于区块引擎，停止时只释放前者。 */
    record Request(CompletableFuture<?> future, Runnable release) {}

    /** 隔离引擎请求和实际施工，使慢块、取消和预算可以离线验证。 */
    interface Access<C> {
        Request request(long key);
        C loaded(long key);
        Set<Long> place(Map<Long, C> ready);
        void failed(long key, RuntimeException failure, boolean finalAttempt);
    }

    private record Task(long key, int attempt) {}

    private final ArrayDeque<Task> waiting = new ArrayDeque<>();
    private final Map<Task, Request> inFlight = new LinkedHashMap<>();
    private final List<Long> firstFailures = new ArrayList<>();
    private final int total;
    private final int window;
    private final int maxAttempts;
    private final int placementBudget;
    private int processed;
    private int failures;
    private boolean stopped;

    RoutePreloadWork(List<Long> ordered, int window, int maxAttempts) {
        this(ordered, window, maxAttempts, DEFAULT_PLACEMENT_BUDGET);
    }

    /** 每 tick 至少推进一块；等待施工额度不算失败，也不能重发加载请求。 */
    RoutePreloadWork(List<Long> ordered, int window, int maxAttempts, int placementBudget) {
        for (long key : ordered) waiting.add(new Task(key, 1));
        total = ordered.size();
        this.window = window;
        this.maxAttempts = maxAttempts;
        this.placementBudget = Math.max(1, placementBudget);
    }

    void tick(Access<C> access) {
        if (stopped) return;
        List<Task> ready = new ArrayList<>();
        Map<Long, C> confirmedLoaded = null;
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Task task = entry.getKey();
            Request request = entry.getValue();
            // 只观察已结束的任务。慢区块保留自己的槽和票据，不能拦住后面已就绪的区块。
            if (!request.future().isDone()) continue;
            RuntimeException problem = null;
            try {
                request.future().getNow(null);
                // 超额成功请求留在原位和原票据下，下个 tick 再取当时的 FULL 句柄。
                // 异常请求在此之前识别，不能被前面的就绪块长期挡住释放和重试。
                if (ready.size() >= placementBudget) continue;
                C loaded = access.loaded(task.key());
                if (loaded != null) {
                    ready.add(task);
                    // FULL 句柄只在本次服务端回调使用，重试和后续 tick 必须重新确认。
                    if (confirmedLoaded == null) confirmedLoaded = new LinkedHashMap<>();
                    confirmedLoaded.put(task.key(), loaded);
                    continue;
                }
                problem = new IllegalStateException("FULL chunk was not available after completion");
            } catch (RuntimeException failure) {
                problem = failure;
            }
            iterator.remove();
            request.release().run();
            retry(access, task, problem);
        }
        if (!ready.isEmpty()) {
            // 保持票据直到施工返回；异常也要释放，不得把已加载误报成已处理。
            Set<Long> settled;
            try {
                settled = access.place(confirmedLoaded);
            } finally {
                for (Task task : ready) inFlight.remove(task).release().run();
            }
            for (Task task : ready) {
                if (settled.contains(task.key())) {
                    processed++;
                } else {
                    // “加载且已尝试”不能算完工：只重试没结案的块，不重新发送成功块。
                    retry(access, task, new IllegalStateException("Loaded road construction did not settle"));
                }
            }
        }
        // 请求若同步失败也只消耗本 tick 的额度，避免一次扫完整条长路线。
        int slots = Math.min(window - inFlight.size(), waiting.size());
        for (int i = 0; i < slots; i++) {
            Task task = waiting.removeFirst();
            try {
                inFlight.put(task, access.request(task.key()));
            } catch (RuntimeException failure) {
                retry(access, task, failure);
            }
        }
    }

    private void retry(Access<C> access, Task task, RuntimeException failure) {
        boolean exhausted = task.attempt() >= maxAttempts;
        if (exhausted) {
            failures++;
            if (firstFailures.size() < 64) firstFailures.add(task.key());
        } else {
            waiting.addLast(new Task(task.key(), task.attempt() + 1));
        }
        access.failed(task.key(), failure, exhausted);
    }

    void stop() {
        // Future 属于区块引擎，可能有别的订阅者；撤自己的票据，不取消共享 Future。
        stopped = true;
        waiting.clear();
        for (Request request : inFlight.values()) request.release().run();
        inFlight.clear();
    }

    void pause() {
        // 主世界暂停时撤票，但暂停不是失败：恢复后同一批请求不扣重试次数。
        List<Task> submitted = new ArrayList<>(inFlight.keySet());
        for (Task task : submitted.reversed()) waiting.addFirst(task);
        for (Request request : inFlight.values()) request.release().run();
        inFlight.clear();
    }

    boolean done() { return waiting.isEmpty() && inFlight.isEmpty(); }
    int total() { return total; }
    int processed() { return processed; }
    int failures() { return failures; }
    List<Long> failureKeys() { return List.copyOf(firstFailures); }
}
