# RoadWeaver 深度代码审查报告（只读审查）

- 审查对象：`RoadWeaver-0.8.160-incremental-reconcile`（Minecraft 1.21.1，Fabric + Architectury）
- 审查方式：只读源码审查，未修改任何源码/配置；本报告是本次审查唯一产出文件。
- 审查范围：`RoadTerrainType` / `RoadTerrainClassifier` / `PlanningConfig.TerrainProfileMode`、`RoadGradePlanner`、`RawPathSearch`、`CoarseHeightAwarePlanner`、`AccurateGradeRepairPlanner`、`PathSpanExtractor`、`Road`、`StructureAvoidanceService`、`RoadPlanningService`、`PlannedPathCache`(+`PlannedPathKey`/`PlannedPathFingerprintService`)、`RoadPathCalculator`、`ThreadPoolManager`、`RuntimeLoadPolicy`、`CoarseTerrainTileCache`、`ConfigService` 及相关调用方（`InitialGenManager`、`RoadGenerationService`、`RoutePrecisionRetryPolicy`）。
- 说明：环境内无 git 可执行文件，未能基于 git 历史辅助定位最近改动；行号以当前工作区源码为准，均为大致行号。

---

## 结论摘要

- **P0：未发现。** 没有会直接崩溃游戏/服务器或损坏持久化数据的问题（候选 NPE 被上层 `catch (Throwable)` 兜住，降级为 P1）。
- **P1：3 项** —— 一处确定性 NPE（Streams 河流晋升路径）、共享线程池嵌套等待 + 交互期缩池导致的运行期卡死链、切/填方限值配置双轨且一处强制下限覆盖用户配置。
- **P2：14 项** —— 硬编码阈值漂移、重复的昂贵的等高线搜索、死代码/死参数、端点锁高采样点偏差、`hasBuildingConflict` 指纹步长错配（历史 "missing persisted route path" 的同族变体）、Streams unknown 误判水、线程池语义地雷、无超时 join、性能热点（`ConfigService.get()` 在最内层循环）等。

---

## P1（严重，建议尽快修复）

