/* 文件职责：保存路网规划半径、算法与地形采样模式配置。 */
package net.shiroha233.roadweaver.config.sub;

import net.shiroha233.roadweaver.config.SubConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 路网规划配置。
 */
public final class PlanningConfig implements SubConfig {

    /**
     * 规划算法枚举。
     *
     * <p>2026-11-13：旧规划器（KNN/Delaunay/RNG/MST/SnapBranch/AlphaModel）已全部删除，
     * 只剩 PIPELINE（测地距离 MST）一种。</p>
     */
    public enum PlanningAlgorithm {
        /**
         * 新三层内核的<b>测地距离</b>最小生成树（2026-11-13 新增，实验性）。
         *
         * <p>与 {@link #MST} 的区别只有一条，但很关键：MST 用<b>直线距离</b>建树，
         * 本项用<b>测地距离</b> —— 也就是「绕开山和水实际要走多远」。两座结构直线
         * 200 格、中间隔一道山脊时，直线距离看不出问题，测地距离会说实话。</p>
         *
         * <p>实现见 {@code PipelinePlanner}。它只接管<b>拓扑</b>，具体走线仍由
         * 后续 A* 算（{@code RoadPipeline} 顺带算出的弹性带曲线暂不消费 ——
         * 生成链没有接收预计算曲线的入口）。</p>
         */
        PIPELINE
    }
    /**
     * Terrain profile mode. Three distinct behaviors: AUTO is the balanced
     * baseline (legacy BALANCED saves load as AUTO), TUNNEL_FIRST keeps the
     * fill budget but tightens cut to 3 and also runs the 3D height-aware
     * search on mountain-heavy routes, BRIDGE_FIRST tightens fill to 1 and
     * lets water crossings ignore the bridge depth threshold, and LIGHT_CUT
     * tightens cut to 3 AND fill to 1 without changing any search behavior —
     * it is a grading-only profile, not a tunnel/bridge preference.
     */
    public enum TerrainProfileMode { AUTO, TUNNEL_FIRST, BRIDGE_FIRST, LIGHT_CUT }

    private int initialPlanRadiusChunks = RoadConstants.DEFAULT_INITIAL_PLAN_RADIUS_CHUNKS;
    private boolean dynamicPlanEnabled = true;
    private int dynamicPlanRadiusChunks = RoadConstants.DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS;
    private int dynamicPlanStrideChunks = RoadConstants.DEFAULT_DYNAMIC_PLAN_STRIDE_CHUNKS;
    private PlanningAlgorithm planningAlgorithm = PlanningAlgorithm.PIPELINE;
    private TerrainSamplingMode terrainSamplingMode = TerrainSamplingMode.COARSE_CORRIDOR;
    /** Radius of the conflict-centred circle used by local route repair. */
    private int localConflictRadiusBlocks = 100;
    /** Building detour radius expands outward from this base in fixed steps. */
    private int structureDetourRadiusStartBlocks = 50;
    private int structureDetourRadiusStepBlocks = 50;
    private int structureDetourRadiusMaxBlocks = 400;
    /** Lake detour radius expands outward from 10/20/50 up to this ceiling. */
    private int lakeDetourRadiusMaxBlocks = 100;
    /** A vertical change above this many blocks is treated as a tunnel candidate. */
    private int heightTunnelThresholdBlocks = 10;
    /** Local height-repair context length on each side of a conflict. */
    private int heightRepairContextBlocks = 50;
    /** Maximum tunnel depth the coarse/repair profile may lower a sustained hump. */
    private int tunnelDepthBlocks = 12;
    /** Runs the expensive 3D height-aware coarse candidate when needed. */
    private boolean coarseHeightAwareEnabled = true;
    private TerrainProfileMode terrainProfileMode = TerrainProfileMode.AUTO;
    /** Ordinary surface grading may cut at most this many blocks below terrain. */
    private int surfaceCutMaxBlocks = 5;
    /** Ordinary surface grading may fill at most this many blocks above terrain. */
    private int surfaceFillMaxBlocks = 2;
    /** A dry depression deeper than this many blocks is bridged instead of filled. */
    private int dryDepressionBridgeDepth = 6;
    /** T-23: 对沿途非端点村庄聚合盒执行分段裁剪(站点落为保护缺口)。默认启用。 */
    private boolean passThroughVillageClipping = true;
    /** Sustained overburden above this many blocks classifies a station as mountain. */
    private int mountainOverburdenBlocks = 8;
    /** A tunnel run must contain at least this many consecutive stations. */
    private int tunnelMinRun = 3;
    /** 0=straightest, 100=most winding mountain switchback search. */
    private int switchbackStrength = 40;
    /** Expanded-state ceiling for the expensive 3D coarse candidate. */
    private int heightAwareStateLimit = 260_000;
    /** Per-request cap on waiting for another thread's in-flight sampling job. */
    private int samplingInFlightTimeoutMs = 3_000;
    /** AUTO: mountain share at or above this fraction biases the route toward tunnels. */
    private double autoMountainThresholdFraction = 0.30D;
    /** AUTO: water share at or above this fraction biases the route toward bridges. */
    private double autoWaterThresholdFraction = 0.25D;
    /** Base A* state ceiling for one local grade-repair window. */
    private int gradeRepairWindowStateLimit = 60_000;
    /**
     * 0.9.001：单个局部修坡窗口的 A* 墙钟预算（毫秒，默认 20_000）。
     *
     * <p>只靠状态数上限时，跨 90 格深谷这类窗口每次都会把 90 000 次展开跑满、
     * 空转 95 秒以上再返回无解，而三波重试各跑一次（真机 {@code road_grade_search}
     * 卡顿的主因之一）。墙钟预算与状态数预算并存，谁先到谁生效；两者耗尽的
     * 语义完全一致：返回 null，走既有的「无解」兜底路径。</p>
     *
     * <p>默认 20 秒 = 修复前单窗口实测 95 秒的约 1/5：正常窗口远跑不到这个量，
     * 只有病态窗口会被截断到该上界。范围夹到 [1_000, 600_000] 毫秒。</p>
     */
    private int gradeRepairWindowBudgetMillis = 20_000;
    /**
     * 0.9.003：局部修坡 A* 的「长度上界可证明剪枝」开关（默认 <b>true</b>）。
     *
     * <p>调用方最终一定会用 {@code routeLength(candidate) <= max(routeLength(original) + K,
     * routeLength(original) * 3.0)} 判一次（K = 32 / 64 / 200，视修复类型而定）。打开后
     * 把同一上界前置进 A*：{@code 累计水平长度 + 到目标的直线距离 - 网格吸附余量 > 上界}
     * 的子节点直接不展开——这类解本来就会被上面那条判据拒掉，所以<b>不改变结果集</b>，
     * 只省掉注定被拒的分支。</p>
     *
     * <p>默认开：剪枝只依赖已算好的启发值，单次比较是纳秒级；唯一的"风险"是若将来有人
     * 放宽接受判据（加大 K 或倍率）而忘了同步这里，剪枝就会偏紧。真出现几何异常时可把
     * 本项改 false 立刻回到 0.9.002 的行为（无需回滚 jar）。</p>
     */
    private boolean gradeRepairLengthBoundPrune = true;

