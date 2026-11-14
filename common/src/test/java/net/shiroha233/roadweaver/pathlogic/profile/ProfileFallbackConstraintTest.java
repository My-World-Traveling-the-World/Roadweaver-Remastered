/* 文件职责：钉住 DP 回落的两条路径参数一致 —— 异常时不许丢掉端点钉与桥面下界。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回落参数一致性测试（2026-11-15，来自独立复核）。
 *
 * <h2>它防的是什么</h2>
 * <p>DP 有两条失败路径：<b>不可行</b>与<b>抛异常</b>。两者都调用
 * {@link ProfileFallback#terrainFollowing}，但修复前参数并不一致：</p>
 *
 * <pre>
 * 不可行：terrainFollowing(..., pin, tunnelMask, minCover, deckFloor)   ← 全部硬约束
 * 异常  ：terrainFollowing(..., null, tunnelMask, minCover, null)       ← 丢了两条
 * </pre>
 *
 * <p>丢的两条恰好是本方法最重要的保证：</p>
 * <ul>
 *   <li><b>pin</b>：村庄 / 出生点 / 命名结构端点必须落在实测地表；</li>
 *   <li><b>deckFloor</b>：桥面不得低于「最高水位 + 净空」。</li>
 * </ul>
 *
 * <p>而且触发面是真实的：{@code deckFloor} 由
 * {@code RoadConstructionSafety.bridgeDeckSurfaceY} 采样世界得出，
 * 它自己就会抛 —— 一抛就把自己从约束里抛没了。</p>
 *
 * <h2>为什么这里测 fallback 而不是测 {@code TargetYComputation}</h2>
 * <p>{@code computeTargetY} 需要 {@code ServerLevel}，无法单测。
 * 但真正要保证的不变量在 fallback 这一层是可验证的：
 * <b>给了 pin 与 deckFloor，回落解就必须遵守它们</b>。
 * 调用方是否把参数传对，由代码结构（在 try 外构造）保证。</p>
 */
class ProfileFallbackConstraintTest {

    private static int[] flat(int n, int y) {
        int[] a = new int[n];
        Arrays.fill(a, y);
        return a;
    }

    @Test
    void 回落解必须遵守传入的端点钉() {
        int[] terrain = flat(21, 64);
        int[] pin = new int[21];
        Arrays.fill(pin, -1);
        pin[0] = 70;      // 起点结构端点实测地表 70
        pin[20] = 58;     // 终点 58

        int[] out = ProfileFallback.terrainFollowing(
                terrain, 16, 16, 4, 6, pin, null, 1, null);

        assertEquals(70, out[0], "起点钉必须精确生效");
        assertEquals(58, out[20], "终点钉必须精确生效");
    }

    @Test
    void 回落解必须遵守传入的桥面下界() {
        int[] terrain = flat(21, 60);   // 地面 60
        int[] deckFloor = new int[21];
        Arrays.fill(deckFloor, Integer.MIN_VALUE);
        for (int i = 8; i <= 12; i++) deckFloor[i] = 66;   // 桥段：水面 63 + 净空 3

        int[] out = ProfileFallback.terrainFollowing(
                terrain, 16, 16, 4, 6, null, null, 1, deckFloor);

        for (int i = 8; i <= 12; i++) {
            assertTrue(out[i] >= 66,
                    "桥段第 " + i + " 站高度 " + out[i] + " 低于桥面下界 66 —— 桥会泡在水里");
        }
    }

    @Test
    void 同时传pin与桥面下界时两条契约都不被牺牲() {
        int[] terrain = flat(31, 62);
        int[] pin = new int[31];
        Arrays.fill(pin, -1);
        pin[0] = 72;
        pin[30] = 72;
        int[] deckFloor = new int[31];
        Arrays.fill(deckFloor, Integer.MIN_VALUE);
        for (int i = 12; i <= 18; i++) deckFloor[i] = 68;

        int[] out = ProfileFallback.terrainFollowing(
                terrain, 16, 16, 4, 8, pin, null, 1, deckFloor);

        assertEquals(72, out[0], "端点钉优先，不许被桥面下界的过渡带吃掉");
        assertEquals(72, out[30], "另一端点同理");
        for (int i = 12; i <= 18; i++) {
            assertTrue(out[i] >= 68, "桥面下界必须同时成立，第 " + i + " 站=" + out[i]);
        }
        // 整条剖面仍须满足一阶坡度
        for (int i = 1; i < out.length; i++) {
            assertTrue(Math.abs(out[i] - out[i - 1]) <= 4,
                    "第 " + i + " 站坡度超限：" + out[i - 1] + "->" + out[i]);
        }
    }

    @Test
    void null约束时行为与无约束一致() {
        int[] terrain = new int[20];
        for (int i = 0; i < 20; i++) terrain[i] = 60 + i % 3;

        int[] withNulls = ProfileFallback.terrainFollowing(
                terrain, 16, 16, 4, 6, null, null, 1, null);
        int[] plain = ProfileFallback.terrainFollowing(
                terrain, 16, 16, 4, 6, null, null, 1, null);

        assertArrayEqualsMsg(plain, withNulls);
    }

    private static void assertArrayEqualsMsg(int[] expected, int[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual),
                "null 约束不应引入任何额外修正");
    }
}