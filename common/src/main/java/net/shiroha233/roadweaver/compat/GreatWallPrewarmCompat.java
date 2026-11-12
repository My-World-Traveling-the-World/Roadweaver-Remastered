package net.shiroha233.roadweaver.compat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.api.incoming.GreatWallReadinessApi;

/**
 * Road-side half of the Great Wall startup gate: the road does not begin its initial generation while
 * the wall is still prewarming, so "river -> wall -> road" stays a strict serial order.
 *
 * <h2>Why the road waits at all</h2>
 * A measured session had the road start initial generation at 15:01:01 while the wall was still in its
 * fine sample (started 14:41:11, unfinished). Both then competed for the same cores, and the wall's
 * sampler - which had 12 lanes and was using about four of them - got slower still. Nothing made the
 * road wait: the wall's {@code WorldServices.awaitPrewarm} existed with no callers, and the road's
 * sources contained no reference to the wall at all.
 *
 * <h2>Never block a server thread</h2>
 * Everything here is asynchronous and non-blocking by construction. The readiness probe calls the
 * wall's own zero-timeout check ({@code awaitPrewarm(0, NANOSECONDS)}, which is exactly a "ready?"
 * question) and a shared daemon scheduler polls it; callers await a {@link CompletableFuture}.
 *
 * <p>The wait has no deadline of its own: a wall that has not settled has not chosen its corridor yet, and
 * a road planned first can be built straight through the ridge the wall then selects - waiting is the
 * point of the gate. Three things bound it. The wall opens it on every exit path (published, failed,
 * timed out, or never started at all). The gate itself samples the wall's progress snapshot and releases
 * the road after {@link #STALL_WINDOW_MILLIS} of a completely unchanged state, so a wall that silently
 * stops making progress can no longer park the road. And {@link #ABSOLUTE_CAP_MILLIS} caps the wait even
 * while the wall keeps reporting progress.
 *
 * <h2>Soft dependency</h2>
 * With no wall installed, {@link #isAvailable()} is false and every gate completes immediately, so
 * the road behaves exactly as it does today. Reflection failures are logged once and treated the same
 * way rather than disabling the road.
 */
