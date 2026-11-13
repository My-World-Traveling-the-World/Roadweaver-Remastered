# B 组：跨度提取/路径计算/道路吸附 —— 方法体逻辑等价性核查

- 旧仓库（oracle）：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`
- 新仓库（重构版）：`D:\云游四海-Mod源码\roadweaver-next` @ `7bc6023`
- 核查范围：`pathfinding/PathSpanExtractor`、`pathlogic/pathcalc/RoadPathCalculator`、`postprocess/RoadSnapService`，及其全部拆分类（`FillBridgeExtender` / `SpanConflictResolver` / `TunnelFootprintSampler` / `UnderseaTunnelPlanner` / `JunctionConnector` / `JunctionTrimOps` / `EndpointConnectOps` / `EndpointApproachConnector` / `NetworkReuseMerger` / `SegmentGrid` / `RoadIndex` / `SnapInternals`）与被它们调用的 `BridgeDeckProfile`、`BridgeSegment`、`util/Line`、`TunnelEnvelopeCriterion`、`SurfaceWaterClassifier`、`RoadConstants`
- 方式：**全程只读**。未改/建/删任何 `.java`，未 git 操作，未编译。唯一的写入物就是本文件。

---

## 结论摘要

- **方法级账目（三个旧文件共 249 个类级成员）：逐字节等价 237 / 机械改写 10 / 记录迁移 2（内容逐字相同）/ 真正的逻辑改变 0**
- **逻辑点级（本文「逐项对照」共 42 行）：逻辑等价 41 项 / 逻辑改变 1 项（**条件性**：仅当显式 `-Droadweaver.profileSolver=dp` 时才发生；**默认路径已于 2026-11-13 17:09:20 切回 LEGACY**，见 ★B-1 与文末「核查期间工作树变更」）/ 无法判定 3 项**（见文末「无法判定的地方」）
- **长桥 ±5 格正弦弯：在。** `BridgeSegment.java:55-62`（旧）↔ `BridgeSegment.java:56-63`（新）逐字节相同；`Line.withHorizontalBend`/`pointAt` 中的 `Math.sin(Math.PI * t)`（旧 `Line.java:28-57` ↔ 新 `Line.java:29-58`）逐字节相同；建造侧落地 `BridgeBuilder.java:74-78`（旧）↔ `BridgeBuilder.java:79-83`（新）也逐字节相同。**注意**：弯是水平几何、只依赖站点 XZ 与哈希，与剖面求解器选 LEGACY 还是显式 DP 无关。
- **水面判定（逐 footprint 列 OR）：一致。** `PathSpanExtractor.java:1521-1557`（旧）↔ `PathSpanExtractor.java:685-721`（新）方法体逐字节相同；核心循环 旧 `1539-1549` ↔ 新 `703-713`，语义是「footprint 里任一列是水 ⇒ 整站记为水」（`found = true`，`wetColumns++`，`Math.max` 取水面 / `Math.min` 取河床）；原注释 `Samples every rasterized road column, not just the center station` 两侧都在（旧 1521 / 新 685）。
- **桥面稳定岸线扩展：一致。** `BridgeDeckProfile.findStableBankRange` 旧 `236-250` ↔ 新 `237-251`，`findStableLeft/Right` 旧 `252-270` ↔ 新 `253-271`，`stableWindow` 旧 `272-281` ↔ 新 `273-282`；`STABLE_SEARCH_SEGMENTS=32 / STABLE_WINDOW_SEGMENTS=4 / STABLE_WINDOW_VARIATION=2` 旧 `11-13` ↔ 新 `12-14`。整份 `BridgeDeckProfile.java` 新旧只差文件头注释 1 行（`diff` 仅 `+/* 文件职责：… */`）。**已知的「向外扩到稳定岸线」膨胀行为被完整保留**（`left/right` 向两侧找 32 站内的 4 站稳定台地，找不到才退回水区间端点）。
- **最高风险**：B 组三个文件本身**没有**逻辑改变。唯一能让「同一输入算出不同结果」的是**跨组**的剖面求解器：**默认已是 LEGACY（与旧版等价）**；只有玩家/集成包显式加 `-Droadweaver.profileSolver=dp` 时，`targetY` 才改由 DP 求解，而 `refineTunnelSpans` / `refineFinalFillBridgeSpans` / `retainVerticallyRelevantCrossings` 都以 `targetY` 为判据输入 ⇒ 最终落盘的桥/隧道 span 集合与桥面 Y 可能不同（水掩码决定的初始 BRIDGE/ROAD_CROSSING 的 XZ 范围不受影响）。**注意：核查期间该默认值被改动过一次（17:09:20 从 DP 切回 LEGACY），详见 ★B-1 与「核查期间工作树变更」。**

---

## 0. 方法级全覆盖账目（先说清楚「数是怎么数出来的」）

抽取算法：按「声明 → `(` 参数表 `)` → 花括号配平」抽出**整个方法体**；把 `//`、`/* */` 全部抹成空格（保留字符串/字符字面量），再把所有空白折叠成单空格后比对。因此「逐字节等价」= **去注释、去空白后方法体字符串完全一致**（缩进、换行、注释不影响判定；任何增删一个字符、换一个比较符、换一个常量都会被抓出来）。

| 旧文件 | 旧成员数 | 新落点 | 等价 | 机械改写 | 记录迁移 |
|---|---|---|---|---|---|
| `pathfinding/PathSpanExtractor.java`（1816 行） | 60 | 本体 980 行 + `UnderseaTunnelPlanner`(250) + `FillBridgeExtender`(297) + `SpanConflictResolver`(326) + `TunnelFootprintSampler`(186) | **60** | 0 | 0 |
| `pathlogic/pathcalc/RoadPathCalculator.java`（1709 行） | 54 | 本体 872 行 + `PathFinalizeOps`(517) + `PathMetricsOps`(494) | **46** | **8** | 0 |
| `postprocess/RoadSnapService.java`（3708 行） | 135 | 本体 995 行 + `JunctionConnector`(556) + `JunctionTrimOps`(425) + `EndpointConnectOps`(529) + `EndpointApproachConnector`(633) + `NetworkReuseMerger`(800) + `SegmentGrid`(79) + `RoadIndex`(89) + `SnapInternals`(68) | **131** | **2** | **2** |
| 合计 | **249** | — | **237** | **10** | **2** |

- 交叉验证：`PathSpanExtractor` 旧文件按「方法声明行」直接 grep 也是 60 条，`RoadSnapService` 133 条（脚本 135 = 133 方法 + 2 record 头），两边对得上，未漏方法。
- **新 `PathSpanExtractor.java` 里 0 个「新方法体」**：对新仓做反向扫描（新方法体在旧仓找不到对应）时，`pathfinding/` 下只有 A* 实现、`PathfinderFactory`、`AccuratePathHeightResolver` 等非本组文件；`pathlogic/bridge/` 整包 **0 个新方法体**。也就是说桥相关的类**整包只有文件头注释和 import 变化**。
- 10 处「机械改写」的逐条清单见附录 A。**没有任何一处改写了条件、比较符、循环边界、早退顺序或常量。**
- 主控点名要搜的 `WaterTierAlignment`：**新旧两仓都不存在**（`grep -rn "WaterTierAlignment"` 两边均 0 命中）。水位/水列判定实际落在 `SurfaceWaterClassifier`（整文件仅差文件头）、`StreamsReflowingCompat`（仅差文件头 1 行）、`PathTerrainField.SampleBundle`（整文件字节相同）三处，本次均等价——也就是说旧版 `PathSpanExtractor` 注释里提到的「高度图兜底」链路没有被换实现。

---

## 逐项对照

> 判定口径：**等价（逐字节）**＝去注释/空白后方法体完全相同；**等价（机械改写）**＝只差「跨类限定前缀 / 静态导入 / record 访问器 / 异常类搬家」，无表达式变化；**等价（索引重写）**＝控制流重写但已逐条证明判定集合相同。