### P1-1 确定性 NPE：先置 null 再解引用（Streams 河流晋升路径）

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/Road.java`，约 293–301 行
- **问题**：在 `ordinary_construction_in_water` 且 `source().startsWith("streams_channel")` 的晋升分支里：
  ```java
  waterValidation = crossedValidation;
  waterCollision = null;                       // ~298 行
  LOGGER.warn("RoadWeaver promoted Streams river collision to {} connection={}->{}",
          crossingTypeAtCollision(crossed, waterCollision.segmentIndex()), ...);  // ~300 行
  ```
  日志参数在 `waterCollision` 被置 `null` **之后**求值，`waterCollision.segmentIndex()` 必然 NPE。
- **为什么是问题**：这是一条**可确定性触发**的成功路径——每当一次 Streams 河道碰撞被成功晋升为 crossings（`crossedValidation.safe()` 且最终校验通过），NPE 就把整次 `generateRoad` 打掉。异常被 `RoadGenerationService.generateTaskInternal` 的 `catch (Throwable)`（约 432 行）兜住记为 FAILED，于是：晋升成果丢失 → 重试/局部修复波次重新生成 → 若地形不变则再次触发同一 NPE → 该路线反复烧掉预算后落进拓扑候选替换。不会崩服，但会把一类"本来已经修好的路"稳定变成失败/重试，且日志里只留下误导性的异常栈。
- **修复方向**：在置 `waterCollision = null` **之前**先计算 `SpanType promotedType = crossingTypeAtCollision(crossed, collisionIndex)` 并存入局部变量；日志改用该局部变量。补一条回归单测：构造 `FinalRoadWaterValidator` 返回 `ordinary_construction_in_water` + `streams_channel` source 的场景，断言晋升分支可完整执行。

### P1-2 共享线程池嵌套等待 + 交互期把池缩到 1：运行期道路生成卡死链

- **文件**：
  - `common/src/main/java/net/shiroha233/roadweaver/runtime/ThreadPoolManager.java`，375–383 行（`executorFor`：`PLANNING/COARSE/GENERATION/COORDINATOR/IDLE/POSTPROCESS` 全部落到**同一个** `sharedExecutor`）
  - `common/src/main/java/net/shiroha233/roadweaver/runtime/RuntimeLoadPolicy.java`，181–203 行（`recommendedRoadWorkerThreads`：玩家移动/交互时返回 **1**；`refreshRoadWorkerBudget` 每 250ms 应用一次）
  - `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/pathfinding/RoadPathCalculator.java`，1073–1095 行（`refreshPlanningSnapshot` → `awaitPlanningSnapshot` 的 `future.get()` 无超时）
  - `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/AccurateGradeRepairPlanner.java`，729–747 行（`awaitLocalStructureCircle` 的 `future.get()` 无超时）
  - `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/StructureAvoidanceService.java`，646、774 行（预测任务经 `ThreadPoolManager.supplyAsync(predictionRole, ...)` 提交回同一个共享池）
- **问题**：道路生成任务（运行期走 `GENERATION`/`IDLE` 角色 → 共享池）在执行中同步 `future.get()` 等待结构预测/快照任务，而后者作为 `PLANNING/COARSE` 角色**排在同一个共享池队列里**。`RuntimeLoadPolicy` 在玩家交互期间把共享池目标线程数压到 1：此时唯一的 worker 正被道路生成任务占着并阻塞在 `future.get()` 上，队列里的预测子任务永远不会被调度 → 整条生成链停摆。
- **为什么是问题**：代码注释（`resolveRuntimeThreadTarget`，ThreadPoolManager 177–186 行）表明作者已经意识到"worker 等待嵌套 planning 任务时池被缩到 1 会永久卡住区域"，并对 **Chunky supply** 路径做了 `max(adaptive, supply)` 兜底；但**玩家交互路径**（`INTERACTION_ACTIVE || RuntimePlayerActivity.isActive() → return 1`）没有同样的兜底。后果：玩家一边移动一边靠 IdleRoadGenerationService 补路时，道路生成在交互期间完全冻结（这恰是玩家最需要路的时刻）；交互结束后 `recommendedRoadWorkerThreads` 恢复、池扩容、子任务得以运行——所以是**自愈型卡死**而非永久死锁，但持续时长等于玩家连续活动时长，且无任何超时保护。
- **修复方向**（任选其一或组合）：
  1. `recommendedRoadWorkerThreads()` 的下限设为 2（一个给道路任务、一个给其嵌套的 planning 子任务），与 `resolveRuntimeThreadTarget` 的注释意图对齐；
  2. 所有"道路任务内等待结构预测"的点改为带超时的 `get(timeout)` + 超时后降级（如沿用既有 snapshot 或跳过该次绕行），或把嵌套子任务路由到独立的小型 helper 池；
  3. 至少在 `RuntimeLoadPolicy` 压池前检查 `ThreadPoolManager` 是否存在活跃的 GENERATION/IDLE 任务占用。
- **附注**：启动期不受影响——`InitialGenManager.isActive()` 时 `beforeTask` 直接放行（RuntimeLoadPolicy 129 行），且启动时池被放大（`setSharedRuntimeThreads(max(configured, roadWorkers))`）。风险集中在运行期/闲置补路路径。

### P1-3 切/填方限值配置双轨，`ordinaryFillLimit()` 强制下限 5 覆盖用户配置

- **文件**：
  - `common/src/main/java/net/shiroha233/roadweaver/pathfinding/PathSpanExtractor.java`，1244–1253 行：
    ```java
    return Math.max(5, Math.min(16, ConfigService.get().planning().surfaceFillMaxBlocks()));
    ```
  - `common/src/main/java/net/shiroha233/roadweaver/config/sub/PlanningConfig.java`，43–44、81 行（`surfaceFillMaxBlocks` 默认 2、sanitize 到 `[0,16]`）
  - `common/src/main/java/net/shiroha233/roadweaver/config/sub/TerrainProfileConfig.java`（`plainSurfaceFillMaxBlocks=2`、`mountainSurfaceFillMaxBlocks=1`、对应 Cut 值 5/3）
  - `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/RoadGradePlanner.java`，377–415 行（使用的是 **TerrainProfileConfig** 的山地/平原分档值，再叠加 TerrainProfileMode 调整）
- **问题**：同一个"普通道路填方上限"概念存在两套互不相通的配置源：
  1. `RoadGradePlanner`（决定目标 Y 能高出地形多少）读 `TerrainProfileConfig.plain/mountainSurfaceFill/CutMaxBlocks`；
  2. `PathSpanExtractor.ordinaryCutLimit/ordinaryFillLimit`（决定干洼地何时不再按普通填方处理、升级为 crossing/bridge）读 `PlanningConfig.surfaceCut/FillMaxBlocks`，且 `ordinaryFillLimit` 里 `Math.max(5, ...)` 把 `[0,4]` 区间的配置值**全部强制变成 5**。
- **为什么是问题**：
  - 玩家把 `surfaceFillMaxBlocks` 设为 0–4 时，PathSpanExtractor 这一侧的实际生效值恒为 5，配置被静默忽略（与同文件 `MAX_ORDINARY_FILL_DEPTH = 5` 的注释"必须与普通道路填方上限一致"自相矛盾）；
  - 玩家改 `TerrainProfileConfig` 的填/切限值不影响 PathSpanExtractor 的桥判定阈值，改 `PlanningConfig` 的不影响 RoadGradePlanner 的实际填切——两个 UI 旋钮各自控制半套逻辑，行为难以预期；
  - `BridgeFirst/LightCut` 模式下 RoadGradePlanner 把填方压到 ≤1，但 PathSpanExtractor 的判定阈值仍是 5，两处对"多深算 unsafe fill"的认知进一步脱节。
- **修复方向**：合并为单一来源（推荐 `TerrainProfileConfig`，因其有山地/平原分档），PathSpanExtractor 与 RoadGradePlanner 共享同一组 getter；`Math.max(5, ...)` 若是刻意的"干洼地历史行为下限"，应改为独立命名常量并在配置注释/UI 里如实说明，而不是借 `surfaceFillMaxBlocks` 之名。

---

## P2（中等：正确性边界、可维护性、性能）

### P2-1 硬编码隧道阈值 7 与可配置 `mountainOverburdenBlocks` 脱节

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RawPathSearch.java`，180–186 行
- **问题**：`hasUnbuildableCoarseTransition` 用 `terrain.height(...) - point.getY() >= 7` 判定"隧道态"，7 是 `mountainOverburdenBlocks` 的默认值但不是引用配置；同文件的坡度上限 `0.50D` 也是硬编码。
- **为什么是问题**：玩家把 `mountainOverburdenBlocks` 调到 10+ 时，粗规划阶段仍按 7 判定"不可行隧道过渡"，与 `RoadGradePlanner.tunnelCoreOverburden()`（读配置，417–423 行）和 `PathSpanExtractor.mountainOverburden()`（1228–1234 行，读配置）不一致，触发高度感知重寻路的时机漂移。
- **修复方向**：改用 `RoadGradePlanner.tunnelCoreOverburden()`（或直接读配置）；坡度上限如需可配再提升为配置项。

