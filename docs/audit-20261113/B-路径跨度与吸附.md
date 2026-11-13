# B 组：路径跨度提取与道路吸附 行为等价性核查

- 旧仓库（oracle，冻结）：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`
- 新仓库（重构版，任务给定基线）：`D:\云游四海-Mod源码\roadweaver-next` @ `7bc6023`
- 核查范围：`pathfinding/PathSpanExtractor`、`pathlogic/pathcalc/RoadPathCalculator`、`postprocess/RoadSnapService`，以及这三份代码拆分出来的 `UnderseaTunnelPlanner` / `FillBridgeExtender` / `SpanConflictResolver` / `TunnelFootprintSampler` / `PathMetricsOps` / `PathFinalizeOps` / `JunctionConnector` / `JunctionTrimOps` / `EndpointConnectOps` / `EndpointApproachConnector` / `NetworkReuseMerger` / `RoadIndex` / `SegmentGrid` / `SnapInternals`，和被它们调用的 `Road` / `TargetYComputation` / `RoadSpanProfileOps` / `RoadCrossingOps` / `BridgeDeckProfile` / `BridgeSegment` / `BridgeBuilder` / `BridgeRangeCalculator` / `RoadTunnelBuilder` / `TunnelEnvelopeCriterion` / `RoadGradePlanner` / `AccurateHeightSampler` / `SurfaceWaterClassifier` / `StreamsReflowingCompat` / `RoadConstants` / `AccurateGradeRepairPlanner` / `StructureAvoidanceService` / `FinalRoadStructureValidator` / `FinalRoadWaterValidator`。
- 方式：**纯只读静态核查**。未改/建/删任何 `.java`，未 `git add/commit/checkout/stash`，未跑 gradle、未编译、未运行测试。本文件是本轮唯一写入物。
- 核查手法（可复现）：对每个 `.java` 用「声明 `(` 参数表 `)` 花括号配平」抽出**整个方法体**，抹掉 `//` 与 `/* */`（保留字符串/字符字面量的占位）后折叠所有空白，做**方法体字符串精确比对**；再对剩余差异做「类限定前缀不敏感」比对，区分**真正的表达式变化**与**跨类搬家**。所以「等价（逐字节）」= 去注释去空白后方法体完全相同，任何增删一个字符、换一个比较符、换一个常量都会被抓出来。

> **基线漂移（必须先说）**：任务说新仓在 `7bc6023`，但核查期间该仓 HEAD 前进了两次 —— `46ee02e`（2026-11-13 17:11:39，「纵断面求解器默认从 DP 切回 LEGACY」）和 `e82bea0`（17:17:26，「桥面水位下界改为 DP 的求解约束」）；核查中途工作区还有 5 个文件未提交改动（`TargetYComputation` / `ProfileDpSource` / `ProfileSolverMode` / `ProfileDpSourceTest` / `D-生成与规划链.md`），17:17 已被 `e82bea0` 一并提交。
> **本文所有行号与结论以 `7bc6023` 及其工作树（17:15 前）为准**；凡涉及 DP 的结论都另外标注了 `46ee02e`/`e82bea0` 的状态。
> 另外：本路径下原有一份同名报告（提交于 `46ee02e`），本文是**独立重跑的第二次核查**，不引用其结论；如需对比可用 `git show 46ee02e -- "docs/audit-20261113/B-路径跨度与吸附.md"` 取回。

---

## 结论摘要

- **等价 31 项 / 移动 22 项 / 删除 0 项 / 改变 1 项**（另有**新增 4 项**，不计入等价/改变，见 §★B-2）。
- **最高风险**：B 组三个文件本身**没有**判据/常量/边界条件变化（249 个成员全部落地、方法体逐字节相同）；唯一能让桥/隧道结果改变的是**跨组**的 `TargetYComputation` 新增了旧版根本不存在的 DP 纵断面求解路径，**在任务基线 `7bc6023` 上它是默认开启的**，而 `refineFinalFillBridgeSpans`（干洼地桥）与 `retainVerticallyRelevantCrossings` 都以 `targetY` 为判据输入 ⇒ 桥的数量/位置/桥面 Y 会变。该默认值在核查期间已被提交 `46ee02e` 切回 LEGACY，随后又在新提交 `e82bea0` 里连同「桥面水位下界」一起重新设计。**详见 ★B-1。**
- **桥/隧道判据是否被改动：否（判据本体逐字相同）**。证据：
  1. `TunnelEnvelopeCriterion.java` 新旧 **535 行 / 23 个方法体逐字相同**（窗口 24 站、10% 分位、种子突起 2、最小突起 4、目标覆土 7、密封核心 3、最长 256 站、站距 8.0、端口坡度 0.08 全同）；
  2. 水掩码判据 `segmentWaterSample`/`bridgeWaterSample` 方法体逐字相同（旧 `PathSpanExtractor.java:1522-1630` ↔ 新 `:686-794`），水深比较仍是 `waterDepth >= Math.max(1, minWaterDepth)`（不是 `>`），水面来源优先级仍是 `Streams 河道 → Streams 湖泊 → PathTerrainField 精确列 → 高度图兜底`；
  3. `crossingTypeForWidth` 仍是 `widthBlocks <= 12 ? ROAD_CROSSING : BRIDGE`（旧 `:1648-1652` ↔ 新 `:812-816`，`ROAD_CROSSING_MAX_WIDTH_BLOCKS=12` 旧 `:67` ↔ 新 `:72`）；
  4. 陆上隧道接线 `boolean buried = landTunnelSwitch && newBuried;` 位置与表达式未变（旧 `:322` ↔ 新 `:327`）。
