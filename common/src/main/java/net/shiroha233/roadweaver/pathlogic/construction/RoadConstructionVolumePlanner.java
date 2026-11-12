package net.shiroha233.roadweaver.pathlogic.construction;

import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshotUnavailableException;
import net.shiroha233.roadweaver.pathlogic.structures.PlanningSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegment;
import net.shiroha233.roadweaver.decoration.system.AboveColumnClearer;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlannerNew;
import net.shiroha233.roadweaver.pathlogic.pathcalc.RoadHeightInterpolator;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.util.Line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;

/** Pure geometry for every block column a finalized road can modify. */
public final class RoadConstructionVolumePlanner {
    private static final int SHOULDER_DEPTH = 5;
    private static final int BRIDGE_BODY_PERIOD = 7;
    private static final int BRIDGE_SUPPORT_PHASE = 3;

    private RoadConstructionVolumePlanner() {
    }

    public enum Kind {
        ROAD,
        ROAD_FALLING_CAP,
        SHOULDER,
        TUNNEL,
        TUNNEL_PORTAL,
        TUNNEL_JUNCTION,
        BRIDGE_DECK,
        BRIDGE_FALLING_CAP,
        BRIDGE_TEMPLATE,
        BRIDGE_SUPPORT,
        BRIDGE_PIER
    }

    public record ColumnVolume(int segmentIndex, Kind kind, int x, int z, int minY, int maxY) {
        public ColumnVolume {
            if (minY > maxY) {
                int swap = minY;
                minY = maxY;
                maxY = swap;
            }
        }

        public BlockPos representativePos() {
            return new BlockPos(x, minY, z);
        }
    }

    @FunctionalInterface
    public interface VolumeVisitor {
        /** Return false to stop visiting. */
        boolean visit(ColumnVolume volume);
    }

    @FunctionalInterface
    public interface TerrainHeight {
        int sample(int x, int z);
    }

    @FunctionalInterface
    public interface WatercourseLookup {
        boolean isWatercourse(int x, int z);
    }

    @FunctionalInterface
    public interface TemplateBridgeProvider {
        TemplateBridgePlan plan(BridgeStation station);
    }

    public record TemplateBridgePlan(List<BlockPos> blocks, boolean centerChunkHandles) {
        public TemplateBridgePlan {
            blocks = blocks == null || blocks.isEmpty() ? List.of() : List.copyOf(blocks);
        }

        public static TemplateBridgePlan none() {
            return new TemplateBridgePlan(List.of(), false);
        }
    }

    public record BridgeStation(Line line,
                                RoadSegmentPlacement segment,
                                BlockPos middle,
                                BlockPos previous,
                                int deckY,
                                boolean landing) {
    }

