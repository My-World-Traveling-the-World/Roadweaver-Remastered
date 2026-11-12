package net.shiroha233.roadweaver.route.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.shiroha233.roadweaver.route.PlayerRoute;
import net.shiroha233.roadweaver.route.RouteKind;
import net.shiroha233.roadweaver.route.RouteKindResolver;
import net.shiroha233.roadweaver.route.RouteSmoother;
import org.joml.Matrix4f;

/**
 * **施工预览**：在世界里显示「按当前路线会建成什么」。
 *
 * <h2>用户需求</h2>
 * 「用粒子大概几个格子渲染一条 - - - - 这样的，按照颜色来区别路/桥/隧道的颜色」
 * —— 视觉上是虚线风格，按类型上色。
 *
 * <h2>这里为什么不用粒子</h2>
 * 与 {@link RouteWorldRenderer} 同样的理由（见那里的注释）：粒子不能射线选中、
 * 长线要每秒重发上百个包、位置还会抖动。改用 {@link RenderType#lines()} 画
 * **虚线**（线段按 dash 间隔跳着画），视觉效果与「- - - -」一致，
 * 但性能好、能配合准星、不抖动。
 *
 * <h2>预览显示什么（诚实说明）</h2>
 * 这是**足迹预览**，不是「跑完整个规划管线后的精确几何」——
 * 后者要跑 A* 与地形采样（秒级、必须异步），不适合实时拖动时每帧刷新。
 * 所以这里展示的是：
 * <ul>
 *   <li><b>路面足迹</b>：沿平滑路径按道路宽度画横向短线，
 *       让玩家看出「路有多宽、往哪走」</li>
 *   <li><b>桥/隧道分段</b>：用类型色 + 更密的线表示，
 *       并在桥/隧道的起止处画竖直标记柱，提示「这里开始架桥 / 进洞」</li>
 *   <li><b>高度</b>：线的 Y 就是玩家给的目标高度，所以能直接看出桥有多高、
 *       隧道入地多深</li>
 * </ul>
 *
 * <p>真正的最终几何仍由施工时的管线决定（含宽度细化、避结构等），
 * 预览与其在大方向上一致（同一套控制点与高度），但不承诺逐格相同。</p>
 */
public final class RoutePreviewRenderer {

    /** 虚线：实线段长度与间隔（世界单位）。 */
    private static final float DASH = 1.6F;
    private static final float GAP = 1.1F;

    /** 路面横线的半宽（格）。取 2 ≈ 5 格宽的路，与默认配置接近。 */
    private static final float HALF_WIDTH = 2.0F;

    /** 横向线间距（沿路径每隔多少格画一条）。 */
    private static final float RIBBON_STEP = 2.5F;

    /** 桥/隧道起止标记柱的高度。 */
    private static final float MARKER_HEIGHT = 3.0F;

    /** 预览整体透明度。 */
    private static final float ALPHA = 0.75F;

    private RoutePreviewRenderer() {
    }

    /** 预览是否开启（由编辑器切换）。 */
    private static boolean enabled = true;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void toggle() {
        enabled = !enabled;
    }

    /**
     * 渲制预览（由世界渲染事件调用）。
     */
    public static void render(PoseStack poseStack, MultiBufferSource consumers, Vec3 cameraPos) {
        if (!enabled) {
            return;
        }
        RouteEditorSession session = RouteEditorSession.get();
        if (!session.isActive()) {
            return;
        }
        PlayerRoute route = session.current();
        // cameraPos 必须判空：@ExpectPlatform 桥在拿不到相机时会传 null，
        // 而下面直接用 cameraPos.x/y/z —— 不判空会 NPE 把渲染线程搞崩。
        if (route == null || !route.isDrawable()
                || poseStack == null || consumers == null || cameraPos == null) {
            return;
        }

        // ★ 审计 P2-4：平滑结果与逐段类型都带缓存。
        //
        // 原实现对每帧都跑一次 RouteSmoother.smooth，而且 kindBetween 对
        // 每个路径点做 O(控制点数) 的全表扫描 → 整体 O(8N²) 外加每帧约 8N 个
        // BlockPos 分配。N=100 时是 64k 次距离计算 + 800 次分配/帧。
        // （真正会掉帧的是这里，不是 RouteWorldRenderer 的线框。）
        Cached cached = cachedFor(route);
        List<BlockPos> path = cached.path;
        if (path.size() < 2) {
            return;
        }
        List<RouteKind> kinds = cached.kinds;

        VertexConsumer vc = consumers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f m = poseStack.last().pose();

        renderRibbon(vc, m, path, kinds);
        renderSectionMarkers(vc, m, path, kinds);

        poseStack.popPose();
    }

    // ─────────────────── 缓存（审计 P2-4）───────────────────
    //
    // 缓存键用 PlayerRoute 的**对象引用**：PlayerRoute 是不可变的，
    // 任何编辑都会产生新实例（见 PlayerRoute 的 withXxx 方法），
    // 所以 `route == cachedRoute` 就等价于「没被改过」。

    private record Cached(PlayerRoute route, List<BlockPos> path, List<RouteKind> kinds) {
    }

    private static volatile Cached cache;

    private static Cached cachedFor(PlayerRoute route) {
        Cached hit = cache;
        if (hit != null && hit.route == route) {
            return hit;
        }
        List<BlockPos> path = RouteSmoother.smooth(route, null);
        // 逐段类型一次算完：沿路径**单调推进**控制点游标，
        // 而不是对每个点重扫全部控制点（那是 O(N²) 的来源）。
        List<RouteKind> kinds = computeSegmentKinds(route, path);
        Cached fresh = new Cached(route, path, kinds);
        cache = fresh;
        return fresh;
    }

