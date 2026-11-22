/* 文件职责：验证具有定位偏移的真实放置候选，从走廊筛选到解析键都保留起始区块。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.search.StructurePredictor.AvoidanceStructureCandidate;
import org.junit.jupiter.api.Test;

class PredictedStartIdentityTest {
    private static final long SEED = 544010L;

    @Test void locateOffsetAcrossNegativeChunkBoundaryDoesNotDropCorridorCandidate() {
        var placement = placement(-16, 16);
        var start = placement.getPotentialStructureChunk(SEED, -64, -96);
        var candidate = new AvoidanceStructureCandidate(start, holder(placement));
        var locateChunk = new ChunkPos(candidate.pos());
        assertEquals(new ChunkPos(start.x - 1, start.z + 1), locateChunk,
                "负坐标夹具必须实际跨区块");
        assertTrue(PathCorridorBuilder.predictedStartInCorridor(candidate, LongSet.of(start.toLong())));
        assertFalse(PathCorridorBuilder.predictedStartInCorridor(candidate, LongSet.of(locateChunk.toLong())),
                "仅定位点进入走廊不能冒充结构起点进入走廊");
    }

    @Test void chordKeepsStartEvenWhenLocatePointLiesOutsideItsBounds() {
        var placement = placement(16, 16);
        var start = placement.getPotentialStructureChunk(SEED, -64, -96);
        var candidate = new AvoidanceStructureCandidate(start, holder(placement));
        var center = start.getMiddleBlockPosition(64);
        var connection = new StructureConnection(center, center.offset(1, 0, 0));
        var scope = new RoutePredictionBounds(connection, start.x, start.x, start.z, start.z,
                0, 0, 0, 0, 4L);
        assertNotEquals(start, new ChunkPos(candidate.pos()));
        assertTrue(PathCorridorBuilder.predictedStartInLineScope(candidate, scope));
        assertFalse(PathCorridorBuilder.predictedStartInLineScope(
                new AvoidanceStructureCandidate(new ChunkPos(start.x + 1, start.z), candidate.structureSet()), scope));
    }

    @Test void prefilterAndExactLookupUseTheTrueStartAndKeepDifferentSetsSeparate() {
        var placement = placement(16, -16);
        var start = placement.getPotentialStructureChunk(SEED, -64, -96);
        var first = new AvoidanceStructureCandidate(start, holder(placement));
        var second = new AvoidanceStructureCandidate(start, holder(placement(16, -16)));
        var last = Set.of(first.structureSet(), second.structureSet());
        assertNotEquals(start, new ChunkPos(first.pos()));
        Set<PreparedStartKey> keys = new LinkedHashSet<>();
        PathCorridorBuilder.collectPredictedStarts("minecraft:overworld", SEED, List.of(first, first, second),
                (set, chunk) -> {
                    // 以真实放置公式为准；定位点由同一 region 的另一个区块反推时不应通过。
                    assertEquals(start, chunk);
                    return placement.getPotentialStructureChunk(SEED, chunk.x, chunk.z).equals(chunk);
                }, keys);
        assertEquals(2, keys.size(), "重复预测去重，不同结构集不能互相覆盖");
        assertEquals(last, keys.stream().map(PreparedStartKey::structureSet).collect(java.util.stream.Collectors.toSet()));
        for (var key : keys) {
            assertEquals(start.toLong(), key.chunkKey());
            assertEquals(SEED, key.seed());
            assertEquals(StructureFileStorage.SOURCE_PREDICTED, key.source());
        }
    }

    private static RandomSpreadStructurePlacement placement(int xOffset, int zOffset) {
        return new RandomSpreadStructurePlacement(new Vec3i(xOffset, 0, zOffset),
                StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F, 181, Optional.empty(),
                32, 8, RandomSpreadType.LINEAR);
    }

    private static Holder<StructureSet> holder(RandomSpreadStructurePlacement placement) {
        return Holder.direct(new StructureSet(List.of(), placement));
    }
}
