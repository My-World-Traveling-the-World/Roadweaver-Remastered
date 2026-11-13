# A 组：结构避让与村庄链 行为等价性核查

> **核查对象（只读）**
> - 旧仓库（oracle）：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a683f6219a5b7c9c4d9f871a77cf3751e0`（工作树干净）
> - 新仓库：`D:\云游四海-Mod源码\roadweaver-next` @ `7bc60233feb4119e1796be562a7136da6b91ed43`（工作树干净）
> - 旧版：`common/.../pathlogic/structures/StructureAvoidanceService.java` **4766 行（3831 行有效代码）**
> - 新版：同名文件 **998 行 + 19 个拆出的类**；本组 8 个指定文件合计（含门面）**20 个文件 / 3966 行有效代码**
> - 约束：未编译、未运行、未改任何 `.java`、未 `git add/commit/checkout/stash`；本文件是唯一写入
> - 核查时间：2026-11-13

---

## 结论摘要

- **等价 35 项 / 移动 119 项 / 删除 0 项 / 改变 0 项**（逐项表共 154 行，编号 1..154 连续）
  （"等价"= 逻辑仍留在 `StructureAvoidanceService` 门面内、逐行相同（34 项），另加 1 项
  "改写实现但已证行为等价"的 `isVillageEndpoint`；"移动"= 逻辑搬到同包新类、逐行相同。
  两项都以"行为是否相同"为准，判定依据见 §1 与逐项表。）
- **最高风险**：本组**没有功能丢失**，真正的风险是**审计前提错了**——被点名"消失"的 7 个方法
  全部还在（`PlanningSnapshot.java` / `EndpointOwnerResolver.java`），名字、判据、阈值一字未改。
  若按"功能已丢失"去补实现，会在同包里造出**第二套村庄判据**，比少功能更危险。
  次高：`isVillageEndpoint` 是唯一改写实现（全表扫描 → 索引查询），已证等价，但它的覆盖性证明依赖
  测试里**硬编码的 16** 与生产常量 `ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS` 两处独立维护（见 §3.1）。
- **村庄包围盒判据是否被改动：否。**
  证据：`passThroughVillageGapMask` 新旧两版**整段代码逐字符相同**（连"按用户口径：只要道路中心线在
  XZ 上穿过非端点村庄的聚合包围盒，就按该盒把路裁开"这段注释都原样保留）：
  - 旧：`StructureAvoidanceService.java:4415-4445`（`insideXZ` 命中即置位，**不做 Y 判定**）
  - 新：`PlanningSnapshot.java:539-568`（同一实现）
  - 聚盒范围同样只取"非端点 + 村庄类 owner"：旧 `:4447-4456` / 新 `PlanningSnapshot.java:571-580`
  - 端点村庄仍被显式排除、仍由 `stopAtVillageBuilding` 的"落到村庄包围盒边界"逻辑处理：
    旧 `:4472-4502` / 新 `PlanningSnapshot.java:596-626`；边界裁剪 `firstOutsideVillageBox`：
    旧 `:4382-4400` / 新 `PlanningSnapshot.java:506-524`
  - 结论：**"用包围盒穿村/裁切"是刻意设计这一点没有被重构改掉**。

### 一句话数字账（为什么"−79%"不是"删了功能"）

| 项 | 旧版 | 新版 | 说明 |
|---|---|---|---|
| `StructureAvoidanceService.java` 行数 | 4766 | 998 | 单文件行数 −79% 成立 |
| 承载旧逻辑的文件数 | 1 | **19**（门面 + 18 类）；另加**新增**的 `StructureBoxIndex` 共 20 个文件参与下面的行数统计 | 纯类拆分 |
| 有效代码行（去注释/空行/import） | 3831 | **3966** | 其中 `StructureBoxIndex` 94 行是**新增**索引；旧逻辑本体 ≈ 3872 行，比旧版 **+41 行**（+1%） |
| 旧文件方法声明数（含嵌套类） | 141 | 139 个方法体在新版**逐字符相同**；2 个例外见 §3 | — |
| 类级常量 | 50 个 | 0 个值变化（另 39 个是 `StructureAvoidanceCache/Diagnostics` 的字段，值也与旧版相同） | — |
| 配置字段（`config/sub/*` 243 个） | 243 | 243，**0 个改名、0 个默认值变化** | 三个村庄开关默认值均未变 |

> **前提纠正**：任务书说"新版全仓已确认这 7 个方法名消失了"——**这是误判**。
> 实测 `grep -rn "endpointVillageBoxes" roadweaver-next --include=*.java` 等 7 条命令均在新仓命中
> （证据表见 §4）。它们从 `StructureAvoidanceService` **挪进了 `PlanningSnapshot`**，访问级别由
> `private` 放宽为包内可见（供同包调用），**没有消失**。

---

## 1. 核查方法与证据强度

本组不是抽样，而是四条互相独立的证据链：

1. **逐方法体文本等价**（最强）：把旧文件按方法签名 + 花括号配对切片，去注释、归一空白后，在新包
   20 个文件的拼接文本里做**子串精确匹配**。
   - 结果：旧文件 141 个方法声明中 **139 个方法体逐字符命中**；
   - 2 个例外：`isVillageEndpoint`（改写，§3.1）与 `PlanningSnapshot` 记录声明本身
     （§3.2：仅 `private`→包内 + 新增 `LOGGER`，记录体内所有方法体全部命中）。
2. **常量/字段值比对**：抽出两版全部 `static final` 声明按"名字=值"对比 → 旧文件 50 个常量
   **0 个变化**；`StructureAvoidanceCache`/`StructureAvoidanceDiagnostics` 逐文件 diff 只有类型限定名
   （`StructureAvoidanceService.X` → `X`）与文件头注释的差异。
3. **配置默认值比对**：`config/sub/*.java` 全部 243 个 `private` 配置字段按"文件::字段=默认值"对比 →
   **0 个新增、0 个消失、0 个默认值变化**（含 `passThroughVillageClipping=true`、
   `filterUnknownEndpointInsideVillage=true`、`prefilterStructureStarts=true`）。
4. **调用点比对**：对村庄链每个入口分别在两仓列出调用文件/行并对照（§2.1、§4），并对关键顺序做了
   人工逐行 diff。
5. **补强：Python 语义模拟**（针对唯一改写点）：把新旧两个判定（全表扫描 vs 桶查询）按 Java 语义
   逐字复刻，20 万组随机端点/盒子/道路半径 → **0 处结果不同**（§3.1）。

**证据级别**：源码级（source-confirmed）。未做运行期验证（受只读约束），所有"可观察差异"结论都标注为
源码推理。

---

## 2. 逐项对照表

> 位置格式：旧版 = `StructureAvoidanceService.java:行`（简写 `旧:行`）；新版如无特别说明，
> `SA`=`StructureAvoidanceService.java`，其余写类名。所有行号均为当前 HEAD 实测。

### 2.1 构造期 / 世界生成避让（`shouldAvoid` 一族）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 1 | `shouldAvoid`（施工带 y−2..y+6 避让；缓存未就绪时**返回 true** 不切结构） | 旧:157-200 | `WorldgenAvoidance.java:96-139` | 移动 | 逐行相同，含"未就绪返回 true"的关键保守分支 |
| 2 | `shouldAvoidAny` | 旧:202-209 | `WorldgenAvoidance.java:141-148` | 移动 | 逐行相同 |
| 3 | `shouldAvoidConstruction`（排除端点 owner + 仅 verified building） | 旧:212-225 | `WorldgenAvoidance.java:151-164` | 移动 | 逐行相同 |
| 4 | `shouldAvoidPrepared`（结构专用查询，不碰 chunk） | 旧:228-239 | `WorldgenAvoidance.java:167-178` | 移动 | 逐行相同 |
| 5 | `shouldAvoidPreparedExact`（精确施工体积、无垂直band） | 旧:242-253 | `WorldgenAvoidance.java:181-192` | 移动 | 逐行相同（`y..y`、半径 0） |
| 6 | `isVerifiedPreparedStructureBlock` | 旧:261-271 | `WorldgenAvoidance.java:200-210` | 移动 | 逐行相同，仍要求 `template-blocks` |
| 7 | `isVerifiedBuilding`（`"template-blocks".equals`） | 旧:273-275 | `WorldgenAvoidance.java:212-214` | 移动 | 逐行相同 |
| 8 | `invalidate` | 旧:277-280 | `WorldgenAvoidance.java:216-219` | 移动 | 逐行相同 |
| 9 | `clearAll`（清 13 个缓存 + 取消 in-flight + 关线程池） | 旧:282-309 | `WorldgenAvoidance.java:221-248` | 移动 | 逐行相同，清理项一个不少 |
| 10 | `discardPlanningSnapshot` | 旧:311-319 | `WorldgenAvoidance.java:250-258` | 移动 | 逐行相同 |
| 11 | `prepareConstructionChunk` | 旧:321-333 | `WorldgenAvoidance.java:260-272` | 移动 | 逐行相同 |
| 12 | `markRoadCorridorPrepared` + `markPrepared` | 旧:340-358 | `WorldgenAvoidance.java:279-297` | 移动 | 逐行相同 |
| 13 | `resolveWorldgenEntry` + `collectPieces` | 旧:361-398 | `WorldgenAvoidance.java:300-337` | 移动 | 逐行相同（含"引用 chunk 缺失返回 null"） |
| 14 | `ChunkEntry`（含 `UNREADY`） | 旧:147-155 | `ChunkEntry.java:75-83` | 移动 | 字段/构造/UNREADY 相同 |

### 2.2 快照准备编排（路径 / 圆域 / 走廊）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 15 | `PrepareTiming`（16 个方法 + 6 段计时） | 旧:432-542 | `PrepareTiming.java:23-133` | 移动 | 逐行相同；`describe()` 字段顺序与 `corridorSnapshotMs=0` 均一致 |
| 16 | `preparePlanningSnapshotsAsync`（2 重载） | 旧:551-583 | `SnapshotOrchestrator.java:102-134` | 移动 | 逐行相同 |
| 17 | `preparePlanningSnapshotsForPathsAsync`（public 2 重载） | 旧:590-600 | `SnapshotOrchestrator.java:141-151` | 移动 | 逐行相同（含 `trackInitialProgress=true`） |
| 18 | `ensurePlanningSnapshotForPathAsync` | 旧:603-611 | `SnapshotOrchestrator.java:154-162` | 移动 | 逐行相同，`SNAPSHOT` 角色未变 |
| 19 | `MAX_CIRCLE_PROBE_CHUNKS` + `clampCircleProbeRadius` | 旧:625-639 | `SnapshotOrchestrator.java:176-190` | 移动 | 256 / 每步 −16 / `πr²/256` 判据全部相同 |
| 20 | `ensurePlanningSnapshotForCircleAsync` | 旧:641-678 | `SnapshotOrchestrator.java:192-229` | 移动 | 逐行相同（含 `+256L` 余量与 `center` 探针） |
| 21 | `preparePlanningSnapshotsForPathsAsync`（私有 4 参） | 旧:680-718 | `SnapshotOrchestrator.java:231-269` | 移动 | 逐行相同 |
| 22 | `preparePathCorridorSnapshots`（按 route 串行 + `IN_FLIGHT_ROUTE_CORRIDORS` 链） | 旧:720-756 | `SnapshotOrchestrator.java:271-307` | 移动 | 逐行相同 |
| 23 | `preparePathCorridorSnapshotWithPermit`（`RUNTIME_CORRIDOR_PERMITS`） | 旧:758-780 | `SnapshotOrchestrator.java:309-331` | 移动 | 逐行相同 |
| 24 | `preparePathCorridorSnapshotsNow`（预测 → 起点 → 解析 → 合并快照 → 进度/日志） | 旧:782-894 | `SnapshotOrchestrator.java:333-445` | 移动 | 逐行相同，含 merge 与 `trimBounded(2048)` |

### 2.3 端点过滤（"村庄 V ↔ V 盒内未知端点"）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 25 | `EndpointFilterOutcome` + `filteredCount` | 旧:897-904 | `EndpointFilterOutcome.java:82-89` | 移动 | 逐行相同 |
| 26 | `UNKNOWN_ENDPOINT_FILTER_MAX_PER_ROUND = 4` | 旧:907 | `SA:169` | 等价 | 值相同（安全阀阈值） |
| 27 | 预筛开关/计数/护栏采样（`STRUCTURE_PREFILTER_ENABLED` 等 5 个静态字段） | 旧:912-919 | `SA:174-181` | 等价 | 名称、初值、语义相同 |
| 28 | `isPotentialStructureChunk`（放置数学预筛） | 旧:932-947 | `SA:194-209` | 等价 | 逐行相同 |
| 29 | `verifyPrefilterExactness`（护栏，不一致就永久关预筛） | 旧:959-998 | `SA:221-259` | 等价 | 逐行相同，采样 64 相同 |
| 30 | `filterUnknownEndpointInsideVillage`（两条安全阀 + `villageInternal` 豁免） | 旧:1020-1109 | `SA:282-371` | 等价 | 逐行相同；`>4`、`leavesEndpointWithoutConnection` 判据未变 |
| 31 | `logFilteredVillageEndpoint` | 旧:1111-1118 | `SA:373-380` | 等价 | 逐行相同 |
| 32 | `isUnknownEndpoint`（只认已核验位点） | 旧:1128-1141 | `SA:390-403` | 等价 | 逐行相同 |
| 33 | `endpointMatchesStructure`（chunkKey + structureId 双核对） | 旧:1150-1161 | `SA:412-423` | 等价 | 逐行相同 |
| 34 | `leavesEndpointWithoutConnection` | 旧:1164-1182 | `SA:426-444` | 等价 | 逐行相同 |
| 35 | `preparePlanningSnapshotsOnServerThread`（服务器线程取快照 + 诊断计数） | 旧:1184-1326 | `SA:446-588` | 等价 | 逐行相同（含 12 个 before 计数） |

### 2.4 规划期避让判据（建筑 / 村庄簇 / 碰撞盒）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 36 | `activatePlanningSnapshot`（2 重载）+ `PlanningScope` | 旧:1329-1353、3483-3486 | `SA:591-615、875-878` | 等价 | 逐行相同；`additionalRoadRadius>0` 才扩 |
| 37 | `shouldAvoidPlanning`（2 重载） | 旧:1356-1365 | `SA:618-627` | 等价 | 逐行相同 |
| 38 | `BuildingObstacleMode` + `BUILDING_OBSTACLE_MODE` ThreadLocal + `BuildingObstacleScope` | 旧:1381-1399 | `SA:643-652`、`ObstacleJudge.java:100-108` | 移动 | 枚举顺序 PENALTY/HARD_BUILDINGS/HARD_BUILDINGS_AND_CLUSTERS 未变；默认 PENALTY 未变 |
| 39 | `blocksVerifiedBuilding` | 旧:1407-1409 | `ObstacleJudge.java:115-117` | 移动 | 逐行相同 |
| 40 | `blocksPlanningObstacle`（PENALTY 早退、簇只在 HARD_BUILDINGS_AND_CLUSTERS） | 旧:1415-1422 | `ObstacleJudge.java:123-130` | 移动 | 逐行相同，短路顺序未变 |
| 41 | `blocksVillageCluster` | 旧:1428-1435 | `ObstacleJudge.java:136-143` | 移动 | 逐行相同（y 用 −2/+6） |
| 42 | `VILLAGE_CLUSTER_MIN_PIECES=3` + `CLUSTER_BOX_CACHE` + `clusterBoxes` | 旧:1438-1453 | `ObstacleJudge.java:146-161` | 移动 | 3 片阈值、缓存上限 16、IdentityHashMap 均相同 |
| 43 | `buildClusterBoxes`（仅 verified、排除端点 owner） | 旧:1455-1485 | `ObstacleJudge.java:163-193` | 移动 | 逐行相同 |
| 44 | `describePlanningCollision`（2 重载） | 旧:1488-1497 | `ObstacleJudge.java:196-205` | 移动 | 逐行相同（仅日志用途） |
| 45 | `planningCollisionBox`（先 building、再 fallback 到任意盒） | 旧:1500-1518 | `ObstacleJudge.java:208-226` | 移动 | 逐行相同，两次尝试顺序未变 |
| 46 | `stopAtVillageBuilding`（API 壳） | 旧:1524-1530 | `ObstacleJudge.java:232-238` | 移动 | 逐行相同；实现见 #76 |
| 47 | `planningRoadRadius`（`ceil(roadWidth/2)`） | 旧:1533-1536 | `ObstacleJudge.java:241-244` | 移动 | 逐行相同 |
| 48 | `stopAtEndpointBuilding`（端点建筑裁剪，<2 站返回 null） | 旧:1543-1566 | `ObstacleJudge.java:251-274` | 移动 | 逐行相同 |

### 2.5 保护缺口 / 过村判定（村庄链核心之一）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 49 | `intersectsPreparedRoute` | 旧:1569-1579 | `ProtectedGapEvaluator.java:98-108` | 移动 | 逐行相同 |
| 50 | `intersectsPreparedRouteWithUnresolvedStructure` | 旧:1582-1589 | `ProtectedGapEvaluator.java:111-118` | 移动 | 逐行相同（两版都恒 false 的占位） |
| 51 | `isProtectedGapAcceptable` | 旧:1596-1600 | `ProtectedGapEvaluator.java:125-129` | 移动 | 逐行相同 |
| 52 | `protectedGapSpans`（footprint + **过村掩码** → PROTECTED_GAP 区间） | 旧:1603-1649 | `ProtectedGapEvaluator.java:132-178` | 移动 | 逐行相同，含 `Math.max(1.0, ...)` 与整路碰撞返回空 |
| 53 | `passThroughVillageCrossings`（快照未就绪返回 −1） | 旧:1656-1672 | `ProtectedGapEvaluator.java:185-201` | 移动 | 逐行相同 |
| 54 | `hasOnlyEndpointProtectedGaps` | 旧:1680-1691 | `ProtectedGapEvaluator.java:209-221` | 移动 | 逐行相同 |
| 55 | `endpointOnlyProtectedGapPatternForTest` | 旧:1694-1697 | `ProtectedGapEvaluator.java:223-226` | 移动 | 逐行相同 |
| 56 | `endpointOnlyProtectedGapPattern`（32 格端点半径） | 旧:1699-1719 | `ProtectedGapEvaluator.java:228-248` | 移动 | 逐行相同；`ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS=32` 未变 |
| 57 | `intersectsPreparedPath`（2 重载，含线段加密采样） | 旧:1722-1760 | `ProtectedGapEvaluator.java:251-289` | 移动 | 逐行相同（`terrainSampler` 仍未使用） |
| 58 | `intersectsPreparedPathWithUnresolvedStructure` | 旧:1763-1769 | `ProtectedGapEvaluator.java:292-299` | 移动 | 逐行相同（恒 false） |
| 59 | `hasAcceptableBuildingGaps` | 旧:1778-1785 | `ProtectedGapEvaluator.java:307-314` | 移动 | 逐行相同 |
| 60 | `sweptBuildingCollisionMask`（线段采样掩码） | 旧:1793-1818 | `ProtectedGapEvaluator.java:322-347` | 移动 | 逐行相同 |
| 61 | `protectedGapPatternAcceptableForTest` | 旧:1821-1823 | `ProtectedGapEvaluator.java:350-352` | 移动 | 逐行相同 |
| 62 | `evaluateProtectedGapPattern`（整路覆盖即拒绝；端点缺口需有可用路面） | 旧:1825-1873 | `ProtectedGapEvaluator.java:354-402` | 移动 | 逐行相同，`before/after` 判据未变 |
| 63 | `routeHorizontalLength` | 旧:1875-1883 | `ProtectedGapEvaluator.java:404-412` | 移动 | 逐行相同 |

### 2.6 快照/强制垂直障碍/查询 API

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 64 | `requirePlanningSnapshot`（2 重载，缺失即抛） | 旧:1885-1910 | `SA:655-680` | 等价 | 逐行相同 |
| 65 | `forceVerticalObstacle` | 旧:1912-1918 | `SA:682-688` | 等价 | 逐行相同 |
| 66 | `isVerifiedBuildingCollision`（owner + verified + 非端点） | 旧:1927-1941 | `SA:697-711` | 等价 | 逐行相同 |
| 67 | `PlanningSnapshotUnavailableException` | 旧:1943-1947 | `PlanningSnapshotUnavailableException.java:5-11` | 移动 | 逐行相同 |

### 2.7 走廊候选 / 结构起点发现（决定"村庄能不能被看见"）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 68 | `buildPathCorridors`（12 chunk 圆 + 已覆盖区块剔除） | 旧:1949-2004 | `PathCorridorBuilder.java:95-150` | 移动 | 逐行相同；`STRUCTURE_START_REACH_CHUNKS=11` + margin 1 未变 |
| 69 | `addChunkLine`（Bresenham） | 旧:2006-2027 | `PathCorridorBuilder.java:152-173` | 移动 | 逐行相同 |
| 70 | `predictObstacleCandidatesForCorridors`（16 chunk 瓦片 + 缓存 8192） | 旧:2029-2131 | `PathCorridorBuilder.java:175-278` | 移动 | 逐行相同 |
| 71 | `collectCorridorStartKeys`（已知位点 + 预筛后的预测起点） | 旧:2134-2176 | `PathCorridorBuilder.java:280-323` | 移动 | 逐行相同 |
| 72 | `withinCorridorPath`（192 格到线段距离） | 旧:2183-2199 | `PathCorridorBuilder.java:329-345` | 移动 | 逐行相同；`PLANNING_CORRIDOR_RADIUS=192` 未变 |
| 73 | `predictObstacleCandidates`（端点矩形 ±368 格） | 旧:2201-2294 | `PathCorridorBuilder.java:347-440` | 移动 | 逐行相同；`192 + 11*16 = 368` 未变 |
| 74 | `collectPlanningStartKeys` | 旧:2296-2333 | `PathCorridorBuilder.java:442-479` | 移动 | 逐行相同 |
| 75 | `collectCandidateStartKey` | 旧:2335-2343 | `PathCorridorBuilder.java:481-489` | 移动 | 逐行相同 |

### 2.8 精确起点解析（结构盒来源；决定村庄建筑盒是否被读到）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 76 | `resolvePreparedStartsAsync`（4 重载） | 旧:2345-2403 | `PreparedStartResolver.java:59-117` | 移动 | 逐行相同 |
| 77 | `resolvePreparedStartWindow`（泵 + 批窗口 2） | 旧:2405-2485 | `PreparedStartResolver.java:119-199` | 移动 | 逐行相同（含 `delayedExecutor(0ms)` 续泵） |
| 78 | `structureStartBatchWindow`（启动期最多 16） | 旧:2487-2493 | `PreparedStartResolver.java:201-207` | 移动 | 逐行相同 |
| 79 | `resolvePreparedStartBatchOnce`（批大小 16） | 旧:2495-2545 | `PreparedStartResolver.java:209-259` | 移动 | 逐行相同 |
| 80 | `resolvePreparedStartAsync`（in-flight 去重） | 旧:2547-2555 | `PreparedStartResolver.java:261-269` | 移动 | 逐行相同 |
| 81 | `createPreparedStartFuture`（manual / native 直算 / 熔断回退） | 旧:2557-2618 | `PreparedStartResolver.java:271-332` | 移动 | 逐行相同，含 `exceptionallyCompose` 回退链 |
| 82 | `resolvePredictedStructureSetBoxes`（权重抽签 + `structure.generate`） | 旧:2625-2676 | `PreparedStartResolver.java:339-390` | 移动 | 逐行相同 |
| 83 | `acquireNativeDirectLane` | 旧:2678-2691 | `PreparedStartResolver.java:392-405` | 移动 | 逐行相同 |
| 84 | `nativeDirectExecutor`（`RW-NativeStructure-N`，优先级 −2） | 旧:2693-2709 | `PreparedStartResolver.java:407-423` | 移动 | 逐行相同 |
| 85 | `nativeDirectWorkerMaximum` | 旧:2711-2717 | `PreparedStartResolver.java:425-431` | 移动 | 逐行相同 |
| 86 | `resolvePredictedChunkBoxesAsync` | 旧:2719-2733 | `PreparedStartResolver.java:433-447` | 移动 | 逐行相同 |
| 87 | `storePreparedStart`（启动期 pin / 普通缓存 32768） | 旧:2735-2757 | `PreparedStartResolver.java:449-471` | 移动 | 逐行相同 |
| 88 | `probeBiomePrefilter` + `probeEnabled`（默认开，只观测） | 旧:2773-2795 | `PreparedStartResolver.java:487-509` | 移动 | 逐行相同 |
| 89 | `cachedPreparedStart`（pin → main → identity 探针） | 旧:2797-2824 | `PreparedStartResolver.java:511-538` | 移动 | 逐行相同 |
| 90 | `probeIdentityLeak` + 诊断计数器 | 旧:2847-2915 | `PreparedStartResolver.java:561-629` | 移动 | 逐行相同（含乱码日志原文，未改） |
| 91 | `logPreparedStartDiagnostics` | 旧:2918-2932 | `PreparedStartResolver.java:632-646` | 移动 | 逐行相同 |
| 92 | `compactPreparedStartCacheAfterInitialGeneration` + 缓存统计 | 旧:2939-2958 | `PreparedStartResolver.java:653-672` | 移动 | 逐行相同 |
| 93 | `resolveManualPointBoxesAsync` | 旧:2961-2965 | `PreparedStartResolver.java:675-679` | 移动 | 逐行相同 |
| 94 | `resolveReferenceChunkBoxesAsync` | 旧:2967-2984 | `PreparedStartResolver.java:681-698` | 移动 | 逐行相同 |
| 95 | `requestManualStartChunks` | 旧:2986-3015 | `PreparedStartResolver.java:700-729` | 移动 | 逐行相同 |
| 96 | `requestChunkAsync` | 旧:3017-3021 | `PreparedStartResolver.java:731-735` | 移动 | 逐行相同 |
| 97 | `requestChunkOnServerThread`（票据 + 非阻塞 future + epoch） | 旧:3023-3078 | `PreparedStartResolver.java:737-792` | 移动 | 逐行相同（`MAX_PENDING_REQUESTS=32` 在门面） |

### 2.9 门面保留的工具/键/缓存

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 98 | `unwrapCompletionFailure` | 旧:3080-3086 | `SA:714-720` | 等价 | 逐行相同 |
| 99 | `callOnServerThread` | 旧:3088-3111 | `SA:722-745` | 等价 | 逐行相同 |
| 100 | `normalizeSource` + `preparedStartKey` | 旧:3113-3130 | `SA:747-764` | 等价 | 逐行相同 |
| 101 | `indexConstructionBoxes` | 旧:3132-3154 | `SA:766-788` | 等价 | 逐行相同 |
| 102 | `distanceSquaredToSegment` | 旧:3156-3169 | `SA:790-803` | 等价 | 逐行相同（含 `ceil`） |
| 103 | `elapsedMillis` | 旧:3171-3173 | `SA:805-807` | 等价 | 逐行相同 |
| 104 | `routeKey`（2 重载，端点排序归一） | 旧:3175-3194 | `SA:809-829` | 等价 | 逐行相同 |
| 105 | `encapsulate` | 旧:3439-3447 | `SA:831-839` | 等价 | 逐行相同 |
| 106 | `containsHorizontal` | 旧:3449-3453 | `SA:841-845` | 等价 | 逐行相同 |
| 107 | `endpointStructureFamily` | 旧:3455-3461 | `SA:847-853` | 等价 | 逐行相同（取 `path` 末段） |
| 108 | `isVillageStructure`（id 含 `village`） | 旧:3463-3465 | `SA:855-857` | 等价 | 逐行相同 |
| 109 | `horizontalDistanceSquared`（box/pos + pos/pos） | 旧:3467-3481 | `SA:859-873` | 等价 | 逐行相同 |
| 110 | `requestMainThreadPreload` | 旧:4650-4688 | `SA:882-920` | 等价 | 逐行相同（32 个待处理上限） |
| 111 | `decrementPendingRequests` | 旧:4690-4692 | `SA:922-924` | 等价 | 逐行相同 |
| 112 | `putIfAbsentBounded` | 旧:4694-4705 | `SA:926-937` | 等价 | 逐行相同 |
| 113 | `putBounded` | 旧:4707-4717 | `SA:939-949` | 等价 | 逐行相同 |
| 114 | `trimBounded` + `PREPARED_CACHE_EVICTIONS` | 旧:4719-4732、4746 | `SA:951-964、978` | 等价 | 逐行相同 |
| 115 | `intersectsConstructionBand` / `intersectsPlanningBand` | 旧:4753-4765 | `SA:985-997` | 等价 | 逐行相同 |
| 116 | 类级常量块（端点村庄 96 / 保护缺口 32 / −2..+6 / 192 / 11 / 16 / 2048 / 32768 / 8192 / 24 / 16 等） | 旧:100-145 | `SA:84-124` | 等价 | **逐值核对 0 变化**（`VILLAGE_GAP_BELOW_TOLERANCE=16` 两版都声明但都未被引用，见 §5） |

### 2.10 端点归属推断（含唯一改写点）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 117 | `endpointOwnersForRoad`（快照优先，否则按存储位点解析） | 旧:3196-3211 | `EndpointOwnerResolver.java:93-108` | 移动 | 逐行相同 |
| 118 | `resolveEndpointOwners` | 旧:3213-3222 | `EndpointOwnerResolver.java:110-119` | 移动 | 逐行相同 |
| 119 | `resolveEndpointOwnersForTest` | 旧:3224-3229 | `EndpointOwnerResolver.java:121-126` | 移动 | 逐行相同 |
| 120 | `isKnownStructureEndpoint` | 旧:3238-3249 | `EndpointOwnerResolver.java:135-146` | 移动 | 逐行相同 |
| 121 | **`isVillageEndpoint`** | 旧:3251-3270 | `EndpointOwnerResolver.java:148-177` | 等价（改写） | 全表扫描 → `StructureBoxIndex.fromChunkBuckets(...).query(±16)`；判据/阈值未动，见 §3.1 的证明与 20 万例模拟 |
| 122 | `resolveEndpointOwnersFromInfos` | 旧:3272-3286 | `EndpointOwnerResolver.java:179-193` | 移动 | 逐行相同 |
| 123 | `resolveEndpointOwner`（identity → 唯一命中 → 家族重映射 24 格 → 附近推断 16 格） | 旧:3288-3393 | `EndpointOwnerResolver.java:195-300` | 移动 | 逐行相同 |
| 124 | `inferNearbyEndpointOwner`（仅 verified + village，唯一最近） | 旧:3395-3424 | `EndpointOwnerResolver.java:302-331` | 移动 | 逐行相同 |
| 125 | `InferredEndpointOwner` | 旧:3426-3427 | `EndpointOwnerResolver.java:333-334` | 移动 | 逐行相同 |
| 126 | `levelOwner`（注册表 id，未知记 `"unknown"`） | 旧:3429-3437 | `EndpointOwnerResolver.java:336-344` | 移动 | 逐行相同 |

### 2.11 嵌套数据类型（PlanningSnapshot 与配套）

| # | 旧版功能点 | 旧版位置 | 重构版位置 | 判定 | 说明 |
|---|---|---|---|---|---|
| 127 | `RouteKey` | 旧:3488-3489 | `RouteKey.java:5` | 移动 | 组件/顺序相同 |
| 128 | `PreparedStartKey` | 旧:3491-3496 | `PreparedStartKey.java:7` | 移动 | 组件/顺序相同（含 `Holder<StructureSet>` identity 语义） |
| 129 | `StructureOwnerKey` | 旧:3498-3499 | `StructureOwnerKey.java:5` | 移动 | 组件/顺序相同 |
| 130 | `OwnedStructureBox`（`collisionBox`/`intersects`/`intersectsSolid`/`fallingCapOccupancyAt`/`occupancyKind`） | 旧:3501-3549 | `OwnedStructureBox.java:9-56` | 移动 | 逐行相同（含 `occupancy==null → "fallback-box"`） |
| 131 | `FallingCapOccupancy` 枚举 | 旧:3551-3556 | `PieceOccupancy.java:55-60` | 移动 | 顺序 AIR/SOLID/REMOVABLE/UNKNOWN 相同 |
| 132 | `PieceOccupancy`（模板占位解析：forPiece/forTest/ensureResolved/碰撞 AABB/200k 上限/诊断） | 旧:3558-3880 | `PieceOccupancy.java:62-387` | 移动 | **逐行相同**，含 `MAX_CACHED_BLOCKS=200_000`、`ignoredState`（air/structure_void/jigsaw）、`classify` |
| 133 | 9 个内部 record（`PredictedCandidateKey`…`SnapshotPreparation`） | 旧:3882-3933 | `StructureKeys.java:75-126` | 移动 | 组件/顺序相同 |
| 134 | **`PlanningSnapshot` record 本体** | 旧:3935-4648 | `PlanningSnapshot.java:55-772` | 移动 | 记录组件 7 个、顺序、类型全部相同；记录体 30 个方法逐个命中（仅 `private`→包内、新增 `LOGGER`，见 §3.2） |
| 135 | `forRoute`（4 重载；roadRadius + endpointOwners + 按 chunk 建桶） | 旧:3945-4013 | `PlanningSnapshot.java:69-137` | 移动 | 逐行相同 |
| 136 | `merge`（2 重载；保留 forcedVerticalOwners） | 旧:4015-4066 | `PlanningSnapshot.java:139-190` | 移动 | 逐行相同 |
| 137 | `blocks` / `blocksFootprint` / `blocksEndpointBuildingFootprint` / `blocksSegmentFootprint` / `blocksEndpointBuildingSegmentFootprint` / `hasUnresolvedSegmentFootprint` / `hasUnresolvedBlock` / `blocksCluster` / `findCollision`（4 参与 5 参）/ `findFallingCapCollision` | 旧:4068-4218 | `PlanningSnapshot.java:192-342` | 移动 | 逐行相同（含 5 参 `findCollision` 的 forced-vertical 走整盒 Y、普通走 `intersectsPlanningBand` 分支） |
| 138 | `findBuildingCollision` | 旧:4220-4239 | `PlanningSnapshot.java:344-363` | 移动 | 逐行相同（`isVerifiedBuilding` + 非端点 + forced 走整盒 Y） |
| 139 | `findEndpointBuildingCollision` | 旧:4241-4251 | `PlanningSnapshot.java:365-375` | 移动 | 逐行相同（只对端点 owner） |
| 140 | `ownsEndpoint`/`insideAnyStructureBox`/`villageOwnerAt` | 旧:4253-4294 | `PlanningSnapshot.java:377-418` | 移动 | 逐行相同（`villageOwnerAt` 多村歧义返回 null） |
| 141 | `describeCollision`（2 重载） | 旧:4296-4331 | `PlanningSnapshot.java:420-455` | 移动 | 逐行相同 |
| 142 | **`stopAtVillageBuilding`（实现：端点村庄盒边界着陆 + 首栋建筑回退）** | 旧:4333-4380 | `PlanningSnapshot.java:457-504` | 移动 | 逐行相同 |
| 143 | **`firstOutsideVillageBox`** | 旧:4382-4400 | `PlanningSnapshot.java:506-524` | 移动 | 逐行相同（从两端找第一个出盒站点） |
| 144 | `insideXZ` | 旧:4402-4405 | `PlanningSnapshot.java:526-529` | 移动 | 逐行相同（`>=min && <=max`） |
| 145 | **`passThroughVillageGapMask`（包围盒裁切判据本体）** | 旧:4415-4445 | `PlanningSnapshot.java:539-568` | 移动 | 逐行相同，**含"按用户口径…XZ 穿过即裁"注释与无 Y 判定的实现** |
| 146 | **`passThroughVillageBoxes`（非端点村庄聚盒）** | 旧:4447-4456 | `PlanningSnapshot.java:571-580` | 移动 | 逐行相同 |
| 147 | `aggregateStructureBox` | 旧:4458-4470 | `PlanningSnapshot.java:582-594` | 移动 | 逐行相同 |
| 148 | **`endpointVillageBoxes`（端点村庄最近的 owner 盒，24 格内）** | 旧:4472-4502 | `PlanningSnapshot.java:596-626` | 移动 | 逐行相同（最近距离并列时取多 owner） |
| 149 | `closestEndpoint` + `horizontalDistanceSquared` | 旧:4504-4519 | `PlanningSnapshot.java:628-643` | 移动 | 逐行相同 |
| 150 | `firstVillageBuildingCollision` + `VillageBuildingCollision` | 旧:4521-4549 | `PlanningSnapshot.java:645-673` | 移动 | 逐行相同（`y+1..y+6` 的 `intersectsSolid`） |
| 151 | `forceVerticalOwner` + **`withForcedVerticalOwners`** | 旧:4551-4562 | `PlanningSnapshot.java:675-686` | 移动 | 逐行相同（加 owner 后重建快照，保留其余字段） |
| 152 | `withAdditionalRoadRadius` | 旧:4564-4586 | `PlanningSnapshot.java:688-710` | 移动 | 逐行相同 |
| 153 | `findEndpointOwners`（端点落在聚合盒 XZ 内） | 旧:4588-4604 | `PlanningSnapshot.java:712-728` | 移动 | 逐行相同 |
| 154 | `expandEndpointVillageOwners`（端点村庄同族 96 格扩组） | 旧:4606-4647 | `PlanningSnapshot.java:730-771` | 移动 | 逐行相同；`ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS=96` 未变 |

---

## 3. ★ 行为改变清单

**本组实测 0 项行为改变。** 为免"看起来没问题"的空口结论，下面把两个**唯一的非纯搬迁点**逐条摊开，
并给出"为什么它不是行为改变"的证据；然后再给出四类高风险差异的专项核对结果。

### 3.1 唯一实现改写：`isVillageEndpoint` 全表扫描 → 空间索引查询（等价）

**旧版**（`StructureAvoidanceService.java:3259-3269`）：

```java
PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
        || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return false;
long maxDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
        * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
for (OwnedStructureBox owned : snapshot.boxes()) {
    if (owned == null || owned.box() == null || owned.owner() == null) continue;
    if (!isVillageStructure(owned.owner().structureId())) continue;
    if (horizontalDistanceSquared(owned.box(), endpoint) <= maxDistanceSquared) return true;
}
return false;
```

**新版**（`EndpointOwnerResolver.java:156-176`）：

```java
PlanningSnapshot snapshot = StructureAvoidanceCache.PLANNING_SNAPSHOTS.get(routeKey(level, connection));
if (snapshot == null || snapshot == PlanningSnapshot.EMPTY
        || snapshot.boxes() == null || snapshot.boxes().isEmpty()) return false;
long maxDistanceSquared = (long) ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS
        * ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
int reach = ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS;
List<OwnedStructureBox> candidates = StructureBoxIndex
        .fromChunkBuckets(snapshot.boxesByChunk())
        .query(endpoint.getX() - reach, endpoint.getZ() - reach,
                endpoint.getX() + reach, endpoint.getZ() + reach);
for (OwnedStructureBox owned : candidates) {
    if (owned == null || owned.box() == null || owned.owner() == null) continue;
    if (!isVillageStructure(owned.owner().structureId())) continue;
    if (horizontalDistanceSquared(owned.box(), endpoint) <= maxDistanceSquared) return true;
}
return false;
```

**为什么等价（三条证据）**：

1. **覆盖性可证**：`reach = ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS = 16`，与距离阈值 `maxDistanceSquared`
   是**同一个常量**；桶表 `boxesByChunk` 的键 = `ChunkPos.asLong(block>>4, block>>4)`，`StructureBoxIndex`
   的 `cellKey` 也是"低 32 位 x、高 32 位 z"（`StructureBoxIndex.java:185-187`），且 `fromChunkBuckets`
   直接复用该映射、不做桶内粗筛（`boxes==null`，整桶返回 → 超集）。任何"水平距离 ≤16"的盒，其
   `maxX ≥ px−16`、`minX ≤ px+16`，其区块范围必与查询的 3×3 桶相交 ⇒ 必被取出，再由原判据精判。
2. **20 万组随机模拟 0 差异**：按两版 Java 语义逐字复刻（盒区间、`ChunkPos` 桶键、按 `roadRadius`
   扩张桶范围、`>>4` 的负坐标地板除），随机端点/盒子/道路半径（0,1,2,3,4,5,6,8）→
   **cases=200000, mismatches=0**。
3. **提交意图与守卫**：新仓 commit `5bce749`（`perf(spatial): 索引接进端点推断`）只改这一处，
   并新增 `StructureBoxIndexTest` 的 `reachQueryCoversEveryBoxWithinReach` /
   `chunkBucketKeysMatchChunkPosLayout` / `emptyChunkBucketsYieldEmptyIndex` 三条测试；
   旧仓同名测试 `StructureAvoidanceServiceTest` 的断言在迁移后**逐条保留**（仅改限定名）。

**可观察差异**：无。`isVillageEndpoint` 只影响"该端点是否享共享高程锁 / 是否算已知结构端点"
（`RoadSpanProfileOps.java:99、134`），而输入集合与判据都未变。

> **残留风险（低）**：覆盖性证明里测试把 `reach` 硬编码为 `16`，生产用常量
> `ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS`。若将来有人只改常量不改测试，测试仍然绿而证明失效
> （生产查询框变小 → 静默漏盒）。建议把测试改成引用同一常量（本次只读核查未改）。

### 3.2 其余"非逐字符"处：只放宽访问级别，不改行为

`PlanningSnapshot` 记录本体在新版是顶层 `public record`（旧版是 `public static record` 嵌套类）。
把旧记录体与新文件做归一 diff，**只有以下差异**：
- 声明由 `public static record PlanningSnapshot(` → `public record PlanningSnapshot(`（移动到顶层）；
- 新增 `private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver")`（旧版用外层类的 LOGGER）；
- 记录体内全部方法 `private` → 包内可见（`blocks`、`findCollision`、`findBuildingCollision`、
  `firstOutsideVillageBox`、`insideXZ`、`passThroughVillageGapMask`、`passThroughVillageBoxes`、
  `aggregateStructureBox`、`endpointVillageBoxes`、`closestEndpoint`、`horizontalDistanceSquared`、
  `firstVillageBuildingCollision`、`VillageBuildingCollision`、`withForcedVerticalOwners` 等）。

访问级别放宽**不改变任何判据或返回值**；记录组件的类型/顺序/语义全部相同（7 个组件逐一核对）。
其余 18 个拆出类同理：只多了文件头注释与（部分文件）统一的 import 列表。

### 3.3 四类高风险差异专项核对

| 类别 | 检查内容 | 结果 |
|---|---|---|
| **默认值/常量** | 旧文件 50 个 `static final` 逐个按"名字=值"比对；`StructureAvoidanceCache`/`Diagnostics` 全文件 diff；`config/sub` 243 个配置字段逐个比对 | **0 变化**。`ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS=96`、`ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS=32`、`CONSTRUCTION_BELOW=2`、`CONSTRUCTION_ABOVE=6`、`PLANNING_CORRIDOR_RADIUS=192`、`STRUCTURE_START_REACH_CHUNKS=11`、`AVOIDANCE_PREDICTION_TILE_CHUNKS=16`、`STRUCTURE_START_BATCH_SIZE=16`、`STRUCTURE_START_BATCH_WINDOW=2`、`PLANNING_SNAPSHOT_CACHE_CAPACITY=2048`、`PREPARED_START_CACHE_CAPACITY=32768`、`OBSTACLE_TILE_CACHE_CAPACITY=8192`、`ENDPOINT_REPLACEMENT_MATCH_BLOCKS=24`、`ENDPOINT_OWNER_INFERENCE_MATCH_BLOCKS=16`、`MAX_CIRCLE_PROBE_CHUNKS=256`、`VILLAGE_CLUSTER_MIN_PIECES=3` 全部一致 |
| **边界条件** | 139 个方法体逐字符相同 ⇒ `<`/`<=`、`null`、空集合、0/负数分支**不可能**不同；另抽查 `insideXZ`（`>=`/`<=`）、`firstOutsideVillageBox`（返回 −1 的语义）、`evaluateProtectedGapPattern`（`path.size()<3`、`total<=0`） | **全部一致**；两版 `insideXZ` 都是闭区间 |
| **执行顺序** | ① 终点裁剪顺序：旧 `RoadPathCalculator.java:1094-1097、1142-1145` = `stopAtEndpointBuilding` → `stopAtVillageBuilding`；新 `PathFinalizeOps.java:154-157、202-205` **同序**。② 快照准备顺序：旧 `RoadPlanningService.java:1578-1628` = 拓扑 → `preparePlanningSnapshotsAsync` → 地形 → `preparePlanningSnapshotsForPathsAsync`；新 `AsyncPlanningOps.java:296-344` **同序、同条件**。③ 裁剪与施工高度重算：旧 `RoadPathCalculator` 先裁剪再算 construction 高度并可能重建 segments 再裁剪；新 `PathFinalizeOps` 同构 | **未发现顺序反转** |
| **判据口径（block vs chunk）** | 全组唯一新增的"块↔区块"换算在 `StructureBoxIndex`：`of()` 用 `floorDiv(box最小X, 16)`、`query()` 用 `floorDiv(查询X, 16)`、`fromChunkBuckets()` 用 `boxesByChunk` 的 `ChunkPos.asLong` 键。三者粒度一致（16 格）；`ChunkPos.asLong` 位布局与 `cellKey` 一致 | **一致**；20 万例模拟（含负坐标、跨桶长盒、`roadRadius` 0..8）0 差异 |

---

## 4. 真删除清单

**无。0 项删除。** 下面给出任务书点名的 7 个方法的全仓 grep 证据（新仓，`--include=*.java`）：

| 旧方法 | 旧版位置 | 新仓命中位置（grep 实测） | 判定 |
|---|---|---|---|
| `endpointVillageBoxes` | 旧:4472 | `PlanningSnapshot.java:463`、`:465`、`:596` | 存在，逐行相同 |
| `findBuildingCollision` | 旧:4220 | `PlanningSnapshot.java:273`、`:344`；调用方 `ObstacleJudge.java:214`、`WorldgenAvoidance.java:207` | 存在，逐行相同 |
| `findEndpointBuildingCollision` | 旧:4241 | `PlanningSnapshot.java:210`、`:365` | 存在，逐行相同 |
| `firstOutsideVillageBox` | 旧:4382 | `PlanningSnapshot.java:473`、`:474`、`:506` | 存在，逐行相同 |
| `passThroughVillageBoxes` | 旧:4447 | `PlanningSnapshot.java:546`、`:571` | 存在，逐行相同 |
| `passThroughVillageGapMask` | 旧:4415 | `PlanningSnapshot.java:539`；调用方 `ProtectedGapEvaluator.java:143`、`:197` | 存在，逐行相同 |
| `withForcedVerticalOwners` | 旧:4558 | `PlanningSnapshot.java:165`、`:189`、`:679`、`:682` | 存在，逐行相同 |

另附"看起来被删、实际是换了类"的公开入口（旧 → 新），避免后续审计误判：

- `StructureAvoidanceService.shouldAvoid* / invalidate / clearAll / prepareConstructionChunk /
  markRoadCorridorPrepared / discardPlanningSnapshot / prepareConstructionChunk` → `WorldgenAvoidance`
- `preparePlanningSnapshotsAsync / preparePlanningSnapshotsForPathsAsync /
  ensurePlanningSnapshotForPathAsync / ensurePlanningSnapshotForCircleAsync / clampCircleProbeRadius` → `SnapshotOrchestrator`
- `blocksPlanningObstacle / blocksVillageCluster / planningCollisionBox / stopAtVillageBuilding /
  stopAtEndpointBuilding / planningRoadRadius / describePlanningCollision` → `ObstacleJudge`
- `protectedGapSpans / passThroughVillageCrossings / hasAcceptableBuildingGaps /
  hasOnlyEndpointProtectedGaps / intersectsPreparedPath / intersectsPreparedRoute` → `ProtectedGapEvaluator`
- `isVillageEndpoint / isKnownStructureEndpoint / resolveEndpointOwners*` → `EndpointOwnerResolver`
- `resolvePreparedStartsAsync / createPreparedStartFuture / requestChunk*` → `PreparedStartResolver`
- `buildPathCorridors / predictObstacleCandidates* / collect*StartKeys` → `PathCorridorBuilder`
- `PlanningSnapshot / OwnedStructureBox / PreparedStartKey / RouteKey / StructureOwnerKey /
  PieceOccupancy / PrepareTiming / EndpointFilterOutcome / 9 个内部 record` → 各自独立文件

**调用点对照（村庄链入口，两仓一致）**：

| 入口 | 旧仓调用点 | 新仓调用点 |
|---|---|---|
| `stopAtVillageBuilding` | 2（`RoadPathCalculator:1096、1144`） | 2（`PathFinalizeOps:156、204`） |
| `stopAtEndpointBuilding` | 2（`RoadPathCalculator:1094、1142`） | 2（`PathFinalizeOps:154、202`） |
| `protectedGapSpans` | 5（`RoadPathCalculator` ×4 + `Road:202`） | 5（`PathMetricsOps` ×4 + `Road:218`） |
| `passThroughVillageCrossings` | 2（`SharedEndpointPathReuse:183、186`） | 2（`SharedEndpointPathReuse:190、193`） |
| `hasOnlyEndpointProtectedGaps` | 3（`InitialGenManager:569、1466` + `RoadGenerationService:518`） | 3（`InitialPlanWaiter:237`、`InitialRouteSubmitOps:322`、`RoadTaskExecutor:381`） |
| `filterUnknownEndpointInsideVillage` | 1（`InitialGenManager:450`） | 1（`InitialPlanWaiter:118`） |
| `forceVerticalObstacle` | 1（`Road:472`） | 1（`Road:489`） |
| 快照准备入口 | 9（`RoadPlanningService` ×6 + `AccurateGradeRepairPlanner` + `RoadPathCalculator` ×2） | 9（`RoadPlanningService` ×2 + `AsyncPlanningOps` ×2 + `PlanningBoundsOps` + `RouteRefinementOps` + `AccurateGradeRepairPlanner` + `PathFinalizeOps` + `PathMetricsOps`） |

---

## 5. 我不确定的地方

1. **没有运行期验证**：受"只读 + 不编译"约束，全部结论来自源码比对与 Python 语义模拟；"道路外观
   （穿村/少桥/土方）完全一致"属于**源码推理**，不是实测。
2. **核查边界**：我只对任务指定的 8 个文件及其**直接调用点**做了完整比对。`planning/*`、
   `pathfinding/*`、`pathlogic/grade/*` 内部的其它重构（DP 纵断面、河湖修复等）不在本组结论内。
   快照**触发时机**在 `AsyncPlanningOps` 里同序，但整条规划流水线的等价性需 B/C 组结论交叉。
3. **`VILLAGE_GAP_BELOW_TOLERANCE = 16` 两版都声明但都没有任何引用**（旧 `:117`、新 `SA:96`；
   两个仓库 grep 都只有声明行）。旧注释称要用它放宽"施工带 Y 相交容忍"，但真正的
   `passThroughVillageGapMask` **从不做 Y 判定**。我无法判断这是历史遗留（曾实现后去掉）还是
   未实现的设计残留；**两版一致，所以不是本次重构引入的回归**，但"注释说 Y 容忍、代码只看 XZ"
   这一自相矛盾在旧版就存在。
4. **两个死 API**：`hasAcceptableBuildingGaps` / `isProtectedGapAcceptable` 在两版都**没有生产调用点**
   （仅测试引用），所以它们的运行时行为不可观测；我只能确认代码逐行相同，不能确认"这条判据是否还在生效"。
5. **旧仓 `build/classes` 里留有 2026-11-12 的 `StructureAvoidanceService*.class`**。我没有反编译去核对
   "冻结源码 = 当时构建产物"（本任务不需要，且旧仓源码是纯文本真值）。若需要，可另开一次字节级审计。
6. **`isVillageEndpoint` 的等价性依赖一个隐含不变量**：`snapshot.boxesByChunk()` 必须是"16 格区块桶 +
   `ChunkPos.asLong` 键"。当前 `PlanningSnapshot.forRoute` 确实如此（`PlanningSnapshot.java:118-133`），
   但若未来有人改桶粒度（例如换 `Long2ObjectOpenHashMap` 或 8 格桶），`fromChunkBuckets` 会静默全空。
   这正是 §3.1 的残留风险。

---

## 附录 A：复现方法（全部只读，未落盘）

1) **逐方法体等价匹配**（旧文件每个方法体在新包 20 文件拼接文本中精确查找）：

```python
import re, glob
def load(p):
    s = open(p, encoding='utf-8').read()
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)   # 去块注释
    s = re.sub(r'//[^\n]*', '', s)                # 去行注释
    return s
old = load('roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathlogic/structures/StructureAvoidanceService.java')
new = ''.join(load(p) for p in glob.glob('roadweaver-next/common/src/main/java/net/shiroha233/roadweaver/pathlogic/structures/*.java'))
newc = re.sub(r'\s+', ' ', new)
for m in re.finditer(r'(?m)^\s{4}(?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*[\w<>,\.\[\]\?]+\s+(\w+)\s*\([^;{}]*\)\s*\{', old):
    i = old.find('{', m.end() - 1); d = 0; j = i
    while j < len(old):
        d += 1 if old[j] == '{' else (-1 if old[j] == '}' else 0)
        if d == 0: break
        j += 1
    if re.sub(r'\s+', ' ', old[i:j+1]) not in newc:
        print('NOT FOUND', m.group(1))
# 实测输出：NOT FOUND isVillageEndpoint / NOT FOUND PlanningSnapshot
```

2) **isVillageEndpoint 语义模拟**（全表扫描 vs 桶查询，20 万组）：按 §3.1 的三段逻辑分别实现后
   逐例比较布尔返回值，实测 `cases=200000 mismatches=0`（脚本见本次会话记录；复刻要点：`>>4` 为
   算术右移、桶范围为 `(minX-roadRadius)>>4 .. (maxX+roadRadius)>>4`、查询为 `±16`）。

3) **常量/配置核对**：正则抽 `static final NAME = VALUE;` 与 `private Type name = default;`，
   按"名字=值"做集合差与逐项比较，实测 0 差异（命令见本次会话记录）。

---

## 附录 B：给后续审计的提醒

- 本组的"−79%"是**单文件行数**，不是代码量；新包 20 个文件的有效代码反而比旧版多 41 行。
  后续组若按"行数缩水"继续推断"功能被删"，请先做同样的**方法体逐字符匹配**，再下结论。
- 旧仓同类测试 `StructureAvoidanceServiceTest`（148 行）在迁移后断言未变（新 151 行 = +3 行 import）。
  新仓另有 `StructureBoxIndexTest`（+3 条索引等价性测试，含 "±reach 必须覆盖所有距离 ≤reach 的盒"）
  与 `PrepareTimingSplitTest`（子段计时之和 ≤ 父段，防同一段时间被重复记账），
  可作为"搬迁/改写后仍要求行为可证"的旁证，但不能代替本报告的逐行比对。
- 新仓 Git 历史可直接佐证：`5166dcb`(PlanningSnapshot 搬出)、`b67e624`(四个 record)、
  `aba9aef`(PrepareTiming)、`defd04b`(PieceOccupancy)、`6edaeb7`(起点解析)、`5fcc8c8`(端点归属)、
  `ea32b94`(走廊构建)、`6bae965`(缺口/过村)、`9dc1c43`(快照编排)、`feaaaf8`(门面降到 999 行)
  全部标注"搬出/纯搬迁"；唯一改行为倾向的 `5bce749` 是索引化性能改动，已在上文证等价。