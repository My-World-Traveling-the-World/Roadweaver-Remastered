/* 文件职责：结构避让内部的各类缓存键与中间记录（包内可见，就近存放）。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.Unit;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.core.registries.Registries;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationStage;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.PlanningUtils;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.search.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.shiroha233.roadweaver.pathlogic.core.Road;
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
