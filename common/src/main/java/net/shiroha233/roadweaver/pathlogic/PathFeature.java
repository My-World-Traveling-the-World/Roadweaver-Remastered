/* 文件职责：实现普通道路的世界生成入口。 */
package net.shiroha233.roadweaver.pathlogic;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegment;
import net.shiroha233.roadweaver.config.PathFeatureConfig;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.decoration.system.DecorationExecutor;
import net.shiroha233.roadweaver.decoration.system.DecorationPlanner;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeRangeCalculator;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlanner;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeSegmentPlannerNew;
import net.shiroha233.roadweaver.pathlogic.grade.RoadConstructionSafety;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation;
import net.shiroha233.roadweaver.pathlogic.construction.SegmentPaver;
import net.shiroha233.roadweaver.pathlogic.structures.StructureAvoidanceService;
import net.shiroha233.roadweaver.pathlogic.pathcalc.HeightProfileService;
import net.shiroha233.roadweaver.pathlogic.bridge.BuoyBuilder;
import net.shiroha233.roadweaver.pathlogic.bridge.BuoyMarkerPlanner;
import net.shiroha233.roadweaver.decoration.system.SkippedBridgeBankSignPlanner;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkPlan;
import net.shiroha233.roadweaver.worldgen.road.RoadChunkSlice;
import net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache;
import net.shiroha233.roadweaver.persistence.RoadConstructionLedger;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.util.Line;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 道路世界生成 Feature
 */
public class PathFeature extends Feature<PathFeatureConfig> {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** T-20C: 空计划(擦边)chunk 聚合诊断计数与周期性摘要窗口。 */
    private static final java.util.concurrent.atomic.AtomicLong EMPTY_WORLDGEN_PLAN_CHUNKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong EMPTY_WORLDGEN_PLAN_SUMMARY_AT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long EMPTY_WORLDGEN_PLAN_SUMMARY_INTERVAL_MS = 60_000L;
    /**
     * T-25 终态:施工拒绝且局部修复(PROTECTED_GAP)不可用、已被标记为永久放弃的
     * chunk 计数与摘要窗口。用于确认"不再重试"是否真的生效(该值应收敛而非单调增长)。
     */
    private static final java.util.concurrent.atomic.AtomicLong ROAD_CHUNKS_ABANDONED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ROAD_CHUNKS_ABANDONED_SUMMARY_AT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long ROAD_CHUNKS_ABANDONED_SUMMARY_INTERVAL_MS = 60_000L;

    public PathFeature(Codec<PathFeatureConfig> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<PathFeatureConfig> ctx) {
        WorldGenLevel world = ctx.level();
        Level lvl = world.getLevel();
        if (!(lvl instanceof ServerLevel server) || !Level.OVERWORLD.equals(server.dimension()))
            return false;

        return placeChunk(world, server, new ChunkPos(ctx.origin()), ctx.random());
    }

