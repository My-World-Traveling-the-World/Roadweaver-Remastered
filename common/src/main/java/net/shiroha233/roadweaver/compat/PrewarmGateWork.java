/* 文件职责：独立推进预热门，允许用受控时钟验证等待、超时和取消。 */
package net.shiroha233.roadweaver.compat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 每次只提交下一次非阻塞查询，避免等候占用工作线程；不持有世界对象。 */
final class PrewarmGateWork {
    interface Probe {
        boolean required();
        boolean ready();
        String signature();
    }

    @FunctionalInterface
    interface Scheduler {
        boolean schedule(Runnable task, long delayMillis);
    }

    enum Resolution { ABSENT, READY, TIMEOUT, STALLED, ABSOLUTE_CAP, POLLER_UNAVAILABLE }

    record Limits(long pollMillis, long firstReadyGraceMillis, long stallMillis, long absoluteMillis) {}

    private final Probe probe;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Consumer<Resolution> resolved;
    private final Limits limits;
    private final long timeoutMillis;
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    private final long startedAt;
    private long lastProgressAt;
    private String lastSignature;

    private PrewarmGateWork(Probe probe, Scheduler scheduler, LongSupplier clock,
                            Consumer<Resolution> resolved, Limits limits, long timeoutMillis) {
        this.probe = probe;
        this.scheduler = scheduler;
        this.clock = clock;
        this.resolved = resolved;
        this.limits = limits;
        this.timeoutMillis = timeoutMillis;
        startedAt = clock.getAsLong();
        lastProgressAt = startedAt;
    }

    static CompletableFuture<Void> await(Probe probe, Scheduler scheduler, LongSupplier clock,
                                          Consumer<Resolution> resolved, Limits limits, long timeoutMillis) {
        PrewarmGateWork work = new PrewarmGateWork(probe, scheduler, clock, resolved, limits, timeoutMillis);
        // 首次 ready 的宽限只防已安装长城的启动竞态；没有提供方不应凭空等五秒。
        if (!probe.required()) work.finish(Resolution.ABSENT);
        else work.arm(0L);
        return work.result;
    }

    private void poll() {
        // 取消后不再读外部状态或重排队，防止上个存档的等待继续轮询。
        if (result.isDone()) return;
        long now = clock.getAsLong();
        long elapsed = now - startedAt;
        if (probe.ready()) {
            if (elapsed >= nanos(limits.firstReadyGraceMillis())) finish(Resolution.READY);
            else arm(limits.pollMillis());
            return;
        }
        // 比较经过时间而不是绝对截止时刻相加，避免 nanoTime 加大超时值溢出。
        if (timeoutMillis > 0L && elapsed >= nanos(timeoutMillis)) {
            finish(Resolution.TIMEOUT);
            return;
        }
        String signature = probe.signature();
        if (signature != null) {
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                lastProgressAt = now;
            } else if (limits.stallMillis() > 0L && now - lastProgressAt >= nanos(limits.stallMillis())) {
                finish(Resolution.STALLED);
                return;
            }
        }
        // 无签名只表示无法观测，不能把旧版提供方或故障探针误判成停滞。
        if (timeoutMillis <= 0L && limits.absoluteMillis() > 0L
                && elapsed >= nanos(limits.absoluteMillis())) {
            finish(Resolution.ABSOLUTE_CAP);
            return;
        }
        arm(limits.pollMillis());
    }

    private void arm(long delayMillis) {
        if (result.isDone()) return;
        boolean accepted;
        try {
            accepted = scheduler.schedule(this::poll, delayMillis);
        } catch (RuntimeException | LinkageError unavailable) {
            accepted = false;
        }
        // 首次提交和后续提交共用结案路径；仅打印警告会留下永不完成的 Future。
        if (!accepted) finish(Resolution.POLLER_UNAVAILABLE);
    }

    private void finish(Resolution reason) {
        if (result.isDone()) return;
        try {
            resolved.accept(reason);
        } finally {
            // 诊断失败也不能把已经有结论的门留在等待态。
            result.complete(null);
        }
    }

    private static long nanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