- **长桥 ±5 格正弦弯是否还在：在（三处全链路都在，且逐字节相同）**。
  - 阈值：`RoadConstants.BRIDGE_CURVE_MIN_LENGTH_BLOCKS = 96`、`BRIDGE_CURVE_MAX_OFFSET_BLOCKS = 5.0D`（旧 `RoadConstants.java:99/101` ↔ 新 `:98/100`，`RoadConstants.java` 两版 `diff` 只有 1 行 import 删除）；
  - 取弯：`BridgeSegment.java` 构造器里 `if (length >= 96) offset = (hash & 1) == 0 ? +5.0 : -5.0;`（旧 `:52-63` ↔ 新 `:53-64`，整份文件只差第 1 行文件头注释）；
  - 几何：`Line.pointAt` 里 `double offset = horizontalBend * Math.sin(Math.PI * t);`（旧 `Line.java:46-57` ↔ 新 `:47-58`，整份 `util/Line.java` 只差文件头注释）；
  - 落地：`BridgeBuilder` 里 `if (geometryLine != null && geometryLine.isCurved()) { seg = new RoadSegmentPlacement(line.mapRoadPosition(...), line.mapRoadPositions(...)); }`（旧 `BridgeBuilder.java:72` ↔ 新 `:77`；`BridgeBuilder` 22/22 方法体逐字相同）。
  - 也就是说：**正弦弯是纯水平几何（只依赖站点 XZ 与哈希），与纵断面求解器选谁无关**，重构没有动它。

### 先说一个量化事实：这三个包不是「缩水」，而是「搬家 + 变长」

| 维度 | 旧 | 新 | 说明 |
|---|---|---|---|
| `PathSpanExtractor.java` | 1816 行 | 980 行 | 方法被拆到 4 个新类 |
| `pathfinding/` 顶层包 | 6 文件 / 94 方法 / 2476 行 | 10 文件 / **96 方法** / **2738 行** | 方法数 +2，行数 +262 |
| `RoadPathCalculator.java` | 1709 行 | 872 行 | 拆到 `PathFinalizeOps`/`PathMetricsOps` |
| `pathlogic/pathcalc/` 包 | 3 文件 / 66 方法 / 1961 行 | 5 文件 / **66 方法** / **2136 行** | 方法数相等，行数 +175 |
| `RoadSnapService.java` | 3708 行 | 995 行 | 拆到 9 个新类 |
| `postprocess/` 包 | 4 文件 / 195 方法 / 5111 行 | 13 文件 / **195 方法** / **5621 行** | 方法数**精确相等 195=195**，行数 +510 |

三个包的行数全部**增加**，方法数没有一处减少 ⇒ 「`RoadSnapService` −73%」是**同包内重新分工**，不是删除。

---

## 逐项对照表

