/* 文件职责：涵洞几何判据的唯一真值来源（常量 + 纯函数），与施工流程解耦以便单测。 */
package net.shiroha233.roadweaver.pathlogic.grade;

import net.shiroha233.roadweaver.config.ConfigService;

/**
 * 涵洞几何判据：<b>常量与纯函数的唯一真值来源</b>。
 *
 * <p>本文件<b>不属于</b>待删（批1）范围：它是 2026-11-15 从
 * {@link RoadConstructionSafety} 抽出的<b>新</b>判据类，与该文件同属
 * 批 1 的「保留输入」（KEEP_INPUT），不删。台账登记见 {@code REFACTOR.md}
 * 的 {@code LEDGER|1} 行。</p>
 *
 * <h2>为什么单独成类</h2>
 * <p>这些判据原本散在 {@link RoadConstructionSafety} 里（那个类已经 950+ 行，
 * 逼近千行硬限制）。2026-11-15 修「整段采样」缺陷时又加了约 90 行，
 * 直接把文件顶过 1000 行。抽出来的额外收益：</p>
 * <ol>
 *   <li>纯函数不需要 {@code WorldGenLevel} 就能单测；</li>
 *   <li>「涵洞算不算得成」这一条职责从此有独立的名字，不再淹没在 1000 行里；</li>
 *   <li>规划期要补同口径判据时（见下），有明确的落点。</li>
 * </ol>
 *
 * <h2>三条判据的现状（2026-11-15）</h2>
 * <table border="1">
 *   <caption>规划期 vs 施工期</caption>
 *   <tr><th>判据</th><th>规划期</th><th>施工期</th><th>状态</th></tr>
 *   <tr><td>站数</td><td>{@code culvertMaxStations}（默认 6）</td>
 *       <td>{@code maxSegments()}（读配置，兜底 6）</td><td>✅ 已同源</td></tr>
 *   <tr><td>水深</td><td>{@code culvertMaxWaterDepth}（默认 2，可配 1..4）</td>
 *       <td>{@code maxWaterDepth()}（读配置，兜底 2）</td><td>✅ 已同源</td></tr>
 *   <tr><td>净空</td><td><b>没有这条判据</b></td>
 *       <td>{@link #CULVERT_MAX_OVERHEAD} = 6</td>
 *       <td><b>规划期缺失</b></td></tr>
 * </table>
 *
 * <h2>★ 站数从 2 改成 6 的由来（2026-11-15）</h2>
 * <p>{@code isShallowCulvertCrossing} 有<b>两个调用点</b>，参数完全不同：</p>
 * <ol>
 *   <li>{@code prepareCulvertDeck} 传单站 {@code (segmentIndex, segmentIndex)}
 *       ⇒ {@code end-start+1 = 1}，站数上限恒不生效；</li>
 *   <li><b>{@code promoteRuntimeBridges} 传完整站段 {@code (start, end)}</b>
 *       ⇒ 站数上限在这里才真正起作用。</li>
 * </ol>
 * <p>于是 3..6 站的窄浅水会出现：<b>规划期</b> {@code CulvertPlanner} 按
 * {@code culvertMaxStations=6} 判成涵洞、把甲板写进 {@code targetY}；
 * <b>施工期</b> {@code promoteRuntimeBridges} 按旧值 2 判「不是浅涵洞」
 * ⇒ {@code isBridge[i]=true} ⇒ 走桥体建造分支 ⇒ {@code prepareCulvertDeck}
 * 根本不会被调用 ⇒ <b>最终架桥，不是涵洞</b>（多建一座本不该建的桥 +
 * 规划期的甲板高度计算白做）。改成 6 后「规划判涵洞 ⇒ 施工就做涵洞」。</p>
 *
 * <p>详见 {@code docs/archive/CULVERT-THRESHOLD-ANALYSIS-20261115.md}。</p>
 */
public final class CulvertJudge {

    /** 水道横截面采样半宽：沿垂直于路线的方向左右各取这么多格。 */
    public static final int WATERCOURSE_HALF_WIDTH = 3;

    /** 配置缺失/不可读时的兜底：水深上限（与 {@code RoadAppearanceConfig} 出厂默认一致）。 */
    static final int DEFAULT_MAX_WATER_DEPTH = 2;

    /** 配置缺失/不可读时的兜底：站数上限（与 {@code RoadAppearanceConfig} 出厂默认一致）。 */
    static final int DEFAULT_MAX_SEGMENTS = 6;

    /**
     * 水深上限的夹取区间 —— <b>与 {@code RoadAppearanceConfig.setCulvertMaxWaterDepth} 同口径</b>。
     *
     * <p>2026-11-15：原来这两组界值是 {@code clamp(..., 1, 4)} 里的**字面量**，
     * 于是「施工期夹到 4」和「配置侧夹到 4」是两份独立写死的数字，
     * 谁改一处就会与另一处漂移（历史上 UI 声明 0..64、下游只认到 16 就是这么来的）。
     * 提成具名常量后，测试可以断言「施工期可读值必须落在配置侧可达范围内」。</p>
     */
    static final int DEPTH_MIN = 1;
    static final int DEPTH_MAX = 4;

    /** 站数上限的夹取区间 —— 与 {@code RoadAppearanceConfig.setCulvertMaxStations} 同口径。 */
    static final int SEGMENTS_MIN = 1;
    static final int SEGMENTS_MAX = 16;

