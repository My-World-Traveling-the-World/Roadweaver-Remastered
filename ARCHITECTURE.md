# RoadWeaver 架构（2026-11-12 重构）

本文件与代码<b>同源受检</b>：`ArchitectureGuardTest` 会检查这里的包职责表与
代码里真实存在的包是否一致。改架构必须同时改这里。

---

## ⚠ 时效性更正（2026-11-13 补，**读下文前先读这一节**）

本文开头那句「与代码<b>同源受检</b>」**远强于实际**。核对后的真实情况：

### 一、机器到底查了什么

`ArchitectureGuardTest.架构文档必须说明每个新架构包` 的实际断言只有一条：

```java
for (String pkg : SOLVER_PACKAGES)          // = ["metric","topology","band","pipeline"]
    if (!text.contains(pkg)) missing.add(pkg);
```

也就是说：**它只检查本文件里出现了这 4 个包名这几个字串**。
下面的依赖规则表、基线数字、包结构图，**没有任何一条被机器校验**。

### 二、`foundation.*` 这套分层**从来没有落地**

下表「目标分层」里的 `foundation.core` / `foundation.terrain` /
`foundation.constraint` / `foundation.build` —— **这些包在代码里不存在**：

| 文档写的 | 代码实际 |
|---|---|
| `foundation.core` | `core`（散在顶层）|
| `foundation.terrain` | `pathfinding/sampling` + `persistence` |
| `foundation.constraint` | **不存在**（避让判据还在 `pathlogic/structures`、`search`、`structures`）|
| `foundation.build` | **不存在**（施工在 `pathlogic/construction`、`pathlogic/tunnel`、`decoration`）|

真实架构是：**保留旧仓的顶层包结构，就地重构**，另外长出 6 个新求解包。
顶层包实际清单（`common/src/main/java/net/shiroha233/roadweaver` 下一级）：

```
apiimpl(2)  band(3)  bridge(6)  client(77)  command(1)  compat(7)  config(39)
core(12)    decoration(24)  generation(22)  map(31)  metric(8)  mixin(1)
network(5)  pathfinding(96)  pathlogic(83)  persistence(24)  pipeline(10)
planning(53)  postprocess(13)  route(29)  runtime(7)  search(3)  spec(4)
structures(24)  topology(5)  util(3)  worldgen(7)
```

> 所以「目标分层」那一段应理解为**当时的设想**，不是现状。
> 真正被强制的依赖约束是 `deps-baseline.txt`（162 条包依赖边的 ratchet）+ `LAYER_RULES`。

### 三、底部的基线表已全部过期

| 指标 | 本文写的（2026-11-12）| 实测（2026-11-13）|
|---|---|---|
| 超过 800 行的类 | 29 个 | **19 个** |
| 有「文件职责」头注的文件 | 172 / 461 = 37.3% | **595 / 595 = 100%**（且有 `duty == total` 硬断言）|
| 超过 60 个方法的类 | 11 个 | 未复核（守卫不查这一项）|

### 四、两处与代码对不上的具体说法

| 本文写的 | 实际 |
|---|---|
| `PathfindingAlgorithm.BAND` 是第 5 个寻路算法实现 | **`PathfindingAlgorithm.java` 不存在**。`pathfinding/PathfinderFactory` 直接 `import bridge.BandPathfinder` |
| `StructureAvoidanceService` = 3,542 行 / 124 个静态方法 | 现在约 **940~1000 行 / 67 个静态方法**（后续轮次又拆过）|

### 五、本文**仍然成立**的部分（这些可以照读）

- 「为什么重构」那张职责分散表 —— 描述的是**旧仓**的病灶，历史事实；
- 「两层必须记录的架构决定」（用已预热的地形场而不是重采样、步长只是搜索粒度）；
- 弹性带的关键性质（直线输入严格不变、曲率不可能上升、μ=0 必须拒绝）；
- 「必须保留的功能」表；
- 「中文注释规范」；
- 附：`StructureAvoidanceService` 的功能域地图 —— **方法清单仍有参考价值**，
  但行数与"剩余 124 个静态方法"这类数字已过期。

