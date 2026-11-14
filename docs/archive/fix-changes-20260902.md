# RoadWeaver 修复改动清单（audit-report.md 落地）

- 日期：2026-09-02
- 范围：仅源码修改，未改动游戏存档/运行配置，未部署。
- 对照文档：`audit-report.md`（P1-1/2/3 与 P2 项）

## 逐文件改动点

### 1. `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/Road.java`
- **[Fix 1 / P1-1] NPE 修复**（原约 293–301 行）：Streams 河道碰撞晋升分支中，先在 `waterCollision = null` 之前计算 `SpanType promotedType = crossingTypeAtCollision(crossed, waterCollision.segmentIndex())`，日志改用该局部变量。原先 `waterCollision.segmentIndex()` 在置 null 之后求值，必然 NPE 并把已成功的晋升整条路线打回失败。
- **[Fix 7 / P2-4] 端点锁高锚点**（原约 664–706 行，两处）：`startCross/endCross` 由裁剪后的 `centers.get(0)/centers.get(last)` 改为 `connection.from()/connection.to()` 本身。同一条结构端点的每条来路现在共享同一采样坐标 → 同一精确地表高度；坡地村庄入口不再可能锁出 1–3 格台阶。`crossingPreciseHeight` 本身已有 null/异常回退，端点为 null 时安全退回 profile 地形。

