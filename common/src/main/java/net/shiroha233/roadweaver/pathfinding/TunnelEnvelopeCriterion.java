/* 文件职责：隧道候选判据（0.9.063 重写）——独立山体包络 + 覆土 + 按坡度算的洞口引道 + 代价平衡。 */
package net.shiroha233.roadweaver.pathfinding;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

import java.util.Arrays;

/**
 * 隧道候选判据：**独立山体包络**（0.9.063 重写，替代 0.9.010 那套「中值基准」）。
 *
 * <h2>为什么重写</h2>
 * <p>0.9.062 的生产判据是「覆土 = 地形高度 − 规划路面 Y」，而规划路面 Y 本身会沿着山包往上爬：
 * 突起越窄，纵断面被平滑得越狠（宽 &lt; 17 站基本被自己抹平）；突起越宽，高地自己就变成了
 * 基准（覆土 ≈ 0）。再叠上 {@code tunnelCoreOverburden=8} 与 {@code RoadTunnelBuilder} 每端
 * {@code PORTAL_TRANSITION_SEGMENTS=14} 站的开挖段（短于此长度的洞身整段都算洞口，等于没有洞身），
 * 命中概率趋近 0 —— 0.9.010 实测 {@code bumps=0 / promMax=n/a}。</p>
 *
 * <h2>新判据（三层，全部是纯函数）</h2>
 * <ol>
 *   <li><b>下包络</b>：{@code envelope[i]} = 沿线地形高度在 {@code [i±24]} 站窗口里的
 *       <b>低分位</b>（10%）。它是「山脚」的估计，<b>不依赖规划路面</b>，所以路面爬上去也不会
 *       把基准带上去；也因为是「取低」而不是「取中」，宽 6~48 站的山都还会被判出突起度
 *       （中值基准在宽度超过半个窗口时会把高地自己当基准）。</li>
 *   <li><b>突起度</b>：{@code prominence[i] = max(0, terrain[i] − envelope[i])}。</li>
 *   <li><b>覆土</b>：{@code overburden = prominence − targetBurial}，{@code targetBurial} 沿用
 *       {@code RoadTunnelBuilder.minOverburden()} = 7（洞顶净空 5 + 顶板 1 + 1 格岩石）。</li>
 *   <li><b>洞口引道</b>：不再用固定的 14 站，而是<b>按实际坡度</b>算 —— 从两端数「突起度还不到
 *       埋深 7 格」的站数（这段bore 上方没岩石，只能明挖）。坡面越陡，突起度越过 7 越快，引道越短。</li>
 * </ol>
 *
 * <h2>阈值来源（报告里要区分「推的」与「拍的」）</h2>
 * <ul>
 *   <li><b>推</b>（从既有常量/几何关系推出来）：{@link #TARGET_BURIAL_BLOCKS}（= roofOffset+1）、
 *       {@link #MIN_PROMINENCE_BLOCKS}（= MAX_SURFACE_CUT−1）、{@link #ENVELOPE_HALF_WINDOW_STATIONS}
 *       的下界（要判出宽 30 站的山，半窗必须 &gt; 15）、{@link #STATION_SPACING_BLOCKS}
 *       （= DEFAULT_PATH_GRID_STEP）、{@link #PORTAL_MAX_GRADE}（= DEFAULT_SOFT_GRADE_LIMIT）。</li>
 *   <li><b>拍</b>（本版定的经验值）：{@link #ENVELOPE_HALF_WINDOW_STATIONS}=24（在 15 下界上留余量）、
 *       {@link #ENVELOPE_QUANTILE}=0.10、{@link #MIN_OVERBURDEN_MARGIN_BLOCKS}=1、
 *       {@link #TUNNEL_BLOCK_COST_FACTOR}=5、{@link #PORTAL_BLOCK_COST_FACTOR}=2。</li>
 * </ul>
 *
 * <h2>本版约定（重要）</h2>
 * <p>代价比较（{@code [ROUTE-CHOICE]}）<b>只打数、不参与选址</b>：{@code elected} 由上面三条几何门
 * 决定。因此 {@code elected=yes} 与 {@code why=cost-loses-to-detour} 可以同时出现 —— 含义是
 * 「隧道按几何判据建了，但代价比较说绕行/硬爬更便宜，本版刻意不据此改选择」。</p>
 */
