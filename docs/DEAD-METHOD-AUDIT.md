# 死方法审计（2026-11-13）

> **相关文档**：本文是**方法论 + 一次性清单**。方法级删除边界见
> [`S5-METHOD-MAP.md`](S5-METHOD-MAP.md)；删除顺序与验收见
> [`S7-SWITCH-CHECKLIST.md`](S7-SWITCH-CHECKLIST.md)。

> 起因：`DeadCodeGuardTest` 只查**类型**（零引用类型会被拦），但**方法**不在它视野里。
> 本轮手工审计发现并删除了 2 个确认无人调用的公共方法。

## 方法

1. 扫全模块（`common` / `fabric` / `api` / `api-fabric`，含 `src/test`）拼成语料；
2. 对 ≥250 行文件里的每个 `public` 方法，统计「方法名 + `(`」在全语料的出现次数；
3. 减去声明本身 + 同文件内部调用 ⇒ 得到「外部 + 内部都为 0」的候选；
4. **逐个人工核实**（这一步不能省，见下）。

## 结果：原始候选 160 个，绝大多数是误判

第一遍扫出 **160 个零引用公共方法**。抽样核实 5 个，4 个是误判：

| 例子 | 为什么是误判 |
|---|---|
| `BlockCandidateWidget.narrationPriority()` | 上方有 `@Override` —— 框架回调，永远不会被本项目代码调用 |
| `SimpleTemplatePiece.handleDataMarker(...)` | `protected` 覆写父类 `TemplateStructurePiece` 的方法 |
| `RoadMapScreen.acceptSnapshotPart(...)` | 通过方法引用 `this::acceptSnapshotPart` 传入，源码里没有 `name(` 形态 |
| `PlanningConfig.setXxx(...)` | 配置类的程序化 setter：GSON 直接读写字段，setter 属 API 表面 |

**结论：按名字统计方法引用，对「覆写 / 方法引用 / 配置 API」三类系统性失效。**
所以**大规模删除方法级死代码是危险的** —— 160 个里真正能删的是个位数。

## 本轮确认并删除（2 个）

| 方法 | 为什么确认安全 |
|---|---|
| `SegmentPaver.pavedOverProtectedRoadbed()` | 全仓唯一命中是它自己的声明。**计数器本身保留** —— 它还在给日志做节流（`paved <= 8 \|\| paved % 64 == 0`），只是 getter 没人读 |
| `RoadConstructionVolumePlanner.collect(road, context)` | 8 行薄封装（内部只是调 `visit` 攒列表），全模块零调用（含测试）|

删后 `verify.ps1` 全绿。

## 值得后续处理但**本轮不动**的

这些看着像死代码，但删之前需要产品判断：

| 类 | 方法 | 为什么要谨慎 |
|---|---|---|
| `RoadSnapService` | `snapAllRoads` / `snapAllRoadsAsync` | 可能是给外部/未来的全量重吸附入口 |
| `ThreadPoolManager` | `resizeInitialPool` / `resizeGenerationPool` / `resizeComputePool` / `computeExecutor` / `generationExecutor` | 线程池的公开控制面，可能是运维/调试入口 |
| `StructureFileStorage` | `claimScanTile` / `markScanTileDone` / `releaseScanTile` 等 | 预生成器扫描协调用的 API，可能有外部调用方 |
| `RouteEditorSession` / `RouteEditorState` | `isDirty` / `routeCount` / `setEditing` 等 | 编辑器状态 API，UI 侧可能通过方法引用使用 |
| `PathfindingCostConfig` / `PlanningConfig` / `PerformanceConfig` | 大量 `setXxx` | 配置 API 表面（同 `PlanningConfig` 的理由）|

## 给后续的建议

1. **不要**用这个方法名单批量删 —— 误判率太高；
2. 想真正自动化，需要**编译期/字节码级**的引用分析（例如 `jdeps` 或用 JavaParser 解析 AST 并解析方法引用 `::`）；
3. 目前的务实做法：**改到哪个文件，就顺手对这个文件跑一次按名审计 + 人工核实** —— 成本低、误判可控。

## 一条方法论结论

> 按名字统计"引用"这件事，对**类型**够用（类型名不会以 `::Type` 形式出现），
> 对**方法**不够用（`::method`、覆写、反射都不留名字痕迹）。
> 这就是为什么 `DeadCodeGuardTest` 只做类型级 —— 那不是我偷懒，是名字级分析的上限。