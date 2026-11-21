# 道路配置专项核对（2026-11-21）

> 历史审查快照：以下计数与缺陷属于修复前基线。配置实施已继续推进，当前菜单、范围与新增控制见[新版道路配置](CONFIGURATION.md)，验证记录见HANDOFF第95轮；不改写本报告原始证据。

核对基线：roadweaver-next@1d6f07e。本轮只审查和记录，不修改生产代码、默认值、配置文件、存档或已部署成品。

## 结论

不能把“界面还有控件”或“代码读取过一次”直接当成“任意情况下修改都有效”。截图中的主要控制项有生产消费者，但存在一个有意不生效的开关、范围不一致、零值解释不一致、缓存身份漏项及分类/文案漂移。

当前 ModConfig 的10个持久化子对象共193个字段，元数据也为193项；Cloth Config的12页共155个直接保存绑定。38个字段没有直接控件，其中2个客户端字段已有地图/编辑器操作入口。此口径不统计独立的结构选择文件、道路材质预设文件，也不把它们误当成未接线。

193是反射排除static/transient/synthetic后的结果；155按Section中实际保存消费者及其配置所属对象计数，区分多个不同对象内的enabled。完整清单与探针结果见 `evidence/config-audit-20261121.json`。

## 一、应该先修的实际问题

### 1. 地形配置没有参与当前规划身份（P1）

`common/src/main/java/net/shiroha233/roadweaver/config/ConfigService.java:103` 的规划载荷纳入planning、pathfindingCost等对象，却遗漏terrainProfile；`:129` 的算法载荷同样不含这组字段。

离线调用真实方法，逐项改变terrainProfile全部10个字段：规划载荷、算法载荷、`PlannedPathFingerprintService.create`返回值均不变。不是自行另写哈希公式。

其中5个绕行倍率参与 `planning/terrain/MountainSwitchbackPlanner.java:95` 的候选长度验收，按主导地形在`:349`起选择倍率；粗规划调用它，缓存的路径因此依赖这些值。路径缓存 `planning/path/PlannedPathCache.java:156` 按指纹相等接纳旧路径，`pathlogic/core/Road.java:154` 命中后走已规划路径入口。改倍率后，旧候选身份仍能匹配。

影响必须分层解释：这是已证实的配置身份遗漏；不等于10项在任何新生成中都无效。最终高度和跨度仍在 `Road.java:182` 后计算，不能据此声称调整海底概率或填方永远无效，更不能声称已有道路会自动改建。

修法：先给真正影响粗路径的地形字段补缓存身份行为测试；分别审查路径缓存、预热完成覆盖、最终施工身份的依赖，再补载荷。保持存档schema不变，通过计算出的缓存身份变化失效；不能把所有性能/显示参数一股脑加入而让无关调整重跑预热。

### 2. 动态半径界面允许4096，保存后变2048（P2）

`fabric/src/main/java/net/shiroha233/roadweaver/client/fabric/PlanningSection.java:55` 的动态半径控件最大值为4096；`config/sub/PlanningConfig.java:304` 夹到 `RoadConstants.COARSE_REGION_MAX_RADIUS_CHUNKS=2048`。真实对象设置4096再sanitize，得到2048。

开局半径上限4096是另一项，保持不变。修法是动态控件引用动态常量，并说明两者区别；不借修UI放宽游玩期的后台范围。

### 3. 坡度0在两条生产路径上含义不同（P1）

`SurfaceSettingsSection.java:48`允许0；配置和运行期快照也保留0。
`pathlogic/core/TargetProfileSolver.java:139` 却通过 `Math.max(1, ...)` 将其变为1。
探针设置坡度限制开启、数值0，调用真实编排并在Solver端口记录，得到每站/每两站上限 `[1,1]`。
`pathlogic/grade/SlopeContinuityEnforcer.java:55` 与`:121`对0直接返回原曲线。

