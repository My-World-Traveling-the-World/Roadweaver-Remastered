package net.shiroha233.roadweaver.pathlogic.validation;

import net.shiroha233.roadweaver.postprocess.JunctionConnector;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.RoutePrecisionRetryPolicy;
import net.shiroha233.roadweaver.pathfinding.PathSpanExtractor;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;

/** Broad model-level coverage for the persisted road contract. */
public final class RoadWholeFlowSimulationTest {
    private RoadWholeFlowSimulationTest() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        testOrdinaryRoadAndOwners();
        testCrossJunctionNormalization();
        testCrossJunctionHeightUnification();
        testNetworkReuseMetadata();
        testHeightRepair();
        testPhysicalSpanGuards();
        testWaterModesAndGapFallback();
        testWaterGapCanAdvanceAcrossMultipleConflicts();
        testRiverWidthSelection();
        testPrecisionPolicy();
        testProactiveTunnelProfile();
        testFailedRouteEndpointFootprints();
        System.out.println("RoadWholeFlowSimulationTest: PASS");
    }

    /** Replays the 24 routes rejected by the 2026-08-30 consistency gate. */
    private static void testFailedRouteEndpointFootprints() {
        int[][] routes = {
                {0, 0, 288, -256}, {112, 800, 160, 464}, {0, 0, -464, -368},
                {-464, -368, -592, -288}, {-864, 752, -813, 716}, {0, 0, 97, 281},
                {160, 464, 97, 281}, {1280, -352, 880, 208}, {-1200, -800, -592, -288},
                {112, 800, 448, 1392}, {2880, -2064, 1888, -1344}, {-1456, -1248, -1200, -800},
                {1072, 1120, 2224, 1088}, {1120, -2032, 1712, -2864},
                {1280, -352, 992, -1024}, {-480, 480, 97, 281}, {-1104, 304, -813, 716},
                {288, -256, 880, 208}, {992, -1024, 1425, -1179},
                {1888, -1344, 1425, -1179}, {1072, 1120, 448, 1392},
                {-480, 480, -813, 716}, {1120, -2032, 1425, -1179},
                {448, 1392, 48, 2848}
        };
        for (int[] route : routes) {
            List<BlockPos> centers = interpolatedRoute(route[0], route[1], route[2], route[3]);
            RoadData road = road(centers, List.of());
            require(RoadPlanConsistencyService.hasUsableSegmentFootprints(road),
                    "narrow endpoint footprint rejected for route " + java.util.Arrays.toString(route));
            require(RoadDataValidator.validateFinalRoad(road).valid(),
                    "replayed route failed final data validation " + java.util.Arrays.toString(route));
        }
    }

    private static List<BlockPos> interpolatedRoute(int x0, int z0, int x1, int z1) {
        int count = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0)));
        List<BlockPos> result = new ArrayList<>(count + 1);
        BlockPos previous = null;
        for (int i = 0; i <= count; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * (i / (double) count));
            int z = (int) Math.round(z0 + (z1 - z0) * (i / (double) count));
            BlockPos point = new BlockPos(x, 64, z);
            if (previous == null || !previous.equals(point)) {
                result.add(point);
                previous = point;
            }
        }
        return result;
    }

    private static void testOrdinaryRoadAndOwners() {
        RoadData road = road(line(6), List.of(), 11L, 22L);
        require(RoadDataValidator.validateFinalRoad(road).valid(), "ordinary road rejected");
        require(road.hasOwnerPair() && road.containsOwner(11L), "road owner pair lost");
        require(road.sharedOwnerWith(road( line(6), List.of(), 11L, 33L)) == 11L,
                "shared owner was not detected");
    }

    private static void testCrossJunctionNormalization() {
        List<BlockPos> cross = List.of(
                p(-2, 0), p(-1, 0), p(0, 0), p(1, 0), p(2, 0),
                p(1, 0), p(0, 0), p(0, -1), p(0, -2));
        RoadData normalized = RoadDataValidator.normalizeDuplicateJunctions(road(cross, List.of()));
        require(normalized.spans().stream().anyMatch(span -> span.type() == SpanType.JUNCTION_ANCHOR),
                "crossing center was not normalized to a junction anchor");
        require(RoadDataValidator.validateFinalRoad(normalized).valid(),
                "normalized cross junction was rejected");
    }

    private static void testCrossJunctionHeightUnification() {
        RoadData high = road(horizontalAt(67, -12, 12), List.of(), 101L, 202L);
        RoadData low = road(verticalAt(57, -12, 0), List.of(), 303L, 101L);
        RoadData connected = JunctionConnector.connectSecondaryToPrimary(high, low);
        require(connected != null, "high/low surface cross junction was rejected");
        RoadData unified = JunctionConnector.lowerPrimaryTunnelGradeAtJunction(high, connected);
        require(unified != null, "cross junction height unification returned null");
    }


    private static void testNetworkReuseMetadata() {
        RoadData reused = road(line(6), List.of(new RoadSpan(p(1, 0), p(4, 0), SpanType.NETWORK_REUSE)), 1L, 2L);
        require(RoadDataValidator.validateFinalRoad(reused).valid(), "network reuse span rejected");
        require(!reused.hasProtectedGap(), "network reuse was confused with protected gap");
    }

    private static void testHeightRepair() {
        List<BlockPos> centers = line(18);
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            heights.add((i > 2 && i < 15 && (i & 1) == 1) ? 68 : 64);
        }
        RoadData rough = road(centers, List.of(), heights, 1L, 2L);
        RoadData repaired = RoadDataValidator.repairFinalHeightSteps(rough);
        require(RoadDataValidator.validateFinalRoad(repaired).valid(),
                "height repair did not produce a valid road or explicit gap");
    }

    private static void testPhysicalSpanGuards() {
        // 0.9.001（3e123b2）：校验器改为认同「tunnel wins」——被隧道覆盖的站会把
        // bridge / crossing 一并清掉（RoadDataValidator "if (tunnel[i])" 分支），
        // 与 PathSpanExtractor.java:806 的既有语义一致：提取器早就会把重叠的物理
        // span 按隧道范围切开（原文 "RoadWeaver resolved physical span overlap:
        // tunnel wins, {} range={}..{}"，并把桥的余段裁短、丢掉单站桥余段）。
        // 因此「桥被隧道盖住」现在是**合法**组合：站 2/3 上只剩 tunnel 一个物理
        // span，physicalCount = 1。旧断言编码的是 tunnel-wins 之前的口径。
        RoadData tunnelOverBridge = road(line(6), List.of(
                new RoadSpan(p(1, 0), p(4, 0), SpanType.BRIDGE),
                new RoadSpan(p(2, 0), p(3, 0), SpanType.TUNNEL)));
        require(RoadDataValidator.validateFinalRoad(tunnelOverBridge).valid(),
                "bridge covered by a tunnel must be accepted (tunnel wins)");

        // 安全网替换，且这里有一条硬事实：tunnel-wins 之后 physicalCount 恒 <= 1
        // （tunnel 吃掉 bridge/crossing，bridge 再吃掉 crossing），所以
        // "overlapping-physical-spans" 已是**不可达分支**，无法再造出触发它的用例。
        // 仍可达的重叠冲突是「隧道交汇点压在桥面上」：TUNNEL_JUNCTION span 只置
        // tunnelJunction[]、不置 tunnel[]，于是它与 BRIDGE 重叠时 bridge[i] 仍为 true，
        // 由 junction-crossing-overlap 照旧拒绝。这条同时也覆盖了失败 detail 里
        // 新增的 spans=[...] 站点清单（0b756e7）。
        RoadData junctionOverBridge = road(line(6), List.of(
                new RoadSpan(p(1, 0), p(4, 0), SpanType.BRIDGE),
                new RoadSpan(p(2, 0), p(3, 0), SpanType.TUNNEL_JUNCTION)));
        RoadDataValidator.ValidationResult junctionResult =
                RoadDataValidator.validateFinalRoad(junctionOverBridge);
        require(!junctionResult.valid()
                        && "junction-crossing-overlap".equals(junctionResult.reason()),
                "tunnel junction over a bridge was not rejected");
        require(junctionResult.detail() != null
                        && junctionResult.detail().contains("spans=["),
                "overlap rejection detail does not dump the offending span ranges");

        RoadData singleBridge = road(line(6), List.of(
                new RoadSpan(p(2, 0), p(2, 0), SpanType.BRIDGE)));
        require(!RoadDataValidator.validateFinalRoad(singleBridge).valid(),
                "single-station physical span was incorrectly accepted");
    }

    private static void testWaterModesAndGapFallback() {
        RoadData crossing = road(line(6), List.of(
                new RoadSpan(p(1, 0), p(4, 0), SpanType.ROAD_CROSSING)));
        // 短跨类型不能单独放行已入水路面，夹具要有真正的高架甲板。
        crossing = new RoadData(crossing.width(), crossing.roadType(), crossing.materials(), crossing.slabMaterials(),
                crossing.roadSegmentList(), crossing.spans(), List.of(66, 67, 67, 67, 67, 66), 1L, 2L);
        require(FinalRoadWaterValidator.validate(crossing, 2,
                        RoadWholeFlowSimulationTest::waterAtX2).safe(),
                "short crossing water validation failed");

        RoadData bridge = road(line(6), List.of(
                new RoadSpan(p(1, 0), p(4, 0), SpanType.BRIDGE)));
        require(FinalRoadWaterValidator.validate(bridge, 2,
                        RoadWholeFlowSimulationTest::waterAtX2).safe(),
                "long bridge water validation failed");

        RoadData ordinary = road(line(6), List.of());
        FinalRoadWaterValidator.ValidationResult failed = FinalRoadWaterValidator.validate(
                ordinary, 2, RoadWholeFlowSimulationTest::waterAtX2);
        require(!failed.safe() && failed.collision() != null,
                "ordinary river collision was not detected");
        RoadData gapped = FinalRoadWaterValidator.retainProtectedGapAt(
                ordinary, failed.collision().segmentIndex());
        require(gapped != null && FinalRoadWaterValidator.validate(gapped, 2,
                        RoadWholeFlowSimulationTest::waterAtX2).safe(),
                "river protection gap did not preserve remaining road");
    }

    private static void testWaterGapCanAdvanceAcrossMultipleConflicts() {
        RoadData ordinary = road(line(7), List.of());
        FinalRoadWaterValidator.ValidationResult first = FinalRoadWaterValidator.validate(
                ordinary, 2, RoadWholeFlowSimulationTest::waterAtX2AndX5);
        require(first.collision() != null, "first water conflict missing");
        RoadData firstGap = FinalRoadWaterValidator.retainProtectedGapAt(
                ordinary, first.collision().segmentIndex());
        FinalRoadWaterValidator.ValidationResult second = FinalRoadWaterValidator.validate(
                firstGap, 2, RoadWholeFlowSimulationTest::waterAtX2AndX5);
        require(second.collision() != null, "second water conflict was hidden by first gap");
        RoadData allGaps = FinalRoadWaterValidator.retainProtectedGapAt(
                firstGap, second.collision().segmentIndex());
        require(FinalRoadWaterValidator.validate(allGaps, 2,
                        RoadWholeFlowSimulationTest::waterAtX2AndX5).safe(),
                "multiple independent water gaps did not converge to a safe route");
    }

    private static void testRiverWidthSelection() throws Exception {
        Method method = PathSpanExtractor.class.getDeclaredMethod("crossingTypeForWidth", int.class);
        method.setAccessible(true);
        require(method.invoke(null, 12) == SpanType.ROAD_CROSSING,
                "12-block river was not classified as direct crossing");
        require(method.invoke(null, 13) == SpanType.BRIDGE,
                "13-block river was not classified as bridge");
    }

    private static void testPrecisionPolicy() {
        require(RoutePrecisionRetryPolicy.fullRouteSteps(8).equals(List.of(8)),
                "configured precision 8 changed unexpectedly");
        require(RoutePrecisionRetryPolicy.fullRouteSteps(1).equals(List.of(1)),
                "precision lower bound was not enforced");
        require(RoutePrecisionRetryPolicy.candidateBatchSteps(4).equals(List.of(4)),
                "candidate route did not retain player precision");
    }

    private static void testProactiveTunnelProfile() {
        int[] terrain = {64, 64, 72, 84, 92, 94, 92, 84, 72, 64, 64};
        int[] surface = terrain.clone();
        int[] tunnel = RoadGradePlanner.applyProactiveTunnelProfile(
                surface, terrain, new boolean[terrain.length], true, 2);
        require(tunnel[4] < terrain[4] && tunnel[5] < terrain[5],
                "sustained mountain hump was not assigned tunnel elevations");

        int[] crossing = terrain.clone();
        boolean[] crossingMask = new boolean[terrain.length];
        crossingMask[4] = true;
        crossingMask[5] = true;
        int[] preserved = RoadGradePlanner.applyProactiveTunnelProfile(
                crossing, terrain, crossingMask, true, 2);
        require(preserved[4] == terrain[4] && preserved[5] == terrain[5],
                "bridge/crossing stations were incorrectly converted to tunnel");

        int[] longTerrain = new int[181];
        java.util.Arrays.fill(longTerrain, 64);
        for (int i = 30; i <= 150; i++) {
            int distance = Math.min(i - 30, 150 - i);
            longTerrain[i] = 94 - Math.min(distance, 20);
        }
        int[] longProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                longTerrain.clone(), longTerrain, new boolean[longTerrain.length], true, 2);
        require(longProfile[90] < longTerrain[90],
                "long mountain interior was not assigned a proactive tunnel profile");
    }

    private static FinalRoadWaterValidator.WaterColumn waterAtX2(int x, int z) {
        return x == 2 ? FinalRoadWaterValidator.WaterColumn.wet(65, 60, "simulation")
                : FinalRoadWaterValidator.WaterColumn.dry();
    }

    private static FinalRoadWaterValidator.WaterColumn waterAtX2AndX5(int x, int z) {
        return x == 2 || x == 5
                ? FinalRoadWaterValidator.WaterColumn.wet(65, 60, "simulation")
                : FinalRoadWaterValidator.WaterColumn.dry();
    }

    private static RoadData road(List<BlockPos> centers, List<RoadSpan> spans) {
        List<Integer> heights = new ArrayList<>();
        for (BlockPos center : centers) heights.add(center.getY());
        return road(centers, spans, heights, 1L, 2L);
    }

    private static RoadData road(List<BlockPos> centers, List<RoadSpan> spans,
                                 long ownerA, long ownerB) {
        List<Integer> heights = new ArrayList<>();
        for (BlockPos center : centers) heights.add(center.getY());
        return road(centers, spans, heights, ownerA, ownerB);
    }

    private static RoadData road(List<BlockPos> centers, List<RoadSpan> spans,
                                 List<Integer> heights, long ownerA, long ownerB) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        for (BlockPos center : centers) segments.add(new RoadSegmentPlacement(center, List.of(center)));
        return new RoadData(3, 0, List.of(), List.of(), segments, spans, heights, ownerA, ownerB);
    }

    private static List<BlockPos> line(int count) {
        List<BlockPos> result = new ArrayList<>(count);
        for (int x = 0; x < count; x++) result.add(p(x, 0));
        return result;
    }

    private static List<BlockPos> horizontalAt(int y, int startX, int endX) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = startX; x <= endX; x++) result.add(new BlockPos(x, y, 0));
        return result;
    }

    private static List<BlockPos> verticalAt(int y, int startZ, int endZ) {
        List<BlockPos> result = new ArrayList<>();
        for (int z = startZ; z <= endZ; z++) result.add(new BlockPos(0, y, z));
        return result;
    }


    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