### P2-2 `wantHeightAware` 分支里重复执行完全相同的昂贵等高线搜索

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RawPathSearch.java`，63–64 行 vs 92–93 行
- **问题**：`MountainSwitchbackPlanner.replaceIfSafer(result.rawPath(), start, end, terrain, config, true)` 以**完全相同的参数**被调用了两次（第二次在 `wantHeightAware` 分支内）。该函数内部是状态上限 26 万、时限 1.5s 的等高线搜索。
- **为什么是问题**：若 `replaceIfSafer` 是确定性的（从代码看无随机源），第二次调用结果必然与第一次相同，`reroutedMode` 与 `modeRoute` 相同 → `hasUnbuildableCoarseTransition(reroutedMode)` 依旧为 true → 每次都白跑一次重型搜索后走到 `LOGGER.warn` 分支。
- **修复方向**：缓存第一次的结果直接复用；或删除该"reroute 重试"块（其意图已由紧随其后的 `CoarseHeightAwarePlanner.find` 分支承担）。

### P2-3 `hasBuildingConflict` 用原始步长查询，局部修复波次中恒 miss（"missing persisted route path" 的静默变体）

- **文件**：
  - `common/src/main/java/net/shiroha233/roadweaver/planning/RoadPlanningService.java`，103–108 行（`PlannedPathCache.find(..., config.pathfindingCost())`，**未** `withAStarStep`）
  - `common/src/main/java/net/shiroha233/roadweaver/generation/InitialGenManager.java`，308–313 行（局部修复波次内调用 `hasBuildingConflict`）
  - 对照：`PlannedPathFingerprintService.create`，48 行（指纹包含 `effectiveAStarStep()`）
- **问题**：局部修复波次中路线以 step=2 重绑定（`rebindGeneratedRouteForRetry` → `withAStarStep(2)`），而 `hasBuildingConflict` 以配置原始步长（默认 8）查询 → 指纹不匹配 → 内存/磁盘双 miss → 返回 `List.of()` → `hasBuildingConflict` 恒 false。
- **为什么是问题**：`InitialGenManager` 用它筛选 `buildingConflictRoutes` 决定哪些路线进入拓扑候选替换。漏检意味着真正压在建筑上的路线不会走候选替换通道，只能靠后续局部修复兜底，多烧一轮波次甚至以保护空位收场。它与已修复的历史 bug（`Road.java:901-910` 注释所述 "missing persisted route path"）同族——**查询键与注册键的步长纪律**只要再漏一处就会复发；此处是当前仍存活的一处。
- **修复方向**：给 `hasBuildingConflict` 增加 `step` 参数，调用方传入与重绑定一致的 `localRepairBatchSteps(...).get(0)`；或提供一个"按任意步长取最近指纹"的查询 API，彻底消除步长纪律依赖。
- **备注（本项的正面结论）**：主链路本身已核实一致——`rebindGeneratedRouteForRetry`（`RoadPlanningService.java:127-146`，`withAStarStep(step)` 注册）↔ `refineInitialRoutesAsync` 查询（`RoadPlanningService.java:569-571`，`withAStarStep(step)`）↔ `generateInitialRoadAtStep` 的 localOnly 预检（`RoadGenerationService.java:547-558`）↔ `generateTaskInternal` 把 `precisionStep` 写回 `genCfg.pathfinding()`（`RoadGenerationService.java:424-428`）↔ `Road.generateRoad` 的 `PlannedPathCache.find(level, connection, ..., genConfig.pathfinding())`（`Road.java:103-107`）。`RoutePrecisionRetryPolicy.localRepairBatchSteps` 恒返回常量 2，注册与查询两端取值一致。**当前主路径不会再现 "missing persisted route path"**，但该不变量靠多处手工对齐维持，脆弱（见上一条）。

### P2-4 共享端点锁高采样的是"裁剪后首/末 segment 中心"，不是端点本身

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/Road.java`，664–706 行（`startCross = centers.get(0)`、`endCross = centers.get(centers.size()-1)`）与 757–771 行（`crossingPreciseHeight`）
- **问题**：端点锁定高度来自对 `centers.get(0)` / 末位 center 的精确采样，而 `centers` 经过 `StructureRoadOffsetService.trimPathNearStructure` 裁剪，首/末中心与结构端点之间有几格~几十格偏移，且**随来路方向不同而不同**。
- **为什么是问题**：`isSharedElevationEndpoint` 的设计目标（`Road.java:773-778` 注释）是"村庄/出生点/结构端点的每条来路都锁到同一地表高度"。两条从不同方向进同一村庄的道路会在不同坐标上做精确采样——平地村庄结果一致，**坡地村庄边缘两条路可能锁出 1–3 格高差**，在村庄入口形成台阶，恰好违背该特性的承诺。
- **修复方向**：锁高锚点改为 `connection.from()/to()` 本身（两条路共享同一坐标 → 同一采样 → 同一高度）；`crossingPreciseHeight` 已支持单列精确查询，直接传端点即可。若担心端点在结构内部，可改为"端点 + 固定小半径窗口的最小地表高"这类方向无关的量。

### P2-5 Streams 查询 `isUnknown` 一律按水处理：系统性失败时整条路被拉成巨型桥

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/pathfinding/PathSpanExtractor.java`，1013–1020 行
- **问题**：`bridgeWaterSample` 遇到 `stream.isUnknown()` 返回"水，水面=海平面，河床=海平面-1"。单列失败时保守处理合理；但当 Streams 数据面整体不可用/失败（会话 `isFailed()`）时，`extractSpans`（84–123 行）仍会逐列调用，`collectBridgeSpans` 的水掩码全真 → 全路线被并为一个跨整条路的 bridge span。
- **为什么是问题**：下游 `BridgeDeckProfile.findStableBankRange` 会在整条路上找"稳定河岸"，产生一条无意义的高架桥计划，且日志洪水。修复 `repairLake/repairRiver` 侧有 `isAvailable()` 预检（`RoadPathCalculator.java:959-961`），`extractSpans` 没有。
- **修复方向**：`extractSpans` 入口对 `streams.isFailed()` 会话退化为"非河道"列（或跳过 Streams 通道判定，仅用地形水掩码）；`isUnknown` 保留给单列级失败。

### P2-6 `ThreadPoolManager.RoleExecutorService.shutdown/shutdownNow` 会关闭**全部**池

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/runtime/ThreadPoolManager.java`，583–592 行
- **问题**：任意一个角色 executor 上调用 `shutdown()`/`shutdownNow()` 都会触发 `ThreadPoolManager.onServerStopping()`，把十个池全部 `shutdownNow`。
- **为什么是问题**：`RoleExecutorService` 实现了 `ExecutorService`，任何第三方/库代码（或未来重构）对单个 executor 调用 shutdown 都会误杀整个线程池体系，且没有日志提示。
- **修复方向**：单角色 shutdown 做成 no-op（或只拒绝新任务），全量关闭只走 `onServerStopping()` 生命周期钩子。

