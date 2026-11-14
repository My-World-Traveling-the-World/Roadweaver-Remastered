/* 文件职责：桥隧联合 DP 与旧判定之间的观测桥 —— 只记录差异，不改任何决策。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 桥隧判定的 AB 观测桥（重制台账批 2 / S7 第 2 级第 3 步）。
 *
 * <h2>它做什么，不做什么</h2>
 * <p><b>做</b>：拿旧判定（{@code PathSpanExtractor}）产出的 {@code spans} 当基线，
 * 另外跑一次 {@link ModeDpSolver}，把两边「几座桥 / 几条隧道 / 差异站在哪」记进日志。</p>
 * <p><b>不做</b>：<b>不修改任何决策</b>。{@code spans} 与剖面都原样传出去 ——
 * 这一版是纯观测，接错了也只是日志难看，不会让一条路变样。</p>
 *
 * <h2>为什么先观测而不是直接接线</h2>
 * <p>S7 第 2 级的通过标准是「旧判定判出的每一座<b>必须</b>的桥，新解也判了
 * （召回率 100%）」。这个数字<b>只能在真实存档上量</b>：合成地形里没有真实的
 * 河宽分布、没有村庄保护区，也没有「已有公路必须桥」这类历史约束。
 * 所以先让它跑一局，拿 {@code [SPAN-AB]} 日志看召回率，达标后再切 {@code on}。</p>
 *
 * <h2>★ 循环论证已解除（2026-11-15，同日发现并修复）</h2>
 *
 * <p><b>曾经的问题：</b>{@code groundBanned} 直接复制 {@code isCrossing}，而
 * {@code isCrossing} 是调用方从<b>旧判定产出的 spans</b> 里推出来的。于是：</p>
 * <pre>
 *   旧 spans ──► isCrossing ──► groundBanned ──► DP 只能在这些站选桥/隧道
 *        └────────────────────────────────────► 再拿 DP 结果与旧 spans 比对
 * </pre>
 * <p>DP 被告知「旧判定认为这里是桥的地方不能铺地面」，再统计它「覆盖了多少旧判定的桥」
 * —— 数字必然偏高，不是独立验证。</p>
 *
 * <p><b>现在的做法：</b>禁地面掩码改由 {@link GroundBanSources} 从
 * <b>与旧判定无关的物理事实</b>构造 —— 水面（地形场水深）与保护区（结构占地快照）。
 * {@code isCrossing} 降级为<b>对照量</b>，只用于日志里比较「独立来源与旧判定差多少」，
 * 不再参与掩码。</p>
 *
 * <p><b>仍需注意两点：</b></p>
 * <ul>
 *   <li>「已有公路」这条来源还没接（现有 API 语义是防树、且被配置门控），属批 4；</li>
 *   <li>保护区快照若不在作用域内会静默返回 false，日志用
 *       {@code snapshot=ok/absent} 如实区分「没有结构」与「没有证据」。</li>
 * </ul>
 * <p>即便解除了循环，{@code [SPAN-AB]} 也只是**同参对照**；
 * 要让 DP 真正接管桥隧决策，还要真机证明「必桥不漏、多判可解释」。</p>
 *
 * <h2>开关</h2>
 * <pre>
 * -Droadweaver.modeDp=ab   只观测（默认）
 * -Droadweaver.modeDp=off  完全不跑（省 CPU）
 * -Droadweaver.modeDp=on   已解析但<b>仍未接决策</b>：当前与 ab 一样只观测，
 *                          不回写 spans，也不会主动抛错
 * </pre>
 * <p>{@code on} 的替换逻辑故意没有实现：在没有真机数据证明召回率之前就允许替换，
 * 等于给「桥隧判错」开一道没有验收的门。注释必须如实说明它目前并非开关。</p>
 *
 * <h2>已知的输入简化（第一版有意为之，会低估桥隧需求）</h2>
 * <ul>
 *   <li>{@code groundBanned} 现由 {@link GroundBanSources} 从「水面 + 保护区」两条
 *       独立物理事实构造（见上面那一节）。<b>「已有公路」仍未接</b> ——
 *       现有 API 语义是防树且受配置门控，属批 4 范围。日志会分别打
 *       {@code water=} / {@code structure=} / {@code snapshot=}，判断各来源贡献。</li>
 *   <li>桥高 / 隧深 / 单价用 {@link ModeDpSolver.Settings#defaults()}，
 *       没有从玩家配置翻译 —— 单价是产品决策，等召回率达标后再统一。</li>
 * </ul>
 */
