/* 文件职责：道路外观配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 道路外观配置
 */
public final class RoadAppearanceConfig implements SubConfig {
    private Boolean roadsEnabled = true;
    private boolean allowArtificial = true;
    private boolean allowNatural = true;
    private boolean placeWaypoints = false;
    private boolean spawnCabinEnabled = true;
    private int averagingRadius = RoadConstants.DEFAULT_AVERAGING_RADIUS;
    private int roadWidth = RoadConstants.DEFAULT_ROAD_WIDTH;
    private boolean roadSignsEnabled = true;
    private int lampInterval = RoadConstants.DEFAULT_LAMP_INTERVAL;
    private int roadClearHeight = RoadConstants.DEFAULT_ROAD_CLEAR_HEIGHT;
    private boolean tunnelEnabled = false;
    private int tunnelClearHeight = RoadConstants.DEFAULT_TUNNEL_CLEAR_HEIGHT;
    private boolean preventTreesOnRoad = true;
    private int maxSlopeStepPerTwoSegments = RoadConstants.DEFAULT_MAX_SLOPE_STEP;
    private boolean slopeLimitEnabled = true;
    /** 密封隧道清除洞内水并封闭壳层水，避免挖空后流体重新灌入。 */
    private boolean tunnelDrySeal = true;
    /** 窄水道优先涵洞，保持通水而不把水道填成堤坝。 */
    private boolean culvertEnabled = true;
    /** 超过该站数的水道交给桥梁处理，避免涵洞承担长跨越。 */
    private int culvertMaxStations = 6;
    /** 深水需要独立桥梁支撑，不能用浅水涵洞替代。 */
    private int culvertMaxWaterDepth = 2;
    /** 按水平里程而非站号计坡；开关变化会影响重新规划的纵断面，不主动重建所有既有道路。 */
    private boolean slopeByArcLength = false;
    /**
     * 0.9.005：净空清理遇到「受保护方块」时是否**强行清掉**（默认 true = 保底）。
     *
     * <p>旧行为：桥面列上方只要有受保护方块（基岩、方块实体、FEATURES_CANNOT_REPLACE、
     * 巨大树预留区、结构盒），整段桥就 `return false` 拒建，结果是山体原样留下、
     * 路被地形截断（真机日志 `bridge segment construction failed` 180 次）。
     * 打开后改为：路面正上方那 N 格仍然强清（打 WARN 带方块 id），**不清侧向**，
     * 换「桥一定建得起来」。设 false 即完全回到旧的「拒建」行为。</p>
     */
    private boolean forcedClearProtected = true;


    /** 陆地覆土充分时允许隧道候选，不被水跨越的桥判据一并否决。 */
    private boolean landTunnelEnabled = true;
    private boolean bumpCutEnabled = true;
    /** 单列最多削掉多少格（默认 5）；≥6 格一律不削，交给「陆上隧道」判定（见 landTunnelEnabled）。 */
    private int bumpCutMaxBlocks = 5;
    /** 局部「高列」占比超过此值就整段不削（避免把一段山路削成壕沟）。 */
    private double bumpCutMaxColumnRatio = 0.5D;

    @Override
    public void sanitize() {
        if (roadsEnabled == null) roadsEnabled = true;
        averagingRadius = Math.max(RoadConstants.AVERAGING_RADIUS_MIN,
                Math.min(RoadConstants.AVERAGING_RADIUS_MAX, averagingRadius));
        // Odd only. The footprint is a capsule around the centre line - every cell within half the width -
        // so an even width cannot be represented: 4 gives halfWidth 2.0 and the band then covers cells at
        // -2..+2, five of them. Rounding up to odd makes the configured width the width the player gets.
        roadWidth = Math.max(0, Math.min(RoadConstants.ROAD_WIDTH_MAX, roadWidth));
        if (roadWidth > 0 && (roadWidth & 1) == 0) {
            roadWidth = roadWidth + 1 <= RoadConstants.ROAD_WIDTH_MAX ? roadWidth + 1 : roadWidth - 1;
        }
        lampInterval = Math.max(RoadConstants.LAMP_INTERVAL_MIN, Math.min(RoadConstants.LAMP_INTERVAL_MAX, lampInterval));
        roadClearHeight = Math.max(RoadConstants.ROAD_CLEAR_HEIGHT_MIN, Math.min(RoadConstants.ROAD_CLEAR_HEIGHT_MAX, roadClearHeight));
        tunnelClearHeight = Math.max(RoadConstants.TUNNEL_CLEAR_HEIGHT_MIN, Math.min(RoadConstants.TUNNEL_CLEAR_HEIGHT_MAX, tunnelClearHeight));
        bumpCutMaxBlocks = Math.max(0, Math.min(24, bumpCutMaxBlocks));
        bumpCutMaxColumnRatio = Math.max(0.0D, Math.min(1.0D, bumpCutMaxColumnRatio));
        maxSlopeStepPerTwoSegments = Math.max(0, Math.min(RoadConstants.MAX_SLOPE_STEP_MAX, maxSlopeStepPerTwoSegments));
    }