> 判定口径：**等价**＝去注释/空白后方法体逐字相同（含跨文件的搬家）；**移动**＝整段逻辑搬到别的类且方法体逐字相同；**改变**＝真的会算出不同结果；**新增**＝旧版没有的新代码。
> 「旧版位置」用旧仓库相对路径+行号；「重构版位置」用 `roadweaver-next/common/src/main/java/net/shiroha233/roadweaver/` 下的相对路径+行号。

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 1 | `extractSpans` 三重载（采样补缺 → 有效水深门槛 → 水掩码） | `pathfinding/PathSpanExtractor.java:95-149` | `pathfinding/PathSpanExtractor.java:100-154` | 等价 | 60 个方法体逐字相同；`effectiveBridgeMinDepth(...)` 后再进 `collectBridgeSpans` |
| 2 | `effectiveBridgeMinDepth`（BRIDGE_FIRST ⇒ 门槛 1） | `:88-93` | `:93-98` | 等价 | 三元表达式一字不差 |
| 3 | `waterSurfaceYForRange`（甲板侧与掩码侧同口径） | `:175-218` | `:180-223` | 等价 | 逐列跑 `segmentWaterSample`，同一份 terrain/samples/streams |
| 4 | `refineTunnelSpans`（两组重载；桥掩码排除 + 包络 + `landTunnelEnabled`） | `:225-386` | `:230-391` | 等价 | `buried = landTunnelSwitch && newBuried`；`TUNNEL_RUN_MIN_LENGTH=3` 旧 `:39` ↔ 新 `:44` |
| 5 | `TunnelEnvelopeCriterion` 包络突起度判据（23 方法） | `pathfinding/TunnelEnvelopeCriterion.java` 535 行 | 同名 535 行 | 等价 | 全部阈值常量与方法体逐字相同 |
| 6 | `tunnelCosts`（坡罚单价/阈值快照，失败回默认） | `:528-543` | `:533-548` | 等价 | `try/catch(Throwable)` 回 `Costs.defaults()` 相同 |
| 7 | 突起度取证 `prominences`/`lowQuantile`/`logBumpRuns`（默认关） | `:412-520` | `:417-525` | 等价 | 仅 `-Droadweaver.bumpDiag=true` 生效，默认 `false` 两侧一致 |
| 8 | `centerStationOverburden` / `isWetStation` | `:545-567` | `:550-572` | 等价 | 中心站覆土、湿站判定同式 |
| 9 | `collectBridgeSpans` 水掩码主循环 | `:1439-1510` | `:603-674` | 等价 | 水区间起止、`highestWaterSurface` 累积同式 |
| 10 | `segmentWaterSample` / `bridgeWaterSample`（逐 footprint 列 OR；四级水面来源） | `:1522-1630` | `:686-794` | 等价 | 水深 `>=`、`Math.max(1, minWaterDepth)`、`stream.inChannel` 优先等全同 |
| 11 | `appendWaterCrossingSpan` / `crossingTypeForWidth` | `:1632-1652` | `:796-816` | 等价 | 稳定岸线 `BridgeDeckProfile.findStableBankRange` 调用参数相同 |
| 12 | `crossingWidthBlocks` 口径 | `:1655-1671` | `:819-835` | 等价 | **口径未变**：沿路弧长求和 + 首尾半步补偿 `max(1, round(distance + representedBlockWidth))`；仍是「沿路长度」而非「直线河宽」，两版一致（历史坑未被本次重构引入，也未被修掉） |
| 13 | `appendNormalizedWaterSpan`（桥吃掉重叠浅滩 / 浅滩被桥裁剪） | `:1685-1756` | `:849-920` | 等价 | 裁剪/插入顺序与边界（`left+1 < start`、`right > end+1`）相同 |
| 14 | `refineRoadTunnels`（修剪/合并后重建隧道） | `:771-786` | `:579-594` | 等价 | 仍先 `refineTunnelSpans` 再 `refineFinalFillBridgeSpans` |
| 15 | `dryDepressionBridgeDepth`（配置夹 2..16，默认 6） | `:1809-1816` | `:972-979` | 等价 | `DEFAULT_DRY_DEPRESSION_BRIDGE_DEPTH=6` 同值 |
| 16 | 18 个跨度阈值常量 | `:39-79` | `:44-84` | 等价 | 逐个核对：3 / 6 / 8 / 10 / 5 / 256 / 8 / 0.5D / 128 / 12 / 3 / 32 / 16 / 2 全同（仅可见性 `private`→包内） |
| 17 | 诊断日志 `[ROAD-BRIDGE-MASK]` / `[ROAD-LANDTUNNEL]` / `[ROAD-BUMP]` | 分散 | 分散 | 等价 | 方法体逐字相同，连占位符顺序都没变 |
| 18 | `convertLongOceanBridgesToUnderseaTunnels`（≥256 格长海桥转海底隧道） | `:573-651` | `pathfinding/UnderseaTunnelPlanner.java:54-132` | 移动 | 方法体逐字相同（含 `oceanColumns < max(3, ceil(3/4·span))` 判据） |
| 19 | `chooseUnderseaTunnel`（哈希种子 + 配置概率，默认 0.50） | `:653-661` | `UnderseaTunnelPlanner.java:134-142` | 移动 | `SplittableRandom` 种子常量 `0x554E444552534541L` 未变 |
| 20 | `extendIndexByDistance`（陆侧引道 128 格） | `:663-673` | `UnderseaTunnelPlanner.java:144-155` | 移动 | 逐字相同 |
| 21 | `applyUnderseaTunnelProfile` / `underseaRoadY` / `smoothUnderseaApproach` | `:676-768` | `UnderseaTunnelPlanner.java:157-243` | 移动 | `safeClear` 夹 2..16、`smoothUnderseaApproach` 的 `max(16,min(128,2·Δ+16))` 同式 |
| 22 | `refineFinalFillBridgeSpans` 两组 + `specialSpanMask` + `appendFinalFillBridgeSpans` + `findDryDepressionBank` | `:794-1036` | `pathfinding/FillBridgeExtender.java:54-297` | 移动 | 干洼地判据 `targetY - lowestFootprintSurface >= dryBridgeDepth` 同式；深 16 强升桥、端点边界回退同式 |
| 23 | `extendExistingStreamsBridgeApproaches` / `extendExistingBridgesIntoCorridor` / `extendBridgeBank` / `corridorResumes` | `:838-931` | `FillBridgeExtender.java:98-191` | 移动 | 桥头延伸「含 1 个稳定岸站」与 `STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS=2` 同值 |
| 24 | `resolvePhysicalSpanOverlaps` / `normalizeTunnelSpans` / `mergeRangesInto` | `:1039-1128` | `pathfinding/SpanConflictResolver.java:59-159` | 移动 | 隧道/桥分别合并、`range[0] <= end+1` 邻接合并同式 |
| 25 | `resolveOverlappingWaterSpans`（隧道优先，桥/浅滩被裁或丢弃） | `:1141-1210` | `SpanConflictResolver.java:161-235` | 移动 | 单站桥余量丢弃、单站浅滩保留的规则相同 |
| 26 | `retainVerticallyRelevantCrossings` / `tunnelFitsBelowWaterBed` | `:1217-1305` | `SpanConflictResolver.java:237-323` | 移动 | 10 参签名与「隧道完全在水下才撤水面跨度」判据相同 |
| 27 | `appendTunnelWithSurfaceMouths` / `sampleTunnelFootprints` / `fullWidthSurfaceConnected` / `maximumFootprintOverburden` / `overburden` | `:1307-1437` | `pathfinding/TunnelFootprintSampler.java:55-186` | 移动 | 洞口向外找满宽地表站的循环、找不到时「保留深挖隧道」的回退相同 |
| 28 | `calculateAStarRoadPath` / `calculateAStarRoadPathDetailed` / `buildSegments` | `pathlogic/pathcalc/RoadPathCalculator.java:909-1020`、`:1591` | `pathlogic/pathcalc/PathMetricsOps.java:387-...`、`:211` | 移动 | 8 处差异经「限定前缀不敏感」比对**全部只是类名搬家**（例：`StructureAvoidanceService.ensurePlanningSnapshotForPathAsync` → 静态导入 `SnapshotOrchestrator.ensurePlanningSnapshotForPathAsync`） |
| 29 | `calculateFromPlannedPath` / `finalizePath` / 三个 `repair*WithCorridorRefresh` / `repairRiverRouteSegments` / `refreshPlanningSnapshot` | `:1023-1580` | `pathlogic/pathcalc/PathFinalizeOps.java:83-...` | 移动 | 同上，全部为类名搬家；`finalizePath` 的段构建、结构与村庄截断顺序未变 |
| 30 | `repairFinalHeightConflict` / `repairAfterNetworkMerge` / `rebuildRoadDataFromProfile` / `shouldSkipExpensiveNetworkMergeHeightRepair` / 冲突提示 API | `:144-908` | `RoadPathCalculator.java:154-...` | 等价 | 47/55 个方法体逐字相同 |
| 31 | `RoadPathCalculator` 常量与缓存 | `:58-124`、`:1461` | 同文件 `:57-135`、`:864` | 等价 | `TERRAIN_PLANNING`、`RIVER_LOCAL_DETOUR_BLOCKS=50`、`MAX_EXACT_CONSTRAINT_CYCLES=4`、`NETWORK_MERGE_*=512/64/24`、`ENDPOINT_APPROACH_STATIONS=24`、`LOCAL_DETOUR_MAX_LENGTH_RATIO=1.6`、`REBUILD_MAX_CENTER_STEP=2`、`UNSMOOTHED_MAX_LENGTH_RATIO=1.15`、`CONSTRUCTION_CONFLICT_HINTS`、`SNAPSHOT_WAIT_CACHE` 全部同值同初始化 |
| 32 | `Road.generateRoad` 里的跨度交付链（提取 → 剖面 → 细化 → 3 轮桥细化 → 保护区 → 复用） | `pathlogic/core/Road.java:161-270` | `pathlogic/core/Road.java:177-238`（`extractPhysicalSpans`/`settleProfile`） | 等价 | 语句级 diff 只显示：3 处类名前缀、2 处 `extractPhysicalSpans(...)`、2 处 `settleProfile(...)`、`StructureRepair`、静态导入、以及 1 行新增的 `logSpecIntegrity(...)`；**顺序与循环次数（`bridgeRefinement < 3`）未变** |
| 33 | `snapAllRoads` / `snapAroundConnection` / `snapAroundConnectionAsync` / `snapAllRoadsAsync` | `postprocess/RoadSnapService.java:111-330` | `postprocess/RoadSnapService.java:123-...` | 等价 | 方法体逐字相同 |
| 34 | `snapRoadList`（吸附主循环） | `:182-330` | `RoadSnapService.java`（`static void snapRoadList`） | 等价 | 唯一差异：`catch (StructureAvoidanceService.PlanningSnapshotUnavailableException)` → `catch (PlanningSnapshotUnavailableException)`（嵌套类提升为顶层类，构造器签名同为 `String`） |
| 35 | `connectSecondaryToPrimary` / `connectSecondaryToPrimaryOnce` | `:332-516` | `postprocess/JunctionConnector.java:71`、`:340` | 移动 | 方法体逐字相同 |
| 36 | `createSecondaryReuseAtCorridor` | `:517-666` | `JunctionConnector.java:256` | 移动 | 逐字相同 |
| 37 | `lowerPrimaryTunnelGradeAtJunction` | `:667-864` | `JunctionConnector.java:406` | 移动 | 逐字相同 |
| 38 | `networkReuseStationCount` | `:1329` | `JunctionConnector.java:76-...` | 移动 | 逐字相同；调用点仍在 `connectSecondaryToPrimary` 内 |
| 39 | `deduplicateExactSpans` / `enforceNetworkReuseConstructionOwnership`（两个重载） | `:865-1328` | `RoadSnapService.java:391-...` | 等价 | 方法体逐字相同 |
| 40 | `markSnapTargets`（`SNAP_DIST2` 进 / `SPLIT_DIST2` 出 / 端点隧道口例外） | `:3094-3133` | `postprocess/EndpointConnectOps.java:489-528` | 移动 | 仅 `nearest.index`/`nearest.dist2`（record 私有字段）→ `nearest.index()`/`nearest.dist2()`；比较符与短路顺序不变 |
| 41 | `NearestResult` / `ConnectionOption`（record 及其比较器） | `:3577-3605` | `postprocess/SnapInternals.java:51-84` | 移动 | `betterThan` 四级比较（复用站数↑、构造长度↓、段数↓、mode 字典序）逐字相同 |
| 42 | 嵌套类 `RoadIndex` / `SegmentGrid` | `:3334-3370`、`:3593-3623` | `postprocess/RoadIndex.java`、`SegmentGrid.java` | 移动 | 结构与方法体相同（`masks = new EnumMap<>(SpanType.class)`、`cells = new HashMap<>()`） |
| 43 | `roadBounds` / `spanMaskView` / `RoadBounds`（含 `gap`） | `:3661-3700` | `RoadSnapService.java:948-995` | 等价 | 逐字相同 |
| 44 | 24 个吸附常量（`SNAP_THRESHOLD`…`HEAD_TRIM_TOLERANCE`） | `:42-368` | 同名文件 | 等价 | `SNAP_THRESHOLD=RoadConstants.ROAD_SNAP_THRESHOLD`、`SPLIT=24`、`MIN_RUN=3`、`GRID_SHIFT=3`、`SNAP_DIST2=(long)SNAP*SNAP` 等全部同表达式；`RoadConstants.java` 只差 1 行 import |
| 45 | 连接/修剪/复用主体（`tryAbsorbEndpointCorridor`、`tryConnectEndpoint`、`tryMergeOverlappingRun`、`trimSecondaryHead/Tail`、`EndpointApproachConnector` 全套） | `:1352-3098` 各方法 | `EndpointConnectOps.java`、`JunctionTrimOps.java`、`EndpointApproachConnector.java`、`NetworkReuseMerger.java` | 移动 | 这 4 个新类共 70 个方法，**全部**能在旧 `RoadSnapService` 里找到方法体逐字相同的对应 |
| 46 | **长桥 ±5 格正弦弯（弦长 ≥96）** | `pathlogic/bridge/BridgeSegment.java:48-71` | `BridgeSegment.java:49-72` | 等价 | 整份文件只差文件头注释；`RoadConstants` 96/5.0 未变 |
| 47 | `Line.pointAt` / `mapRoadPosition`（正弦偏移与列映射） | `util/Line.java:45-82` | `util/Line.java:46-83` | 等价 | `horizontalBend * Math.sin(Math.PI * t)` 同式 |
| 48 | `BridgeDeckProfile`（14 方法：`minimumDeckY`/`applyFixedRange`/`enforceAdjacentBridgeContinuity`/稳定岸线搜索…） | 297 行 | 298 行 | 等价 | 14/14 方法体逐字相同；`STABLE_SEARCH_SEGMENTS=32`、`STABLE_WINDOW_SEGMENTS=4` 等同值 |
| 49 | 桥包其余（`BridgeBuilder`/`BridgeRangeCalculator`/`BridgePierPolicy`/`BridgeShare*`/`CulvertPlanner`/`Buoy*`） | `pathlogic/bridge/` 14 文件 | 同 14 文件 | 等价 | **整包 131/131 个方法体逐字相同，0 个方法体在旧仓找不到对应** |
| 50 | `RoadTunnelBuilder`（门户、灯笼、景观点、安全检查） | 1519 行 / 88 方法 | 1002 行 / 88 方法 | 等价 | 86 个方法体逐字相同；2 个「差异」是 record（`TunnelPlan`、`SafetyCheck`）**只改了可见性**，字段/顺序一个没变；其余为 `private static final`→`static final` 与拆分到 `PlannedSectionBuilder`/`PortalSafetyRules`/`ScenicDomeBuilder` |
| 51 | `Road.applyCrossingProfiles`（定甲板：稳定岸线 / 干峡谷 / 短浅滩 / 固定桥面 / 相邻桥连续性） | `pathlogic/core/Road.java:1442-...` | `pathlogic/core/RoadSpanProfileOps.java:245-...` | 移动 | `RoadSpanProfileOps` 12 个方法里 10 个在旧仓有逐字相同方法体，另 2 个（`isSharedElevationEndpoint`/`isUnknownStructureEndpoint`）只差类名前缀 |
| 52 | `isVillageEndpoint`（端点归属推断，决定端点高度锁） | `pathlogic/structures/StructureAvoidanceService.java` | `pathlogic/structures/EndpointOwnerResolver.java:148-177` | 等价 | 判据（村庄结构 + 水平距² ≤ 16² + 出生点排除）一字未改；全表扫描换成 `StructureBoxIndex.fromChunkBuckets(snapshot.boxesByChunk())` 的桶查询，桶表按盒 AABB±roadRadius 逐区块入桶（`PlanningSnapshot.java:118-129`）⇒ 候选必为超集，结果不变（细节见「我不确定的地方」） |
| 53 | `StructureAvoidanceService` 4766 → 998 行的拆分（`SnapshotOrchestrator`/`ProtectedGapEvaluator`/`PreparedStartResolver`/`EndpointOwnerResolver`/…） | 4766 行 / 238 方法 | 998 行 + 多个新类 | 移动 | 238 个方法里 236 个方法体逐字相同；2 个「差异」是 `PlanningSnapshot` record 组件搬家与 §52 的索引化 |
| 54 | **纵断面求解器：旧版没有「DP 求解」这条路** | 无 | `pathlogic/core/TargetYComputation.java:452-465`、`:567-660`；`pathlogic/profile/ProfileDpSource.java`、`ProfileSolverMode.java` | **改变** | 见 ★B-1 |
| 55 | —— 新增自检日志 `logSpecIntegrity` | 无 | `pathlogic/core/Road.java:641`、`:795-810` | 新增 | 每条路结束时打一行 `road spec OK/VIOLATION`，全包在 `try/catch(Throwable)` 里，只写日志不改数据 |
| 56 | —— 新增桥范围缓存 `BuildFrame` | 无 | `pathlogic/construction/BuildFrame.java` | 新增 | 把「每区块重算 `BridgeRangeCalculator.compute`」变成按「路内容指纹 + 4 个桥配置标量」缓存，取用时深拷贝掩码。见「我不确定的地方」 |
| 57 | —— 新增结构盒网格索引 `StructureBoxIndex` | 无 | `pathlogic/structures/StructureBoxIndex.java` | 新增 | 供 §52 与避让判定使用；注释明确「只保证不漏，精判归调用方」 |
| 58 | —— 新增 `pathlogic/profile/` 整包（DP 求解器、AB 观测器、指标） | 无 | `pathlogic/profile/*`（9 文件） | 新增 | 与 §54 同源特性 |

