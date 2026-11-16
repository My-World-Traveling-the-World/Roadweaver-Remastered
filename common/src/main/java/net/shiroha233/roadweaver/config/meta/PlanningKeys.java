/* 文件职责：PlanningConfig 60 个键的元数据 —— 中文名、单位、范围、默认值与是否接线。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/**
 * {@code PlanningConfig} 的键元数据表。
 *
 * <h2>它回答三个问题</h2>
 * <ol>
 *   <li><b>这个键是什么</b> —— 中文名 + 一句话说明，供 UI 直接显示</li>
 *   <li><b>它能取什么值</b> —— 类型与范围，供 UI 的滑块与校验</li>
 *   <li><b>它真的起作用吗</b> —— {@link ConfigStatus}，把已接线 / 未接线 / 实验性 / 已废弃分开</li>
 * </ol>
 *
 * <h2>第三条是这个表存在的主要理由</h2>
 * <p>旧实现有一批键<b>写进配置、进了 UI、玩家改了却什么也不发生</b>。已实证的六个：
 * {@code chunkyRoadThreadCap}、{@code openclAccurateSamplingEnabled}、{@code keepLamps}、
 * {@code minLength}、{@code oceanBridgeMinDepth}、{@code placeWaypoints}，
 * 外加 6 个 {@code biomeCost*} 永久死键（{@code PathTerrainField.biomeCategory()}
 * 全仓 0 处覆写）。把它们标成 {@link ConfigStatus#UNWIRED} 并写清原因，
 * 比让玩家反复试错要好得多 —— {@code ConfigKeyTableConsistencyTest} 强制
 * 每个非 ACTIVE 的键必须给出原因。</p>
 *
 * <h2>范围的两重口径</h2>
 * <p>这里的 {@code min/max} 是<b>给 UI 用的</b>；真正生效的夹取在
 * {@code PlanningConfig.sanitize()} 里，两者可能不同（例如
 * {@code dynamicPlanStrideChunks} 会被按半径重算）。
 * 凡是「UI 范围不等于实际生效范围」的键，{@code noteZh} 里都写明了。</p>
 *
 * @see ConfigKey
 * @see ConfigKeyTable
 * @see net.shiroha233.roadweaver.config.sub.PlanningConfig
 */
public final class PlanningKeys {

    private PlanningKeys() {}

    /** 60 个键，顺序与 {@code PlanningConfig} 字段声明顺序一致。 */
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

            ConfigKey.integer("dynamicPlanStrideChunks", RoadConstants.DEFAULT_DYNAMIC_PLAN_STRIDE_CHUNKS, RoadConstants.PLAN_TILE_MIN, RoadConstants.PLAN_TILE_MAX,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "动态规划步长", "Dynamic plan stride",
                    "波次之间的推进距离",
                    "区块", "sanitize 会重算：max(PLAN_TILE_MIN, min(PLAN_TILE_MAX, max(1,半径)/2)) —— 写大了不生效"),

            ConfigKey.enumeration("planningAlgorithm", "PIPELINE",
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "路网拓扑算法", "Planning algorithm",
                    "决定结构节点之间怎么连边",
                    "只剩 PIPELINE（测地距离 MST）。旧规划器已全部删除，此项不可切换"),

            ConfigKey.enumeration("terrainSamplingMode", "COARSE_CORRIDOR",
                    ConfigCategory.TERRAIN, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "地形采样模式", "Terrain sampling mode",
                    "规划阶段怎么拿地形高度。COARSE_CORRIDOR 复用原版 preliminary surface，不重复构造 NoiseChunk",
                    "可选：LEGACY_DIRECT（不建区域地形、逐连接寻路）/ COARSE_CORRIDOR / FULL_REGION（不可用时自动降级）"),

            ConfigKey.integer("localConflictRadiusBlocks", 100, 16, 512,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
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
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "绕湖最大半径", "Lake detour max radius",
                    "湖泊的绕行上限。超过就考虑架桥直穿",
                    "格", ""),