    /** Shared construction entry for normal worldgen and late-published roads in already-full chunks. */
    public static boolean placeChunk(WorldGenLevel world,
                                     ServerLevel server,
                                     ChunkPos currentChunk,
                                     RandomSource random) {
        if (world == null || server == null || currentChunk == null || random == null
                || !Level.OVERWORLD.equals(server.dimension())) return false;

        ModConfig cfg = ConfigService.get();
        if (!cfg.roadAppearance().roadsEnabled())
            return false;

        try {
            RoadChunkPlan plan = RoadWorldgenPlanCache.get(server, currentChunk, cfg);
            if (plan.slices().isEmpty()) {
                // A chunk that still lists road shards but compiles to an empty
                // plan means the worldgen plan cache is stale or the compiler
                // pruned the road here. This is the missing-construction case.
                var shards = RoadShardStorage.queryChunk(server, currentChunk);
                if (shards != null && !shards.isEmpty()) {
                    // Task 4: shard archives cover the width-radius disc around
                    // every station (RoadFootprint.addDisk), so a chunk touched
                    // only by that disc — or by a corridor replanned elsewhere
                    // since — compiles to an empty plan without being a gap.
                    // A real gap shows up in the compiler's 'produced 0
                    // localIndices' diagnostic with nonzero maskHits or
                    // chunkIntersects=true. Those compiler-internal counters
                    // are unavailable at this call site; the shard fingerprints
                    // and span counts below identify the roads to cross-check.
                    // T-20C: 明细降级为 debug(仅在 debug 开启时承担指纹计算
                    // 成本),WARN 洪峰改为周期性一条聚合 INFO。
                    if (LOGGER.isDebugEnabled()) {
                        for (RoadData shardRoad : shards) {
                            if (shardRoad == null) continue;
                            LOGGER.debug("RoadWeaver empty worldgen plan detail chunk={},{} shardRoadId={} "
                                            + "stations={} spans={} compilerMaskHits=unavailable "
                                            + "compilerChunkIntersects=unavailable compilerLocalIndices=unavailable",
                                    currentChunk.x, currentChunk.z,
                                    RoadShardStorage.computeFingerprint(shardRoad),
                                    shardRoad.roadSegmentList() == null ? 0 : shardRoad.roadSegmentList().size(),
                                    shardRoad.spans() == null ? 0 : shardRoad.spans().size());
                        }
                        LOGGER.debug("RoadWeaver chunk has {} road shard(s) but empty worldgen plan chunk={},{} "
                                        + "(benign when the road disc only grazes this chunk or the route moved; "
                                        + "cross-check the compiler 'produced 0 localIndices' diagnostic)",
                                shards.size(), currentChunk.x, currentChunk.z);
                    }
                    EMPTY_WORLDGEN_PLAN_CHUNKS.incrementAndGet();
                    logEmptyWorldgenPlanSummary();
                }
                return false;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RoadWeaver chunk plan chunk={},{} slices={} revision={}",
                        currentChunk.x, currentChunk.z, plan.slices().size(),
                        plan.revision());
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    prepareConstructionChunk(
                            world, new ChunkPos(currentChunk.x + dx, currentChunk.z + dz));
                }
            }
            int averagingRadius = Math.max(0, cfg.averagingRadius());
            Set<ProcessedSegment> processedSegments = new HashSet<>();
            Set<Decoration> decorations = new HashSet<>();
            Set<Long> completedRoads = new HashSet<>();
            Set<Long> incompleteRoads = new HashSet<>();
            Map<Long, RefusalAccumulator> refusals = new LinkedHashMap<>();

            processRoadSlices(world, server, currentChunk, plan.slices(), cfg, random,
                    averagingRadius, processedSegments, decorations, completedRoads, incompleteRoads, refusals);

            // T-24: 施工拒绝(fill-too-deep / protected-*)的连片站落为
            // PROTECTED_GAP 并把 RoadData 原位替换一次;替换会使计划缓存失效,
            // 随后用新鲜计划重跑一轮 —— 缺口站被既有掩码跳过(不再铺装、不再
            // 打密度 stamp、不再放路灯),剩余站正常铺装并记账,chunk 由此按
            // 完成处理,不再进入 1-5 秒重试循环。只允许一轮替换。
            boolean gapRepairApplied = false;
            if (!refusals.isEmpty() && persistConstructionRefusalGaps(server, refusals)) {
                gapRepairApplied = true;
                RoadChunkPlan refreshedPlan = RoadWorldgenPlanCache.get(server, currentChunk, cfg);
                if (!refreshedPlan.slices().isEmpty()) {
                    refusals = new LinkedHashMap<>();
                    processRoadSlices(world, server, currentChunk, refreshedPlan.slices(), cfg, random,
                            averagingRadius, processedSegments, decorations, completedRoads, incompleteRoads, refusals);
                }
            }

            DecorationExecutor.tryPlaceDecorations(decorations);
            for (long roadFingerprint : completedRoads) {
                RoadConstructionLedger.markComplete(server, currentChunk, roadFingerprint);
            }

