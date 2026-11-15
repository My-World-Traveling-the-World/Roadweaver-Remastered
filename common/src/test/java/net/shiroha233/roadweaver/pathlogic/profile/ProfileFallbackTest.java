/* 文件职责：钉住 DP 不可行时的回落解 —— 必须真的满足坡限与站钉，而不是"看起来差不多"。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纵断面回落求解器测试（2026-11-14）。
 *
 * <h2>为什么这个回落解必须有测试</h2>
 * <p>它是「删掉旧版 15 道 pass」（缺陷 C，用户拍板「旧的全部删除」）的<b>前置条件</b>：
 * DP 不可行时唯一的退路。退路一旦不合规，真机上就是台阶路或泡水桥。</p>
 *
 * @see ProfileFallback
 */
class ProfileFallbackTest {

    private static int[] flat(int n, int y) {
        int[] terrain = new int[n];
        Arrays.fill(terrain, y);
        return terrain;
    }

    @Test
    void 平地上与地形完全重合() {
        int[] terrain = flat(20, 64);
        int[] h = ProfileFallback.terrainFollowing(terrain, 5, 2, 2, 3, null, null, 0, null);

        assertArrayEquals(terrain, h, "平地上不该有任何挖填");
        assertEquals(0, ProfileFallback.violations(h, 2, 3), "不应违反坡度约束");
    }

    @Test
    void 陡坎被坡度约束压成斜坡() {
        // 0..9 在 y=64，10..19 在 y=80（16 格垂直断崖）
        int[] terrain = new int[20];
        for (int i = 0; i < 10; i++) terrain[i] = 64;
        for (int i = 10; i < 20; i++) terrain[i] = 80;

        int[] h = ProfileFallback.terrainFollowing(terrain, 20, 20, 2, 3, null, null, 0, null);

        assertEquals(0, ProfileFallback.violations(h, 2, 3),
                "陡坎必须被压成合规斜坡（一阶≤2、二阶≤3）");
        // 斜坡必然带来挖填：断崖下方被填、上方被挖
        assertTrue(ProfileFallback.earthwork(h, terrain) > 0, "陡坎必然产生土方");
    }

    @Test
    void 站钉必须被严格遵守() {
        int[] terrain = flat(10, 64);
        int[] pin = new int[10];
        Arrays.fill(pin, -1);
        pin[0] = 70;                      // 起点钉死 70
        pin[9] = 60;                      // 终点钉死 60

        int[] h = ProfileFallback.terrainFollowing(terrain, 30, 30, 3, 4, pin, null, 0, null);

        assertEquals(70, h[0], "起点站钉必须生效");
        assertEquals(60, h[9], "终点站钉必须生效");
        assertEquals(0, ProfileFallback.violations(h, 3, 4), "钉住两端后仍须满足坡度");
    }

    @Test
    void 隧道站必须被压到地表以下至少覆土深度() {
        int[] terrain = flat(10, 64);
        boolean[] mustCover = new boolean[10];
        mustCover[5] = true;

        int[] h = ProfileFallback.terrainFollowing(terrain, 30, 30, 4, 6, null, mustCover, 3, null);

        // ★ 2026-11-15 更正：本条原先断言 `h[5] - terrain[5] >= 3`，即要求路面**高于**地表 3 格
        // —— 那是把缺陷固化成契约。覆土（cover）的物理含义是「隧道顶上方要有这么多土」，
        // 所以隧道站必须**低于**地表至少 cover 格；抬到地表上方的是天桥不是隧道。
        // 外部独立审计实测：地表 80 / 覆土 4 时 DP 给 76、回落给 84（反了）。
        assertTrue(h[5] <= terrain[5] - 3,
                "隧道站必须低于地表至少 3 格（覆土在上方），实际 h-terrain="
                        + (h[5] - terrain[5]));
    }

    @Test
    void 隧道覆土与最大挖方冲突时让步的是挖方() {
        // 覆土是硬要求：即使 maxCut 很小，也必须挖到能覆土的深度（与 ProfileDpSource 同契约）
        int[] terrain = flat(10, 64);
        boolean[] mustCover = new boolean[10];
        mustCover[5] = true;

        int[] h = ProfileFallback.terrainFollowing(terrain, 2, 30, 4, 6, null, mustCover, 5, null);

        assertTrue(h[5] <= 59,
                "maxCut=2 但要求覆土 5 格 ⇒ 必须挖到 59 以下（让步的是挖方），实际 " + h[5]);
    }

    @Test
    void 隧道站绝不允许被抬到地表以上() {
        // 回归钉：「回落把覆土当上界」一旦被改回下界，这条必红
        int[] terrain = flat(8, 80);
        boolean[] mustCover = new boolean[8];
        for (int i = 2; i <= 5; i++) mustCover[i] = true;

        int[] h = ProfileFallback.terrainFollowing(terrain, 30, 30, 4, 6, null, mustCover, 4, null);

        for (int i = 2; i <= 5; i++) {
            assertTrue(h[i] < terrain[i],
                    "第 " + i + " 站是隧道，绝不许高于或等于地表（实际 " + h[i]
                            + " vs 地表 " + terrain[i] + "）—— 高于地表就是悬空天桥，不是隧道");
        }
    }

    @Test
    void 桥面下界必须生效() {
        int[] terrain = flat(10, 60);
        int[] deckFloor = new int[10];
        Arrays.fill(deckFloor, Integer.MIN_VALUE);
        deckFloor[4] = 66;                // 水面 63 + 净空 3

        int[] h = ProfileFallback.terrainFollowing(terrain, 30, 30, 4, 6, null, null, 0, deckFloor);

        assertTrue(h[4] >= 66, "桥面格必须不低于给定下界，实际 " + h[4]);
    }

    @Test
    void 结果具有幂等性_再跑一次不改变任何站() {
        int[] terrain = new int[30];
        for (int i = 0; i < 30; i++) terrain[i] = 60 + (int) Math.round(6 * Math.sin(i / 4.0));

        int[] first = ProfileFallback.terrainFollowing(terrain, 5, 3, 2, 3, null, null, 0, null);
        int[] second = ProfileFallback.terrainFollowing(first, 5, 3, 2, 3, null, null, 0, null);

        assertArrayEquals(first, second, "已合规的剖面再跑必须逐站不变（无振荡）");
    }

    @Test
    void 空剖面不崩() {
        assertArrayEquals(new int[0],
                ProfileFallback.terrainFollowing(new int[0], 5, 2, 2, 3, null, null, 0, null));
    }
}