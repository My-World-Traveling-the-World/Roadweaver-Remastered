/* 文件职责：量局部求值、旋转山地对拍和解析面的网格细化误差，不把有限实验说成收敛证明。 */
package net.shiroha233.roadweaver.metric;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalCandidateStudyTest {
    @Test
    void localQuadraticRetainsObstaclesScaleAndAllDirectionSigns() {
        var metric = new FinslerMetric(terrain(30, 2, true), new ObstacleField() {
            public double potential(int x, int z) { return .1 + (x + z + 48) / 100.0; }
            public boolean impassable(int x, int z) { return false; }
        }, 3, 7, (x, z) -> .3 + (x + 24) / 50.0, null);
        Random random = new Random(4401);
        for (int i = 0; i < 100; i++) {
            int x = random.nextInt(49) - 24, z = random.nextInt(49) - 24;
            var local = DirectionalLocalCost.sample(metric, x, z);
            for (int j = 0; j < 20; j++) {
                double dx = random.nextDouble() * 6 - 3, dz = random.nextDouble() * 6 - 3;
                assertEquals(Math.hypot(dx, dz) * metric.cost(x, z, dx, dz), local.travel(dx, dz), 1e-11);
            }
        }
        assertEquals(0, DirectionalLocalCost.sample(metric, 0, 0).travel(0, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30, 45, 90})
    void rotatedRidgesRetainFrozenArrivalFieldAndRealPassage(int degrees) {
        var terrain = terrain(degrees, 2, true);
        ObstacleField obstacles = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 0 && Math.abs(z) > 2; }
        };
        var metric = new FinslerMetric(terrain, obstacles, 2, 4, (x, z) -> x < -10 ? .8 : 1, null);
        var old = FrozenDirectionalCandidate.solve(metric, -20, -12);
        var now = DirectionalUpwindCandidate.solve(metric, -20, -12);
        for (int x = -24; x <= 24; x += 2) for (int z = -24; z <= 24; z += 2) {
            assertEquals(old.reachable(x, z), now.reachable(x, z));
            if (old.reachable(x, z)) assertEquals(old.at(x, z), now.at(x, z), 1e-8);
        }
        var path = now.pathTo(20, 12);
        assertTrue(path.size() > 2);
        assertTrue(path.stream().anyMatch(p -> p[0] == 0 && Math.abs(p[1]) <= 2));
        for (int[] point : path) assertTrue(metric.isPassable(point[0], point[1]));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30, 45})
    void planeRefinementReducesMeasuredArrivalError(int degrees) {
        double dx = 20, dz = 12, angle = Math.toRadians(degrees);
        double along = .25 * (Math.cos(angle) * dx + Math.sin(angle) * dz) / Math.hypot(dx, dz);
        double exact = Math.hypot(dx, dz) * (1 + along * along);
        double[] error = new double[3];
        int[] steps = {4, 2, 1};
        for (int i = 0; i < steps.length; i++) {
            var metric = FinslerMetric.of(terrain(degrees, steps[i], false), ObstacleField.NONE);
            error[i] = Math.abs(DirectionalUpwindCandidate.solve(metric, 0, 0).at(20, 12) - exact);
        }
        // 仅断言这三个解析面的实测细化趋势；不把有限平面夹具升级成一般地形定理。
        assertTrue(error[1] < error[0], Arrays.toString(error));
        assertTrue(error[2] < error[1], Arrays.toString(error));
        assertTrue(error[2] < .6, Arrays.toString(error));
    }

    @Test
    void gradientSamplingIsBoundedPerNodeRatherThanPerObjectiveEvaluation() {
        var source = terrain(30, 2, true);
        var calls = new AtomicInteger();
        var counting = counted(source, calls);
        var metric = FinslerMetric.of(counting, ObstacleField.NONE);
        var old = FrozenDirectionalCandidate.solve(metric, 0, 0);
        int previousCalls = calls.getAndSet(0);
        var now = DirectionalUpwindCandidate.solve(metric, 0, 0);
        int currentCalls = calls.get();
        assertTrue(currentCalls > 0);
        assertTrue(currentCalls <= source.columns() * source.rows() * 3);
        assertTrue(previousCalls > currentCalls * 10, "必须真实运行冻结目标函数，不能只断言配置值");
        assertEquals(old.at(20, 12), now.at(20, 12), 1e-8);
    }

    static TerrainField counted(TerrainField source, AtomicInteger calls) {
        return new TerrainField() {
            public int height(int x, int z) { return source.height(x, z); }
            public double[] gradient(int x, int z) { calls.incrementAndGet(); return source.gradient(x, z); }
            public int minX() { return source.minX(); }
            public int minZ() { return source.minZ(); }
            public int maxX() { return source.maxX(); }
            public int maxZ() { return source.maxZ(); }
            public int step() { return source.step(); }
        };
    }

    static TerrainField terrain(int degrees, int step, boolean mountain) {
        double cos = Math.cos(Math.toRadians(degrees)), sin = Math.sin(Math.toRadians(degrees));
        return new TerrainField() {
            public int height(int x, int z) {
                double u = cos * x + sin * z, v = -sin * x + cos * z;
                return (int)Math.round(64 + .25 * u + (mountain ? 8 * Math.exp(-u * u / 100) + 2 * Math.sin(v / 8) : 0));
            }
            public double[] gradient(int x, int z) {
                double u = cos * x + sin * z, v = -sin * x + cos * z;
                double gu = .25 + (mountain ? -.16 * u * Math.exp(-u * u / 100) : 0);
                double gv = mountain ? .25 * Math.cos(v / 8) : 0;
                return new double[]{gu * cos - gv * sin, gu * sin + gv * cos};
            }
            public int minX() { return -24; }
            public int minZ() { return -24; }
            public int maxX() { return 24; }
            public int maxZ() { return 24; }
            public int step() { return step; }
        };
    }
}
