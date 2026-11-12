/* 文件职责：绘制地图 Dock 使用的几何线性图标。 */
package net.shiroha233.roadweaver.client.map.render;

import net.minecraft.client.gui.GuiGraphics;

public final class MapIconRenderer {
    private MapIconRenderer() {}

    public static void render(GuiGraphics graphics, MapDockAction action, int centerX, int centerY, int color, int size) {
        int s = Math.max(12, size);
        int half = s / 2;
        int left = centerX - half;
        int top = centerY - half;
        int right = centerX + half;
        int bottom = centerY + half;
        int clip = s + 4;
        switch (action) {
            case SEARCH -> search(graphics, centerX, centerY, color, s, clip);
            case FILTER -> filter(graphics, left, top, right, bottom, color);
            case REFRESH -> refresh(graphics, centerX, centerY, color, s, clip);
            case MANUAL_CONNECT -> connect(graphics, centerX, centerY, color, s, clip);
            case DELETE_CONNECTION -> deleteConnection(graphics, centerX, centerY, color, s, clip);
            // ⚠️ 审计 P1-7：曾经漏掉这个 case。这是**语句 switch**（不是表达式），
            // Java 不要求穷尽，所以编译通过、运行时不画任何东西 ——
            // Dock 上「手绘路线」是功能主入口，却渲染成一个空白格子。
            case ROUTE_EDIT -> routeEdit(graphics, centerX, centerY, color, s, clip);
            // ★ 任务 A（2026-09-26）：端点标记开关图标。
            //   ⚠️ 这是**语句 switch**（不是表达式），Java 不要求穷尽 ——
            //   漏掉 case 会编译通过但渲染成空白格子（审计 P1-7 就是这么出的事）。
            case TOGGLE_MARKERS -> toggleMarkers(graphics, centerX, centerY, color, s, clip);
            case CONFIG -> config(graphics, centerX, centerY, color, s, clip);
            case CLOSE -> close(graphics, centerX, centerY, color, s, clip);
        }
    }

    /**
     * 「端点标记」图标：三个菱形标记点 + 一条连线（★ 任务 A）。
     *
     * <p>语义：地图上的那些端点图标开关。用"菱形标记"而不是圆点，
     * 与小地图/路线控制点的圆形区分开。</p>
     */
    private static void toggleMarkers(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int r = Math.max(3, size / 6);
        int spread = Math.max(5, size / 3);
        // 三个标记点（左上 / 右上 / 下中）
        diamond(g, cx - spread, cy - spread / 2, r, color);
        diamond(g, cx + spread, cy - spread / 2, r, color);
        diamond(g, cx, cy + spread, r, color);
        // 连线：让"这是标记集合"更好读
        line(g, cx - spread, cy - spread / 2, cx + spread, cy - spread / 2, color, 1, clip);
        line(g, cx, cy + spread, cx, cy - spread / 2, color, 1, clip);
    }

