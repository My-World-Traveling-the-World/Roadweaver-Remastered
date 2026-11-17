/* 文件职责：桥隧联合 DP 与旧判定之间的观测桥 —— 只记录差异，不改任何决策。 */
package net.shiroha233.roadweaver.pathlogic.profile;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

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
 *   <li>{@code groundBanned} 现由 {@link GroundBanSources} 从「水面 + 保护区 + 已有公路」
 *       三条独立物理事实构造（见上面那一节）。<b>三条都已接入</b>
 *       （2026-11-15 更正：此处原先写「已有公路仍未接」，已过时 ——
 *       它由 {@link ExistingRoadProbe#forCorridor} 以走廊 owner 键构造，含自排除）。
 *       日志会分别打 {@code water=} / {@code structure=} / {@code road=} / {@code snapshot=}，
 *       判断各来源贡献。</li>
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
        WRITEBACK_ON = null;
        STASH.remove();
        STASH_SOLVED.set(0);
        STASH_CONSUMED.set(0);
        STASH_MISSED.set(0);
        WRITEBACK_APPLIED.set(0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 跨度回写（方案 A：ThreadLocal 快照）
    //
    // 【为什么需要这一整块】
    // `observe` 只能把 DP 判出的桥隧数打进日志，**不改任何决策** —— 旧判定仍然
    // 主导 spans。批 2 要收尾就必须让 DP 的结果真的写回 spans，否则「ModeDpSolver
    // 接管桥隧」永远只是观测。
    //
    // 【为什么用 ThreadLocal 而不是改方法签名】
    // `computeTargetY` 有 6 个调用点、`SingleSourceOfTruthTest` 按触发文件数数它，
    // 改签名牵动面大、回退成本高。ThreadLocal 快照的改动面只在三个文件：
    // 本类（求解+存取）、`TargetYComputation`（求解入口）、`Road`（消费+合并）。
    //
    // 【三道保护（这是本方案能上生产的前提）】
    //   ① 默认关闭：`-Droadweaver.spanWriteback=on` 才生效；关掉时本块所有方法
    //      都是空操作，零开销、零行为变化。
    //   ② 可观测：`stashSolved` / `stashConsumed` / `stashMissed` / `writebackApplied`
    //      四个计数器 + `[SPAN-WB]` 日志，让「没拿到数据」不再静默 ——
    //      这正是本仓库吃过的亏（`shouldAvoidPlanning` 静默失明，第 6 轮才补观测）。
    //   ③ 生命周期双保险：`Road.generateRoad` 开场 {@link #clearStash()} 防残留，
    //      取用时 {@link #consumeStash()} 即清 —— 一条路最多消费一次，
    //      绝不会把上一条路的解用到下一条路上。
    // ══════════════════════════════════════════════════════════════════════

    /** 回写开关。{@code on} 才生效，默认关闭。 */
    private static final String PROPERTY_WRITEBACK = "roadweaver.spanWriteback";
    private static volatile Boolean WRITEBACK_ON = null;

    /**
     * 回写是否开启（默认 <b>关</b>）。
     *
     * <p>刻意与 {@link #mode()} 独立：观测开关控制「打不打日志」，
     * 回写开关控制「改不改生产 spans」。两者可以任意组合 ——
     * 比如「观测关、回写开」是纯静默接管，「观测开、回写关」是纯观测。</p>
     */
    public static boolean writebackEnabled() {
        Boolean cached = WRITEBACK_ON;
        if (cached != null) return cached;
        boolean on = "on".equalsIgnoreCase(System.getProperty(PROPERTY_WRITEBACK, ""))
                || "true".equalsIgnoreCase(System.getProperty(PROPERTY_WRITEBACK, ""));
        WRITEBACK_ON = on;
        return on;
    }

    /** 一条路上「求解出来的桥隧模式」的快照。 */
    /**
     * 一条路上「求解出来的桥隧模式」的快照。
     *
     * @param terrainY 求本次解用的地形数组（<b>按引用保存，只用于身份比较</b>）
     */
    public record Stash(ModeDpSolver.Solution solution,
                        List<BlockPos> centers,
                        int stations,
                        int[] terrainY) {
    }

    private static final ThreadLocal<Stash> STASH = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger STASH_SOLVED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger STASH_CONSUMED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger STASH_MISSED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger WRITEBACK_APPLIED =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 求解并把结果存进 ThreadLocal（<b>与观测开关无关</b>）。
     *
     * <p>回写关闭时直接返回，不做任何计算 —— 这是「默认关闭零开销」的实现点。</p>
     *
     * <p>与 {@link #observe} 的关系：两者共用同一次求解。{@code observe} 会先看
     * 快照里有没有解，有就复用、没有才自己算 —— 所以「观测开 + 回写开」时
     * <b>只求解一次</b>，不会翻倍开销。</p>
     */
    public static void solveAndStash(int[] terrainY,
                                     GroundBanSources.Result bans,
                                     SurfaceGradeLimits gradeLimits,
                                     RoadGenerationConfig cfg,
                                     List<BlockPos> centers) {
        if (!writebackEnabled()) return;
        // ★ 入口先清、成功才写 —— 这是「本次求解无效」与「本次没求解」的统一语义。
        //
        // 为什么必须在**所有**早退分支之前清：`computeTargetY` 在一次 generateRoad 里
        // 会被调用多次（主路径 4 次 + tryRiverRepair + settleProfile）。若某一次
        // 因为地形为空 / DP 无解 / 抛异常而没产出新解，却把上一轮的快照留着，
        // 后面 `consumeStash` 就会把**上一次地形（甚至 repair 之前）的解**
        // 当成这一次的解去合并 spans。
        //
        // 第一版就是在 `solved.isEmpty()` 里单独写了 `STASH.remove()`，
        // 结果 `terrainY.length == 0` 的早退分支根本没走到 —— 被
        // `无解时必须清掉上一轮留下的快照` 这个用例当场抓出来。
        STASH.remove();
        if (terrainY == null || terrainY.length == 0 || centers == null) return;
        try {
            int n = terrainY.length;
            boolean[] groundBanned = bans == null ? new boolean[n] : bans.banned();
            var solved = ModeDpSolver.solve(terrainY, slopeStepPerStation(cfg), settingsFor(gradeLimits), groundBanned);
            if (solved.isEmpty()) {
                // 无解 ⇒ 必须**主动清掉旧快照**。
                //
                // 踩过的坑：第一版只写了 `return`，注释还说「不留快照」。但
                // `computeTargetY` 在一次 generateRoad 里会被调用多次
                // （Road.java 的主路径 4 次 + tryRiverRepair + settleProfile），
                // 若前一次留了快照、这一次无解而只是 return，那么旧快照会一直
                // 挂到后面被 `consumeStash` 取走 ⇒ **拿上一次地形（甚至是上一轮
                // repair 之前）的解去合并本轮的 spans**。清掉才是「本次无解」。
                return;
            }
            STASH.set(new Stash(solved.get(), centers, n, terrainY));
            STASH_SOLVED.incrementAndGet();
        } catch (Throwable t) {
            // 求解失败绝不能影响生产：咽掉并记一条，快照保持为空。
            LOGGER.warn("[SPAN-WB] solve failed (不影响生产，按旧判定走): {}", t.toString());
        }
    }

    /** 开场清理：防止上一条路的快照残留到这一条。 */
    public static void clearStash() {
        if (!writebackEnabled()) return;
        STASH.remove();
    }

    /**
     * 取用并清除快照。一条路最多成功消费一次。
     *
     * @return 快照；没有则 {@code null}（同时把 {@code stashMissed} 计一次）
     */
    public static Stash consumeStash() {
        if (!writebackEnabled()) return null;
        Stash s = STASH.get();
        STASH.remove();
        if (s == null) {
            STASH_MISSED.incrementAndGet();
            return null;
        }
        STASH_CONSUMED.incrementAndGet();
        return s;
    }

    /** 记一次「回写真的应用了」。由消费端调用。 */
    public static void noteWritebackApplied() {
        WRITEBACK_APPLIED.incrementAndGet();
    }

    /** 把坡度口径折算成「每站最大高差」，与 {@link #observe} 内部同口径。 */
    static int slopeStepPerStation(RoadGenerationConfig cfg) {
        int perTwo = Math.max(1, cfg == null ? 2 : cfg.maxSlopeStepPerTwoSegments());
        return Math.max(1, (perTwo + 1) / 2);
    }

    /** 回写健康度快照，供日志与测试读取。 */
    public static String writebackStats() {
        return "enabled=" + writebackEnabled()
                + " solved=" + STASH_SOLVED.get()
                + " consumed=" + STASH_CONSUMED.get()
                + " missed=" + STASH_MISSED.get()
                + " applied=" + WRITEBACK_APPLIED.get();
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
            // bans 为 null 时上面那行会造一个全 false 掩码，这里也必须跟着兜底 ——
            // 否则 NPE 被外层 catch(Throwable) 吞掉，整条观测静默失效（只剩一条 warn）。
            int banned = bans == null ? 0 : bans.bannedStations();
            // 旧判定的跨水站数：只作对照，用来量化「独立来源与旧判定差多少」。
            int legacyCrossing = 0;
            if (isCrossing != null) {
                for (int i = 0; i < Math.min(n, isCrossing.length); i++) {
                    if (isCrossing[i]) legacyCrossing++;
                }
            }

            int perTwo = Math.max(1, cfg == null ? 2 : cfg.maxSlopeStepPerTwoSegments());
            // 一阶上限与 DP 剖面求解同口径：把「每两站 N 格」折算成每站（向上取整）。
            int maxStep = slopeStepPerStation(cfg);

            // ★ 回写若已经解过（`solveAndStash`），这里直接复用 ——
            //   「观测开 + 回写开」是最可能同时存在的组合，不能让它求解两遍。
            //   注意只在 n 与 centers 一致时才复用，避免错配。
            Stash shared = STASH.get();
            var solved = (shared != null && shared.solution() != null
                    && shared.stations() == n && shared.centers() == centers
                    && shared.terrainY() == terrainY)
                    ? java.util.Optional.of(shared.solution())
                    : ModeDpSolver.solve(terrainY, maxStep, settingsFor(gradeLimits), groundBanned);
            if (solved.isEmpty()) {
                LOGGER.info("【桥隧模式DP无可行解的对照证据】 " + "[SPAN-AB] tag={} dp=infeasible stations={} banned={}"
                                + "(water={} structure={} road={} snapshot={}) legacyCrossing={} legacy={}",
                        tag, n, banned, (bans == null ? 0 : bans.waterStations()), (bans == null ? 0 : bans.structureStations()),
                        (bans == null ? 0 : bans.roadStations()),
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
                            + "(water={} structure={} road={} snapshot={}) legacyCrossing={} perTwo={} "
                            + "legacy[bridge={}站/{}座 tunnel={}站/{}条] "
                            + "dp[bridge={}站/{}座 tunnel={}站/{}条] "
                            + "差异站={} dpCost={}",
                    tag, n, banned,
                    (bans == null ? 0 : bans.waterStations()), (bans == null ? 0 : bans.structureStations()),
                    (bans == null ? 0 : bans.roadStations()),
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
            // ★ null 必须当「无模式」跳过，不能落到下面的跨度收集里。
            //   踩过：null == GROUND 为 false ⇒ 进入收集分支，
            //   而 `m == BRIDGE` 也为 false ⇒ span 类型被定成 TUNNEL
            //   ⇒ 凭空多出一条假隧道。DP 数组理论上不该有 null，
            //   但这条路径是回写生产跨度的入口，宁可多一道防御。
            if (m == null || m == ModeDpSolver.Mode.GROUND) {
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

    /**
     * 把 DP 判出的桥/隧道段合并进旧跨度列表（2026-11-15 新增，批 2 回写的第一步）。
     *
     * <h2>为什么是「合并」而不是「替换」</h2>
     * <p>{@link ModeDpSolver.Mode} 只有 {@code GROUND / BRIDGE / TUNNEL} 三种，
     * 而 {@link SpanType} 有 <b>七种</b>：</p>
     * <pre>
     *   DP 能判 ──► BRIDGE / TUNNEL
     *   DP 判不了 ──► ROAD_CROSSING（路跨路）/ JUNCTION_ANCHOR / TUNNEL_JUNCTION
     *                NETWORK_REUSE（路网复用）/ PROTECTED_GAP（保护缺口）
     * </pre>
     * <p>后五种来自完全不同的判定逻辑（路网复用、保护缺口、路口锚点），
     * DP 既没有它们的输入也不知道它们的存在。所以回写必须<b>只动桥隧段</b>，
     * 其余原样保留 —— 否则就是拿「桥隧判断更准」去换「丢功能」，
     * 违反「旧版能力一个都不能丢」。</p>
     *
     * <h2>ROAD_CROSSING 为什么也算「保留」</h2>
     * <p>它字面上是桥的一种（路跨路要架桥），但携带的语义是「这里跨的是另一条路」，
     * 施工侧据此走路口接驳逻辑而不是普通过水桥。DP 的 modes 里没有这个区分
     * （它的 BRIDGE 只表示「地面不可用」），所以不能拿 DP 的 BRIDGE 覆盖它。</p>
     *
     * <h2>排序</h2>
     * <p>按 span 起点在 {@code centers} 里的索引升序输出，保证同输入同输出
     * （下游多处按顺序读取跨度，顺序不稳会引入难查的偶发差异）。
     * 起点找不到的排到最后，宁可顺序靠后也不丢跨度。</p>
     *
     * @param legacySpans 旧判定产出的跨度（可为 null）
     * @param modes       DP 判出的每站模式（可为 null ⇒ 退化为原样返回旧跨度）
     * @param centers     每站中心坐标
     * @param n           站数
     * @return 新的跨度列表；永不返回 null
     */
    public static List<RoadSpan> mergeByModes(List<RoadSpan> legacySpans,
                                              ModeDpSolver.Mode[] modes,
                                              List<BlockPos> centers, int n) {
        // ★ DP 没有结果时**一个跨度都不动** —— 包括旧的桥隧判定。
        //   这一点踩过：第一版只在这是「不加 DP 结果」，结果旧 BRIDGE 被当桥隧段丢掉，
        //   等于「DP 没算出来 ⇒ 桥没了」。正确语义是「DP 没结果 ⇒ 保持原样」。
        if (modes == null) {
            List<RoadSpan> copy = new ArrayList<>();
            if (legacySpans != null) {
                for (RoadSpan sp : legacySpans) {
                    if (sp != null && sp.type() != null
                            && sp.start() != null && sp.end() != null) {
                        copy.add(sp);
                    }
                }
            }
            return copy;
        }
        List<RoadSpan> out = new ArrayList<>();
        // ① 旧判定里**非桥隧**的跨度原样保留（ROAD_CROSSING / JUNCTION_ANCHOR /
        //    TUNNEL_JUNCTION / NETWORK_REUSE / PROTECTED_GAP）
        if (legacySpans != null) {
            for (RoadSpan sp : legacySpans) {
                if (sp == null || sp.type() == null || sp.start() == null || sp.end() == null) continue;
                if (isModeSpan(sp.type())) continue;   // 桥隧段交给 DP 重判
                out.add(sp);
            }
        }
        // ② 加上 DP 判出的桥隧段
        out.addAll(spansFrom(modes, centers, n));
        // ③ 按起点站索引排序，保证确定性
        out.sort(java.util.Comparator.comparingInt(sp -> {
            int idx = indexOf(sp.start(), centers);
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));
        return out;
    }

    /** 这两种跨度由 DP 负责重判，其余类型一律保留。 */
    private static boolean isModeSpan(SpanType type) {
        return type == SpanType.BRIDGE || type == SpanType.TUNNEL;
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