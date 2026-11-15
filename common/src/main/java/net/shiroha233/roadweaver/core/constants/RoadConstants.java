/* 文件职责：集中定义全局常量。 */
package net.shiroha233.roadweaver.core.constants;

public final class RoadConstants {

    private RoadConstants() {}

    // ── 纵断面高度步进校验上限（RoadDataValidator 与 RoadSnapService 共用同一口径）──
    //
    // 这三条原本在 RoadDataValidator 与 RoadSnapService 里各写一份（同值同义）。
    // 同值同义的常量复制两份，只要有人改了其中一份，「校验通过」与「吸附后校验通过」
    // 就会用不同尺子，而两边都不会报错。2026-11-12 结构重构时收成单一来源。
    /** 相邻两站中心点允许的高度差（格）。 */
    public static final int MAX_CENTER_STEP = 2;
    /** 相邻两站允许的高度差（格）。注意：站距约 1.0 格/站，所以 1 实义是 100% 坡度。 */
    public static final int MAX_HEIGHT_STEP = 1;
    /** 单条路允许的超限步进次数上限。 */
    public static final int MAX_HEIGHT_STEP_EXCEPTIONS = 3;

    public static final int DEFAULT_ASTAR_STEP = 8;
    public static final int DEFAULT_ASTAR_MAX_STEPS = 200_000;
    public static final int ASTAR_MAX_STEPS_MIN = 3_000;
    public static final int ASTAR_MAX_STEPS_MAX = 200_000;
    public static final int ASTAR_STEP_MAX = 128;
    /** A smaller lattice step is a finer route sample; local repair uses the 1-block floor. */
    public static final int ASTAR_STEP_MIN = 1;

    public static final int DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS = 2048;
    public static final int DEFAULT_BRIDGE_JOIN_LEN_BLOCKS = 1536;
    public static final double DEFAULT_KNN_ALPHA = 1.8;
    public static final double DEFAULT_KNN_MIN_ANGLE_DEG = 40.0;
    public static final double DEFAULT_COMPONENT_MIN_ANGLE_DEG = 35.0;
    public static final int DEFAULT_KNN_K = 2;
    public static final int DEFAULT_KNN_DEGREE_CAP = 2;
    public static final int DEFAULT_COMPONENT_DEGREE_CAP = 3;
    public static final int DEFAULT_INITIAL_PLAN_RADIUS_CHUNKS = 128;
    public static final int DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS = 128;
    public static final int DEFAULT_DYNAMIC_PLAN_STRIDE_CHUNKS = 64;
    public static final int COARSE_REGION_MAX_RADIUS_CHUNKS = 2048;
    public static final int COARSE_REGION_MAX_TILES = 70_000;
    public static final int COARSE_REGION_MAX_SAMPLES = 1_100_000;
    public static final int MANUAL_MAP_SAMPLING_MAX_COARSE_TILES = 1_024;
    public static final int ACCURATE_REGION_MAX_SAMPLES = 1_100_000;
    public static final int COARSE_REGION_PARALLEL_MIN_SAMPLES = 16_384;
    public static final int COARSE_REGION_PARALLEL_MIN_ROWS_PER_WORKER = 64;
    public static final int COARSE_REGION_PARALLEL_MAX_THREADS = 24;
    public static final int COARSE_REGION_TILE_MAX_PIXELS = 256;
    public static final int COARSE_TERRAIN_TILE_SIZE_CHUNKS = 16;
    public static final int COARSE_TERRAIN_TILE_SCHEMA_VERSION = 2;
    public static final int COARSE_TERRAIN_TILE_CACHE_MAX_ENTRIES = 256;
    public static final int COARSE_TERRAIN_TILE_HALO_BLOCKS = 16;
    public static final int MAX_PLANNED_KEYS = 200_000;
    public static final int PLAN_TILE_MIN = 8;
    public static final int PLAN_TILE_MAX = 256;

