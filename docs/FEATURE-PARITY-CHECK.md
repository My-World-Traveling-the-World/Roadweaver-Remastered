# 旧功能保真核查（Feature Parity Check）

> **这份文档回答**：`roadweaver-next`（重制版）有没有丢掉旧版的能力？
>
> **它不是「已通过验收」的证明** —— 它是一套**可重复执行的核查方法** + 已经做过的结果。
> 局限写在第一节，读结论前请先读局限。

---

## 一、方法与其局限

**做法**：`docs/functional-inventory/` 的 8 份盘点已经逐条列出了旧版（oracle，
`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`）的能力点与**关键符号名**。
对每个能力点，在 `roadweaver-next` 的生产源码里 grep 它的**关键符号**（类名 / 常量名 / 方法名），
存在即记为「已迁移」。

**这个方法的局限（方向都是「高估保真度」）**：

1. **只验证「符号存在」，不验证「行为等价」。**
   一个方法在，不代表它还被生产链调用、也不代表判据没被改过。
2. **参数值只核了少数几个。** 盘点里给的关键参数（如 `MIN_RUN=6`、`MIN_DOT=0.75`）
   可以逐个核值，但**大部分没核**。
3. **符号改名会漏判。** 如果 next 把一个类重命名了，grep 会报「缺失」，而实际上能力在
   （本轮就遇到一次反向情况：`MAX_JUNCTION_CONSTRUCTION_OVERHEAD` 定义是
   `Math.ceil(ROAD_SNAP_THRESHOLD × √2)` 的**表达式**而不是字面 `17`，
   按「= 17」去 grep 会误报缺失）。
4. **反向也成立**：符号在但生产链根本没调它，grep 完全看不出来。
   真正的判据是「生产调用链是否覆盖」，那要靠读代码，不能靠 grep。

⇒ 所以本文件的结论只能读成：**「没发现缺失」**，不能读成「保证没丢」。

---

## 二、批 4（路口与合并）—— 已核，16/16

来源：`docs/functional-inventory/02-结构避让与路口.md` 的「五、路口合并 / 辫状 / 共享跨越」。

| 能力点 | 关键符号 | next 位置 | 结论 |
|---|---|---|---|
| 批次合并详细入口 | `connectPlannedBatchDetailed` | `junction/ExistingRoadJunctionService` | ✅ |
| 单供体选择 | `plausibleDonor` | 同上 | ✅ |
| 交点锚点 | `JUNCTION_ANCHOR` | 同上 | ✅ |
| 隧道口交点 | `TUNNEL_JUNCTION` | 同上 | ✅ |
| 施工开销上限 | `MAX_JUNCTION_CONSTRUCTION_OVERHEAD` | 同上 `:56`，值 `ceil(12×√2)=17`（与 oracle 同表达式）| ✅ |
| 精修超时 | `JUNCTION_REFINE_TIMEOUT_MS = 120_000` | 同上 | ✅ |
| 辫状最小段长 | `MIN_RUN = 6` | `junction/RoadBraidDetector` | ✅ |
| 辫状站距上限 | `MAX_DISTANCE`（取 `RoadConstants.ROAD_SNAP_THRESHOLD`）| 同上 | ✅ |
| 辫状方向点积 | `MIN_DOT`（取 `DEFAULT_BRAID_MIN_DIRECTION_DOT`）| 同上 | ✅ |
| 改道半径 | `BRAID_REROUTE_RADIUS` / `braidRerouteRadius` | 同上 | ✅ |
| 改道长度比 | `BRAID_REROUTE_MAX_LENGTH_RATIO = 1.5` | 同上 | ✅ |
| 禁令站余量 | `BRAID_BAN_STATION_MARGIN = 4` | 同上 | ✅ |
| 改道最小长度比 | `BRAID_REROUTE_MIN_LENGTH_RATIO = 0.6` | 同上 | ✅ |
| 桥共享服务 | `BridgeShareService` | `pathlogic/bridge/` | ✅ |
| 桥衔接阈值 | `APPROACH_THRESHOLD = 48` | `EndpointReuseOps`（oracle `ERE`）| ✅ |
| 桥共享半径 | `bridgeShareRadius` | 配置读取处 | ✅ |

**另外**：`ExistingRoadJunctionService` 与 oracle 同类同路径，`MAX_JUNCTION_CONSTRUCTION_OVERHEAD`
的可见性从 `private` 放宽到包内（供测试访问），**定义表达式一字未改**。

---

