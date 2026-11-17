/* 文件职责：对候选方向迎风模板做解析面及硬约束对照，不能把粗代价当施工验收。 */
package net.shiroha233.roadweaver.metric;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalUpwindCandidateTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 45})
    void directionalCandidateDistinguishesUphillFromContourAfterRotation(int degrees) {
        var metric = new FinslerMetric(plane(degrees, .25), ObstacleField.NONE, 1, 4);
        var candidate = DirectionalUpwindCandidate.solve(metric, 0, 0);
        var production = new FastMarchingSolver(metric).solve(0, 0);
        int ux = 12, uz = degrees == 0 ? 0 : 12;
        int cx = degrees == 0 ? 0 : 12, cz = degrees == 0 ? 12 : -12;
        double up = candidate.at(ux, uz) / Math.hypot(ux, uz);
        double contour = candidate.at(cx, cz) / Math.hypot(cx, cz);
        assertEquals(1.0625, up, .035);
        assertEquals(1, contour, .035);
        assertTrue(up > contour + .04, "旋转后仍须区分上坡与等高线方向");
        if (degrees == 45) assertEquals(production.at(ux, uz), production.at(cx, cz), 1e-9);
        assertFalse(candidate.pathTo(ux, uz).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void barrierAndSingleCellPassRemainRealConnectivityConstraints(boolean gap) {
        ObstacleField obstacles = new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 0 && (!gap || z != 0); }
        };
        var metric = FinslerMetric.of(plane(0, 0), obstacles);
        var candidate = DirectionalUpwindCandidate.solve(metric, -12, -4);
        var production = new FastMarchingSolver(metric).solve(-12, -4);
        assertEquals(gap, candidate.reachable(12, 4));
        assertEquals(production.reachable(12, 4), candidate.reachable(12, 4));
        var path = candidate.pathTo(12, 4);
        if (!gap) { assertTrue(path.isEmpty()); return; }
        assertTrue(path.size() > 2, "窄通道夹具必须实际回溯出整路");
        assertTrue(path.stream().anyMatch(p -> p[0] == 0 && p[1] == 0));
        for (int[] p : path) assertTrue(metric.isPassable(p[0], p[1]));
    }

    @Test
    void edgeOnlyBarrierCannotBeBypassedWithDiagonalStencil() {
        var metric = new FinslerMetric(plane(0, 0), ObstacleField.NONE, 1, 4, null,
                (x, z, nx, nz) -> Math.min(x, nx) <= 0 && Math.max(x, nx) > 0);
        var field = DirectionalUpwindCandidate.solve(metric, -12, 0);
        assertFalse(field.reachable(12, 0));
        assertTrue(field.pathTo(12, 0).isEmpty());
        assertTrue(field.reachable(-1, 0));
    }

    @Test
    void outsideAndBlockedSourceNeverProduceReachableField() {
        var terrain = plane(0, 0);
        var outside = DirectionalUpwindCandidate.solve(FinslerMetric.of(terrain, ObstacleField.NONE), -100, 0);
        assertFalse(outside.reachable(0, 0));
        var metric = FinslerMetric.of(terrain, new ObstacleField() {
            public double potential(int x, int z) { return 0; }
            public boolean impassable(int x, int z) { return x == 0 && z == 0; }
        });
        assertFalse(DirectionalUpwindCandidate.solve(metric, 0, 0).reachable(12, 0));
    }

    static TerrainField plane(int degrees, double slope) {
        double gx = slope * Math.cos(Math.toRadians(degrees)), gz = slope * Math.sin(Math.toRadians(degrees));
        return new TerrainField() {
            public int height(int x, int z) { return (int)Math.round(64 + gx * x + gz * z); }
            // 解析梯度不经整数高度差分，避免把取整误差误报成方向公式收益。
            public double[] gradient(int x, int z) { return new double[]{gx, gz}; }
            public int minX() { return -16; }
            public int minZ() { return -16; }
            public int maxX() { return 16; }
            public int maxZ() { return 16; }
        };
    }
}