---


## 为什么重构

旧仓 109,275 行里，包语义边界已经崩了：

| 真实职责 | 分散在几个包 | 位置 |
|---|---|---|
| 数据模型 | 3 | `core` · `pathlogic/Road` · `route` |
| 地形采样 | 3 | `pathfinding/sampling` · `pathfinding/impl` · `persistence` |
| 寻路算法 | 2 | `pathfinding/impl` · `planning/RawPathSearch` |
| 几何后处理 | 2 | `postprocess` · `pathlogic/pathcalc` |
| 纵断面 | 2 | `pathlogic/grade` · `planning/terrain` |
| 结构避让 | 3 | `pathlogic/structures` · `search` · `structures` |
| 施工 | 3 | `pathlogic/construction` · `pathlogic/tunnel` · `decoration` |
| 路网 | 2 | `pathlogic/ExistingRoadJunction` · `network` |
| 编排 | 2 | `generation` · `planning` |
| 客户端 | 3 | `client` · `map` · `route/渲染` |

**一个职责分散在 2~3 个包，一个包（`pathlogic`）含 6 个不相干职责。**

根本病因：**A\* 同时背了「拓扑」和「几何」两个职责**，几何只能靠事后
8 个阶段去修，每修一次多一个 artifact。

---

## 目标分层

```
api / api-fabric          契约（25 文件，冻结）
        ▲
foundation                不变层（行为锁死，只重构结构）
  core          RoadData / RoadSegment / SpanType / 常量
  terrain       采样 + 瓦片缓存 + OpenCL/CPU 后端
  constraint    结构避让 / 村庄盒 / 保护缺口 / 长城（判据不动）
  persistence   计划缓存 / 采样文件缓存
  build         铺路 / 桥 / 隧道 / 装饰
  worldgen      落块 / 树木抑制 mixin
  config        ★ 元数据表（241 键，一表生成全部）
  compat        跨 mod（apiimpl / runtime，冻结）
        ▲
metric            Finsler 度量 + Fast Marching
        ▲
topology          MST / Steiner
        ▲
band              弹性带（纯线性 thin-plate）
        ▲
pipeline          阶段编排 + QualityGate + 预热进度
        ▲
client / fabric   状态渲染分离 / 平台壳
```

## 依赖规则（`ArchitectureGuardTest` 强制）

| 包 | 允许依赖 | 禁止依赖 |
|---|---|---|
| `api` | 无 | 一切内部包 |
| `foundation.*` | `api` | `metric` / `topology` / `band` / `pipeline` / `client` |
| `metric` | `foundation`, `api` | `topology` / `band` / `pipeline` |
| `topology` | `metric`, `foundation`, `api` | `band` / `pipeline` |
| `band` | `topology`, `foundation`, `api` | `pipeline` |
| `pipeline` | 全部求解层 | — |
| `bridge` | foundation（含 `pathlogic` 避让判据）+ 全部求解层 | —— （**任何求解层不许 import bridge**）|
| `client` | `pipeline`, `foundation`, `api` | 求解层内部实现 |

**这句话的实际强度（2026-11-13 独立审计核实后修正）：**

上面那张表**只约束 5 个包**（`metric` / `topology` / `band` / `pipeline` / `bridge`）。
其余 **24 个顶层包没有任何方向规则** —— 它们的依赖由两份 ratchet 守着：

| 守卫 | 管什么 | 管不了什么 |
|---|---|---|
| `deps-baseline.txt`（边 + 次数）| 新增一条边 / 已有边上的 import 变多 | **循环**：A→B 与 B→A 各自都是合法边 |
| `bidirectional-baseline.txt`（34 对）| **新形成一对互相 import** | 已有循环的消除（那是重构任务） |

