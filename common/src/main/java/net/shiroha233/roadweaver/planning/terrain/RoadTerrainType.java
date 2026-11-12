/* 文件职责：寻路与地形诊断共用的粗粒度地形标签。 */
package net.shiroha233.roadweaver.planning.terrain;

/** Coarse terrain label shared by pathfinding and terrain diagnostics. */
public enum RoadTerrainType {
    MOUNTAIN,
    PLAIN,
    COAST,
    RIVER,
    LAKE,
    OCEAN
}