**行数统计：等价 31（#1-17、#30-34、#39、#43、#44、#46-50、#52）/ 移动 22（#18-29、#35-38、#40-42、#45、#51、#53）/ 删除 0 / 改变 1（#54）/ 新增 4（#55-58）。**

---

## ★ 行为改变清单

### ★B-1 新版多了一条「DP 求纵断面」路径，且在任务基线 `7bc6023` 上默认开启

**旧版怎么写**（`pathlogic/core/Road.java:1442-` 的 `computeTargetY` 收尾，全文 397 行，`/tmp/old_cty.txt` 可复现）：

```java
applyCrossingProfiles(level, cache, terrain, segments, centers, smoothed, crossingRanges, cfg,
        streams, routeBridgeMinWaterDepth());
List<RoadSpan> underseaSpans = PathSpanExtractor.convertLongOceanBridgesToUnderseaTunnels(
        spans, segments, level, cache, footprintSamples);
...
smoothed = RoadGradePlanner.enforceContinuousSlopePreferCut(
        smoothed, isCrossing, cfg.maxSlopeStepPerTwoSegments(), pinnedMask,
        RoadGradePlanner.configuredStationSpacing(centers));
List<Integer> out = new ArrayList<>(n);
for (int v : smoothed) out.add(v);
// ← 到此为止：smoothed 就是最终剖面，没有任何「求解器」概念
```

