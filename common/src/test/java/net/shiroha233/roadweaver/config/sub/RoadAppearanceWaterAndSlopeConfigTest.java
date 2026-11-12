package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.435：C1「干燥隧道」与 C2「坡度按真实弧长」两个开关的接线回归。
 *
 * <p>两个开关都会改世界生成结果，默认值本身就是契约：隧道默认必须是干的（用户选定），
 * 弧长坡度默认必须是关的（打开会让每一段坡重新计算，需要真机对比后才允许当默认）。
 */
final class RoadAppearanceWaterAndSlopeConfigTest {

    @Test
    void aSealedTunnelIsDryByDefault() {
        assertTrue(new RoadAppearanceConfig().tunnelDrySeal());
        assertTrue(new ModConfig().tunnelDrySeal());
    }

    @Test
    void arcLengthSlopesStayOffUntilAsked() {
        assertFalse(new RoadAppearanceConfig().slopeByArcLength());
        assertFalse(new ModConfig().slopeByArcLength());
    }

    @Test
    void aSnapshotCarriesBothSwitches() {
        RoadAppearanceConfig config = new RoadAppearanceConfig();
        config.setTunnelDrySeal(false);
        config.setSlopeByArcLength(true);

        RoadAppearanceConfig copy = config.snapshot();

        assertFalse(copy.tunnelDrySeal());
        assertTrue(copy.slopeByArcLength());
    }
}