| # | 逻辑点 | 旧版位置 | 新版位置 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | `extractSpans` 主流程（采样 → 有效水深门槛 → 水掩码） | `PathSpanExtractor.java:111-149` | `PathSpanExtractor.java:116-154` | 等价（逐字节） | 方法体相同；两侧都调 `effectiveBridgeMinDepth(...)` 后进 `collectBridgeSpans` |
| 2 | **水面判定：逐 footprint 列采样后取 OR** | `PathSpanExtractor.java:1521-1557`（循环 1539-1549） | `PathSpanExtractor.java:685-721`（循环 703-713） | 等价（逐字节） | `for (BlockPos position : footprint)` 逐列调 `bridgeWaterSample`；`if (!sample.water()) continue;` 后 `wetColumns++; found=true;` |
| 3 | 水面来源优先级（Streams 河道 → Streams 湖泊 → `PathTerrainField` 精确列 → 高度图兜底） | `PathSpanExtractor.java:1570-1630` | `PathSpanExtractor.java:734-794` | 等价（逐字节） | 四个分支的 `return new BridgeWaterSample(...)` 参数一字不差 |
| 4 | 水深门槛比较方向 | `:1608-1609`、`:1622-1624` | `:772-773`、`:786-788` | 等价（逐字节） | 两处都是 `waterDepth >= Math.max(1, minWaterDepth)`，**不是 `>`** |
| 5 | `BRIDGE_FIRST` 下有效水深门槛降为 1 | `:89-96` | `:94-101` | 等价（逐字节） | `mode == BRIDGE_FIRST ? 1 : configuredMinDepth` |
| 6 | 桥/浅滩宽度阈值（决定 BRIDGE 还是 ROAD_CROSSING） | `:1648-1652`（`ROAD_CROSSING_MAX_WIDTH_BLOCKS = 12`，`:67`） | `:812-816`（常量 `:72`） | 等价（逐字节） | `widthBlocks <= 12 ? ROAD_CROSSING : BRIDGE`，`<=` 未变 |
| 7 | 跨度宽度计算 `crossingWidthBlocks`（含两端半站修正） | `:1655-1671` | `:819-835` | 等价（逐字节） | `Math.max(1, (int)Math.round(distance + representedBlockWidth))` 相同 |
| 8 | 隧道判据常量与阈值组合 | `TunnelEnvelopeCriterion.java`（26961 字节） | 同名文件（26961 字节） | 等价（逐字节） | `cmp` 完全相同；`ENVELOPE_HALF_WINDOW_STATIONS=24`(:56)、`ENVELOPE_QUANTILE=0.10`(:59)、`SEED_PROMINENCE_BLOCKS=2`(:64)、`MIN_PROMINENCE_BLOCKS=4`(:75)、`TARGET_BURIAL_BLOCKS=7`(:81)、`MIN_OVERBURDEN_MARGIN_BLOCKS=1`(:87)、`MIN_SEALED_CORE_STATIONS=3`(:100)、`MAX_TUNNEL_RUN_STATIONS=256`(:110) 两版同文件同值 |
| 9 | `refineTunnelSpans`（桥掩码排除 + 包络突起度 + `landTunnelEnabled` 开关） | `PathSpanExtractor.java:231-393` | `PathSpanExtractor.java:232-…` | 等价（逐字节） | `boolean buried = landTunnelSwitch && newBuried;` 在两侧同一位置；`TUNNEL_RUN_MIN_LENGTH=3` 旧 `:39` ↔ 新 `:44` |
| 10 | 干洼地被判成「填方桥」（`refineFinalFillBridgeSpans`） | `PathSpanExtractor.java:798-839`、`:936-955` | `FillBridgeExtender.java:58-99`、`:196-215` | 等价（逐字节） | `targetY.get(index) - lowestFootprintSurface[index] >= dryBridgeDepth` 相同（新 `:210`） |
| 11 | 桥头/Streams 走廊延伸（`extendExistingStreamsBridgeApproaches` / `extendExistingBridgesIntoCorridor` / `extendBridgeBank`） | `:840-935` | `FillBridgeExtender.java:100-215` | 等价（逐字节） | 三个方法体归一化后相同；`STREAMS_BRIDGE_CORRIDOR_GAP_SEGMENTS=2` 旧 `:79` ↔ 新 `:84` |
| 12 | 相邻水域跨度重叠归一（桥吃掉浅滩） | `:1040-1147`、`:1148-1226` | `SpanConflictResolver.java:60-161`、`:162-246` | 等价（逐字节） | `appendNormalizedWaterSpan` 的「BRIDGE 保留、裁剪 ROAD_CROSSING」逻辑相同（旧 `:1685-1757` ↔ 新 `:849-921`） |
| 13 | 竖直相关跨度保留（`retainVerticallyRelevantCrossings`，10 参） | `:1227-1299` | `SpanConflictResolver.java:247-319` | 等价（逐字节） | 参数表、判据、日志占位符全同 |
| 14 | 跨海隧道（长度 ≥256、深 ≥8、概率 0.5、陆侧引道 128） | `:578-772` | `UnderseaTunnelPlanner.java:59-250` | 等价（逐字节） | 常量旧 `:61-65` ↔ 新同名类；`chooseUnderseaTunnel`/`applyUnderseaTunnelProfile` 等 7 个方法体相同 |
| 15 | 隧道足迹满列采样（端口闭合） | `:1313-1447` | `TunnelFootprintSampler.java:61-…` | 等价（逐字节） | `fullWidthSurfaceConnected`/`maximumFootprintOverburden`/`overburden` 相同 |
| 16 | **长桥 ±5 格正弦横弯** | `BridgeSegment.java:55-64` | `BridgeSegment.java:56-65` | 等价（逐字节） | `if (length >= BRIDGE_CURVE_MIN_LENGTH_BLOCKS)` → 哈希奇偶取 `±BRIDGE_CURVE_MAX_OFFSET_BLOCKS` → `Line.withHorizontalBend` |
| 17 | 正弦弯几何实现 | `util/Line.java:28-30`、`:46-57`、`:60-80` | `util/Line.java:29-31`、`:47-58`、`:61-81` | 等价（逐字节） | `offset = horizontalBend * Math.sin(Math.PI * t)` 两侧同式；整份 `Line.java` diff 只有文件头 1 行 |
| 18 | 弯在建造侧真正落地 | `BridgeBuilder.java:74-78`；`RoadConstructionVolumePlanner.java:252-257` | `BridgeBuilder.java:79-83`；`RoadConstructionVolumePlanner.java:251-256` | 等价（逐字节） | `if (geometryLine != null && geometryLine.isCurved()) { seg = new RoadSegmentPlacement(line.mapRoadPosition(...), line.mapRoadPositions(...)); }` |
| 19 | 弯判定用的桥掩码来源 | `BridgeRangeCalculator.java`（整文件） | 同文件 | 等价（逐字节） | `diff` 仅文件头注释 1 行 |
| 20 | **桥面稳定岸线扩展** | `BridgeDeckProfile.java:236-250`（及 `:152`、`:233`） | `BridgeDeckProfile.java:237-251`（及 `:153`、`:234`） | 等价（逐字节） | 整文件 diff 仅文件头 1 行；`findStableLeft/Right` 32 站搜索、4 站窗口、`|Δ|>1` 早退、median≥`minimumBankY` 全同 |
| 21 | 桥面甲板高度 `minimumDeckY`/`applyFixedRange`/`enforceAdjacentBridgeContinuity` | `BridgeDeckProfile.java:1-234` | 同文件 `:1-235` | 等价（逐字节） | 同上（本项只判方法体；它在 DP 路径（仅显式开启时）上是否被使用见 ★B-1 与 C 组） |
| 22 | 吸附入口（`snapAllRoads`/`snapAroundConnection`/`snapAllRoadsAsync`） | `RoadSnapService.java:111-181` | `RoadSnapService.java:123-193` | 等价（逐字节） | 方法体相同 |
| 23 | 吸附主循环 `snapRoadList` | `:182-317` | `:194-329` | 等价（机械改写） | 全方法仅 `catch (StructureAvoidanceService.PlanningSnapshotUnavailableException …)` → `catch (PlanningSnapshotUnavailableException …)`（异常类从嵌套类搬到顶层文件，构造器同 `String message`） |
| 24 | 吸附半径/网格常量 | `:42-109`（源自 `RoadConstants:427-432`） | `:54-121`（源自 `RoadConstants:426-431`） | 等价（逐字节） | `ROAD_SNAP_THRESHOLD=12`、`ROAD_SNAP_SPLIT_THRESHOLD=24`、`ROAD_SNAP_MIN_RUN_LENGTH=3`、`ROAD_SNAP_GRID_SHIFT=3`；`RoadConstants.java` 两版 diff 只有 1 行 import 删除 |
| 25 | 头部裁剪 `HEAD_TRIM_TOLERANCE` / `tryConnect` / `trimSecondaryHead/Tail` | `:982-1258`、`:1058`、`:1073`、`:1173` | `JunctionTrimOps.java:66-342`、`:142`、`:157`、`:257` | 等价（逐字节） | 头部判据「切比雪夫 ≤2 且不是任何 span 端点」相同；`HEAD_TRIM_TOLERANCE=2` 同值 |
| 26 | 复用已有路 `REUSE_EXTEND_*`（容差 2、最短连续 3、高差 ≤1） | `:364-368`、`:384-600` | `JunctionConnector.java:103-107`、`:123-339` | 等价（逐字节） | `matchesPrimaryCorridor` 旧 `:489-501` ↔ 新 `:229-240`：`Math.abs(donorY - targetY) <= REUSE_EXTEND_MAX_HEIGHT_DELTA(1)` |
| 27 | 走廊复用延伸（0.8.366 M2：收集**每一段**合格走廊，不再 `break`） | `:418-470` | `JunctionConnector.java:157-200` | 等价（逐字节） | 同为「逐段收集 + `REUSE_EXTEND_MIN_RUN` 门槛 + 校验失败保留原 candidate」 |
| 28 | 吸附目标标记 `markSnapTargets`（`SNAP_DIST2` 进入 / `SPLIT_DIST2` 退出 / 端点隧道口例外面） | `:3099-3133` | `EndpointConnectOps.java:494-528` | 等价（机械改写） | 仅 `nearest.index`/`nearest.dist2`（record 私有字段，旧版靠 nestmate 访问）→ `nearest.index()`/`nearest.dist2()`；表达式与短路顺序不变 |
| 29 | 交叉点连接候选（`tryAbsorbEndpointCorridor`/`tryConnectEndpoint`/`tryMergeOverlappingRun` 等） | `:1352-3098` 内各方法 | `EndpointConnectOps.java` / `EndpointApproachConnector.java` / `NetworkReuseMerger.java` | 等价（逐字节） | 见附录 B「迁移落点」；逐方法体比对全部相同 |
| 30 | `NearestResult` / `ConnectionOption` record | `:3574-3605` | `SnapInternals.java:51-84` | 等价（迁移） | 组件名/类型/顺序相同；`betterThan` 的四级比较（reuse↑、构造长度↓、段数↓、mode 字典序）逐字节相同 |
| 31 | 嵌套类 `SegmentGrid` / `RoadIndex` | `:3593-3623`、`:3334-3370` | `SegmentGrid.java`、`RoadIndex.java` | 等价（迁移） | 旧类体归一化后是新文件内容的**子串**（长度 1319 / 942 字符，`in` 判定 True） |
| 32 | 失败标记 `LAST_SEGMENT_FAILURE` 及三种取值 | `RoadPathCalculator.java:1583-1592`、`:1609`、`:1658`、`:1672` | `PathMetricsOps.java:203-215`、`:229`、`:277`、`:291` | 等价（逐字节） | `curved_null` / `linear_null` / `still_intersects_protected_structure` 三串相同；`consume` 后 `remove()` 相同 |
| 33 | 重建失败标记 `LAST_REBUILD_FAILURE` | `RoadPathCalculator.java:597-603`、`:619`、`:630` | `RoadPathCalculator.java:607-613`、`:629`、`:640` | 等价（逐字节） | `null_input` / `segments_rejected:<reason>` / `segment_count_mismatch` 相同 |
| 34 | 失败后的重试链路 | `Road.java:298/315/421/476/574` → `RoadPlanningService.java:245-264` → `Road.java:1625-1628` | `Road.java:315/332/438/493/591` → `RoadPlanningService.java:252-271` → `Road.java:767-770` | 等价（逐字节） | `rebindGeneratedRouteForRetry` 两版 20 行 diff 为空；`localRetryStep()` 相同（`RoutePrecisionRetryPolicy.localRepairBatchSteps(configured).get(0)`） |
| 35 | 路径计算入口分派（`calculateFromPlannedPath` / `calculateAStarRoadPathDetailed`） | `Road.java:133-138` | `Road.java:149-154` | 等价（机械改写） | 仅 `RoadPathCalculator.` → `PathFinalizeOps.`/`PathMetricsOps.` 前缀 |
| 36 | 跨度提取接线（两处调用参数必须一致） | `Road.java:161-162`、`:243-244` | `Road.java:684-689`（唯一入口 `extractPhysicalSpans`） | 等价（逐字节） | 新方法体就是 `PathSpanExtractor.extractSpans(segments, level, cache, terrain, streams, routeBridgeMinWaterDepth())`，与旧两处实参相同 |
| 37 | 剖面/隧道结算三连（复制 → `computeTargetY` → `refineTunnelSpans`） | `Road.java:181-182`、`:198-200`、`:254-257` | `Road.java:703-711`（`settleProfile`） | 等价（逐字节） | `new ArrayList<>(spans)` → `computeTargetY(...)` → `PathSpanExtractor.refineTunnelSpans(...)` 顺序与实参相同 |
| 38 | `Road.generateRoad` 全方法体 | 旧 `:91-631`（534 行） | 新 `:107-649`（536 行） | 等价（机械改写 + 1 处纯诊断插入） | 行级 diff 只有：3 处类名前缀、2 处 `extractPhysicalSpans`、2 处 `settleProfile`、1 处 `StructureRepair`、3 处静态导入、1 行 `logSpecIntegrity(rd, rawStart, rawEnd)`（只打日志，见 §可观测性） |
| 39 | 精确高度采样器（水面兜底高度来源） | `AccurateHeightSampler.java`（1090 行） | 同名（981 行）+ `SamplerStatics.java` | 等价（机械改写） | 全文件只有 `private`→包可见、静态辅助搬到 `SamplerStatics`；`inFlightTimeoutMs`/`uniquePositions`/`orderedChunks` 等搬迁体逐字节相同 |
| 40 | 水面/地形判定辅助 | `SurfaceWaterClassifier.java`、`PathTerrainField.java`、`PathfindingHelper.isWaterLike`、`StreamsReflowingCompat.java` | 同 | 等价（逐字节） | 前两者与 `isWaterLike` diff 仅文件头；`StreamsReflowingCompat` diff 仅文件头 1 行 |
| 41 | 村庄端点判定 `isVillageEndpoint` | `StructureAvoidanceService.java:3251-3270` | `EndpointOwnerResolver.java:148-177` | 等价（索引重写） | 判据 `isVillageStructure(...) && 水平距 ≤16²` 一字未改；全表扫描→`boxesByChunk` 桶索引，桶表覆盖整个盒 AABB（`PlanningSnapshot.java:118-129` 按 `minX/maxX±roadRadius` 逐区块入桶）⇒ 候选必为超集（详见 A 组报告同一结论） |
| 42 | **剖面求解器模式（跨组）** | 旧版无此概念（`computeTargetY` 直接返回自己的结果） | `ProfileSolverMode.java:86-92`（当前默认 LEGACY）、`TargetYComputation.java:452-464`、`:567-621` | **条件性改变**（仅显式 `-Droadweaver.profileSolver=dp`；默认已切回 LEGACY） | 见 ★B-1 |