            ConfigKey.integer("heightTunnelThresholdBlocks", 10, 2, 32,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道判定高差阈值", "Tunnel height threshold",
                    "路线与地形高差超过这个值才考虑挖隧道，低于它用挖填处理",
                    "格", ""),

            ConfigKey.integer("heightRepairContextBlocks", 50, 16, 256,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高度修复上下文", "Height repair context",
                    "做高度修复时前后各看多少格。范围越大越平滑，但更慢",
                    "格", ""),

            ConfigKey.integer("tunnelDepthBlocks", 12, 4, 64,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道埋深", "Tunnel depth",
                    "隧道洞身埋在地表以下的深度。太小会挖穿山顶",
                    "格", ""),

            ConfigKey.bool("coarseHeightAwareEnabled", true,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "粗规划考虑高度", "Coarse planning height-aware",
                    "粗规划阶段就按坡度评估代价。关闭后在平坦区域更快，但山区选线明显变差",
                    ""),

            ConfigKey.enumeration("terrainProfileMode", "AUTO",
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "纵断面模式", "Terrain profile mode",
                    "怎么决定路面的挖填策略。AUTO 会按路线地形自动切到桥优先或隧道优先",
                    "★ AUTO 命中桥优先/隧道优先时会把 fill 压到 min(1,配置)、cut 压到 min(3,配置)，"
                            + "此时改 surfaceFillMaxBlocks / surfaceCutMaxBlocks 对这类路线完全无效且没有日志"),

            ConfigKey.integer("surfaceCutMaxBlocks", 5, 0, 16,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "最大下挖", "Max surface cut",
                    "单个站点最多向下挖多少格。0 表示完全不下挖",
                    "格", "terrainProfileMode=AUTO 被判为桥优先/隧道优先时会改用 min(1,本值) 且无日志"),

            ConfigKey.integer("surfaceFillMaxBlocks", 2, 0, 16,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "最大填方", "Max surface fill",
                    "单个站点最多向上填多少格。0 表示完全不填，路面会悬空",
                    "格", "terrainProfileMode=AUTO 被判为桥优先时会改用 min(1,本值) 且无日志"),

            ConfigKey.integer("dryDepressionBridgeDepth", 6, 2, 16,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "干洼地架桥深度", "Dry depression bridge depth",
                    "干燥洼地深于此值时用短桥跨越，避免先挖再填",
                    "格", ""),

            ConfigKey.bool("passThroughVillageClipping", true,
                    ConfigCategory.STRUCTURE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "允许穿村裁切", "Allow passing through villages",
                    "道路可以穿过村庄并裁掉挡路的房屋。关闭后村庄会被整片绕过",
                    "关闭会显著增加绕行长度"),

            ConfigKey.integer("mountainOverburdenBlocks", 8, 2, 32,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "山地最小覆土", "Mountain minimum overburden",
                    "山地隧道洞顶至少要保留的岩层厚度。太小会开路明天窗",
                    "格", ""),

            ConfigKey.integer("tunnelMinRun", 3, 2, 16,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "隧道最短段长", "Tunnel minimum run",
                    "短于此值的隧道改回明挖。太短挖隧道既费工又难看",
                    "站", ""),

            ConfigKey.integer("switchbackStrength", 40, 0, 100,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
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

            ConfigKey.decimal("autoMountainThresholdFraction", 0.30D, 0.0, 1.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "AUTO 山地阈值", "AUTO mountain threshold",
                    "terrainProfileMode=AUTO 时，山地占比超过这个比例就切换为隧道优先",
                    "0-1", "切到隧道优先后 surfaceFillMaxBlocks/surfaceCutMaxBlocks 会被压到 1/3"),

            ConfigKey.decimal("autoWaterThresholdFraction", 0.25D, 0.0, 1.0D,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "AUTO 水域阈值", "AUTO water threshold",
                    "terrainProfileMode=AUTO 时，水域占比超过这个比例就切换为桥优先",
                    "0-1", ""),

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
                    "长度界剪枝", "Length-bound pruning",
                    "用剩余长度下界剪掉不可能更优的分支。关闭后更慢但结果理论上更优",
                    ""),