    /**
     * 0.9.009：过河入门费（A* 里每一步"踏进水里"的固定代价）。0 = 关闭该段。
     * 原实现是硬编码 1_200，与水深无关；改成「入门费 + 每湿步递增」后，长距离跨河才会真的变贵。
     */
    private double baseWaterPenalty = 200.0D;
    /** 0.9.009：在水里每多累计一步的代价（沿路径累计，见 wetRun）。150 ⇒ 3 站小溪 650、50 站大河 7700。 */
    private double perStationWaterPenalty = 150.0D;
    /**
     * 0.9.011：临河距离惩罚（每步），**只用来防「死贴河岸」**，不再用来把路赶出整个河谷。
     * 距河 &lt;= riverProximityRadius 格时线性衰减，在水里加满。0 = 完全禁用。
     *
     * <p>300 → 8 的依据（真机 0.9.010 一局）：原值 300/步 × 8 格半径比过水本身贵得多 ——
     * 绕 100 格陆地约 100，而沿溪走 10 步约 1900，过 20 格宽的河才 3200 ⇒ A* 学到的是
     * 「离所有水远一点」而不是「该过就过」，表现为规划阶段一律绕行、桥永远不被选中。
     * 8 只够让路停在离水 5 格外，不再扭曲整条走廊。</p>
     */
    private double riverProximityPenalty = 8.0D;
    /**
     * 0.9.011：临河惩罚作用半径（格）。8 → 5：用户要求「离河边 5 格可以，别死贴河边」。
     * 0 = 禁用。范围 0~32。
     */
    private int riverProximityRadius = 5;

    /**
     * 0.9.009：涵洞段的每步过水代价（默认 2.0）。当连续湿步数 <= roadAppearance.culvertMaxStations
     * 且该列水深 <= culvertMaxWaterDepth 时，过河总价 = 湿站数 × 本值（3 站 6、6 站 12），
     * 远低于绕行 20 格 ⇒ 「小溪直接过」；一旦超过 culvertMaxStations 或水深超限，
     * 总价立刻切成 baseWaterPenalty + perStationWaterPenalty × 湿站数（7 站 1250）⇒ 「大河才绕」。
     */
    private double culvertCostPerStation = 2.0D;
    /**
     * 0.9.011：陆路闸门倍数。&gt; 0 时先跑一趟「湿列禁行」的 A*，只要陆路绕行长度
     * &lt;= 直连距离 × 本值，就**一定走陆路、绝不架桥**；否则才放开水面重跑。
     * 这就是「只有实在绕行太远才架桥」的量化。默认 1.6。
     *
     * <p>0 = 关闭闸门，退化成 0.9.010 的单趟 A*，并且宽水价目回退到
     * {@code baseWaterPenalty} / {@code perStationWaterPenalty} —— 即完整回到旧行为。</p>
     */
    private double landDetourFactor = 1.6D;
    /**
     * 0.9.011：窄水上界（按连续湿步数算，步长由搜索网格决定）。湿步 &lt;= 本值时按
     * {@code narrowWaterPerStation} 计价（「小桥，直接过」）；超过本值进宽水档
     * （「大河，重罚绕行」）。默认 20。
     *
     * <p>与 {@code roadAppearance.culvertMaxStations}(6) 一起构成 6 / 20 / ∞ 三档断崖。</p>
     */
    private int narrowWaterMaxWidth = 20;
    /** 0.9.011：窄水（&gt; 涵洞档、&lt;= narrowWaterMaxWidth）每湿步代价。默认 20 ⇒ 20 格宽 = 400。 */
    private double narrowWaterPerStation = 20.0D;
    /** 0.9.011：宽水（&gt; narrowWaterMaxWidth）起跳价。默认 600。仅 landDetourFactor &gt; 0 时生效。 */
    private double wideWaterBase = 600.0D;
    /** 0.9.011：宽水每湿步代价。默认 80 ⇒ 40 格 = 600+3200 = 3800。仅 landDetourFactor &gt; 0 时生效。 */
    private double wideWaterPerStation = 80.0D;
    /**
     * Maximum vertical rise per horizontal block accepted anywhere in the
     * coarse path search and the post-hoc transition check. One shared value
     * keeps the two stages from disagreeing: at the default 0.5 a cliff must
     * be crossed with a ramp (or a tunnel/bridge), never a near-vertical shaft.
     */
    private double maxGradeFraction = 0.5D;
    /**
     * Parallelism for the local grade-repair window pool. 0 = auto: derived
     * from the CPU count and the current road-worker budget (never below 4,
     * never above the available processors). The old fixed pool of 4 left
     * most road workers blocked in future.get() while cores idled.
     */
    private int gradeRepairParallelism = 0;
    /**
     * 0.8.340：粗规划路径搜索的等待方式。默认 <b>false</b> = 旧行为（逐个 future.get()
     * 串行等待）。设 true 则改为"同时等全部完成"。
     *
     * <p>背景：{@code CoarseCorridorPlanningAdapter} 把每条路的搜索作为独立任务提交到同一
     * 线程池（提交方式不变），但旧实现逐个 get()，于是第一个任务拖多久、后面就白等多久。
     * 真机 20260911 开局：16 条路 pathfindingMs=81~84 秒而 CPU 仅约 1 核。</p>
     *
     * <p>语义严格等价：任一任务失败仍立即抛 {@code IllegalStateException}，线程中断仍立即
     * 取消其余任务并抛 {@code CancellationException}；任务数量/顺序/输入/产出均不变。
     * 因此在默认关闭下行为与旧版逐位一致，出问题改配置即可回退（无需换 jar）。</p>
     */
    private boolean pathfindingParallelWait = false;