所以准确说法是：

> **对那 5 个求解包：方向错了会红。**
> **对其余 24 个包：新增边、import 变多、新形成循环会红；已有的 34 对循环不会红（它们是已知债务）。**

实测数据（审计 + 我复算，一致）：全仓 **157 条边**、**34 对双向依赖**。
最重的几对：`pathlogic<->planning` 68/84、`generation<->pathlogic` 74/84、
`generation<->planning` 85/30、`pathfinding<->pathlogic` 35/89。

---

## 三层算法

### 第一层 · metric：Finsler 度量 + Fast Marching　✅ **已实现（2026-11-12）**

实现了 5 个文件 + 8 个测试用例（`PASS tests=502`）：

| 文件 | 职责 |
|---|---|
| `TerrainField` | port：`height(x,z)` + `gradient(x,z)`，与采样实现无关 |
| `ObstacleField` | port：连续障碍势（不是布尔「能不能走」） |
| `FinslerMetric` | `c(x,θ) = 1 + λg(∇h·θ̂)² + λo·V(x)` |
| `FastMarchingSolver` | Godunov upwind + 堆驱动，`O(n log n)` |
| `ArrivalTimeField` | 到达时间场 + 负梯度回溯出路径 |

**测试抓到两个真实实现 bug**（都是「看起来对、跑起来错」的类型）：

1. **慢度方向写反**：`slowness = 1/cost` ⇒ 高代价格子变成「走得快」⇒ 路径直接穿墙。
   `cost` 本身就是每单位长度的代价，Eikonal 里 `|∇T| = cost`，慢度就是 `cost`。
2. **慢度取错格**：用「已冻结格」的代价去更新邻居 ⇒ 墙后的便宜格子把值倒灌回墙格
   ⇒ **一道 9 倍代价的墙完全透明**（`T(30,20)` 恰好等于直线 10.0）。
   迎风格式必须取「正在求解的那一格」的慢度。

**已量化的已知限制**：一阶 4 邻域格式在 45° 方向高估欧氏距离约 21%（理论值）。
这不是 bug —— 上层只用它决定拓扑与走位，精确几何由 `band` 层修正；换二阶或 8 邻域可压到 2~5%，属 V2。

```
c(x, θ̂) = 1 + λ_grade·(∇h(x)·θ̂)² + λ_obs·V(x)
```

Eikonal 方程 `|∇T| = 1/c`，Godunov upwind 离散：

```
T¹ = min(T[i-1,j], T[i+1,j]);  T² = min(T[i,j-1], T[i,j+1])
若 |T¹−T²| ≥ s·h :  T = min(T¹,T²) + s·h
否则             :  T = (T¹ + T² + √(2(s·h)² − (T¹−T²)²)) / 2
```

堆驱动 `O(n log n)`；梯度流回溯得到<b>方向连续</b>的路径。

### 第二层 · topology：MST / Steiner

用第一层的测地距离矩阵求最小生成树 —— **边天然不相交**，
消掉旧实现的 `parallel corridor` / `braid` / `mergeRoads` 三套检测器。

### 第三层 · band：弹性带（线性）　✅ **已实现（2026-11-12）**

实现了 2 个文件 + 7 个测试用例：

| 文件 | 职责 |
|---|---|
| `ElasticBandSolver` | 五对角稀疏求解（PCG + Jacobi 预条件）|
| `BandResult` | 平滑结果 + **曲率前后对比**（供质量门判断有没有变好）|

**关键性质（都被测试钉住）**：

- **直线输入严格不变** —— 这是旧实现 DP+Catmull-Rom 的钉子：它曾在「长直段+转弯」处
  冲出 **9 格（L=200）到 53 格（L=800）**。弹性带用自然边界 `γ''=0`（镜像虚点），
  直线是精确极小，CG 初始残差为 0、一次都不迭代。
