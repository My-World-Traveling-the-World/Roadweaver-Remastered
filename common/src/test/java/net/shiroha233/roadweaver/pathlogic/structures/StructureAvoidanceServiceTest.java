package net.shiroha233.roadweaver.pathlogic.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small deterministic simulation for endpoint ownership and protected gaps. */
public final class StructureAvoidanceServiceTest {
    private StructureAvoidanceServiceTest() {
    }

    public static void main(String[] args) {
        testEndpointOwnerInference();
        testEndpointOwnerDistanceAndAmbiguity();
        testEndpointExitRemapAcrossWorldgenIds();
        testProtectedGapPolicy();
        testEndpointOnlyGapDoesNotRequireCandidate();
        System.out.println("StructureAvoidanceServiceTest: PASS");
    }

    private static void testEndpointOwnerInference() {
        BlockPos endpoint = new BlockPos(0, 64, 0);
        StructureOwnerKey owner =
                new StructureOwnerKey(11L, "test:village_terminal");
        OwnedStructureBox box = box(owner, 2, -1, 4, 1);
        StructureConnection connection = new StructureConnection(endpoint, new BlockPos(100, 64, 0));

        Set<StructureOwnerKey> inferred =
                StructureAvoidanceService.resolveEndpointOwnersForTest(connection, List.of(), List.of(box));
        require(inferred.contains(owner), "nearby verified endpoint building was not inferred");

        Set<StructureOwnerKey> exact =
                StructureAvoidanceService.resolveEndpointOwnersForTest(
                        connection, List.of(new StructureInfo(endpoint, "test:terminal")), List.of(box));
        require(exact.contains(owner), "known endpoint owner was not retained");
    }

    private static void testEndpointOwnerDistanceAndAmbiguity() {
        BlockPos endpoint = new BlockPos(0, 64, 0);
        StructureConnection connection = new StructureConnection(endpoint, new BlockPos(100, 64, 0));
        StructureOwnerKey farOwner =
                new StructureOwnerKey(12L, "test:far");
        Set<StructureOwnerKey> far =
                StructureAvoidanceService.resolveEndpointOwnersForTest(
                        connection, List.of(), List.of(box(farOwner, 20, 0, 24, 1)));
        require(far.isEmpty(), "far building was incorrectly inferred as endpoint");

        StructureOwnerKey left =
                new StructureOwnerKey(13L, "test:left");
        StructureOwnerKey right =
                new StructureOwnerKey(14L, "test:right");
        Set<StructureOwnerKey> ambiguous =
                StructureAvoidanceService.resolveEndpointOwnersForTest(
                        connection, List.of(), List.of(box(left, -4, 0, -4, 0), box(right, 4, 0, 4, 0)));
        require(ambiguous.isEmpty(), "equidistant endpoint buildings were incorrectly selected");
    }

    private static void testEndpointExitRemapAcrossWorldgenIds() {
        BlockPos endpoint = new BlockPos(256, 300, -2160);
        StructureConnection connection = new StructureConnection(endpoint, new BlockPos(1104, 180, -2400));
        StructureOwnerKey actualVillage =
                new StructureOwnerKey(15L, "ctov:large/village_mountain");
        Set<StructureOwnerKey> remapped =
                StructureAvoidanceService.resolveEndpointOwnersForTest(
                        connection,
                        List.of(new StructureInfo(endpoint, "minecraft:village_snowy")),
                        List.of(box(actualVillage, 252, -2164, 263, -2155)));
        require(remapped.contains(actualVillage),
                "unique nearby generated village was not accepted as the selected endpoint exit");

        StructureOwnerKey competing =
                new StructureOwnerKey(16L, "ctov:small/village_snowy_igloo");
        Set<StructureOwnerKey> ambiguous =
                StructureAvoidanceService.resolveEndpointOwnersForTest(
                        connection,
                        List.of(new StructureInfo(endpoint, "minecraft:village_snowy")),
                        List.of(box(actualVillage, 252, -2164, 255, -2157),
                                box(competing, 257, -2164, 260, -2157)));
        require(!ambiguous.contains(actualVillage) && !ambiguous.contains(competing),
                "ambiguous nearby generated buildings were incorrectly accepted as an endpoint exit");
    }

    private static void testProtectedGapPolicy() {
        List<BlockPos> path = path(20);
        require(StructureAvoidanceService.protectedGapPatternAcceptableForTest(
                        path, mask(20, 5, 15)), "internal long building gap should be accepted");
        require(StructureAvoidanceService.protectedGapPatternAcceptableForTest(
                        path, mask(20, 3, 4, 12, 13)), "separate internal building gaps should be accepted");
        require(StructureAvoidanceService.protectedGapPatternAcceptableForTest(
                        path, mask(20, 0, 3)), "route-start building gap should retain the remaining route");
        require(StructureAvoidanceService.protectedGapPatternAcceptableForTest(
                        path, mask(20, 16, 19)), "route-end building gap should retain the remaining route");
        require(!StructureAvoidanceService.protectedGapPatternAcceptableForTest(
                        path, new boolean[20]), "route without a building collision should not be a gap");
    }

    private static void testEndpointOnlyGapDoesNotRequireCandidate() {
        List<BlockPos> placements = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BlockPos pos = new BlockPos(i * 8, 64, 0);
            placements.add(pos);
        }
        StructureConnection connection = new StructureConnection(
                placements.get(0), placements.get(11));
        require(StructureAvoidanceService.endpointOnlyProtectedGapPatternForTest(
                        List.of(placements.get(0), placements.get(11)),
                        List.of(new RoadSpan(placements.get(11), placements.get(11),
                                SpanType.PROTECTED_GAP))),
                "endpoint-only protected gap was incorrectly sent to topology fallback");

        require(!StructureAvoidanceService.endpointOnlyProtectedGapPatternForTest(
                        List.of(placements.get(0), placements.get(11)),
                        List.of(new RoadSpan(placements.get(6), placements.get(6),
                                SpanType.PROTECTED_GAP))),
                "internal protected gap bypassed topology fallback");
    }

    private static OwnedStructureBox box(
            StructureOwnerKey owner, int minX, int minZ, int maxX, int maxZ) {
        return new OwnedStructureBox(
                owner, new BoundingBox(minX, 60, minZ, maxX, 70, maxZ), Map.of());
    }

    private static List<BlockPos> path(int size) {
        List<BlockPos> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) points.add(new BlockPos(i, 64, 0));
        return points;
    }

    private static boolean[] mask(int size, int... indexes) {
        boolean[] result = new boolean[size];
        Arrays.stream(indexes).forEach(index -> result[index] = true);
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