---

## 逐项证据（必须两边都对照的部分）

### §1 水面判定：`segmentWaterSample` 是逐 footprint 列采样后取 OR —— 一致

**旧** `roadweaver/.../pathfinding/PathSpanExtractor.java:1521-1557`：

```java
1521    /** Samples every rasterized road column, not just the center station. */
1522    private static BridgeWaterSample segmentWaterSample(ServerLevel level,
...
1530                                                        int[] wetColumnsOut) {
1531        if (segment == null) return new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
1532        List<BlockPos> footprint = segment.positions();
1533        if (footprint == null || footprint.isEmpty()) footprint = List.of(segment.middlePos());
1534        boolean found = false;
1535        int surface = Integer.MIN_VALUE;
1536        int bed = Integer.MAX_VALUE;
1537        int columns = 0;
1538        int wetColumns = 0;
1539        for (BlockPos position : footprint) {
1540            if (position == null) continue;
1541            columns++;
1542            BridgeWaterSample sample = bridgeWaterSample(
1543                    level, cache, terrain, accurate, samples, streams, position, minWaterDepth);
1544            if (!sample.water()) continue;
1545            wetColumns++;
1546            found = true;
1547            surface = Math.max(surface, sample.waterSurfaceY());
1548            bed = Math.min(bed, sample.bedY());
1549        }
1550        if (wetColumnsOut != null && wetColumnsOut.length >= 2) {
1551            wetColumnsOut[0] = columns;
1552            wetColumnsOut[1] = wetColumns;
1553        }
1554        return found
1555                ? new BridgeWaterSample(true, surface, bed == Integer.MAX_VALUE ? Integer.MIN_VALUE : bed)
1556                : new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
1557    }
```

