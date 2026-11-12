package net.shiroha233.roadweaver.postprocess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.019（O-02 / O-13）：DP 简化的两条契约。
 *
 * <p>O-02：密度下限不满足时**不再把容差对半砍**（那会让整个简化自我否决、等于零简化），
 * 改为「按最大偏离逐点放回」。放回的必须是**原始点本身**，结果必须是原始点列的
 * 子序列 —— 不移动、不新建任何坐标。</p>
 *
 * <p>O-13：容差跟步长走，{@code clamp(0.5×step, 2, 4)}。默认档 {@code step=8} 必须
 * 得到 4.0，与原常量完全相同 ⇒ 默认档逐位不变。</p>
 */
final class SimplifyDensityTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 22.5° 锯齿：容差 4 会把它压到极少点，必须靠放回点保住密度下限。 */
    private static List<BlockPos> sawtooth(int stations) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            int x = k * 8;
            int z = (k % 2 == 0) ? 0 : 7;
            points.add(new BlockPos(x, 64, z));
        }
        return points;
    }

    /** 小幅锯齿（横向只 ±1 格）：容差 4 会把几乎全部点删掉，正好压到密度下限。 */
    private static List<BlockPos> flatSawtooth(int stations) {
        List<BlockPos> points = new ArrayList<>(stations);
        for (int k = 0; k < stations; k++) {
            points.add(new BlockPos(k * 8, 64, (k % 2 == 0) ? 0 : 1));
        }
        return points;
    }

    @Test
    void densityFloorIsMetWithoutShrinkingTolerance() {
        List<BlockPos> input = flatSawtooth(200);
        List<BlockPos> simplified = PathPostProcessor.simplifyPath(input, 8);

        double totalLength = 0.0D;
        for (int i = 1; i < input.size(); i++) {
            totalLength += Math.hypot(input.get(i).getX() - input.get(i - 1).getX(),
                    input.get(i).getZ() - input.get(i - 1).getZ());
        }
        int minPoints = Math.max(2, (int) Math.ceil(totalLength / PathPostProcessor.SIMPLIFY_MIN_POINTS_PER_BLOCK));

        assertTrue(minPoints > 2, "本用例要求密度下限确实高于 2，否则压不到放回逻辑上");
        assertTrue(simplified.size() >= minPoints,
                "放回后必须达到密度下限 " + minPoints + "，实测 " + simplified.size());
        assertTrue(simplified.size() < input.size(), "同时必须真的删掉一些点，否则简化没生效");
    }

    /** 结果必须是原始点列的子序列，且首末点不变。 */
    @Test
    void resultIsASubsequenceOfTheOriginalPoints() {
        List<BlockPos> input = sawtooth(200);
        List<BlockPos> simplified = PathPostProcessor.simplifyPath(input, 8);

        Set<BlockPos> allowed = new HashSet<>(input);
        for (BlockPos point : simplified) {
            assertTrue(allowed.contains(point), "结果里出现了原始点列之外的坐标：" + point);
        }
        assertEquals(input.get(0), simplified.get(0), "起点必须保留");
        assertEquals(input.get(input.size() - 1), simplified.get(simplified.size() - 1), "终点必须保留");
        // 顺序必须保持单调 —— 子序列不能重排。
        int cursor = 0;
        for (BlockPos point : simplified) {
            int found = -1;
            for (int i = cursor; i < input.size(); i++) {
                if (input.get(i).equals(point)) {
                    found = i;
                    break;
                }
            }
            assertTrue(found >= 0, "结果点顺序被打乱：" + point);
            cursor = found + 1;
        }
    }

    /** 容差跟步长走；默认档必须与原常量完全一致。 */
    @Test
    void toleranceFollowsTheGridStep() {
        assertEquals(PathPostProcessor.SIMPLIFY_TOLERANCE, PathPostProcessor.toleranceForStep(8), 1e-9D,
                "默认档 step=8 必须得到 4.0，否则默认档的行为被改了");
        assertEquals(4.0D, PathPostProcessor.toleranceForStep(9), 1e-9D, "大于 8 的步长不得把容差放大");
        assertEquals(2.0D, PathPostProcessor.toleranceForStep(4), 1e-9D);
        assertEquals(2.0D, PathPostProcessor.toleranceForStep(2), 1e-9D, "最小容差不得低于 2");
        assertEquals(2.0D, PathPostProcessor.toleranceForStep(1), 1e-9D);
    }

    /** 直线点列：容差内应删到只剩首末，且不触发放回（短路径密度下限很低）。 */
    @Test
    void straightLineCollapsesToEndpoints() {
        List<BlockPos> input = new ArrayList<>();
        for (int k = 0; k <= 20; k++) {
            input.add(new BlockPos(k * 8, 64, 0));
        }
        List<BlockPos> simplified = PathPostProcessor.simplifyPath(input, 8);
        assertEquals(2, simplified.size(), "共线点应全部删掉");
        assertEquals(input.get(0), simplified.get(0));
        assertEquals(input.get(input.size() - 1), simplified.get(1));
    }
}