    /**
     * <b>粗规划路径搜索的等待超时（毫秒，0 = 不限时）。默认 180000（3 分钟）。</b>
     *
     * <h2>为什么需要（审计 P1-1）</h2>
     * {@code CoarseCorridorPlanningAdapter.plan} 把每条路的粗搜索提交到
     * {@code TaskRole.INITIAL} 池，然后在共享池线程上 {@code future.get()} **无超时**等待。
     *
     * <p>真机取证（2026-09-24 崩溃日志的 55 份 ModernFix 线程转储）：
     * {@code RW-Worker-1} 在同一个 {@code FutureTask@4acf1b0d} 上等了
     * <b>≥14 分钟</b>（08:40:18 → 08:54:42 四份转储都是同一对象），
     * 而同期 {@code RW-Initial-1..16} 有 15 个是空闲的 —— 子任务并非没线程跑，
     * 而是**排在队列后面长时间轮不到**（12 条 lane 各投一批分钟级任务 → 护航效应）。</p>
     *
     * <p>观感与死锁完全一致：「Chunky 预热卡住不动」。加超时后超时会取消该任务并
     * 抛出，让这个 region 走既有的 {@code attempt=2/3 with guaranteed coordinator progress}
     * 重试路径 —— 失败重试远好过无限挂起。</p>
     *
     * <h2>取值考量</h2>
     * 粗搜索本身是**分钟级**任务（真机实测单条 pathfindingMs 可到 81~84 秒，
     * 内存压力下更久），所以阈值不能小。默认 3 分钟 ≈ 正常耗时的 2 倍余量：
     * 搜索与物理队列分别限时，主动等游玩许可不消耗搜索期限，避免前台暂停耗尽重试次数。
     * 设 0 关闭期限，但停服、换世界和中断仍可取消等待。
     */
    private int coarsePathWaitTimeoutMs = 180_000;

    /**
     * Only routes whose building conflict cannot be avoided go through the
     * local-repair wave (default true, the historical behavior). When false a
     * protected-gap route is retained as-is right after the initial batch and
     * the whole local-repair wave is skipped, which saves wall-clock time on
     * gap-heavy batches at the cost of not attempting the local detours.
     * Reverting the config restores the exact prior behavior.
     */
    private boolean localRepairOnlyOnUnavoidable = false;
    /**
     * B 阶段：规划前过滤"村庄 V ↔ V 结构盒内的未知端点"。默认启用。
     *
     * <p>连接某一端点是未知端点（无已核验 owner），且该端点 XZ 落在某个村庄 V 的
     * 结构聚合盒内，而另一端点恰好就是那个村庄 V 时，该连接不参与规划/施工/局部
     * 修复。设 false 可完全恢复旧行为。</p>
     */
    private boolean filterUnknownEndpointInsideVillage = true;
    /**
     * E 阶段：结构起点放置数学预筛。默认启用。
     *
     * <p>用 {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} 判定候选区块
     * 是否是它所属 region 的潜在起点；不是则精确解析必然为空，可直接跳过。非随机散布的
     * 放置类型保守放行。护栏：预筛前后 exactPieceBoxes 不一致时自动关闭并打警告。</p>
     */
    private boolean prefilterStructureStarts = true;
    /**
     * 河流局部修复的"必然无解"预判。默认启用。
     *
     * <p>河道局部修复的判据是"候选路径不得触碰任何河道列"
     * （{@code edgeIntersectsRiver} + {@code isRiverRepairAccepted}）。若河道把局部窗口
     * 一分为二（在窗口边界上出现 ≥2 段河道，或整个窗口泡在水里），则任何候选都必然失败，
     * 真机近 4 局 30 次尝试 0 成功。启用后先用窗口边界采样判定，命中就直接跳过那次
     * step=1 的局部 A*（只打一行 info），行为与结果不变。设 false 可完全恢复旧行为。</p>
     */
    private boolean riverRepairBypassPrecheck = true;
    /**
     * 局部修复窗口合并半径（格）。默认 50，设 0 或负数恢复旧值 padding/4。
     *
     * <p>同一条路上的多个冲突点各自起一个半径 100 的圆形窗口，窗口中心相距 30~60 格时
     * 圆几乎完全重叠：真机 17 个缺口点被旧阈值（padding/4=25）只合并到 13 个窗口，
     * 于是同一条路跑了 13 次完整局部重规划（每次一次 step=2 圆内 A* + 粗地形预检 +
     * 隧道重路由 + 走廊快照）。A* 的节点数由圆半径决定、与圆内冲突个数无关，所以把
     * 相邻窗口合并成一个圆是"同样的单次成本、更少的次数"。</p>
     */
    private int localRepairWindowClusterRadiusBlocks = 50;
    /**
     * 平滑路径出现保护缺口时，先算一遍未平滑直线版本并择优。默认启用。
     *
     * <p>Catmull-Rom 平滑会把已经绕开建筑的折线推进建筑里（真机
     * "retained route with internal building protection gap" 全部出现在平滑路径上）。
     * 旧行为是直接保留带缺口的平滑路径；启用后补算未平滑版本，缺口更少就用它。
     * 缺口 = 路面被建筑打断，比路稍显生硬严重得多。设 false 恢复旧行为。</p>
     */
    private boolean preferUnsmoothedOnProtectionGap = true;
    /**
     * 施工高度冲突局部绕行的最大窗口（站数，默认 96）。
     *
     * <p>{@code rerouteConstructionHeightConflict} 原本只按
     * {@code HEIGHT_ANOMALY_DETOUR_BLOCKS=50} 站做一次局部 A*；真机 3 次
     * {@code construction-height reroute no bypass}（rise=294/331）都发生在爬山段，
     * ±50 站的窗口里没有旁路。启用后按 50 → 本值 逐级放大重试，仍无解才放弃。
     * 设 <=50 则关闭放大（等价旧行为）。</p>
     */
    private int heightAnomalyDetourMaxBlocks = 96;

