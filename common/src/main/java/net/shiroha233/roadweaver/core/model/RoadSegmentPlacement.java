/* 文件职责：道路分段放置数据（中心点 + 方块位置列表）。 */
package net.shiroha233.roadweaver.core.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 道路分段放置数据（中心点 + 方块位置列表）
 */
public record RoadSegmentPlacement(BlockPos middlePos, List<BlockPos> positions) {
    /** 冻结调用方的工作列表和游标，避免发布后改坐标使校验与施工看到不同产物；非法空值仍交给校验器报告。 */
    public RoadSegmentPlacement {
        middlePos = middlePos == null ? null : middlePos.immutable();
        if (positions != null) {
            var frozen = new java.util.ArrayList<BlockPos>(positions.size());
            for (BlockPos position : positions) frozen.add(position == null ? null : position.immutable());
            positions = java.util.Collections.unmodifiableList(frozen);
        }
    }

    public static final Codec<RoadSegmentPlacement> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("middle_pos").forGetter(RoadSegmentPlacement::middlePos),
                    BlockPos.CODEC.listOf().fieldOf("positions").forGetter(RoadSegmentPlacement::positions)
            ).apply(instance, RoadSegmentPlacement::new)
    );
}
