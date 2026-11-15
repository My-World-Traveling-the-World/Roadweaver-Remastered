package net.shiroha233.roadweaver.bridge;

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

    private record FlatTerrain(int zBound) implements PathTerrainField {
        public int seaLevel() { return 63; }
        public int height(int x, int z) { return 64; }
        public int oceanFloor(int x, int z) { return 64; }
        public boolean isColumnWater(int x, int z) { return false; }
        public boolean isNearWater(int x, int z, int distance) { return false; }
        public Holder<Biome> biome(int x, int z) { return null; }
        public boolean contains(int x, int z) { return x >= -80 && x <= 80 && Math.abs(z) <= zBound; }
        public int step() { return 8; }
    }
}
