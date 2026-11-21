/* 文件职责：桥梁配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 桥梁配置
 */
public final class BridgeConfig implements SubConfig {
    private boolean enabled = true;
    // 模板桥灯按跨度里程布置，与陆路按站计数的间隔分开；0显式关闭新增灯。
    private int lightIntervalBlocks = RoadConstants.DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS;
    private int deckClearance = RoadConstants.DEFAULT_BRIDGE_DECK_CLEARANCE;
    private int maxLengthBlocks = RoadConstants.DEFAULT_BRIDGE_MAX_LENGTH_BLOCKS;
    private boolean useBuoysInstead = false;
    private boolean useBuoysWhenSkipped = false;
    private int buoyIntervalBlocks = RoadConstants.DEFAULT_BUOY_INTERVAL_BLOCKS;
    private int pierInterval = RoadConstants.DEFAULT_BRIDGE_PIER_INTERVAL;
    private int pierWidth = RoadConstants.DEFAULT_BRIDGE_PIER_WIDTH;
    private int pierMaxHeight = RoadConstants.DEFAULT_BRIDGE_PIER_MAX_HEIGHT;
    private int rampSegments = RoadConstants.DEFAULT_BRIDGE_RAMP_SEGMENTS;
    private int minWaterDepth = RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH;
    private int mergeGap = RoadConstants.DEFAULT_BRIDGE_MERGE_GAP;
    /** 0.9.029 / R-03：跨路并行桥共享半径（格）。0 = 关闭；默认 24。 */
    private int shareRadius = 24;
    /**
     * 0.9.005：桥面**上方**保底强行净空的高度（格）。0 = 跟随隧道净空
     * （{@code roadAppearance.tunnelClearHeight}，默认 5）。范围 0~16。
     *
     * <p>只清 [桥面, 桥面 + 高度) 这一竖向带，**绝不向下挖**：桥面以下的路基、河床、
     * 桥墩支撑都不动，所以不会把地形挖成峡谷。</p>
     */
    private int clearHeight = 0;
    /** 0.9.005：桥面两侧各清几格（横向），默认 2，范围 0~4。同样只清上方净空带，不下挖。 */
    private int sideClear = 2;

    @Override
    public void sanitize() {
        lightIntervalBlocks = Math.max(0, Math.min(2048, lightIntervalBlocks));
        deckClearance = Math.max(RoadConstants.BRIDGE_DECK_CLEARANCE_MIN, Math.min(RoadConstants.BRIDGE_DECK_CLEARANCE_MAX, deckClearance));
        maxLengthBlocks = Math.max(0, Math.min(RoadConstants.BRIDGE_MAX_LENGTH_MAX, maxLengthBlocks));
        buoyIntervalBlocks = Math.max(RoadConstants.BUOY_INTERVAL_MIN, Math.min(RoadConstants.BUOY_INTERVAL_MAX, buoyIntervalBlocks));
        pierInterval = Math.max(RoadConstants.BRIDGE_PIER_INTERVAL_MIN, Math.min(RoadConstants.BRIDGE_PIER_INTERVAL_MAX, pierInterval));
        pierWidth = Math.max(RoadConstants.BRIDGE_PIER_WIDTH_MIN, Math.min(RoadConstants.BRIDGE_PIER_WIDTH_MAX, pierWidth));
        pierMaxHeight = Math.max(RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MIN, Math.min(RoadConstants.BRIDGE_PIER_MAX_HEIGHT_MAX, pierMaxHeight));
        rampSegments = Math.max(0, Math.min(RoadConstants.BRIDGE_RAMP_SEGMENTS_MAX, rampSegments));
        minWaterDepth = Math.max(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MIN,
                Math.min(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MAX, minWaterDepth));
        mergeGap = Math.max(RoadConstants.BRIDGE_MERGE_GAP_MIN,
                Math.min(RoadConstants.BRIDGE_MERGE_GAP_MAX, mergeGap));
        shareRadius = Math.max(0, Math.min(96, shareRadius));
        clearHeight = Math.max(0, Math.min(16, clearHeight));
        sideClear = Math.max(0, Math.min(4, sideClear));
    }