    public static final long WORK_PERIOD_MS = 20;
    public static final long MAX_SLEEP_MS = 200;
    public static final int DEFAULT_DUTY_CYCLE = 50;
    public static final int DUTY_CYCLE_MIN = 1;
    public static final int DUTY_CYCLE_MAX = 100;
    public static final int DEFAULT_INITIAL_GENERATION_THREADS = 6;
    public static final int COMPUTE_THREADS_MAX = 128;

    public static final int DEFAULT_BRIDGE_DECK_CLEARANCE = 2;
    public static final int BRIDGE_DECK_CLEARANCE_MIN = 1;
    public static final int BRIDGE_DECK_CLEARANCE_MAX = 8;
    /** Zero means unlimited; long ocean crossings must remain bridges. */
    public static final int DEFAULT_BRIDGE_MAX_LENGTH_BLOCKS = 0;
    public static final int BRIDGE_MAX_LENGTH_MAX = 10_000;
    public static final int DEFAULT_BUOY_INTERVAL_BLOCKS = 32;
    public static final int BUOY_INTERVAL_MIN = 4;
    public static final int BUOY_INTERVAL_MAX = 256;
    public static final int DEFAULT_BRIDGE_PIER_INTERVAL = 6;
    public static final int BRIDGE_PIER_INTERVAL_MIN = 3;
    public static final int BRIDGE_PIER_INTERVAL_MAX = 32;
    public static final int DEFAULT_BRIDGE_PIER_WIDTH = 1;
    public static final int BRIDGE_PIER_WIDTH_MIN = 1;
    public static final int BRIDGE_PIER_WIDTH_MAX = 3;
    public static final int DEFAULT_BRIDGE_PIER_MAX_HEIGHT = 0;
    public static final int BRIDGE_PIER_MAX_HEIGHT_MIN = 0;
    public static final int BRIDGE_PIER_MAX_HEIGHT_MAX = 64;
    public static final int DEFAULT_BRIDGE_RAMP_SEGMENTS = 4;
    public static final int BRIDGE_RAMP_SEGMENTS_MAX = 12;
    public static final int DEFAULT_BRIDGE_MIN_WATER_DEPTH = 1;
    public static final int BRIDGE_MIN_WATER_DEPTH_MIN = 1;
    public static final int BRIDGE_MIN_WATER_DEPTH_MAX = 10;
    public static final int DEFAULT_BRIDGE_MIN_LENGTH = 5;
    public static final int BRIDGE_MIN_LENGTH_MIN = 1;
    public static final int BRIDGE_MIN_LENGTH_MAX = 32;
    public static final int DEFAULT_BRIDGE_MERGE_GAP = 8;
    public static final int BRIDGE_MERGE_GAP_MIN = 1;
    public static final int BRIDGE_MERGE_GAP_MAX = 32;
    /** Replace one bridge-deck center block with a sea lantern at this spacing. */
    public static final int DEFAULT_BRIDGE_LIGHT_INTERVAL_BLOCKS = 16;
    /** Only long water spans receive a small plan-view bend. */
    public static final int BRIDGE_CURVE_MIN_LENGTH_BLOCKS = 96;
    /** Maximum lateral offset of a curved bridge from its chord. */
    public static final double BRIDGE_CURVE_MAX_OFFSET_BLOCKS = 5.0D;

    public static final int DEFAULT_ROAD_WIDTH = 3;
    public static final int ROAD_WIDTH_MAX = 15;
    /** Target horizontal distance between ordinary roadside lamps. */
    public static final int DEFAULT_LAMP_INTERVAL = 32;
    public static final int LAMP_INTERVAL_MIN = 1;
    public static final int LAMP_INTERVAL_MAX = 2048;
    public static final int DEFAULT_ROAD_CLEAR_HEIGHT = 4;
    public static final int ROAD_CLEAR_HEIGHT_MIN = 1;
    public static final int ROAD_CLEAR_HEIGHT_MAX = 16;
    public static final int DEFAULT_TUNNEL_CLEAR_HEIGHT = 5;
    public static final int TUNNEL_CLEAR_HEIGHT_MIN = 2;
    public static final int TUNNEL_CLEAR_HEIGHT_MAX = 16;
    public static final int DEFAULT_AVERAGING_RADIUS = 8;
    public static final int AVERAGING_RADIUS_MIN = 0;
    public static final int AVERAGING_RADIUS_MAX = 64;
    public static final int DEFAULT_MAX_SLOPE_STEP = 1;
    public static final int MAX_SLOPE_STEP_MAX = 8;

