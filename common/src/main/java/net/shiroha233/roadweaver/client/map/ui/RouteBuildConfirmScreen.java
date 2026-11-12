package net.shiroha233.roadweaver.client.map.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shiroha233.roadweaver.client.render.RoadWeaverScreen;
import net.shiroha233.roadweaver.client.render.ScreenBackgrounds;

/**
 * 施工前**确认框**（★ UX-7，用户 2026-09-25 要求「弹确认框」）。
 *
 * <h2>为什么需要</h2>
 * 施工是个**有点代价**的操作：它会提交连接、后台生成道路、并（0.8.391 起）
 * <b>强制加载该路线经过的区块</b>。以前的交互是「点一下按钮/B 键 → 立刻提交」——
 * 玩家在没看清参数（尤其**会不会触发未探索区域地形生成**）的情况下就提交了。
 *
 * <h2>显示什么</h2>
 * <ul>
 *   <li>控制点数 / 路线长度 / 桥·隧道段数</li>
 *   <li><b>将加载的区块数</b>（UX-1 的 footprint）</li>
 *   <li>其中**未探索**多少 —— 有就**显式警告**（UX-3：会触发生成、明显更慢）</li>
 *   <li>预计耗时（按区块数与"是否含未探索"粗估）</li>
 * </ul>
 *
 * <h2>交互</h2>
 * 确认 / 取消两个按钮；回车=确认、Esc=取消。取消时不提交任何东西。
 */
public class RouteBuildConfirmScreen extends RoadWeaverScreen {

    /** 一行要显示的信息（文字 + 颜色）。 */
    public record Line(String text, int color) {
    }

    /** 确认回调；取消时不调用。 */
    private final Consumer<Boolean> onResult;
    private final Screen parent;
    private final List<Line> lines;

    public RouteBuildConfirmScreen(Screen parent,
                                   List<Line> lines,
                                   Consumer<Boolean> onResult) {
        super(Component.translatable("roadweaver.route.confirm.title"));
        this.parent = parent;
        this.lines = lines == null ? List.of() : lines;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        int bw = 100;
        int gap = 12;
        int totalW = bw * 2 + gap;
        int x = (this.width - totalW) / 2;
        int y = this.height / 2 + (Math.max(4, lines.size()) * 11) / 2 + 16;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("roadweaver.route.confirm.ok"), b -> finish(true))
                .bounds(x, y, bw, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.roadweaver.common.cancel"), b -> finish(false))
                .bounds(x + bw + gap, y, bw, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.render(graphics, this.width, this.height);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int top = this.height / 2 - (Math.max(4, lines.size()) * 11) / 2;

        graphics.drawCenteredString(this.font,
                Component.translatable("roadweaver.route.confirm.title"),
                cx, top - 24, 0xFFFFEE88);

        int y = top;
        for (Line line : lines) {
            graphics.drawCenteredString(this.font, Component.literal(line.text()), cx, y, line.color());
            y += 11;
        }
    }

    private void finish(boolean confirmed) {
        // 先切回父界面，再回调 —— 避免回调里再 setScreen 造成闪烁
        Minecraft.getInstance().setScreen(parent);
        if (onResult != null) {
            onResult.accept(confirmed);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {     // Enter
            finish(true);
            return true;
        }
        if (keyCode == 256) {                        // Esc
            finish(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        finish(false);
    }

    // ─────────────────── 便于组装的工厂方法 ───────────────────

    /**
     * 按路线参数算出要显示的行（★ 包含 UX-1 footprint 与 UX-3 未探索警告）。
     *
     * <p>采样上限：为避免卡界面，只对**控制点**计数区块（不跑完整平滑）；
     * 真正的预加载区块数由 {@code RouteChunkPreloader} 在服务端精算。</p>
     */
    public static List<Line> describe(int points,
                                      int roadSegments,
                                      int bridgeSegments,
                                      int tunnelSegments,
                                      double lengthBlocks,
                                      int approxChunks,
                                      int unexploredChunks) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(txt("points", "控制点") + ": " + points, 0xFFFFFFFF));
        out.add(new Line(txt("length", "路线长度") + ": " + Math.round(lengthBlocks) + " m", 0xFFCCCCCC));
        out.add(new Line(txt("segments", "分段") + ": " + roadSegments
                + "  " + txt("bridge", "桥") + ": " + bridgeSegments
                + "  " + txt("tunnel", "隧道") + ": " + tunnelSegments, 0xFFCCCCCC));
        // ★ UX-1：将加载的区块数（预加载 footprint 的近似）
        out.add(new Line(txt("chunks", "将加载区块") + ": ~" + approxChunks, 0xFF99CCFF));
        // ★ UX-3：未探索警告
        if (unexploredChunks > 0) {
            out.add(new Line("⚠ " + txt("unexplored_warn",
                            "含约 " + unexploredChunks + " 个未探索区块，会触发生成地形，明显更慢")
                    , 0xFFFFAA33));
        }
        out.add(new Line(txt("eta", "预计")
                + ": " + estimate(unexploredChunks, approxChunks), 0xFFBBBBBB));
        return out;
    }

    /** 粗估耗时（含未探索区块时按每块 ~2s，否则按每块 ~20ms 的二次加载）。 */
    private static String estimate(int unexplored, int total) {
        long ms = (long) unexplored * 2000L + (long) Math.max(0, total - unexplored) * 20L;
        if (ms < 3000) {
            return txt("eta_fast", "几秒（都是已生成区块）");
        }
        long sec = ms / 1000;
        if (sec < 60) {
            return txt("eta_sec", "约 " + sec + " 秒");
        }
        return txt("eta_min", "约 " + (sec / 60) + " 分 " + (sec % 60) + " 秒");
    }

    private static String txt(String key, String fallback) {
        return net.shiroha233.roadweaver.route.editor.RouteText
                .get("roadweaver.route.confirm." + key, fallback);
    }
}
