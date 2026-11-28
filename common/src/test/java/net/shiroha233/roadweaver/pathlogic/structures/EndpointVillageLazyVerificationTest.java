package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointVillageLazyVerificationTest {
    @Test
    void farVillageBuildingDoesNotDecodeItsTemplate() throws ReflectiveOperationException {
        var constructor = PieceOccupancy.class.getDeclaredConstructor(Supplier.class, BlockPos.class,
                Mirror.class, Rotation.class, BlockPos.class);
        constructor.setAccessible(true);
        var decoded = new AtomicInteger();
        Supplier<StructureTemplate> probe = () -> { decoded.incrementAndGet(); return null; };
        var near = new BlockPos(5, 70, 5);
        var far = new BlockPos(5, 70, 200);
        var start = new StructureOwnerKey(0L, "minecraft:village_plains");
        var related = new StructureOwnerKey(1L, "minecraft:village_plains");
        var connection = new StructureConnection(BlockPos.ZERO, new BlockPos(40, 0, 0));
        // 用同家族的近点作非空正控，远点只能在触发解码前排除。
        var boxes = List.of(
                new OwnedStructureBox(related, BoundingBox.fromCorners(near, near),
                        constructor.newInstance(probe, near, Mirror.NONE, Rotation.NONE, BlockPos.ZERO)),
                new OwnedStructureBox(related, BoundingBox.fromCorners(far, far),
                        constructor.newInstance(probe, far, Mirror.NONE, Rotation.NONE, BlockPos.ZERO)));
        assertEquals(Set.of(start), PlanningSnapshot.expandEndpointVillageOwners(connection, boxes, Set.of(start)));
        assertEquals(1, decoded.get(), "只允许近处同村庄建筑进入惰性解码");
    }
}
