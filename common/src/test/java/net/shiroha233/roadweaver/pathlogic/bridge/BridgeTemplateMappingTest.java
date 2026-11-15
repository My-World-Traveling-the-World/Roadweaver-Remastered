/* 文件职责：桥模板纵向映射 mapTemplateX 的契约测试 —— 含一个已修历史缺陷的守卫。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BridgeSegmentPlannerNew#mapTemplateX} 的契约测试。
 *
 * <h2>为什么补这个</h2>
 * <p>{@code BridgeSegmentPlannerNew} 是**生产主路径**（`PathFeature:515` 先调它，
 * 模板失败才走程序化兜底）上的 545 行新实现，2026-11-15 实测**整类零测试**。
 * 它吃 {@code WorldGenLevel} + {@code ServerLevel}，主体无法离线跑；
 * 但其中的坐标映射 {@code mapTemplateX} 是**纯数学、不碰世界**，
 * 而且是「模板沿桥长怎么铺」的唯一决定者 —— 桥面接不上岸、桥在岸边提前结束，
 * 都由这一步的边界行为决定。</p>
 *
 * <h2>★ 它守的是一个「已修但没守卫」的缺陷</h2>
 * <p>源码里这段注释记着一次修复：</p>
 * <pre>
 * // The endpoint at t=1 previously mapped to sizeX exactly, which is one
 * // column outside the template and made the bridge stop before the bank.
 * double maximum = Math.max(0.0D, totalLength - 1.0E-4D);
 * </pre>
 * <p>即：{@code t=1} 曾经恰好映射到 {@code totalLength}（模板最后一列之外一格），
 * 表现为「桥在岸边提前结束」。修法是把上界压到 {@code totalLength - 1e-4}。
 * <b>此前没有任何测试钉住这个上界</b> —— 谁把它改回 {@code totalLength}，
 * 这个缺陷就会静默复活。</p>
 */
class BridgeTemplateMappingTest {

    /** 便于读的默认参数：桥长 20、模板总长 40、首尾各 4 格封口、甲板每 4 格循环。 */
    private static final double BRIDGE = 20.0D;
    private static final int TOTAL = 40;
    private static final int START = 4;
    private static final int END = 4;
    private static final int DECK = 4;

    private static double map(double localX) {
        return BridgeSegmentPlannerNew.mapTemplateX(
                localX, BRIDGE, START, END, DECK, TOTAL);
    }

    // ══════════ 一、起点与首段：不参与平铺 ══════════

    @Test
    void 起点映射到模板第零列() {
        assertEquals(0.0D, map(0.0D), 1e-9D,
                "桥起点必须落在模板第 0 列");
    }

    @Test
    void 首段封口内原样映射() {
        // localX 在 [0, START) 内不平铺、不镜像
        assertEquals(1.0D, map(1.0D), 1e-9D, "首段封口内的坐标必须原样映射");
        assertEquals(3.0D, map(3.0D), 1e-9D, "首段封口内的坐标必须原样映射");
    }

    // ══════════ 二、中段：按甲板周期平铺 ══════════

    @Test
    void 中段按甲板周期循环() {
        // START=4, DECK=4 ⇒ 中段 4,5,6,7 循环回 4,5,6,7
        assertEquals(4.0D, map(4.0D), 1e-9D, "中段起点映射到模板第 START 列");
        assertEquals(5.0D, map(5.0D), 1e-9D);
        assertEquals(4.0D, map(8.0D), 1e-9D,
                "跨过一个甲板周期（8 = 4 + 4）必须回到第 START 列 —— "
                        + "若这里没回落，桥面会沿模板一路往后跑并在中途用光模板");
        assertEquals(6.0D, map(10.0D), 1e-9D, "10 = 4 + 6 ⇒ 模板第 6 列");
    }

    @Test
    void 甲板周期为零时不做平铺() {
        // deckLength = 0 属于异常参数（模板没有可循环段）⇒ 必须原样通过，
        // 而不是除零或死循环
        double v = BridgeSegmentPlannerNew.mapTemplateX(9.0D, BRIDGE, START, END, 0, TOTAL);
        assertEquals(9.0D, v, 1e-9D,
                "甲板周期 <= 0 时必须跳过平铺分支（原样映射），否则会除零");
    }

    // ══════════ 三、尾段：镜像到模板末尾 ══════════

    @Test
    void 尾段把剩余桥长镜像到模板末尾() {
        // repeatEnd = 20 - 4 = 16；localX >= 16 走镜像：mapped = TOTAL - (BRIDGE - localX)
        assertEquals(38.0D, map(18.0D), 1e-9D,
                "18 ⇒ 40 - (20-18) = 38 —— 尾段必须贴住模板末尾，"
                        + "否则桥台会用错模板的端部造型");
        assertEquals(39.0D, map(19.0D), 1e-9D);
    }

    // ══════════ 四、★ 守历史缺陷：上界必须是 totalLength - 1e-4，不是 totalLength ══════════

    @Test
    void 终点绝不能映射到模板总长这一列() {
        double v = map(BRIDGE);
        assertTrue(v < TOTAL,
                "终点映射值必须严格小于模板总长 " + TOTAL + "，实际 " + v
                        + "。★ 这正是那次修复要防的：映射到 totalLength 等于「模板外一列」，"
                        + "表现是桥在岸边提前结束");
        assertEquals(TOTAL - 1.0E-4D, v, 1e-9D,
                "上界必须精确压到 totalLength - 1e-4（源码里那个 maximum 常量）。"
                        + "若有人把它改回 totalLength，这条会红");
    }

    @Test
    void 超出桥长的输入也被夹在上界内() {
        double v = map(BRIDGE + 100.0D);
        assertTrue(v < TOTAL,
                "超出桥长 100 格的输入也必须被夹在模板范围内，实际 " + v
                        + "（否则模板访问会越界取到 null）");
    }

    @Test
    void 负输入被夹到零() {
        assertEquals(0.0D, map(-5.0D), 1e-9D,
                "负坐标必须夹到 0（否则模板访问负下标）");
    }

    // ══════════ 五、单调性与范围不变量 ══════════

    @Test
    void 全区间映射结果都落在模板范围内() {
        for (int i = -20; i <= (int) BRIDGE + 20; i++) {
            double localX = i * 0.5D;
            double v = map(localX);
            assertTrue(v >= 0.0D && v < TOTAL,
                    "localX=" + localX + " 映射到 " + v
                            + " 越出模板范围 [0," + TOTAL + ")");
        }
    }
}