### P2-7 无超时的 in-flight `join()`：owner 卡死则等待者全部无限阻塞

- **文件**：
  - `common/src/main/java/net/shiroha233/roadweaver/pathfinding/terrain/region/CoarseTerrainTileCache.java`，73–83 行（`existing.join()`）
  - `common/src/main/java/net/shiroha233/roadweaver/pathfinding/cache/AccurateHeightSampler.java`，566–571、622–635 行（`joinWaiting` / `joinWaitingTiles`）
- **问题**：两者都是"in-flight 合并 + join 拥有者 future"模式。正常路径下 owner 总会 `complete/completeExceptionally`（含 `catch (Throwable)` 兜底）；但若 owner 线程**卡住不返回**（例如 OpenCL native 调用挂起、底层世界生成/区块加载长时间阻塞），等待者没有超时，会集体挂在 `join()` 上。
- **为什么是问题**：与 P1-2 叠加时会放大为整池占满；单独发生时也难以从日志定位（所有线程都停在 `join`，无 owner 侧心跳）。
- **修复方向**：`join(orTimeout)` / `get(timeout)` + 超时后重试成为 owner 或返回失败；至少给 in-flight 表加"owner 线程存活检测"。

### P2-8 `MountainSwitchbackPlanner` 状态预算硬编码，不读 `heightAwareStateLimit`

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/planning/terrain/MountainSwitchbackPlanner.java`，约 82–84 行（`findContourRoute(start, goal, terrain, config, 260_000, 1_500_000_000L)`）
- **问题**：`PlanningConfig.heightAwareStateLimit`（sanitize `[50_000, 1_000_000]`）只在 `RawPathSearch` 调 `CoarseHeightAwarePlanner.find` 时生效（`RawPathSearch.java:105-107`）；盘山道等高线搜索的状态上限固定 260_000。
- **为什么是问题**：玩家调大 `heightAwareStateLimit` 期望"更充分的高度感知搜索"，但盘山道路径不受控；两个 3D 搜索的预算语义不一致。
- **修复方向**：该处改读 `ConfigService.get().planning().heightAwareStateLimit()`（或独立配置项），与 `CoarseHeightAwarePlanner` 对齐。

### P2-9 `applyCoarseTerrainModes` 用 `DEFAULT_BRIDGE_MIN_WATER_DEPTH` 常量而非配置

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RawPathSearch.java`，223–224 行
- **问题**：粗规划隧道预检的 crossings 掩码用 `RoadConstants.DEFAULT_BRIDGE_MIN_WATER_DEPTH`（=1），而正式 span 提取用 `bridgeMinWaterDepth`（BridgeFirst 模式下强制 1，否则读配置 `bridgeMinWaterDepth`，见 `PathSpanExtractor.java:117-119`）。
- **为什么是问题**：玩家配置了更高桥水深的，粗规划阶段与精确阶段对"哪列算水"判断不一致，预检对 crossings 的豁免范围漂移。
- **修复方向**：传入与 `extractSpans` 相同的 `effectiveBridgeMinDepth`。

### P2-10 死代码 / 死参数集合

- **文件与位置**：
  1. `RawPathSearch.java`，211–245 行：`applyCoarseTerrainModes(..., boolean allowReroute)` 的 `allowReroute` 参数在方法体内从未使用（三处调用点传了 `true/true/false`，无效果）。
  2. `RoadGradePlanner.java`，373–375 行：无参 `surfaceCutLimit()` 私有重载未被调用。
  3. `PathSpanExtractor.java`，47 行：`UNDERSEA_TUNNEL_SELECTION_PROBABILITY = 0.5D` 已被 `terrainProfile().oceanUnderseaChance()`（250–258 行）取代，常量残留。
  4. `RoadPathCalculator.java`，899–911 行与 963–967 行：`RoutePrecisionRetryPolicy.allowsLocalDetour(step)` 恒为 true（其定义是 `step >= RoadConstants.ASTAR_STEP_MIN`，而 `ASTAR_STEP_MIN=1` 且 `effectiveAStarStep()` 已 clamp ≥1），"deferred local structure/lake detour" 两个分支永不可达。
  5. `InitialGenManager.java`，200–219 行：`tiers` 恒来自 `initialBatchSteps`（长度恒 1），`tierIndex > 0` 的 LOCAL_REPAIR HUD 分支不可达（真正的局部修复在 302 行之后的独立波次）。
- **为什么是问题**：#4 尤其值得注意——它让读者以为"粗步长时会跳过局部结构绕行以省预算"，实际从未发生：任何步长都会跑完整 4 轮半径的结构绕行（与 P2-12 性能项相关）。其余为可读性/维护噪音。
- **修复方向**：删除或实现；#4 要么真正实现"按步长节流"，要么删掉分支并更新注释。

### P2-11 `PlannedPathCache` 每条连接同步落盘 + 每次新建 store；复用 span 以 x/z 去重会撞同平面坐标

