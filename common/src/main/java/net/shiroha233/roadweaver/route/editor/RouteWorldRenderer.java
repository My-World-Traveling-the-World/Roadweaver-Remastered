package net.shiroha233.roadweaver.route.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteKind;
import net.shiroha233.roadweaver.route.RoutePoint;
import net.shiroha233.roadweaver.route.RouteSmoother;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * **世界内 3D** 手绘路线的绘制逻辑（平台无关部分）。
 *
 * <h2>为什么和注册分开</h2>
 * 注册要调 Fabric 的 {@code WorldRenderEvents}（fabric-api，common 看不见），
 * 而绘制本身只用到 Minecraft 自己的渲染类。所以：
 * <pre>
 *   RouteWorldRenderer        ← 本类，绘制（common，平台无关）
 *   RouteWorldRendererBridge  ← 注册声明（common，@ExpectPlatform）
 *   fabric 的实现类            ← 真正调 WorldRenderEvents 注册
 * </pre>
 *
 * <h2>为什么用线条而不是粒子</h2>
 * 最初考虑粒子画「- - - -」虚线，否决理由：粒子不能参与射线命中检测
 * （而玩家要「准星选中点、微调」）、长线要每秒重发上百个包、位置还有抖动。
 * 改用 {@link RenderType#lines()} 本地绘制：不发包、可远观、能配合射线选择。
 *
 * <h2>画什么</h2>
 * <ol>
 *   <li><b>平滑曲线</b> —— 与地图、与施工路径同一套 Catmull-Rom（所见即所建）</li>
 *   <li><b>控制点线框方块</b> —— 端点大、中间点小</li>
 *   <li><b>竖直引线</b> —— 从控制点向下拉一段，
 *       解决「3D 里看不出这个点悬在多高」的可读性问题</li>
 *   <li><b>选中高亮</b> —— 红色放大框</li>
 * </ol>
 *
 * <h2>★ 相机相对坐标</h2>
 * 必须在减去相机位置的坐标系里画，否则坐标一大 float 精度不足会抖动闪烁。
 */
public final class RouteWorldRenderer {

    private static final int CURVE_STEPS = 8;

    private static final float ENDPOINT_HALF = 0.22F;
    private static final float MIDPOINT_HALF = 0.15F;
    private static final float SELECT_GROW = 0.12F;

    /** 竖直引线最大长度（桥很高时别拉到天边）。 */
    private static final float MAX_DROP_LINE = 24.0F;

    private static final float[] COLOR_HANDLE = {0.95F, 0.95F, 0.95F, 1.0F};
    private static final float[] COLOR_SELECTED = {1.0F, 0.23F, 0.19F, 1.0F};
    private static final float[] COLOR_DROP = {0.6F, 0.6F, 0.6F, 0.7F};

    /**
     * 多条未施工路线各自的配色（按路线在列表里的顺序循环取用）。
     *
     * <p>用户要求：「如果有多个未施工的，不同颜色同时标记」——
     * 这样玩家在地图上能一眼分清哪条线是哪条，而不会看成一条乱麻。</p>
     *
     * <p>颜色刻意选高辨识度且彼此区分大的（青 / 品红 / 黄 / 蓝 / 橙 / 绿），
     * 且与三种类型色（路绿 / 桥蓝 / 隧橙）不冲突 —— 类型色用于**中心线**，
     * 这里是**控制点句柄**的着色，两者视觉层次不同。</p>
     */
    private static final float[][] ROUTE_PALETTE = {
            {0.35F, 0.85F, 1.00F, 1.0F},   // 青
            {1.00F, 0.45F, 0.85F, 1.0F},   // 品红
            {1.00F, 0.90F, 0.35F, 1.0F},   // 黄
            {0.55F, 0.70F, 1.00F, 1.0F},   // 蓝
            {1.00F, 0.65F, 0.35F, 1.0F},   // 橙
            {0.60F, 1.00F, 0.55F, 1.0F},   // 绿
    };

    /** 海平面近似值，用于算竖直引线长度。 */
    private static final int SEA_LEVEL = 62;

    private RouteWorldRenderer() {
    }

    /**
     * 世界渲染主入口（由平台层在渲染事件里调用）。
     *
     * <h2>★ 渲染哪些路线（用户要求）</h2>
     * <pre>
     *   已施工的（status != DRAFT）→ 不再显示
     *   未施工的（status == DRAFT）→ 显示
     *   多条未施工的              → 各用不同颜色同时显示
     * </pre>
     * 这样地图上留下的永远只是「还没建的线」，画完建完就自然消失，
     * 不会积累成一堆看不懂的旧线。
     *
     * @param poseStack 渲染事件的矩阵栈
     * @param consumers 渲染事件的 buffer 源
     * @param cameraPos 当前相机世界坐标
     */
    public static void render(PoseStack poseStack, MultiBufferSource consumers, Vec3 cameraPos) {
        RouteEditorSession session = RouteEditorSession.get();
        if (!session.isActive()) {
            return;
        }
        // 只取「未施工」的路线（DRAFT）
        List<PlayerRoute> pending = session.pendingRoutes();
        if (pending.isEmpty()) {
            return;
        }
        if (poseStack == null || consumers == null || cameraPos == null) {
            return;
        }

        // ★ 任务 B（2026-09-26）：**双 pass 渲染**，让地下的点与线能透视看到。
        //
        //   用户反馈：「如果点还有线进了地下还是看不到的啊。能做成可以透视的么」
        //   根因：RenderType.lines() 开着深度测试 ⇒ 地下的线被地表挡住。
        //
        //   做法：
        //     pass 1  穿透层（seenThroughLines，关深度测试）—— 让地下的线透出来
        //     pass 2  正常层（RenderType.lines()，有深度测试）—— 近处照旧遮挡
        //   两层叠加 ⇒ 地下的线"淡显"、地上/近处的线正常，立体感保留。
        //
        //   ⚠️ 先画穿透层、后画正常层：这样正常层画在上层，
        //      视觉上"真实的那一遍"占主导，穿透只作为补充提示。
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        PlayerRoute current = session.current();

        // ── pass 1：穿透层（★ W-12/I-7：**只画低于地表的段**）──
        //
        //   原先无条件全画一遍 —— 顶点数翻倍，且地上的线被叠加两遍导致颜色失真。
        //   现在只有"真的在地下"的段才进穿透 buffer，省一半顶点、地面不受叠加。
        try {
            VertexConsumer through = consumers.getBuffer(RouteRenderTypes.seenThroughLines());
            for (int i = 0; i < pending.size(); i++) {
                PlayerRoute route = pending.get(i);
                float[] tint = ROUTE_PALETTE[i % ROUTE_PALETTE.length];
                // 用半透明色调（alpha 压到 ~42%）作为"透视提示层"
                renderRoute(through, matrix, route, -1, false, fade(tint, 0.42F), cameraPos, true);
            }
        } catch (RuntimeException | LinkageError e) {
            // 穿透层失败不影响正常层（例如某些渲染后端不支持该状态组合）
            org.slf4j.LoggerFactory.getLogger("roadweaver/route-editor")
                    .warn("手绘路线穿透层渲染失败，已跳过（正常层不受影响）", e);
        }

        // ── pass 2：正常层（原有行为）──
        VertexConsumer lines = consumers.getBuffer(RenderType.lines());
        for (int i = 0; i < pending.size(); i++) {
            PlayerRoute route = pending.get(i);
            float[] tint = ROUTE_PALETTE[i % ROUTE_PALETTE.length];
            // 只有「当前正在编辑的那条」才画序号与选中高亮，避免多条叠加时标记打架
            boolean isCurrent = (route == current);
            renderRoute(lines, matrix, route, isCurrent ? RouteWorldEditor.selectedIndex() : -1,
                    isCurrent, tint, cameraPos);
        }

        poseStack.popPose();
    }

    /**
     * 这一段是否**低于地表**（★ 2026-09-26 修 W-12/I-7）。
     *
     * <p>穿透层的意义是"让被地面挡住的部分可见"—— 所以只有**真的在地下**
     * 的段才需要进穿透层。地上的段在 pass 2 已经画过，再进穿透层只会：
     * ① 白耗一半顶点；② 同一条线被叠加两遍导致颜色失真。</p>
     *
     * <p>判定用两端的 Y 与地表高度比较（取两端都**严格低于**地表才算地下，
     * 保守：只要有一端在地上，就交给 pass 2 正常画）。</p>
     *
     * <p>拿不到地表高度时返回 {@code false}（按地上处理）——
     * 宁可少画穿透，也不要因为采样失败把整条线塞进穿透层。</p>
     */
    private static boolean isUnderground(BlockPos a, BlockPos b) {
        Integer ha = surfaceY(a);
        Integer hb = surfaceY(b);
        if (ha == null || hb == null) {
            return false;
        }
        return a.getY() < ha && b.getY() < hb;
    }

    /** 取 (x,z) 处地表高度；拿不到返回 null。 */
    private static Integer surfaceY(BlockPos pos) {
        if (pos == null) {
            return null;
        }
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
     * 把配色按比例调暗（用于穿透层）—— 只改 RGB 与 alpha，不改色相。
     *
     * @param tint  原色 {r, g, b, a}（0~1）
     * @param scale 缩放系数（&lt;1 更暗更透明）
     */
    private static float[] fade(float[] tint, float scale) {
        if (tint == null || tint.length < 4) {
            return new float[]{1f, 1f, 1f, scale};
        }
        return new float[]{tint[0], tint[1], tint[2], tint[3] * scale};
    }

    /**
     * 渲染单条路线。
     *
     * @param selected   当前选中的控制点下标；-1 表示不高亮（非当前编辑的路线）
     * @param isCurrent  是否为「正在编辑的那条」—— 只有它画序号与选中框，
     *                   避免多条路线叠加时标记打架
     * @param tint       本路线的配色（多条未施工路线靠它区分）
     */
    private static void renderRoute(VertexConsumer vc, Matrix4f m,
                                    PlayerRoute route, int selected,
                                    boolean isCurrent, float[] tint, Vec3 cameraPos) {
        renderRoute(vc, m, route, selected, isCurrent, tint, cameraPos, false);
    }

    /**
     * 渲染单条路线。
     *
     * @param undergroundOnly ★ 2026-09-26 修 W-12/I-7：**只画"低于地表"的段**。
     *                        穿透层用它 —— 原先穿透层**无条件全画一遍**
     *                        （顶点数翻倍），但地上的线第二遍纯属浪费：
     *                        它们在 pass 2 已经画过，穿透层再画一次只会
     *                        让颜色失真、白耗顶点。
     *                        只画地下的段，既省一半顶点，又让"地面上不叠加"。
     */
    private static void renderRoute(VertexConsumer vc, Matrix4f m,
                                    PlayerRoute route, int selected,
                                    boolean isCurrent, float[] tint, Vec3 cameraPos,
                                    boolean undergroundOnly) {
        List<RoutePoint> pts = route.points();
        int n = pts.size();
        if (n == 0) {
            return;
        }

        // ── ⓪ 原始折线（点与点直连）──
        //
        // 用户要求：「端点点了中间连线起来 要不不知道怎么走的」。
        // 平滑曲线是**最终形态**，但玩家画的时候脑子里是「我点了这几个点」，
        // 需要看到点与点的直连关系才知道自己连成了什么形状。
        // 所以先画一层淡色直线，再叠平滑曲线。
        if (n >= 2) {
            for (int i = 0; i + 1 < n; i++) {
                BlockPos a = pts.get(i).pos();
                BlockPos b = pts.get(i + 1).pos();
                if (undergroundOnly && !isUnderground(a, b)) {
                    continue;   // 地上的段留给 pass 2
                }
                line(vc, m,
                        a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5,
                        b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5,
                        tint[0] * 0.55F, tint[1] * 0.55F, tint[2] * 0.55F, 0.55F);
            }
        }

        // ── ① 平滑曲线（按段类型上色；类型切换处自然断开）──
        if (n >= 2) {
            for (int i = 0; i + 1 < n; i++) {
                float[] c = rgbOf(pts.get(i).kind());
                BlockPos p0 = pts.get(i).pos();
                BlockPos p1 = pts.get(i + 1).pos();
                // ★ 修 W-12/I-7：穿透层只画地下的段（地上的留给 pass 2）
                if (undergroundOnly && !isUnderground(p0, p1)) {
                    continue;
                }
                BlockPos prev = i > 0 ? pts.get(i - 1).pos() : p0;
                BlockPos next = i + 2 < n ? pts.get(i + 2).pos() : p1;

                double lx = p0.getX() + 0.5, ly = p0.getY() + 0.5, lz = p0.getZ() + 0.5;
                for (int s = 1; s <= CURVE_STEPS; s++) {
                    float t = (float) s / CURVE_STEPS;
                    double cx = RouteSmoother.catmullRom(prev.getX(), p0.getX(), p1.getX(), next.getX(), t) + 0.5;
                    double cy = RouteSmoother.catmullRom(prev.getY(), p0.getY(), p1.getY(), next.getY(), t) + 0.5;
                    double cz = RouteSmoother.catmullRom(prev.getZ(), p0.getZ(), p1.getZ(), next.getZ(), t) + 0.5;
                    line(vc, m, lx, ly, lz, cx, cy, cz, c[0], c[1], c[2], c[3]);
                    lx = cx; ly = cy; lz = cz;
                }
            }
        }

        // ── ② 竖直引线 + 控制点方块 ──
        for (int i = 0; i < n; i++) {
            BlockPos p = pts.get(i).pos();
            double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;

            float dropLen = Math.min(MAX_DROP_LINE, Math.max(0, p.getY() - SEA_LEVEL));
            if (dropLen > 0.5F) {
                line(vc, m, x, y, z, x, y - dropLen, z,
                        COLOR_DROP[0], COLOR_DROP[1], COLOR_DROP[2], COLOR_DROP[3]);
            }

            boolean isEndpoint = (i == 0 || i == n - 1);
            boolean isSelected = isCurrent && (i == selected);
            float half = (isEndpoint ? ENDPOINT_HALF : MIDPOINT_HALF) + (isSelected ? SELECT_GROW : 0F);
            // 非当前编辑的路线用它的专属颜色，当前编辑的用白色（便于区分"我在改这条"）
            float[] c = isSelected ? COLOR_SELECTED : (isCurrent ? COLOR_HANDLE : tint);
            box(vc, m, x, y, z, half, c[0], c[1], c[2]);
        }

        // ── ③ 控制点序号（1 2 3 …）──
        //
        // 用户要求「编号显示成 1 2 3」—— 方便口头/心里指认"第 3 个点"。
        // 只在当前编辑的路线 + 玩家一定看得见的范围内画，避免多条叠加时糊成一片。
        if (isCurrent) {
            renderPointLabels(vc, m, pts, cameraPos);
        }
    }

    /**
     * 在每个控制点旁画序号数字（1、2、3 …）。
     *
     * <h2>怎么画数字</h2>
     * {@code RenderType.lines()} 只能画线段，没有字体渲染。这里用
     * **七段数码管**的思路：每个数字由若干条短线段拼成，画在点的正上方一点点。
     *
     * <p>为什么不用 GUI 文字（{@code GuiGraphics.drawString}）：
     * 那是**屏幕坐标**，而这里是世界坐标 —— 需要自己投影才能对齐，
     * 且远处会被地形遮挡关系搞乱。用世界内的线段画数字，
     * 天然跟随深度、跟随缩放、跟随遮挡，观感一致。</p>
     *
     * <h2>★ 审计 R3-P2-14：上限改为「按距离取最近的 20 个」</h2>
     * 原实现是 {@code Math.min(n, 20)} —— **按索引取前 20 个**，
     * 所以第 21 个控制点之后**永远没有编号**，玩家没法口头指认
     * 「第 25 个点往左挪」。
     *
     * <p>现在按到相机的距离排序取最近的 20 个：远处的点本来就小到看不清，
     * 优先标玩家身边的那些才是对的信息密度分配。</p>
     *
     * <p>数字大小固定（约 0.5 格高），不随距离变化 —— 远处会小但不会消失。
     * （0.8.383 真机反馈过「可能太小」，但七段线段画太大反而会盖住点本身；
     * 这里保持尺寸，改为优先标近处的点来提升可读性。）</p>
     *
     * @param cameraPos 相机位置；为 null 时退回「按索引取前 20」（保持旧行为）
     */
    private static void renderPointLabels(VertexConsumer vc, Matrix4f m, List<RoutePoint> pts,
                                          @Nullable Vec3 cameraPos) {
        int n = pts.size();
        if (n == 0) {
            return;
        }
        java.util.List<Integer> order = labelOrder(pts, cameraPos, n);
        for (int i : order) {
            BlockPos p = pts.get(i).pos();
            double x = p.getX() + 0.5;
            double y = p.getY() + 0.5 + ENDPOINT_HALF + 0.35;   // 抬到方块上方
            double z = p.getZ() + 0.5;
            drawNumber(vc, m, x, y, z, i + 1);
        }
    }

    /**
     * 决定要标哪些点的编号（★ 审计 R3-P2-14）。
     *
     * <p>点数不超过 {@value #MAX_LABELS} 时全部返回（保持原有顺序）；
     * 否则按到相机的距离取最近的 {@value #MAX_LABELS} 个
     * —— 距离取的是**水平距离 + 垂直距离**的平方和，与实际观感一致。</p>
     */
    private static java.util.List<Integer> labelOrder(List<RoutePoint> pts,
                                                      @Nullable Vec3 cameraPos, int n) {
        java.util.List<Integer> all = new java.util.ArrayList<>(n);
        if (n <= MAX_LABELS) {
            for (int i = 0; i < n; i++) {
                all.add(i);
            }
            return all;
        }
        if (cameraPos == null) {
            // 没有相机位置（离线/单测）：退回顺序前 N 个
            for (int i = 0; i < MAX_LABELS; i++) {
                all.add(i);
            }
            return all;
        }
        // 按距离平方排序，取最近的 MAX_LABELS 个。
        // 用手写插入（N 通常几百，避免每帧 new Comparator + 装箱排序）。
        Integer[] best = new Integer[MAX_LABELS];
        double[] bestD = new double[MAX_LABELS];
        int filled = 0;
        for (int i = 0; i < n; i++) {
            BlockPos p = pts.get(i).pos();
            double dx = p.getX() + 0.5 - cameraPos.x;
            double dy = p.getY() + 0.5 - cameraPos.y;
            double dz = p.getZ() + 0.5 - cameraPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (filled < MAX_LABELS) {
                best[filled] = i;
                bestD[filled] = d2;
                filled++;
                if (filled == MAX_LABELS) {
                    sortByDistance(best, bestD, filled);
                }
            } else if (d2 < bestD[MAX_LABELS - 1]) {
                // 比当前最远的那个近 → 替换它并重新沉到位
                best[MAX_LABELS - 1] = i;
                bestD[MAX_LABELS - 1] = d2;
                sortByDistance(best, bestD, MAX_LABELS);
            }
        }
        for (int k = 0; k < filled; k++) {
            all.add(best[k]);
        }
        // 按索引升序返回，编号绘制顺序稳定（不随相机微动闪烁）
        all.sort(java.util.Comparator.naturalOrder());
        return all;
    }

    /** 对 (index, distance) 平行数组按距离升序做插入排序（N = 20，足够快）。 */
    private static void sortByDistance(Integer[] idx, double[] d, int len) {
        for (int i = 1; i < len; i++) {
            double dv = d[i];
            int iv = idx[i];
            int j = i - 1;
            while (j >= 0 && d[j] > dv) {
                d[j + 1] = d[j];
                idx[j + 1] = idx[j];
                j--;
            }
            d[j + 1] = dv;
            idx[j + 1] = iv;
        }
    }

    /** 最多标多少个控制点序号（★ 审计 R3-P2-14）。 */
    private static final int MAX_LABELS = 20;

    /** 用七段线段画一个正整数（十进制逐位）。 */
    private static void drawNumber(VertexConsumer vc, Matrix4f m,
                                   double x, double y, double z, int value) {
        // ★ 审计 R3-P3-6：不再每次 new ArrayList + Collections.reverse。
        // 数字最多 4 位（控制点数量上限远小于 10000），用栈上的定长数组
        // 逆序写入即可 —— 这个方法每帧会对每个标号调用一次。
        int v = Math.max(0, value);
        int[] digits = new int[5];
        int count = 0;
        if (v == 0) {
            digits[count++] = 0;
        } else {
            while (v > 0 && count < digits.length) {
                digits[count++] = v % 10;
                v /= 10;
            }
        }
        // digits 里是低位在前，所以绘制时**从后往前**遍历即为高位在左
        double w = 0.18;    // 单段长
        double h = 0.30;    // 单段高
        double gap = 0.06;  // 数字间距
        double startX = x - (count * (w + gap)) / 2.0;

        for (int d = 0; d < count; d++) {
            double dx = startX + d * (w + gap);
            drawDigit(vc, m, dx, y, z, digits[count - 1 - d], w, h);
        }
    }

    /**
     * 画单个数字（七段式）：段布局
     * <pre>
     *    aaa
     *   f   b
     *    ggg
     *   e   c
     *    ddd
     * </pre>
     * 线段画在 XY 平面（面向 +Z），所以从南北向看数字是正的。
     */
    private static void drawDigit(VertexConsumer vc, Matrix4f m,
                                  double x, double y, double z, int digit,
                                  double w, double h) {
        // 每段一根线，坐标是相对点中心的偏移
        // a 上横 / b 右上竖 / c 右下竖 / d 下横 / e 左下竖 / f 左上竖 / g 中横
        boolean a = false, b = false, c = false, d = false, e = false, f = false, g = false;
        switch (digit) {
            case 0 -> { a = b = c = d = e = f = true; }
            case 1 -> { b = c = true; }
            case 2 -> { a = b = d = e = g = true; }
            case 3 -> { a = b = c = d = g = true; }
            case 4 -> { b = c = f = g = true; }
            case 5 -> { a = c = d = f = g = true; }
            case 6 -> { a = c = d = e = f = g = true; }
            case 7 -> { a = b = c = true; }
            case 8 -> { a = b = c = d = e = f = g = true; }
            case 9 -> { a = b = c = d = f = g = true; }
            default -> { }
        }
        double left = x - w / 2.0;
        double right = x + w / 2.0;
        double top = y + h / 2.0;
        double bottom = y - h / 2.0;
        double mid = y;
        float[] col = {1.0F, 1.0F, 1.0F, 0.95F};

        if (a) line(vc, m, left, top, z, right, top, z, col[0], col[1], col[2], col[3]);
        if (g) line(vc, m, left, mid, z, right, mid, z, col[0], col[1], col[2], col[3]);
        if (d) line(vc, m, left, bottom, z, right, bottom, z, col[0], col[1], col[2], col[3]);
        if (f) line(vc, m, left, mid, z, left, top, z, col[0], col[1], col[2], col[3]);
        if (e) line(vc, m, left, bottom, z, left, mid, z, col[0], col[1], col[2], col[3]);
        if (b) line(vc, m, right, mid, z, right, top, z, col[0], col[1], col[2], col[3]);
        if (c) line(vc, m, right, bottom, z, right, mid, z, col[0], col[1], col[2], col[3]);
    }

    /**
     * 取类型颜色（★ 审计 R3-P3-6：改为静态常量表，不再每次 new float[4]）。
     *
     * <p>原先每帧对每个控制点都 new 一个 float[4] —— 一条 100 点的路线
     * 每秒就要分配 6000 个短命数组。{@link RouteKind} 只有三个取值，
     * 预建三份常量即可，且返回的数组**只读**（调用方只读取分量）。</p>
     */
    private static float[] rgbOf(RouteKind kind) {
        if (kind == null) {
            return COLOR_ROAD;
        }
        return switch (kind) {
            case ROAD -> COLOR_ROAD;
            case BRIDGE -> COLOR_BRIDGE;
            case TUNNEL -> COLOR_TUNNEL;
        };
    }

    /** 与 {@link RouteKind#editorColor()} 保持一致的预建颜色常量。 */
    private static final float[] COLOR_ROAD = rgb(RouteKind.ROAD);
    private static final float[] COLOR_BRIDGE = rgb(RouteKind.BRIDGE);
    private static final float[] COLOR_TUNNEL = rgb(RouteKind.TUNNEL);

    private static float[] rgb(RouteKind kind) {
        int c = kind.editorColor();
        return new float[]{
                ((c >> 16) & 0xFF) / 255.0F,
                ((c >> 8) & 0xFF) / 255.0F,
                (c & 0xFF) / 255.0F,
                1.0F
        };
    }

    // ───────────────────────── 图元 ─────────────────────────

    /**
     * 画一条线。
     *
     * <p>1.21.1 的 {@code RenderType.lines()} 要求**成对**提交顶点；
     * 法线只是 API 必填项（线段不参与光照），这里给单位方向向量。</p>
     */
    private static void line(VertexConsumer vc, Matrix4f m,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-5F) {
            return;
        }
        nx /= len; ny /= len; nz /= len;
        vc.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(m, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    /** 画线框立方体（12 条棱）。 */
    private static void box(VertexConsumer vc, Matrix4f m,
                            double cx, double cy, double cz, float half,
                            float r, float g, float b) {
        float x0 = (float) (cx - half), x1 = (float) (cx + half);
        float y0 = (float) (cy - half), y1 = (float) (cy + half);
        float z0 = (float) (cz - half), z1 = (float) (cz + half);

        // 下底
        line(vc, m, x0, y0, z0, x1, y0, z0, r, g, b, 1F);
        line(vc, m, x1, y0, z0, x1, y0, z1, r, g, b, 1F);
        line(vc, m, x1, y0, z1, x0, y0, z1, r, g, b, 1F);
        line(vc, m, x0, y0, z1, x0, y0, z0, r, g, b, 1F);
        // 上底
        line(vc, m, x0, y1, z0, x1, y1, z0, r, g, b, 1F);
        line(vc, m, x1, y1, z0, x1, y1, z1, r, g, b, 1F);
        line(vc, m, x1, y1, z1, x0, y1, z1, r, g, b, 1F);
        line(vc, m, x0, y1, z1, x0, y1, z0, r, g, b, 1F);
        // 立柱
        line(vc, m, x0, y0, z0, x0, y1, z0, r, g, b, 1F);
        line(vc, m, x1, y0, z0, x1, y1, z0, r, g, b, 1F);
        line(vc, m, x1, y0, z1, x1, y1, z1, r, g, b, 1F);
        line(vc, m, x0, y0, z1, x0, y1, z1, r, g, b, 1F);
    }

    // ───────────────────── 编辑模式生命周期 ─────────────────────

    public static void onEnterEditMode() {
        RouteWorldEditor.reset();
    }

    public static void onExitEditMode() {
        RouteWorldEditor.reset();
    }

    /** 当前是否正在世界内编辑。 */
    public static boolean isEditing() {
        return RouteEditorSession.get().isActive();
    }

    /** 相机位置（命中检测与渲染共用，避免两处算法不一致）。 */
    public static Vec3 cameraPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return null;
        }
        return mc.gameRenderer.getMainCamera().getPosition();
    }
}