            ConfigKey.integer("gradeRepairParallelism", 0, 0, 256,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "纵断面修复并行度", "Grade repair parallelism",
                    "0 表示自动按可用核数决定。设成 1 可让结果跨机可复现",
                    "线程", ""),

            ConfigKey.bool("pathfindingParallelWait", false,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "等待并行寻路", "Wait for parallel pathfinding",
                    "开启后会等待并行寻路全部完成才继续。关闭则是谁先好先用谁",
                    ""),

            ConfigKey.integer("coarsePathWaitTimeoutMs", 180000, 10000, 1800000,
                    ConfigCategory.PERFORMANCE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "粗寻路等待超时", "Coarse path wait timeout",
                    // 让路时间不能被描述成寻路超时，否则玩家会把正常暂停误认为规划失败。
                    "实际粗搜索与执行队列分别使用此期限；等待游玩预算不计时，超时取消本批并交上层处理",
                    "毫秒", ""),

            ConfigKey.decimal("baseWaterPenalty", 200.0D, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "过水基础代价", "Base water penalty",
                    "任何跨越水体的固定代价，防止路随便过水",
                    "代价", "0 表示过水不额外收费"),

            ConfigKey.decimal("perStationWaterPenalty", 150.0D, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "每站过水代价", "Per-station water penalty",
                    "每多跨一站水增加的代价。与基础代价相加决定过水有多贵",
                    "代价/站", ""),

            ConfigKey.decimal("riverProximityPenalty", 8.0D, 0.0, 100000.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "临河惩罚", "River proximity penalty",
                    "贴着河岸走的惩罚，只用于防止路死贴水边",
                    "代价", "2026-11-05 由 300 降到 8；300 时会把路推离河岸很远"),

            ConfigKey.integer("riverProximityRadius", 5, 0, 32,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "临河半径", "River proximity radius",
                    "临河惩罚的作用半径。5 格意味着路会稳定在离水约 5 格处",
                    "格", ""),

            ConfigKey.decimal("culvertCostPerStation", 2.0D, 0.0, 100.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "涵洞每站代价", "Culvert cost per station",
                    "浅水且窄时走涵洞的每站代价。远低于架桥，所以涵洞是首选",
                    "代价/站", ""),

            ConfigKey.decimal("landDetourFactor", 1.6D, 0.0, 10.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "陆路绕行上限系数", "Land detour factor",
                    "陆路绕行不超过直连距离的这个倍数，就判定绕行合格、不架桥",
                    "倍", "实际门限取 max(直连,8)×本值 与原走廊长×1.05 中较松的一支；低于 1.0 会让闸门永久不通过"),

            ConfigKey.integer("narrowWaterMaxWidth", 20, 0, 256,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "窄水最大宽度", "Narrow water max width",
                    "宽度不超过这个值的算窄水，按窄水价目计价",
                    "格", "计价档与建造档存在错位带 (12, 本值]；实测 63 次过水仅 1 次落在带内"),

            ConfigKey.decimal("narrowWaterPerStation", 20.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "窄水每站代价", "Narrow water per station",
                    "窄水区每站的代价。20 表示宽度 20 的窄水总代价约 400",
                    "代价/站", ""),

            ConfigKey.decimal("wideWaterBase", 600.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "宽水基础代价", "Wide water base cost",
                    "宽水区的基础代价。与窄水价目形成断崖，用来阻止随便横渡大江",
                    "代价", ""),

