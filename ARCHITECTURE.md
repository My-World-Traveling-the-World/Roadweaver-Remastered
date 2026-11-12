# RoadWeaver 架构（2026-11-12 重构）

本文件与代码<b>同源受检</b>：`ArchitectureGuardTest` 会检查这里的包职责表与
代码里真实存在的包是否一致。改架构必须同时改这里。

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
| `bridge` | foundation + 全部求解层 | —— （**任何求解层不许 import bridge**）|
| `client` | `pipeline`, `foundation`, `api` | 求解层内部实现 |

**反向依赖 = 测试直接红。**

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
