/* 文件职责：最大坡度档位（0.9.061）—— 三档常量、运行时解析、纯函数折算。 */
package net.shiroha233.roadweaver.core.constants;

import java.util.Locale;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;

/**
 * 最大纵向坡度档位（0.9.061）。
 *
 * <p><b>为什么要有这个类。</b>0.9.060 之前，整条剖面链上真正绑定「路能爬多陡」的是
 * {@code maxSlopeStepPerTwoSegments = 1}（{@link RoadConstants#DEFAULT_MAX_SLOPE_STEP}），
 * 它的语义是「任意两个相邻站之间最多抬 1 格」。实测站距是 <b>1.0 格/站</b>
 * （{@code PathPostProcessor.extractCenters} 沿样条每 1.0 格弧长落一个中心站，
 * 见 {@link RoadConstants#MEASURED_STATION_SPACING_BLOCKS} 的注释），所以这条约束折算出来是
 * <b>每 2 格抬 1 格 = 50% 坡度</b>。真实盘山公路是 8~15%：50% 的坡意味着
 * 「直着爬」永远比「绕之字」省地，A* 没有任何理由去盘山。</p>
 *
 * <p>本类只做三件事：① 定义三档「每 N 站抬 1 格」；② 从系统属性
 * {@code -Droadweaver.gradeCap=tight|normal|loose} 解析当前档位（非法值回落
 * {@link #DEFAULT_MODE}，0.9.062 起是 loose）；
 * ③ 提供 <b>纯函数</b> {@link #maxRise(double, Mode)}，把「水平跑了多少格」折算成
 * 「允许抬几格」，供剖面约束与单元测试共用。</p>
 *
 * <p><b>为什么是「每 N 站抬 1 格」而不是「每两站抬 M 格」。</b>剖面高度是逐站整数，
 * 12.5% 在 1 格/站的站距下就是「8 站抬 1 格」；用整数 M/N 表达不了 0.25 格的上升，
 * 所以窗口宽度 N 才是正确的参数化方式。M 恒为
 * {@link RoadConstants#GRADE_CAP_RISE_BLOCKS}。</p>
 *
 * <p>本类<b>只读</b>系统属性、不碰世界、不碰配置、不做 I/O，因此可以在纯 JUnit 里跑。</p>
 */
public final class GradeCap {

    /** 三档。0.9.062 起 {@link #LOOSE} 是默认档。 */
    public enum Mode {
        /** 每 10 站抬 1 格 ≈ 10%。 */
        TIGHT,
        /** 每 8 站抬 1 格 ≈ 12.5%。 */
        NORMAL,
        /** 每 7 站抬 1 格 ≈ 14.29%（0.9.062 起的默认档，最接近旧行为里「温和」的那一档）。 */
        LOOSE
    }

    /**
     * 默认档 = {@link Mode#LOOSE}（每 7 站抬 1 格 ≈ 14.29%），0.9.062 起。
     *
     * <p>0.9.061 的默认是 {@link Mode#NORMAL}(12.5%)。真机结论是「剖面收紧逼不出之字、
     * 只会切更深被施工拒」—— 之字的成因在代价函数（坡罚量纲）而不在剖面档位，
     * 所以先把档位放到最松的一档，把剖面约束从「制造冲突」的角色里摘出来。</p>
     */
    public static final Mode DEFAULT_MODE = Mode.LOOSE;

    private GradeCap() {
    }

    /**
     * 解析一个档位字符串。{@code null}、空白、以及任何非法值一律回落
     * {@link #DEFAULT_MODE}（0.9.062 起是 loose）—— 这是硬约定：调试开关写错不许改变默认行为。
     */
    public static Mode resolve(String raw) {
        if (raw == null) return DEFAULT_MODE;
        String value = raw.trim();
        if (value.isEmpty()) return DEFAULT_MODE;
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_MODE;
        }
    }

    /** {@return 当前生效档位}（每次都读系统属性，所以运行时可切换，不必重开游戏）。 */
    public static Mode current() {
        return resolve(System.getProperty(RoadConstants.GRADE_CAP_PROPERTY));
    }

    /** {@return 该档位「每多少站允许抬 M 格」里的站数 N} */
    public static int riseStations(Mode mode) {
        if (mode == null) return RoadConstants.DEFAULT_GRADE_CAP_RISE_STATIONS;
        return switch (mode) {
            case TIGHT -> RoadConstants.GRADE_CAP_TIGHT_RISE_STATIONS;
            case NORMAL -> RoadConstants.GRADE_CAP_NORMAL_RISE_STATIONS;
            case LOOSE -> RoadConstants.GRADE_CAP_LOOSE_RISE_STATIONS;
        };
    }

    /** {@return 该档位「每 N 站允许抬多少格」里的格数 M}（当前三档恒为 1） */
    public static int riseBlocks(Mode mode) {
        return RoadConstants.GRADE_CAP_RISE_BLOCKS;
    }

    /** {@return 实测轴向站距（格/站）} */
    public static double stationSpacing(Mode mode) {
        return RoadConstants.MEASURED_STATION_SPACING_BLOCKS;
    }

    /** {@return 该档位在实测站距下折算出的坡度百分比（轴向，正常坡向）} */
    public static double gradePercent(Mode mode) {
        double run = riseStations(mode) * stationSpacing(mode);
        return run <= 0.0D ? 0.0D : 100.0D * riseBlocks(mode) / run;
    }

    /**
     * <b>纯函数</b>：水平跑 {@code distance} 格允许抬多少格。
     *
     * <p>向下取整（只收不放）：{@code floor(distance * M / (N * 站距))}。取整方向是有意的 ——
     * 短窗口只会更严格，绝不会因为浮点误差放宽一档。</p>
     *
     * <p>边界（见 {@code GradeCapTest}）：10 格 @tight = 1；10 格 @normal = 1；
     * 20 格 @normal = 2；7 格 @loose = 1；6 格 @loose = 0；任何负数/NaN = 0。</p>
     */
    public static int maxRise(double distance, Mode mode) {
        if (Double.isNaN(distance) || distance <= 0.0D) return 0;
        double run = riseStations(mode) * stationSpacing(mode);
        if (run <= 0.0D) return 0;
        double rise = distance * riseBlocks(mode) / run;
        if (rise >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.floor(rise + 1e-9D);
    }

    /** {@return 小写档位名，供 {@code [GRADE-CAP]} 日志行使用} */
    public static String label(Mode mode) {
        Mode effective = mode == null ? DEFAULT_MODE : mode;
        return effective.name().toLowerCase(Locale.ROOT);
    }

    /**
     * {@code [GRADE-CAP]} 汇总行（纯函数，便于单元测试钉住字段与顺序）。
     *
     * <p>字段：{@code mode / risePerStations / blocks / maxGradePct / cappedStations /
     * totalStations / cappedPct}。{@code cappedStations} = 被这一道窗口约束实际压低的站数，
     * 是判断三档怎么选的唯一依据。</p>
     */
    public static String summary(Mode mode, int cappedStations, int totalStations) {
        int total = Math.max(0, totalStations);
        int capped = Math.max(0, Math.min(cappedStations, total));
        double cappedPct = total <= 0 ? 0.0D : 100.0D * capped / total;
        return String.format(Locale.ROOT,
                "[GRADE-CAP] mode=%s risePerStations=%d blocks=%d maxGradePct=%.2f "
                        + "cappedStations=%d totalStations=%d cappedPct=%.2f",
                label(mode), riseStations(mode), riseBlocks(mode), gradePercent(mode),
                capped, total, cappedPct);
    }
}
