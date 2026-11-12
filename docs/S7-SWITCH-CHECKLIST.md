# S7 切换清单：从「新内核待命」到「默认启用」

> 本文是**照着做就行**的操作手册。每一级都写明：前置条件、要改什么、怎么验证、怎么回滚。
> 铁律：**任何一级验证不过，就停在这一级，不许往下走。**

## 现状（2026-11-13）

| 组件 | 状态 |
|---|---|
| `ProfileDpSolver`（纵断面全局 DP） | ✅ 内核完成，暴力枚举 300 组证明最优 |
| `ModeDpSolver`（桥隧联合 DP） | ✅ 内核完成，独立重写规则的暴力解 200 组对拍通过 |
| `ProfileDpSource` / `ProfileMetrics` | ✅ 输入翻译 + 度量口径 |
| `ProfileAbObserver` | ✅ 双跑观察 + `summarize()` / `worstCases()` |
| `ProfileTerrainRecorder` | ✅ 真实地形录制 + CI 重放通道 |
| `BuildFrame`（桥范围按路缓存） | ✅ 已上线（唯一已生效的性能改动）|
| `verify.ps1` | ✅ 双模块闸门（fabric 编译 + common 回归）|

**尚未接线**：`ProfileDpSource` / `ModeDpSolver` 还没有进入生产路径的开关。
下面第 1 级是纯数据采集（已有），第 2 级起需要写代码。

---

## 第 0 级：采集真实地形（已完成，只需你跑一次）

**前置**：无。

**操作**：

```
启动参数加 -Droadweaver.profileAb=on
正常玩一会儿（开局预热就会跑几十条路线）
取 <游戏根>/logs/roadweaver-terrain-ab.tsv
复制成 common/src/test/resources/profile/terrain-samples.tsv
```

**验证**：

```powershell
.\verify.ps1
```

看 `[PROFILE-REPLAY]` 汇总行。它会给：

```
样本=N DP可行=.. DP胜=.. 旧胜=.. 平=.. 土方合计 旧=.. DP=..
```

**通过标准**：`样本 >= 20` 且 `DP 土方合计 <= 旧`。

**不通过怎么办**：看 `[PROFILE-REPLAY]` 逐行里「旧胜」的那些样本，
用它们的 `tag` 去游戏日志里找对应连接，分析是地形特殊还是指标口径问题。
**停在这一级，不许往下走。**

---

## 第 1 级：剖面求解器加开关（默认仍走旧链路）

**前置**：第 0 级通过。

**要写的代码**（预计 100~150 行）：

1. 新增 `pathlogic/profile/ProfileSolverMode`：
   `LEGACY`（默认）/ `DP` / `AB`，从 `-Droadweaver.profileSolver=` 读，非法值回落 `LEGACY`；
2. `TargetYComputation.computeTargetY` 末尾按模式分派：
   - `LEGACY`：原样返回（**必须与现在逐字节一致**）；
   - `DP`：用 `ProfileDpSource.solve(...)` 的结果替换 `smoothed`；
   - `AB`：观察并返回 `LEGACY`（等于现在的 `profileAb`）。
3. 日志：`DP` 模式下每 N 条打一行 `[PROFILE-DP] tag=... stations=... feasible=...`。

**验证**：

```powershell
# ① 默认路径必须完全不变
.\verify.ps1
# ② DP 路径必须不崩、且剖面合法
#    （在游戏里开 -Droadweaver.profileSolver=dp 跑一批，看日志有没有 feasible=false 大量出现）
```

**通过标准**：
- `verify.ps1` 全绿（证明默认路径没被动过）；
- `DP` 模式下 `feasible=false` 的比例 **< 5%**；超过说明窗口参数不合适，回去调。

**回滚**：删掉开关即回到 `LEGACY`。或者保持开关但把默认值设回 `LEGACY`。

**✅ 已解决（2026-11-13）**：`ProfileDpSource` 现在支持三条硬契约 ——
`ProfileDpSource.Constraints(pin, mustBeCovered, minCover)`：

| 契约 | 实现 |
|---|---|
| 结构边界锁定（村庄 / 出生点 / 命名结构端点）| `pin[i] = 实测地表高度` |
| 未知端点共享高度 | `pin[i] = UnknownEndpointHeightRegistry 给出的高度` |
| 隧道覆土 | `mustBeCovered[i] = true` + `minCover`，**宁可挖得比 maxCut 更深也要保住覆土** |

**有一处刻意的语义改进**：旧版 `applySingleBoundaryGrade` 会硬铺一条**无视 fill 窗口**的引道，
这正是「锁定点周围填方超限」的来源。新实现把选择权交给调用方 ——
要贴住高处的锁定点，就显式放宽 `maxFill`；求解器**不会偷偷超限**。
已用测试钉住这条（`钉住高度超出窗口时_要么放宽窗口要么不可行`）。

**✅ 调用方接线也已完成（2026-11-13）**：`TargetYComputation.applyProfileSolver(...)`
已经按 `-Droadweaver.profileSolver=dp` 分派，并且：