- 锯齿曲率降 >50%、振幅从 4 降到 3 以下
- 端点锁死（它是结构端点坐标，动了就连不上）
- 曲率**不可能上升**（30 条随机折线逐条验证）
- μ 语义：0.01 几乎拉直、100 几乎不动
- μ=0 必须拒绝（线性系统退化成求核空间，解不由输入决定）


```
min ∫|γ''|² + μ∫|γ−γ₀|²     ⇒  五对角稀疏系统，一次 PCG
```

---

## 新架构已接进真实规划链（2026-11-12）

`PathfindingAlgorithm.BAND` 是第 5 个寻路算法实现，由 `PathfinderFactory` 选择。
**出厂默认仍是 `POTENTIAL_FIELD`** —— 换算法必须显式选择。

```
PathfindingCostConfig.pathfindingAlgorithm = BAND
        ↓ PathfinderFactory
bridge/BandPathfinder implements pathfinding/Pathfinder      ← 第 5 个实现
        ↓
PathTerrainField（旧链已预热好的量化地形）→ StridedTerrainField
StructureAvoidanceService.blocksPlanningObstacle（判据一字不改）→ 硬挡掩码
        ↓
FinslerMetric → FastMarchingSolver → ArrivalTimeField.pathTo → ElasticBandSolver
        ↓
PathResult.raw(List<BlockPos>)   ← 旧链照常消费
```

### 两条必须记录的架构决定

**① `PathterrainField` 而不是重新采样。**
旧链已经把地形预热成量化场（带缓存、带水体标记）。重建采样会把预热成本再付一次，
而且两边看到的地形可能不一致 —— 那会让 AB 对比失去意义。

**② 步长只是搜索粒度，不再是几何粒度。**
旧 A* 的 `aStarStep` 同时决定了搜索粒度和几何粒度，所以 8 格步长直接让路变成
8 格折线。新架构里步长只影响场求解的网格；几何由弹性带在**方块坐标**里做。

### 唯一被放行的 foundation → bridge 跨界

`pathfinding/PathfinderFactory`（foundation）要构造 `BandPathfinder`（bridge）。
这是全仓唯一的 foundation → 求解侧依赖，已在 `ArchitectureGuardTest` 的白名单里
显式声明。旧链对求解层内部结构一无所知，只知道 `Pathfinder` 这一个契约。

### 第一版的已知边界

- 不做纵断面：`RoadGradePlanner` 保留，吃本类输出的中心线
- 不做桥隧判定：`PathSpanExtractor` 保留，同样吃站序列

---

## 求解链已端到端打通（2026-11-12）

`pipeline/` 落地后，从节点到路网可以完整跑通：

```
节点集
  ↓  N 次场求解
GeodesicMatrix（测地距离矩阵）
  ↓  Kruskal + 并查集
TopologyPlan（最小生成森林，边天然不相交）
  ↓  按源分组再求解一次 + 梯度流回溯
ArrivalTimeField.pathTo（方向连续的准测地线）
  ↓  PCG 五对角求解
ElasticBandSolver（曲率受限的平滑曲线）
  ↓
RoadNetworkPlan → QualityGate
```

| 文件 | 职责 |
|---|---|
| `RoadPipeline` | 两轮求解：矩阵（场丢弃）→ MST → 只对 MST 用到的源重解并回溯 |
| `RoadNetworkPlan` | 节点 + 边（测地距离 / 直线距离 / 曲线 / 弹性带诊断）|
| `QualityGate` | 把指标接到「接受/拒绝」上，不再只打日志 |

**为什么必须两轮求解**：拓扑决策需要完整距离矩阵，但那时还不知道哪几条边进 MST；
若在第一轮为每个源保留整张时间场，24 节点 × 49 万格 × 8 字节 ≈ 94 MB 不能常驻。
两轮代价不超过 2N 次求解，内存 O(1)。

