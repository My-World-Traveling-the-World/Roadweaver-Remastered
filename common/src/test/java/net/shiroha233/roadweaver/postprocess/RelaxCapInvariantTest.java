package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.019（O-05）：{@code relax} 的位移上限必须是<b>严格不变量</b>。
 *
 * <p>0.9.018 及以前，上限是「先缩到 cap、再 {@code Math.round} 取整」—— 每个轴
 * 取整最多多走 0.5 格，于是取整后的真实位移可以超出 cap（分册实测普通点
 * 2.24 格 / 桥头 1.24 格，而 cap 分别是 2.0 / 1.0）。这让所有「位移 ≤ cap」的
 * 断言根本写不出来，也意味着桥头收紧到 1.0 的那条设置实际从未兑现。</p>
 *
 * <p>本测试是这条不变量的守门人：任何未来改动只要让取整后的位移超界，它就红。</p>
 */
final class RelaxCapInvariantTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一条 22.5° 左右的锯齿路：平滑会明显挪点，正好用来压上限。 */
    private static List<BlockPos> zigzag(int stations) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            int x = k * 8;
            int z = (k % 2 == 0) ? 0 : 7;
            points.add(new BlockPos(x, 64, z));
        }
        return points;
    }

    private static List<BlockPos> negativeCoords(int stations) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            int x = -k * 8;
            int z = (k % 2 == 0) ? 0 : -7;
            points.add(new BlockPos(x, 64, z));
        }
        return points;
    }

    private static void assertCapRespected(List<BlockPos> input, List<BlockPos> output, double cap) {
        assertEquals(input.size(), output.size(), "平滑不得改变站点数量");
        for (int i = 0; i < input.size(); i++) {
            double distance = Math.hypot(
                    output.get(i).getX() - input.get(i).getX(),
                    output.get(i).getZ() - input.get(i).getZ());
            assertTrue(distance <= cap + 1e-9D,
                    "第 " + i + " 站的位移 " + distance + " 超出了上限 " + cap);
        }
    }

    @Test
    void displacementNeverExceedsTheDefaultCap() {
        List<BlockPos> input = zigzag(41);
        List<BlockPos> output = PathPostProcessor.relax(input, null);
        assertCapRespected(input, output, PathPostProcessor.RELAX_MAX_DISPLACEMENT);
    }

    @Test
    void displacementNeverExceedsTheBridgeHeadCap() {
        List<BlockPos> input = zigzag(41);
        boolean[] bridgeMask = new boolean[input.size()];
        for (int i = 14; i <= 20; i++) {
            bridgeMask[i] = true;
        }
        List<BlockPos> output = PathPostProcessor.relax(input, bridgeMask);
        assertCapRespected(input, output, PathPostProcessor.RELAX_MAX_DISPLACEMENT);
        // 桥头分区上限按**实际 cap** 单独核：±2/±3 = 1.0、±4..±8 = 2.0、更远 = 3.0。
        // 这里原来打的是 RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT 常量，一旦引道带引入 2.0 档
        // 就会把 2.0 的位移误判成越界 —— 断言必须跟着真实分档走。
        for (int i = 0; i < input.size(); i++) {
            double cap = PathPostProcessor.bridgeHeadCap(bridgeMask, i);
            if (cap >= PathPostProcessor.RELAX_MAX_DISPLACEMENT) {
                continue;
            }
            double distance = Math.hypot(
                    output.get(i).getX() - input.get(i).getX(),
                    output.get(i).getZ() - input.get(i).getZ());
            assertTrue(distance <= cap + 1e-9D,
                    "桥头第 " + i + " 站的位移 " + distance + " 超出了该区间的上限 " + cap);
        }
        // V-4 甲：三档分界本身也要钉住（桥段从 14 起）。
        assertEquals(PathPostProcessor.RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT,
                PathPostProcessor.bridgeHeadCap(bridgeMask, 14 - 3), 1e-9D,
                "±3 站必须是 1.0 档");
        assertEquals(PathPostProcessor.RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT,
                PathPostProcessor.bridgeHeadCap(bridgeMask, 14 - 8), 1e-9D,
                "±8 站必须是 2.0 档");
        assertEquals(PathPostProcessor.RELAX_MAX_DISPLACEMENT,
                PathPostProcessor.bridgeHeadCap(bridgeMask, 14 - 9), 1e-9D,
                "±9 站必须回到全局上限");
    }

    /** 端点一个都不能动 —— 端点对齐是硬契约。 */
    @Test
    void endpointsAreNeverMoved() {
        List<BlockPos> input = zigzag(25);
        List<BlockPos> output = PathPostProcessor.relax(input, null);
        assertEquals(input.get(0), output.get(0));
        assertEquals(input.get(input.size() - 1), output.get(output.size() - 1));
    }

    /** 负坐标不得因为向零截断而整体偏一格（O-05 的第二处修复）。 */
    @Test
    void negativeCoordinatesAreTreatedSymmetrically() {
        List<BlockPos> positive = zigzag(41);
        List<BlockPos> negative = negativeCoords(41);
        List<BlockPos> relaxedPositive = PathPostProcessor.relax(positive, null);
        List<BlockPos> relaxedNegative = PathPostProcessor.relax(negative, null);
        for (int i = 0; i < positive.size(); i++) {
            int positiveDeltaX = relaxedPositive.get(i).getX() - positive.get(i).getX();
            int negativeDeltaX = relaxedNegative.get(i).getX() - negative.get(i).getX();
            // 镜像点列在镜像平滑下必须得到镜像位移：正坐标偏 +1 时负坐标应偏 -1。
            assertTrue(Math.abs(positiveDeltaX + negativeDeltaX) <= 1,
                    "第 " + i + " 站的 x 位移在正负坐标下不对称：+"
                            + positiveDeltaX + " vs " + negativeDeltaX);
        }
    }
}
