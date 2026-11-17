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
    @Test void anchorsAndObservationReuseFrozenStationsButIsolateOuterLists() throws Exception {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for(int x=0;x<12;x++) stations.add(new RoadSegmentPlacement(new BlockPos(x,64,0),List.of(new BlockPos(x,64,0))));
        var anchor = EndpointApproachConnector.buildAnchorPlan(stations,Collections.nCopies(12,64),null,0,1,false,10);
        assertNotNull(anchor);assertEquals(10,anchor.segments().size());
        for(int i=0;i<10;i++) assertSame(stations.get(i),anchor.segments().get(i));
        var method = PathPostProcessor.class.getDeclaredMethod("freezeProduct",List.class);method.setAccessible(true);
        @SuppressWarnings("unchecked") var observed = (List<RoadSegmentPlacement>)method.invoke(null,stations);
        assertEquals(12,observed.size());assertSame(stations.getFirst(),observed.getFirst());
        for(var invalid:List.of(new RoadSegmentPlacement(new BlockPos(0,64,0),null),
                new RoadSegmentPlacement(new BlockPos(0,64,0),Arrays.asList((BlockPos)null)))) {
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,() -> method.invoke(null,List.of(invalid)));
            assertInstanceOf(NullPointerException.class,failure.getCause(),"复用不能放宽原观测方对非法输入的拒绝");
        }
        stations.clear();assertEquals(12,observed.size());assertEquals(10,anchor.segments().size());
        assertThrows(UnsupportedOperationException.class,observed::clear);
        assertThrows(UnsupportedOperationException.class,anchor.segments()::clear);
        assertThrows(UnsupportedOperationException.class,observed.getFirst().positions()::clear);
    }
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
            var changed = new RoadData(road.width(),road.roadType(),road.materials(),road.slabMaterials(),
                    road.roadSegmentList(),road.spans(),List.of(64,64,64,64),1,2);
            assertEquals("target-height-size",RoadDataValidator.validateFinalRoad(changed).reason(),"新产物必须独立校验，不能继承旧对象结果");
            assertTrue(RoadDataValidator.validateFinalRoad(road).valid());
        } finally {
            RoadSnapService.GRID_CACHE.remove(); RoadSnapService.ROAD_INDEX_CACHE.remove(); RoadSnapService.BOUNDS_CACHE.remove();
        }
    }
}
