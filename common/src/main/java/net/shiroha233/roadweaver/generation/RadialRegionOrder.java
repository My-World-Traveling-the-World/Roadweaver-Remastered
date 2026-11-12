/* 文件职责：按到中心的半径顺序枚举区域。 */
package net.shiroha233.roadweaver.generation;

import net.shiroha233.roadweaver.planning.RoadPlanningReadinessIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RadialRegionOrder {
    private RadialRegionOrder() {
    }

    record Region(int x, int z) {
    }

    static List<Region> betweenRadii(
            int centerChunkX,
            int centerChunkZ,
            int previousRadiusChunks,
            int radiusChunks) {
        int radius = Math.max(0, radiusChunks);
        int previous = Math.min(radius, previousRadiusChunks);
        int regionChunks = RoadPlanningReadinessIndex.REGION_CHUNKS;

        int minRegionX = Math.floorDiv(centerChunkX - radius, regionChunks);
        int maxRegionX = Math.floorDiv(centerChunkX + radius, regionChunks);
        int minRegionZ = Math.floorDiv(centerChunkZ - radius, regionChunks);
        int maxRegionZ = Math.floorDiv(centerChunkZ + radius, regionChunks);

        int oldMinRegionX = Math.floorDiv(centerChunkX - previous, regionChunks);
        int oldMaxRegionX = Math.floorDiv(centerChunkX + previous, regionChunks);
        int oldMinRegionZ = Math.floorDiv(centerChunkZ - previous, regionChunks);
        int oldMaxRegionZ = Math.floorDiv(centerChunkZ + previous, regionChunks);
        boolean includePrevious = previousRadiusChunks >= 0;

        ArrayList<Region> regions = new ArrayList<>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                if (includePrevious
                        && regionX >= oldMinRegionX && regionX <= oldMaxRegionX
                        && regionZ >= oldMinRegionZ && regionZ <= oldMaxRegionZ) {
                    continue;
                }
                regions.add(new Region(regionX, regionZ));
            }
        }

        long centerX2 = (long) centerChunkX * 2L;
        long centerZ2 = (long) centerChunkZ * 2L;
        regions.sort(Comparator
                .comparingLong((Region region) -> chebyshevDistance2(region, centerX2, centerZ2, regionChunks))
                .thenComparingLong(region -> squareDistance2(region, centerX2, centerZ2, regionChunks))
                .thenComparingInt(Region::z)
                .thenComparingInt(Region::x));
        return regions;
    }

    static long chebyshevDistance2(Region region, long centerChunkX2, long centerChunkZ2, int regionChunks) {
        long regionCenterX2 = (long) region.x() * regionChunks * 2L + regionChunks - 1L;
        long regionCenterZ2 = (long) region.z() * regionChunks * 2L + regionChunks - 1L;
        return Math.max(Math.abs(regionCenterX2 - centerChunkX2), Math.abs(regionCenterZ2 - centerChunkZ2));
    }

    static long squareDistance2(Region region, long centerChunkX2, long centerChunkZ2, int regionChunks) {
        long regionCenterX2 = (long) region.x() * regionChunks * 2L + regionChunks - 1L;
        long regionCenterZ2 = (long) region.z() * regionChunks * 2L + regionChunks - 1L;
        long dx = regionCenterX2 - centerChunkX2;
        long dz = regionCenterZ2 - centerChunkZ2;
        return dx * dx + dz * dz;
    }
}
