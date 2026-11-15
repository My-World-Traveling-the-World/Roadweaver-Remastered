# GUI 与业务逻辑耦合审计（2026-11-15）

> **这份文档回答**：用户长期要求「UI 界面独立出来，好更改接线功能」——
> 现状到底有多独立？哪些是必须改的，哪些是本就该有的。
>
> **结论先说**：**不算差，但有 5 处真越界** ——
> UI 层**直接读持久化**、**直接调 worldgen 编译器**、**直接找生成期管理器**，
> 而不是走已有的服务层。这 5 处正是「改接线时要同时改 UI」的根源。

---

## 一、方法与数字

判据：`common/src/main/java/.../client/**` 下每个类，看它的 `import` 指向哪些**顶层包**。

| 包 | 类数 | 行数 | 性质 |
|---|---|---|---|
| `client` | 74 | 12,926 | UI / 渲染 / 屏幕 |
| `route` | 29 | 9,954 | 手绘路线编辑器（注：它**自带 UI**，见第四节） |
| `map` | 22 | 1,567 | 地图服务层（权限 / 搜索 / 瓦片） |
| `decoration` | 21 | 2,079 | 装饰（世界侧） |
| `network` | 5 | 607 | 网络封包 |

`client/*` 对外依赖分布（import 次数）：

```
client -> client       78    ← 内部协作，正常
client -> map          23
client -> core         22    ← 数据模型（RoadData 等），正常
client -> config       21    ← 读配置显示，正常
client -> planning     14    ← ★ 业务逻辑
client -> generation    6    ← ★ 业务逻辑
client -> route         3
client -> persistence   3    ← ★ 持久化
client -> pathlogic     3
client -> network       2
client -> util/worldgen 2
```

**只有 13 个 `client` 类 import 了业务包**（13 / 74 = 18%），这个比例不算高。

---

## 二、13 个文件的分类

### 2.1 可接受（8 个）—— 读「状态快照」或「纯工具函数」

| 文件 | 行数 | 依赖 | 为什么可接受 |
|---|---|---|---|
| `RoadMapScreen` | 999 | `AutomaticPlanningSamplingBounds` / `TerrainSamplingSessionSnapshot` | 两者都是**只读快照/边界值**，显示层需要它 |
| `LoadingOverlayText` | 199 | `InitialGenerationProgressSnapshot.RoutePhaseProgress` / `InitialGenerationStage` | **枚举与快照**，纯数据 |
| `MapStatusRenderer` | 158 | `TerrainSamplingSessionSnapshot` | 只读快照 |
| `MapSnapshotCache` | 128 | `WorldgenAvoidance` | **静态工具函数**（距离/包含判定），不是控制流 |
| `ClientMapTileTextureCache` | 300 | `WorldgenAvoidance` | 同上 |
| `MapSnapshotStore` | 449 | `WorldgenAvoidance` / `PlanningUtils` | 同上（`PlanningUtils.pos2dKey` 是纯函数） |
| `MapAutomaticPlanningSamplingCache` | 57 | `AutomaticPlanningSamplingBounds` | 只读边界 |
| `MapAutomaticPlanningSamplingOverlayRenderer` | 54 | `AutomaticPlanningSamplingBounds` | 只读边界 |

**这 8 个不改。** 「UI 读业务状态」是必然的，问题只在「怎么读」。

### 2.2 ★ 真越界（5 个）

| # | 文件 | 行数 | 越界调用 | 违反了什么 |
|---|---|---|---|---|
| 1 | `MapRoadCollector` | 102 | `RoadShardStorage.queryRect(level, ...)`（`:35`）<br>`RoadShardStorage.computeFingerprint(road)`（`:81`）<br>`RoadChunkPlanCompiler.normalizeForPhysicalOwnership(...)`（`:37`） | **UI 直接查持久化 + 调 worldgen 编译器**。最重的一处 |
| 2 | `MapStructureCollector` | 220 | `StructureFileStorage.getStructureSnapshot(level)`（`:37`）<br>`StructureFileStorage.SOURCE_MANUAL/PREDICTED`（`:34-35`） | **UI 直接读结构档案** |
| 3 | `MapConnectionCollector` | 47 | `WorldDataProvider.getInstance()`（`:23`） | **UI 直接取世界数据单例** |
| 4 | `LoadingGenerationOverlayRenderer` | 513 | `InitialGenManager.isActive()`（`:87`）<br>`InitialGenManager.getProgressSnapshot()`（`:89`） | **UI 直接找生成期管理器** |
| 5 | `MapScreenQueries` | 72 | `AutomaticPlanningSamplingActivities` 等 4 个 planning 类型 | 混了「活动状态查询」与「只读快照」，前者是控制面 |