    /**
     * 为路径上每一点算出应显示的类型。
     *
     * <h2>★ 审计 R3-P1-5：统一走 {@link RouteKindResolver}</h2>
     * 原实现自己写了一套「单调游标 + 最近控制点」（规则 C），虽然与
     * {@code RouteSegmentMapper} 的规则 B 接近，但两者都不等于
     * {@code RouteSmoother} 的规则 A（段起点）—— 三份实现各自"对"，
     * 叠加起来就是「桥在段中点被截断」。
     *
     * <p>现在预览、施工映射、平滑器共用 {@link RouteKindResolver} 一份实现，
     * 所以「玩家预览看到的类型」必然等于「实际施工的类型」。</p>
     */
    private static List<RouteKind> computeSegmentKinds(PlayerRoute route, List<BlockPos> path) {
        // 路径点沿路线单调排列，RouteKindResolver.Cursor 因此是 O(N+M)
        return RouteKindResolver.kindsAlong(route, path);
    }

    /**
     * 路面足迹：沿路径每隔一段画一条横向虚线，形成「虚线画出的路面」。
     */
    private static void renderRibbon(VertexConsumer vc, Matrix4f m,
                                     List<BlockPos> path, List<RouteKind> kinds) {
        double acc = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            // 类型由缓存预计算好（原来是每段重扫全部控制点，O(N²)）
            RouteKind kind = i < kinds.size() ? kinds.get(i) : RouteKind.ROAD;
            float[] c = rgbOf(kind);

            double ax = a.getX() + 0.5, ay = a.getY() + 0.5, az = a.getZ() + 0.5;
            double bx = b.getX() + 0.5, by = b.getY() + 0.5, bz = b.getZ() + 0.5;
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-4) {
                continue;
            }
            // 水平法向（用于画横向线）
            double nx = -dz / len;
            double nz = dx / len;
            double nh = Math.sqrt(nx * nx + nz * nz);
            if (nh < 1e-4) {
                continue;
            }
            nx /= nh;
            nz /= nh;

            // 沿这段按 RIBBON_STEP 画横线（虚线段）
            acc += len;
            while (acc >= RIBBON_STEP) {
                acc -= RIBBON_STEP;
                double t = 1.0 - acc / len;
                double px = ax + dx * t;
                double py = ay + dy * t;
                double pz = az + dz * t;
                // 横线本身就是虚线：分成两小段中间留空
                dashedCrossLine(vc, m, px, py, pz, nx, nz, c);
            }
        }
    }

    /** 画一条「中间断开」的横向线，形成 - - 的观感。 */
    private static void dashedCrossLine(VertexConsumer vc, Matrix4f m,
                                        double px, double py, double pz,
                                        double nx, double nz, float[] c) {
        float seg = HALF_WIDTH * 0.7F;
        line(vc, m, px - nx * HALF_WIDTH, py, pz - nz * HALF_WIDTH,
                px - nx * seg, py, pz - nz * seg, c);
        line(vc, m, px + nx * seg, py, pz + nz * seg,
                px + nx * HALF_WIDTH, py, pz + nz * HALF_WIDTH, c);
    }

    /**
     * 桥/隧道分段的起止标记柱 —— 提示玩家「这里开始架桥 / 进洞」。
     */
    private static void renderSectionMarkers(VertexConsumer vc, Matrix4f m,
                                             List<BlockPos> path, List<RouteKind> kinds) {
        RouteKind prev = null;
        for (int i = 0; i + 1 < path.size(); i++) {
            RouteKind kind = i < kinds.size() ? kinds.get(i) : RouteKind.ROAD;
            if (kind != prev) {
                if (kind == RouteKind.BRIDGE || kind == RouteKind.TUNNEL) {
                    BlockPos p = path.get(i);
                    float[] c = rgbOf(kind);
                    double x = p.getX() + 0.5, y = p.getY() + 0.5, z = p.getZ() + 0.5;
                    // 向上和向下各一根短柱：桥向上、隧道也向上（都在点的位置标出）
                    line(vc, m, x, y, z, x, y + MARKER_HEIGHT, z, c);
                    line(vc, m, x, y, z, x, y - MARKER_HEIGHT * 0.5, z, c);
                }
                prev = kind;
            }
        }
        // 末端：用缓存里最后一段的类型（原来是 route.point(last).kind()，
        // 与前面 kindBetween 的判据不一致，可能给出矛盾的颜色）
        if (!path.isEmpty() && !kinds.isEmpty()) {
            RouteKind kind = kinds.get(kinds.size() - 1);
            if (kind == RouteKind.BRIDGE || kind == RouteKind.TUNNEL) {
                BlockPos last = path.get(path.size() - 1);
                float[] c = rgbOf(kind);
                double x = last.getX() + 0.5, y = last.getY() + 0.5, z = last.getZ() + 0.5;
                line(vc, m, x, y, z, x, y + MARKER_HEIGHT, z, c);
            }
        }
    }

    private static float[] rgbOf(RouteKind kind) {
        int rgb = kind.editorColor();
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                ALPHA
        };
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2, float[] c) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-5F) {
            return;
        }
        nx /= len; ny /= len; nz /= len;
        vc.addVertex(m, (float) x1, (float) y1, (float) z1)
                .setColor(c[0], c[1], c[2], c[3]).setNormal(nx, ny, nz);
        vc.addVertex(m, (float) x2, (float) y2, (float) z2)
                .setColor(c[0], c[1], c[2], c[3]).setNormal(nx, ny, nz);
    }
}
