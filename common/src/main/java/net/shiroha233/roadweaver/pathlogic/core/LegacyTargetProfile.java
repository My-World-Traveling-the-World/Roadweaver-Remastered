/* 文件职责：保留显式旧模式的剖面编排，顺序与端点锁契约不随拆分改变。 */
package net.shiroha233.roadweaver.pathlogic.core;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import net.shiroha233.roadweaver.pathlogic.grade.SlopeContinuityEnforcer;
import java.util.*;
import static net.shiroha233.roadweaver.pathlogic.core.EndpointProfileOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps.*;
import static net.shiroha233.roadweaver.pathlogic.core.Road.*;

final class LegacyTargetProfile {
    private LegacyTargetProfile() {}
    /** 同一组站位及掩码整体传递，避免搬迁时把桥掩码与钉高掩码互换。 */
    record Stations(List<BlockPos> centers, int[] terrainY, boolean[] isCrossing,
                    boolean[] tunnelMask, boolean[] pinnedMask, List<SpanRange> crossingRanges) {}
    /** 端点锁是独立契约，旧模式每次后处理仍按原顺序重座入口坡道。 */
    record BoundaryLocks(boolean structureGradesLocked, boolean startVillage, boolean endVillage,
                         boolean startUnknown, boolean endUnknown, int[] startSharedProfile,
                         int[] endSharedProfile, int startBoundary, int endBoundary) {}
    /** 世界读写留在门面；此处只规定与剖面变换的交错顺序。 */
    interface WorldPasses {
        void crossing(int[] profile);
        int[] undersea(int[] profile);
    }
    static int[] apply(int[] initial, Stations stations, List<RoadSpan> spans,
                       RoadGenerationConfig cfg, BoundaryLocks locks,
                       StructureConnection connection, WorldPasses passes) {
        int[] smoothed = initial;
        List<BlockPos> centers = stations.centers();
        int n = centers.size();
        int[] terrainY = stations.terrainY();
        boolean[] isCrossing = stations.isCrossing(), tunnelMask = stations.tunnelMask(), pinnedMask = stations.pinnedMask();
        List<SpanRange> crossingRanges = stations.crossingRanges();
        boolean structureGradesLocked = locks.structureGradesLocked();
        boolean startVillage = locks.startVillage(), endVillage = locks.endVillage();
        boolean startUnknown = locks.startUnknown(), endUnknown = locks.endUnknown();
        int[] startSharedProfile = locks.startSharedProfile(), endSharedProfile = locks.endSharedProfile();
        int startBoundary = locks.startBoundary(), endBoundary = locks.endBoundary();
        int[] tunnelProfile;
        if (crossingRanges.isEmpty()) {
            tunnelProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                    smoothed, terrainY, isCrossing,
                    cfg.slopeLimitEnabled(), cfg.maxSlopeStepPerTwoSegments(), pinnedMask);
        } else {
            // 河岸附近仍保留地面爬坡，避免桥与隧道交替交织；护栏只影响主动选隧道。
            final int riversideTunnelGuard = Math.max(2, cfg.bridgeRampSegments() + 4);
            boolean[] tunnelGuard = isCrossing.clone();
            for (SpanRange range : crossingRanges) {
                int from = Math.max(0, range.start() - riversideTunnelGuard);
                int to = Math.min(n - 1, range.end() + riversideTunnelGuard);
                for (int k = from; k <= to; k++) tunnelGuard[k] = true;
            }
            tunnelProfile = RoadGradePlanner.applyProactiveTunnelProfile(
                    smoothed, terrainY, tunnelGuard,
                    cfg.slopeLimitEnabled(), cfg.maxSlopeStepPerTwoSegments(), pinnedMask);
        }
        if (!Arrays.equals(smoothed, tunnelProfile)) {
            int changed = 0;
            for (int i = 0; i < smoothed.length; i++) {
                if (smoothed[i] != tunnelProfile[i]) changed++;
            }
            LOGGER.info("【主动隧道规划已选取山体剖面】 " + "RoadWeaver proactive tunnel planning selected mountain profile: changedStations={} totalStations={}",
                    changed, smoothed.length);
            smoothed = tunnelProfile;
        }
        if (structureGradesLocked) {
            if (startVillage || startUnknown) {
                if (startSharedProfile != null && startSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, 0, startSharedProfile);
                } else if (startUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, 0, startBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, 0, startBoundary);
                }
            }
            if (endVillage || endUnknown) {
                if (endSharedProfile != null && endSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, smoothed.length - 1, endSharedProfile);
                } else if (endUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, smoothed.length - 1, endBoundary);
                }
            }
            BlockPos startCross = connection.from();
            BlockPos endCross = connection.to();
            LOGGER.info("【建筑边界纵坡与端点高度已锁定】 " + "RoadWeaver structure boundary grades locked connection={}->{} startY={} endY={} "
                            + "terrainStartY={} terrainEndY={} startCross=({},{}) endCross=({},{}) stations={} "
                            + "startLocked={} endLocked={} startUnknown={} endUnknown={}",
                    connection.from(), connection.to(), startBoundary, endBoundary,
                    terrainY[0], terrainY[terrainY.length - 1],
                    startCross == null ? "?" : startCross.getX(), startCross == null ? "?" : startCross.getZ(),
                    endCross == null ? "?" : endCross.getX(), endCross == null ? "?" : endCross.getZ(),
                    smoothed.length,
                    startVillage, endVillage, startUnknown, endUnknown);
        }
        logEndpointProfile("after-structure-lock", connection, smoothed, terrainY, n, startBoundary, endBoundary);
        // 世界操作保持交叉剖面在前、海底重分类在后，不能借拆分交换副作用顺序。
        passes.crossing(smoothed);
        smoothed = passes.undersea(smoothed);

        smoothed = SlopeContinuityEnforcer.enforceContinuousSlopePreferCut(
                smoothed, isCrossing, cfg.maxSlopeStepPerTwoSegments(), pinnedMask,
                SlopeContinuityEnforcer.configuredStationSpacing(centers));

        smoothed = closeTunnelWaterBoundary(smoothed, centers, spans);

        smoothed = RoadGradePlanner.removeSingleStationSpikes(smoothed, tunnelMask);

        // 后道闭合与去尖峰都可能改入口，末次重座必须晚于它们，避免锁高处留下断崖。
        if (structureGradesLocked) {
            if (pinnedMask[0]) {
                if (startSharedProfile != null && startSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, 0, startSharedProfile);
                } else if (startUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, 0, startBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, 0, startBoundary);
                }
            }
            if (pinnedMask[n - 1]) {
                if (endSharedProfile != null && endSharedProfile.length > 0) {
                    applySharedApproachProfile(smoothed, n - 1, endSharedProfile);
                } else if (endUnknown) {
                    applyPreferCutBoundaryGrade(smoothed, n - 1, endBoundary);
                } else {
                    applySingleBoundaryGrade(smoothed, n - 1, endBoundary);
                }
            }
        }

        return smoothed;
    }
}