这是语义不一致；本轮没有擅自决定0应代表“完全水平”还是“不限制”。修法应先定义唯一口径，使配置校验、DP、回落、精采样及最终施工检查一致，并覆盖0、1、最大值及坡度开关关闭四类输入。常规用户关闭坡度限制已有独立开关。

### 4. 精确GPU开关可改，但有意不生效（展示问题）

`fabric/src/main/java/net/shiroha233/roadweaver/client/fabric/PerformanceSection.java:181`仍建立 `openclAccurateSamplingEnabled` 的布尔控件；元数据明确标记UNWIRED，实际精确采样保持CPU。

此项是既定设计，不能重新开启GPU精确采样。应把可编辑控件换成“精确采样：CPU，固定”的只读说明。配置键是否删除另按配置清理契约处理，本轮未删键。

## 二、截图里有用但需要讲清条件的参数

| 参数 | 实际用途 / 条件 | 应修的说明 |
| --- | --- | --- |
| 动态规划半径、步进 | 动态规划开启后控制后台扩展 | 开关关闭时应灰显或折叠，不能叫死参数 |
| 分支最小夹角、单点边数 | 补连不连通分量时使用 | 不是整张MST对所有点统一限度数 |
| 山地/平原挖填 | 最终地形限制快照及相关判据读取 | 明确与粗规划过渡检查的另一组挖填限制不同 |
| 地形倾向 | 会进一步调整挖填限制及搜索倾向 | `SurfaceGradeLimits.java:110-115`：偏隧道/少切方将切方压至最多3；偏桥/少切方对正填方值减1、下限1，0保持0。现有tooltip仍写填方固定≤1，已过时 |
| 山/海岸/河/湖/海绕行倍率 | 盘山候选接受长度的乘数 | `MountainSwitchbackPlanner.java:95-98`：乘在既有长度预算上，不直接等于“全局路线最多直线距离的几倍”，也不直接修改FMM成本 |
| 道路宽度 | 影响新道路几何/占地 | 正偶数保存时向相邻奇数修正，0经生成快照至少为1；应显示实际值，不能让用户以为4就是4格 |
| 高度平滑半径 | 至少在纵断面输入中是相邻站的窗口 | `TargetTerrainInputs.java:66`、`RoadGradePlanner.java:104`按站索引取窗口中位数；笼统写“方块半径/平均”不精确 |
| 每两段最大坡度 | 每两段允许的高差数量 | 显示单位“格/两段”，不要与0.5这类无量纲坡度比例混为一项 |
| 正交/对角成本、高度变化权重等 | 仍用于局部修复或桥隧经济判据 | 有用，但并非BAND主度量的坡度/避障权重 |
| 桥墩宽度 | 程序桥使用；模板桥新增支撑按单列放置 | 不应承诺统一改变模板桥体素尺寸；`BridgeBuilder.java:168`对照`BridgeSegmentPlannerNew.java:273` |
| GPU并发、间隔、显存预算 | 对应GPU启用、运行状态与压力门 | 列出适用条件；CPU-only时不承诺能调出GPU效果 |

出生点连接开关本轮继续追到了 `generation/InitialGenManager.java:241` → `structures/placement/SpawnCabinPlacer.java:28`：它登记出生点结构/待放置项与连接节点。元数据仍写“生成起点小屋”，界面写“连接出生点道路”；应按实际节点/结构行为统一措辞，而不是凭旧类名改回“小屋”。

## 三、已有配置但没有直接菜单控件

完整38项如下；这是界面覆盖清单，不是38项都应摆到首页。

