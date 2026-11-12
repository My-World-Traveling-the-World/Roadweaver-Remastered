package net.shiroha233.roadweaver.core.constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.062（③）：最大坡度默认档 {@code normal} → {@code loose}。
 *
 * <p><b>为什么改。</b>0.9.061 的默认是 normal（每 8 站抬 1 格 ≈ 12.5%）。真机结论是
 * 「剖面收紧逼不出之字」：之字的成因在代价函数（坡罚量纲，见 ②），不在剖面档位；把剖面压得更死，
 * 只会让每一站更想切深去贴地形，切深被施工端拒绝，冲突原样留着。所以先把默认放到最松的一档
 * （loose = 每 7 站抬 1 格 ≈ 14.29%），把剖面约束从"制造冲突"的角色里摘出来。</p>
 *
 * <p>本用例钉：无系统属性时 {@code GradeCap.current() == LOOSE}、{@code DEFAULT_MODE == LOOSE}、
 * 默认站数跟着一起走，并且显式 {@code -Droadweaver.gradeCap=normal} 仍然能切回去（回退路径）。</p>
 */
final class GradeCapDefaultLooseTest {

    private static final String PROPERTY = RoadConstants.GRADE_CAP_PROPERTY;

    @Test
    void currentIsLooseWhenNoPropertyIsSet() {
        String previous = System.getProperty(PROPERTY);
        try {
            System.clearProperty(PROPERTY);

            assertEquals(GradeCap.Mode.LOOSE, GradeCap.DEFAULT_MODE, "0.9.062 起默认档是 loose");
            assertEquals(GradeCap.Mode.LOOSE, GradeCap.current(),
                    "无系统属性时 GradeCap.current() 必须是 loose");
            assertEquals(RoadConstants.GRADE_CAP_LOOSE_RISE_STATIONS,
                    RoadConstants.DEFAULT_GRADE_CAP_RISE_STATIONS,
                    "默认站数必须跟着默认档一起改成 7");
            assertEquals(7, GradeCap.riseStations(GradeCap.current()));
            assertEquals(7, GradeCap.riseStations(null), "null 档位回落默认档");

            // ≈ 14.29%
            assertEquals(100.0D / 7.0D, GradeCap.gradePercent(GradeCap.current()), 1.0E-9D);
            assertTrue(GradeCap.gradePercent(GradeCap.current()) > 14.0D,
                    "loose 档必须比 normal(12.5%) 松");
            // 该档位下 7 格刚好允许抬 1 格
            assertEquals(1, GradeCap.maxRise(7.0D, GradeCap.current()));
            assertEquals(0, GradeCap.maxRise(6.999D, GradeCap.current()));

            // 回退路径：显式属性仍然优先
            System.setProperty(PROPERTY, "normal");
            assertEquals(GradeCap.Mode.NORMAL, GradeCap.current(), "显式 normal 仍然生效");
            assertEquals(8, GradeCap.riseStations(GradeCap.current()));
            System.setProperty(PROPERTY, "tight");
            assertEquals(GradeCap.Mode.TIGHT, GradeCap.current(), "显式 tight 仍然生效");
        } finally {
            if (previous == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, previous);
            }
        }
    }
}
