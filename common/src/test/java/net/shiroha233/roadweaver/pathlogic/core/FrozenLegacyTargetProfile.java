/* 文件职责：冻结0b4b1dc的旧模式编排，仅世界三次调用经端口替代，独立于迁出后的编排。 */
package net.shiroha233.roadweaver.pathlogic.core;

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

final class FrozenLegacyTargetProfile {
    static int[] apply(int[] initial, LegacyTargetProfile.Stations stations, List<RoadSpan> spans,
                       RoadGenerationConfig cfg, LegacyTargetProfile.BoundaryLocks locks,
                       StructureConnection connection, LegacyTargetProfile.WorldPasses passes) {
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
            LOGGER.info("RoadWeaver proactive tunnel planning selected mountain profile: changedStations={} totalStations={}",
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
            LOGGER.info("RoadWeaver structure boundary grades locked connection={}->{} startY={} endY={} "
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
        passes.crossing(smoothed);
        smoothed = passes.undersea(smoothed);

        smoothed = SlopeContinuityEnforcer.enforceContinuousSlopePreferCut(
                smoothed, isCrossing, cfg.maxSlopeStepPerTwoSegments(), pinnedMask,
                SlopeContinuityEnforcer.configuredStationSpacing(centers));

        smoothed = closeTunnelWaterBoundary(smoothed, centers, spans);

        smoothed = RoadGradePlanner.removeSingleStationSpikes(smoothed, tunnelMask);

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