| 所属对象 | 字段 |
| --- | --- |
| structurePrediction（2） | structureWhitelist、structureBlacklist |
| planning（13） | gradeRepairWindowBudgetMillis、gradeRepairLengthBoundPrune、baseWaterPenalty、perStationWaterPenalty、riverProximityPenalty、riverProximityRadius、culvertCostPerStation、landDetourFactor、narrowWaterMaxWidth、narrowWaterPerStation、wideWaterBase、wideWaterPerStation、coarsePathWaitTimeoutMs |
| pathfindingCost（9） | braidRerouteOnOverlap、braidRerouteRadius、braidRerouteMaxLengthRatio、corridorAttractRadius、corridorAttractFactor、headingFlipCost、headingCurveCost、headingShortLegBlocks、headingShortLegMultiplier |
| roadAppearance（5） | forcedClearProtected、landTunnelEnabled、bumpCutEnabled、bumpCutMaxBlocks、bumpCutMaxColumnRatio |
| bridge（2） | clearHeight、sideClear |
| performance（5） | playerRoutePriorityEnabled、playerRoutePriorityExemptPauseReasons、playerRoutePriorityMaxThreads、playerRoutePriorityHardLimitMinutes、coarseHeightBandCount |
| client（2） | routeEditorHudEnabled、mapEndpointMarkersEnabled |

两个client项已有入口：`client/map/RoadMapScreen.java:419`切HUD、`:584`切端点标记；不是功能遗漏。结构选择页写独立的 `structure_selection.json`，不能与structurePrediction白/黑名单直接混算。

建议优先补菜单的是：

1. **单次局部修坡超时、粗路径等待超时**：让用户有明确的时间预算入口。保留真实单位和各自0值规则，二者不能合并。
2. **手绘路线优先开关、优先并发上限、持续时间上限**：便于权衡手绘施工与游玩负载。暂停原因豁免集合放专家折叠，不使用自由文本让普通用户误配。
3. **桥面净空高度**：现成的有效配置；说明0跟随隧道净空。侧向净空仅对程序桥适用，模板桥保护已保存体素，不假承诺通用。
4. **陆地隧道与凸起削平开关/阈值**：属于生成风格，应与挖填、桥隧参数摆在一起。
5. **河岸退距、陆路绕行容忍度、小溪/宽水计价**：已有JSON字段，但目前供局部搜索使用；必须标清作用阶段，不能命名成BAND全局过河总策略。
6. **粗采样分带数**：专家性能项；开局和Chunky预热强制单带，游玩期分带才读配置，依据 `pathfinding/terrain/region/CoarseTerrainTileSampler.java:103-109`。不要暗示提高此项能加速开局。

剩余折返惩罚、并行重叠重搜、走廊吸引和长度剪枝适合专家折叠。强清保护开关涉及生成策略，应完整说明保护边界；不能只给一个含糊的“强制生成”按钮。

## 四、真正尚未成为配置的新算法参数

最值得补的不是更多旧网格单价，而是玩家目前无法调整的新算法主参数：

| 建议 | 真实硬编码证据 | 开放方式 |
| --- | --- | --- |
| 主选线坡度偏好 | `bridge/BandPathfinder.java:305`引用Finsler默认gradeWeight=1；`planning/impl/PipelinePlanner.java:188`也使用默认度量 | 一个共用配置输入同时接拓扑和单路度量；默认值不变，有限非负范围 |
| 主选线软避障偏好 | `BandPathfinder.java:306`默认obstacleWeight=4 | 只改变软成本，硬保护不可被0绕过；说明不等于结构保护距离 |
| 曲线保形强度 | `BandPathfinder.java:318`使用 `ElasticBandSolver.defaults()`，默认mu=1 | 专家项，严格正值；端点、整条边保护、站距和长桥正弦守卫仍必须通过 |
| 模板桥灯间隔 | `pathlogic/bridge/BridgeSegmentPlannerNew.java:320`固定16格 | 桥梁装饰项，说明与陆路路灯“站/段”间隔区别；定义0关闭并验证 |

前三项改变几何，接线时应补缓存身份和同地形参数对拍；最终观感需真机A/B。算法迭代次数、残差阈值、CG数值细节暂不新增普通控件，避免制造需要用户调数学收敛的设置页。

