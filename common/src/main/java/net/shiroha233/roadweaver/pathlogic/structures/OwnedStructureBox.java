/* 文件职责：带归属信息的结构包围盒，逐块精确碰撞判定转发给 PieceOccupancy。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.Map;

public record OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, PieceOccupancy occupancy) {
    OwnedStructureBox(StructureOwnerKey owner, BoundingBox box) {
        this(owner, box, (PieceOccupancy) null);
    }

    OwnedStructureBox(StructureOwnerKey owner, BoundingBox box, Map<BlockPos, BlockState> testBlocks) {
        this(owner, box, PieceOccupancy.forTest(testBlocks));
    }

    BoundingBox collisionBox() {
        if (occupancy == null) return box;
        BoundingBox resolved = occupancy.collisionBounds();
        return resolved == null ? box : resolved;
    }

    boolean intersects(int x, int z, int minY, int maxY, int horizontalRadius) {
        if (box == null || x < box.minX() - horizontalRadius || x > box.maxX() + horizontalRadius
                || z < box.minZ() - horizontalRadius || z > box.maxZ() + horizontalRadius
                || maxY < box.minY() || minY > box.maxY()) return false;
        // A fallback box is metadata only. Planning deliberately ignores
        // it, so final validation must not turn it into a hard collision.
        if (occupancy == null) return false;
        Boolean exact = occupancy.intersects(x, z, minY, maxY, horizontalRadius);
        return Boolean.TRUE.equals(exact);
    }

    boolean intersectsSolid(int x, int z, int minY, int maxY) {
        if (box == null || x < box.minX() || x > box.maxX()
                || z < box.minZ() || z > box.maxZ()
                || maxY < box.minY() || minY > box.maxY()) return false;
        // Unknown occupancy is not proof of a real building block.
        if (occupancy == null) return false;
        Boolean exact = occupancy.intersectsSolid(x, z, minY, maxY);
        return Boolean.TRUE.equals(exact);
    }

    FallingCapOccupancy fallingCapOccupancyAt(int x, int y, int z) {
        if (box == null || x < box.minX() || x > box.maxX()
                || y < box.minY() || y > box.maxY()
                || z < box.minZ() || z > box.maxZ()) return FallingCapOccupancy.AIR;
        return occupancy == null
                ? FallingCapOccupancy.UNKNOWN
                : occupancy.fallingCapOccupancyAt(x, y, z);
    }

    String occupancyKind() {
        return occupancy == null ? "fallback-box" : occupancy.kind();
    }
}
