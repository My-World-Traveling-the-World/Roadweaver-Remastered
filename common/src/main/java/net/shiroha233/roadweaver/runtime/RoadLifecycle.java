/* 文件职责：服务端停机期间异步道路工作线程的全局写闸门。 */
package net.shiroha233.roadweaver.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** 停服时全局写门阻止异步旧任务继续持久化，不能只靠Future取消。 */
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