**新版怎么写**（`pathlogic/core/TargetYComputation.java`，`computeTargetY` 搬到此处后加了分派）：

```java
// ── 剖面求解器模式分派（S7 第 1 级）────
int[] legacySnapshot = smoothed.clone();
RoadGradePlanner.SurfaceGradeLimits gradeLimits = RoadGradePlanner.SurfaceGradeLimits.capture();
smoothed = applyProfileSolver(level, centers, smoothed, terrainY, isCrossing, tunnelMask,
        pinnedMask, startBoundary, endBoundary, cfg, gradeLimits, connection);
List<Integer> out = new ArrayList<>(n);
for (int v : smoothed) out.add(v);
```

```java
private static int[] applyProfileSolver(...) {
    if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) {
        return smoothed;                       // LEGACY：原样返回，行为与旧版一致
    }
    ...
    var solved = ProfileDpSource.solve(terrainY, isCrossing,
            maxCut, maxFill, maxStep, perTwo, constraints);
    if (solved.isEmpty()) { LOGGER.warn("... infeasible, falling back to legacy"); return smoothed; }
    return solved.get();                       // ← 整条剖面被 DP 解替换
}
```

**基线状态（关键）**：`git show 7bc6023:.../ProfileSolverMode.java` 的 `parse()` 是 `if (raw == null || raw.isBlank()) return DP; ... return DP;`，注释写着「2026-11-13 起为默认」⇒ **任务基线里 DP 是默认**。

