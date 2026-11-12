package net.shiroha233.roadweaver.planning.path;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedEndpointPathReuseTest {
    public static void main(String[] args) {
        SharedEndpointPathReuseTest test = new SharedEndpointPathReuseTest();
        test.sameEndpointUsesOneTrunkAndPreservesBothFarEndpoints();
        test.providedCoordinateAndSpawnScenariosKeepSingleTrunks();
        test.endpointApproachReachableYBoundsByWindow();
        test.lowerEndpointApproachLowersOnlyTowardTheUnifiedHeight();
        System.out.println("SharedEndpointPathReuseTest: PASS");
    }

    @Test
    void endpointApproachReachableYBoundsByWindow() {
        // Endpoint at (0,64,0); approach climbs to 100 over the next 20 stations.
        List<BlockPos> path = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            path.add(new BlockPos(i * 4, 64 + i * 2, 0));
        }
        BlockPos endpoint = path.get(0);
        // The minimum over the endpoint + 16 stations window is at the
        // endpoint itself (64), so the reachable height is the endpoint's own.
        int minY = SharedEndpointPathReuse.endpointApproachReachableYForTest(path, endpoint);
        assertEquals(64, minY);

        // Endpoint at the top of a climb (index 0 is high, approach descends):
        // the reachable height is the low end of the window, not the endpoint.
        List<BlockPos> descending = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            descending.add(new BlockPos(i * 4, 100 - i * 2, 0));
        }
        assertEquals(100 - 8, SharedEndpointPathReuse.endpointApproachReachableYForTest(descending, descending.get(0)));
    }

    @Test
    void lowerEndpointApproachLowersOnlyTowardTheUnifiedHeight() {
        // Endpoint (0,100,0) with a flat plateau at 100 behind it; unified 80.
        List<BlockPos> path = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            path.add(new BlockPos(i * 4, 100, 0));
        }
        List<BlockPos> lowered = SharedEndpointPathReuse.lowerEndpointApproachForTest(path, path.get(0), 80);
        assertEquals(80, lowered.get(0).getY(), "the endpoint station is seated on the unified height");
        // Ramp: each successive station is at most one block above the previous
        // toward the endpoint (station k may sit at 80 + k at most).
        for (int i = 1; i < lowered.size(); i++) {
            assertTrue(lowered.get(i).getY() <= lowered.get(i - 1).getY() + 1,
                    "no station may be more than one block above its neighbor toward the endpoint");
        }
        assertTrue(lowered.get(lowered.size() - 1).getY() <= 100,
                "stations never rise above the original plateau");
    }

    @Test
    void sameEndpointUsesOneTrunkAndPreservesBothFarEndpoints() {
        BlockPos shared = p(0, 0);
        BlockPos farA = p(64, 0);
        BlockPos farB = p(64, 20);
        StructureConnection a = new StructureConnection(shared, farA, ConnectionStatus.PLANNED);
        StructureConnection b = new StructureConnection(shared, farB, ConnectionStatus.PLANNED);

        List<BlockPos> primary = lineTo(64, 0);
        List<BlockPos> secondary = new ArrayList<>();
        secondary.add(shared);
        for (int x = 4; x <= 32; x += 4) secondary.add(p(x, 1));
        for (int x = 36; x <= 64; x += 4) secondary.add(p(x, 20));

        LinkedHashMap<StructureConnection, List<BlockPos>> paths = new LinkedHashMap<>();
        paths.put(a, primary);
        paths.put(b, secondary);
        RoadTerrainPlanningPort.Result result = SharedEndpointPathReuse.reuseSharedEndpointTails(
                new RoadTerrainPlanningPort.Result(TerrainSamplingMode.COARSE_CORRIDOR, paths), 4);

        List<BlockPos> merged = result.paths().get(b);
        assertEquals(shared, merged.get(0), "the shared endpoint must remain the route start");
        assertEquals(farB, merged.get(merged.size() - 1), "the secondary structure endpoint must remain");
        assertTrue(merged.stream().anyMatch(pos -> pos.getX() == 32 && pos.getZ() == 0),
                "the route must contain the primary shared trunk before branching");
        assertTrue(merged.stream().anyMatch(pos -> pos.getX() == 36 && pos.getZ() == 20),
                "the route must keep the secondary branch after the single split");
    }

    @Test
    void providedCoordinateAndSpawnScenariosKeepSingleTrunks() {
        BlockPos near1898 = p(21, -1898);
        assertSingleTrunkGroup(near1898,
                List.of(p(0, 0), p(192, -2080), p(-176, -2080)));

        BlockPos spawn = p(0, 0);
        assertSingleTrunkGroup(spawn,
                List.of(p(21, -1898), p(192, -1800), p(-180, -1700)));
    }

    private static void assertSingleTrunkGroup(BlockPos shared, List<BlockPos> farEndpoints) {
        LinkedHashMap<StructureConnection, List<BlockPos>> paths = new LinkedHashMap<>();
        for (BlockPos far : farEndpoints) {
            StructureConnection connection = new StructureConnection(shared, far, ConnectionStatus.PLANNED);
            paths.put(connection, pathFromShared(shared, far));
        }
        RoadTerrainPlanningPort.Result result = SharedEndpointPathReuse.reuseSharedEndpointTails(
                new RoadTerrainPlanningPort.Result(TerrainSamplingMode.COARSE_CORRIDOR, paths), 4);

        for (Map.Entry<StructureConnection, List<BlockPos>> entry : result.paths().entrySet()) {
            List<BlockPos> route = entry.getValue();
            assertEquals(shared, route.get(0), "common endpoint was lost at " + shared);
            assertEquals(entry.getKey().to(), route.get(route.size() - 1),
                    "far endpoint was replaced at " + shared);
        }
        List<List<BlockPos>> routes = new ArrayList<>(result.paths().values());
        int commonPrefix = 0;
        while (commonPrefix < routes.get(0).size()) {
            boolean same = true;
            for (List<BlockPos> route : routes) {
                if (commonPrefix >= route.size()
                        || !route.get(commonPrefix).equals(routes.get(0).get(commonPrefix))) {
                    same = false;
                    break;
                }
            }
            if (!same) break;
            commonPrefix++;
        }
        assertTrue(commonPrefix >= 4, "routes near " + shared + " did not form a shared trunk");
    }

    private static List<BlockPos> pathFromShared(BlockPos shared, BlockPos far) {
        BlockPos trunk = p(shared.getX(), shared.getZ() - 48);
        List<BlockPos> result = new ArrayList<>();
        appendSegment(result, shared, trunk, 4);
        appendSegment(result, trunk, far, 4);
        return result;
    }

    private static void appendSegment(List<BlockPos> out, BlockPos from, BlockPos to, int step) {
        int distance = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        int count = Math.max(1, (distance + step - 1) / step);
        for (int i = out.isEmpty() ? 0 : 1; i <= count; i++) {
            double fraction = (double) i / count;
            out.add(p((int) Math.round(from.getX() + (to.getX() - from.getX()) * fraction),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * fraction)));
        }
    }

    private static List<BlockPos> lineTo(int x, int z) {
        List<BlockPos> result = new ArrayList<>();
        for (int current = 0; current <= x; current += 4) result.add(p(current, z));
        return result;
    }

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }
}