- **文件**：`common/src/main/java/net/shiroha233/roadweaver/planning/path/PlannedPathCache.java`，39–64 行（`register` 逐条 `store.save` 同步 IO）、185–187 行（`store(level)` 每次 `new FilePlannedPathStore`）、155–171 行（`plannedReuseSpans` 的 `indices.putIfAbsent(key(middlePos()), i)` 只按 x/z 建）
- **问题**：
  1. 批量注册（初始规划一次注册全部路线）时，磁盘写是逐条同步的，发生在规划线程上；
  2. `plannedReuseSpans` 以 x/z 长整型做索引，隧道下穿/立交场景中两个不同 Y 的 segment 共享 x/z 时会命中错误下标。
- **为什么是问题**：1 是启动期可避免的 IO 串行开销；2 会把 NETWORK_REUSE span 标到错误区段（概率低但存在，立交道路出现"重复铺装"或"漏铺装"）。
- **修复方向**：`register` 改批量写/异步队列；`plannedReuseSpans` 的键加入 Y 或改为 `BlockPos` 精确匹配。

### P2-12 性能热点：`ConfigService.get()`（synchronized）在最内层循环 + 每次扩展分配数组

- **文件**：
  - `common/src/main/java/net/shiroha233/roadweaver/config/ConfigService.java`，83–85 行（`public static synchronized ModConfig get()`）
  - `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/CoarseHeightAwarePlanner.java`，126–142 行（`candidateHeights` 在 A* 每个邻居扩展里调 `ConfigService.get()` 并 new 一个最多 33 长度的数组；8 方向 × 状态上限 26 万~100 万 → 单次搜索可达数百万次 synchronized 调用与分配）
  - `common/src/main/java/net/shiroha233/roadweaver/features/path/pathlogic/core/RoadGradePlanner.java`，377–415 行（`surfaceCutLimit/surfaceFillLimit` 每个道路站位调用一次；`stabilizeSurfaceProfile` 最多 6 轮 × 2 个钳制函数 × n 站位 × 各 2 次 → 每条路数千~数万次）
  - `common/src/main/java/net/shiroha233/roadweaver/pathfinding/PathSpanExtractor.java`，816、829–830、529 行等（`ordinaryCutLimit/ordinaryFillLimit` 在逐站位循环内调用）
  - `common/src/main/java/net/shiroha233/roadweaver/planning/terrain/RoadTerrainClassifier.java`（`classifyRoute` 每点 `sampleBundle+biome+isNearWater`，量级可接受，列出备查）
- **为什么是问题**：`ConfigService.get()` 是类级 synchronized；多个道路 worker 并发跑高度感知搜索时，这些毫无必要的热点调用会产生真实锁竞争（粗采样/精确采样 worker + GPU 批处理共存时更明显）。未竞争的 synchronized 虽便宜，但数百万次/搜索的量级加上每邻居数组分配，属于纯浪费。
- **修复方向**：每个规划 pass 开始时把所需标量（cut/fill/overburden/tunnelDepth/stateLimit）读一次进局部 `record`，逐层下传；`candidateHeights` 的档位数组在 `find` 入口构造一次复用；长期可将 `INSTANCE` 改 volatile 读替代方法级 synchronized（`load/save` 仍加锁）。

### P2-13 其他小项

1. **`CoarseTerrainTileCache` 全局单锁 LRU**（`pathfinding/terrain/region/CoarseTerrainTileCache.java`，24–27、162–172 行）：所有 worker 的每次 tile 命中都串行化在一个 `MEMORY_LOCK` 上，容量固定 256（`RoadConstants.COARSE_TERRAIN_TILE_CACHE_MAX_ENTRIES`）。多 worker 热路径下是潜在争用点。方向：改 `ConcurrentHashMap` + 分段锁/`Caffeine` 式结构，或至少把锁粒度按 tile 分片。
2. **`StructureAvoidanceService` 快照刷新频度**：`refreshPlanningSnapshot` 在每个结构绕行半径（4 次）+ 每个湖修复 cycle（≤4×4 次）被反复调用（`RoadPathCalculator.java:898/920/924/984` 等）。若 `ensurePlanningSnapshotForPathAsync` 内部没有对"同路径指纹"做短路，这就是重复的预测/采样。建议核实其去重键并在无变化时直接返回已完成 future。
3. **`isSharedElevationEndpoint` 线性扫全部结构快照**（`Road.java:791-800`，每条路 2 次）：结构数上百时是小常数开销，可改 Map<XZ, info>。列入备查，不紧急。
4. **`PlannedPathCache.discard` 在 `Road.java:415`**：生成成功后删除缓存与落盘文件——与 `rebindGeneratedRouteForRetry` 的注释意图一致（重试前保留），顺序上无问题，列出备查。

---

## 按审查重点逐项结论

### 1. 地形档位与地形分类 —— 已接入，但 5 档里只有 3 种实际行为

- `RoadTerrainClassifier`（`planning/terrain/RoadTerrainClassifier.java`）被 `RawPathSearch.classifyRoute`（66–72 行）真实消费：分类结果驱动 `mountain/water` 布尔 → 决定是否触发高度感知粗规划；`MountainSwitchbackPlanner.dominantTerrainMultiplier` 也消费地形乘数。分类逻辑本身（overburden、beach/water biome 判定、`hasBeachNeighbor`）无空值/越界问题，`point==null || !terrain.contains(...)` 有兜底。
- `TerrainProfileMode` **不是 UI 摆设**，共 4 处消费点：`RoadGradePlanner:385-388`（切方）、`:407-410`（填方）、`RawPathSearch:73-74`（TUNNEL_FIRST 触发隧道优先高度感知）、`PathSpanExtractor:117-119`（BRIDGE_FIRST 把 `effectiveBridgeMinDepth` 压到 1）。
- **但**：`AUTO` 与 `BALANCED` 在所有消费点行为完全相同（AUTO 没有任何"按地形自动选择"的分支——真正的地形自适应来自 mountain/plain 分档限值，与档位无关）；`LIGHT_CUT` 在切方上等同 TUNNEL_FIRST、在填方上等同 BRIDGE_FIRST，且**不**触发 tunnel-first 高度感知、**不**触发 bridge-first 桥深放宽——它只是"切≤3 且填≤1"的杂合档。五种档位实际只有 `{AUTO,BALANCED}`、`{TUNNEL_FIRST}`、`{BRIDGE_FIRST,LIGHT_CUT}` 三种行为。
- 建议：要么给 AUTO 实现真正的自动选择（如按 `classifyRoute` 的山地/水体占比切换 TUNNEL_FIRST/BRIDGE_FIRST 行为），要么在 UI 合并重复档并如实命名。