public final class GreatWallPrewarmCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/GreatWallGate");

    /**
     * How long the road is willing to wait for the wall before it proceeds on its own.
     *
     * <p>{@code 0} means <b>no deadline</b>: the road waits until the wall settles. That is the
     * intended behaviour - a wall that has not finished its planning stage has not chosen its
     * corridor yet, and a road that plans first can end up cutting through the very ridge the wall
     * then picks. The wait is bounded in practice by the wall's own long-stop, which reports a
     * timeout and opens this gate on every exit path (published, failed, or timed out), so an
     * unbounded wait here cannot park the road forever.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 0L;

    /** Poll interval for the readiness probe. Small enough to look instant, large enough to be free. */
    private static final long POLL_MILLIS = 250L;

    /**
     * How long a wall that reports no prewarm progress at all is still waited for.
     *
     * <p>"No deadline" is only safe while the wall keeps working. A measured session had the wall's fine
     * sample fail at 23:33:26, after which the road sat on this gate for another four minutes until the
     * wall's own thirty-minute long-stop released it - and that long-stop is the only thing that made the
     * wait finite. This detector is the gate's own answer: the wall publishes a progress snapshot
     * ({@code WorldServices.prewarmSnapshot()}), the gate samples it every poll, and five minutes without
     * a single changed field means the wall is not going to open this gate on its own. Five minutes is
     * deliberately generous: the fine sample reports its column counter continuously and the compile
     * reports owner progress, so nothing legitimate stays silent that long.
     */
    /**
     * How long an unchanged wall state may last before the road gives up on it.
     *
     * <p>Zero - the default - means never: the road waits for the wall to open its gate, however
     * long that takes. A wall that dies still opens the gate (the wall side releases on its own
     * failure path), so this is a wait for the wall to be done, not a wait for it to succeed.
     * Set -Droadweaver.greatwallStallWindowMillis=300000 to restore the old bail-out.
     */
    /**
     * How long a first "ready" is disbelieved.
     *
     * <p>The wall's gate reads OPEN until it calls PrewarmProgress.request(), and the road can poll
     * inside that window. A measured session did: the road asked at world load, was told "ready", and
     * began laying at 14:28:39 while the wall was still scanning structures it had started at 14:25.
     * A wall that has really finished is minutes past this point, so one short grace closes the race
     * without ever delaying a genuine release.
     */
    private static final long FIRST_READY_GRACE_MILLIS = 5_000L;

    private static final long STALL_WINDOW_MILLIS =
            Long.getLong("roadweaver.greatwallStallWindowMillis", 600_000L);

    /** Absolute cap on the wait, even while the wall keeps reporting progress. */
    /**
     * Absolute cap on the wait, even while the wall keeps reporting progress.
     *
     * <p>Zero - the default - means no cap. Set -Droadweaver.greatwallAbsoluteCapMillis to bound it.
     */
    private static final long ABSOLUTE_CAP_MILLIS =
            Long.getLong("roadweaver.greatwallAbsoluteCapMillis", 1_800_000L);

    private static final ScheduledExecutorService POLLER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "roadweaver-greatwall-gate");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean probeFailureLogged;

    private GreatWallPrewarmCompat() {}



    /** {@return true when the Great Wall mod is installed, whether or not its probe resolved} */
    public static boolean wallPresent() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isModLoaded("plaincraft_great_wall");
        } catch (Throwable ignored) {
            return false;
        }
    }


    /** True when a Great Wall mod is present and it has published its readiness provider. */
    public static boolean isAvailable() {
        return GreatWallReadinessApi.isLinked();
    }

    /**
     * Non-blocking readiness question. True means the wall either finished prewarming, failed, or is
     * not installed; false means it is still working and the road should hold off.
     */
    public static boolean isPrewarmComplete() {
        // Fail closed, not open. A wall that is installed but whose provider never registered used to
        // answer "ready", which is how the road laid itself over a wall that had not sampled a column.
        // Being installed means the wall will publish; the road waits for it and says why once.
        if (!GreatWallReadinessApi.isLinked()) return !wallPresent();
        try {
            return GreatWallReadinessApi.isReady();
        } catch (RuntimeException | LinkageError failure) {
            // Fail closed: a probe that stops working while a wall is installed says nothing about whether
            // the wall published. Answering "ready" here let the road start on a broken probe - the one
            // failure mode that is worse than not gating at all, because it looks gated. The absolute cap
            // below still guarantees the road is not parked forever.
            if (!probeFailureLogged) {
                probeFailureLogged = true;
                LOGGER.warn("RoadWeaver could not query the Great Wall prewarm state; keeping the road "
                        + "gated until the wait cap expires.", failure);
            }
            return !wallPresent();
        }
    }

    /**
     * Gate the road's initial generation on the wall, without blocking the caller.
     *
     * <p>Completes as soon as the wall reports ready. A non-positive {@code timeoutMillis} means no
     * deadline: the road waits for as long as the wall is genuinely still working, which is what the
     * caller wants - the wall opens this gate on every exit path (published, failed, or its own
     * long-stop timeout), so the wait cannot park the road forever.
     */
    public static CompletableFuture<Void> awaitPrewarmAsync(long timeoutMillis) {
        // No fast path here. This early exit is what made the grace period above useless: it answered
        // "ready" before the poller (and therefore before the grace) ever ran, so a road that polled
        // in the window where the wall has not asked anyone to wait went straight to laying. The
        // poller below decides readiness, and it holds a first ready for FIRST_READY_GRACE_MILLIS.
        boolean unlimited = timeoutMillis <= 0L;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean();
        long startedAt = System.nanoTime();
        var lastSignature = new java.util.concurrent.atomic.AtomicReference<String>();
        var lastProgressAt = new java.util.concurrent.atomic.AtomicLong(startedAt);
        // 0.9.023（O-09 / N-03）：长城根本没装时不要打这行。
        // 真机实测：禁用长城后 23:30:42 打「waiting for the Great Wall prewarm」，23:30:47 就打
        // 「Great Wall prewarm finished」—— 实际只等了 5 秒（!wallPresent() ⇒ 立即为真），
        // 但两行并排会让人以为在等长城，排查时被带偏。
        if (wallPresent()) {
            if (unlimited) {
                LOGGER.info("RoadWeaver initial generation is waiting for the Great Wall prewarm "
                        + "(no deadline; the road waits for the wall's own gate).");
            } else {
                LOGGER.info("RoadWeaver initial generation is waiting for the Great Wall prewarm "
                        + "(timeout {} ms).", timeoutMillis);
            }
        }
        schedule(new Runnable() {
            @Override public void run() {
                if (settled.get()) return;
                if (isPrewarmComplete()) {
                    long seenFor = System.nanoTime() - startedAt;
                    if (seenFor < TimeUnit.MILLISECONDS.toNanos(FIRST_READY_GRACE_MILLIS)) {
                        // Do not trust a "ready" this early; ask again after the grace. See
                        // FIRST_READY_GRACE_MILLIS for the session that made this necessary.
                        if (!trySchedule(this, POLL_MILLIS) && settled.compareAndSet(false, true)) {
                            gate.complete(null);
                        }
                        return;
                    }
                    if (settled.compareAndSet(false, true)) {
                        // 0.9.047（D-34）：这句原来写死 "Great Wall prewarm finished"，但**没装长城时也照样打**
        // （`!wallPresent()` ⇒ isPrewarmComplete() 立刻为真，实际一个长城阶段都没跑）。
        // 改成两种情况下都成立的说法，避免拿这条日志当"长城预热真的跑过"的证据。
        LOGGER.info("Great Wall prewarm gate resolved (wallPresent={}); RoadWeaver initial generation may start.",
                wallPresent());
                        gate.complete(null);
                    }
                    return;
                }
                long now = System.nanoTime();
                if (!unlimited && now >= deadline) {
                    if (settled.compareAndSet(false, true)) {
                        LOGGER.warn("Great Wall prewarm did not finish within {} ms; "
                                + "RoadWeaver initial generation starts without it.", timeoutMillis);
                        gate.complete(null);
                    }
                    return;
                }
                // Liveness, not just completion: a wall that is still working keeps changing its snapshot,
                // and a wall that stopped is released here instead of holding the road until its own
                // long-stop. A snapshot that cannot be read at all (an older wall) leaves the detector
                // quiet and the absolute cap as the only bound.
                String signature = progressSignature();
                if (signature != null) {
                    if (!signature.equals(lastSignature.get())) {
                        lastSignature.set(signature);
                        lastProgressAt.set(now);
                    } else if (STALL_WINDOW_MILLIS > 0L && now - lastProgressAt.get() >= TimeUnit.MILLISECONDS.toNanos(STALL_WINDOW_MILLIS)) {
                        if (settled.compareAndSet(false, true)) {
                            LOGGER.warn("The Great Wall has reported no prewarm progress for {} s "
                                    + "(last state: {}); RoadWeaver initial generation starts without "
                                    + "waiting any longer.", STALL_WINDOW_MILLIS / 1000L, signature);
                            gate.complete(null);
                        }
                        return;
                    }
                }
                if (unlimited && ABSOLUTE_CAP_MILLIS > 0L && now - startedAt >= TimeUnit.MILLISECONDS.toNanos(ABSOLUTE_CAP_MILLIS)) {
                    if (settled.compareAndSet(false, true)) {
                        LOGGER.warn("Great Wall prewarm still running after {} minutes; RoadWeaver initial "
                                + "generation starts without it.", ABSOLUTE_CAP_MILLIS / 60_000L);
                        gate.complete(null);
                    }
                    return;
                }
                if (!trySchedule(this, POLL_MILLIS) && settled.compareAndSet(false, true)) {
                    // The gate must never stay pending because its own poll loop could not be re-armed.
                    LOGGER.warn("RoadWeaver's Great Wall gate poller stopped; initial generation proceeds "
                            + "without waiting for the wall.");
                    gate.complete(null);
                }
            }
        }, 0L);
        return gate;
    }

    /**
     * {@return the wall's progress as a change-detecting signature, or {@code null} when there is none}
     *
     * <p>The signature is now produced by the wall itself
     * ({@link net.shiroha233.roadweaver.api.outgoing.GreatWallReadiness#progressSignature()}) instead of
     * being assembled here from nine reflected record accessors. Same rule still applies: any failure
     * means "no signal", never "stalled" - a wall this build cannot read must not have its gate released
     * early, and a signature that moves on its own (e.g. one containing a clock) would silently disable
     * the stall detector.
     */
    private static String progressSignature() {
        return GreatWallReadinessApi.progressSignature();
    }


    /** Convenience overload using {@link #DEFAULT_TIMEOUT_MILLIS}. */
    public static CompletableFuture<Void> awaitPrewarmAsync() {
        return awaitPrewarmAsync(DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Gate used by the initial generation coordinator. Kept level-aware so a future per-dimension rule
     * has one place to live; today the wall only exists in the Overworld and other dimensions pass
     * straight through.
     */
    public static CompletableFuture<Void> awaitPrewarmForInitialGeneration(ServerLevel level) {
        if (level == null || !net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())) {
            return CompletableFuture.completedFuture(null);
        }
        return awaitPrewarmAsync();
    }

    private static void schedule(Runnable task, long delayMillis) {
        if (!trySchedule(task, delayMillis)) {
            // The poller is gone and this tick cannot be re-armed; releasing is the only answer that keeps
            // the road moving. (The previous shape called task.run() here, which re-scheduled itself from
            // inside the same failing path and ended up throwing out of the gate's own poll loop.)
            LOGGER.warn("RoadWeaver's Great Wall gate poller is unavailable; "
                    + "initial generation proceeds without waiting for the wall.");
        }
    }

    /** {@return false when the poll cannot be armed, which the caller must treat as "let the road pass"} */
    private static boolean trySchedule(Runnable task, long delayMillis) {
        try {
            POLLER.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable rejected) {
            return false;
        }
    }

}
