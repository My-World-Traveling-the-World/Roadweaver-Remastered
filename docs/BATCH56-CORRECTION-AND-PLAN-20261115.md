# 批 5/6 更正 + 双平滑方案（2026-11-15）

> **这份文档先更正我上一份审计里的两个错误，再给批 5 的方案选择。**
>
> **更正**：
> ① 批 6「硬约束未接」**是错的** —— 已接且在生产链上。
> ② 批 5「弹性带没接」**原因写错了** —— band 已接，真问题是**双平滑**。

---

## 一、更正①：批 6 的硬约束**已经接了**

### 我上一轮写的
> 「`ElasticBandSolver:222` 自承『band 只许依赖 topology，所以这里**不直接吃**
> `ObstacleField`』⇒『硬约束由 band 投影负责』这条契约**没人实现**」

### 实际是什么
`ElasticBandSolver` **明确有** `BlockedCheck` 端口 + `smooth(input, blocked)` 重载
（`:226` / `:257`），并且**生产链上真的在传**：

```
BandPathfinder:265  ObstacleField obstacles = new StridedObstacleField(...真实列的不可通行/惩罚/越界...)
BandPathfinder:270  FinslerMetric metric = new FinslerMetric(terrainField, obstacles, ...)
BandPathfinder:279  BandRouteSolver routeSolver = new BandRouteSolver(metric, ElasticBandSolver.defaults())
BandRouteSolver:63  bandSolver.smooth(raw, (x, z) -> !metric.isPassable(x, z))   ← BlockedCheck 在这里
FinslerMetric:124   isPassable(x,z) = !obstacles.impassable(x,z)                 ← 真实判据
```

`BandPathfinder` 是**在生产路径上**的（`RawPathSearch` → band）。所以
**「绝不穿结构」的硬投影已经在跑。**

### 我为什么判错（这是典型的「把历史结论当现状」）
两个原因叠加：
1. **记忆里那条「`ElasticBandSolver` 完全不认识 `TerrainField`/`ObstacleField`」是 2026-11-13 的结论，
   2026-11-14 已被修掉** —— `FinslerMetric:36` 与 `ObstaclePotential:34` 的 javadoc 都写着
   「（2026-11-14 起真的做了）」。
2. **我把 `:222` 那段注释读反了**：它是在解释「**为什么** band 不直接依赖 metric
   （要守分层规则）」，**不是**说「契约没人实现」。

⇒ **教训：注释里写「不直接吃 X」通常是在讲依赖方向的设计理由，不等于功能缺失。
判「有没有做」只能看调用链。**

### 对批 6 状态的修正
| 维度 | 修正前 | **修正后** |
|---|---|---|
| band 硬约束投影 | ❌ 未接 | ✅ **已接且在生产链上** |

批 6 剩下的问题不是「band 不吃障碍」，而是**避让仍由 `StructureAvoidanceService`（1000 行）
一肩挑** —— 那是「避让逻辑该不该外置」的问题，属另一件事。

---

## 二、更正②：批 5 是**双平滑**，不是「band 没接」

### `PathMetricsOps.buildSegments` 的源码注释自己写着
> 「入参 `path` 是**弹性带平滑之后**的路径；下面 `PathPostProcessor` 会**再平滑一遍**。
> 两处各打一份指标，才能回答『谁把路形改了多少』。」

### 实际串行链
```
BandPathfinder ──(band: 曲率最小化 + 硬投影)──► path
     │
     └─► PathMetricsOps.buildSegments(path)
              └─► PathPostProcessor.process(...) 6 步：
                   ① simplifyPath            （Douglas-Peucker 简化）
                   ② straightenBridgeRuns    （桥段拉直）
                   ③ relaxPathSkippingBridge （拉普拉斯松弛）
                   ④ densifyControlPoints    （控制点密化）
                   ⑤ generateSplinePoints    （Catmull-Rom 样条）
                   ⑥ rasterizeSegments       （栅格化）
```

⇒ **band 确实接上了；问题是后处理在 band 之后又把简化/松弛/样条全跑了一遍。**
弹性带花力气最小化的曲率，会被 ②③⑤ 再改一次 —— 方向可能相同也可能相反。