### 2. 高度决策链 —— 无空值/越界，阈值有一处硬编码漂移（P2-1）与一处预算不一致（P2-8）

- `RoadGradePlanner`：所有数组操作有长度/空值守卫（25–42、83–99、106–127 行）；`stabilizeSurfaceProfile` 固定 ≤6 轮收敛；`enforceContinuousSlope(PreferCut)` 的 step2 clamp `[0,8]`、`lowerRunToSlope` 方向正确（只降不升，不会造出天路）；隧道锚点搜索范围 128 站位、端点 16 站位窗口有桥掩码保护。无越界路径。
- `RawPathSearch`：`applyCoarseTerrainModes` 把 proactive tunnel 弦下压（只降不升）；`hasUnbuildableCoarseTransition` 逻辑正确但阈值硬编码（P2-1）；`TunnelHintTerrainField` 装饰器正确委托全部接口。
- `CoarseHeightAwarePlanner`：状态上限来自配置且 sanitize 到 `[50k,1M]`，`expanded < maxExpandedStates` 严格递增，无溢出；`COARSE_MIN_BUILD_Y=-64` 符合 1.21 世界底；`Corridor.contains` 的点积/叉积几何正确；`candidateHeights` 的 `levels = tunnelDepth/2+1`（上限 33）与 `tunnelDepthBlocks`（[4,64]）一致；等待/邻居剪枝（`rise > floor(horizontal)` 剪枝）与 `tunnelPenalty`、water 惩罚一致。除 P2-12 的性能问题外无缺陷。
- `AccurateGradeRepairPlanner`：局部修复用专用 `GRADE_REPAIR_POOL`（2–4 线程，77–84 行）+ 非重叠窗口并行，`future.get()` 有异常降级（137–144 行 plans.add(null)）；`MAX_LOCAL_REPAIRS=4` 防失控；`withAStarStep(2)/(1)` 的精细档与 `config.sanitize()` 的 clamp 均存在。未发现空值/越界。
- 阈值一致性矩阵：`tunnelCoreOverburden` = `mountainOverburdenBlocks`（[2,32]，默认 7）在 `RoadGradePlanner` 与 `PathSpanExtractor` 一致 ✓；`tunnelDepthBlocks`（[4,64]，默认 12）只在 `CoarseHeightAwarePlanner` 消费 ✓；`tunnelMinRun`（[2,16]，默认 3）在 `RoadGradePlanner` 两处一致 ✓；`switchbackStrength`（[0,100]）真实消费于 `MountainSwitchbackPlanner.switchbackStrength()` ✓；`heightAwareStateLimit` 消费于 `RawPathSearch` ✓ 但盘山道硬编码 260k（P2-8）；切/填限值双轨 + 强制下限（P1-3）；粗规划过渡阈值硬编码 7（P2-1）。

### 3. 桥梁与干洼地判定 —— 判定链自洽；浅干洼地**不会**被普遍误判为桥，但有两处值得注意

- **`ordinaryFillLimit`（P1-3）**：强制下限 5 覆盖用户配置（见 P1-3）。
- **浅干洼地是否误判为桥**：不会普遍误判。`refineFinalFillBridgeSpans`（515–601 行）只对 `targetY - lowestFootprintSurface >= ordinaryFillLimit()`（默认 5）的站位判 unsafe，而 `RoadGradePlanner` 已把普通站位目标 Y 钳到 footprintSupport + fillLimit(≤2)——宽而浅的洼地会被规划成"下坡进洼地再上坡"，根本不会产生 unsafeFill，更不会成桥。**会**成桥的是：① 路面脚印内存在 robust support 忽略不掉的深洞（targetY 高于洞底 ≥5 格，此时 span 覆盖 bank-to-bank，窄洞 → `ROAD_CROSSING`（≤12 格），宽洞 → `BRIDGE`）；② `maximumDepth >= DRY_DEPRESSION_FORCE_BRIDGE_DEPTH(16)` 或 `bankHeightDelta > 3` 强制 `BRIDGE`（591–594 行）。这些是"fill 无法安全表达"的情形，判桥合理。**边界提醒**：单个 3–4 格深、1–2 列宽的洞会因 robustFootprintSupport 跳过一列而触发 ≥5 的 unsafeFill，得到一个几格宽的 ROAD_CROSSING——行为正确但可能视觉上突兀，可考虑对"洞宽 ≤ 2"走局部补土而非 span。
- **水体分类**：`bridgeWaterSample` 三级来源——Streams 河道（权威，无视深度阈值，1026–1028 行，注释解释了为什么浅河道必须是 ROAD_CROSSING）→ 精确地形场（`columnWater && waterDepth >= max(1,minWaterDepth)`）→ accurate 采样兜底（biome+海平面）。`isUnknown` 保守判水（P2-5）。`collectBridgeSpans` 只对真实水掩码生成 span，峡谷另行处理（905–907 行注释 + `appendCanyonCrossingSpans`），逻辑清晰。
- **海底隧道 50% 概率**：`chooseUnderseaTunnel`（250–258 行）用 span 端点 + 首中心的**确定性散列种子**（SplittableRandom），概率读 `terrainProfile.oceanUnderseaChance()`（默认 0.5，sanitize [0,1]）——同一条桥每次重生成选择一致，不会重试时翻转 ✓。触发前置条件：桥长 ≥256 格、≥75% 列为海洋 biome 且全是水列、最大水深 ≥8（201–238 行），河道/湖泊/浅滩不会进入 ✓。 approaches 各 128 格并入 TUNNEL span ✓。残留死常量见 P2-10#3。

