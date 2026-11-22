/* 文件职责：用含失败、保护缺口和成功补边的同一端点图验证终态口径。 */
package net.shiroha233.roadweaver.generation;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InitialNetworkConnectivityTest {
    @Test
    void completedTopologyDoesNotCrossGapOrFailedEdges() {
        BlockPos spawn = new BlockPos(-32, 80, -208);
        BlockPos a = spawn.east(16), b = a.east(16), c = b.east(16), d = c.east(16);
        var initial = List.of(new StructureConnection(spawn.atY(0), a, ConnectionStatus.COMPLETED),
                new StructureConnection(a, b, ConnectionStatus.PROTECTED_GAP),
                new StructureConnection(b, c, ConnectionStatus.COMPLETED),
                new StructureConnection(c, d, ConnectionStatus.FAILED),
                new StructureConnection(d, d.east(16), ConnectionStatus.FILTERED));
        // 同一出生点 Y=0 或实际 Y 都要算同一端点；只允许无缺口的边连接分量。
        assertEquals(new InitialNetworkConnectivity.Summary(5, 3, 2, 2),
                InitialNetworkConnectivity.summarize(initial, spawn));
        var topology = InitialNetworkConnectivity.from(initial);
        assertFalse(topology.connected(spawn, c));
        topology.join(new StructureConnection(a, c, ConnectionStatus.COMPLETED));
        assertTrue(topology.connected(spawn, b));
        assertFalse(topology.connected(spawn, d));
        assertEquals(0, InitialNetworkConnectivity.summarize(initial, d.east(16)).spawnComponent());
    }

    @Test
    void finalPublishedFootprintDecidesStatusAfterMergeAndValidation() {
        var full = PlannedPathKey.of(BlockPos.ZERO, new BlockPos(10, 0, 0));
        var clipped = PlannedPathKey.of(BlockPos.ZERO, new BlockPos(20, 0, 0));
        var rejected = PlannedPathKey.of(BlockPos.ZERO, new BlockPos(30, 0, 0));
        var persisted = new InitialGenManager.PersistedBatch(Set.of(full, clipped), Set.of(clipped), Set.of());
        // 同样从生成回报成功，最终被剪和被拒绝应分别显示缺口和失败。
        assertEquals(ConnectionStatus.PROTECTED_GAP, InitialPlanLifecycleOps.terminalStatus(true, clipped, persisted));
        assertEquals(ConnectionStatus.FAILED, InitialPlanLifecycleOps.terminalStatus(true, rejected, persisted));
        assertEquals(ConnectionStatus.COMPLETED, InitialPlanLifecycleOps.terminalStatus(true, full, persisted));
        assertEquals(ConnectionStatus.FAILED, InitialPlanLifecycleOps.terminalStatus(false, full, persisted));
    }
}
