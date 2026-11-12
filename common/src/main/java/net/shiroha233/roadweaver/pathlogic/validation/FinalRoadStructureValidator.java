package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner;

/** Save-time gate that validates the final, span-aware three-dimensional road volume. */
public final class FinalRoadStructureValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private FinalRoadStructureValidator() {
    }

    public static ValidationResult validate(ServerLevel level,
                                            RoadData road,
                                            TerrainSamplingCache cache,
                                            StreamsReflowingCompat.SamplingSession streams) {
        return validate(level, null, road, cache, streams);
    }

    public static ValidationResult validate(ServerLevel level,
                                            StructureConnection connection,
                                            RoadData road,
                                            TerrainSamplingCache cache,
                                            StreamsReflowingCompat.SamplingSession streams) {
        if (!ConfigService.get().structureAvoidanceEnabled()) return ValidationResult.success();
        if (level == null || road == null || cache == null) {
            return ValidationResult.failure("missing-final-validation-input", null);
        }
        StructureAvoidanceService.PlanningSnapshot snapshot = connection == null
                ? StructureAvoidanceService.requirePlanningSnapshot(level, road)
                : StructureAvoidanceService.requirePlanningSnapshot(level, connection);
        prefetchMissingValidationColumns(level, road, cache);
        RoadConstructionVolumePlanner.Context context =
                RoadConstructionVolumePlanner.Context.forWorld(level, cache, streams);
        return validate(snapshot, road, context);
    }

    /**
     * Final structure validation must not discover a cache miss after entering
     * the volume walk. Prefetch the road's actual footprint columns. The CPU
     * backend samples whole chunks into the shared chunk cache, so a column
     * requested here becomes reusable by every later road in the same region
     * (and by the volume walk itself via the chunk-derived sample). Columns
     * are therefore fetched per chunk when a chunk is sufficiently covered,
     * and the sparse remainder is filled column-by-column.
     */
    private static void prefetchMissingValidationColumns(ServerLevel level,
                                                         RoadData road,
                                                         TerrainSamplingCache cache) {
        if (level == null || road == null || cache == null
                || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()) return;
        List<BlockPos> positions = new ArrayList<>();
        positions.addAll(RoadConstructionVolumePlanner.terrainSamplePositions(road));
        for (var segment : road.roadSegmentList()) {
            if (segment == null) continue;
            if (segment.middlePos() != null) positions.add(segment.middlePos());
            if (segment.positions() != null) positions.addAll(segment.positions());
        }
        if (positions.isEmpty()) return;
        AccurateHeightSampler sampler = cache.getAccurateSampler(level);
        Set<Long> known = sampler.cachedSamples(positions).keySet();
        List<BlockPos> missing = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (BlockPos position : positions) {
            if (position == null) continue;
            long key = AccurateHeightSample.key(position.getX(), position.getZ());
            if (!known.contains(key) && seen.add(key)) missing.add(position);
        }
        if (missing.isEmpty()) {
            if (DetailedRoadGenerationStats.routeTiming() != null) {
                DetailedRoadGenerationStats.routeTiming().addMissingColumns(positions.size(), 0);
                DetailedRoadGenerationStats.routeTiming().addColumnSource(positions.size(), 0, 0);
            }
            return;
        }
        long roadId = net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage.computeFingerprint(road);
        LOGGER.info("RoadWeaver final validation filling missing terrain columns: roadId={} totalColumns={} missingColumns={} backend={} device={}",
                roadId, positions.size(), missing.size(), sampler.backendName(), sampler.deviceName());
        if (DetailedRoadGenerationStats.routeTiming() != null) {
            DetailedRoadGenerationStats.routeTiming().addMissingColumns(positions.size(), missing.size());
        }
        // Group the missing columns by chunk. A chunk that needs several
        // columns is fetched whole: the CPU backend caches the full chunk, so
        // the volume walk and every later road in that chunk hit the shared
        // cache instead of resampling thousands of isolated columns. Sparse
        // chunks (a handful of columns) keep the cheap column path. Both paths
        // derive heights from the same NoiseChunk sampler, so the values are
        // identical — this changes caching only, never geometry.
        java.util.Map<Long, List<BlockPos>> byChunk = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Integer> footprintByChunk = new java.util.LinkedHashMap<>();
        List<BlockPos> sparse = new ArrayList<>();
        for (BlockPos position : positions) {
            if (position == null) continue;
            long footprintChunkKey = net.minecraft.world.level.ChunkPos.asLong(
                    Math.floorDiv(position.getX(), 16), Math.floorDiv(position.getZ(), 16));
            footprintByChunk.merge(footprintChunkKey, 1, Integer::sum);
        }
        for (BlockPos position : missing) {
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(
                    Math.floorDiv(position.getX(), 16), Math.floorDiv(position.getZ(), 16));
            byChunk.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(position);
        }
        java.util.Set<Long> wholeChunks = new java.util.LinkedHashSet<>();
        int wholeChunkColumns = 0;
        for (java.util.Map.Entry<Long, List<BlockPos>> entry : byChunk.entrySet()) {
            // 整块预取的判据：缺列 >=4，或"这条路的足迹在这个 chunk 里有一半以上缺列"。
            // 后者覆盖"宽路沿 chunk 边界穿过"的形态：只差 2~3 列也会整块取，
            // 让 volume walk 与后续同区块的路全部命中共享缓存。
            int missingInChunk = entry.getValue().size();
            int footprintInChunk = footprintByChunk.getOrDefault(entry.getKey(), missingInChunk);
            if (missingInChunk >= 4 || missingInChunk * 2 >= footprintInChunk) {
                wholeChunks.add(entry.getKey());
                wholeChunkColumns += missingInChunk;
            } else {
                sparse.addAll(entry.getValue());
            }
        }
        if (!wholeChunks.isEmpty()) {
            sampler.sampleChunks(wholeChunks);
        }
        if (!sparse.isEmpty()) {
            sampler.samplePositions(sparse);
        }
        // 缺列来源：命中缓存（未进 missing）/ 整块预取（按 chunk 一次取整块）/ 逐列采样。
        if (DetailedRoadGenerationStats.routeTiming() != null) {
            DetailedRoadGenerationStats.routeTiming().addColumnSource(
                    positions.size() - missing.size(), wholeChunkColumns, sparse.size());
        }
        if (!wholeChunks.isEmpty()) {
            LOGGER.info("RoadWeaver final validation chunk prefetch: roadId={} totalColumns={} wholeChunks={} coveredColumns={} "
                            + "sparseColumns={} cachedChunks={} cachedColumns={} backend={}",
                    roadId, positions.size(), wholeChunks.size(), wholeChunkColumns, sparse.size(),
                    sampler.cachedChunkCount(), sampler.cachedColumnCount(), sampler.backendName());
        }
    }

    static ValidationResult validate(StructureAvoidanceService.PlanningSnapshot snapshot,
                                     RoadData road,
                                     RoadConstructionVolumePlanner.Context context) {
        if (snapshot == null || snapshot == StructureAvoidanceService.PlanningSnapshot.EMPTY) {
            throw new StructureAvoidanceService.PlanningSnapshotUnavailableException(
                    "Final road validation requires a complete structure snapshot");
        }
        AtomicReference<Collision> collision = new AtomicReference<>();
        boolean completed = RoadConstructionVolumePlanner.visit(road, context, volume -> {
            // Building avoidance is defined by the road body. Terrain
            // shoulders and falling caps are optional clearance helpers and
            // must not turn a passable five-block road into a rejection. Real
            // bridge/template/support volumes remain protected.
            if (!isRoadBodyVolume(volume.kind())) return true;
            StructureAvoidanceService.OwnedStructureBox owned = switch (volume.kind()) {
                case ROAD_FALLING_CAP, BRIDGE_FALLING_CAP -> snapshot.findFallingCapCollision(
                        volume.x(), volume.z(), volume.minY(), volume.maxY());
                default -> snapshot.findCollision(volume.x(), volume.z(), volume.minY(), volume.maxY());
            };
            if (owned == null) return true;
            // Endpoint structures are explicitly allowed to receive this road
            // during planning. Apply the same rule to the final swept-volume
            // check instead of rejecting the route only at save time.
            if (snapshot.endpointOwners().contains(owned.owner())) return true;
            collision.set(new Collision(
                    volume.segmentIndex(), volume.kind(), volume.representativePos(),
                    volume.minY(), volume.maxY(), owned.owner(), owned.box()));
            return false;
        });
        if (!completed && collision.get() == null) {
            return ValidationResult.failure("invalid-final-construction-volume", null);
        }
        return collision.get() == null
                ? ValidationResult.success()
                : ValidationResult.failure("protected-structure-intersection", collision.get());
    }

    /**
     * 0.8.365 (S3): which swept volumes count as the road body for structure
     * avoidance.
     *
     * <p>Falling caps are now validated. They always had a dedicated, more
     * permissive test in the visitor below ({@code findFallingCapCollision}:
     * it only reports a collision for a {@code REMOVABLE} structure voxel and
     * returns {@code null} as soon as it meets a {@code SOLID} one, matching the
     * runtime scan which simply stops at solid rock). That branch was dead
     * because this guard excluded the caps first, so a falling cap that would
     * delete part of a building passed planning and only failed during
     * construction as {@code protected-falling-cap}. Planning and construction
     * now use the same bands: both derive them from
     * {@code AboveColumnClearer.fallingCapStartY/EndY} with the same surface Y
     * and clear height.</p>
     *
     * <p>{@code SHOULDER} deliberately stays excluded: shoulders are not built
     * at all ({@code SegmentPaver.paveSegment} returns before grading them and
     * {@code RoadConstructionSafety.gradeShoulders} has no caller), so a
     * shoulder-only collision can never fail construction.</p>
     */
    private static boolean isRoadBodyVolume(RoadConstructionVolumePlanner.Kind kind) {
        return switch (kind) {
            case ROAD, TUNNEL, TUNNEL_PORTAL, TUNNEL_JUNCTION, BRIDGE_DECK -> true;
            case ROAD_FALLING_CAP, BRIDGE_FALLING_CAP -> true;
            case SHOULDER -> false;
            case BRIDGE_TEMPLATE, BRIDGE_SUPPORT, BRIDGE_PIER -> true;
        };
    }

    /**
     * Retains an otherwise usable route while marking every internal verified
     * building collision as an explicit unpaved station. Final construction
     * checks the complete swept volume, so handling only the first collision
     * can leave an adjacent shoulder or falling-cap collision unprotected.
     */
    public static RoadData retainVerifiedBuildingGaps(ServerLevel level,
                                                      StructureConnection connection,
                                                      RoadData road,
                                                      TerrainSamplingCache cache,
                                                      StreamsReflowingCompat.SamplingSession streams) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().size() < 3) {
            return null;
        }
        RoadData current = road;
        Set<Integer> marked = new HashSet<>();
        for (int attempt = 0; attempt < current.roadSegmentList().size(); attempt++) {
            ValidationResult validation = validate(level, connection, current, cache, streams);
            if (validation.safe()) return current;
            Collision collision = validation.collision();
            int index = collision == null ? -1 : collision.segmentIndex();
            if (index <= 0 || index >= current.roadSegmentList().size() - 1
                    || collision == null
                    || !StructureAvoidanceService.isVerifiedBuildingCollision(
                    level, connection, collision.owner(), collision.position(),
                    collision.minY(), collision.maxY())
                    || !marked.add(index)) {
                return null;
            }
            current = smoothProtectedGapApproaches(withProtectedGapAt(current, index));
            if (!RoadDataValidator.validateFinalRoad(current).valid()) return null;
        }
        return null;
    }

    /**
     * Before a protected gap is accepted, pull its two pavement edges into one
     * continuous profile: both edges are seated onto the lower side and the
     * higher approach descends onto it (prefer-cut — stations are only ever
     * lowered, never filled), so no adjacent ordinary pair exceeds the
     * validator's one-block step. Ramp windows extend 8..48 stations per side
     * (a 40-block delta needs 42, so a 32 cap could never satisfy it). A gap
     * whose descent cannot fit its window is left unchanged with a warning
     * and the validator's existing failure path hands it to the topology
     * fallback. Ordinary continuous segments and bridge/tunnel decisions are
     * untouched; bridge/crossing/tunnel stations anchor the ramp.
     */
    static RoadData smoothProtectedGapApproaches(RoadData road) {
        if (road == null || road.targetY() == null || road.roadSegmentList() == null
                || road.roadSegmentList().size() < 3
                || road.targetY().size() != road.roadSegmentList().size()) {
            return road;
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int n = segments.size();
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < n; i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) {
                indices.putIfAbsent(xzKey(segment.middlePos()), i);
            }
        }
        List<int[]> ranges = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.PROTECTED_GAP) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            ranges.add(new int[]{Math.min(start, end), Math.max(start, end)});
        }
        if (ranges.isEmpty()) return road;
        int[] profile = new int[n];
        for (int i = 0; i < n; i++) profile[i] = road.targetY().get(i);
        boolean[] gapMask = new boolean[n];
        for (int[] range : ranges) {
            for (int i = range[0]; i <= range[1]; i++) gapMask[i] = true;
        }
        // Bridge decks / crossings / tunnels inside a ramp window keep their
        // engineered heights: they anchor the ramp instead of being lowered.
        boolean[] frozen = gapMask.clone();
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() == null) continue;
            if (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING
                    && span.type() != SpanType.TUNNEL
                    && span.type() != SpanType.TUNNEL_JUNCTION) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) frozen[i] = true;
        }
        int stepsBefore = ordinaryHeightSteps(profile, gapMask, 0, n - 1);
        boolean changed = false;
        int affectedStart = Integer.MAX_VALUE;
        int affectedEnd = Integer.MIN_VALUE;
        for (int[] range : ranges) {
            int gapStart = range[0];
            int gapEnd = range[1];
            int left = gapStart - 1;
            int right = gapEnd + 1;
            if (left < 0 || right >= n || gapMask[left] || gapMask[right]) continue;
            int leftY = profile[left];
            int rightY = profile[right];
            int seat = Math.min(leftY, rightY);
            if (Math.max(leftY, rightY) - seat <= 1) continue;
            int descent = Math.max(leftY, rightY) - seat;
            int window = Math.max(8, Math.min(48, descent + 2));
            // Seat the whole unpaved gap plus both edges; the ramps then pull
            // the surrounding ordinary stations down onto the seat at most one
            // block per station (prefer-cut: nothing is ever raised).
            for (int i = gapStart; i <= gapEnd; i++) profile[i] = seat;
            profile[left] = seat;
            profile[right] = seat;
            profile = rampOntoSeat(profile, left - window, left, seat, frozen);
            profile = rampOntoSeat(profile, right, right + window, seat, frozen);
            affectedStart = Math.min(affectedStart, left - window);
            affectedEnd = Math.max(affectedEnd, right + window);
            changed = true;
        }
        if (!changed) return road;
        // Acceptance: every processed gap must have its two pavement edges
        // seated onto one height, and the affected region (ramp windows plus
        // one boundary pair) must be free of >1-block ordinary steps. A
        // descent that could not fit its ramp window pushes its remaining
        // cliff just past the window — this check catches exactly that and
        // keeps the original profile, so the caller's failure path hands the
        // route to the topology fallback instead of persisting a cliff.
        int stepsAfter = ordinaryHeightSteps(profile, gapMask, affectedStart - 1, affectedEnd + 1);
        boolean allSeated = true;
        for (int[] range : ranges) {
            int left = range[0] - 1;
            int right = range[1] + 1;
            if (left >= 0 && right < n && Math.abs(profile[left] - profile[right]) > 1) {
                allSeated = false;
                break;
            }
        }
        if (!allSeated || stepsAfter != 0) {
            LOGGER.warn("RoadWeaver protected-gap smoothing could not flatten every gap "
                            + "(descent does not fit the ramp window); keeping the original profile "
                            + "heightSteps={} -> affectedSteps={}",
                    stepsBefore, stepsAfter);
            return road;
        }
        List<Integer> newTargetY = new ArrayList<>(n);
        for (int value : profile) newTargetY.add(value);
        LOGGER.info("RoadWeaver protected-gap approaches smoothed gaps={} heightSteps={} -> {}",
                ranges.size(), stepsBefore, stepsAfter);
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), road.spans(), List.copyOf(newTargetY),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    /**
     * Forces [from..to] into a ≤1-block-per-station ramp that meets
     * {@code seat} at the window edge adjoining the gap. Two clamping passes
     * (outward from the seat, then back) leave every adjacent difference ≤ 1
     * while only ever lowering a station: the high side descends onto the
     * seat, the low side keeps its profile. Frozen stations (gap, bridge
     * decks, crossings, tunnels) are anchors and are never modified.
     */
    private static int[] rampOntoSeat(int[] profile, int from, int to, int seat,
                                      boolean[] frozen) {
        int start = Math.max(0, from);
        int end = Math.min(profile.length - 1, to);
        if (end - start < 1) return profile;
        for (int i = end - 1; i >= start; i--) {
            if (frozen[i]) continue;
            profile[i] = Math.min(profile[i], profile[i + 1] + 1);
        }
        for (int i = start + 1; i <= end; i++) {
            if (frozen[i]) continue;
            profile[i] = Math.min(profile[i], profile[i - 1] + 1);
        }
        return profile;
    }

    /** Same adjacent-pair rule as RoadDataValidator (gap-adjacent pairs exempt),
     * evaluated over [from..to] clamped to the profile. */
    private static int ordinaryHeightSteps(int[] profile, boolean[] gapMask, int from, int to) {
        int steps = 0;
        int start = Math.max(1, from);
        int end = Math.min(profile.length - 1, to);
        for (int i = start; i <= end; i++) {
            if (Math.abs(profile[i] - profile[i - 1]) > 1 && !gapMask[i] && !gapMask[i - 1]) steps++;
        }
        return steps;
    }

    private static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static RoadData withProtectedGapAt(RoadData road, int index) {
        if (road == null || road.roadSegmentList() == null
                || index < 0 || index >= road.roadSegmentList().size()) return road;
        BlockPos point = road.roadSegmentList().get(index).middlePos();
        if (point == null) return road;
        List<RoadSpan> spans = new ArrayList<>(road.spans() == null ? List.of() : road.spans());
        boolean exists = spans.stream().anyMatch(span -> span != null
                && span.type() == SpanType.PROTECTED_GAP
                && point.equals(span.start()) && point.equals(span.end()));
        if (!exists) spans.add(new RoadSpan(point, point, SpanType.PROTECTED_GAP));
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), List.copyOf(spans), road.targetY(),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    public record Collision(int segmentIndex,
                            RoadConstructionVolumePlanner.Kind kind,
                            BlockPos position,
                            int minY,
                            int maxY,
                            StructureAvoidanceService.StructureOwnerKey owner,
                            BoundingBox componentBox) {
    }

    public record ValidationResult(boolean safe, String reason, Collision collision) {
        public static ValidationResult success() {
            return new ValidationResult(true, "ok", null);
        }

        private static ValidationResult failure(String reason, Collision collision) {
            return new ValidationResult(false, reason, collision);
        }
    }
}