**新** `roadweaver-next/.../pathfinding/PathSpanExtractor.java:685-721`：

```java
685    /** Samples every rasterized road column, not just the center station. */
686    static BridgeWaterSample segmentWaterSample(ServerLevel level,
...
694                                                        int[] wetColumnsOut) {
695        if (segment == null) return new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
696        List<BlockPos> footprint = segment.positions();
697        if (footprint == null || footprint.isEmpty()) footprint = List.of(segment.middlePos());
698        boolean found = false;
699        int surface = Integer.MIN_VALUE;
700        int bed = Integer.MAX_VALUE;
701        int columns = 0;
702        int wetColumns = 0;
703        for (BlockPos position : footprint) {
704            if (position == null) continue;
705            columns++;
706            BridgeWaterSample sample = bridgeWaterSample(
707                    level, cache, terrain, accurate, samples, streams, position, minWaterDepth);
708            if (!sample.water()) continue;
709            wetColumns++;
710            found = true;
711            surface = Math.max(surface, sample.waterSurfaceY());
712            bed = Math.min(bed, sample.bedY());
713        }
714        if (wetColumnsOut != null && wetColumnsOut.length >= 2) {
715            wetColumnsOut[0] = columns;
716            wetColumnsOut[1] = wetColumns;
717        }
718        return found
719                ? new BridgeWaterSample(true, surface, bed == Integer.MAX_VALUE ? Integer.MIN_VALUE : bed)
720                : new BridgeWaterSample(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
721    }
```

**判定**：`private static` → `static`（可见性），**其余逐字符相同**。对外部可观察行为而言：OR 语义（任一步 footprint 列湿 ⇒ 整站湿）完全保留；`wetColumnsOut[0]/[1]` 仍只喂给 `[ROAD-BRIDGE-MASK]` 诊断日志（旧 `logWaterMaskDiag:1512-1519` ↔ 新 `:676-683`），不参与判定。

### §2 桥/隧道阈值：常量值同、比较方向同

- 水深门槛（**比较方向关键**）：旧 `:1608-1609` / `:1622-1624` ↔ 新 `:772-773` / `:786-788`：

```java
// 旧 1608-1609（PathTerrainField 精确列分支） / 新 772-773
boolean water = sample.columnWater()
        && sample.waterDepth() >= Math.max(1, minWaterDepth);
// 旧 1622-1624 / 新 786-788（高度图兜底分支）
boolean bridgeWater = waterColumn && waterDepth >= Math.max(1, minWaterDepth);
```

  两版都是 `>=`，下界都是 `Math.max(1, …)`。`minWaterDepth` 来自 `effectiveBridgeMinDepth`（旧 `:89-96` ↔ 新 `:94-101`）：`BRIDGE_FIRST` 时恒为 1，其它模式取配置 `bridgeMinWaterDepth`。`routeBridgeMinWaterDepth()`（旧 `Road.java:1270-1275` ↔ 新 `:737-742`）是同一个表达式。

- 桥/浅滩宽度（旧 `:1648-1652` ↔ 新 `:812-816`）：

```java
static SpanType crossingTypeForWidth(int widthBlocks) {
    return widthBlocks <= ROAD_CROSSING_MAX_WIDTH_BLOCKS   // 12
            ? SpanType.ROAD_CROSSING
            : SpanType.BRIDGE;
}
```

  仍是 `<=`（12 格宽仍算 ROAD_CROSSING，第 13 格才变 BRIDGE）。

- 隧道：`TunnelEnvelopeCriterion.java` 两版**字节相同**（26961 字节），阈值组合（24 站窗口 / 10% 分位 / 种子突起 2 / 最小突起 4 / 目标覆土 7 / 密封核心 3 / 最长 256 站 / 站距 8.0 / 端口坡度 0.08）无一处变化；`refineTunnelSpans` 方法体逐字节相同（旧 `:231-393` ↔ 新 `:232-…`），`TUNNEL_RUN_MIN_LENGTH=3`（旧 `:39` ↔ 新 `:44`）与 `landTunnelEnabled` 接线（`boolean buried = landTunnelSwitch && newBuried;`）同位置同表达式。

### §3 长桥（弦长 ≥96 格）±5 格正弦横弯 —— 在

**旧** `roadweaver/.../pathlogic/bridge/BridgeSegment.java:52-64` ↔ **新** `roadweaver-next/.../pathlogic/bridge/BridgeSegment.java:53-65`（整份文件只差第 1 行文件头注释，故新行号 = 旧 +1）：

```java
52/53        double length = Math.sqrt((end.x - start.x) * (end.x - start.x)
53/54                + (end.z - start.z) * (end.z - start.z));
54/55        double offset = 0.0D;
55/56        if (length >= RoadConstants.BRIDGE_CURVE_MIN_LENGTH_BLOCKS) {      // 96
56/57            long hash = Double.doubleToLongBits(start.x) * 31L
57/58                    + Double.doubleToLongBits(start.z) * 17L
58/59                    + Double.doubleToLongBits(end.x) * 13L
59/60                    + Double.doubleToLongBits(end.z);
60/61            offset = (hash & 1L) == 0L
61/62                    ? RoadConstants.BRIDGE_CURVE_MAX_OFFSET_BLOCKS          // +5.0
62/63                    : -RoadConstants.BRIDGE_CURVE_MAX_OFFSET_BLOCKS;        // -5.0
63/64        }
64/65        var line = Line.withHorizontalBend(start, end, offset);
```

常量：`BRIDGE_CURVE_MIN_LENGTH_BLOCKS = 96`（旧 `RoadConstants.java:99` ↔ 新 `:98`）、`BRIDGE_CURVE_MAX_OFFSET_BLOCKS = 5.0D`（旧 `:101` ↔ 新 `:100`）。

正弦形状在 `util/Line.java`（两版 diff 仅文件头 1 行）：

