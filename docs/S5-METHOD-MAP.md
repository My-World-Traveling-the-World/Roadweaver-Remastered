# S5 方法级台账：3 个 REPLACE_BY_DP 文件的删除边界

> **相关文档**：本文只说「删什么」。[`S7-SWITCH-CHECKLIST.md`](S7-SWITCH-CHECKLIST.md)
> 说「**按什么顺序删、每步怎么验**」，其「第 3 级前置 A」还写明了
> `SurfaceGradeLimits` 必须先抽出来才能删 `RoadGradePlanner`。
> 文件级分类见 [`S5-POSTPROCESS-LEDGER.md`](S5-POSTPROCESS-LEDGER.md)。

> 由 `S5MethodMapTest` 机器校验：**分类为 `DELETE_WITH_LEGACY` 的方法，外部引用必须恒为 0**。
> 注意：那个统计按 `\b名称\s*\(` 匹配，抓不到 `Type.method()` 与纯类型用法 —— 所以本表仍可能是错的，抽查时请自己 grep。
> 一旦有人从别处调用它，守卫会红 —— 因为它就意味着「这个删不掉了」。

## 分类口径

| 类别 | 含义 |
|---|---|
| `DELETE_WITH_LEGACY` | **16** |
| `LEGACY_ONLY` | 19 |
| `KEEP_INPUT` | 3 |
| `AMBIGUOUS`（待人工核） | 6 |

## 旧链路成员（一起消失）

`AccurateGradeRepairPlanner`, `AccuratePathHeightResolver`, `GradeOptimizer`, `PathSpanExtractor`, `RawPathSearch`, `RoadGradePlanner`, `SlopeContinuityEnforcer`, `TargetYComputation`

## 台账

<!-- S5M:BEGIN -->
<!-- ★ 2026-11-13 修正：SurfaceGradeLimits 原先被标成 DELETE_WITH_LEGACY / 外部引用 0，
     实测被 4 个生产文件引用（其中 TunnelFootprintSampler 与
     UnknownEndpointHeightRegistry 不在旧链路里）⇒ 改成 KEEP_INPUT。
     守卫当时抓不到有两个原因：①它按 `\b名称\s*\(` 统计，匹配不到
     `SurfaceGradeLimits.capture()` 这种「类型.方法」调用与纯类型用法；
     ②它是嵌套 record，被 METHOD_DECL 正则当成了方法。
     这也正是 docs/S7-SWITCH-CHECKLIST.md「第 3 级前置 A」要求先把它抽成顶层类型的原因。 -->
