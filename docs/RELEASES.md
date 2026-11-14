# roadweaver-next 发布台账

> 本节是**持久台账**（不是临时文档）：每次出可部署 jar 都必须记一条，含 commit / 改动 / 回归 / jar 体积 / SHA-256 / 回退方式。
> 旧仓库（`D:\云游四海-Mod源码\roadweaver`）的历史版本条目在它自己的 `roadweaver-变更历史.md` 里，本项目从 0.9.099 起独立记账。

## 0.9.102（2026-11-14）

**为什么要出这一版**：本轮修完 2026-11-14 审计的 14 项缺陷 + 完成缺陷 C（纵断面去双跑）之后，
**一次真机都没跑过**。路线图的「阶段 0（采集真实地形）」是阶段 3/4 的前置，它必须在游戏里跑一局
才能产出 `terrain-samples.tsv`。所以先出一版能进游戏的 jar。

| 项 | 值 |
|---|---|
| commit | 见本文件所在提交（`build.gradle` 版本号递增提交）|
| 版本 | `2.3.1-1.21.1-plaincraft-direct-core.0.9.102` |
| jar | `roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 6,861,221 B（`fabric/build/final/` 的 `embedApiJar` 产物）|
| SHA-256 | `22751e3b3f0954db…` |
| 回归 | **PASS tests=723**（`RoadJUnitRegressionSuite`）|
| 回退 | 客户端 mods 里 `0.9.101.jar.bak` 改回 `.jar` 即可 |

> ★ **部署件必须取自 `fabric/build/final/`，不是 `build/libs/`**。
> `build/libs` 那个同名 jar 只有 4 个内嵌 jar、**缺 `META-INF/jars/roadweaver-api.jar`**，
> 装进 mods 会报「不兼容的模组：需要 roadweaver_api」并把 6 个下游模组一起拒掉。
> 判据：`Entries | Where FullName -match '\.jar$'` 必须 **5 个**且含 `roadweaver-api.jar`。
> （本次第一版就装错了 `build/libs` 的 6,858,008 B 件，读到 `HANDOVER.md` 第 6.5 节才发现并改正 ——
> 当时台账里记的 SHA `785d84b3` 是**错件**的，已作废。）

### 本版相对 0.9.101 的改动（12 个提交）

**审计缺陷修复（14 项，全部带回归钉子）**

| 缺陷 | 提交 | 一句话 |
|---|---|---|
| NEW-1（Blocker）源点碰障碍抛异常瘫痪整区 | `7acab2a` | 返回全不可达场，与「目标点不可通行」对称 |
| A（Blocker）降级重搜障碍场失效穿村穿楼 | `07b8c5a` | 硬掩码（模式门控）与软掩码（真实判定）分离 |
| NEW-8 隧道重搜绕过硬障碍 | `4766d98` | 包进 `HARD_BUILDINGS_AND_CLUSTERS` 作用域 |
| NEW-2 坐标量化 Round/Floor 错位 7 格 | `d3c3247` | 全仓统一就近吸附 + clamp |
| NEW-3 曲线算完即弃 | `8d30002` | `planTopology` 只跑拓扑轮（规划时间约减半）|
| NEW-4 曲线首尾未对齐节点 | `a7c8e11` | 首尾强制对齐；**负控实证**：不修时起点偏 6 格 |
| N1 只测格点、缺边级检测 | `cfb89e3` | 每边采 1/4、1/2、3/4 三点 + 保守膨胀 |
| B 弹性带无障碍投影（契约断裂）| `5038e62` | `BlockedCheck` 端口 + 环搜索投影；站数不变 |
| NEW-7 fillWeight=0.7 反转 prefer-cut | `f0e2bb7` | defaults → `(0.7, 1.0)`，填方严格贵于挖方 |
| NEW-5 对角偏爱 41.4% | `241ab36` | 回溯改按「单位弧长下降量」选向 |
| F 三处 fallbackMST 静默吞异常 | `2414037` | 全部补 WARN（含异常对象与上下文）|
| G AB 双跑两臂同型 | `2414037` | 检测同型即短路 + 一次性 WARN |
| H 六处文档不符 | `2414037` | 逐条更正 |
| FMM 预计算 + 目标早停 | `c289353` | **可证明逐位等价**的提速 |

**缺陷 C（纵断面双跑）主体完成**

| 提交 | 内容 |
|---|---|
| `8590d27` | 新增 `ProfileFallback.terrainFollowing`（无状态回落解，满足全部硬约束、幂等）|
| `bac8c18` | DP 先行；成功即跳过整段旧 pass（本版当时：DP 不可行仍回旧 pass）|

> 后续边界：`b7ef179` 起 DP 不可行也改走 `ProfileFallback`，但该提交**不在 0.9.102 内**。
> 当前源码已领先本版 10 个提交；不能把本版台账当成当前源码的完整功能清单。

### 待真机验证（本版必须验的清单）

1. **不出崩**：进世界、跑到道路生成完成，日志里不能出现 `IllegalArgumentException: 源点...是硬不可通行格`（NEW-1）
2. **不穿建筑**：路过村庄时，路面不该压在房屋上（A + N1 + B）
3. **纵断面偏好**：坡地/河边不应出现长距离人工路堤或天桥（NEW-7）
4. **曲线端点**：桥/隧道接缝处不该有错位台阶（NEW-4）
5. **性能基线**：`coarse planning timing` 里的 `pathfindingMs` 应显著低于旧版 215 s（阶段 2 的验收）
6. **剖面日志**：`RoadWeaver profile solver=dp` 应出现且**没有** `running legacy passes` 警告（C）

### 已知未做的三项（不要误认为已修）

- **C 的后续边界**：旧 pass 编排块已按铁律原地打标，不强行搬迁；但 `stabilizeSurfaceProfile` 仍无条件先跑，RawPathSearch/精确高度解析/精确坡度修复也仍调旧 `RoadGradePlanner`，不能把「编排块退场」误写成「全生产零旧调用」。
- **D**：`MountainSwitchbackPlanner` / `CoarseHeightAwarePlanner` 两处选线候选补充待评估；`LocalRouteSearch` 经核实是**局部修复基础设施**，不是残留。
- **N2**：FMM 求解的是轴对齐对角黎曼度量，不含 Finsler 交叉项 `2λ·gx·gz`；会改路线形状，必须真机 A/B 后再动。