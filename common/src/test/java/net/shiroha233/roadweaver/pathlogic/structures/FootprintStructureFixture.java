package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.core.model.StructureConnection;

public final class FootprintStructureFixture {
    private FootprintStructureFixture() {
    }

    public static PlanningSnapshot snapshot(BlockPos buildingBlock) {
        return buildings(buildingBlock == null ? List.of() : List.of(buildingBlock));
    }

    public static PlanningSnapshot buildings(List<BlockPos> buildingBlocks) {
        var connection = new StructureConnection(new BlockPos(-50, 64, 20), new BlockPos(50, 64, 20));
        // 每个碰撞点都经过实际体积查询，不能以假碰撞回调替代。
        return PlanningSnapshot.forRoute(connection, buildingBlocks.stream().map(buildingBlock ->
                new OwnedStructureBox(new StructureOwnerKey(0L, "test:remote"),
                BoundingBox.fromCorners(buildingBlock, buildingBlock),
                Map.of(buildingBlock, Blocks.OAK_PLANKS.defaultBlockState()))).toList());
    }
}