## 五、分类调整建议

当前有12页，路网规划单页41个直接控件；“运行时GPU策略”里有CPU/普通道路线程上限，“桥梁”里有隧道/涵洞，“测试栏”实际只有加载显示设置。分组未按用户要改变的效果组织。

建议8页，页内再按“常用 / 高级 / 专家”折叠，保持JSON对象和字段名不变：

| 页 | 内容 |
| --- | --- |
| 预热与规划 | 开局范围、动态范围/步进、拓扑连边长度与分量补连 |
| 结构与避让 | 结构选择、预测、保护、村庄/其它结构偏移、绕建筑策略 |
| 地形与坡度 | 地形倾向、挖填、坡度、平滑、盘山、地形绕行倍率 |
| 桥隧与过水 | 桥梁、隧道、涵洞、海底转换、共享桥段与过水策略 |
| 路面与装饰 | 总开关、材质/预设、宽度、路牌、路灯、清障、防树 |
| 路边建筑 | 路边结构与路边村庄，分别折叠保留完整控制 |
| 性能与调度 | 开局/游玩/空闲CPU预算、GPU采样与预算、时间预算、手绘优先 |
| 地图与界面 | H地图、手绘HUD、端点标记、加载提示、加载进度 |

GPU设置可以保留页内独立折叠，而不是把与GPU无关的CPU控制继续塞进同一标签。动态关闭、桥梁关闭等依赖应在页内说明或灰显；保留设置值，便于重新开启。

## 六、配置基建应一起整理

`config/meta/ConfigKeyTable.java:25`仍声称232键，实际193；其注释还宣称UI自动生成、范围同源，实际界面由Section手写，元数据仅用于自证日志等。`ConfigTier`的折叠/只读帮助方法没有UI消费点。

存在具体范围漂移：TerrainProfileKeys写切方0..1000000、倍率0..1000000、概率0..1000000，配置与控件实际分别0..16、0.25..4、0..1；RoadAppearanceKeys的宽度、平滑半径、坡度上限也与实际不一致。已有一致性测试检查字段/类型/默认值及min≤max，并不验证每个范围与sanitize相符。

分步处理：

1. 先修真实配置身份、动态半径及0坡度问题，各自独立测试和负控；不改变开局4096、几何默认或已有保护策略。
2. 集中修正元数据、语言说明和实际范围，标明阶段/条件/单位/重启或重算时机。
3. 搬动Section的分类，不动配置持久化结构；按 `(配置对象, 字段)` 建UI清单，不能靠裸字段名enabled匹配。
4. 补优先缺失的现成控件与只读CPU说明，加入控件缺失/错绑/错误范围的负控。
5. 最后才增加新算法参数，以原默认值接入；参数变化必须能改变相应真实算法输入，并对缓存身份、合法性与产物分别验收。

## 七、本轮实际验证与未验证

完整道路回归：`PASS tests=1784 skipped=0 aborted=0 failed=0`，Fabric编译通过。使用指定Gradle8.8/JDK21、offline/daemon，不用包装器。第一次沙箱运行无法载入native-platform.dll，获准后执行。

另运行临时编译的只读Java探针：193字段/193元数据；动态4096→2048；10个地形字段逐项改变、三种真实身份均不变；0坡度送进真实DP编排的端口是1/1。探针首次使用source-launcher因类加载器不同不能访问包内接口，改成先编译再在同一classpath运行，最终 `BUILD SUCCESSFUL`。探针位于本地 `build/config-audit/`，结构化结果已存入evidence。

这是诊断证据，不是新增持久回归守卫；本轮没有生产变异，没有新增测试数。没有对193项逐项做世界生成A/B，没有重建/部署jar，没有启动Minecraft验证分类布局，也未验证真实存档调参后的缓存复用和帧时。本轮确认的问题尚未修复，按上述顺序继续。