    // ── 0.9.061 最大坡度三档（每 N 站抬 M 格）────────────────────────────────
    //
    // 实测站距：**1.0 格/站**（轴向）。依据是 PathPostProcessor.extractCenters：
    // 它沿样条累积弧长，`currentDist + 0.5 >= nextCenterDist` 就落一个中心站，
    // 落点后 `nextCenterDist = currentDist + 1.0` —— 也就是每 1.0 格弧长一站。
    // 斜向一步的整数距离是 sqrt(2) ≈ 1.414 格（同一个采样器四舍五入到整数格）。
    // 因为 stationSpacing 只在 slopeByArcLength=true 时才传给剖面约束（默认 false），
    // 所以「每两站 1 格」在轴向折算 **1/2.0 = 50%**、在纯斜向折算 **1/2.828 ≈ 35.4%**。
    // 下表按轴向 1.0 格/站折算；纯斜向的实际坡度会低约 29%。
    /** 每 10 站抬 1 格：轴向 ≈ 10%。 */
    public static final int GRADE_CAP_TIGHT_RISE_STATIONS = 10;
    /** 每 8 站抬 1 格：轴向 ≈ 12.5%。 */
    public static final int GRADE_CAP_NORMAL_RISE_STATIONS = 8;
    /** 每 7 站抬 1 格：轴向 ≈ 14.29%（0.9.062 起的默认档）。 */
    public static final int GRADE_CAP_LOOSE_RISE_STATIONS = 7;
    /** 三档共用的「抬多少格」：恒为 1（12.5% 这种档位靠拉长窗口表达，不靠分数格）。 */
    public static final int GRADE_CAP_RISE_BLOCKS = 1;
    /**
     * 默认档 = loose（每 7 站抬 1 格 ≈ 14.29%，0.9.062 起）。
     *
     * <p>0.9.061 的默认是 normal(12.5%)。实测收紧剖面并不能逼出之字：它只会让每一站都更想
     * 「切深」去贴合地形，于是切深被施工端拒绝，冲突原样留着。所以先把默认放到 loose，
     * 等坡罚量纲（0.9.062 ②）稳定下来再谈是否收紧。</p>
     */
    public static final int DEFAULT_GRADE_CAP_RISE_STATIONS = GRADE_CAP_LOOSE_RISE_STATIONS;
    /** 实测轴向站距（格/站），见上方注释与 PathPostProcessor.extractCenters。 */
    public static final double MEASURED_STATION_SPACING_BLOCKS = 1.0D;
    /**
     * 坡度档位的运行时调试开关：{@code -Droadweaver.gradeCap=tight|normal|loose}。
     * 非法值一律回落 {@code GradeCap.DEFAULT_MODE}（0.9.062 起是 loose）。这是调试口，不进配置界面（配置界面里仍然是
     * {@code max_slope_step_per_two_segments}，语义未变）。
     */
    public static final String GRADE_CAP_PROPERTY = "roadweaver.gradeCap";
    /**
     * 0.9.062（①）：长度上界「策略感知」的运行时开关。
     *
     * <p>{@code -Droadweaver.steepLengthCap=false}（也认 0/off）即回到 0.9.061 的唯一口径
     * —— 无论地形陡不陡都只用 {@code LENGTH_CAP_DETOUR_FACTOR}(1.6)。默认开。</p>
     */
    public static final String STEEP_LENGTH_CAP_PROPERTY = "roadweaver.steepLengthCap";
    /**
     * 0.9.062（②）：坡度罚计费基准的运行时开关。
     *
     * <p>默认 {@code length}（按水平长度收）。写成 {@code -Droadweaver.slopePenaltyBasis=legacy}
     * （也认 per-step/false/0/off）即逐位回到「按步收」的旧公式。</p>
     */
    public static final String SLOPE_PENALTY_BASIS_PROPERTY = "roadweaver.slopePenaltyBasis";
    /**
     * 0.9.062（④）：局部搜索返回 null 时是否保留原候选的运行时开关。
     *
     * <p>{@code -Droadweaver.keepNullCandidate=false}（也认 0/off）即回到 0.9.061 的行为 ——
     * 搜索结果（可能是 {@code null}）直接覆盖调用前的候选，也不打 {@code [REPAIR-NULL]}。
     * 这是纯回退口：默认开，因为旧行为是"静默吞掉候选 + 无日志"的那个 bug。</p>
     */
    public static final String KEEP_NULL_CANDIDATE_PROPERTY = "roadweaver.keepNullCandidate";

