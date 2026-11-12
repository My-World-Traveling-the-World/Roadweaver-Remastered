package net.shiroha233.roadweaver.pathlogic.grade;

import net.shiroha233.roadweaver.config.ModConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.shiroha233.roadweaver.compat.GreatWallRoadGuard;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;
import net.shiroha233.roadweaver.decoration.system.PlacementRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;
import net.shiroha233.roadweaver.pathlogic.core.Road;

/**
 * Final-world construction rules used by the RoadWeaver core.  These rules
 * intentionally run only against the worldgen region currently being placed:
 * they do not request chunks and therefore cannot stall C2ME workers.
 */
public final class RoadConstructionSafety {
    public static final int MAX_CUT_DEPTH = 5;
    /**
     * 路堤（向上填）的单站上限。
     *
     * <p>A continuous open road may absorb a short 6-8 block hillside pass.</p>
     *
     * <p>0.9.087 起这个值<b>只管填方</b>。挖方另有一条更宽的线
     * {@link #MAX_LOCAL_CENTER_CUT_DEPTH} —— 理由见那里的注释。</p>
     */
    public static final int MAX_LOCAL_CENTER_GRADE_DEPTH = 8;
    /**
     * 路堑（向下挖）的单站上限。**0.9.087 新增：8 → 32。**
     *
     * <p><b>为什么挖方要比填方宽得多</b>：这两种土方在观感和工程上是相反的。</p>
     * <ul>
     *   <li><b>填 8 格以上 = 高堤</b>：路面悬在填土堆上，两侧是陡坡，视觉上非常突兀，
     *       而且一旦有一列没填实就是一个洞。所以填方必须守在 8 格。</li>
     *   <li><b>挖 19~32 格 = 路堑</b>：山体上开一道槽，路面本身仍是平的、连续的，
     *       两侧是岩壁 —— 这正是现实里山区公路的标准做法（垭口/明堑）。</li>
     * </ul>
     *
     * <p><b>真机依据</b>（存档 2026-11-11，半径 128 那局）：5 条 initial connection 失败，
     * 其中 2 条是真的「路要穿过比它高 19 / 25 格的山脊」——
     * {@code seg=816 cut=19 targetY=388 terrainY=407}、
     * {@code seg=2200 cut=25 targetY=71 terrainY=96}。
     * 这两条既不是参数错，也不是修复失败，而是**旧上限 8 让它们不可能被建造**，
     * 于是被当成「canyon」整条丢弃。放开到 32 后它们能作为路堑建出来。</p>
     *
     * <p>上界取 32 的依据：{@link #MAX_OPEN_CROSS_SECTION_CUT_DEPTH} 早就允许
     * 侧列挖到 64 格，说明施工链路本身扛得住深挖；中心站限制一直是更严的那一条。
     * 32 能覆盖真机观测到的 19/25，同时仍挡住真正的「把山挖穿」。</p>
     */
    public static final int MAX_LOCAL_CENTER_CUT_DEPTH = 32;
    public static final int MAX_OPEN_CROSS_SECTION_CUT_DEPTH = 64;
    public static final int MAX_FILL_DEPTH = 2;
    /**
     * Lateral fill in one road cross-section may extend the short grade
     * independently of the longitudinal centre fill. The longitudinal route
     * grade keeps a tight fill budget, while a single side column can absorb a
     * normal 3..5 block cross-slope without failing the whole station.
     */
    private static final int MAX_CROSS_SECTION_FILL_DEPTH = 5;
    private static final int MAX_SHOULDER_DEPTH = 5;
    private static final int WATERCOURSE_HALF_WIDTH = 3;
    private static final int RUNTIME_BRIDGE_CHUNK_RADIUS = 5;
    private static final int CULVERT_MAX_WATER_DEPTH = 2;
    private static final int CULVERT_MAX_SEGMENTS = 2;
    private static final int CULVERT_MAX_OVERHEAD = 6;
    private static final int MAX_WATER_SCAN_DEPTH = 128;
    private static final int MAX_WATER_ROOF_THICKNESS = 8;
    private static final ConcurrentHashMap<RuntimeBridgeKey, List<RuntimeRange>> RUNTIME_BRIDGES =
            new ConcurrentHashMap<>();

    private RoadConstructionSafety() {
    }

    @FunctionalInterface
    public interface SurfaceWaterLookup {
        boolean hasSurfaceWater(int x, int z);
    }

    public record GradePreparation(boolean ready, String reason, BlockPos position, int depth) {
        private static GradePreparation success() {
            return new GradePreparation(true, "ready", null, 0);
        }

        private static GradePreparation failure(String reason, BlockPos position, int depth) {
            return new GradePreparation(false, reason, position, Math.max(0, depth));
        }
    }

