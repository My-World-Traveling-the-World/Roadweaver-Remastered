/* 文件职责：将道路聚合数据一次性编译为区块局部世界生成计划。 */
package net.shiroha233.roadweaver.worldgen.road;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int COLUMN_COUNT = 256;
    private static final int BANK_WIDTH = 2;
    private static final int BRIDGE_TEMPLATE_MARGIN_BLOCKS = 10;
    private static final int FILL_DEPTH = 6;
    private static final float MAX_FILL = 0.5F;
    private static final float MAX_CARVE = 0.6F;
    /** A road is a swept corridor, not only its centre stations. */
    private static final int PHYSICAL_REUSE_MIN_RUN = 4;
    /** Maximum centre-line separation for a continuous five-block corridor. */
    private static final int PHYSICAL_REUSE_MAX_CENTER_DISTANCE = 5;
    /** Nearby parallel corridors need a longer run than an exact crossing. */
    private static final int PHYSICAL_REUSE_CORRIDOR_MIN_RUN = 8;
    private static final int PHYSICAL_REUSE_MAX_HEIGHT_DELTA = 1;
    private static final int PHYSICAL_REUSE_MAX_PRIMARY_INDEX_STEP = 8;

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
        List<JunctionMarker> tunnelJunctions = collectTunnelJunctions(validRoads);
        for (RoadData road : validRoads) {
            List<RoadSegmentPlacement> placements = road.roadSegmentList();
            if (placements == null || placements.size() < 2) continue;

            int halfWidth = Math.max(1, (road.width() + 1) / 2);
            // T-20C 单遍位置预筛:对不可能触及本 chunk 的道路(覆盖站点触达、
            // 桥模板 margin、占用圆盘与密度光栅的全部上界)直接跳过,不再构建
            // span 掩码、不做 markOccupied、不输出 produced 0 localIndices。
            // 预筛条件是主循环所有触达判定的上界,因此真正相交的 chunk 的
            // 编译结果不变。
            if (!roadTouchesChunk(placements, chunkPos, halfWidth)) continue;

            boolean[] bridgeMask = buildSpanMask(placements, road.spans(), SpanType.BRIDGE);
            boolean[] directCrossingMask = buildSpanMask(
                    placements, road.spans(), SpanType.ROAD_CROSSING);
            boolean[] networkReuseMask = buildSpanMask(
                    placements, road.spans(), SpanType.NETWORK_REUSE);
            boolean[] protectedGapMask = buildSpanMask(
                    placements, road.spans(), SpanType.PROTECTED_GAP);
            boolean[] tunnelJunctionMask = buildSpanMask(
                    placements, road.spans(), SpanType.TUNNEL_JUNCTION);
            boolean[] tunnelMask = buildSpanMask(placements, road.spans(), SpanType.TUNNEL);
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
                boolean touches = intersectsChunk(placement, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, featureMargin);
                if (!touches && i > 0 && placements.get(i - 1) != null) {
                    touches = segmentIntersectsChunk(placements.get(i - 1).middlePos(),
                            placement.middlePos(), chunkMinX - featureMargin, chunkMinZ - featureMargin,
                            chunkMaxX + featureMargin, chunkMaxZ + featureMargin);
                }
                if (!touches && i + 1 < placements.size() && placements.get(i + 1) != null) {
                    touches = segmentIntersectsChunk(placement.middlePos(),
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
                    LOGGER.debug("RoadWeaver compile chunk={},{} road={} produced 0 localIndices; "
                                    + "placements={} spans={} maskHits(reuse={},gap={},junction={},tunnel={},bridge={},crossing={}) "
                                    + "chunkIntersects={}",
                            chunkPos.x, chunkPos.z, fp, placements.size(),
                            road.spans() == null ? 0 : road.spans().size(),
                            countMaskHits(placements, chunkPos, networkReuseMask),
                            countMaskHits(placements, chunkPos, protectedGapMask),
                            countMaskHits(placements, chunkPos, tunnelJunctionMask),
                            countMaskHits(placements, chunkPos, tunnelMask),
                            countMaskHits(placements, chunkPos, bridgeMask),
                            countMaskHits(placements, chunkPos, directCrossingMask),
                            anyPlacementIntersectsChunk(placements, chunkPos));
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
                if (isDirectCrossing) {
                    terrainStampExclusions.add(new RasterSegment(
                            a.getX(), a.getZ(), y0,
                            b.getX(), b.getZ(), y1,
                            halfWidth));
                    continue;
                }
                int minX = Math.min(a.getX(), b.getX()) - halfWidth - BANK_WIDTH;
                int maxX = Math.max(a.getX(), b.getX()) + halfWidth + BANK_WIDTH;
                int minZ = Math.min(a.getZ(), b.getZ()) - halfWidth - BANK_WIDTH;
                int maxZ = Math.max(a.getZ(), b.getZ()) + halfWidth + BANK_WIDTH;
                if (maxX < chunkMinX || minX > chunkMaxX || maxZ < chunkMinZ || minZ > chunkMaxZ) {
                    continue;
                }
                rasterSegments.add(new RasterSegment(
                        a.getX(), a.getZ(), y0,
                        b.getX(), b.getZ(), y1,
                        halfWidth));
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
        float[] bestFill = new float[COLUMN_COUNT];
        float[] bestCarve = new float[COLUMN_COUNT];
        int[] fillY = new int[COLUMN_COUNT];
        int[] carveY = new int[COLUMN_COUNT];

        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int index = localX | (localZ << 4);
                int x = minX + localX;
                int z = minZ + localZ;
                if (isTerrainStampExcluded(x, z, exclusions)) continue;
                for (RasterSegment segment : segments) {
                    Projection projection = project(x, z, segment);
                    double target = segment.y0 + projection.t() * (segment.y1 - segment.y0);
                    int surfaceY = (int) Math.round(target);
                    double distance = Math.sqrt(projection.distanceSq());

                    double totalWidth = segment.halfWidth + BANK_WIDTH;
                    if (distance <= totalWidth) {
                        double inner = Math.max(0.0D, segment.halfWidth * 0.25D);
                        float lateral = (float) smoothFalloff(distance, inner, totalWidth);
                        lateral *= lateral;
                        if (lateral > bestFill[index]) {
                            bestFill[index] = lateral;
                            fillY[index] = surfaceY;
                        }
                    }

                    if (distance <= segment.halfWidth && 1.0F > bestCarve[index]) {
                        bestCarve[index] = 1.0F;
                        carveY[index] = surfaceY;
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

        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (bestFill[i] > 0.0F) {
                fillTargetY[i] = fillY[i];
                fillLateral[i] = bestFill[i];
            }
            if (bestCarve[i] > 0.0F) {
                carveTargetY[i] = carveY[i];
                carveLateral[i] = bestCarve[i];
            }
        }
        return new RoadDensityStamp(chunkPos, fillTargetY, carveTargetY,
                fillLateral, carveLateral, fillDepth, carveDepth, occupied, true);
    }

    private static boolean isTerrainStampExcluded(int x,
                                                   int z,
                                                   List<RasterSegment> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return false;
        for (RasterSegment exclusion : exclusions) {
            double protectedWidth = exclusion.halfWidth + BANK_WIDTH;
            if (project(x, z, exclusion).distanceSq() <= protectedWidth * protectedWidth) {
                return true;
            }
        }
        return false;
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

    /**
     * Applies the same deterministic physical ownership pass used by chunk
     * construction to read-only map consumers. This keeps map geometry from
     * displaying a secondary route over a corridor that is already owned by
     * an earlier route, including after a world reload when planning-side
     * NETWORK_REUSE metadata is no longer in memory.
     */
    public static List<RoadData> normalizeForPhysicalOwnership(Collection<RoadData> roads) {
        List<RoadData> valid = validRoads(roads);
        return normalizePhysicalOverlaps(valid);
    }

    /** Construction-side ownership guard for overlapping route footprints. */
    private static List<RoadData> normalizePhysicalOverlaps(List<RoadData> roads) {
        // ★ 2026-10-15: 单条路也要走一遍 —— 原先 size() < 2 直接返回，会让「这条
        // 路让出的那一段，编译集合里根本没有主人」这种最坏情况被跳过（孤儿让出
        // 恰恰常出现在主人已被删除、只剩它自己触到该区块的时候）。
        if (roads == null || roads.isEmpty()) return List.of();
        // Query order changes after a road replacement and is not a durable
        // ownership contract. Use the canonical road fingerprint so every
        // chunk compile selects the same physical owner.
        List<RoadData> ordered = new ArrayList<>(roads);
        ordered.sort(Comparator.comparingLong(RoadFingerprint::compute));
        List<RoadData> normalized = new ArrayList<>(roads.size());
        for (RoadData secondary : ordered) {
            if (secondary == null || secondary.roadSegmentList() == null
                    || secondary.roadSegmentList().size() < PHYSICAL_REUSE_MIN_RUN) {
                normalized.add(secondary);
                continue;
            }
            boolean[] reused = new boolean[secondary.roadSegmentList().size()];
            for (RoadData primary : normalized) markPhysicalReuse(primary, secondary, reused);
            RoadData body = hasTrue(reused) ? withPhysicalReuseSpans(secondary, reused) : secondary;
            normalized.add(verifySurrenderedReuse(body, secondary, ordered, reused));
        }
        return List.copyOf(normalized);
    }

    /** ★ 2026-10-15: see {@link #verifySurrenderedReuse}. Total, for the rate-limited log. */
    private static final java.util.concurrent.atomic.AtomicLong RECLAIMED_REUSE_STATIONS =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastReclaimLogNanos;

    /**
     * ★ 2026-10-15：**让出必须有人接** —— 不可兑现的 NETWORK_REUSE 由本路自己铺。
     *
     * <h2>缺陷</h2>
     * NETWORK_REUSE 是规划期写在**后一条路**上的元数据，含义是「这段由主路铺」，
     * 但它匹配的是主路的**规划期中心线快照**
     * （{@code PlannedPathCache.plannedReuseSpans}），而主路随后可能：
     * <ul>
     *   <li>在 abort / roadside 失败时被 {@code RoadShardStorage.deleteRoad} 删掉
     *       （{@code Road.java} 的清理路径），此后没有任何代码回头检查依赖它的路；</li>
     *   <li>在四次几何处理与 {@code trimPathNearStructure} 之后被裁短，末端比
     *       让出的那一段短几十格。</li>
     * </ul>
     * 两种情况下这段路**两头都不铺**：本路见到自己的 NETWORK_REUSE 站点就
     * {@code continue}（{@code PathFeature} 的所有权契约），主路却压根没覆盖到。
     * 玩家看到的就是「地图上有蓝线、世界里断一截」。
     *
     * <h2>修法（fail-safe：多铺一段，好过留个洞）</h2>
     * 一次编译里，对每个让出站点问一句：**本次编译集合里真有别的路铺到这儿吗？**
     * 有 ⇒ 保持让出（原有的单次铺设契约不变）；没有 ⇒ 撤销让出，本路自己铺。
     * 判据复用完全相同的匹配几何（Chebyshev 距离、高差、方向、区间长度），
     * 只是允许让出站点参与匹配 —— 原来的匹配恰恰把它们排除在外，于是**在最需要
     * 验证的地方是瞎的**。
     *
     * <p>代价：当主人路不在本次编译集合里（区块级查询）时可能重复铺一次同一段
     * 路面；两个方向都比「整段缺失」轻。确定性与世界兼容性不受影响：只改内存里
     * 的编译结果，落盘的 spans/placements 与指纹都不动。</p>
     */
    private static RoadData verifySurrenderedReuse(RoadData body, RoadData original,
                                                   List<RoadData> all, boolean[] reused) {
        if (original.spans() == null || original.spans().isEmpty()) return body;
        boolean[] surrendered = buildSpanMask(
                original.roadSegmentList(), original.spans(), SpanType.NETWORK_REUSE);
        if (surrendered == null || !hasTrue(surrendered)) return body;
        boolean[] claimed = new boolean[surrendered.length];
        for (RoadData other : all) {
            if (other == null || other == original) continue;
            markPhysicalReuse(other, original, claimed, true);
        }
        boolean[] keep = new boolean[surrendered.length];
        int dropped = 0;
        for (int i = 0; i < keep.length; i++) {
            boolean owned = reused != null && i < reused.length && reused[i];
            keep[i] = owned || (surrendered[i] && claimed[i]);
            if (surrendered[i] && !keep[i]) dropped++;
        }
        if (dropped == 0) return body;
        reportReclaimedReuse(dropped);
        return rebuildReuseSpans(body, keep);
    }

    private static void reportReclaimedReuse(int dropped) {
        long total = RECLAIMED_REUSE_STATIONS.addAndGet(dropped);
        long now = System.nanoTime();
        if (now - lastReclaimLogNanos < 10_000_000_000L) return;
        lastReclaimLogNanos = now;
        LOGGER.info("RoadWeaver reclaimed {} station(s) of NETWORK_REUSE that nobody paves "
                        + "(total {} this session); the marking road lays them itself instead of a gap.",
                dropped, total);
    }

    /** Rebuilds the road's NETWORK_REUSE spans from {@code keep}; other span types pass through. */
    private static RoadData rebuildReuseSpans(RoadData road, boolean[] keep) {
        List<RoadSpan> spans = new ArrayList<>();
        for (RoadSpan span : road.spans()) {
            if (span != null && span.type() != SpanType.NETWORK_REUSE) spans.add(span);
        }
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int start = -1;
        for (int i = 0; i <= keep.length; i++) {
            boolean value = i < keep.length && keep[i];
            if (value && start < 0) start = i;
            if (!value && start >= 0) {
                BlockPos from = segments.get(start).middlePos();
                BlockPos to = segments.get(i - 1).middlePos();
                if (from != null && to != null) {
                    spans.add(new RoadSpan(from, to, SpanType.NETWORK_REUSE));
                }
                start = -1;
            }
        }
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                segments, spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    private static boolean hasTrue(boolean[] values) {
        if (values == null) return false;
        for (boolean value : values) if (value) return true;
        return false;
    }

    private static void markPhysicalReuse(RoadData primary, RoadData secondary, boolean[] reused) {
        markPhysicalReuse(primary, secondary, reused, false);
    }

    /**
     * ★ 2026-10-15：同一个匹配过程，两种用途。
     *
     * <p>{@code matchReuseStations == false}（原有语义）：次路的 NETWORK_REUSE 站点
     * 视作「已让出」，不参与匹配 —— 它已经不属于自己，不能被别的路再认领一次。</p>
     *
     * <p>{@code matchReuseStations == true}（新增）：**反过来问「这段让出去的
     * 路，真的有人铺吗」**。让出是规划期写下的元数据（
     * {@code PlannedPathCache.plannedReuseSpans} 匹配的是主路的**规划期中心线快照**），
     * 而主路可能随后被删（{@code Road.java} 的 abort 路径会
     * {@code RoadShardStorage.deleteRoad}）或在四次几何处理里被裁短。那样这段路
     * **两头都不铺**：次路在这里跳过（{@code PathFeature} 见 NETWORK_REUSE 即
     * continue），主路却根本没有覆盖到 —— 地图上有蓝线，世界里是空的。</p>
     */
    private static void markPhysicalReuse(RoadData primary, RoadData secondary, boolean[] reused,
                                          boolean matchReuseStations) {
        if (primary == null || secondary == null || reused == null
                || primary.roadSegmentList() == null || primary.roadSegmentList().size() < 2
                || primary.roadType() != secondary.roadType()) return;
        // A primary NETWORK_REUSE span still describes the physical corridor
        // owned by an earlier road and may be used as the canonical owner.
        // A secondary NETWORK_REUSE span is already non-owning and must not be
        // claimed again by another later route.
        boolean[] primaryBlocked = physicalReuseBlocked(primary, false);
        boolean[] secondaryBlocked = physicalReuseBlocked(secondary, !matchReuseStations);
        List<RoadSegmentPlacement> pri = primary.roadSegmentList();
        List<RoadSegmentPlacement> sec = secondary.roadSegmentList();
        SpanKind[] primaryKinds = spanKinds(primary);
        SpanKind[] secondaryKinds = spanKinds(secondary, !matchReuseStations);
        int[] matches = new int[sec.size()];
        boolean[] corridorMatches = new boolean[sec.size()];
        Arrays.fill(matches, -1);
        for (int i = 0; i < sec.size(); i++) {
            if (secondaryBlocked[i]) continue;
            RoadSegmentPlacement candidate = sec.get(i);
            if (candidate == null || candidate.middlePos() == null) continue;
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            boolean bestCorridorMatch = false;
            for (int j = 0; j < pri.size(); j++) {
                if (primaryBlocked[j]) continue;
                RoadSegmentPlacement owner = pri.get(j);
                if (owner == null || owner.middlePos() == null) continue;
                int distance = Math.max(Math.abs(candidate.middlePos().getX() - owner.middlePos().getX()),
                        Math.abs(candidate.middlePos().getZ() - owner.middlePos().getZ()));
                boolean sameTunnel = primaryKinds[j] == SpanKind.TUNNEL
                        && secondaryKinds[i] == SpanKind.TUNNEL;
                if (distance > 64 || !physicalSpanKindCompatible(primaryKinds[j], secondaryKinds[i])
                        || !physicalDirectionCompatible(sec, i, pri, j)) continue;
                // Tunnel entrances can be vertically offset while still being
                // the same directional passage. Once the two tunnel routes
                // are near each other and point the same way, reuse the
                // existing tunnel instead of excavating a second upper/lower
                // tunnel. Surface roads retain the strict footprint/grade
                // checks below.
                boolean exactFootprint = footprintsIntersect(owner, candidate);
                if (!sameTunnel && ((!exactFootprint
                        && distance > PHYSICAL_REUSE_MAX_CENTER_DISTANCE)
                        || Math.abs(targetAt(secondary.targetY(), i,
                        candidate.middlePos().getY()) - targetAt(primary.targetY(), j,
                        owner.middlePos().getY())) > PHYSICAL_REUSE_MAX_HEIGHT_DELTA)) continue;
                // Two tunnels that only happen to be within 64 blocks in the
                // same direction are separate excavations. Reusing the primary
                // there would suppress the secondary's tunnel without the
                // primary actually building this chunk, leaving a gap. Only
                // treat them as the same passage when the footprints overlap.
                if (sameTunnel && !exactFootprint) continue;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = j;
                    bestCorridorMatch = !sameTunnel && !exactFootprint;
                }
            }
            matches[i] = best;
            corridorMatches[i] = best >= 0 && bestCorridorMatch;
        }

        int runStart = -1;
        int previousPrimary = -1;
        boolean corridorRun = false;
        for (int i = 0; i <= matches.length; i++) {
            int current = i < matches.length ? matches[i] : -1;
            boolean contiguous = current >= 0 && (runStart < 0
                    || Math.abs(current - previousPrimary) <= PHYSICAL_REUSE_MAX_PRIMARY_INDEX_STEP);
            if (contiguous) {
                if (runStart < 0) runStart = i;
                corridorRun |= corridorMatches[i];
                previousPrimary = current;
                continue;
            }
            int requiredRun = corridorRun
                    ? PHYSICAL_REUSE_CORRIDOR_MIN_RUN : PHYSICAL_REUSE_MIN_RUN;
            if (runStart >= 0 && i - runStart >= requiredRun) {
                for (int index = runStart; index < i; index++) reused[index] = true;
            }
            runStart = current >= 0 ? i : -1;
            corridorRun = current >= 0 && corridorMatches[i];
            previousPrimary = current;
        }
    }

    private static boolean physicalDirectionCompatible(List<RoadSegmentPlacement> first, int firstIndex,
                                                        List<RoadSegmentPlacement> second, int secondIndex) {
        double[] a = localDirection(first, firstIndex);
        double[] b = localDirection(second, secondIndex);
        return a == null || b == null || Math.abs(a[0] * b[0] + a[1] * b[1]) >= 0.75D;
    }

    private static double[] localDirection(List<RoadSegmentPlacement> segments, int index) {
        if (segments == null || segments.size() < 2) return null;
        BlockPos first = segments.get(Math.max(0, index - 1)).middlePos();
        BlockPos last = segments.get(Math.min(segments.size() - 1, index + 1)).middlePos();
        if (first == null || last == null) return null;
        double dx = last.getX() - first.getX();
        double dz = last.getZ() - first.getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[]{dx / length, dz / length};
    }

    private static boolean[] physicalReuseBlocked(RoadData road, boolean blockNetworkReuse) {
        boolean[] blocked = new boolean[road.roadSegmentList().size()];
        if (road.spans() == null) return blocked;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(xzKey(segment.middlePos()), i);
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.start() == null || span.end() == null) continue;
            boolean blocksReuse = switch (span.type()) {
                case BRIDGE, ROAD_CROSSING, PROTECTED_GAP -> true;
                case NETWORK_REUSE -> blockNetworkReuse;
                // Tunnel spans can be physical owners. They are filtered by
                // physicalSpanKindCompatible before a secondary is claimed.
                default -> false;
            };
            if (!blocksReuse) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) blocked[i] = true;
        }
        return blocked;
    }

    private static boolean physicalSpanKindCompatible(SpanKind first, SpanKind second) {
        if (first == SpanKind.TUNNEL || second == SpanKind.TUNNEL) {
            return first == SpanKind.TUNNEL && second == SpanKind.TUNNEL;
        }
        return first == SpanKind.ORDINARY && second == SpanKind.ORDINARY;
    }

    private static SpanKind[] spanKinds(RoadData road) {
        return spanKinds(road, true);
    }

    /**
     * @param networkReuseIsOther 为 true（原有语义）时 NETWORK_REUSE 站点算
     *        {@link SpanKind#OTHER}，于是既不能当主人、也不能被认领；
     *        为 false 时它们保留物理类别（通常是 ORDINARY）—— 只用于
     *        {@code matchReuseStations == true} 那一问：「这段让出去的路，
     *        真的有人铺吗」。让出这个标记本身不该成为「没人能铺」的理由。
     */
    private static SpanKind[] spanKinds(RoadData road, boolean networkReuseIsOther) {
        int size = road == null || road.roadSegmentList() == null ? 0 : road.roadSegmentList().size();
        SpanKind[] kinds = new SpanKind[size];
        Arrays.fill(kinds, SpanKind.ORDINARY);
        markSpanKind(kinds, road, SpanType.TUNNEL, SpanKind.TUNNEL);
        markSpanKind(kinds, road, SpanType.TUNNEL_JUNCTION, SpanKind.TUNNEL);
        markSpanKind(kinds, road, SpanType.BRIDGE, SpanKind.OTHER);
        markSpanKind(kinds, road, SpanType.ROAD_CROSSING, SpanKind.OTHER);
        markSpanKind(kinds, road, SpanType.PROTECTED_GAP, SpanKind.OTHER);
        if (networkReuseIsOther) {
            markSpanKind(kinds, road, SpanType.NETWORK_REUSE, SpanKind.OTHER);
        }
        return kinds;
    }

    private static void markSpanKind(SpanKind[] kinds, RoadData road,
                                     SpanType type, SpanKind kind) {
        if (road == null || road.spans() == null || kinds.length == 0) return;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) {
                indices.putIfAbsent(xzKey(segment.middlePos()), i);
            }
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end) && i < kinds.length; i++) {
                kinds[i] = kind;
            }
        }
    }

    private enum SpanKind {
        ORDINARY,
        TUNNEL,
        OTHER
    }

    private static RoadData withPhysicalReuseSpans(RoadData road, boolean[] reused) {
        List<RoadSpan> spans = new ArrayList<>(road.spans());
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        int start = -1;
        for (int i = 0; i <= reused.length; i++) {
            boolean value = i < reused.length && reused[i];
            if (value && start < 0) start = i;
            if (!value && start >= 0) {
                int end = i - 1;
                if (end - start + 1 >= PHYSICAL_REUSE_MIN_RUN) {
                    BlockPos from = segments.get(start).middlePos();
                    BlockPos to = segments.get(end).middlePos();
                    if (from != null && to != null) spans.add(new RoadSpan(from, to, SpanType.NETWORK_REUSE));
                }
                start = -1;
            }
        }
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                segments, spans, road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    private static List<JunctionMarker> collectTunnelJunctions(List<RoadData> roads) {
        List<JunctionMarker> markers = new ArrayList<>();
        for (RoadData road : roads) {
            List<RoadSegmentPlacement> placements = road.roadSegmentList();
            boolean[] mask = buildSpanMask(placements, road.spans(), SpanType.TUNNEL_JUNCTION);
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
                if (!footprintsIntersect(placement, marker.placement())
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

    private static boolean footprintsIntersect(RoadSegmentPlacement first, RoadSegmentPlacement second) {
        Set<Long> footprint = new HashSet<>();
        addFootprint(footprint, first);
        if (second.middlePos() != null && footprint.contains(xzKey(second.middlePos()))) return true;
        if (second.positions() != null) {
            for (BlockPos pos : second.positions()) {
                if (pos != null && footprint.contains(xzKey(pos))) return true;
            }
        }
        return false;
    }

    private static void addFootprint(Set<Long> footprint, RoadSegmentPlacement placement) {
        if (placement.middlePos() != null) footprint.add(xzKey(placement.middlePos()));
        if (placement.positions() != null) {
            for (BlockPos pos : placement.positions()) {
                if (pos != null) footprint.add(xzKey(pos));
            }
        }
    }

    private static long xzKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    private record JunctionMarker(RoadData owner, RoadSegmentPlacement placement, int y) {
    }

    /** Task 3 diagnostic helper kept private: 1-block sampling of one centre edge. */
    private static boolean segmentIntersectsChunk(BlockPos from, BlockPos to,
                                                  int minX, int minZ, int maxX, int maxZ) {
        if (from == null || to == null) return false;
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return true;
        }
        return false;
    }

    /** Task 16 diagnostic: stations of one span mask inside the chunk bounds. */
    private static int countMaskHits(List<RoadSegmentPlacement> placements,
                                     ChunkPos chunkPos,
                                     boolean[] mask) {
        if (mask == null) return 0;
        int hits = 0;
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();
        for (int i = 0; i < placements.size() && i < mask.length; i++) {
            if (!mask[i]) continue;
            RoadSegmentPlacement placement = placements.get(i);
            if (placement == null || placement.middlePos() == null) continue;
            int x = placement.middlePos().getX();
            int z = placement.middlePos().getZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) hits++;
        }
        return hits;
    }

    /** Task 16 diagnostic: whether any placement footprint touches the chunk. */
    private static boolean anyPlacementIntersectsChunk(List<RoadSegmentPlacement> placements,
                                                       ChunkPos chunkPos) {
        for (RoadSegmentPlacement placement : placements) {
            if (placement == null) continue;
            if (intersectsChunk(placement,
                    chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ(), 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T-20C: 单遍位置预筛。一条道路可能影响本 chunk 的全部途径及其上界:
     * <ul>
     *   <li>站点触达(localIndices)——margin 0,桥模板站 margin
     *       {@value #BRIDGE_TEMPLATE_MARGIN_BLOCKS};</li>
     *   <li>占用标记(markOccupied)——足迹列在 chunk 内,或中点圆盘半径
     *       min(16, halfWidth+1);</li>
     *   <li>密度光栅(rasterSegments)——中点对 bbox 外扩 halfWidth+BANK_WIDTH。</li>
     * </ul>
     * 预筛 margin 取上述上界,因此被拒绝的道路对这些产物的影响全部为空,
     * 真正相交 chunk 的编译结果不变。单次 O(站位+足迹+邻接边采样)。
     */
    private static boolean roadTouchesChunk(List<RoadSegmentPlacement> placements,
                                            ChunkPos chunkPos, int halfWidth) {
        int margin = Math.max(BRIDGE_TEMPLATE_MARGIN_BLOCKS, halfWidth + BANK_WIDTH + 1);
        int minBoundaryX = chunkPos.getMinBlockX() - margin;
        int minBoundaryZ = chunkPos.getMinBlockZ() - margin;
        int maxBoundaryX = chunkPos.getMaxBlockX() + margin;
        int maxBoundaryZ = chunkPos.getMaxBlockZ() + margin;
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int chunkMaxX = chunkPos.getMaxBlockX();
        int chunkMaxZ = chunkPos.getMaxBlockZ();
        BlockPos previousMiddle = null;
        for (RoadSegmentPlacement placement : placements) {
            if (placement == null) {
                previousMiddle = null;
                continue;
            }
            BlockPos middle = placement.middlePos();
            if (middle != null
                    && middle.getX() >= minBoundaryX && middle.getX() <= maxBoundaryX
                    && middle.getZ() >= minBoundaryZ && middle.getZ() <= maxBoundaryZ) {
                return true;
            }
            List<BlockPos> positions = placement.positions();
            if (positions != null) {
                for (BlockPos position : positions) {
                    if (position != null
                            && position.getX() >= chunkMinX && position.getX() <= chunkMaxX
                            && position.getZ() >= chunkMinZ && position.getZ() <= chunkMaxZ) {
                        return true;
                    }
                }
            }
            if (previousMiddle != null && middle != null
                    && segmentIntersectsChunk(previousMiddle, middle,
                    minBoundaryX, minBoundaryZ, maxBoundaryX, maxBoundaryZ)) {
                return true;
            }
            previousMiddle = middle;
        }
        return false;
    }

    private static boolean intersectsChunk(RoadSegmentPlacement placement,
                                           int minX, int minZ, int maxX, int maxZ,
                                           int margin) {        int safeMargin = Math.max(0, margin);
        int queryMinX = minX - safeMargin;
        int queryMinZ = minZ - safeMargin;
        int queryMaxX = maxX + safeMargin;
        int queryMaxZ = maxZ + safeMargin;
        if (placement.positions() != null) {
            for (BlockPos position : placement.positions()) {
                if (position != null && position.getX() >= queryMinX && position.getX() <= queryMaxX
                        && position.getZ() >= queryMinZ && position.getZ() <= queryMaxZ) {
                    return true;
                }
            }
        }
        BlockPos middle = placement.middlePos();
        return middle != null && middle.getX() >= queryMinX && middle.getX() <= queryMaxX
                && middle.getZ() >= queryMinZ && middle.getZ() <= queryMaxZ;
    }

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

    private static int targetAt(List<Integer> targetY, int index, int fallback) {
        return targetY != null && index >= 0 && index < targetY.size() && targetY.get(index) != null
                ? targetY.get(index) : fallback;
    }

    private static boolean[] buildSpanMask(List<RoadSegmentPlacement> segments,
                                           List<RoadSpan> spans,
                                           SpanType type) {
        if (spans == null || spans.isEmpty()) return null;
        Map<Long, Integer> indexByPosition = new HashMap<>(segments.size() * 2);
        for (int i = 0; i < segments.size(); i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment != null && segment.middlePos() != null) {
                indexByPosition.put(xzKey(segment.middlePos()), i);
            }
        }
        boolean[] mask = new boolean[segments.size()];
        boolean found = false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
            Integer start = indexByPosition.get(xzKey(span.start()));
            Integer end = indexByPosition.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            int from = Math.max(0, Math.min(start, end));
            int to = Math.min(mask.length - 1, Math.max(start, end));
            for (int i = from; i <= to; i++) mask[i] = true;
            found = true;
        }
        return found ? mask : null;
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