    public static final int DEFAULT_MAX_STRUCTURES_PER_ROAD = 3;
    public static final int MAX_STRUCTURES_PER_ROAD_MAX = 20;
    public static final int DEFAULT_SMALL_STRUCTURE_OFFSET = 8;
    public static final int DEFAULT_MEDIUM_STRUCTURE_OFFSET = 12;
    public static final int DEFAULT_LARGE_STRUCTURE_OFFSET = 16;
    public static final int STRUCTURE_OFFSET_MIN = 1;
    public static final int STRUCTURE_OFFSET_MAX = 64;

    public static final int DEFAULT_MAX_ROADSIDE_VILLAGES_PER_ROAD = 6;
    public static final int ROADSIDE_VILLAGE_MAX_PER_ROAD_MAX = 8;
    public static final int DEFAULT_ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS = 32;
    public static final int ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MIN = 32;
    public static final int ROADSIDE_VILLAGE_MIN_ROAD_SEGMENTS_MAX = 512;
    public static final int DEFAULT_ROADSIDE_VILLAGE_WINDOW_SEGMENTS = 24;
    public static final int ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MIN = 24;
    public static final int ROADSIDE_VILLAGE_WINDOW_SEGMENTS_MAX = 256;
    public static final int DEFAULT_ROADSIDE_VILLAGE_NODE_COUNT_MIN = 6;
    public static final int DEFAULT_ROADSIDE_VILLAGE_NODE_COUNT_MAX = 14;
    public static final int ROADSIDE_VILLAGE_NODE_COUNT_MIN = 1;
    public static final int ROADSIDE_VILLAGE_NODE_COUNT_MAX = 24;
    public static final int DEFAULT_ROADSIDE_VILLAGE_MAX_HEIGHT_DIFF = 4;
    public static final int ROADSIDE_VILLAGE_MAX_HEIGHT_DIFF_MAX = 12;
    public static final int DEFAULT_ROADSIDE_VILLAGE_MAX_LOCAL_SLOPE = 4;
    public static final int ROADSIDE_VILLAGE_MAX_LOCAL_SLOPE_MAX = 8;
    public static final double DEFAULT_ROADSIDE_VILLAGE_MIN_CURVE_ANGLE = 0.0;
    public static final double DEFAULT_ROADSIDE_VILLAGE_MAX_CURVE_ANGLE = 70.0;
    public static final int DEFAULT_ROADSIDE_VILLAGE_ROAD_BUFFER_BLOCKS = 6;
    public static final int ROADSIDE_VILLAGE_ROAD_BUFFER_MIN = 4;
    public static final int ROADSIDE_VILLAGE_ROAD_BUFFER_MAX = 64;
    public static final int DEFAULT_ROADSIDE_VILLAGE_SLOT_SPACING_BLOCKS = 6;
    public static final double DEFAULT_ROADSIDE_VILLAGE_SPAWN_CHANCE = 0.3;
    public static final int ROADSIDE_VILLAGE_SLOT_SPACING_MIN = 4;
    public static final int ROADSIDE_VILLAGE_SLOT_SPACING_MAX = 64;
    public static final int DEFAULT_ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL = 3;
    public static final int ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MIN = 2;
    public static final int ROADSIDE_VILLAGE_BUILDING_GAP_INTERVAL_MAX = 8;
    public static final int DEFAULT_ROADSIDE_VILLAGE_MAX_STEP_HEIGHT = 3;
    public static final int ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MIN = 1;
    public static final int ROADSIDE_VILLAGE_MAX_STEP_HEIGHT_MAX = 6;