**为什么这会改变 B 组的产出**（三条传导链，全部以 `targetY` 为输入）：

1. **干洼地桥数量/位置**：`FillBridgeExtender.refineFinalFillBridgeSpans` 的判据是 `targetY.get(i) - lowestFootprintSurface[i] >= dryBridgeDepth`（旧 `PathSpanExtractor.java:950`、新 `FillBridgeExtender.java:210`）。DP 以「最小土方」为目标、且受 `maxCut/maxFill` 施工窗口约束，剖面的填方深度分布与旧 15 道 pass 的结果不同 ⇒ 同一地形下被判成「需要架桥的深填区」的站段会变（一般是变少），最终落盘的 BRIDGE 集合随之改变。
2. **桥面高度不再是 `applyFixedRange` 的结果**：`RoadSpanProfileOps.applyCrossingProfiles` 在 DP 之前先把桥面写成 `BridgeDeckProfile.applyFixedRange(profile, ..., minimumDeck, cfg.bridgeRampSegments())`（`RoadSpanProfileOps.java:309`），随后 DP 用「整条最优剖面」**整体覆盖**这个数组。`7bc6023` 版 `applyProfileSolver` 是 10 参版本、**没有把桥面水位下界喂进去**（`ProfileDpSource.Constraints` 只有 `(pin, mustBeCovered, minCover)`）⇒ DP 完全可以把桥面压到水面附近甚至水下。
3. **短浅滩 `applyShortFordClearance` 的效果同样被覆盖**（`RoadSpanProfileOps.java:307` 写入 → `:463` 被 DP 覆盖）。