    /**
     * 涵洞几何允许的最大水深 —— <b>读配置 {@code culvertMaxWaterDepth}</b>。
     *
     * <h2>★ 2026-11-15 从硬编码改为读配置</h2>
     * <p>原先是硬编码 2，而规划期 {@code culvertMaxWaterDepth} <b>可配 1..4</b>
     * ⇒ 用户配成 3/4 时，规划期认得、施工期只认 2，于是
     * <b>又产生「规划判涵洞、施工架桥」</b>（与站数那条同形）。</p>
     *
     * <p>现在两处都读同一个配置键，夹取范围统一为 {@code [1,4]}（规划期口径）。
     * 配置不可读时（单测 / 启动早期）退回 {@link #DEFAULT_MAX_WATER_DEPTH}。</p>
     */
    public static int maxWaterDepth() {
        try {
            return clamp(ConfigService.get().roadAppearance().culvertMaxWaterDepth(), DEPTH_MIN, DEPTH_MAX);
        } catch (Throwable ignored) {
            return DEFAULT_MAX_WATER_DEPTH;
        }
    }

    /**
     * 涵洞段允许的最大站数 —— <b>读配置 {@code culvertMaxStations}</b>。
     *
     * <p>见类注释「站数从 2 改成 6 的由来」。夹取范围 {@code [1,16]}，
     * 与 {@code RoadAppearanceConfig.setCulvertMaxStations} 和规划期
     * {@code LocalRouteSearch} 完全一致（2026-11-15 统一）。</p>
     */
    public static int maxSegments() {
        try {
            return clamp(ConfigService.get().roadAppearance().culvertMaxStations(), SEGMENTS_MIN, SEGMENTS_MAX);
        } catch (Throwable ignored) {
            return DEFAULT_MAX_SEGMENTS;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 涵洞几何允许的最大净空。
     *
     * <p>⚠️ <b>规划期根本没有这条判据</b>，这里等于单向收紧。
     * 正确的修法是<b>给规划期补上</b>，不是放宽这里 —— 净空越大说明路面被埋得越深，
     * 涵洞越难做，收紧是对的。</p>
     */
    public static final int CULVERT_MAX_OVERHEAD = 6;

    private CulvertJudge() {
    }

    /**
     * 单段（可能只是一站）是否满足涵洞几何。
     *
     * @param segmentCount 站数
     * @param waterDepth   水深（&gt;0 才算有水）
     * @param overhead     净空（路面之上到水面的厚度）
     */
    static boolean isCulvertGeometry(int segmentCount, int waterDepth, int overhead) {
        return segmentCount > 0 && segmentCount <= maxSegments()
                && waterDepth > 0 && waterDepth <= maxWaterDepth()
                && overhead >= 0 && overhead <= CULVERT_MAX_OVERHEAD;
    }

    /**
     * 纯函数：给定<b>每一站</b>的水深与净空，判整段是否够格做涵洞。
     *
     * <h2>★ 2026-11-15 修的真缺陷：原实现只在首站采样</h2>
     * <p>原 {@code isShallowCulvertCrossing} 只取 {@code centers.get(start)} 做横截面采样，
     * 然后拿「首站的水深与净空」去判「整段 3..6 站」。当时上限是 2、最多漏 1 站，
     * 影响有限；但上限提到 6 之后 <b>最多会漏 5 站</b> —— 等于修好一个不一致、
     * 又把另一个缺陷放大 5 倍。</p>
     *
     * <p>真实后果（首站浅、中后段深的水道）：这里判成「浅涵洞」⇒
     * {@code promoteRuntimeBridges} 不提升为桥 ⇒ 走铺路分支 ⇒ 到
     * {@code prepareCulvertDeck} 逐站复检时深的那几站直接返回
     * ⇒ <b>既不架桥也不抬甲板</b>，那段路就压在深水上。</p>
     *
     * <h2>为什么是「逐站都必须满足」而不是「取最大值再判一次」</h2>
     * <p>水深有<b>两条</b>边界：{@code >0}（得有水）与 {@code <=上限}（不能太深）。
     * 「取最大值」只能守住上界 —— 中间某站水深为 0 会被别的站的大值盖过去，
     * 于是「中间有一段其实没水」的伪水道也会被判成可做涵洞。
     * 逐站套同一个判据则同时守住两条边界，语义也最直白：<b>整段合格 = 每一站都合格</b>。</p>
     *
     * @param stationCount       整段站数
     * @param depthPerStation    每站最大水深（长度须 ≥ stationCount）
     * @param overheadPerStation 每站最大净空（长度须 ≥ stationCount）
     * @return 整段是否够格做涵洞；任何输入不合法都保守返回 {@code false}
     */
    static boolean isCulvertRun(int stationCount, int[] depthPerStation, int[] overheadPerStation) {
        if (stationCount <= 0 || depthPerStation == null || overheadPerStation == null) return false;
        if (depthPerStation.length < stationCount || overheadPerStation.length < stationCount) return false;
        for (int i = 0; i < stationCount; i++) {
            if (!isCulvertGeometry(stationCount, depthPerStation[i], overheadPerStation[i])) {
                return false;
            }
        }
        return true;
    }
}