    public static final int DEFAULT_ROADSIDE_VILLAGE_MAX_DISTANCE_FROM_CENTER = 96;
    public static final int ROADSIDE_VILLAGE_MAX_DISTANCE_MIN = 16;
    public static final int ROADSIDE_VILLAGE_MAX_DISTANCE_MAX = 128;

    public static final int DEFAULT_VILLAGE_ROAD_OFFSET = 60;
    public static final int DEFAULT_OTHER_STRUCTURE_ROAD_OFFSET = 15;
    public static final int ROAD_OFFSET_MAX = 256;

    public static final int DEFAULT_PREDICT_RADIUS_CHUNKS = 1024;

    public static final double DEFAULT_ORTHO_STEP_COST = 1.0;
    public static final double DEFAULT_DIAG_STEP_COST = 1.414;
    public static final int DEFAULT_ELEVATION_WEIGHT = 80;
    public static final int DEFAULT_BIOME_WEIGHT = 2;
    // ── 0.8.418「蜿蜒优先」新默认值（预设 C + dev0，实测依据见下）──────────────────
    // 10 种地形原型 × 300 种子 × 20 配置的指标矩阵（原型等权，见
    // roadweaver-参数调优.md §15）三套目标全部第一：
    //   meander +1.640（最差原型 +0.431）/ efficiency +0.888（最差 −0.167）/ balanced +1.264。
    // 形态变化：缓弯 +21.1%、急转弯（抖动）−44.4%、涉水 +11.7%、平均坡度 −17.2%。
    // 逐项依据：stabilityWeight 15→30（贴地、砍抖动）；hardGradeLimit 0.15→0.10（少硬爬）；
    // biomeCostMountain 0→25（山地略避让）；nearWaterCost 100→0（允许贴水岸走，反而多弯）；
    // deviationWeight 0.2→0（势场法原先没有这道闸，接线后 0.2 会真实生效并把路拉直——默认关，
    // 想「不许偏离直线太远」的玩家可以调回去）。
    public static final int DEFAULT_STABILITY_WEIGHT = 30;
    public static final int DEFAULT_WATER_DEPTH_WEIGHT = 160;
    public static final int DEFAULT_NEAR_WATER_COST = 0;
    public static final int DEFAULT_WATER_PROXIMITY_COST = 30;
    public static final double DEFAULT_HEURISTIC_WEIGHT = 2.0;
    public static final double DEFAULT_DEVIATION_WEIGHT = 0.0;

    // ── 0.8.409 分段/软惩罚代价参数（最细粒度奖励惩罚体系）──────────────────────
    // 坡度分段：温和坡度便宜、陡崖重罚。默认值 = 原线性 80 按「温和=30、陡崖=400」重排，
    // 使温和起伏不再被过度惩罚，而陡崖被强制绕开。gentleMax 以下的每格代价走 gentleWeight，
    // (gentleMax, cliffThreshold] 走 steepWeight，超过 cliffThreshold 走 cliffWeight。
    public static final int DEFAULT_ELEVATION_GENTLE_MAX = 2;
    public static final int DEFAULT_ELEVATION_GENTLE_WEIGHT = 30;
    public static final int DEFAULT_ELEVATION_STEEP_WEIGHT = 120;
    public static final int DEFAULT_ELEVATION_CLIFF_THRESHOLD = 5;
    public static final int DEFAULT_ELEVATION_CLIFF_WEIGHT = 400;

