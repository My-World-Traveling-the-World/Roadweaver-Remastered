/* 文件职责：PathfindingCostConfig 57 个键的元数据 —— 选线代价函数的所有旋钮。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code PathfindingCostConfig} 的键元数据表。
 *
 * <h2>为什么这一类最需要元数据</h2>
 * <p>这个类有 57 个代价旋钮，是历史包袱最重的一块。已实证的问题：</p>
 * <ul>
 *   <li><b>出厂默认算法曾经是 GRADIENT_DESCENT</b>，而生产与调参基线都是
 *       POTENTIAL_FIELD ⇒ 新装、重置、缺键的玩家会落进 GD，
 *       一次性让 20 多个旋钮变成死键。现在默认已改回 POTENTIAL_FIELD。</li>
 *   <li><b>38 倍坡罚不对称</b>：坡度罚按步收而基础代价按长度收，
 *       同样爬 1 格，正交步总代价 8008.5 vs 对角步 210.9
 *       ⇒ ±8 幅锯齿成为<b>全局唯一最优解</b>。{@code slopePenaltyByLength}
 *       就是修这个的第 0 层开关。</li>
 *   <li><b>5 个群系代价是死键</b>：{@code PathTerrainField.biomeCategory()}
 *       全仓只有默认实现、0 处覆写，而默认实现只返回 WATER 与 OTHER
 *       ⇒ Plains/Forest/Mountain/Swamp/Desert 五档永远不会被产生。</li>
 * </ul>
 *
 * <h2>本表的状态列是逐键取证得来的，不是猜的</h2>
 * <p>标 {@link ConfigStatus#UNWIRED} 的 6 个键，依据是把每个键名放到
 * 全部 462 个非配置源文件里数引用：5 个群系代价只在
 * {@code PlannedPathFingerprintService}（算指纹）里出现，
 * 真正的代价计算点读取。</p>
 *
 * <h2>★ 2026-11-14：10 个死键已物理删除</h2>
 * <p>下面这 10 个键当时被标成 {@code ACTIVE}，但严格全仓复核（扫
 * {@code .java}/字符串引用，排除 config 自身）<b>剩余引用全部为 0</b> ——
 * 属元数据标注错误（该标 UNWIRED 却标了 ACTIVE）。既然旧 A* 寻路器整族已删
 * （重制「旧的全部删除」），它们再没有接线的那一天，于是从
 * {@code PathfindingCostConfig} / 本表 / {@code ConfigService} / {@code RoadConstants}
 * 一并删除：{@code accumulatedSlopeWeight}、{@code accumulatedSlopeWindow}、
 * {@code braidAllowJunctionCross}、{@code contourBonus}、{@code contourFactorFloor}、
 * {@code deviationFreeRadius}、{@code deviationHardCap}、{@code deviationSquared}、
 * {@code nearWaterDecayRadius}、{@code nearWaterDecayRate}。</p>
 * <p>删掉不丢任何能力 —— 它们本来就没生效；玩家配置文件里残留的旧键会被
 * GSON 当未知字段忽略。</p>
 *
 * @see ConfigKey
 * @see ConfigKeyTable
 * @see net.shiroha233.roadweaver.config.sub.PathfindingCostConfig
 */
public final class PathfindingCostKeys {

    private PathfindingCostKeys() {}

