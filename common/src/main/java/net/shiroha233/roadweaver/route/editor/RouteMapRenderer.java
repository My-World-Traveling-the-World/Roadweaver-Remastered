/* 文件职责：把玩家手绘路线画在地图上 —— 曲线、控制点句柄、选中高亮、拐点提示。
 *
 * 只画「控制点之间的插值曲线」+ 控制点句柄；密集采样点留给世界内 3D 渲染，
 * 因为地图是俯视的、密集点在视觉上和曲线没区别，而**控制点必须可拖动**。
 */
package net.shiroha233.roadweaver.route.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shiroha233.roadweaver.client.map.render.RenderUtils;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteKind;
import net.shiroha233.roadweaver.route.RoutePoint;
import net.shiroha233.roadweaver.route.RouteSmoother;

/**
 * 把玩家手绘路线画在地图上。
 *
 * <h2>画什么</h2>
 * <ol>
 *   <li><b>平滑后的曲线</b> —— 按段的 {@link RouteKind} 上色，
 *       这样玩家一眼能看出「这段是路、那段是桥」。
 *       曲线只连控制点，不画密集采样点（密集点留给世界内 3D 渲染）。</li>
 *   <li><b>控制点句柄</b> —— 每个控制点画一个方块，可拖动。
 *       端点画大一点（区别于中间点）。</li>
 *   <li><b>选中高亮</b> —— 当前选中的点画白框。</li>
 *   <li><b>拐点提示</b> —— 类型切换处画一个小三角，提示「这里换类型了」。</li>
 * </ol>
 *
 * <h2>为什么不直接画平滑后的密集点</h2>
 * 地图是俯视的，密集点和曲线视觉上一样；但**控制点必须可见**
 * —— 因为玩家要拖它们。所以这里只画「控制点之间的插值曲线」+ 控制点句柄。
 */
public final class RouteMapRenderer {

    /** 端点句柄边长（像素）。 */
    private static final int ENDPOINT_HANDLE = 7;
    /** 中间点句柄边长（像素）。 */
    private static final int MIDPOINT_HANDLE = 5;
    /** 选中框的额外留白。 */
    private static final int SELECT_PAD = 3;
    /** 曲线绘制精度：每个控制点区间插几个点。 */
    private static final int CURVE_STEPS = 6;

    private static final int COLOR_HANDLE_FILL = 0xFFF0F0F0;
    private static final int COLOR_HANDLE_BORDER = 0xFF202020;
    private static final int COLOR_SELECTED = 0xFFFF3B30;
    private static final int COLOR_HEIGHT_LABEL = 0xFFFFEE88;
    /** 控制点序号颜色（与高度数字区分：序号用白，高度用黄）。 */
    private static final int COLOR_INDEX_LABEL = 0xFFFFFFFF;

    /** 点对点直连线的颜色（淡灰，只作"我连了哪两个点"的提示，不抢曲线视觉）。 */
    private static final int COLOR_CONNECT_HINT = 0x8099AABB;

    /** `- - - -` 虚线预览：实线段长与间隔（像素）。 */
    private static final int DASH_LEN = 5;
    private static final int DASH_GAP = 4;

    private RouteMapRenderer() {
    }

    /**
     * 渲染整条路线。
     *
     * @param g            绘图上下文
     * @param state        编辑器状态（提供路线与选中项）
     * @param toScreenX    blockX → 屏幕 X
     * @param toScreenY    blockZ → 屏幕 Y
     * @param isInView     某个 (x,z) 是否在视野内（做剔除）
     * @param left         裁剪矩形
     * @param showHeights  是否在点上标高度数字
     * @param mouseX       鼠标屏幕 X（画「下一段会连到哪」的虚线预览用；越界/负数表示不画）
     * @param mouseY       鼠标屏幕 Y
     * @return 每个控制点的屏幕坐标 [x0,y0,x1,y1,...]，供命中检测复用
     */
    public static List<Integer> render(GuiGraphics g, RouteEditorState state,
                                       IntUnaryOperator toScreenX,
                                       IntUnaryOperator toScreenY,
                                       java.util.function.BiPredicate<Integer, Integer> isInView,
                                       int left, int top, int right, int bottom,
                                       boolean showHeights,
                                       int mouseX, int mouseY) {
        List<Integer> screenXY = new ArrayList<>();
        PlayerRoute route = state.route();
        if (route == null || route.isEmpty()) {
            return screenXY;
        }

        List<RoutePoint> pts = route.points();

        // ── ① 先把所有控制点的屏幕坐标算出来 ──
        int n = pts.size();
        int[] sx = new int[n];
        int[] sy = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = pts.get(i).pos();
            sx[i] = toScreenX.applyAsInt(p.getX());
            sy[i] = toScreenY.applyAsInt(p.getZ());
            screenXY.add(sx[i]);
            screenXY.add(sy[i]);
        }

        // ── ①b 点对点直连线（★ A3：看清"这个点连的是哪个点"）──        //
        // 用户要求：「平面上也要显示那个点连接的哪个」。
        // 平滑曲线是**最终形态**，弯曲之后看不出「谁连谁」——
        // 尤其 S 形/绕行时，玩家分不清第 3 点是连第 4 点还是被平滑甩到了别处。
        // 所以先用淡灰虚线画出**原始折线**（控制点直连），再叠平滑曲线。
        //
        // 用虚线而不是实线：实线会和"最终形态"的平滑曲线混淆，
        // 虚线一眼就能区分「这是我点的折线」vs「这是会建成的曲线」。
        for (int i = 0; i + 1 < n; i++) {
            RenderUtils.drawDashedLine(g, sx[i], sy[i], sx[i + 1], sy[i + 1],
                    COLOR_CONNECT_HINT, DASH_LEN, DASH_GAP, left, top, right, bottom);
        }

