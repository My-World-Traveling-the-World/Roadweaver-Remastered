# D 组：开局生成与规划调度链 —— 方法体逻辑等价性核查

- 旧仓库（oracle）：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`
- 新仓库（重构版）：`D:\云游四海-Mod源码\roadweaver-next` @ `7bc6023`
- 方式：**只读**。未改/建/删任何 `.java`，未 git 操作，未编译。本文档是唯一写入物。
- 判定口径：**每个判定点都必须新旧两边同时给位置**；下表「等价」= 方法体归一化（trim + 抹掉 `public/private/protected` + 删空行）后逐行相同；「重限定」= 方法体唯一差异是 `OldClass.name(...)` → 静态导入 `name(...)`，且被搬走的目标方法本体另行复核过。

---

## 结论摘要

- **逻辑等价 23 项 / 逻辑改变 0 项 / 无法判定 0 项**
  （「项」按§逐项对照表的行计；判定点共 23 个）
- 另有 **2 项默认关闭的新增能力**（`PIPELINE` 规划器、`planPrimaryEdges` 多一个 `level` 形参）。它们不改默认路径的任何结果，单列在§逻辑改变清单里，不计入「改变」。
- **调用链顺序：一致。** 开局主流水线 `blockUntilDoneInternal` 新旧都是 **612 行**（旧 `InitialGenManager.java:434-1045` ↔ 新 `InitialPlanWaiter.java:102-713`），去掉缩进后 `diff` **只有 7 处差异，全部是符号重限定**（见 §调用链顺序对照）。阶段顺序、阶段间闸门、批次屏障、10ms 自旋、5s 心跳、线程数抬升策略逐字未改。
- **精度梯级：一致，且那个坑原样还在。** `RoutePrecisionRetryPolicy` 新旧逐字相同（只多一行文件头注释）。`initialBatchSteps()` 仍恒返回**一档**，step=2 局部修复波次仍由 `tiers.size() == 1` 触发（旧 `InitialGenManager.java:674` ↔ 新 `InitialPlanWaiter.java:342`，那块 163 行代码体**逐字相同**）。**退化风险仍在**：一旦有人让 `initialBatchSteps()` 返回多档，整个 step=2 局部修复波次会被**静默**跳过且无日志。
- **多波次规划：一致。** 两层波次（① 一次 `planRectAsync` 内的自适应扩边轮次；② 会话内多次 `planRectAsync`）机制逐字未改；`finishAdaptivePlan` 仍是**所有轮次跑完只发布一次**（`AsyncPlanningOps` 侧逐字相同）。`network merge timing` 新旧都只有 **1 个打点**（`ExistingRoadJunctionService`），且它在开局链上只被调用 1 次。
- **PIPELINE 默认状态：关。** 配置项 `planningAlgorithm`；字段初值 `PlanningAlgorithm.RNG`（`roadweaver-next/.../config/sub/PlanningConfig.java:59`），配置元数据默认值同为 `"RNG"`（`config/meta/PlanningKeys.java:67`）。`PIPELINE` 只是枚举新增的第 7 项，**没有任何代码强制选它**。
- **最高风险：** 默认路径 0 处行为改变，但**「档位列表长度」与「局部修复波次是否执行」之间的隐式耦合在新版被拆到了两个文件里**（`RoutePrecisionRetryPolicy.java` ↔ `InitialPlanWaiter.java:342`），既无断言也无日志，比旧版（同一文件内相隔 160 行）更难被发现。

> **口径修正（对主控交办说明的一处订正）**：交办里写的是「如果 `tiers.size()==1`，会静默关掉整个 step=2 局部修复波次」。方向是**反的** —— 代码是
> `if (!remaining.isEmpty() && tiers.size() == 1) { …局部修复波次… }`（旧 `InitialGenManager.java:674`），
> 即 **`tiers.size()==1` 时波次才执行**。当前 `initialBatchSteps()` 恒返回 1 档，所以波次每次都跑。
> 真正的隐患是反过来的条件：**`tiers.size() != 1` 时波次被静默跳过**。这个隐患新旧都存在、且新版没修。

---

## 调用链顺序对照

### 旧版（`InitialGenManager.blockUntilDoneInternal`，`InitialGenManager.java:434-1045`）

1. `L435` 闸门：`!active || level==null || 非主世界` → 直接返回
2. `L439-444` 取连接表；空表 → `active=false` + `finish()` 收尾返回
3. `L445` `filterPlanned(list)` 只留 `PLANNED`
4. `L449-450` `StructureAvoidanceService.filterUnknownEndpointInsideVillage(level, roadTasks)`
5. `L451-454` 有被滤连接 → `markFilteredConnections(...)`（落 FILTERED 终态 + 地图补丁）
6. `L460-463` `total.set(...)` + `logPersistedReuse(...)`
7. `L465-469` `enterStage(ROAD_GENERATION,"generating_roads")` → `updateRetryStates(0,0)` → `updateConnections(...)` → `recordCheckpoint(...)`
8. `L482-484` `beginRoutePhase(ORIGINAL, n, "waiting_for_initial_routes")`
9. `L491` **`awaitGreatWallPrewarm(level)`**（长城预热闸门，软失败）
10. `L492` **`prewarmInitialRiverPlates(level, roadTasks)`** → `L495` `fireRiversPrewarmed()`
11. `L496` `DetailedRoadGenerationStats.reset()`
12. `L507-509` `configuredStep` / `startupBounds` / **`tiers = RoutePrecisionRetryPolicy.initialBatchSteps(configuredStep)`**
13. `L513-651` **档位循环**（`tierIndex < tiers.size() && !remaining.isEmpty()`）：
    - `L536-542` `tierIndex==0`：`await(refineInitialBatchAsync(level, startupBounds, remaining, step))` ← **批次屏障**
    - `L546-548` `tierIndex==0`：`preplanUnknownEndpointHeights(level, remaining)`
    - `L555-556` `submitAndCollectAtStep(level, remaining, step, tierIndex>0, tierIndex==0?prewarmedRoutePositions:null)`
    - `L563-640` 逐连接分类（protected-gap 三条分支：配置直留 / 缺口太小直留 / 移交局部修复）+ 逐条 `recordCheckpoint`
14. `L653-667` `settleRoutePhase(ORIGINAL,…)` + `initial route wave summary` 日志
15. `L674-836` **`if (!remaining.isEmpty() && tiers.size() == 1)` → step=2 局部修复波次（163 行）**：
    - `L685-691` 先探 `hasBuildingConflict` → 建 `buildingConflictRoutes`
    - `L705-706` `await(refineInitialRoutesAsync(level, remaining, step, 100))`（splice 阶段）
    - `L719-732` 用 `hasLocalRepairReboundPath` 预筛，剔掉必然失败的路，再 `submitAndCollectAtStep(..., localOnly=true)`
    - `L753-808` 结果分类；带缺口时用 `leavesFewerGaps` 在「第一波路」与「修复后路」之间择优
16. `L839-993` `TOPOLOGY_CANDIDATES_ENABLED` 拓扑候选块 → **`false`（`L113` 常量），整块死代码**
17. `L995-1008` 仍未解决的连接：有 protected 兜底就用兜底，否则记 FAILED
18. `L1009-1026` 终态计数 `done/failed` → `persistInitialRoadBatch(level, …)`
    - 内部：`ExistingRoadJunctionService.connectPlannedBatchDetailed`（**`network merge timing`，全程 1 次**）→ `processFinalRoad` 并发最终校验 → `RoadShardStorage.addRoads`
19. `L1025` `batchUpdateConnectionStatus(...)`
20. `L1028` `ensureInitialConnectionsTerminal(...)`
21. `L1034` **`DeferredInitialRoadConstructionService.enqueue(level, initialConstructionChunks)`**
22. `L1041` `flushAndFinish(level)`

### 新版（`InitialPlanWaiter.blockUntilDoneInternal`，`InitialPlanWaiter.java:102-713`）

**与旧版一一对应，编号相同，无一处顺序变化：**

1. `L103` 闸门（同）
2. `L107-112` 取表 / 空表收尾（同）
3. `L113` `filterPlanned`（同）
4. `L117-118` `filterUnknownEndpointInsideVillage`（同）
5. `L119-122` `markFilteredConnections`（同）
6. `L128-131` `total.set` + `logPersistedReuse`（同）
7. `L133-137` `enterStage` / `updateRetryStates` / `updateConnections` / `recordCheckpoint`（同）
8. `L150-152` `beginRoutePhase(ORIGINAL,…)`（同）
9. `L159` **`awaitGreatWallPrewarm`**（同）
10. `L160` **`prewarmInitialRiverPlates`** → `L163` `fireRiversPrewarmed()`（同）
11. `L164` `DetailedRoadGenerationStats.reset()`（同）
12. `L175-177` `configuredStep` / `startupBounds`（改由 `ConflictWindowOps.initialSamplingBounds`）/ `tiers`（同）
13. `L181-319` **档位循环**（同）：
    - `L205-206` `await(RouteRefinementOps.refineInitialBatchAsync(...))` ← 批次屏障（同）
    - `L215` `preplanUnknownEndpointHeights`（同）
    - `L223-224` `submitAndCollectAtStep(..., tierIndex>0, …)`（同）
    - `L231-308` 逐连接分类 + 逐条 `recordCheckpoint`（同）
14. `L321-335` `settleRoutePhase(ORIGINAL,…)` + 波次汇总（同）
15. `L342-504` **`if (!remaining.isEmpty() && tiers.size() == 1)` → step=2 局部修复波次（同，163 行）**
16. `L507-661` 拓扑候选块 → `TOPOLOGY_CANDIDATES_ENABLED=false`（新 `InitialGenManager.java:123`），死代码（同）
17. `L663-676` 未解决连接兜底（同）
18. `L677-694` 终态计数 → `persistInitialRoadBatch` → `batchUpdateConnectionStatus`（同）
19. `L696` `ensureInitialConnectionsTerminal`（同）
20. `L702` **`DeferredInitialRoadConstructionService.enqueue`**（同）
21. `L709` `flushAndFinish`（同）

### 差异

**只有 7 处，全部是符号换名，逻辑与顺序 0 变化**（`diff` 原文，旧行号 ↔ 新行号）：

| # | 旧 | 新 | 性质 |
|---|---|---|---|
| 1 | `InitialGenManager.java:434` `private static void blockUntilDoneInternal` | `InitialPlanWaiter.java:102` `public static void …` | 可见性（跨类调用需要） |
| 2 | `L449` `StructureAvoidanceService.EndpointFilterOutcome` | `L117` `EndpointFilterOutcome` | 类型搬到 `pathlogic/structures/EndpointFilterOutcome.java:82`，record 分量逐字相同 |
| 3 | `L508` `RoadPlanningService.initialSamplingBounds` | `L176` `ConflictWindowOps.initialSamplingBounds` | 方法搬家，本体逐字相同（见 E3） |
| 4 | `L537` `RoadPlanningService.refineInitialBatchAsync` | `L205` `RouteRefinementOps.refineInitialBatchAsync` | 方法搬家，本体逐字相同（见 E4） |
| 5 | `L569` `StructureAvoidanceService.hasOnlyEndpointProtectedGaps` | `L237` `hasOnlyEndpointProtectedGaps` | 静态导入自 `ProtectedGapEvaluator.java:209`，本体逐字相同（见 E5） |
| 6 | `L705` `RoadPlanningService.refineInitialRoutesAsync` | `L373` `RouteRefinementOps.refineInitialRoutesAsync` | 方法搬家，本体**逐字相同**（`diff` 无差异） |
| 7 | `L901` `RoadPlanningService.refineInitialRoutesAsync` | `L569` `RouteRefinementOps.refineInitialRoutesAsync` | 同一目标的第二处调用点（候选波，死代码内） |

> 交办说明/既有草稿里若声称「5 处差异」，那是漏了 #3、#4 这两处 `RoadPlanningService.* → ConflictWindowOps/RouteRefinementOps.*`（不是 `StructureAvoidanceService` 那一类）。

---

## 逐项对照

| # | 逻辑点 | 旧版位置 | 新版位置 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | 开局主流水线（整块 612 行） | `InitialGenManager.java:434-1045` | `InitialPlanWaiter.java:102-713` | 等价 | 612 行 vs 612 行，归一化 `diff` 仅 7 处重限定（见上表），阶段顺序逐行相同 |
| 2 | 精度梯级策略（`initialBatchSteps`/`localRepairBatchSteps`/`candidateBatchSteps`/`fullRouteSteps`/`finestStep`/`allowsLocalDetour`/`shouldStopIdenticalConflictStreak`/`structureDetourBlocks`/`lakeDetourBlocks`/两个常量） | `generation/RoutePrecisionRetryPolicy.java:15-100` | `generation/RoutePrecisionRetryPolicy.java:1-101` | 等价 | 整文件 `diff` 唯一差异是新增文件头注释；`initialBatchSteps` 仍 = `fullRouteSteps` = `List.of(clamp(step))`（恒 1 档） |
| 3 | step=2 局部修复波次（含 `tiers.size()==1` 闸门） | `InitialGenManager.java:674-836` | `InitialPlanWaiter.java:342-504` | 等价 | 闸门条件与 163 行波次体逐字相同（由 #1 的整块 diff 覆盖）；`localRepairBatchSteps` 仍返回 `[max(2, ASTAR_STEP_MIN)]` |
| 4 | 批次提交/采集/高度预取/失败记账 | `InitialGenManager.java:1220-1558` | `InitialRouteSubmitOps.java:92-390`；`recordInitialRouteFailure` 在 `InitialRoutePersistOps.java:93-116` | 等价 | `diff` 仅 4 处可见性 + 1 处 `hasOnlyEndpointProtectedGaps` 重限定 + 两段 javadoc 被删；10ms `parkNanos`、5s 心跳、`while` 采集顺序、`retryPhase` 判定逐字相同 |
| 5 | 持久化 + 最终逐路处理 | `InitialGenManager.java:1560-1679`（`persistInitialRoadBatch(Internal)`）、`1728-1946`（`processFinalRoad` + 两个 timed 校验）、`1696-1726`（`protectedGapRuns/Blocks`、`leavesFewerGaps`） | `InitialRoutePersistOps.java:118-237`、`273-489`、`243-271` | 等价 | 三段 `diff` 仅可见性；`markRoadCorridorPrepared` 改静态导入（目标本体见 E6） |
| 6 | 连接状态归一 / 终态兜底 / 收尾 / 中止 / 计数 | `InitialGenManager.java:1950-2073`、`2084-2139`、`2141-2166` | `InitialPlanLifecycleOps.java:94-217`、`225-279`、`281-306` | 等价 | 第三段**完全零差异**；前两段仅可见性 + `compactPreparedStartCacheAfterInitialGeneration` 改静态导入（E7） |
| 7 | 生命周期辅助（`ownerKey`/`removeGeneratedRoad`/`addSupplementalConnection`/`candidateConnectsToExistingNetwork`/`roadsGeometricallyConnected`/`await`） | `InitialGenManager.java:1152-1207`、`2078-2082` | `InitialPlanLifecycleOps.java:308-362`、`219-223` | 等价 | 56 行 vs 56 行，`diff` 仅可见性 |
| 8 | 长城闸门 + 河流预热 + 断点/降级/入口判定 | `InitialGenManager.java:1101-1138`、`146-154`、`165-348` | `InitialGenManager.java:498-535`、`156-164`、`175-348` | 等价 | `awaitGreatWallPrewarm`、`prewarmInitialRiverPlates`、`resetForServerStop`、`begin`、`shouldRunInitialGeneration`、`resolveResumePoint`、`parseStage`、`downgradeStaleGenerating`、`recordCheckpoint`、`countByStatus`、`roadStationCount`、`logPersistedReuse`、`filterPlanned`、`markFilteredConnections` 全部只差可见性 |
| 9 | 开局生成入口（`generateInitialRoad`/`generateInitialRoadAtStep`/`hasLocalRepairReboundPath`/`InitialTaskResult`/`InitialGenerationResult`/`generateInitialTask`） | `RoadGenerationService.java:786-927` | `RoadGenerationService.java:339-480` | 等价 | 142 行 vs 142 行，**零差异**；`localOnly` 时才走 `tryAttachAndRegenerate` 这条 0.9.085 兜底也原样保留 |
| 10 | 运行期 tick 调度（手绘优先、暂停豁免、预算、并发提交） | `RoadGenerationService.java:202-360` | `RoadTaskExecutor.java:81-239` | 等价 | 159 行 vs 159 行，**零差异**（含 `while (cnt.get() < limit)`、`tryAcquireRuntimeSlot` 失败即 break、提交失败回滚 `PLANNED` 并重抛） |
| 11 | 运行时槽位 / 取消 / 计数回收 | `RoadGenerationService.java:362-402` | `RoadGenerationService.java:207-247` | 等价 | 仅 `finishRunningTask` 可见性；CAS 循环、`Math.min(limit, value+1)` 逐字相同 |
| 12 | 服务器起停状态复位 | `RoadGenerationService.java:131-198` | `RoadGenerationService.java:136-203` | 等价 | 仅 3 处重限定：`StructureAvoidanceService.clearAll()`→`clearAll()`（E8，本体逐字相同）、`RoadPlanningService.resetAll()`→`PlanningBoundsOps.resetAll()`（E9，本体逐字相同） |
| 13 | 逐任务生成与失败降级（`tryAttachAndRegenerate`/`generateTaskWithFallback`） | `RoadGenerationService.java:442-611` | `RoadTaskExecutor.java:305-473` | 等价 | 仅可见性 + 3 处 `discardPlanningSnapshot` 重限定 + 1 处 `hasOnlyEndpointProtectedGaps` 重限定；`TOPOLOGY_CANDIDATES_ENABLED` 仍 false、玩家手绘路线两次短路（`L350-356`、`L398-403`）逐字相同 |
| 14 | `generateTaskInternal`（单步，含玩家路线覆盖） | `RoadGenerationService.java:612-753` | `RoadTaskExecutor.java:545-596`（主）+ `241-304`（`applyPlayerRouteOverrides`） | 等价 | 主线 50 行零差异；`applyPlayerRouteOverrides` 仅可见性 |
| 15 | `safeGenerate`（异步包装 + 主线程回写） | `RoadGenerationService.java:931-993` | `RoadTaskExecutor.java:480-542` | 等价 | 63 行 vs 63 行，仅可见性 |
| 16 | 队列刷新 / 就近轮询 / 暂停豁免 | `RoadGenerationService.java:1228-1270`、`1274-1311`、`1340-1377` | `RoadQueueOps.java:76-118`、`120-157`、`158-185` | 等价 | 三段仅可见性；`isPlayerRouteSafe`（旧 `1313-1338` ↔ 新 `RoadGenerationService.java:720-745`）仅可见性 |
| 17 | 规划服务 100 个方法（含内嵌类方法） | `planning/RoadPlanningService.java` 全文 2200 行 | `RoadPlanningService` 994 + `AsyncPlanningOps` 462 + `RouteRefinementOps` 379 + `PlanningBoundsOps` 392 + `ConflictWindowOps` 317 | 等价 | 方法级匹配：**100 个方法，90 个 ratio=1.0，10 个仅符号重限定，0 个丢失**（明细见 E10） |
| 18 | 多波次自适应规划（扩边轮次 + 只发布一次） | `RoadPlanningService.java:1641-1670`（`planRectRounds`）、`1671-1705`（`finishAdaptivePlan`）、`1797-1870+`（`AdaptivePlanState`） | `AsyncPlanningOps.java:358-…`、`…`、`RoadPlanningService.java:906+` | 等价 | `planRectRounds` ratio=0.9808 且差异全在 `PrepareTiming` 类型限定；`planRectAsync` ratio=0.9512 同因；`AdaptiveCandidateBounds` 整文件零差异；`AdaptivePlanState.canExpand()=margin<capMargin`、`absorb/rewind/expand` 逐字相同 |
| 19 | 区块计划编译（`compile`/`buildSpanMask`/`normalizeForPhysicalOwnership`/`markPhysicalReuse`/`verifySurrenderedReuse`/`externalTunnelJunctionIndices`/`roadTouchesChunk` + 40 个辅助） | `worldgen/road/RoadChunkPlanCompiler.java:1-1048` | `RoadChunkPlanCompiler.java` 543 + `RoadPhysicalReuseNormalizer.java` 444 + `RoadChunkIntersection.java` 161 | 等价 | 方法级：**47 个方法，40 个 ratio=1.0，7 个仅 `RoadChunkIntersection.x`/`RoadPhysicalReuseNormalizer.x` 重限定，0 个丢失**；5 个 `PHYSICAL_REUSE_*` 常量值完全相同（4/5/8/1/8） |
| 20 | 路网合并（开局链上只调 1 次的那个） | `pathlogic/junction/ExistingRoadJunctionService.java:100`（`connectPlannedBatchDetailed`） | `pathlogic/junction/ExistingRoadJunctionService.java:110` | 等价 | 该文件 42 个方法：38 个 ratio=1.0，4 个仅 `RoadSnapService.x`→`JunctionConnector.x`/`JunctionTrimOps.x` 重限定；4 个目标本体 ratio 全 = 1.0（E11）。`network merge timing` 新旧都 1 个打点 |
| 21 | 6 个既有规划器 | `planning/impl/{KNN,Delaunay,RNG,MST,SnapBranch,AlphaModel}Planner.java` | 同名文件 | 等价 | 6 个文件逐个 `diff`，唯一差异是各自新增一行文件头注释 |
| 22 | 开局施工两个服务 + 接线 | `InitialRoadConstructionService.java` 127 行 / `DeferredInitialRoadConstructionService.java` 305 行 | 128 行 / 307 行 | 等价 | 两个文件 `diff` 只有文件头注释 + 一个静态导入；**同步版新旧都零调用点**（仅在 `PathFeature.java` 注释里被提到），延迟版仍由开局链 `enqueue`（旧 `InitialGenManager.java:1034` ↔ 新 `InitialPlanWaiter.java:702`） |
| 23 | `isVillageEndpoint`（被开局链 `preplanUnknownEndpointHeights` 直接调用） | `StructureAvoidanceService.java:3251-3265` | `EndpointOwnerResolver.java:148-177` | 等价（算法性替换，已证） | 判据 `horizontalDistanceSquared(box, endpoint) <= 16²` 与 `ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS`（旧 `:143` / 新 `:122`，**都是 16**）一字未改；候选集为超集（见 E12 的三点证明） |

### 补充证据

**E1 主流水线 612 行 vs 612 行**：`sed -n '434,1045p'` 旧 / `sed -n '102,713p'` 新，各自 `trim()` 去行尾后 `diff` 输出 **7 个 hunk**（即上表）。

**E2 `RoutePrecisionRetryPolicy` 恒 1 档**：
```java
// 旧/新 完全相同
public static List<Integer> initialBatchSteps(int configuredStep) {
    return fullRouteSteps(configuredStep);           // → List.of(clamp(step))
}
```
所以 `tiers.size() == 1` 恒真 → 局部修复波次每次必跑。旧版 `InitialGenManager.java:519-522` 的注释已经自己写明这件事：
```java
// initialBatchSteps() always yields exactly one tier, so
// the old tierIndex > 0 local-repair branch here was
// unreachable and has been removed; local repair runs as
// its own explicit wave below.
```
新版把这 5 行注释原样搬到 `InitialPlanWaiter.java:187-190`。

**E3 `initialSamplingBounds`**：旧 `RoadPlanningService.java:1269-1282` ↔ 新 `ConflictWindowOps.java:260-273`，14 行 vs 14 行 `diff` **零差异**（`radiusChunks`、`>>4`、`*16`、`boundsIncludingConnections` 全同）。

**E4 `refineInitialBatchAsync`**：旧 `RoadPlanningService.java:675-726` ↔ 新 `RouteRefinementOps.java:327-378`，`diff` 仅 `private static` → `public static`。`LEGACY_DIRECT` 直接返回 `completedFuture`、`epoch` 校验、`filterSuppressedPaths`、`SharedEndpointPathReuse`、`PlannedPathCache.register` 顺序全同。

**E5 `hasOnlyEndpointProtectedGaps` + `endpointOnlyProtectedGapPattern`**：旧 `StructureAvoidanceService.java:1680-1692` / `1699-1720` ↔ 新 `ProtectedGapEvaluator.java:209-221` / `227-248`，两段 `diff` 均只差可见性（`private` → 包私有）。`ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS` 引用同一常量名。

**E6 `markRoadCorridorPrepared`**：旧 `StructureAvoidanceService.java:340-360` ↔ 新 `WorldgenAvoidance.java:279-299`，仅 `markPrepared` 可见性。

**E7 `compactPreparedStartCacheAfterInitialGeneration`**：旧 `StructureAvoidanceService.java:2939-2958` ↔ 新 `PreparedStartResolver.java:653-672`，逐字相同（含 `IN_FLIGHT_*` 无关，此方法只做 pin→bounded 合并 + trim + 两条日志）。

**E8 `clearAll`**：旧 `StructureAvoidanceService.java:282-309` ↔ 新 `WorldgenAvoidance.java:221-248`，28 行 vs 28 行**零差异**（含对 4 个 `IN_FLIGHT_*` 的 `cancel(true)`、`NATIVE_DIRECT_EXECUTOR.shutdownNow()`、两个 ThreadLocal `remove()`）。新版 `RoadGenerationService.onServerStarting:147`/`onServerStopping:194` 通过 `import static …WorldgenAvoidance.*` 调到它。

**E9 `resetAll`**：旧 `RoadPlanningService.java:2194-2199` ↔ 新 `PlanningBoundsOps.java:386-391`，逐字相同（清 `ROAD_BACKFILL_PLAN_TILES`/`PLANNING_TILES`/`PROTECTED_GAP_HINTS` + `AutomaticPlanningSamplingActivities.clearAll()`）。这 3 个集合在新版仍定义在 `RoadPlanningService.java:69/70/76`，被静态导入引用，**是同一个对象**。

**E10 `RoadPlanningService` 方法级明细**：抽取器按「4~8 空格缩进 + 修饰符 + 返回类型 + 名字 + `(`」定位声明并花括号配平，覆盖内嵌类方法（`AdaptivePlanState`、`AsyncPlanningTiming` 等）。结果 `methods=100 identical=90 differ=10 missing=0`。10 处差异全部为：

- `StructureAvoidanceService.PrepareTiming` → `PrepareTiming`（5 处：`logAsyncPlanning`、`planRectAsync`、`planRectRound`×2、`planRectRounds`）
- `StructureAvoidanceService.preparePlanningSnapshotsAsync` / `…ForPathsAsync` / `ensurePlanningSnapshotForCircleAsync` → 静态导入（4 处；目标本体 220 行 vs 220 行仅 3 处可见性，含私有 `preparePlanningSnapshotsForPathsAsync`）
- `planPrimaryEdges` 多一个 `ServerLevel level` 形参 + `NetworkPlannerFactory.create(algorithm, level)`（1 处，见 §逻辑改变清单）
- `preplanUnknownEndpointHeights` 里 `Road.isUnknownStructureEndpoint` → `RoadSpanProfileOps.isUnknownStructureEndpoint`（目标本体旧 `Road.java:1325-1355` ↔ 新 `RoadSpanProfileOps.java:128-158`，仅 `isVillageEndpoint`/`isKnownStructureEndpoint` 两处静态导入 + 后一方法可见性）

**E11 被搬走的 4 个 junction 方法**：`connectSecondaryToPrimary`（旧 `RoadSnapService.java:332` ↔ 新 `JunctionConnector.java:71`）、`createSecondaryReuseAtCorridor`（`517` ↔ `256`）、`connectSecondaryToPrimaryOnce`（`601` ↔ `340`）、`lowerPrimaryTunnelGradeAtJunction`（`667` ↔ `406`）、`networkReuseStationCount`（`1329` ↔ `JunctionTrimOps.java:413`）—— **ratio 全部 = 1.0000**。

**E12 `isVillageEndpoint` 索引替换的等价性三点证明**：

1. **索引返回超集**：`StructureBoxIndex.fromChunkBuckets(...)` 走 `new StructureBoxIndex(DEFAULT_CELL_SIZE, buckets, null, entries)`，即 `boxes = null`（`StructureBoxIndex.java:118-126`）；`query` 在 `boxes == null` 时**跳过桶内包围盒粗筛、整桶返回**（`StructureBoxIndex.java:148-158`），注释明写「仍然是超集，语义不变」。
2. **桶表覆盖盒 footprint**：`boxesByChunk` 的所有非空来源只有三处 —— `PlanningSnapshot.java:134`（`forRoute` 工厂，按 `box.minX-roadRadius … box.maxX+roadRadius` 逐区块铺开）、`:708`（`withAdditionalRoadRadius`，同模式再扩）、`:684`（`withForcedVerticalOwners`，**原样复用**上一份 map）。`merge(...)` 在框未变时直接返回 `previous`（保住 map），框变了就回到 `forRoute`（`PlanningSnapshot.java:187-188`）。**不存在「只按盒起点所在区块建桶」的路径。**
3. **查询矩形覆盖判据半径**：查询是 `x±16, z±16` 的矩形 → 转成区块后覆盖端点在的最多 3×3 个桶；一个满足「距端点 ≤16」的盒，其 footprint 必落在这些桶里 → 一定被取到。判据本身（距离平方 ≤ 16² 且 `isVillageStructure(...)`）一个字没改。

结论：`isVillageEndpoint` 的返回值与旧版全表扫描**逐位相同**，只是少了无关盒的遍历。新版另加 `StructureBoxIndexTest` 并注册进回归套件。

---

## ★ 逻辑改变清单

**默认配置下（`planningAlgorithm=RNG`）：0 条。** 同样的世界种子 + 同样的配置，新旧两版走的是同一串调用、同样的判据、同样的顺序、同样的并发策略。

下面是**全部**被确认为「新旧不一样」的东西，逐条给出为何不影响默认路径：

### 改变 1 —— `planPrimaryEdges` / `NetworkPlannerFactory` 多一个 `ServerLevel` 形参

旧（`RoadPlanningService.java:525-527`）：
```java
private static List<StructureConnection> planPrimaryEdges(List<BlockPos> points, ModConfig cfg0) {
    NetworkPlanner planner = NetworkPlannerFactory.create(cfg0.planning().planningAlgorithm());
```

新（`RoadPlanningService.java:542-545`）：
```java
static List<StructureConnection> planPrimaryEdges(ServerLevel level, List<BlockPos> points,
                                                  ModConfig cfg0) {
    NetworkPlanner planner = NetworkPlannerFactory.create(
            cfg0.planning().planningAlgorithm(), level);
```

旧工厂只有 6 个 case；新工厂（`NetworkPlannerFactory.java:33-43`）多一个：
```java
case PIPELINE -> new PipelinePlanner(level);
```
并保留 1 参重载 `create(algorithm)` → `create(algorithm, null)`。

**可观察差异：默认路径 0。** 其余 6 种规划器全部忽略 `level`（构造函数无参，见 #21 六个文件逐字相同），`KNNPlanner` 分支的额外参数、`maxEdge` 上限、`planner.plan(points, maxEdge)` 调用顺序都没变。**只有把 `planningAlgorithm` 改成 `PIPELINE` 时才会不同**，而那个值旧版不存在。

### 改变 2 —— 新增 `PlanningAlgorithm.PIPELINE`（枚举第 7 项）

旧（`config/sub/PlanningConfig.java:15`）：
```java
public enum PlanningAlgorithm { KNN, DELAUNAY, RNG, MST, SNAP_BRANCH, ALPHA_MODEL }
```
新（`PlanningConfig.java:15-…`）：同 6 项顺序不变，**末尾追加** `PIPELINE`。

**可观察差异：默认路径 0**，两条依据：
1. 默认值没变：字段初值 `PlanningAlgorithm.RNG`（旧 `PlanningConfig.java:31` ↔ 新 `PlanningConfig.java:59`）；元数据默认值同为 `"RNG"`（新 `config/meta/PlanningKeys.java:67`）。
2. 新项加在**末尾**，既有 6 项的 ordinal 不变；`values()` 只在测试 `tuning/TopologyHarness.java:170` 里被遍历，且该 harness **不在** `RoadJUnitRegressionSuite` 注册表内。

**但有一处文档不同步（不是行为改变，是可见性瑕疵）**：`PlanningKeys.java:71` 的选项说明仍写着
`"可选：KNN / DELAUNAY / RNG / MST / SNAP_BRANCH / ALPHA_MODEL"` —— 没提 `PIPELINE`。若配置界面按 `values()` 渲染下拉框，用户会看到一个**没有说明文字**的选项。

### 改变 3 —— `isVillageEndpoint` 从「全表扫描」改为「区块桶索引查询」（**已证等价，不是行为改变**）

旧（`StructureAvoidanceService.java:3251-3265`）遍历 `snapshot.boxes()` 全部框；新（`EndpointOwnerResolver.java:148-177`）改为
`StructureBoxIndex.fromChunkBuckets(snapshot.boxesByChunk()).query(x±reach, z±reach)`。

**判定：等价（依据见 E12 的三点证明）：候选集是超集、桶表按盒 footprint 建、判据与 `16` 这个阈值一字未改。**
这属于「同输入同输出、只是少遍历无关盒」的纯索引替换，**输出逐位相同**。它落在 `pathlogic/structures`（相邻组地盘），因为被开局链的 `preplanUnknownEndpointHeights` 直接调用，所以记在这里备案。

### 非行为性差异（列出以免被误当成改变）

| 类别 | 数量 | 说明 |
|---|---|---|
| `private static` → 包私有/`public static` | 约 60 处 | 拆类后跨类调用的必然结果，无行为影响 |
| 类/嵌套类型搬家 | `EndpointFilterOutcome`、`PrepareTiming`、`LocalSamplingWindow`、`LocalRouteSlice`、`AdaptiveRound`、`Frame`、`PlannedRegionResult`、`TerrainPlannedRegionResult`、`CachedPlayerList`、`RunningTask`、`GenResult`、`BatchGenerationResult`、`PersistedBatch`、`FinalRoadWork`、`ConnectionChange`、`GenerationOutcome`、`GenerationAttempt`、`InitialTaskResult`、`InitialGenerationResult`、`AdaptivePlanState` | 记录/枚举分量逐个比对过，无字段增减 |
| 方法前 javadoc 被删 | 若干（`prefetchInitialRoadHeights`、`collectInitialRouteBatch`、`candidateConnectsToExistingNetwork`、`logCompletionSummary`、`protectedGapRuns/Blocks`、`leavesFewerGaps`、`timedFinal*Validation`） | 纯注释；`candidateConnectsToExistingNetwork` 等领域注释在新文件里还留着孤行（`InitialGenManager.java:549`），是拆类留痕 |
| 新增文件头注释 | 每个新类一行 `/* 文件职责：… */` | 纯注释 |

---

## 无法判定的地方

**无。** 本组 23 个判定点全部给出了「等价」的证据；唯一一度存疑的 `isVillageEndpoint` 已在 E12 里补齐三点证明（超集 + 桶表口径 + 查询覆盖），转为「等价」。

**明确不归本组、未做判定的 2 处**（避免越界下结论，主控若需要请派给对应组）：

| 范围 | 内容 | 归属 |
|---|---|---|
| `pathlogic/grade/AccurateGradeRepairPlanner.java`（旧 3189 行 → 新拆 8 类） | 精确纵断面修复 | C 组（纵断面与坡度） |
| `-Droadweaver.solverMode` / `BAND` 寻路开关 | 走线算法切换 | B 组（路径跨度与吸附） |

**另需主控自行确认的 1 处文档同步项**（不是行为改变）：`config/meta/PlanningKeys.java:71` 的选项说明未列 `PIPELINE`，与新增的枚举第 7 项不同步。

---

## 给主控的两条可执行结论

1. **D 组可以判定为「默认路径行为等价」。** 开局链的调用顺序、闸门位置、批次屏障、失败兜底、并发槽位、波次机制均无一处可观察变化；行数缩水全部由拆类解释（旧 4 文件 6896 行 → 新对应 15 文件 8085 行，多出的全是 import/类头/静态导入）。同种子同配置下不该出现「路变少 / 变慢 / 不合并 / 不重试」。

2. **建议给 `tiers.size() == 1` 这个耦合加一条断言或日志**（新旧都缺，不是本次重构引入的回归，但重构把它从「同文件相隔 160 行」变成「跨两个文件」）：
   - 位置：`InitialPlanWaiter.java:342`
   - 最小改法：在那行 `if` 前加
     `if (!remaining.isEmpty() && tiers.size() != 1) LOGGER.warn("RoadWeaver local repair wave skipped: tiers={} …", tiers.size());`
   - 或让 `RoutePrecisionRetryPolicy.initialBatchSteps` 直接 `throw` 多于 1 档，把这个契约钉死在它自己文件里。
   - 现在唯一的护栏是测试 `RoutePrecisionRetryPolicyTest.usesOnlyConfiguredStep`（断言 `fullRouteSteps` 返回单元素列表），它**没有**断言 `initialBatchSteps`，也**没有**把「波次依赖档数」写进任何测试。