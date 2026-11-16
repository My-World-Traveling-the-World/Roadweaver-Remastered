# S5 台账：后处理降级为「网络缝合器」

> **相关文档**：本文是**文件级**分类（20 个文件留还是删）。
> 方法级删除边界见 [`S5-METHOD-MAP.md`](S5-METHOD-MAP.md)（当前 39 个受管方法里 33 个待旧链路下线后删除）；
> 历史切换方案见 [`S7-SWITCH-CHECKLIST.md`](archive/S7-SWITCH-CHECKLIST.md)，现行验证见[手册](VALIDATION.md)。

> 本文件由 `PostprocessLedgerTest` 机器校验。**删文件必须同步删台账行**，
> 否则测试直接红 —— 这样就不会出现「文件删了、文档还说它在」的漂移。

## 一、先澄清一个命名误解

S5 说「后处理降级为网络缝合器」，但 `postprocess` 包里装的**不是**「修路」，
而是 **raw path → 站点 → 栅格化** 的几何流水线（`PathPostProcessor.process(...)`，
每个寻路器都调它）。这条流水线不存在「降级」的余地 —— 没有它就没有站序列。

真正在做「修路」的是三处：

| 位置 | 干什么 |
|---|---|
| `pathlogic/grade/RoadGradePlanner` | 15 道剖面 pass（夹坡度、平滑、隧道剖面、去尖峰……）|
| `pathlogic/grade/GradeOptimizer` + `SlopeContinuityEnforcer` | 剖面的局部优化与坡率强制 |
| `planning/terrain/AccurateGradeRepairPlanner` | 精确高度修复，必要时整条改线 |
| `pathlogic/pathcalc/RoadPathCalculator.repairFinalHeightConflict` | 跑完之后补救各 pass 的冲突 |

所以 S5 的真实内容 = **把上面这些「事后修」降级/移除，让 `RoadSpec` + 全局 DP 一次算对**，
而 `postprocess` 里只保留与**网络缝合**有关的部分（路口、端点引道、吸附、复用）。

## 二、分类（机器可读，见文末代码块）

四类，词汇表封闭：

| 类别 | 含义 |
|---|---|
| `KEEP_SEAM` | 网络缝合：把这条路接到既有的路/结构/网络上。**永远保留** |
| `KEEP_INPUT` | 给判定提供输入（地形采样、保护判定、几何工具）。**保留** |
| `REPLACE_BY_DP` | 事后修剖面的 pass。全局 DP 上线后由它取代 |
| `DELETE_LATER` | 补救性质的整条改线/冲突修复。DP 切默认后删 |

## 三、替换关系

- `REPLACE_BY_DP` → 由 `pathlogic/profile/ProfileDpSolver`（纵断面）与
  `ModeDpSolver`（桥隧联合）取代；
- `DELETE_LATER` → 先等 S3/S4 切默认（`roadweaver.profileAb` 收集到足够数据、
  差异可解释），再逐块删；
- `KEEP_SEAM` → 不在 DP 的职责范围内：DP 只决定「这一条路自己怎么走」，
  「这条路和别的路怎么接」仍然是缝合器的活。

## 四、台账

<!-- S5:BEGIN -->
S5|postprocess/BridgeMaskTool.java|KEEP_INPUT|桥掩码工具，供样条与栅格化判断哪些站不许松弛|—
S5|postprocess/EndpointApproachConnector.java|KEEP_SEAM|端点引道连接：把路的端点接到结构或既有网络上|—
S5|postprocess/EndpointConnectOps.java|KEEP_SEAM|端点连接的具体操作|—
S5|postprocess/JunctionConnector.java|KEEP_SEAM|路口连接与共享走廊|—
S5|postprocess/JunctionTrimOps.java|KEEP_SEAM|路口裁剪，避免两条路重叠铺装|—
S5|postprocess/NetworkReuseMerger.java|KEEP_SEAM|复用既有路网，避免重复开路|—
S5|postprocess/PathPostProcessor.java|KEEP_INPUT|raw path → 站点 → 栅格化的几何流水线|—
S5|postprocess/PathControlSimplifier.java|KEEP_INPUT|控制点简化与原始点密度补点；从门面等价迁出，不改变容差或顺序|—
S5|postprocess/PathControlRelaxer.java|KEEP_INPUT|控制点限幅松弛，统一保留桥头及急弯冻结约束；未移除第二次松弛|—
S5|postprocess/RoadIndex.java|KEEP_SEAM|既有路的空间索引|—
S5|postprocess/RoadSnapService.java|KEEP_SEAM|把新路端点吸附到既有路|—
S5|postprocess/SawtoothMetric.java|KEEP_INPUT|锯齿度量，仅用于诊断|—
S5|postprocess/SegmentGrid.java|KEEP_INPUT|栅格索引工具|—
S5|postprocess/SnapInternals.java|KEEP_SEAM|吸附的内部工具|—
S5|postprocess/SplineHelper.java|KEEP_INPUT|样条几何工具|—
S5|pathlogic/grade/GradeOptimizer.java|REPLACE_BY_DP|剖面的局部启发式优化，与全局 DP 的目标函数重叠|pathlogic/profile/ProfileDpSolver
S5|pathlogic/grade/SurfaceGradeLimits.java|KEEP_INPUT|配置上限快照（挖/填/覆土/最短隧道段）。2026-11-14 从 RoadGradePlanner 抽成顶层类型：被 4 个文件引用，其中 TunnelFootprintSampler 与 UnknownEndpointHeightRegistry 不在旧链路里|—
S5|pathlogic/grade/RoadConstructionSafety.java|KEEP_INPUT|地形采样、保护判定、施工安全门。注意 promoteRuntimeBridges 属 S2 范围|—
S5|pathlogic/grade/CulvertJudge.java|KEEP_INPUT|涵洞几何判据（3 个上限常量 + 2 个纯函数），2026-11-15 从 RoadConstructionSafety 抽出 —— 那个类已 950+ 行逼近千行限制，修「整段采样」缺陷时又加约 90 行会顶破。判定逻辑本身属施工安全门，不归 DP 取代|—
S5|pathlogic/grade/RoadGradePlanner.java|REPLACE_BY_DP|15 道剖面 pass 的家：夹坡度、平滑、隧道剖面、去尖峰……|pathlogic/profile/ProfileDpSolver + ModeDpSolver
S5|pathlogic/grade/SlopeContinuityEnforcer.java|REPLACE_BY_DP|事后强制坡率连续，DP 的 maxStep 硬约束已覆盖|pathlogic/profile/ProfileDpSolver
S5|pathlogic/grade/UnknownEndpointHeightRegistry.java|KEEP_INPUT|未知结构端点的共享高度注册表，是跨路一致性契约，DP 无法单独产生|—
S5|planning/terrain/AccurateGradeRepairPlanner.java|DELETE_LATER|精确高度修复，必要时整条改线。DP 切默认后不再需要|pathlogic/profile/ModeDpSolver
S5|pathlogic/pathcalc/RoadPathCalculator.java|DELETE_LATER|repairFinalHeightConflict / repairAfterNetworkMerge 是「跑完再补救」的典型|pathlogic/profile/ProfileDpSolver
<!-- S5:END -->
