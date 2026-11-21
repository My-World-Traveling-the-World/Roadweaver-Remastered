package net.shiroha233.roadweaver.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.band.BandResult;
import net.shiroha233.roadweaver.band.ElasticBandSolver;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.metric.FinslerMetric;
import net.shiroha233.roadweaver.metric.ObstacleField;
import net.shiroha233.roadweaver.metric.TerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 参数必须改变真实数学量和生产路线，同时默认参数严格保持原模型。 */
final class ConfiguredRouteSolversTest {
    private static final TerrainField RAMP = new TerrainField() {
        public int height(int x, int z) { return x; }
        public int minX() { return -80; }
        public int maxX() { return 80; }
        public int minZ() { return -32; }
        public int maxZ() { return 32; }
    };

    @Test
    void defaultsEqualOriginalModelAndExplicitWeightsChangeCosts() {
        var config = new PathfindingCostConfig();
        var baseline = FinslerMetric.of(RAMP, (x, z) -> 0.5);
        var metric = ConfiguredRouteSolvers.metric(config, RAMP, (x, z) -> 0.5, null, null);
        assertEquals(baseline.cost(0, 0, 1, 0), metric.cost(0, 0, 1, 0));
        assertEquals(4.0, metric.cost(0, 0, 1, 0));
        config.setFinslerGradeWeight(3);
        config.setFinslerObstacleWeight(8);
        config.sanitize();
        metric = ConfiguredRouteSolvers.metric(config.snapshot(), RAMP, (x, z) -> 0.5, null, null);
        assertEquals(8.0, metric.cost(0, 0, 1, 0));
        assertEquals(5.0, metric.cost(0, 0, 0, 1));
    }

    @Test
    void zeroSoftWeightsNeverRemoveHardBlockedEdges() {
        var config = new PathfindingCostConfig();
        config.setFinslerGradeWeight(0);
        config.setFinslerObstacleWeight(0);
        var metric = ConfiguredRouteSolvers.metric(config, RAMP, ObstacleField.NONE, null,
                (x0, z0, x1, z1) -> x0 < 0 && x1 >= 0);
        assertEquals(1.0, metric.cost(-1, 0, 1, 0));
        assertTrue(metric.blocksEdge(-1, 0, 1, 0));
    }

    @Test
    void stiffnessChangesActualSmoothedPointsWhileKeepingBothEndpoints() {
        List<BandResult.Point> line = List.of(new BandResult.Point(0, 0),
                new BandResult.Point(20, 30), new BandResult.Point(40, 0),
                new BandResult.Point(60, -30), new BandResult.Point(80, 0));
        var config = new PathfindingCostConfig();
        assertEquals(ElasticBandSolver.defaults().smooth(line).points(),
                ConfiguredRouteSolvers.smoother(config).smooth(line).points());
        config.setElasticBandStiffness(0.01);
        var soft = ConfiguredRouteSolvers.smoother(config).smooth(line);
        config.setElasticBandStiffness(100);
        var retained = ConfiguredRouteSolvers.smoother(config).smooth(line);
        assertNotEquals(soft.points(), retained.points());
        assertTrue(ElasticBandSolver.discreteCurvature(soft.points())
                < ElasticBandSolver.discreteCurvature(retained.points()));
        assertEquals(line.getFirst(), soft.points().getFirst());
        assertEquals(line.getLast(), soft.points().getLast());
        assertEquals(line.getFirst(), retained.points().getFirst());
        assertEquals(line.getLast(), retained.points().getLast());
    }

    @Test
    void realBandEntryReadsGradePreferenceAndAvoidsRidgeWhenExpensive() {
        var config = new PathfindingCostConfig();
        config.setPathGridStep(8);
        config.setBiomeWeight(0);
        config.setFinslerGradeWeight(0);
        var finder = new BandPathfinder();
        var start = new BlockPos(-64, 64, 0);
        var end = new BlockPos(64, 64, 0);
        var terrain = new Ridge();
        var direct = finder.findPath(start, end, 1, null, null, terrain, config);
        assertTrue(direct.success());
        assertTrue(direct.rawPath().stream().anyMatch(p -> terrain.height(p.getX(), p.getZ()) > 64),
                "零权重正控必须实际翻山，不能以两次都绕山的夹具冒充接线");
        config.setFinslerGradeWeight(100);
        var avoided = finder.findPath(start, end, 1, null, null, terrain, config);
        assertTrue(avoided.success());
        assertFalse(avoided.rawPath().isEmpty());
        assertNotEquals(direct.rawPath(), avoided.rawPath());
        assertTrue(avoided.rawPath().stream().mapToInt(p -> p.getY()).max().orElseThrow()
                < direct.rawPath().stream().mapToInt(p -> p.getY()).max().orElseThrow());
        assertEquals(start, avoided.rawPath().getFirst());
        assertEquals(end, avoided.rawPath().getLast());
    }

    private static final class Ridge implements PathTerrainField {
        public int seaLevel() { return 63; }
        public int height(int x, int z) {
            return 64 + (Math.abs(z) <= 8 ? Math.max(0, 32 - Math.abs(x)) : 0);
        }
        public int oceanFloor(int x, int z) { return 64; }
        public boolean isColumnWater(int x, int z) { return false; }
        public boolean isNearWater(int x, int z, int distance) { return false; }
        public Holder<Biome> biome(int x, int z) { return null; }
        public boolean contains(int x, int z) { return Math.abs(x) <= 80 && Math.abs(z) <= 32; }
        public int step() { return 8; }
    }
}