            ConfigKey.decimal("wideWaterPerStation", 80.0D, 0.0, 100000.0D,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "宽水每站代价", "Wide water per station",
                    "宽水区每站的代价。20 格宽 → 400，21 格宽 → 2280（5.7 倍断崖）",
                    "代价/站", ""),

            ConfigKey.decimal("maxGradeFraction", 0.5D, 0.0, 2.0D,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "最大坡度", "Max grade",
                    "允许的最大坡度（0.5 = 50%）。超过会被纵断面规划修复",
                    "比例", ""),

            ConfigKey.bool("localRepairOnlyOnUnavoidable", false,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
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

            ConfigKey.integer("localRepairWindowClusterRadiusBlocks", 50, 8, 128,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "局部修复窗口聚类半径", "Local repair window cluster radius",
                    "把相距不远的局部修复点合并成一个窗口，减少重复搜索",
                    "格", ""),

            ConfigKey.bool("preferUnsmoothedOnProtectionGap", true,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "保护缺口时用未平滑路径", "Prefer unsmoothed on protection gap",
                    "当平滑会压到受保护方块时改用未平滑路径，优先保住建筑",
                    ""),

            ConfigKey.integer("heightAnomalyDetourMaxBlocks", 96, 16, 512,
                    ConfigCategory.PATHFIND, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "高度异常绕行上限", "Height anomaly detour max",
                    "为躲开高度异常区最多绕多远。超过就接受异常",
                    "格", ""),

            ConfigKey.integer("knnK", RoadConstants.DEFAULT_KNN_K, 1, 64,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "KNN 邻居数", "KNN neighbor count",
                    "每个节点考虑多少个最近邻来生成候选边。太小路网稀疏，太大会生成大量无用的边",
                    "个", ""),

            ConfigKey.decimal("knnAlpha", RoadConstants.DEFAULT_KNN_ALPHA, 0.0, 10.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "KNN 角度权重", "KNN angle weight",
                    "选边时对方向一致性的权重。越大越偏好不转弯的连线",
                    "权重", ""),

            ConfigKey.decimal("knnMinAngleDeg", RoadConstants.DEFAULT_KNN_MIN_ANGLE_DEG, 0.0, 180.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "KNN 最小夹角", "KNN min angle",
                    "两条边夹角小于这个值就视为重复，只保留一条",
                    "度", ""),

            ConfigKey.integer("knnDegreeCap", RoadConstants.DEFAULT_KNN_DEGREE_CAP, 1, 32,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "KNN 度数上限", "KNN degree cap",
                    "每个节点最多连出多少条边。防止枢纽节点连成一片",
                    "条", ""),

            ConfigKey.decimal("componentMinAngleDeg", RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG, 0.0, 180.0D,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "连通分量最小角度", "Component min angle",
                    "跨连通分量接边时的最小夹角要求，避免接出锐角",
                    "度", ""),

            ConfigKey.integer("componentDegreeCap", RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP, 1, 32,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "连通分量度数上限", "Component degree cap",
                    "跨连通分量补边时每个节点最多补几条",
                    "条", ""),

            ConfigKey.integer("planMaxEdgeLenBlocks", RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS, 64, 8192,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "规划最大边长", "Plan max edge length",
                    "两个节点相距超过这个值就不直接连边，改为中间补节点",
                    "格", ""),

            ConfigKey.integer("attachToExistingRoadRadiusBlocks", 256, 16, 2048,
                    ConfigCategory.PATHFIND, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "挂接既有道路半径", "Attach to existing road radius",
                    "新路线在这个半径内能接到既有路就直接接上，不再独立成路",
                    "格", ""),

            ConfigKey.integer("bridgeJoinLenBlocks", RoadConstants.DEFAULT_BRIDGE_JOIN_LEN_BLOCKS, 0, 512,
                    ConfigCategory.CROSSING, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "桥接合段长度", "Bridge join length",
                    "桥头与陆路之间过渡段的长度。太短会形成台阶",
                    "格", "")
    );
}
