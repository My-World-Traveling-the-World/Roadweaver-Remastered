# RoadWeaver 旧版与重制版功能对照

对照基线：同级只读旧仓库 `roadweaver@2c6547a`，重制版第46轮。冻结版的[八域明细](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/tree/f2c06ea93c3d77e71c982086f59e688c433bd426/docs/functional-inventory)用于列场景，从两边的工厂、入口和实际调用复核，不把同名类、配置键或测试数量当作功能。

按下面定义的 **30 个场景组**，重制版 **24 组保留、4 组换实现、2 组旧算法选择已退役**。同一组包含多条入口时只计一次；“保留”指在适用维度、配置和依赖条件下能走到相应生产逻辑，不等于同一种子结果一致，也不代表真机完成率。

| # | 旧版功能 | 重制版状态与可复核入口 |
|---|---|---|
| 1 | 开局生成、区域扩张、空闲自动规划 | **保留。** `MinecraftServerMixin → InitialGenManager` 仍控制开局；`ServerPlanningHooks → IdleRoadGenerationService` 驱动后台扩张。 |
| 2 | KNN、Delaunay、RNG、MST、SnapBranch、AlphaModel 可选 | **选择退役。** 旧 `NetworkPlannerFactory` 按枚举创建 6 种实现。新版工厂只给 `PipelinePlanner`，以测地距离选网、进 Kruskal 前过滤超长边。旧枚举仍可解析，不会恢复旧路网风格。 |
| 3 | 基础 A*、双向 A*、GradientDescent、PotentialField 可选 | **选择退役。** 旧 `PathfinderFactory` 创建 4 种实现；新版 `createClassic` 和 BAND 都返回 `BandPathfinder`，AB 同型时只跑一次。保留旧值是为了读旧配置。 |
| 4 | 硬避让、共享跨越走廊，绕过狭窄障碍 | **保留。** 旧势场走 `PathfindingHelper`；新版 `BandPathfinder` 给 FMM 硬掩码、边级禁行及共享走廊代价。窄禁带、单格绕行和共享走廊有离线生产入口守卫；粗拓扑的 `ObstacleField.NONE` 仍不同于单路线。 |
| 5 | 水域/非水可调成本 | **换实现。** 旧 `PotentialFieldPathfinder:330,370` 读 `PathTerrainField` 的 WATER/OTHER。新版 `BandPathfinder` 把两档价差折成有限软倍率，复用已有水柱和河/海标记，唯一通路过水时仍可达。粗拓扑仍只用高度；森林等细分偏好两版均无生产分类。 |
| 6 | 选线时避让巨树预留，并防止施工破坏 | **换实现。** 旧 4 个寻路器查 `HugeTreeReservation.isReserved`。新版 BAND 一次读取相关区块的预留快照，按路宽扩大后约束格点、跨边、平滑和端点短接。API 仍能预留、查询和释放，`RoadConstructionSafety` 继续按落块时的状态保护；后续几何改写的最终占地已有专项离线校验，真实落块仍待验。 |
| 7 | 粗高度瓦片与缓存、精确采样、盘山折返 | **保留。** 新版仍有 `CoarseTerrainTileSampler` 和 `AccurateHeightSampler`，生产 `RawPathSearch:69,173` 仍调用 `MountainSwitchbackPlanner.replaceIfSafer`。 |
| 8 | 过路村庄包围盒裁切、端点村庄落地、保护缺口 | **保留。** 旧 `StructureAvoidanceService` 职责拆到现行 `EndpointOwnerResolver`、`ProtectedGapEvaluator` 等；施工 `PathFeature` 消费保护跨度。 |
| 9 | 结构预测/验证、快照准备及精确占用 | **保留。** `StructureVerificationService`、`PreparedStartResolver` 和 `StructureAvoidanceService` 仍为生产链供数。群系单点筛不是替代原版多点验证的实现。 |
| 10 | 路口接入、批量合并、并排改道、共用桥段和主路 | **保留。** `ExistingRoadJunctionService.connectPlannedBatchDetailed` 仍编排，`ParallelOverlapResolver` 和区块编译复用落列；批量选主与占地所有权有离线回归。 |
| 11 | 路边结构、村落预计算与按区块注入 | **保留。** `ServerLevelStructureMixin → StructureInjector.injectPendingStructures` 仍接线；新版补齐未知 ID 丢弃计数。 |
| 12 | 河湖桥、干峡谷跨度、浅涵洞、运行时桥提升 | **保留。** `PathSpanExtractor` 仍供默认跨度；`Road → CulvertPlanner.cross`，`PathFeature → promoteRuntimeBridges` 两条链仍在。 |
| 13 | 山体、海底隧道的跨度判定 | **保留。** 默认仍走 `PathSpanExtractor`。新版联合桥隧 DP 的结果可选回写（`spanWriteback=on`），默认观察，不能算已接管；`crossing` 仍由旧跨度供给。 |
| 14 | 模板桥、程序化桥、长桥正弦弯、浮标 | **保留。** `PathFeature:425–529` 保留浮标并优先尝试 `BridgeSegmentPlannerNew`，模板不适用走程序化分支；`BridgeSegment` 保留长桥 ±5 格。模板落块有离线负控。 |
| 15 | 隧道洞口、洞身清理、观景穹顶 | **保留。** `PathFeature → RoadTunnelBuilder`；后者调用 `ScenicDomeBuilder` 并按洞口和洞身规则施工。 |
| 16 | 纵向剖面、最大坡度、覆土和桥面下界 | **换实现。** 旧 `Road.computeTargetY` 多步修形，新主路径由 `TargetYComputation → ProfileDpSource`；不可行时由新版 `ProfileFallback` 保覆土上界，不退回旧版。最终坡度与桥头仍需同种子真机比较。 |
| 17 | 几何平滑、站点密化、桥头及宽度栅格 | **保留。** `PathMetricsOps → PathPostProcessor.process` 仍走默认生产路径，整路丢弃有负控；保留弹性带后的陆地松弛，影子结果不施工。 |
| 18 | 精确局部坡度、结构和河湖冲突修复 | **保留。** 旧 `AccurateGradeRepairPlanner` 集中修复，新版 `PathMetricsOps`、`PathFinalizeOps`、`StructureRepair` 分工，旧类名消失的部分也能从精确净空入口找到。 |
| 19 | 人工/自然路面、半砖、路基，保护列只铺或跳过 | **保留。** `PathFeature → SegmentPaver`、`RoadConstructionSafety`、`RoadBlockPlacer`；路肩仍有意关闭。自然路的群系材料和寻路群系成本是两件事。 |
| 20 | 路灯、路标、结构装饰 | **保留。** `PathFeature → DecorationPlanner → DecorationExecutor` 有路灯、提灯和距离牌；路旁结构走第 11 项的预计算/注入。 |
| 21 | 玩家附近回填、无人预生成区补建、重开恢复 | **换实现。** 旧版 `DeferredInitialRoadConstructionService.tick` 最多施工 8 块，却会扫完整个待施工集合。重制版用 `ServerPlanningHooks` 注册加载唤醒，`DeferredChunkQueue` 限额补扫，仍只对当前可取的区块施工；`recover` 仍从路档和台账重建。 |
| 22 | OpenCL 粗高度采样、设备探测、CPU 降级 | **保留。** `CoarseHeightBatchSamplers → OpenCLCoarseHeightBatchSampler.tryCreate`；不可用时仍有 CPU。此轮未跑物理 GPU。 |
| 23 | CPU 精确采样 | **保留。** 两边 `AccurateHeightBackends.create` 都选 CPU；停用已知会失败的精采 OpenCL 是既有规则。 |
| 24 | H 键路网地图、状态与进度、Xaero 底图 | **保留。** `ClientInit → RoadMapScreen`，含路线、结构、采样进度及 Xaero 渲染。旧地形采样及瓦片渲染死链不计作被删能力。 |
| 25 | 地图搜索、手动连接、删除、传送、访问权限 | **保留。** `MapNetworkFabric.registerServerReceivers` 仍注册对应请求并做权限检查；两版 `/roadweaver mapaccess info|mode|allow|deny` 都由 Fabric 注册。 |
| 26 | 手绘道路、桥、隧道；预加载施工、草稿自动存和重开恢复 | **保留。** `ClientInit → RouteEditorSession / RouteBuildService`，`RouteChunkPreloader` 保留主动预加载例外；`RouteStorage` 仍写 `routes.json` version 1，三类线段有 Codec 回归。 |
| 27 | 世界创建/游戏内配置、材料预设与预设编辑 | **保留。** `CreateWorldScreenMixin`、`ConfigScreenFactory`、`MaterialPresetEditorScreen` 和 `PresetService` 都在现行入口；旧算法旋钮不生效的情况另计第 2/3/5 项。 |
| 28 | RoadData、空间索引、施工台账和世界指纹 | **保留。** `RoadData.CODEC` 初始化一致，索引 / 施工台账 / 世界生成指纹 schema 分别为 2 / 3 / 1；重制版读故障拒绝写入部分索引。现行台账仍不因同键内容变化而自动重铺。 |
| 29 | 结构端点、对外预留、就绪订阅及负载 API | **保留。** `api`/ `api-fabric` 的 25 个 Java 路径一致，忽略行尾的源码差异仅新增文件职责注释；`RoadWeaver` 仍安装后端。未在真实外部模组组合下运行。 |
| 30 | Chunky、Streams、长城、巡航等跨模组协调与 Mixin | **保留。** `RoadPlanningReadinessIndex`、`StreamsReflowingCompat`、`GreatWallPrewarmCompat`、`LoadSignalBridge` 仍接线，Fabric Mixin 清单一致；现行整合包运行结果待验。 |