| 保障 | 实现 |
|---|---|
| 默认路径逐字节不变 | `LEGACY` 时**立刻返回入参**，不构造任何对象 |
| DP 失败必须回落 | 不可行 / 任何异常 ⇒ 返回旧剖面 + 记警告，绝不让一条路算不出来 |
| 硬契约一定传进去 | `pinnedMask` → `pin`（用 `startBoundary`/`endBoundary` 实测地表）<br>`tunnelMask` → `mustBeCovered`，覆土取 `SurfaceGradeLimits.tunnelCoreOverburden()` |

⇒ **第 1 级的代码已全部就位**，剩下的只是"在真实存档上跑一次 `=dp` 看结果"。

---

## 第 2 级：桥隧联合求解接入

**前置**：第 1 级通过，且第 1 级里补上了结构边界锁定与端点共享高度。

**要写的代码**（预计 200~300 行）：

1. 把旧判定（`PathSpanExtractor`）的结果翻译成 `ModeDpSolver` 的**约束**：
   - 水面 / 保护区内 → `groundBanned[] = true`；
   - 已有公路必须桥、隧道口必须隧道 → 对应站的 `pin[]`；
2. `ModeDpSolver` 的解反向生成 `List<RoadSpan>`（与 `RoadSpan`/`SpanType` 对接）；
3. 与旧 `spans` 做**差异日志**：`[SPAN-AB] legacy=n dp=m 差异站数=..`。

**验证**：

```powershell
.\verify.ps1
# 游戏内开 AB 看 [PROFILE-AB-MODE] 的 legacy[bannedGround=..] vs dp[bridge=..(n座)]
```

**通过标准**：
- 旧判定判出的每一座**必须**的桥（横跨水面 ≥ 阈值），新解也判了（召回率 100%）；
- 新解**多**判的桥，逐座能解释（例如"那里其实是浅涵洞"）。

**回滚**：第 2 级的开关独立于第 1 级，关掉即回旧判定。

---

## 第 3 级：删旧实现（按 S5 方法台账三级走）

**前置**：第 1、2 级都通过，且**默认值已切成新内核并稳定一段时间**。

**删除顺序**（来自 `docs/S5-METHOD-MAP.md`，36/44 个方法会消失）：

| 子级 | 删什么 | 每步验证 |
|---|---|---|
| 3a | `DELETE_WITH_LEGACY` 里的**叶子**方法（`windowRun` / `lowerRunToGradeCap` / `solveGradeSegment` / `lowerRunToSlope` / `twoStepAt` / `constrainRun` …） | `verify.ps1` |
| 3b | `DELETE_WITH_LEGACY` 的其余方法（`constrainProfile` / `enforceGradeCap` / `findTunnelCorridors` …） | `verify.ps1` |
| 3c | `LEGACY_ONLY` 的入口方法（`stabilizeSurfaceProfile` / `applyProactiveTunnelProfile` / `planAccurateGradeProfile` …） | `verify.ps1` |
| 3d | 3 个文件本体（`RoadGradePlanner` / `GradeOptimizer` / `SlopeContinuityEnforcer`）+ 台账行 | `verify.ps1` + `S5MethodMapTest` 会要求删台账行 |

**每一步都要做**：

```powershell
# 1. 删
# 2. 删台账里对应行（否则 S5MethodMapTest 会红："台账登记了不存在的方法"）
# 3. 验证
.\verify.ps1
# 4. 提交（一次只删一组，提交信息写清删了哪些方法）
```

**通过标准**：`verify.ps1` 全绿 + `S5MethodMapTest` 全绿。

**回滚**：`git revert <该次提交>`。**不要**用 `git checkout --` 整片还原（会连带回退别的改动）。

---

## 第 4 级：清理死代码与注释

**前置**：第 3 级完成。

**操作**：
1. 跑 `DeadCodeGuardTest` —— 删完旧实现后，`dead-code-baseline.txt` 里的
   `[A] 真死代码` 应当增加（原来被旧链路调用、现在没人用了）；
2. 把新增的死代码搬进 `[A]` 组并逐个删除；
3. 更新 `docs/METRICS-20261113.md` 快照。

**验证**：`verify.ps1` + `MaintainabilityReportTest`（职责头仍须 100%）。

---

## 全局回滚预案

任何一级出问题：

| 症状 | 处置 |
|---|---|
| 游戏崩溃 / 存档报错 | 立刻把默认值切回 `LEGACY` 并重新部署；**不要**继续调参 |
| 路面明显变差（浮空/深槽) | 同上；把该批路线的 `tag` 记下来，回头看 `worstCases()` |
| `verify.ps1` 红 | 不提交；若是编译错，先 `git status` 看有没有半成品 |
| 部署后世界生成卡住 | 检查是否误删了 `promoteRuntimeBridges` 相关路径（**它是安全网，不能删**）|

## ★ 三条不许做

1. **不许**在没有真实地形数据的情况下把默认值切成 `DP`；
2. **不许**在第 1 级验证通过前动第 2 级（桥隧依赖剖面的高度契约）；
3. **不许**删 `promoteRuntimeBridges` —— 它是「别把半边河床填平」的安全网
   （见 `REFACTOR.md` 的自我更正段）。