    public record Context(ModConfig config,
                          TerrainHeight terrain,
                          TerrainHeight foundation,
                          WatercourseLookup watercourse,
                          TemplateBridgeProvider templateBridgeProvider) {
        public Context(ModConfig config,
                       TerrainHeight terrain,
                       WatercourseLookup watercourse,
                       TemplateBridgeProvider templateBridgeProvider) {
            this(config, terrain, terrain, watercourse, templateBridgeProvider);
        }

        public Context {
            // 0.8.345：**不要对传进来的 config 调 sanitize()**。
            // 此前 forWorld 传的是 ConfigService.get()（全局单例），而紧凑构造器里的
            // config.sanitize() 会**在 worker 线程上改写全局配置**，并与菜单 setter
            // 存在丢失更新窗口。调用方现在传副本（snapshot），这里只做 null 兜底。
            config = config == null ? new ModConfig() : config;
            terrain = terrain == null ? (x, z) -> 64 : terrain;
            foundation = foundation == null ? terrain : foundation;
            watercourse = watercourse == null ? (x, z) -> false : watercourse;
            templateBridgeProvider = templateBridgeProvider == null
                    ? station -> TemplateBridgePlan.none() : templateBridgeProvider;
        }

        public static Context forWorld(ServerLevel level,
                                       TerrainSamplingCache cache,
                                       StreamsReflowingCompat.SamplingSession streams) {
            AccurateHeightSampler sampler = cache.getAccurateSampler(level);
            StreamsReflowingCompat.SamplingSession session = streams == null
                    ? StreamsReflowingCompat.openSession(level) : streams;
            // 0.8.345：传**副本**而非全局单例 —— 见紧凑构造器上的说明。
            return new Context(
                    ConfigService.get().snapshot(),
                    (x, z) -> cachedSurfaceHeight(level, sampler, x, z),
                    (x, z) -> cachedOceanFloor(level, sampler, x, z),
                    (x, z) -> {
                        if (session.isAvailable()) {
                            StreamsReflowingCompat.RiverColumn column = session.sample(x, z);
                            if (column.isUnknown() || column.inChannel() || column.inCorridor()) return true;
                        }
                        return RoadConstructionSafety.hasSurfaceWaterNearby(x, z, (waterX, waterZ) -> {
                            AccurateHeightSample sample = sampler.cachedSample(waterX, waterZ);
                            if (sample == null
                                    && level.getChunkSource().getChunkNow(waterX >> 4, waterZ >> 4) == null) {
                                // This is an optional shoulder-water probe,
                                // not the road-body validation. Unknown means
                                // skip the shoulder rather than reject the
                                // entire route or request a chunk here.
                                return true;
                            }
                            return RoadConstructionSafety.heightmapsContainSurfaceWater(
                                    cachedWorldSurface(level, sampler, waterX, waterZ),
                                    cachedOceanFloor(level, sampler, waterX, waterZ));
                        });
                    },
                    station -> {
                        BridgeSegmentPlannerNew.PlannedBridge planned = BridgeSegmentPlannerNew.plannedBridge(
                                level, station.line(), station.segment(), station.middle(), station.previous(),
                                station.deckY(), station.landing());
                        return new TemplateBridgePlan(planned.blocks(), planned.centerChunkHandles());
                     });
        }

        private static int cachedSurfaceHeight(ServerLevel level, AccurateHeightSampler sampler, int x, int z) {
            AccurateHeightSample sample = sampler.cachedSample(x, z);
            if (sample != null) return sampler.surfaceHeight(sample);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                return RoadConstructionSafety.terrainHeight(level, x, z);
            }
            throw new PlanningSnapshotUnavailableException(
                    "final validation accurate terrain sample is unavailable at " + x + "," + z);
        }

