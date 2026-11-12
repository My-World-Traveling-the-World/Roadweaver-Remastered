/* 文件职责：结构归属键：起点所在区块 + 结构 id。 */
package net.shiroha233.roadweaver.pathlogic.structures;


public record StructureOwnerKey(long startChunkKey, String structureId) {
}
