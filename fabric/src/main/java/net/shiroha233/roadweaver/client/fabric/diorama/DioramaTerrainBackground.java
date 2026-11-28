/* 文件职责：从 Diorama 的现有地表快照绘制道路地图底图。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.MapSession;
import dev.diorama.ServerRules;
import dev.diorama.render.MapLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.material.MapColor;
import net.shiroha233.roadweaver.client.map.MapTerrainBackground;
import net.shiroha233.roadweaver.client.map.MapView;

/** 从 Diorama 内存里的地表快照重绘俯视图；不用斜视成品瓦片，避免道路和点选位置漂移。 */
final class DioramaTerrainBackground implements MapTerrainBackground {
    private static final int SIZE = DioramaTerrainGrid.SIDE;
    private static final int PIXELS_PER_FRAME = 2048;
    private static final long REFRESH_NS = 2_000_000_000L;
    private final int[] pixels = new int[SIZE * SIZE];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private MapLevel map;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private DioramaTerrainGrid completed;
    private DioramaTerrainGrid pending;
    private long nextRefresh;
    private int cursor;
    private boolean hasTerrain;
    private boolean fogOfWar;

    @Override
    public boolean render(GuiGraphics graphics, MapView view, int width, int height) {
        var mc = Minecraft.getInstance();
        var session = MapSession.get();
        // 尊重 Diorama 的维度和服务器限制；不跨会话复用地表图，也不展示洞穴。
        MapLevel current = mc.level != null && session != null && ServerRules.minimapAllowed()
                && !ServerRules.mapOff(mc.level.dimension().location().toString()) ? session.map() : null;
        if (current == null || current.roofless()) {
            if (map != null) reset();
            return false;
        }
        if (mc.level != level || current != map || fogOfWar != current.fogOfWar()) {
            reset();
            level = mc.level;
            map = current;
            // 服务器可在地图打开时收紧可见范围，之前的纹理不能留到下次刷新。
            fogOfWar = current.fogOfWar();
        }
        if (width <= 0 || height <= 0) return false;
        var grid = DioramaTerrainGrid.around(view.getMinX(), view.getMaxX(), view.getMinZ(), view.getMaxZ());
        long now = System.nanoTime();
        if (pending == null && (!grid.equals(completed) || now >= nextRefresh)
                || pending != null && !pending.overlaps(view.getMinX(), view.getMaxX(), view.getMinZ(), view.getMaxZ())) {
            pending = grid;
            cursor = 0;
            hasTerrain = false;
        }
        if (pending != null) advance(mc, now);
        if (textureId == null || completed == null
                || !completed.overlaps(view.getMinX(), view.getMaxX(), view.getMinZ(), view.getMaxZ())) return false;
        int x = view.toScreenX(completed.x(), 0, 0, width);
        int y = view.toScreenY(completed.z(), 0, 0, height);
        int right = view.toScreenX(completed.maxX(), 0, 0, width);
        int bottom = view.toScreenY(completed.maxZ(), 0, 0, height);
        if (right <= x || bottom <= y) return false;
        graphics.blit(textureId, x, y, right - x, bottom - y, 0, 0, SIZE, SIZE, SIZE, SIZE);
        return true;
    }

    private void advance(Minecraft mc, long now) {
        // 同一张纹理按帧摊平读取成本，平移期间旧图仍按原世界位置绘制。
        int limit = Math.min(pixels.length, cursor + PIXELS_PER_FRAME);
        for (; cursor < limit; cursor++) {
            int color = colorAt(pending.sampleX(cursor), pending.sampleZ(cursor));
            pixels[cursor] = color;
            hasTerrain |= color != 0;
        }
        if (cursor != pixels.length) return;
        completed = pending;
        pending = null;
        nextRefresh = now + REFRESH_NS;
        if (!hasTerrain) {
            releaseTexture(mc);
            return;
        }
        if (texture == null) {
            texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
            textureId = mc.getTextureManager().register("roadweaver_diorama_terrain", texture);
        }
        var image = texture.getPixels();
        for (int i = 0; i < pixels.length; i++) image.setPixelRGBA(i % SIZE, i / SIZE, pixels[i]);
        texture.upload();
    }

    private int colorAt(int x, int z) {
        // worldChunk 只查当前快照的内存表。未知地块保持透明，不读存档或请求服务端区块。
        var chunk = map.worldChunk(x >> 4, z >> 4);
        if (chunk == null) return 0;
        int y = chunk.surface(x, z);
        int section = (y >> 4) - chunk.minSectionY;
        if (section < 0 || section >= chunk.sectionCount() || fogOfWar && !map.isSeen(x, y, z)) return 0;
        var blocks = chunk.section(section);
        if (blocks == null) return 0;
        var state = blocks.get(x & 15, y & 15, z & 15);
        MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, pos.set(x, y, z));
        return color == MapColor.NONE ? 0 : color.calculateRGBColor(MapColor.Brightness.NORMAL);
    }

    @Override
    public void reset() {
        releaseTexture(Minecraft.getInstance());
        level = null;
        map = null;
        completed = null;
        pending = null;
        nextRefresh = 0;
    }

    private void releaseTexture(Minecraft mc) {
        if (textureId == null) return;
        mc.getTextureManager().release(textureId);
        textureId = null;
        texture = null;
    }
}
