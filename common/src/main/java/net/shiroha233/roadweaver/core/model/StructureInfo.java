/* 文件职责：单个结构位置与类型标识。 */
package net.shiroha233.roadweaver.core.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * 单个结构位置与类型标识
 */
public record StructureInfo(BlockPos pos, String structureId) {
    public static final Codec<StructureInfo> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(StructureInfo::pos),
                    Codec.STRING.optionalFieldOf("structure_id", "unknown").forGetter(StructureInfo::structureId)
            ).apply(instance, StructureInfo::new)
    );

    public static boolean isKnownId(String structureId) {
        return structureId != null && !structureId.isBlank() && !"unknown".equalsIgnoreCase(structureId.trim());
    }

    /** 村庄识别基于结构ID中的village标记；该启发式不等价于确认所有未知模组结构均非村庄。 */
    public static boolean isVillageId(String structureId) {
        return structureId != null
                && structureId.toLowerCase(java.util.Locale.ROOT).contains("village");
    }
}
