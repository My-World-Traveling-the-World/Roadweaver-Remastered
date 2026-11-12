/* 文件职责：聚合并校验全局模组配置。 */
package net.shiroha233.roadweaver.config;

import net.shiroha233.roadweaver.config.sub.*;

import java.util.List;

/**
 * 模组配置聚合类，通过组合持有所有子配置实例
 */
public final class ModConfig {

    private StructurePredictionConfig structurePrediction = new StructurePredictionConfig();
    private PlanningConfig planning = new PlanningConfig();
    private TerrainProfileConfig terrainProfile = new TerrainProfileConfig();
    private PathfindingCostConfig pathfindingCost = new PathfindingCostConfig();
    private RoadAppearanceConfig roadAppearance = new RoadAppearanceConfig();
    private BridgeConfig bridge = new BridgeConfig();

    private PerformanceConfig performance = new PerformanceConfig();
    private RoadsideStructureConfig roadsideStructure = new RoadsideStructureConfig();
    private RoadsideVillageConfig roadsideVillage = new RoadsideVillageConfig();
    private ClientConfig client = new ClientConfig();

    /**
     * 0.8.345：返回一份**深拷贝快照**，供只读消费方使用。
     *
     * <p>背景：{@code ConfigService.get()} 返回的是**全局单例本身**。
     * 有些消费方（如 {@code RoadConstructionVolumePlanner.Context}）会对拿到的配置
     * 调 {@code sanitize()} —— 那会**在 worker 线程上改写全局配置**，
     * 并与菜单 setter 存在丢失更新窗口；且它位于道路最终校验的**热路径**上，
     * 每次都触发一次全量 sanitize（级联 10 个子配置，含 trim/toUpperCase 分配），
     * 加剧堆压力。</p>
     *
     * <p>需要"改一份自己的副本"的场景请改用本方法。</p>
     */
    public ModConfig snapshot() {
        ModConfig copy = new ModConfig();
        copy.structurePrediction = structurePrediction == null ? new StructurePredictionConfig() : structurePrediction.snapshot();
        copy.planning = planning == null ? new PlanningConfig() : planning.snapshot();
        copy.terrainProfile = terrainProfile == null ? new TerrainProfileConfig() : terrainProfile.snapshot();
        copy.pathfindingCost = pathfindingCost == null ? new PathfindingCostConfig() : pathfindingCost.snapshot();
        copy.roadAppearance = roadAppearance == null ? new RoadAppearanceConfig() : roadAppearance.snapshot();
        copy.bridge = bridge == null ? new BridgeConfig() : bridge.snapshot();
        copy.performance = performance == null ? new PerformanceConfig() : performance.snapshot();
        copy.roadsideStructure = roadsideStructure == null ? new RoadsideStructureConfig() : roadsideStructure.snapshot();
        copy.roadsideVillage = roadsideVillage == null ? new RoadsideVillageConfig() : roadsideVillage.snapshot();
        copy.client = client == null ? new ClientConfig() : client.snapshot();
        return copy;
    }

    public void sanitize() {
        if (structurePrediction == null) structurePrediction = new StructurePredictionConfig();        if (planning == null) planning = new PlanningConfig();
        if (terrainProfile == null) terrainProfile = new TerrainProfileConfig();
        if (pathfindingCost == null) pathfindingCost = new PathfindingCostConfig();
        if (roadAppearance == null) roadAppearance = new RoadAppearanceConfig();
        if (bridge == null) bridge = new BridgeConfig();
        if (performance == null) performance = new PerformanceConfig();
        if (roadsideStructure == null) roadsideStructure = new RoadsideStructureConfig();
        if (roadsideVillage == null) roadsideVillage = new RoadsideVillageConfig();
        if (client == null) client = new ClientConfig();

        structurePrediction.sanitize();
        planning.sanitize();
        terrainProfile.sanitize();
        pathfindingCost.sanitize();
        roadAppearance.sanitize();
        bridge.sanitize();
        performance.sanitize();
        roadsideStructure.sanitize();
        roadsideVillage.sanitize();
        client.sanitize();

    }

    public StructurePredictionConfig structurePrediction() { return structurePrediction; }
    public PlanningConfig planning() { return planning; }
    public TerrainProfileConfig terrainProfile() { return terrainProfile; }
    public PathfindingCostConfig pathfindingCost() { return pathfindingCost; }
    public RoadAppearanceConfig roadAppearance() { return roadAppearance; }
    public BridgeConfig bridge() { return bridge; }