## 三个容易判错的地方

- **旧版没有按森林、沼泽等细分类型选线。** 对旧版整个 `common/src/main`、`fabric/src/main` 查 `biomeCategory(`，只有默认方法和势场调用。只有 WATER/OTHER（默认成本 36/0，权重 2）接入新版 BAND；其余键留作兼容，仍不参与采样或求解。
- **巨树的选线保护和施工保护在不同阶段。** BAND 用单次快照，离线生产入口已守住跨边单列和道路全宽。施工端还需复核实时预留，因为求解完成后世界状态与路线几何都可能变化。
- **删除地图类不等于丢底图。** 对旧版删掉的 12 个地图/传输 Java 路径反查生产引用，唯一跨出该组的引用来自两个 PNG writer；这两个 writer 的入口又只在已脱离生产入口的地形采样/回填类里。旧、新 `RoadMapScreen` 均绘制 Xaero，均接采样进度。这个结论不覆盖渲染视觉差异。

## 第46轮重新核对（2026-11-17）

本轮只读重查同级旧仓库，HEAD仍为2c6547a、工作区空。上表是30个场景组的入口对照，不代表同种子产物完全一致。本轮补查资源、API和全部删除路径，没有证据可称“完美重构”。

| 核对面 | 实际结果 | 证据与局限 |
|---|---|---|
| 公开API | 25个API Java去注释token一致；api-fabric描述一致 | scripts/parity/legacy-contract.json冻结旧签名token摘要，只证明原公共契约未删改，不证明外部模组运行正确。 |
| 模板、结构、材质与Fabric资源 | common 81/81、Fabric 2/2路径全保留；common仅两份语言文件变化 | 非语言资源及api-fabric共82文件归一行尾后逐字一致；桥模板、结构NBT、worldgen资源与Mixin清单没有丢失。 |
| 网络与手绘按键 | 旧5个C2S/6个S2C接收注册、13个手绘按键注册均一致 | MapNetworkFabric、RouteKeysBridgeImpl完整集合对照；13是实际注册数，不是15个定义键数。 |
| 中英语言键 | en旧704键全部保留；zh只缺10个退役算法选项标签 | 其余旧键均在，允许删除的10键在基线逐项列出，不能扩大为整个配置前缀。 |
| 存档、手绘模型、命令 | RoadData外层、RouteStorage、PlayerRoute、RouteKind、MapAccessCommand去注释token一致 | 分站/跨度新增冻结构造器不改CODEC；schema2/3/1和routes version1保持。此轮未运行旧档游戏重开。 |
| 删除类全量追踪 | common36、Fabric2，共38路径全部核对旧引用 | 6个拓扑、4个寻路算法退役；TerrainGradientHelper/ThrottleHelper只供这些算法。旧KNN连分量调用已由ComponentConnector.connectComponents接管，不能只因KNN删除就判丢连通。 |
| 删除的装饰、施工和地图壳 | 旧ArtificialDecorationSystem、NaturalDecorationSystem、FenceWaypointDecoration、InitialRoadConstructionService、TwoStagePathfindingManager、PresetConfig、RoadDirection、Records外部生产token引用均为0 | 对旧common/fabric全部Java去注释token查完整引用。当前PathFeature直接调用DecorationPlanner/DecorationExecutor；地图采样删除组内部互引，但上游MapTerrainSamplingService/回填入口无旧生产引用，现行RoadMapScreen保留Xaero。 |
| 动态入口与数据生成 | Fabric entrypoints/mixins逐字相同，无旧datagen入口 | 删除的RoadWeaverDataGenerator仅引用ModWorldGenerator，旧Fabric描述没有注册它；三处既定兼容反射保留。 |
| 自动路网、保护、桥隧与装饰 | 保留生产链与离线守卫 | ServerPlanningHooks仍tick后台、延迟施工并recover；RawPathSearch仍两处调用盘山；Road仍浅涵洞；PathFeature仍桥提升、模板桥、隧道、装饰；JunctionBatchCoordinator仍负责合流。桥模板、桥共享、隧道、村庄、最终占地和路口端口已有回归，但测试不等于真机召回率。 |
| 河流→长城→道路 | 道路侧等待/API保留 | InitialGenManager仍等待长城预热并预热初始路线河流；有长城读其就绪，无长城接河流。最终三模组Mixin/事件顺序仍需整合运行，本轮未改邻仓。 |

