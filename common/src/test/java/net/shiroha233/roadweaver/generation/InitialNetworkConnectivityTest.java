/* 文件职责：用含失败、保护缺口和成功补边的同一端点图验证终态口径。 */
package net.shiroha233.roadweaver.generation;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.planning.PlanningUtils;
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

    @Test void interruptedOrUnknownFailureCannotBlacklistACandidate() {
        var edge = new StructureConnection(BlockPos.ZERO, BlockPos.ZERO.east());
        var key = PlannedPathKey.of(edge);
        var failed = RoadGenerationService.InitialTaskResult.FAILED;
        // 取消和工作线程异常都没有拒绝见证；不能因此封禁该端点。
        assertFalse(InitialConnectivityRepair.provenRejection(edge, new InitialGenManager.GenResult(key, edge, failed, null)));
        var witness = new net.shiroha233.roadweaver.core.model.ProfileConflict(
                net.shiroha233.roadweaver.core.model.ProfileConflict.Reason.COVER, 4, 3, 70, 62);
        assertTrue(InitialConnectivityRepair.provenRejection(edge, new InitialGenManager.GenResult(key, edge, failed, null, witness)));
    }

    @Test
    void repairWillOnlySubmitTheCandidateItActuallyGeneratedWithoutAGap() {
        var edge = new StructureConnection(new BlockPos(-4, 0, 0), new BlockPos(5, 0, 0));
        var key = PlannedPathKey.of(edge);
        var result = RoadGenerationService.InitialTaskResult.COMPLETED;
        long from = PlanningUtils.pos2dKey(edge.from()), to = PlanningUtils.pos2dKey(edge.to());
        var stations = IntStream.rangeClosed(-4, 5).mapToObj(x -> {
            var p = new BlockPos(x, 64, 0);
            return new RoadSegmentPlacement(p, List.of(p.north(2), p.north(), p, p.south(), p.south(2)));
        }).toList();
        var target = Collections.nCopies(stations.size(), 64);
        var full = new RoadData(5, 1, List.of(), List.of(), stations, List.of(), target, from, to);
        var gap = new RoadData(5, 1, List.of(), List.of(), stations,
                List.of(new RoadSpan(stations.get(4).middlePos(), stations.get(5).middlePos(), SpanType.PROTECTED_GAP)),
                target, from, to);
        // 两份几何都合法，区别只在于保护缺口是否切断了这条候补。
        assertTrue(RoadDataValidator.validateFinalRoad(full).valid());
        assertTrue(RoadDataValidator.validateFinalRoad(gap).valid());
        assertTrue(InitialConnectivityRepair.usable(edge, new InitialGenManager.GenResult(key, edge, result, full)));
        assertFalse(InitialConnectivityRepair.usable(edge, new InitialGenManager.GenResult(key, edge, result, gap)));
        assertFalse(InitialConnectivityRepair.usable(new StructureConnection(edge.from(), edge.to().east()),
                new InitialGenManager.GenResult(key, edge, result, full)));
    }
}
