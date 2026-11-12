package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;

/**
 * 客户端显示配置
 */
public final class ClientConfig implements SubConfig {
    private boolean loadingTipsEnabled = true;
    private boolean loadingProgressEnabled = true;

    /**
     * 手绘路线编辑面板（HUD）是否显示（★ 2026-09-25 修 Codex R-9）。
     *
     * <h2>为什么放进配置</h2>
     * 原先它是 {@code RouteMapRenderer} 里的 {@code static volatile boolean} ——
     * **退出游戏就重置回 true**。玩家每次启动都要重按一次 K 收起面板。
     * 放进客户端配置后能持久化。
     *
     * <p>⚠️ 安全性已核实：{@code WorldgenFingerprintService} 的指纹只包含
     * **种子 + 噪声设置**，**不含** {@code client()} 这一段配置 ——
     * 所以在这里加字段**不会作废任何已生成的道路**（那会是很严重的副作用）。</p>
     */
    private boolean routeEditorHudEnabled = true;

    /**
     * 地图上「连接端点标记」是否显示（★ 2026-09-26 任务 A）。
     *
     * <p>那些 `?` 图标 = 连接端点被当成结构点。手绘路线一条 5 点拆成 4 条连接
     * ⇒ 最多 8 个 `?`，很乱。用户要求「**默认全部隐藏**，玩家开关控制」。</p>
     *
     * <p>注意这与 {@code routeEditorHudEnabled} 不同：那个默认开（新玩家要看提示），
     * 这个**默认关**（默认画面干净）。</p>
     */
    private boolean mapEndpointMarkersEnabled = false;

    @Override
    public void sanitize() {
        // 布尔字段无需校验
    }

    @Override
    public ClientConfig snapshot() {
        ClientConfig copy = new ClientConfig();
        copy.loadingTipsEnabled = this.loadingTipsEnabled;
        copy.loadingProgressEnabled = this.loadingProgressEnabled;
        copy.routeEditorHudEnabled = this.routeEditorHudEnabled;
        copy.mapEndpointMarkersEnabled = this.mapEndpointMarkersEnabled;
        return copy;
    }

    public boolean loadingTipsEnabled() { return loadingTipsEnabled; }
    public void setLoadingTipsEnabled(boolean v) { this.loadingTipsEnabled = v; }
    public boolean loadingProgressEnabled() { return loadingProgressEnabled; }
    public void setLoadingProgressEnabled(boolean v) { this.loadingProgressEnabled = v; }
    public boolean routeEditorHudEnabled() { return routeEditorHudEnabled; }
    public void setRouteEditorHudEnabled(boolean v) { this.routeEditorHudEnabled = v; }
    public boolean mapEndpointMarkersEnabled() { return mapEndpointMarkersEnabled; }
    public void setMapEndpointMarkersEnabled(boolean v) { this.mapEndpointMarkersEnabled = v; }
}