## 二之二、批 6（结构避让）—— 已核，68/68

来源：`docs/functional-inventory/02-结构避让与路口.md` 的第一~四、六、七节
（第五节是批 4，见上一节）。

| 小节 | 项数 | 结论 |
|---|---|---|
| 一、村庄判据与建筑冲突（4 个小节）| 14 | ✅ 全在 |
| 二、`PlanningSnapshot`（准备/缓存/失效/线程作用域）| 33 | ✅ 全在 |
| 三、结构预测 | （含在上行 33 内）| ✅ |
| 四、结构起点解析与 owner | （含在上行 33 内）| ✅ |
| 六、`structures/` 预计算与占位注入（5 个小节）| 15 | ✅ 全在 |
| 七、保护缺口（`PROTECTED_GAP`）| 6 | ✅ 全在 |

**逐项清单**（分节列出，便于回查）：

**一、村庄判据**（14/14）：`passThroughVillageClipping` / `passThroughVillageCrossings` /
`CONSTRUCTION_BELOW=2` / `CONSTRUCTION_ABOVE=6` / `isVillageStructure` /
`ENDPOINT_VILLAGE_GROUP_RADIUS_BLOCKS=96` / `ENDPOINT_REPLACEMENT_MATCH_BLOCKS=24` /
`findCollision` / `occupancyKind` / `MAX_CACHED_BLOCKS=200_000` / `forcedVerticalOwners` /
`VILLAGE_CLUSTER_MIN_PIECES=3` / `CLUSTER_BOX_CACHE_CAP=16` /
**`VILLAGE_GAP_BELOW_TOLERANCE`**（连盘点里标注「仅声明无消费点」的**死键**都搬过来了 ——
说明这块是纯搬迁而不是重写）。

**二~四、快照 / 预测 / 起点**（33/33）：`PlanningSnapshot` / `EMPTY` / `forRoute` / `merge` /
`invalidate` / `discardPlanningSnapshot` / `PREPARED_CACHE_EVICTIONS` /
`ACTIVE_PLANNING_SNAPSHOT` / `PlanningScope` / `additionalRoadRadius` /
`PreparedStartKey` / 等（按反引号标识符逐个 grep，33 个全命中）。

**六、预计算与注入**（15/15）：`maxStructuresPerRoad` / `MIN_ROAD_SEGMENTS_FOR_STRUCTURE=10` /
`PendingStructureStorage` / `MAX_INJECTED=4096` / `markAsInjected` /
`PendingRoadsideContentFileStorage` / `maxVillagesPerRoad` / `minRoadSegments` /
`spawnChance` / `maxStepHeight` / `MAX_INJECTED=2048` / `class StructureInjector` /
`RoadsideStructure` / `RoadsideVillageStructure` / `SimpleTemplatePiece`。

**七、保护缺口**（6/6）：`ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS=32` / `PROTECTED_GAP` /
`PlanningSnapshotUnavailableException` / `MAX_PROTECTED_GAP_FALLBACK` / `hasProtectedGap` /
`class WorldgenAvoidance`。

**★ 一个值得记的发现**：盘点第六节专门写了一小节「`WorldgenAvoidance` —— 未找到同名类」，
说它的职责散在旧仓三处。而 **next 里这个类确实存在**（`class WorldgenAvoidance` 命中）——
即拆分时把那三处职责**收拢成了一个类**。这不违反「能力不能丢」，反而是重构该有的样子；
但读盘点时容易误以为「旧仓没有 ⇒ 我们也别建」，这条要记住。

---

## 二之三、批 2（跨度与桥隧）—— 已核，131/131；并发现一个「旧功能被接活」的实例

来源：`docs/functional-inventory/03-跨度与桥隧.md` 的第一~十一节 + 附二。

| 小节 | 项数 | 结论 |
|---|---|---|
| 一、跨度提取（水面跨度 / 干峡谷 / 海底隧道 / 陆上隧道 / 村庄与路跨路 / 互斥合并 / 桥面水位下界）| 43 | ✅ 全在 |
| 二~十一（跨度细化 / 桥范围 / 运行期提升 / 桥体几何 / 桥段规划 / 桥共享 / 隧道施工 / 涵洞 / 浮标 / 观景穹顶）| 88 | ✅ 全在 |
| **合计** | **131** | **✅ 无缺失** |

**★ 附二「死常量」的复核结果（这一节比符号核查更有信息量）**：

