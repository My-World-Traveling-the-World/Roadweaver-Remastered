/* 文件职责：从道路段中心线中提取桥梁与隧道跨度。 */
package net.shiroha233.roadweaver.pathfinding;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
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
import static net.shiroha233.roadweaver.pathfinding.UnderseaTunnelPlanner.*;
import static net.shiroha233.roadweaver.pathfinding.FillBridgeExtender.*;
import static net.shiroha233.roadweaver.pathfinding.SpanConflictResolver.*;
import static net.shiroha233.roadweaver.pathfinding.TunnelFootprintSampler.*;

/**
 * 道路跨度提取器。
 */
public final class PathSpanExtractor {
    static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    static final int TUNNEL_RUN_MIN_LENGTH = 3;
    // ---- 0.9.010 突起度(prominence)取证：只读诊断，判定路径一行未动 ----
    // 用户观察：沿途常有「宽 5~10 格、高 10~15 格」的孤立突起，路却被打成断头路。怀疑是口径问题：
    // 现有 buried 用「footprint 最大覆土 = 地形 − targetY(规划路面)」，纵断面一旦沿突起爬上去，
    // 这个差值就趋近 0，于是判不出。这里另算一套「中心站地形高度 − 两侧基准高度」的突起度，
    // 与旧判据并列打印，用于一局取数后定阈值。buried 仍然是 oldBuried，本段不影响任何输出。
    // 开关：默认 **关**（0.9.015 改）。原来默认 true，等于给每个玩家都刷 [ROAD-BUMP] 日志；
    // 而这套取证在两片世界跑完后结论已经明确：同一判据下 bumps 全是 0，promMax=n/a ——
    // 说明「孤立突起」不是这条路线的主要地形形态，取证使命结束。
    // 需要复现时加 -Droadweaver.bumpDiag=true；阈值/窗口仍可 -Droadweaver.bumpProminence= / bumpWindow=。
    static final boolean BUMP_DIAG = Boolean.parseBoolean(
            System.getProperty("roadweaver.bumpDiag", "false"));
    static final int BUMP_PROMINENCE_THRESHOLD = Integer.getInteger(
            "roadweaver.bumpProminence", 8);
    static final int BUMP_WINDOW_STATIONS = Integer.getInteger(
            "roadweaver.bumpWindow", 12);
    /** 配置不可读取时使用明确的干凹地阈值默认值，不能跳过桥接判定。 */
    static final int DEFAULT_DRY_DEPRESSION_BRIDGE_DEPTH = 6;
    static final int CANYON_BANK_SEARCH_SEGMENTS = 8;
    static final int CANYON_MIN_DEPTH_BLOCKS = 10;
    static final int CANYON_MIN_WIDTH_BLOCKS = 5;
    /** 仅很长且很深的海洋跨越考虑透明海底隧道，普通河湖仍保留桥梁。 */
    static final int UNDERSEA_TUNNEL_MIN_LENGTH_BLOCKS = 256;
    static final int UNDERSEA_TUNNEL_MIN_DEPTH_BLOCKS = 8;
    static final double UNDERSEA_TUNNEL_SELECTION_PROBABILITY = 0.5D;
    /** 海底隧道延伸到陆侧的接近段，避免在岸线突然封口。 */
    static final int UNDERSEA_APPROACH_TUNNEL_BLOCKS = 128;
    /** 窄水域按水平宽度判短跨越，不能只看站数。 */
    public static final int ROAD_CROSSING_MAX_WIDTH_BLOCKS = 12;
    /** 浅干凹地在有限端点坡差内允许整平，避免全部升级为桥。 */
    static final int MAX_DRY_CROSSING_ENDPOINT_DELTA = 3;
    /** 搜索越过直接边缘以确认真实峡谷，避免单列噪声拉出过短跨越。 */
    static final int DRY_DEPRESSION_BANK_SEARCH_STATIONS = 32;
    /** 深干凹地禁止落回普通填方，避免在谷底堆出高填土墙。 */
    static final int DRY_DEPRESSION_FORCE_BRIDGE_DEPTH = 16;
    /** 桥梁延至河流雕刻走廊的稳定岸边，避免桥端低岸被后续普通填方拒绝。
 *
     * Streams distinguishes its wet channel from the wider terrain-carved
     * corridor.  A persisted bridge must reach the latter's bank; otherwise a
     * low bank station is later rejected as an unsafe ordinary-road fill.
     */
    static final int STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS = 2;

    private PathSpanExtractor() {}