    public PerformanceConfig performance() { return performance; }
    public RoadsideStructureConfig roadsideStructure() { return roadsideStructure; }
    public RoadsideVillageConfig roadsideVillage() { return roadsideVillage; }
    public ClientConfig client() { return client; }

    public boolean loadingTipsEnabled() {
        return client.loadingTipsEnabled();
    }

    public boolean loadingProgressEnabled() {
        return client.loadingProgressEnabled();
    }

    public boolean tunnelEnabled() {
        return roadAppearance.tunnelEnabled();
    }

    public int roadClearHeight() {
        return roadAppearance.roadClearHeight();
    }

    public int tunnelClearHeight() {
        return roadAppearance.tunnelClearHeight();
    }

    /** Keep a sealed tunnel dry: no water inside the bore, water in its shell replaced by lining. */
    public boolean tunnelDrySeal() {
        return roadAppearance.tunnelDrySeal();
    }

    /** Measure slopes against the real path length rather than the station index. */
    public boolean slopeByArcLength() {
        return roadAppearance.slopeByArcLength();
    }

    /** Cross a narrow watercourse with a culvert instead of a dam or a gap. */
    public boolean culvertEnabled() {
        return roadAppearance.culvertEnabled();
    }

    public int culvertMaxStations() {
        return roadAppearance.culvertMaxStations();
    }

    public int culvertMaxWaterDepth() {
        return roadAppearance.culvertMaxWaterDepth();
    }

    public int averagingRadius() {
        return roadAppearance.averagingRadius();
    }

    public boolean structurePredictionEnabled() {
        return structurePrediction.enabled();
    }

    public boolean structureAvoidanceEnabled() {
        return structurePrediction.structureAvoidanceEnabled();
    }

    public boolean bridgeEnabled() {
        return bridge.enabled();
    }

    public int bridgeMergeGap() {
        return bridge.mergeGap();
    }

    /** 0.9.029 / R-03：跨路并行桥共享半径（格），0 关闭。 */
    public int bridgeShareRadius() {
        return bridge.shareRadius();
    }

    public int bridgeMaxLengthBlocks() {
        return bridge.maxLengthBlocks();
    }

    public boolean bridgeUseBuoysInstead() {
        return bridge.useBuoysInstead();
    }

    public boolean bridgeUseBuoysWhenSkipped() {
        return bridge.useBuoysWhenSkipped();
    }

    public int buoyIntervalBlocks() {
        return bridge.buoyIntervalBlocks();
    }

    public int bridgeDeckClearance() {
        return bridge.deckClearance();
    }

    public int bridgePierInterval() {
        return bridge.pierInterval();
    }

    public int bridgePierWidth() {
        return bridge.pierWidth();
    }

    public int bridgePierMaxHeight() {
        return bridge.pierMaxHeight();
    }

    public int bridgeRampSegments() {
        return bridge.rampSegments();
    }

    public int bridgeMinWaterDepth() {
        return bridge.minWaterDepth();
    }

    public int maxSlopeStepPerTwoSegments() {
        return roadAppearance.maxSlopeStepPerTwoSegments();
    }

    public boolean slopeLimitEnabled() {
        return roadAppearance.slopeLimitEnabled();
    }

    public int lampInterval() {
        return roadAppearance.lampInterval();
    }

    public int predictRadiusChunks() {
        return structurePrediction.predictRadiusChunks();
    }

    public boolean biomePrefilter() {
        return structurePrediction.biomePrefilter();
    }

    public List<String> structureWhitelist() {
        return structurePrediction.structureWhitelist();
    }

    public void setStructureWhitelist(List<String> v) {
        structurePrediction.setStructureWhitelist(v);
    }

    public List<String> structureBlacklist() {
        return structurePrediction.structureBlacklist();
    }

    public void setStructureBlacklist(List<String> v) {
        structurePrediction.setStructureBlacklist(v);
    }

    public int villageRoadOffset() {
        return structurePrediction.villageRoadOffset();
    }

    public int otherStructureRoadOffset() {
        return structurePrediction.otherStructureRoadOffset();
    }

    public int aStarStep() {
        return pathfindingCost.aStarStep();
    }
}
