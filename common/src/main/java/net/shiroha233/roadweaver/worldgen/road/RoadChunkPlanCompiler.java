/* 文件职责：将道路聚合数据一次性编译为区块局部世界生成计划。 */
package net.shiroha233.roadweaver.worldgen.road;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 道路计划编译器。编译阶段允许 O(road segments)，世界生成阶段只读取 stamp。
 */
public final class RoadChunkPlanCompiler {
    /** 读端口归属编译领域，门面只转发查询，避免为离线注入增加新的跨层类型依赖。 */
    @FunctionalInterface
    interface Rectangle { List<RoadData> read(int minX, int minZ, int maxX, int maxZ); }
    record Query(Rectangle rectangle, java.util.function.Function<ChunkPos, List<RoadData>> chunk) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int COLUMN_COUNT = 256;
    static final int BANK_WIDTH = 2;
    static final int BRIDGE_TEMPLATE_MARGIN_BLOCKS = 10;
    private static final int FILL_DEPTH = 6;
    private static final float MAX_FILL = 0.5F;
    private static final float MAX_CARVE = 0.6F;


    /** 平行近路需要更长证据段，真正交叉使用独立判据。 */

    private RoadChunkPlanCompiler() {
    }

    public static RoadChunkPlan compile(ChunkPos chunkPos,
                                        Collection<RoadData> roads,
                                        boolean includeBridgeSegments,
                                        int clearHeight,
                                        long revision) {
        if (chunkPos == null) {
            throw new IllegalArgumentException("chunkPos must not be null");
        }
        int safeClearHeight = Math.max(0, Math.min(16, clearHeight));
        List<RoadData> validRoads = normalizeForPhysicalOwnership(validRoads(roads));
        if (validRoads.isEmpty()) {
            return RoadChunkPlan.empty(chunkPos, safeClearHeight, revision);
        }

        List<RasterSegment> rasterSegments = new ArrayList<>();
        List<RasterSegment> terrainStampExclusions = new ArrayList<>();
        List<RoadChunkSlice> slices = new ArrayList<>();
        boolean[] occupied = new boolean[COLUMN_COUNT];
        // 同次编译按归一化后的实例准备，不能跨道路集合缓存物理归属。
        var spanInputs = new java.util.IdentityHashMap<RoadData, ExactSpans>();
        for (RoadData road : validRoads) spanInputs.put(road, new ExactSpans(road.roadSegmentList(), road.spans()));
        List<JunctionMarker> tunnelJunctions = collectTunnelJunctions(validRoads, spanInputs);
        for (RoadData road : validRoads) {
            List<RoadSegmentPlacement> placements = road.roadSegmentList();
            if (placements == null || placements.size() < 2) continue;

            int halfWidth = Math.max(1, (road.width() + 1) / 2);
            // T-20C 单遍位置预筛:对不可能触及本 chunk 的道路(覆盖站点触达、
            // 桥模板 margin、占用圆盘与密度光栅的全部上界)直接跳过,不再构建
            // span 掩码、不做 markOccupied、不输出 produced 0 localIndices。
            // 预筛条件是主循环所有触达判定的上界,因此真正相交的 chunk 的
            // 编译结果不变。
            if (!RoadChunkIntersection.roadTouchesChunk(placements, chunkPos, halfWidth)) continue;

            ExactSpans spans = spanInputs.get(road);
            boolean[] bridgeMask = spans.mask(SpanType.BRIDGE);
            boolean[] directCrossingMask = spans.mask(SpanType.ROAD_CROSSING);
            boolean[] networkReuseMask = spans.mask(SpanType.NETWORK_REUSE);
            boolean[] protectedGapMask = spans.mask(SpanType.PROTECTED_GAP);
            boolean[] tunnelJunctionMask = spans.mask(SpanType.TUNNEL_JUNCTION);
            boolean[] tunnelMask = spans.mask(SpanType.TUNNEL);
            int[] externalTunnelJunctions = externalTunnelJunctionIndices(
                    road, placements, tunnelMask, tunnelJunctions);
            int[] allTunnelJunctions = mergeMarkedIndices(tunnelJunctionMask, externalTunnelJunctions);
            boolean[] allTunnelJunctionMask = booleanMask(placements.size(), allTunnelJunctions);
            List<Integer> localIndices = new ArrayList<>();
            int chunkMinX = chunkPos.getMinBlockX();
            int chunkMinZ = chunkPos.getMinBlockZ();
            int chunkMaxX = chunkPos.getMaxBlockX();
            int chunkMaxZ = chunkPos.getMaxBlockZ();

            for (int i = 0; i < placements.size(); i++) {
                RoadSegmentPlacement placement = placements.get(i);
                if (placement == null || placement.middlePos() == null) continue;
                // A NETWORK_REUSE span belongs to the earlier road which supplied
                // its exact stations.  The later road must not contribute any
                // density stamp, clearing, bridge margin, or occupancy here.
                boolean skipStamping = (networkReuseMask != null && networkReuseMask[i]
                        && !allTunnelJunctionMask[i])
                        || (protectedGapMask != null && protectedGapMask[i]);
                if (!skipStamping) {
                    markOccupied(occupied, placement, chunkPos, halfWidth);
                }
                int featureMargin = bridgeMask != null && bridgeMask[i]
                        ? BRIDGE_TEMPLATE_MARGIN_BLOCKS
                        : 0;
                // Task 11: masked stations still count towards localIndices.
                // Skipping them entirely left chunks whose every station was a
                // reuse/gap station with zero localIndices -> empty worldgen
                // plan -> a visible gap even though a road shard exists. The
                // slice is produced (PathFeature itself skips the masked
                // stations while paving), only the raster stamp is skipped.
                // Task 3: a station also belongs to this chunk when the line
                // to either neighbour crosses it - two placements can sit in
                // adjacent chunks while their connecting edge passes through
                // this one, and the raster pass stamps by edge.
                boolean touches = RoadChunkIntersection.intersectsChunk(placement, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, featureMargin);
                if (!touches && i > 0 && placements.get(i - 1) != null) {
                    touches = RoadChunkIntersection.segmentIntersectsChunk(placements.get(i - 1).middlePos(),
                            placement.middlePos(), chunkMinX - featureMargin, chunkMinZ - featureMargin,
                            chunkMaxX + featureMargin, chunkMaxZ + featureMargin);
                }
                if (!touches && i + 1 < placements.size() && placements.get(i + 1) != null) {
                    touches = RoadChunkIntersection.segmentIntersectsChunk(placement.middlePos(),
                            placements.get(i + 1).middlePos(), chunkMinX - featureMargin, chunkMinZ - featureMargin,
                            chunkMaxX + featureMargin, chunkMaxZ + featureMargin);
                }
                if (touches) {
                    localIndices.add(i);
                }
            }
            if (!localIndices.isEmpty()) {
                addNeighborIndices(localIndices, placements.size(), networkReuseMask);
                localIndices.sort(Comparator.naturalOrder());
                slices.add(new RoadChunkSlice(road, chunkPos, toIntArray(localIndices), allTunnelJunctions));
            } else {
                // A road that is in validRoads but contributes no placement to
                // this chunk is the "planned but not constructed" case: either
                // a span mask skipped its stations or its footprint missed the
                // chunk bounds. Log the fingerprint + span breakdown (task 16).
                // T-20C: 经预筛后这里只会命中"确实触及 chunk 但全部站位被掩码"
                // 的少数道路,降级为 debug 且仅在 debug 开启时才计算 maskHits
                // 统计,避免为被抑制的日志支付 O(站数×7) 的格式化成本。
                if (LOGGER.isDebugEnabled()) {
                    long fp = RoadShardStorage.computeFingerprint(road);
                    LOGGER.debug("【区块计划编译未产生局部站点，记录掩码与相交判据】 " + "RoadWeaver compile chunk={},{} road={} produced 0 localIndices; "
                                    + "placements={} spans={} maskHits(reuse={},gap={},junction={},tunnel={},bridge={},crossing={}) "
                                    + "chunkIntersects={}",
                            chunkPos.x, chunkPos.z, fp, placements.size(),
                            road.spans() == null ? 0 : road.spans().size(),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, networkReuseMask),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, protectedGapMask),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, tunnelJunctionMask),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, tunnelMask),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, bridgeMask),
                            RoadChunkIntersection.countMaskHits(placements, chunkPos, directCrossingMask),
                            RoadChunkIntersection.anyPlacementIntersectsChunk(placements, chunkPos));
                }
            }

            List<Integer> targetY = road.targetY();
            for (int i = 0; i < placements.size() - 1; i++) {
                if (!isRenderable(placements.get(i)) || !isRenderable(placements.get(i + 1))) continue;
                boolean firstReused = networkReuseMask != null && networkReuseMask[i];
                boolean secondReused = networkReuseMask != null && networkReuseMask[i + 1];
                boolean firstProtectedGap = protectedGapMask != null && protectedGapMask[i];
                boolean secondProtectedGap = protectedGapMask != null && protectedGapMask[i + 1];
                // Do not stamp the transition edge either.  A bridge template
                // may extend well beyond a single station, so retaining this
                // edge lets a secondary road write bridge abutments or piers
                // into the primary road's shared corridor.
                // A protected gap is an intentional unpaved interval. Do not
                // let the raster fill/carve pass bridge across either edge of
                // the skipped station.
                if ((firstReused || secondReused)
                        && !(allTunnelJunctionMask[i] || allTunnelJunctionMask[i + 1])) continue;
                if (firstProtectedGap || secondProtectedGap) continue;
                boolean isBridge = bridgeMask != null && (bridgeMask[i] || bridgeMask[i + 1]);
                if (isBridge && !includeBridgeSegments) continue;
                // Tunnel spans are excavated by RoadTunnelBuilder.  They must
                // never enter the ordinary density fill/carve pass: if the
                // tunnel preflight later rejects a protected section, a
                // normal terrain stamp here would already have cut a deep
                // open trench and leave a ravine without a roof.  Portal and
                // junction sections are also handled by the tunnel builder.
                boolean isTunnel = (tunnelMask != null && (tunnelMask[i] || tunnelMask[i + 1]))
                        || (allTunnelJunctionMask[i] || allTunnelJunctionMask[i + 1]);
                if (isTunnel) continue;
                boolean isDirectCrossing = directCrossingMask != null
                        && (directCrossingMask[i] || directCrossingMask[i + 1]);

                BlockPos a = placements.get(i).middlePos();
                BlockPos b = placements.get(i + 1).middlePos();
                int y0 = targetAt(targetY, i, a.getY());
                int y1 = targetAt(targetY, i + 1, b.getY());
                // 禁切填与普通路段的影响半径相同。远离本区块的跨路段也不必创建光栅条目。
                int radius = halfWidth + BANK_WIDTH;
                if (Math.max(a.getX(), b.getX()) + radius < chunkMinX
                        || Math.min(a.getX(), b.getX()) - radius > chunkMaxX
                        || Math.max(a.getZ(), b.getZ()) + radius < chunkMinZ
                        || Math.min(a.getZ(), b.getZ()) - radius > chunkMaxZ) continue;
                var segment = new RasterSegment(a.getX(), a.getZ(), y0,
                        b.getX(), b.getZ(), y1, halfWidth);
                (isDirectCrossing ? terrainStampExclusions : rasterSegments).add(segment);
            }
        }

        if (rasterSegments.isEmpty()) {
            return RoadChunkPlan.of(chunkPos, slices,
                    RoadDensityStamp.empty(chunkPos, safeClearHeight, occupied), revision);
        }
        RoadDensityStamp stamp = compileStamp(
                chunkPos, rasterSegments, terrainStampExclusions, occupied, safeClearHeight);
        return RoadChunkPlan.of(chunkPos, slices, stamp, revision);
    }

    private static RoadDensityStamp compileStamp(ChunkPos chunkPos,
                                                  List<RasterSegment> segments,
                                                  List<RasterSegment> exclusions,
                                                  boolean[] occupied,
                                                  int clearHeight) {
        int[] fillTargetY = new int[COLUMN_COUNT];
        int[] carveTargetY = new int[COLUMN_COUNT];
        Arrays.fill(fillTargetY, Integer.MIN_VALUE);
        Arrays.fill(carveTargetY, Integer.MIN_VALUE);
        float[] fillLateral = new float[COLUMN_COUNT];
        float[] carveLateral = new float[COLUMN_COUNT];
        // 先在跨路段实际触及的列上取禁切填并集，避免让区块每一列都扫描所有跨路段。
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        boolean[] excluded = exclusions.isEmpty() ? null : new boolean[COLUMN_COUNT];
        for (RasterSegment segment : exclusions) {
            int radius = segment.halfWidth + BANK_WIDTH;
            double radiusSq = (double) radius * radius;
            ColumnBounds bounds = columnBounds(segment, minX, minZ, radius);
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    int index = x | (z << 4);
                    if (!excluded[index] && project(minX + x, minZ + z, segment).distanceSq() <= radiusSq) {
                        excluded[index] = true;
                    }
                }
            }
        }

        // 按原路段顺序更新各列，保留填方等权或多个挖方候选时「先到者胜出」的语义。
        // 胶囊的投影不可能超出端点包围盒加半径，盒外无需计算投影。
        for (RasterSegment segment : segments) {
            int radius = segment.halfWidth + BANK_WIDTH;
            double inner = Math.max(0.0D, segment.halfWidth * 0.25D);
            ColumnBounds bounds = columnBounds(segment, minX, minZ, radius);
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    int index = x | (z << 4);
                    if (excluded != null && excluded[index]) continue;
                    Projection projection = project(minX + x, minZ + z, segment);
                    double distance = Math.sqrt(projection.distanceSq());
                    if (distance > radius) continue;
                    float lateral = (float) smoothFalloff(distance, inner, radius);
                    lateral *= lateral;
                    boolean replaceFill = lateral > fillLateral[index];
                    boolean setCarve = distance <= segment.halfWidth && carveLateral[index] == 0.0F;
                    if (!replaceFill && !setCarve) continue;
                    int surfaceY = (int) Math.round(segment.y0 + projection.t() * (segment.y1 - segment.y0));
                    if (replaceFill) {
                        fillLateral[index] = lateral;
                        fillTargetY[index] = surfaceY;
                    }
                    if (setCarve) {
                        carveLateral[index] = 1.0F;
                        carveTargetY[index] = surfaceY;
                    }
                }
            }
        }

        float[] fillDepth = new float[FILL_DEPTH + 1];
        for (int i = 0; i < fillDepth.length; i++) {
            double depthT = Mth.clamp((i + 0.5D) / (FILL_DEPTH + 1.0D), 0.0D, 1.0D);
            fillDepth[i] = MAX_FILL * (float) Math.pow(1.0D - depthT, 2.2D);
        }
        float[] carveDepth = new float[clearHeight + 1];
        for (int i = 0; i < carveDepth.length; i++) {
            double clearT = Mth.clamp(i / (double) Math.max(1, clearHeight), 0.0D, 1.0D);
            carveDepth[i] = -MAX_CARVE * (float) Math.pow(1.0D - clearT, 1.8D);
        }

        // 数组均为本次编译独占且不再写入，直接移交给不可变的列数据。
        return new RoadDensityStamp(chunkPos, fillTargetY, carveTargetY,
                fillLateral, carveLateral, fillDepth, carveDepth, occupied, false);
    }

    private static ColumnBounds columnBounds(RasterSegment segment, int minX, int minZ, int radius) {
        return new ColumnBounds(
                Math.max(0, Math.min(segment.x0, segment.x1) - minX - radius),
                Math.min(15, Math.max(segment.x0, segment.x1) - minX + radius),
                Math.max(0, Math.min(segment.z0, segment.z1) - minZ - radius),
                Math.min(15, Math.max(segment.z0, segment.z1) - minZ + radius));
    }

    private record ColumnBounds(int minX, int maxX, int minZ, int maxZ) {
    }

    private static void markOccupied(boolean[] occupied,
                                     RoadSegmentPlacement placement,
                                     ChunkPos chunkPos,
                                     int halfWidth) {
        if (placement.positions() != null && !placement.positions().isEmpty()) {
            for (BlockPos position : placement.positions()) markOccupied(occupied, position, chunkPos);
        }
        BlockPos middle = placement.middlePos();
        if (middle == null) return;
        int radius = Math.min(16, Math.max(1, halfWidth + 1));
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int localCenterX = middle.getX() - chunkMinX;
        int localCenterZ = middle.getZ() - chunkMinZ;
        if (localCenterX < -radius || localCenterX >= 16 + radius
                || localCenterZ < -radius || localCenterZ >= 16 + radius) return;
        int radiusSquared = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radiusSquared) {
                    markOccupied(occupied, middle.getX() + dx, middle.getZ() + dz, chunkPos);
                }
            }
        }
    }

    private static void markOccupied(boolean[] occupied, BlockPos position, ChunkPos chunkPos) {
        if (position == null) return;
        markOccupied(occupied, position.getX(), position.getZ(), chunkPos);
    }

    private static void markOccupied(boolean[] occupied, int blockX, int blockZ, ChunkPos chunkPos) {
        int localX = blockX - chunkPos.getMinBlockX();
        int localZ = blockZ - chunkPos.getMinBlockZ();
        if ((localX | localZ) < 0 || localX >= 16 || localZ >= 16) return;
        occupied[localX | (localZ << 4)] = true;
    }

    private static List<RoadData> validRoads(Collection<RoadData> roads) {
        if (roads == null || roads.isEmpty()) return List.of();
        List<RoadData> valid = new ArrayList<>(roads.size());
        for (RoadData road : roads) {
            if (road == null || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()) continue;
            valid.add(road);
        }
        return List.copyOf(valid);
    }

    /** 地图复用施工物理归属归一，重开丢失规划内存后也不重复显示已让给主路的走廊。
 *
     * Applies the same deterministic physical ownership pass used by chunk
     * construction to read-only map consumers. This keeps map geometry from
     * displaying a secondary route over a corridor that is already owned by
     * an earlier route, including after a world reload when planning-side
     * NETWORK_REUSE metadata is no longer in memory.
     */
    public static List<RoadData> normalizeForPhysicalOwnership(Collection<RoadData> roads) {
        List<RoadData> valid = validRoads(roads);
        return RoadPhysicalReuseNormalizer.normalizePhysicalOverlaps(valid);
    }

    /** 施工占地重叠按统一所有权规则消解，避免两个道路分片重复铺设。 */

    private static List<JunctionMarker> collectTunnelJunctions(List<RoadData> roads,
            Map<RoadData, ExactSpans> spanInputs) {
        List<JunctionMarker> markers = new ArrayList<>();
        for (RoadData road : roads) {
            List<RoadSegmentPlacement> placements = road.roadSegmentList();
            boolean[] mask = spanInputs.get(road).mask(SpanType.TUNNEL_JUNCTION);
            if (mask == null) continue;
            for (int i = 0; i < placements.size(); i++) {
                if (!mask[i]) continue;
                RoadSegmentPlacement placement = placements.get(i);
                if (placement == null || placement.middlePos() == null) continue;
                int y = targetAt(road.targetY(), i, placement.middlePos().getY());
                markers.add(new JunctionMarker(road, placement, y));
            }
        }
        return List.copyOf(markers);
    }

    private static int[] externalTunnelJunctionIndices(RoadData road,
                                                        List<RoadSegmentPlacement> placements,
                                                        boolean[] tunnelMask,
                                                        List<JunctionMarker> markers) {
        if (tunnelMask == null || markers.isEmpty()) return new int[0];
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < placements.size(); i++) {
            if (!tunnelMask[i]) continue;
            RoadSegmentPlacement placement = placements.get(i);
            if (placement == null || placement.middlePos() == null) continue;
            for (JunctionMarker marker : markers) {
                // A branch may meet the trunk one or more blocks above or
                // below it. The junction section must still be opened so the
                // two tunnel volumes connect instead of leaving a sealed wall.
                if (marker.owner() == road) continue;
                if (!RoadPhysicalReuseNormalizer.footprintsIntersect(placement, marker.placement())
                        && horizontalDistance(placement.middlePos(), marker.placement().middlePos()) > 8) continue;
                for (int delta = -1; delta <= 1; delta++) {
                    int candidate = i + delta;
                    if (candidate >= 0 && candidate < placements.size() && tunnelMask[candidate]) {
                        indices.add(candidate);
                    }
                }
                break;
            }
        }
        return indices.stream().sorted().mapToInt(Integer::intValue).toArray();
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        if (first == null || second == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getZ() - second.getZ()));
    }

    private static int[] mergeMarkedIndices(boolean[] ownMask, int[] external) {
        Set<Integer> indices = new HashSet<>();
        if (ownMask != null) {
            for (int i = 0; i < ownMask.length; i++) {
                if (ownMask[i]) indices.add(i);
            }
        }
        if (external != null) {
            for (int index : external) indices.add(index);
        }
        return indices.stream().sorted().mapToInt(Integer::intValue).toArray();
    }

    private static boolean[] booleanMask(int size, int[] indices) {
        boolean[] mask = new boolean[Math.max(0, size)];
        if (indices != null) {
            for (int index : indices) {
                if (index >= 0 && index < mask.length) mask[index] = true;
            }
        }
        return mask;
    }

    private record JunctionMarker(RoadData owner, RoadSegmentPlacement placement, int y) {
    }

    /** 诊断按一格步距检查中心边，不能只取两个端点。 */
    private static void addNeighborIndices(List<Integer> indices, int size, boolean[] excluded) {
        Set<Integer> expanded = new HashSet<>(indices);
        for (int index : indices) {
            for (int delta = -2; delta <= 2; delta++) {
                int candidate = index + delta;
                if (candidate >= 0 && candidate < size
                        && (excluded == null || candidate >= excluded.length || !excluded[candidate])) {
                    expanded.add(candidate);
                }
            }
        }
        indices.clear();
        indices.addAll(expanded);
    }

    private static boolean isRenderable(RoadSegmentPlacement placement) {
        return placement != null && placement.middlePos() != null
                && placement.positions() != null && !placement.positions().isEmpty();
    }

    static int targetAt(List<Integer> targetY, int index, int fallback) {
        return targetY != null && index >= 0 && index < targetY.size() && targetY.get(index) != null
                ? targetY.get(index) : fallback;
    }

    static boolean[] buildSpanMask(List<RoadSegmentPlacement> segments,
                                           List<RoadSpan> spans,
                                           SpanType type) {
        return new ExactSpans(segments, spans).mask(type);
    }

    /** 编译器只接受精确XZ；未命中返回null，与施工4格近站规则严格分开。 */
    static final class ExactSpans {
        private final List<RoadSegmentPlacement> segments;
        private final List<RoadSpan> spans;
        private final Map<SpanType, boolean[]> masks = new java.util.EnumMap<>(SpanType.class);
        private Map<Long, Integer> indices;
        ExactSpans(List<RoadSegmentPlacement> segments, List<RoadSpan> spans) {
            this.segments = segments; this.spans = spans;
        }
        boolean[] mask(SpanType type) {
            if (spans == null || spans.isEmpty()) return null;
            if (masks.containsKey(type)) return masks.get(type);
            // 无关类型不必建表；首次真正用到时只建一次，重复XZ保留最后站。
            if (spans.stream().noneMatch(span -> span != null && span.type() == type)) {
                masks.put(type, null); return null;
            }
            if (indices == null) {
                indices = new HashMap<>(segments.size() * 2);
                for (int i = 0; i < segments.size(); i++) {
                    RoadSegmentPlacement segment = segments.get(i);
                    if (segment != null && segment.middlePos() != null)
                        indices.put(RoadPhysicalReuseNormalizer.xzKey(segment.middlePos()), i);
                }
            }
            boolean[] mask = new boolean[segments.size()]; boolean found = false;
            for (RoadSpan span : spans) {
                if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
                Integer start = indices.get(RoadPhysicalReuseNormalizer.xzKey(span.start()));
                Integer end = indices.get(RoadPhysicalReuseNormalizer.xzKey(span.end()));
                if (start == null || end == null) continue;
                int from = Math.max(0, Math.min(start, end)), to = Math.min(mask.length - 1, Math.max(start, end));
                for (int i = from; i <= to; i++) mask[i] = true;
                found = true;
            }
            masks.put(type, found ? mask : null);
            return found ? mask : null;
        }
    }

    private static Projection project(int x, int z, RasterSegment segment) {
        double dx = segment.x1 - segment.x0;
        double dz = segment.z1 - segment.z0;
        double lenSq = dx * dx + dz * dz;
        double t = lenSq < 1.0E-9D
                ? 0.0D
                : Mth.clamp(((x - segment.x0) * dx + (z - segment.z0) * dz) / lenSq, 0.0D, 1.0D);
        double px = segment.x0 + t * dx;
        double pz = segment.z0 + t * dz;
        double ddx = x - px;
        double ddz = z - pz;
        return new Projection(t, ddx * ddx + ddz * ddz);
    }

    private static double smoothFalloff(double distance, double fullRadius, double zeroRadius) {
        if (distance <= fullRadius) return 1.0D;
        if (distance >= zeroRadius) return 0.0D;
        double t = (distance - fullRadius) / (zeroRadius - fullRadius);
        double x = Mth.clamp(t, 0.0D, 1.0D);
        double smooth = x * x * x * (x * (x * 6.0D - 15.0D) + 10.0D);
        return 1.0D - smooth;
    }

    private record Projection(double t, double distanceSq) {
    }

    private record RasterSegment(int x0, int z0, int y0,
                                 int x1, int z1, int y1,
                                 int halfWidth) {
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }
}