public final class TunnelEnvelopeCriterion {

    // ── 阈值常量 ───────────────────────────────────────────────────────────

    /**
     * 包络半窗（站）。**推导 + 余量**：要判出「宽 30 站」的对称山，峰顶的窗口必须能伸出山体两侧
     * （半窗 &gt; 15），否则窗口整段都在山里、包络跟着抬高。取 24 = 15 × 1.6。
     * 换算成格：24 站 × 8 格 = 192 格。
     */
    public static final int ENVELOPE_HALF_WINDOW_STATIONS = 24;

    /** 包络取的分位。**拍**：取低分位而不是硬最小值 —— 单格尖顶/单格深坑不该决定整段基准。 */
    public static final double ENVELOPE_QUANTILE = 0.10D;

    /**
     * 突起度种子：低于它的起伏连候选都不建立（避免每段丘陵都刷一行日志）。**拍**：2 格。
     */
    public static final int SEED_PROMINENCE_BLOCKS = 2;

    /**
     * 候选的最小突起度（取段内中位数）。**推**：{@code RoadGradePlanner.MAX_SURFACE_CUT(5) − 1 = 4} ——
     * 4 格以内的起伏靠普通挖方就能过去，不值得打隧道。
     *
     * <p><b>0.9.081 实测复核：维持 4。</b>曾试抬到 8 来砍掉「小丘陵挖个洞」的碎片隧道，
     * 但 {@code TunnelProminencePureFunctionTest} 证明它会连「中间高 12 格、两侧坡缓」的
     * 宽山一起判掉（中位数按整段算）—— 破坏面远大于收益。碎片化改用
     * {@link #MIN_SEALED_CORE_STATIONS} 处理，那个才是「洞身太短」的精确判据。</p>
     */
    public static final int MIN_PROMINENCE_BLOCKS = 4;

    /**
     * 目标埋深（格）。**推**：{@code RoadTunnelBuilder.minOverburden()} = {@code roofOffset()+1}
     * = 净空 5 + 顶板 1 + 1 格岩石 = 7。沿用既有常量，不新拍。
     */
    public static final int TARGET_BURIAL_BLOCKS = 7;

    /**
     * 覆土余量（格）。**推**：{@code 覆土 = 突起度 − 埋深}，要求 &gt; 0 ⇒ 山体高度必须超过 7 格。
     * 取 1 还让「覆土不足」与「突起度不足」两个分支的区间不重叠（后者 &lt; 4）。
     */
    public static final int MIN_OVERBURDEN_MARGIN_BLOCKS = 1;

    /**
     * 洞身（突起度 ≥ 埋深的连续站）最少站数。**推**：{@code PathSpanExtractor.TUNNEL_RUN_MIN_LENGTH}
     * = 3 —— 短于 3 站的洞身连洞口都摆不开，{@code appendTunnelWithSurfaceMouths} 本来也会丢掉它。
     *
     * <p><b>0.9.081 实测复核：维持 3，不许抬。</b>曾试抬到 6 来砍「一个小包挖个洞」的短隧道，
     * 被两条测试直接否决：{@code TunnelProminencePureFunctionTest.narrowEightStationRidgeIsDetected}
     * 钉住「宽 8 站、高 11 的窄脊**必须**判出隧道」，而同一文件的负证据
     * {@code legacyPlannedProfileRuleErasesTheNarrowRidge} 证明「窄脊判不出」= 整道脊被
     * 明挖削平 —— 那比多挖几个短隧道坏得多。真机那 15% 的 &lt;10 站隧道属于这一类，
     * 是正确行为，不是碎片。</p>
     */
    public static final int MIN_SEALED_CORE_STATIONS = 3;

