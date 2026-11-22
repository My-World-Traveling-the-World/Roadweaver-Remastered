/* 文件职责：保卫开局补边的分量约束、长度预算与手绘保护。 */
package net.shiroha233.roadweaver.planning;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InitialConnectivityCandidateTest {
    @Test void excludedFirstCandidateLeavesOneEligibleReplacement() {
        var anchor = BlockPos.ZERO;
        var loose = anchor.east(300);
        var failed = new StructureConnection(anchor, loose, ConnectionStatus.FAILED);
        var candidates = List.of(anchor.east(200), anchor.east(160));
        var edges = candidates.stream().map(p -> new StructureConnection(anchor, p, ConnectionStatus.COMPLETED)).toList();
        // 两条路本来都能接通；排掉更近的一条，仍应接上另一条。
        for (var order : List.of(candidates, candidates.reversed())) {
            assertEquals(List.of(candidates.getLast()), ConnectionFallbackService.rankCandidates(failed, order,
                    Set.of(), edges, List.of(), true, 200, c -> !c.to().equals(candidates.getFirst()))
                    .stream().map(StructureConnection::to).toList());
        }
    }

    @Test
    void onlyTheOppositeConfirmedComponentCanReceiveABoundedStartupRepair() {
        BlockPos anchor = BlockPos.ZERO, loose = anchor.east(300), useful = new BlockPos(160, 0, 32);
        BlockPos gap = anchor.east(240), pending = anchor.east(260), unconnected = anchor.east(280);
        var failed = new StructureConnection(anchor, loose, ConnectionStatus.FAILED);
        var existing = List.of(new StructureConnection(anchor, useful, ConnectionStatus.COMPLETED),
                new StructureConnection(anchor, gap, ConnectionStatus.PROTECTED_GAP),
                new StructureConnection(anchor, pending, ConnectionStatus.PLANNED));
        var positions = List.of(useful, gap, pending, unconnected);
        // 缺口和未连点更近；能返回更远的对侧分量成员才证明用了已验收拓扑。
        var choice = ConnectionFallbackService.rankCandidates(failed, positions, Set.of(),
                existing, List.of(), true, 160).getFirst();
        assertEquals(loose, choice.from());
        assertEquals(useful, choice.to());
        assertTrue(ConnectionFallbackService.rankCandidates(failed, positions, Set.of(),
                existing, List.of(), true, 128).isEmpty());
        assertTrue(ConnectionFallbackService.rankCandidates(failed, positions,
                Set.of(PlanningUtils.pos2dKey(loose)), existing, List.of(), true, 160).isEmpty());
        var alreadyConnected = new StructureConnection(useful, loose, ConnectionStatus.COMPLETED);
        assertTrue(ConnectionFallbackService.rankCandidates(failed, positions, Set.of(),
                existing, List.of(alreadyConnected), true, 160).isEmpty());
    }
}