```java
// 旧 46-57 / 新 47-58
public Vec3 pointAt(double t) {
    t = Math.max(0.0D, Math.min(1.0D, t));
    Vec3 point = chordPoint(t);
    if (!isCurved()) return point;
    double dx = end.x - start.x;
    double dz = end.z - start.z;
    double length = Math.sqrt(dx * dx + dz * dz);
    double nx = -dz / length;
    double nz = dx / length;
    double offset = horizontalBend * Math.sin(Math.PI * t);   // ±5 的正弦横弯
    return new Vec3(point.x + nx * offset, point.y, point.z + nz * offset);
}
```

落地（施工期真把站点映射到弯线上）：旧 `BridgeBuilder.java:74-78` ↔ 新 `:79-83`

```java
if (geometryLine != null && geometryLine.isCurved()) {
    seg = new RoadSegmentPlacement(
            geometryLine.mapRoadPosition(seg.middlePos()),
            geometryLine.mapRoadPositions(seg.positions()));
    ...
}
```

以及旧 `RoadConstructionVolumePlanner.java:252-257` ↔ 新 `:251-256`（桥墩中心、前/后站同样过 `mapRoadPosition`）。**该弯是 XZ 平面几何，只依赖首末站 XZ 与哈希，不读 `targetY`**，因此与「剖面切 DP」无关——用户拍板要保留的 ±5 正弦弯在新版**完整保留**。

### §4 桥面稳定岸线扩展（已知膨胀源）—— 完整保留

**旧** `BridgeDeckProfile.java:236-250` ↔ **新** `:237-251`（整文件 diff 仅文件头 1 行）：

```java
public static int[] findStableBankRange(int[] terrain,
                                        int waterStart,
                                        int waterEnd,
                                        int minimumBankY) {
    if (terrain == null || terrain.length == 0) return new int[]{0, 0};
    int start = Math.max(0, Math.min(waterStart, terrain.length - 1));
    int end = Math.max(start, Math.min(waterEnd, terrain.length - 1));
    int left = findStableLeft(terrain, start - 1, minimumBankY);
    int right = findStableRight(terrain, end + 1, minimumBankY);
    if (left < 0) left = start;
    if (right < 0) right = end;
    if (left > start) left = start;
    if (right < end) right = end;
    return new int[]{left, right};
}
```

**旧** `:252-281` ↔ **新** `:253-282`：

```java
private static int findStableLeft(int[] terrain, int from, int minimumBankY) {
    int lower = Math.max(0, from - STABLE_SEARCH_SEGMENTS + 1);          // 32
    for (int edge = Math.min(from, terrain.length - 1); edge >= lower; edge--) {
        int windowStart = edge - STABLE_WINDOW_SEGMENTS + 1;             // 4
        if (windowStart < 0) continue;
        if (stableWindow(terrain, windowStart, edge, minimumBankY)) return edge;
    }
    return -1;
}
...
private static boolean stableWindow(int[] terrain, int start, int end, int minimumBankY) {
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    for (int i = start; i <= end; i++) {
        min = Math.min(min, terrain[i]);
        max = Math.max(max, terrain[i]);
        if (i > start && Math.abs(terrain[i] - terrain[i - 1]) > 1) return false;
    }
    return max - min <= STABLE_WINDOW_VARIATION && median(terrain, start, end) >= minimumBankY;
}
```

常量 `STABLE_SEARCH_SEGMENTS = 32` / `STABLE_WINDOW_SEGMENTS = 4` / `STABLE_WINDOW_VARIATION = 2`：旧 `:11-13` ↔ 新 `:12-14`。膨胀链路也在：`appendWaterCrossingSpan` 用 `minimumBankY = waterSurfaceY + 1` 调 `findStableBankRange`（旧 `:1632-1646` ↔ 新 `:796-810`），随后 `appendNormalizedWaterSpan(spans, centers, stable[0], stable[1], type)` —— **即水区间先被向外扩到稳定岸线，再按扩张后的站数落 BRIDGE**（旧的真实案例 `width=74 water=266..337 stable=258..363` 的 72→106 站膨胀，在新版同一函数、同一搜索窗口下会得到同一结果）。`applyStableBankMask`（桥区掩码也按稳定岸线扩）旧 `:152-233` ↔ 新 `:153-234` 同样逐字节相同。

### §5 道路吸附/复用/头部裁剪 —— 常数、判据、顺序全同

- 常量：`SNAP_DIST2 = SNAP_THRESHOLD²`（12²=144）、`SPLIT_DIST2 = SPLIT_THRESHOLD²`（24²=576）旧 `RoadSnapService.java:101-102` ↔ 新 `:113-114`；底层 `RoadConstants`（旧 `:427-432` ↔ 新 `:426-431`）：`ROAD_SNAP_THRESHOLD=12`、`ROAD_SNAP_SPLIT_THRESHOLD=24`、`ROAD_SNAP_MIN_RUN_LENGTH=3`、`ROAD_SNAP_GRID_SHIFT=3`。
- 入口：`snapAllRoads` / `snapAroundConnection` / `snapAroundConnectionAsync` / `snapAllRoadsAsync` 旧 `:111-181` ↔ 新 `:123-193`，方法体逐字节相同。
- 主循环 `snapRoadList`：旧 `:182-317` ↔ 新 `:194-329`，唯一差异是异常类型前缀（旧 `StructureAvoidanceService.PlanningSnapshotUnavailableException` → 新顶层 `PlanningSnapshotUnavailableException`）。新异常类 `PlanningSnapshotUnavailableException.java:5-9` 与原嵌套类（旧 `StructureAvoidanceService.java:1943-1947`）同为 `extends RuntimeException` + `String message` 构造器，`catch` 语义不变。
- 头部裁剪：`HEAD_TRIM_TOLERANCE = 2`（旧 `:1058` ↔ 新 `JunctionTrimOps.java:142`）；`trimSecondaryHeadAtJunction` 旧 `:1073-1146` ↔ 新 `:157-230`，`nearPrimaryStation` 旧 `:1148-1157` ↔ 新 `:232-241`，`trimSecondaryTailAtJunction` 旧 `:1173-1254` ↔ 新 `:257-338`，三处方法体逐字节相同（判据：「切比雪夫 ≤2 且不是任何 span 端点」）。
- 复用扩展：`REUSE_EXTEND_TOLERANCE=2` / `REUSE_EXTEND_MIN_RUN=3` / `REUSE_EXTEND_MAX_HEIGHT_DELTA=1`（旧 `:364-368` ↔ 新 `JunctionConnector.java:103-107`）。`extendSecondaryReuseAtJunction`（旧 `:384-489` ↔ 新 `:123-228`）的「只从 lastReuse 之后找、连续 ≥3 站、站不能是已有 span 端点」逐字节相同；`matchesPrimaryCorridor`（旧 `:489-501` ↔ 新 `:229-240`）：

```java
for (int dx = -REUSE_EXTEND_TOLERANCE; dx <= REUSE_EXTEND_TOLERANCE; dx++) {
    for (int dz = -REUSE_EXTEND_TOLERANCE; dz <= REUSE_EXTEND_TOLERANCE; dz++) {
        Integer donorY = primaryHeights.get(xzKey(new BlockPos(pos.getX() + dx, 0, pos.getZ() + dz)));
        if (donorY != null && Math.abs(donorY - targetY) <= REUSE_EXTEND_MAX_HEIGHT_DELTA) {
            return true;
        }
    }
}
return false;
```

  以及 0.8.366(M2) 的「收集每一段走廊 run、不再在第一段后 break」（旧 `:418-453` ↔ 新 `:157-192`）同样逐字节相同；失败时 `RoadDataValidator.validateFinalRoad(extended)` 不通过就**原样返回 candidate**（旧 `:466-473` ↔ 新 `:205-212`）也一样。
