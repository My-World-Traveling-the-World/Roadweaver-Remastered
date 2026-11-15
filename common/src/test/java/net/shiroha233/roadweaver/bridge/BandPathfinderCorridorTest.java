package net.shiroha233.roadweaver.bridge;

import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservationTest;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract;
import net.shiroha233.roadweaver.pathfinding.impl.CorridorBan;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class BandPathfinderCorridorTest {
    private static final BlockPos START = new BlockPos(-64, 64, 0);
    private static final BlockPos END = new BlockPos(64, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void forbiddenStripBetweenGridPointsClosesTheOnlyPassage() {
        PathfindingCostConfig config = config();
        assertTrue(find(config, 16).success(), "正控：无禁行带时这条通道是连通的");
        // x=-4..-1 不含 x=-8 或 x=0 两边的 FMM 格点，只查格点会把整道禁行带漏掉。
        CorridorBan ban = ban(IntStream.rangeClosed(-4, 4).mapToObj(i -> new BlockPos(-4, 64, i * 4)).toList());
        assertFalse(ban.blocks(-8, 0));
        assertFalse(ban.blocks(0, 0));
        assertTrue(ban.blocks(-4, 0));
        // 生产平行路重算用线程作用域；显式配置的绕行另有下一条测试覆盖。
        assertFalse(CorridorBan.withScoped(ban, () -> find(config, 16)).success(),
                "作用域硬禁不能因为粗搜索格距大就失效");
        assertTrue(find(config, 16).success(), "作用域结束后下一条路不受上一次重算污染");
    }

    @Test
    void isolatedForbiddenCellCanBeRoutedAroundWithoutCrossingIt() {
        PathfindingCostConfig config = config();
        CorridorBan ban = ban(List.of(new BlockPos(-4, 64, 0)));
        config.setCorridorBan(ban);
        PathResult result = find(config, 32);
        assertTrue(result.success(), "绕开单个禁行格后仍有解");
        List<BlockPos> path = result.rawPath();
        assertEquals(START, path.getFirst());
        assertEquals(END, path.getLast());
        assertTrue(path.stream().anyMatch(p -> p.getZ() != 0));
        // 验证输出边的中间点，避免只检查路点而漏掉光滑后掠过禁区的情况。
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int count = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
            for (int j = 0; j <= count; j++) {
                double t = count == 0 ? 0 : (double) j / count;
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                assertFalse(ban.blocks(x, z), "成品不得切过禁区: " + x + "," + z);
            }
        }
    }

    @Test
    void discountPullsTheActualRouteTowardAnOffsetCrossingCorridor() {
        PathfindingCostConfig config = config();
        var straight = find(config, 32);
        assertTrue(straight.success());
        config.setCorridorAttractRadius(4);
        config.setCorridorAttractFactor(0.10);
        CorridorAttract corridor = CorridorAttract.forPlanning(config).withPath(
                IntStream.rangeClosed(-10, 10).mapToObj(i -> new BlockPos(i * 4, 64, 16)).toList());
        assertTrue(corridor.hits(0, 16));
        assertFalse(corridor.hits(0, 0));
        assertTrue(straight.rawPath().stream().noneMatch(p -> corridor.hits(p.getX(), p.getZ())));
        config.setCorridorAttract(corridor);
        final var discounted = find(config, 32);
        assertTrue(discounted.success());
        assertTrue(discounted.rawPath().stream().filter(p -> corridor.hits(p.getX(), p.getZ())).count() >= 3,
                "打开优惠后实际路径要进入共用走廊");
    }

    @Test
    void sameGridRouteKeepsBothEndpointsAndRejectsMissingConnectorColumn() {
        BlockPos start = new BlockPos(-5, 64, -3), end = new BlockPos(-2, 64, -3);
        BandPathfinder solver = new BandPathfinder();
        // 同格的跨度是 3 格；走生产入口可抓到 set(0)/set(last) 覆盖同一站的退化。
        PathResult open = solver.findPath(start, end, 1, null, 100, null, new FlatTerrain(32), config());
        assertTrue(open.success(), "同一采样格不等于同一现实端点");
        assertEquals(List.of(start, end), open.rawPath(), "两端都要留给后处理");

        // x=-4 的缺口不在粗采样格上，只有真实世界坐标的短接线检查能看到。
        PathResult blocked = solver.findPath(start, end, 1, null, 100, null, new FlatTerrain(32, true), config());
        assertFalse(blocked.success(), "粗网格可达不能掩盖接线中间缺一列");
    }

    @Test
    void sameGridRouteCannotShortCircuitHardCorridor() {
        BlockPos start = new BlockPos(-2, 64, -2), end = new BlockPos(1, 64, 1);
        var config = config();
        var solver = new BandPathfinder();
        assertTrue(solver.findPath(start, end, 1, null, 100, null, new FlatTerrain(32), config).success());
        // (-1,0) 在公共角另一侧；两个端点都不在这一片禁行量化格内。
        CorridorBan ban = ban(List.of(new BlockPos(-1, 64, 0)));
        assertFalse(ban.blocks(start.getX(), start.getZ()));
        assertFalse(ban.blocks(end.getX(), end.getZ()));
        assertTrue(ban.intersectsSegment(start.getX(), start.getZ(), end.getX(), end.getZ()));
        config.setCorridorBan(ban);
        assertFalse(solver.findPath(start, end, 1, null, 100, null, new FlatTerrain(32), config).success(),
                "同格连接也不能从禁行角钻过");
    }

    @Test
    void treeColumnBetweenGridPointsClosesOnlyPassage() {
        var start = new BlockPos(-64, 64, 0);
        var end = new BlockPos(64, 64, 0);
        var terrain = new FlatTerrain(0);
        var config = config();
        assertTrue(new BandPathfinder().findPath(start, end, 1, null, 1000, null, terrain, config).success());
        // 不扩大夹具，令两侧粗格都可达；必须由边检查拒绝，不能靠点掩码碰巧变红。
        var mask = HugeTreeReservationTest.mask(-128, -64, 128, 64, 0, new BlockPos(-5, 64, 0));
        assertFalse(mask.blocks(-8, 0));
        assertFalse(mask.blocks(0, 0));
        var solver = new BandPathfinder((level, x0, z0, x1, z1, radius) -> mask);
        assertFalse(solver.findPath(start, end, 1, null, 1000, null, terrain, config).success());
    }

    @Test
    void roadWidthAndSmoothingClearTheLiveReservationFootprint() {
        var config = config();
        var tree = new BlockPos(0, 64, 6);
        var terrain = new FlatTerrain(32);
        assertTrue(new BandPathfinder().findPath(START, END, 15, null, 1000, null, terrain, config)
                .rawPath().stream().allMatch(p -> p.getZ() == 0));
        var solver = new BandPathfinder((level, x0, z0, x1, z1, radius) ->
                HugeTreeReservationTest.mask(x0, z0, x1, z1, radius, tree));
        var result = solver.findPath(START, END, 15, null, 1000, null, terrain, config);
        assertTrue(result.success(), "一列预留有绕路，不能直接拒绝");
        assertEquals(START, result.rawPath().getFirst());
        assertEquals(END, result.rawPath().getLast());
        assertTrue(result.rawPath().stream().anyMatch(p -> p.getZ() < 0));
        var expected = HugeTreeReservationTest.mask(-128, -64, 128, 64, 8, tree);
        for (int i = 1; i < result.rawPath().size(); i++) {
            var a = result.rawPath().get(i - 1);
            var b = result.rawPath().get(i);
            assertFalse(expected.intersects(a.getX(), a.getZ(), b.getX(), b.getZ()));
        }
    }

    private static CorridorBan ban(List<BlockPos> line) {
        List<RoadSegmentPlacement> stations = line.stream()
                .map(pos -> new RoadSegmentPlacement(pos, List.of(pos))).toList();
        RoadData road = new RoadData(4, 1, List.of(), List.of(), stations, List.of(),
                line.stream().map(BlockPos::getY).toList(), 1L, 2L);
        return CorridorBan.aroundRoadRun(road, 0, stations.size() - 1, 0);
    }

    private static PathfindingCostConfig config() {
        var config = new PathfindingCostConfig();
        config.setAStarStep(8);
        return config;
    }

    private static PathResult find(PathfindingCostConfig config, int zBound) {
        return new BandPathfinder().findPath(START, END, 1, null, 1000, null, new FlatTerrain(zBound), config);
    }

    private record FlatTerrain(int zBound, boolean missingColumn) implements PathTerrainField {
        private FlatTerrain(int zBound) { this(zBound, false); }
        public int seaLevel() { return 63; }
        public int height(int x, int z) { return 64; }
        public int oceanFloor(int x, int z) { return 64; }
        public boolean isColumnWater(int x, int z) { return false; }
        public boolean isNearWater(int x, int z, int distance) { return false; }
        public Holder<Biome> biome(int x, int z) { return null; }
        public boolean contains(int x, int z) {
            return x >= -80 && x <= 80 && Math.abs(z) <= zBound && !(missingColumn && x == -4 && z == -3);
        }
        public int step() { return 8; }
    }
}
