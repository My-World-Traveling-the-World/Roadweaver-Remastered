/* 文件职责：河流就绪的一次性广播：发布就绪、查是否就绪与监听器数量、可重置。 */
package net.shiroha233.roadweaver.api.outgoing;

import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessListener;

/**
 * 「河流就绪」的<b>广播端</b>：由河流提供方调用，其它 mod 用
 * {@link RiverReadinessApi} + {@link RiverReadinessListener} 订阅。
 *
 * <p>用法（在河流 mod 里，河流板块全部落地之后）：</p>
 * <pre>
 * int fanOut = RiverReadinessBroadcast.publishReady(RiverReadinessBroadcast.PROVIDER_STREAMS);
 * </pre>
 *
 * <p>替代的是改造前那种 {@code Class.forName("local.plaincraft.greatwall.lifecycle.WorldServices")}
 * 硬编码反射直连：那种写法下游一改名就<b>静默失效</b>，而且编译期毫无保护。</p>
 */
public final class RiverReadinessBroadcast {

    /** 已知提供方 id：河流 mod。注册表与日志里都用它，不要改。 */
    public static final String PROVIDER_STREAMS = "streams";

    private RiverReadinessBroadcast() {
    }

    /**
     * 报告「河流已就绪」。
     *
     * <p>可以安全地重复调用：消费者按自己的粒度幂等，重复通知无害；而抑制重复会让
     * 第二个存档永远收不到事件。</p>
     *
     * @param providerId 提供方 id，建议用 {@link #PROVIDER_STREAMS}
     * @return 本次被成功通知的订阅方数量
     */
    public static int publishReady(String providerId) {
        return RiverReadinessApi.markReady(providerId);
    }

    /** 当前已就绪？供提供方自查。 */
    public static boolean isReady() {
        return RiverReadinessApi.isReady();
    }

    /** 当前订阅方数量，供日志排查。 */
    public static int listenerCount() {
        return RiverReadinessApi.listenerCount();
    }

    /**
     * 清掉「已就绪」标记。<b>建议在服务器停止时调用</b>，让下一个存档从干净状态开始，
     * 这样 {@link #isReady()} 不会跨存档残留。
     *
     * <p>只清状态，不回调任何东西；订阅方列表保持不动（它们是 mod 生命周期的一部分，
     * 不是存档生命周期的一部分）。</p>
     */
    public static void reset() {
        RiverReadinessApi.reset();
    }
}