            // T-25 终态:局部修复(PROTECTED_GAP 原位替换)没能救回来时不再重试。
            //
            // 2026-09-16 真机事故:某条路的隧道 span 因 fluid-in-wall-volume 被拒,
            // 地表回退又因 fill-too-deep 失败,而失败站全部落在既有 TUNNEL span 的
            // covered 掩码内 —— 于是 withConstructionRefusalGapSpans() 返回 null,
            // 缺口一个都加不上,chunk 永远 incomplete。后果是每 60 秒重规划一次、
            // 每次刷 168 行 WARN(单存档累计 13224 次 tunnel rejected),主线程被
            // 日志 I/O 拖死;重启后 recover() 又按台账把这些 chunk 捞回队列,
            // 形成跨会话死循环。
            //
            // 用户定的停止准则:「留个缺口,局部修复救不了就不救了」。
            //
            // 🔴 关键设计:终态必须写进 RoadConstructionLedger(有持久化),而不是
            // DeferredInitialRoadConstructionService.NO_CONSTRUCTION_REQUIRED(纯
            // 内存态)。因为「是否还需要施工」的三处判定全部只读 ledger:
            //   ① DeferredInitialRoadConstructionService.recover()  —— 重启后重入队
            //   ② InitialRoadConstructionService.verifyConstruction() —— 开局校验会抛异常
            //   ③ LoadedChunkRoadBackfill.isConstructionComplete()   —— 每 60 秒回填
            // 写进 ledger 后三处自动跳过,且重启不再复活;若只写内存态,①会复活死循环、
            // ②会让开局直接抛 IllegalStateException(比修复前更糟)。
            boolean abandoned = false;
            if (!incompleteRoads.isEmpty() && completedRoads.isEmpty()
                    && !refusals.isEmpty() && !gapRepairApplied) {
                abandoned = true;
                for (long roadFingerprint : incompleteRoads) {
                    RoadConstructionLedger.markComplete(server, currentChunk, roadFingerprint);
                }
                ROAD_CHUNKS_ABANDONED.incrementAndGet();
                LOGGER.warn("RoadWeaver chunk={},{} 施工拒绝且局部修复(PROTECTED_GAP)不可用,"
                                + "已记台账为终态(留缺口,不再重试)。roads={}",
                        currentChunk.x, currentChunk.z, incompleteRoads.size());
                logAbandonedSummary();
            } else if (!incompleteRoads.isEmpty() && completedRoads.isEmpty() && !gapRepairApplied) {
                LOGGER.warn("RoadWeaver chunk={},{} has road slice(s) but none completed; "
                                + "likely tunnel/volume rejected",
                        currentChunk.x, currentChunk.z);
            }
            // 终态放弃的 chunk 按"已处理"返回 true,调用方不再把它当待施工候选。
            return !completedRoads.isEmpty() || abandoned;
        } finally {
            HugeTreeReservation.releaseChunk(server, currentChunk);
        }
    }

    /** T-24: 单次 placeChunk 的切片处理循环(替换后用新鲜计划重跑一次)。 */
    private static void processRoadSlices(WorldGenLevel world,
            ServerLevel server,
            ChunkPos currentChunk,
            List<RoadChunkSlice> slices,
            ModConfig cfg,
            RandomSource random,
            int averagingRadius,
            Set<ProcessedSegment> processedSegments,
            Set<Decoration> decorations,
            Set<Long> completedRoads,
            Set<Long> incompleteRoads,
            Map<Long, RefusalAccumulator> refusals) {
        for (RoadChunkSlice slice : slices) {
            long roadFingerprint = RoadShardStorage.computeFingerprint(slice.road());
            if (RoadConstructionLedger.isComplete(server, currentChunk, roadFingerprint)) continue;
            boolean completed = processRoadSlice(world, server, currentChunk, slice,
                    processedSegments, decorations, random, cfg, averagingRadius, refusals);
            if (completed) {
                completedRoads.add(roadFingerprint);
                incompleteRoads.remove(roadFingerprint);
            } else {
                incompleteRoads.add(roadFingerprint);
                completedRoads.remove(roadFingerprint);
            }
        }
    }

    /**
     * T-24: 把施工拒绝的连续站合并为 PROTECTED_GAP span 并原位替换道路数据
     * (每条路至多一次)。替换使计划缓存按受影响 chunk 失效;带缺口 span 的
     * chunk 重新编译后跳过这些站,施工台账按完成记账。水/结构校验自身的
     * 保护缺口语义不受影响。
     */
    private static boolean persistConstructionRefusalGaps(ServerLevel server,
                                                          Map<Long, RefusalAccumulator> refusals) {
        if (refusals.isEmpty()) return false;
        // C1：原先对每个 accumulator 单独调一次 replaceRoads，单次调用会让
        // RoadChunkStore.persistSnapshot 以 previous ∪ next 全量遍历 + 写全部分片
        // （实测 6094 个 chunk 分片）。同一 chunk 的多个 slice 会触发多次全量落盘，
        // 叠在主线程上。这里先收集全部替换，一次性提交，落盘次数由 N 降为 1。
        List<RoadReplacement> replacements = new ArrayList<>(refusals.size());
        List<RefusalAccumulator> accepted = new ArrayList<>(refusals.size());
        for (RefusalAccumulator accumulator : refusals.values()) {
            RoadData updated = accumulator.withConstructionRefusalGapSpans();
            if (updated == null) continue;
            replacements.add(new RoadReplacement(accumulator.fingerprint(), updated));
            accepted.add(accumulator);
        }
        if (replacements.isEmpty()) return false;
        try {
            RoadShardStorage.replaceRoads(server, replacements);
        } catch (RuntimeException failure) {
            // 批量提交失败时不谎报成功：让 chunk 保持未完成，走既有重试路径。
            for (RefusalAccumulator accumulator : accepted) {
                LOGGER.warn("RoadWeaver could not persist construction refusal gaps road={}",
                        accumulator.fingerprint(), failure);
            }
            return false;
        }
        for (RefusalAccumulator accumulator : accepted) {
            LOGGER.info("RoadWeaver persisted construction refusal gaps road={} refusedStations={} "
                            + "(chunk completes; retry loop ends)",
                    accumulator.fingerprint(), accumulator.stations().size());
        }
        return true;
    }

    /** T-24: 单条道路的施工拒绝累积(站点索引 -> 拒绝原因)。 */
    private static final class RefusalAccumulator {
        private final RoadData road;
        private final long fingerprint;
        private final java.util.TreeMap<Integer, String> stations = new java.util.TreeMap<>();

        RefusalAccumulator(RoadData road, long fingerprint) {
            this.road = road;
            this.fingerprint = fingerprint;
        }

        java.util.TreeMap<Integer, String> stations() {
            return stations;
        }

        long fingerprint() {
            return fingerprint;
        }

        /**
         * 连续失败站合并为 PROTECTED_GAP span。与既有 span 重叠的站跳过
         * (隧道/桥/复用走廊的归属不变);合并后道路必须通过最终校验,
         * 否则返回 null(保持既有重试行为)。
         */
        RoadData withConstructionRefusalGapSpans() {
            List<RoadSegmentPlacement> segments = road.roadSegmentList();
            if (stations.isEmpty() || segments == null || segments.size() < 3) return null;
            Map<Long, Integer> indexByMiddle = new HashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                RoadSegmentPlacement segment = segments.get(i);
                if (segment != null && segment.middlePos() != null) {
                    indexByMiddle.putIfAbsent(xzKey(segment.middlePos()), i);
                }
            }
            boolean[] covered = new boolean[segments.size()];
            for (RoadSpan span : road.spans() == null ? List.<RoadSpan>of() : road.spans()) {
                if (span == null || span.start() == null || span.end() == null) continue;
                Integer a = indexByMiddle.get(xzKey(span.start()));
                Integer b = indexByMiddle.get(xzKey(span.end()));
                if (a == null || b == null) continue;
                for (int i = Math.min(a, b); i <= Math.max(a, b); i++) covered[i] = true;
            }
            List<RoadSpan> spans = new ArrayList<>(road.spans() == null ? List.of() : road.spans());
            Integer runStart = null;
            Integer runEnd = null;
            boolean added = false;
            for (java.util.Map.Entry<Integer, String> entry : stations.entrySet()) {
                int index = entry.getKey();
                if (index < 0 || index >= segments.size() || covered[index]) continue;
                if (runStart != null && index == runEnd + 1) {
                    runEnd = index;
                    continue;
                }
                if (runStart != null) {
                    spans.add(new RoadSpan(segments.get(runStart).middlePos(),
                            segments.get(runEnd).middlePos(), SpanType.PROTECTED_GAP));
                    added = true;
                }
                runStart = index;
                runEnd = index;
            }
            if (runStart != null) {
                spans.add(new RoadSpan(segments.get(runStart).middlePos(),
                        segments.get(runEnd).middlePos(), SpanType.PROTECTED_GAP));
                added = true;
            }
            if (!added) return null;
            RoadData updated = new RoadData(road.width(), road.roadType(), road.materials(),
                    road.slabMaterials(), segments, List.copyOf(spans), road.targetY(),
                    road.ownerA2dKey(), road.ownerB2dKey());
            return RoadDataValidator.validateFinalRoad(updated).valid() ? updated : null;
        }
    }

    private static boolean processRoadSlice(WorldGenLevel world,
            ServerLevel server,
            ChunkPos currentChunk,
            RoadChunkSlice slice,
            Set<ProcessedSegment> processedSegments,
            Set<Decoration> decorations,
            RandomSource random,
            ModConfig cfg,
            int averagingRadius,
            Map<Long, RefusalAccumulator> refusals) {
        if (!cfg.roadAppearance().roadsEnabled()) {
            return true;
        }

        RoadData data = slice.road();
        long roadFingerprint = RoadShardStorage.computeFingerprint(data);
        RefusalAccumulator refusalSink = refusals.computeIfAbsent(roadFingerprint,
                ignored -> new RefusalAccumulator(data, roadFingerprint));
        boolean[] constructionRefused = new boolean[data.roadSegmentList() == null ? 0 : data.roadSegmentList().size()];
        boolean bridgeEnabled = cfg.bridgeEnabled();
        int roadType = data.roadType();
        if (roadType != 0 && roadType != 1) {
            LOGGER.warn("RoadWeaver rejected malformed road slice with unsupported roadType={} chunk={} road={}",
                    roadType, currentChunk, roadFingerprint);
            return false;
        }
        
        int roadWidth = Math.max(1, data.width());
        List<BlockState> materials = data.materials();
        List<BlockState> slabMaterials = data.slabMaterials();
        List<RoadSegmentPlacement> segments = data.roadSegmentList();
        if (segments == null || segments.size() < 5) {
            LOGGER.warn("RoadWeaver rejected malformed road slice with too few segments chunk={} road={} segments={}",
                    currentChunk, roadFingerprint, segments == null ? 0 : segments.size());
            return false;
        }
        boolean[] completed = {true};

        List<BlockPos> middlePositions = segments.stream().map(RoadSegmentPlacement::middlePos).toList();
        BridgeRangeCalculator.RangeResult plannedBridgeResult =
                BridgeRangeCalculator.compute(middlePositions, data.spans(), cfg);
        BridgeRangeCalculator.RangeResult res = RoadConstructionSafety.promoteRuntimeBridges(
                world, currentChunk, data, middlePositions, plannedBridgeResult);
        boolean[] isBridge = res.isBridge();
        List<int[]> bridgeRanges = res.mergedRanges();
        boolean[] skipSegments = res.skipSegments();
        boolean[] preserveCrossingGrade = buildPreserveCrossingGradeMask(
                middlePositions, data, cfg.bridgeRampSegments());
        boolean[] directCrossing = buildSpanMask(middlePositions, data, SpanType.ROAD_CROSSING);
        boolean[] networkReuse = buildSpanMask(middlePositions, data, SpanType.NETWORK_REUSE);
        boolean[] protectedGap = buildSpanMask(middlePositions, data, SpanType.PROTECTED_GAP);
        boolean[] tunnelMask = buildSpanMask(middlePositions, data, SpanType.TUNNEL);

        boolean useBuoysInstead = bridgeEnabled && cfg.bridgeUseBuoysInstead();
        boolean useBuoysWhenSkipped = bridgeEnabled && cfg.bridgeUseBuoysWhenSkipped();

        int intervalBlocks = Math.max(4, cfg.buoyIntervalBlocks());
        boolean[] buoyMarkersForBridge = (useBuoysInstead
                ? BuoyMarkerPlanner.markersForBridgeRanges(middlePositions, bridgeRanges, intervalBlocks)
                : null);
        boolean[] buoyMarkersForSkipped = (useBuoysWhenSkipped
                ? BuoyMarkerPlanner.markersForMask(middlePositions, skipSegments, intervalBlocks)
                : null);

        var targetY = data.targetY();
        boolean slopeLimitEnabled = cfg.slopeLimitEnabled();
        HeightProfileService.HeightProfile hp = HeightProfileService.build(
                world,
                middlePositions,
                currentChunk,
                averagingRadius,
                slopeLimitEnabled,
                cfg.maxSlopeStepPerTwoSegments(),
                targetY);
        boolean usePersisted = hp.usePersisted();
        int[] smoothedYArr = hp.smoothedY();
        int[] baseYArr;
        if (usePersisted && targetY != null && targetY.size() == middlePositions.size()) {
            baseYArr = targetY.stream().mapToInt(Integer::intValue).toArray();
        } else {
            baseYArr = smoothedYArr;
        }
        BridgeSegment bridgeSegment = new BridgeSegment(isBridge, segments, baseYArr);
        int deckY = server.getSeaLevel() + cfg.bridgeDeckClearance();
        BridgeSegmentPlanner.Context bridgeCtx = BridgeSegmentPlanner.newContext();
        
        for (int localIndex = 0; localIndex < slice.segmentCount(); localIndex++) {
            int i = slice.segmentIndexAt(localIndex);
            // Shared stations are navigation data for this later road only.
            // The owner road is solely responsible for pavement, clearing,
            // bridge decks/piers, tunnels, and decorations at these stations.
            if ((networkReuse != null && i >= 0 && i < networkReuse.length && networkReuse[i])
                    || (protectedGap != null && i >= 0 && i < protectedGap.length && protectedGap[i])) continue;
            BlockPos middle = middlePositions.get(i);
            int segmentIndex = Math.max(0, Math.min(segments.size() - 2, i));
            boolean centerOwned = contains(currentChunk, middle);
            boolean templateBridgeCandidate = bridgeEnabled && isBridge[i];
            if (!centerOwned && !templateBridgeCandidate)
                continue;
            if (!processedSegments.add(new ProcessedSegment(roadFingerprint, i)))
                continue;

            BlockPos prev = middlePositions.get(Math.max(0, i - 2));
            BlockPos next = middlePositions.get(Math.min(middlePositions.size() - 1, i + 2));

            BlockPos averaged = null;
            int baseYForThis = baseYArr != null ? baseYArr[i] : middle.getY();
            if (centerOwned) {
                int sea = server.getSeaLevel();
                int motion = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, middle.getX(), middle.getZ());
                int surface = world.getHeight(Heightmap.Types.WORLD_SURFACE_WG, middle.getX(), middle.getZ());
                int topYCenter = (motion > sea + 2) ? motion : surface;
                averaged = new BlockPos(middle.getX(), topYCenter, middle.getZ());
                if (baseYArr == null) {
                    baseYForThis = topYCenter;
                }
            }

            RoadSegmentPlacement seg = segments.get(i);
            if (skipSegments != null && i >= 0 && i < skipSegments.length && skipSegments[i]) {
                if (!centerOwned) {
                    continue;
                }
                if (useBuoysWhenSkipped && buoyMarkersForSkipped != null && i < buoyMarkersForSkipped.length
                        && buoyMarkersForSkipped[i]) {
                    BuoyBuilder.placeBuoy(world, middle, server.getSeaLevel(), random, cfg);
                }
                continue;
            }

            if (useBuoysInstead && isBridge != null && i >= 0 && i < isBridge.length && isBridge[i]) {
                if (!centerOwned) {
                    continue;
                }
                if (buoyMarkersForBridge != null && i < buoyMarkersForBridge.length && buoyMarkersForBridge[i]) {
                    BuoyBuilder.placeBuoy(world, middle, server.getSeaLevel(), random, cfg);
                }
                continue;
            }

            if (bridgeEnabled && isBridge[i]) {
                var line = bridgeSegment.getLine(i);
                int actualDeckY = baseYForThis;
                boolean placedTemplate = line != null && BridgeSegmentPlannerNew.processSegment(
                                world, currentChunk, data, line, seg, middle, prev, actualDeckY,
                                bridgeSegment.isLanding(i));
                if (!placedTemplate) {
                    if (!centerOwned) {
                        continue;
                    }
                    boolean placedFallback = BridgeSegmentPlanner.processSegment(world, data, seg, middle, prev, next, roadWidth, actualDeckY,
                            deckY, segmentIndex, random, cfg, bridgeRanges, baseYArr, i, bridgeCtx, line);
                    if (!placedFallback) {
                        completed[0] = false;
                        LOGGER.warn("RoadWeaver bridge segment construction failed road={} segment={} pos={}",
                                data.hasOwnerPair() ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                i, middle);
                    }
                }
            } else {
                RoadTunnelBuilder.processSegment(world, data, seg, i, middlePositions, baseYArr,
                        slice.isTunnelJunction(i), tunnel -> {
                    if (tunnel.blocked()) {
                        if (tunnel.protectedGap()) {
                            // One genuinely hard-protected volume (confirmed
                            // structure block, bedrock, block entity, reserved
                            // trunk, sanctuary) stops excavation at exactly
                            // this station. Skip it as an explicit protected
                            // gap: the span keeps building as a tunnel around
                            // it, the chunk completes, and the backfill loop
                            // never retries it.
                            LOGGER.warn("RoadWeaver tunnel station skipped as PROTECTED_GAP road={} segment={} pos={} action=skip_station span_stays_tunnel",
                                    data != null && data.hasOwnerPair()
                                            ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                    i, middle);
                            return;
                        }
                        // Task 4: a rejected tunnel must not leave the station
                        // half-cleaned or unpaved. Seat the station back on the
                        // terrain line and build it as an ordinary surface road
                        // (surface-cut fallback); if even that fails the chunk
                        // stays incomplete so the backfill pass retries, and
                        // the ledger never records a half-built tunnel.
                        BlockPos blockedCenter = seg.middlePos();
                        if (blockedCenter != null) {
                            int surfaceY = RoadConstructionSafety.terrainHeight(
                                    world, blockedCenter.getX(), blockedCenter.getZ());
                            int[] fallbackY = baseYArr != null ? baseYArr : new int[middlePositions.size()];
                            fallbackY[i] = surfaceY;
                            LOGGER.warn("RoadWeaver tunnel rejected; falling back to surface-cut build road={} segment={} pos={} y={} reason=see_blocked_tunnel_section",
                                    data != null && data.hasOwnerPair()
                                            ? data.ownerA2dKey() + "->" + data.ownerB2dKey() : "unknown",
                                    i, blockedCenter, surfaceY);
                            SegmentPaver.SegmentGrade fallbackGrade = SegmentPaver.planGrade(
                                    world, seg, i, middlePositions, fallbackY, false,
                                    preserveCrossingGrade[i]);
                            boolean paved = SegmentPaver.paveSegment(world, data, seg, i, middlePositions,
                                    fallbackY, fallbackGrade, roadType, materials, slabMaterials, random, cfg,
                                    refusalSink.stations());
                            if (!paved) {
                                // T-24: 记录拒绝,缺口轮次会把连续失败站落为
                                // PROTECTED_GAP 并停止该 chunk 的重试循环。
                                if (refusalSink.stations().containsKey(i)) constructionRefused[i] = true;
                                completed[0] = false;
                            }
                            return;
                        }
                        completed[0] = false;
                        return;
                    }
                    SegmentPaver.SegmentGrade grade = SegmentPaver.planGrade(
                            world, seg, i, middlePositions, baseYArr, tunnel.active(),
                            preserveCrossingGrade[i]);
                    boolean paved = SegmentPaver.paveSegment(world, data, seg, i, middlePositions, baseYArr, grade,
                            roadType, materials, slabMaterials, random, cfg, refusalSink.stations(),
                            tunnel.portal());
                    if (!paved) {
                        if (refusalSink.stations().containsKey(i)) constructionRefused[i] = true;
                        completed[0] = false;
                        // Do not apply a tunnel shell after its road deck or
                        // clearance preflight failed. That created orphaned
                        // tunnel mouths/platforms and disconnected routes.
                        return;
                    }
                    RoadTunnelBuilder.apply(world, tunnel);
                });

                if (cfg.roadAppearance().roadSignsEnabled()) {
                    SkippedBridgeBankSignPlanner.addIfSkippedBridgeBank(
                            world,
                            decorations,
                            averaged,
                            next,
                            prev,
                            roadWidth,
                            skipSegments,
                            i);
                }
            }

            // Bridge segments use the dedicated guardrail-lantern system.  Do
            // not also run the normal road decoration planner here: doing so
            // places tall roadside lamps on the deck (or below a water span)
            // and duplicates the bridge lighting.
            // T-24: 施工拒绝站不放路灯 —— 失败站没有任何路面,路灯会插进
            // 村庄/地形里(用户观察点 -523,144,-263 的密集路灯即来源于此)。
            if (centerOwned && !directCrossing[i] && !isBridge[i]
                    && !(tunnelMask != null && i >= 0 && i < tunnelMask.length && tunnelMask[i])
                    && !(constructionRefused != null && i < constructionRefused.length && constructionRefused[i])) {
                DecorationPlanner.addDecoration(
                        world,
                        decorations,
                        averaged,
                        segmentIndex,
                        next,
                        prev,
                        middlePositions,
                        roadWidth,
                        random,
                        cfg,
                        (roadType == 0 ? DecorationPlanner.Mode.ARTIFICIAL : DecorationPlanner.Mode.NATURAL));
            }
        }
        LOGGER.info("RoadWeaver road slice result road={} chunk={} segments={} bridge={} tunnel={} "
                        + "reuse={} gap={} completed={}",
                roadFingerprint, currentChunk, slice.segmentCount(),
                countMask(isBridge), countMask(tunnelMask),
                countMask(networkReuse), countMask(protectedGap), completed[0]);
        if (!completed[0]) {
            int tunnelStations = 0;
            int tunnelJunctionStations = 0;
            for (int index = 0; index < isBridge.length; index++) {
                if (tunnelMask != null && index < tunnelMask.length && tunnelMask[index]) tunnelStations++;
                if (slice.isTunnelJunction(index)) tunnelJunctionStations++;
            }
            LOGGER.warn("RoadWeaver chunk construction incomplete road={} chunk={} tunnelStations={} "
                            + "tunnelJunctionStations={} segments={}",
                    roadFingerprint, currentChunk, tunnelStations, tunnelJunctionStations, segments.size());
        }
        return completed[0];
    }

    private static boolean contains(ChunkPos chunkPos, BlockPos pos) {
        return pos.getX() >= chunkPos.getMinBlockX() && pos.getX() <= chunkPos.getMaxBlockX()
                && pos.getZ() >= chunkPos.getMinBlockZ() && pos.getZ() <= chunkPos.getMaxBlockZ();
    }

    /** T-20C: 每 60 秒至多一条聚合 INFO,替代原先每 chunk 一条的 WARN 洪峰。 */
    private static void logEmptyWorldgenPlanSummary() {
        long now = System.currentTimeMillis();
        long last = EMPTY_WORLDGEN_PLAN_SUMMARY_AT.get();
        if (now - last < EMPTY_WORLDGEN_PLAN_SUMMARY_INTERVAL_MS) return;
        if (!EMPTY_WORLDGEN_PLAN_SUMMARY_AT.compareAndSet(last, now)) return;
        LOGGER.info("RoadWeaver empty worldgen plan chunks observed: total={} (benign grazing discs or moved routes; "
                + "per-chunk detail at debug level)", EMPTY_WORLDGEN_PLAN_CHUNKS.get());
    }

    /** T-25: 每 60 秒至多一条终态放弃的聚合 INFO，替代每 chunk 重复刷 WARN。 */
    private static void logAbandonedSummary() {
        long now = System.currentTimeMillis();
        long last = ROAD_CHUNKS_ABANDONED_SUMMARY_AT.get();
        if (now - last < ROAD_CHUNKS_ABANDONED_SUMMARY_INTERVAL_MS) return;
        if (!ROAD_CHUNKS_ABANDONED_SUMMARY_AT.compareAndSet(last, now)) return;
        LOGGER.info("RoadWeaver 终态放弃的施工 chunk 累计: {} (局部修复不可用,已留缺口并退出重试;"
                + "该值应停止增长)", ROAD_CHUNKS_ABANDONED.get());
    }

    private static boolean[] buildPreserveCrossingGradeMask(List<BlockPos> centers,
                                                             RoadData road,
                                                             int rampSegments) {
        boolean[] result = new boolean[centers.size()];
        if (road == null || road.spans() == null || road.spans().isEmpty()) return result;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) indices.put(xzKey(centers.get(i)), i);
        int ramps = Math.max(0, rampSegments);
        for (var span : road.spans()) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) continue;
            Integer first = indices.get(xzKey(span.start()));
            Integer second = indices.get(xzKey(span.end()));
            if (first == null || second == null) continue;
            int start = Math.max(0, Math.min(first, second) - ramps);
            int end = Math.min(result.length - 1, Math.max(first, second) + ramps);
            for (int i = start; i <= end; i++) result[i] = true;
        }
        return result;
    }

    /**
     * 跨度遮罩。0.9.080：端点坐标失配时退化为「最近的站」，不再静默丢弃整段。
     *
     * <p>原来的精确 XZ 匹配一旦对不上就 {@code continue}，后果是整段 span 不参与任何处理：
     * 隧道遮罩全 false 时 {@code RoadTunnelBuilder.processSegment} 的 findSpan 也找不到 span，
     * 于是隧道一格不挖、连日志都没有。站序列会被 merge/rebuild 重建过，坐标漂 1~2 格是常态。</p>
     */
    private static boolean[] buildSpanMask(List<BlockPos> centers, RoadData road, SpanType type) {
        boolean[] result = new boolean[centers.size()];
        if (road == null || road.spans() == null || road.spans().isEmpty()) return result;
        Map<Long, Integer> indices = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) indices.put(xzKey(centers.get(i)), i);
        for (var span : road.spans()) {
            if (span == null || span.type() != type) continue;
            Integer first = resolveStationIndex(indices, centers, span.start());
            Integer second = resolveStationIndex(indices, centers, span.end());
            if (first == null || second == null) {
                SPAN_MASK_UNMATCHED.incrementAndGet();
                continue;
            }
            int start = Math.max(0, Math.min(first, second));
            int end = Math.min(result.length - 1, Math.max(first, second));
            for (int i = start; i <= end; i++) result[i] = true;
        }
        return result;
    }

    private static final int SPAN_MASK_FALLBACK_TOLERANCE = 4;
    private static final java.util.concurrent.atomic.AtomicLong SPAN_MASK_UNMATCHED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 精确 XZ → 最近站（≤4 格漂移）→ null。 */
    private static Integer resolveStationIndex(Map<Long, Integer> indices, List<BlockPos> centers,
                                               BlockPos target) {
        if (target == null || centers == null || centers.isEmpty()) return null;
        Integer exact = indices.get(xzKey(target));
        if (exact != null) return exact;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos c = centers.get(i);
            if (c == null) continue;
            int d = Math.max(Math.abs(c.getX() - target.getX()), Math.abs(c.getZ() - target.getZ()));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return (best >= 0 && bestDist <= SPAN_MASK_FALLBACK_TOLERANCE) ? best : null;
    }

    /**
     * T-24: 去重键。roadFingerprint 是内容指纹(与 RoadShardStorage 一致),
     * 原位替换前后保持稳定,因此重跑轮次不会对已处理站点二次铺装。
     */
    private record ProcessedSegment(long roadFingerprint, int segmentIndex) {
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    private static int countMask(boolean[] mask) {
        if (mask == null) return 0;
        int count = 0;
        for (boolean value : mask) {
            if (value) count++;
        }
        return count;
    }

}
