/* 文件职责：缓存回收只影响复用，不改变索引、掩码和包围盒。 */
package net.shiroha233.roadweaver.postprocess;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.util.WeakIdentityCache;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoadSnapCacheLifecycleTest {
    @Test void collectedRoadKeysReleaseAllThreeDerivedCachesAndProductsRebuildEqually() throws Exception {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for(int x=0;x<5;x++) stations.add(new RoadSegmentPlacement(new BlockPos(x,64,0),List.of(new BlockPos(x,64,0))));
        var road = new RoadData(3,0,List.of(),List.of(),stations,
                List.of(new RoadSpan(stations.getFirst().middlePos(),stations.getLast().middlePos(),SpanType.BRIDGE)),
                List.of(64,64,64,64,64),1,2);
        try {
            RoadSnapService.GRID_CACHE.get().clear(); RoadSnapService.ROAD_INDEX_CACHE.get().clear(); RoadSnapService.BOUNDS_CACHE.get().clear();
            var grid = RoadSnapService.buildGrid(road,null);
            var index = RoadSnapService.roadIndex(road);
            var bounds = RoadSnapService.roadBounds(road);
            assertSame(grid,RoadSnapService.buildGrid(road,null));
            assertSame(index,RoadSnapService.roadIndex(road));
            assertSame(bounds,RoadSnapService.roadBounds(road));
            for(var cache : List.of(RoadSnapService.GRID_CACHE.get(),RoadSnapService.ROAD_INDEX_CACHE.get(),RoadSnapService.BOUNDS_CACHE.get())) {
                var field = WeakIdentityCache.class.getDeclaredField("values"); field.setAccessible(true);
                var entries = (Map<?,?>)field.get(cache);
                assertEquals(1,entries.size());
                Reference<?> key = (Reference<?>)entries.keySet().iterator().next();
                key.clear(); assertTrue(key.enqueue()); assertEquals(0,cache.size());
            }
            assertNotSame(grid,RoadSnapService.buildGrid(road,null));
            assertEquals(grid.findNearest(new BlockPos(2,64,0)),RoadSnapService.buildGrid(road,null).findNearest(new BlockPos(2,64,0)));
            assertArrayEquals(index.spanMask(SpanType.BRIDGE),RoadSnapService.roadIndex(road).spanMask(SpanType.BRIDGE));
            assertEquals(bounds,RoadSnapService.roadBounds(road));
            var validation = RoadDataValidator.validateFinalRoad(road);
            assertTrue(validation.valid());
            assertSame(validation,RoadDataValidator.validateFinalRoad(road));
        } finally {
            RoadSnapService.GRID_CACHE.remove(); RoadSnapService.ROAD_INDEX_CACHE.remove(); RoadSnapService.BOUNDS_CACHE.remove();
        }
    }
}
