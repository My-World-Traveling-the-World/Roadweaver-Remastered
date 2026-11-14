/* 文件职责：我们暴露给别的 mod 的规划生命周期订阅：注册/派发/注销监听器。 */
package net.shiroha233.roadweaver.api.incoming;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.RoadWeaverBackend;

/**
 * 注册初始生成阶段回调。
 *
 * <p>用法（在自己的 mod 初始化里）：</p>
 * <pre>
 * PlanningLifecycleApi.addListener(new PlanningLifecycleListener() {
 *     &#64;Override public void onInitialGenerationStart() { ... }
 *     &#64;Override public void onInitialGenerationEnd(InitialGenerationSummary s) { ... }
 * });
 * </pre>
 *
 * <h2>注册时机：为什么允许「后端还没注入」时先排队</h2>
 *
 * <p><b>这是一个真实踩过的坑。</b>Fabric 的 {@code depends} 只保证<b>契约 mod</b>
 * （{@code roadweaver_api}）先加载，<b>不保证 RoadWeaver 自己的 {@code onInitialize}
 * 早于别人的</b>。实测某局：</p>
 * <pre>
 * 10:47:47  [GreatWall] Common entrypoint loaded      ← 长城先跑
 * 10:47:50  Initializing RoadWeaver (Fabric)          ← 道路 3 秒后才注入后端
 * 10:47:51  lifecycle listener registered (total=1)   ← 只有 streams 注册上
 * </pre>
 * <p>长城早 3 秒，那时 {@code backend} 还是 {@code null}，旧实现直接返回
 * {@code false} → 调用方吞掉返回值 → <b>回调静默丢失</b>，河流预热再也不会触发。
 * 这类「以为注册了其实没注册」的失效最难排查。</p>
 *
 * <p>现在改成：后端未就绪时<b>先排队</b>，等 RoadWeaver 调
 * {@link RoadWeaverApi#installBackend} 时由 {@link #flushPending} 补注册。
 * 排队是 api 模块自己做的 —— 它由 {@code depends} 保证先于任何消费方加载，
 * 所以队列一定先于所有 {@code addListener} 调用存在。</p>
 */
public final class PlanningLifecycleApi {

    /** 后端尚未注入时排队的监听器。写入与遍历都在 CopyOnWriteArrayList 上，线程安全。 */
    private static final List<PlanningLifecycleListener> PENDING = new CopyOnWriteArrayList<>();

    private PlanningLifecycleApi() {
    }

    /**
     * 注册监听器。
     *
     * <p>后端已注入时立即注册；未注入时<b>排队</b>，等 {@link RoadWeaverApi#installBackend}
     * 之后自动补注册。</p>
     *
     * @return {@code true} 表示<b>已被接受</b>（立即注册成功，或已排队等待补注册）；
     *         {@code false} 仅当传入 {@code null}，或后端在注册时抛异常
     */
    public static boolean addListener(PlanningLifecycleListener listener) {
        if (listener == null) {
            return false;
        }
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            PENDING.add(listener);
            return true;
        }
        return deliver(b, listener);
    }

    /** 注销监听器。排队中的也一并移除。 */
    public static void removeListener(PlanningLifecycleListener listener) {
        if (listener == null) {
            return;
        }
        PENDING.remove(listener);
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.removeLifecycleListener(listener);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /**
     * 把排队中的监听器补注册进后端。<b>内部用</b>，由
     * {@link RoadWeaverApi#installBackend} 在后端注入后调用，消费方不要自己调。
     *
     * <p>补注册失败（后端抛异常）的监听器会被丢弃 —— 后端已经坏了，重试没有意义。</p>
     */
    public static void flushPending(RoadWeaverBackend backend) {
        if (backend == null || PENDING.isEmpty()) {
            return;
        }
        for (PlanningLifecycleListener listener : PENDING) {
            deliver(backend, listener);
        }
        PENDING.clear();
    }

    /**
     * 当前排队中的监听器数量。<b>内部用</b>，供诊断与测试。
     *
     * <p>正常应为 {@code 0}；若长期大于 0，说明 RoadWeaver 没有注入后端
     * （即道路本体没装），这些回调永远不会触发。</p>
     */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** 清空队列。仅测试用。 */
    public static void clearPendingForTest() {
        PENDING.clear();
    }

    private static boolean deliver(RoadWeaverBackend backend, PlanningLifecycleListener listener) {
        try {
            backend.addLifecycleListener(listener);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