    // ── 0.8.409 麻花/重叠判定（禁麻花 + 允许丁字/十字）────────────────────────
    // 两条路方向点积 ≥ 此值视为「平行可合并」（麻花危险区）；< 此值则视为「相交」（丁字/十字，允许）。
    public static final double DEFAULT_BRAID_MIN_DIRECTION_DOT = 0.75;
    // 方向点积落在此值之上仍视为「接近平行」，即便未合并成功也禁止并排重叠（禁麻花）。
    // 与 minDirectionDot 构成危险区间 (minDirectionDot, nearParallelDot]。
    public static final double DEFAULT_BRAID_NEAR_PARALLEL_DOT = 0.90;
    // 禁止并排重叠（麻花）：方向接近平行但未合并的两条路，强制拉开或拒绝重叠。
    public static final boolean DEFAULT_BRAID_ALLOW_PARALLEL_OVERLAP = false;

    // ── 0.9.018 D2 平行双桥「方案 C：后者整体改道」───────────────────────────
    // 全批次两两预检到并排重叠时，是否让后一条路整体重算以绕开前一条路的走廊。
    public static final boolean DEFAULT_BRAID_REROUTE_ON_OVERLAP = true;
    // 改道时走廊两侧各留多少格净空（实测 12 格站距上限 + 路面半宽，取 18）。
    public static final int DEFAULT_BRAID_REROUTE_RADIUS = 18;
    // 改道结果的最大可接受长度比：超过原路这么多倍就认为「绕太远」，放弃改道保留原路。
    public static final double DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO = 1.5;
    // 一次重叠最多尝试改道几次（每次对仍未消除的重叠段重新下禁令）。
    public static final int DEFAULT_BRAID_REROUTE_ATTEMPTS = 2;

    // ── 0.9.056 规划期走廊吸引（CorridorAttract，真机 (236,-170) 平行双桥）────────
    // 同一批里后算的路被先算完的路中心线吸过去的半径（格）。0 = 关闭本机制。
    // 取 10：A* 步长为 8，路宽半幅约 3~5 格，10 格足以让后一条路并进同一条桥面走廊，
    // 又不会把「两条路本来就该分开」的走向也吸在一起。
    public static final int DEFAULT_CORRIDOR_ATTRACT_RADIUS = 10;
    // 命中优惠带时步进代价的乘数。0.35 = 沿着已有走廊走比另开新路便宜约 65%；
    // 1.0 = 关闭本机制（逐位退回没有吸引的旧行为）。
    public static final double DEFAULT_CORRIDOR_ATTRACT_FACTOR = 0.35;

    // ── 0.9.057 规划期「并行 → 协商 → 并行」三段式 ───────────────────────────
    /**
     * 过河点聚簇半径（格）：XZ 距离 ≤ 这个值的过河点算「同一条河上的同一个跨越」。
     *
     * <p>取 24 与生成后处理 {@code BridgeShareDetector.DEFAULT_RADIUS} 同口径 ——
     * 规划期要认成「同一跨越」的距离，和事后判定「这两座桥该不该合并」的距离必须是同一个数，
     * 否则规划期协商出来的共享点，事后仍会被判成两座桥。</p>
     *
     * <p>这是<b>编译期常量而不是配置项</b>：本次只要求把串行改回并行 + 协商，
     * 没有要求暴露新旋钮；{@code corridorAttractRadius} / {@code corridorAttractFactor}
     * 仍然是这道机制的总开关。</p>
     */
    public static final int DEFAULT_CROSSING_CLUSTER_RADIUS = 24;

    /**
     * 数据库锁超时（毫秒）。
     *
     * <p>⚠️ 2026-11-15 核对：**全仓零引用**，**既没有锁、也没有超时判断**。
     * 本仓库的持久化走 RoadChunkStore / index.json 事务，全仓没有 tryLock / FileLock 调用；
     * 这个常量是早期设计的遗留。保留是因为它是 public 的 ——
     * 可能有外部 mod 引用，删掉会让下游编译失败。</p>
     */
    public static final int DB_LOCK_TIMEOUT_MS = 10_000;