    @Override
    public RoadAppearanceConfig snapshot() {
        RoadAppearanceConfig copy = new RoadAppearanceConfig();
        copy.roadsEnabled = this.roadsEnabled;
        copy.allowArtificial = this.allowArtificial;
        copy.allowNatural = this.allowNatural;
        copy.placeWaypoints = this.placeWaypoints;
        copy.spawnCabinEnabled = this.spawnCabinEnabled;
        copy.averagingRadius = this.averagingRadius;
        copy.roadWidth = this.roadWidth;
        copy.roadSignsEnabled = this.roadSignsEnabled;
        copy.lampInterval = this.lampInterval;
        copy.roadClearHeight = this.roadClearHeight;
        copy.tunnelEnabled = this.tunnelEnabled;
        copy.tunnelClearHeight = this.tunnelClearHeight;
        copy.preventTreesOnRoad = this.preventTreesOnRoad;
        copy.maxSlopeStepPerTwoSegments = this.maxSlopeStepPerTwoSegments;
        copy.slopeLimitEnabled = this.slopeLimitEnabled;
        copy.tunnelDrySeal = this.tunnelDrySeal;
        copy.culvertEnabled = this.culvertEnabled;
        copy.culvertMaxStations = this.culvertMaxStations;
        copy.culvertMaxWaterDepth = this.culvertMaxWaterDepth;
        copy.slopeByArcLength = this.slopeByArcLength;
        copy.forcedClearProtected = this.forcedClearProtected;
        copy.landTunnelEnabled = this.landTunnelEnabled;
        copy.bumpCutEnabled = this.bumpCutEnabled;
        copy.bumpCutMaxBlocks = this.bumpCutMaxBlocks;
        copy.bumpCutMaxColumnRatio = this.bumpCutMaxColumnRatio;
        return copy;
    }

    public boolean roadsEnabled() { return roadsEnabled == null || roadsEnabled; }
    public void setRoadsEnabled(boolean v) { this.roadsEnabled = v; }
    public boolean allowArtificial() { return allowArtificial; }
    public void setAllowArtificial(boolean v) { this.allowArtificial = v; }
    public boolean allowNatural() { return allowNatural; }
    public void setAllowNatural(boolean v) { this.allowNatural = v; }
    public boolean placeWaypoints() { return placeWaypoints; }
    public void setPlaceWaypoints(boolean v) { this.placeWaypoints = v; }
    public boolean spawnCabinEnabled() { return spawnCabinEnabled; }
    public void setSpawnCabinEnabled(boolean v) { this.spawnCabinEnabled = v; }
    public int averagingRadius() { return averagingRadius; }
    public void setAveragingRadius(int v) { this.averagingRadius = Math.max(RoadConstants.AVERAGING_RADIUS_MIN,
            Math.min(RoadConstants.AVERAGING_RADIUS_MAX, v)); }
    public int roadWidth() { return roadWidth; }
    public void setRoadWidth(int v) { this.roadWidth = v; }
    public boolean roadSignsEnabled() { return roadSignsEnabled; }
    public void setRoadSignsEnabled(boolean v) { this.roadSignsEnabled = v; }
    public int lampInterval() { return lampInterval; }
    public void setLampInterval(int v) { this.lampInterval = v; }
    public int roadClearHeight() { return roadClearHeight; }
    public void setRoadClearHeight(int v) { this.roadClearHeight = v; }
    public boolean tunnelEnabled() { return tunnelEnabled; }
    public void setTunnelEnabled(boolean v) { this.tunnelEnabled = v; }
    public int tunnelClearHeight() { return tunnelClearHeight; }
    public void setTunnelClearHeight(int v) { this.tunnelClearHeight = v; }
    public boolean preventTreesOnRoad() { return preventTreesOnRoad; }
    public void setPreventTreesOnRoad(boolean v) { this.preventTreesOnRoad = v; }
    public int maxSlopeStepPerTwoSegments() { return maxSlopeStepPerTwoSegments; }
    public void setMaxSlopeStepPerTwoSegments(int v) { this.maxSlopeStepPerTwoSegments = v; }
    public boolean slopeLimitEnabled() { return slopeLimitEnabled; }
    public void setSlopeLimitEnabled(boolean v) { this.slopeLimitEnabled = v; }
    public boolean tunnelDrySeal() { return tunnelDrySeal; }
    public void setTunnelDrySeal(boolean v) { this.tunnelDrySeal = v; }
    public boolean culvertEnabled() { return culvertEnabled; }
    public void setCulvertEnabled(boolean v) { this.culvertEnabled = v; }
    public int culvertMaxStations() { return culvertMaxStations; }
    public void setCulvertMaxStations(int v) { this.culvertMaxStations = Math.max(1, Math.min(16, v)); }
    public int culvertMaxWaterDepth() { return culvertMaxWaterDepth; }
    public void setCulvertMaxWaterDepth(int v) { this.culvertMaxWaterDepth = Math.max(1, Math.min(4, v)); }
    public boolean slopeByArcLength() { return slopeByArcLength; }
    public void setSlopeByArcLength(boolean v) { this.slopeByArcLength = v; }
    /** 净空清理是否强行清掉受保护方块（保底档）。见字段注释。 */
    public boolean forcedClearProtected() { return forcedClearProtected; }
    public void setForcedClearProtected(boolean v) { this.forcedClearProtected = v; }

    /** 控制陆地隧道候选入口，与水下隧道转换分开。 */
    public boolean landTunnelEnabled() { return landTunnelEnabled; }
    public void setLandTunnelEnabled(boolean v) { this.landTunnelEnabled = v; }
    public boolean bumpCutEnabled() { return bumpCutEnabled; }
    public void setBumpCutEnabled(boolean v) { this.bumpCutEnabled = v; }
    public int bumpCutMaxBlocks() { return bumpCutMaxBlocks; }
    public void setBumpCutMaxBlocks(int v) { this.bumpCutMaxBlocks = Math.max(0, Math.min(24, v)); }
    public double bumpCutMaxColumnRatio() { return bumpCutMaxColumnRatio; }
    public void setBumpCutMaxColumnRatio(double v) { this.bumpCutMaxColumnRatio = Math.max(0.0D, Math.min(1.0D, v)); }
}