- 吸附目标 `markSnapTargets`：旧 `:3099-3133` ↔ 新 `EndpointConnectOps.java:494-528`，唯一差异是 record 访问器（`nearest.index`→`nearest.index()`、`nearest.dist2`→`nearest.dist2()`）。旧版能写字段是因为 `NearestResult` 是**同一个顶层类的嵌套 record**（nestmate 可访问私有字段）；搬到另一个顶层类后必须改用访问器。**表达式、短路顺序、`SNAP_DIST2`/`SPLIT_DIST2` 的进出边界都没变。**

### §6 路径计算的失败处理：标记还在，失败后是「重绑路径 + 局部重试」而不是静默放弃

- `LAST_SEGMENT_FAILURE`（ThreadLocal）**仍在**：旧 `RoadPathCalculator.java:1583-1592` ↔ 新 `PathMetricsOps.java:203-215`；三处置值点旧 `:1609`（`curved_null`）/`:1658`（`linear_null`）/`:1672`（`still_intersects_protected_structure`）↔ 新 `:229`/`:277`/`:291`。`consumeSegmentFailureReason()` 旧 `:1585-1591` ↔ 新 `:205-211`，都是「取出后 `remove()`」。
- 消费点：旧 `RoadPathCalculator.java:631` ↔ 新 `RoadPathCalculator.java:641`，拼进 `segments_rejected:` 前缀后交给 `LAST_REBUILD_FAILURE`/日志。即：**失败原因只做观测，不参与判定**（原注释「纯观测」保留）。
- `LAST_REBUILD_FAILURE` 同族：旧 `:597-603` ↔ 新 `:607-613`，置值点旧 `:619`/`:630` ↔ 新 `:629`/`:640`。
- 失败后的动作（不是放弃）：`rebuildRoadDataFromProfileInternal` 返回 `null` → 调用方（`Road.generateRoad` / `Road.repairFinalHeightConflict`）在失败分支调 `RoadPlanningService.rebindGeneratedRouteForRetry(level, connection, rd, localRetryStep())` 并返回 `null`，由规划层按「重绑后的路径 + 局部修复精度」再生成。这条链路两版**逐字节相同**：
  - `rebindGeneratedRouteForRetry`：旧 `RoadPlanningService.java:245-264` ↔ 新 `:252-271`（20 行 `diff` 为空）；
  - 调用点：旧 `Road.java:298/315/421/476/574` ↔ 新 `:315/332/438/493/591`（数量、位置一一对应）；
  - `localRetryStep()`：旧 `Road.java:1625-1628` ↔ 新 `:767-770`（`RoutePrecisionRetryPolicy.localRepairBatchSteps(configured).get(0)`）。

### §7 跨度提取的接线：两处调用收成一处，实参不变

- 旧 `Road.java:161-162`（首次）与 `:243-244`（高度重路由后重算）都是
  `PathSpanExtractor.extractSpans(segments, level, cache, terrain, streams, routeBridgeMinWaterDepth())`；
- 新 `Road.java:177` / `:260` 都调 `extractPhysicalSpans(segments, level, cache, terrain, streams)`，其定义（新 `Road.java:684-689`）就是同一行调用，实参顺序与个数完全一致（含 `routeBridgeMinWaterDepth()`），**没有少传/多传/换默认值**；
- 旧 `spans = new ArrayList<>(spans); targetY = computeTargetY(...); spans = refineTunnelSpans(...)`（旧 `:181-182`、`:198-200`、`:254-257`）↔ 新 `settleProfile`（新 `:703-711`），三条语句顺序、实参相同（多类型 `SettledProfile` 只是打包返回值）；
- `Road.generateRoad` 全方法 diff 见附录 A 第 38 行说明；除命名与 1 行 `logSpecIntegrity` 外无逻辑差异。

---

## ★ 逻辑改变清单

### ★B-1（跨组；核查期间从「默认改变」变为「条件性改变」）：剖面求解器默认 LEGACY（=旧版），仅显式 `-Droadweaver.profileSolver=dp` 时改由 DP 求解

> 这一项不在 `PathSpanExtractor` / `RoadPathCalculator` / `RoadSnapService` 里，但它位于 B 组判定的**上游**：`PathSpanExtractor.refineTunnelSpans`、`FillBridgeExtender.refineFinalFillBridgeSpans`、`SpanConflictResolver.retainVerticallyRelevantCrossings` 全部以 `targetY` 为判据输入。C 组报告 `C-纵断面与坡度.md` 已对 DP 本体（★C1–★C14）做了完整清单，这里只列与 B 组结果相接的部分，避免重复。

**旧**（`roadweaver/.../pathlogic/core/Road.java:744-1135`，`computeTargetY` 末尾直接返回自己的结果）：

```java
// 旧：15 道 pass 全部算完后，直接把 smoothed 逐站填进 out 返回
List<Integer> out = new ArrayList<>(n);
for (int v : smoothed) out.add(v);
...
return out;
```

**新**（`roadweaver-next/.../pathlogic/core/TargetYComputation.java:452-464` + `:567-621`，旧流水线算完后被 DP 覆盖）：

```java
452        // ── 剖面求解器模式分派（S7 第 1 级）──────────────────────────────
453        // ★ 默认已是 DP；{@code -Droadweaver.profileSolver=legacy} 可显式回退。
...
463        smoothed = applyProfileSolver(smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
464                startBoundary, endBoundary, cfg, gradeLimits, connection);
...
567    private static int[] applyProfileSolver(...) {
573        if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) {
574            return smoothed;                       // LEGACY：原样返回，逐字节不变
575        }
...
601        var solved = ProfileDpSource.solve(terrainY, isCrossing,
602                maxCut, maxFill, maxStep, perTwo, constraints);
603        if (solved.isEmpty()) {                    // 不可行 ⇒ 回落旧流水线
604            LOGGER.warn("RoadWeaver profile solver=dp infeasible, falling back to legacy" ...);
609            return smoothed;
610        }
611        LOGGER.info("RoadWeaver profile solver=dp stations={} perTwo={} maxStep={} cut={} fill={} minCover={} ...");
615        return solved.get();
```

**两态记录（同一个文件在本次核查期间被改过一次，见文末「核查期间工作树变更」）**：

> 注：上面新代码块第 453 行的注释「★ 默认已是 DP」写于「默认 DP」时期；17:09 默认改回 LEGACY 后该注释未同步（`TargetYComputation.java` mtime 未变），现与实现相反——见「非逻辑但值得记录」#3。

- **态 A：核查开始时读到的实现（默认 DP）**

```java
// 17:00 左右工作树中的 ProfileSolverMode.parse（当前文件已不是这样）
public static ProfileSolverMode parse(String raw) {
    if (raw == null || raw.isBlank()) return DP;         // ← 未设置属性 = DP
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if ("dp".equals(v)) return DP;
    if ("legacy".equals(v) || "classic".equals(v)) return LEGACY;
    return DP;                                           // ← 写错也回落 DP
}
```

- **态 B：当前工作树（`ProfileSolverMode.java` mtime = 2026-11-13 17:09:20，默认 LEGACY）**

```java
81    public static ProfileSolverMode fromProperty() {
82        return parse(System.getProperty(PROPERTY));
83    }
84
85    /** 解析字符串（大小写不敏感）；未设或非法返回 {@link #LEGACY}（默认）。 */
86    public static ProfileSolverMode parse(String raw) {
87        if (raw == null || raw.isBlank()) return LEGACY;     // ← 未设置属性 = LEGACY
88        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
89        if ("dp".equals(v)) return DP;
90        if ("legacy".equals(v) || "classic".equals(v)) return LEGACY;
91        return LEGACY;                                       // ← 写错也回落 LEGACY
92    }
```