    /** 57 个键，顺序与 {@code PathfindingCostConfig} 实例字段声明顺序一致（2 个 transient 字段除外）。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.decimal("orthoStepCost", RoadConstants.DEFAULT_ORTHO_STEP_COST, 0.0, 1000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "正交步代价", "Orthogonal step cost",
                    "沿 x/z 方向走一步的基础代价。与对角步代价的比例决定路径偏好哪个方向",
                    "代价", ""),

            ConfigKey.decimal("diagStepCost", RoadConstants.DEFAULT_DIAG_STEP_COST, 0.0, 1000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "对角步代价", "Diagonal step cost",
                    "沿斜向走一步的基础代价",
                    "代价", "★ 与正交步的比值是 38 倍坡罚不对称的根源之一：两者若不满足 sqrt(2) 比例，路径会系统性偏好呈锯齿"),

            ConfigKey.integer("aStarStep", RoadConstants.DEFAULT_ASTAR_STEP, 1, 32,
                    ConfigCategory.PERFORMANCE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "寻路步长", "A* step",
                    "A* 搜索网格的间隔。8 表示 8 格一个节点",
                    "格", "新架构里步长只是搜索粒度，几何由弹性带在方块坐标系里做"),

            ConfigKey.integer("aStarMaxSteps", RoadConstants.DEFAULT_ASTAR_MAX_STEPS, 100, 1000000,
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "寻路最大步数", "A* max steps",
                    "单次搜索的节点上限，到上限就放弃这条连接",
                    "步", ""),

            ConfigKey.enumeration("pathfindingAlgorithm", "BAND",
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "寻路算法", "Pathfinding algorithm",
                    "决定用哪套求解器选线",
                    "只剩 BAND（Finsler 度量 + Fast Marching + 弹性带）。旧 A* 系已全部删除，此项不可切换"),

            ConfigKey.decimal("heuristicWeight", RoadConstants.DEFAULT_HEURISTIC_WEIGHT, 0.0, 10.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启发权重", "Heuristic weight",
                    "A* 启发函数的倍数。大于 1 更快但不保证最优",
                    "倍", ""),

            ConfigKey.decimal("deviationWeight", RoadConstants.DEFAULT_DEVIATION_WEIGHT, 0.0, 1000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "偏离权重", "Deviation weight",
                    "偏离起点终点连线的惩罚权重，防止路绕太远",
                    "权重", ""),

            ConfigKey.decimal("softGradeLimit", RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, 0.0, 2.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "软坡度上限", "Soft grade limit",
                    "超过此坡度开始额外计费，但还能走",
                    "比例", ""),

            ConfigKey.decimal("hardGradeLimit", RoadConstants.DEFAULT_HARD_GRADE_LIMIT, 0.0, 4.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "硬坡度上限", "Hard grade limit",
                    "超过此坡度视为不可通行",
                    "比例", ""),

            ConfigKey.decimal("softGradePenalty", RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "软坡惩罚", "Soft grade penalty",
                    "超软限后每单位坡度的额外代价",
                    "代价", ""),

            ConfigKey.decimal("hardGradePenalty", RoadConstants.DEFAULT_HARD_GRADE_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "硬坡惩罚", "Hard grade penalty",
                    "逼近硬限时的惩罚强度",
                    "代价", ""),

            ConfigKey.bool("slopePenaltyByLength", true,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "坡罚按长度计", "Slope penalty by length",
                    "开启时坡度罚按实际行走长度收，关闭则回到旧的按步收",
                    "★ 出厂 true 可被系统属性 -Droadweaver.slopePenaltyBasis 覆盖（legacy/per-step/false/0 一律回退旧公式）"),

            ConfigKey.bool("elevationSegmentedCost", RoadConstants.DEFAULT_ELEVATION_COST_SEGMENTED,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "分段高程代价", "Segmented elevation cost",
                    "按缓坡/陡坡/悬崖分段计费，而不是线性插值",
                    ""),

            ConfigKey.decimal("gradientAlignPenalty", RoadConstants.DEFAULT_GRADIENT_ALIGN_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "逆坡惩罚", "Gradient alignment penalty",
                    "沿坡上行/下行的方向性惩罚，让路更愿意贴着等高线走",
                    "代价", ""),

            ConfigKey.decimal("contourDiscount", RoadConstants.DEFAULT_CONTOUR_DISCOUNT, 0.0, 10.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "等高线折扣", "Contour discount",
                    "贴着等高线走时的代价折扣",
                    "倍", ""),

            ConfigKey.decimal("contourFollowWeight", RoadConstants.DEFAULT_CONTOUR_FOLLOW_WEIGHT, 0.0, 100.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "等高线跟随权重", "Contour follow weight",
                    "鼓励沿等高线走的权重",
                    "权重", ""),

            ConfigKey.integer("elevationWeight", RoadConstants.DEFAULT_ELEVATION_WEIGHT, 0, 1000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "高程权重", "Elevation weight",
                    "爬升在总代价里的基础权重",
                    "权重", ""),

            ConfigKey.integer("elevationGentleMax", RoadConstants.DEFAULT_ELEVATION_GENTLE_MAX, 0, 64,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "缓坡上限", "Elevation gentle max",
                    "坡度不超过这个值算缓坡",
                    "格", ""),

            ConfigKey.integer("elevationGentleWeight", RoadConstants.DEFAULT_ELEVATION_GENTLE_WEIGHT, 0, 1000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "缓坡权重", "Elevation gentle weight",
                    "缓坡段的爬升权重",
                    "权重", ""),

            ConfigKey.integer("elevationSteepWeight", RoadConstants.DEFAULT_ELEVATION_STEEP_WEIGHT, 0, 1000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "陡坡权重", "Elevation steep weight",
                    "陡坡段的爬升权重。远大于缓坡权重",
                    "权重", ""),

            ConfigKey.integer("elevationCliffThreshold", RoadConstants.DEFAULT_ELEVATION_CLIFF_THRESHOLD, 0, 64,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "悬崖阈值", "Elevation cliff threshold",
                    "单步高差超过这个值算悬崖",
                    "格", ""),

            ConfigKey.integer("elevationCliffWeight", RoadConstants.DEFAULT_ELEVATION_CLIFF_WEIGHT, 0, 100000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "悬崖权重", "Elevation cliff weight",
                    "跨悬崖的惩罚权重，通常极大以阻止直接翻越",
                    "权重", ""),

            // BAND 只消费水/陆差价；保留原键，让旧配置和缓存身份继续使用同一组值。
            ConfigKey.integer("biomeWeight", RoadConstants.DEFAULT_BIOME_WEIGHT, 0, 1000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "水陆代价权重", "Water/land cost weight",
                    "放大水域与非水域的差价；0 关闭这一项",
                    "权重", ""),

            ConfigKey.integer("stabilityWeight", RoadConstants.DEFAULT_STABILITY_WEIGHT, 0, 1000,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "稳定性权重", "Stability weight",
                    "地形稳定度（陡坡/悬崖密度）在总代价里的权重",
                    "权重", ""),

            ConfigKey.integer("waterDepthWeight", RoadConstants.DEFAULT_WATER_DEPTH_WEIGHT, 0, 1000,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "水深权重", "Water depth weight",
                    "水深在过水代价里的权重",
                    "权重", ""),

            ConfigKey.decimal("biomeCostPlains", RoadConstants.DEFAULT_BIOME_COST_PLAINS, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.UNWIRED,
                    "平原群系代价", "Plains biome cost",
                    "按群系给道路加不同代价，用来让选线偏好平原、避开森林与山地",
                    "代价",
                    "★ 已实证未接线：PathTerrainField.biomeCategory() 全仓只有默认实现、0 处覆写，默认实现只返回 WATER 与 OTHER ⇒ 本类别永远不会被产生。全仓对本键的引用只出现在 PlannedPathFingerprintService（算指纹用），路径代价里从未使用"),

            ConfigKey.decimal("biomeCostForest", RoadConstants.DEFAULT_BIOME_COST_FOREST, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.UNWIRED,
                    "森林群系代价", "Forest biome cost",
                    "按群系给道路加不同代价，用来让选线偏好平原、避开森林与山地",
                    "代价",
                    "★ 已实证未接线：PathTerrainField.biomeCategory() 全仓只有默认实现、0 处覆写，默认实现只返回 WATER 与 OTHER ⇒ 本类别永远不会被产生。全仓对本键的引用只出现在 PlannedPathFingerprintService（算指纹用），路径代价里从未使用"),

            ConfigKey.decimal("biomeCostMountain", RoadConstants.DEFAULT_BIOME_COST_MOUNTAIN, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.UNWIRED,
                    "山地群系代价", "Mountain biome cost",
                    "按群系给道路加不同代价，用来让选线偏好平原、避开森林与山地",
                    "代价",
                    "★ 已实证未接线：PathTerrainField.biomeCategory() 全仓只有默认实现、0 处覆写，默认实现只返回 WATER 与 OTHER ⇒ 本类别永远不会被产生。全仓对本键的引用只出现在 PlannedPathFingerprintService（算指纹用），路径代价里从未使用"),

            ConfigKey.decimal("biomeCostSwamp", RoadConstants.DEFAULT_BIOME_COST_SWAMP, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.UNWIRED,
                    "沼泽群系代价", "Swamp biome cost",
                    "按群系给道路加不同代价，用来让选线偏好平原、避开森林与山地",
                    "代价",
                    "★ 已实证未接线：PathTerrainField.biomeCategory() 全仓只有默认实现、0 处覆写，默认实现只返回 WATER 与 OTHER ⇒ 本类别永远不会被产生。全仓对本键的引用只出现在 PlannedPathFingerprintService（算指纹用），路径代价里从未使用"),

            ConfigKey.decimal("biomeCostDesert", RoadConstants.DEFAULT_BIOME_COST_DESERT, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.UNWIRED,
                    "沙漠群系代价", "Desert biome cost",
                    "按群系给道路加不同代价，用来让选线偏好平原、避开森林与山地",
                    "代价",
                    "★ 已实证未接线：PathTerrainField.biomeCategory() 全仓只有默认实现、0 处覆写，默认实现只返回 WATER 与 OTHER ⇒ 本类别永远不会被产生。全仓对本键的引用只出现在 PlannedPathFingerprintService（算指纹用），路径代价里从未使用"),

            ConfigKey.decimal("biomeCostWater", RoadConstants.DEFAULT_BIOME_COST_WATER, -100000.0D, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "水域选线代价", "Water routing cost",
                    "比非水域代价高时优先绕水或缩短过水路段；不会禁止过水",
                    "代价", "选线只看水陆价差；森林等细分群系不参与"),

            ConfigKey.decimal("biomeCostOther", RoadConstants.DEFAULT_BIOME_COST_OTHER, -100000.0D, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "非水域选线代价", "Land routing cost",
                    "和水域代价比较；两者相同则不对选线加价",
                    "代价", "按水列和河海标记分档，价差只用于粗选线"),

            ConfigKey.integer("nearWaterCost", RoadConstants.DEFAULT_NEAR_WATER_COST, 0, 100000,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "临水代价", "Near-water cost",
                    "靠近水体的额外代价",
                    "代价", ""),

            ConfigKey.integer("waterProximityCost", RoadConstants.DEFAULT_WATER_PROXIMITY_COST, 0, 100000,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "近水距离代价", "Water proximity cost",
                    "按与水的距离分档收费",
                    "代价", ""),

            ConfigKey.decimal("pfWaterBasePenalty", RoadConstants.DEFAULT_PF_WATER_BASE_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "势场过水基础代价", "Potential-field water base penalty",
                    "势场寻路里跨水的固定代价",
                    "代价", ""),

            ConfigKey.integer("corridorAttractRadius", RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS, 0, 512,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "走廊吸引半径", "Corridor attract radius",
                    "靠近已规划走廊时的吸引作用半径，让多条路自然汇拢",
                    "格", ""),

            ConfigKey.decimal("corridorAttractFactor", RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR, 0.0, 10.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "走廊吸引系数", "Corridor attract factor",
                    "走廊吸引的强度",
                    "系数", ""),

            ConfigKey.decimal("braidMinDirectionDot", RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT, -1.0, 1.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "并排判定方向点积", "Braid min direction dot",
                    "两条路方向点积大于此值才算同向并排",
                    "点积", ""),

            ConfigKey.decimal("braidNearParallelDot", RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT, -1.0, 1.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "近平行点积", "Braid near-parallel dot",
                    "判定「近平行」的点积阈值，比同向宽松",
                    "点积", ""),

            ConfigKey.bool("braidAllowParallelOverlap", RoadConstants.DEFAULT_BRAID_ALLOW_PARALLEL_OVERLAP,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "允许并行重叠", "Allow parallel overlap",
                    "允许两条路平行重叠而不做处理",
                    ""),

            ConfigKey.bool("braidRerouteOnOverlap", RoadConstants.DEFAULT_BRAID_REROUTE_ON_OVERLAP,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "重叠时改道", "Reroute on overlap",
                    "检测到并排/重叠时尝试改道",
                    "实测改道 A* 一次要约 7.5 秒且常常返回 null"),

            ConfigKey.integer("braidRerouteRadius", RoadConstants.DEFAULT_BRAID_REROUTE_RADIUS, 0, 512,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "改道半径", "Braid reroute radius",
                    "改道时允许偏离原走廊的距离",
                    "格", ""),

            ConfigKey.decimal("braidRerouteMaxLengthRatio", RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO, 1.0, 10.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "改道长度比上限", "Braid reroute max length ratio",
                    "改道后长度不超过原长度的这个倍数，否则放弃改道",
                    "倍", ""),

            ConfigKey.decimal("headingFlipCost", 16.0D, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "掉头代价", "Heading flip cost",
                    "原地掉头的固定代价，防止路径来回折返",
                    "代价", ""),

            ConfigKey.decimal("headingCurveCost", 0.0D, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "转向代价", "Heading curve cost",
                    "每次转向的代价，鼓励走直",
                    "代价", ""),

            ConfigKey.decimal("headingShortLegBlocks", 48.0D, 0.0, 512.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "短腿判定长度", "Heading short leg blocks",
                    "两点间距小于此值算「短腿」",
                    "格", ""),

            ConfigKey.decimal("headingShortLegMultiplier", 3.0D, 1.0, 100.0D,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "短腿代价倍数", "Heading short leg multiplier",
                    "短腿段的转向代价倍数，用来压制密集小折角",
                    "倍", "")
    );
}