    /** 偏桥模式放宽水跨越深度，其他模式保留原配置阈值。
 *
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
     * <p>定甲板（{@code RoadSpanProfileOps.applyCrossingProfiles}）过去**另起一条水面查询**：只问 Streams
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

    /** 已有精采样本包含路宽列；直接复用同一物理水掩码来确定短跨净空。 */
    public static int shortCrossingWaterY(RoadSegmentPlacement station, ServerLevel level,
            TerrainSamplingCache cache, PathTerrainField terrain, AccurateHeightSampler accurate,
            Map<Long, AccurateHeightSample> samples, StreamsReflowingCompat.SamplingSession streams) {
        return segmentWaterSample(level, cache, terrain, accurate, samples, streams, station, 1, null).waterSurfaceY();
    }

    /** 从最终目标剖面识别隧道，不能只用原始节点的Y跳变代替覆土。
 *
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
        // 复用纵断面限制和已经取到的列；显式要求隧道优先时，保留玩家的地形策略。
        var profileMode = ConfigService.get().planning().terrainProfileMode();
        SurfaceGradeLimits limits = SurfaceGradeLimits.capture(profileMode);
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
                LOGGER.info("【山体包络隧道候选判定】 {}", candidate.logLine());
                LOGGER.info("【山体包络路线选择结果】 {}", candidate.routeChoiceLine());
            }
            if (!candidate.elected()) continue;
            if (profileMode.prefersExistingSurface() && SurfaceTunnelPreference.canUseSurface(
                    centers, targetY, Math.max(0, candidate.start() - 1),
                    Math.min(centers.size() - 1, candidate.end() + 1), tunnelCosts.softGradeLimit(),
                    i -> !bridge[i] && !isWetStation(centers, footprintSamples, i)
                            && fullWidthSurfaceConnected(segments, centers, targetY, footprintSamples, accurate, i, limits))) {
                LOGGER.info("【已有缓坡地表可满足挖填预算，取消隧道候选】 [ROAD-TUNNEL-SURFACE] seg={}..{}",
                        candidate.start(), candidate.end());
                continue;
            }
            for (int i = candidate.start(); i <= candidate.end(); i++) {
                envelopeSealed[i] = prominence[i] >= TunnelEnvelopeCriterion.TARGET_BURIAL_BLOCKS;
            }
        }
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
                LOGGER.info("【陆地隧道埋深与判定统计】 " + "[ROAD-LANDTUNNEL] seg={}-{} stations={} oldBuried={} newBuried={} "
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
            LOGGER.info("【陆地隧道判据汇总】 " + "[ROAD-LANDTUNNEL-SUMMARY] stations={} waterStations={} "
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
    static int[] centerTerrainHeights(List<BlockPos> centers,
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
    static int lowQuantile(int[] values, int from, int to) {
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
    static int[] prominences(int[] terrainHeight, int window) {
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
    static void logBumpRuns(int[] terrainHeight,
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
        LOGGER.info("【路面凸起识别与漏检统计】 " + "[ROAD-BUMP-SUMMARY] stations={} bumps={} bumpsMissed={} promMax={} "
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
    static TunnelEnvelopeCriterion.Costs tunnelCosts() {
        try {
            var cfg = ConfigService.get().pathfindingCost();
            double step = Math.max(1, cfg.effectivePathGridStep());
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

    /** 中心站覆土按地面高度减计划路面高度计算，避免把水深当岩层。 */
    static int centerStationOverburden(List<BlockPos> centers, List<Integer> targetY,
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

    /** 中心列是否水域由水面与海床高度关系判断，不能只认河流群系。 */
    static boolean isWetStation(List<BlockPos> centers,
                                        Map<Long, AccurateHeightSample> samples, int index) {
        if (index < 0 || index >= centers.size() || samples == null || samples.isEmpty()) return false;
        BlockPos center = centers.get(index);
        if (center == null) return false;
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
        return sample != null && sample.worldSurfaceWg() > sample.oceanFloorWg();
    }


    /** 裁切或合流后从最终路线重建隧道跨度，避免旧端点失配。 */
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


    /** 已分类物理跨度最后互斥归一，避免同站既桥又隧道。 */
    static void collectBridgeSpans(List<RoadSpan> spans,
                                           List<RoadSegmentPlacement> segments,
                                           List<BlockPos> centers,
                                           ServerLevel level,
                                           TerrainSamplingCache cache,
                                           PathTerrainField terrain,
                                           AccurateHeightSampler accurate,
                                           Map<Long, AccurateHeightSample> samples,
                                           StreamsReflowingCompat.SamplingSession streams,
                                           int bridgeMinWaterDepth) {
        var appearance = ConfigService.get().roadAppearance();
        int maxCulvert = appearance.culvertEnabled() ? appearance.culvertMaxStations() : 0;
        int maxCulvertDepth = appearance.culvertMaxWaterDepth();
        // 即使水浅于桥梁阈值，也要看完整湿段，避免浅岸掩盖深水或遗漏涵洞。
        int minWaterDepth = 1;
        boolean[] waterMask = new boolean[centers.size()];
        int[] surfaceY = new int[centers.size()];
        boolean inWater = false;
        int waterStart = -1;
        int highestWaterSurface = Integer.MIN_VALUE;
        int deepest = 0;
        boolean authoritative = false;
        // 只统计每个水段的湿列密度，不改变按实际路宽判断跨越的口径。
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
                int depth = waterSample.bedY() == Integer.MIN_VALUE ? Integer.MAX_VALUE
                        : Math.max(1, waterSample.waterSurfaceY() - waterSample.bedY() + 1);
                deepest = Math.max(deepest, depth);
                authoritative |= waterSample.authoritative();
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
                ShortCrossingGeometry.appendPlannedWetSpan(spans, centers, surfaceY, waterStart, i - 1, highestWaterSurface,
                        deepest, maxCulvert, maxCulvertDepth, authoritative ? 0 : bridgeMinWaterDepth);
                deepest = 0;
                authoritative = false;
                logWaterMaskDiag(waterStart, i - 1, runStations, runFootprint,
                        runFullWet, runPartlyWet, runSingleColumnWet);
                inWater = false;
                waterStart = -1;
                highestWaterSurface = Integer.MIN_VALUE;
                runStations = 0; runFullWet = 0; runPartlyWet = 0; runSingleColumnWet = 0; runFootprint = 0;
            }
        }

