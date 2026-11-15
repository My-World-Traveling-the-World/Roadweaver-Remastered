package net.shiroha233.roadweaver.pathlogic.junction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JunctionOrchestrationTest {
    @Test
    void 批量后路选中前路并提交精修产物() {
        RoadData primary = line(0, 1, 2);
        RoadData secondary = line(8, 3, 4);
        Site site = new Site(List.of(), true);

        var result = ExistingRoadJunctionService.connectPlannedBatchWithAccess(site, List.of(primary, secondary));

        assertEquals(2, result.roads().size());
        assertSame(primary, result.roads().get(0));
        RoadData merged = result.roads().get(1);
        assertReused(secondary, merged);
        assertEquals(List.of(primary), site.donors);
        assertEquals(List.of(merged), site.validated);
        assertEquals(java.util.Set.of(RoadShardStorage.computeFingerprint(merged)), result.refinedRoadFingerprints());
        assertTrue(site.closed);
    }

    @Test
    void 已选主的候选撞结构时校验保留拒绝原因并退回原路() {
        RoadData primary = line(0, 1, 2);
        RoadData secondary = line(8, 3, 4);
        Site site = new Site(List.of(primary), false);

        // 读生产日志，避免只断言假现场自己提供的校验原因。
        List<String> messages = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger("roadweaver");
        var appender = new AbstractAppender("junction-test", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        ExistingRoadJunctionService.MergeBatchResult result;
        try {
            result = ExistingRoadJunctionService.connectPlannedBatchWithAccess(site, List.of(secondary));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }

        assertEquals(List.of(primary), site.donors);
        assertEquals(1, site.validated.size(), "必须在已选出候选后被拒绝");
        assertReused(secondary, site.validated.getFirst());
        assertTrue(messages.stream().anyMatch(message -> message.contains("structureReason=test-structure-collision")));
        assertSame(secondary, result.roads().getFirst());
        assertTrue(result.refinedRoadFingerprints().isEmpty());
        assertTrue(site.closed);
    }

    @Test
    void 单路先查附近存档再精修复用路() {
        RoadData primary = line(0, 1, 2);
        RoadData secondary = line(8, 3, 4);
        Site site = new Site(List.of(primary), true);

        RoadData merged = ExistingRoadJunctionService.connectToExistingNetworkWithAccess(site, secondary);

        assertReused(secondary, merged);
        assertEquals(List.of(-32, -24, 190, 40), site.queryBounds);
        assertEquals(1, site.runtimeRefines.get());
        assertEquals(0, site.donorWrites.get(), "平地路没有理由覆盖已持久化捐赠路");
    }

    private static void assertReused(RoadData secondary, RoadData merged) {
        assertNotSame(secondary, merged);
        var validated = RoadDataValidator.validateFinalRoad(merged);
        assertTrue(validated.valid(), validated::reason);
        assertTrue(JunctionTrimOps.networkReuseStationCount(merged) >= 50,
                "需要可施工复用的共同走廊，而非某个字段发生变化");
    }

    private static RoadData line(int z, long ownerA, long ownerB) {
        // 合法的两格站距；80 站近邻平行走廊远超过并入所需长度。
        List<RoadSegmentPlacement> stations = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            BlockPos pos = new BlockPos(i * 2, 64, z);
            stations.add(new RoadSegmentPlacement(pos, List.of(pos)));
            heights.add(64);
        }
        RoadData road = new RoadData(5, 0, List.of(), List.of(), stations, List.of(), heights, ownerA, ownerB);
        assertTrue(RoadDataValidator.isValidFinalRoad(road), "夹具必须通过候选前置校验");
        return road;
    }

    private static final class Site implements ExistingRoadJunctionService.BatchAccess,
            ExistingRoadJunctionService.BatchRefinement, ExistingRoadJunctionService.RuntimeAccess {
        private final List<RoadData> persisted;
        private final FinalRoadStructureValidator.ValidationResult validation;
        private final List<RoadData> donors = new ArrayList<>();
        private final List<RoadData> validated = new ArrayList<>();
        private final AtomicInteger runtimeRefines = new AtomicInteger();
        private final AtomicInteger donorWrites = new AtomicInteger();
        private List<Integer> queryBounds;
        private boolean closed;

        private Site(List<RoadData> persisted, boolean safe) {
            this.persisted = persisted;
            validation = safe ? FinalRoadStructureValidator.ValidationResult.success()
                    : new FinalRoadStructureValidator.ValidationResult(false, "test-structure-collision", null);
        }

        public List<RoadData> query(int minX, int minZ, int maxX, int maxZ) {
            queryBounds = List.of(minX, minZ, maxX, maxZ);
            return persisted;
        }

        public ExistingRoadJunctionService.BatchRefinement openRefinement() { return this; }
        public ExistingRoadJunctionService.BraidPrecheck resolveOverlaps(
                List<RoadData> current, List<RoadData> persisted, boolean[] skip) {
            return ExistingRoadJunctionService.BraidPrecheck.none();
        }

        public CompletableFuture<ExistingRoadJunctionService.MergeResult> refine(
                ExistingRoadJunctionService.MergeWork item) {
            // 模拟地下采样不改变本例的平地候选；不模拟选主和提交。
            donors.add(item.selection().donor());
            return CompletableFuture.completedFuture(new ExistingRoadJunctionService.MergeResult(
                    item.index(), item.secondary(), item.selection().road(), item.selection().donor()));
        }

        public FinalRoadStructureValidator.ValidationResult validate(RoadData road) {
            validated.add(road);
            return validation;
        }

        public void close() { closed = true; }
        public RoadData refineTunnels(RoadData road) {
            runtimeRefines.incrementAndGet();
            return road;
        }
        public void replaceDonor(long fingerprint, RoadData road) { donorWrites.incrementAndGet(); }
    }
}
