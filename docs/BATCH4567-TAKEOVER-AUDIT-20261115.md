# 批 4/5/6/7 生产链存活 + 新引擎接管度核对（2026-11-15）

> **这份文档回答两个问题**：
> ① 批 4/5/6/7 的旧包实现，还在不在生产链上？
> ② 更重要：这四批的**新引擎**有没有真的接管？
>
> **结论先说**：
> ① **旧包零死代码，全部在活链路上。**
> ② **七批里真正「新引擎接管」的只有批 1 和批 2。**
> 批 3/4/5/6 的新引擎都没有接管，批 7 的守门器仍不拦。

---

## 一、方法

同批 3 的做法：对每个旧包的**顶层类型**（不是文件名！）逐个统计生产引用，
再回溯到已确认的入口。入口用已核实的两个：
- `LoadedChunkRoadBackfill:159` → `PathFeature.placeChunk`（施工侧）
- `Road.generateRoad` → `PathMetricsOps` / `RoadPathCalculator`（规划侧）

---

## 二、旧包存活情况：零死代码

| 批 | 旧包 | 文件数 | 顶层类型数 | 零外部引用的类型 |
|---|---|---|---|---|
| 4 | `pathlogic/junction` | 4 | 12 | **0** |
| 5 | `postprocess` | 13 | 22 | **0** |
| 6 | `pathlogic/structures` | 23 | 39 | **0** |
| 7 | `pathlogic/validation` | 4 | 12 | **0** |

**全部类型都有外部引用。** 批 4 的 4 个类（`CrossingNegotiator` /
`ExistingRoadJunctionService` / `ParallelOverlapResolver` / `RoadBraidDetector`）
引用者从 `initialGen` 到 `PathFeature` 到 `pathlogic/tunnel` 都有，全在活链路上。

---

## 三、★ 真正的问题：新引擎接管度

| 批 | 新引擎 | 接管证据 | 判定 |
|---|---|---|---|
| **1** 纵断面 | 全局 DP | `ProfileSolverMode` 默认 **DP**（2026-11-13 起） | ✅ **已接管** |
| **2** 桥隧判定 | `ModeDpSolver` | 回写已实施（`Road.applySpanWriteback`），但**默认关闭** | 🟡 **代码就绪，默认关** |
| **3** 桥体施工 | 无（`pathlogic/construction` 只有铺装） | 桥隧建造仍跑旧包 4,887 行 | ❌ **未接管** |
| **4** 路口合并 | **不存在** | 新求解包（metric/topology/band/pipeline）里**没有任何路口概念** | ❌ **未接管（也没有新引擎）** |
| **5** 几何后处理 | 弹性带（应把后处理降级为「网络缝合」） | ⚠️ **本行 2026-11-15 更正**：band **已接**（`BandPathfinder:279` → `BandRouteSolver:63`）；真问题是 `PathMetricsOps.buildSegments` 在 band **之后**又调 `PathPostProcessor.process` 跑 6 步 ⇒ **双平滑** | ❌ **未降级（原因改为双平滑）** |
| **6** 结构避让 | 弹性带 + `ObstacleField` 硬约束 | ✅ **本行 2026-11-15 更正为「已接」**：`ElasticBandSolver` 有 `BlockedCheck` 端口（`:226`），`BandPathfinder:265` 建真实 `StridedObstacleField` → `:279` 建 `BandRouteSolver` → `BandRouteSolver:63` 传 `!metric.isPassable(x,z)` 给 `smooth(...)` | ✅ **硬约束已接且在活链路上** |
| **7** 验证收尾 | `QualityGate` | `PipelinePlanner:183` 调了，但 `report.passed()` 只决定 INFO/WARN，**不拦** | ❌ **REPORT-ONLY** |

### 3.1 批 5 的具体证据
`PathPostProcessor` 有 8 个 `public static` 入口，其中 `process(...)` 4 个重载 +
`processUnrelaxedLinear(...)` 都被 `pathlogic/pathcalc/PathMetricsOps.java`
（`:233` / `:266` / `:296`）在生产里调用。
按 S5 的设计，后处理应当**降级为「网络缝合器」**（只负责把路由接到既有路网上），
而「简化 / 样条 / 松弛」应当由弹性带在前一步就做掉。**现状是弹性带没接，
所以后处理仍在全量干活。**

### 3.2 批 6 的具体证据
`ElasticBandSolver:222-224` 的注释原文：
> 「band 只许依赖 topology，所以这里**不直接吃** `ObstacleField`」