    // ── 0.8.420：KNN 路网拓扑参数（原为 RoadConstants 硬编码）────────────────
    // 这六项决定「哪些结构互相连路」⇒ 它们已经在 planning() 段里，随
    // ConfigService.planningFingerprintPayload() 整体序列化自动进规划指纹；
    // 改任一值 ⇒ 指纹变 ⇒ 已有缓存路线重算（刻意行为，建新世界前先定好）。
    /** KNN：每个点尝试连的最近邻个数。默认 2；调大路网更密（冗余边多）。 */
    private int knnK = RoadConstants.DEFAULT_KNN_K;
    /** KNN：候选距离放大系数（相对最近邻距离）。默认 1.8；<=0 关闭该过滤。 */
    private double knnAlpha = RoadConstants.DEFAULT_KNN_ALPHA;
    /** KNN：同一顶点任意两条边的最小夹角（度）。默认 40；调小允许更扁的扇形。 */
    private double knnMinAngleDeg = RoadConstants.DEFAULT_KNN_MIN_ANGLE_DEG;
    /** KNN：单点最大度数（边数上限）。默认 2；调大允许枢纽。 */
    private int knnDegreeCap = RoadConstants.DEFAULT_KNN_DEGREE_CAP;
    /** 连通分支合并：同一顶点两条边的最小夹角（度）。默认 35。 */
    private double componentMinAngleDeg = RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG;
    /** 连通分支合并：单点最大度数。默认 3。 */
    private int componentDegreeCap = RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP;

    // 0.8.421：这两项原先写死在 RoadConstants 里，是**真正决定「哪些结构连得上」**的
    // 唯一旋钮（主连边的长度上限、跨接缺口的上限），现晋升为可配置。
    // 同样位于 planning() 段内 ⇒ 自动进规划指纹，改任一项 ⇒ 已有缓存路线重算。
    /**
     * 主连边长度上限（格）。默认 2048。
     * 调大：允许更远的两个结构直接连边；调小：只连附近的，远距离留给跨接阶段。
     */
    private int planMaxEdgeLenBlocks = RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS;
    /**
     * 0.9.079：A\* 失败后把端点就近改接到已有道路的搜索半径（格）。0 = 关闭。
     *
     * <p>真机取证：23 条连接里 3 条彻底失败，但两端距最近已有道路只有
     * 0 / 16.6 / 49.2 / 55.0 / 64.3 格 —— 路已经修到旁边了，只是 A* 还在坚持生成
     * 完整长路而卡在校验上。允许改接后，生成的是几十格的接入短段，几乎必然成功。</p>
     */
    private int attachToExistingRoadRadiusBlocks = 256;
    /**
     * 跨接缺口上限（格）。默认 1536。
     * 路网碎片化时，两个连通块最近点相距不超过此值才会补一条桥接道路；
     * 调大 ⇒ 远处的村子也能连上（路网更连通，总里程更长）。
     */
    private int bridgeJoinLenBlocks = RoadConstants.DEFAULT_BRIDGE_JOIN_LEN_BLOCKS;

