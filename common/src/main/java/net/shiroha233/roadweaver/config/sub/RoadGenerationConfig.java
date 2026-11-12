/* 文件职责：道路生成配置，聚合道路生成所需的各项配置参数。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 道路生成配置，聚合道路生成所需的各项配置参数
 */
public final class RoadGenerationConfig implements SubConfig {

    private int effectiveRoadWidth = RoadConstants.DEFAULT_ROAD_WIDTH;
    private boolean allowArtificial = true;
    private boolean allowNatural = true;
    private int averagingRadius = RoadConstants.DEFAULT_AVERAGING_RADIUS;
    private int tunnelClearHeight = 5;
    private boolean slopeLimitEnabled = true;
    private int maxSlopeStepPerTwoSegments = RoadConstants.DEFAULT_MAX_SLOPE_STEP;
    private PathfindingCostConfig pathfinding = new PathfindingCostConfig();
    private int bridgeMinWaterDepth = RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH;
    private int bridgeDeckClearance = RoadConstants.DEFAULT_BRIDGE_DECK_CLEARANCE;
    private int bridgeRampSegments = 4;

    public static RoadGenerationConfig from(ModConfig config) {
        RoadGenerationConfig cfg = new RoadGenerationConfig();
        if (config == null) {
            cfg.sanitize();
            return cfg;
        }

        cfg.effectiveRoadWidth = config.roadAppearance().roadWidth();
        cfg.allowArtificial = config.roadAppearance().allowArtificial();
        cfg.allowNatural = config.roadAppearance().allowNatural();
        cfg.averagingRadius = config.averagingRadius();
        cfg.tunnelClearHeight = config.tunnelClearHeight();
        cfg.slopeLimitEnabled = config.slopeLimitEnabled();
        cfg.maxSlopeStepPerTwoSegments = config.roadAppearance().maxSlopeStepPerTwoSegments();

        PathfindingCostConfig pf = config.pathfindingCost();
        if (pf != null) {
            cfg.pathfinding = pf.snapshot();
        }
        cfg.bridgeMinWaterDepth = config.bridge().minWaterDepth();
        cfg.bridgeDeckClearance = config.bridgeDeckClearance();
        cfg.bridgeRampSegments = config.bridgeRampSegments();

        cfg.sanitize();
        return cfg;
    }

    @Override
    public void sanitize() {
        effectiveRoadWidth = Math.max(1, Math.min(RoadConstants.ROAD_WIDTH_MAX, effectiveRoadWidth));
        averagingRadius = Math.max(RoadConstants.AVERAGING_RADIUS_MIN,
                Math.min(RoadConstants.AVERAGING_RADIUS_MAX, averagingRadius));
        tunnelClearHeight = Math.max(2, Math.min(16, tunnelClearHeight));
        maxSlopeStepPerTwoSegments = Math.max(0, Math.min(RoadConstants.MAX_SLOPE_STEP_MAX, maxSlopeStepPerTwoSegments));
        bridgeMinWaterDepth = Math.max(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MIN,
                Math.min(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MAX, bridgeMinWaterDepth));
        bridgeDeckClearance = Math.max(RoadConstants.BRIDGE_DECK_CLEARANCE_MIN,
                Math.min(RoadConstants.BRIDGE_DECK_CLEARANCE_MAX, bridgeDeckClearance));
        bridgeRampSegments = Math.max(0, Math.min(RoadConstants.BRIDGE_RAMP_SEGMENTS_MAX, bridgeRampSegments));
        if (pathfinding == null) {
            pathfinding = new PathfindingCostConfig();
        }
        pathfinding.sanitize();
    }

    @Override
    public RoadGenerationConfig snapshot() {
        RoadGenerationConfig copy = new RoadGenerationConfig();
        copy.effectiveRoadWidth = this.effectiveRoadWidth;
        copy.allowArtificial = this.allowArtificial;
        copy.allowNatural = this.allowNatural;
        copy.averagingRadius = this.averagingRadius;
        copy.tunnelClearHeight = this.tunnelClearHeight;
        copy.slopeLimitEnabled = this.slopeLimitEnabled;
        copy.maxSlopeStepPerTwoSegments = this.maxSlopeStepPerTwoSegments;
        copy.pathfinding = (PathfindingCostConfig) this.pathfinding.snapshot();
        copy.bridgeMinWaterDepth = this.bridgeMinWaterDepth;
        copy.bridgeDeckClearance = this.bridgeDeckClearance;
        copy.bridgeRampSegments = this.bridgeRampSegments;
        return copy;
    }

    public int effectiveRoadWidth(int baseWidth) {
        return Math.max(1, Math.min(RoadConstants.ROAD_WIDTH_MAX, baseWidth > 0 ? baseWidth : effectiveRoadWidth));
    }

    public boolean allowArtificial() {
        return allowArtificial;
    }

    public boolean allowNatural() {
        return allowNatural;
    }

    public int averagingRadius() {
        return averagingRadius;
    }

    public int tunnelClearHeight() {
        return tunnelClearHeight;
    }

    public boolean slopeLimitEnabled() {
        return slopeLimitEnabled;
    }

    public int maxSlopeStepPerTwoSegments() {
        return maxSlopeStepPerTwoSegments;
    }

    public PathfindingCostConfig pathfinding() {
        return pathfinding;
    }

    public int bridgeMinWaterDepth() {
        return bridgeMinWaterDepth;
    }

    public int bridgeDeckClearance() {
        return bridgeDeckClearance;
    }

    public int bridgeRampSegments() {
        return bridgeRampSegments;
    }
}
