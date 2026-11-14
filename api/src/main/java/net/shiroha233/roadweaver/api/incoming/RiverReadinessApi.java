/* 文件职责：我们暴露给别的 mod 的河流就绪订阅：注册/通知/注销监听器。 */
package net.shiroha233.roadweaver.api.incoming;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「河流就绪」事件总线：河流 mod 广播，依赖河流的 mod 订阅。
 *
 * <h2>为什么这个事件不经过 RoadWeaver 的后端</h2>
 *
 * <p>「河流就绪」的<b>提供方是河流 mod（streams），消费方是长城</b>，道路只是这个契约
 * 的宿主（契约 jar 由道路的 Jar-in-Jar 提供），<b>它不参与触发时机</b>。</p>
 *
 * <p>这一点是踩过的坑：改造前 {@code PlanningLifecycleApi.onRiversPrewarmed} 由道路
 * 在「结构扫描之后」的河流步骤里触发，而那时长城早已自己预热完毕，事件被幂等吞掉；
 * 真正管用的是 streams 里一条 {@code Class.forName} 硬编码直连。把事件留在道路后端上
 * 会同时继承两个问题：触发时机不对，以及「道路后端尚未注入时注册会丢」。</p>
 *
 * <p>所以这里用 <b>api 模块内的静态注册表</b>，不依赖 {@code RoadWeaverBackend}：
 * api 模块由 {@code depends} 保证先于所有消费方加载，注册表一定先于任何
 * {@code addListener} 调用存在，不存在「后端没准备好」这个状态。</p>
 *
 * <h2>晚订阅也能拿到</h2>
 *
 * <p>已就绪之后再 {@link #addListener} 的监听器会<b>立即补一次</b>回调。Fabric 不保证
 * 各 mod 的初始化顺序，长城完全可能比河流晚注册；不做补发就会重现「以为注册了其实
 * 永远等不到」那类静默失效。</p>
 */
public final class RiverReadinessApi {

    private static final List<RiverReadinessListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 非 {@code null} 表示已经有提供方报告过就绪；用于给晚订阅者补发。 */
    private static volatile String readyProviderId;

    private RiverReadinessApi() {
    }

    /**
     * 注册监听器。若河流已经就绪，<b>立即以当前提供方 id 补发一次</b>。
     *
     * @return {@code true} 表示已接受；{@code false} 仅当传入 {@code null}
     */
    public static boolean addListener(RiverReadinessListener listener) {
        if (listener == null) {
            return false;
        }
        LISTENERS.add(listener);
        String provider = readyProviderId;
        if (provider != null) {
            notifyOne(listener, provider);
        }
        return true;
    }

    /** 注销监听器。 */
    public static void removeListener(RiverReadinessListener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.remove(listener);
    }

    /** 河流是否已就绪（至少有一个提供方报告过，且此后没有 {@link #reset()}）。 */
    public static boolean isReady() {
        return readyProviderId != null;
    }

    /** 最近一次报告就绪的提供方 id；未就绪时为 {@code null}。 */
    public static String readyProviderId() {
        return readyProviderId;
    }

    /** 当前监听器数量（诊断用）。 */
    public static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * 广播「河流已就绪」。<b>由提供方经
     * {@link net.shiroha233.roadweaver.api.outgoing.RiverReadinessBroadcast} 调用</b>，
     * 消费方不要直接调。
     *
     * <p><b>每次都广播，不做幂等抑制</b>：一个存档里河流可能因为退出重进而再次就绪，
     * 消费者（长城）自身按存档幂等，重复通知无害；反过来做抑制会让「第二个存档」
     * 永远收不到事件。</p>
     *
     * @return 本次被成功通知的监听器数量
     */
    public static int markReady(String providerId) {
        String id = (providerId == null || providerId.isEmpty()) ? "unknown" : providerId;
        readyProviderId = id;
        int notified = 0;
        for (RiverReadinessListener listener : LISTENERS) {
            if (notifyOne(listener, id)) {
                notified++;
            }
        }
        return notified;
    }

    /**
     * 清空就绪状态与监听器。<b>仅测试用，以及世界卸载时的显式回收。</b>
     *
     * <p>只清状态、<b>不</b>回调任何东西 —— 监听器注销由它们自己的生命周期负责。</p>
     */
    public static void reset() {
        readyProviderId = null;
    }

    /** 清空监听器。仅测试用。 */
    public static void clearListenersForTest() {
        LISTENERS.clear();
        readyProviderId = null;
    }

    private static boolean notifyOne(RiverReadinessListener listener, String providerId) {
        try {
            listener.onRiversReady(providerId);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            // 一个订阅方坏掉不能连累其它订阅方，更不能让广播方失败。
            return false;
        }
    }
}
