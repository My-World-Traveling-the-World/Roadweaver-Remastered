package net.shiroha233.roadweaver.pathfinding.impl;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;

/**
 * 基于 ThreadLocal 的 CPU 节流工具，控制寻路线程占空比
 */
public final class ThrottleHelper {
    private ThrottleHelper() {}

    private static final ThreadLocal<Long> WORK_START = ThreadLocal.withInitial(System::currentTimeMillis);

    /**
     * P0-7: honours the configured duty cycle. The four pathfinders used to
     * pass {@code RoadConstants.DEFAULT_DUTY_CYCLE} (50) explicitly, so a player
     * who raised {@code performance.threadDutyCycle} to 100 to disable throttling
     * was still silently capped at half speed outside initial generation.
     */
    public static void throttle() {
        int duty = RoadConstants.DEFAULT_DUTY_CYCLE;
        try {
            duty = ConfigService.get().performance().threadDutyCycle();
        } catch (Throwable ignored) {
        }
        throttle(duty);
    }

    public static void throttle(int duty) {
        if (InitialGenManager.isActive() || RuntimeLoadPolicy.isChunkyPreflightActive()) return;
        if (duty >= RoadConstants.DUTY_CYCLE_MAX) return;
        if (duty <= 0) duty = RoadConstants.DEFAULT_DUTY_CYCLE;
        long now = System.currentTimeMillis();
        long elapsed = now - WORK_START.get();
        if (elapsed >= RoadConstants.WORK_PERIOD_MS) {
            long sleepMs = (long) (RoadConstants.WORK_PERIOD_MS * (100.0 - duty) / duty);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(Math.min(sleepMs, RoadConstants.MAX_SLEEP_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            WORK_START.set(System.currentTimeMillis());
        }
    }

    public static void resetThrottle() {
        WORK_START.set(System.currentTimeMillis());
    }

    public static void clearThrottle() {
        WORK_START.remove();
    }
}