| 旧版死常量 | next 状态 | 判读 |
|---|---|---|
| `CANYON_MIN_DEPTH_BLOCKS=10` | 出现 **1 次 / 1 文件** | 仍是死常量（只有声明）✓ 诚实继承 |
| `CANYON_MIN_WIDTH_BLOCKS=5` | 1 次 / 1 文件 | 同上 |
| `CANYON_BANK_SEARCH_SEGMENTS=8` | 1 次 / 1 文件 | 同上 |
| `UNDERSEA_TUNNEL_SELECTION_PROBABILITY=0.5` | 1 次 / 1 文件 | 同上（实读 `oceanUnderseaChance`）|
| **`bridgeDeckSurfaceY`** | **4 次 / 3 文件（活的）** | **★ 见下** |
| `isWallOwned` | 22 次 / 5 文件 | 活 |

### ★ `bridgeDeckSurfaceY`：从「旧版零调用点」变成「新内核的求解下界」

盘点附二原文写它「**全仓无调用点**」—— 这在 **oracle 里是对的**。但在 next 里它是**活的**：

```
RoadConstructionSafety:640  定义
TargetYComputation:753      deckFloor[i] = RoadConstructionSafety.bridgeDeckSurfaceY(...)
ProfileDpSource:64          javadoc：下界由 bridgeDeckSurfaceY 提供
```

这正是用户要的那件事的实例：**旧版的能力不该被丢掉，而应该变成新内核的求解约束**。
旧版算了桥面水位下界但没人用；next 把它喂进 `deckFloor[]` → `ProfileDpSource.Constraints`，
让 DP 在求纵断面时天然满足「桥面不得插水」。

> **教训**：读 oracle 的死代码结论时，**必须到 next 里重新核一遍**。
> 「oracle 里没调用点」≠「next 里也该没有」—— 恰恰相反，接活旧死代码正是重构该干的事。

---

## 二之四、批 3（施工与装饰）—— 已核，**61 个真符号 + 1 个误提取**（5 项「缺失」全是改名）

来源：`docs/functional-inventory/06-施工与装饰.md`（10 节 + 配置附录）。

**62 项提取，57 直接命中，5 项经核为「改名/前缀化」，其中 1 项是误提取**：

> ★ 2026-11-15 复核更正：下表里的 `f18492b` 是 **commit hash**，不是符号。
> 所以真实符号数是 **61 个**，不是 62。总账的加法仍然成立，但「62/62」这个说法
> 应当读作「61 个符号全在 + 1 个误提取」。

| 盘点里的写法 | next 实际 | 判读 |
|---|---|---|
| `PIER` | **`Kind.BRIDGE_PIER`** | 前缀化 |
| `SUPPORT` | **`Kind.BRIDGE_SUPPORT`** | 前缀化 |
| `TEMPLATE` | **`Kind.BRIDGE_TEMPLATE`** | 前缀化 |
| `fallingCap` | **`ROAD_FALLING_CAP` / `BRIDGE_FALLING_CAP`** / `AboveColumnClearer.fallingCapStartY/EndY` / `stabilizeFallingCap` | 枚举化 + 拆分 |
| `f18492b` | — | **误提取**（那是 commit hash，不是符号）|

`Kind` 完整枚举（`RoadConstructionVolumePlanner:52-63`）：
`ROAD` / `ROAD_FALLING_CAP` / `TUNNEL` / `TUNNEL_PORTAL` / `TUNNEL_JUNCTION` /
`BRIDGE_DECK` / `BRIDGE_FALLING_CAP` / `BRIDGE_TEMPLATE` / `BRIDGE_SUPPORT` / `BRIDGE_PIER`
—— 比盘点里点名的多，**没有少**。

> **★ 这是第二次踩同一个坑**：上一节（批 4）已经记过「grep 报缺失先查是不是改名」，
> 这一节又因为 `PIER`/`SUPPORT`/`TEMPLATE` 没带 `BRIDGE_` 前缀而误报 3 个。
> ⇒ 遇缺失**必须看该符号在盘点里的上下文写法**（这里是 `Kind.` 限定），
> 而不是只 grep 裸名字。

---

## 二之五、批 5（纵断面与后处理）—— 已核，127/127

来源：`docs/functional-inventory/05-纵断面与后处理.md`（15 道 pass + 坡度 AUTO + 平滑 +
端点锁定 + `PathPostProcessor` + 锯齿度量 + 急弯冻结）。