- 分派点 `TargetYComputation.applyProfileSolver` 本身**两态下都没有改**（该文件 mtime 未变）：`if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) { return smoothed; }`（`:573-575`）。所以在**态 B（当前默认）下，`applyProfileSolver` 直接返回旧流水线算好的 `smoothed`，与旧版逐字节相同**；DP 只在显式开关 + `slopeLimitEnabled` 为真时才介入（`:576-579`）。

**同样输入下结果会怎么不同**：

1. `terrainY`/`isCrossing`/`tunnelMask`/`pinnedMask` 相同、且**显式 `-Droadweaver.profileSolver=dp`** 时，新版返回 DP 的最优解（最小土方；2026-11-13 早些时候的 AB 记录为 57 次观测、土方 325,346 → 8,996），旧版返回 15 道 pass 的结果，两者逐站 Y 通常不同。**不设属性时（当前默认，态 B）`applyProfileSolver` 直接 `return smoothed`，剖面逐字节等同于旧版。**
2. 该 `targetY` 被 B 组以下位置当判据读：
   - `PathSpanExtractor.refineTunnelSpans`：`centerStationOverburden` / `maximumFootprintOverburden`（如旧 `:546-556`，`accurate.surfaceHeight(sample) - targetY.get(index)`）；
   - `FillBridgeExtender.refineFinalFillBridgeSpans`：新 `:207-212` `unsafeFill[index] = targetY.get(index) - lowestFootprintSurface[index] >= dryBridgeDepth;`、新 `:264-267` 的 `bankHeightDelta = Math.abs(targetY.get(bridgeStart) - targetY.get(bridgeEnd))`；
   - `SpanConflictResolver.retainVerticallyRelevantCrossings`（首轮用 `provisionalTargetY`）。
   于是**最终落盘的 TUNNEL / BRIDGE / 干洼地填方桥的 span 集合、桥面绝对 Y 会随桩号不同而不同**。
3. **不受影响的部分**（B 组自己的判定）：水掩码只依赖水位/河床/湖泊/Streams，`findStableBankRange` 只依赖 `waterSurfaceY + 1` 和地形列，所以「哪一段水面变成 BRIDGE/ROAD_CROSSING、XZ 范围、稳定岸线扩张量」在同一地形/水位输入下与旧版相同；±5 正弦弯只依赖 XZ 与哈希，也不受影响。

**玩家能观察到的现象（仅显式开启 DP 时）**：同一 seed、同一配置 + `-Droadweaver.profileSolver=dp` 下，会出现「同一条河上桥仍是桥，但桥面高度/桥头引道长度与旧版不同」「本该成隧道的山段因为 DP 把路面压低/抬高而多成或少成隧道」「干洼地填方桥的起止点变化」。这与 C 组结论（DP 路径上「桥面=水位+净空」的平甲板、浅滩抬升、相邻桥连续、峡谷稳岸、跨海隧道剖面等不再生效）是同一根因在不同组的投影。

**这段历史在核查期间被推翻过一次**：17:09:20 工作树把默认值从 DP 改回 LEGACY（当前 `ProfileSolverMode.java:86-92`），并把理由写进 `:42-77`——「DP 拿不到水位，桥面从平甲板变斜弦」「prefer-cut 契约被翻转」「约 10 项仅 LEGACY 生效的旧补丁」，与 C 组结论完全一致。因此**当前工作树默认路径逐字节等同于旧版**；只有显式 `-Droadweaver.profileSolver=dp` 才复现上面这些差异。**这不是本次重构的漏改，也不该被算作默认行为改变。**

---

## 非逻辑但值得记录（不是逻辑改变，但会影响可维护性/误导后来人）

1. **`RoadCrossingOps.getRandomWidth` 是返回 0 的空壳，且无调用者**（新 `RoadCrossingOps.java:89-91`）。旧版 `Road.getRandomWidth`（旧 `Road.java:633-635`）同样 `return 0`，所以**不是回归**；但类头注释写「宽度为什么带随机量 / 用站点坐标做确定性抖色」（新 `:80-83`）与实际实现相反，且 `Road.generateRoad` 实际调的是 `Road` 里那个同名私有方法（新 `Road.java:119`）。属**文档/死代码陷阱**。
2. **`CulvertPlanner` 新增了一个未使用的 import**（新 `CulvertPlanner.java:15` `import …postprocess.JunctionTrimOps;`，全文件无引用）。Java 允许，无行为影响。
3. **`ProfileSolverMode` 的文档与实现一度相反，现已修正；但 `TargetYComputation:453` 的注释仍是旧说法**：`// ★ 默认已是 DP；{@code -Droadweaver.profileSolver=legacy} 可显式回退。` 这行在 17:09 把默认改回 LEGACY 之后**没有同步**（该文件 mtime 未变），现在与实现（默认 LEGACY）相反。属**注释/文档不一致**，不影响行为，但会误导后来人——建议后续改成「默认 LEGACY；`-Droadweaver.profileSolver=dp` 显式开启」。
4. **大量 `private` → 包可见/`public`**：`PathSpanExtractor`（如 `segmentWaterSample`/`bridgeWaterSample`/`appendWaterCrossingSpan`）、`RoadSnapService` 的常量与工具方法、`AccurateHeightSampler` 的缓存与网络列方法。这是拆类必需的放宽，不改行为，但**扩大了同包耦合面**；今后任何同包类都能直接调这些内部判定。
5. **`SamplerStatics` ↔ `AccurateHeightSampler` 双向静态导入**（新 `SamplerStatics.java:33` 与 `AccurateHeightSampler.java:32`）。Java 静态导入不触发类初始化循环，我核过两侧 `<clinit>` 互不引用对方非编译期常量，**当前无初始化顺序问题**；但这是编译期才能最终确认的形态。

---

## 可观测性退步

**没有发现 B 组原有日志被删或降级**（方法体逐字节相同，日志字符串必然保留）。逐条核对：

| 日志 | 旧位置 | 新位置 | 状态 |
|---|---|---|---|
| `[ROAD-BRIDGE-MASK] waterRange=… stations=… footprintCols=…` | `PathSpanExtractor.java:1512-1519` | `:676-683` | 保留（同样每段水一行） |
| `crossing plan type=… widthBlocks=… waterRange=… stableRange=…` | `:1643-1645` | `:807-809` | 保留 |
| `route stage=span_extraction connection=… spans=…` | `Road.java:168-169` | `Road.java:183-184` | 保留 |
| `kept secondary reuse as-is: extended reuse failed validation reason=…` | `RoadSnapService.java:467-472` | `JunctionConnector.java:206-211` | 保留（文案一字未改，含 `runs/first` 细节） |
| `profile height step` / `water-span boundary height jump` | `Road.java:1090-1133`（C 组 #11 已核） | `TargetYComputation.java:467-510` | 保留 |

**新增（不是退步，是噪音/信息量变化）**：

- `logSpecIntegrity(rd, rawStart, rawEnd)` 插在 `Road.generateRoad` 返回前（新 `Road.java:641`，定义 `:795-811`）：每路一条 `road spec OK … stations/sections/uncovered`，违例时 `road spec VIOLATION …`（前 3 次 + 每 50 次打印）。只读自检，异常被吞（`:806-810`），**不改变返回值**。
- `profile solver=dp stations=… perTwo=…` 每路一条 INFO（`TargetYComputation.java:611-614`），不可行/异常时各一条 WARN（`:604-605`、`:618`）——**只在显式 `-Droadweaver.profileSolver=dp` 时才会打印**（默认 LEGACY 在 `:573-575` 直接返回，不产生任何此类日志）。默认路径无新增日志。

**文档层面退步**：`RoadCrossingOps` 类头声称「判据与阈值一字未改」但其 `getRandomWidth` 只是空壳（旧版也是空壳，表述易误导）。`ProfileSolverMode` 的文档与实现一度相反，已在核查期间修正（见下节）。

---

## 无法判定的地方（如实列，不猜）