### 对批 5 状态的修正
| 维度 | 修正前 | **修正后** |
|---|---|---|
| band 是否接管 | ❌ 未接 | ✅ **已接** |
| 后处理是否降级 | ❌ 未降级 | ❌ **未降级，且造成双平滑** |

---

## 三、批 5 的方案选择（需要你拍板）

### 现状测量工具已经就绪
`GeometryDriftObserver`（`pathlogic/pathcalc/`，2026-11-15 新增）就是为这件事建的：
- 开关 `-Droadweaver.geometryAb=on`
- 在 **band 之后**与 **postprocess 之后**各打一行 `[GEOM-AB]`
- 指标：`flips/100`（横向偏移符号翻转率 = 锯齿）、`ampP50` / `ampP95`（摆动幅度）、
  `maxTurn`（最大转角）、`n` / `len`
- 末尾一行 `[GEOM-AB-SUMMARY]` 汇总

**但它只能在真机跑**（链路需要 `ServerLevel`）。

### 三个方案

| 方案 | 做法 | 行为变化 | 风险 | 真机需求 |
|---|---|---|---|---|
| **A. 直接降级** | `buildSegments` 改为只调 ①②⑥（简化→桥段拉直→栅格化），去掉 ③④⑤ | **大**：路形明显变（曲线更"band 原样"） | 高 —— 曲率、避障余量、桥面接缝全变 | 必须 |
| **B. 先测后降级** | 先只开 `-Droadweaver.geometryAb=on` 跑一局，拿 `[GEOM-AB]` 的两阶段数字，判断 postprocess 到底把 band 改坏了还是改好了，再决定降级到什么程度 | **零**（只加日志） | 零 | 必须（但只跑一局看日志） |
| **C. 不动** | 维持双平滑，把它写进文档当已知事实 | 零 | 零 | 不需要 |

### 我的建议：**B**
理由：
1. **现在是「暂不上实机」阶段**，A 无法验证；C 又等于放弃批 5。
2. B **零风险**（只加日志，不改行为），而且**它产出的数字直接决定 A 该降级到哪一步** ——
   如果 `[GEOM-AB]` 显示 postprocess 让 `flips/100` 下降（变好），说明现在的双平滑其实有用，
   那批 5 的目标本身要重新审视；如果显示 `ampP95` 变大（变坏），就按 A 降级。
3. `GeometryDriftObserver` 已经写好了，**只需要在真机开一次开关**。

### 如果一定要现在动代码（不推荐）
最小的一步是**只去掉 ③ `relaxPathSkippingBridge`**（拉普拉斯松弛与弹性带目标函数重叠度最高），
保留 ①②④⑤⑥。但这仍然是行为改动，仍需真机。

---

## 四、这份文档**没有**做什么

- **没有改任何代码**（本轮只做更正与方案）。
- **没有**核对批 6「避让该不该外置」的完整证据链（那是另一件事）。
- **没有**验证 `PathPostProcessor` 的 6 步里每一步是否都必要 —— 只确认了它们**跑了**。
- **没有**真机数据，所以「谁把路形改了多少」这个核心问题**仍未回答**。

---

## 五、方法论备忘：我连续两轮在这上面出错

| 轮次 | 错误 | 成因 |
|---|---|---|
| 47 | 批 6「硬约束未接」 | 引用 2026-11-13 的旧记忆 + **把设计理由注释读成功能缺失** |
| 47 | 批 5「band 没接」 | 同上，没看 `BandPathfinder:279` 这条实例化 |
| 46 | `SnapInternals`/`StructureCacheKeys` 死代码 | **按文件名 grep**，而它们是容器文件 |
| 45 | 甲板 Y「四处各一套口径」 | 照着一份**已被更早修复取代的旧注释**下结论 |
| 44 | 「79 个零引用常量」全当真问题 | 没区分「注释声称行为」与「纯数值噪音」 |

**共同模式：我倾向于拿「注释/记忆/文件名」当证据，而不是拿「调用链/类型/实际值」。**
⇒ **以后的核对必须落到调用链上，注释只用来提示去哪找，不能用来定结论。**