        private static int cachedWorldSurface(ServerLevel level, AccurateHeightSampler sampler, int x, int z) {
            AccurateHeightSample sample = sampler.cachedSample(x, z);
            if (sample != null) return sample.worldSurfaceWg();
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
            }
            throw new PlanningSnapshotUnavailableException(
                    "final validation world-surface sample is unavailable at " + x + "," + z);
        }

        private static int cachedOceanFloor(ServerLevel level, AccurateHeightSampler sampler, int x, int z) {
            AccurateHeightSample sample = sampler.cachedSample(x, z);
            if (sample != null) return sample.oceanFloorWg();
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            }
            throw new PlanningSnapshotUnavailableException(
                    "final validation ocean-floor sample is unavailable at " + x + "," + z);
        }
    }

    public static List<ColumnVolume> collect(RoadData road, Context context) {
        List<ColumnVolume> result = new ArrayList<>();
        visit(road, context, volume -> {
            result.add(volume);
            return true;
        });
        return List.copyOf(result);
    }

    /**
     * Enumerates every X/Z column whose terrain height may be consulted while
     * validating a road. This is geometry-only and deliberately does not read
     * the world or invoke a chunk future. It covers tunnel portals/shells and
     * shoulder probes that are not present in the persisted road center list.
     */
    public static List<BlockPos> terrainSamplePositions(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()) {
            return List.of();
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) continue;
            centers.add(segment.middlePos());
        }
        if (centers.isEmpty()) return List.of();
        int[] profile = road.targetY() == null
                ? new int[centers.size()]
                : road.targetY().stream().mapToInt(Integer::intValue).toArray();
        if (profile.length != centers.size()) {
            profile = new int[centers.size()];
            for (int i = 0; i < centers.size(); i++) profile[i] = centers.get(i).getY();
        }
        SpanMasks masks = SpanMasks.from(centers, road.spans());
        ModConfig config = ConfigService.get();
        BridgeRangeCalculator.RangeResult bridgeRanges = BridgeRangeCalculator.compute(
                centers, road.spans(), config);
        BridgeSegment bridgeSegment = new BridgeSegment(bridgeRanges.isBridge(), segments, profile);
        LinkedHashMap<Long, BlockPos> unique = new LinkedHashMap<>();
        java.util.function.Consumer<BlockPos> add = position -> {
            if (position != null) unique.putIfAbsent(columnKey(position.getX(), position.getZ()), position);
        };
        for (int index = 0; index < segments.size() && index < centers.size(); index++) {
            RoadSegmentPlacement segment = segments.get(index);
            if (segment == null) continue;
            if (segment.positions() != null) segment.positions().forEach(add);
            if (bridgeRanges.isBridge()[index] && !bridgeRanges.skipSegments()[index]) {
                Line line = bridgeSegment.getLine(index);
                List<BlockPos> mapped = line == null || segment.positions() == null
                        ? segment.positions() : line.mapRoadPositions(segment.positions());
                if (mapped != null) mapped.forEach(add);
                BlockPos middle = centers.get(index);
                BlockPos pierCenter = line == null ? middle : line.mapRoadPosition(middle);
                int pierHalf = Math.max(0, config.bridgePierWidth() - 1);
                for (int dx = -pierHalf; dx <= pierHalf; dx++) {
                    for (int dz = -pierHalf; dz <= pierHalf; dz++) {
                        add.accept(pierCenter.offset(dx, 0, dz));
                    }
                }
                continue;
            }
            if (masks.tunnel[index] || masks.tunnelJunction[index]) {
                int spanStart = masks.tunnelStart[index] >= 0
                        ? masks.tunnelStart[index] : Math.max(0, index - 1);
                RoadTunnelBuilder.PlannedSection section = RoadTunnelBuilder.plannedSection(
                        segment, index, centers, profile, spanStart);
                if (section != null) {
                    section.road().forEach(add);
                    section.interior().forEach(add);
                    section.shell().forEach(add);
                    section.walls().forEach(add);
                }
                continue;
            }
            if (masks.roadCrossing[index] || centers.size() < 2) continue;
            BlockPos before = centers.get(Math.max(0, index - 1));
            BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
            int nx = Integer.compare(after.getZ(), before.getZ());
            int nz = Integer.compare(before.getX(), after.getX());
            if (nx == 0 && nz == 0) continue;
            int maxProjection = Integer.MIN_VALUE;
            for (BlockPos pos : segment.positions()) {
                if (pos != null) maxProjection = Math.max(maxProjection, pos.getX() * nx + pos.getZ() * nz);
            }
            if (maxProjection == Integer.MIN_VALUE) continue;
            for (BlockPos edge : segment.positions()) {
                if (edge == null || edge.getX() * nx + edge.getZ() * nz != maxProjection) continue;
                for (int width = 1; width <= SHOULDER_DEPTH; width++) {
                    add.accept(edge.offset(nx * width, 0, nz * width));
                }
            }
        }
        return List.copyOf(unique.values());
    }

    public static boolean visit(RoadData road, Context context, VolumeVisitor visitor) {
        if (road == null || context == null || visitor == null
                || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()
                || road.targetY() == null || road.targetY().size() != road.roadSegmentList().size()) {
            return false;
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return false;
            centers.add(segment.middlePos());
        }
        int[] profile = road.targetY().stream().mapToInt(Integer::intValue).toArray();
        SpanMasks masks = SpanMasks.from(centers, road.spans());
        BridgeRangeCalculator.RangeResult bridgeRanges = BridgeRangeCalculator.compute(
                centers, road.spans(), context.config());
        BridgeSegment bridgeSegment = new BridgeSegment(bridgeRanges.isBridge(), segments, profile);

        for (int index = 0; index < segments.size(); index++) {
            // A later road may follow an existing tunnel, but it must never
            // claim its excavation or construction volume.
            if (masks.networkReuse[index] || masks.protectedGap[index]) continue;
            RoadSegmentPlacement segment = segments.get(index);
            if (bridgeRanges.isBridge()[index] && !bridgeRanges.skipSegments()[index]) {
                if (!visitBridge(road, context, visitor, segments, centers, profile,
                        bridgeRanges, bridgeSegment, segment, index)) return false;
                continue;
            }
            if (masks.tunnel[index] || masks.tunnelJunction[index]) {
                if (!visitTunnel(context, visitor, centers, profile, masks, segment, index)) return false;
                continue;
            }
            if (!visitOrdinaryRoad(road, context, visitor, centers, profile, masks, segment, index)) return false;
        }
        return true;
    }

    private static boolean visitOrdinaryRoad(RoadData road,
                                             Context context,
                                             VolumeVisitor visitor,
                                             List<BlockPos> centers,
                                             int[] profile,
                                             SpanMasks masks,
                                             RoadSegmentPlacement segment,
                                             int index) {
        List<BlockPos> positions = segment.positions();
        if (positions == null || positions.isEmpty()) return true;
        int[] heights = RoadHeightInterpolator.batchInterpolate(positions, index, centers, profile);
        int[] terrain = sampleTerrain(context, positions);
        if (!masks.roadCrossing[index]) {
            int representative = RoadConstructionSafety.representativeHeight(heights);
            Arrays.fill(heights, representative);
        }
        int clearHeight = Math.max(1, context.config().roadClearHeight());
        for (int columnIndex = 0; columnIndex < positions.size(); columnIndex++) {
            BlockPos column = positions.get(columnIndex);
            if (column == null) continue;
            int target = heights[columnIndex];
            int original = terrain[columnIndex];
            int minY = Math.min(original, target - 1);
            int maxY = Math.max(original - 1, target + clearHeight - 1);
            if (!visitor.visit(new ColumnVolume(index, Kind.ROAD,
                    column.getX(), column.getZ(), minY, maxY))) return false;
            if (!visitFallingCap(visitor, index, Kind.ROAD_FALLING_CAP,
                    column.getX(), column.getZ(), target, clearHeight)) return false;
        }
        if (!masks.roadCrossing[index]) {
            return visitShoulders(road, context, visitor, centers, positions, heights, index);
        }
        return true;
    }

    private static boolean visitShoulders(RoadData road,
                                          Context context,
                                          VolumeVisitor visitor,
                                          List<BlockPos> centers,
                                          List<BlockPos> positions,
                                          int[] profile,
                                          int index) {
        if (centers.size() < 2) return true;
        BlockPos before = centers.get(Math.max(0, index - 1));
        BlockPos after = centers.get(Math.min(centers.size() - 1, index + 1));
        int dx = Integer.compare(after.getX(), before.getX());
        int dz = Integer.compare(after.getZ(), before.getZ());
        if (dx == 0 && dz == 0) return true;
        return visitShoulderSide(road, context, visitor, positions, profile, -dz, dx, index)
                && visitShoulderSide(road, context, visitor, positions, profile, dz, -dx, index);
    }

    private static boolean visitShoulderSide(RoadData road,
                                             Context context,
                                             VolumeVisitor visitor,
                                             List<BlockPos> positions,
                                             int[] profile,
                                             int nx,
                                             int nz,
                                             int segmentIndex) {
        int maxProjection = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            if (pos != null) maxProjection = Math.max(maxProjection, pos.getX() * nx + pos.getZ() * nz);
        }
        if (maxProjection == Integer.MIN_VALUE) return true;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos edge = positions.get(i);
            if (edge == null || edge.getX() * nx + edge.getZ() * nz != maxProjection) continue;
            int roadY = profile[i];
            for (int width = 1; width <= SHOULDER_DEPTH; width++) {
                BlockPos shoulder = edge.offset(nx * width, 0, nz * width);
                long key = columnKey(shoulder.getX(), shoulder.getZ());
                if (!visited.add(key) || context.watercourse().isWatercourse(
                        shoulder.getX(), shoulder.getZ())) continue;
                int terrain = context.terrain().sample(shoulder.getX(), shoulder.getZ());
                if (terrain == roadY) continue;
                int target = terrain < roadY
                        ? Math.max(terrain, roadY - width)
                        : Math.min(terrain, roadY + width);
                if (terrain < target && road.roadType() == 0) continue;
                int minY = Math.min(terrain, target);
                int maxY = Math.max(terrain, target) - 1;
                if (minY <= maxY && !visitor.visit(new ColumnVolume(segmentIndex, Kind.SHOULDER,
                        shoulder.getX(), shoulder.getZ(), minY, maxY))) return false;
            }
        }
        return true;
    }

    private static boolean visitTunnel(Context context,
                                       VolumeVisitor visitor,
                                       List<BlockPos> centers,
                                       int[] profile,
                                       SpanMasks masks,
                                       RoadSegmentPlacement segment,
                                       int index) {
        int spanStart = masks.tunnelStart[index] >= 0 ? masks.tunnelStart[index] : Math.max(0, index - 1);
        RoadTunnelBuilder.PlannedSection section = RoadTunnelBuilder.plannedSection(
                segment, index, centers, profile, spanStart);
        if (section == null) return true;
        boolean junction = masks.tunnelJunction[index];
        boolean portal = !junction && isPortal(context, centers, profile, section, index);
        Kind kind = junction ? Kind.TUNNEL_JUNCTION : portal ? Kind.TUNNEL_PORTAL : Kind.TUNNEL;
        Set<Long> paved = new HashSet<>();
        for (BlockPos roadColumn : section.road()) paved.add(roadColumn.asLong());

        for (BlockPos column : section.interior()) {
            int clearTo;
            if (junction) {
                clearTo = RoadTunnelBuilder.clearHeight();
            } else if (portal) {
                int terrain = context.terrain().sample(column.getX(), column.getZ());
                clearTo = Math.max(RoadTunnelBuilder.clearHeight(), terrain - column.getY() + 1);
            } else {
                clearTo = RoadTunnelBuilder.roofOffset();
            }
            int minY = paved.contains(column.asLong()) ? column.getY() - 1 : column.getY() + 1;
            if (!visitor.visit(new ColumnVolume(index, kind, column.getX(), column.getZ(),
                    minY, column.getY() + clearTo))) return false;
        }
        if (!portal && !junction) {
            for (BlockPos wall : section.walls()) {
                if (!visitor.visit(new ColumnVolume(index, kind, wall.getX(), wall.getZ(),
                        wall.getY(), wall.getY() + RoadTunnelBuilder.roofOffset()))) return false;
            }
        }
        return true;
    }

    private static boolean isPortal(Context context,
                                    List<BlockPos> centers,
                                    int[] profile,
                                    RoadTunnelBuilder.PlannedSection section,
                                    int index) {
        if (centers.get(index).getY() - profile[index] < RoadTunnelBuilder.minOverburden()) return true;
        for (BlockPos column : section.shell()) {
            int terrain = context.terrain().sample(column.getX(), column.getZ());
            if (terrain < column.getY() + RoadTunnelBuilder.minOverburden()) return true;
        }
        return false;
    }

    private static boolean visitBridge(RoadData road,
                                       Context context,
                                       VolumeVisitor visitor,
                                       List<RoadSegmentPlacement> segments,
                                       List<BlockPos> centers,
                                       int[] profile,
                                       BridgeRangeCalculator.RangeResult ranges,
                                       BridgeSegment bridgeSegment,
                                       RoadSegmentPlacement segment,
                                       int index) {
        BlockPos middle = centers.get(index);
        BlockPos previous = centers.get(Math.max(0, index - 2));
        Line line = bridgeSegment.getLine(index);
        TemplateBridgePlan templatePlan = line == null ? TemplateBridgePlan.none()
                : context.templateBridgeProvider().plan(
                new BridgeStation(line, segment, middle, previous, profile[index], bridgeSegment.isLanding(index)));
        if (templatePlan != null && !templatePlan.blocks().isEmpty()) {
            for (BlockPos block : templatePlan.blocks()) {
                if (block != null && !visitor.visit(new ColumnVolume(index, Kind.BRIDGE_TEMPLATE,
                        block.getX(), block.getZ(), block.getY(), block.getY()))) return false;
            }
            if (templatePlan.centerChunkHandles()) return true;
        }

        List<BlockPos> nextPositions = line == null ? segment.positions() : line.mapRoadPositions(segment.positions());
        if (nextPositions == null || nextPositions.isEmpty()) return true;
        // The deck block sits one above the profile Y (BridgeBuilder: deckSurfaceY = profile + 2, then
        // deckBlockY = deckSurfaceY - 1). This fallback used profile - 1, so the volume the validator checked
        // was two blocks low: the top two blocks of the real deck were never validated and two blocks of
        // empty air below it were.
        // The builder puts the deck block on the profile Y (BridgeBuilder: deckSurfaceY - 1 with
        // deckSurfaceY = profile + 1), so the volume has to start there too or every bridge volume is
        // validated one block high.
        int deckBlockY = profile[index];
        int clearHeight = Math.max(1, context.config().roadClearHeight());
        for (BlockPos position : nextPositions) {
            if (position == null) continue;
            if (!visitor.visit(new ColumnVolume(index, Kind.BRIDGE_DECK,
                    position.getX(), position.getZ(), deckBlockY, deckBlockY + clearHeight))) return false;
            if (!visitFallingCap(visitor, index, Kind.BRIDGE_FALLING_CAP,
                    position.getX(), position.getZ(), deckBlockY + 1, clearHeight)) return false;
        }

        BlockPos next = centers.get(Math.min(centers.size() - 1, index + 2));
        if (line != null && line.isCurved()) {
            previous = line.mapRoadPosition(previous);
            next = line.mapRoadPosition(next);
        }
        int[] edgeRange = edgeProjectionRange(new RoadSegmentPlacement(segment.middlePos(), nextPositions), previous, next);
        int[] range = containingRange(ranges.mergedRanges(), index);
        boolean landing = range != null && (index == range[0] || index == range[1]);
        int phase = Math.floorMod(index, BRIDGE_BODY_PERIOD);
        if (phase == BRIDGE_SUPPORT_PHASE) {
            for (BlockPos position : nextPositions) {
                int slot = widthSlot(edgeProjection(position, previous, next), edgeRange);
                if (slot != 1 && slot != 4) continue;
                int terrain = context.foundation().sample(position.getX(), position.getZ());
                for (int depth = 1; depth <= 3; depth++) {
                    int y = deckBlockY - depth;
                    if (y < terrain) break;
                    if (!visitor.visit(new ColumnVolume(index, Kind.BRIDGE_SUPPORT,
                            position.getX(), position.getZ(), y, y))) return false;
                }
            }
        }
        if (!landing && edgeRange[0] != edgeRange[1]) {
            for (BlockPos position : nextPositions) {
                int projection = edgeProjection(position, previous, next);
                if (projection != edgeRange[0] && projection != edgeRange[1]) continue;
                if (!visitor.visit(new ColumnVolume(index, Kind.BRIDGE_DECK,
                        position.getX(), position.getZ(), deckBlockY + 1, deckBlockY + 1))) return false;
            }
        }

        int pierMaxHeight = Math.max(0, context.config().bridgePierMaxHeight());
        int interval = Math.max(3, context.config().bridgePierInterval());
        int segmentIndex = Math.max(0, Math.min(segments.size() - 2, index));
        if (pierMaxHeight > 0 && segmentIndex % interval == 0) {
            int pierWidth = context.watercourse().isWatercourse(middle.getX(), middle.getZ())
                    ? 1 : Math.max(1, context.config().bridgePierWidth());
            int half = Math.max(0, pierWidth - 1);
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    BlockPos pierCenter = line == null ? middle : line.mapRoadPosition(middle);
                    int x = pierCenter.getX() + dx;
                    int z = pierCenter.getZ() + dz;
                    int terrain = context.foundation().sample(x, z);
                    int top = deckBlockY - 1;
                    int bottom = Math.max(terrain, top - pierMaxHeight + 1);
                    if (bottom <= top && !visitor.visit(new ColumnVolume(index, Kind.BRIDGE_PIER,
                            x, z, bottom, top))) return false;
                }
            }
        }
        return true;
    }

    private static boolean visitFallingCap(VolumeVisitor visitor,
                                           int segmentIndex,
                                           Kind kind,
                                           int x,
                                           int z,
                                           int surfaceY,
                                           int clearHeight) {
        return visitor.visit(new ColumnVolume(
                segmentIndex, kind, x, z,
                AboveColumnClearer.fallingCapStartY(surfaceY, clearHeight),
                AboveColumnClearer.fallingCapEndY(surfaceY, clearHeight)));
    }

    private static int[] sampleTerrain(Context context, List<BlockPos> positions) {
        int[] result = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            result[i] = pos == null ? 0 : context.terrain().sample(pos.getX(), pos.getZ());
        }
        return result;
    }

    private static int[] containingRange(List<int[]> ranges, int index) {
        if (ranges == null) return null;
        for (int[] range : ranges) {
            if (range != null && range.length >= 2 && index >= range[0] && index <= range[1]) return range;
        }
        return null;
    }

    private static int[] edgeProjectionRange(RoadSegmentPlacement segment, BlockPos previous, BlockPos next) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (BlockPos pos : segment.positions()) {
            int projection = edgeProjection(pos, previous, next);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        return new int[]{min, max};
    }

    private static int edgeProjection(BlockPos pos, BlockPos previous, BlockPos next) {
        int dx = Integer.compare(next.getX(), previous.getX());
        int dz = Integer.compare(next.getZ(), previous.getZ());
        return pos.getX() * -dz + pos.getZ() * dx;
    }

    private static int widthSlot(int projection, int[] range) {
        if (range[0] >= range[1]) return 3;
        double normalized = (projection - range[0]) / (double) (range[1] - range[0]);
        return Math.max(0, Math.min(6, (int) Math.round(normalized * 6.0D)));
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class SpanMasks {
        private final boolean[] tunnel;
        private final boolean[] tunnelJunction;
        private final boolean[] networkReuse;
        private final boolean[] protectedGap;
        private final boolean[] roadCrossing;
        private final int[] tunnelStart;

        private SpanMasks(int size) {
            tunnel = new boolean[size];
            tunnelJunction = new boolean[size];
            networkReuse = new boolean[size];
            protectedGap = new boolean[size];
            roadCrossing = new boolean[size];
            tunnelStart = new int[size];
            Arrays.fill(tunnelStart, -1);
        }

        private static SpanMasks from(List<BlockPos> centers, List<RoadSpan> spans) {
            SpanMasks masks = new SpanMasks(centers.size());
            if (spans == null || spans.isEmpty()) return masks;
            Map<Long, Integer> indices = new HashMap<>();
            for (int i = 0; i < centers.size(); i++) indices.put(xzKey(centers.get(i)), i);
            for (RoadSpan span : spans) {
                if (span == null || span.start() == null || span.end() == null || span.type() == null) continue;
                Integer startIndex = indices.get(xzKey(span.start()));
                Integer endIndex = indices.get(xzKey(span.end()));
                if (startIndex == null || endIndex == null) continue;
                int from = Math.min(startIndex, endIndex);
                int to = Math.max(startIndex, endIndex);
                for (int index = from; index <= to; index++) {
                    switch (span.type()) {
                        case TUNNEL -> {
                            masks.tunnel[index] = true;
                            masks.tunnelStart[index] = from;
                        }
                        case TUNNEL_JUNCTION -> masks.tunnelJunction[index] = true;
                        case NETWORK_REUSE -> masks.networkReuse[index] = true;
                        case PROTECTED_GAP -> masks.protectedGap[index] = true;
                        case ROAD_CROSSING -> masks.roadCrossing[index] = true;
                        default -> {
                        }
                    }
                }
            }
            return masks;
        }
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
