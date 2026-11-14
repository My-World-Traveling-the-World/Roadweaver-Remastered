# RoadWeaver 旧版功能盘点（2026-11-13）

> **基线**：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`（冻结）
> **方法**：8 个子代理并行只读盘点，每个功能点附源码行号证据
> **目的**：为重制版建立完整的功能清单，确保旧版能力一个都不丢
> **规模**：531 个类 / 89,670 行有效代码 / 232 个持久化配置字段

---

## 一、功能域总览

旧版 RoadWeaver 的功能可以划分为 **8 大功能域**：

```
┌─────────────────────────────────────────────────────────────┐
│  1. 规划与寻路（Planning & Pathfinding）                     │
│     - 6 种拓扑规划算法（KNN/Delaunay/RNG/MST/SnapBranch/Alpha）│
│     - 4 种寻路器（BasicAStar/Bidirectional/GradientDescent/PF）│
│     - 采样系统（快速/粗区域/精确/两阶段）                      │
│     - OpenCL 加速（粗采样在用、精确采样停用）                  │
├─────────────────────────────────────────────────────────────┤
│  2. 结构避让与路口（Structure Avoidance & Junction）          │
│     - 村庄判据（9 个子功能）                                  │
│     - PlanningSnapshot 准备/缓存/失效                         │
│     - 结构预测与预筛                                          │
│     - 路口合并/Braided/共享跨越                               │
│     - structures/ 预计算与占位注入                            │
├─────────────────────────────────────────────────────────────┤
│  3. 跨度与桥隧（Span & Bridge/Tunnel）                        │
│     - 跨度提取（水面/干峡谷/村庄/海底隧道）                    │
│     - 桥面高度（最低高程/稳定岸/相邻连续/长桥正弦弯）          │
│     - 隧道判据（包络/洞口/洞身施工）                          │
│     - 坡度规划（prefer-cut/DAG/GradeCap）                     │
├─────────────────────────────────────────────────────────────┤
│  4. 纵断面与后处理（Profile & Post-process）                  │
│     - 15 道纵断面 pass（Road.java）                           │
│     - 后处理（DP 简化/relax/样条/密化/桥头切线）               │
│     - 精度梯级与局部修复                                      │
├─────────────────────────────────────────────────────────────┤
│  5. 施工与装饰（Construction & Decoration）                   │
│     - 铺路（三态保护列）                                      │
│     - 桥面/隧道施工                                           │
│     - 装饰系统（自然/人工/栅栏路标）                          │
├─────────────────────────────────────────────────────────────┤
│  6. 配置与 UI（Config & UI）                                  │
│     - 266 个配置字段（全口径）/ 232 个持久化叶字段             │
│     - 配置界面（分组/标签/滑块）                              │
│     - 道路地图（搜索/图层/瓦片渲染）                          │
├─────────────────────────────────────────────────────────────┤
│  7. 持久化与 API（Persistence & API）                         │
│     - 道路主存储（RoadChunkStore SCHEMA_VERSION=2）           │
│     - 玩家路线（routes.json version=1，单人主世界）            │
│     - API 契约（LoadSignalBridge/GreatWallReadinessApi/...）  │
├─────────────────────────────────────────────────────────────┤
│  8. OpenCL 加速（GPU Acceleration）                           │
│     - 粗采样：在用（37 文件 / 6913 行）                       │
│     - 精确采样：停用（工厂写死 CPU）                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、出厂默认组合

```
planningAlgorithm=RNG                    （相对邻域图，不是随机数）
pathfindingAlgorithm=POTENTIAL_FIELD     （势场 + 梯度流）
terrainSamplingMode=COARSE_CORRIDOR      （粗走廊采样）
aStarStep=8                              （A* 步长 8 格）
```

**配置默认值集中在**：
- `PlanningConfig.java`（602 行）
- `PathfindingCostConfig.java`（542 行）
- `PerformanceConfig.java`（378 行）
- `RoadConstants.java`（433 行）

---

## 三、核心发现（命名纠正与无效声明）

### 3.1 命名纠正（别再引用错误名称）