    /**
     * 单条隧道的最大站数。**0.9.081 新增**：256 站 × 8 格 = 2048 格（2 公里）。
     *
     * <p>真机取证：2026-11-11 那局出现过 <b>1012 站（8128 格 ≈ 8 公里）</b>的单条隧道，
     * 占所在道路 2276 站的 44%。根因是 {@link #runs(int[], int)} 只在「突起度局部低谷」处切分 ——
     * 连绵山区里上千站没有低谷，就整段连成一条。现实里没有 8 公里的连续隧道，
     * 必须分段并在中间露天（见 {@link #TUNNEL_WINDOW_STATIONS}）。</p>
     */
    public static final int MAX_TUNNEL_RUN_STATIONS = 256;

    /**
     * 强制切分时，两段隧道之间留出的露天（天窗）站数。**0.9.081 新增**：8 站 = 64 格。
     *
     * <p>不留天窗直接首尾相接，视觉上仍是一条无限长的洞；留 64 格露天段，
     * 玩家能看见两次进出，地图上也读得出「两座山两条隧道」。</p>
     */
    public static final int TUNNEL_WINDOW_STATIONS = 8;

    /** 名义站距（格）。**推**：{@code RoadConstants.DEFAULT_PATH_GRID_STEP} = 8（粗规划每一步的距离）。 */
    public static final double STATION_SPACING_BLOCKS = 8.0D;

    /**
     * 洞口引道允许的最大坡度。**推**：{@code RoadConstants.DEFAULT_SOFT_GRADE_LIMIT} = 0.08 ——
     * 再陡 A* 就要开始付坡罚，那段「引道」根本不会被选出来。注意这里是<b>常量</b>：判据不读配置，
     * 玩家改 {@code softGradeLimit} 时本判据不同步（已知简化）。
     */
    public static final double PORTAL_MAX_GRADE = 0.08D;

    /**
     * 每格洞身相对「1 格平路」的代价倍数。**拍**：5 —— 要挖、衬砌、铺灯、防水，且洞内不能借地形。
     */
    public static final double TUNNEL_BLOCK_COST_FACTOR = 5.0D;

    /** 每格洞口明挖相对「1 格平路」的代价倍数。**拍**：2 —— 只挖不衬砌。 */
    public static final double PORTAL_BLOCK_COST_FACTOR = 2.0D;

    /** 判据结果码。 */
    public enum Why {
        NO_PROMINENCE("no-prominence"),
        OVERBURDEN_LOW("overburden-low"),
        PORTAL_TOO_LONG("portal-too-long"),
        COST_LOSES_TO_DETOUR("cost-loses-to-detour"),
        ELECTED("elected");

        private final String logValue;

        Why(String logValue) {
            this.logValue = logValue;
        }

        /** 日志里用的短名（连字符），与 {@code [TUNNEL-CANDIDATE] why=} 的取值一一对应。 */
        public String logValue() {
            return logValue;
        }
    }

    /** 代价比较的三个选项（{@code [ROUTE-CHOICE] chosen=} 的取值）。 */
    public enum Choice {
        SWITCHBACK("switchback"),
        TUNNEL("tunnel"),
        CLIMB("climb");

        private final String logValue;

        Choice(String logValue) {
            this.logValue = logValue;
        }

        public String logValue() {
            return logValue;
        }
    }

