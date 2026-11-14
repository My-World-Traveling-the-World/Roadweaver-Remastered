/* 文件职责：钉住「横截面压平必须用中心列，不能用中位数」这条口径。 */
package net.shiroha233.roadweaver.pathlogic.grade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 横截面压平口径测试（2026-11-15）。
 *
 * <h2>背景：体积规划与施工曾经用两套压平口径</h2>
 * <ul>
 *   <li><b>施工侧</b>（`SegmentPaver`）：`resolveRoadCrossSection(heights, terrain,
 *       nearestColumnIndex(positions, seg.middlePos()))` —— <b>用中心线最近列</b>；</li>
 *   <li><b>体积规划</b>（`RoadConstructionVolumePlanner`）：`representativeHeight(heights)`
 *       —— <b>用中位数</b>。</li>
 * </ul>
 *
 * <p>后果是「校验按中位数算高度、施工按中心列铺」，两边 `minY/maxY` 可能差 1~2 格。
 * 2026-11-15 把规划侧改成与施工侧同调。</p>
 *
 * <h2>为什么中位数是错的</h2>
 * <p>`resolveRoadCrossSection` 的 javadoc 已经写明：横截面各列**投影到中心线的位置并不相同**，
 * 取中位数会让相邻站在斜坡上轮流高一格，路面变成 `0,1,0,1` 阶梯而不是坡道。
 * 中心列才是计划坡度真正算出来的那一列。</p>
 *
 * <h2>这组测试守什么</h2>
 * <ol>
 *   <li><b>必要性</b>：非均匀横截面上，中心列口径与中位数口径**确实不同** ——
 *       若哪天有人把规划侧改回中位数，这里能说清「为什么不行」。</li>
 *   <li><b>回退语义</b>：中心列取不到（`centreIndex < 0`）时必须退回中位数，
 *       不能抛异常、也不能保持各列原样。</li>
 *   <li><b>最近列选择</b>：`nearestColumnIndex` 在对称布局下选中间、
 *       在偏置布局下选真正最近的那一列。</li>
 * </ol>
 *
 * <h2>局限（如实说明）</h2>
 * <p>本组测试**不能**证明「体积规划确实调用了中心列口径」—— 那需要构造
 * 能让 `batchInterpolate` 产生非均匀输出的非平凡中心线几何，成本高于收益。
 * 调用关系由代码结构保证（两处都调同一个 `resolveRoadCrossSection`），
 * 本测试负责证明「这个选择在数值上确实有区别、不是空转」。</p>
 */
class CrossSectionFlattenTest {

    @Test
    void 非均匀横截面上中心列口径与中位数口径必须不同() {
        // 中间高、两侧低：中位数取 64，中心列（index 1）是 66
        int[] heights = {64, 66, 64};
        int[] terrain = {64, 66, 64};

        int median = RoadConstructionSafety.representativeHeight(heights);
        assertEquals(64, median, "中位数应当是 64（三列排序后取中间）");

        int[] byCentre = heights.clone();
        RoadConstructionSafety.resolveRoadCrossSection(byCentre, terrain, 1);
        assertEquals(66, byCentre[0], "中心列口径必须把整段压到中心列高度 66");

        assertNotEquals(median, byCentre[0],
                "中心列口径 (66) 与中位数口径 (64) 在非均匀横截面上必须不同 —— "
                        + "如果两者恒等，说明这个改动是空转，"
                        + "也说明 VolumePlanner 之前的口径差不存在，需要重新核对结论。");
    }

    @Test
    void 中心列取不到时退回中位数而不是保持原样() {
        int[] heights = {62, 64, 66};
        int[] terrain = {62, 64, 66};
        int[] before = heights.clone();

        RoadConstructionSafety.resolveRoadCrossSection(heights, terrain, -1);

        int median = RoadConstructionSafety.representativeHeight(before);
        assertEquals(median, heights[0], "centreIndex<0 应退回中位数");
        assertEquals(median, heights[1]);
        assertEquals(median, heights[2]);
        // 关键：不能「保持原样」—— 原样是 62/64/66 三条不同高度
        assertNotEquals(before[0], heights[0],
                "退回路径也必须压平，不能悄悄什么都不做");
    }

