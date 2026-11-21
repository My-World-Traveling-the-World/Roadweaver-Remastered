/* 文件职责：仅保留BAND网格、桥隧经济判据和局部修复实际读取的配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

public final class PathfindingCostConfig implements SubConfig {

    public enum PathfindingAlgorithm {

        BAND
    }

    // 局部地形修复仍按网格搜索计价；这些权重不控制BAND的各向异性度量。
    private double orthoStepCost = RoadConstants.DEFAULT_ORTHO_STEP_COST;
    private double diagStepCost = RoadConstants.DEFAULT_DIAG_STEP_COST;
    private int elevationWeight = RoadConstants.DEFAULT_ELEVATION_WEIGHT;
    private int biomeWeight = RoadConstants.DEFAULT_BIOME_WEIGHT;
    private int stabilityWeight = RoadConstants.DEFAULT_STABILITY_WEIGHT;
    private int waterDepthWeight = RoadConstants.DEFAULT_WATER_DEPTH_WEIGHT;
    private int nearWaterCost = RoadConstants.DEFAULT_NEAR_WATER_COST;
    private double heuristicWeight = RoadConstants.DEFAULT_HEURISTIC_WEIGHT;
    private double deviationWeight = RoadConstants.DEFAULT_DEVIATION_WEIGHT;
    private int pathGridStep = RoadConstants.DEFAULT_PATH_GRID_STEP;

    // 桥隧判据比较翻山与挖隧道的费用，软硬阈值都不能误当作废弃寻路参数。
    private double softGradeLimit = RoadConstants.DEFAULT_SOFT_GRADE_LIMIT;
    private double hardGradeLimit = RoadConstants.DEFAULT_HARD_GRADE_LIMIT;
    private double softGradePenalty = RoadConstants.DEFAULT_SOFT_GRADE_PENALTY;
    private double hardGradePenalty = RoadConstants.DEFAULT_HARD_GRADE_PENALTY;

    // BAND只区分水陆，两者差价经权重与饱和处理后影响水陆选线。
    private double biomeCostWater = RoadConstants.DEFAULT_BIOME_COST_WATER;
    private double biomeCostOther = RoadConstants.DEFAULT_BIOME_COST_OTHER;

    // 合流与重叠修复使用方向和绕行约束，不能与已删除的KNN拓扑参数混淆。
    private double braidMinDirectionDot = RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT;
    private double braidNearParallelDot = RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT;
    private boolean braidAllowParallelOverlap = RoadConstants.DEFAULT_BRAID_ALLOW_PARALLEL_OVERLAP;

    private boolean braidRerouteOnOverlap = RoadConstants.DEFAULT_BRAID_REROUTE_ON_OVERLAP;

    private int braidRerouteRadius = RoadConstants.DEFAULT_BRAID_REROUTE_RADIUS;

    private double braidRerouteMaxLengthRatio = RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO;

    // 走廊属于一次规划；不落盘，快照在派发线程前捕获当前作用域。
    private transient net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan;

    private int corridorAttractRadius = RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS;

    private double corridorAttractFactor = RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR;

    private transient net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract corridorAttract;

    // 盘山折返对短腿和反向转弯计价，避免局部搜索产生密集锯齿。
    private double headingFlipCost = 16.0D;
    private double headingCurveCost = 0.0D;
    private double headingShortLegBlocks = 48.0D;
    private double headingShortLegMultiplier = 3.0D;

    @Override
    public void sanitize() {
        orthoStepCost = finiteNonNegative(orthoStepCost, RoadConstants.DEFAULT_ORTHO_STEP_COST);
        diagStepCost = finiteNonNegative(diagStepCost, RoadConstants.DEFAULT_DIAG_STEP_COST);
        elevationWeight = Math.max(0, elevationWeight);
        biomeWeight = Math.max(0, biomeWeight);
        stabilityWeight = Math.max(0, stabilityWeight);
        waterDepthWeight = Math.max(0, waterDepthWeight);
        nearWaterCost = Math.max(0, nearWaterCost);
        heuristicWeight = finiteNonNegative(heuristicWeight, RoadConstants.DEFAULT_HEURISTIC_WEIGHT);
        deviationWeight = finiteNonNegative(deviationWeight, RoadConstants.DEFAULT_DEVIATION_WEIGHT);
        pathGridStep = Math.max(RoadConstants.PATH_GRID_STEP_MIN,
                Math.min(RoadConstants.PATH_GRID_STEP_MAX, pathGridStep));

        softGradeLimit = finiteFraction(softGradeLimit, RoadConstants.DEFAULT_SOFT_GRADE_LIMIT);
        hardGradeLimit = finiteFraction(hardGradeLimit, RoadConstants.DEFAULT_HARD_GRADE_LIMIT);
        if (hardGradeLimit < softGradeLimit) hardGradeLimit = softGradeLimit;
        softGradePenalty = finiteNonNegative(softGradePenalty, RoadConstants.DEFAULT_SOFT_GRADE_PENALTY);
        hardGradePenalty = finiteNonNegative(hardGradePenalty, RoadConstants.DEFAULT_HARD_GRADE_PENALTY);

        biomeCostWater = finiteSigned(biomeCostWater, RoadConstants.DEFAULT_BIOME_COST_WATER);
        biomeCostOther = finiteSigned(biomeCostOther, RoadConstants.DEFAULT_BIOME_COST_OTHER);

        braidMinDirectionDot = finiteFraction(braidMinDirectionDot, RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT);
        braidNearParallelDot = finiteFraction(braidNearParallelDot, RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT);
        if (braidNearParallelDot < braidMinDirectionDot) {
            braidNearParallelDot = braidMinDirectionDot;
        }

        braidRerouteRadius = Math.max(4, Math.min(64, braidRerouteRadius));
        if (!Double.isFinite(braidRerouteMaxLengthRatio)) {
            braidRerouteMaxLengthRatio = RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO;
        }

        braidRerouteMaxLengthRatio = Math.max(1.05D, Math.min(4.0D, braidRerouteMaxLengthRatio));

        corridorAttractRadius = Math.max(0, Math.min(64, corridorAttractRadius));
        corridorAttractFactor = finiteFraction(corridorAttractFactor,
                RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR);
        if (corridorAttractFactor < 0.05D) corridorAttractFactor = 0.05D;
    }

    private static double finiteFraction(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : fallback;
    }

    private static double finiteNonNegative(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : fallback;
    }

    private static double finiteSigned(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    @Override
    public PathfindingCostConfig snapshot() {
        PathfindingCostConfig copy = new PathfindingCostConfig();
        copy.orthoStepCost = this.orthoStepCost;
        copy.diagStepCost = this.diagStepCost;
        copy.elevationWeight = this.elevationWeight;
        copy.biomeWeight = this.biomeWeight;
        copy.stabilityWeight = this.stabilityWeight;
        copy.waterDepthWeight = this.waterDepthWeight;
        copy.nearWaterCost = this.nearWaterCost;
        copy.heuristicWeight = this.heuristicWeight;
        copy.deviationWeight = this.deviationWeight;
        copy.pathGridStep = this.pathGridStep;
        copy.softGradeLimit = this.softGradeLimit;
        copy.hardGradeLimit = this.hardGradeLimit;
        copy.softGradePenalty = this.softGradePenalty;
        copy.hardGradePenalty = this.hardGradePenalty;
        copy.biomeCostWater = this.biomeCostWater;
        copy.biomeCostOther = this.biomeCostOther;
        copy.braidMinDirectionDot = this.braidMinDirectionDot;
        copy.braidNearParallelDot = this.braidNearParallelDot;
        copy.braidAllowParallelOverlap = this.braidAllowParallelOverlap;
        copy.headingFlipCost = this.headingFlipCost;
        copy.headingCurveCost = this.headingCurveCost;
        copy.headingShortLegBlocks = this.headingShortLegBlocks;
        copy.headingShortLegMultiplier = this.headingShortLegMultiplier;

        copy.braidRerouteOnOverlap = this.braidRerouteOnOverlap;
        copy.braidRerouteRadius = this.braidRerouteRadius;
        copy.braidRerouteMaxLengthRatio = this.braidRerouteMaxLengthRatio;

        copy.corridorAttractRadius = this.corridorAttractRadius;
        copy.corridorAttractFactor = this.corridorAttractFactor;
        copy.corridorAttract = corridorAttract();

        copy.corridorBan = corridorBan();
        return copy;
    }

    public double orthoStepCost() { return orthoStepCost; }
    public void setOrthoStepCost(double v) { this.orthoStepCost = v; }
    public double diagStepCost() { return diagStepCost; }
    public void setDiagStepCost(double v) { this.diagStepCost = v; }
    public int elevationWeight() { return elevationWeight; }
    public void setElevationWeight(int v) { this.elevationWeight = v; }
    public int biomeWeight() { return biomeWeight; }
    public void setBiomeWeight(int v) { this.biomeWeight = v; }
    public int stabilityWeight() { return stabilityWeight; }
    public void setStabilityWeight(int v) { this.stabilityWeight = v; }
    public int waterDepthWeight() { return waterDepthWeight; }
    public void setWaterDepthWeight(int v) { this.waterDepthWeight = v; }
    public int nearWaterCost() { return nearWaterCost; }
    public void setNearWaterCost(int v) { this.nearWaterCost = v; }
    public double heuristicWeight() { return heuristicWeight; }
    public void setHeuristicWeight(double v) { this.heuristicWeight = v; }
    public double deviationWeight() { return deviationWeight; }
    public void setDeviationWeight(double v) { this.deviationWeight = v; }
    public int pathGridStep() { return pathGridStep; }
    public void setPathGridStep(int v) { this.pathGridStep = v; }
    /** 算法是固定能力，不再读取或保存旧算法选择键。 */
    public PathfindingAlgorithm pathfindingAlgorithm() { return PathfindingAlgorithm.BAND; }

    public double softGradeLimit() { return softGradeLimit; }
    public void setSoftGradeLimit(double v) { this.softGradeLimit = v; }
    public double hardGradeLimit() { return hardGradeLimit; }
    public void setHardGradeLimit(double v) { this.hardGradeLimit = v; }
    public double softGradePenalty() { return softGradePenalty; }
    public void setSoftGradePenalty(double v) { this.softGradePenalty = v; }
    public double hardGradePenalty() { return hardGradePenalty; }
    public void setHardGradePenalty(double v) { this.hardGradePenalty = v; }

    public double biomeCostWater() { return biomeCostWater; }
    public void setBiomeCostWater(double v) { this.biomeCostWater = v; }
    public double biomeCostOther() { return biomeCostOther; }
    public void setBiomeCostOther(double v) { this.biomeCostOther = v; }

    public double headingFlipCost() { return headingFlipCost; }
    public void setHeadingFlipCost(double v) { this.headingFlipCost = Math.max(0.0D, v); }
    public double headingCurveCost() { return headingCurveCost; }
    public void setHeadingCurveCost(double v) { this.headingCurveCost = Math.max(0.0D, v); }
    public double headingShortLegBlocks() { return headingShortLegBlocks; }
    public void setHeadingShortLegBlocks(double v) { this.headingShortLegBlocks = Math.max(1.0D, v); }
    public double headingShortLegMultiplier() { return headingShortLegMultiplier; }
    public void setHeadingShortLegMultiplier(double v) { this.headingShortLegMultiplier = Math.max(1.0D, v); }

    public double braidMinDirectionDot() { return braidMinDirectionDot; }
    public void setBraidMinDirectionDot(double v) { this.braidMinDirectionDot = v; }
    public double braidNearParallelDot() { return braidNearParallelDot; }
    public void setBraidNearParallelDot(double v) { this.braidNearParallelDot = v; }
    public boolean braidAllowParallelOverlap() { return braidAllowParallelOverlap; }
    public void setBraidAllowParallelOverlap(boolean v) { this.braidAllowParallelOverlap = v; }

    public boolean braidRerouteOnOverlap() { return braidRerouteOnOverlap; }
    public void setBraidRerouteOnOverlap(boolean v) { this.braidRerouteOnOverlap = v; }
    public int braidRerouteRadius() { return braidRerouteRadius; }
    public void setBraidRerouteRadius(int v) { this.braidRerouteRadius = v; }
    public double braidRerouteMaxLengthRatio() { return braidRerouteMaxLengthRatio; }
    public void setBraidRerouteMaxLengthRatio(double v) { this.braidRerouteMaxLengthRatio = v; }

    public net.shiroha233.roadweaver.pathfinding.impl.CorridorBan corridorBan() {

        return corridorBan != null ? corridorBan : net.shiroha233.roadweaver.pathfinding.impl.CorridorBan.current();
    }

    public void setCorridorBan(net.shiroha233.roadweaver.pathfinding.impl.CorridorBan v) {
        this.corridorBan = v;
    }

    public int corridorAttractRadius() { return corridorAttractRadius; }
    public void setCorridorAttractRadius(int v) { this.corridorAttractRadius = v; }

    public double corridorAttractFactor() { return corridorAttractFactor; }
    public void setCorridorAttractFactor(double v) { this.corridorAttractFactor = v; }

    public net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract corridorAttract() {

        return corridorAttract != null
                ? corridorAttract : net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract.current();
    }

    public void setCorridorAttract(net.shiroha233.roadweaver.pathfinding.impl.CorridorAttract v) {
        this.corridorAttract = v;
    }

    public int effectivePathGridStep() {
        if (pathGridStep < RoadConstants.PATH_GRID_STEP_MIN) return RoadConstants.PATH_GRID_STEP_MIN;
        if (pathGridStep > RoadConstants.PATH_GRID_STEP_MAX) return RoadConstants.PATH_GRID_STEP_MAX;
        return pathGridStep;
    }

    /** 临时精度仅改独立快照，避免改变全局配置。 */
    public PathfindingCostConfig withPathGridStep(int step) {
        PathfindingCostConfig copy = snapshot();
        copy.pathGridStep = step;
        copy.sanitize();
        return copy;
    }
}
