/* 文件职责：道路跨度区间（起点、终点、类型）。 */
package net.shiroha233.roadweaver.core.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * 道路跨度区间（起点、终点、类型）
 */
public record RoadSpan(BlockPos start, BlockPos end, SpanType type) {
    /** 跨度与站点须共享发布后不变的坐标语义，不能让构建时复用的游标挪走桥头。 */
    public RoadSpan {
        start = start == null ? null : start.immutable();
        end = end == null ? null : end.immutable();
    }

    public static final Codec<RoadSpan> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("start").forGetter(RoadSpan::start),
                    BlockPos.CODEC.fieldOf("end").forGetter(RoadSpan::end),
                    Codec.STRING.fieldOf("type")
                            .xmap(SpanType::valueOf, Enum::name)
                            .forGetter(RoadSpan::type)
            ).apply(instance, RoadSpan::new)
    );
}
