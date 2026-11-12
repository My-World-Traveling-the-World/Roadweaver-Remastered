/* 文件职责：从 region 提取的真实地形网格（CSV → 只读量化场）。 */
package net.shiroha233.roadweaver.tuning;

import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * 真实世界地形（由 extract_region.py 从 region 文件解码出的 CSV 加载），
 * 提供与 {@link SyntheticTerrain} 同名的只读采样方法，供 {@link RealTerrainField}
 * 适配进 {@code PathTerrainField} 后离线跑势场法。
 *
 * <p>CSV 列序：{@code x,z,surfaceY,groundY,isWater,waterDepth,biome}。
 * 无表头行也可（load 自动跳过含非数字首列的 header）。
 */
public final class RealTerrain implements RouteQualityMetrics.WaterProbe {

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] surface;      // 水面（水柱）= 海平面；陆地 = 地表
    private final int[] floor;        // 水下地面 / 陆地地面
    private final int[] waterDepth;   // 水柱深度（陆地 0）
    private final PathTerrainField.BiomeCategory[] category;

    private RealTerrain(int minX, int minZ, int width, int depth,
                        int[] surface, int[] floor, int[] waterDepth,
                        PathTerrainField.BiomeCategory[] category) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.surface = surface;
        this.floor = floor;
        this.waterDepth = waterDepth;
        this.category = category;
    }

    public static RealTerrain load(String csvPath) throws IOException {
        // 第一遍：定界。
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        try (BufferedReader r = new BufferedReader(new FileReader(csvPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",");
                if (f.length < 6) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(f[0].trim());
                    int z = Integer.parseInt(f[1].trim());
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                } catch (NumberFormatException skipHeader) {
                    // header 行跳过。
                }
            }
        }
        if (minX > maxX) {
            throw new IOException("empty terrain CSV: " + csvPath);
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int n = width * depth;
        int[] surface = new int[n];
        int[] floor = new int[n];
        int[] waterDepth = new int[n];
        PathTerrainField.BiomeCategory[] category = new PathTerrainField.BiomeCategory[n];
        for (int i = 0; i < n; i++) {
            surface[i] = Integer.MIN_VALUE; // 哨兵：无数据
        }

        // 第二遍：填充。
        try (BufferedReader r = new BufferedReader(new FileReader(csvPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",");
                if (f.length < 6) {
                    continue;
                }
                int x, z, gr, wd;
                try {
                    x = Integer.parseInt(f[0].trim());
                    z = Integer.parseInt(f[1].trim());
                    gr = Integer.parseInt(f[3].trim());
                    wd = Integer.parseInt(f[5].trim());
                } catch (NumberFormatException skipHeader) {
                    continue;
                }
                int idx = (z - minZ) * width + (x - minX);
                // 路由面 = 水柱取水面（groundY + waterDepth），陆地取地面（groundY）。
                // 不用 CSV 的 surfaceY（首个非空气块，会混入树冠/结构），否则结构顶会被当成山。
                surface[idx] = gr + wd;
                floor[idx] = gr;
                waterDepth[idx] = wd;
                boolean water = wd > 0;
                String biome = f.length > 6 ? f[6].trim() : "";
                category[idx] = water ? PathTerrainField.BiomeCategory.WATER : mapBiome(biome);
            }
        }
        return new RealTerrain(minX, minZ, width, depth, surface, floor, waterDepth, category);
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return minX + width - 1;
    }

    public int maxZ() {
        return minZ + depth - 1;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    private int idx(int x, int z) {
        return (z - minZ) * width + (x - minX);
    }

    public boolean contains(int x, int z) {
        if (x < minX || x > maxX() || z < minZ || z > maxZ()) {
            return false;
        }
        return surface[idx(x, z)] != Integer.MIN_VALUE;
    }

    @Override
    public boolean isWater(int x, int z) {
        if (!contains(x, z)) {
            return false;
        }
        return waterDepth[idx(x, z)] > 0;
    }

    public int surfaceY(int x, int z) {
        return surface[idx(x, z)];
    }

    public int floorY(int x, int z) {
        return floor[idx(x, z)];
    }

    public int waterDepth(int x, int z) {
        return waterDepth[idx(x, z)];
    }

    public boolean isNearWater(int x, int z, int d) {
        for (int dz = -d; dz <= d; dz++) {
            for (int dx = -d; dx <= d; dx++) {
                if (isWater(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    public PathTerrainField.BiomeCategory biomeCategory(int x, int z) {
        return category[idx(x, z)];
    }

    /**
     * 生物群系 ID → 路由类别。启发式：按关键词分到 MOUNTAIN/SWAMP/FOREST/DESERT/PLAINS/WATER/OTHER，
     * 用于让「群系代价」参数在真实地形上生效。水柱已由加载逻辑优先判为 WATER。
     */
    private static PathTerrainField.BiomeCategory mapBiome(String id) {
        String l = id.toLowerCase();
        if (l.contains("mountain") || l.contains("peak") || l.contains("slope")
                || l.contains("alpine") || l.contains("highland") || l.contains("meadow")
                || l.contains("grove") || l.contains("cliff") || l.contains("caldera")
                || l.contains("volcanic") || l.contains("shattered") || l.contains("canyon")
                || l.contains("windswept") || l.contains("badlands")) {
            return PathTerrainField.BiomeCategory.MOUNTAIN;
        }
        if (l.contains("swamp") || l.contains("marsh") || l.contains("bog")
                || l.contains("mangrove")) {
            return PathTerrainField.BiomeCategory.SWAMP;
        }
        if (l.contains("forest") || l.contains("wood") || l.contains("jungle")
                || l.contains("taiga") || l.contains("redwood") || l.contains("grove")) {
            return PathTerrainField.BiomeCategory.FOREST;
        }
        if (l.contains("desert") || l.contains("dune") || l.contains("sandstone")) {
            return PathTerrainField.BiomeCategory.DESERT;
        }
        if (l.contains("plains") || l.contains("savanna") || l.contains("steppe")
                || l.contains("grassland") || l.contains("prairie") || l.contains("shrubland")
                || l.contains("brushland")) {
            return PathTerrainField.BiomeCategory.PLAINS;
        }
        if (l.contains("river") || l.contains("ocean") || l.contains("lake")
                || l.contains("sea") || l.contains("beach") || l.contains("shore")
                || l.contains("creek")) {
            return PathTerrainField.BiomeCategory.WATER;
        }
        return PathTerrainField.BiomeCategory.OTHER;
    }
}
