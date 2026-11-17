/* 文件职责：寻路与地形诊断共用的粗粒度地形标签。 */
package net.shiroha233.roadweaver.planning.terrain;

/** 粗地形枚举与诊断共用名称，持久化时名称兼容性不可随意破坏。 */
public enum RoadTerrainType {
    MOUNTAIN,
    PLAIN,
    COAST,
    RIVER,
    LAKE,
    OCEAN
}