### 4. 共享端点高度统一 —— 链路正确，锁高采样点有偏差（P2-4）

- `Road.isSharedElevationEndpoint`（779–802 行）：村庄（`StructureAvoidanceService.isVillageEndpoint`）∨ 出生点（x/z 精确匹配）∨ 结构快照中任意（命名/unknown）位置 x/z 匹配 → 锁定；普通道路交点三者皆非 → 不锁 ✓。
- `StructureAvoidanceService.isVillageEndpoint`（2352–2371 行）：显式排除 spawn（2356–2359 行），只认村庄结构盒（`isVillageStructure`）在 `ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS` 半径内。它与 `Road` 层"spawn 返回 true"并不矛盾——Road 在 isVillageEndpoint 之外**另行**检查 spawn 与结构快照，是分层设计 ✓。
- `crossingPreciseHeight`（757–771 行）：单列精确采样 + RuntimeException 回退 profile 地形，稳妥 ✓。锁高在隧道选择前后各应用一次（673–679、694–706 行），防止隧道弦吃掉边界 ✓。
- 唯一实质缺口即 P2-4（锚点用裁剪后的首/末 center 而非端点本身，坡地村庄可能锁出不同高度）。

### 5. 局部修复与路径持久化 —— 主链路键一致，历史 bug 已修；发现一处同族静默变体（P2-3）

- 完整证据链见 P2-3 备注：rebind（step=2 注册）↔ 局部修复波查询（step=2）↔ localOnly 预检（step=2）↔ `generateTaskInternal` 把波次步长写回 genConfig ↔ `Road.generateRoad` 用同一步长查缓存。`localRetryStep()`（`Road.java:907-910`）+ 注释表明该历史 bug 已被针对性修复，主路径**不会**再现 "missing persisted route path"。
- `PlannedPathKey` 为无向端点对（含 Y），规范化排序，`Objects.requireNonNull` 完整；`PlannedPathCache` 的内存表用 `IdentityHashMap<ServerLevel,...>` + 全局锁 + 内层 ConcurrentHashMap，`discard/clear/clearAll` 锁纪律一致；指纹失败时降级为内存-only 语义正确。
- 残余风险：① P2-3（`hasBuildingConflict` 步长错配，静默 false）；② 键一致性靠多处手工对齐（fingerprint 含 `effectiveAStarStep`），任何新增查询点都可能复发——建议在 `PlannedPathCache` 提供"步长无关"的查询或集中注册"合法步长集合"。

### 6. 建筑绕行保留空位策略 —— 语义符合预期；"defer"节流分支是死代码（P2-10#4）

- `repairStructureIntersectionsWithCorridorRefresh`（`RoadPathCalculator.java:882-948`）：遍历 `LOCAL_STRUCTURE_DETOUR_BLOCKS`（来自 `RoutePrecisionRetryPolicy.structureDetourBlocks()` = 配置 start=50/step=50/max=200 → **50/100/150/200**）；每轮半径：修复 → 若 `!intersectsPreparedPath` 立即接受返回；否则 `working = exact` 继续下一半径。**四轮全部失败后**返回 `working` 并由 FinalRoadStructureValidator 走 `retainVerifiedBuildingGaps` 保护空位（`Road.java:355-371`）✓。任何一轮修复抛异常则原样上抛（943–947 行），不会静默吞掉。
- **水体/高度修复后是否重跑结构绕行**：是。路径级顺序为 structure → river → lake → structure（783–790 行）；construction 级同样顺序（821–830 行）；湖修复每个 cycle 内修复 grade + structure（984–987 行）。河修复接受后（1016–1025 行）路径级第二轮 structure pass 会覆盖到 ✓。唯一顺序缺口：路径级第二轮 structure 之后没有再跑水体检查，但 `FinalRoadWaterValidator`（`Road.java:277-350`）在更晚的阶段兜底（含 `retainProtectedGapAt`/`tryRiverRepair`），可接受。
- 结构修复接受后**没有**对应的"structure 修复把路推进水里"的即时回滚——同上由 FinalRoadWaterValidator 兜底，属可接受设计，列出备查。

### 7. 并发与死锁 —— 无 P0 级死锁；存在 P1-2 自愈型卡死链与 P2-7 无超时 join

