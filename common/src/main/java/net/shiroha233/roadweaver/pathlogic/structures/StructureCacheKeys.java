/* 文件职责：结构避让内部的各类缓存键与中间记录（包内可见，就近存放）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;

record PredictedCandidateKey(long chunkKey, Holder<StructureSet> structureSet) {
}

class NativeDirectCircuitOpenException extends RuntimeException {
    NativeDirectCircuitOpenException() {
        super("native structure prediction circuit is open", null, false, false);
    }
}

record ChunkRequestKey(String dimension, long seed, long chunkKey, ChunkStatus status) {
}

record RuntimePreloadKey(String dimension, long seed, long chunkKey) {
}

record ManualStartReference(Structure structure, long chunkKey) {
}

record ManualStartBatch(
        List<ManualStartReference> references,
        List<CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>>> futures) {
}

record ObstacleTileKey(String dimension, long seed, int tileX, int tileZ) {
}

record RoutePredictionBounds(StructureConnection connection,
                                     int minChunkX,
                                     int maxChunkX,
                                     int minChunkZ,
                                     int maxChunkZ,
                                     int minTileX,
                                     int maxTileX,
                                     int minTileZ,
                                     int maxTileZ,
                                     long reachSquared) {
}

record RouteStartPlan(StructureConnection connection,
                              Set<PreparedStartKey> starts,
                              Set<Long> coveredCandidateChunks) {
}

record PathCorridorPlan(StructureConnection connection,
                                List<BlockPos> path,
                                Set<Long> pathChunks,
                                LongSet candidateChunks) {
}

record SnapshotPreparation(Map<RouteKey, RouteStartPlan> routePlans,
                                   List<PreparedStartKey> uniqueStarts) {
}
