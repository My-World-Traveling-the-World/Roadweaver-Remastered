/* 文件职责：用两张世界生成高度图（地表 / 海底）判定地表水列。 */
package net.shiroha233.roadweaver.pathfinding.terrain;

/** 由世界生成高度图判别表面水列，避免以群系名称代替实际水深。
 *
 * Classifies surface-water columns from the two world-generation heightmaps.
 */
public final class SurfaceWaterClassifier {
    private SurfaceWaterClassifier() {}

    public static boolean isWaterColumn(int worldSurfaceY,
                                        int oceanFloorY,
                                        boolean waterBiome,
                                        int seaLevel) {
        return worldSurfaceY > oceanFloorY || (waterBiome && oceanFloorY < seaLevel);
    }

    public static int waterSurfaceY(int worldSurfaceY,
                                    int oceanFloorY,
                                    boolean waterBiome,
                                    int seaLevel,
                                    boolean waterColumn) {
        if (!waterColumn) return Integer.MIN_VALUE;
        if (waterBiome && oceanFloorY < seaLevel) {
            // Preserve the established sea-level coordinate used by bridge
            // profiles. WORLD_SURFACE_WG is normally one block above the top
            // fluid block, so using it here would lift every ocean bridge by
            // one block. Elevated non-water-biome lakes use their own sampled
            // surface below.
            return seaLevel;
        }
        return worldSurfaceY;
    }

    public static int waterDepth(int worldSurfaceY,
                                 int oceanFloorY,
                                 boolean waterBiome,
                                 int seaLevel,
                                 boolean waterColumn) {
        int surfaceY = waterSurfaceY(worldSurfaceY, oceanFloorY, waterBiome, seaLevel, waterColumn);
        return surfaceY == Integer.MIN_VALUE ? 0 : Math.max(0, surfaceY - oceanFloorY);
    }
}
