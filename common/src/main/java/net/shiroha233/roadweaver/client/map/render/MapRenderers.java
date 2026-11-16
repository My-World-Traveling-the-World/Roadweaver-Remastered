/* 文件职责：集中绘制地图网格、结构、道路与玩家标记。 */
package net.shiroha233.roadweaver.client.map.render;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.shiroha233.roadweaver.core.model.StructureConnection;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/**
 * 地图渲染器集合
 */
public final class MapRenderers {
    private MapRenderers() {}

    public interface SegmentInView { boolean test(int x1, int z1, int x2, int z2); }

    public static void renderGrid(GuiGraphics g,
                                  Font font,
                                  int mapX, int mapY, int mapW, int mapH,
                                  int innerPad,
                                  double viewMinX, double viewMaxX, double viewMinZ, double viewMaxZ,
                                  int colorGrid, int gridTargetPx, int colorText) {
        GridRenderer.render(g, font, mapX, mapY, mapW, mapH, innerPad,
                viewMinX, viewMaxX, viewMinZ, viewMaxZ, colorGrid, gridTargetPx, colorText);
    }

    public static void renderStructures(GuiGraphics g,
                                        Font font,
                                        List<BlockPos> points,
                                        Function<BlockPos, String> structureIdResolver,
                                        IntUnaryOperator toScreenX,
                                        IntUnaryOperator toScreenY,
                                        java.util.function.BiPredicate<Integer, Integer> isInViewWorld,
                                        int size,
                                        int left, int top, int right, int bottom) {
        for (BlockPos p : points) {
            int bx = p.getX();
            int bz = p.getZ();
            if (!isInViewWorld.test(bx, bz)) continue;
            int x = toScreenX.applyAsInt(bx);
            int y = toScreenY.applyAsInt(bz);
            String structureId = structureIdResolver != null ? structureIdResolver.apply(p) : null;
            StructureIconRenderer.render(g, font, structureId, x, y, Math.max(10, size + 2), left, top, right, bottom);
        }
    }

    public static void renderConnections(GuiGraphics g,
                                         List<StructureConnection> connections,
                                         SegmentInView segmentInView,
                                         IntUnaryOperator toScreenX,
                                         IntUnaryOperator toScreenY,
                                         int thickness,
                                         int colorPlanned, int colorGenerating, int colorCompleted, int colorFailed,
                                         int left, int top, int right, int bottom) {
        for (StructureConnection c : connections) {
            int fx = c.from().getX();
            int fz = c.from().getZ();
            int tx = c.to().getX();
            int tz = c.to().getZ();
            if (!segmentInView.test(fx, fz, tx, tz)) continue;
            int x1 = toScreenX.applyAsInt(fx);
            int y1 = toScreenY.applyAsInt(fz);
            int x2 = toScreenX.applyAsInt(tx);
            int y2 = toScreenY.applyAsInt(tz);
            switch (c.status()) {
                case PLANNED -> {
                    RenderUtils.drawThickLine(g, x1, y1, x2, y2, colorPlanned, thickness, left, top, right, bottom);
                }
                case GENERATING -> {
                    RenderUtils.drawThickDashedLine(g, x1, y1, x2, y2, colorGenerating, thickness, 8, 6, left, top, right, bottom);
                }
                case COMPLETED -> {
                    RenderUtils.drawThickLine(g, x1, y1, x2, y2, colorCompleted, thickness, left, top, right, bottom);
                }
                case PROTECTED_GAP -> {
                    RenderUtils.drawThickDashedLine(g, x1, y1, x2, y2, colorFailed, thickness, 6, 4, left, top, right, bottom);
                }
                case FAILED -> {
                    RenderUtils.drawThickLine(g, x1, y1, x2, y2, colorFailed, thickness, left, top, right, bottom);
                }
            }
        }
    }