    public static final int BLOCK_UPDATE_FLAG = 3;
    public static final int BELOW_DEPTH_1 = 1;
    public static final int BELOW_DEPTH_2 = 2;

    // 2026-11-13：GRADIENT_DESCENT_* / POTENTIAL_FIELD_* 五个常量随旧 A* 寻路器一并删除。
    public static final double DEFAULT_CONTOUR_DISCOUNT = 0.55;
    public static final double DEFAULT_SOFT_GRADE_LIMIT = 0.08;
    // 0.8.418：0.15 → 0.10。收紧硬坡闸门后道路更少硬爬（急转弯 −45%、贴地更好），
    // 三套目标同时改善（见上方 0.8.418 新默认值说明）。
    public static final double DEFAULT_HARD_GRADE_LIMIT = 0.10;
    /**
     * 坡罚 soft 档单价：每超出免罚额度 1 格竖向爬升收多少。
     *
     * <p><b>0.9.063 重标定：600 → 780。</b>0.9.062 把计费基准从「按步收」改成「按长度收」
     * 之后量纲对了，但绝对刻度比 0.9.061 松了 —— 默认参照（step=8、softLimit=0.08、
     * hardLimit=0.10）下「正交爬 1 格」从 7837.5 掉到 6216.0、「对角爬 1 格」从 62.9126
     * 掉到 56.9420。坡罚便宜 ⇒ A* 更愿意硬爬 ⇒ 离盘山路更远，所以本版只重标单价。</p>
     *
     * <p><b>推导。</b>按长度收的罚额 = 单价 × 超出格数，而两个参照点的「超出格数」只由
     * step / limit 决定、与单价无关，因此整档乘一个系数 m 就能同时把两个参照点抬回去：</p>
     * <pre>
     *   正交 L=8：overSoft=1-0.08×8   =0.36      overHard=1-0.10×8    =0.20
     *             每单位单价贡献 = 600×0.36+30000×0.20 = 6216.0
     *   对角 L=8√2：overSoft=1-0.08×11.3137=0.0949033 overHard=0（0.10×11.3137&gt;1）
     *             每单位单价贡献 = 600×0.0949033 = 56.9420
     *   紧约束（正交）：6216.0·m ≥ 7837.5 ⇒ m ≥ 1.260876
     *   松约束（对角）：56.9420·m ≥ 62.9126 ⇒ m ≥ 1.104833
     * </pre>
     * <p>取 <b>m = 1.30</b>：比紧约束再留 3.2% 余量，两个常量都停在两位有效数字
     * （600→780、30000→39000），soft:hard 仍是 1:50 —— 只改绝对刻度，不改两档的相对权重。
     * 正步 6216.0→8080.8（旧 7837.5 的 103.1%）、斜步 56.9420→74.0246（旧 62.9126 的 117.7%），
     * 平路（每步 rise ≤ softLimit×L）罚额仍恒为 0。</p>
     */
    public static final double DEFAULT_SOFT_GRADE_PENALTY = 30.0;
    // 陡坡罚款（hard 档）。0.9.063：30000 → 39000，与 soft 用同一个系数 1.30（保持 1:50）。
    // 历史注记：40000 种子离线搜索的最优值（原 6000）。仍是可配置旋钮，配置里可覆盖。
    public static final double DEFAULT_HARD_GRADE_PENALTY = 300.0;
    /**
     * 0.9.061 及以前的坡罚单价（「按步收」时代的出厂默认值）。
     *
     * <p>只用于两件事：①0.9.063 的配置迁移（老配置里正好写着这两个数就升到新出厂值）；
     * ②启动期 {@code [GRADE-PENALTY]} 里重算 {@code oldOrthPenalty}/{@code oldDiagPenalty}
     * 做对照。<b>不要再拿它们当默认值。</b></p>
     */
    public static final double LEGACY_SOFT_GRADE_PENALTY = 600.0;
    public static final double LEGACY_HARD_GRADE_PENALTY = 30000.0;
    /**
     * 0.9.061 在两个参照点上的实测总坡罚（默认 step=8 / softLimit=0.08 / hardLimit=0.10）。
     *
     * <p>这是 0.9.063「不得低于」的地板值，写死成常量供标定测试断言：</p>
     * <pre>
     *   正交：600×(0.125-0.08)/0.08 + 30000×(0.125-0.10)/0.10 = 337.5 + 7500 = 7837.5
     *   对角：600×(0.0883883-0.08)/0.08 = 62.9126073624（hard 档不触发）
     * </pre>
     * <p>数值随 step/limit 变，随单价不变 —— 换 step 之后要重算这两个地板。</p>
     */
    public static final double LEGACY_ORTHO_REFERENCE_PENALTY = 7837.5;
    public static final double LEGACY_DIAG_REFERENCE_PENALTY = 62.9126073624;
    public static final double DEFAULT_GRADIENT_ALIGN_PENALTY = 80.0;
    public static final double DEFAULT_PF_WATER_BASE_PENALTY = 800.0;

