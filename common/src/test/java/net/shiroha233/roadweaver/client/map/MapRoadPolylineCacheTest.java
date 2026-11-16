package net.shiroha233.roadweaver.client.map;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.client.map.data.MapSnapshot;
import net.shiroha233.roadweaver.client.map.render.MapRenderers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MapRoadPolylineCacheTest {
    private record Line(int x1, int z1, int x2, int z2) {}

    @Test
    void cachedFrameRechecksViewportAndPreservesCrossScreenAndTailOrder() {
        var cache = new MapRenderers.RoadPolylineCache();
        var first = new CountingLine(List.of(at(-32, 1), at(-1, 1), at(32, 1), at(34, 1)));
        var second = new CountingLine(List.of(at(1, 0), at(3, 0)));
        List<List<BlockPos>> roads = List.of(first, second);
        MapView tailSide = view(0, 36);
        assertEquals(List.of(new Line(-32, 1, 32, 1), new Line(32, 1, 34, 1), new Line(1, 0, 3, 0)),
                rendered(cache, roads, 50, tailSide));
        assertTrue(first.reads >= 4 && second.reads >= 2, "首次需要实际读取道路站序");

        int previousReads = first.reads + second.reads;
        MapView narrow = view(-5, 5);
        assertFalse(narrow.isInViewWorld(-32, 1));
        assertFalse(narrow.isInViewWorld(32, 1));
        // 两端均在屏幕外的跨屏线仍可见；平移不读点，但重查可见性。
        assertEquals(List.of(new Line(-32, 1, 32, 1), new Line(1, 0, 3, 0)),
                rendered(cache, roads, 50, narrow));
        assertEquals(previousReads, first.reads + second.reads);

        assertEquals(List.of(new Line(-32, 1, -1, 1), new Line(-1, 1, 32, 1), new Line(1, 0, 3, 0)),
                rendered(cache, roads, 2, narrow));
        int afterZoomReads = first.reads + second.reads;
        assertTrue(afterZoomReads > previousReads, "缩放跨档要重新抽样");
        assertEquals(2, rendered(cache, roads, 50, narrow).size());
        assertEquals(afterZoomReads, first.reads + second.reads, "切回近一档不再读道路点");
    }

    @Test
    void replacingOrDeletingSnapshotNeverDrawsPriorRoadsAtSameZoom() {
        var cache = new MapRenderers.RoadPolylineCache();
        MapView view = view(-40, 40);
        var original = snapshot(List.of(List.of(at(-9, 1), at(4, 1))));
        var replacement = snapshot(List.of(List.of(at(11, -1), at(15, -1))));
        assertEquals(List.of(new Line(-9, 1, 4, 1)), rendered(cache, original.roadPolylines(), 10, view));
        assertEquals(List.of(new Line(11, -1, 15, -1)), rendered(cache, replacement.roadPolylines(), 10, view));
        assertEquals(List.of(), rendered(cache, MapSnapshot.empty().roadPolylines(), 10, view));
        assertEquals(List.of(new Line(-9, 1, 4, 1)), rendered(cache, original.roadPolylines(), 10, view));
    }

    private static List<Line> rendered(MapRenderers.RoadPolylineCache cache, List<List<BlockPos>> roads,
                                       int step, MapView view) {
        var lines = new ArrayList<Line>();
        cache.forEachVisible(roads, step, view::segmentInViewWorld,
                (x1, z1, x2, z2) -> lines.add(new Line(x1, z1, x2, z2)));
        return lines;
    }

    private static MapView view(int minX, int maxX) {
        var view = new MapView();
        view.resetFromSnapshot(new MapSnapshot(List.of(at(minX, -2), at(maxX, 2)),
                List.of(), List.of(), List.of()));
        return view;
    }

    private static MapSnapshot snapshot(List<List<BlockPos>> roads) {
        return new MapSnapshot(List.of(), List.of(), List.of(), roads);
    }

    private static BlockPos at(int x, int z) { return new BlockPos(x, 64, z); }

    private static final class CountingLine extends AbstractList<BlockPos> {
        private final List<BlockPos> points;
        private int reads;

        private CountingLine(List<BlockPos> points) { this.points = points; }
        @Override public BlockPos get(int index) { reads++; return points.get(index); }
        @Override public int size() { return points.size(); }
    }
}