    public static void renderRoadPolylines(GuiGraphics g,
                                           RoadPolylineCache cache,
                                           List<List<BlockPos>> polylines,
                                           SegmentInView segmentInView,
                                           IntUnaryOperator toScreenX,
                                           IntUnaryOperator toScreenY,
                                           int thickness,
                                           int color,
                                           int left, int top, int right, int bottom,
                                           int lodStepBlocks) {
        cache.forEachVisible(polylines, lodStepBlocks, segmentInView, (x1, z1, x2, z2) ->
                RenderUtils.drawThickLine(g, toScreenX.applyAsInt(x1), toScreenY.applyAsInt(z1),
                        toScreenX.applyAsInt(x2), toScreenY.applyAsInt(z2), color, thickness, left, top, right, bottom));
    }

    @FunctionalInterface
    public interface RoadLineConsumer {
        void draw(int x1, int z1, int x2, int z2);
    }

    /** 屏幕会话内按不可变折线列表和实际抽样步长缓存世界坐标；平移每帧仍用当前视口。 */
    public static final class RoadPolylineCache {
        private static final int MAX_LOD_STEPS = 2;
        private List<List<BlockPos>> source;
        private final Map<Integer, int[]> lod = new LinkedHashMap<>(4, 0.75F, true);

        public void forEachVisible(List<List<BlockPos>> polylines, int step, SegmentInView inView,
                                   RoadLineConsumer consumer) {
            if (source != polylines) {
                // 删路、切维度、更新快照和过滤列表都可能复用同一缩放档位。
                source = polylines;
                lod.clear();
            }
            int[] segments = lod.get(step);
            if (segments == null) {
                segments = compile(polylines, step);
                if (lod.size() >= MAX_LOD_STEPS) lod.remove(lod.keySet().iterator().next());
                lod.put(step, segments);
            }
            for (int i = 0; i < segments.length; i += 4) {
                int x1 = segments[i], z1 = segments[i + 1], x2 = segments[i + 2], z2 = segments[i + 3];
                if (inView.test(x1, z1, x2, z2)) consumer.draw(x1, z1, x2, z2);
            }
        }

        private static int[] compile(List<List<BlockPos>> polylines, int step) {
            // 每档只存整数端点，保持原 roads/stations 遍历序与末段判定。
            IntArrayList segments = new IntArrayList();
            for (List<BlockPos> pl : polylines) {
                if (pl == null || pl.size() < 2) continue;
                BlockPos keep = pl.getFirst();
                for (int i = 1; i < pl.size(); i++) {
                    BlockPos curr = pl.get(i);
                    if (Math.abs(curr.getX() - keep.getX()) + Math.abs(curr.getZ() - keep.getZ()) < step) continue;
                    append(segments, keep, curr);
                    keep = curr;
                }
                BlockPos tail = pl.getLast();
                if (tail != keep) append(segments, keep, tail);
            }
            return segments.toIntArray();
        }

        private static void append(IntArrayList segments, BlockPos from, BlockPos to) {
            segments.add(from.getX());
            segments.add(from.getZ());
            segments.add(to.getX());
            segments.add(to.getZ());
        }
    }

