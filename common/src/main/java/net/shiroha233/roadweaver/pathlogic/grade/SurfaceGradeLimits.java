/* 文件职责：纵断面的「配置上限快照」—— 从 RoadGradePlanner 抽出的顶层类型（S7 第 3 级前置 A）。 */
package net.shiroha233.roadweaver.pathlogic.grade;

import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.sub.PlanningConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-pass snapshot of every config-derived surface grade limit. Captured
 * once per planning pass so the per-station clamps below never touch
 * {@link ConfigService} (which is synchronized) in their inner loops.
 * Shared with PathSpanExtractor so cut/fill decisions use one source.
 *
 * <h2>为什么它是顶层类型（2026-11-14）</h2>
 * <p>它原本是 {@code RoadGradePlanner} 的嵌套 record。按
 * {@code docs/S7-SWITCH-CHECKLIST.md}「第 3 级前置 A」抽出，理由是：
 * 这个快照被 <b>4 个文件</b>引用，其中 {@code TunnelFootprintSampler} 与
 * {@code UnknownEndpointHeightRegistry} <b>不在旧链路里</b> ——
 * 直接删 {@code RoadGradePlanner}（重制台账批 1）会砸掉两个与旧算法无关的调用方。</p>
 *
 * <p>判据：<b>算法（15 道 pass）可以删，配置上限快照必须留。</b></p>
 *
 * <h2>抽出时踩过的两个坑（别再重复）</h2>
 * <ol>
 *   <li><b>新文件头不许再写一遍 {@code public record ...}</b> —— 否则搬来的
 *       record 变成内层类型，把外层实现全遮住，报「在记录中定义了变量」。</li>
 *   <li><b>删常量块必须覆盖整个 javadoc</b> —— 按「看起来像常量块」的行号删，
 *       会把上方的注释结束符一起删掉，注释一路吞到下一个 注释结束符，
 *       把后面的常量吃进注释里（报「找不到符号 TUNNEL_MOUTH_OVERBURDEN」）。</li>
 * </ol>
 */
