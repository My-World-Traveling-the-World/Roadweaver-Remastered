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
        var connection = new StructureConnection(new BlockPos(-50, 64, 20), new BlockPos(50, 64, 20));
        if (buildingBlock == null) return PlanningSnapshot.forRoute(connection, List.of());
        // 走真实体素占用判定；仅有元数据包围盒的结构不会挡路。
        var occupied = new OwnedStructureBox(new StructureOwnerKey(0L, "test:remote"),
                BoundingBox.fromCorners(buildingBlock, buildingBlock),
                Map.of(buildingBlock, Blocks.OAK_PLANKS.defaultBlockState()));
        return PlanningSnapshot.forRoute(connection, List.of(occupied));
    }
}
