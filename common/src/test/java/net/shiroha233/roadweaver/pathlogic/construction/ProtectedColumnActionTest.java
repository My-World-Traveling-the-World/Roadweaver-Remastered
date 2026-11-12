package net.shiroha233.roadweaver.pathlogic.construction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.9.033（O-14「单列保护漏单格洞」）的回归。
 *
 * <p><b>缺陷</b>：{@code SegmentPaver.paveSegment} 原来写的是</p>
 * <pre>
 * boolean protectedColumn = isProtectedPreparedBlock(pos) || isProtectedPreparedBlock(pos.below());
 * if (protectedColumn) continue;
 * </pre>
 * <p>两种受保护被并成一件事。{@code isProtectedState} 覆盖 {@code hasBlockEntity}，所以路面
 * 下面只要有一个箱子/告示牌/熔炉这类方块实体，这一列的路面就被**整格跳过**，而左右邻列照常
 * 铺 —— 驾驶面上出现 1×1 的坑。这条在村镇里几乎必然命中。</p>
 *
 * <p><b>为什么不能简单「两种都不跳过」</b>：那条路会走
 * {@code SurfacePlacementUtil.placeOnSurface} → {@code RoadBlockPlacer.placeRoadBlock}，
 * 而后者**无条件** {@code setBlock(pos.below(), chosen)}，正好把受保护的那一格覆盖掉
 * （台账警告的「覆盖受保护方块」）。所以「只有路基受保护」必须走一条只写 pos、不写
 * pos.below() 的窄路径 —— 这就是 {@link SegmentPaver.ColumnAction#PAVE_SURFACE_ONLY}。</p>
 *
 * <p>本测试钉的是**优先级**：路面格受保护永远优先于路基受保护，两者都受保护时必须跳过。
 * 这一条一旦写反，表现是「受保护方块被路面替换」——静默、不可回滚、但质量指标完全不报警。</p>
 */
final class ProtectedColumnActionTest {

    private static SegmentPaver.ColumnAction classify(boolean surface, boolean roadbed) {
        return SegmentPaver.classifyProtectedColumn(surface, roadbed);
    }

    @Test
    void surfaceProtectionWinsOverRoadbedProtection() {
        // 两者都受保护：必须跳过 —— 跳过的是受保护方块自己那一格。
        assertEquals(SegmentPaver.ColumnAction.SKIP, classify(true, true));
    }

    @Test
    void protectedSurfaceIsAlwaysSkipped() {
        assertEquals(SegmentPaver.ColumnAction.SKIP, classify(true, false));
    }

    @Test
    void protectedRoadbedNoLongerPunchesASingleCellHole() {
        // 这条就是 O-14 的修复点：0.9.032 及以前这里是 SKIP（= 1×1 的坑）。
        assertEquals(SegmentPaver.ColumnAction.PAVE_SURFACE_ONLY, classify(false, true));
    }

    @Test
    void ordinaryColumnKeepsTheFullPlacementPath() {
        assertEquals(SegmentPaver.ColumnAction.PAVE_NORMAL, classify(false, false));
    }

    @Test
    void onlyTheSurfaceOnlyPathAvoidsRewritingTheCellBelow() {
        // 行为契约：只有 PAVE_SURFACE_ONLY 允许绕开 placeOnSurface。
        for (boolean surface : new boolean[]{false, true}) {
            for (boolean roadbed : new boolean[]{false, true}) {
                SegmentPaver.ColumnAction action = classify(surface, roadbed);
                boolean mayUsePlaceOnSurface = action != SegmentPaver.ColumnAction.PAVE_SURFACE_ONLY;
                if (action == SegmentPaver.ColumnAction.SKIP) {
                    // 跳过路径根本不写方块，placeOnSurface 不会被调用；这里只要求它不是那条窄路径。
                    mayUsePlaceOnSurface = true;
                }
                if (!surface && roadbed) {
                    assertEquals(false, mayUsePlaceOnSurface,
                            "只有路基受保护时必须走窄路径，否则 placeOnSurface 会覆盖 pos.below()");
                }
            }
        }
    }
}