**质量门四条判据**：连通性、加权绕行比（默认 ≤1.5，旧实现真机 2.93）、
曲率不得上升、端点必须等于节点坐标。

---

## 必须保留的功能

| 功能 | 实现位置 | 为什么不能动 |
|---|---|---|
| 开局预热动态进度 | `generation/progress/*`（825+142+585+135 行）| 已是「状态→快照→渲染」三件套，有单测 |
| 桥梁 / 隧道建造 | `pathlogic/tunnel`, `pathlogic/bridge`, `pathlogic/construction` | 吃 `List<RoadSegment>`，与算法无关 |
| 树木抑制 | `GenericVegetationFeatureMixin` + `RoadSpatialIndex` | worldgen mixin 层，问「这个 chunk 有路吗」 |
| 村庄端点筛选 | `StructureAvoidanceService` 的 `route filtered` | **在选线之前**，几何重构碰不到 |
| 结构 / 建筑避让判据 | `StructureAvoidanceService` | 用户已拍板「村庄裁切包围盒保留原判据」 |
| 装饰 | `decoration/*` | 吃站序列 |
| 跨 mod 契约 | `api` / `apiimpl` / `compat` / `runtime` | 2026-11-12 刚真机验证通过 |

---

## 架构守卫基线（只许降不许升）

`ArchitectureGuardTest` 用 ratchet 机制：记录当前违规集合，
之后只允许<b>减少</b>，不允许增加。

| 指标 | 基线（2026-11-12） | 目标 |
|---|---|---|
| 超过 800 行的类 | **29 个** | 0 |
| 超过 60 个方法的类 | **11 个** | 0 |
| 有「文件职责」头注的文件 | **172 / 461 = 37.3%** | 100% |

---

## 中文注释规范

每个 `.java` 文件首行必须是：

```java
/* 文件职责：<做什么、不做什么>—— 一句话。 */
```

每个 `public` 类必须有中文 javadoc，并写清：

1. **职责** —— 这个类负责什么、不负责什么
2. **为什么这样设计** —— 关键约束、数学依据
3. **踩过的坑** —— 历史事故与它们的钉子（测试）

---

## 附：`StructureAvoidanceService` 的功能域地图（2026-11-12 实测）

这个类是新仓里最后一块「上帝静态类」。它从 4,766 行拆到 3,542 行（-1,223）之后，
剩余部分**已经没有可整体搬出的嵌套类型**了 —— 全部是 **124 个静态方法**。
所以要继续拆分，只能按下面的功能域**成簇搬方法**，而不是搬类型。

### 已搬出的部分（可对照这些文件看职责边界）

| 文件 | 行数 | 职责 |
|---|---|---|
| `PieceOccupancy` | 387 | 单个结构碎片的逐块占位判定（实心/杂物/会掉的块）|
| `PrepareTiming` | 133 | 准备阶段分段计时（纯记账，不参与判定）|
| `OwnedStructureBox` | 57 | 带归属信息的结构包围盒，碰撞判定转发给 `PieceOccupancy` |
| `PreparedStartKey` / `RouteKey` / `StructureOwnerKey` | 12 / 6 / 6 | 三个缓存键 record |
| `PlanningSnapshot` | 772 | 规划期不可变结构快照（哪些盒挡路、哪些端点豁免）|
| `PlanningSnapshotUnavailableException` | 9 | 「没有快照」这条路径的显式异常 |

### 剩余 3,542 行的功能域划分（按方法名与行号聚类）