        if (inWater && waterStart >= 0) {
            ShortCrossingGeometry.appendPlannedWetSpan(spans, centers, surfaceY, waterStart, centers.size() - 1, highestWaterSurface,
                    deepest, maxCulvert, maxCulvertDepth, authoritative ? 0 : bridgeMinWaterDepth);
            logWaterMaskDiag(waterStart, centers.size() - 1, runStations, runFootprint,
                    runFullWet, runPartlyWet, runSingleColumnWet);
        }
        // 用同一批精采识别有两岸的短干沟，不能等路面已沉到谷底后才看填方差值。
        if (!spans.isEmpty()) {
            boolean[] physical = FillBridgeExtender.specialSpanMask(spans, centers);
            for (int i = 0; i < waterMask.length; i++) waterMask[i] |= physical[i];
        }
        for (var notch : ShortCrossingGeometry.dryNotches(centers, surfaceY, dryDepressionBridgeDepth(), waterMask)) {
            spans.add(new RoadSpan(centers.get(notch.left()), centers.get(notch.right()), SpanType.ROAD_CROSSING));
        }
    }

    /** 0.9.034（R-04 诊断）：一段水域的「湿列密度」只读摘要，每段水一行。 */
    static void logWaterMaskDiag(int waterStart, int waterEnd, int stations, int footprint,
                                         int fullWet, int partlyWet, int singleColumnWet) {
        if (stations <= 0) return;
        LOGGER.info("【桥掩码湿列分布】 " + "RoadWeaver [ROAD-BRIDGE-MASK] waterRange={}..{} stations={} footprintCols={} "
                        + "stationsFullWet={} stationsPartlyWet={} partlyWetSingleColumn={}",
                waterStart, waterEnd, stations, footprint, fullWet, partlyWet, singleColumnWet);
    }

    /** 检查全部栅格路面列，避免中心安全而侧列入水。 */
    static BridgeWaterSample segmentWaterSample(ServerLevel level,
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
        boolean authoritative = false;
        for (BlockPos position : footprint) {
            if (position == null) continue;
            columns++;
            BridgeWaterSample sample = bridgeWaterSample(
                    level, cache, terrain, accurate, samples, streams, position, minWaterDepth);
            if (!sample.water()) continue;
            wetColumns++;
            found = true;
            authoritative |= sample.authoritative();
            surface = Math.max(surface, sample.waterSurfaceY());
            bed = Math.min(bed, sample.bedY());
        }
        if (wetColumnsOut != null && wetColumnsOut.length >= 2) {
            wetColumnsOut[0] = columns;
            wetColumnsOut[1] = wetColumns;
        }
        return found
                ? new BridgeWaterSample(true, surface, bed == Integer.MAX_VALUE ? Integer.MIN_VALUE : bed, authoritative)
                : new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    static int terrainSurfaceY(PathTerrainField terrain,
                                       AccurateHeightSampler accurate,
                                       Map<Long, AccurateHeightSample> samples,
                                       BlockPos pos) {
        if (terrain != null && terrain.hasAccurateSample(pos.getX(), pos.getZ())) {
            return terrain.height(pos.getX(), pos.getZ());
        }
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(pos.getX(), pos.getZ()));
        return sample != null ? sample.worldSurfaceWg() : accurate.worldSurfaceWg(pos.getX(), pos.getZ());
    }

    static BridgeWaterSample bridgeWaterSample(ServerLevel level,
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
            return new BridgeWaterSample(true, surface, Integer.MIN_VALUE, true);
        }
        // Streams' channel marker is authoritative for rivers. A shallow,
        // short channel must still become ROAD_CROSSING; applying the bridge
        // depth threshold here incorrectly removed the span and made the
        // final water validator reject it as ordinary construction. The
        // threshold remains applicable to generic terrain water below.
        if (stream.inChannel()) {
            return new BridgeWaterSample(true, stream.waterSurfaceY(), stream.bedY(), true);
        }
        if (streams != null && streams.sampleLake(pos.getX(), pos.getZ()).present()) {
            StreamsReflowingCompat.LakeColumn lake = streams.sampleLake(pos.getX(), pos.getZ());
            return new BridgeWaterSample(true, lake.surfaceY(), lake.bedY(), true);
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

    static SpanType crossingTypeForWidth(int widthBlocks) {
        return widthBlocks <= ROAD_CROSSING_MAX_WIDTH_BLOCKS
                ? SpanType.ROAD_CROSSING
                : SpanType.BRIDGE;
    }

    /** 水域宽度使用水平格距，与短涉水跨越判据保持同口径。 */
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

    static double horizontalDistance(BlockPos a, BlockPos b) {
        long dx = (long) b.getX() - a.getX();
        long dz = (long) b.getZ() - a.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    /** 稳定岸侧接近段可以重叠；桥拥有重叠站，短跨越须裁掉重叠而不能双重持久化。
 *
     * Water ranges include stable bank approaches. Adjacent narrow and wide
     * water ranges can therefore overlap by one or more stations even though
     * the water itself does not. A bridge owns that overlap; trim the smaller
     * crossing instead of persisting two physical span types for one station.
     */
    static void appendNormalizedWaterSpan(List<RoadSpan> spans,
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

    static boolean[] crossingMask(List<RoadSpan> spans, List<BlockPos> centers) {
        boolean[] result = new boolean[centers.size()];
        for (RoadSpan span : spans) {
            // 涵洞下方留空通水，该站不能又被识别成地表下的隧道。
            if (span.type() != SpanType.BRIDGE && span.type() != SpanType.ROAD_CROSSING
                    && span.type() != SpanType.CULVERT) continue;
            int start = indexOfXz(centers, span.start());
            int end = indexOfXz(centers, span.end());
            if (start < 0 || end < 0) continue;
            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) result[i] = true;
        }
        return result;
    }

    record BridgeWaterSample(boolean water, int waterSurfaceY, int bedY, boolean authoritative) {
        BridgeWaterSample(boolean water, int waterSurfaceY, int bedY) {
            this(water, waterSurfaceY, bedY, false);
        }
    }

    static int indexOfXz(List<BlockPos> centers, BlockPos target) {
        if (centers == null || target == null) return -1;
        long key = xzKey(target);
        for (int i = 0; i < centers.size(); i++) {
            if (xzKey(centers.get(i)) == key) return i;
        }
        return -1;
    }

    /** 一次跨度处理共用XZ到站号索引，避免每个跨度重复全路扫描。 */
    static Map<Long, Integer> xzIndex(List<BlockPos> centers) {
        Map<Long, Integer> index = new HashMap<>(centers == null ? 0 : centers.size() * 2);
        if (centers == null) return index;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos center = centers.get(i);
            if (center != null) index.putIfAbsent(xzKey(center), i);
        }
        return index;
    }

    static int indexOfXz(Map<Long, Integer> index, BlockPos target) {
        if (index == null || target == null) return -1;
        Integer found = index.get(xzKey(target));
        return found == null ? -1 : found;
    }

    static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /** 干凹地桥判据按完整路面最低支撑列的填方深度计算，与普通填方上限分离。
 *
     * A dry depression only becomes a bridge/crossing once the required fill
     * above its lowest footprint column reaches this depth. Independent of the
     * ordinary fill limit: shallow depressions are graded, deep ones bridged.
     */
    static int dryDepressionBridgeDepth() {
        try {
            return Math.max(2, Math.min(16,
                    ConfigService.get().planning().dryDepressionBridgeDepth()));
        } catch (Throwable ignored) {
            return DEFAULT_DRY_DEPRESSION_BRIDGE_DEPTH;
        }
    }
}
