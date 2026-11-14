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

## 三、还没核的批次

| 批 | 对应盘点 | 状态 |
|---|---|---|
| 1 纵断面与坡度 | `05-纵断面与后处理.md` | 未核（但「重制总纲」已标 DONE 且有独立证据）|
| 2 桥隧判定 | `03-跨度与桥隧.md` | **未核** —— 且已知有两处未完成（回写、已有公路来源）|
| 3 桥体与施工 | `06-施工与装饰.md` | **未核** |
| 5 几何后处理 | `05-纵断面与后处理.md` | **未核** |
| 6 结构避让 | `02` 的一~四、六、七节 | ✅ **已核 68/68**（见二之二）|
| 7 验证与收尾 | 散在 `01`/`07`/`08` | **未核** |

**下一步**：按 `02` 的一~四、六节把批 6 核完，然后 `03`（批 2）、`06`（批 3）、`05`（批 5）。

---

## 四、怎么用这份文档

1. **每核完一个域，就在第二节的表里加一节**，不要只写「已核 8 个域」这种汇总 ——
   逐条留痕才能在被质疑时回查。
2. **遇到 grep 报「缺失」先别下结论**，按第一节第 3 条查是不是改名或表达式定义。
   本轮就差点把 `MAX_JUNCTION_CONSTRUCTION_OVERHEAD` 误报成缺失。
3. **不要用这份文档替代真机验收**。符号全在、行为全错，grep 一样全绿。
   真机验收的边界见 `docs/STATUS-20261115.md`。