**可观察差异**：桥/浅滩数量与位置变化；桥面 Y 与河面净空变化（极端情况「路泡在水里」）；土方量显著下降。
**仓库自己的证据**：提交 `46ee02e`（17:11:39，核查期间产生）标题即「**纵断面求解器默认从 DP 切回 LEGACY —— 三份独立核查证明 DP 会插水**」；`ProfileSolverMode` 文档自述「真实地形 AB 数据：57 次观测、土方 325,346 → 8,996」。这两条都指向「DP 与旧版输出不同」这一结论（提交信息是仓库作者自述，不是我实测）。
**回退开关**：`-Droadweaver.profileSolver=legacy`（`7bc6023`）或 `dp`（`46ee02e` 之后）可切换；非法值在 `7bc6023` 落 DP、在 `46ee02e` 落 LEGACY —— **默认值在核查期间被改了两次**（见文末「我不确定的地方」）。

> **判定**：这是**改变**，但**不是重构丢失**——旧版从来没有这条路，新版是「多了一种可选求解器 + 一个可配置默认值」。要回答「重构是否等价」，必须把默认值钉死：**默认 LEGACY 时与旧版等价；默认 DP 时不等价。**

### ★B-2 三处「新增但不改变既有行为」

| 新增物 | 位置 | 为什么不改变行为 |
|---|---|---|
| `logSpecIntegrity` | `Road.java:641`（调用）、`:795-810`（实现） | 只在 `Road.generateRoad` 成功收尾处调用一次；`try { RoadSpec.from(rd) ... } catch (Throwable t) { }` 全包，只 `LOGGER.info/warn`，不写回任何数据 |
| `BuildFrame` 桥范围缓存 | `pathlogic/construction/BuildFrame.java` | 缓存值 = `BridgeRangeCalculator.compute(middlePositions, spans, cfg)`（纯函数）；键 = `RoadShardStorage.computeFingerprint(data)` + `bridgeEnabled/bridgeUseBuoysInstead/bridgeMaxLengthBlocks/bridgeMergeGap` 压成的标量（`BuildFrame.java:83-90`），而 `BridgeRangeCalculator.compute` 恰好只读这 4 个配置字段（`BridgeRangeCalculator.java:64/68/69/90`）；取用走 `copyForUser()` 深拷贝 |
| `StructureBoxIndex` | `pathlogic/structures/StructureBoxIndex.java` | 只把「全表扫描」换成「按区块桶查候选超集」，判据本身（含距离平方比较）留在调用方未改（§52） |

### 唯一一类「机械改写」的完整清单（10 处，均已逐条确认无表达式变化）

| 文件 | 方法 | 差异内容 |
|---|---|---|
| `PathMetricsOps` | `calculateAStarRoadPathDetailed`(8 参) | `StructureAvoidanceService.ensurePlanningSnapshotForPathAsync` → 静态导入 `SnapshotOrchestrator.*` 的同名方法（该方法体本身逐字相同） |
| `PathFinalizeOps` | `finalizePath`(7 参) | `StructureAvoidanceService.stopAtEndpointBuilding/stopAtVillageBuilding/intersectsPreparedRoute/...` → 静态导入；可见性 `private`→`public` |
| `PathFinalizeOps` | `repairStructureIntersectionsWithCorridorRefresh` | `AccurateGradeRepairPlanner.ExactStructureRepairOutcome` → `StructureRepair.ExactStructureRepairOutcome`；`repairStructureIntersectionsAtExactClearanceDetailed` 换宿主类（方法体逐字相同） |
| `PathFinalizeOps` | `repairLakeIntersectionsWithCorridorRefresh` | `AccurateGradeRepairPlanner.repairLakeIntersections` → `LakeRiverRepair.repairLakeIntersections`（方法体逐字相同） |
| `PathFinalizeOps` | `repairRiverIntersectionsWithCorridorRefresh` | 同上 |
| `PathFinalizeOps` | `repairRiverRouteSegments` | 同上 |
| `PathFinalizeOps` | `refreshPlanningSnapshot` | 同第 1 条 |
| `PathMetricsOps` | `buildSegments` | 4 处 `StructureAvoidanceService.*` → 静态导入 |
| `RoadSnapService` | `snapRoadList` | 异常类 `StructureAvoidanceService.PlanningSnapshotUnavailableException`（嵌套）→ 顶层同名类，构造器同为 `String` |
| `EndpointConnectOps` | `markSnapTargets` | record 私有字段访问 `nearest.index` → `nearest.index()`（Java 21 nestmate/访问器差异），表达式不变 |

---

## 真删除清单（0 项）

**结论：B 组没有任何一个方法、常量或判定被真正删除。** 反证四条：

1. **方法体逐字命中**：旧 `PathSpanExtractor` 60/60、`RoadPathCalculator` 55/55（47 逐字 + 8 机械改写）、`RoadSnapService` 142/142（139 逐字 + 3 机械改写/记录搬家）在**新仓全库**都能找到对应方法体，无一条「找不到」。
2. **包级方法数不减**：`pathfinding/` 94→96、`pathlogic/pathcalc/` 66→66、`postprocess/` **195→195**。`postprocess` 是精确相等——旧 4 个文件 195 个方法，新 13 个文件仍是 195 个方法，一个没多一个没少。
3. **包级行数不减**：`pathfinding/` 2476→2738、`pathcalc/` 1961→2136、`postprocess/` 5111→5621。三处都**变长**。
4. **反向扫描无孤儿**：对新 `postprocess/` 全部 13 个文件、`UnderseaTunnelPlanner`/`FillBridgeExtender`/`SpanConflictResolver`/`TunnelFootprintSampler` 做反向审计（每个新方法体在旧仓能否找到），结果：**除上表 3 处机械改写外，其余 100% 在旧仓有逐字相同的方法体**；再对这批新类做「方法名全仓出现次数」扫描，**没有任何一个只被声明不被调用的死方法**。

