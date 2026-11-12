/* 文件职责：已准备的结构起点键：维度 + 种子 + 区块 + 来源 + 结构集。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;

record PreparedStartKey(String dimension,
                                long seed,
                                long chunkKey,
                                int source,
                                Holder<StructureSet> structureSet) {
}
