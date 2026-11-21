/* 文件职责：钉住 AB 双跑的诚实性 —— 两臂同型时不许假装做对比、也不许白算两遍。 */
package net.shiroha233.roadweaver.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.pathfinding.PathResult;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AB 双跑测试（2026-11-14，审计 G）。
 *
 * <h2>缺陷是什么</h2>
 * <p>四个旧 A* 寻路器删除后，{@code PathfinderFactory.createClassic} 也返回
 * {@link BandPathfinder} —— 于是 AB 模式的 classic 与 band 两臂是<b>同一个类</b>，
 * 跑两遍得到逐字节相同的结果，而 {@code [ROAD-AB] verdict=...} 日志还在打，
 * 读日志的人会以为「新链与旧链有实测差异」。同时算力白花一倍。</p>
 *
 * <p>修复：检测到两臂同型时直接短路，只跑一次，并打一条 WARN 说明原因。</p>
 *
 * @see AbObservingPathfinder
 */
class AbObservingPathfinderTest {

    /** 数调用次数的桩寻路器（同型两臂必须共用同一个类）。 */
    private static final class CountingPathfinder implements Pathfinder {
        int calls;

        @Override
        public PathResult findPath(BlockPos start, BlockPos end, int width,
                                   ServerLevel level,
                                   TerrainSamplingCache cache, PathTerrainField terrain,
                                   PathfindingCostConfig config) {
            calls++;
            return PathResult.failure();
        }
    }

    @Test
    void 两臂同型时必须短路只跑一次() {
        CountingPathfinder stub = new CountingPathfinder();
        AbObservingPathfinder observing = new AbObservingPathfinder(stub, stub);

        observing.findPath(null, null, 1, null, null, null, null);

        assertEquals(1, stub.calls,
                "两臂同为 " + stub.getClass().getSimpleName() + " 时不该双跑；"
                        + "实际调用 " + stub.calls + " 次（说明还在白算一遍并打假对比日志）");
    }

    @Test
    void 两次调用仍然每次都只跑一次() {
        CountingPathfinder stub = new CountingPathfinder();
        AbObservingPathfinder observing = new AbObservingPathfinder(stub, stub);

        observing.findPath(null, null, 1, null, null, null, null);
        observing.findPath(null, null, 1, null, null, null, null);

        assertEquals(2, stub.calls, "每次 findPath 各跑一次，不应翻倍");
    }
}