S5M|类|方法|外部引用文件数|调用方(次数)|分类
S5M|RoadGradePlanner|RoadGradePlanner|1|TunnelFootprintSampler(1)|KEEP_INPUT
S5M|RoadGradePlanner|SurfaceGradeLimits|4|PathSpanExtractor,TargetYComputation,TunnelFootprintSampler,UnknownEndpointHeightRegistry|KEEP_INPUT
S5M|RoadGradePlanner|capture|2|PathSpanExtractor(1),TargetYComputation(2)|LEGACY_ONLY
S5M|RoadGradePlanner|logReducedLimits|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|cut|2|TunnelFootprintSampler(1),UnknownEndpointHeightRegistry(1)|AMBIGUOUS
S5M|RoadGradePlanner|fill|46|AlphaModelPlanner(7),BlockCandidateWidget(4),ContextMenu(5),EndpointConnectOps(1)|AMBIGUOUS
S5M|RoadGradePlanner|constrainProfile|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|robustWindowHeight|1|TargetYComputation(1)|LEGACY_ONLY
S5M|RoadGradePlanner|robustFootprintSupport|1|TargetYComputation(2)|LEGACY_ONLY
S5M|RoadGradePlanner|ignoredFootprintColumns|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|constrainProfileToFootprintSupport|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|enforceContinuousSlopePreferCut|2|SlopeContinuityEnforcer(5),TargetYComputation(1)|LEGACY_ONLY
S5M|RoadGradePlanner|stabilizeSurfaceProfile|1|TargetYComputation(1)|LEGACY_ONLY
S5M|RoadGradePlanner|applyConfiguredGradeCap|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|enforceGradeCap|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|lowerRunToGradeCap|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|windowRun|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|smoothProfileWithinBudget|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|applyProactiveTunnelProfile|2|RawPathSearch(1),TargetYComputation(2)|LEGACY_ONLY
S5M|RoadGradePlanner|planAccurateGradeProfile|1|AccuratePathHeightResolver(1)|LEGACY_ONLY
S5M|RoadGradePlanner|planConfiguredGradeProfile|1|AccurateGradeRepairPlanner(1)|LEGACY_ONLY
S5M|RoadGradePlanner|removeSingleStationSpikes|1|TargetYComputation(1)|LEGACY_ONLY
S5M|RoadGradePlanner|clamp|18|AccurateRegionBounds(3),AdaptiveCandidateBounds(2),CoarseRegionBounds(3),CoarseTerrainTile(3)|AMBIGUOUS
S5M|RoadGradePlanner|findTunnelCorridors|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|isTunnelCore|0||DELETE_WITH_LEGACY
S5M|RoadGradePlanner|overburden|2|PathSpanExtractor(2),TunnelFootprintSampler(4)|AMBIGUOUS
S5M|RoadGradePlanner|isBridge|6|BuildFrame(1),GradeOptimizer(9),PathFeature(1),RoadConstructionSafety(2)|AMBIGUOUS
S5M|RoadGradePlanner|isPinned|1|SlopeContinuityEnforcer(2)|LEGACY_ONLY
S5M|GradeOptimizer|solveOptimalGradeProfile|1|RoadGradePlanner(1)|LEGACY_ONLY
S5M|GradeOptimizer|solveGradeSegment|0||DELETE_WITH_LEGACY
S5M|GradeOptimizer|deviationCost|1|PotentialFieldPathfinder(2)|KEEP_INPUT
S5M|GradeOptimizer|surfaceCutFeasible|1|RoadGradePlanner(1)|LEGACY_ONLY
S5M|GradeOptimizer|findLowerAnchorLeft|1|RoadGradePlanner(1)|LEGACY_ONLY
S5M|GradeOptimizer|findLowerAnchorRight|1|RoadGradePlanner(1)|LEGACY_ONLY
S5M|GradeOptimizer|aboveAnchorChord|1|RoadGradePlanner(1)|LEGACY_ONLY
S5M|GradeOptimizer|mergeMasks|1|RoadGradePlanner(2)|LEGACY_ONLY
S5M|SlopeContinuityEnforcer|enforceContinuousSlope|0||DELETE_WITH_LEGACY
S5M|SlopeContinuityEnforcer|enforceContinuousSlopePreferCut|2|RoadGradePlanner(2),TargetYComputation(1)|LEGACY_ONLY
S5M|SlopeContinuityEnforcer|lowerRunToSlope|0||DELETE_WITH_LEGACY
S5M|SlopeContinuityEnforcer|adjacentStepAt|1|GradeOptimizer(1)|LEGACY_ONLY
S5M|SlopeContinuityEnforcer|twoStepAt|0||DELETE_WITH_LEGACY
S5M|SlopeContinuityEnforcer|configuredStationSpacing|2|AccuratePathHeightResolver(1),TargetYComputation(1)|LEGACY_ONLY
S5M|SlopeContinuityEnforcer|stationSpacing|1|GradeCap(3)|AMBIGUOUS
S5M|SlopeContinuityEnforcer|constrainRun|0||DELETE_WITH_LEGACY
<!-- S5M:END -->

## 汇总

| 分类 | 数量 |
|---|---|
| `DELETE_WITH_LEGACY` | **16** |
| `LEGACY_ONLY` | 19 |
| `KEEP_INPUT` | 3 |
| `AMBIGUOUS`（待人工核） | 6 |

`DELETE_WITH_LEGACY` + `LEGACY_ONLY` = **35** 个方法会随旧链路一起消失，
占这 3 个文件全部方法的 80%。这就是 S7 第一波删除的真实体量。

**必须等旧链路整体下线再一起删** —— 同包内部还在互相调用，单独删任何一个都会编译失败。
这也正是它们被归为「与旧链路同生共死」的原因。