1. **「相同输入 ⇒ 输出逐位相同」无法在本轮证明**：本轮只做源码静态比对（未编译、未运行、未开游戏）。我证明的是「方法体在去注释/空白后一致（237/249）、仅机械改写（10/249）、记录迁移（2/249）」；运行时因素（并发完成顺序、Streams 会话成功/失败分支的触发时机、缓存命中与否）不在本次可证范围内。缓存类差异按代码注释「只决定复用、不决定采样值」，但那是注释而非我的实测。
2. **DP 求解器在真实地形上的差异幅度**：我没有复跑 AB。C 组报告给出 57 次观测（土方 325,346 → 8,996）与「DP 不可行会回落 legacy」的代码路径（`TargetYComputation.java:603-610`）；**注意当前默认已回到 LEGACY，这些差异只在显式 `-Droadweaver.profileSolver=dp` 时才会出现**；哪些具体路线会变、变多少，需真机日志（`profile solver=dp` / `infeasible`）才能定量。
3. **`PlanningSnapshot.boxesByChunk` 与 `boxes` 的一致性依赖构造点**：我复核了 `PlanningSnapshot.forRoute`（新 `:118-129` 按 `minX/maxX±roadRadius` 逐块入桶）与 `withAdditionalRoadRadius`（`:692-708`）两处；A 组报告称仓内 5 处 `new PlanningSnapshot(...)` 均满足。但 `PlanningSnapshot` 是 record、规范构造器 public，**若将来有人手工拼出不一致快照，`isVillageEndpoint` 会静默漏判**（不报错、不告警）。属可维护性风险，不是现存缺陷，我无法用静态分析穷尽未来调用点。

---

## 核查期间工作树变更（必须交代的时序）

本组核查期间（约 17:00 开始，17:09 结束），`roadweaver-next` 工作树发生了**一处**与本组相关的变化：

| 文件 | mtime | 变化 |
|---|---|---|
| `pathlogic/profile/ProfileSolverMode.java` | **2026-11-13 17:09:20** | 默认剖面求解器 **DP → LEGACY**（`parse` 在未设置/非法值时返回 `LEGACY`，仅 `-Droadweaver.profileSolver=dp` 才用 DP）；同时在 javadoc 里记录了切回理由 |

证据与说明：

1. 该文件的新旧两态我都读到过：**态 A**（默认 DP）的 `parse` 实现与 **态 B**（默认 LEGACY，当前）的 `parse` 实现都原样贴在 ★B-1；`TargetYComputation.java` 的 mtime 未变，`applyProfileSolver` 的分派逻辑两态一致。
2. 我按硬约束**没有执行任何 git 命令**，因此**无法判断该改动是否已提交到 `7bc6023`**。如果审计基线是「git 提交内容」而不是「工作树」，请以提交为准复核这一处；如果基线是「当前工作树」，则判定为：**默认路径与旧版等价，DP 为显式可选项（条件性改变）**。
3. 本组其余全部结论（237 处逐字节等价 / 10 处机械改写 / 2 处记录迁移）不受这处改动影响——它们都不在 `ProfileSolverMode` 的判定链上。

---

## 附录 A：10 处「机械改写」全表（方法体其余部分逐字节相同）

| # | 方法 | 旧位置 | 新位置 | 差异内容 |
|---|---|---|---|---|
| 1 | `calculateAStarRoadPathDetailed/8` | `RoadPathCalculator.java:955` | `PathMetricsOps.java:433` | 1 处 `StructureAvoidanceService.ensurePlanningSnapshotForPathAsync` → 静态导入裸名 |
| 2 | `finalizePath/7` | `:1050` | `PathFinalizeOps.java:110` | 6 处 `StructureAvoidanceService.*` → 静态导入裸名 |
| 3 | `repairStructureIntersectionsWithCorridorRefresh/5` | `:1187` | `PathFinalizeOps.java:247` | `AccurateGradeRepairPlanner.*` → `StructureRepair.*`（+2 处静态导入） |
| 4 | `repairLakeIntersectionsWithCorridorRefresh/6` | `:1314` | `PathFinalizeOps.java:374` | `AccurateGradeRepairPlanner.repairLakeIntersections` → `LakeRiverRepair.repairLakeIntersections` |
| 5 | `repairRiverIntersectionsWithCorridorRefresh/6` | `:1363` | `PathFinalizeOps.java:423` | 同上（river） |
| 6 | `repairRiverRouteSegments/6` | `:1400` | `PathFinalizeOps.java:460` | 同上（river） |
| 7 | `refreshPlanningSnapshot/3` | `:1430` | `PathFinalizeOps.java:490` | 1 处静态导入 |
| 8 | `buildSegments/7` | `:1597` | `PathMetricsOps.java:217` | 6 处静态导入（`intersectsPreparedRoute`/`protectedGapSpans` 等） |
| 9 | `snapRoadList/2` | `RoadSnapService.java:182` | `RoadSnapService.java:194` | `catch (StructureAvoidanceService.PlanningSnapshotUnavailableException)` → `catch (PlanningSnapshotUnavailableException)` |
| 10 | `markSnapTargets/6` | `:3099` | `EndpointConnectOps.java:494` | `nearest.index`/`nearest.dist2` → `nearest.index()`/`nearest.dist2()`（record 跨顶层类后必须用访问器） |

## 附录 B：迁移落点（旧方法 → 新类，全部逐字节等价，抽样列位置）

> 约定：本表括号里的旧行号、以及「新位置」的行号，都是**方法体左花括号所在行**（与 `grep -n` 能对上）；完整区间见正文「逐项对照」表。

| 旧方法（旧行号） | 新位置 |
|---|---|
| `extractSpans/6`（116） | `PathSpanExtractor.java:121` |
| `collectBridgeSpans/11`（1448） | `PathSpanExtractor.java:612` |
| `convertLongOceanBridgesToUnderseaTunnels/6`（578） | `UnderseaTunnelPlanner.java:59` |
| `refineFinalFillBridgeSpans/5`（798） | `FillBridgeExtender.java:58` |
| `resolvePhysicalSpanOverlaps/2`（1040） | `SpanConflictResolver.java:60` |
| `appendTunnelWithSurfaceMouths/11`（1313） | `TunnelFootprintSampler.java:61` |
| `connectSecondaryToPrimary/2`（332） | `JunctionConnector.java:71` |
| `tryConnect/4`（982） | `JunctionTrimOps.java:66` |
| `tryAbsorbEndpointCorridor/4`（1352） | `EndpointConnectOps.java:71` |
| `tryReuseBetweenTwoApproaches/4`（1537） | `NetworkReuseMerger.java:50` |
| `findSharedEndpointApproach/5`（2478） | `EndpointApproachConnector.java:68` |
| `buildGrid/2`（3229） | `RoadSnapService.java:606`（`SegmentGrid` 在独立文件） |
| `NearestResult`/`ConnectionOption`（3574/3580） | `SnapInternals.java:51`/`:54` |

## 附录 C：本组使用的核对命令（可复现、只读）

```text
# 1) 方法体抽取 + 归一化比对（去注释/空白后按「方法名+参个数」在旧仓与新仓全树匹配）
#    旧三文件 249 个类级成员 → 237 逐字节等价 / 10 机械改写 / 2 记录迁移
# 2) 新仓反向扫描「新方法体」：pathfinding/ 仅 A* 与工厂类；pathlogic/bridge/ 0 个新方法体
# 3) 整文件字节比对：
cmp -s .../pathlogic/bridge/BridgeDeckProfile.java ...   # 仅头注释 1 行
cmp -s .../pathlogic/bridge/BridgeSegment.java ...       # 仅头注释 1 行
cmp -s .../util/Line.java ...                            # 仅头注释 1 行
cmp -s .../pathfinding/TunnelEnvelopeCriterion.java ...  # 完全相同
cmp -s .../pathlogic/structures/PlanningSnapshotUnavailableException.java ...  # 无旧文件（旧为嵌套类）
```