package net.shiroha233.roadweaver.api.outgoing;

/**
 * streams 采样线程控制通道：<b>由 streams 实现并注册</b>，RoadWeaver 调用。
 *
 * <p>现在 RoadWeaver 用反射写 streams 的静态方法：</p>
 * <pre>
 * "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkySupplyThreads"
 * "local.plaincraft.streamscontrol.StreamsSamplerModeController", "setChunkyExclusive"
 * </pre>
 * <p>这是<b>命令</b>方向（我们写别人），方法名一漂移就静默失效。</p>
 *
 * <p>注册方式：{@link RoadLoadSignalsRegistry#registerStreamsThreadControl}。</p>
 */
public interface StreamsThreadControl {

    /**
     * 设置给 Chunky 的供应线程数。
     *
     * @param threads 线程数，{@code <= 0} 表示交回 streams 自己决定
     */
    void setChunkySupplyThreads(int threads);

    /**
     * 进入 / 退出 Chunky 独占期。
     *
     * @param exclusive {@code true} 表示进入独占期（道路应让路）
     */
    void setChunkyExclusive(boolean exclusive);
}