顺带说明两个「看起来像删除」其实不是的东西：

- `CANYON_BANK_SEARCH_SEGMENTS=8`、`CANYON_MIN_DEPTH_BLOCKS=10`、`CANYON_MIN_WIDTH_BLOCKS=5`（旧 `PathSpanExtractor.java:57-59` ↔ 新 `:62-64`）**两版都声明了但都没有任何引用**（全仓 grep 只有声明行）。它是旧版遗留的死常量，新版按原样保留，不是本次重构删的；旧注释也写明「A canyon is not a river. Only the actual water mask above may create BRIDGE or ROAD_CROSSING spans.」⇒ 干峡谷不造桥是**两版共同**的既定设计。
- `UNDERSEA_TUNNEL_SELECTION_PROBABILITY=0.5D` 同样两版都只在声明处出现，实际取值走 `ConfigService.get().terrainProfile().oceanUnderseaChance()`（默认 `0.50D`，旧 `TerrainProfileConfig.java:17` ↔ 新 `:18`）——两版一致。

---

## 我不确定的地方

1. **DP 默认值在核查期间被反复改，我无法给出「最终默认是什么」的结论。** 时间线（全部来自 `git log` / `git show`）：`7bc6023`（16:52）默认 **DP** → `46ee02e`（17:11）默认改回 **LEGACY** → 工作区 17:13 又未提交地把 `ProfileSolverMode.parse` 改回 **DP**，同时 `TargetYComputation` 加了 `deckFloor` 水位下界（12 参 `applyProfileSolver`）→ `e82bea0`（17:17）提交「桥面水位下界改为 DP 的求解约束」。也就是说 ★B-1 的第 2 条（DP 会把桥面压进水里）在 `e82bea0` 之后**可能**已被 `deckFloor` 修掉，但那条修改是我审计结束之后落的，**我没有逐行核过它**，因此不能声称「已修复」或「仍然会插水」。
2. **`BuildFrame` 缓存的键完备性（条件风险，非已证缺陷）。** 缓存键只压缩了 4 个配置字段（`bridgeEnabled` / `bridgeUseBuoysInstead` / `bridgeMaxLengthBlocks` / `bridgeMergeGap`），而 `BridgeRangeCalculator.compute` 当前确实只读这 4 个；但这是**靠注释纪律维持的隐含契约**——将来 `BridgeRangeCalculator` 多读一个配置字段而忘了同步 `BuildFrame.configVersion`，就会出现「配置改了但桥掩码还是旧的」。另外我没有核实 `RoadShardStorage.computeFingerprint(data)` 是否把 `spans` 全部纳入（若只哈希 segments，改 spans 后可能命中旧缓存）。
3. **`isVillageEndpoint` 的索引化等价性依赖 `boxesByChunk` 覆盖完整。** 我核了 `PlanningSnapshot` 的构造（`PlanningSnapshot.java:118-129`：每个盒按 `minX/maxX±roadRadius`、`minZ/maxZ±roadRadius` 逐区块入桶）与 `withAdditionalRoadRadius` 的重建，逻辑上「距端点 ≤16 格的盒必落在查询桶内」成立；但 `merge(...)` 分支里新增盒子的入桶逻辑我只扫了一眼，没有逐行验；如果某条 merge 路径漏入桶，`isVillageEndpoint` 会从 true 变 false（端点高度锁丢失）。建议由 A 组（结构避让）交叉确认。
4. **`crossingWidthBlocks` 的口径问题两版都在。** 它量的是「沿路弧长 + 首尾半步」，不是「河面直线宽度」。对斜穿河道的情况，`<= 12` 判浅滩会比真实河宽更早升级成桥。这是**旧版就有的口径**（我逐字核对过两版相同），不是本次重构引入；我只是把它记下来，避免下一次审计又把它当成本次重构的新问题。
5. **`CANYON_*` 三个常量的原意我判断不出。** 两版都没有引用、也没有测试引用，注释只说「干峡谷不是河」。它可能对应某个早已删除的干峡谷架桥算法，但我无法从代码里还原它当年怎么用。
6. **未做的事**：没有编译、没有跑测试、没有跑游戏。所有结论都来自源码静态比对；「方法体逐字相同」不等于「运行时行为一定相同」——调用点、调用顺序、线程、配置读入时机我都核了（§32、§6、§44），但**并发路径（吸附在异步区块线程里跑）与缓存失效时机没有运行期证据**。
7. **行号可能漂移**：新仓在我核查期间被改了 3 次（`46ee02e`/`e82bea0`/工作区）。表中新仓行号是 17:15 前后的工作树；`TargetYComputation` 与 `ProfileSolverMode` 两个文件的行号**已经不能保证与当前工作树一致**（其余文件未变）。