**127 项符号，126 直接命中；唯一「缺失」的 `SawtoothMetricTest` 是测试类**
（`common/src/test/java/.../postprocess/SawtoothMetricTest.java`），不在生产源码里 ⇒ **不算缺失**。

> ★ 这一条反过来也是个提醒：盘点里会引用**测试类名**（用来指「这件事有测试守着」），
> 只扫 `src/main` 会把它们误判成缺失。**核对脚本应当同时说清扫的是哪个根。**

---

## 二之六、批 7（验证与收尾）—— 已核；并彻底澄清「病根 B」的准确状态

批 7 没有专属的盘点文件（它的能力散在各域里），所以改用**目录级对比**。

### 1. `pathlogic/validation` —— 4/4 继承完好

| 类 | oracle | next | 判读 |
|---|---|---|---|
| `RoadDataValidator` | 544 行 | 549 行 | ✅ |
| `FinalRoadStructureValidator` | 455 行 | 462 行 | ✅ |
| `FinalRoadWaterValidator` | 371 行 | 373 行 | ✅ |
| `RoadPlanConsistencyService` | 216 行 | 220 行 | ✅ |

行数微增来自中文注释，**类一个不少**。

### 2. `metric` —— 8 类是 **next 新增**，oracle 根本没有这个包

| 类 | 行数 |
|---|---|
| `FastMarchingSolver` | 338 |
| `ArrivalTimeField` | 203 |
| `ObstaclePotential` | 187 |
| `GridTerrainField` | 162 |
| `FinslerMetric` | 133 |
| `TerrainField` | 105 |
| `ObstacleField` | 54 |
| `package-info` | 33 |

⇒ **这不是「旧功能迁移」，是新内核的建设成果**。核对「旧功能没丢」时不该把它们算进来。

### 3. ★ 彻底澄清「病根 B：指标不进决策」的准确状态

之前多处记录写「`QualityGate` **零调用**」，**这句话从一开始就搞错了对象**。实测：

- **`QualityGate` 是 next 新写的**（引入提交 `8f4ccfc`「端到端流水线 + 质量门 —— 新架构五层全部就绪」），
  **oracle 里完全没有这个类**（`grep -rl QualityGate roadweaver/` 零命中）。
  它的 javadoc 自己写着存在的理由：「旧实现 `RoadQualityMetrics` 的 javadoc 已经写着
  「every number on it is a regression guard」，但那些数字**只打日志、从不参与决策**」。
  **它不是「零调用」**——`PipelinePlanner:183` 每轮拓扑规划都调 `evaluateTopology`，
  只是 `report.passed()` 只决定打 `INFO` 还是 `WARN ... (REPORT-ONLY, still adopted)`。

- **真正「只打日志」的是旧指标 `RoadQualityMetrics`**（oracle 461 行 / next 462 行），
  它仍活着：`Road.java:640` 调 `RoadQualityMetrics.record(rd, rawStart, rawEnd)`
  —— **oracle 与 next 各 1 处调用**，继承完好、不是死代码。

⇒ **病根 B 的准确状态（三层）**：
  1. ✅ **「能拦」的机制已经建好了**（`QualityGate`，next 新增且已接线）；
  2. ⏳ **但阈值未标定所以不敢拦**（`Thresholds.lenient()` = 绕行比 3.0、允许孤立；
     `defaults()` 的 1.5 会把整张路网判死）—— **必须真机数据标定**；
  3. 🔴 **旧的 `RoadQualityMetrics` 仍在只打日志** —— 它的数字至今没接进任何决策。

  **所以「病根 B 已解决」和「病根 B 零调用」两种说法都不对。**
  准确说法是：**机制已建、阈值待标定、旧指标仍在空转**。

> **教训**：同一个概念在重构里会「换对象」——
> 旧的 `RoadQualityMetrics`（只打日志）→ 新的 `QualityGate`（能拦但不敢拦）。
> 记录时**必须写清楚是哪一个类**，否则「零调用」这种结论会挂在错误的类上，
> 一传十十传百，最后谁也不知道到底在说谁。

---

## 二之七、★ 生产调用链核对（补上「符在但没人调用」这个盲区）

第一节的局限④说「符号在但生产链根本没调用，grep 完全看不出」。这一节就是专门补这一条的：
**对批 3（施工与装饰）的 7 个职责，逐个追「谁在调它」**。

