package net.shiroha233.roadweaver.pathlogic.pathcalc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryProtectionProductTest {
    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos END = new BlockPos(8, 64, 0);
    private static final BlockPos PROTECTED = new BlockPos(4, 64, 1);
    private static final StructureConnection CONNECTION = new StructureConnection(START, END);

    @Test
    void 曲线侵入保护列时选择不侵入的可铺几何() {
        // 两个产物都有实际铺装列；曲线的中段跨进保护列，而未松弛版本绕过它。
        var curve = footprint(true);
        var safe = footprint(false);
        assertTrue(touches(curve));
        assertFalse(touches(safe));
        assertEquals(9, safe.size());
        var attempts = new AtomicInteger();
        var protection = new PathMetricsOps.GeometryProtection(GeometryProtectionProductTest::touches,
                segments -> touches(segments) ? 1 : 0, () -> true);
        var chosen = PathMetricsOps.chooseGeometry(curve, () -> {
            attempts.incrementAndGet();
            return safe;
        }, CONNECTION, protection);

        assertSame(safe, chosen);
        assertEquals(1, attempts.get(), "旁路保护检查会直接返回侵入版");
        assertFalse(touches(chosen));
    }

    @Test
    void 两种几何都侵入且无法声明施工缺口时拒绝() {
        var occupied = footprint(true);
        assertTrue(touches(occupied));
        var protection = new PathMetricsOps.GeometryProtection(GeometryProtectionProductTest::touches,
                segments -> 0, () -> true);
        // 空缺口指此碰撞不能表达为可施工缺口，两个版本都不能下发。
        assertNull(PathMetricsOps.chooseGeometry(occupied, () -> occupied, CONNECTION, protection));
    }

    private static List<RoadSegmentPlacement> footprint(boolean detour) {
        return PathPostProcessor.rasterizeStations(java.util.stream.IntStream.rangeClosed(0, 8)
                .mapToObj(x -> new BlockPos(x, 64, detour && x >= 3 && x <= 5 ? 1 : 0)).toList(), 1);
    }

    private static boolean touches(List<RoadSegmentPlacement> segments) {
        return segments.stream().flatMap(s -> s.positions().stream())
                .anyMatch(p -> p.getX() == PROTECTED.getX() && p.getZ() == PROTECTED.getZ());
    }
}