    @Override
    public void sanitize() {
        if (initialPlanRadiusChunks <= 0) initialPlanRadiusChunks = 64;
        // 开局预热与游玩期负载分开配置，不能为了扩大开局覆盖同步放宽动态范围。
        initialPlanRadiusChunks = Math.min(RoadConstants.INITIAL_PLAN_MAX_RADIUS_CHUNKS, initialPlanRadiusChunks);
        if (dynamicPlanRadiusChunks <= 0) dynamicPlanRadiusChunks = RoadConstants.DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS;
        dynamicPlanRadiusChunks = Math.min(RoadConstants.COARSE_REGION_MAX_RADIUS_CHUNKS, dynamicPlanRadiusChunks);
        if (dynamicPlanStrideChunks <= 0) {
            dynamicPlanStrideChunks = Math.max(RoadConstants.PLAN_TILE_MIN,
                    Math.min(RoadConstants.PLAN_TILE_MAX, Math.max(1, dynamicPlanRadiusChunks) / 2));
        }
        if (dynamicPlanStrideChunks > dynamicPlanRadiusChunks) {
            dynamicPlanStrideChunks = dynamicPlanRadiusChunks;
        }
        dynamicPlanStrideChunks = Math.min(RoadConstants.PLAN_TILE_MAX, dynamicPlanStrideChunks);
        if (planningAlgorithm == null) planningAlgorithm = PlanningAlgorithm.PIPELINE;
        // 2026-11-13：旧规划器已全部删除，枚举只剩 PIPELINE 一种。
        // 老配置里写着的旧枚举名（KNN/DELAUNAY/RNG/MST/SNAP_BRANCH/ALPHA_MODEL）
        // 会被 GSON 当成未知值返回 null，这里统一回落到 PIPELINE。
        if (terrainSamplingMode == null) terrainSamplingMode = TerrainSamplingMode.COARSE_CORRIDOR;
        localConflictRadiusBlocks = Math.max(16, Math.min(512, localConflictRadiusBlocks));
        structureDetourRadiusStartBlocks = Math.max(16, Math.min(512, structureDetourRadiusStartBlocks));
        structureDetourRadiusStepBlocks = Math.max(8, Math.min(128, structureDetourRadiusStepBlocks));
        structureDetourRadiusMaxBlocks = Math.max(structureDetourRadiusStartBlocks,
                Math.min(1024, structureDetourRadiusMaxBlocks));
        lakeDetourRadiusMaxBlocks = Math.max(16, Math.min(1024, lakeDetourRadiusMaxBlocks));
        heightTunnelThresholdBlocks = Math.max(2, Math.min(32, heightTunnelThresholdBlocks));
        heightRepairContextBlocks = Math.max(16, Math.min(256, heightRepairContextBlocks));
        if (localRepairWindowClusterRadiusBlocks > 0) {
            localRepairWindowClusterRadiusBlocks = Math.max(8,
                    Math.min(128, localRepairWindowClusterRadiusBlocks));
        }
        heightAnomalyDetourMaxBlocks = Math.max(16, Math.min(512, heightAnomalyDetourMaxBlocks));
        tunnelDepthBlocks = Math.max(4, Math.min(64, tunnelDepthBlocks));
        if (terrainProfileMode == null) terrainProfileMode = TerrainProfileMode.AUTO;
        surfaceCutMaxBlocks = Math.max(0, Math.min(16, surfaceCutMaxBlocks));
        surfaceFillMaxBlocks = Math.max(0, Math.min(16, surfaceFillMaxBlocks));
        dryDepressionBridgeDepth = Math.max(2, Math.min(16, dryDepressionBridgeDepth));
        mountainOverburdenBlocks = Math.max(2, Math.min(32, mountainOverburdenBlocks));
        tunnelMinRun = Math.max(2, Math.min(16, tunnelMinRun));
        switchbackStrength = Math.max(0, Math.min(100, switchbackStrength));
        heightAwareStateLimit = Math.max(50_000, Math.min(1_000_000, heightAwareStateLimit));
        samplingInFlightTimeoutMs = Math.max(100, Math.min(30_000, samplingInFlightTimeoutMs));
        // 0 = 关闭（保持旧行为）；否则夹到 [10s, 30min]。
        // 下限 10s 防止误配成过小值把正常的分钟级粗搜索全判超时。
        if (coarsePathWaitTimeoutMs != 0) {
            coarsePathWaitTimeoutMs = Math.max(10_000, Math.min(1_800_000, coarsePathWaitTimeoutMs));
        }
        autoMountainThresholdFraction = finiteFraction(autoMountainThresholdFraction, 0.30D);
        autoWaterThresholdFraction = finiteFraction(autoWaterThresholdFraction, 0.25D);
        gradeRepairWindowStateLimit = Math.max(20_000, Math.min(500_000, gradeRepairWindowStateLimit));
        gradeRepairWindowBudgetMillis = Math.max(1_000, Math.min(600_000, gradeRepairWindowBudgetMillis));
        baseWaterPenalty = Math.max(0.0D, Math.min(100_000.0D, baseWaterPenalty));
        perStationWaterPenalty = Math.max(0.0D, Math.min(100_000.0D, perStationWaterPenalty));
        riverProximityPenalty = Math.max(0.0D, Math.min(100_000.0D, riverProximityPenalty));
        riverProximityRadius = Math.max(0, Math.min(32, riverProximityRadius));
        culvertCostPerStation = Math.max(0.0D, Math.min(100.0D, culvertCostPerStation));
        landDetourFactor = Math.max(0.0D, Math.min(10.0D, landDetourFactor));
        narrowWaterMaxWidth = Math.max(0, Math.min(256, narrowWaterMaxWidth));
        narrowWaterPerStation = Math.max(0.0D, Math.min(100_000.0D, narrowWaterPerStation));
        wideWaterBase = Math.max(0.0D, Math.min(100_000.0D, wideWaterBase));
        wideWaterPerStation = Math.max(0.0D, Math.min(100_000.0D, wideWaterPerStation));
        maxGradeFraction = finiteFraction(maxGradeFraction, 0.5D);
        gradeRepairParallelism = Math.max(0, Math.min(Runtime.getRuntime().availableProcessors(),
                gradeRepairParallelism));
        // KNN 路网拓扑
        knnK = Math.max(1, Math.min(8, knnK));
        knnAlpha = Math.max(0.0D, Math.min(5.0D, knnAlpha));
        knnMinAngleDeg = Math.max(0.0D, Math.min(89.0D, knnMinAngleDeg));
        knnDegreeCap = Math.max(1, Math.min(8, knnDegreeCap));
        componentMinAngleDeg = Math.max(0.0D, Math.min(89.0D, componentMinAngleDeg));
        componentDegreeCap = Math.max(1, Math.min(8, componentDegreeCap));
        planMaxEdgeLenBlocks = Math.max(128, Math.min(16384, planMaxEdgeLenBlocks));
        attachToExistingRoadRadiusBlocks = Math.max(0, Math.min(1024, attachToExistingRoadRadiusBlocks));
        bridgeJoinLenBlocks = Math.max(64, Math.min(16384, bridgeJoinLenBlocks));
    }