    /** 判据参数快照（纯值，可离线构造）。 */
    public record Params(int minProminenceBlocks,
                         int targetBurialBlocks,
                         int minOverburdenMarginBlocks,
                         int envelopeHalfWindowStations,
                         double envelopeQuantile,
                         double seedProminenceBlocks,
                         double stationSpacingBlocks,
                         double portalMaxGrade) {

        /** 出厂参数：全部来自本类的阈值常量（{@link RoadConstants} 不参与，判据不读配置）。 */
        public static Params defaults() {
            return new Params(MIN_PROMINENCE_BLOCKS, TARGET_BURIAL_BLOCKS, MIN_OVERBURDEN_MARGIN_BLOCKS,
                    ENVELOPE_HALF_WINDOW_STATIONS, ENVELOPE_QUANTILE, SEED_PROMINENCE_BLOCKS,
                    STATION_SPACING_BLOCKS, PORTAL_MAX_GRADE);
        }
    }

    /**
     * 代价权重（与 A* 同量纲：1 格正交平路 = {@code orthoStepCost / step}）。
     *
     * @param roadPerBlock        1 格平路的 A* 代价（默认 1.0/8 = 0.125）
     * @param softGradePenalty    soft 档坡罚单价（格⁻¹）
     * @param hardGradePenalty    hard 档坡罚单价（格⁻¹）
     * @param softGradeLimit      soft 免罚坡度
     * @param hardGradeLimit      hard 免罚坡度
     * @param tunnelBlockFactor   每格洞身 × 平路
     * @param portalBlockFactor   每格明挖 × 平路
     */
    public record Costs(double roadPerBlock,
                        double softGradePenalty,
                        double hardGradePenalty,
                        double softGradeLimit,
                        double hardGradeLimit,
                        double tunnelBlockFactor,
                        double portalBlockFactor) {

        /** 出厂权重：单价取 {@link RoadConstants} 的新标定默认值，倍数取本类的「拍」值。 */
        public static Costs defaults() {
            double step = RoadConstants.DEFAULT_PATH_GRID_STEP;
            return new Costs(RoadConstants.DEFAULT_ORTHO_STEP_COST / step,
                    RoadConstants.DEFAULT_SOFT_GRADE_PENALTY,
                    RoadConstants.DEFAULT_HARD_GRADE_PENALTY,
                    RoadConstants.DEFAULT_SOFT_GRADE_LIMIT,
                    RoadConstants.DEFAULT_HARD_GRADE_LIMIT,
                    TUNNEL_BLOCK_COST_FACTOR,
                    PORTAL_BLOCK_COST_FACTOR);
        }
    }

    /** 一个候选段（{@code [start, end]} 含端点，单位=站）。 */
    public record Candidate(int start,
                            int end,
                            int stations,
                            int promP50,
                            int promMax,
                            int overburdenMax,
                            int portalLen,
                            boolean elected,
                            Why why,
                            double tunnelCost,
                            double switchbackCost,
                            double climbCost,
                            Choice chosen) {

        /** {@code [TUNNEL-CANDIDATE]} 的完整一行（不含日志级别/time 前缀）。 */
        public String logLine() {
            return "[TUNNEL-CANDIDATE] seg=" + start + "-" + end
                    + " stations=" + stations
                    + " promP50=" + promP50
                    + " promMax=" + promMax
                    + " overburdenMax=" + overburdenMax
                    + " portalLen=" + portalLen
                    + " elected=" + (elected ? "yes" : "no")
                    + " why=" + why.logValue();
        }

        /** {@code [ROUTE-CHOICE]} 的完整一行（本版只打印，不参与选址）。 */
        public String routeChoiceLine() {
            return "[ROUTE-CHOICE] connector=" + start + "-" + end
                    + " switchbackCost=" + fmt(switchbackCost)
                    + " tunnelCost=" + fmt(tunnelCost)
                    + " climbCost=" + fmt(climbCost)
                    + " chosen=" + chosen.logValue();
        }

        private static String fmt(double value) {
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }
    }

    private TunnelEnvelopeCriterion() {
    }

    // ── 纯函数 ─────────────────────────────────────────────────────────────

    /** 默认参数下的下包络。 */
    public static int[] lowerEnvelope(int[] terrain) {
        return lowerEnvelope(terrain, ENVELOPE_HALF_WINDOW_STATIONS, ENVELOPE_QUANTILE);
    }