| 职责 | 符号/类 | 外部调用方 | 结论 |
|---|---|---|---|
| 单列放置入口 | `RoadBlockPlacer` | `SurfacePlacementUtil` | ✅ 在链上 |
| 三态保护列 | `ColumnAction` | `SegmentPaver` 内部（`:263` `SKIP`、`:289` `PAVE_SURFACE_ONLY`）| ✅ 在链上 |
| 上方清空 | `AboveColumnClearer` | `BridgeBuilder` / `BuoyBuilder` / `SurfacePlacementUtil` / **`SegmentPaver`** | ✅ 在链上 |
| 施工高度解析 | `SegmentPaver.planGrade` | `PathFeature:563/:580` | ✅ 在链上 |
| 运行期桥提升 | `promoteRuntimeBridges` | `PathFeature` | ✅ 在链上 |
| 隧道清空台账 | `TunnelClearLedger` | `RoadTunnelBuilder` | ✅ 在链上 |
| 保护判定 | `isProtectedExactConstructionBlock` | `BridgeBuilder` / `BridgeSegmentPlannerNew` / `ScenicDomeBuilder` | ✅ 在链上 |

**完整施工链（自底向上追到底）**：

```
LoadedChunkRoadBackfill:159   PathFeature.placeChunk(...)
  → PathFeature:580           SegmentPaver.planGrade(...)
  → SegmentPaver:311          SurfacePlacementUtil.placeOnSurface(...)
  →                           RoadBlockPlacer.placeRoadBlock(...)
```

链路接通，**没有断点**。

### ★ 顺带核实两条最关键的施工硬约束（都完好）

**① `PAVE_SURFACE_ONLY` 绝不走 `placeOnSurface`**（`SegmentPaver:289-311`）：

```java
if (columnAction == ColumnAction.PAVE_SURFACE_ONLY) {
    // 受保护的是 pos.below()，这一列绝不能走 placeOnSurface ——
    // RoadBlockPlacer.placeRoadBlock 会无条件 setBlock(pos.below(), chosen)，
    // 正好把受保护方块的状态改掉
    AboveColumnClearer.clearAboveColumn(...);      // 只清净空
    world.setBlock(pos, ..., 3);                    // 只铺路面那一格，pos.below() 一个字节不碰
} else {
    SurfacePlacementUtil.placeOnSurface(...);       // 只有 PAVE_NORMAL 才走这里
}
```

**② 隧道洞身禁止向上清空**（同处）：

```java
if (tunnelBody) {
    // 0.9.065：洞身段只清洞身净空 —— 上方岩体就是顶板，不能往上削
    AboveColumnClearer.clearTunnelBoreHeadroom(...);
} else {
    AboveColumnClearer.clearAboveColumn(...);
}
```

两条都在注释里写明**原因**与**历史版本号**，不是靠记忆维持的。

---

## 二之八、复核实际生产能力（第 4 轮）

对照为旧仓库 refactor/structure-20261112@2c6547a，next 起点为 818b076。以下来自实际调用链、行为测试或精确文本对拍。

| 场景 | 结论 |
|---|---|
| 禁行走廊、共用过河点 | 旧 PotentialFieldPathfinder:123–124 禁入禁行格，:154–160 降低指定走廊步进代价。next 的 ParallelOverlapResolver、CoarseCorridorPlanningAdapter 仍生成走廊，但起点的 BAND 均未消费；改道链还可能复用原路线或清空作用域禁令。已在 3a4327a 接回 FMM。窄禁带、单格绕行、优惠吸引三条离线入口回归通过。 |
| 群系偏好 | 旧 PotentialFieldPathfinder:330 调用 biomeCostFor。next 路径成本无此生产调用，配置、指纹和分类代码仍在。不要直接把旧版每格权重或群系预筛塞回新版。 |
| 特大树预留 | 旧 PotentialFieldPathfinder:137 在选线查 HugeTreeReservation.isReserved。next 仅 API 与 RoadConstructionSafety 调用。施工保护仍在；规划期绕开已预留树的能力没接回。实际命中率未核。 |
| 施工、道路复用 | 两版 PathFeature 均进入模板桥、路面坡度铺设、隧道 apply、NETWORK_REUSE。确认生产调用在，不等于真实地形产物等价。 |
| 地图底图 | 旧 MapTerrainSamplingService 的生产全文只有类内 4 处，无其它入口；两版 RoadMapScreen 都调用 Xaero 底图。未发现删除该类导致已可用底图丢失的证据。 |
| 存档边界 | 两版 RoadData.CODEC 初始化文本相同；路网分片 schema 2、施工台账 schema 3、待生成路径世界指纹 schema 1 一致。未用真实存档验证迁移。 |