    /** Returns the build Y immediately above solid terrain, never a canopy. */
    public static int terrainHeight(WorldGenLevel world, int x, int z) {
        int minY = world.getMinBuildHeight();
        int top = Math.max(
                world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z),
                world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)) - 1;
        if (top < minY) return minY;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        if (!world.getBlockState(cursor).getFluidState().isEmpty()) return top + 1;
        for (int y = top, lower = Math.max(minY, top - 128); y >= lower; y--) {
            cursor.setY(y);
            BlockState state = world.getBlockState(cursor);
            if (isGround(state)) return y + 1;
        }
        return Math.max(minY, top + 1);
    }

    /**
     * Returns the build Y immediately above <b>solid</b> ground - water does not count.
     *
     * <p>{@link #terrainHeight} answers "where would a walker stand", so a water column answers the water
     * surface plus one. That is right for a road deck and wrong for anything that asks "how much rock is
     * above me": tunnel overburden used it, so an undersea tunnel counted the whole ocean as rock. The roof
     * of such a tunnel ended up flush with the seabed - one stone-brick layer with the sea directly on top -
     * while the log still reported {@code overburden=14..20}, because 14..20 was the water depth.
     *
     * <p>Reads the ocean-floor heightmap (the topmost non-air, non-fluid block) and then walks down to the
     * first ground block, so an aquifer or a kelp column cannot be mistaken for a surface either.
     */
    public static int rockSurfaceHeight(WorldGenLevel world, int x, int z) {
        int minY = world.getMinBuildHeight();
        int top = Math.max(
                world.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z),
                world.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)) - 1;
        if (top < minY) return minY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top, lower = Math.max(minY, top - 128); y >= lower; y--) {
            cursor.setY(y);
            if (isGround(world.getBlockState(cursor))) return y + 1;
        }
        return Math.max(minY, top + 1);
    }

    public static int[] sampleTerrain(WorldGenLevel world, List<BlockPos> positions) {
        int[] result = new int[positions == null ? 0 : positions.size()];
        if (positions == null) return result;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            result[i] = pos == null ? world.getMinBuildHeight() : terrainHeight(world, pos.getX(), pos.getZ());
        }
        return result;
    }


    /**
     * Resolves a buildable surface height for an ordinary road column. Tunnel
     * sections deliberately bypass this clamp after their complete-span
     * preflight succeeds.
     */
    public static int clampTargetHeight(WorldGenLevel world, int x, int z, int plannedY) {
        int terrainY = terrainHeight(world, x, z);
        return Math.max(terrainY - MAX_CUT_DEPTH, Math.min(terrainY + MAX_FILL_DEPTH, plannedY));
    }

    public static void clampProfileToTerrain(int[] profile, int[] terrain) {
        if (profile == null || terrain == null || profile.length != terrain.length) return;
        for (int i = 0; i < profile.length; i++) {
            profile[i] = Math.max(terrain[i] - MAX_CUT_DEPTH,
                    Math.min(terrain[i] + MAX_FILL_DEPTH, profile[i]));
        }
    }

    /**
     * Keeps one road station on its persisted route-level elevation. Terrain
     * bounds may shape the shoulder, but must never rewrite the longitudinal
     * profile one chunk at a time. Doing so recreated cliff-sized steps after
     * the route planner had already produced a continuous grade.
     */
    public static void resolveRoadCrossSection(int[] profile, int[] terrain) {
        resolveRoadCrossSection(profile, terrain, -1);
    }

    /**
     * Flattens the cross-section to one height, preferring the column nearest the centre line.
     *
     * <p>{@code centreIndex} &lt; 0 keeps the old median. Callers that know the station's {@code middlePos}
     * should pass it: the median is over cells whose projections onto the centre line differ, so on a slope
     * - and most visibly on a diagonal - neighbouring stations take turns being one block higher, and the
     * plate comes out as a 0,1,0,1 staircase instead of a ramp. The centre column is the one the planned
     * grade was computed for.
     */
    public static void resolveRoadCrossSection(int[] profile, int[] terrain, int centreIndex) {
        if (profile == null || terrain == null || profile.length == 0 || profile.length != terrain.length) return;
        int planned = centreIndex >= 0 && centreIndex < profile.length
                ? profile[centreIndex]
                : representativeHeight(profile);
        Arrays.fill(profile, planned);
    }

    public static int representativeHeight(int[] values) {
        if (values == null || values.length == 0) return 0;
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0
                ? (int) Math.round((sorted[middle - 1] + sorted[middle]) / 2.0D)
                : sorted[middle];
    }

    /** Applies a bounded cut/fill before the road material is placed. */
    public static GradePreparation prepareRoadGrade(WorldGenLevel world, RoadData road,
            BlockPos center, List<BlockPos> positions, int[] profile, int[] terrain) {
        if (!matching(positions, profile, terrain)) {
            return GradePreparation.failure("invalid-cross-section", null, 0);
        }
        int centerIndex = nearestColumnIndex(positions, center);
        if (centerIndex < 0) {
            return GradePreparation.failure("missing-center-column", center, 0);
        }
        // Wall columns are a construction veto for their own cut/fill only. They must not change the
        // shared centre depth: doing that used to turn an allowed 6..8 block side fill into
        // fill-too-deep and reject the whole station (0.8.461 regression).
        boolean[] wallColumn = new boolean[positions.size()];
        if (GreatWallRoadGuard.isAvailable()) {
            for (int i = 0; i < positions.size(); i++) {
                BlockPos column = positions.get(i);
                if (column == null) continue;
                wallColumn[i] = GreatWallRoadGuard.blocksFor(GreatWallRoadGuard.Ch.CUT,
                        column.getX(), profile[i], column.getZ());
            }
        }
        int centerCutDepth = Math.max(0, terrain[centerIndex] - profile[centerIndex]);
        int centerFillDepth = Math.max(0, profile[centerIndex] - terrain[centerIndex]);
        // The route planner has already selected the longitudinal grade. A
        // short six-to-eight-block cut/fill on an otherwise ordinary surface
        // road is a construction pass, not a route failure. The old neighbor
        // target check rejected valid continuous slopes whenever the sampled
        // station spacing or a repaired profile differed by one extra block.
        boolean extendedCenterGrade = isExtendedCenterGrade(centerCutDepth, centerFillDepth);
        // A wall centre is a construction boundary, not a route failure. Its own depth must not
        // make an ordinary side column fail the whole station.
        int stationCenterCutDepth = wallColumn[centerIndex] ? 0 : centerCutDepth;
        int stationCenterFillDepth = wallColumn[centerIndex] ? 0 : centerFillDepth;
        if (!wallColumn[centerIndex]
                && centerCutDepth > MAX_CUT_DEPTH && centerCutDepth > MAX_LOCAL_CENTER_CUT_DEPTH) {
            BlockPos centerColumn = positions.get(centerIndex);
            return GradePreparation.failure("cut-too-deep-center",
                    new BlockPos(centerColumn.getX(), profile[centerIndex], centerColumn.getZ()), centerCutDepth);
        }
        if (!wallColumn[centerIndex] && centerFillDepth > MAX_LOCAL_CENTER_GRADE_DEPTH) {
            BlockPos centerColumn = positions.get(centerIndex);
            return GradePreparation.failure("fill-too-deep-center",
                    new BlockPos(centerColumn.getX(), profile[centerIndex], centerColumn.getZ()), centerFillDepth);
        }
        for (int i = 0; i < positions.size(); i++) {
            BlockPos column = positions.get(i);
            if (column == null) return GradePreparation.failure("missing-column", null, 0);
            // The wall owns this column. Leave it completely alone: no cut, no fill, no rejection.
            if (wallColumn[i]) continue;
            // Water and culvert columns keep their existing crossing behavior.
            if (hasWatercourseNearby(world, column)) continue;
            int target = profile[i];
            int original = terrain[i];
            BlockPos construction = new BlockPos(column.getX(), target, column.getZ());
            BlockPos roadBlock = construction.below();
            // A decoded structure block is a construction gap, not a route
            // failure.  Leave the whole column untouched and let neighboring
            // columns continue paving.
            if (StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, roadBlock, road)
                    || StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, construction, road)) {
                continue;
            }
            if (isProtectedPreparedBlock(world, roadBlock, road)) {
                return GradePreparation.failure("protected-roadbed", roadBlock, 0);
            }
            BlockState predictedRoadbed = world.getBlockState(roadBlock);
            if (original < target && predictedRoadbed.isAir()) {
                predictedRoadbed = Blocks.DIRT.defaultBlockState();
            }
            if (!PlacementRules.placeAllowedCheck(predictedRoadbed.getBlock())) {
                return GradePreparation.failure("placement-rule-rejected-roadbed", roadBlock, 0);
            }
            if (original > target) {
                int depth = original - target;
                if ((!extendedCenterGrade && stationCenterCutDepth > MAX_CUT_DEPTH)
                        || depth > MAX_OPEN_CROSS_SECTION_CUT_DEPTH) {
                    return GradePreparation.failure("cross-section-cut-too-deep", construction, depth);
                }
                for (int y = target; y < original; y++) {
                    BlockPos volume = new BlockPos(column.getX(), y, column.getZ());
                    if (StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, volume, road)) {
                        continue;
                    }
                    if (isProtectedPreparedBlock(world, volume, road)) {
                        return GradePreparation.failure("protected-cut-volume", volume, depth);
                    }
                }
            } else if (original < target) {
                int depth = target - original;
                if (!isCrossSectionFillAllowed(stationCenterCutDepth, stationCenterFillDepth, depth)) {
                    return GradePreparation.failure("fill-too-deep", construction, depth);
                }
                for (int y = original; y < target; y++) {
                    BlockPos volume = new BlockPos(column.getX(), y, column.getZ());
                    if (StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, volume, road)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(volume);
                    if (isWaterState(state)) {
                        return GradePreparation.failure("water-in-fill-volume", volume, depth);
                    }
                    if (isProtectedPreparedBlock(world, volume, road)) {
                        return GradePreparation.failure("protected-fill-volume", volume, depth);
                    }
                }
            }
        }
        for (int i = 0; i < positions.size(); i++) {
            BlockPos column = positions.get(i);
            if (column == null || hasWatercourseNearby(world, column) || wallColumn[i]) continue;
            int target = profile[i];
            int original = terrain[i];
            BlockPos construction = new BlockPos(column.getX(), target, column.getZ());
            // Structure avoidance is resolved during planning.  Repeating the
            // asynchronous lookup here used to turn an approved route into
            // single-column holes while the cache was still warming up.
            if (original > target) {
                clearColumn(world, road, column, target, original - 1, MAX_OPEN_CROSS_SECTION_CUT_DEPTH);
            } else if (original < target) {
                int maxFillDepth = extendedCenterGrade
                        ? MAX_LOCAL_CENTER_GRADE_DEPTH : MAX_CROSS_SECTION_FILL_DEPTH;
                fillColumn(world, road, column, original, target, maxFillDepth, Blocks.DIRT.defaultBlockState());
            }
        }
        return GradePreparation.success();
    }

    /**
     * The route center decides whether an ordinary segment is a surface road
     * or should have been planned as a tunnel. Once the center is valid, the
     * rest of the road width may cut farther into a side slope so one steep
     * cross-section cannot leave terrain columns inside an otherwise paved
     * station.
     */
    static boolean isRoadFootprintCutPermitted(int centerCutDepth, int columnCutDepth) {
        return centerCutDepth <= MAX_CUT_DEPTH
                && columnCutDepth <= MAX_OPEN_CROSS_SECTION_CUT_DEPTH;
    }

    static boolean isExtendedCenterGrade(int centerCutDepth, int centerFillDepth) {
        return (centerCutDepth > MAX_CUT_DEPTH && centerCutDepth <= MAX_LOCAL_CENTER_CUT_DEPTH)
                || (centerFillDepth > MAX_FILL_DEPTH && centerFillDepth <= MAX_LOCAL_CENTER_GRADE_DEPTH);
    }

    static boolean isCrossSectionFillAllowed(int centerCutDepth, int centerFillDepth, int columnFillDepth) {
        boolean extendedCenterGrade = isExtendedCenterGrade(centerCutDepth, centerFillDepth);
        return columnFillDepth <= MAX_CROSS_SECTION_FILL_DEPTH
                || (extendedCenterGrade && columnFillDepth <= MAX_LOCAL_CENTER_GRADE_DEPTH);
    }

    /** {@return the column index nearest {@code center}, or -1} (package-visible: the paver uses it too) */
    public static int nearestColumnIndex(List<BlockPos> positions, BlockPos center) {
        if (positions == null || positions.isEmpty() || center == null) return -1;
        int nearest = -1;
        long nearestDistance = Long.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos position = positions.get(i);
            if (position == null) continue;
            long dx = (long) position.getX() - center.getX();
            long dz = (long) position.getZ() - center.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = i;
                if (distance == 0L) break;
            }
        }
        return nearest;
    }

    /** Builds a compact 1:1 cut/fill shoulder without blocking streams. */
    public static void gradeShoulders(WorldGenLevel world, RoadData road,
                                      List<BlockPos> roadPositions, int[] profile,
                                      int segmentIndex, List<BlockPos> centers) {
        if (roadPositions == null || profile == null || roadPositions.size() != profile.length || centers.size() < 2) {
            return;
        }
        int index = Math.max(0, Math.min(segmentIndex, centers.size() - 1));
        BlockPos before = centers.get(Math.max(0, index - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
        if (before == null || after == null) return;
        int dx = Integer.compare(after.getX(), before.getX());
        int dz = Integer.compare(after.getZ(), before.getZ());
        if (dx == 0 && dz == 0) return;
        gradeShoulderSide(world, road, roadPositions, profile, -dz, dx);
        gradeShoulderSide(world, road, roadPositions, profile, dz, -dx);
    }

    /**
     * Streams Reflowing may change final water after the route was planned.
     * Promote the whole final crossing to a bridge range before any column is
     * paved, instead of filling one side of a riverbed.
     */
    public static BridgeRangeCalculator.RangeResult promoteRuntimeBridges(WorldGenLevel world,
            ChunkPos processingChunk, RoadData road, List<BlockPos> centers,
            BridgeRangeCalculator.RangeResult original) {
        if (world == null || processingChunk == null || road == null
                || centers == null || centers.isEmpty() || original == null) {
            return original;
        }
        // Streams exposes its complete river plan before chunk decoration.
        // PathSpanExtractor has already persisted those crossings, so growing
        // ranges again from whichever chunk happens to generate first would
        // make bridge endpoints and template coordinates nondeterministic.
        if (StreamsReflowingCompat.isAvailable()) return original;
        boolean[] bridge = copyMask(original.isBridge(), centers.size());
        boolean[] skip = copyMask(original.skipSegments(), centers.size());
        List<int[]> ranges = copyRanges(original.mergedRanges());
        RuntimeBridgeKey key = runtimeBridgeKey(world, road, centers);
        List<RuntimeRange> detectedNow = new ArrayList<>();
        for (int i = 0; i < centers.size(); ) {
            // A bridge already established at planning time must not be re-probed and re-grown by
            // whichever chunk generates first: that made its range (and the template seed derived
            // from its length) depend on chunk generation order, so the same bridge could pick
            // different templates in different chunks. Keep the planned bridge as-is.
            if (original.isBridge()[i]) {
                i++;
                continue;
            }
            if (!inWindow(processingChunk, centers.get(i)) || !hasWatercourseAcrossRoad(world, centers, i)) {
                i++;
                continue;
            }
            int start = i;
            while (i + 1 < centers.size() && inWindow(processingChunk, centers.get(i + 1))
                    && hasWatercourseAcrossRoad(world, centers, i + 1)) i++;
            int end = i;
            if (isShallowCulvertCrossing(world, centers, start, end)) {
                // A narrow, shallow stream stays open beneath a short raised
                // road deck.  Do not turn it into a bridge or fill its bed.
                i++;
                continue;
            }
            int waterY = waterSurfaceY(world, centers.get((start + end) / 2));
            RuntimeRange stable = expandRuntimeBridgeRange(centers, start, end, waterY);
            detectedNow.add(stable);
            i++;
        }
        List<RuntimeRange> stableRanges = RUNTIME_BRIDGES.compute(key, (ignored, existing) -> {
            List<RuntimeRange> combined = new ArrayList<>(existing == null ? List.of() : existing);
            combined.addAll(detectedNow);
            List<RuntimeRange> merged = mergeRuntimeRanges(combined);
            return merged.isEmpty() ? null : merged;
        });
        if (stableRanges == null) stableRanges = List.of();
        for (RuntimeRange range : stableRanges) ranges = mergeRange(ranges, range.start(), range.end());
        for (int[] range : ranges) {
            for (int i = Math.max(0, range[0]); i <= Math.min(centers.size() - 1, range[1]); i++) {
                bridge[i] = true;
                skip[i] = false;
            }
        }
        return new BridgeRangeCalculator.RangeResult(bridge, ranges, skip);
    }

    static int[] expandRuntimeBridgeRangeForTest(List<BlockPos> centers, int start, int end, int waterY) {
        RuntimeRange range = expandRuntimeBridgeRange(centers, start, end, waterY);
        return new int[]{range.start(), range.end()};
    }

    private static RuntimeRange expandRuntimeBridgeRange(List<BlockPos> centers, int start, int end, int waterY) {
        int safeStart = Math.max(0, Math.min(start, centers.size() - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, centers.size() - 1));
        int[] terrain = centers.stream().mapToInt(BlockPos::getY).toArray();
        int minimumBankY = waterY < Integer.MIN_VALUE / 2 ? Integer.MIN_VALUE : waterY + 1;
        int[] stable = net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile
                .findStableBankRange(terrain, safeStart, safeEnd, minimumBankY);
        return new RuntimeRange(stable[0], stable[1]);
    }

    private static List<RuntimeRange> mergeRuntimeRanges(List<RuntimeRange> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<RuntimeRange> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingInt(RuntimeRange::start));
        List<RuntimeRange> merged = new ArrayList<>();
        for (RuntimeRange range : sorted) {
            if (merged.isEmpty() || range.start() > merged.getLast().end() + 1) merged.add(range);
            else {
                RuntimeRange previous = merged.removeLast();
                merged.add(new RuntimeRange(previous.start(), Math.max(previous.end(), range.end())));
            }
        }
        return List.copyOf(merged);
    }

    private static RuntimeBridgeKey runtimeBridgeKey(WorldGenLevel world, RoadData road, List<BlockPos> centers) {
        long first = road.hasOwnerPair() ? road.ownerA2dKey() : centers.getFirst().asLong();
        long second = road.hasOwnerPair() ? road.ownerB2dKey() : centers.getLast().asLong();
        if (first > second) { long swap = first; first = second; second = swap; }
        return new RuntimeBridgeKey(world.getLevel().dimension().location().toString(), first, second,
                centers.getFirst().asLong(), centers.getLast().asLong(), centers.size());
    }

    public static void clearRuntimeBridges() {
        RUNTIME_BRIDGES.clear();
    }

    /** A construction block must not replace an existing protected structure/tree block. */
    public static boolean isProtectedColumn(WorldGenLevel world, BlockPos pos) {
        if (pos == null) return true;
        if (HugeTreeReservation.isReserved(world.getLevel(), pos)) return true;
        if (isSanctuary(world, new ChunkPos(pos))) return true;
        for (int y = pos.getY() - 1; y <= pos.getY() + 5; y++) {
            if (isProtectedState(world.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())))) return true;
        }
        return false;
    }

    public static boolean isProtectedConstructionColumn(WorldGenLevel world, BlockPos pos, RoadData road) {
        return isProtectedColumn(world, pos) || StructureAvoidanceService.shouldAvoidConstruction(world, pos, road);
    }

    /** Exact bridge/template protection without the legacy vertical structure band. */
    public static boolean isProtectedExactConstructionBlock(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (world == null || pos == null) return true;
        return HugeTreeReservation.isReserved(world.getLevel(), pos)
                || isSanctuary(world, new ChunkPos(pos))
                || isProtectedState(world.getBlockState(pos))
                || StructureAvoidanceService.shouldAvoidPreparedExact(world, pos, road);
    }

    /** Exact block-volume protection using only the planning snapshot. */
    public static boolean isProtectedPreparedBlock(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (world == null || pos == null) return true;
        return isProtectedPreparedLocation(world, pos, road) || isProtectedState(world.getBlockState(pos));
    }

    /** Planning-owned spatial protection without classifying the current block state. */
    public static boolean isProtectedPreparedLocation(WorldGenLevel world, BlockPos pos, RoadData road) {
        if (world == null || pos == null) return true;
        return HugeTreeReservation.isReserved(world.getLevel(), pos)
                || isSanctuary(world, new ChunkPos(pos))
                || StructureAvoidanceService.shouldAvoidPreparedExact(world, pos, road);
    }

    /**
     * Hard excavation protection for one tunnel volume (bore, wall or roadbed).
     * The huge-tree reservation is two-dimensional, but trunk blocks always sit
     * at or above the terrain surface build Y of their column, so a volume
     * below that surface can only contain ordinary terrain. Consulting the
     * reservation there rejected deep mountain bores whose actual blocks were
     * plain stone and stuck the whole tunnel in a construction-retry loop.
     * Confirmed structure template blocks, sanctuaries and hard block states
     * (bedrock, block entities, FEATURES_CANNOT_REPLACE) still block at every
     * depth; {@code columnTerrainY} is the already-sampled surface build Y of
     * the volume's column (pass the surface of a filled portal column so the
     * trunk reservation keeps applying above ground).
     */
    public static boolean isProtectedTunnelVolume(WorldGenLevel world, BlockPos pos, RoadData road,
                                                  BlockState state, int columnTerrainY) {
        if (world == null || pos == null) return true;
        if (pos.getY() >= columnTerrainY && HugeTreeReservation.isReserved(world.getLevel(), pos)) return true;
        if (isSanctuary(world, new ChunkPos(pos))) return true;
        if (StructureAvoidanceService.shouldAvoidPreparedExact(world, pos, road)) return true;
        return isProtectedState(state);
    }

    /**
     * 洞身清障专用：**不重复判结构避让**（0.9.080）。
     *
     * <p>隧道 span 是规划期的产物 —— `PathSpanExtractor` 判定隧道时路线本身已经过
     * `shouldAvoidPlanning` 的三维结构绕行，`Road` 构造阶段还会用 `protectedGapSpans`
     * 把真正压到建筑的站标成 PROTECTED_GAP。也就是说「洞身会不会碰到村庄」在规划期
     * 已经定论了；建造期再问一次 `shouldAvoidPreparedExact` 只会产生一个后果：
     * 清障循环在洞身中间 break，该列保留地形、上面还盖着顶板
     * ⇒ 玩家看到的「隧道里断断续续的柱子」。</p>
     *
     * <p>仍然保留的硬保护（规划期无法预知的运行时事实）：巨树保留区、圣域、
     * 以及 isProtectedState（基岩 / 方块实体 / FEATURES_CANNOT_REPLACE）——
     * 挖掉箱子、告示牌、基岩没有正当理由。</p>
     */
    public static boolean isProtectedTunnelVolumeWithoutStructures(WorldGenLevel world, BlockPos pos,
                                                                   int columnTerrainY) {
        if (world == null || pos == null) return true;
        if (pos.getY() >= columnTerrainY && HugeTreeReservation.isReserved(world.getLevel(), pos)) return true;
        if (isSanctuary(world, new ChunkPos(pos))) return true;
        return isProtectedState(world.getBlockState(pos));
    }


    /**
     * An external road junction may enter the endpoint structure owned by the
     * other road, but it must not weaken huge-tree, sanctuary, block-entity or
     * other hard construction protection.
     */
    public static boolean isClearableExternalJunctionStructureBlock(
            WorldGenLevel world, BlockPos pos, RoadData road) {
        if (world == null || pos == null
                || HugeTreeReservation.isReserved(world.getLevel(), pos)
                || isSanctuary(world, new ChunkPos(pos))
                || isProtectedState(world.getBlockState(pos))) {
            return false;
        }
        return StructureAvoidanceService.shouldAvoidPreparedExact(world, pos, road);
    }

    /** Final generated water state; safe for bridge and retaining-wall construction checks. */
    public static boolean isWatercourse(WorldGenLevel world, BlockPos pos) {
        return pos != null && hasWatercourseNearby(world, pos);
    }

    /**
     * Resolves the bridge walking surface from the final water blocks visible
     * to the current worldgen region. The result depends only on this route
     * segment, so a bridge does not change height when another chunk later
     * discovers a longer part of the same crossing.
     */
    public static int bridgeDeckSurfaceY(WorldGenLevel world, List<BlockPos> centers,
                                         int index, int clearance, int fallback) {
        if (world == null || centers == null || index < 0 || index >= centers.size()) return fallback;
        BlockPos center = centers.get(index);
        if (center == null) return fallback;

        int highestWater = waterSurfaceY(world, center);
        BlockPos before = centers.get(Math.max(0, index - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
        if (before != null && after != null) {
            int nx = -Integer.compare(after.getZ(), before.getZ());
            int nz = Integer.compare(after.getX(), before.getX());
            for (int offset = 1; offset <= WATERCOURSE_HALF_WIDTH; offset++) {
                highestWater = Math.max(highestWater,
                        waterSurfaceY(world, center.offset(nx * offset, 0, nz * offset)));
                highestWater = Math.max(highestWater,
                        waterSurfaceY(world, center.offset(-nx * offset, 0, -nz * offset)));
            }
        }
        return highestWater >= world.getMinBuildHeight()
                ? Math.max(fallback, highestWater + Math.max(1, clearance))
                : fallback;
    }

    /**
     * Raises one narrow, shallow crossing into a covered water channel.  The
     * existing water blocks are left untouched, so Streams Reflowing keeps
     * its final flow instead of being replaced by road fill.
     */
    public static void prepareCulvertDeck(WorldGenLevel world, List<BlockPos> positions, int[] profile,
                                          int segmentIndex, List<BlockPos> centers) {
        if (world == null || positions == null || positions.isEmpty() || profile == null
                || profile.length != positions.size() || !isShallowCulvertCrossing(world, centers, segmentIndex, segmentIndex)) {
            return;
        }
        int deck = Integer.MIN_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            deck = Math.max(deck, profile[i]);
            int waterY = waterSurfaceY(world, pos);
            if (waterY >= world.getMinBuildHeight()) deck = Math.max(deck, waterY + 2);
        }
        if (deck == Integer.MIN_VALUE) return;
        for (int i = 0; i < profile.length; i++) profile[i] = deck;
    }

    private static void gradeShoulderSide(WorldGenLevel world, RoadData road,
            List<BlockPos> positions, int[] profile, int nx, int nz) {
        int minProjection = Integer.MAX_VALUE;
        int maxProjection = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            if (pos == null) continue;
            int projection = pos.getX() * nx + pos.getZ() * nz;
            minProjection = Math.min(minProjection, projection);
            maxProjection = Math.max(maxProjection, projection);
        }
        if (maxProjection == Integer.MIN_VALUE) return;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos edge = positions.get(i);
            if (edge == null || edge.getX() * nx + edge.getZ() * nz != maxProjection) continue;
            int roadY = profile[i];
            for (int width = 1; width <= MAX_SHOULDER_DEPTH; width++) {
                BlockPos shoulder = edge.offset(nx * width, 0, nz * width);
                if (!visited.add(shoulder.asLong()) || hasWatercourseNearby(world, shoulder)) continue;
                int terrain = terrainHeight(world, shoulder.getX(), shoulder.getZ());
                if (terrain == roadY) continue;
                int target = terrain < roadY ? Math.max(terrain, roadY - width) : Math.min(terrain, roadY + width);
                if (isProtectedColumn(world,
                        new BlockPos(shoulder.getX(), target, shoulder.getZ()))) continue;
                if (terrain > target) {
                    clearColumn(world, road, shoulder, target, terrain - 1, MAX_SHOULDER_DEPTH);
                } else if (road == null || road.roadType() != 0) {
                    // Artificial roads already have a filled pavement footprint.
                    // Extending dirt beyond that footprint produced isolated
                    // columns on steep slopes instead of a coherent shoulder.
                    fillColumn(world, road, shoulder, terrain, target, MAX_SHOULDER_DEPTH, Blocks.DIRT.defaultBlockState());
                }
            }
        }
    }

    private static boolean matching(List<BlockPos> positions, int[] profile, int[] terrain) {
        return positions != null && profile != null && terrain != null
                && positions.size() == profile.length && positions.size() == terrain.length;
    }

    private static void clearColumn(WorldGenLevel world, RoadData road, BlockPos column,
                                    int fromY, int toY, int limit) {
        if (toY < fromY || toY - fromY + 1 > limit) return;
        // 长城列整列不动。这里是「删方块」的唯一入口，过去没有任何所有权护栏 ——
        // 六道护栏全挂在「放方块」上，所以路肩削坡/横断面开山能把墙挖成空气。
        // 注意：这里只能 return（本列不挖），绝不能让调用方判整站失败。
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                column.getX(), fromY, column.getZ())) return;
        for (int y = fromY; y <= toY; y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (isProtectedState(world.getBlockState(pos))
                    || StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, pos, road)) return;
        }
        for (int y = fromY; y <= toY; y++) world.setBlock(new BlockPos(column.getX(), y, column.getZ()), Blocks.AIR.defaultBlockState(), 3);
        AboveColumnClearer.clearUnsupportedSurfaceClutter(
                world, new BlockPos(column.getX(), toY + 1, column.getZ()), road);
    }

    private static void fillColumn(WorldGenLevel world, RoadData road, BlockPos column,
                                   int fromY, int targetY, int limit, BlockState fill) {
        if (targetY <= fromY || targetY - fromY > limit) return;
        // 长城列同样不填：既不削墙，也不往墙基里塞土/石。
        if (net.shiroha233.roadweaver.compat.GreatWallRoadGuard.blocksFor(
                net.shiroha233.roadweaver.compat.GreatWallRoadGuard.Ch.CUT,
                column.getX(), fromY, column.getZ())) return;
        for (int y = fromY; y < targetY; y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (isWaterState(world.getBlockState(pos))
                    || StructureAvoidanceService.isVerifiedPreparedStructureBlock(world, pos, road)) return;
        }
        for (int y = fromY; y < targetY; y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            BlockState present = world.getBlockState(pos);
            if (!present.isAir() && present.getFluidState().isEmpty()) continue;
            world.setBlock(pos, fill, 3);
        }
    }

    private static boolean hasWatercourseAcrossRoad(WorldGenLevel world, List<BlockPos> centers, int index) {
        BlockPos center = centers.get(index);
        if (center == null || hasWaterSurface(world, center)) return center != null;
        BlockPos before = centers.get(Math.max(0, index - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
        if (before == null || after == null) return false;
        int dx = Integer.compare(after.getX(), before.getX());
        int dz = Integer.compare(after.getZ(), before.getZ());
        if (dx == 0 && dz == 0) return false;
        int nx = -dz;
        int nz = dx;
        boolean left = false, right = false;
        for (int offset = 1; offset <= WATERCOURSE_HALF_WIDTH; offset++) {
            left |= hasWaterSurface(world, center.offset(nx * offset, 0, nz * offset));
            right |= hasWaterSurface(world, center.offset(-nx * offset, 0, -nz * offset));
        }
        return left && right;
    }

    private static boolean isShallowCulvertCrossing(WorldGenLevel world, List<BlockPos> centers, int start, int end) {
        if (world == null || centers == null || start < 0 || end < start || end >= centers.size()) return false;
        if (end - start + 1 > CULVERT_MAX_SEGMENTS || !hasWatercourseAcrossRoad(world, centers, start)) return false;
        BlockPos center = centers.get(start);
        if (center == null) return false;
        int deepest = waterDepth(world, center);
        int largestOverhead = waterOverhead(world, center);
        BlockPos before = centers.get(Math.max(0, start - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, start + 1));
        if (before != null && after != null) {
            int nx = -Integer.compare(after.getZ(), before.getZ());
            int nz = Integer.compare(after.getX(), before.getX());
            for (int offset = 1; offset <= WATERCOURSE_HALF_WIDTH; offset++) {
                deepest = Math.max(deepest, waterDepth(world, center.offset(nx * offset, 0, nz * offset)));
                deepest = Math.max(deepest, waterDepth(world, center.offset(-nx * offset, 0, -nz * offset)));
                largestOverhead = Math.max(largestOverhead,
                        waterOverhead(world, center.offset(nx * offset, 0, nz * offset)));
                largestOverhead = Math.max(largestOverhead,
                        waterOverhead(world, center.offset(-nx * offset, 0, -nz * offset)));
            }
        }
        return isCulvertGeometry(end - start + 1, deepest, largestOverhead);
    }

    static boolean isCulvertGeometry(int segmentCount, int waterDepth, int overhead) {
        return segmentCount > 0 && segmentCount <= CULVERT_MAX_SEGMENTS
                && waterDepth > 0 && waterDepth <= CULVERT_MAX_WATER_DEPTH
                && overhead >= 0 && overhead <= CULVERT_MAX_OVERHEAD;
    }

    private static boolean hasWatercourseNearby(WorldGenLevel world, BlockPos pos) {
        return pos != null && hasSurfaceWaterNearby(pos.getX(), pos.getZ(),
                (x, z) -> hasWaterSurface(world, new BlockPos(x, pos.getY(), z)));
    }

    public static boolean hasSurfaceWaterNearby(int x, int z, SurfaceWaterLookup lookup) {
        if (lookup == null) return false;
        return lookup.hasSurfaceWater(x, z)
                || lookup.hasSurfaceWater(x, z - 1)
                || lookup.hasSurfaceWater(x, z + 1)
                || lookup.hasSurfaceWater(x + 1, z)
                || lookup.hasSurfaceWater(x - 1, z);
    }

    public static boolean heightmapsContainSurfaceWater(int worldSurfaceY, int oceanFloorY) {
        return worldSurfaceY > oceanFloorY;
    }

    private static boolean hasWaterSurface(WorldGenLevel world, BlockPos pos) {
        return waterSurfaceY(world, pos) >= world.getMinBuildHeight();
    }

    private static int waterSurfaceY(WorldGenLevel world, BlockPos pos) {
        return observeWater(world, pos).surfaceY();
    }

    private static int waterOverhead(WorldGenLevel world, BlockPos pos) {
        WaterObservation observation = observeWater(world, pos);
        return observation.surfaceY() < world.getMinBuildHeight()
                ? Integer.MAX_VALUE
                : Math.max(0, observation.topY() - observation.surfaceY());
    }

    private static WaterObservation observeWater(WorldGenLevel world, BlockPos pos) {
        try {
            int top = Math.max(
                    world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ()),
                    world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())) - 1;
            int lower = Math.max(world.getMinBuildHeight(), top - MAX_WATER_SCAN_DEPTH);
            int roofThickness = 0;
            boolean openBelowRoof = false;
            for (int y = Math.min(top, world.getMaxBuildHeight() - 1); y >= lower; y--) {
                BlockState state = world.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
                if (isWaterState(state)) {
                    int depth = 0;
                    for (int waterY = y; waterY >= world.getMinBuildHeight()
                            && depth <= CULVERT_MAX_WATER_DEPTH + 1; waterY--) {
                        if (!isWaterState(world.getBlockState(new BlockPos(pos.getX(), waterY, pos.getZ())))) break;
                        depth++;
                    }
                    return new WaterObservation(y, depth, top);
                }
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    if (!state.getFluidState().isEmpty()) return WaterObservation.NONE;
                    openBelowRoof = true;
                    continue;
                }
                if (state.blocksMotion()) {
                    if (openBelowRoof || ++roofThickness > MAX_WATER_ROOF_THICKNESS) {
                        return WaterObservation.NONE;
                    }
                }
            }
            return WaterObservation.NONE;
        } catch (RuntimeException ignored) {
            return WaterObservation.NONE;
        }
    }

    private static int waterDepth(WorldGenLevel world, BlockPos pos) {
        return observeWater(world, pos).depth();
    }

    private static boolean isWaterState(BlockState state) {
        if (state == null) return false;
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) return false;
        if (fluid.is(FluidTags.WATER)) return true;
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid.getType());
        return (blockId != null && blockId.getNamespace().contains("stream"))
                || (fluidId != null && fluidId.getNamespace().contains("stream"));
    }

    private static boolean isGround(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return state.blocksMotion() && (id == null || !"tanshugetrees".equals(id.getNamespace()));
    }

    static boolean isProtectedState(BlockState state) {
        if (state.isAir()) return false;
        return state.hasBlockEntity() || state.is(BlockTags.FEATURES_CANNOT_REPLACE) || state.is(Blocks.BEDROCK);
    }

    private static boolean isSanctuary(WorldGenLevel world, ChunkPos chunk) {
        BlockPos sample = new BlockPos(chunk.getMiddleBlockX(), world.getSeaLevel(), chunk.getMiddleBlockZ());
        return world.getBiome(sample).unwrapKey()
                .map(key -> "plaincraft_erdtree_sanctuary".equals(key.location().getNamespace())).orElse(false);
    }

    private static boolean inWindow(ChunkPos current, BlockPos pos) {
        return pos != null && Math.abs((pos.getX() >> 4) - current.x) <= RUNTIME_BRIDGE_CHUNK_RADIUS
                && Math.abs((pos.getZ() >> 4) - current.z) <= RUNTIME_BRIDGE_CHUNK_RADIUS;
    }

    private static boolean[] copyMask(boolean[] source, int size) {
        boolean[] result = new boolean[size];
        if (source != null) System.arraycopy(source, 0, result, 0, Math.min(source.length, size));
        return result;
    }

    private static List<int[]> copyRanges(List<int[]> source) {
        List<int[]> result = new ArrayList<>();
        if (source != null) for (int[] range : source) if (range != null && range.length >= 2) result.add(new int[]{range[0], range[1]});
        return result;
    }

    private static List<int[]> mergeRange(List<int[]> ranges, int start, int end) {
        List<int[]> all = copyRanges(ranges);
        all.add(new int[]{start, end});
        all.sort(Comparator.comparingInt(range -> range[0]));
        List<int[]> result = new ArrayList<>();
        for (int[] range : all) {
            if (result.isEmpty() || range[0] > result.getLast()[1] + 1) result.add(range);
            else result.getLast()[1] = Math.max(result.getLast()[1], range[1]);
        }
        return result;
    }

    private record RuntimeBridgeKey(String dimension, long first, long second,
                                    long routeStart, long routeEnd, int routeSize) { }

    private record RuntimeRange(int start, int end) { }

    private record WaterObservation(int surfaceY, int depth, int topY) {
        private static final WaterObservation NONE =
                new WaterObservation(Integer.MIN_VALUE, 0, Integer.MIN_VALUE);
    }
}
