package net.shiroha233.roadweaver.generation;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadStructureValidator;
import net.shiroha233.roadweaver.pathlogic.validation.FinalRoadWaterValidator;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InitialMergedRoadValidationTest {
    @Test
    void mergedRoadWithoutDuplicateJunctionsStillRunsTheWorldChecks() {
        RoadData original = road(0);
        RoadData merged = road(1);
        // 夹具通过基本校验，且没有重复路口能触发旧版引用比较。
        assertTrue(RoadDataValidator.validateFinalRoad(merged).valid());
        assertSame(merged, RoadDataValidator.normalizeDuplicateJunctions(merged));
        var visited = new ArrayList<String>();
        var result = InitialRoutePersistOps.processFinalRoad(null, merged,
                Map.of(InitialPlanLifecycleOps.ownerKey(original), original), null, Set.of(),
                new TerrainSamplingCache(), new InitialRoutePersistOps.FinalChecks(
                        road -> { assertSame(merged, road); visited.add("repair"); return road; },
                        road -> { assertSame(merged, road); visited.add("spans"); return road; },
                        road -> {
                            visited.add(road == merged ? "merged-water" : "original-water");
                            return new FinalRoadWaterValidator.ValidationResult(false, "fixture-underwater", null);
                        },
                        road -> fail("入水路线不应到达结构验收或写盘")));
        assertNull(result.road());
        assertEquals(List.of("repair", "spans", "merged-water", "original-water"), visited);
    }

    @Test
    void refinedMergedRoadSkipsOnlyTheRepeatedContinuityRepair() {
        RoadData original = road(0);
        RoadData merged = road(1);
        var visited = new ArrayList<String>();
        var result = InitialRoutePersistOps.processFinalRoad(null, merged,
                Map.of(InitialPlanLifecycleOps.ownerKey(original), original), null,
                Set.of(net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage.computeFingerprint(merged)),
                new TerrainSamplingCache(), new InitialRoutePersistOps.FinalChecks(
                        road -> fail("已经精修的同一结果不需要再跑连续性修复"),
                        road -> { visited.add("spans"); return road; },
                        road -> { visited.add("water"); return new FinalRoadWaterValidator.ValidationResult(true, "", null); },
                        road -> { visited.add("structure"); return new FinalRoadStructureValidator.ValidationResult(false, "fixture-building", null); }));
        assertNull(result.road());
        assertEquals(List.of("spans", "water", "structure", "structure"), visited);
    }

    private static RoadData road(int z) {
        var stations = new ArrayList<RoadSegmentPlacement>();
        for (int x = 0; x < 5; x++) {
            var center = new BlockPos(x, 64, z);
            stations.add(new RoadSegmentPlacement(center, List.of(center)));
        }
        return new RoadData(3, 0, List.of(), List.of(),
                stations, List.of(), List.of(64, 64, 64, 64, 64), 1L, 2L);
    }
}