| 错误说法 | 真相 |
|---|---|
| RNG = 随机数规划 | **RNG = Relative Neighborhood Graph（相对邻域图）** |
| `refineLocalRouteWindows` | 实际是 `refineInitialRoutesAsync` → `refineLocalRouteWindowAsync` |
| `ExistingRoadJunctionService.mergeRoads()` | **没有这个方法**，真实入口是 `connectPlannedBatchDetailed()` |
| `GradientDescent` = 纯梯度下降 | 实际是 **A* 变体** |
| `MountainSwitchback` 是工厂可选算法 | 实际是 `RawPathSearch` 的补救，不是第五种可选 |
| 精度梯级 8→4→2→1 | 实际是「整路用配置步长（默认 8）、局部修复固定 step=2、河流 step=1」 |
| `MapSearchController` | 旧版全仓无命中，实际是 `RoadMapScreen + MapWorkspacePanel + MapStructureSearchService` |
| `GreatWallLoadingProgressRenderer` | 旧版全仓无命中，实际是 `LoadingGenerationOverlayRenderer` |

### 3.2 无效声明（配置存在但不生效）

| 配置/常量 | 状态 |
|---|---|
| `bridgeDeckSurfaceY` | **全仓只有定义，无调用点** |
| `CANYON_BANK_SEARCH_SEGMENTS=8` / `CANYON_MIN_WIDTH_BLOCKS=5` | **只声明未使用** |
| `UNDERSEA_TUNNEL_SELECTION_PROBABILITY=0.5` | **未读取**（真实读取 `terrainProfile.oceanUnderseaChance`） |
| `VILLAGE_GAP_BELOW_TOLERANCE=16` | **声明但掩码未使用** |
| `bridge.minLength=5` / `keepLamps=true` | **除 getter/setter 外无消费者** |
| `openclAccurateSamplingEnabled` | **死开关**（工厂写死 CPU，配置键也无法接回） |

### 3.3 三层隧道阈值（不可混用）

| 层 | 阈值 | 位置 |
|---|---|---|
| 最终包络判据 | `TARGET_BURIAL=7` / `MIN_SEALED_CORE=3` | `TunnelEnvelopeCriterion` |
| 主动纵断面 | `mountainOverburdenBlocks=8` / `tunnelMinRun=3` | `RoadGradePlanner.applyProactiveTunnelProfile` |
| 施工端 | `roofOffset=6` / `minOverburden=7` | `RoadTunnelBuilder`（由 `tunnelClearHeight=5` 推导） |

### 3.4 OpenCL 加速边界

| 功能 | 状态 | 原因 |
|---|---|---|
| 粗高度采样 | **在用** | `OpenCLCoarseHeightBatchSampler` 700 行 + `DensityGraphCompiler` 801 行 |
| 精确高度采样 | **停用** | dense mod-pack 精采图使单缓冲超过 4060 的 **2046 MiB** 限制，反复 `clCreateBuffer -61` |
| 拓扑/寻路/修复 | **CPU** | 共享优先队列、路径历史、动态建筑禁令不适合 GPU kernel |

---

## 四、配置字段口径

- **主 `roadweaver.json` 实际持久化叶字段**：**232 个**
- **全口径（含派生/瞬态/DTO）**：**266 个**
  - `sub/*Config.java` 245（含派生 11 + transient 2）
  - `ModConfig` 聚合字段 10
  - `StructureSelectionConfig` 4
  - `PresetConfig` 1
  - `PresetService.PresetFile` 6

---

## 五、迁移时最值得保留的整体顺序

1. 路线候选/落库结构 → 精确 start 与模板占用 → 不可变 `PlanningSnapshot`
2. A* 先硬躲建筑/村庄群，无解则退回高代价模式
3. 端点村庄走边界着陆；过路村庄走聚合盒保护缺口；真实建筑走精确 AABB 碰撞
4. 批次后处理顺序：**单 donor 路网合并 → 桥梁共享 → Braided 检测/后者改道 → 精修与最终校验**
5. 规划期过河协商负责「事前统一 XZ/deckY」，生成后桥梁共享负责「事后能安全吸收才吸收」

---

## 六、详细报告索引