### 2. `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/pathfinding/RoadPathCalculator.java`
- **[Fix 2 / P2-10#4] 删除死分支**：
  - `repairStructureIntersectionsWithCorridorRefresh` 中 `if (!RoutePrecisionRetryPolicy.allowsLocalDetour(...))` 整块删除（该条件恒为 false——`effectiveAStarStep()` 已被 sanitize 到 ≥ `ASTAR_STEP_MIN=1`，而 `allowsLocalDetour` 即 `step >= 1`，"defer at coarse step" 永不可达）。
  - `repairLakeIntersectionsWithCorridorRefresh` 中同样的恒假 defer 分支删除。
  - 两处均保留原注释说明删除原因。行为不变（分支本就不可达），局部绕行在所有步长下照常执行。
- `RoutePrecisionRetryPolicy.allowsLocalDetour` 本体保留未动（`RoutePrecisionRetryPolicyTest` 仍在覆盖其语义），仅生产代码不再调用。

### 3. `common/src/main/java/net/shiroha233/roadweaver/config/sub/PlanningConfig.java`
- **[Fix 3 / P1-3] 新增配置项 `dryDepressionBridgeDepth`**：
  - 字段默认 6，注释"A dry depression deeper than this many blocks is bridged instead of filled."
  - `sanitize()` 钳制 `[2,16]`；`snapshot()` 复制；新增 getter/setter（`dryDepressionBridgeDepth()` / `setDryDepressionBridgeDepth(int)`）。
  - 该字段随既有 `planningFingerprintPayload` 自动纳入路径指纹（一次性使旧计划路径缓存失效，属预期配置演进）。

### 4. `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/RoadGradePlanner.java`（整体重构，公共方法签名不变）
- **[Fix 4 / P2-12] 限值快照化**：新增 `public record SurfaceGradeLimits(plainCut, plainFill, mountainCut, mountainFill, tunnelCoreOverburden, tunnelMinRun)`：
  - `capture()` 一次性读取 `ConfigService.get().planning()` + `.terrainProfile()`，应用 `TerrainProfileMode` 调整（切方：TUNNEL_FIRST/LIGHT_CUT → `min(3, cut)`；填方：BRIDGE_FIRST/LIGHT_CUT → `min(1, fill)`；AUTO/BALANCED 原值——与原 switch 语义逐分支一致），失败时回退原常量（MAX_SURFACE_CUT=5 / MAX_SURFACE_FILL=2 / 7 / 3）。
  - `cut(boolean mountain)` / `fill(boolean mountain)` 访问器。
- 公共 API（`constrainProfile`、`constrainProfileToFootprintSupport`、`stabilizeSurfaceProfile`、`applyProactiveTunnelProfile`、`enforceContinuousSlope(PreferCut)`、`robustWindowHeight`、`robustFootprintSupport`）签名不变；内部改为：
  - `constrainProfile` 公共入口捕获一次快照后委托私有重载；`stabilizeSurfaceProfile` 整个 6 轮循环共享同一快照；
  - `findTunnelCorridors` / `isTunnelCore` / `findLowerAnchorLeft/Right` / `aboveAnchorChord` 改为接收快照值（原每站位/每候选调用 `ConfigService.get()`）；
  - 删除原私有 `surfaceCutLimit()/surfaceFillLimit()/tunnelCoreOverburden()/tunnelMinRun()`。
- **AUTO/BALANCED 行为无回归**：默认配置（AUTO、plain 5/2、mountain 3/1）下所有钳制结果与重构前逐值相同。

### 5. `common/src/main/java/net/shiroha233/roadweaver/pathfinding/PathSpanExtractor.java`
- **[Fix 3 / P1-3] 干洼地阈值独立化**：
  - 新增 `dryDepressionBridgeDepth()`（读 `planning().dryDepressionBridgeDepth()`，钳制 [2,16]，回退常量 6）；
  - `refineFinalFillBridgeSpans` 的 `unsafeFill` 判定由 `>= ordinaryFillLimit()`（原 `Math.max(5,...)` 强制下限，覆盖用户 `surfaceFillMaxBlocks`）改为 `>= dryBridgeDepth`（方法入口捕获一次）。强制下限覆写已删除。
- **[Fix 3] 切/填方来源统一到 RoadGradePlanner**：
  - `fullWidthSurfaceConnected`（隧道洞口判定）改用 `RoadGradePlanner.SurfaceGradeLimits`（TerrainProfileConfig 山地/平原分档 + 档位调整），与 RoadGradePlanner 的 profile 钳制共用同一来源；
  - `refineTunnelSpans` 每站位的 `mountainOverburden()`（ConfigService 调用）改为 pass 入口一次 `limits.tunnelCoreOverburden()`；
  - 删除 `ordinaryCutLimit()/ordinaryFillLimit()/mountainOverburden()` 及残留死常量 `TUNNEL_MIN_OVERBURDEN`、`MAX_ORDINARY_CUT_DEPTH`、`MAX_ORDINARY_FILL_DEPTH`、`UNDERSEA_TUNNEL_SELECTION_PROBABILITY`（后者早被 `oceanUnderseaChance` 配置取代）。
  - `appendTunnelWithSurfaceMouths` 增加快照参数并下传（内部私有签名，无外部调用者）。
- **行为说明**：默认配置下干洼地成桥阈值由"强制 5"变为"可配置默认 6"，浅 5 格洼地将按普通填方处理——此为修复项要求的行为变更（配置从 [0,4] 死区变为 [2,16] 全程生效）。

### 6. `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RawPathSearch.java`
- **[Fix 5a] `hasUnbuildableCoarseTransition`**：硬编码 `>= 7` 改为方法入口捕获 `max(2, min(32, planning().mountainOverburdenBlocks()))`（与 `RoadGradePlanner` 隧道核判定同源，回退 7），循环内不再读配置。
- **[Fix 5b] 删除重复等高线搜索**：`wantHeightAware` 分支里第二次完全相同参数的 `MountainSwitchbackPlanner.replaceIfSafer` 整块删除，复用第一次结果（`modeRoute`）；`coarseHeightAwareEnabled` 开关改为直接守护其原本意图守护的 `CoarseHeightAwarePlanner.find` 块（默认 true，默认行为不变；该配置项不再悬空）。
- **[Fix 5c] 桥水深同源**：`applyCoarseTerrainModes` 的 crossings 掩码由 `RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH` 改为与 `PathSpanExtractor.extractSpans` 一致的 `effectiveBridgeMinDepth`（`BRIDGE_FIRST → 1`，否则 `ConfigService.get().bridge().minWaterDepth()`，`max(1,...)` 下限）。
- 顺带：删除 `applyCoarseTerrainModes` 未使用的 `allowReroute` 参数及其 3 处调用实参（P2-10#1，私有方法）。

### 7. `common/src/main/java/net/shiroha233/roadweaver/planning/RoadPlanningService.java` + `generation/InitialGenManager.java`
- **[Fix 6 / P2-3] `hasBuildingConflict` 增加 `int step` 参数**：内部查询改用 `config.pathfindingCost().withAStarStep(max(1, step))`，使指纹（含 `effectiveAStarStep`）与局部修复波次的重绑定步长一致；javadoc 说明步长纪律。
- `InitialGenManager` 局部修复波次调用处（原 308–313 行）传入 `RoutePrecisionRetryPolicy.localRepairBatchSteps(configuredStep).get(0)`（= 2，与 `rebindGeneratedRouteForRetry` 注册步长相同）。修复前该查询用配置原始步长（默认 8）→ 指纹恒 miss → `buildingConflictRoutes` 漏检压建筑路线。

### 8. `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/MountainSwitchbackPlanner.java`
- **[Fix 8 / P2-8] 状态预算配置化**：粗规划等高线搜索 `findContourRoute(..., 260_000, 1_500_000_000L)` 改为 `coarseStateLimit()`（读 `planning().heightAwareStateLimit()`，钳制 [50k,1M]，回退 260_000）与 `coarseTimeBudgetNanos()`（`max(1.5s, stateLimit × 6ns)`，默认 260k ≈ 1.56s，与原值一致）。

### 9. `common/src/main/java/net/shiroha233/roadweaver/planning/path/PlannedPathCache.java`
- **[Fix 9 / P2-11] `plannedReuseSpans` 精确匹配**：segment 索引改为双表——`exactIndices`（`BlockPos.asLong()`，含 Y）优先命中，`flatIndices`（原 x/z 键，改名 `flatKey`）仅作回退。下穿/立交场景两个不同 Y 的 segment 共享 x/z 时不再命中错误 NETWORK_REUSE 区段；旧数据 Y 不对齐时回退保持原行为。

### 10. `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/CoarseHeightAwarePlanner.java`
- **[Fix 10 / P2-12] 每邻居扩展的配置读取与分配消除**：`find` 入口一次 `tunnelVerticalOffsets()`（读 `planning().tunnelDepthBlocks()`，钳制 ≥4，回退 12，生成 `{0,-2,-4,...}` 偏移数组）；扩展循环改为索引遍历偏移数组，目标/起点列仍单候选。删除 `candidateHeights`（原每邻居调 synchronized `ConfigService.get()` 并分配 ≤33 长度数组）。候选 Y 序列与原先逐值相同（level 0 = 地表，向下步长 2，`COARSE_MIN_BUILD_Y=-64` 下限）。

---

## 验证结果

### 编译
- `gradlew :common:compileJava :fabric:compileJava` → **BUILD SUCCESSFUL**（仅原有 deprecation 提示，无错误无新警告）。
- `:common:compileTestJava` 一并成功（测试代码对改动 API 的引用完好；`findTunnelCorridors`/`applyCoarseTerrainModes` 等内部签名的变化无测试依赖破坏）。

### 回归（按要求执行，未跑 `:common:test`）
`gradlew :common:roadJUnitRegressionSuite :common:roadWholeFlowSimulation :common:tunnelJunctionGradeRegression :common:roadTopologyWaterSimulation :common:structureAvoidanceRegression --rerun-tasks` → **BUILD SUCCESSFUL**，逐项：

| 回归任务 | 结果 |
|---|---|
| `:common:roadJUnitRegressionSuite` | **PASS**（tests=13） |
| `:common:roadWholeFlowSimulation` | **PASS**（RoadWholeFlowSimulationTest: PASS） |
| `:common:tunnelJunctionGradeRegression` | **PASS**（TunnelJunctionGradeSimulationTest: PASS） |
| `:common:roadTopologyWaterSimulation` | **PASS**（RoadTopologyWaterSimulationTest: PASS） |
| `:common:structureAvoidanceRegression` | **PASS**（StructureAvoidanceServiceTest: PASS） |

### 行为回归说明（刻意的行为变更，其余逐值保持）
1. 干洼地成桥阈值：强制 5 → 可配置默认 6（Fix 3 要求；`surfaceFillMaxBlocks` 的 [0,4] 配置死区消除）。
2. `coarseHeightAwareEnabled=false` 时现在会同时跳过 `CoarseHeightAwarePlanner.find`（原先该开关只守护已删除的冗余重跑块）；默认 true，默认行为不变。
3. `hasBuildingConflict` 在局部修复波次内的查询步长 8→2，修复的是漏检（返回值只会在"确实压建筑"时由 false 变 true）。
4. `PlanningConfig` 新增字段使路径指纹一次性轮换（旧 planned_paths 缓存按预期失效重建一次）。

## 遗留（本次未做，见 audit-report.md）
- P1-2（共享池嵌套等待 + 交互期缩池 1）、P2-7（in-flight join 无超时）、P2-5（Streams unknown 保守判水）、P2-6（RoleExecutorService.shutdown 全池语义）等并发项未在本次清单内，建议下一批处理。

---

## 追加修复（本机完成，已复跑回归通过）

- **P1-2 交互期线程下限**：`RuntimeLoadPolicy.recommendedRoadWorkerThreads()` 在玩家移动/交互时不再返回 1，改为至少 2（上限取 normalCap 与 4 的较小值），避免道路任务阻塞等待嵌套 planning 子任务时只有一个 worker 卡死。
- **P2-5 Streams 会话失败**：`PathSpanExtractor.bridgeWaterSample` 在 `streams.isFailed()` 时不再把整条路当成巨型桥，回退到地形水掩码；只有单列 `isUnknown` 仍保守判水。
- **P2-6 单角色 shutdown 语义**：`RoleExecutorService.shutdown()/shutdownNow()` 改为 no-op，只有 `onServerStopping()` 生命周期钩子允许全池关闭。
- **界面与配置**：新增 `dryDepressionBridgeDepth`（默认 6）到 `路网规划` 分类，补中英文说明，并写入客户端 `roadweaver.json`。

复跑归因结果（本机）：
- `:common:compileJava` / `:fabric:compileJava` → BUILD SUCCESSFUL
- `:common:roadJUnitRegressionSuite` → PASS
- `:common:roadWholeFlowSimulation` → PASS
- `:common:tunnelJunctionGradeRegression` → PASS
- `:common:roadTopologyWaterSimulation` → PASS
- `:common:structureAvoidanceRegression` → PASS

---

# 追加批次：P2-7 / P2-13 / 地形档位语义 / 死代码 / 并发测试（2026-09-02）

状态：**全部完成，编译与 5 项回归通过**（基线先跑：改动前 `roadJUnitRegressionSuite` PASS tests=13；改动后 PASS tests=29，新增 16 条断言）。未触碰已通过回归的路线/桥/隧道决策逻辑本身；地形档位按任务 3 的"UI 合并 + 语义写清"方案处理（见下）。

## 改动 1：P2-7 无超时 in-flight join → 限时等待 + 超时接管

- **`config/sub/PlanningConfig.java`**：新增 `samplingInFlightTimeoutMs`（默认 3000，sanitize [100,30000]），含 sanitize/snapshot/getter/setter。
- **`pathfinding/terrain/region/CoarseTerrainTileCache.java`**：
  - in-flight 等待从 `existing.join()`（无超时）改为 `awaitExisting()` 内 `get(timeoutMs, MS)`；
  - 超时后本线程 `IN_FLIGHT.remove(key, existing)` **接管成为 owner** 并自己跑加载；若接管竞态失败（他人抢先或 owner 已完成）则本次请求降级为 miss（返回 null），绝不再无限阻塞；owner 异常完成仍降级为 miss（与原 join 抛异常转 null 契约一致）；
  - 中断恢复中断标记并降级；同线程递归请求同一 tile 维持**直接抛 IllegalStateException**（不自旋，原有守卫保留并写明注释）；
  - 抽出包私有 `coordinateInFlight(key, Supplier<CoarseTerrainTile>)` 供并发回归在无 Minecraft 环境驱动；`loadOrSample` 改为自取世代号；加载完成后补一道 `ensureCurrentGeneration` 后置校验（clearAll 换代语义不变：世代号失效、旧代任务直接 CancellationException 取消、内存/in-flight 整体替换）。
- **`pathfinding/cache/AccurateHeightSampler.java`**：
  - `joinWaitingTiles(waiting, coalescer)`（原 `joinWaitingTiles(waiting)`）与 `joinWaiting`（改实例方法，用 `inFlight`）都改为 `get(timeoutMs, MS)`；超时后 `coalescer/inFlight.remove(tileKey, future)` **释放停滞 owner 的占位**，外层 claim 循环随后本线程接管重采（`samplePositionsInternal` 的 while 重分组、`sampleChunks` 的 while 循环、`sampleWholeGridWithTileLease` 递归 `sampleGridInternal` 三条路径都天然支持接管）；
  - 中断 → 取消 future + 恢复中断 + 抛 CancellationException；owner 异常完成的传播语义保持（CancellationException/RuntimeException 原样抛，受检异常包成 IllegalStateException）；
  - 补上 chunk 级递归守卫：新增 `ownedChunks` ThreadLocal，claim 阶段发现同线程递归请求同一 chunk 直接抛 IllegalStateException（与既有 tile 级 `ownedTiles` 守卫对齐）。
- **界面/语言/客户端 JSON**：`ConfigScreenFactoryImpl` "路网规划"分类在 `height_aware_state_limit` 之后新增 `sampling_in_flight_timeout_ms` 整型项（min 100 / max 30000）；`assets/roadweaver/lang/en_us.json`、`zh_cn.json` 增加名称+说明（两文件已通过 JSON 解析校验）。说明：仓库内不存在独立的"客户端默认配置 JSON"模板（grep heightAwareStateLimit 无命中），客户端 JSON 指即这两份客户端语言资源；`roadweaver.json` 由 Gson 序列化自动带上新字段。

## 改动 2：P2-13 CoarseTerrainTileCache 全局单锁 → 分片 LRU

- `MEMORY`（单把 `MEMORY_LOCK` + 单个 access-order LinkedHashMap）替换为 **16 个分片 LRU**（每片独立锁，`Math.floorMod(key.hashCode(), 16)` 选片；总容量仍受 `COARSE_TERRAIN_TILE_CACHE_MAX_ENTRIES=256` 约束，每片 16 条）。
- `clearAll()` 语义不变：`CACHE_GENERATION.incrementAndGet()` → 整组分片表与 IN_FLIGHT 表**整体替换**（volatile 写），旧代加载线程在下一个 `ensureCurrentGeneration` 检查点直接 CancellationException 取消，旧分片上残留写入随孤儿 map 被 GC。

## 改动 3：TerrainProfileMode 语义清理（选择"合并 + 写清"方案，行为零变更）

- **现状固化**：改动前 `roadJUnitRegressionSuite` PASS tests=13（本批第一步复跑记录）；改动后同回归 PASS tests=29。
- **实现**（选用任务 3 的第二方案，理由：真正"按山地/水体占比自动切档"会改变默认 AUTO 路线的切/填与搜索触发，正是已通过回归的决策路径，风险与本次"不要动已回归逻辑"的约束冲突；文档已注明为后续可选项）：
  - `PlanningConfig.TerrainProfileMode` 移除 `BALANCED` 枚举值（与 AUTO 在所有消费点行为完全相同——审计确认的重复档）。旧配置 JSON 中的 `"BALANCED"` 经 Gson 读为 null，由 `sanitize()` 回落为 AUTO，无数据风险；全仓 grep 无代码引用残留；
  - 枚举 javadoc 明确三种真实行为：AUTO=均衡基准；TUNNEL_FIRST=切≤3 且山岭路线触发 3D 高度感知；BRIDGE_FIRST=填≤1 且水面对照忽略最小桥水深；**LIGHT_CUT=切≤3 且填≤1，不改变任何搜索行为（只影响整平，不是隧道/桥倾向）**——修正了名称与行为的错位；
  - 语言文件：中英文 tooltip 重写为真实语义（原文"自动档：高山给隧道/盘山道，水体用桥/海底隧道"承诺了不存在的行为，已删除该承诺），`option.auto` 更名 "自动（均衡）/ Auto (balanced)"，删除 `option.balanced` 词条。
- 为回归断言抽出两个纯函数（行为逐值不变）：`PathSpanExtractor.effectiveBridgeMinDepth(mode, configured)`（替代 extractSpans 内联的 bridgeFirst 判断）、`RawPathSearch.wantsHeightAware(enabled, transition, tunnelFirst, mountain)`（替代 find 内联触发条件）。

## 改动 4：审计点名的死代码清理

- `InitialGenManager`：删除主批次循环中不可达的 `if (tierIndex > 0) {...}` 整块（`initialBatchSteps()` 恒返回单元素列表，该分支永不可达；真正的局部修复是其后独立波次），保留 `submitAndCollectAtStep(..., tierIndex > 0)` 的表达式形式以维持泛化。
- 其余三项在上一批已完成、本批核实无残留：`RawPathSearch.applyCoarseTerrainModes` 的 `allowReroute` 废弃参数已删；`RoadGradePlanner` 无参 `surfaceCutLimit()` 已随快照化重构消失；`PathSpanExtractor` 的 `TUNNEL_MIN_OVERBURDEN`/`MAX_ORDINARY_CUT_DEPTH`/`MAX_ORDINARY_FILL_DEPTH`/`UNDERSEA_TUNNEL_SELECTION_PROBABILITY` 残留常量已删。
- `RoutePrecisionRetryPolicy.allowsLocalDetour` 保留（`RoutePrecisionRetryPolicyTest` 仍在覆盖其语义；生产代码自上批删除死分支后已不再调用）。

## 改动 5：新增测试（16 条，全部可确定性运行）

- **`pathfinding/terrain/region/CoarseTerrainTileCacheConcurrencyTest`**（注入 loader，无需 ServerLevel）：
  1. `contendedKeyIsLoadedByExactlyOneThread`：8 线程同 key 竞争（CyclicBarrier 对齐），loader 恰好执行 1 次，8 个结果同一实例；
  2. `timeoutTakesOverStalledOwner`：owner 卡在 loader，waiter 在 200ms 超时后接管成功并返回自己的瓦片；owner 释放后仍以自己的瓦片正常完成（两边各跑一次 loader）；
  3. `clearAllGenerationBumpCancelsInFlightOwner`：owner 加载期间 `clearAll()` 换代，返回 null（旧代结果被取消，不发布不阻塞）；
  4. `recursiveSameThreadRequestFailsFast`：同线程递归请求同一 tile 立即抛 IllegalStateException，不自旋不死锁。
- **`pathfinding/TerrainProfileModeGradeLimitsTest`**：AUTO/TUNNEL_FIRST/BRIDGE_FIRST/LIGHT_CUT 四档的 plain/mountain 切/填矩阵（5/2、3/1 基线 + 各档收紧）、overburden=7、tunnelMinRun=3 跨档不变、`effectiveBridgeMinDepth`（BRIDGE_FIRST→1，其余→配置值）。
- **`planning/terrain/TerrainProfileModeSearchBehaviorTest`**：高度感知触发矩阵——开关关闭恒 false；过渡不可施工恒触发；TUNNEL_FIRST+山地触发；TUNNEL_FIRST 无山地不触发；非 TUNNEL_FIRST 需要过渡才触发。
- 套件注册：`RoadJUnitRegressionSuite` selectors 增加 3 个测试类。

## 验证（本机，仅跑指定任务）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` | BUILD SUCCESSFUL |
| `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | **PASS tests=29**（基线 13 + 新增 16） |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未运行 `:common:test`（按要求）。
- lang 两份 JSON 均通过解析校验。
- 未提交任何 git commit，未触碰存档/部署目录。

## 遗留 / 交接

- 真正的"按 RoadTerrainClassifier 山地/水体占比自动切档"（任务 3 的方案一）未实施：会改变默认 AUTO 的决策行为，建议作为独立特性单独回归后再上。
- P1-2（共享池嵌套等待 + 交互期缩池 1）仍未处理，建议与 `samplingInFlightTimeoutMs` 的实际运行观测一起排期。

---

# 追加批次：真正的 AUTO 地形切换 + 性能/健壮性 + 测试（2026-09-02，0.8.236 之后）

状态：**全部完成，编译与 5 项回归通过**。基线固化：改动前 `roadJUnitRegressionSuite` PASS tests=29；改动后 PASS tests=41（新增 12 条断言：AUTO 行为 10 条、批量落盘 2 条）。四项模型回归（whole-flow / tunnel-junction / topology-water / structure-avoidance）全部 PASS——默认均衡参数下 AUTO 阈值未改变既有决策结果，仅占比越限时才切换。注：上一批交接中"真正的 AUTO 切换未实施"一项，本批已完成。

## 任务 A：真正的 AUTO 地形切换（核心）

- **决策点**：`RawPathSearch.find` 对每条路线用 `RoadTerrainClassifier.classifyRoute` 求出山地/水体占比（水体 = RIVER+LAKE+OCEAN，COAST 属陆地不计入），当全局档位为 AUTO 时按阈值解析出**路线级倾向**，经 `RouteProfileTendency.set(connection, tendency)` 存入按连接键控的内存注册表：
  - 山地占比 ≥ `autoMountainThresholdFraction`（默认 0.30）→ TUNNEL_FIRST 倾向：切方收紧 ≤3、山岭路线触发三维高度感知搜索；
  - 水体占比 ≥ `autoWaterThresholdFraction`（默认 0.25）→ BRIDGE_FIRST 倾向：填方收紧 ≤1、本路线桥梁最小水深放宽到 1（粗规划隧道预检用放宽后的深度重跑一次，保证与 span 提取一致）；
  - 两者都超阈值：占比更高者优先；都不超：均衡基准。
- **新文件 `planning/RouteProfileTendency.java`**：按 `PlannedPathKey` 键控的 ConcurrentHashMap。条目按连接数有界、每次重规划覆盖；`effectiveMode(connection)` 规则：全局显式档位（TUNNEL_FIRST/BRIDGE_FIRST/LIGHT_CUT）直接返回全局值并完全忽略注册表；仅全局 AUTO 时读取路线倾向。**不写回 ConfigService，不污染持久化指纹**。
- **消费点**：
  - 高度感知触发：`wantsHeightAware(enabled, transition, tunnelFirst, mountain)` 的 `tunnelFirst = 全局 TUNNEL_FIRST || (AUTO 且倾向 TUNNEL_FIRST)`；
  - 切/填收紧：`Road.computeTargetY` 调用新增 `RoadGradePlanner.stabilizeSurfaceProfile(..., effectiveMode)` 重载（内部 `SurfaceGradeLimits.capture(mode)` 按倾向捕获；`constrainProfileToFootprintSupport` 由 stabilize 把同一 limits 下传，路线内完全一致）；
  - 桥深放宽：`Road.routeBridgeMinWaterDepth()`（5 处调用点：两次 extractSpans、retainVerticallyRelevantCrossings、reconcile、tryRiverRepair）与 `applyCoarseTerrainModes(..., depth)`。
- **显式档位不受影响**：`resolveAutoTendency` 对非 AUTO 一律返回 BALANCED（倾向层无操作），全局档位行为与改动前逐值一致；AUTO 默认走均衡路径，仅占比确实越限时切换。
- **新配置**（`PlanningConfig`：字段 + sanitize [0.1,0.9] + snapshot + getter/setter）：`autoMountainThresholdFraction`（0.30）、`autoWaterThresholdFraction`（0.25）。UI "路网规划"分类新增两个 Double 字段（min 0.1 / max 0.9，位于采样超时之后）；中英文说明已补（en_us.json / zh_cn.json 均通过解析校验）；客户端 `roadweaver.json` 由 Gson 序列化自动携带新字段。

## 任务 B：性能/健壮性

1. **B1 快照等待去重（`RoadPathCalculator.refreshPlanningSnapshot`）**：新增按连接键控的 `SNAPSHOT_WAIT_CACHE`（路径指纹 → 已完成 future）。同一连接、路径指纹未变且 snapshot 已完成时直接返回，跳过每个结构绕行半径/湖修复 cycle 的重复预测/采样；路径变化（修复拼接后）指纹不同 → 照常重新预测；future 异常完成不缓存。
2. **B2 批量异步落盘（`PlannedPathCache.register`）**：内存写入保持同步（权威层），持久化扇出到新建的**有界写线程池** `RW-PlannedPathWriter`（1–4 线程，容量取 `ThreadPoolManager.initialGenerationThreadCapacity()` 与 4 的较小值）；失败仅 warn、内存路径保留，下次规划重写；`clearAll()` 先给排队写 5 秒收尾窗口再清内存，避免手动清缓存丢已规划路线。抽出包私有 `registerInternal(memory, store, fingerprint, paths, logDim)` 供回归注入。
3. **B3 `ConfigService.get()` 去 synchronized**：`INSTANCE` 改 `volatile`，`get()` 纯引用读（热路径不再走类锁）；`load/save/planningFingerprintPayload` 仍 synchronized，配置交换与文件 IO 语义不变。全仓 grep 确认无任何调用点依赖 get() 的 monitor。
4. **B4 `isSharedElevationEndpoint` 线性扫描**：**本轮标记可选、不硬改**（已加详细注释）。原因：`StructureLocationData.structureInfos()` 是被 `StructureFileStorage` 原地 add/remove 的可变列表，做 XZ 索引必须在每个变更点挂失效钩子，漏一处会造成端点锁高判定失真——风险大于收益（该扫描每条路仅 ~2 次 O(n)，非管线热点）。

## 任务 C：新增测试（12 条）

- **`TerrainProfileModeAUTOBehaviorTest`**（10 条）：山地 0.4 → TUNNEL_FIRST；水体 0.3 → BRIDGE_FIRST；双超阈值按更高占比（0.4/0.3 → TUNNEL_FIRST，0.3/0.4 → BRIDGE_FIRST）；双不超 → BALANCED；阈值含边界（== 触发）；显式 TUNNEL_FIRST/BRIDGE_FIRST/LIGHT_CUT 任何占比都不产生路线倾向；占比计算（COAST 不计入水体）；`RouteProfileTendency.effectiveMode`（AUTO+倾向可见、清空回落 AUTO、显式全局档位覆盖任何存储倾向）；阈值 sanitize 钳制 [0.1,0.9]。
- **`PlannedPathCacheBatchWriteTest`**（2 条，伪造 PlannedPathStore + 注入内存表）：8 条路线批量注册 → 内存即时全部可读（磁盘仍在途）、全部写完成后不同写线程数 ≤ 池容量（1–4）；单键保存失败 → 内存路径保留、无异常外抛。
- 套件注册：`RoadJUnitRegressionSuite` selectors 增加 2 个测试类。

## 验证（本机，仅跑指定任务）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` | BUILD SUCCESSFUL |
| `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | 改动前 PASS tests=29（基线）→ 改动后 **PASS tests=41** |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未运行 `:common:test`（按要求）；lang 两份 JSON 均通过解析校验；未提交 git commit，未触碰存档/部署目录。

## 交接备注

- AUTO 切换决策仅在粗规划 `RawPathSearch.find` 内做一次并存入注册表；局部修复波次不重跑 find，直接复用注册表中该连接的倾向——这正是按连接键控而非 ThreadLocal 的原因。
- `RouteProfileTendency` 条目按连接数有界、不主动清空（每次重规划覆盖，陈旧条目仅在全局 AUTO 时被读取且会被覆盖）；如需强制失效，可在 `PlannedPathCache.discard` 处挂 `RouteProfileTendency.clear`。
- 水体占比定义：RIVER+LAKE+OCEAN（COAST 是陆地近水，不计入）；如需把海岸计入水占比，改 `RawPathSearch.waterFraction` 的类型参数即可。
- 现有 whole-flow / topology-water 场景在默认阈值下未跨过 0.30/0.25 或切换后仍满足断言；后续新增强山地/强水体场景时 AUTO 切换会真实生效，断言需按新行为设计。
- P1-2（共享池嵌套等待 + 交互期缩池 1）仍待处理。

---

# 追加批次：性能剖析 + 线程池健壮性 + RouteProfileTendency 失效 + 路线级 AUTO 回归（2026-09-02，0.8.237 之后）

状态：**全部完成**。`:common:compileJava` / `:fabric:compileJava` BUILD SUCCESSFUL；`:common:roadJUnitRegressionSuite` **PASS tests=47**（29 → 41 → 47）；四项模型回归全部 PASS。

## 剖析方法与基线（任务 5 的一部分）

- 环境）：本仓库的可执行回归（whole-flow / topology-water 等）是**无 ServerLevel 的模型级模拟**，不经过 `RoadPathCalculator.finalizePath` 的计时埋点，`DetailedRoadGenerationStats` 的阶段计数器不会移动；而 `logSummary`（exactHeightMs / gradeRepairMs / structureCorridorMs / spanExtractionMs / segmentBuildMs / totalMs）此前**只在启动期** `InitialGenManager:575` 输出一次——真实游戏内启动时即可在日志中 grep `detailed generation timing` 取到。profiler（spark 等）在本环境不可用。
- **因此本轮先修可观测性，再谈优化**（见 1c）：给运行期补上周期性阶段耗时汇总，下个版本起即可在真实游玩中采集基线并对比优化效果。
- 组件级"预估"（代码审计结论，非实测）：
  - `AccurateGradeRepairPlanner.repair`：违例扫描 `findFirstViolation` 是**纯 Y 比较**（零采样），已稳定路线本只需 1 次 O(n) 扫描 + 拷贝；真正的大头是真实违例的 `findLocalRoute` A*（单窗口状态上限 90_000，`expandedStateLimit` 最多放大到 90k+requested，最多 4 窗口/轮 × 最多 4 轮）。
  - `GRADE_REPAIR_POOL` 并行度 2–4（`max(2,min(4,procs))`）——合理：路线级并行已占用核；未盲调。
  - `sampler.samplePositions` 重复采样：`AccurateHeightSampler` 已有三层去重（列去重 `uniquePositions` + 列缓存 + chunk 缓存），修复窗口按 cursor 构造**互不重叠**，无重复列采样；维持原状。
  - `StructureAvoidanceService` 重复预测：路径侧已由 B1 缓存覆盖；本轮补齐 **circle 侧**（见 1b）。

## 任务 1：最大耗时点优化（已直接改）

1. **1a "已稳定则跳过"快速路径**（`AccurateGradeRepairPlanner.repair`）：入口先跑一次纯 Y 的 `findFirstViolation`，无违例直接返回原列表——跳过批收集、`GRADE_REPAIR_POOL` 提交、context walk、splice 和防御拷贝。stable 路线成本从"1 次扫描 + 批机制"降到"1 次扫描"；返回同一实例还让 `finalizePath` 的 `equals` 检查短路，省掉二次 buildSegments。调用方（finalizePath / 构造中心 / lake cycle）全部基于 `equals` 比较，无行为差异。
2. **1b circle 快照等待去重**（`AccurateGradeRepairPlanner.awaitLocalStructureCircle`）：新增 `CIRCLE_SNAPSHOT_CACHE`（连接 + 圆心 + 半径指纹 → 已完成 future），与 B1 的路径侧去重对称。局部修复的每个绕行半径与湖 cycle 重复请求同一 100-block 冲突圆时不再重复触发结构发现。
3. **1c 运行期可观测性**（`DetailedRoadGenerationStats` + `RoadGenerationService.safeGenerate`）：
   - **根因修复**：`add()` 与 `recordRoad()` 此前被 `InitialGenManager.isActive()` 门控——运行期阶段计时根本不累加，任何运行期监控都无从谈起。移除门控（`reset()` 在启动时清零，启动汇总窗口依然干净；运行期累计供周期汇总）。
   - 新增 `logSummaryIfDue()`：至多每 10 分钟、且仅当阶段计时器有新增时输出与 `logSummary` 同格式的 INFO 汇总（wallMs=0 由调用方语义解释）；挂接在每条运行期道路完成后。
   - **后续监控建议**：真实服务器跑 30–60 分钟后 grep `detailed generation timing`，对比 exactHeightMs / gradeRepairMs / structureCorridorMs 的单路均值；若 gradeRepairMs 单路 > 1.5s 或 structureCorridorMs > 2s，优先查违例窗口数量与 circle 预测次数。

## 任务 2：线程池健壮性（选择方案 a：专用 helper 池）

- **选择说明**：采用"道路任务内部同步等待的子任务路由到独立小型池"，即新增 `TaskRole.SNAPSHOT`（优先级 6）+ 专用固定 2 线程池 `RW-Snapshot`（`ThreadPoolManager`，与其他角色一致走 `PrioritizedRunnable`/epoch/生命周期管理）。不采用"等待前检查剩余 worker 数"——那需要在每个等待点引入降级逻辑，复杂且仍可能挨饿；也未拆分 PLANNING/COARSE 与 GENERATION/IDLE（改动面大）。
- **重挂点**：`StructureAvoidanceService.ensurePlanningSnapshotForPathAsync` 与 `ensurePlanningSnapshotForCircleAsync` 的预测提交由 `TaskRole.STRUCTURE` → `TaskRole.SNAPSHOT`。此前剖析确认运行期预测本就不在共享池（为 STRUCTURE 专用池），但结构池会被运行期 QoS 压到 1 线程且与真实结构采样任务竞争；SNAPSHOT 池独立于 QoS 缩容，等待中的道路任务不再被结构采样长任务挡住，嵌套等待链彻底断开。
- **启动期行为不变**：`trackInitialProgress=true` 的启动预测仍走 `INITIAL` 池；`Chunky`/交互暂停的 `beforeTask` 门控对 SNAPSHOT 角色与原 STRUCTURE 角色路径一致（awaitRuntimePermit）。
- 池规格：2 线程、`PriorityBlockingQueue`、守护线程、`RW-Snapshot` 命名——足以覆盖 `maxConcurrentGenerations`（默认 1–2）并发的快照预测。

## 任务 3：RouteProfileTendency 失效

- `PlannedPathCache.discard` 现在调用 `RouteProfileTendency.clear(connection)`（路线生成成功或被删除时清理，避免同端点新规划读到旧 AUTO 倾向）；`PlannedPathCache.clearAll()` 调用新增的 `RouteProfileTendency.clearAll()`。
- 生命周期测试（`TerrainProfileModeAUTOBehaviorTest` 新增 2 条）：注册 → 生效 → 覆盖 → `clear` 回落 AUTO；`clearAll` 清空全部连接。
- 注：`discard` 挂接本身需要 ServerLevel 才能在单测中直接驱动，已由编译 + 回归覆盖该一行调用。

## 任务 4：路线级 AUTO 回归（`RouteLevelAutoTendencyTest`，6 条）

- 用**可控 `PathTerrainField` 匿名实现**（固定 surfaceY / columnWater / null biome）驱动真实决策链：`RoadTerrainClassifier.classifyRoute` → 占比 → `resolveAutoTendency` → `RouteProfileTendency.set` → `effectiveMode` → 三个消费断言（`SurfaceGradeLimits.capture(effectiveMode)`、`PathSpanExtractor.effectiveBridgeMinDepth`、`wantsHeightAware`）。
- 场景：山地路线（surface=100 / 路 Y=60，全 MOUNTAIN）→ TUNNEL_FIRST + 切方 3 + 高度感知触发 + 桥深不放宽；水体路线（null-biome 水列 → LAKE 分类，水体占比 1.0）→ BRIDGE_FIRST + 填方 1 + 桥深放宽到 1 + 不触发高度感知；均衡地形（全 PLAIN）→ 保持 AUTO 基准（切 5/填 2/桥深 4 不变）；显式全局档位（TUNNEL_FIRST/LIGHT_CUT）覆盖任何路线地形。
- 说明：测试用 null-biome 水列（分类为 LAKE）绕开"此分支核心包无编译期可用的 `BuiltInRegistries.BIOME`"的环境限制，水体占比语义不变；`find` 的 A* 内部仍由模型回归覆盖。

## 验证（本机，仅跑指定任务）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | **PASS tests=47**（上一批 41 + 本批 6） |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`；未提交 git commit；未触碰存档/部署目录。

## 遗留 / 下一步

1. **真实基线采集**：部署后跑 30–60 分钟真实世界，收集 ≥3 次 `detailed generation timing`，用单路均值验证"快速路径 + circle 去重"的收益（预期：stable 路线的 gradeRepair 调用从"批机制"降为一次 O(n) Y 扫描；结构圆预测次数按去重率下降）。
2. **耗时阈值告警**：如需主动告警，可在 `logSummaryIfDue` 内对单路均值超阈值时把 INFO 升级为 WARN（当前仅周期输出，避免日志洪水）。
3. P1-2 残余：启动期道路任务（INITIAL 池）提交启动期预测（同为 INITIAL 池）的同池嵌套仍在，但启动期有"先批量规划、后批量生成"的批次屏障 + 快照预取，实际未见卡死；如要彻底消除，可把启动预测也迁到 SNAPSHOT 池并扩容，需单独回归启动时长。
4. B4（`isSharedElevationEndpoint` XZ 索引）维持"标记可选"，原因同上批（可变列表 + 失效钩子风险）。

---

# 追加批次：全量性能优化（精确生成 / 局部修复 / 后处理落盘）（2026-09-02，0.8.238 之后）

状态：**全部完成**。`:common:compileJava` / `:fabric:compileJava` BUILD SUCCESSFUL；`:common:roadJUnitRegressionSuite` **PASS tests=47**；四项模型回归（whole-flow / tunnel-junction / topology-water / structure-avoidance）全部 PASS；两份 lang JSON 解析校验 OK。路线/桥/隧道决策逻辑未改变（见各项"可回退性"说明）。

## 基线（任务保留项）

- **真实启动日志**：本环境无法运行 Minecraft 服务器，`detailed generation timing`（wallMs/exactHeightMs/gradeRepairMs/structureCorridorMs/segmentBuildMs/constructionSetupMs/targetHeightMs/spanExtractionMs）仍只能在真实世界入口产生（`InitialGenManager` → `DetailedRoadGenerationStats.logSummary`，启动汇总；运行期由上批新增的 `logSummaryIfDue` 每 10 分钟输出一次）。已确认该机制在位；**部署后采集步骤**：启动世界 → grep `detailed generation timing`（记录 wallMs 与各阶段累计）→ 游玩/补路 30 分钟 → 再 grep 取运行期窗口。
- **局部修复波计时**：本轮补齐——`local repair wave finished` 日志新增 `wallMs`（`InitialGenManager`，覆盖 `refineInitialRoutesAsync` + `submitAndCollectAtStep` 的完整波次墙钟）。窗口/采样数已有既有日志（`local route refinement window` / `local conflict prefetch scheduled`）。
- **"初始规划 ready → deferred construction enqueue"墙钟**：源码中无该名字的既有日志；deferred 队列入口 `DeferredInitialRoadConstructionService.enqueue` 已有 `queued: chunks=N` 日志，`InitialGenManager:583` 附近为提交点。两个时间点可由既有日志行的时间戳差值得到，未再加重复计时。

## 一、精确道路生成（逐项结论）

1. **finalizePath 重复 grade repair / 二次 buildSegments** —— 部分已修 + 本轮补齐：
   - 二次 buildSegments 原本就有 `!repairedConstruction.equals(constructionTerrain)` 守卫 ✓；
   - grade repair 的"已稳定跳过"上一批已在 `AccurateGradeRepairPlanner.repair` 入口实现（纯 Y 扫描）✓；
   - **本轮新增**：finalizePath 的路径级与构造级两条修复链中，**收尾的第二遍 structure repair 现在仅当水体修复真的移动了路线时才重跑**（河/湖修复无操作时返回同一实例且不动快照，结构修复对相同 (路径， 快照) 是确定性的——跳过是纯冗余消除，结果逐值相同）。判定用实例相等（`afterRiver != beforeWater || afterLake != afterRiver`）。
   - 可回退性：不改变任何输出（确定性论证见代码注释）；如需绝对保守可恢复原顺序，无开关。
2. **repairAndRecordGrade 整条扫** —— 已有快速路径（上批），本轮核实其位于 hint 分支之后的整路径修复入口 ✓ 无重复扫描。
3. **buildSegments 采样** —— 核实：`PathPostProcessor.process` 走 `AccurateHeightSampler.samplePositions`（列去重 + 列缓存 + chunk 缓存 + 持久层，只补 miss）；`RoadSegmentPlacement.positions` 由后处理器一次生成复用 ✓ 无需改。
4. **stabilizeSurfaceProfile 每轮 capture** —— 已修（上批）：pass 级一次快照下传 `constrainProfile` / `constrainProfileToFootprintSupport` / `findTunnelCorridors` ✓ 本轮核实无残留每站位/每轮读取。
5. **extractSpans 的 stream.sample / 会话复用** —— 核实：`Road.generateRoad` 每条路线开**一个** `SamplingSession` 并全程下传（span 提取、验证、tryRiverRepair、reconcile 共用）；`SamplingSession` 内部已有 `ConcurrentHashMap` 列级缓存（`columns`/`lakes`，computeIfAbsent）✓；带会话参数的调用无重复开会话。仅遗留的"自开会话"重载（`extractSpans(segments, level, cache, depth)`）被非路线调用方使用，无热路径影响。
6. **BridgeDeckProfile / applyUnderseaTunnelProfile 中间结果** —— 审阅：`findStableBankRange` 为 O(n) 单趟、按 span 调用，无跨调用重复；海底隧道概率选择为确定性散列 + 单次遍历。无可安全缓存的中间结果（缓存收益 < 失效复杂度），维持原状。

## 二、局部修复（逐项结论）

1. **conflictSamplingWindows** —— 核实：**hints 已优先**（protected-gap hints 直接生成窗口并先加入），随后仍做全路径 O(n) 冲突扫描。**刻意保留全路径扫描**：它是修复窗口定位的正确性来源（hints 只覆盖有 gap 的历史场景），砍掉会让漏检冲突 → 修复不触发 → 连通性风险，违反"不牺牲连通性"约束。`shouldAvoidPlanning`/`pathEdgeConflicts` 均为内存快照查询，无采样 IO。
2. **重复快照刷新** —— 已覆盖 ✓：路径侧 `refreshPlanningSnapshot`（B1 指纹缓存）、circle 侧 `awaitLocalStructureCircle`（上批新增圆形缓存）；两处是全部"道路任务内等待快照"的入口，本轮核实无第三处。
3. **GRADE_REPAIR_POOL 并行度/窗口** —— 核实：每窗口一个任务（最多 4 个并发提交）在 2–4 线程池上，单窗口不会"占满"池（一个窗口 = 一个任务）；窗口互不重叠（cursor 机制）。**本轮新增可配置**：`gradeRepairWindowStateLimit`（默认 90000 = 历史固定值，范围 [20000, 500000]）——慢机器可调低换延迟；默认行为不变。
4. **无 timeout 的 join/get** —— 本轮补齐两处**道路任务会阻塞的快照等待**：`RoadPathCalculator.awaitPlanningSnapshot` 与 `AccurateGradeRepairPlanner.awaitLocalStructureCircle` 的 miss 路径改为 `get(timeout)`；超时 = 取消 future 并抛 CancellationException → 路线走拓扑回退（**保守降级，绝不无快照铺路**）。超时值 = `samplingInFlightTimeoutMs × 10`，钳制 [5s, 120s]。有意保留（语义不同）：`InitialGenManager` 的 `managedBlock + get`（服务线程世界入口，managedBlock 防冻结）、`StructureAvoidanceService:1833/2119`（等待 Minecraft 区块生成 future，超时会破坏结构加载语义）、`StreamsReflowingCompat:292`（预热聚合，owner 有异常兜底）、`ExistingRoadJunctionService:114`（启动期小批量）。
5. **samplePositions 缓存优先** —— 已核实：列缓存 → chunk 缓存 → 持久层，仅 miss 补采 ✓。
6. **expandedStateLimit** —— 已配置化（见上）；默认与历史行为逐值相同；隧道窗口仍按 `tunnelDepthBlocks × 列数` 上浮、由 `MAX_TUNNEL_EXPANDED_STATES` 与 `MAX_EXACT_BUILDING_EXPANDED_STATES` 封顶。

## 三、后处理 + 落盘（逐项结论）

1. **RoadSnapService 交叉口 O(n²)** —— 核实：配对循环**已有空间剪枝**——每条路预计算 `RoadBounds`，`gap(primary, secondary) > SPATIAL_REJECT_MARGIN` 的不可能配对直接跳过（注释明确"changes no result, only avoids impossible pairs"）；实际 junction 匹配/验证只对邻近配对运行。剩余 O(n²) 只是 O(1) 的 bbox gap 比较，分桶收益可忽略，不引入新数据结构。
2. **高度统一复制** —— 核实：配对循环原地改 `current[]`，持久化用单次 `replaceRoads` 批量提交 ✓ 无反复全量复制。
3. **RoadShardStorage 串行写** —— 核实：初始批持久化走 `RoadShardStorage.addRoads`（批量）；`RoadFileStorage` 写入为**原子替换文件**（Deflate + 临时文件替换，无半写窗口）；单路 `addRoad` 仅运行期逐条路径（语义上必须即时可见）。维持原状。
4. **RoadWorldgenPlanCache** —— 已有 (chunk, revision) 计划缓存（`Session` + `MAX_PLANS_PER_LEVEL=4096` + 受影响 chunk 精准失效）✓ 无重复 compile。
5. **PlannedPathCache.store(level) 每次 new** —— **本轮修复**：按 level 缓存 `PlannedPathStore`（`STORES` IdentityHashMap + 同步），`clear(level)/clearAll()` 同步驱逐；写任务继续复用 `RW-PlannedPathWriter` 有界池。
6. **地图/快照持久化** —— 核实：`MapPatchService` 已经是 `MAP_READ` 池上的**差分 patch** 构建（`buildRoadPatch`），非每 tick 全量写 ✓。
7. **initial construction enqueue** —— 核实：`DeferredInitialRoadConstructionService.enqueue` 接收调用方已算好的 chunkKeys 集合（无二次全 chunk 遍历）；`tick` 每 tick 最多 8 chunk、玩家半径 289 格内早停；`recover` 单趟遍历持久化道路 ✓。

## 可回退性汇总

- **行为逐值不变**的优化（无开关，确定性论证在代码注释）：finalizePath 收尾结构遍跳过；PlannedPathStore 复用；窗口状态上限默认值不变。
- **新增可回退旋钮**：`gradeRepairWindowStateLimit`（默认=历史值）；`samplingInFlightTimeoutMs` 间接控制两处快照等待上界（默认 3s×10=30s）。
- **保守降级语义**：快照预测超时 → 路线失败走拓扑回退（替代原先的无限挂起），不会无快照铺路。
- 未改变任何路线/桥/隧道决策阈值或连通性判定。

## 验证

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | **PASS tests=47** |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`；未提交 git commit；未触碰存档/部署目录。
- 本地模拟真实初始化流程：不可行（模型回归无 ServerLevel），初始化墙钟请按上文"部署后采集步骤"在真实服务器取数。

## 遗留 / 下一步

1. 部署后按上述步骤采集两组 `detailed generation timing`（启动 + 运行期 30 分钟），验证 gradeRepairMs / structureCorridorMs 的下降幅度（预期：stable 路线 gradeRepair 接近零增量；结构圆预测按去重率下降；finalizePath 少一趟结构预检）。
2. 若 `gradeRepairWindowStateLimit` 调低后出现更多"cliff window 不可修复"告警，回调至 90_000 即可（配置热改，无需回滚代码）。

---

# 追加批次：交叉口剪尾 + 保护空位高度流 + unknown 端点高度协商（2026-09-02，0.8.239 之后）

状态：**全部完成**。`:common:compileJava` / `:fabric:compileJava` BUILD SUCCESSFUL；`:common:roadJUnitRegressionSuite` **PASS tests=60**（47 + 本批 13）；四项模型回归（whole-flow / tunnel-junction / topology-water / structure-avoidance）全部 PASS。

## 任务 1：交叉口剪尾（RoadSnapService）

- **目标**：次路接入主路后，不留压在主路走廊上的尾部段。
- **文件**：`postprocess/RoadSnapService.java`。
- **改动**：
  - `tryConnect` 选定候选后调用新增的 `trimSecondaryTailAtJunction(primary, candidate, mode)`；
  - 仅对端点接入类模式生效（`spatial-start` / `spatial-end` / `shared-endpoint` / `endpoint-corridor`）；复用走廊类模式（`parallel-overlap` / `two-junctions` / `two-approaches`）不剪；
  - 从末尾向前找落在主路站位的连续尾段，保留至第一个主路站位（junction），segments / targetY / spans **三者同步裁剪**；主路侧任何数据不动；
  - 守卫：尾部含 NETWORK_REUSE / JUNCTION_ANCHOR span → 不剪（锚点被下游依赖）；裁剪后 <5 段 → 不剪；裁剪结果过不了 `RoadDataValidator` → 不剪（WARN 现含 reason/index/detail，本批顺手补的可观测性）；
  - 成功日志：`RoadWeaver trimmed secondary tail at junction … primary=… segments=N -> M`。
- **默认行为变化**：是——端点接入的次路不再留下压在主路上的尾巴段（正是本任务要修的缺陷）；连接的其余判定（候选选择、开销上限、验证门槛）逐值不变。

## 任务 2：保护空位两侧高度流（FinalRoadStructureValidator）

- **目标**：保留 PROTECTED_GAP 前先把空位两侧拉成连续坡（高侧降、低侧只降不升），使保留的空位不再伴随陡坎 / invalid-height-step。
- **文件**：`features/path/pathlogic/core/FinalRoadStructureValidator.java`。
- **改动**（`retainVerifiedBuildingGaps` 每标记一个空位后调用 `smoothProtectedGapApproaches`）：
  1. 按 span 索引取每个空位的前后普通站位，两侧都落到**低侧**高度（seat）；
  2. 两侧坡道窗口 8..48 站（历史 32 上限满足不了 40 块落差需要的 42 站——这正是 `invalid-height-step` 反复出现的根因之一），双向钳制 pass（先向窗口外、再收回）保证相邻普通对 ≤1 块，且**只降不升**；
  3. 空位段整体落到 seat（保持不铺，铺装逻辑不动）；
  4. 桥/路口/隧道站位为**冻结锚点**（坡道绕行，不压它们的工程高度）；
  5. 验收：所有处理的空位两侧落座 + 受影响区域内 ordinary 相邻对步数 == 0；不满足则整体回退原 profile 并 WARN，交给既有失败路径走拓扑回退——**绝不保留半拉子陡坎**。
- **默认行为变化**：是——带落差的空位现在会得到连续坡（或整条回退重试），而不是带陡坎的空位；无落差空位逐值不变（`assertSame` 由测试锁定）。

## 任务 3：unknown 端点高度协商（UnknownEndpointHeightRegistry）

- **目标**：同一 unknown 节点多条路线接入时按施工代价协商一个统一高度；"高侧爬不上去就让路降下来"，而不是锁一个无法施工的结构地表高度。
- **文件**：新增 `features/path/pathlogic/core/UnknownEndpointHeightRegistry.java`；接线 `Road.java`、`PlannedPathCache.java`。
- **实现**：
  1. `approachFeasibleY`：端点前 16 站的 profile 值按每站 ≤1 块向端点外推——地表够不着时得到"路这一侧可达高度"；
  2. `chooseHeight`：对"结构地表"与"可达高度"各做一次只降坡道模拟（近端 48 站窗口），代价 = 超出配置切/填上限的方量 + 相邻 ordinary 对 >1 块的罚分；代价低者胜，平手取地表（历史行为）；
  3. `lockedHeight`：首条路计算并写注册表；后续路复用；存的高度对该路不可施工时降级到自身可达高度并 WARN（注册表值保留，能施工的路仍用它）；
  4. **只在 unknown 端点生效**：新增 `Road.isUnknownStructureEndpoint`（structureId 为 unknown / 非村庄 id / 非出生点）；`isSharedElevationEndpoint` 相应**排除** unknown id——村庄、出生点、命名结构仍走原地表锁，逻辑零改动；
  5. unknown 的坡道用新增 `applyPreferCutBoundaryGrade`（纯只降），`chosenHeight < structureSurfaceY` 时高侧自动压下来接住路口；
  6. `PlannedPathCache.clearAll()` 顺带 `clearAll()` 注册表，防跨世界泄漏。
- **默认行为变化**：是——unknown 端点可能锁到低于结构地表的高度（高侧降下来）；村庄/出生点/命名结构行为逐值不变（由回归验证）。

## 任务 4：测试（13 条新断言，套件 47 → 60）

- **`RoadSnapServiceTailTrimTest`**（5 条）：尾巴压主路 → 裁到 junction、segments/targetY/spans 同步、仅 junction 一站留在主路上、窗口内 span 保留而尾部 span 剔除；不重叠 → 不裁；尾部含 NETWORK_REUSE → 不裁；裁剪会剩 <5 段 → 拒绝；复用类模式 → 永不裁。
- **`ProtectedGapHeightContinuityTest`**（3 条）：40 块落差 → 两侧落座、ordinary 相邻对全部 ≤1、高侧下降、仍过 `validateFinalRoad`；已连续 → `assertSame` 原对象；60 块落差超出 48 站窗口 → 原样保留（不产生半拉子陡坎）。
- **`UnknownEndpointHeightConsistencyTest`**（5 条）：20 站高台 + 低地表 → 选"降到可达高度 88"而非锁 64；平地 → 锁地表；两条不同方向路线 → 同一注册高度；存储高度不可施工 → 该路降级 + 注册表值保留；`clearAll` 清空。
- 套件注册：`RoadJUnitRegressionSuite` 新增 3 个测试类。

## 验证（本机，仅跑指定任务）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | **PASS tests=60** |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`；未提交 git commit；未触碰存档/部署目录。
- 调试期测试侧修正（非源码行为）：模型类测试需 `SharedConstants/Bootstrap` 引导；测试路线数据统一 2 格中心步长（校验器 `invalid-center-step` 要求相邻站位水平步长一致）；两处数组下标随路线长度修正。

## 交接备注

- 剪尾的"尾部含锚点 span 则不裁"是保守取舍：若观察到带 NETWORK_REUSE 的接入路线仍留长尾，可在生成端把锚点 span 收敛到 junction 之前，再放开该守卫。
- 空位平滑的"整条回退"语义：落差超出 48 站窗口的空位会走拓扑回退（换候选连接）。若真实世界出现大量此类 WARN，可把窗口上限 48 提为配置（当前硬编码，改动面小）。
- unknown 高度注册表按端点 x/z 2D 键控、进程内存储；跨重启后首条路重新协商（结果确定性取决于地形，通常一致）。



---

# RoadWeaver 0.8.243：physical-span 互斥 + 坡度前置 + 走廊刷新去重

状态：**5 项回归全绿**（roadJUnitRegressionSuite tests=60 + whole-flow / tunnel-junction / topology-water / structure-avoidance 4 项模拟）。版本号已从 0.8.242 递增到 0.8.243（build.gradle）。

## 任务 5：overlapping-physical-spans 互斥（完成）

- 文件：`pathfinding/PathSpanExtractor.java`
- 根因：海底隧道转换（±128 站出入段）吞掉相邻桥 span 后，同一 index 同时是 bridge 和 tunnel，RoadDataValidator 以 overlapping-physical-spans 拒绝该连接（真实日志 (1408,3648)->(2448,3312)）。
- 改动：`normalizeTunnelSpans` 尾部新增 `resolveOverlappingWaterSpans` 自检——同一站只能有一个物理类型；隧道与桥/路口重叠时隧道胜出，桥/路口被删或收缩到非重叠剩余段（单站桥剩余丢弃，单站路口保留）。三个隧道产生方（refineTunnelSpans / convertLongOceanBridgesToUnderseaTunnels / refineRoadTunnels）都经 normalizeTunnelSpans 流经该自检。
- 默认行为变化：是——重叠段不再拒绝整条路（修复实测 bug）；无重叠时逐值不变。
- 测试：whole-flow / topology-water 过。

## 任务 2：高度/切深/坡度前置（完成）

- 文件：`config/sub/PlanningConfig.java`、`planning/terrain/CoarseHeightAwarePlanner.java`、`planning/terrain/RawPathSearch.java`
- 根因：粗规划 A* 允许 1:1 坡度（约 45°），后处理判据是 0.5；两边不一致导致 45° 崖坡全被拖进 grade repair，隧道/桥端出现 1 格水平 + 数十格垂直的竖井，最终 invalid-height-step。
- 改动：新增配置 `maxGradeFraction`（默认 0.5，sanitize [0.1,0.9]，snapshot/getter/setter 齐全）；粗规划 A* 邻居扩展的坡度上限由 `rise > floor(horizontal)`（1:1）改为读 `maxGradeFraction`（每搜索读一次，不进内层循环）；`RawPathSearch.hasUnbuildableCoarseTransition` 的硬编码 0.50D 同步改为同一配置，保证前后两阶段对"多陡需要隧道/桥"的判定一致。
- 默认行为变化：默认值 0.5 与原后处理判据一致；粗规划从"可接受 1:1"收紧到"≤0.5"，高崖坡在粗阶段就被要求走缓坡/隧道/桥，而不是留到后处理竖井化。
- 说明：切深/填方（≤surfaceCut/FillMaxBlocks）与 PROTECTED_GAP 高度流均未改动（边界遵守）；未做处见"未完成项"。

## 任务 4：走廊刷新短路（完成，小）

- 文件：`features/path/pathlogic/pathfinding/RoadPathCalculator.java`
- 改动：`repairStructureIntersectionsWithCorridorRefresh` 循环前的 `refreshPlanningSnapshot(level, connection, path)` 删除——`working` 初始即 `copyOf(path)`，首轮半径迭代会以相同内容刷新（B1 指纹去重后纯重复），删除是等价去重。每条路的走廊快照请求因此每轮减 1 次无谓刷新。
- 默认行为变化：无（纯去重）。

## 任务 6：剪尾锚点收缩（已回退）

- 完成实现并更新测试（tail-only reuse 随尾段丢弃、跨 junction 锚点收缩），但 **tunnelJunctionGradeRegression 失败**：日志显示裁剪现在真的把次路裁短（segments=30->21），破坏"T 型交叉口应降下高主干匹配次路"的既有语义。
- 结论：按"不为小改进牺牲连通性/回归必须全绿"，整体回退任务 6（RoadSnapService.java + RoadSnapServiceTailTrimTest.java 已 git checkout 恢复 0.8.242 基线）。剪尾主体 `trimSecondaryTailAtJunction` 保留 0.8.242 版本（含"尾部锚点不裁"守卫），残余小尾段问题留待主仓库在生成端把锚点 span 收敛到 junction 之前再放开守卫（已写入上批交接备注）。

## 未完成项（写入原因，未强行改动）

1. **任务 1（合并双修复链）**：finalizePath 路径中心链与施工中心链确有两套重复修复，但施工链处理的是 buildSegments 之后 segment 中心的精确水体/结构冲突，两条链语义不同且合并属高风险几何/批流重构；本环境无法跑真实世界初始化流程做几何回归，现有 5 项模拟不足以证明"合并后路线几何逐值一致"。留待主仓库带真实初始化观测做。
2. **任务 3（建筑规避前置）**：InitialGenManager 初始批"hasProtectedGap 即整条进局部"的主控流程改动会改变 24 条连接的波次调度与结果归属，风险高且需真实启动墙钟验证。留待下一批。

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=60 |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。git commit 前工作区含 6 个文件的改动（build.gradle 版本、PlanningConfig、RoadPathCalculator、PathSpanExtractor、CoarseHeightAwarePlanner、RawPathSearch）。
- 环境注意：build.gradle 曾因 UTF-8 BOM 导致 Gradle 解析失败（首行 Unexpected character '?'），已去 BOM；另需 JAVA_HOME=jdk-21（Gradle 8.8 不支持 Java 26，daemon 需先 --stop）。


---

# RoadWeaver 0.8.244：局部修复并发池扩展 + 局部修复可回退开关

状态：**5 项回归全绿**（roadJUnitRegressionSuite tests=60 + whole-flow / tunnel-junction / topology-water / structure-avoidance 4 项模拟）。版本号已从 0.8.243 递增到 0.8.244（build.gradle）。HEAD 基线 7d9c505（0.8.243）。

## 背景（本批针对的真实耗时）

- 0.8.239 启动日志：局部修复 wave wallMs≈1193710（约 19.9 分钟），后处理 gradeRepair 累计 gradeRepairMs≈2598523。
- 根因：grade 修复线程池固定仅 4 线程，14 条道路 worker 提交后全在 future.get() 阻塞等它，多数核空转。

## 任务 A：扩展 grade 修复并发池（完成）

- 文件：`config/sub/PlanningConfig.java`、`planning/terrain/AccurateGradeRepairPlanner.java`、`fabric/.../ConfigScreenFactoryImpl.java`、两份 lang JSON。
- 现状：`GRADE_REPAIR_POOL = newFixedThreadPool(max(2, min(4, procs)))`，固定 4 线程。
- 改动：
  - 新增配置 `gradeRepairParallelism`（默认 0 = auto）；sanitize 钳制 [0, 可用核数]，snapshot 复制，getter/setter 齐全。
  - auto 解析：`max(4, min(可用核数, RuntimeLoadPolicy.recommendedRoadWorkerThreads()))`——不低于原下限 4，按当前道路 worker 预算缩放，整体以可用核数封顶防超订。
  - 线程名保持 `RoadWeaver-GradeRepair`、daemon。解析在类初始化时执行一次。
  - UI：路网规划分类新增整型项（min 0 / max 16）；en_us.json / zh_cn.json 补名称与说明。
- 默认行为变化：无几何/高度变化（只改变量并行度）；默认 auto 在核数多的机器上线程数从 4 提升到按核数缩放，直接缓解"worker 全阻塞、核空转"。
- 测试：5 项回归全绿（roadWholeFlowSimulation 佐证单路几何与 0.8.243 一致）。

## 任务 B：双修复链去重审计（无可证明逐值一致的新去重点，如实说明）

- 文件：`features/path/pathlogic/pathfinding/RoadPathCalculator.java`（finalizePath）。
- 审计结论（未改动）：
  1. 路径中心链 resolve(rawPath) 与施工中心链 resolve(constructionCenters) 输入点位集**不同**（前者是 A* 路径点、后者是 buildSegments 之后的 segment 中心），结果不可复用；AccuratePathHeightResolver 内部走 AccurateHeightSampler 缓存，已避免重复后端采样。
  2. 收尾 structure pass 短路（beforeWater/afterRiver/afterLake 条件触发）与二次 buildSegments 的 equals 守卫在 0.8.243 已存在。
  3. river/lake 修复入口已按 intersectsRiver/Lake 预检、无冲突直接返回原实例。
  - 结论：finalizePath 的"看起来重复"均已具备确定性命中短路；两链语义不同（路径级 vs segment 中心级）且输入不同，没有可证明逐值一致的新去重点，故不强行合并（符合任务 B 约束"无法证明逐值一致就不改、写明原因"）。

## 任务 C：建筑规避前置 —— 新增可回退开关（完成）

- 文件：`config/sub/PlanningConfig.java`、`generation/InitialGenManager.java`、`fabric/.../ConfigScreenFactoryImpl.java`、两份 lang JSON。
- 现状（0.8.243）：含 protected-gap 的路已按 hints 只修冲突圆（非整条重跑），失败回退 protectedGapFallbacks 原路；"送局部"已是"规避后真绕不过才进"语义。
- 改动：新增配置 `localRepairOnlyOnUnavoidable`（默认 true = 现状语义不变）：
  - true（默认）：保持现有行为——含 gap 路 rebind 后进局部修复波次，尝试局部绕行；失败保留 gap 路。
  - false：初始批次后含 gap 路**直接保留**（resolved），不送局部修复波次，跳过整波（省墙钟）；局部绕行不再尝试。
  - 回退：配置改回 true 即恢复 0.8.243 精确行为。
- 预期影响（false 时）：局部修复 wave 的输入数下降、wallMs 缩短；代价是这些连接不做局部绕行，以"保留原始空位"为终点——这是该开关的语义（省时 vs 局部尝试），出问题改回 true 即可。
- 默认 true，故默认行为与 0.8.243 完全一致；5 项回归全绿。

## 新配置项汇总

| 配置 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `gradeRepairParallelism` | 0（auto） | [0, 核数] | 局部修复窗口线程池线程数；auto = max(4, min(核数, 推荐 worker))，核数封顶 |
| `localRepairOnlyOnUnavoidable` | true | boolean | true=含 gap 路进局部修复波；false=直接保留 gap 路、跳过局部波 |

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=60 |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。
- lang 两份 JSON 通过解析校验。
- 边界遵守：unknown 端点协商、出生点/村庄/命名端点表面锁、PROTECTED_GAP 高度流、剪尾主体、T5/T2/T4 均未触碰。

## 交接备注

- gradeRepairParallelism 的 auto 解析在类加载时执行一次（读取推荐 worker 预算）；若未来需要运行期热调，可改为懒加载 + 缓存失效，当前非必需。
- localRepairOnlyOnUnavoidable=false 是纯墙钟优化开关；建议在真实启动时对比 wave wallMs 与最终 retained 空位数，确认省时收益与空位代价符合预期后再决定是否改默认。
- 环境注意沿用上批：build.gradle 避免 UTF-8 BOM；JAVA_HOME=jdk-21（Gradle 8.8 不支持 Java 26）。


---

# RoadWeaver 0.8.245：shared-endpoint unknown 端点取低 + 合并后尾段裁剪

状态：**5 项回归全绿**（roadJUnitRegressionSuite tests=60 + whole-flow / tunnel-junction / topology-water / structure-avoidance 4 项模拟；另 sharedEndpointReuseRegression PASS）。版本号已从 0.8.244 递增到 0.8.245（build.gradle）。HEAD 基线 bce5f57（0.8.244）。

## 背景（真实日志证据，0.8.244）

- 端点 (97,281) 被日志标注 unknown，3 条路在此复用共享 trunk；低侧来路接入时"平地起桥"，交叉口"多一节"。
- 根因：SharedEndpointPathReuse 的 reuseSharedEndpointTails 在 detailed height/bridge/tunnel/construction 之前运行；unknown 端点高度直接取 primary 路径高度（非所有来路最低），mergeIntoSharedTrunk 刻意保留次路自身端点尾段 → 天然"多一节"。
- 边界：UnknownEndpointHeightRegistry（0.8.242 registry 侧）只对未复用的单条连接生效，覆盖不到 shared-endpoint 合并后的 trunk，故本次修复落在 SharedEndpointPathReuse 本身，未动 registry。

## 任务 A：unknown 共享端点取"多来路最低可达成高度"（完成）

- 文件：`planning/path/SharedEndpointPathReuse.java`
- 改动：
  1. `reuseSharedEndpointTails(level, ...)`（两处生产调用点均传 level）里，在 choosePrimary/merge 之前先扫描每个有 ≥2 条来路的端点，用 `Road.isUnknownStructureEndpoint(level, probe, from)` 判定是否 unknown（from = 该 probe 连接哪一端等于端点）。出生点、村庄、命名结构端点不进入该 pass（`isUnknownStructureEndpoint` 内部已排除村庄与出生点、只认 unknown id）。
  2. 对每个 unknown 端点收集所有来路的粗 path（直接用入参 result.paths()，未重新查 PlannedPathCache），`endpointApproachMinY` 取"端点站 + 向 far 侧最多 16 站"的最低 Y 作为该路可达成高度；统一高度 = 各来路最小值（迁就最低侧）。
  3. `lowerEndpointApproach` 把每条来路（primary 与 secondary 一视同仁）的端点站落到统一高度，并只降平滑（每站至多比前站高 1、永不抬升）；写回 rewritten，后续 merge 从同一高度起步。
  4. 用 `level != null` 门控——生产路径（两处调用都传 level）生效；纯内存测试（null 重载）不受影响、行为不变。
- 高度来源说明：统一高度 = 各来路"端点侧 16 站窗口最低地表高度"的最小值（纯几何、不查 registry、不查结构表面）。村庄/出生点/命名结构仍走原表面高度锁（未参与取低）。
- 验收：同端点的所有来路在端点处高度相同且不高于"最低来路可达成高度"，低侧不再平地起桥。

## 任务 B：合并后裁掉次路覆盖 trunk 的尾段（完成，带保守回退）

- 文件：`planning/path/SharedEndpointPathReuse.java`（mergeIntoSharedTrunk）
- 现状：合并保留 secondary 自身端点尾段（join 之后 append secondary[secondaryEnd+1..]），尾段若仍贴着 trunk 走廊就造成"多一节"。
- 改动：
  1. join 之后从 `tailStart = join.secondaryEnd()+1` 开始，用 `withinTrunkCorridor`（Chebyshev ≤ reuseDistance，扫描 primary[0..join.primaryEnd]）把"仍贴着 trunk 走廊的连续次路站"走完；
  2. 若 tailStart 落在 secondary 内部（存在真正离开走廊的站），则 trunk 直接 connect 到该站、裁掉中间重叠段（日志记录被裁区间）；
  3. **保守回退**：若整个尾段都不离开 trunk 走廊（tailStart 到末尾仍 hugging），合并会吞掉 secondary 自己的 far 端点、破坏连通——此时回退保留原行为（append 到尾）并 WARN，绝不因裁剪断连通；
  4. 裁剪只发生在 pre-detail 合并阶段（生成前），不动落盘后剪尾 RoadSnapService.trimSecondaryTailAtJunction（边界遵守）。
- 自检：裁剪只删"贴着 primary trunk 走廊"的站，不改变走廊外的 far 端点段，因此不会引入新的 overlapping-physical-spans（同一站只有一个物理类型的判定由后置 span 提取负责，本改动不产生重叠站）。若合并结果 < MIN_REUSABLE_STATIONS 仍返回 null（原守卫保留）。

## 测试

- `SharedEndpointPathReuseTest` 新增 2 条纯函数测试 + 2 个测试 seam（endpointApproachMinYForTest / lowerEndpointApproachForTest）：
  - `endpointApproachMinYScansTheNearWindow`：攀升来路的可达高度=自身低端；下降来路的可达高度=16 站窗口低端（100-32=68）。
  - `lowerEndpointApproachLowersOnlyTowardTheUnifiedHeight`：端点落到统一高度 80、每站至多比前站高 1、永不高于原平台 100。
- sharedEndpointReuseRegression PASS（含新增测试）；roadJUnitRegressionSuite tests=60 PASS。

## 诚实说明（覆盖边界）

- 现有 5 项模拟均跑在构造拓扑上（平地等高的测试几何），**未覆盖真实 (97,281) 这种"多路共享 unknown trunk + 低侧来路"的场景**；任务的 level 门控取低流程与 B 的走廊贴合裁剪在模拟里不会被触发（模拟 level=null → 任务 A 跳过；测试几何无 hugging 尾段 → 任务 B 走保留分支）。
- 因此不能声称"已保证 (97,281) 坐标正确"——需要真实初始化流程验证：观察日志 `RoadWeaver shared-endpoint unknown height unified ...`（A 触发）与 `RoadWeaver shared-endpoint trunk trim removed secondary stub ...`（B 触发），并核对端点处所有来路同一高度、无低侧起桥、无"多一节"。
- 若真实流程中出现 B 的 WARN（`kept overlapping tail: secondary never leaves the trunk corridor`），属保守回退路径（保连通），符合设计。

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=60 |
| `:common:sharedEndpointReuseRegression` | PASS（含新增 2 条） |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。
- 边界遵守：registry 侧（UnknownEndpointHeightRegistry.preplan/lockedHeight、RoadPlanningService.preplanUnknownEndpointHeights、Road.isUnknownStructureEndpoint、InitialGenManager preplan 调用）、出生点/村庄/命名结构表面锁、RoadSnapService.trimSecondaryTailAtJunction、T5/T2/T4/A/C、PROTECTED_GAP 高度流与干洼地桥判定均未触碰。


---

# RoadWeaver 0.8.246：final validation 补采按 chunk 复用缓存（后处理提速）

状态：**5 项回归全绿**（roadJUnitRegressionSuite tests=60 + whole-flow / tunnel-junction / topology-water / structure-avoidance 4 项模拟）。版本号已从 0.8.245 递增到 0.8.246（build.gradle）。HEAD 基线 89299f6（0.8.245）。

## 背景（真实日志证据）

- 每条路 PLANNED_OK 前，最终校验 CPU 补采缺失地形列：`final validation filling missing terrain columns: columns=4790 / 3226  backend=CPU`；corridorNewChunks=526 说明跨路重复采样大量新块。
- 降采样范围不减少该数值：补的是道路本身体积列的缺失，与采样范围无关。

## 任务 1：final validation 复用已采样列（完成，不改变几何）

- 文件：`features/path/pathlogic/core/FinalRoadStructureValidator.java`（prefetchMissingValidationColumns）
- 根因调查（已确认）：
  1. prefetch 用 `RoadConstructionVolumePlanner.terrainSamplePositions` + 全部 segment.positions() 生成候选列；`sampler.cachedSamples` 判定 miss 后逐列 `samplePositions(missing)` 补。
  2. **CPU backend 的 `samplePositions` 只按请求列局部采样、不填 chunkCache**（`CpuAccurateHeightBackend.samplePositions` → noiseChunkSampler.sampleChunkColumns 局部列）；而 `sampleChunks` 整块采样才填 chunkCache，之后 `cachedSample` 可从 chunk 推断任意列命中（`AccurateHeightSampler.cachedSample` → sampleFromChunk）。
  3. 因此 missing=4790 的列所在 chunk 从未被整块采过 → 同 chunk 其它列持续 miss、跨路无法共享（corridorNewChunks=526 的直接来源）。
  4. **逐值一致前提成立**：`NoiseChunkHeightSampler.sampleChunk(chunkX,chunkZ)` 内部即 `sampleChunkColumns(chunkX, chunkZ, null)`——全列与局部列同一实现、逐列输出一致。chunk 级采样只改缓存层，不改几何。
- 改动：
  - missing 列先按 chunk 分组：某 chunk 缺 ≥4 列 → 整块 `sampler.sampleChunks(wholeChunks)`（填 chunkCache，本路 volume walk 全命中 + 同区域后续路从 chunk 缓存/持久层命中）；零散 chunk（<4 列）保留逐列 `samplePositions` 兜底。
  - 新增日志 `final validation chunk prefetch: wholeChunks=... coveredColumns=... sparseColumns=...` 便于观测。
- 边界：未触碰生成几何/高度/跨度判定；未动 UnknownEndpointHeightRegistry、smoothProtectedGapApproaches、gradeRepairParallelism、localRepairOnlyOnUnavoidable、T5/T2/T4；精确采样默认仍 CPU（未加 GPU 配置、未改默认）。
- **columns 数值预期**：同一条路内，被整块采样覆盖的列从"逐列 miss"变为 chunk 命中（volume walk 不再二次补采）；**跨路**在真实流程中，同区域后续路的 cachedSamples 会从 chunk 缓存/持久层命中，columns 应显著下降。**模拟说明见下**。

## 任务 2：whole-path grade repair 快速路径核实（已存在，未改动）

- `AccurateGradeRepairPlanner.repair` 开头已有快速路径（0.8.244 加入）：`findFirstViolation(accuratePath) == null`（纯 Y 比较、零采样）直接返回原路径——稳定路线不进批收集/池提交/A*。
- 核实结论：稳定路的 gradeRepair 调用已接近零 A*；不改变任何有违例的修复结果。本批未放宽任何高度判据（不引入 invalid-height-step）。

## 诚实说明（覆盖边界）

- 现有 5 项模拟跑在构造拓扑上，**不触发"大量 volume 列逐列补采"的真实场景**（模拟无 ServerLevel，prefetch 走 level==null 早退或缓存全命中），无法在模拟里观测 columns 数值下降。
- 因此不能声称"已保证某数值"——需在真实初始化流程验证：观察每条路 `final validation chunk prefetch: wholeChunks=...` 日志出现（本路覆盖生效），并对比前后两条日志 `final validation filling missing terrain columns: columns=N`：同区域后一条路的 N 应明显低于前一条（chunk 缓存命中），wallMs 应下降。
- 若真实流程未见整块采样触发（wholeChunks=0 全 sparse），说明缺列太零散（每 chunk <4 列），columns 降幅有限——可调低阈值（如 ≥2 列即整块）或改在 volume 轨迹首次采样时就整块采，需真实数据支撑再调。

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=60 |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。
- 改动文件仅 2 个：build.gradle（版本）+ FinalRoadStructureValidator.java（prefetch 逻辑）。


---

# RoadWeaver 0.8.247：全源码性能/质量审计 + 安全算法优化

状态：**5 项回归全绿**（roadJUnitRegressionSuite tests=60 + whole-flow / tunnel-junction / topology-water / structure-avoidance 4 项模拟）。版本号已从 0.8.246 递增到 0.8.247（build.gradle）。HEAD 基线 3085482（0.8.246）。

## 阶段 1：系统审计清单（common/src/main/java，410 个文件，按收益/风险排序）

### 已实施（本次唯一安全落地点）

- **[O1] PathSpanExtractor xz 索引化（已实施，不改变几何）**
  - 文件：`pathfinding/PathSpanExtractor.java`
  - 根因：`normalizeTunnelSpans` + `resolveOverlappingWaterSpans`（T5 overlap 互斥自检，0.8.243 新增）在 span 循环内反复调用线性 `indexOfXz(centers, ...)`（O(spans × centers)），隧道/桥/海底隧道转换路径每条路都触发。
  - 方案：方法入口建一次 `xzIndex(centers)`（HashMap，x/z → 首现 index），新增 `indexOfXz(Map, target)` 重载；`resolveOverlappingWaterSpans` 增加带索引重载。
  - 预计收益：该路径从 O(spans×centers) 降到 O(spans+centers)；收益随 span/centers 数量线性放大，属微优化但纯确定性。
  - 风险：无——索引 `putIfAbsent` 保留"首现"语义与原线性扫描一致，逐值相同。
  - 回归验证：能（5 项回归全绿）。

### 已审、无需改动（此前批次已覆盖或本无问题）

- **finalizePath 双修复链**：路径中心链（resolve(rawPath)）与施工中心链（resolve(constructionCenters)）输入点位集不同（A* 路径点 vs segment 中心），结果不可复用；采样走 AccurateHeightSampler 缓存已去重。收尾 structure pass 短路、二次 buildSegments equals 守卫、河/湖入口预检均已存在。**建议不合并**（两链语义不同，合并属高风险几何重构）。
- **whole-path grade repair 快速路径**：`AccurateGradeRepairPlanner.repair` 开头 `findFirstViolation==null`（纯 Y 比较、零采样）即返回，稳定路不进 A*。已确认拦截。
- **采样列/chunk/持久层缓存**：AccurateHeightSampler 按 level 共享单例（IdentityHashMap），列缓存(8192)+chunk 缓存(256)+持久层三层；0.8.246 已将 final validation 补采升级为 chunk 级预取填 chunkCache，跨路复用已就位。
- **OpenCL 采样去重**：`OpenCLAccurateHeightBackend.samplePositions` 先 `uniquePositions` 去重再提交，无同列重复提交；GPU 默认关闭（精确采样默认 CPU，符合边界）。
- **RoadSnapService 交叉口 O(n²)**：配对循环已用 `RoadBounds.gap() > SPATIAL_REJECT_MARGIN` bbox 剪枝（0.8.243 就位），且段内 span 端点已用 HashMap 索引，无线性残留。
- **StructureAvoidanceService snapshot**：`getStructureSnapshot` 走内存态（非逐读盘）；其内部有预测 tile 缓存与 prepared 缓存，未发现无谓重复。
- **CoarseTerrainTileCache 锁竞争**：已 16 分片 LRU（此前批次），无单锁瓶颈。
- **线程泄漏**：全仓 `new Thread(` 仅 7 处，均已 `setDaemon(true)`（PlannedPathCache / StructureAvoidanceService / CoarseTerrainRegionSampler / AccurateGradeRepairPlanner / ThreadPoolManager 等），无非 daemon 泄漏。
- **无超时 join/get**：`InitialGenManager:733/939` 均在 `isDone()`/`completed.take()` 之后调用 get（非无超时阻塞）；`CoarseTerrainRegionSampler:231` 在 `completion.take()` 后 get；`AccurateGradeRepairPlanner:175` 是 GRADE_REPAIR_POOL future（有异常降级）；其余 join 点（StreamsReflowingCompat:292、StructureAvoidanceService 区块预取）已在 0.8.244 批次做过超时接管改造，无残留。

### 审计发现、建议不实施（风险 > 收益，留档）

- **[S1] StructureFileStorage.state() 每次同步读盘**：`getStructureSnapshot` 高频调用（端点判定、快照预取），`state(level)` 每次 `readFileState` 读 JSON。看似可加内存缓存，但**现状每次返回新鲜列表对象（快照隔离语义）**，缓存化会引入共享可变列表的别名/竞态，且 mutation 点分散（多处 `save(level,state)`），缓存失效易漏。**建议不实施**；若未来需要，应改为"不可变快照 + 版本号失效"，而非简单缓存。
- **[S2] computeTargetY×4 / refineTunnelSpans×3 / extractSpans×2+（finalizePath 桥填充循环）**：这些是几何结果依赖（每次输入 spans/targetY 已变），去掉会改变结果。**建议不实施**。
- **[S3] RoadDataValidator.validateFinalRoad 多处重复调用**：校验结果依赖 RoadData 内容（不可变 record），但调用点上下文各异（结构/水/交叉口校验、snap 候选等），memo 需按"对象身份+配置快照"键控且要防配置热改，复杂度 > 收益。**建议不实施**。
- **[S4] 空 catch {} 共 17 处**：多为客户端渲染/文件 IO 兜底（ClientMapTileTextureCache、FileStorageIO、png writer 等），吞异常语义为刻意降级，不在热路径；仅记录不修改。

## 阶段 2 实施纪律与结果

- 一次只改一个独立优化点（O1），改完立即编译 + 5 项回归，全绿后锁定。
- 未为提速放宽任何高度/跨度/桥/隧道判据；未触碰边界项（UnknownEndpointHeightRegistry/preplan、gradeRepairParallelism+localRepairOnlyOnUnavoidable、SharedEndpointPathReuse unknown 取低+尾段裁剪、FinalRoadStructureValidator chunk prefetch、T5/T2/T4、交叉口剪尾主体、PROTECTED_GAP 高度流、精确采样默认 CPU）。

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=60 |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。

## 诚实说明（几何 vs 提速，分开写）

- **能证明"不改变几何"**：O1 是纯查找结构替换（`putIfAbsent` 保留首现语义，与原线性扫描逐值一致），5 项回归（含 whole-flow / topology-water 的 span 几何断言）全绿，证明最终 span/targetY/segments 与 0.8.246 一致。
- **不能证明"提速多少"**：现有 5 项模拟跑在构造拓扑上、路径短、span 少，O1 的收益在模拟里可忽略；真实长路径（数百上千站）下 O(spans×centers)→O(spans+centers) 的收益需在真实初始化流程观测后处理墙钟确认。其余建议项（S1–S4）的收益同样需真实场景数据佐证，故均留档未实施。

## 交接备注

- 本批仅 2 个文件改动：build.gradle（版本）+ PathSpanExtractor.java（xzIndex 优化）。
- 若后续要动 S1（snapshot 缓存）或 S3（validate memo），务必走"不可变快照 + 版本号失效 / 对象身份+配置指纹键控"，并补并发测试。

---

# 0.8.248 —— StructureFileStorage 只读快照读盘缓存（S1）

> 基线：0.8.247（commit `49c1f8a`，5 项回归全绿）。本批把 0.8.247 审计中留档的 S1 落地：去掉 `getStructureSnapshot(level)` 的重复读盘，同时保持"每个调用者拿到一致快照"的隔离语义。

## 目标与方案

`getStructureSnapshot(level)` 是 `public static synchronized`，此前每次调用都 `state(level) → readFileState` 读 JSON，端点判定（`Road.isSharedElevationEndpoint` / `isUnknownStructureEndpoint`）、快照预取、unknown 端点判定、map 采集等高频调用它。

- **只缓存"只读且不可变"的 `StructureSnapshot`，不缓存可变 `StateData`**（mutation 会污染）。
- 新增 `VersionedSnapshotCache<K, S>`：按 key（维度 `ResourceKey<Level>`）持有一个递增版本号 + 不可变快照 entry；`getOrLoad` 命中同版本直接复用，否则跑 loader 重建；`bump` 版本 +1 并丢弃旧 entry。
- `getStructureSnapshot` 改为 `SNAPSHOT_CACHE.getOrLoad(dimension, () -> { state(level) 读盘 → new StructureSnapshot(...) })`。

## 缓存结构（不可变化处理）

- **`StructureSnapshot` 改为真正的不可变**：构造器里 `locations` 用 `StructureLocationData.frozen(...)` 包装，`sources` 用 `Map.copyOf(...)`。
- **`StructureLocationData` 新增 `frozen(...)` 工厂 + 私有 `FrozenList`**（继承 `AbstractList`，只实现 `get/size`，`add/remove/set` 抛 `UnsupportedOperationException`）。普通构造器行为不变（仍 `new ArrayList<>(...)` 防御拷贝），只有 `frozen(...)` 走不可变包装——不影响现有可变实例、Codec 与 `addStructure/addStructureInfo`。
- 快照内部列表现在对调用方是**只读的**：外部 `.structureInfos().add(...)` 会抛异常（原语义是每次新建可变列表，调用方理论上可改但从未这样做——全仓 grep 确认所有 `getStructureSnapshot` 消费点都只读迭代，无任何 `.add/.remove/.removeIf` 到快照列表上）。

## 版本失效点（全量清单）

所有落盘都经唯一私有入口 `save(ServerLevel, StateData)` 汇聚，`save` 在 `writeStringAtomic` **成功之后** `SNAPSHOT_CACHE.bump(level.dimension())`。因此以下所有 mutation 点自动失效，无需逐一加码：

| 公开 mutation 方法 | 落盘入口 |
|---|---|
| setStructureLocations | save |
| setStructureConnections | save |
| mergeStructureConnections | save |
| pruneUnknownNonSpawnEndpoints | save |
| recordFailedFallbackAttempt | save（条件命中时） |
| suppressConnection | save |
| allowConnection | save（移除成功时） |
| setPlannedTileKeys | save |
| setPlannedTileCenters | save |
| addStructures | save |
| ensurePolicy | save（hash 变化时） |
| claimScanTile | save |
| markScanTileDone | save |
| releaseScanTile | save |
| claimScanWindow → claimScanKey | save |
| markScanWindowDone → markScanKeyDone | save |
| releaseScanWindow → releaseScanKey | save |

- grep 确认 `state.json` 的写盘只有 `StructureFileStorage.save` 一处（`FileStorageIO.writeStringAtomic(statePath(level), ...)`），无绕过 `save` 的散落写盘点，故无遗漏失效风险。
- mutation 方法本身仍走 `state(level)` 读盘（频率低，可接受），未为它们加缓存。
- **生命周期**：`CacheManager.onServerStarted()` 与 `onServerStopping()` 均新增 `StructureFileStorage.resetSnapshotCache()`，防止跨世界维度 key 复用时残留 stale 快照。

## 隔离语义

- 快照不可变 → 多处共享同一实例安全。
- `bump` 与 `getOrLoad` 都在 `VersionedSnapshotCache` 的实例锁内（synchronized），有 happens-before 保证，不会读到半写状态；且写盘成功才 bump，失败抛异常则缓存保持旧版本（下次仍会重建，安全）。
- `sourceAt / isKnownId / isVillageId` 等只读方法不受影响。

## 并发测试（新增，注册进 roadJUnitRegressionSuite）

`common/.../persistence/files/StructureFileStorageSnapshotConcurrencyTest`（纯内存，无 ServerLevel，确定性）：

1. `concurrentGetReturnsConsistentSnapshot`：8 线程并发 get 同一版本，内容一致且 loader 只执行 1 次（读盘去重）。
2. `saveBumpsVersionAndNextGetSeesFresh`：先 get 缓存 → bump 新状态 → 再 get 读到新状态（loader 执行 2 次）。
3. `noStaleLeakAcrossMutation`：mutation 后旧缓存实例不被返回（版本不匹配）。
4. `immutableSnapshotSharedAcrossThreads`：共享不可变快照被 8 线程并发迭代不抛 CME；并断言 `.structureInfos()/.structureLocations()/.sources()` 的写操作抛 `UnsupportedOperationException`。

`roadJUnitRegressionSuite` 从 tests=60 增至 **tests=64**。

## 验证（本机，仅跑指定任务，JAVA_HOME=jdk-21）

| 任务 | 结果 |
|---|---|
| `:common:compileJava` / `:fabric:compileJava` | BUILD SUCCESSFUL |
| `:common:roadJUnitRegressionSuite` | PASS tests=64（含新增 4 项并发测试） |
| `:common:roadWholeFlowSimulation` | PASS |
| `:common:tunnelJunctionGradeRegression` | PASS |
| `:common:roadTopologyWaterSimulation` | PASS |
| `:common:structureAvoidanceRegression` | PASS |

- 未跑 `:common:test`。

## 诚实说明

- **能证明的**：版本失效逻辑与"不可变共享"正确、并发一致（新增并发测试全绿）、5 项回归全绿（证明未改变任何结构快照内容/端点判定/预取/unknown 端点行为）。
- **模拟环境无法证明的**："读盘次数下降 / 加载变快"需要在真实初始化流程观测——可用 `--debug` 或临时日志统计 `getStructureSnapshot` 实际读盘（`readFileState`）次数，对比 0.8.247 基线。本批只保证正确性与隔离，不虚构墙钟收益。
- 改动文件：`StructureFileStorage.java`（缓存接入）、`StructureLocationData.java`（frozen 不可变）、新增 `VersionedSnapshotCache.java`、`StructureFileStorageSnapshotConcurrencyTest.java`、`RoadJUnitRegressionSuite.java`（注册）、`CacheManager.java`（生命周期）、`build.gradle`（版本号 0.8.248）。