    /**
     * 沿线地形高度的<b>下包络</b>：{@code envelope[i]} = {@code terrain[i±halfWindow]} 里的
     * {@code quantile} 分位（默认 10%）。窗口在路线两端会被截断（不做环形回绕）——
     * 端点本来也打不了隧道。
     */
    public static int[] lowerEnvelope(int[] terrain, int halfWindow, double quantile) {
        int n = terrain == null ? 0 : terrain.length;
        int[] envelope = new int[n];
        if (n == 0) {
            return envelope;
        }
        int window = Math.max(1, halfWindow);
        double q = Math.max(0.0D, Math.min(1.0D, quantile));
        int[] scratch = new int[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - window);
            int hi = Math.min(n - 1, i + window);
            int count = 0;
            for (int j = lo; j <= hi; j++) {
                scratch[count++] = terrain[j];
            }
            Arrays.sort(scratch, 0, count);
            int index = (int) Math.floor(q * (count - 1));
            if (index < 0) index = 0;
            if (index >= count) index = count - 1;
            envelope[i] = scratch[index];
        }
        return envelope;
    }

    /** 默认参数下的突起度：{@code max(0, terrain − envelope)}。 */
    public static int[] prominence(int[] terrain) {
        return prominence(terrain, ENVELOPE_HALF_WINDOW_STATIONS, ENVELOPE_QUANTILE);
    }

    /** 突起度：{@code max(0, terrain[i] − envelope[i])}。谷地恒为 0。 */
    public static int[] prominence(int[] terrain, int halfWindow, double quantile) {
        int[] envelope = lowerEnvelope(terrain, halfWindow, quantile);
        int[] prominence = new int[envelope.length];
        for (int i = 0; i < envelope.length; i++) {
            prominence[i] = Math.max(0, terrain[i] - envelope[i]);
        }
        return prominence;
    }


    /**
     * 输出一段 run，必要时按 {@link #MAX_TUNNEL_RUN_STATIONS} 切分并留
     * {@link #TUNNEL_WINDOW_STATIONS} 站天窗。
     *
     * <p>{@code sizeRef[0]} 用来在方法间传递 {@code out} 的写入位置 —— 原地复用同一个
     * int 数组，避免为了一条 8 公里的 run 重新分配整个输出缓冲。</p>
     */
    private static int[] emitBoundedRun(int[] out, int[] sizeRef, int from, int to) {
        int size = sizeRef[0];
        int cursor = from;
        // 只有「切掉一段 + 天窗之后仍留得下最小洞身」才值得切。
        // 否则会为了多出的一两站硬切一刀，反而把队尾那几站吞掉（257 站切成 256 + 丢掉 1）。
        int worthSplitting = MAX_TUNNEL_RUN_STATIONS + TUNNEL_WINDOW_STATIONS + MIN_SEALED_CORE_STATIONS;
        while (to - cursor + 1 > worthSplitting) {
            out = ensureCapacity(out, size + 2);
            out[size++] = cursor;
            out[size++] = cursor + MAX_TUNNEL_RUN_STATIONS - 1;
            cursor += MAX_TUNNEL_RUN_STATIONS + TUNNEL_WINDOW_STATIONS;
        }
        if (cursor <= to) {
            out = ensureCapacity(out, size + 2);
            out[size++] = cursor;
            out[size++] = to;
        }
        sizeRef[0] = size;
        return out;
    }

    public static int[] runs(int[] prominence, int seedProminence) {
        if (prominence == null || prominence.length == 0) {
            return new int[0];
        }
        int seed = Math.max(1, seedProminence);
        int[] out = new int[8];
        int size = 0;
        int start = -1;
        for (int i = 0; i < prominence.length; i++) {
            boolean inRun = prominence[i] >= seed;
            if (inRun && start < 0) start = i;
            boolean closes = !inRun && start >= 0;
            boolean lastOpen = i == prominence.length - 1 && inRun && start >= 0;
            if (!closes && !lastOpen) continue;
            int end = closes ? i - 1 : i;
            int segmentStart = start;
            for (int j = start + 1; j < end; j++) {
                if (prominence[j] < prominence[j - 1] && prominence[j] < prominence[j + 1]) {
                    int[] sizeRef = new int[]{size};
                    out = emitBoundedRun(out, sizeRef, segmentStart, j);
                    size = sizeRef[0];
                    segmentStart = j + 1;
                }
            }
            int[] sizeRef = new int[]{size};
            out = emitBoundedRun(out, sizeRef, segmentStart, end);
            size = sizeRef[0];
            start = -1;
        }
        return Arrays.copyOf(out, size);
    }

    /** 容量不够就翻倍（{@code runs()} 里唯一的可变状态）。 */
    private static int[] ensureCapacity(int[] array, int needed) {
        return needed <= array.length ? array : Arrays.copyOf(array, Math.max(needed, array.length * 2));
    }

    /** 默认参数下评估一个突起段。 */
    public static Candidate evaluate(int[] terrain, int[] prominence, int start, int end) {
        return evaluate(terrain, prominence, start, end, Params.defaults(), Costs.defaults());
    }

    /**
     * 评估一个突起段（{@code [start, end]}，含端点）。
     *
     * <p>门的顺序：突起度 → 覆土 → 洞口引道 → 代价标签。前三条决定 {@code elected}，
     * 代价只决定 {@code why} 落到 {@code elected} 还是 {@code cost-loses-to-detour}。</p>
     *
     * @return 候选结果；输入为空或区间非法时返回 {@code null}
     */
    public static Candidate evaluate(int[] terrain,
                                     int[] prominence,
                                     int start,
                                     int end,
                                     Params params,
                                     Costs costs) {
        if (terrain == null || prominence == null) {
            return null;
        }
        int n = Math.min(terrain.length, prominence.length);
        if (n == 0) {
            return null;
        }
        Params p = params == null ? Params.defaults() : params;
        Costs c = costs == null ? Costs.defaults() : costs;
        int from = Math.max(0, Math.min(start, end));
        int to = Math.min(n - 1, Math.max(start, end));
        if (from > to) {
            return null;
        }
        int stations = to - from + 1;

        int[] sorted = new int[stations];
        System.arraycopy(prominence, from, sorted, 0, stations);
        Arrays.sort(sorted);
        int promMax = sorted[stations - 1];
        int promP50 = sorted[stations / 2];
        int overburdenMax = promMax - p.targetBurialBlocks();

        // 峰顶：取「最大突起度平台」的中点。整数地形在山顶天然有一段等高平台，
        // 取最左会把坡面站数（flank）压成 1，于是「这条山能不能按允许坡度开上去」永远判错。
        int peakFirst = from;
        int peakLast = from;
        for (int i = from; i <= to; i++) {
            if (prominence[i] > prominence[peakFirst]) {
                peakFirst = i;
                peakLast = i;
            } else if (prominence[i] == prominence[peakFirst]) {
                peakLast = i;
            }
        }
        int peak = (peakFirst + peakLast) / 2;
        // 洞口明挖段（两端各数「突起度还不到埋深」的站）：按实际坡度算，替代固定的 14 站
        int openLeft = 0;
        while (from + openLeft <= to && prominence[from + openLeft] < p.targetBurialBlocks()) openLeft++;
        int openRight = 0;
        while (to - openRight >= from && prominence[to - openRight] < p.targetBurialBlocks()) openRight++;
        int portalLen = Math.max(openLeft, openRight);

        // 按允许坡度爬完 promMax 需要几站；与山体实际提供的坡面站数比
        double blocksPerStation = p.portalMaxGrade() * p.stationSpacingBlocks();
        int requiredRampStations = blocksPerStation <= 0.0D
                ? Integer.MAX_VALUE
                : (int) Math.ceil(promMax / blocksPerStation);
        int flank = Math.min(peak - from + 1, to - peak + 1);

        boolean elected;
        Why why;
        if (promP50 < p.minProminenceBlocks()) {
            elected = false;
            why = Why.NO_PROMINENCE;
        } else if (overburdenMax < p.minOverburdenMarginBlocks()) {
            elected = false;
            why = Why.OVERBURDEN_LOW;
        } else if (requiredRampStations <= flank
                || stations - openLeft - openRight < MIN_SEALED_CORE_STATIONS) {
            // ①山体坡面本身就够长：按允许坡度直接开上去就行，整座山都会变成明挖引道；
            // ②突起度够埋深的连续站不足 3 站：洞身摆不开，挖出来还是明挖。
            elected = false;
            why = Why.PORTAL_TOO_LONG;
        } else {
            elected = true;
            why = Why.ELECTED;
        }

        // ── 代价平衡（只打数，不参与选址）──────────────────────────────
        double spacing = p.stationSpacingBlocks();
        double lengthBlocks = stations * spacing;
        double openBlocks = Math.min(lengthBlocks, (openLeft + openRight) * spacing);
        double sealedBlocks = Math.max(0.0D, lengthBlocks - openBlocks);
        double tunnelCost = sealedBlocks * c.tunnelBlockFactor() * c.roadPerBlock()
                + openBlocks * c.portalBlockFactor() * c.roadPerBlock();
        double switchbackBlocks = lengthBlocks + (2.0D * promMax) / Math.max(1.0E-6D, c.softGradeLimit());
        double switchbackCost = switchbackBlocks * c.roadPerBlock();
        // 硬爬：直接沿地表翻过去要付的坡罚。坡度必须按「峰顶到种子边界」这一段算 ——
        // 按整段平均会把下坡也算进分母，12 格高、30 站宽的山平均坡度才 0.06，罚额会被算成 0。
        double climbCost = lengthBlocks * c.roadPerBlock() + climbPenalty(promMax, flank, spacing, c);

        Choice chosen;
        if (tunnelCost <= switchbackCost && tunnelCost <= climbCost) {
            chosen = Choice.TUNNEL;
        } else if (switchbackCost <= climbCost) {
            chosen = Choice.SWITCHBACK;
        } else {
            chosen = Choice.CLIMB;
        }
        if (elected && chosen != Choice.TUNNEL) {
            why = Why.COST_LOSES_TO_DETOUR;
        }
        return new Candidate(from, to, stations, promP50, promMax, overburdenMax, portalLen,
                elected, why, tunnelCost, switchbackCost, climbCost, chosen);
    }

    /**
     * 直接把山「沿地表硬爬上去」要付的坡罚（A* 单位）。
     *
     * <p>每站抬升 {@code promMax / riseStations}（{@code riseStations} 取坡面站数，即峰顶到种子边界的
     * 最短一侧）；超 soft/hard 免罚额度的部分按单价结账，与
     * {@code PotentialFieldPathfinder#gradePenalty} 的「按长度收」完全同口径。</p>
     *
     * @param riseStations 爬完 {@code promMax} 实际用了几个站（必须 &gt; 0）
     */
    public static double climbPenalty(int promMax, int riseStations, double stationSpacingBlocks, Costs c) {
        if (riseStations <= 0 || promMax <= 0) {
            return 0.0D;
        }
        double risePerStation = promMax / (double) riseStations;
        double overSoft = Math.max(0.0D, risePerStation - c.softGradeLimit() * stationSpacingBlocks);
        double overHard = Math.max(0.0D, risePerStation - c.hardGradeLimit() * stationSpacingBlocks);
        double perStation = c.softGradePenalty() * overSoft + c.hardGradePenalty() * overHard;
        return perStation * riseStations;
    }
}