public record SurfaceGradeLimits(int plainCut,
                                 int plainFill,
                                 int mountainCut,
                                 int mountainFill,
                                 int tunnelCoreOverburden,
                                 int tunnelMinRun) {

    /**
     * 纵断面填方上限。**直接引用施工端的权威常量**，不再自己写一份。
     *
     * <p>这里原本是独立的 {@code = 2}，与 {@link RoadConstructionSafety#MAX_FILL_DEPTH}
     * 数值恰好相同——但那是「巧合相同」，不是「同一个来源」。2026-11-11 排查 4 条
     * initial connection 失败时，正是因为规划期与施工前门槛各写一套上限，
     * 一条 legitimately 规划出 fill=3 的路被门槛按 2 判死，而且两边都没有日志说明
     * 自己用的是哪个数。现在两处同源，改一处即同时生效。</p>
     */
    public static final int MAX_SURFACE_FILL = RoadConstructionSafety.MAX_FILL_DEPTH;

    /** 纵断面挖方上限。同 {@link #MAX_SURFACE_FILL}，与施工端 {@link RoadConstructionSafety#MAX_CUT_DEPTH} 同源。 */
    public static final int MAX_SURFACE_CUT = RoadConstructionSafety.MAX_CUT_DEPTH;

    /** 隧道洞身覆土。注意它<b>只被本快照的回落分支用</b>，见下方 {@code TUNNEL_*} 分界说明。 */
    public static final int TUNNEL_CORE_OVERBURDEN = 7;

    /** 最短隧道段。旧算法（{@code RoadGradePlanner}）仍在使用，故原处保留同名别名。 */
    public static final int TUNNEL_MIN_RUN = 3;

    private static final Logger LIMIT_LOGGER = LoggerFactory.getLogger("roadweaver");
    private static volatile long LIMIT_LOG_AT = 0L;

    public static SurfaceGradeLimits capture() {
        try {
            return capture(ConfigService.get().planning().terrainProfileMode());
        } catch (Throwable ignored) {
            return new SurfaceGradeLimits(MAX_SURFACE_CUT, MAX_SURFACE_FILL,
                    MAX_SURFACE_CUT, MAX_SURFACE_FILL,
                    TUNNEL_CORE_OVERBURDEN, TUNNEL_MIN_RUN);
        }
    }

    /**
     * Captures limits for an explicit effective mode — the global mode, or
     * the route-level AUTO tendency resolved by {@code RouteProfileTendency}
     * for this connection. Never writes anything back to config.
     */
    public static SurfaceGradeLimits capture(PlanningConfig.TerrainProfileMode mode) {
        try {
            var planning = ConfigService.get().planning();
            var terrain = ConfigService.get().terrainProfile();
            boolean reducedCut = mode == PlanningConfig.TerrainProfileMode.TUNNEL_FIRST
                    || mode == PlanningConfig.TerrainProfileMode.LIGHT_CUT;
            boolean reducedFill = mode == PlanningConfig.TerrainProfileMode.BRIDGE_FIRST
                    || mode == PlanningConfig.TerrainProfileMode.LIGHT_CUT;
            int plainCut = Math.max(0, Math.min(16, terrain.plainSurfaceCutMaxBlocks()));
            int plainFill = Math.max(0, Math.min(16, terrain.plainSurfaceFillMaxBlocks()));
            int mountainCut = Math.max(0, Math.min(16, terrain.mountainSurfaceCutMaxBlocks()));
            int mountainFill = Math.max(0, Math.min(16, terrain.mountainSurfaceFillMaxBlocks()));
            // 0.9.019（O-07）：减配从**绝对值**改成**相对值**。
            //
            // 原来写的是 min(3, cut) / min(1, fill)，而 AUTO 会按路线地形把模式换成
            // BRIDGE_FIRST（水占比 ≥25%）或 TUNNEL_FIRST（山地 ≥30%）—— 于是**越是
            // 需要抬高/削平地形的路线（沿河、山地），玩家配的填方越会被压回 1 格**，
            // 而且零日志。玩家把 plainSurfaceFillMaxBlocks 从 2 调到 4 对这类路线
            // 完全无效，这就是「配置调了没反应」的直接来源。
            //
            // 改成「比配置少 1 格、下限 1」：仍然是相对减配（保留倾向语义），但玩家
            // 调大多少就真拿到多少。**只改 fill 一侧** —— cut 侧 min(3, cut) 的绝对上限
            // 是既有语义（TUNNEL_FIRST 就是要少切），而且玩家抱怨的是「填方调了没反应」，
            // 不是「挖方调了没反应」；动 cut 会同时改掉 3 条既有测试锚定的语义。
            int effectivePlainCut = reducedCut ? Math.min(3, plainCut) : plainCut;
            // 0.9.045（D-24）：fill==0 是玩家明确关掉填方，不能被 reducedFill 抬成 1。
            int effectivePlainFill = reducedFill && plainFill > 0 ? Math.max(1, plainFill - 1) : plainFill;
            int effectiveMountainCut = reducedCut ? Math.min(3, mountainCut) : mountainCut;
            // 0.9.045（D-24）：同上，mountainFill==0 保持 0。
            int effectiveMountainFill = reducedFill && mountainFill > 0 ? Math.max(1, mountainFill - 1) : mountainFill;
            if (reducedCut || reducedFill) {
                logReducedLimits(mode, plainCut, plainFill, mountainCut, mountainFill,
                        effectivePlainCut, effectivePlainFill, effectiveMountainCut, effectiveMountainFill);
            }
            return new SurfaceGradeLimits(
                    effectivePlainCut,
                    effectivePlainFill,
                    effectiveMountainCut,
                    effectiveMountainFill,
                    Math.max(2, Math.min(32, planning.mountainOverburdenBlocks())),
                    Math.max(2, Math.min(16, planning.tunnelMinRun())));
        } catch (Throwable ignored) {
            return new SurfaceGradeLimits(MAX_SURFACE_CUT, MAX_SURFACE_FILL,
                    MAX_SURFACE_CUT, MAX_SURFACE_FILL,
                    TUNNEL_CORE_OVERBURDEN, TUNNEL_MIN_RUN);
        }
    }

    public int cut(boolean mountain) {
        return mountain ? mountainCut : plainCut;
    }

    public int fill(boolean mountain) {
        return mountain ? mountainFill : plainFill;
    }

    /**
     * 减配时的取证日志（O-07）。
     *
     * <p>行数会与路线数同阶，所以按 10 秒节流 —— 一局开局几十条路只会看到一两行，
     * 但只要还有人被减配，就一定能看到「配置是几、实际用了几」。</p>
     */
    private static void logReducedLimits(PlanningConfig.TerrainProfileMode mode,
                                         int configuredPlainCut, int configuredPlainFill,
                                         int configuredMountainCut, int configuredMountainFill,
                                         int effectivePlainCut, int effectivePlainFill,
                                         int effectiveMountainCut, int effectiveMountainFill) {
        long now = System.currentTimeMillis();
        if (now - LIMIT_LOG_AT < 10_000L) return;
        LIMIT_LOG_AT = now;
        LIMIT_LOGGER.info("RoadWeaver grade limits reduced by profile tendency: mode={} "
                        + "plainCut={}->{} plainFill={}->{} mountainCut={}->{} mountainFill={}->{}",
                mode, configuredPlainCut, effectivePlainCut, configuredPlainFill, effectivePlainFill,
                configuredMountainCut, effectiveMountainCut, configuredMountainFill, effectiveMountainFill);
    }
}