    @Override
    public BridgeConfig snapshot() {
        BridgeConfig copy = new BridgeConfig();
        copy.enabled = this.enabled;
        copy.lightIntervalBlocks = this.lightIntervalBlocks;
        copy.deckClearance = this.deckClearance;
        copy.maxLengthBlocks = this.maxLengthBlocks;
        copy.useBuoysInstead = this.useBuoysInstead;
        copy.useBuoysWhenSkipped = this.useBuoysWhenSkipped;
        copy.buoyIntervalBlocks = this.buoyIntervalBlocks;
        copy.pierInterval = this.pierInterval;
        copy.pierWidth = this.pierWidth;
        copy.pierMaxHeight = this.pierMaxHeight;
        copy.rampSegments = this.rampSegments;
        copy.minWaterDepth = this.minWaterDepth;
        copy.mergeGap = this.mergeGap;
        copy.shareRadius = this.shareRadius;
        copy.clearHeight = this.clearHeight;
        copy.sideClear = this.sideClear;
        return copy;
    }

    public int lightIntervalBlocks() { return lightIntervalBlocks; }
    public void setLightIntervalBlocks(int v) { lightIntervalBlocks = v; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int deckClearance() { return deckClearance; }
    public void setDeckClearance(int v) { this.deckClearance = v; }
    public int maxLengthBlocks() { return maxLengthBlocks; }
    public void setMaxLengthBlocks(int v) { this.maxLengthBlocks = v; }
    public boolean useBuoysInstead() { return useBuoysInstead; }
    public void setUseBuoysInstead(boolean v) { this.useBuoysInstead = v; }
    public boolean useBuoysWhenSkipped() { return useBuoysWhenSkipped; }
    public void setUseBuoysWhenSkipped(boolean v) { this.useBuoysWhenSkipped = v; }
    public int buoyIntervalBlocks() { return buoyIntervalBlocks; }
    public void setBuoyIntervalBlocks(int v) { this.buoyIntervalBlocks = v; }
    public int pierInterval() { return pierInterval; }
    public void setPierInterval(int v) { this.pierInterval = v; }
    public int pierWidth() { return pierWidth; }
    public void setPierWidth(int v) { this.pierWidth = v; }
    public int pierMaxHeight() { return pierMaxHeight; }
    public void setPierMaxHeight(int v) { this.pierMaxHeight = v; }
    /** 桥面净空高度（格）；0 = 跟随 {@code roadAppearance.tunnelClearHeight}。见字段注释。 */
    public int clearHeight() { return clearHeight; }
    public void setClearHeight(int v) { this.clearHeight = v; }
    /** 桥面两侧各清几格。见字段注释。 */
    public int sideClear() { return sideClear; }
    public void setSideClear(int v) { this.sideClear = v; }
    public int rampSegments() { return rampSegments; }
    public void setRampSegments(int v) { this.rampSegments = v; }
    public int minWaterDepth() { return minWaterDepth; }
    public void setMinWaterDepth(int v) { this.minWaterDepth = Math.max(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MIN,
            Math.min(RoadConstants.BRIDGE_MIN_WATER_DEPTH_MAX, v)); }
    public int mergeGap() { return mergeGap; }
    /** 0.9.029 / R-03：跨路并行桥共享半径（格），0 关闭。 */
    public int shareRadius() { return shareRadius; }
    public void setShareRadius(int v) { this.shareRadius = Math.max(0, Math.min(96, v)); }
    public void setMergeGap(int v) { this.mergeGap = Math.max(RoadConstants.BRIDGE_MERGE_GAP_MIN,
            Math.min(RoadConstants.BRIDGE_MERGE_GAP_MAX, v)); }
}