可重复运行：`python scripts/parity/verify_legacy_contract.py`；拒绝夹具：`python -m unittest discover -s scripts/parity -v`。基线来自旧仓库，不从新版生成。CI同时运行契约和证据工具测试；工具检查外部契约，不检测所有Java行为变化，不能用摘要通过代替游戏验收。

第2/3组旧算法选择退役、第5/6/16/21组换实现仍是明确差异。本轮没有找到新的已证实功能丢失，也没有把真机未测写成保留率100%。后续几何的终态占地已有GeometryProtectionProductTest/FinalConstructionFootprintTest，旧表第6项“待专项校验”已过时，应读作“离线已补、真实落块待验”。

## 后续对照

1. **终态保护。** 用盘山折返、宽度栅格化和路口合并夹具，核对最终施工占地是否仍避开巨树。保留落块时的现有实时保护。
2. **水路权衡。** 同种子记录绕岸、短桥及共享渡口的变化，核对过水距离、桥隧召回及最终连通性。粗拓扑的评分改动先走影子对照。
3. **完整产物和尾延迟。** 对照同种子首次生成、复制旧档重开、Chunky 无玩家。分别核对警示、遗漏道路、必要桥隧、保护碰撞和最大施工等待；采样、选路、编译、落块分开计时。重制版有较多离线守卫，但不能由此推断真机加载更快或全部产物等价。
