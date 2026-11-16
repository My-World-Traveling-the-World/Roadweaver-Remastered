/* 文件职责：验证接路索引的跨度优先级、端点边界、同坐标站序与查询复用。 */
package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionSpanIndexTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 显式隧道路口可打开洞身，但不能打开桥身或保护缺口。 */
    @Test
    void preservesBridgeHeadsAndHardGapsAcrossTunnelJunctions() {
        var stations = stations(12);
        var spans = List.of(span(stations, 1, 4, SpanType.BRIDGE),
                span(stations, 5, 10, SpanType.TUNNEL),
                span(stations, 3, 9, SpanType.TUNNEL_JUNCTION),
                span(stations, 8, 8, SpanType.PROTECTED_GAP));
        var index = new JunctionSpanIndex(stations, spans);
        assertEquals(Set.of(2, 3, 8), marked(index.blocked()));
        assertEquals(Set.of(2, 3), marked(index.searchExcluded()));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), marked(index.special()));
        // 每次返回独立数组，调用方修改一份不能污染后续查询。
        index.blocked()[2] = false;
        assertTrue(index.blocked()[2]);
    }

    /** 坐标有重复时沿用旧覆盖顺序；同次多掩码不再重复扫描站序列。 */
    @Test
    void reusesOneIndexAndKeepsLastStationAtDuplicateXZ() {
        var source = new ArrayList<>(stations(6));
        source.set(4, new RoadSegmentPlacement(new BlockPos(0, 70, 0), List.of(new BlockPos(0, 70, 0))));
        var counted = new Counted(source);
        var index = new JunctionSpanIndex(counted,
                List.of(new RoadSpan(new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), SpanType.PROTECTED_GAP)));
        assertEquals(6, counted.reads);
        assertEquals(Set.of(4, 5), marked(index.blocked()));
        assertEquals(Set.of(), marked(index.searchExcluded()));
        assertEquals(Set.of(4, 5), marked(index.special()));
        assertEquals(6, counted.reads, "同一查询上下文不能为每份掩码再建一遍站号表");
        assertTrue(new JunctionSpanIndex(stations(6), List.of()).special().length == 6);
    }

    private static List<RoadSegmentPlacement> stations(int count) {
        var result = new ArrayList<RoadSegmentPlacement>();
        for (int i = 0; i < count; i++) {
            var pos = new BlockPos(i, 64, 0);
            result.add(new RoadSegmentPlacement(pos, List.of(pos)));
        }
        return result;
    }

    private static RoadSpan span(List<RoadSegmentPlacement> stations, int from, int to, SpanType type) {
        return new RoadSpan(stations.get(from).middlePos(), stations.get(to).middlePos(), type);
    }

    private static Set<Integer> marked(boolean[] mask) {
        var result = new TreeSet<Integer>();
        for (int i = 0; i < mask.length; i++) if (mask[i]) result.add(i);
        return result;
    }

    /** 从入口观测索引建立次数，避免用新增生产计数污染运行时。 */
    private static final class Counted extends AbstractList<RoadSegmentPlacement> {
        private final List<RoadSegmentPlacement> source;
        private int reads;
        Counted(List<RoadSegmentPlacement> source) { this.source = source; }
        @Override public RoadSegmentPlacement get(int index) { reads++; return source.get(index); }
        @Override public int size() { return source.size(); }
    }
}
