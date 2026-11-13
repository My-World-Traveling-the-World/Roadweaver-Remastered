# RoadWeaver 重构版：还能提升的地方（2026-11-14 审查）

> 基于 4 路并行审查（新内核缺陷 / 生产链接线 / 性能瓶颈 / 质量缺陷）+ 我的快速核查。
> 每个问题都附源码行号证据，不是凭空猜测。

---

## 一、已确认的性能问题

### 🔴 1. 纵断面是「旧流水线 + DP」双跑（性能审查组发现）

**证据**：`TargetYComputation.java:452-464`
```java
int[] legacySnapshot = smoothed.clone();   // 先保留旧剖面结果
// ...
smoothed = applyProfileSolver(level, centers, smoothed, ...);  // 再跑 DP
```

**`ProfileSolverMode.parse` 默认返回 DP**（`:86-91`）。

**后果**：旧流水线的 15 道 pass **先跑一遍**，然后 DP **再跑一遍**。默认端到端成本 = **旧流水线 + DP**（不是替代）。

**DP 的复杂度**：`O(n·H·D²)`（D=2N+1），**不是类注释声称的 O(nH)**。

**修法**：
- 方案 A：DP 替代旧流水线（而不是在旧流水线之后跑）
- 方案 B：保留双跑但加缓存（如果旧流水线的结果和 DP 一样，就跳过 DP）

---

### 🟡 2. GeodesicMatrix 已通过 RoadPipeline 间接接入，但 MinimumSpanningTree 还没接

**证据**：`GeodesicMatrix` 被 `RoadPipeline` 用（生产链），但 `MinimumSpanningTree` 只被测试引用。

**后果**：测地距离矩阵已经算出来了，但 MST 还没接上 —— 拓扑优化还没真正发生。

**修法**：把 `MinimumSpanningTree.solve` 接进 `RoadPipeline` 的拓扑阶段。

---

### 🟡 3. 结构枚举群系预筛还没做

**证据**：结构解析 74 秒（53% of async planning），`emptyResults=3997/4374=91.4%` 解析完是空的。

**后果**：91.4% 的结构起点解析完是空的，白花了 74 秒。

**修法**：枚举时先按 structure set 的 allowed biomes 并集否决（14 高度取样表），先上计数模式（`wouldRejectNonEmpty==0` 才真开）。

---

## 二、已确认的质量缺陷

### 🔴 4. ModeDpSolver 未接入生产

**证据**：`ModeDpSolver` 存在于 `pathlogic/profile/ModeDpSolver.java`（361 行，有 `solve` 接口），但**只被测试引用**，生产链零调用。

**后果**：桥隧判定还是「事后贴」（先算剖面再决定桥隧），不是「求解前约束」。

**修法**：在 `TargetYComputation` 里调用 `ModeDpSolver.solve(terrain, maxStep, settings, groundBanned)`，让 DP 同时决定每站是「地面/桥/隧道」。

---

### 🔴 5. 弹性带不认识障碍场

**证据**：`ElasticBandSolver` 的 `smooth(List<BandResult.Point>)` 只收点列，**完全不认识 `ObstacleField`**。

**后果**：弹性带会把曲线拉进山体/建筑（硬约束没人做）。

**修法**：给 `ElasticBandSolver` 传入 `ObstacleField`，每轮迭代后把穿入障碍的点投影回外。

---

### 🔴 6. ArrivalTimeField.pathTo 不查 isPassable

**证据**：`ArrivalTimeField.pathTo` 只做 8 邻域 max 下降，**不查 `isPassable`**。

**后果**：回溯时会切角穿建筑（45° 量化）。

**修法**：`pathTo` 里加 `isPassable` 检查，遇到不可通行的格子就绕。

---

### 🟡 7. prefer-cut 还没补进 DP

**证据**：`CostModel` 的 `cut=1.0 / fill=0.7`（`CostModel:37`），填比挖便宜 ⇒ 路面浮起。

**后果**：DP 会选「垫高」而不是「削顶」，路面浮在地面之上。

**修法**：改 `CostModel` 权重（`fill ≥ cut`），或加不对称窗口（挖的范围比填宽）。

---

## 三、已确认的「误报」（之前说已做，实际没做）

| 之前说 | 实际 |
|---|---|
| `BuildPlan` / `BuildPlanCompiler` 已落地 | **不存在**（`spec` 包里只有 `RoadSpec.java`）|
| `GeodesicMatrix` 只被测试引用 | **已被 `RoadPipeline` 用**（生产链）|
| `FinslerMetric.cost()` 零调用 | **已被 `FastMarchingSolver:132-133` 调用** |

---

## 四、提升方向汇总（按收益排序）

### 🚀 速度提升

| # | 方向 | 预期收益 | 难度 |
|---|---|---|---|
| 1 | **纵断面去双跑**（DP 替代旧流水线，或加缓存） | 消除旧流水线 + DP 的重复成本 | 中 |
| 2 | **MinimumSpanningTree 接进 RoadPipeline** | 拓扑优化真正发生 | 低 |
| 3 | **结构枚举群系预筛** | 74 秒 → <20 秒 | 低（先计数模式） |
| 4 | **FMM 改二阶格式或 8 邻域** | 对角误差 21% → 2~5% | 中 |

### 🎯 质量提升

| # | 方向 | 预期收益 | 难度 |
|---|---|---|---|
| 1 | **ModeDpSolver 接生产**（桥隧联合 DP） | 桥隧判定从「事后贴」→「求解前约束」 | 中 |
| 2 | **弹性带接障碍投影** | 弹性带不穿建筑，替代 8 个后处理 pass | 中 |
| 3 | **pathTo 查 isPassable** | 回溯不穿建筑 | 低 |
| 4 | **prefer-cut 补进 DP** | 修「路面浮起」（每条路） | 低 |

---

## 五、我的建议（按依赖顺序）

**阶段 1（最快见效）**：
- **纵断面去双跑**（消除旧流水线 + DP 的重复成本）
- **prefer-cut 补进 DP**（修路面浮起）
- **pathTo 查 isPassable**（回溯不穿建筑）

**阶段 2（质量收尾）**：
- **ModeDpSolver 接生产**（桥隧联合 DP）
- **弹性带接障碍投影**（替代 8 个后处理 pass）
- **MinimumSpanningTree 接进 RoadPipeline**（拓扑优化）

**阶段 3（结构收口）**：
- **结构枚举群系预筛**（74 秒 → <20 秒）
- **FMM 改二阶格式**（对角误差 21% → 2~5%）

**你选哪个？还是按阶段 1→2→3 全做？**