        // ── ①c 「下一段」橡皮筋预览（★ A2：- - - - 跟着鼠标走）──
        //
        // 用户要求：「画的时候要有个 - - - - 这样的线段预览」。
        // 从**最后一个控制点**拉一条虚线到鼠标位置，让玩家在落点之前
        // 就看清「这一段会往哪走、多长」。
        //
        // 只在「鼠标落在地图绘制区内」时画 —— 移出去就别拉了
        // （否则虚线会从路线尾巴一直甩到工具栏上，很干扰）。
        // 注意这里比较的是**屏幕坐标**（mouseX/mouseY 与 left/top/right/bottom 同系），
        // 不能用 isInView（那个吃的是世界坐标）。
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            int anchorX = sx[n - 1];
            int anchorY = sy[n - 1];
            // 用**当前类型色**画，于是"我现在要画桥"一眼可见
            int previewColor = 0xFF000000 | state.currentKind().editorColor();
            RenderUtils.drawDashedLine(g, anchorX, anchorY, mouseX, mouseY,
                    previewColor, DASH_LEN + 1, DASH_GAP, left, top, right, bottom);
        }

        // ── ② 画曲线（按段上色，类型切换处自然断开）──
        for (int i = 0; i + 1 < n; i++) {
            RouteKind kind = pts.get(i).kind();
            int color = kind.editorColor();
            drawSmoothSegment(g, sx[i], sy[i], sx[i + 1], sy[i + 1],
                    i > 0 ? sx[i - 1] : sx[i], i > 0 ? sy[i - 1] : sy[i],
                    i + 2 < n ? sx[i + 2] : sx[i + 1],
                    i + 2 < n ? sy[i + 2] : sy[i + 1],
                    color, left, top, right, bottom);
        }

        // ── ③ 类型切换处画小三角（提示玩家这里换类型了）──
        for (int i = 1; i < n; i++) {
            if (pts.get(i - 1).kind() != pts.get(i).kind()) {
                RenderUtils.fillTriangle(g,
                        sx[i], sy[i] - 9,
                        sx[i] - 4, sy[i] - 15,
                        sx[i] + 4, sy[i] - 15,
                        0xFFFFFFFF, left, top, right, bottom);
            }
        }

        // ── ④ 画控制点句柄 ──
        for (int i = 0; i < n; i++) {
            boolean endpoint = (i == 0 || i == n - 1);
            boolean selected = (i == state.selectedPointIndex());
            int size = endpoint ? ENDPOINT_HANDLE : MIDPOINT_HANDLE;
            int half = size / 2;

            // 选中：先画一个更大的红框
            if (selected) {
                RenderUtils.drawPoint(g, sx[i], sy[i], size + SELECT_PAD * 2,
                        COLOR_SELECTED, left, top, right, bottom);
            }
            RenderUtils.drawPoint(g, sx[i], sy[i], size,
                    COLOR_HANDLE_FILL, left, top, right, bottom);
            // 边框：用四条线围出来（drawPoint 是实心方块，这里补一个描边效果）
            if (endpoint) {
                RenderUtils.drawLine(g, sx[i] - half, sy[i] - half, sx[i] + half, sy[i] - half,
                        COLOR_HANDLE_BORDER, left, top, right, bottom);
                RenderUtils.drawLine(g, sx[i] - half, sy[i] + half, sx[i] + half, sy[i] + half,
                        COLOR_HANDLE_BORDER, left, top, right, bottom);
            }
        }

        // ── ⑤ 控制点序号（★ A1：1 2 3 …，与 3D 世界内编号一致）──
        //
        // 用户要求：「按照 1 2 3 阶段数字显示」。
        // 3D 世界渲染器（RouteWorldRenderer）早已有编号，地图这边一直缺 ——
        // 于是玩家在地图上画的时候只能数格子，没法说"把第 3 个点往左挪"。
        // 这里补齐，编号口径与 3D 完全一致（i + 1）。
        //
        // 位置：画在句柄**左上方**（高度数字占了右下方 +8/-4 的位置，
        // 两者错开才不会叠在一起）。
        var font = net.minecraft.client.Minecraft.getInstance().font;
        for (int i = 0; i < n; i++) {
            String idx = Integer.toString(i + 1);
            drawClampedLabel(g, font, idx, sx[i] - 6 - font.width(idx), sy[i] + 6,
                    COLOR_INDEX_LABEL, left, top, right, bottom);
        }

        // ── ⑥ 高度数字（可选）──
        // ★ A4：加边界钳制，修「标签画到工具条上」（真机截图实证）。
        //   原来直接画在 (sx+8, sy-4)，路线靠近屏幕底部时数字会压到工具条/状态栏上。
        if (showHeights) {
            for (int i = 0; i < n; i++) {
                String label = Integer.toString(pts.get(i).pos().getY());
                drawClampedLabel(g, font, label, sx[i] + 8, sy[i] - 4,
                        COLOR_HEIGHT_LABEL, left, top, right, bottom);
            }
        }

        // ── ⑤c ★ UX-8：选中点的**相对地形高度差** ──
        //
        // 用户反复困惑「我设了高度为什么不切方/为什么贴地了」。根因是
        // ROAD 段取 min(玩家高度, 地形) 且平滑阶段就会贴地 —— 玩家看不到
        // "我设的比地表高/低多少"，自然无从判断。
        // 这里在选中点旁标出相对地表的差值（+ 高于地表 / − 低于地表）。
        if (showHeights && state.selectedPointIndex() >= 0
                && state.selectedPointIndex() < n) {
            int si = state.selectedPointIndex();
            Integer terrain = sampleTerrainY(pts.get(si).pos());
            if (terrain != null) {
                int diff = pts.get(si).pos().getY() - terrain;
                String txt = (diff >= 0 ? "+" : "") + diff;
                int color = diff > 0 ? 0xFFFFAA33
                        : (diff < 0 ? 0xFF66CCFF : 0xFFAAAAAA);
                drawClampedLabel(g, font, txt, sx[si] + 8, sy[si] + 10,
                        color, left, top, right, bottom);
            }
        }

        // ── ⑦ ★ UX-5：预加载失败区块的警告标记 ──
        //
        // 预加载某些区块失败时，那些位置的路会缺一截。以前只写服务端日志，
        // 玩家只能到世界里盲找。这里在地图上用红色叉标出来。
        drawFailedChunkMarkers(g, state, toScreenX, toScreenY, left, top, right, bottom);

        return screenXY;
    }

    /**
     * 采样某点的地形高度（★ UX-8 用；失败返回 null，不阻塞渲染）。
     *
     * <p>只在服务端可用且区块已加载时才拿到值 —— 拿不到就不显示差值，
     * 而不是显示一个错的数。</p>
     */
    private static Integer sampleTerrainY(BlockPos pos) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            var level = server.overworld();
            if (level == null || !level.hasChunkAt(pos)) {
                return null;
            }
            return level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    pos.getX(), pos.getZ());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 画出预加载**失败区块**的警告标记（★ UX-5）。
     *
     * <p>数据来自 {@code RoutePreloadStatus}（服务端预加载循环每批写入，
     * 客户端渲染线程只读）。每条失败区块画一个红框 + 叉，
     * 让玩家一眼看到"路在这里断了"。</p>
     */
    private static void drawFailedChunkMarkers(GuiGraphics g,
                                               RouteEditorState state,
                                               IntUnaryOperator toScreenX,
                                               IntUnaryOperator toScreenY,
                                               int left, int top, int right, int bottom) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            var level = server.overworld();
            if (level == null) {
                return;
            }
            var route = state.route();
            if (route == null || route.id() == null) {
                return;
            }
            var status = net.shiroha233.roadweaver.route.RoutePreloadStatus
                    .snapshot(level, route.id());
            if (status.failedChunks().isEmpty()) {
                return;
            }
            // 一个区块 = 16 格；用区块的左上/右下角换算到屏幕
            for (var cp : status.failedChunks()) {
                int bx0 = cp.getMinBlockX();
                int bz0 = cp.getMinBlockZ();
                int bx1 = cp.getMaxBlockX();
                int bz1 = cp.getMaxBlockZ();
                int x0 = toScreenX.applyAsInt(bx0);
                int z0 = toScreenY.applyAsInt(bz0);
                int x1 = toScreenX.applyAsInt(bx1);
                int z1 = toScreenY.applyAsInt(bz1);
                if (x1 < left || x0 > right || z1 < top || z0 > bottom) {
                    continue;   // 不在视野内
                }
                // 红框 + 两条对角线（叉）
                g.renderOutline(x0, z0, Math.max(2, x1 - x0), Math.max(2, z1 - z0), 0xCCFF4444);
                g.fill(x0, z0, x1, z0 + 1, 0xCCFF4444);
                g.fill(x0, z1 - 1, x1, z1, 0xCCFF4444);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 标记画不出来不影响主渲染
        }
    }

    /**
     * 画一个文字标签，**做边界钳制**（★ A4）。
     *
     * <h2>为什么需要</h2>
     * 真机截图实证：路线画在靠近屏幕底部时，高度数字（原实现直接画在
     * {@code (sx + 8, sy - 4)}）会**压到工具条上**，与「＋ 新建 / ✕ 删除」
     * 按钮文字叠在一起，两边都看不清。
     *
     * <p>修法：把标签位置夹进「绘制区 + 一点内边距」里。
     * 只夹位置、不改内容 —— 数字本身永远是对的，只是挪一下别出界。</p>
     */
    private static void drawClampedLabel(GuiGraphics g, net.minecraft.client.gui.Font font,
                                         String text, int x, int y, int color,
                                         int left, int top, int right, int bottom) {
        int pad = 2;
        int w = font.width(text);
        int clampedX = Math.max(left + pad, Math.min(right - pad - w, x));
        // 9 = 默认行高（font.lineHeight），保证整行都在区内
        int clampedY = Math.max(top + pad, Math.min(bottom - pad - font.lineHeight, y));
        g.drawString(font, text, clampedX, clampedY, color, true);
    }

    /**
     * 画一段**带前后邻居切线**的平滑曲线（与 {@link RouteSmoother} 用同一套 Catmull-Rom）。
     *
     * <p>这里只取每段 {@value #CURVE_STEPS} 个采样点用于**显示** ——
     * 真正的施工路径点由 {@link RouteSmoother#smooth} 生成，两者点数不同，
     * 但都基于同一控制点与同一条样条，所以视觉一致。</p>
     */
    private static void drawSmoothSegment(GuiGraphics g,
                                          int x1, int y1, int x2, int y2,
                                          int px, int py, int nx, int ny,
                                          int color,
                                          int left, int top, int right, int bottom) {
        int prevX = x1;
        int prevY = y1;
        for (int s = 1; s <= CURVE_STEPS; s++) {
            float t = (float) s / CURVE_STEPS;
            double cx = RouteSmoother.catmullRom(px, x1, x2, nx, t);
            double cy = RouteSmoother.catmullRom(py, y1, y2, ny, t);
            int ix = (int) Math.round(cx);
            int iy = (int) Math.round(cy);
            RenderUtils.drawLine(g, prevX, prevY, ix, iy, color, left, top, right, bottom);
            prevX = ix;
            prevY = iy;
        }
    }

    /** 工具条：路 / 桥 / 隧道 三个色块 + 「施工」按钮。 */
    public static void renderToolbar(GuiGraphics g, RouteEditorState state,
                                     int x, int y, int width, int height) {
        RouteKind[] kinds = RouteKind.values();
        var font = net.minecraft.client.Minecraft.getInstance().font;
        for (int i = 0; i < kinds.length; i++) {
            RouteKind kind = kinds[i];
            int bx = x + i * (width + 4);
            g.fill(bx, y, bx + width, y + height, 0xE0202020);
            // 颜色条
            g.fill(bx + 2, y + 2, bx + width - 2, y + height - 2, 0xFF000000 | kind.editorColor());
            // 选中框
            if (kind == state.currentKind()) {
                g.renderOutline(bx - 1, y - 1, width + 2, height + 2, 0xFFFFFFFF);
            }
            String label = switch (kind) {
                case ROAD -> "路";
                case BRIDGE -> "桥";
                case TUNNEL -> "隧道";
            };
            g.drawString(font, label, bx + 3, y + height + 3, 0xFFCCCCCC, true);
        }

        // ── 「施工」按钮：玩家确认后才动工（用户明确要求）──
        int bx = x + kinds.length * (width + 4) + 12;
        g.fill(bx, y, bx + width + 18, y + height, 0xE0244024);
        g.renderOutline(bx - 1, y - 1, width + 20, height + 2, 0xFF66FF88);
        g.drawString(font, RouteText.get("roadweaver.route.btn.build", "▶ 施工"),
                bx + 3, y + 6, 0xFF66FF88, true);

        // ── ★ 审计 R3-P2-8：新建 / 删除 / 重新编辑 三个入口 ──
        //
        // 原先这三个操作**完全没有入口**（discard / removeRoute / cancel /
        // isEditable 全是死代码），后果：
        //   · 「新建路线」不存在 ⇒ 第二条路线永远画不出来（多路线配色不可达）
        //   · 「删除路线」不存在 ⇒ 画错只能改存档
        //   · READY 路线无法退回编辑 ⇒ 一旦提交就永久锁死（连锁 R3-P1-2）
        int lx = bx + width + 30;
        boolean editable = state.isEditable();
        // 新建：始终可用
        lx = drawToolButton(g, font, lx, y, width, height, "＋ 新建",
                "roadweaver.route.btn.new", 0xFF99CCFF, true);
        // 删除：有路线才可用
        lx = drawToolButton(g, font, lx, y, width, height, "✕ 删除",
                "roadweaver.route.btn.delete", 0xFFFF8888,
                state.route() != null);
        // 重新编辑：仅在已提交（不可编辑）时提示可用 —— 这是 READY 唯一的出路
        lx = drawToolButton(g, font, lx, y, width, height, "↺ 重新编辑",
                "roadweaver.route.btn.reopen", 0xFFFFEE88, !editable);
        // ★ 2026-09-25 用户要求：「地图里面加个按钮开关」——
        //   与控制 HUD 的那个按键是**同一个状态**，两边都能切。
        //   标签随状态变（显示 → 「HUD 开」，隐藏 → 「HUD 关」）方便一眼看出当前态。
        boolean hudOn = isHudEnabled();
        lx = drawToolButton(g, font, lx, y, width, height,
                hudOn ? "HUD 开" : "HUD 关",
                hudOn ? "roadweaver.route.btn.hud_on" : "roadweaver.route.btn.hud_off",
                hudOn ? 0xFF66FF88 : 0xFF888888, true);
        // ★ 任务 A（2026-09-26 修订）：端点标记开关**已移到地图底部 Dock 的独立按钮** ——
        //   用户明确「这里下方不是有一排按钮么加个格外的按钮就行，不用绑定键盘了」，
        //   所以手绘工具条上**不再**重复放这个按钮（避免同一开关多个入口）。
        // ★ UX-5：只有存在"有失败区块"的路线时才出现「重试」按钮 ——
        //   没有失败就不显示，避免工具条常驻一个没用的按钮。
        if (hasFailedPreload()) {
            drawToolButton(g, font, lx, y, width, height, "⟳ 重试铺设",
                    "roadweaver.route.btn.retry_preload", 0xFFFFAA33, true);
        }

        // ── 施工结果反馈 ──
        int result = state.lastSubmitResult();
        if (result != Integer.MIN_VALUE) {
            String msg;
            int color;
            if (result < 0) {
                msg = RouteText.get("roadweaver.route.btn.submit_unsupported",
                        "施工失败：需单人游戏");
                color = 0xFFFF6666;
            } else if (result == 0) {
                msg = RouteText.get("roadweaver.route.btn.submit_none",
                        "无可施工分段（至少 2 个点且不重合）");
                color = 0xFFFFAA33;
            } else {
                msg = RouteText.get("roadweaver.route.btn.submit_ok",
                        "已提交 %d 段，RoadWeaver 排队生成中", result);
                color = 0xFF66FF88;
            }
            g.drawString(font, msg, bx, y - 12, color, true);
        }
    }

    /** 画一个工具条按钮并返回下一个按钮的左边界（★ 审计 R3-P2-8）。 */
    private static int drawToolButton(GuiGraphics g, net.minecraft.client.gui.Font font,
                                      int x, int y, int width, int height,
                                      String fallback, String key, int color, boolean enabled) {
        int w = width + 18;
        g.fill(x, y, x + w, y + height, enabled ? 0xE0202030 : 0x80000000);
        g.renderOutline(x - 1, y - 1, w + 2, height + 2,
                enabled ? color : 0xFF555555);
        g.drawString(font, RouteText.get(key, fallback), x + 3, y + 6,
                enabled ? color : 0xFF777777, true);
        return x + w + 4;
    }

    /** 「施工」按钮的屏幕矩形（供点击命中）。返回 {x1,y1,x2,y2}。 */
    public static int[] buildButtonBounds(int x, int y, int width, int height) {
        int bx = x + RouteKind.values().length * (width + 4) + 12;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /**
     * 「新建路线」按钮的屏幕矩形（★ 审计 R3-P2-8）。返回 {x1,y1,x2,y2}。
     */
    public static int[] newButtonBounds(int x, int y, int width, int height) {
        int bx = x + RouteKind.values().length * (width + 4) + 12
                + width + 18 + 4;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /** 「删除路线」按钮的屏幕矩形（★ 审计 R3-P2-8）。 */
    public static int[] deleteButtonBounds(int x, int y, int width, int height) {
        int[] n = newButtonBounds(x, y, width, height);
        int bx = n[2] + 4;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /** 「重新编辑」按钮的屏幕矩形（★ 审计 R3-P2-8）。 */
    public static int[] reopenButtonBounds(int x, int y, int width, int height) {
        int[] d = deleteButtonBounds(x, y, width, height);
        int bx = d[2] + 4;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /**
     * 「HUD 开关」按钮的屏幕矩形（★ 用户 2026-09-25 要求）。
     *
     * <p>位置紧跟在「↺ 重新编辑」右边 —— 与 {@code renderToolbar} 里
     * {@code drawToolButton} 的调用顺序一致，改一处必须改另一处。</p>
     */
    public static int[] hudButtonBounds(int x, int y, int width, int height) {
        int[] r = reopenButtonBounds(x, y, width, height);
        int bx = r[2] + 4;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /**
     * 「重试铺设」按钮的屏幕矩形（★ UX-5）。
     *
     * <p>位置在 HUD 按钮右边；**仅当 {@link #hasFailedPreload()} 为真时才绘制**，
     * 所以命中检测也必须先问同一个条件，否则会出现"看不见但能点到"的幽灵按钮。</p>
     *
     * <p>★ 2026-09-26 修订：原先这里与「端点标记」按钮之间还夹着一个
     * {@code markersButtonBounds} —— 该按钮已移到地图底部 Dock，
     * 所以重试按钮现在紧邻 HUD 按钮。</p>
     */
    public static int[] retryButtonBounds(int x, int y, int width, int height) {
        int[] r = hudButtonBounds(x, y, width, height);
        int bx = r[2] + 4;
        return new int[]{bx, y, bx + width + 18, y + height};
    }

    /**
     * 「重试铺设」按钮是否可见（★ UX-5 / 2026-09-26 修 W-16）。
     *
     * <p>条件与 {@code RoutePreloadStatus.retryable()} **完全一致** ——
     * 有失败区块 **或** 曾被中止。修 W-16：中止后原先没有出口
     * （占位保留了却没有重试入口），现在中止也会让按钮出现。</p>
     */
    public static boolean retryButtonVisible() {
        return hasFailedPreload();
    }

    /** 当前是否有需要重试的预加载（有失败 或 曾被中止）。 */
    private static boolean hasFailedPreload() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return false;
            }
            var level = server.overworld();
            if (level == null) {
                return false;
            }
            return !net.shiroha233.roadweaver.route.RoutePreloadStatus
                    .retryable(level).isEmpty();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 顶部状态提示（画了几个点、当前类型、撤销深度）。 */
    public static void renderStatus(GuiGraphics g, RouteEditorState state, int x, int y) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        PlayerRoute route = state.route();
        int points = route == null ? 0 : route.pointCount();
        String text = "手绘路线：" + points + " 点 / "
                + (route == null ? 0 : route.segmentCount()) + " 段"
                + "　当前：" + switch (state.currentKind()) {
                    case ROAD -> "路";
                    case BRIDGE -> "桥";
                    case TUNNEL -> "隧道";
                }
                + "　可撤销：" + state.undoDepth();
        g.drawString(font, Component.literal(text), x, y, 0xFFE0E0E0, true);
        // ★ Ctrl 提示：不按时地图是正常地图（可拖动/缩放），按了才是编辑
        g.drawString(font, Component.literal(
                        "按住 Ctrl + 左键 = 放点/选中点 ｜ Ctrl + 滚轮 = 调高度 ｜ 不按 Ctrl 可正常拖动与缩放地图"),
                x, y + 12, 0xFF99CCFF, true);
    }

    /**
     * 取某个按键绑定的**当前**显示名。
     *
     * <p>读实际绑定而不是写死字符串 —— 玩家在设置里改过键之后，
     * HUD 提示会跟着变，不会出现「提示说按 P、其实已经改成别的了」。</p>
     *
     * @return 显示名；绑定未初始化时退回 "?"（初始化顺序问题，不抛异常）
     */
    private static String keyName(net.minecraft.client.KeyMapping mapping) {
        if (mapping == null) {
            return "?";
        }
        try {
            return mapping.getTranslatedKeyMessage().getString();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 高度输入框。 */
    public static void renderHeightInput(GuiGraphics g, RouteEditorState state,
                                         int centerX, int centerY) {        if (!state.isHeightInputOpen()) {
            return;
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int w = 110;
        int h = 20;
        int x = centerX - w / 2;
        int y = centerY - h / 2;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xF0101010);
        g.renderOutline(x - 2, y - 2, w + 4, h + 4, 0xFFFFFFFF);
        g.drawString(font, "高度 Y=", x + 3, y + 6, 0xFFCCCCCC, false);
        g.drawString(font, state.heightInputText() + "_", x + 50, y + 6, 0xFFFFEE88, false);
    }


    /**
     * 世界内编辑模式的 HUD（★ 2026-09-25 改为**左侧竖排**，可用按键/按钮开关）。
     *
     * <h2>用户要求</h2>
     * <blockquote>
     * 「hud 就是改成左侧竖排，按某键才显示，地图里面加个按钮开关」
     * </blockquote>
     * 原先是一行超长横排贴在屏幕底部 —— 信息全挤在一起、跨了整屏宽度，很难读。
     * 现在改成**左上角竖排面板**，每条信息一行；并且可以整体收起来
     * （左侧竖排会挡住屏幕左边一块，尤其和小地图类 mod 叠在一起时）。
     *
     * <h2>开关</h2>
     * 两个入口，共用同一个状态：
     * <ul>
     *   <li>按键 {@link RouteKeys#TOGGLE_HUD}（默认 K）——世界内直接切</li>
     *   <li>地图工具条上的「HUD」按钮（见 {@link #renderToolbar}）</li>
     * </ul>
     *
     * <p>关闭时**什么都不画**（连提示也不留），保持画面干净。</p>
     */
    public static void renderWorldHud(GuiGraphics g, int screenWidth, int screenHeight) {
        if (!isHudEnabled()) {
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        var font = mc.font;
        var session = net.shiroha233.roadweaver.route.editor.RouteEditorSession.get();
        var route = session.current();

        // ── 左侧竖排：左上角起，逐行下推 ──
        int x = 8;
        int line = 8;
        final int LINE = 11;              // 行距（比 drawString 默认 9 稍宽，读起来不挤）
        final int PANEL_W = 178;          // 面板宽度（够放最长那行中文提示）
        int top = line - 3;

        // 用半透明底板垫一下，否则浅色地形上文字看不清
        // （先记下最终高度，画完再补底板 —— 这里先收集行内容）
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();

        // ── ① 状态 ──
        String kindLabel = switch (session.currentKind()) {
            case ROAD -> "路";
            case BRIDGE -> "桥";
            case TUNNEL -> "隧道";
        };
        int points = route == null ? 0 : route.pointCount();
        lines.add(RouteText.get("roadweaver.route.hud.title", "手绘路线编辑"));
        colors.add(0xFFFFEE88);
        lines.add(RouteText.get("roadweaver.route.hud.points", "点数") + " " + points
                + "   " + RouteText.get("roadweaver.route.hud.kind_label", "类型")
                + " [" + kindLabel + "]");
        colors.add(0xFFCCCCCC);

        // 选中点（序号 + 坐标 + 高度，方便对照世界里的编号）
        int sel = session.selectedPointIndex();
        if (route != null && sel >= 0 && sel < route.pointCount()) {
            BlockPos sp = route.point(sel).pos();
            lines.add(RouteText.get("roadweaver.route.hud.selected", "选中") + " [" + (sel + 1) + "]");
            lines.add("  (" + sp.getX() + ", " + sp.getY() + ", " + sp.getZ() + ")");
            colors.add(0xFFFFFFFF);
            colors.add(0xFFFFFFFF);
        } else {
            lines.add(RouteText.get("roadweaver.route.hud.no_selection", "（未选中点）"));
            colors.add(0xFF888888);
        }

        int pending = session.pendingRoutes().size();
        lines.add(RouteText.get("roadweaver.route.hud.pending", "未施工") + " " + pending
                + " " + RouteText.get("roadweaver.route.hud.routes_unit", "条"));
        colors.add(0xFFCCCCCC);

        // ── ①b ★ UX-6：当前路线的提交次数（重新编辑后能看出这是第几轮）──
        int submits = route == null ? 0 : session.submitCount(route);
        if (submits > 0) {
            lines.add(RouteText.get("roadweaver.route.hud.submit_count", "本条已提交") + " "
                    + submits + " " + RouteText.get("roadweaver.route.hud.times_unit", "次"));
            colors.add(0xFFFFEE88);
        }

        // ── ①c ★ UX-10：最近提交的路线摘要（画布清空后仍能看到刚提交了什么）──
        var lastSub = session.lastSubmitted();
        if (lastSub != null && points == 0) {
            // 只在"当前画布是空的"时显示 —— 玩家正在画的时候不用占地方
            lines.add(RouteText.get("roadweaver.route.hud.last_submitted", "最近提交")
                    + ": " + lastSub.segments() + " "
                    + RouteText.get("roadweaver.route.hud.segments_unit", "段")
                    + " (" + lastSub.points() + " "
                    + RouteText.get("roadweaver.route.hud.points_unit", "点") + ")");
            colors.add(0xFF66FF88);
        }

        // ── ② 施工进度（从连接状态实时汇总）──
        var srvLevel = session.serverLevelOrNull();
        var progress = (route == null || srvLevel == null)
                ? net.shiroha233.roadweaver.route.RouteBuildProgress.Snapshot.EMPTY
                : net.shiroha233.roadweaver.route.RouteBuildProgress
                        .cachedSnapshot(srvLevel, route.id());
        if (progress.total() > 0) {
            lines.add(progress.describe());
            colors.add(progress.isDone() ? 0xFF66FF88
                    : (progress.failed() > 0 ? 0xFFFFAA33 : 0xFFFFEE88));
        }

        // ── ③ 保存失败（审计 R3-P2-15）──
        if (session.isSaveFailed()) {
            lines.add(RouteText.get("roadweaver.route.hud.save_failed",
                    "⚠ 保存失败，正在重试（请检查磁盘空间 / 文件是否被占用）"));
            colors.add(0xFFFF6666);
        }

        // ── ②b ★ UX-2/3：预加载进度（含未探索警告）──
        //
        // 用户 2026-09-25 要求预加载「不设超时、必须全部完成」——
        // 这个过程可能持续十几秒到几分钟，**必须让玩家看到在干什么**，
        // 否则会以为游戏卡死。同时若有未探索区块要明确警告（会更慢）。
        if (route != null && srvLevel != null) {
            // ★ 2026-09-26 修 I-12：优先模式明示（原先只有日志，玩家在 UI 上看不到）。
            //
            //   背景：0.8.397 起「手绘施工优先」会为玩家的路线**豁免让路暂停**，
            //   但玩家毫无察觉；超限恢复后台时更不知道为什么突然变慢。
            //   这里在进度行上方给一行明确状态。
            if (net.shiroha233.roadweaver.route.PlayerRoutePriority.isActive(srvLevel)) {
                int n = net.shiroha233.roadweaver.route.PlayerRoutePriority
                        .activeCount(srvLevel);
                boolean over = net.shiroha233.roadweaver.route.PlayerRoutePriority
                        .isOverHardLimit(srvLevel);
                if (over) {
                    lines.add(RouteText.get("roadweaver.route.hud.priority_over",
                            "已恢复后台规划（优先时段已结束）"));
                    colors.add(0xFFFFAA33);
                } else {
                    lines.add(RouteText.get("roadweaver.route.hud.priority_active",
                            "优先施工中") + "（" + RouteText.get("roadweaver.route.hud.priority_left",
                            "剩余") + " " + n + " " + RouteText.get("roadweaver.route.hud.segments_unit", "段") + "）");
                    colors.add(0xFF66FF88);
                }
            }
            var preload = net.shiroha233.roadweaver.route.RoutePreloadStatus
                    .snapshot(srvLevel, route.id());
            if (preload.total() > 0) {
                String bar = progressBar(preload.ratio(), 14);
                lines.add(RouteText.get("roadweaver.route.hud.preload", "预加载区块") + " "
                        + preload.processed() + "/" + preload.total() + " " + bar);
                colors.add(preload.active() ? 0xFF99CCFF : 0xFF66FF88);
                if (preload.hasUnexplored() && preload.active()) {
                    lines.add("⚠ " + RouteText.get("roadweaver.route.hud.unexplored",
                            "含未探索区域，正在生成地形，会比较慢"));
                    colors.add(0xFFFFAA33);
                }
                if (preload.failed() > 0) {
                    lines.add(RouteText.get("roadweaver.route.hud.preload_failed", "失败") + " "
                            + preload.failed() + " " + RouteText.get("roadweaver.route.hud.chunks_unit", "区块")
                            + RouteText.get("roadweaver.route.hud.retry_hint", "（已转后台回填，可走过去补）"));
                    colors.add(0xFFFF8888);
                }
                if (preload.active()) {
                    lines.add(indent(keyName(RouteKeys.ABORT_PRELOAD),
                            RouteText.get("roadweaver.route.hud.abort", "中止预加载")));
                    colors.add(0xFFFFAA33);
                }
            }
        }

        // ── ④ 操作提示（键名按当前实际绑定显示）──
        if (!RouteKeys.isInitialized()) {
            lines.add(RouteText.get("roadweaver.route.hud.keys_missing",
                    "⚠ 当前平台未注册手绘路线按键，世界内编辑不可用"));
            colors.add(0xFFFF8888);
        } else {
            lines.add("");
            colors.add(0xFF888888);
            lines.add(RouteText.get("roadweaver.route.hud.controls", "— 操作 —"));
            colors.add(0xFF99CCFF);
            lines.add(indent(keyName(RouteKeys.EDIT_MODIFIER),
                    RouteText.get("roadweaver.route.hud.help", "按住才能编辑")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.SPEED_BOOST) + "+滚轮",
                    RouteText.get("roadweaver.route.hud.height", "调高")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(RouteText.get("roadweaver.route.hud.move_keys", "方向键"),
                    RouteText.get("roadweaver.route.hud.move", "挪位")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.CYCLE_KIND),
                    RouteText.get("roadweaver.route.hud.kind", "换类型")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.INSERT_POINT),
                    RouteText.get("roadweaver.route.hud.insert", "插点")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.DELETE_POINT),
                    RouteText.get("roadweaver.route.hud.delete", "删点")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.TOGGLE_PREVIEW),
                    RouteText.get("roadweaver.route.hud.preview_noc", "预览")));
            colors.add(0xFFBBBBBB);
            lines.add(indent(keyName(RouteKeys.TOGGLE_HUD),
                    RouteText.get("roadweaver.route.hud.hide_hud", "收起本面板")));
            colors.add(0xFF888888);
            // ★ UX-11：中止预加载（需 Ctrl，见键位说明）
            lines.add(indent(keyName(RouteKeys.EDIT_MODIFIER) + "+"
                            + keyName(RouteKeys.ABORT_PRELOAD),
                    RouteText.get("roadweaver.route.hud.abort", "中止预加载")));
            colors.add(0xFF888888);
            lines.add(indent("J", RouteText.get("roadweaver.route.hud.exit", "退出")));
            colors.add(0xFFBBBBBB);

            // 施工预览开关状态
            boolean previewOn = net.shiroha233.roadweaver.route.editor.RoutePreviewRenderer.isEnabled();
            lines.add(RouteText.get(previewOn
                    ? "roadweaver.route.hud.preview_on" : "roadweaver.route.hud.preview_off",
                    previewOn ? "施工预览：开" : "施工预览：关"));
            colors.add(previewOn ? 0xFF66FF88 : 0xFF888888);

            lines.add(RouteText.get("roadweaver.route.hud.build_at_map", "去地图界面施工"));
            colors.add(0xFF66FF88);
        }

        // ── 画半透明底板（先算高度）──
        int bottom = line + lines.size() * LINE + 2;
        g.fill(x - 4, top, x - 4 + PANEL_W, bottom, 0xB0101010);
        g.renderOutline(x - 4, top, PANEL_W, bottom - top, 0x60FFFFFF);

        // ── 逐行输出 ──
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            if (!text.isEmpty()) {
                g.drawString(font, text, x, line, colors.get(i), true);
            }
            line += LINE;
        }
    }

    /**
     * 一个纯文字的进度条（★ UX-2）。
     *
     * <p>用方块字符拼 —— 不需要贴图、不受字体宽度影响、深浅色地形上都看得清。
     * 形如 {@code ████████░░░░░░}。</p>
     *
     * @param ratio 0~1
     * @param width 字符数
     */
    private static String progressBar(float ratio, int width) {
        int n = Math.max(0, Math.min(width, Math.round(ratio * width)));
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        for (int i = 0; i < width; i++) {
            sb.append(i < n ? '█' : '░');
        }
        sb.append(']');
        return sb.toString();
    }

    /** 左对齐的「键名 — 说明」一行（键名固定占 10 字符宽度，右侧对齐好看）。 */
    private static String indent(String key, String desc) {
        StringBuilder sb = new StringBuilder("  ");
        sb.append(key);
        // 补齐到 10 字符（中文按 2 算的粗略对齐，够了）
        int pad = 10 - key.length();
        for (int i = 0; i < pad; i++) {
            sb.append(' ');
        }
        sb.append(desc);
        return sb.toString();
    }

    // ─────────────────── HUD 开关状态 ───────────────────
    //
    // ★ 2026-09-25 修 Codex R-9：从「进程内静态字段」改为**读客户端配置**。
    //
    // 原先 hudEnabled 是 static volatile，**退出游戏就重置回 true** ——
    // 玩家每次启动都要重按一次 K 收起面板。现在持久化到
    // ClientConfig.routeEditorHudEnabled（已核实该段不进 worldgen 指纹，
    // 不会作废已生成的道路）。

    /** HUD 是否显示（默认**开** —— 新玩家需要看到操作提示）。 */
    public static boolean isHudEnabled() {
        var cfg = net.shiroha233.roadweaver.config.ConfigService.get();
        if (cfg == null) {
            return true;   // 配置不可用时按默认（显示）
        }
        try {
            return cfg.client().routeEditorHudEnabled();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    public static void setHudEnabled(boolean value) {
        var cfg = net.shiroha233.roadweaver.config.ConfigService.get();
        if (cfg == null) {
            return;
        }
        try {
            cfg.client().setRouteEditorHudEnabled(value);
            // 持久化到磁盘（ConfigService 已有统一的 save 路径）
            net.shiroha233.roadweaver.config.ConfigService.save();
        } catch (RuntimeException | LinkageError e) {
            // 保存失败不影响本次会话的显示效果
        }
    }

    /** 切换 HUD 显示，返回切换后的状态。 */
    public static boolean toggleHud() {
        boolean next = !isHudEnabled();
        setHudEnabled(next);
        return next;
    }

}
