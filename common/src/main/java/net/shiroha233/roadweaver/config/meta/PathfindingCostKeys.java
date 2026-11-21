/* 文件职责：PathfindingCostKeys当前有效配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * BAND网格、水陆选线、桥隧经济判据与局部修复使用的配置元数据。
 * 阈值不是通行禁令；桥隧判据按超限爬升量计价，界面须与实际公式一致。
 */
public final class PathfindingCostKeys {

    private PathfindingCostKeys() {}

    /** 与配置实例字段逐项对应，删除无效字段时同步删除元数据。 */
    public static final List<ConfigKey> ALL = List.of(
            // 新算法主参数与旧局部修复成本分开命名，避免玩家调错生产阶段。
            ConfigKey.decimal("finslerGradeWeight", 1.0D, 0.0D, 100.0D,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "主选线坡度权重", "Main route grade weight",
                    "同时影响测地拓扑和BAND选线。越大越偏好缓坡；0只关闭软坡罚，不关闭施工限坡",
                    "权重", "默认1保持原模型；仅影响重新规划的道路"),
            ConfigKey.decimal("finslerObstacleWeight", 4.0D, 0.0D, 100.0D,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "主选线软避障权重", "Main route soft obstacle weight",
                    "增加靠近软障碍的成本。0也不能取消结构、巨树或跨边硬保护",
                    "权重", "不等于建筑保护距离"),
            ConfigKey.decimal("elasticBandStiffness", 1.0D, 0.01D, 100.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "曲线保形强度", "Curve shape retention",
                    "越大越贴近回溯折线，越小平滑越强。仍保留端点、硬保护和站距约束",
                    "权重", "默认1；最终观感需真机对照"),

            ConfigKey.decimal("orthoStepCost", RoadConstants.DEFAULT_ORTHO_STEP_COST, 0.0, 1000.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "正交步代价", "Orthogonal step cost",
                    "沿 x/z 方向走一步的基础代价。与对角步代价的比例决定路径偏好哪个方向",
                    "代价", ""),

            ConfigKey.decimal("diagStepCost", RoadConstants.DEFAULT_DIAG_STEP_COST, 0.0, 1000.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "对角步代价", "Diagonal step cost",
                    "沿斜向走一步的基础代价",
                    "代价", "局部修复搜索的对角步成本"),

            ConfigKey.integer("pathGridStep", RoadConstants.DEFAULT_PATH_GRID_STEP, RoadConstants.PATH_GRID_STEP_MIN, RoadConstants.PATH_GRID_STEP_MAX,
                    ConfigCategory.PERFORMANCE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "寻路网格间距", "Path grid spacing",
                    "BAND/Fast Marching及局部修复的采样间隔，8表示8格一个节点",
                    "格", "新架构里步长只是搜索粒度，几何由弹性带在方块坐标系里做"),

            ConfigKey.decimal("heuristicWeight", RoadConstants.DEFAULT_HEURISTIC_WEIGHT, 0.0, 10.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "启发权重", "Heuristic weight",
                    "局部地形修复搜索的启发倍数；不改变BAND的FMM求解",
                    "倍", ""),

            ConfigKey.decimal("deviationWeight", RoadConstants.DEFAULT_DEVIATION_WEIGHT, 0.0, 1000.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "偏离权重", "Deviation weight",
                    "偏离起点终点连线的惩罚权重，防止路绕太远",
                    "权重", ""),

            ConfigKey.decimal("softGradeLimit", RoadConstants.DEFAULT_SOFT_GRADE_LIMIT, 0.0, 1.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "软坡度上限", "Soft grade limit",
                    "桥隧经济判据超过此坡度开始计算软坡度费用",
                    "比例", ""),

            ConfigKey.decimal("hardGradeLimit", RoadConstants.DEFAULT_HARD_GRADE_LIMIT, 0.0, 1.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "硬坡度上限", "Hard grade limit",
                    "桥隧经济判据超过此坡度追加硬坡度费用，不是不可通行判定",
                    "比例", ""),

            ConfigKey.decimal("softGradePenalty", RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "软坡惩罚", "Soft grade penalty",
                    "超过软阈值的爬升格数单价，用于翻山与挖隧道比价",
                    "代价", ""),

            ConfigKey.decimal("hardGradePenalty", RoadConstants.DEFAULT_HARD_GRADE_PENALTY, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "硬坡惩罚", "Hard grade penalty",
                    "超过硬阈值的爬升格数单价，叠加在软坡费用之上",
                    "代价", ""),

            ConfigKey.integer("elevationWeight", RoadConstants.DEFAULT_ELEVATION_WEIGHT, 0, 1000,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高程权重", "Elevation weight",
                    "爬升在总代价里的基础权重",
                    "权重", ""),

            // BAND实际消费水陆差价，这两个类别及权重仍是有效控制项。
            ConfigKey.integer("biomeWeight", RoadConstants.DEFAULT_BIOME_WEIGHT, 0, 1000,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "水陆代价权重", "Water/land cost weight",
                    "放大水域与非水域的差价；0 关闭这一项",
                    "权重", ""),

            ConfigKey.integer("stabilityWeight", RoadConstants.DEFAULT_STABILITY_WEIGHT, 0, 1000,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "稳定性权重", "Stability weight",
                    "地形稳定度（陡坡/悬崖密度）在总代价里的权重",
                    "权重", ""),

            ConfigKey.integer("waterDepthWeight", RoadConstants.DEFAULT_WATER_DEPTH_WEIGHT, 0, 1000,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "水深权重", "Water depth weight",
                    "水深在过水代价里的权重",
                    "权重", ""),

            ConfigKey.decimal("biomeCostWater", RoadConstants.DEFAULT_BIOME_COST_WATER, -100000.0D, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "水域选线代价", "Water routing cost",
                    "比非水域代价高时优先绕水或缩短过水路段；不会禁止过水",
                    "代价", "选线只看水陆价差；森林等细分群系不参与"),

            ConfigKey.decimal("biomeCostOther", RoadConstants.DEFAULT_BIOME_COST_OTHER, -100000.0D, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "非水域选线代价", "Land routing cost",
                    "和水域代价比较；两者相同则不对选线加价",
                    "代价", "按水列和河海标记分档，价差只用于粗选线"),

            ConfigKey.integer("nearWaterCost", RoadConstants.DEFAULT_NEAR_WATER_COST, 0, 100000,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "临水代价", "Near-water cost",
                    "靠近水体的额外代价",
                    "代价", ""),

            ConfigKey.integer("corridorAttractRadius", RoadConstants.DEFAULT_CORRIDOR_ATTRACT_RADIUS, 0, 64,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "走廊吸引半径", "Corridor attract radius",
                    "靠近已规划走廊时的吸引作用半径，让多条路自然汇拢",
                    "格", ""),

            ConfigKey.decimal("corridorAttractFactor", RoadConstants.DEFAULT_CORRIDOR_ATTRACT_FACTOR, 0.05D, 1.0D,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "走廊吸引系数", "Corridor attract factor",
                    "走廊吸引的强度",
                    "系数", ""),

            ConfigKey.decimal("braidMinDirectionDot", RoadConstants.DEFAULT_BRAID_MIN_DIRECTION_DOT, 0.0D, 1.0D,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "并排判定方向点积", "Braid min direction dot",
                    "两条路方向点积大于此值才算同向并排",
                    "点积", ""),

            ConfigKey.decimal("braidNearParallelDot", RoadConstants.DEFAULT_BRAID_NEAR_PARALLEL_DOT, 0.0D, 1.0D,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "近平行点积", "Braid near-parallel dot",
                    "判定「近平行」的点积阈值，比同向宽松",
                    "点积", ""),

            ConfigKey.bool("braidAllowParallelOverlap", RoadConstants.DEFAULT_BRAID_ALLOW_PARALLEL_OVERLAP,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "允许并行重叠", "Allow parallel overlap",
                    "允许两条路平行重叠而不做处理",
                    ""),

            ConfigKey.bool("braidRerouteOnOverlap", RoadConstants.DEFAULT_BRAID_REROUTE_ON_OVERLAP,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "重叠时改道", "Reroute on overlap",
                    "检测到并排/重叠时尝试改道",
                    "重叠修复使用受限走廊重新求解，不保证能找到合规绕行"),

            ConfigKey.integer("braidRerouteRadius", RoadConstants.DEFAULT_BRAID_REROUTE_RADIUS, 4, 64,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "改道半径", "Braid reroute radius",
                    "改道时允许偏离原走廊的距离",
                    "格", ""),

            ConfigKey.decimal("braidRerouteMaxLengthRatio", RoadConstants.DEFAULT_BRAID_REROUTE_MAX_LENGTH_RATIO, 1.05D, 4.0D,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "改道长度比上限", "Braid reroute max length ratio",
                    "改道后长度不超过原长度的这个倍数，否则放弃改道",
                    "倍", ""),

            ConfigKey.decimal("headingFlipCost", 16.0D, 0.0, 100000.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "掉头代价", "Heading flip cost",
                    "原地掉头的固定代价，防止路径来回折返",
                    "代价", ""),

            ConfigKey.decimal("headingCurveCost", 0.0D, 0.0, 100000.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "转向代价", "Heading curve cost",
                    "每次转向的代价，鼓励走直",
                    "代价", ""),

            ConfigKey.decimal("headingShortLegBlocks", 48.0D, 1.0D, 512.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "短腿判定长度", "Heading short leg blocks",
                    "两点间距小于此值算「短腿」",
                    "格", ""),

            ConfigKey.decimal("headingShortLegMultiplier", 3.0D, 1.0, 100.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "短腿代价倍数", "Heading short leg multiplier",
                    "短腿段的转向代价倍数，用来压制密集小折角",
                    "倍", "")
    );
}
