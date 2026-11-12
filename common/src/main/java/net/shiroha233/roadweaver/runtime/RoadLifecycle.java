package net.shiroha233.roadweaver.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Global write gate for async road workers during server shutdown. */
public final class RoadLifecycle {
    private static final AtomicBoolean STOPPING = new AtomicBoolean();

    private RoadLifecycle() {
    }

    public static void beginStarting() {
        STOPPING.set(false);
    }

    public static void onServerStarted() {
        STOPPING.set(false);
    }

    public static void beginStopping() {
        STOPPING.set(true);
    }

    public static boolean allowsWrites() {
        return !STOPPING.get();
    }

    public static boolean isStopping() {
        return STOPPING.get();
    }
}
