/* 文件职责：**地图端点标记开关**（★ 2026-09-26 任务 A，用户要求）。 */
package net.shiroha233.roadweaver.client.map.data;

import net.shiroha233.roadweaver.config.ConfigService;

/**
 * **地图端点标记开关**（★ 2026-09-26 任务 A，用户要求）。
 *
 * <h2>要解决什么</h2>
 * 地图上那些 `?` 图标来自「连接端点被当成结构点」。一条 5 点的手绘路线
 * 会拆成 4 条连接 ⇒ 最多 8 个端点全在地图上显示成 `?`，看着很乱。
 *
 * <p>用户原话：
 * <blockquote>
 *   「所有的端点默认全部隐藏了这样更省事吧，玩家开关控制开启关闭」
 * </blockquote>
 * 所以做成**默认关闭、按键/按钮可开**。</p>
 *
 * <h2>状态存哪</h2>
 * 持久化到 {@code ClientConfig.mapEndpointMarkersEnabled} ——
 * 与 HUD 开关（{@code routeEditorHudEnabled}）同一套模式。
 *
 * <p>⚠️ 已核实：{@code WorldgenFingerprintService} 的指纹只包含
 * <b>种子 + 噪声设置</b>，<b>不含</b> {@code client()} 段配置 ——
 * 所以在这里加字段**不会作废任何已生成的道路**。</p>
 *
 * <h2>关掉时会怎样</h2>
 * 只是**不画端点图标**；连接线（蓝/黄/绿/红那几条）与真实结构
 * （村庄、出生小屋）**完全不受影响**。
 */
public final class MapMarkersVisible {

    private MapMarkersVisible() {
    }

    /** 端点标记是否可见（默认**关** —— 用户要求默认全部隐藏）。 */
    public static boolean enabled() {
        var cfg = ConfigService.get();
        if (cfg == null) {
            return false;   // 配置不可用时按默认（隐藏）
        }
        try {
            return cfg.client().mapEndpointMarkersEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static void set(boolean value) {
        var cfg = ConfigService.get();
        if (cfg == null) {
            return;
        }
        try {
            cfg.client().setMapEndpointMarkersEnabled(value);
            ConfigService.save();
        } catch (RuntimeException | LinkageError e) {
            // 保存失败不影响本次会话
        }
    }

    /** 切换，返回切换后的状态。 */
    public static boolean toggle() {
        boolean next = !enabled();
        set(next);
        return next;
    }
}
