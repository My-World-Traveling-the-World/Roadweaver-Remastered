/* 文件职责：玩家手绘路线的一个**控制点**。 */
package net.shiroha233.roadweaver.route;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * 玩家手绘路线的一个**控制点**。
 *
 * <h2>它和「平滑后的路径点」的区别</h2>
 * <pre>
 *   控制点（本类）     玩家亲手放的，数量少（几个到几十个），可拖动/改高/增删
 *   路径点（平滑产物） 由控制点插值算出来的密集点，每格一个，不直接编辑
 * </pre>
 *
 * <p>{@link #kind} 描述的是「**从这个点走到下一个点**这一段是什么」，
 * 而不是「这个点本身是什么」。所以最后一个控制点的 kind 无意义
 * （它后面没有段了），约定沿用前一个点的 kind。</p>
 */
public record RoutePoint(
        BlockPos pos,
        RouteKind kind
) {
    public static final Codec<RoutePoint> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(RoutePoint::pos),
                    Codec.STRING.optionalFieldOf("kind", RouteKind.ROAD.serializedName())
                            .xmap(RouteKind::fromSerializedName, RouteKind::serializedName)
                            .forGetter(RoutePoint::kind)
            ).apply(instance, RoutePoint::new)
    );

    public RoutePoint(BlockPos pos) {
        this(pos, RouteKind.ROAD);
    }

    public RoutePoint withPos(BlockPos newPos) {
        return new RoutePoint(newPos, kind);
    }

    public RoutePoint withKind(RouteKind newKind) {
        return new RoutePoint(pos, newKind);
    }

    /** 仅移动水平位置，保持 Y（拖动挪点用）。 */
    public RoutePoint withXZ(int x, int z) {
        return new RoutePoint(new BlockPos(x, pos.getY(), z), kind);
    }

    /** 仅改高度（滚轮 / 输入框调高用）。 */
    public RoutePoint withY(int y) {
        return new RoutePoint(new BlockPos(pos.getX(), y, pos.getZ()), kind);
    }
}
