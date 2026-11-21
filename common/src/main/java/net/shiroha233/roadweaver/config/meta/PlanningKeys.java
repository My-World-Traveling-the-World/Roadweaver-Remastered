/* 文件职责：PlanningKeys当前有效配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * 当前PIPELINE路网规划的配置元数据。
 * 范围与PlanningConfig的夹取保持一致，动态步长还受半径约束。
 */
public final class PlanningKeys {

    private PlanningKeys() {}

    /** 与配置实例字段逐项对应，删除无效字段时同步删除元数据。 */
    public static final List<ConfigKey> ALL = List.of(
            ConfigKey.bool("dynamicPlanEnabled", true,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "启用动态规划", "Enable dynamic planning",
                    "开局预热之外，按玩家所在区块继续铺开新路网。关闭后只在开局规划一次",
                    ""),

            // UI 必须使用开局专用上限，否则保存到配置的 4096 会在界面上被再次缩小。
            ConfigKey.integer("initialPlanRadiusChunks", RoadConstants.DEFAULT_INITIAL_PLAN_RADIUS_CHUNKS, 1, RoadConstants.INITIAL_PLAN_MAX_RADIUS_CHUNKS,
                    ConfigCategory.PATHFIND, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "初始规划半径", "Initial plan radius",
                    "开局一次性规划的区块半径。调大能一次规划更远，但预热量与耗时按面积增长",
                    "区块", ""),

            ConfigKey.integer("dynamicPlanRadiusChunks", RoadConstants.DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS, 1, RoadConstants.COARSE_REGION_MAX_RADIUS_CHUNKS,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "动态规划半径", "Dynamic plan radius",
                    "每次动态波次覆盖的区块半径。太小会切碎路网、多出断头路",
                    "区块", ""),

            ConfigKey.integer("dynamicPlanStrideChunks", RoadConstants.DEFAULT_DYNAMIC_PLAN_STRIDE_CHUNKS, 1, RoadConstants.PLAN_TILE_MAX,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "动态规划步进",
                    "Dynamic plan stride",
                    "仅启用动态规划时生效；1..256区块且不超过动态半径，保存时按半径收紧。",
                    "区块",
                    ""),

            ConfigKey.enumeration("terrainSamplingMode", "COARSE_CORRIDOR",
                    ConfigCategory.PERFORMANCE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "地形采样模式", "Terrain sampling mode",
                    "规划阶段怎么拿地形高度。COARSE_CORRIDOR 复用原版 preliminary surface，不重复构造 NoiseChunk",
                    "可选：LEGACY_DIRECT（不建区域地形、逐连接寻路）/ COARSE_CORRIDOR / FULL_REGION（不可用时自动降级）"),

            ConfigKey.integer("localConflictRadiusBlocks", 100, 16, 512,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "局部冲突半径", "Local conflict radius",
                    "判定两条路冲突（过近/并行/交叉）的距离。调小会让并排路变多",
                    "格", ""),

            ConfigKey.integer("structureDetourRadiusStartBlocks", 50, 16, 512,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "绕结构起始半径", "Structure detour start radius",
                    "遇到结构时第一圈绕行的半径。太小会贴着建筑走",
                    "格", ""),

            ConfigKey.integer("structureDetourRadiusStepBlocks", 50, 8, 128,
                    ConfigCategory.STRUCTURE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "绕结构半径步长", "Structure detour radius step",
                    "绕不过去时每轮把半径增加多少。步长太大容易一下绕太远",
                    "格", ""),

            ConfigKey.integer("structureDetourRadiusMaxBlocks", 400, 16, 1024,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "绕结构最大半径", "Structure detour max radius",
                    "绕行半径的上限。到这个半径还绕不过就放弃该路线或改接既有路",
                    "格", "sanitize 保证不小于起始半径"),

            ConfigKey.integer("lakeDetourRadiusMaxBlocks", 100, 16, 1024,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "绕湖最大半径", "Lake detour max radius",
                    "湖泊的绕行上限。超过就考虑架桥直穿",
                    "格", ""),

            ConfigKey.integer("heightTunnelThresholdBlocks", 10, 2, 32,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "隧道判定高差阈值", "Tunnel height threshold",
                    "路线与地形高差超过这个值才考虑挖隧道，低于它用挖填处理",
                    "格", ""),

            ConfigKey.integer("heightRepairContextBlocks", 50, 16, 256,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高度修复上下文", "Height repair context",
                    "做高度修复时前后各看多少格。范围越大越平滑，但更慢",
                    "格", ""),

            ConfigKey.integer("tunnelDepthBlocks", 12, 4, 64,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "隧道埋深", "Tunnel depth",
                    "隧道洞身埋在地表以下的深度。太小会挖穿山顶",
                    "格", ""),

            ConfigKey.bool("coarseHeightAwareEnabled", true,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "粗规划考虑高度", "Coarse planning height-aware",
                    "粗规划阶段就按坡度评估代价。关闭后在平坦区域更快，但山区选线明显变差",
                    ""),

            ConfigKey.enumeration("terrainProfileMode", "AUTO",
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "地形倾向",
                    "Terrain profile policy",
                    "AUTO按地形选择倾向。少切方时切方最多3；少填方时正填方值减1且至少1，0仍为0。",
                    ""),

            ConfigKey.integer("surfaceCutMaxBlocks", 5, 0, 16,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "粗规划最大下挖",
                    "Coarse planning maximum cut",
                    "粗规划过渡检查的挖方预算；最终施工还受山地/平原挖方和地形倾向约束。",
                    "格",
                    ""),

            ConfigKey.integer("surfaceFillMaxBlocks", 2, 0, 16,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "粗规划最大填方",
                    "Coarse planning maximum fill",
                    "粗规划过渡检查的填方预算；最终施工还受山地/平原填方和地形倾向约束。",
                    "格",
                    ""),

            ConfigKey.integer("dryDepressionBridgeDepth", 6, 2, 16,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "干洼地架桥深度", "Dry depression bridge depth",
                    "干燥洼地深于此值时用短桥跨越，避免先挖再填",
                    "格", ""),

            ConfigKey.bool("passThroughVillageClipping", true,
                    ConfigCategory.STRUCTURE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "允许穿村裁切",
                    "Allow village clipping",
                    "按村庄包围盒裁切道路，保留村内既有连接；关闭后改为绕村。不会裁掉村庄房屋。",
                    ""),

            ConfigKey.integer("mountainOverburdenBlocks", 8, 2, 32,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "山地最小覆土", "Mountain minimum overburden",
                    "山地隧道洞顶至少要保留的岩层厚度。太小会开路明天窗",
                    "格", ""),

            ConfigKey.integer("tunnelMinRun", 3, 2, 16,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "隧道最短段长", "Tunnel minimum run",
                    "短于此值的隧道改回明挖。太短挖隧道既费工又难看",
                    "站", ""),

            ConfigKey.integer("switchbackStrength", 40, 0, 100,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "之字弯强度", "Switchback strength",
                    "陡坡上允许之字弯的程度。0 表示完全不走之字弯（会直接硬爬）",
                    "0-100", ""),

            ConfigKey.integer("heightAwareStateLimit", 260000, 50000, 1000000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高度感知状态上限", "Height-aware state limit",
                    "高度感知搜索的状态数上限，到上限就退化为普通搜索。调大更准但更慢",
                    "状态", ""),

            ConfigKey.integer("samplingInFlightTimeoutMs", 3000, 100, 30000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "采样等待超时", "Sampling in-flight timeout",
                    "等待其他线程正在采样同一格的上限。超时后自己重采，避免死等",
                    "毫秒", ""),

            ConfigKey.decimal("autoMountainThresholdFraction", 0.30D, 0.1D, 0.9D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "AUTO山地阈值",
                    "AUTO mountain threshold",
                    "仅AUTO倾向生效；山地占比超过阈值时采用偏隧道策略，范围0.1..0.9。",
                    "0-1",
                    ""),

            ConfigKey.decimal("autoWaterThresholdFraction", 0.25D, 0.1D, 0.9D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "AUTO水域阈值",
                    "AUTO water threshold",
                    "仅AUTO倾向生效；水域占比超过阈值时采用偏桥策略，范围0.1..0.9。",
                    "0-1",
                    ""),

            ConfigKey.integer("gradeRepairWindowStateLimit", 60000, 20000, 500000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "纵断面修复状态上限", "Grade repair window state limit",
                    "单次纵断面修复允许搜索的状态数。调小会放弃难修的剖面",
                    "状态", ""),

            ConfigKey.integer("gradeRepairWindowBudgetMillis", 20000, 1000, 600000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "纵断面修复预算", "Grade repair window budget",
                    "单次纵断面修复的墙钟预算上限。这是跨机不可复现的主要来源之一",
                    "毫秒", ""),

            ConfigKey.bool("gradeRepairLengthBoundPrune", true,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "修坡长度界剪枝",
                    "Grade repair length-bound pruning",
                    "用剩余长度的下界排除超预算候选，减少无效展开；关闭用于对拍，不扩大约定长度预算。",
                    ""),

            ConfigKey.integer("gradeRepairParallelism", 0, 0, Runtime.getRuntime().availableProcessors(),
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "纵断面修复并行度", "Grade repair parallelism",
                    "0 表示自动按可用核数决定。设成 1 可让结果跨机可复现",
                    "线程", ""),

            ConfigKey.bool("pathfindingParallelWait", false,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "等待并行寻路", "Wait for parallel pathfinding",
                    "开启后会等待并行寻路全部完成才继续。关闭则是谁先好先用谁",
                    ""),

            ConfigKey.integer("coarsePathWaitTimeoutMs", 180000, 0, 1800000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "粗路径等待超时",
                    "Coarse path wait timeout",
                    "0不设超时，否则10000..1800000毫秒；实际搜索和排队各用此期限，游玩让路等待不计时。",
                    "毫秒",
                    ""),

            ConfigKey.decimal("baseWaterPenalty", 200.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "过水基础代价", "Base water penalty",
                    "仅局部修复搜索计价，不是BAND主选线总开关。任何跨越水体的固定代价，防止路随便过水",
                    "代价", "0 表示过水不额外收费"),

            ConfigKey.decimal("perStationWaterPenalty", 150.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "每站过水代价", "Per-station water penalty",
                    "仅局部修复搜索计价，不是BAND主选线总开关。每多跨一站水增加的代价。与基础代价相加决定过水有多贵",
                    "代价/站", ""),

            ConfigKey.decimal("riverProximityPenalty", 8.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "临河惩罚", "River proximity penalty",
                    "仅局部修复搜索计价，不是BAND主选线总开关。贴着河岸走的惩罚，只用于防止路死贴水边",
                    "代价", "2026-11-05 由 300 降到 8；300 时会把路推离河岸很远"),

            ConfigKey.integer("riverProximityRadius", 5, 0, 32,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "临河半径", "River proximity radius",
                    "仅局部修复搜索计价，不是BAND主选线总开关。临河惩罚的作用半径。5 格意味着路会稳定在离水约 5 格处",
                    "格", ""),

            ConfigKey.decimal("culvertCostPerStation", 2.0D, 0.0, 100.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "涵洞每站代价", "Culvert cost per station",
                    "仅局部修复搜索计价，不是BAND主选线总开关。浅水且窄时走涵洞的每站代价。远低于架桥，所以涵洞是首选",
                    "代价/站", ""),

            ConfigKey.decimal("landDetourFactor", 1.6D, 0.0, 10.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "陆路绕行上限系数", "Land detour factor",
                    "仅局部修复搜索计价，不是BAND主选线总开关。陆路绕行不超过直连距离的这个倍数，就判定绕行合格、不架桥",
                    "倍", "实际门限取 max(直连,8)×本值 与原走廊长×1.05 中较松的一支；低于 1.0 会让闸门永久不通过"),

            ConfigKey.integer("narrowWaterMaxWidth", 20, 0, 256,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "窄水最大宽度", "Narrow water max width",
                    "仅局部修复搜索计价，不是BAND主选线总开关。宽度不超过这个值的算窄水，按窄水价目计价",
                    "格", "计价档与建造档存在错位带 (12, 本值]；实测 63 次过水仅 1 次落在带内"),

            ConfigKey.decimal("narrowWaterPerStation", 20.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "窄水每站代价", "Narrow water per station",
                    "仅局部修复搜索计价，不是BAND主选线总开关。窄水区每站的代价。20 表示宽度 20 的窄水总代价约 400",
                    "代价/站", ""),

            ConfigKey.decimal("wideWaterBase", 600.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "宽水基础代价", "Wide water base cost",
                    "仅局部修复搜索计价，不是BAND主选线总开关。宽水区的基础代价。与窄水价目形成断崖，用来阻止随便横渡大江",
                    "代价", ""),

            ConfigKey.decimal("wideWaterPerStation", 80.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "宽水每站代价", "Wide water per station",
                    "仅局部修复搜索计价，不是BAND主选线总开关。宽水区每站的代价。20 格宽 → 400，21 格宽 → 2280（5.7 倍断崖）",
                    "代价/站", ""),

            ConfigKey.decimal("maxGradeFraction", 0.5D, 0.1D, 0.9D,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "粗规划最大坡度比例",
                    "Coarse planning maximum grade",
                    "局部修复使用的坡度比例，范围0.1..0.9；与最终“每两段最大高差”分开。",
                    "比例",
                    ""),

            ConfigKey.bool("localRepairOnlyOnUnavoidable", false,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "仅在不可避免时局部修复", "Local repair only when unavoidable",
                    "开启后只有整条路都绕不过去时才做局部修复，减少无谓的截断",
                    ""),

            ConfigKey.bool("filterUnknownEndpointInsideVillage", true,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "过滤村内未知端点", "Filter unknown endpoints inside villages",
                    "落在村庄内部但无法识别归属的结构起点会被丢弃，避免在村里乱开路",
                    ""),

            ConfigKey.bool("prefilterStructureStarts", true,
                    ConfigCategory.STRUCTURE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "预筛结构起点", "Prefilter structure starts",
                    "规划前先筛掉数学上不可能的区块。关闭后更慢但不会漏",
                    ""),

            ConfigKey.bool("riverRepairBypassPrecheck", true,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "河流修复跳过预检", "River repair bypass precheck",
                    "河流修复时跳过部分前置检查以提速。关闭更保守",
                    ""),

            ConfigKey.integer("localRepairWindowClusterRadiusBlocks", 50, 0, 128,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "局部修复聚类半径",
                    "Local repair cluster radius",
                    "合并附近修复窗口以复用采样；0关闭，否则8..128格。",
                    "格",
                    ""),

            ConfigKey.bool("preferUnsmoothedOnProtectionGap", true,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "保护缺口时用未平滑路径", "Prefer unsmoothed on protection gap",
                    "当平滑会压到受保护方块时改用未平滑路径，优先保住建筑",
                    ""),

            ConfigKey.integer("heightAnomalyDetourMaxBlocks", 96, 16, 512,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高度异常绕行上限", "Height anomaly detour max",
                    "为躲开高度异常区最多绕多远。超过就接受异常",
                    "格", ""),

            ConfigKey.decimal("componentMinAngleDeg", RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG, 0.0D, 89.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "分量补连最小夹角",
                    "Component join minimum angle",
                    "合并不连通分量时的夹角约束，不是整张MST所有节点的统一夹角。",
                    "度",
                    ""),

            ConfigKey.integer("componentDegreeCap", RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP, 1, 8,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "分量补连单点边数上限",
                    "Component join degree limit",
                    "补连不连通分量时控制单点边数，不直接裁断已有主树。",
                    "条",
                    ""),

            ConfigKey.integer("planMaxEdgeLenBlocks", RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS, 128, 16384,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "主连边长度上限",
                    "Main connection length limit",
                    "候选进入Kruskal之前按端点三维直线距离筛选；不自动生成中间节点。",
                    "格",
                    ""),

            ConfigKey.integer("attachToExistingRoadRadiusBlocks", 256, 0, 1024,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "挂接既有道路半径", "Attach to existing road radius",
                    "新路线在这个半径内能接到既有路就直接接上，不再独立成路",
                    "格", ""),

            ConfigKey.integer("bridgeJoinLenBlocks", RoadConstants.DEFAULT_BRIDGE_JOIN_LEN_BLOCKS, 64, 16384,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "跨接口上限",
                    "Component bridge length limit",
                    "跨分量补连的候选长度上限；不是桥头坡道长度。",
                    "格",
                    "")
    );
}
