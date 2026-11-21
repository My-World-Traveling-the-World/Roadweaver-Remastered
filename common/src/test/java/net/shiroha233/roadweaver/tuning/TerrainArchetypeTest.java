/* 文件职责：地形原型「坡度谱」自检——确保分段坡度代价的三档在语料里真的被触发。 */
package net.shiroha233.roadweaver.tuning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形原型的「坡度谱」自检。
 *
 * <p>背景：分段坡度代价分三档——缓坡 ≤2、中坡 3~5、陡坡 &gt;5（单位＝每步高度变化，
 * 步长=A* 步长 4 格，故三档对应坡度 ≤50% / 50%~125% / &gt;125%）。上一轮 12 个分段配置
 * 扫出完全相同的目标值，根因就是当时的合成地形坡度只有 8%~14%（每步 0~1 格），
 * <b>只有缓坡档被触发过</b>。
 *
 * <p>本测试把「三档都被可观比例触发」变成硬约束，防止以后改地形时又退化成单一缓坡。
 */
class TerrainArchetypeTest {

    /** A* 步长（与 PathfindingCostConfig 的 pathGridStep 生产取值一致）。 */
    private static final int STEP = 4;
    /** 采样步长（比 STEP 稀疏，够快又够代表性）。 */
    private static final int SAMPLE = 8;

    @Test
    void defaultConstructorIsGentleHills() {
        SyntheticTerrain a = new SyntheticTerrain(7L);
        SyntheticTerrain b = new SyntheticTerrain(7L, SyntheticTerrain.Archetype.GENTLE_HILLS);
        assertEquals(SyntheticTerrain.Archetype.GENTLE_HILLS, a.archetype());
        for (int z = 0; z <= 1024; z += 64) {
            for (int x = 0; x <= 1024; x += 64) {
                assertEquals(b.groundY(x, z), a.groundY(x, z), "x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void sameArchetypeAndSeedProduceIdenticalTerrain() {
        for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
            SyntheticTerrain t1 = new SyntheticTerrain(12345L, a);
            SyntheticTerrain t2 = new SyntheticTerrain(12345L, a);
            assertEquals(t1.groundY(480, 512), t2.groundY(480, 512), a.name());
            assertEquals(t1.profile().elevationMean(), t2.profile().elevationMean(), 1e-9, a.name());
        }
    }

    /** 平原原型必须显著缓于其余原型（作为「全缓坡」对照组）。 */
    @Test
    void plainsFlatStaysGentle() {
        for (long seed = 0; seed < 4; seed++) {
            double max = new SyntheticTerrain(seed, SyntheticTerrain.Archetype.PLAINS_FLAT)
                    .stepDeltaStats(STEP)[2];
            assertTrue(max <= 3.0, "plains should stay gentle but max step delta = " + max);
        }
    }

    /** 至少 6 种原型必须能打出「陡坡档」（每步 &gt;5 格），否则语料不足以区分分段参数。 */
    @Test
    void mostArchetypesReachTheCliffTier() {
        int reaching = 0;
        StringBuilder spectrum = new StringBuilder();
        for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
            double max = 0.0;
            for (long seed = 0; seed < 4; seed++) {
                max = Math.max(max, new SyntheticTerrain(seed, a).stepDeltaStats(STEP)[2]);
            }
            spectrum.append(a.name()).append("=").append(String.format(java.util.Locale.ROOT, "%.1f", max)).append(' ');
            if (max > 5.0) {
                reaching++;
            }
        }
        System.out.println("SLOPE-SPECTRUM (whole world, step=" + STEP + "): " + spectrum);
        assertTrue(reaching >= 6, "only " + reaching + " archetypes reach the cliff tier: " + spectrum);
    }

    /**
     * 全语料（10 原型 × 8 种子）的坡度谱必须同时覆盖三档，且中坡/陡坡各占可观比例。
     * 这是「调参有意义」的充要前提。
     */
    @Test
    void corpusCoversAllThreeGradeTiers() {
        long gentle = 0;
        long mid = 0;
        long cliff = 0;
        for (SyntheticTerrain.Archetype a : SyntheticTerrain.Archetype.values()) {
            for (long seed = 0; seed < 8; seed++) {
                SyntheticTerrain t = new SyntheticTerrain(seed, a);
                for (int z = 0; z <= SyntheticTerrain.WORLD_MAX_Z; z += SAMPLE) {
                    for (int x = 0; x + STEP <= SyntheticTerrain.WORLD_MAX_X; x += SAMPLE) {
                        int d = Math.abs(t.groundY(x + STEP, z) - t.groundY(x, z));
                        if (d <= 2) {
                            gentle++;
                        } else if (d <= 5) {
                            mid++;
                        } else {
                            cliff++;
                        }
                    }
                }
            }
        }
        long total = gentle + mid + cliff;
        assertTrue(total > 100_000, "corpus too small: " + total);
        assertTrue(mid > 0 && cliff > 0, "mid=" + mid + " cliff=" + cliff);
        double midShare = mid * 100.0 / total;
        double cliffShare = cliff * 100.0 / total;
        assertTrue(midShare > 1.0, "mid tier share too small: " + midShare + "%");
        assertTrue(cliffShare > 1.0, "cliff tier share too small: " + cliffShare + "%");
    }

    /** 含屏障的原型必须让主线（64,64）→（960,960）真的翻越屏障（沿途出现陡坡）。 */
    @Test
    void barrierArchetypesForceAClimbAlongTheMainLine() {
        SyntheticTerrain.Archetype[] barrierTypes = {
                SyntheticTerrain.Archetype.MOUNTAIN_PASS,
                SyntheticTerrain.Archetype.RIDGE_CROSS,
                SyntheticTerrain.Archetype.CLIFF_PLATEAU,
                SyntheticTerrain.Archetype.MESA_TERRACES,
                SyntheticTerrain.Archetype.CANYON,
                SyntheticTerrain.Archetype.VOLCANO};
        for (SyntheticTerrain.Archetype a : barrierTypes) {
            boolean climbed = false;
            int best = 0;
            for (long seed = 0; seed < 8 && !climbed; seed++) {
                SyntheticTerrain t = new SyntheticTerrain(seed, a);
                for (int s = 0; s <= 896; s += STEP) {
                    int x0 = 64 + s;
                    int z0 = 64 + s;
                    int d = Math.abs(t.groundY(x0 + STEP, z0) - t.groundY(x0, z0));
                    best = Math.max(best, d);
                    if (d > 5) {
                        climbed = true;
                        break;
                    }
                }
            }
            assertTrue(climbed, a.name() + " never forces a cliff-tier climb along the main line (max step delta="
                    + best + ")");
        }
    }
}