旧版只作功能证据；后续看 [重制版的下一阶段](NEXT-DIRECTION-20261115.md)。

---

## 三、原七批次的符号总账（2026-11-15）

| 批 | 对应盘点 | 项数 | 结论 | 一节内位置 |
|---|---|---|---|---|
| 1 纵断面与坡度 | `05`（已是 DONE，见总纲独立证据）| — | 未逐项重核 | — |
| **2 桥隧判定** | `03-跨度与桥隧.md` | **131** | ✅ 无缺失 | 二之三 |
| **3 桥体与施工** | `06-施工与装饰.md` | **62** | ✅ 无缺失（5 项为改名）| 二之四 |
| **4 路口与合并** | `02` 第五节 | **16** | ✅ 无缺失 | 二 |
| **5 几何后处理** | `05-纵断面与后处理.md` | **127** | ✅ 无缺失 | 二之五 |
| **6 结构避让** | `02` 的一~四、六、七节 | **68** | ✅ 无缺失 | 二之二 |
| **7 验证与收尾** | 无专属盘点 → 目录级对比 | — | ✅ `validation` 4/4 继承完好；`metric` 8 类为**新增** | 二之六 |
| **符号级合计** | | **404** | **✅ 零缺失** | |

### 这个总账能说明什么、不能说明什么

**能说明**：`docs/functional-inventory/` 里点名的每一个关键符号，
在 next 的生产源码里都还能找到同名或改名后的对应物。第二之八节给出了**符号仍在、行为丢失**的实例。

**不能说明**：
- ❌ **行为等价**。符号在 ≠ 判据没改。
- ❌ **生产调用链覆盖**。符号在但没人调用，grep 看不出来（`metric` 包里就有这种）。
- ❌ **参数值等价**。只核了少数几个常量值（`MIN_RUN=6`、`MAX_INJECTED=4096/2048`、
  `ENDPOINT_PROTECTED_GAP_RADIUS_BLOCKS=32` 等），大多数参数没核。
- ❌ **批次完成度**。这是本文最容易被误读的地方，详见下一节。

---

## 三之二、★ 务必分清三件事

这三件事经常被混成一句「批 X 完成了」，但它们互相独立：

| 概念 | 判据 | 当前状态 |
|---|---|---|
| **登记符号仍在** | 本文件的符号级核对 | 404 项有同名或改名对应物；实际功能见第二之八节 |
| **代码已迁移/拆分** | next 与 oracle 的类数/行数对比（见总纲批次表）| ✅ 批 4/5/6/7 已完成拆分 |
| **批次真正完成** | 新内核接线 + 阈值标定 + 真机验收 | ❌ 批 2/3/7 都还没到 |

**具体地**：
- **批 2**：已有公路探针已进入跨度观测链，生产可选回写已接入，默认关闭。ProfileDpSource 的 crossing 输入仍来自旧判定；缺真机对照，本轮保持不动。
- **批 3**：符号全在，但除了「`BuildFrame` 缓存键纳入 spans」那一项实质修复外，
  其余桥体/施工职责**仍待逐项核对**（不是核对符号，而是核对「每个职责在新链路上是否真的生效」）。
- **批 7**：`validation` 全在，但 `QualityGate` 仍是 **REPORT-ONLY**（阈值需真机标定）。

符号盘点不能判定功能等价，也不能代替批次验收。本轮确认的退化与未核范围以第二之八节为准。

---

## 四、后续该怎么继续

---

1. **每核完一个域，就在第二节的表里加一节**，不要只写「已核 8 个域」这种汇总 ——
   逐条留痕才能在被质疑时回查。
2. **遇到 grep 报「缺失」先别下结论**，按第一节第 3 条查是不是改名或表达式定义。
   本轮踩了**三次**同一个坑：`MAX_JUNCTION_CONSTRUCTION_OVERHEAD`（表达式定义）、
   `PIER`/`SUPPORT`/`TEMPLATE`（没带 `BRIDGE_` 前缀）、`SawtoothMetricTest`（测试类不在 `src/main`）。
3. **不要用这份文档替代真机验收**。符号全在、行为全错，grep 一样全绿。
   真机验收的边界见 `docs/STATUS-20261115.md`。
4. **新增能力时不必登记**：本文件只核「旧版有没有丢」，next 新增的东西
   （`metric` 8 类、`QualityGate`、`pipeline`/`topology`/`band`）**本来就不该出现在这里** ——
   把它们当「已迁移」会让总账虚高。