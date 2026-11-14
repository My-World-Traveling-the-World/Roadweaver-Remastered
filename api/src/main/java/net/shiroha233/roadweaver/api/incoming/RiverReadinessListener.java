/* 文件职责：河流就绪回调契约（只一个方法，避免把内部状态漏给消费方）。 */
package net.shiroha233.roadweaver.api.incoming;

/**
 * 「河流就绪」回调。
 *
 * <p>由依赖河流板块的 mod 实现并注册到 {@link RiverReadinessApi}。典型订阅方是长城：
 * 它要等河流板块全部落地之后才开始预热，否则会在还没成型的水面上布墙。</p>
 */
@FunctionalInterface
public interface RiverReadinessListener {

    /**
     * 河流板块已全部生成完毕，可以开始依赖河流的工作。
     *
     * @param providerId 报告就绪的提供方 id（例如 {@code "streams"}）。带上它是为了日志与
     *                   排查 —— 过去这条链是硬编码反射直连，出问题时无法判断到底是谁
     *                   在什么时候报的，只能靠猜。
     */
    void onRiversReady(String providerId);
}