| 功能域 | 方法（示例） | 大致行数 | 可否独立？ |
|---|---|---|---|
| **A. 世界生成期避让查询** | `shouldAvoid` / `shouldAvoidAny` / `shouldAvoidConstruction` / `shouldAvoidPrepared*` / `resolveWorldgenEntry` / `collectPieces` | ~290 | 可，但被 `Road` / 施工侧直接调，需同步改调用点 |
| **B. 路由线准备与快照编排** | `preparePlanningSnapshotsAsync`(×2) / `preparePlanningSnapshotsForPathsAsync`(×3) / `ensurePlanningSnapshotForPathAsync` / `preparePathCorridorSnapshots*` | ~350 | 可，依赖 `PreparedStartResolver` 域 |
| **C. 端点筛选与归属推断** | `EndpointFilterOutcome` / `filterUnknownEndpointInsideVillage` / `endpointMatchesStructure` / `resolveEndpointOwners*` / `inferNearbyEndpointOwner` / `levelOwner` | ~520 | **最适合下一刀**：输入输出清晰，外部只经 `PlanningSnapshot.endpointOwners()` 消费 |
| **D. 障碍判定与建筑规避** | `blocksVerifiedBuilding` / `blocksPlanningObstacle` / `blocksVillageCluster` / `buildClusterBoxes` / `describePlanningCollision` / `planningCollisionBox` | ~180 | 可，是对外契约最核心的一块 |
| **E. 保护缺口与过村逻辑** | `isProtectedGapAcceptable` / `protectedGapSpans` / `passThroughVillageCrossings` / `evaluateProtectedGapPattern` / `hasOnlyEndpointProtectedGaps` | ~230 | 可，但判据与旧仓 `PROTECTED_GAP` 逐字对应，改结构时**不许动判据** |
| **F. 走廊构建** | `buildPathCorridors` / `addChunkLine` / `collectCorridorStartKeys` / `withinCorridorPath` / `predictObstacleCandidates` | ~290 | 可，纯几何 |
| **G. 结构起点解析** | `resolvePreparedStartsAsync`(×4) / `resolvePreparedStartWindow` / `resolvePreparedStartBatchOnce` / `createPreparedStartFuture` / `acquireNativeDirectLane` / `requestChunkAsync` / `storePreparedStart` / `cachedPreparedStart` | ~735 | **最大的一块**，已于 2026-11-12 尝试搬出但编译未通过而回退（见下）|
| **H. 小工具** | `normalizeSource` / `distanceSquaredToSegment` / `putBounded` / `trimBounded` / `elapsedMillis` / `intersectsConstructionBand` | ~80 | 可，纯函数 |

### ★ 尝试搬 G 域时遇到的拦路问题（下次动手前必须先解决）

把 2,229~2,962 行整块切成 `PreparedStartResolver` 后编译报：

```
PreparedStartResolver.java:121: 错误: 未结束的注释
PreparedStartResolver.java:2:   错误: 未声明的类不应有程序包
```

- 已排除：切片首尾边界正确（2,229 是方法签名、2,962 是方法闭合、2,963 是空行）；
  逐行扫描块注释深度显示**整个文件是平衡的**；把生成的中文 javadoc 换成纯 ASCII **错误不变**。
- 因此问题在**搬出的正文**里，与注释文字无关。下一刀的定位办法：
  把切片二分成两半分别生成文件编译，用二分法把 `/*` 的来源缩到一个方法内。
- 一旦定位，G 域仍是最值得搬的一块（735 行，且域内几乎不依赖 A~F）。

### 双向静态导入：拆静态工具类的标准手法

搬静态方法簇时**两侧调用点都可以一个字不改**：

```java
// 新类里
import static ...StructureAvoidanceService.*;   // 用服务侧的常量与工具
// 服务里
import static ...PreparedStartResolver.*;       // 仍按原短名调被搬走的方法
```

代价是相关成员从 `private` 放宽到**包内可见**。这条手法在搬 `PlanningSnapshot`
时已验证可行（13 条静态导入 + 服务侧 15 个成员放宽 + 快照类内 17 个方法放宽），
回归 `PASS tests=589` 不变。**它比「把每处调用改成 `类名.方法(...)`」安全得多**，
因为 diff 能直接证明「只有可见性变了，逻辑没变」。
