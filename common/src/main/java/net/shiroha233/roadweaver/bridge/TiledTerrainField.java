/* 文件职责：用有界三通道瓦片承接全局地形查询，避免先分配整场高度数组。 */
package net.shiroha233.roadweaver.bridge;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.shiroha233.roadweaver.metric.GridLayout;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest;

import java.util.Objects;

/** 瓦片只改变读入和保留方式；全局原点、步长、搜索边界和差分邻点均不变。 */
public final class TiledTerrainField implements TerrainField {
    // 每块最多 4096 列，64 块三通道数组共 3 MiB；不包括采样器自身缓存和临时工作区。
    public static final int TILE_SIDE = 64;
    public static final int MAX_TILES = 64;

    /** 生产必须传入 sampleGrid，让既有预算、认领、持久缓存和关闭检查仍然生效。 */
    @FunctionalInterface
    public interface GridSampler {
        AccurateHeightGrid sample(AccurateHeightGridRequest request);
    }

    private final GridLayout grid;
    private final int tileSide;
    private final int maxTiles;
    private final GridSampler sampler;
    private final Long2ObjectLinkedOpenHashMap<AccurateHeightGrid> tiles;
    private long loadAttempts, loadedTiles, loadedColumns, evictions, retainedHeightPayloadBytes;

    /** 统计真实请求和驱逐，避免只凭缓存容量声称没有重算；字节数只计三图原始数组。 */
    public record Stats(long loadAttempts, long loadedTiles, long loadedColumns, long evictions,
                        int retainedTiles, long retainedHeightPayloadBytes) {}

    public synchronized Stats stats() {
        return new Stats(loadAttempts, loadedTiles, loadedColumns, evictions, tiles.size(), retainedHeightPayloadBytes);
    }

    public TiledTerrainField(GridLayout grid, GridSampler sampler) {
        this(grid, TILE_SIDE, MAX_TILES, sampler);
    }

    /** 可调整瓦片的离线入口用于驱逐和接缝对拍，不增加玩家配置或改动采样精度。 */
    public TiledTerrainField(GridLayout grid, int tileSide, int maxTiles, GridSampler sampler) {
        this.grid = Objects.requireNonNull(grid, "grid");
        grid.arrayLength();
        if (tileSide <= 0 || maxTiles <= 0) throw new IllegalArgumentException("invalid tile cache size");
        this.tileSide = tileSide;
        this.maxTiles = maxTiles;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        // 原始长整型键避免每次梯度查询为瓦片坐标装箱。
        this.tiles = new Long2ObjectLinkedOpenHashMap<>(Math.min(maxTiles, 64));
    }

    @Override
    public int height(int x, int z) {
        requireInside(x, z);
        return heightAt(grid.snapX(x), grid.snapZ(z));
    }

    @Override
    public double[] gradient(int x, int z) {
        requireInside(x, z);
        int ix = grid.snapX(x), iz = grid.snapZ(z);
        // 邻点按全域索引裁边，不能在瓦片接缝处错误地改成单侧差分。
        int xm = Math.max(0, ix - 1), xp = Math.min(grid.columns() - 1, ix + 1);
        int zm = Math.max(0, iz - 1), zp = Math.min(grid.rows() - 1, iz + 1);
        double gx = xp == xm ? 0.0 : (heightAt(xp, iz) - heightAt(xm, iz))
                / ((double) (xp - xm) * grid.step());
        double gz = zp == zm ? 0.0 : (heightAt(ix, zp) - heightAt(ix, zm))
                / ((double) (zp - zm) * grid.step());
        return new double[]{gx, gz};
    }

    /** 只在原请求的精确采样列给出湿列代理，不能把吸附邻点当作该列的流体证据。 */
    public int waterProxy(int x, int z) {
        long dx = (long) x - grid.minX(), dz = (long) z - grid.minZ();
        if (!contains(x, z) || dx % grid.step() != 0 || dz % grid.step() != 0) return -1;
        int ix = (int) (dx / grid.step()), iz = (int) (dz / grid.step());
        AccurateHeightGrid tile = tile(ix, iz);
        int index = localIndex(ix, iz, tile.request().width());
        int gap = tile.worldSurface()[index] - tile.oceanFloor()[index];
        return gap > 0 ? gap : -1;
    }

    private int heightAt(int ix, int iz) {
        AccurateHeightGrid tile = tile(ix, iz);
        return tile.motionBlocking()[localIndex(ix, iz, tile.request().width())];
    }

    private int localIndex(int ix, int iz, int width) {
        return ix % tileSide + iz % tileSide * width;
    }

    private synchronized AccurateHeightGrid tile(int ix, int iz) {
        int tx = ix / tileSide, tz = iz / tileSide;
        long key = ((long) tx << 32) | (tz & 0xffffffffL);
        AccurateHeightGrid existing = tiles.getAndMoveToLast(key);
        if (existing != null) return existing;
        int firstX = tx * tileSide, firstZ = tz * tileSide;
        AccurateHeightGridRequest request = new AccurateHeightGridRequest(
                Math.toIntExact((long) grid.minX() + (long) firstX * grid.step()),
                Math.toIntExact((long) grid.minZ() + (long) firstZ * grid.step()),
                Math.min(tileSide, grid.columns() - firstX),
                Math.min(tileSide, grid.rows() - firstZ), grid.step());
        // 串行发布同一规划的缺页，防止并发梯度重复采样；失败或错位结果不得进入缓存。
        loadAttempts++;
        AccurateHeightGrid loaded = Objects.requireNonNull(sampler.sample(request), "sampled tile");
        if (!request.equals(loaded.request())) throw new IllegalArgumentException("sampled tile request mismatch");
        tiles.putAndMoveToLast(key, loaded);
        loadedTiles++;
        loadedColumns += request.sampleCount();
        retainedHeightPayloadBytes += 3L * Integer.BYTES * request.sampleCount();
        if (tiles.size() > maxTiles) {
            AccurateHeightGrid removed = tiles.removeFirst();
            retainedHeightPayloadBytes -= 3L * Integer.BYTES * removed.request().sampleCount();
            evictions++;
        }
        return loaded;
    }

    private void requireInside(int x, int z) {
        if (!contains(x, z)) throw new IllegalArgumentException("point outside tiled terrain: " + x + "," + z);
    }

    @Override public int minX() { return grid.minX(); }
    @Override public int minZ() { return grid.minZ(); }
    @Override public int maxX() { return grid.maxX(); }
    @Override public int maxZ() { return grid.maxZ(); }
    @Override public int step() { return grid.step(); }
}