- `ThreadPoolManager`：固定大小池 + 无界 `PriorityBlockingQueue`（core=max，不会出现"队列有任务却不建线程"的经典错误）；`RoleExecutorService.execute` 包装 `PrioritizedRunnable` 实现角色优先级 + 序号 FIFO；epoch 机制使停服后旧任务自失效；`shutdownQuietly` 用 `shutdownNow`，in-flight future 会以异常完成（配合上游 `catch (Throwable)`）。主要结构问题：PLANNING/COARSE/GENERATION/COORDINATOR/IDLE 共用一个池（P1-2 的前提）与 shutdown 语义地雷（P2-6）。
- `RuntimeLoadPolicy`：暂停等待全部是 `LockSupport.parkNanos(25ms)` 轮询 + 中断感知，条件均为外部状态（Chunky/玩家/巡航），**自身不会形成环**；`acquireCoarseUnit` 的 CAS 循环有 `recommendedRoadWorkerThreads()` 上限且有界；反射方法句柄有双检缓存，失败永久降级为 false ✓。唯一结构性风险是交互期压池到 1 与嵌套等待的组合（P1-2）。
- `CoarseTerrainTileCache`：OWNED_KEYS ThreadLocal 检测同线程递归（75–77 行抛异常而非自旋，好）；generation 变更抛 CancellationException 防止 clearAll 后写入旧代缓存；内存 LRU 与 in-flight 表在 `clearAll` 时整体替换。残余：`existing.join()` 无超时（P2-7）；owner 卡在世界生成/native 时集体阻塞。**没有**发现"采样线程同步等待服务端线程"的反向依赖——`InitialGenManager` 的世界入口等待用 `server.managedBlock(future::isDone)` + `future.get()`（705–713 行），这是正确姿势，服务端可继续 tick。
- `RoadPlanningService`：规划任务链（`ComputeService.supplyAsync` → 共享池）之间用 `thenCompose` 串联而非阻塞 join ✓；`awaitInitialFuture` 仅在世界入口/generation 波次屏障使用且有 managedBlock 保护 ✓。
- 结论：**未发现会永久挂死的无缓解死锁**；P1-2（自愈型）与 P2-7（无超时）是仅有的两个真实风险点。

### 8. 性能热点 —— 汇总

1. **`ConfigService.get()`（synchronized）在最内层循环**（P2-12，最重要）：`CoarseHeightAwarePlanner.candidateHeights`（每邻居扩展）、`RoadGradePlanner.surfaceCut/FillLimit`（每站位每轮）、`PathSpanExtractor.ordinaryCut/FillLimit`（每站位）。修复方式：pass 级快照下传。
2. **重复的昂贵搜索**：`RawPathSearch` 在 `wantHeightAware` 下重复跑同一 `MountainSwitchbackPlanner.replaceIfSafer`（P2-2）；`refreshPlanningSnapshot` 在每个绕行半径/湖 cycle 重复调用（P2-13#2）。
3. **本应局部的搜索是否被扩大**：未发现"局部修复被扩成整条路线"的反例——`conflictSamplingWindows` 以 `localConflictRadiusBlocks`（默认 100）为界，`refineInitialRoutesAsync` 明确拒绝把无冲突窗口的路线重跑成全路线采样（`RoadPlanningService.java:577-588`）；`generateInitialRoadAtStep(localOnly=true)` 在缺重绑缓存时**拒绝**而不是退回全量扫描（`RoadGenerationService.java:547-558`）✓。`CoarseHeightAwarePlanner` 有走廊约束（横向 ≤ max(step×18, 0.5×直线距离)、端点 padding）防止整图搜索 ✓。
4. **同步磁盘 IO**：`PlannedPathCache.register` 逐条同步落盘（P2-11）；`CoarseTerrainTileCache` 落盘在采样线程（可接受）。
5. **重复采样**：`Road.java` 的 `crossingPreciseHeight` 为单列精确查询（便宜）；`segmentWaterSample` 逐脚印列采样但有 `samples` map 复用 ✓；`extractSpans` 的 missing-only 批量采样（100–116 行）避免重复 ✓。整体重复采样控制良好。

---

## 剩余测试缺口

现有测试覆盖：`StructureAvoidanceServiceTest`、`RoutePrecisionRetryPolicyTest`、`RoadDataValidatorTest`、`SharedEndpointPathReuseTest`、`TunnelJunctionGradeSimulationTest`。缺口如下（按优先级）：

1. **Road.java 晋升分支回归测试**（对应 P1-1）：构造 Streams 河道碰撞 + 晋升成功场景，断言不再抛 NPE、路线保留 crossings。
2. **TerrainProfileMode 5 档决策矩阵**：对每档断言 cut/fill 上限（含山地/平原）、`effectiveBridgeMinDepth`、高度感知触发与否；特别锁定 AUTO vs BALANCED、LIGHT_CUT 的预期行为（修复 item-1 结论前先固化现状）。
3. **PathSpanExtractor 干洼地判定**：宽度 ≤12/>12、深度 ≥16、bankDelta >3、单一深洞、路线端点处的 unsafeFill（575–577 行 fallback）各一例。
4. **PlannedPathCache 指纹×步长矩阵**：注册 step=2 / 查询 step=2、8、null 三种组合 + 指纹载荷变更（`planningFingerprintPayload`）后的 miss 行为；`hasBuildingConflict` 在重绑定后的行为（对应 P2-3）。
5. **并发压力**：共享池缩到 1 时 GENERATION 任务嵌套等待 planning 子任务（对应 P1-2，修复后验证超时/下限生效）；`CoarseTerrainTileCache` 多线程同 key 竞争 + clearAll 竞态；epoch 失效时 in-flight future 的完成语义。
6. **CoarseHeightAwarePlanner 边界**：`heightAwareStateLimit` 下限 50k（提前终止返回 null）、`tunnelDepthBlocks=64`（33 层候选）、goal=start 退化。
7. **端点锁高一致性**（对应 P2-4）：同一村庄两条不同方向道路，断言 `startBoundary/endBoundary` 相等——该测试在当前实现下应**失败**，作为 P2-4 修复的驱动。

---

## 附：已核实为正确/无需改动的关键设计

- 世界入口等待统一走 `server.managedBlock` + `get()`，无服务线程冻结风险。
- 海底隧道选择是确定性散列 + 可配置概率，重试不翻转。
- 局部修复"先发现全部冲突窗口、再共享批量预取、后并行 splice"的两阶段结构（`RoadPlanningService.java:592-604`）有效避免重复采样。
- `RoutePrecisionRetryPolicy` 把所有重试固定到玩家选择精度的意图在主链路贯彻一致（仅 `localRepairBatchSteps` 例外地恒为 2，且两端一致）。
- 持久化指纹（世界生成 + 采样模式 + 寻路配置 + 全量规划载荷）足够完备，跨配置复用旧路径的风险被覆盖。