    @Test
    void 均匀横截面上两种口径结果相同() {
        int[] heights = {65, 65, 65};
        int[] terrain = {65, 65, 65};

        int[] byMedian = heights.clone();
        int[] byCentre = heights.clone();
        RoadConstructionSafety.resolveRoadCrossSection(byMedian, terrain, -1);
        RoadConstructionSafety.resolveRoadCrossSection(byCentre, terrain, 1);

        assertArrayEquals(byMedian, byCentre,
                "横截面本来就均匀时，两种口径没有差别 —— 这也是为什么这个问题只在"
                        + "斜坡/对角线上才暴露出来");
    }

    @Test
    void 最近列在对称布局下选中点() {
        List<BlockPos> positions = new ArrayList<>();
        positions.add(new BlockPos(0, 64, -1));
        positions.add(new BlockPos(0, 64, 0));
        positions.add(new BlockPos(0, 64, 1));
        BlockPos middle = new BlockPos(0, 64, 0);

        assertEquals(1, RoadConstructionSafety.nearestColumnIndex(positions, middle),
                "对称布局下最近列应当是正中间那一个");
    }

    @Test
    void 最近列在偏置布局下选真正最近的那一列() {
        List<BlockPos> positions = new ArrayList<>();
        positions.add(new BlockPos(0, 64, -2));
        positions.add(new BlockPos(0, 64, 0));
        positions.add(new BlockPos(0, 64, 1));
        // 中心点 z=0.6 —— 距离 z=1 的列更近，而不是中间那个 z=0
        BlockPos middle = new BlockPos(0, 64, 1);

        int index = RoadConstructionSafety.nearestColumnIndex(positions, middle);
        assertEquals(2, index, "应当选 z=1 那一列(index 2)，而不是盲目取中间");
        assertEquals(1, positions.get(index).getZ());
    }

    @Test
    void 最近列在空输入下返回负一() {
        assertEquals(-1, RoadConstructionSafety.nearestColumnIndex(List.of(), new BlockPos(0, 64, 0)),
                "空集合必须返回 -1，让调用方走中位数回退");
        assertEquals(-1, RoadConstructionSafety.nearestColumnIndex(null, new BlockPos(0, 64, 0)));
        assertEquals(-1, RoadConstructionSafety.nearestColumnIndex(
                List.of(new BlockPos(0, 64, 0)), null), "center 为 null 也必须返回 -1");
    }

    @Test
    void 偶数列表的中位数取中间两个的平均() {
        int[] values = {60, 62, 66, 70};
        assertEquals(64, RoadConstructionSafety.representativeHeight(values),
                "偶数个样本应取中间两个的平均（62 与 66 ⇒ 64）");
        assertTrue(values.length % 2 == 0);
    }

    /**
     * 源码级守卫：体积规划必须用中心列口径。
     *
     * <h2>为什么需要「读源码」这种笨办法</h2>
     * <p>行为测试没法区分这件事 —— {@code RoadConstructionVolumePlanner.visit} 拿到的
     * {@code heights} 在多数几何下本来就是均匀的，两种口径输出相同；
     * 只有非平凡中心线才会分叉，而构造那种几何的成本高于收益（见类注释的「局限」）。
     * 所以退一步：直接断言调用关系本身。</p>
     *
     * <p>这不是过度设计 —— 本仓库已有先例：{@code RegressionRegistrationTest}
     * 也是读源码来校验「测试类是否登记进套件」，属于同一类「结构不变式」守卫。</p>
     */
    @Test
    void 体积规划必须使用中心列口径压平横截面() throws IOException {
        Path source = Paths.get("src", "main", "java", "net", "shiroha233", "roadweaver",
                "pathlogic", "construction", "RoadConstructionVolumePlanner.java");
        assertTrue(Files.isRegularFile(source),
                "找不到 " + source.toAbsolutePath()
                        + " —— 本守卫需要在 common 模块目录下运行（与 RegressionRegistrationTest 同理）。");

        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(text.contains("RoadConstructionSafety.resolveRoadCrossSection"),
                "体积规划不再调用 resolveRoadCrossSection —— 也就是说它回到了"
                        + "「用中位数压平」的旧口径，与施工侧 SegmentPaver 不一致。"
                        + "这会让「校验算的方块」和「施工改的方块」差 1~2 格。");

        assertFalse(text.contains("RoadConstructionSafety.representativeHeight"),
                "体积规划又直接调用了 representativeHeight —— 那是中位数口径。"
                        + "施工侧早就不用它了（resolRoadCrossSection 内部在 centreIndex<0 时才回退到它）。"
                        + "如果确实需要中位数语义，请改用 resolveRoadCrossSection(..., -1) 显式表达。");
    }
}