**为什么这 5 个是真问题**（而不是「读状态」）：
- 前 3 个**直接碰持久化**（`RoadShardStorage` / `StructureFileStorage` / `WorldDataProvider`）
  ⇒ 存档格式一改、存储后端一换，UI 就得跟着改；
- 第 1 个还**调了 `RoadChunkPlanCompiler`** —— 那是 worldgen 侧的「物理归属归一化」，
  让 UI 去调它意味着 UI 必须理解生成期的所有权模型；
- 第 4 个直接依赖 `InitialGenManager` 这个**管理器类**，而不是一个「进度查询接口」
  ⇒ 生成期内部重构（比如把 `InitialGenManager` 拆了）就会波及 UI。

---

## 三、★ 关键观察：仓库里**已经有服务层**，但这 5 处没用

| 已有服务 | 位置 | 谁在用 |
|---|---|---|
| `MapAccessService` | `map/permission/` | 只有 `command/MapAccessCommand` |
| `MapStructureSearchService` | `map/search/` | 只有 `network/MapNetworkPayloads`（还只用了它的 `MAX_QUERY_LENGTH` 常量） |
| `MapPatchService` | `map/` | 服务端 |

⇒ **服务层不是不存在，是 UI 绕过了它。**
这也是「改接线要同时改 UI」的直接原因：接线在服务层，UI 却直连底层。

---

## 四、另一个更值得注意的事实：`route` 包自带 UI

`route` 包 **29 类 / 9,954 行**，里面既有：
- **纯逻辑**：`RouteCodec` / `RoutePreload*` / `RouteBuildRegistry` / `RouteRoadDataPatcher`
- **UI 与交互**：`RouteWorldEditor` / `RoutePreviewRenderer` / `RouteMapRenderer` / `RouteEditorSession`

它们**平铺在同一个包里**，没有 `route/client` 与 `route/core` 之分。
按「UI 独立化」这个目标，这是比 `client` 那 5 处更系统性的问题 ——
因为 `client` 至少是「UI 包依赖业务包」，方向清楚；而 `route` 是**混在一起**。

---

## 五、建议的修法（分层，按性价比排序）

### 第 1 步：给 UI 一个只读查询端口（收益最大、风险最低）
新增 `map/query/MapQueryPort`（接口 + 默认实现）：
```
RoadView[]        roadsIn(Rect)
StructureView[]   structuresIn(Rect)
ConnectionView[]  connectionsIn(Rect)
GenerationProgressView generationProgress()
```
- `MapRoadCollector` / `MapStructureCollector` / `MapConnectionCollector` /
  `LoadingGenerationOverlayRenderer` 全部改走它
- **实现放服务端侧**，`RoadShardStorage` / `RoadChunkPlanCompiler` / `InitialGenManager`
  的细节不再漏进 UI
- 这一步**不改任何行为**（纯搬调用），可以配 characterization 测试证明输出不变

### 第 2 步：把 `route` 拆成 `route/core` 与 `route/ui`
**风险中等**，因为会动 import 与包路径，但纯搬迁。

### 第 3 步：给架构守卫补规则
现在 `ArchitectureGuardTest` 的 `LAYER_RULES` 只覆盖 5 个求解包，
`client` 对 `persistence` / `generation` 的依赖**没有任何规则**。
补一条：`client` 不得直接 import `persistence` / `worldgen` / `generation`（`progress` 子包除外）。

---

## 六、这份审计**没有**做什么

- **只看了 import 关系**，没看方法调用深度（比如 `RoadMapScreen` 那 999 行内部
  是否还有 UI 与逻辑混写）。
- **没核 `fabric` 模块的 `client/fabric/*`**（那是配置界面 Section，24 个类），
  它们大概率只是「配置项绑定」，但没逐个确认。
- **没核 `network` 包**：UI 通过封包拿数据是正常架构，本文没把它算作越界。
- **没有真机验证**：判断全是静态的，「改起来疼不疼」要看实际改动。
- **`decoration` 包（21 类 / 2,079 行）没审** —— 它是世界侧，不是 UI。

---

## 七、与用户长期要求的关系

用户原话：「**ui界面独立出来，好更改接线功能**」。

这份审计给出的答案：**方向是对的，但有 5 个漏点没堵上**。
堵上之后「改接线」就只需要改服务层 + 查询端口实现，UI 一行不用动。
`route` 包的「UI 与逻辑平铺」是第二优先，因为它是**系统性问题**而非几个漏点。