    // ── 蜿蜒 / 群系走向（0.8.413 起）────────────────────────────────────────
    // 逐群系类别路由代价（正=避让，负=偏好）。默认只保留水=36（与旧 BIOME_BASE_COST*3 一致），
    // 其余类别中性 0，由调参决定是否给山/林/沼泽/沙漠赋予偏好或避让。
    public static final double DEFAULT_BIOME_COST_WATER = 36.0;
    public static final double DEFAULT_BIOME_COST_PLAINS = 0.0;
    public static final double DEFAULT_BIOME_COST_FOREST = 0.0;
    // 0.8.418：山地 0 → 25（略微避让高山，帮道路走山谷/垭口而非硬翻山头）。
    public static final double DEFAULT_BIOME_COST_MOUNTAIN = 25.0;
    public static final double DEFAULT_BIOME_COST_SWAMP = 0.0;
    public static final double DEFAULT_BIOME_COST_DESERT = 0.0;
    public static final double DEFAULT_BIOME_COST_OTHER = 0.0;
    // 等高线跟随强度：>1 放大柔和地形上的等高线折扣，产生更蜿蜒的走向。
    public static final double DEFAULT_CONTOUR_FOLLOW_WEIGHT = 1.0;

    // 竖向代价模型：true=0.8.409 三段台阶（默认，Q1 已接入）；false=旧二次式
    // （高度²×elevationWeight×0.5），保留以便对比与回退。
    public static final boolean DEFAULT_ELEVATION_COST_SEGMENTED = true;

    public static final int SPATIAL_INDEX_GRID_SIZE = 8;
    public static final int SPATIAL_INDEX_GRID_SHIFT = 3;
    public static final int SPATIAL_INDEX_MAX_CACHED_CHUNKS = 512;
    public static final int SPATIAL_INDEX_LRU_INITIAL_CAPACITY = 64;
    public static final float SPATIAL_INDEX_LRU_LOAD_FACTOR = 0.75f;
    public static final int SPATIAL_INDEX_SEARCH_MARGIN = 4;
    public static final int SPATIAL_INDEX_MAX_MARGIN = 127;

    public static final int MIN_ROAD_SEGMENTS_FOR_STRUCTURE = 10;
    public static final int STRUCTURE_PLACEMENT_WINDOW_SIZE = 10;
    public static final int MAX_STRUCTURE_SLOPE = 3;

    public static final int CHUNK_SIZE_BLOCKS = 16;

    // 道路吸附后处理
    public static final int ROAD_SNAP_THRESHOLD = 12;
    public static final int ROAD_SNAP_SPLIT_THRESHOLD = 24;
    public static final int ROAD_SNAP_MIN_RUN_LENGTH = 3;
    public static final int ROAD_SNAP_GRID_SIZE = 8;
    public static final int ROAD_SNAP_GRID_SHIFT = 3;
    public static final int ROAD_SNAP_TRANSITION_SEGMENTS = 3;
}
