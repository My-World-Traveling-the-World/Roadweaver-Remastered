package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;

import java.util.List;
import java.util.Set;

/** Regression coverage for bounded, direction-aware fallback selection. */
public final class ConnectionFallbackPolicyTest {
    private ConnectionFallbackPolicyTest() {
    }

    public static void main(String[] args) {
        testOnlyOneCandidateIsReturned();
        testSameSideCandidateWinsAndOppositeSideIsRejected();
        testGeneratingEndpointIsReserved();
        System.out.println("ConnectionFallbackPolicyTest: PASS");
    }

    private static void testOnlyOneCandidateIsReturned() {
        StructureConnection failed = connection(0, 0, 100, 0, ConnectionStatus.FAILED);
        List<StructureConnection> ranked = ConnectionFallbackService.rankCandidates(
                failed,
                List.of(pos(80, 0), pos(80, 20), pos(80, -20)),
                Set.of(PlanningUtils.pos2dKey(pos(-100, 0))),
                List.of(support(0, 0, -100, 0)),
                List.of());
        require(ranked.size() <= 1, "fallback returned more than one candidate");
    }

    private static void testSameSideCandidateWinsAndOppositeSideIsRejected() {
        StructureConnection failed = connection(0, 0, 100, 0, ConnectionStatus.FAILED);
        List<StructureConnection> ranked = ConnectionFallbackService.rankCandidates(
                failed,
                // Candidate at (80,20) remains on the failed target side;
                // candidate at (200,100) points away and must be rejected.
                List.of(pos(80, 0), pos(200, 100)),
                Set.of(PlanningUtils.pos2dKey(pos(-100, 0))),
                List.of(support(0, 0, -100, 0)),
                List.of());
        require(ranked.size() == 1, "same-side candidate was not selected");
        BlockPos selected = ranked.getFirst().to();
        require(selected.getX() == 80 && selected.getZ() == 0,
                "opposite-side candidate outranked same-side continuation");
    }

    private static void testGeneratingEndpointIsReserved() {
        StructureConnection failed = connection(0, 0, 100, 0, ConnectionStatus.FAILED);
        StructureConnection inFlight = connection(80, 0, 70, 0, ConnectionStatus.GENERATING);
        List<StructureConnection> ranked = ConnectionFallbackService.rankCandidates(
                failed,
                List.of(pos(80, 0), pos(60, 0)),
                Set.of(PlanningUtils.pos2dKey(pos(-100, 0))),
                List.of(support(0, 0, -100, 0), inFlight),
                List.of());
        require(ranked.size() == 1, "reserved endpoint left no deterministic fallback");
        BlockPos selected = ranked.getFirst().to();
        require(selected.getX() == 60 && selected.getZ() == 0,
                "fallback targeted an endpoint already being generated");
    }

    private static StructureConnection support(int fromX, int fromZ, int toX, int toZ) {
        return connection(fromX, fromZ, toX, toZ, ConnectionStatus.COMPLETED);
    }

    private static StructureConnection connection(int fromX, int fromZ, int toX, int toZ,
                                                   ConnectionStatus status) {
        return new StructureConnection(pos(fromX, fromZ), pos(toX, toZ), status);
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