| 功能域 | 报告文件 | 规模 | 状态 |
|---|---|---|---|
| 规划与寻路 | `01-规划与寻路.md` | 32,658 B | ✅ 完整（2026-11-13）|
| 结构避让与路口 | `02-结构避让与路口.md` | 32,389 B | ✅ 完整（2026-11-15）|
| 跨度与桥隧 | `03-跨度与桥隧.md` | 27,438 B | ✅ 完整（2026-11-15）|
| OpenCL 加速 | `04-OpenCL加速.md` | — | ⏳ 待补 |
| 纵断面与后处理 | `05-纵断面与后处理.md` | — | ⏳ 待补 |
| 施工与装饰 | `06-施工与装饰.md` | 15,534 B | ✅ 完整（2026-11-15）|
| 配置与 UI | `07-配置与UI.md` | — | ⏳ 待补 |
| 持久化与 API | `08-持久化与API.md` | — | ⏳ 待补 |

> **⚠️ 2026-11-15 更正**：本表此前把 `02` / `03` / `04` 三项都标成「✅ 完整」，
> 但目录里**实际只存在 `01`**。这是典型的「文档声称与实际不符」——
> 谁按这张表去查功能清单都会被误导。
> 现在状态列一律以**目录里真实存在的文件**为准，缺失的一律标「⏳ 待补」。
> 补一份就把该行改成实际体积 + 日期，不要预先把没写的东西标成完整。

### 两种引用风格并存（有意为之）

- `01` / `03` / `06`：正文里写**完整相对路径**的 markdown 链接。
- `02`：开头放一张**代号索引表**（16 个代号 → 相对路径 + 行数），正文只写 `代号:行号`。

原因：`02` 涉及的 `StructureAvoidanceService` 在旧仓库是 **4,766 行的上帝类**，
一个功能点常常横跨十几处行号；若每处都写完整路径，文档会被路径淹没。
`02` 的代号表已经把「代号 → 文件 → 行数」讲全，正文 `SAS:4415` 可无歧义回溯。

### 一处重要澄清（`02` 的命名对账，2026-11-15 已核实）

`02` 开头写「任务点名的 `ObstacleJudge` / `PreparedStartResolver` / `EndpointOwnerResolver` /
`ProtectedGapEvaluator` / `WorldgenAvoidance` 在旧仓库三模块**均无同名类**」——
这一点**实测为真**：旧仓库
`pathlogic/structures/` 只有 4 个类
（`StructureAvoidanceCache` 60 / `StructureAvoidanceDiagnostics` 44 /
`StructureAvoidanceService` **4,766** / `StructureRoadOffsetService` 221）。

⇒ 那 5 个类名是 **`roadweaver-next` 重构时从上帝类里拆出来的**，不是旧版就有的。
读 `02` 时不要以为能在 oracle 仓库里找到它们；要对照实现请回到 `SAS`。

---

## 七、与重构版的对比

> **统计日期：2026-11-15**（数字会随代码漂移；要看当前值请重新统计，不要引用本表当现状）

| 维度 | 旧版 | 重构版（当前） | 差异 |
|---|---|---|---|
| 类数量（`.java` 文件数）| 531 | 679 | +148 |
| 生产源码总行数（三模块 `src/main/java`）| 119,112 | 132,930 | +13,818 |
| 回归测试（`PASS tests=` 实跑）| 482 | **811** | +329 |
| 架构守卫类 | 0 | 13 | 全新 |
| 新算法包 | 0 | 32 文件 / 4,150 行 | metric 8/1,215、topology 5/350、band 3/445、pipeline 10/1,045、bridge 6/1,095 |

**★ 更正（2026-11-15）：新架构五包现在都已进入生产路径，不再是「只有 BandPathfinder 接上」。**
证据链：
- `PipelinePlanner:167` → `new RoadPipeline(metric, null).planTopology(nodes)` ⇒ **pipeline + topology 在生产**（网络级拓扑：测地矩阵 + MST）
- `BandPathfinder:279` → `new BandRouteSolver(metric, ElasticBandSolver.defaults())` ⇒ 单路链走 `pipeline/ab`
- `BandRouteSolver:63` → `bandSolver.smooth(raw, !metric.isPassable)` ⇒ **band 在生产**
- `BandRouteSolver` 内部调 `FastMarchingSolver`，后者 `:154-155` 调 `FinslerMetric.cost(dirX, dirZ)` ⇒ **metric 在生产**

仍属观测/未接管的部分（与「新架构没接」是两件事）：
- `ModeDpSolver` 桥隧决策**只观测不回写 spans**（设计稿见 `docs/DESIGN-SPAN-WRITEBACK.md`）
- `RoadPipeline.run()`（完整含曲线那条）**无生产调用者**，生产只调 `planTopology`