    private static double finiteFraction(double value, double fallback) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(0.1D, Math.min(0.9D, value));
    }

    @Override
    public PlanningConfig snapshot() {
        PlanningConfig copy = new PlanningConfig();
        copy.initialPlanRadiusChunks = this.initialPlanRadiusChunks;
        copy.dynamicPlanEnabled = this.dynamicPlanEnabled;
        copy.dynamicPlanRadiusChunks = this.dynamicPlanRadiusChunks;
        copy.dynamicPlanStrideChunks = this.dynamicPlanStrideChunks;
        copy.planningAlgorithm = this.planningAlgorithm;
        copy.terrainSamplingMode = this.terrainSamplingMode;
        copy.localConflictRadiusBlocks = this.localConflictRadiusBlocks;
        copy.structureDetourRadiusStartBlocks = this.structureDetourRadiusStartBlocks;
        copy.structureDetourRadiusStepBlocks = this.structureDetourRadiusStepBlocks;
        copy.structureDetourRadiusMaxBlocks = this.structureDetourRadiusMaxBlocks;
        copy.lakeDetourRadiusMaxBlocks = this.lakeDetourRadiusMaxBlocks;
        copy.heightTunnelThresholdBlocks = this.heightTunnelThresholdBlocks;
        copy.heightRepairContextBlocks = this.heightRepairContextBlocks;
        copy.tunnelDepthBlocks = this.tunnelDepthBlocks;
        copy.coarseHeightAwareEnabled = this.coarseHeightAwareEnabled;
        copy.terrainProfileMode = this.terrainProfileMode;
        copy.surfaceCutMaxBlocks = this.surfaceCutMaxBlocks;
        copy.surfaceFillMaxBlocks = this.surfaceFillMaxBlocks;
        copy.dryDepressionBridgeDepth = this.dryDepressionBridgeDepth;
        copy.passThroughVillageClipping = this.passThroughVillageClipping;
        copy.mountainOverburdenBlocks = this.mountainOverburdenBlocks;
        copy.tunnelMinRun = this.tunnelMinRun;
        copy.switchbackStrength = this.switchbackStrength;
        copy.heightAwareStateLimit = this.heightAwareStateLimit;
        copy.samplingInFlightTimeoutMs = this.samplingInFlightTimeoutMs;
        copy.autoMountainThresholdFraction = this.autoMountainThresholdFraction;
        copy.autoWaterThresholdFraction = this.autoWaterThresholdFraction;
        copy.gradeRepairWindowStateLimit = this.gradeRepairWindowStateLimit;
        copy.gradeRepairWindowBudgetMillis = this.gradeRepairWindowBudgetMillis;
        copy.gradeRepairLengthBoundPrune = this.gradeRepairLengthBoundPrune;
        copy.baseWaterPenalty = this.baseWaterPenalty;
        copy.perStationWaterPenalty = this.perStationWaterPenalty;
        copy.riverProximityPenalty = this.riverProximityPenalty;
        copy.riverProximityRadius = this.riverProximityRadius;
        copy.culvertCostPerStation = this.culvertCostPerStation;
        copy.landDetourFactor = this.landDetourFactor;
        copy.narrowWaterMaxWidth = this.narrowWaterMaxWidth;
        copy.narrowWaterPerStation = this.narrowWaterPerStation;
        copy.wideWaterBase = this.wideWaterBase;
        copy.wideWaterPerStation = this.wideWaterPerStation;
        copy.maxGradeFraction = this.maxGradeFraction;
        copy.gradeRepairParallelism = this.gradeRepairParallelism;
        copy.pathfindingParallelWait = this.pathfindingParallelWait;
        copy.coarsePathWaitTimeoutMs = this.coarsePathWaitTimeoutMs;
        copy.localRepairOnlyOnUnavoidable = this.localRepairOnlyOnUnavoidable;
        copy.filterUnknownEndpointInsideVillage = this.filterUnknownEndpointInsideVillage;
        copy.prefilterStructureStarts = this.prefilterStructureStarts;
        copy.riverRepairBypassPrecheck = this.riverRepairBypassPrecheck;
        copy.localRepairWindowClusterRadiusBlocks = this.localRepairWindowClusterRadiusBlocks;
        copy.preferUnsmoothedOnProtectionGap = this.preferUnsmoothedOnProtectionGap;
        copy.heightAnomalyDetourMaxBlocks = this.heightAnomalyDetourMaxBlocks;
        copy.knnK = this.knnK;
        copy.knnAlpha = this.knnAlpha;
        copy.knnMinAngleDeg = this.knnMinAngleDeg;
        copy.knnDegreeCap = this.knnDegreeCap;
        copy.componentMinAngleDeg = this.componentMinAngleDeg;
        copy.componentDegreeCap = this.componentDegreeCap;
        copy.planMaxEdgeLenBlocks = this.planMaxEdgeLenBlocks;
        copy.attachToExistingRoadRadiusBlocks = this.attachToExistingRoadRadiusBlocks;
        copy.bridgeJoinLenBlocks = this.bridgeJoinLenBlocks;
        return copy;
    }

    public int initialPlanRadiusChunks() { return initialPlanRadiusChunks; }
    public void setInitialPlanRadiusChunks(int v) { this.initialPlanRadiusChunks = v; }
    public boolean dynamicPlanEnabled() { return dynamicPlanEnabled; }
    public void setDynamicPlanEnabled(boolean v) { this.dynamicPlanEnabled = v; }
    public int dynamicPlanRadiusChunks() { return dynamicPlanRadiusChunks; }
    public void setDynamicPlanRadiusChunks(int v) { this.dynamicPlanRadiusChunks = v; }
    public int dynamicPlanStrideChunks() { return dynamicPlanStrideChunks; }
    public void setDynamicPlanStrideChunks(int v) { this.dynamicPlanStrideChunks = v; }
    public PlanningAlgorithm planningAlgorithm() { return planningAlgorithm; }
    public void setPlanningAlgorithm(PlanningAlgorithm v) { this.planningAlgorithm = v; }
    public PlanningAlgorithm algorithm() { return planningAlgorithm; }
    public void setAlgorithm(PlanningAlgorithm v) { this.planningAlgorithm = v; }
    public TerrainSamplingMode terrainSamplingMode() { return terrainSamplingMode; }
    public void setTerrainSamplingMode(TerrainSamplingMode v) { this.terrainSamplingMode = v; }
    public int localConflictRadiusBlocks() { return localConflictRadiusBlocks; }
    public void setLocalConflictRadiusBlocks(int v) { this.localConflictRadiusBlocks = v; }
    public int structureDetourRadiusStartBlocks() { return structureDetourRadiusStartBlocks; }
    public void setStructureDetourRadiusStartBlocks(int v) { this.structureDetourRadiusStartBlocks = v; }
    public int structureDetourRadiusStepBlocks() { return structureDetourRadiusStepBlocks; }
    public void setStructureDetourRadiusStepBlocks(int v) { this.structureDetourRadiusStepBlocks = v; }
    public int structureDetourRadiusMaxBlocks() { return structureDetourRadiusMaxBlocks; }
    public void setStructureDetourRadiusMaxBlocks(int v) { this.structureDetourRadiusMaxBlocks = v; }
    public int lakeDetourRadiusMaxBlocks() { return lakeDetourRadiusMaxBlocks; }
    public void setLakeDetourRadiusMaxBlocks(int v) { this.lakeDetourRadiusMaxBlocks = v; }
    public int heightTunnelThresholdBlocks() { return heightTunnelThresholdBlocks; }
    public void setHeightTunnelThresholdBlocks(int v) { this.heightTunnelThresholdBlocks = v; }
    public int heightRepairContextBlocks() { return heightRepairContextBlocks; }
    public void setHeightRepairContextBlocks(int v) { this.heightRepairContextBlocks = v; }
    public int tunnelDepthBlocks() { return tunnelDepthBlocks; }
    public void setTunnelDepthBlocks(int v) { this.tunnelDepthBlocks = v; }
    public boolean coarseHeightAwareEnabled() { return coarseHeightAwareEnabled; }
    public void setCoarseHeightAwareEnabled(boolean v) { this.coarseHeightAwareEnabled = v; }
    public TerrainProfileMode terrainProfileMode() { return terrainProfileMode; }
    public void setTerrainProfileMode(TerrainProfileMode v) { this.terrainProfileMode = v; }
    public int surfaceCutMaxBlocks() { return surfaceCutMaxBlocks; }
    public void setSurfaceCutMaxBlocks(int v) { this.surfaceCutMaxBlocks = v; }
    public int surfaceFillMaxBlocks() { return surfaceFillMaxBlocks; }
    public void setSurfaceFillMaxBlocks(int v) { this.surfaceFillMaxBlocks = v; }
    public int dryDepressionBridgeDepth() { return dryDepressionBridgeDepth; }

    public boolean passThroughVillageClipping() { return passThroughVillageClipping; }

    public void setPassThroughVillageClipping(boolean value) { this.passThroughVillageClipping = value; }
    public void setDryDepressionBridgeDepth(int v) { this.dryDepressionBridgeDepth = v; }
    public int mountainOverburdenBlocks() { return mountainOverburdenBlocks; }
    public void setMountainOverburdenBlocks(int v) { this.mountainOverburdenBlocks = v; }
    public int tunnelMinRun() { return tunnelMinRun; }
    public void setTunnelMinRun(int v) { this.tunnelMinRun = v; }
    public int switchbackStrength() { return switchbackStrength; }
    public void setSwitchbackStrength(int v) { this.switchbackStrength = v; }
    public int heightAwareStateLimit() { return heightAwareStateLimit; }
    public void setHeightAwareStateLimit(int v) { this.heightAwareStateLimit = v; }
    public int samplingInFlightTimeoutMs() { return samplingInFlightTimeoutMs; }
    public void setSamplingInFlightTimeoutMs(int v) { this.samplingInFlightTimeoutMs = v; }
    public double autoMountainThresholdFraction() { return autoMountainThresholdFraction; }
    public void setAutoMountainThresholdFraction(double v) { this.autoMountainThresholdFraction = v; }
    public double autoWaterThresholdFraction() { return autoWaterThresholdFraction; }
    public void setAutoWaterThresholdFraction(double v) { this.autoWaterThresholdFraction = v; }
    public int gradeRepairWindowStateLimit() { return gradeRepairWindowStateLimit; }
    public void setGradeRepairWindowStateLimit(int v) { this.gradeRepairWindowStateLimit = v; }
    /** 单个局部修坡窗口的 A* 墙钟预算（毫秒）。见字段注释。 */
    public int gradeRepairWindowBudgetMillis() { return gradeRepairWindowBudgetMillis; }
    public void setGradeRepairWindowBudgetMillis(int v) { this.gradeRepairWindowBudgetMillis = v; }
    /** 局部修坡 A* 的长度上界可证明剪枝开关。见字段注释。 */
    public boolean gradeRepairLengthBoundPrune() { return gradeRepairLengthBoundPrune; }
    public void setGradeRepairLengthBoundPrune(boolean v) { this.gradeRepairLengthBoundPrune = v; }
    /** 过河入门费。见字段注释。 */
    public double baseWaterPenalty() { return baseWaterPenalty; }
    public void setBaseWaterPenalty(double v) { this.baseWaterPenalty = v; }
    /** 每湿步递增项。见字段注释。 */
    public double perStationWaterPenalty() { return perStationWaterPenalty; }
    public void setPerStationWaterPenalty(double v) { this.perStationWaterPenalty = v; }
    /** 临河距离惩罚（0 = 禁用新惩罚）。见字段注释。 */
    public double riverProximityPenalty() { return riverProximityPenalty; }
    public void setRiverProximityPenalty(double v) { this.riverProximityPenalty = v; }
    /** 临河惩罚半径（格，0 = 禁用）。见字段注释。 */
    public int riverProximityRadius() { return riverProximityRadius; }
    public void setRiverProximityRadius(int v) { this.riverProximityRadius = v; }
    /** 涵洞段每步过水代价。见字段注释。 */
    public double culvertCostPerStation() { return culvertCostPerStation; }
    public void setCulvertCostPerStation(double v) { this.culvertCostPerStation = v; }
    /** 陆路闸门倍数（0 = 关闭闸门，回到单趟 A* + 旧宽水价目）。见字段注释。 */
    public double landDetourFactor() { return landDetourFactor; }
    public void setLandDetourFactor(double v) { this.landDetourFactor = v; }
    /** 窄水上界（连续湿步数）。见字段注释。 */
    public int narrowWaterMaxWidth() { return narrowWaterMaxWidth; }
    public void setNarrowWaterMaxWidth(int v) { this.narrowWaterMaxWidth = v; }
    /** 窄水每湿步代价。见字段注释。 */
    public double narrowWaterPerStation() { return narrowWaterPerStation; }
    public void setNarrowWaterPerStation(double v) { this.narrowWaterPerStation = v; }
    /** 宽水起跳价。见字段注释。 */
    public double wideWaterBase() { return wideWaterBase; }
    public void setWideWaterBase(double v) { this.wideWaterBase = v; }
    /** 宽水每湿步代价。见字段注释。 */
    public double wideWaterPerStation() { return wideWaterPerStation; }
    public void setWideWaterPerStation(double v) { this.wideWaterPerStation = v; }
    public double maxGradeFraction() { return maxGradeFraction; }
    public void setMaxGradeFraction(double v) { this.maxGradeFraction = v; }
    public int gradeRepairParallelism() { return gradeRepairParallelism; }
    public void setGradeRepairParallelism(int v) { this.gradeRepairParallelism = v; }
    public boolean pathfindingParallelWait() { return pathfindingParallelWait; }
    public void setPathfindingParallelWait(boolean v) { this.pathfindingParallelWait = v; }

    /** 粗规划路径等待超时（毫秒，0 = 不限时）。见字段注释。 */
    public int coarsePathWaitTimeoutMs() {
        return coarsePathWaitTimeoutMs;
    }

    public void setCoarsePathWaitTimeoutMs(int v) {
        this.coarsePathWaitTimeoutMs = v;
    }
    public boolean localRepairOnlyOnUnavoidable() { return localRepairOnlyOnUnavoidable; }
    public void setLocalRepairOnlyOnUnavoidable(boolean v) { this.localRepairOnlyOnUnavoidable = v; }
    public boolean filterUnknownEndpointInsideVillage() { return filterUnknownEndpointInsideVillage; }
    public void setFilterUnknownEndpointInsideVillage(boolean v) { this.filterUnknownEndpointInsideVillage = v; }
    public boolean prefilterStructureStarts() { return prefilterStructureStarts; }
    public void setPrefilterStructureStarts(boolean v) { this.prefilterStructureStarts = v; }
    public boolean riverRepairBypassPrecheck() { return riverRepairBypassPrecheck; }
    public void setRiverRepairBypassPrecheck(boolean v) { this.riverRepairBypassPrecheck = v; }
    public int localRepairWindowClusterRadiusBlocks() { return localRepairWindowClusterRadiusBlocks; }
    public void setLocalRepairWindowClusterRadiusBlocks(int v) { this.localRepairWindowClusterRadiusBlocks = v; }
    public boolean preferUnsmoothedOnProtectionGap() { return preferUnsmoothedOnProtectionGap; }
    public void setPreferUnsmoothedOnProtectionGap(boolean v) { this.preferUnsmoothedOnProtectionGap = v; }
    public int heightAnomalyDetourMaxBlocks() { return heightAnomalyDetourMaxBlocks; }
    public void setHeightAnomalyDetourMaxBlocks(int v) { this.heightAnomalyDetourMaxBlocks = v; }

    // KNN 路网拓扑（0.8.420）
    public int knnK() { return knnK; }
    public void setKnnK(int v) { this.knnK = v; }
    public double knnAlpha() { return knnAlpha; }
    public void setKnnAlpha(double v) { this.knnAlpha = v; }
    public double knnMinAngleDeg() { return knnMinAngleDeg; }
    public void setKnnMinAngleDeg(double v) { this.knnMinAngleDeg = v; }
    public int knnDegreeCap() { return knnDegreeCap; }
    public void setKnnDegreeCap(int v) { this.knnDegreeCap = v; }
    public double componentMinAngleDeg() { return componentMinAngleDeg; }
    public void setComponentMinAngleDeg(double v) { this.componentMinAngleDeg = v; }
    public int componentDegreeCap() { return componentDegreeCap; }
    public void setComponentDegreeCap(int v) { this.componentDegreeCap = v; }
    public int planMaxEdgeLenBlocks() { return planMaxEdgeLenBlocks; }

    public int attachToExistingRoadRadiusBlocks() { return attachToExistingRoadRadiusBlocks; }

    public void setAttachToExistingRoadRadiusBlocks(int v) { this.attachToExistingRoadRadiusBlocks = v; }
    public void setPlanMaxEdgeLenBlocks(int v) { this.planMaxEdgeLenBlocks = v; }
    public int bridgeJoinLenBlocks() { return bridgeJoinLenBlocks; }
    public void setBridgeJoinLenBlocks(int v) { this.bridgeJoinLenBlocks = v; }
}
