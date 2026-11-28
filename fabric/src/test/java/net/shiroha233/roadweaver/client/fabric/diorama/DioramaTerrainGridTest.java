package net.shiroha233.roadweaver.client.fabric.diorama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DioramaTerrainGridTest {
    @Test void coversWideAndTallViewsAtNegativeAndPositiveCoordinates() {
        for (int center : new int[]{-29_999_000, -2049, -1, 0, 2047, 29_999_000}) {
            for (double span : new double[]{5, 127, 128, 129, 1500, 8192, 24000}) {
                checkView(center, -center, span, span / 2);
                checkView(-center, center, span / 2, span);
            }
        }
    }

    @Test void samplesFollowWorldEastAndSouthRegardlessOfMapCamera() {
        var grid = DioramaTerrainGrid.around(-100, 900, -700, 300);
        assertEquals(grid.sampleX(0) + grid.step(), grid.sampleX(1));
        assertEquals(grid.sampleZ(0), grid.sampleZ(1));
        assertEquals(grid.sampleX(0), grid.sampleX(DioramaTerrainGrid.SIDE));
        assertEquals(grid.sampleZ(0) + grid.step(), grid.sampleZ(DioramaTerrainGrid.SIDE));
        assertEquals(grid.x() + grid.step() / 2, grid.sampleX(0));
        assertEquals(grid.maxX() - grid.step() + grid.step() / 2,
                grid.sampleX(DioramaTerrainGrid.SIDE - 1));
    }

    @Test void smallPansReuseGridButLargeJumpsNeverShowTheOldGrid() {
        var grid = DioramaTerrainGrid.around(4, 14, 3, 13);
        assertEquals(grid, DioramaTerrainGrid.around(6, 16, 5, 15));
        assertTrue(grid.overlaps(-10, 10, -10, 10));
        assertFalse(grid.overlaps(grid.maxX(), grid.maxX() + 30, -10, 10));
        assertFalse(grid.overlaps(-10, 10, grid.maxZ() + 90, grid.maxZ() + 130));
    }

    @Test void unsupportedOrMissingDioramaKeepsRoadMapIndependent() {
        assertTrue(DioramaBackgroundCompat.supports("0.11.0+1.21-1.21.1"));
        assertFalse(DioramaBackgroundCompat.supports("0.11.0+1.21.4"));
        assertFalse(DioramaBackgroundCompat.supports("0.12.0+1.21-1.21.1"));
        assertFalse(DioramaBackgroundCompat.supports(null));
    }

    private static void checkView(int x, int z, double spanX, double spanZ) {
        var grid = DioramaTerrainGrid.around(x - spanX / 2, x + spanX / 2, z - spanZ / 2, z + spanZ / 2);
        // 独立验覆盖矩形，尤其是负坐标和二次幂精度刚切换时的视口边缘。
        assertTrue(grid.x() <= x - spanX / 2 && grid.maxX() >= x + spanX / 2);
        assertTrue(grid.z() <= z - spanZ / 2 && grid.maxZ() >= z + spanZ / 2);
    }
}