也就是说「硬约束由 band 投影负责」这条契约**没人实现**。
`StructureAvoidanceService`（恰好 1000 行）仍是避让的唯一实现，全在旧包。

### 3.3 批 4 的具体证据
在 `metric` / `topology` / `band` / `pipeline` 四个新求解包里搜 `junction`：
**只有 `topology/package-info.java` 里的一句说明**，没有任何路口/合并的类型或算法。
⇒ 批 4 **既没有新引擎，也就谈不上接管**；它的「拆分完成」只是旧包内拆了协作类。

---

## 四、这把七批的完成度重新定义了

**不能再用「代码是否搬到新包」或「符号是否齐全」来衡量完成度** ——
那两条在批 3/4/5/6 上都已经「通过」，但新引擎一个都没接管。

正确的完成度口径应当是三条同时成立：

| 口径 | 批1 | 批2 | 批3 | 批4 | 批5 | 批6 | 批7 |
|---|---|---|---|---|---|---|---|
| ① 符号保真（功能不丢） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ② 新引擎接管（实现对换） | ✅ | 🟡默认关 | ❌ | ❌ | ❌ | ❌ | ❌ |
| ③ 真机验收 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

> ## ★★ 2026-11-15 二次更正（本表批 5、批 6 两行已被推翻）
> 本表初版把批 6 判成「硬约束未接」、批 5 判成「band 没接」。**这两条都错了**：
> - **批 6 硬约束已接**：`BandPathfinder:265` 建真实 `StridedObstacleField` →
>   `:279` 建 `BandRouteSolver` → `BandRouteSolver:63` 把 `!metric.isPassable(x,z)` 当
>   `BlockedCheck` 传给 `ElasticBandSolver.smooth(...)`。**已在生产链上。**
> - **批 5 band 已接**：真问题是 `PathMetricsOps.buildSegments` 在 band 之后
>   又跑 `PathPostProcessor.process` 的 6 步 ⇒ **双平滑**，不是「band 没接」。
>
> 错因：引用了 2026-11-13 的旧记忆，且**把「band 不直接依赖 metric（分层设计理由）」的注释
> 读成了「契约没人实现」**。完整更正与方案见
> [`BATCH56-CORRECTION-AND-PLAN-20261115.md`](BATCH56-CORRECTION-AND-PLAN-20261115.md)。

**⇒ 七批里没有一批是「三条全过」。**
批 1 最接近（①② 都过，只差③），批 2 差②的默认开启 + ③。

---

## 五、★ 方法论：我自己在这一轮踩了两次假阳性

### 5.1 按文件名 grep ⇒ 假阳性（**同一个坑第二次踩**）
第一版审计按**文件名**统计零引用，结论说
`SnapInternals`（37 行）与 `StructureCacheKeys`（75 行）是死代码 —— **错了**。

真相：这两个文件**里面没有同名的类型**，它们是「容器文件」：
- `SnapInternals.java` 只声明 `record NearestResult` / `record ConnectionOption`
- `StructureCacheKeys.java` 只声明 11 个包内类型（`ChunkRequestKey` 等）

按真类型核对后：**全部有外部引用**（`NearestResult` 4 处、`ConnectionOption` 5 处、
`StructureCacheKeys` 的 11 个类型 1~12 处）。

> **这与记忆里 `StructureKeys.java` 那个坑完全同形** ——
> 那个文件也差点被当死代码删掉，因为里面没有名为 `StructureKeys` 的类型。
> ⇒ **判据必须是「按类型」不是「按文件名」。**

### 5.2 「零外部引用」≠ 死代码
第二版按类型统计，报了 7 个「零外部引用」类型：
`JunctionSelection` / `Densified` / `InferredEndpointOwner` / `VillageBuildingCollision` /
`WaterValidation` / `ColumnSource` / `WaterColumn`。
逐个核对后**全部是文件内使用的嵌套类型**（4~14 处使用）。
⇒ **文件内嵌套类型天然零外部引用，不能当死代码。**

---

## 六、这份核对**没有**做什么

- **没有**判定「批 3/4/5/6 的新引擎该不该现在接管」—— 那是路线决策，不是审计。
- **没有**核每条旧实现内部是否还有**平行的重复实现**（例如两套简化算法）。
- **没有**真机验证。全部结论是静态的。
- **没有**改任何代码。
- 批 5 的「降级」判据用的是「`PathPostProcessor.process` 是否仍被生产调用」，
  这是必要条件不是充分条件 —— 即使它仍被调用，也可能已经只做缝合的一部分。