    /** 画一个小菱形（标记点）。 */
    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int i = 0; i <= r; i++) {
            int w = r - i;
            g.fill(cx - w, cy - i, cx + w + 1, cy - i + 1, color);
            g.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    private static void search(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int radius = Math.max(4, size / 3);
        line(g, cx - radius, cy - radius, cx + radius, cy + radius, color, 2, clip);
        line(g, cx + radius - 1, cy + radius - 1, cx + size / 2, cy + size / 2, color, 2, clip);
        eraseCenter(g, cx + 1, cy + 1, radius - 2);
    }

    private static void filter(GuiGraphics g, int left, int top, int right, int bottom, int color) {
        int cx = (left + right) / 2;
        int y = top + 3;
        line(g, left + 2, y, right - 2, y, color, 2, 20);
        line(g, left + 5, y + 5, right - 5, y + 5, color, 2, 20);
        line(g, left + 8, y + 10, right - 8, y + 10, color, 2, 20);
        line(g, cx, y + 10, cx, bottom - 1, color, 2, 20);
    }

    private static void refresh(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int radius = Math.max(5, size / 3);
        line(g, cx - radius, cy, cx - radius / 2, cy - radius, color, 2, clip);
        line(g, cx - radius / 2, cy - radius, cx + radius, cy - radius / 2, color, 2, clip);
        line(g, cx + radius, cy - radius / 2, cx + radius, cy + radius / 2, color, 2, clip);
        line(g, cx + radius, cy + radius / 2, cx + radius / 2, cy + radius, color, 2, clip);
        line(g, cx + radius / 2, cy + radius, cx - radius, cy + radius / 2, color, 2, clip);
        line(g, cx - radius, cy + radius / 2, cx - radius, cy, color, 2, clip);
        g.fill(cx - radius - 1, cy - 1, cx - radius + 3, cy + 3, color);
    }

    private static void connect(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int offset = Math.max(4, size / 3);
        line(g, cx - offset + 1, cy - offset + 1, cx + offset - 1, cy + offset - 1, color, 2, clip);
        g.fill(cx - offset - 2, cy - offset - 2, cx - offset + 3, cy - offset + 3, color);
        g.fill(cx + offset - 2, cy + offset - 2, cx + offset + 3, cy + offset + 3, color);
        g.fill(cx - offset, cy - offset, cx - offset + 1, cy - offset + 1, 0xFF1A1A1A);
        g.fill(cx + offset, cy + offset, cx + offset + 1, cy + offset + 1, 0xFF1A1A1A);
    }

    private static void deleteConnection(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int offset = Math.max(4, size / 3);
        line(g, cx - offset, cy - offset, cx - 2, cy - 2, color, 2, clip);
        line(g, cx + 2, cy + 2, cx + offset, cy + offset, color, 2, clip);
        g.fill(cx - offset - 2, cy - offset - 2, cx - offset + 3, cy - offset + 3, color);
        g.fill(cx + offset - 2, cy + offset - 2, cx + offset + 3, cy + offset + 3, color);
        line(g, cx - 3, cy + 3, cx + 3, cy - 3, color, 2, clip);
    }

    private static void config(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int radius = Math.max(4, size / 3);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            int x = cx + (int) Math.round(Math.cos(angle) * (radius + 2));
            int y = cy + (int) Math.round(Math.sin(angle) * (radius + 2));
            g.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
        g.fill(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1, color);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF1A1A1A);
    }

    private static void close(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int radius = Math.max(5, size / 3);
        line(g, cx - radius, cy - radius, cx + radius, cy + radius, color, 2, clip);
        line(g, cx + radius, cy - radius, cx - radius, cy + radius, color, 2, clip);
    }

    /**
     * 「手绘路线」图标：一条折线 + 三个控制点。
     *
     * <p>与 {@link #connect}（两点一条直线）视觉上区分开 ——
     * 那个是「把两个结构连起来」的旧手动模式，这个是「画任意多点折线」。</p>
     */
    private static void routeEdit(GuiGraphics g, int cx, int cy, int color, int size, int clip) {
        int r = Math.max(3, size / 5);
        // 三个控制点：左上 → 中下 → 右上（折线走势）
        int ax = cx - r * 2, ay = cy - r;
        int bx = cx, by = cy + r;
        int dx = cx + r * 2, dy = cy - r;
        // 折线
        line(g, ax, ay, bx, by, color, 2, clip);
        line(g, bx, by, dx, dy, color, 2, clip);
        // 端点方块（控制点）
        g.fill(ax - 2, ay - 2, ax + 2, ay + 2, color);
        g.fill(dx - 2, dy - 2, dx + 2, dy + 2, color);
        // 中间拐点用空心小方块，提示「这里是可编辑的中间点」
        g.fill(bx - 2, by - 2, bx + 2, by + 2, color);
        g.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFF1A1A1A);
    }

    private static void eraseCenter(GuiGraphics g, int cx, int cy, int radius) {
        if (radius > 0) {
            g.fill(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1, 0xFF1A1A1A);
        }
    }

    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int thickness, int clip) {
        RenderUtils.drawThickLine(g, x1, y1, x2, y2, color, thickness,
                x1 - clip, y1 - clip, x1 + clip, y1 + clip);
    }
}
