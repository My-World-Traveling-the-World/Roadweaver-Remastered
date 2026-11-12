package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;

/** Per-terrain overrides for the automatic terrain profile. */
public final class TerrainProfileConfig implements SubConfig {
    private int mountainSurfaceCutMaxBlocks = 3;
    private int mountainSurfaceFillMaxBlocks = 1;
    private int plainSurfaceCutMaxBlocks = 5;
    private int plainSurfaceFillMaxBlocks = 2;
    private double mountainDetourMultiplier = 1.25D;
    private double coastDetourMultiplier = 1.30D;
    private double riverDetourMultiplier = 1.15D;
    private double lakeDetourMultiplier = 1.30D;
    private double oceanDetourMultiplier = 1.10D;
    private int oceanBridgeMinDepth = 1;
    private double oceanUnderseaChance = 0.50D;

    @Override
    public void sanitize() {
        mountainSurfaceCutMaxBlocks = clampCut(mountainSurfaceCutMaxBlocks, 3);
        mountainSurfaceFillMaxBlocks = clampFill(mountainSurfaceFillMaxBlocks, 1);
        plainSurfaceCutMaxBlocks = clampCut(plainSurfaceCutMaxBlocks, 5);
        plainSurfaceFillMaxBlocks = clampFill(plainSurfaceFillMaxBlocks, 2);
        mountainDetourMultiplier = clampMultiplier(mountainDetourMultiplier, 1.25D);
        coastDetourMultiplier = clampMultiplier(coastDetourMultiplier, 1.30D);
        riverDetourMultiplier = clampMultiplier(riverDetourMultiplier, 1.15D);
        lakeDetourMultiplier = clampMultiplier(lakeDetourMultiplier, 1.30D);
        oceanDetourMultiplier = clampMultiplier(oceanDetourMultiplier, 1.10D);
        oceanBridgeMinDepth = Math.max(1, Math.min(10, oceanBridgeMinDepth));
        oceanUnderseaChance = Math.max(0.0D, Math.min(1.0D, oceanUnderseaChance));
    }

    @Override
    public TerrainProfileConfig snapshot() {
        TerrainProfileConfig copy = new TerrainProfileConfig();
        copy.mountainSurfaceCutMaxBlocks = mountainSurfaceCutMaxBlocks;
        copy.mountainSurfaceFillMaxBlocks = mountainSurfaceFillMaxBlocks;
        copy.plainSurfaceCutMaxBlocks = plainSurfaceCutMaxBlocks;
        copy.plainSurfaceFillMaxBlocks = plainSurfaceFillMaxBlocks;
        copy.mountainDetourMultiplier = mountainDetourMultiplier;
        copy.coastDetourMultiplier = coastDetourMultiplier;
        copy.riverDetourMultiplier = riverDetourMultiplier;
        copy.lakeDetourMultiplier = lakeDetourMultiplier;
        copy.oceanDetourMultiplier = oceanDetourMultiplier;
        copy.oceanBridgeMinDepth = oceanBridgeMinDepth;
        copy.oceanUnderseaChance = oceanUnderseaChance;
        return copy;
    }

    public int mountainSurfaceCutMaxBlocks() { return mountainSurfaceCutMaxBlocks; }
    public void setMountainSurfaceCutMaxBlocks(int v) { mountainSurfaceCutMaxBlocks = clampCut(v, 3); }
    public int mountainSurfaceFillMaxBlocks() { return mountainSurfaceFillMaxBlocks; }
    public void setMountainSurfaceFillMaxBlocks(int v) { mountainSurfaceFillMaxBlocks = clampFill(v, 1); }
    public int plainSurfaceCutMaxBlocks() { return plainSurfaceCutMaxBlocks; }
    public void setPlainSurfaceCutMaxBlocks(int v) { plainSurfaceCutMaxBlocks = clampCut(v, 5); }
    public int plainSurfaceFillMaxBlocks() { return plainSurfaceFillMaxBlocks; }
    public void setPlainSurfaceFillMaxBlocks(int v) { plainSurfaceFillMaxBlocks = clampFill(v, 2); }
    public double mountainDetourMultiplier() { return mountainDetourMultiplier; }
    public void setMountainDetourMultiplier(double v) { mountainDetourMultiplier = clampMultiplier(v, 1.25D); }
    public double coastDetourMultiplier() { return coastDetourMultiplier; }
    public void setCoastDetourMultiplier(double v) { coastDetourMultiplier = clampMultiplier(v, 1.30D); }
    public double riverDetourMultiplier() { return riverDetourMultiplier; }
    public void setRiverDetourMultiplier(double v) { riverDetourMultiplier = clampMultiplier(v, 1.15D); }
    public double lakeDetourMultiplier() { return lakeDetourMultiplier; }
    public void setLakeDetourMultiplier(double v) { lakeDetourMultiplier = clampMultiplier(v, 1.30D); }
    public double oceanDetourMultiplier() { return oceanDetourMultiplier; }
    public void setOceanDetourMultiplier(double v) { oceanDetourMultiplier = clampMultiplier(v, 1.10D); }
    public int oceanBridgeMinDepth() { return oceanBridgeMinDepth; }
    public void setOceanBridgeMinDepth(int v) { oceanBridgeMinDepth = Math.max(1, Math.min(10, v)); }
    public double oceanUnderseaChance() { return oceanUnderseaChance; }
    public void setOceanUnderseaChance(double v) {
        oceanUnderseaChance = Math.max(0.0D, Math.min(1.0D, v));
    }

    private static int clampCut(int value, int fallback) {
        if (value < 0 || value > 16) return fallback;
        return value;
    }

    private static int clampFill(int value, int fallback) {
        if (value < 0 || value > 16) return fallback;
        return value;
    }

    private static double clampMultiplier(double value, double fallback) {
        if (!Double.isFinite(value) || value < 0.25D || value > 4.0D) return fallback;
        return value;
    }
}