    public static void drawPlayerAvatar(GuiGraphics g,
                                        ResourceLocation skinTexture,
                                        int sx,
                                        int sy,
                                        float yawDeg,
                                        int size,
                                        int frameColor,
                                        int directionColor,
                                        int directionOutlineColor,
                                        int directionTipLen,
                                        int directionBaseOffset,
                                        int directionHalfWidth,
                                        int left,
                                        int top,
                                        int right,
                                        int bottom) {
        if (skinTexture == null) return;
        double rx = Math.toRadians(yawDeg);
        double dirX = -Math.sin(rx);
        double dirY = Math.cos(rx);
        double perpX = -dirY;
        double perpY = dirX;
        double tipX = sx + dirX * directionTipLen;
        double tipY = sy + dirY * directionTipLen;
        double baseX = sx + dirX * directionBaseOffset;
        double baseY = sy + dirY * directionBaseOffset;
        int ipx = (int) Math.round(tipX);
        int ipy = (int) Math.round(tipY);
        int ibx1 = (int) Math.round(baseX + perpX * directionHalfWidth);
        int iby1 = (int) Math.round(baseY + perpY * directionHalfWidth);
        int ibx2 = (int) Math.round(baseX - perpX * directionHalfWidth);
        int iby2 = (int) Math.round(baseY - perpY * directionHalfWidth);
        RenderUtils.fillTriangle(g, ipx - 1, ipy, ibx1 - 1, iby1, ibx2 - 1, iby2, directionOutlineColor, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx + 1, ipy, ibx1 + 1, iby1, ibx2 + 1, iby2, directionOutlineColor, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx, ipy - 1, ibx1, iby1 - 1, ibx2, iby2 - 1, directionOutlineColor, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx, ipy + 1, ibx1, iby1 + 1, ibx2, iby2 + 1, directionOutlineColor, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx, ipy, ibx1, iby1, ibx2, iby2, directionColor, left, top, right, bottom);

        int half = size / 2;
        int drawX = sx - half;
        int drawY = sy - half;
        if (drawX > right || drawX + size < left || drawY > bottom || drawY + size < top) {
            return;
        }
        g.fill(drawX - 2, drawY - 2, drawX + size + 2, drawY + size + 2, frameColor);
        g.fill(drawX - 1, drawY - 1, drawX + size + 1, drawY + size + 1, 0x66FFFFFF);
        PlayerFaceRenderer.draw(g, skinTexture, drawX, drawY, size);
    }

    public static void drawPlayerArrow(GuiGraphics g,
                                       int sx, int sy,
                                       float yawDeg,
                                       int tipLen,
                                       int baseLen,
                                       int baseHalfWidth,
                                       int color,
                                       int left, int top, int right, int bottom,
                                       double pxPerBlockX,
                                       double pxPerBlockZ) {
        double rx = Math.toRadians(yawDeg);
        double vx = -Math.sin(rx);
        double vz = Math.cos(rx);
        double dirX = vx * pxPerBlockX;
        double dirY = vz * pxPerBlockZ;
        double mag = Math.hypot(dirX, dirY);
        if (mag < 1e-6) { dirX = 1; dirY = 0; mag = 1; }
        dirX /= mag; dirY /= mag;
        double px = sx + dirX * tipLen;
        double py = sy + dirY * tipLen;
        double bx = sx - dirX * baseLen;
        double by = sy - dirY * baseLen;
        double perpX = -dirY;
        double perpY = dirX;
        double bx1 = bx + perpX * baseHalfWidth;
        double by1 = by + perpY * baseHalfWidth;
        double bx2 = bx - perpX * baseHalfWidth;
        double by2 = by - perpY * baseHalfWidth;
        int ipx = (int)Math.round(px);
        int ipy = (int)Math.round(py);
        int ibx1 = (int)Math.round(bx1);
        int iby1 = (int)Math.round(by1);
        int ibx2 = (int)Math.round(bx2);
        int iby2 = (int)Math.round(by2);
        int outline = 0xFFFFFFFF;
        RenderUtils.fillTriangle(g, ipx - 1, ipy,     ibx1 - 1, iby1,     ibx2 - 1, iby2,     outline, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx + 1, ipy,     ibx1 + 1, iby1,     ibx2 + 1, iby2,     outline, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx,     ipy - 1, ibx1,     iby1 - 1, ibx2,     iby2 - 1, outline, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx,     ipy + 1, ibx1,     iby1 + 1, ibx2,     iby2 + 1, outline, left, top, right, bottom);
        RenderUtils.fillTriangle(g, ipx,     ipy,     ibx1,     iby1,     ibx2,     iby2,     color,   left, top, right, bottom);
    }
}
