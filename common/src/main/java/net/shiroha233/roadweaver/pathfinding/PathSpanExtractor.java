/* 文件职责：从道路段中心线中提取桥梁与隧道跨度。 */
package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.pathlogic.bridge.BridgeDeckProfile;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.tunnel.RoadTunnelBuilder;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper.isWaterLike;

/**
 * 道路跨度提取器。
 */
public final class PathSpanExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int TUNNEL_RUN_MIN_LENGTH = 3;
    // ---- 0.9.010 突起度(prominence)取证：只读诊断，判定路径一行未动 ----
    // 用户观察：沿途常有「宽 5~10 格、高 10~15 格」的孤立突起，路却被打成断头路。怀疑是口径问题：
    // 现有 buried 用「footprint 最大覆土 = 地形 − targetY(规划路面)」，纵断面一旦沿突起爬上去，
    // 这个差值就趋近 0，于是判不出。这里另算一套「中心站地形高度 − 两侧基准高度」的突起度，
    // 与旧判据并列打印，用于一局取数后定阈值。buried 仍然是 oldBuried，本段不影响任何输出。
    // 开关：默认 **关**（0.9.015 改）。原来默认 true，等于给每个玩家都刷 [ROAD-BUMP] 日志；
    // 而这套取证在两片世界跑完后结论已经明确：同一判据下 bumps 全是 0，promMax=n/a ——
    // 说明「孤立突起」不是这条路线的主要地形形态，取证使命结束。
    // 需要复现时加 -Droadweaver.bumpDiag=true；阈值/窗口仍可 -Droadweaver.bumpProminence= / bumpWindow=。
    private static final boolean BUMP_DIAG = Boolean.parseBoolean(
            System.getProperty("roadweaver.bumpDiag", "false"));
    private static final int BUMP_PROMINENCE_THRESHOLD = Integer.getInteger(
            "roadweaver.bumpProminence", 8);
    private static final int BUMP_WINDOW_STATIONS = Integer.getInteger(
            "roadweaver.bumpWindow", 12);
    /** Fallback when the dry-depression bridge threshold cannot be read. */
    private static final int DEFAULT_DRY_DEPRESSION_BRIDGE_DEPTH = 6;
    private static final int CANYON_BANK_SEARCH_SEGMENTS = 8;
    private static final int CANYON_MIN_DEPTH_BLOCKS = 10;
    private static final int CANYON_MIN_WIDTH_BLOCKS = 5;
    /** Very long, deep ocean crossings may become transparent sea-floor tunnels. */
    private static final int UNDERSEA_TUNNEL_MIN_LENGTH_BLOCKS = 256;
    private static final int UNDERSEA_TUNNEL_MIN_DEPTH_BLOCKS = 8;
    private static final double UNDERSEA_TUNNEL_SELECTION_PROBABILITY = 0.5D;
    /** Land-side approach covered by the tunnel before reaching the shoreline. */
    private static final int UNDERSEA_APPROACH_TUNNEL_BLOCKS = 128;
    /** A water crossing up to this many blocks wide stays a short ford (ROAD_CROSSING). */
    public static final int ROAD_CROSSING_MAX_WIDTH_BLOCKS = 12;
    /** A shallow dry depression can be graded across a bounded endpoint step. */
    static final int MAX_DRY_CROSSING_ENDPOINT_DELTA = 3;
    /** Search beyond the immediate edge when a dry depression is a real canyon. */
    private static final int DRY_DEPRESSION_BANK_SEARCH_STATIONS = 32;
    /** Deep dry depressions must never fall back to ordinary fill. */
    private static final int DRY_DEPRESSION_FORCE_BRIDGE_DEPTH = 16;
    /**
     * Streams distinguishes its wet channel from the wider terrain-carved
     * corridor.  A persisted bridge must reach the latter's bank; otherwise a
     * low bank station is later rejected as an unsafe ordinary-road fill.
     */
    private static final int STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS = 2;

    private PathSpanExtractor() {}

    /**
     * Bridge-first lets every water crossing ignore the bridge depth
     * threshold; other modes keep the configured depth. Public pure helper,
     * exercised directly by the profile-mode regression.
     */
    public static int effectiveBridgeMinDepth(net.shiroha233.roadweaver.config.sub.PlanningConfig.TerrainProfileMode mode,
                                              int configuredMinDepth) {
        return mode == net.shiroha233.roadweaver.config.sub.PlanningConfig.TerrainProfileMode.BRIDGE_FIRST
                ? 1
                : configuredMinDepth;
    }

    public static List<RoadSpan> extractSpans(List<RoadSegmentPlacement> segments,
                                              ServerLevel level,
                                              TerrainSamplingCache cache,
                                              int bridgeMinWaterDepth) {
        return extractSpans(segments, level, cache, null, bridgeMinWaterDepth);
    }

    public static List<RoadSpan> extractSpans(List<RoadSegmentPlacement> segments,
                                              ServerLevel level,
                                              TerrainSamplingCache cache,
                                              PathTerrainField terrain,
                                              int bridgeMinWaterDepth) {
        return extractSpans(segments, level, cache, terrain,
                StreamsReflowingCompat.openSession(level), bridgeMinWaterDepth);
    }

    public static List<RoadSpan> extractSpans(List<RoadSegmentPlacement> segments,
                                              ServerLevel level,
                                              TerrainSamplingCache cache,
                                              PathTerrainField terrain,
                                              StreamsReflowingCompat.SamplingSession streams,
                                              int bridgeMinWaterDepth) {
        List<RoadSpan> spans = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return spans;
        }

        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement seg : segments) {
            centers.add(seg.middlePos());
        }

        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        Set<Long> sampledKeys = new HashSet<>();
        List<BlockPos> missing = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null) continue;
            List<BlockPos> footprint = segment.positions();
            if (footprint == null || footprint.isEmpty()) footprint = List.of(segment.middlePos());
            for (BlockPos position : footprint) {
                if (position == null) continue;
                long key = AccurateHeightSample.key(position.getX(), position.getZ());
                if (!sampledKeys.add(key)) continue;
                if (terrain == null || !terrain.hasAccurateSample(position.getX(), position.getZ())) {
                    missing.add(position);
                }
            }
        }
        Map<Long, AccurateHeightSample> samples = accurate.samplePositions(missing);
        int effectiveBridgeMinDepth = effectiveBridgeMinDepth(
                ConfigService.get().planning().terrainProfileMode(), bridgeMinWaterDepth);
        collectBridgeSpans(spans, segments, centers, level, cache, terrain, accurate, samples,
                streams, effectiveBridgeMinDepth);
        return spans;
    }

    /**
     * 0.9.058（缺陷 A）：某段站点范围里的**最高水面 Y**—— 与 {@link #collectBridgeSpans}
     * 的逐列水掩码**同一套采样**，整段无水返回 {@link Integer#MIN_VALUE}。
     *
     * <h2>为什么要有这个函数</h2>
     * <p>定甲板（{@code Road.applyCrossingProfiles}）过去**另起一条水面查询**：只问 Streams
     * 模组的 {@code ColumnPlan.inChannel()/waterSurfaceY()}。Streams 缺席、会话降级或该列
     * 判不出河道时那条查询恒为 {@code inChannel=false}，于是真机 32 个跨度**全部**
     * {@code highestWater=none}（连按定义必过水的 {@code type=ROAD_CROSSING} 也一样），
     * 每一个跨度都掉进「干峡谷」分支 {@code minimumDeck = max(两端站Y) + clearance}，
     * 而河面与站同高 ⇒ 桥面贴着水面泡水。</p>
     *
     * <p>根因是**两套口径**：认跨度（BRIDGE / ROAD_CROSSING）用的是这里的逐列水掩码
     * （Streams → 湖泊 → {@code PathTerrainField} 精确列 → 高度图兜底），定甲板用的却是
     * Streams 一条。本函数把甲板侧接到掩码侧：逐列跑一遍 {@link #segmentWaterSample}
     * —— 也就是生成水掩码时**同一个函数**、同一份 terrain / samples / streams 会话、
     * 同一道 {@link #effectiveBridgeMinDepth} 门槛 —— 所以两套口径不可能再分叉。
     * 没有发起第二条独立查询，也没有引入新的水面来源。</p>
     *
     * <p>范围与掩码侧的跨度一一对应：调用方传的是（规整后的）跨度站点区间，它必然覆盖
     * 原来被判定为「湿」的那些站，因此由水掩码生出来的跨度一定能取到具体的水面 Y。</p>
     *
     * @return 区间内逐列采样到的最高水面 Y；整段无水时 {@link Integer#MIN_VALUE}
     */
    public static int waterSurfaceYForRange(List<RoadSegmentPlacement> segments,
                                            ServerLevel level,
                                            TerrainSamplingCache cache,
                                            PathTerrainField terrain,
                                            StreamsReflowingCompat.SamplingSession streams,
                                            int bridgeMinWaterDepth,
                                            int start,
                                            int end) {
        if (segments == null || segments.isEmpty() || level == null) return Integer.MIN_VALUE;
        int from = Math.max(0, Math.min(start, end));
        int to = Math.min(segments.size() - 1, Math.max(start, end));
        if (from > to) return Integer.MIN_VALUE;

        // 与 extractSpans 一样：只补采样 terrain 没覆盖到的 footprint 列。
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        Set<Long> sampledKeys = new HashSet<>();
        List<BlockPos> missing = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            RoadSegmentPlacement segment = segments.get(i);
            if (segment == null) continue;
            List<BlockPos> footprint = segment.positions();
            if (footprint == null || footprint.isEmpty()) footprint = List.of(segment.middlePos());
            for (BlockPos position : footprint) {
                if (position == null) continue;
                long key = AccurateHeightSample.key(position.getX(), position.getZ());
                if (!sampledKeys.add(key)) continue;
                if (terrain == null || !terrain.hasAccurateSample(position.getX(), position.getZ())) {
                    missing.add(position);
                }
            }
        }
        Map<Long, AccurateHeightSample> samples = accurate.samplePositions(missing);
        int effectiveBridgeMinDepth = effectiveBridgeMinDepth(
                ConfigService.get().planning().terrainProfileMode(), bridgeMinWaterDepth);

        int highest = Integer.MIN_VALUE;
        for (int i = from; i <= to; i++) {
            BridgeWaterSample sample = segmentWaterSample(level, cache, terrain, accurate, samples,
                    streams, segments.get(i), effectiveBridgeMinDepth, null);
            if (!sample.water() || sample.waterSurfaceY() == Integer.MIN_VALUE) continue;
            highest = Math.max(highest, sample.waterSurfaceY());
        }
        return highest;
    }

    /**
     * Select tunnels from the final persisted road profile, rather than from
     * raw path Y jumps.  A smoothed hillside route has no sharp jump, yet may
     * still be buried deep enough to require a tunnel.
     */
    public static List<RoadSpan> refineTunnelSpans(List<RoadSpan> existing,
                                                    List<RoadSegmentPlacement> segments,
                                                    List<Integer> targetY) {
        return refineTunnelSpans(existing, segments, targetY, null, null);
    }

    public static List<RoadSpan> refineTunnelSpans(List<RoadSpan> existing,
                                                    List<RoadSegmentPlacement> segments,
                                                    List<Integer> targetY,
                                                    ServerLevel level,
                                                    TerrainSamplingCache cache) {
        List<RoadSpan> result = new ArrayList<>();
        if (existing != null) for (RoadSpan span : existing) if (span != null && span.type() != SpanType.TUNNEL) result.add(span);
        if (segments == null || targetY == null || segments.size() != targetY.size() || segments.size() < 3) return result;

        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) centers.add(segment.middlePos());
        Map<Long, AccurateHeightSample> footprintSamples = sampleTunnelFootprints(
                segments, centers, targetY, level, cache);
        AccurateHeightSampler accurate = level != null && cache != null ? cache.getAccurateSampler(level) : null;
        boolean[] bridge = crossingMask(result, centers);
        // 0.9.063（任务二）：地形高度与突起度不再是「只在取证开关下算」的只读诊断 —— 它们是
        // 生产判据的输入。基准换成了**独立山体包络**（TunnelEnvelopeCriterion），不再用
        // 「地形高度 − 规划路面 Y」：那个基准会跟着纵断面一起爬，突起越窄被抹得越狠。
        int[] terrainHeight = centerTerrainHeights(centers, footprintSamples, accurate);
        int[] prominence = TunnelEnvelopeCriterion.prominence(terrainHeight,
                TunnelEnvelopeCriterion.ENVELOPE_HALF_WINDOW_STATIONS,
                TunnelEnvelopeCriterion.ENVELOPE_QUANTILE);
        // 0.9.010 的旧取证口径（±12 站 / 25% 分位），只在 -Droadweaver.bumpDiag=true 时与
        // [ROAD-BUMP] 一起打印；**生产判据不再使用它**。
        int[] diagnosticProminence = BUMP_DIAG ? prominences(terrainHeight, BUMP_WINDOW_STATIONS) : null;
        boolean[] oldBuriedFlags = new boolean[centers.size()];
        // 0.9.063：先按包络切出候选段并逐段过判据，被选中的段里只有突起度达到埋深的站才是洞身。
        boolean[] envelopeSealed = new boolean[centers.size()];
        TunnelEnvelopeCriterion.Params tunnelParams = TunnelEnvelopeCriterion.Params.defaults();
        TunnelEnvelopeCriterion.Costs tunnelCosts = tunnelCosts();
        int[] envelopeRuns = TunnelEnvelopeCriterion.runs(prominence,
                TunnelEnvelopeCriterion.SEED_PROMINENCE_BLOCKS);
        for (int envelopeRun = 0; envelopeRun + 1 < envelopeRuns.length; envelopeRun += 2) {
            TunnelEnvelopeCriterion.Candidate candidate = TunnelEnvelopeCriterion.evaluate(
                    terrainHeight, prominence, envelopeRuns[envelopeRun], envelopeRuns[envelopeRun + 1],
                    tunnelParams, tunnelCosts);
            if (candidate == null) continue;
            // 只打「值得看」的段：突起度峰值都不到候选门槛的小起伏不刷日志（每个 refine 回合
            // 会跑 4~5 遍，真实地形上这种 1~3 格的小起伏能有几十上百个）。
            // why=no-prominence 仍然会出现 —— 那是「峰值够 4 格、但中位数不够 4 格」的宽缓丘。
            if (candidate.promMax() >= TunnelEnvelopeCriterion.MIN_PROMINENCE_BLOCKS) {
                LOGGER.info(candidate.logLine());
                LOGGER.info(candidate.routeChoiceLine());
            }
            if (!candidate.elected()) continue;
            for (int i = candidate.start(); i <= candidate.end(); i++) {
                envelopeSealed[i] = prominence[i] >= TunnelEnvelopeCriterion.TARGET_BURIAL_BLOCKS;
            }
        }
        // One snapshot per refine pass: the per-station loop below must not
        // hit the synchronized ConfigService for every road station.
        RoadGradePlanner.SurfaceGradeLimits limits = RoadGradePlanner.SurfaceGradeLimits.capture();
        // 0.9.026: 这一行不再是「仅日志」—— 见下方 `buried`，开关现在真的会拦断陆上隧道。
        // 0.9.005-B 当时是 LOGGING ONLY（判定保持不变：!bridge + footprint max）。
        // 0.9.063（任务二）：`buried` 的判据已换成下面的**独立山体包络**（envelopeBuried），
        // 不再是 0.9.005-B 的「!bridge + footprint max 覆土」。
        boolean landTunnelSwitch = true;
        try {
            landTunnelSwitch = ConfigService.get().roadAppearance().landTunnelEnabled();
        } catch (Throwable ignored) { }
        int sumStations = 0;
        int sumWater = 0;
        int waterOverMin = Integer.MAX_VALUE;
        int waterOverMax = Integer.MIN_VALUE;
        int landOverMin = Integer.MAX_VALUE;
        int landOverMax = Integer.MIN_VALUE;
        int runStart = -1;
        int runOld = 0;
        int runNew = 0;
        int runWater = 0;
        int runCenterMin = Integer.MAX_VALUE;
        int runCenterMax = Integer.MIN_VALUE;
        int runFootMax = Integer.MIN_VALUE;
        int runPromMin = Integer.MAX_VALUE;
        int runPromMax = Integer.MIN_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            int footprintOver = maximumFootprintOverburden(
                    segments, centers, targetY, footprintSamples, accurate, i);
            int centerOver = centerStationOverburden(centers, targetY, footprintSamples, accurate, i);
            boolean wet = isWetStation(centers, footprintSamples, i);
            boolean oldBuried = !bridge[i] && footprintOver >= limits.tunnelCoreOverburden();
            // 0.9.063（任务二）：新判据 = 包络突起度达到埋深（且所属候选段被选中、且不是桥/水面段）。
            // 「地形 − 规划路面 Y」口径的覆土只留给下面的取证日志（oldBuried / centerOver）。
            boolean envelopeBuried = envelopeSealed[i] && !bridge[i];
            boolean newBuried = envelopeBuried;
            oldBuriedFlags[i] = oldBuried;
            // 0.9.026（O-12）：`landTunnelEnabled` 原本是**假开关** —— 它只被算进 `newBuried`
            // 供日志对比，真正决定 span 的 `buried` 恒等于 `oldBuried`，所以玩家把开关关掉
            // 一点反应都没有。这里按开关名本身的语义接线：
            //   true（默认） ⇒ buried = oldBuried，**行为与改前逐位一致**；
            //   false       ⇒ 完全不产生陆上隧道，路按地表挖方通过（这正是开关名的承诺）。
            boolean buried = landTunnelSwitch && newBuried;
            if (buried && runStart < 0) runStart = i;
            if (runStart >= 0) {
                runOld += oldBuried ? 1 : 0;
                runNew += newBuried ? 1 : 0;
                runWater += wet ? 1 : 0;
                runFootMax = Math.max(runFootMax, footprintOver);
                if (prominence != null && i < prominence.length && prominence[i] != Integer.MIN_VALUE) {
                    runPromMin = Math.min(runPromMin, prominence[i]);
                    runPromMax = Math.max(runPromMax, prominence[i]);
                }
                if (centerOver != Integer.MIN_VALUE) {
                    runCenterMin = Math.min(runCenterMin, centerOver);
                    runCenterMax = Math.max(runCenterMax, centerOver);
                    if (wet) {
                        waterOverMin = Math.min(waterOverMin, centerOver);
                        waterOverMax = Math.max(waterOverMax, centerOver);
                    } else {
                        landOverMin = Math.min(landOverMin, centerOver);
                        landOverMax = Math.max(landOverMax, centerOver);
                    }
                }
                sumStations++;
                if (wet) sumWater++;
            }
            if ((!buried || i == centers.size() - 1) && runStart >= 0) {
                int end = buried && i == centers.size() - 1 ? i : i - 1;
                LOGGER.info("[ROAD-LANDTUNNEL] seg={}-{} stations={} oldBuried={} newBuried={} "
                                + "centerOver={}..{} footprintMax={} waterStations={} bridgeMasked={} prom={}..{}",
                        runStart, end, end - runStart + 1, runOld, runNew,
                        runCenterMin == Integer.MAX_VALUE ? "n/a" : String.valueOf(runCenterMin),
                        runCenterMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(runCenterMax),
                        runFootMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(runFootMax),
                        runWater, bridge[runStart] ? "yes" : "no",
                        runPromMin == Integer.MAX_VALUE ? "n/a" : String.valueOf(runPromMin),
                        runPromMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(runPromMax));
                appendTunnelWithSurfaceMouths(result, segments, centers, targetY, bridge,
                        footprintSamples, accurate, runStart, end, limits);
                runStart = -1;
                runOld = 0;
                runNew = 0;
                runWater = 0;
                runCenterMin = Integer.MAX_VALUE;
                runCenterMax = Integer.MIN_VALUE;
                runFootMax = Integer.MIN_VALUE;
                runPromMin = Integer.MAX_VALUE;
                runPromMax = Integer.MIN_VALUE;
            }
        }
        if (sumStations > 0) {
            LOGGER.info("[ROAD-LANDTUNNEL-SUMMARY] stations={} waterStations={} "
                            + "waterCenterOverMin={} waterCenterOverMax={} "
                            + "landCenterOverMin={} landCenterOverMax={} threshold={} landTunnelEnabled={}",
                    sumStations, sumWater,
                    waterOverMin == Integer.MAX_VALUE ? "n/a" : String.valueOf(waterOverMin),
                    waterOverMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(waterOverMax),
                    landOverMin == Integer.MAX_VALUE ? "n/a" : String.valueOf(landOverMin),
                    landOverMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(landOverMax),
                    limits.tunnelCoreOverburden(), landTunnelSwitch);
        }
        if (diagnosticProminence != null) {
            logBumpRuns(terrainHeight, diagnosticProminence, targetY, bridge, oldBuriedFlags);
        }
        return normalizeTunnelSpans(result, centers);
    }

    /**
     * 0.9.010 取证：每个中心站的地形表面高度。缺快采样时退回站自身 Y，
     * 与 {@link #centerStationOverburden} 的兜底口径保持一致。
     */
    private static int[] centerTerrainHeights(List<BlockPos> centers,
                                              Map<Long, AccurateHeightSample> samples,
                                              AccurateHeightSampler accurate) {
        int[] heights = new int[centers.size()];
        for (int i = 0; i < centers.size(); i++) {
            heights[i] = Integer.MIN_VALUE;
            BlockPos center = centers.get(i);
            if (center == null) continue;
            if (accurate != null && samples != null && !samples.isEmpty()) {
                AccurateHeightSample sample = samples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
                if (sample != null) {
                    heights[i] = accurate.surfaceHeight(sample);
                    continue;
                }
            }
            heights[i] = center.getY();
        }
        return heights;
    }

    /** [from, to] 内高度的 25% 分位（稳健下沿，抗单格噪声）；全无效返回 MIN_VALUE。 */
    private static int lowQuantile(int[] values, int from, int to) {
        if (values == null) return Integer.MIN_VALUE;
        int lo = Math.max(0, from);
        int hi = Math.min(values.length - 1, to);
        if (lo > hi) return Integer.MIN_VALUE;
        int[] copy = new int[hi - lo + 1];
        int n = 0;
        for (int i = lo; i <= hi; i++) {
            if (values[i] != Integer.MIN_VALUE) copy[n++] = values[i];
        }
        if (n == 0) return Integer.MIN_VALUE;
        java.util.Arrays.sort(copy, 0, n);
        return copy[Math.max(0, Math.min(n - 1, n / 4))];
    }

    /**
     * 0.9.010 的取证口径：突起度 = 中心站地形高度 − max(前窗口下沿, 后窗口下沿)。
     *
     * <p><b>0.9.063 起它不是生产判据</b>：只服务 {@code -Droadweaver.bumpDiag=true} 下的
     * {@code [ROAD-BUMP]} 行。生产判据见 {@link TunnelEnvelopeCriterion#prominence(int[])} ——
     * 那个用「低分位下包络」当基准，不会因为突起比窗口还宽就把高地自己当成基准。</p>
     */
    private static int[] prominences(int[] terrainHeight, int window) {
        if (terrainHeight == null || terrainHeight.length == 0) return null;
        int[] prom = new int[terrainHeight.length];
        for (int i = 0; i < terrainHeight.length; i++) {
            prom[i] = Integer.MIN_VALUE;
            if (terrainHeight[i] == Integer.MIN_VALUE) continue;
            int back = lowQuantile(terrainHeight, i - window, i - 1);
            int forward = lowQuantile(terrainHeight, i + 1, i + window);
            if (back == Integer.MIN_VALUE || forward == Integer.MIN_VALUE) continue;
            prom[i] = terrainHeight[i] - Math.max(back, forward);
        }
        return prom;
    }

    /**
     * 0.9.010 取证：把「按突起度算出来的连续凸起」逐个打出来，并标出旧判据有没有覆盖它。
     * 只读：不改 result、不改 targetY、不改任何 span。
     * 关注 {@code covered=NO} 的那些行 —— 它们就是「该打隧道却被铺成路面」的候选。
     */
    private static void logBumpRuns(int[] terrainHeight,
                                    int[] prominence,
                                    List<Integer> targetY,
                                    boolean[] bridge,
                                    boolean[] oldBuriedFlags) {
        if (prominence == null || prominence.length == 0) return;
        int runs = 0;
        int missed = 0;
        int promMax = Integer.MIN_VALUE;
        int climbedOnMissed = 0;
        StringBuilder detail = new StringBuilder();
        int i = 0;
        while (i < prominence.length) {
            if (prominence[i] == Integer.MIN_VALUE
                    || prominence[i] < BUMP_PROMINENCE_THRESHOLD
                    || (bridge != null && i < bridge.length && bridge[i])) {
                i++;
                continue;
            }
            int start = i;
            int promLo = Integer.MAX_VALUE;
            int promHi = Integer.MIN_VALUE;
            int hLo = Integer.MAX_VALUE;
            int hHi = Integer.MIN_VALUE;
            int buriedInside = 0;
            while (i < prominence.length
                    && prominence[i] != Integer.MIN_VALUE
                    && prominence[i] >= BUMP_PROMINENCE_THRESHOLD
                    && (bridge == null || i >= bridge.length || !bridge[i])) {
                promLo = Math.min(promLo, prominence[i]);
                promHi = Math.max(promHi, prominence[i]);
                if (terrainHeight != null && i < terrainHeight.length
                        && terrainHeight[i] != Integer.MIN_VALUE) {
                    hLo = Math.min(hLo, terrainHeight[i]);
                    hHi = Math.max(hHi, terrainHeight[i]);
                }
                if (oldBuriedFlags != null && i < oldBuriedFlags.length && oldBuriedFlags[i]) buriedInside++;
                i++;
            }
            int end = i - 1;
            runs++;
            promMax = Math.max(promMax, promHi);
            int yStart = targetY != null && start < targetY.size() && targetY.get(start) != null
                    ? targetY.get(start) : 0;
            int yEnd = targetY != null && end < targetY.size() && targetY.get(end) != null
                    ? targetY.get(end) : 0;
            boolean covered = buriedInside > 0;
            if (!covered) {
                missed++;
                climbedOnMissed += Math.abs(yEnd - yStart);
            }
            if (detail.length() < 2400) {
                detail.append(String.format(
                        "%n  [ROAD-BUMP] seg=%d-%d stations=%d prom=%d..%d terrainH=%s..%s "
                                + "targetY=%d..%d climbed=%d oldBuriedInside=%d covered=%s",
                        start, end, end - start + 1, promLo, promHi,
                        hLo == Integer.MAX_VALUE ? "n/a" : String.valueOf(hLo),
                        hHi == Integer.MIN_VALUE ? "n/a" : String.valueOf(hHi),
                        yStart, yEnd, yEnd - yStart, buriedInside, covered ? "yes" : "NO"));
            }
        }
        LOGGER.info("[ROAD-BUMP-SUMMARY] stations={} bumps={} bumpsMissed={} promMax={} "
                        + "threshold={} window={} climbedOnMissed={}{}",
                prominence.length, runs, missed,
                promMax == Integer.MIN_VALUE ? "n/a" : String.valueOf(promMax),
                BUMP_PROMINENCE_THRESHOLD, BUMP_WINDOW_STATIONS, climbedOnMissed, detail);
    }

    /**
     * 0.9.063（任务三）：隧道判据的代价权重快照 —— 从当前配置取坡罚单价与阈值，
     * 「洞身/明挖 vs 平路」的倍数取 {@link TunnelEnvelopeCriterion} 里的常量。
     *
     * <p>读不到配置时退回出厂默认（离线单测 / 初始化早期走的都是这条）。</p>
     */
    private static TunnelEnvelopeCriterion.Costs tunnelCosts() {
        try {
            var cfg = ConfigService.get().pathfindingCost();
            double step = Math.max(1, cfg.effectiveAStarStep());
            return new TunnelEnvelopeCriterion.Costs(
                    cfg.orthoStepCost() / step,
                    cfg.softGradePenalty(),
                    cfg.hardGradePenalty(),
                    cfg.softGradeLimit(),
                    cfg.hardGradeLimit(),
                    TunnelEnvelopeCriterion.TUNNEL_BLOCK_COST_FACTOR,
                    TunnelEnvelopeCriterion.PORTAL_BLOCK_COST_FACTOR);
        } catch (Throwable ignored) {
            return TunnelEnvelopeCriterion.Costs.defaults();
        }
    }

    /** 0.9.005-B: centre-station overburden (terrain height - planned road Y). */
    private static int centerStationOverburden(List<BlockPos> centers, List<Integer> targetY,
                                               Map<Long, AccurateHeightSample> samples,
                                               AccurateHeightSampler accurate, int index) {
        if (index < 0 || index >= centers.size() || index >= targetY.size()) return Integer.MIN_VALUE;
        BlockPos center = centers.get(index);
        if (center == null) return Integer.MIN_VALUE;
        if (accurate != null && samples != null && !samples.isEmpty()) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
            if (sample != null) return accurate.surfaceHeight(sample) - targetY.get(index);
        }
        return overburden(centers, targetY, index);
    }

    /** 0.9.005-B: is the centre station a wet column (water surface above ocean floor). */
    private static boolean isWetStation(List<BlockPos> centers,
                                        Map<Long, AccurateHeightSample> samples, int index) {
        if (index < 0 || index >= centers.size() || samples == null || samples.isEmpty()) return false;
        BlockPos center = centers.get(index);
        if (center == null) return false;
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
        return sample != null && sample.worldSurfaceWg() > sample.oceanFloorWg();
    }

    /**
     * Converts only very long, deep ocean bridges into TUNNEL spans. Rivers,
     * lakes, shallow crossings and mixed shore corridors remain bridges.
     */
    public static List<RoadSpan> convertLongOceanBridgesToUnderseaTunnels(
            List<RoadSpan> existing,
            List<RoadSegmentPlacement> segments,
            ServerLevel level,
            TerrainSamplingCache cache,
            Map<Long, AccurateHeightSample> sampledCenters) {
        if (existing == null || existing.isEmpty() || segments == null
                || segments.size() < 3 || level == null || cache == null) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(existing);
            centers.add(segment.middlePos());
        }
        Map<Long, AccurateHeightSample> centerSamples = sampledCenters == null ? Map.of() : sampledCenters;
        List<RoadSpan> result = new ArrayList<>();
        int converted = 0;
        for (RoadSpan span : existing) {
            if (span == null || span.type() != SpanType.BRIDGE) {
                if (span != null) result.add(span);
                continue;
            }
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) {
                result.add(span);
                continue;
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (crossingWidthBlocks(centers, from, to) < UNDERSEA_TUNNEL_MIN_LENGTH_BLOCKS) {
                result.add(span);
                continue;
            }
            if (!chooseUnderseaTunnel(span, centers)) {
                result.add(span);
                LOGGER.info("RoadWeaver retained long ocean bridge by 50%% selection range={}..{}",
                        from, to);
                continue;
            }
            int oceanColumns = 0;
            int waterColumns = 0;
            int minimumBed = Integer.MAX_VALUE;
            int maximumDepth = 0;
            for (int index = from; index <= to; index++) {
                BlockPos center = centers.get(index);
                AccurateHeightSample sample = centerSamples.get(
                        AccurateHeightSample.key(center.getX(), center.getZ()));
                if (sample == null) continue;
                boolean ocean = cache.getBiome(level, center.getX(), center.getZ()).is(BiomeTags.IS_OCEAN);
                boolean water = SurfaceWaterClassifier.isWaterColumn(
                        sample.worldSurfaceWg(), sample.oceanFloorWg(), ocean, level.getSeaLevel());
                if (!ocean || !water) continue;
                oceanColumns++;
                waterColumns++;
                minimumBed = Math.min(minimumBed, sample.oceanFloorWg());
                maximumDepth = Math.max(maximumDepth,
                        SurfaceWaterClassifier.waterDepth(sample.worldSurfaceWg(), sample.oceanFloorWg(),
                                ocean, level.getSeaLevel(), true));
            }
            int spanColumns = Math.max(1, to - from + 1);
            if (oceanColumns < Math.max(3, Math.ceilDiv(spanColumns * 3, 4))
                    || waterColumns != oceanColumns
                    || maximumDepth < UNDERSEA_TUNNEL_MIN_DEPTH_BLOCKS
                    || minimumBed == Integer.MAX_VALUE) {
                result.add(span);
                continue;
            }
            int tunnelFrom = extendIndexByDistance(centers, from, -1, UNDERSEA_APPROACH_TUNNEL_BLOCKS);
            int tunnelTo = extendIndexByDistance(centers, to, 1, UNDERSEA_APPROACH_TUNNEL_BLOCKS);
            result.add(new RoadSpan(centers.get(tunnelFrom), centers.get(tunnelTo), SpanType.TUNNEL));
            converted++;
            LOGGER.info("RoadWeaver converted long ocean bridge to undersea tunnel range={}..{} approach={} lengthBlocks={} minBedY={} maxDepth={}",
                    tunnelFrom, tunnelTo, UNDERSEA_APPROACH_TUNNEL_BLOCKS,
                    crossingWidthBlocks(centers, from, to), minimumBed, maximumDepth);
        }
        return converted == 0 ? List.copyOf(existing) : normalizeTunnelSpans(result, centers);
    }

    private static boolean chooseUnderseaTunnel(RoadSpan span, List<BlockPos> centers) {
        if (span == null || centers == null) return false;
        long seed = 0x554E444552534541L;
        seed = seed * 31L + span.start().asLong();
        seed = seed * 31L + span.end().asLong();
        if (!centers.isEmpty()) seed ^= centers.get(0).asLong();
        double probability = ConfigService.get().terrainProfile().oceanUnderseaChance();
        return new SplittableRandom(seed).nextDouble() < probability;
    }

    private static int extendIndexByDistance(List<BlockPos> centers, int start, int direction, int blocks) {
        int index = Math.max(0, Math.min(centers.size() - 1, start));
        double travelled = 0.0D;
        while (travelled < blocks) {
            int next = index + direction;
            if (next < 0 || next >= centers.size()) break;
            travelled += horizontalDistance(centers.get(index), centers.get(next));
            index = next;
        }
        return index;
    }

    /** Lowers converted ocean-tunnel stations below the sampled sea floor. */
    public static List<Integer> applyUnderseaTunnelProfile(
            List<Integer> targetY,
            List<RoadSpan> spans,
            List<RoadSegmentPlacement> segments,
            ServerLevel level,
            TerrainSamplingCache cache,
            Map<Long, AccurateHeightSample> sampledCenters,
            int clearHeight) {
        if (targetY == null || spans == null || segments == null || level == null || cache == null
                || targetY.size() != segments.size() || segments.size() < 3) {
            return targetY == null ? List.of() : List.copyOf(targetY);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(targetY);
            centers.add(segment.middlePos());
        }
        Map<Long, AccurateHeightSample> samples = sampledCenters == null ? Map.of() : sampledCenters;
        ArrayList<Integer> result = new ArrayList<>(targetY);
        int safeClear = Math.max(2, Math.min(16, clearHeight));
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            int firstWater = -1;
            int lastWater = -1;
            int waterCount = 0;
            Map<Integer, Integer> underwaterTargets = new java.util.LinkedHashMap<>();
            for (int index = from; index <= to; index++) {
                BlockPos center = centers.get(index);
                AccurateHeightSample sample = samples.get(
                        AccurateHeightSample.key(center.getX(), center.getZ()));
                if (sample == null || !cache.getBiome(level, center.getX(), center.getZ()).is(BiomeTags.IS_OCEAN)
                        || !SurfaceWaterClassifier.isWaterColumn(
                        sample.worldSurfaceWg(), sample.oceanFloorWg(), true, level.getSeaLevel())) continue;
                if (firstWater < 0) firstWater = index;
                lastWater = index;
                waterCount++;
                underwaterTargets.put(index, underseaRoadY(sample.oceanFloorWg()));
            }
            if (firstWater < 0 || waterCount < Math.max(3, Math.ceilDiv(to - from + 1, 2))) continue;

            for (Map.Entry<Integer, Integer> target : underwaterTargets.entrySet()) {
                int index = target.getKey();
                result.set(index, Math.min(result.get(index), target.getValue()));
            }

            // Build short ramps at both sea-floor tunnel mouths instead of a
            // vertical drop from the surface bridge deck.
            smoothUnderseaApproach(result, targetY, firstWater, -1, safeClear);
            smoothUnderseaApproach(result, targetY, lastWater, 1, safeClear);
        }
        return List.copyOf(result);
    }

    /**
     * The deck level for an undersea tunnel station, from the ocean floor's first water block.
     *
     * <p>{@code oceanFloorWg} is "topmost solid block + 1", so the topmost solid block is
     * {@code oceanFloorWg - 1}. The deck must sit deep enough that the roof - {@code roofOffset()} above the
     * deck - leaves {@link RoadTunnelBuilder#ROCKS_ABOVE_ROOF} solid blocks below the water. The old formula
     * ({@code oceanFloorWg - safeClear - 1}) put the roof one block <em>above</em> the seabed, i.e. in the
     * first water block: the "tunnel" was a single stone-brick layer lying on the sea floor with the ocean
     * resting on it, and nothing in the pipeline noticed because the overburden test measured to the water
     * surface.
     */
    public static int underseaRoadY(int oceanFloorWg) {
        return oceanFloorWg - 1 - RoadTunnelBuilder.roofOffset() - RoadTunnelBuilder.ROCKS_ABOVE_ROOF;
    }

    private static void smoothUnderseaApproach(List<Integer> result, List<Integer> original,
                                               int mouth, int direction, int clearHeight) {
        if (mouth < 0 || mouth >= result.size()) return;
        int underwater = result.get(mouth);
        int reference = original.get(mouth);
        int maxApproach = Math.max(16, Math.min(128, Math.abs(reference - underwater) * 2 + 16));
        int anchorIndex = mouth + direction * maxApproach;
        if (anchorIndex < 0 || anchorIndex >= original.size()) {
            anchorIndex = mouth + direction * Math.min(16, maxApproach);
        }
        anchorIndex = Math.max(0, Math.min(original.size() - 1, anchorIndex));
        int anchorY = original.get(anchorIndex);
        for (int distance = 1; distance <= maxApproach; distance++) {
            int index = mouth + direction * distance;
            if (index < 0 || index >= result.size()) break;
            double ratio = distance / (double) maxApproach;
            int ramp = (int) Math.round(underwater + (anchorY - underwater) * ratio);
            result.set(index, Math.min(result.get(index), ramp));
        }
    }

    /** Rebuilds tunnel spans after a road has been trimmed or merged. */
    public static RoadData refineRoadTunnels(RoadData road,
                                             ServerLevel level,
                                             TerrainSamplingCache cache) {
        if (road == null || level == null || cache == null) return road;
        List<RoadSpan> refined = refineTunnelSpans(
                road.spans(), road.roadSegmentList(), road.targetY(), level, cache);
        // A junction rewrite can create a new dry depression after the first
        // tunnel pass. Re-run the bounded final-grade classifier so the
        // rewritten route cannot fall back to ordinary paving and leave a gap.
        refined = refineFinalFillBridgeSpans(
                refined, road.roadSegmentList(), road.targetY(), level, cache);
        if (refined.equals(road.spans())) return road;
        return new RoadData(road.width(), road.roadType(), road.materials(), road.slabMaterials(),
                road.roadSegmentList(), refined, road.targetY(),
                road.ownerA2dKey(), road.ownerB2dKey());
    }

    /**
     * Detects a bounded dry depression after the final height pass. Short
     * depressions use the direct-crossing construction path; wider ones use
     * the bridge template. Water spans already present in {@code existing}
     * always take precedence.
     */
    public static List<RoadSpan> refineFinalFillBridgeSpans(List<RoadSpan> existing,
                                                             List<RoadSegmentPlacement> segments,
                                                             List<Integer> targetY,
                                                             ServerLevel level,
                                                             TerrainSamplingCache cache) {
        if (segments == null || targetY == null || segments.size() != targetY.size()
                || segments.size() < 3 || level == null || cache == null) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        Set<Long> sampledKeys = new HashSet<>();
        List<BlockPos> positions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return existing == null ? List.of() : List.copyOf(existing);
            centers.add(segment.middlePos());
            if (segment.positions() == null) continue;
            for (BlockPos position : segment.positions()) {
                if (position != null && sampledKeys.add(AccurateHeightSample.key(position.getX(), position.getZ()))) {
                    positions.add(position);
                }
            }
        }
        Map<Long, AccurateHeightSample> samples = cache.getAccurateSampler(level).samplePositions(positions);
        int[] lowest = new int[segments.size()];
        java.util.Arrays.fill(lowest, Integer.MAX_VALUE);
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        for (int index = 0; index < segments.size(); index++) {
            List<BlockPos> footprint = segments.get(index).positions();
            if (footprint == null) continue;
            for (BlockPos position : footprint) {
                if (position == null) continue;
                AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
                if (sample != null) lowest[index] = Math.min(lowest[index], accurate.surfaceHeight(sample));
            }
        }
        return refineFinalFillBridgeSpans(existing, centers, targetY, lowest);
    }

    /**
     * Extends an already selected Streams bridge through the remainder of its
     * own carved corridor.  This is deliberately not a new crossing detector:
     * a dry Streams corridor cannot create a bridge by itself.  It only keeps
     * a bridge chosen from a real channel from stopping before the bank.
     */
    public static List<RoadSpan> extendExistingStreamsBridgeApproaches(List<RoadSpan> existing,
                                                                         List<RoadSegmentPlacement> segments,
                                                                         StreamsReflowingCompat.SamplingSession streams) {
        if (existing == null || existing.isEmpty() || segments == null || segments.size() < 2
                || streams == null || !streams.isAvailable()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<BlockPos> centers = new ArrayList<>(segments.size());
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null || segment.middlePos() == null) return List.copyOf(existing);
            centers.add(segment.middlePos());
        }
        boolean[] corridor = new boolean[centers.size()];
        for (int index = 0; index < centers.size(); index++) {
            BlockPos center = centers.get(index);
            corridor[index] = streams.sample(center.getX(), center.getZ()).inCorridor();
        }
        return extendExistingBridgesIntoCorridor(existing, centers, corridor);
    }

    static List<RoadSpan> extendExistingBridgesIntoCorridor(List<RoadSpan> existing,
                                                              List<BlockPos> centers,
                                                              boolean[] corridor) {
        if (existing == null || existing.isEmpty() || centers == null || centers.isEmpty()
                || corridor == null || corridor.length != centers.size()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<int[]> ranges = new ArrayList<>();
        List<RoadSpan> result = new ArrayList<>();
        boolean changed = false;
        for (RoadSpan span : existing) {
            if (span == null || span.type() != SpanType.BRIDGE) {
                if (span != null) result.add(span);
                continue;
            }
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) {
                result.add(span);
                continue;
            }
            int lower = Math.min(start, end);
            int upper = Math.max(start, end);
            int extendedLower = extendBridgeBank(corridor, lower, -1);
            int extendedUpper = extendBridgeBank(corridor, upper, 1);
            changed |= extendedLower != lower || extendedUpper != upper;
            ranges.add(new int[]{extendedLower, extendedUpper});
        }
        if (ranges.isEmpty() || !changed) return List.copyOf(existing);

        ranges.sort(java.util.Comparator.comparingInt(range -> range[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty() || range[0] > merged.getLast()[1] + 1) {
                merged.add(range);
            } else {
                merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            }
        }
        for (int[] range : merged) {
            result.add(new RoadSpan(centers.get(range[0]), centers.get(range[1]), SpanType.BRIDGE));
        }
        return List.copyOf(result);
    }

    private static int extendBridgeBank(boolean[] corridor, int bridgeEdge, int direction) {
        int index = bridgeEdge;
        for (int candidate = bridgeEdge + direction;
             candidate >= 0 && candidate < corridor.length;
             candidate += direction) {
            if (corridor[candidate]) {
                index = candidate;
                continue;
            }
            if (corridorResumes(corridor, candidate, direction)) {
                index = candidate;
                continue;
            }
            // Include one stable bank station so the template and the ordinary
            // road own the same landing column, then stop on dry land.
            if (index != bridgeEdge) index = candidate;
            break;
        }
        return index;
    }

    private static boolean corridorResumes(boolean[] corridor, int from, int direction) {
        for (int step = 1; step <= STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS; step++) {
            int candidate = from + direction * step;
            if (candidate < 0 || candidate >= corridor.length) return false;
            if (corridor[candidate]) return true;
        }
        return false;
    }

    static List<RoadSpan> refineFinalFillBridgeSpans(List<RoadSpan> existing,
                                                      List<BlockPos> centers,
                                                      List<Integer> targetY,
                                                      int[] lowestFootprintSurface) {
        if (centers == null || targetY == null || lowestFootprintSurface == null
                || centers.size() != targetY.size() || centers.size() != lowestFootprintSurface.length
                || centers.size() < 3) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<RoadSpan> result = new ArrayList<>(existing == null ? List.of() : existing);
        boolean[] special = specialSpanMask(result, centers);
        // Dry-depression bridging has its own threshold; the ordinary fill
        // limit stays owned by RoadGradePlanner's shared grade snapshot.
        int dryBridgeDepth = dryDepressionBridgeDepth();
        boolean[] unsafeFill = new boolean[targetY.size()];
        for (int index = 0; index < targetY.size(); index++) {
            if (special[index] || lowestFootprintSurface[index] == Integer.MAX_VALUE) continue;
            unsafeFill[index] = targetY.get(index) - lowestFootprintSurface[index] >= dryBridgeDepth;
        }
        appendFinalFillBridgeSpans(result, centers, targetY, lowestFootprintSurface, unsafeFill, special);
        return List.copyOf(result);
    }

    private static boolean[] specialSpanMask(List<RoadSpan> spans, List<BlockPos> centers) {
        boolean[] special = new boolean[centers.size()];
        if (spans == null) return special;
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING
                    && span.type() != SpanType.TUNNEL
                    && span.type() != SpanType.TUNNEL_JUNCTION
                    && span.type() != SpanType.NETWORK_REUSE
                    && span.type() != SpanType.CULVERT
                    && span.type() != SpanType.PROTECTED_GAP)) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            for (int index = Math.min(start, end); index <= Math.max(start, end); index++) special[index] = true;
        }
        return special;
    }

    private static void appendFinalFillBridgeSpans(List<RoadSpan> spans,
                                                   List<BlockPos> centers,
                                                   List<Integer> targetY,
                                                   int[] lowestFootprintSurface,
                                                   boolean[] unsafeFill,
                                                   boolean[] special) {
        for (int index = 0; index < unsafeFill.length; ) {
            while (index < unsafeFill.length && !unsafeFill[index]) index++;
            if (index >= unsafeFill.length) return;
            int unsafeStart = index;
            while (index + 1 < unsafeFill.length && unsafeFill[index + 1]) index++;
            int unsafeEnd = index++;

            int bridgeStart = findDryDepressionBank(
                    unsafeStart, -1, unsafeFill, special);
            int bridgeEnd = findDryDepressionBank(
                    unsafeEnd, 1, unsafeFill, special);
            // A deep fill can start or end at the route boundary. There is no
            // ordinary bank on the missing side, but the endpoint itself is a
            // valid landing station. Without this fallback the unsafe run was
            // silently left unclassified and ordinary paving later skipped it
            // one station at a time, producing a disconnected canyon.
            if (bridgeStart < 0 && unsafeStart == 0 && !special[0]) bridgeStart = 0;
            if (bridgeEnd < 0 && unsafeEnd == unsafeFill.length - 1
                    && !special[unsafeFill.length - 1]) bridgeEnd = unsafeFill.length - 1;
            if (bridgeStart < 0 || bridgeEnd < 0 || bridgeStart >= bridgeEnd) continue;
            int maximumDepth = 0;
            for (int candidate = unsafeStart; candidate <= unsafeEnd; candidate++) {
                maximumDepth = Math.max(maximumDepth,
                        targetY.get(candidate) - lowestFootprintSurface[candidate]);
            }
            int widthBlocks = crossingWidthBlocks(centers, bridgeStart, bridgeEnd);
            int bankHeightDelta = Math.abs(targetY.get(bridgeStart) - targetY.get(bridgeEnd));
            SpanType type = crossingTypeForWidth(widthBlocks);
            // A deep depression or a large bank mismatch cannot be represented
            // by ordinary fill. Promote it to a bridge even when its horizontal
            // width is short; this prevents a 20+ block vertical fill from
            // being retried as a normal road and then rejected downstream.
            if (maximumDepth >= DRY_DEPRESSION_FORCE_BRIDGE_DEPTH
                    || bankHeightDelta > MAX_DRY_CROSSING_ENDPOINT_DELTA) {
                type = SpanType.BRIDGE;
            }
            appendNormalizedWaterSpan(spans, centers, bridgeStart, bridgeEnd, type);
            LOGGER.info("RoadWeaver final-grade dry depression plan type={} widthBlocks={} "
                            + "unsafeFillRange={}..{} stableRange={}..{} depth={} bankHeightDelta={}",
                    type, widthBlocks, unsafeStart, unsafeEnd, bridgeStart, bridgeEnd,
                    maximumDepth, bankHeightDelta);
        }
    }

    private static int findDryDepressionBank(int start,
                                             int direction,
                                             boolean[] unsafeFill,
                                             boolean[] special) {
        if (unsafeFill == null || special == null || unsafeFill.length != special.length) return -1;
        for (int distance = 1; distance <= DRY_DEPRESSION_BANK_SEARCH_STATIONS; distance++) {
            int index = start + direction * distance;
            if (index < 0 || index >= unsafeFill.length) return -1;
            if (!unsafeFill[index] && !special[index]) return index;
        }
        return -1;
    }

    /** Final physical-span mutual exclusion for already-classified spans. */
    public static List<RoadSpan> resolvePhysicalSpanOverlaps(
            List<RoadSpan> spans, List<BlockPos> centers) {
        return normalizeTunnelSpans(spans, centers);
    }

    static List<RoadSpan> normalizeTunnelSpans(List<RoadSpan> spans, List<BlockPos> centers) {
        if (spans == null || spans.isEmpty() || centers == null || centers.isEmpty()) {
            return spans == null ? List.of() : List.copyOf(spans);
        }
        // One x/z -> index lookup per call: the per-span loops below would
        // otherwise rescan the whole center list for every span endpoint,
        // turning an O(spans + centers) pass into O(spans * centers).
        Map<Long, Integer> centerIndex = xzIndex(centers);
        List<RoadSpan> result = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        // T-26: bridge ranges are collected on the same pass so they can be
        // merged below. Before this, TUNNEL was the only physical type that got
        // a merge, so the final-fill / canyon / water producers could each
        // append a BRIDGE over the same water and every one of them survived.
        List<int[]> bridgeRanges = new ArrayList<>();
        // Spans of every other type keep their original relative order.
        List<RoadSpan> passthrough = new ArrayList<>();
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.TUNNEL && span.type() != SpanType.BRIDGE)) {
                passthrough.add(span);
                continue;
            }
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) {
                // Unresolvable endpoint: keep the span untouched. TUNNEL used to
                // be dropped here and still is; a BRIDGE was kept, so it stays
                // kept (it simply cannot join the merge below).
                if (span.type() == SpanType.BRIDGE) passthrough.add(span);
                continue;
            }
            // Keep the pre-existing drop of single-station TUNNELs; a
            // one-station BRIDGE was always retained, so it still is here and
            // simply participates in the merge below.
            if (start == end && span.type() == SpanType.TUNNEL) continue;
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (span.type() == SpanType.TUNNEL) ranges.add(new int[]{from, to});
            else bridgeRanges.add(new int[]{from, to});
        }
        result.addAll(passthrough);
        mergeRangesInto(result, centers, ranges, SpanType.TUNNEL);
        mergeRangesInto(result, centers, bridgeRanges, SpanType.BRIDGE);
        return resolveOverlappingWaterSpans(result, centers, centerIndex);
    }

    /**
     * T-26: merges every overlapping-or-adjacent range of one physical type into
     * a single span.
     *
     * <p>Real case (2026-09-16): one water crossing produced two BRIDGE spans
     * whose endpoints differed by 1..30 stations. Both were persisted, and
     * {@code Road.applyCrossingProfiles} then ran {@code applyFixedRange} once
     * per range — each doing its own linear bank-to-bank interpolation over the
     * shared stations. The later range overwrote the earlier deck height, so a
     * single bridge came out as two parallel decks a few blocks apart, with a
     * visible step at the overlap.</p>
     *
     * <p>Adjacency ({@code range[0] <= end + 1}) merges too, matching the
     * TUNNEL behaviour: two bridges separated by a single dry station are one
     * structure, and leaving a one-station gap there produces the same deck
     * discontinuity.</p>
     */
    private static void mergeRangesInto(List<RoadSpan> result,
                                        List<BlockPos> centers,
                                        List<int[]> ranges,
                                        SpanType type) {
        if (ranges.isEmpty()) return;
        ranges.sort(java.util.Comparator.comparingInt(range -> range[0]));
        int start = -1;
        int end = -1;
        for (int[] range : ranges) {
            if (start < 0) {
                start = range[0];
                end = range[1];
            } else if (range[0] <= end + 1) {
                end = Math.max(end, range[1]);
            } else {
                result.add(new RoadSpan(centers.get(start), centers.get(end), type));
                start = range[0];
                end = range[1];
            }
        }
        if (start >= 0) result.add(new RoadSpan(centers.get(start), centers.get(end), type));
    }

    /**
     * Physical-span mutual exclusion (T5): a station may carry only one of
     * bridge / crossing / tunnel. Tunnel selection is based on the real
     * overburden profile and the undersea conversion is a deliberate
     * water-column decision, so where a tunnel range overlaps a bridge or
     * crossing the tunnel wins and the water span is dropped or shrunk to its
     * non-overlapping remainder. Without this, a converted undersea tunnel's
     * ±128-station approach swallows a neighbouring bridge and the validator
     * rejects the road with overlapping-physical-spans. Runs after every
     * tunnel-normalizing producer, so the self-check covers all of them.
     */
    private static List<RoadSpan> resolveOverlappingWaterSpans(List<RoadSpan> spans,
                                                               List<BlockPos> centers) {
        return resolveOverlappingWaterSpans(spans, centers, xzIndex(centers));
    }

    private static List<RoadSpan> resolveOverlappingWaterSpans(List<RoadSpan> spans,
                                                               List<BlockPos> centers,
                                                               Map<Long, Integer> centerIndex) {
        int n = centers.size();
        boolean[] tunnel = new boolean[n];
        boolean anyTunnel = false;
        for (RoadSpan span : spans) {
            if (span == null || span.type() != SpanType.TUNNEL) continue;
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                tunnel[i] = true;
                anyTunnel = true;
            }
        }
        if (!anyTunnel) return spans;
        List<RoadSpan> result = new ArrayList<>(spans.size());
        for (RoadSpan span : spans) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) {
                result.add(span);
                continue;
            }
            int start = indexOfXz(centerIndex, span.start());
            int end = indexOfXz(centerIndex, span.end());
            if (start < 0 || end < 0) {
                result.add(span);
                continue;
            }
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            List<int[]> freeRuns = new ArrayList<>();
            int runStart = -1;
            for (int i = from; i <= to; i++) {
                if (!tunnel[i]) {
                    if (runStart < 0) runStart = i;
                } else if (runStart >= 0) {
                    freeRuns.add(new int[]{runStart, i - 1});
                    runStart = -1;
                }
            }
            if (runStart >= 0) freeRuns.add(new int[]{runStart, to});
            if (freeRuns.size() == 1 && freeRuns.get(0)[0] == from
                    && freeRuns.get(0)[1] == to) {
                result.add(span);
                continue;
            }
            LOGGER.info("RoadWeaver resolved physical span overlap: tunnel wins, {} range={}..{} "
                            + "kept as {} remainder run(s)",
                    span.type(), from, to, freeRuns.size());
            for (int[] run : freeRuns) {
                // A bridge needs two distinct endpoints; a one-station bridge
                // remainder is invalid, so it is dropped (the tunnel already
                // implements those stations). A one-station crossing is valid.
                if (span.type() == SpanType.BRIDGE && run[0] == run[1]) {
                    LOGGER.info("RoadWeaver dropped one-station bridge remainder inside tunnel range={}..{}",
                            from, to);
                    continue;
                }
                result.add(new RoadSpan(centers.get(run[0]), centers.get(run[1]), span.type()));
            }
        }
        return result;
    }

    /**
     * Removes a surface-water crossing when the complete tunnel clearance is
     * below the river bed. Crossing width and bridge selection stay unchanged
     * for roads whose construction volume actually reaches the water column.
     */
    public static List<RoadSpan> retainVerticallyRelevantCrossings(
            List<RoadSpan> existing,
            List<RoadSpan> provisionalTunnels,
            List<RoadSegmentPlacement> segments,
            List<Integer> provisionalTargetY,
            ServerLevel level,
            TerrainSamplingCache cache,
            PathTerrainField terrain,
            StreamsReflowingCompat.SamplingSession streams,
            int tunnelClearHeight,
            int bridgeMinWaterDepth) {
        if (existing == null || existing.isEmpty() || segments == null
                || provisionalTargetY == null || segments.size() != provisionalTargetY.size()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }

        List<BlockPos> centers = new ArrayList<>(segments.size());
        Map<Long, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            BlockPos center = segments.get(i).middlePos();
            centers.add(center);
            indices.putIfAbsent(xzKey(center), i);
        }
        boolean[] tunnel = new boolean[segments.size()];
        if (provisionalTunnels != null) {
            for (RoadSpan span : provisionalTunnels) {
                if (span == null || span.type() != SpanType.TUNNEL) continue;
                Integer start = indices.get(xzKey(span.start()));
                Integer end = indices.get(xzKey(span.end()));
                if (start == null || end == null) continue;
                for (int i = Math.min(start, end); i <= Math.max(start, end); i++) tunnel[i] = true;
            }
        }

        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        Map<Long, AccurateHeightSample> samples = accurate.samplePositions(centers);
        List<RoadSpan> retained = new ArrayList<>(existing.size());
        int clearHeight = Math.max(2, Math.min(16, tunnelClearHeight));
        for (RoadSpan span : existing) {
            if (span == null || (span.type() != SpanType.BRIDGE
                    && span.type() != SpanType.ROAD_CROSSING)) {
                retained.add(span);
                continue;
            }
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) {
                retained.add(span);
                continue;
            }

            int from = Math.min(start, end);
            int to = Math.max(start, end);
            boolean foundWater = false;
            boolean safelyBelowEveryWaterColumn = true;
            int maximumTunnelCeiling = Integer.MIN_VALUE;
            int minimumBed = Integer.MAX_VALUE;
            for (int i = from; i <= to; i++) {
                BlockPos center = centers.get(i);
                BridgeWaterSample water = bridgeWaterSample(
                        level, cache, terrain, accurate, samples, streams, center, bridgeMinWaterDepth);
                if (!water.water()) continue;
                foundWater = true;
                int ceilingY = provisionalTargetY.get(i) + clearHeight - 1;
                maximumTunnelCeiling = Math.max(maximumTunnelCeiling, ceilingY);
                minimumBed = Math.min(minimumBed, water.bedY());
                if (!tunnel[i] || !tunnelFitsBelowWaterBed(
                        provisionalTargetY.get(i), clearHeight, water.bedY())) {
                    safelyBelowEveryWaterColumn = false;
                    break;
                }
            }
            if (foundWater && safelyBelowEveryWaterColumn) {
                LOGGER.info("RoadWeaver retained underground tunnel below surface water type={} range={}..{} "
                                + "maximumTunnelCeilingY={} minimumRiverBedY={}",
                        span.type(), from, to, maximumTunnelCeiling, minimumBed);
                continue;
            }
            retained.add(span);
        }
        return retained;
    }

    static boolean tunnelFitsBelowWaterBed(int roadY, int clearHeight, int bedY) {
        if (bedY == Integer.MIN_VALUE) return false;
        int safeClearHeight = Math.max(2, Math.min(16, clearHeight));
        long ceilingY = (long) roadY + safeClearHeight - 1L;
        return ceilingY < bedY;
    }

    private static void appendTunnelWithSurfaceMouths(List<RoadSpan> result,
            List<RoadSegmentPlacement> segments,
            List<BlockPos> centers, List<Integer> targetY, boolean[] bridge,
            Map<Long, AccurateHeightSample> samples,
            AccurateHeightSampler accurate,
            int buriedStart, int buriedEnd,
            RoadGradePlanner.SurfaceGradeLimits limits) {
        if (buriedEnd - buriedStart + 1 < TUNNEL_RUN_MIN_LENGTH) return;

        // Include the complete approach up to the last surface-height segment.
        // The builder treats those mouth sections as open portals instead of
        // demanding a full stone roof. Keeping only the >=7 block core left a
        // buried gap between the visible road and the tunnel interior.
        int leftMouth = buriedStart - 1;
        while (leftMouth >= 0 && !bridge[leftMouth]
                && !fullWidthSurfaceConnected(segments, centers, targetY, samples, accurate,
                leftMouth, limits)) leftMouth--;
        int rightMouth = buriedEnd + 1;
        while (rightMouth < centers.size() && !bridge[rightMouth]
                && !fullWidthSurfaceConnected(segments, centers, targetY, samples, accurate,
                rightMouth, limits)) rightMouth++;

        if (leftMouth < 0 || rightMouth >= centers.size()
                || bridge[Math.max(0, Math.min(bridge.length - 1, leftMouth))]
                || bridge[Math.max(0, Math.min(bridge.length - 1, rightMouth))]) {
            // The route is already a sustained deep cut. Do not discard the
            // tunnel classification merely because a clean surface mouth
            // could not be found (for example at a connected mountain edge).
            // Otherwise the same stations fall back to ordinary-road paving
            // and are rejected later as cut-too-deep, leaving a gap instead
            // of excavating the planned tunnel.
            int fallbackStart = buriedStart;
            int fallbackEnd = buriedEnd;
            if (fallbackStart > 0 && !bridge[fallbackStart - 1]) fallbackStart--;
            if (fallbackEnd + 1 < centers.size() && !bridge[fallbackEnd + 1]) fallbackEnd++;
            if (fallbackEnd - fallbackStart + 1 >= TUNNEL_RUN_MIN_LENGTH) {
                result.add(new RoadSpan(centers.get(fallbackStart), centers.get(fallbackEnd), SpanType.TUNNEL));
                LOGGER.warn("RoadWeaver retained deep-cut tunnel without stable surface mouths "
                                + "range={}..{} buried={}..{}",
                        fallbackStart, fallbackEnd, buriedStart, buriedEnd);
            }
            return;
        }
        int tunnelStart = leftMouth;
        int tunnelEnd = rightMouth;
        if (tunnelEnd - tunnelStart + 1 >= TUNNEL_RUN_MIN_LENGTH) {
            result.add(new RoadSpan(centers.get(tunnelStart), centers.get(tunnelEnd), SpanType.TUNNEL));
        }
    }

    private static Map<Long, AccurateHeightSample> sampleTunnelFootprints(
            List<RoadSegmentPlacement> segments,
            List<BlockPos> centers,
            List<Integer> targetY,
            ServerLevel level,
            TerrainSamplingCache cache) {
        if (segments == null || level == null || cache == null) return Map.of();
        List<BlockPos> positions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            if (segment == null) continue;
            // 0.9.063：中心站列**必须**被采到 —— 突起度判据吃的是中心站的地形高度，
            // 而 footprint 不保证包含中心列（见 TwoStagePathfindingManager / RoadSnapService）。
            // 漏采会退回 center.getY()（那是**规划路面**高度），突起度就会恒为 0。
            if (segment.middlePos() != null) positions.add(segment.middlePos());
            if (segment.positions() == null) continue;
            positions.addAll(segment.positions());
        }
        return positions.isEmpty() ? Map.of() : cache.getAccurateSampler(level).samplePositions(positions);
    }

    private static boolean fullWidthSurfaceConnected(List<RoadSegmentPlacement> segments,
                                                     List<BlockPos> centers,
                                                     List<Integer> targetY,
                                                     Map<Long, AccurateHeightSample> samples,
                                                     AccurateHeightSampler accurate,
                                                     int index,
                                                     RoadGradePlanner.SurfaceGradeLimits limits) {
        // Same cut/fill source as RoadGradePlanner (TerrainProfileConfig +
        // terrain profile mode) so tunnel portal selection cannot disagree
        // with the profile clamps that produced the road height.
        int cutLimit = limits.cut(false);
        int fillLimit = limits.fill(false);
        if (accurate == null || samples == null || samples.isEmpty()) {
            int fallbackOverburden = overburden(centers, targetY, index);
            return fallbackOverburden <= cutLimit
                    && fallbackOverburden >= -fillLimit;
        }
        RoadSegmentPlacement segment = segments.get(index);
        if (segment == null || segment.positions() == null || segment.positions().isEmpty()) return false;
        int roadY = targetY.get(index);
        for (BlockPos position : segment.positions()) {
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            int terrainY = sample != null ? accurate.surfaceHeight(sample) : centers.get(index).getY();
            // A tunnel may begin immediately after a legal open cut.  Requiring
            // a near-flat (two-block) station here left a 5 -> 6 block hillside
            // transition without a valid portal, so construction later skipped
            // the six-block core instead of building the tunnel.
            if (terrainY - roadY > cutLimit
                    || roadY - terrainY > fillLimit) return false;
        }
        return true;
    }

    private static int maximumFootprintOverburden(List<RoadSegmentPlacement> segments,
                                                   List<BlockPos> centers,
                                                   List<Integer> targetY,
                                                   Map<Long, AccurateHeightSample> samples,
                                                   AccurateHeightSampler accurate,
                                                   int index) {
        if (index < 0 || index >= segments.size() || index >= targetY.size()) return Integer.MAX_VALUE;
        int roadY = targetY.get(index);
        RoadSegmentPlacement segment = segments.get(index);
        if (accurate == null || samples == null || samples.isEmpty()
                || segment == null || segment.positions() == null || segment.positions().isEmpty()) {
            return overburden(centers, targetY, index);
        }
        int maximum = Integer.MIN_VALUE;
        for (BlockPos position : segment.positions()) {
            if (position == null) continue;
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(position.getX(), position.getZ()));
            int terrainY = sample != null ? accurate.surfaceHeight(sample) : centers.get(index).getY();
            maximum = Math.max(maximum, terrainY - roadY);
        }
        return maximum == Integer.MIN_VALUE ? overburden(centers, targetY, index) : maximum;
    }

    private static int overburden(List<BlockPos> centers, List<Integer> targetY, int index) {
        BlockPos center = centers.get(index);
        Integer target = targetY.get(index);
        return center == null || target == null ? Integer.MAX_VALUE : center.getY() - target;
    }

    private static void collectBridgeSpans(List<RoadSpan> spans,
                                           List<RoadSegmentPlacement> segments,
                                           List<BlockPos> centers,
                                           ServerLevel level,
                                           TerrainSamplingCache cache,
                                           PathTerrainField terrain,
                                           AccurateHeightSampler accurate,
                                           Map<Long, AccurateHeightSample> samples,
                                           StreamsReflowingCompat.SamplingSession streams,
                                           int bridgeMinWaterDepth) {
        int minWaterDepth = bridgeMinWaterDepth;
        boolean[] waterMask = new boolean[centers.size()];
        int[] surfaceY = new int[centers.size()];
        boolean inWater = false;
        int waterStart = -1;
        int highestWaterSurface = Integer.MIN_VALUE;
        // 0.9.034（R-04 诊断）：一段水域里「整站有水」与「只有部分列有水」的站数。
        // 现有 bridgeWaterSample 已经是**逐 footprint 列**采样后取 OR（segmentWaterSample），
        // 所以剩下唯一没量过的就是：这个 OR 到底有多宽松 —— 如果大量站是「5 列里只有 1 列湿」，
        // 那改成逐列掩码就能把桥明显缩短；如果绝大多数站是「整站都湿」，逐列改造就没有收益。
        // 纯计数，不改变 waterMask，也不改变任何 span。
        int[] wetColumns = new int[2];
        int runStations = 0;
        int runFullWet = 0;
        int runPartlyWet = 0;
        int runSingleColumnWet = 0;
        int runFootprint = 0;

        for (int i = 0; i < centers.size(); i++) {
            BridgeWaterSample waterSample = segmentWaterSample(
                    level, cache, terrain, accurate, samples, streams, segments.get(i), minWaterDepth, wetColumns);
            boolean water = waterSample.water();
            waterMask[i] = water;
            surfaceY[i] = terrainSurfaceY(terrain, accurate, samples, centers.get(i));

            if (water) {
                runStations++;
                if (wetColumns[0] > 0) {
                    runFootprint = Math.max(runFootprint, wetColumns[0]);
                    if (wetColumns[1] >= wetColumns[0]) runFullWet++;
                    else {
                        runPartlyWet++;
                        if (wetColumns[1] <= 1) runSingleColumnWet++;
                    }
                }
            }

            if (water && !inWater) {
                inWater = true;
                waterStart = i;
                highestWaterSurface = waterSample.waterSurfaceY();
            } else if (water) {
                highestWaterSurface = Math.max(highestWaterSurface, waterSample.waterSurfaceY());
            } else if (!water && inWater) {
                appendWaterCrossingSpan(spans, centers, waterStart, i - 1, highestWaterSurface);
                logWaterMaskDiag(waterStart, i - 1, runStations, runFootprint,
                        runFullWet, runPartlyWet, runSingleColumnWet);
                inWater = false;
                waterStart = -1;
                highestWaterSurface = Integer.MIN_VALUE;
                runStations = 0; runFullWet = 0; runPartlyWet = 0; runSingleColumnWet = 0; runFootprint = 0;
            }
        }

        if (inWater && waterStart >= 0) {
            appendWaterCrossingSpan(spans, centers, waterStart, centers.size() - 1, highestWaterSurface);
            logWaterMaskDiag(waterStart, centers.size() - 1, runStations, runFootprint,
                    runFullWet, runPartlyWet, runSingleColumnWet);
        }
        // A canyon is not a river. Only the actual water mask above may create
        // BRIDGE or ROAD_CROSSING spans.
    }

    /** 0.9.034（R-04 诊断）：一段水域的「湿列密度」只读摘要，每段水一行。 */
    private static void logWaterMaskDiag(int waterStart, int waterEnd, int stations, int footprint,
                                         int fullWet, int partlyWet, int singleColumnWet) {
        if (stations <= 0) return;
        LOGGER.info("RoadWeaver [ROAD-BRIDGE-MASK] waterRange={}..{} stations={} footprintCols={} "
                        + "stationsFullWet={} stationsPartlyWet={} partlyWetSingleColumn={}",
                waterStart, waterEnd, stations, footprint, fullWet, partlyWet, singleColumnWet);
    }

    /** Samples every rasterized road column, not just the center station. */
    private static BridgeWaterSample segmentWaterSample(ServerLevel level,
                                                        TerrainSamplingCache cache,
                                                        PathTerrainField terrain,
                                                        AccurateHeightSampler accurate,
                                                        Map<Long, AccurateHeightSample> samples,
                                                        StreamsReflowingCompat.SamplingSession streams,
                                                        RoadSegmentPlacement segment,
                                                        int minWaterDepth,
                                                        int[] wetColumnsOut) {
        if (segment == null) return new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
        List<BlockPos> footprint = segment.positions();
        if (footprint == null || footprint.isEmpty()) footprint = List.of(segment.middlePos());
        boolean found = false;
        int surface = Integer.MIN_VALUE;
        int bed = Integer.MAX_VALUE;
        int columns = 0;
        int wetColumns = 0;
        for (BlockPos position : footprint) {
            if (position == null) continue;
            columns++;
            BridgeWaterSample sample = bridgeWaterSample(
                    level, cache, terrain, accurate, samples, streams, position, minWaterDepth);
            if (!sample.water()) continue;
            wetColumns++;
            found = true;
            surface = Math.max(surface, sample.waterSurfaceY());
            bed = Math.min(bed, sample.bedY());
        }
        if (wetColumnsOut != null && wetColumnsOut.length >= 2) {
            wetColumnsOut[0] = columns;
            wetColumnsOut[1] = wetColumns;
        }
        return found
                ? new BridgeWaterSample(true, surface, bed == Integer.MAX_VALUE ? Integer.MIN_VALUE : bed)
                : new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private static int terrainSurfaceY(PathTerrainField terrain,
                                       AccurateHeightSampler accurate,
                                       Map<Long, AccurateHeightSample> samples,
                                       BlockPos pos) {
        if (terrain != null && terrain.hasAccurateSample(pos.getX(), pos.getZ())) {
            return terrain.height(pos.getX(), pos.getZ());
        }
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(pos.getX(), pos.getZ()));
        return sample != null ? sample.worldSurfaceWg() : accurate.worldSurfaceWg(pos.getX(), pos.getZ());
    }

    private static BridgeWaterSample bridgeWaterSample(ServerLevel level,
                                         TerrainSamplingCache cache,
                                         PathTerrainField terrain,
                                         AccurateHeightSampler accurate,
                                         Map<Long, AccurateHeightSample> samples,
                                         StreamsReflowingCompat.SamplingSession streams,
                                         BlockPos pos,
                                         int minWaterDepth) {
        StreamsReflowingCompat.RiverColumn stream = streams == null
                ? StreamsReflowingCompat.sample(level, pos.getX(), pos.getZ())
                : streams.sample(pos.getX(), pos.getZ());
        if (streams != null && streams.isFailed()) {
            // A failed Streams session must not turn the whole road into one
            // giant bridge. Fall back to the terrain water mask below.
            return new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
        if (stream.isUnknown()) {
            // A failed Streams query must never downgrade a water column to
            // ordinary road construction. Use a conservative sea-level
            // bridge sample; the final validator can still reject it if the
            // resulting span cannot be made safe.
            int surface = level == null ? 63 : level.getSeaLevel();
            return new BridgeWaterSample(true, surface, surface - 1);
        }
        // Streams' channel marker is authoritative for rivers. A shallow,
        // short channel must still become ROAD_CROSSING; applying the bridge
        // depth threshold here incorrectly removed the span and made the
        // final water validator reject it as ordinary construction. The
        // threshold remains applicable to generic terrain water below.
        if (stream.inChannel()) {
            return new BridgeWaterSample(true, stream.waterSurfaceY(), stream.bedY());
        }
        if (streams != null && streams.sampleLake(pos.getX(), pos.getZ()).present()) {
            StreamsReflowingCompat.LakeColumn lake = streams.sampleLake(pos.getX(), pos.getZ());
            return new BridgeWaterSample(true, lake.surfaceY(), lake.bedY());
        }
        if (terrain != null && terrain.hasAccurateSample(pos.getX(), pos.getZ())) {
            PathTerrainField.SampleBundle sample = terrain.sampleBundle(pos.getX(), pos.getZ());
            boolean water = sample.columnWater()
                    && sample.waterDepth() >= Math.max(1, minWaterDepth);
            int waterSurfaceY = SurfaceWaterClassifier.waterSurfaceY(
                    sample.height(), sample.oceanFloor(), sample.waterBiome(), terrain.seaLevel(), sample.columnWater());
            return new BridgeWaterSample(water,
                    water ? waterSurfaceY : Integer.MIN_VALUE,
                    water ? sample.oceanFloor() : Integer.MIN_VALUE);
        }
        int sea = level.getSeaLevel();
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(pos.getX(), pos.getZ()));
        int oceanFloor = sample != null ? sample.oceanFloorWg() : accurate.oceanFloorWg(pos.getX(), pos.getZ());
        int surfaceY = sample != null ? sample.worldSurfaceWg() : accurate.worldSurfaceWg(pos.getX(), pos.getZ());
        boolean waterBiome = isWaterLike(cache, pos.getX(), pos.getZ(), level);
        boolean waterColumn = SurfaceWaterClassifier.isWaterColumn(surfaceY, oceanFloor, waterBiome, sea);
        int waterDepth = SurfaceWaterClassifier.waterDepth(
                surfaceY, oceanFloor, waterBiome, sea, waterColumn);
        boolean bridgeWater = waterColumn && waterDepth >= Math.max(1, minWaterDepth);
        int waterSurface = bridgeWater
                ? SurfaceWaterClassifier.waterSurfaceY(surfaceY, oceanFloor, waterBiome, sea, true)
                : Integer.MIN_VALUE;
        return new BridgeWaterSample(bridgeWater, waterSurface,
                bridgeWater ? oceanFloor : Integer.MIN_VALUE);
    }

    private static void appendWaterCrossingSpan(List<RoadSpan> spans,
                                                List<BlockPos> centers,
                                                int waterStart,
                                                int waterEnd,
                                                int waterSurfaceY) {
        int[] terrain = centers.stream().mapToInt(BlockPos::getY).toArray();
        int minimumBankY = waterSurfaceY == Integer.MIN_VALUE ? Integer.MIN_VALUE : waterSurfaceY + 1;
        int[] stable = BridgeDeckProfile.findStableBankRange(terrain, waterStart, waterEnd, minimumBankY);
        int widthBlocks = crossingWidthBlocks(centers, waterStart, waterEnd);
        SpanType type = crossingTypeForWidth(widthBlocks);
        appendNormalizedWaterSpan(spans, centers, stable[0], stable[1], type);
        LOGGER.info("RoadWeaver crossing plan type={} widthBlocks={} waterRange={}..{} stableRange={}..{} "
                        + "waterSurfaceY={}",
                type, widthBlocks, waterStart, waterEnd, stable[0], stable[1], waterSurfaceY);
    }

    static SpanType crossingTypeForWidth(int widthBlocks) {
        return widthBlocks <= ROAD_CROSSING_MAX_WIDTH_BLOCKS
                ? SpanType.ROAD_CROSSING
                : SpanType.BRIDGE;
    }

    /** Horizontal block width of one wet range, used by the short-ford decision as well. */
    public static int crossingWidthBlocks(List<BlockPos> centers, int waterStart, int waterEnd) {
        if (centers == null || centers.isEmpty()) return 0;
        int start = Math.max(0, Math.min(waterStart, centers.size() - 1));
        int end = Math.max(start, Math.min(waterEnd, centers.size() - 1));
        double distance = 0.0D;
        for (int i = start; i < end; i++) {
            BlockPos a = centers.get(i);
            BlockPos b = centers.get(i + 1);
            distance += horizontalDistance(a, b);
        }
        double beforeStep = start > 0
                ? horizontalDistance(centers.get(start - 1), centers.get(start)) : 1.0D;
        double afterStep = end + 1 < centers.size()
                ? horizontalDistance(centers.get(end), centers.get(end + 1)) : beforeStep;
        double representedBlockWidth = Math.max(1.0D, (beforeStep + afterStep) * 0.5D);
        return Math.max(1, (int) Math.round(distance + representedBlockWidth));
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        long dx = (long) b.getX() - a.getX();
        long dz = (long) b.getZ() - a.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    /**
     * Water ranges include stable bank approaches. Adjacent narrow and wide
     * water ranges can therefore overlap by one or more stations even though
     * the water itself does not. A bridge owns that overlap; trim the smaller
     * crossing instead of persisting two physical span types for one station.
     */
    private static void appendNormalizedWaterSpan(List<RoadSpan> spans,
                                                  List<BlockPos> centers,
                                                  int waterStart,
                                                  int waterEnd,
                                                  SpanType type) {
        if (spans == null || centers == null || centers.isEmpty()) return;
        int start = Math.max(0, Math.min(waterStart, centers.size() - 1));
        int end = Math.max(start, Math.min(waterEnd, centers.size() - 1));
        if (type == SpanType.BRIDGE) {
            List<RoadSpan> retained = new ArrayList<>();
            for (RoadSpan existing : spans) {
                if (existing == null || existing.type() != SpanType.ROAD_CROSSING) {
                    retained.add(existing);
                    continue;
                }
                int oldStart = centers.indexOf(existing.start());
                int oldEnd = centers.indexOf(existing.end());
                if (oldStart < 0 || oldEnd < 0) {
                    retained.add(existing);
                    continue;
                }
                int left = Math.min(oldStart, oldEnd);
                int right = Math.max(oldStart, oldEnd);
                if (right < start || left > end) {
                    retained.add(existing);
                    continue;
                }
                if (left + 1 < start) {
                    retained.add(new RoadSpan(centers.get(left), centers.get(start - 1),
                            SpanType.ROAD_CROSSING));
                }
                if (right > end + 1) {
                    retained.add(new RoadSpan(centers.get(end + 1), centers.get(right),
                            SpanType.ROAD_CROSSING));
                }
            }
            spans.clear();
            spans.addAll(retained);
            spans.add(new RoadSpan(centers.get(start), centers.get(end), SpanType.BRIDGE));
            return;
        }

        // A narrow crossing arriving after a bridge is clipped to the bridge
        // boundary. If it is fully covered, no ROAD_CROSSING span is emitted.
        List<int[]> remaining = new ArrayList<>();
        remaining.add(new int[]{start, end});
        for (RoadSpan existing : spans) {
            if (existing == null || existing.type() != SpanType.BRIDGE) continue;
            int bridgeStart = centers.indexOf(existing.start());
            int bridgeEnd = centers.indexOf(existing.end());
            if (bridgeStart < 0 || bridgeEnd < 0) continue;
            int left = Math.min(bridgeStart, bridgeEnd);
            int right = Math.max(bridgeStart, bridgeEnd);
            List<int[]> next = new ArrayList<>();
            for (int[] interval : remaining) {
                if (interval[1] < left || interval[0] > right) {
                    next.add(interval);
                } else {
                    if (interval[0] < left) next.add(new int[]{interval[0], left - 1});
                    if (interval[1] > right) next.add(new int[]{right + 1, interval[1]});
                }
            }
            remaining = next;
            if (remaining.isEmpty()) break;
        }
        for (int[] interval : remaining) {
            if (interval[0] < interval[1]) {
                spans.add(new RoadSpan(centers.get(interval[0]), centers.get(interval[1]),
                        SpanType.ROAD_CROSSING));
            }
        }
    }

    private static boolean[] crossingMask(List<RoadSpan> spans, List<BlockPos> centers) {
        boolean[] result = new boolean[centers.size()];
        for (RoadSpan span : spans) {
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) result[i] = true;
        }
        return result;
    }

    private record BridgeWaterSample(boolean water, int waterSurfaceY, int bedY) {
    }

    private static int indexOfXz(List<BlockPos> centers, BlockPos target) {
        if (centers == null || target == null) return -1;
        long key = xzKey(target);
        for (int i = 0; i < centers.size(); i++) {
            if (xzKey(centers.get(i)) == key) return i;
        }
        return -1;
    }

    /** Builds one x/z -> index lookup for a whole span pass. */
    private static Map<Long, Integer> xzIndex(List<BlockPos> centers) {
        Map<Long, Integer> index = new HashMap<>(centers == null ? 0 : centers.size() * 2);
        if (centers == null) return index;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            if (center != null) index.putIfAbsent(xzKey(center), i);
        }
        return index;
    }

    private static int indexOfXz(Map<Long, Integer> index, BlockPos target) {
        if (index == null || target == null) return -1;
        Integer found = index.get(xzKey(target));
        return found == null ? -1 : found;
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /**
     * A dry depression only becomes a bridge/crossing once the required fill
     * above its lowest footprint column reaches this depth. Independent of the
     * ordinary fill limit: shallow depressions are graded, deep ones bridged.
     */
    private static int dryDepressionBridgeDepth() {
        try {
            return Math.max(2, Math.min(16,
                    ConfigService.get().planning().dryDepressionBridgeDepth()));
        } catch (Throwable ignored) {
            return DEFAULT_DRY_DEPRESSION_BRIDGE_DEPTH;
        }
    }
}
