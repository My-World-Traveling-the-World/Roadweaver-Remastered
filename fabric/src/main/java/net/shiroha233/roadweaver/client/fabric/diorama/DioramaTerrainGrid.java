/* 文件职责：将道路地图视口对齐到可复用的俯视地表网格。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

/** 用整数世界格定位栅格；平移只换屏幕坐标，不移动已缓存地形的世界位置。 */
record DioramaTerrainGrid(int x, int z, int step) {
    static final int SIDE = 160;

    static DioramaTerrainGrid around(double minX, double maxX, double minZ, double maxZ) {
        double span = Math.max(maxX - minX, maxZ - minZ);
        int step = 1;
        while (step * (SIDE - 32.0) < span) step <<= 1;
        int snap = step * 16;
        int x = Math.floorDiv((int) Math.floor((minX + maxX) / 2), snap) * snap;
        int z = Math.floorDiv((int) Math.floor((minZ + maxZ) / 2), snap) * snap;
        return new DioramaTerrainGrid(x - step * (SIDE / 2), z - step * (SIDE / 2), step);
    }

    int maxX() { return x + step * SIDE; }
    int maxZ() { return z + step * SIDE; }
    int sampleX(int index) { return x + (index % SIDE) * step + step / 2; }
    int sampleZ(int index) { return z + (index / SIDE) * step + step / 2; }

    boolean overlaps(double minX, double maxX, double minZ, double maxZ) {
        return x < maxX && maxX() > minX && z < maxZ && maxZ() > minZ;
    }
}
