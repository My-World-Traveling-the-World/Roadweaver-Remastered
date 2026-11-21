/* 文件职责：直接调用生产 plan，防止瓦片只在测试中使用而主入口仍整域采样。 */
package net.shiroha233.roadweaver.planning.impl;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.bridge.SampledTerrainField;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGrid;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelinePlannerSamplingTest {
    @org.junit.jupiter.api.Test
    void realTopologyUsesConfiguredGradeWeightInsteadOfAnotherDefaultMetric() {
        var config = net.shiroha233.roadweaver.config.ConfigService.get().pathfindingCost();
        double saved = config.finslerGradeWeight();
        try {
            var planner = new PipelinePlanner(8, () -> request -> {
                int[] height = new int[request.sampleCount()];
                for (int i = 0; i < height.length; i++) {
                    int x = request.blockX(i), z = request.blockZ(i);
                    height[i] = 64 + (Math.abs(x) <= 8 && z < 24 ? 48 : 0);
                }
                return new AccurateHeightGrid(request, height, height, height);
            });
            var a = new BlockPos(-24, 64, 0);
            var b = new BlockPos(24, 64, 0);
            var c = new BlockPos(0, 64, 64);
            config.setFinslerGradeWeight(0);
            var flat = planner.plan(List.of(a, b, c), 128);
            config.setFinslerGradeWeight(100);
            var graded = planner.plan(List.of(a, b, c), 128);
            assertEquals(2, flat.size());
            assertEquals(2, graded.size());
            var flatEdges = flat.stream().map(edge -> java.util.Set.of(edge.from(), edge.to()))
                    .collect(java.util.stream.Collectors.toSet());
            var gradedEdges = graded.stream().map(edge -> java.util.Set.of(edge.from(), edge.to()))
                    .collect(java.util.stream.Collectors.toSet());
            assertNotEquals(flatEdges, gradedEdges, "真实规划入口必须接到新版坡度参数");
        } finally {
            config.setFinslerGradeWeight(saved);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 131000})
    void planUsesInjectedSamplingBoundaryAndPreservesActualConnections(int span) {
        var samplerCreates = new java.util.concurrent.atomic.AtomicInteger();
        var requests = new ArrayList<net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightGridRequest>();
        var planner = new PipelinePlanner(8, () -> {
            samplerCreates.incrementAndGet();
            // 只注入世界读取；布局与整场/瓦片选择必须由真实 plan 决定。
            return request -> {
                requests.add(request);
                // 大包围盒的远点没有准入邻居；实际 plan 必须只采近点附近。
                if (span > 1000) assertTrue(request.sampleCount() <= 4096);
                int[] motion = new int[request.sampleCount()], surface = new int[motion.length], floor = new int[motion.length];
                Arrays.fill(motion, 64); Arrays.fill(surface, 71); Arrays.fill(floor, 64);
                return new AccurateHeightGrid(request, surface, floor, motion);
            };
        });
        var a = new BlockPos(0, 64, 0); var b = new BlockPos(24, 64, 24);
        var isolated = new BlockPos(span, 64, span);
        var connections = planner.plan(List.of(a, b, isolated), 48);
        assertEquals(1, samplerCreates.get());
        assertEquals(1, connections.size(), "不能靠回退或丢空结果冒充完成规划");
        assertEquals(a, connections.getFirst().from()); assertEquals(b, connections.getFirst().to());
        assertEquals(ConnectionStatus.PLANNED, connections.getFirst().status());
        assertFalse(requests.isEmpty(), "实际 plan 必须触发精确高度边界");
        assertTrue(requests.size() <= 4);
        assertEquals(-64, requests.getFirst().minX()); assertEquals(-64, requests.getFirst().minZ());
        if (span == 128) {
            var layout = SampledTerrainField.planningLayout(0, 0, span, span, 64, 8);
            assertEquals(layout.columns(), requests.getFirst().width());
            assertEquals(layout.rows(), requests.getFirst().height());
        }
    }
}