public final class ModeDpBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String PROPERTY = "roadweaver.modeDp";

    /** 进程内缓存；{@code null} = 还没解析过。 */
    private static volatile Mode CURRENT = null;

    /** 观测开关。 */
    public enum Mode {
        /** 完全不跑。 */
        OFF,
        /** 只观测、只记日志（默认）。 */
        AB,
        /** 用 DP 结果替换旧判定（未实现）。 */
        ON
    }

    private ModeDpBridge() {
    }

    /** 读开关。默认 {@link Mode#AB}。 */
    public static Mode mode() {
        Mode cached = CURRENT;
        if (cached != null) return cached;
        String raw = System.getProperty(PROPERTY, "");
        Mode parsed;
        if ("off".equalsIgnoreCase(raw)) {
            parsed = Mode.OFF;
        } else if ("on".equalsIgnoreCase(raw)) {
            parsed = Mode.ON;
        } else {
            // 未设 / 非法值 ⇒ AB。选 AB 而不是 OFF：观测只多花一次 O(n·W) 的 DP，
            // 但它是批 2 唯一的证据来源。
            parsed = Mode.AB;
        }
        CURRENT = parsed;
        return parsed;
    }

    /** 测试用：清缓存，让下一次 {@link #mode()} 重新读 system property。 */
    public static void resetForTest() {
        CURRENT = null;
    }

    /**
     * 观测：跑一次联合 DP，把与旧判定的差异记进日志。**不修改任何入参**。
     *
     * @param terrainY           每站地面高度
     * @param bans               禁地面掩码（由调用方统一计算，两臂共用同一份）
     * @param isCrossing         旧判定判出的跨水站；<b>只作对照</b>，不再参与禁地面掩码
     * @param gradeLimits        施工窗口快照（挖/填/覆土与旧链路同源）
     * @param cfg                生成配置（只用来取坡度口径，保证与 DP 剖面同参）
     * @param centers            每站的方块坐标（转 {@link RoadSpan} 用）
     * @param legacySpans        旧判定产出的跨度（基线）
     * @param tag                连接标识，进日志便于对账
     */
    public static void observe(int[] terrainY,
                               GroundBanSources.Result bans,
                               boolean[] isCrossing,
                               SurfaceGradeLimits gradeLimits,
                               RoadGenerationConfig cfg,
                               List<BlockPos> centers,
                               List<RoadSpan> legacySpans,
                               String tag) {
        if (mode() == Mode.OFF) return;
        if (terrainY == null || terrainY.length == 0) return;
        try {
            int n = terrainY.length;

            // ★ 2026-11-15：禁地面掩码由调用方统一算好传入（水面 + 保护区两条独立来源），
            //   不再复制 isCrossing。两臂共用同一份掩码 ⇒ 口径可比。
            boolean[] groundBanned = bans == null ? new boolean[n] : bans.banned();
            int banned = bans.bannedStations();
            // 旧判定的跨水站数：只作对照，用来量化「独立来源与旧判定差多少」。
            int legacyCrossing = 0;
            if (isCrossing != null) {
                for (int i = 0; i < Math.min(n, isCrossing.length); i++) {
                    if (isCrossing[i]) legacyCrossing++;
                }
            }

            int perTwo = Math.max(1, cfg == null ? 2 : cfg.maxSlopeStepPerTwoSegments());
            // 一阶上限与 DP 剖面求解同口径：把「每两站 N 格」折算成每站（向上取整）。
            int maxStep = Math.max(1, (perTwo + 1) / 2);

            var solved = ModeDpSolver.solve(terrainY, maxStep, settingsFor(gradeLimits), groundBanned);
            if (solved.isEmpty()) {
                LOGGER.info("[SPAN-AB] tag={} dp=infeasible stations={} banned={}"
                                + "(water={} structure={} snapshot={}) legacyCrossing={} legacy={}",
                        tag, n, banned, (bans == null ? 0 : bans.waterStations()), (bans == null ? 0 : bans.structureStations()),
                        (bans != null && bans.structureSnapshotAvailable()) ? "ok" : "absent",
                        legacyCrossing, describe(legacySpans));
                return;
            }
            var solution = solved.get();
            List<RoadSpan> dpSpans = spansFrom(solution.modes(), centers, n);

            Counts lg = count(legacySpans, centers);
            Counts dp = count(dpSpans, centers);
            int differingStations = differingStations(solution.modes(), legacySpans, centers, n);

            LOGGER.info("[SPAN-AB] tag={} stations={} banned={}"
                            + "(water={} structure={} snapshot={}) legacyCrossing={} perTwo={} "
                            + "legacy[bridge={}站/{}座 tunnel={}站/{}条] "
                            + "dp[bridge={}站/{}座 tunnel={}站/{}条] "
                            + "差异站={} dpCost={}",
                    tag, n, banned,
                    (bans == null ? 0 : bans.waterStations()), (bans == null ? 0 : bans.structureStations()),
                    (bans != null && bans.structureSnapshotAvailable()) ? "ok" : "absent",
                    legacyCrossing, perTwo,
                    lg.bridgeStations, lg.bridgeRuns, lg.tunnelStations, lg.tunnelRuns,
                    dp.bridgeStations, dp.bridgeRuns, dp.tunnelStations, dp.tunnelRuns,
                    differingStations, String.format("%.1f", solution.totalCost()));
        } catch (Throwable t) {
            // 观测绝不能影响生产：任何异常都咽掉并记一条，绝不向上抛。
            LOGGER.warn("[SPAN-AB] tag={} observation failed (不影响生产): {}", tag, t.toString());
        }
    }

    /**
     * 施工窗口 → 求解参数。
     *
     * <p>挖/填/覆土三项<b>必须</b>与旧链路同源（{@link SurfaceGradeLimits}），
     * 否则比较的是两套窗口下的解，AB 结论没有意义。桥高/隧深/单价沿用 defaults。</p>
     */
    static ModeDpSolver.Settings settingsFor(SurfaceGradeLimits gradeLimits) {
        var base = ModeDpSolver.Settings.defaults();
        if (gradeLimits == null) return base;
        return new ModeDpSolver.Settings(
                Math.max(0, gradeLimits.plainCut()),
                Math.max(0, gradeLimits.plainFill()),
                base.maxBridgeHeight(),
                base.maxTunnelDepth(),
                // 覆土与旧链路同源；但 Settings 要求 maxTunnelDepth ≥ minTunnelCover。
                Math.max(1, Math.min(base.maxTunnelDepth(), gradeLimits.tunnelCoreOverburden())),
                base.bridgePerStation(),
                base.tunnelPerStation(),
                base.abutmentCost(),
                base.portalCost(),
                base.groundCost());
    }

    /**
     * 逐站模式 → 跨度列表：连续同模式的站合并成一段。
     *
     * <p>只有 {@code BRIDGE}/{@code TUNNEL} 会产生跨度；{@code GROUND} 是默认态不记。
     * 这正对应 {@link RoadSpan} 只记录「桥/隧道」的语义。</p>
     */
    public static List<RoadSpan> spansFrom(ModeDpSolver.Mode[] modes,
                                           List<BlockPos> centers, int n) {
        List<RoadSpan> out = new ArrayList<>();
        if (modes == null || centers == null) return out;
        int limit = Math.min(n, Math.min(modes.length, centers.size()));
        int i = 0;
        while (i < limit) {
            ModeDpSolver.Mode m = modes[i];
            if (m == ModeDpSolver.Mode.GROUND) {
                i++;
                continue;
            }
            int start = i;
            while (i < limit && modes[i] == m) i++;
            int end = i - 1;
            BlockPos a = centers.get(start);
            BlockPos b = centers.get(end);
            if (a == null || b == null) continue;
            out.add(new RoadSpan(a, b,
                    m == ModeDpSolver.Mode.BRIDGE ? SpanType.BRIDGE : SpanType.TUNNEL));
        }
        return out;
    }

    /** 跨度统计：站数按「起止坐标在 centers 里的索引区间」精确算，不靠猜。 */
    private record Counts(int bridgeStations, int bridgeRuns,
                          int tunnelStations, int tunnelRuns) {
    }

    private static Counts count(List<RoadSpan> spans, List<BlockPos> centers) {
        int bStations = 0, bRuns = 0, tStations = 0, tRuns = 0;
        if (spans == null || centers == null) return new Counts(0, 0, 0, 0);
        for (RoadSpan sp : spans) {
            if (sp == null || sp.type() == null || sp.start() == null || sp.end() == null) continue;
            boolean isBridge = sp.type() == SpanType.BRIDGE
                    || sp.type() == SpanType.ROAD_CROSSING;
            boolean isTunnel = sp.type() == SpanType.TUNNEL
                    || sp.type() == SpanType.TUNNEL_JUNCTION;
            if (!isBridge && !isTunnel) continue;
            int span = spanStations(sp, centers);
            if (isBridge) {
                bRuns++;
                bStations += span;
            } else {
                tRuns++;
                tStations += span;
            }
        }
        return new Counts(bStations, bRuns, tStations, tRuns);
    }

    /** 起止坐标在 centers 里的站数（含两端）；任一端找不到则返回 0，宁可少算不乱算。 */
    private static int spanStations(RoadSpan sp, List<BlockPos> centers) {
        int a = indexOf(sp.start(), centers);
        int b = indexOf(sp.end(), centers);
        if (a < 0 || b < 0) return 0;
        return Math.abs(b - a) + 1;
    }

    private static int indexOf(BlockPos p, List<BlockPos> centers) {
        if (p == null || centers == null) return -1;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos c = centers.get(i);
            if (c != null && c.getX() == p.getX() && c.getZ() == p.getZ()) return i;
        }
        return -1;
    }

    /**
     * DP 判为非地面、但旧判定没覆盖的站数（新解多判），以及反方向（旧判了新解没判）。
     *
     * <p>后者才是<b>召回率</b>的关键指标：旧判定判出的桥，DP 漏了就是召回缺口。</p>
     */
    private static int differingStations(ModeDpSolver.Mode[] modes,
                                         List<RoadSpan> legacySpans,
                                         List<BlockPos> centers, int n) {
        if (modes == null || centers == null) return 0;
        int diff = 0;
        int limit = Math.min(n, Math.min(modes.length, centers.size()));
        for (int i = 0; i < limit; i++) {
            boolean dpOffGround = modes[i] != ModeDpSolver.Mode.GROUND;
            boolean legacyCovered = coveredByLegacy(centers.get(i), legacySpans);
            if (dpOffGround != legacyCovered) diff++;
        }
        return diff;
    }

    /** 该站是否落在某个旧跨度的起止之间（按坐标矩形线性扫，观测专用）。 */
    private static boolean coveredByLegacy(BlockPos p, List<RoadSpan> spans) {
        if (p == null || spans == null || spans.isEmpty()) return false;
        for (RoadSpan sp : spans) {
            if (sp == null || sp.type() == null || sp.start() == null || sp.end() == null) continue;
            if (sp.type() != SpanType.BRIDGE && sp.type() != SpanType.ROAD_CROSSING
                    && sp.type() != SpanType.TUNNEL && sp.type() != SpanType.TUNNEL_JUNCTION) {
                continue;
            }
            if (between(p, sp.start(), sp.end())) return true;
        }
        return false;
    }

    /** 平面矩形内判定（跨度是起止站对，路线在 XZ 上单调推进）。 */
    private static boolean between(BlockPos p, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    private static String describe(List<RoadSpan> spans) {
        Counts c = count(spans, List.of());
        return "bridge=" + c.bridgeRuns + "座 tunnel=" + c.tunnelRuns + "条";
    }
}