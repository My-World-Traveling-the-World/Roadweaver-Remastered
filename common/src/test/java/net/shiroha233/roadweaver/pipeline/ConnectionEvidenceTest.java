/* 文件职责：证明观测接到实际候选，并且观测失败不会改变下发森林。 */
package net.shiroha233.roadweaver.pipeline;

import java.util.*;
import net.shiroha233.roadweaver.metric.*;
import net.shiroha233.roadweaver.topology.RoadNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionEvidenceTest {
    @Test
    void selectedAndAlternativeEvidenceUsesRealEndpointsAndLeavesTopologyUnchanged() {
        var pipeline = new RoadPipeline(metric(), null);
        var nodes = nodes();
        var expected = pipeline.planTopology(nodes);
        List<RoadPipeline.ConnectionEvidence> events = new ArrayList<>();
        var actual = pipeline.planTopologyWithShadow(nodes, 0, null, (x, z) -> 0, events::add);
        assertEquals(expected.edges(), actual.edges());
        assertTrue(events.size() > actual.edges().size(), "必须真实评过候补，不能空集合通过");
        assertTrue(events.size() <= 12);
        var selected = events.stream().filter(RoadPipeline.ConnectionEvidence::selected).toList();
        assertEquals(actual.edges().size(), selected.size());
        for (var edge : actual.edges()) {
            var a = nodes.get(edge.from()); var b = nodes.get(edge.to());
            assertEquals(1, selected.stream().filter(e -> e.fromX() == a.x() && e.fromZ() == a.z()
                    && e.toX() == b.x() && e.toZ() == b.z() && e.geodesic() == edge.distance()).count());
        }
        assertTrue(events.stream().allMatch(e -> e.detail().contains("unknownProtection=")),
                "粗格没有结构快照，证据必须继续明确缺失");
    }

    @Test
    void throwingObserverCannotTriggerFallbackOrChangeSelectedEdges() {
        var pipeline = new RoadPipeline(metric(), null);
        int[] calls = {0};
        var expected = pipeline.planTopology(nodes());
        var actual = pipeline.planTopologyWithShadow(nodes(), 0, null, (x, z) -> 0, e -> {
            calls[0]++; throw new IllegalStateException("controlled observer failure");
        });
        assertEquals(1, calls[0]);
        assertEquals(expected.edges(), actual.edges());
    }

    private static List<RoadNode> nodes() {
        return List.of(new RoadNode("0", -4, -4), new RoadNode("1", 4, -4),
                new RoadNode("2", -4, 4), new RoadNode("3", 4, 4));
    }

    private static FinslerMetric metric() {
        return FinslerMetric.of(new TerrainField() {
            public int height(int x, int z) { return 64; }
            public int minX() { return -8; }
            public int maxX() { return 8; }
            public int minZ() { return -8; }
            public int maxZ() { return 8; }
        }, ObstacleField.NONE);
    }
}
