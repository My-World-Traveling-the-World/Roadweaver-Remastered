# 交接台账（HANDOFF）

> **规则：每次停下前必须更新本文件。** 接手方靠它判断「什么做完了、什么没验证、哪里是半截」。
> **「我没什么没能验证」这一栏是核心** —— 写得越老实，接手方越快。

---

## 模板（复制这一段填写）

```markdown
## 第 N 轮 ｜ <开始时间> → <停止时间> ｜ HEAD `<commit>` ｜ `PASS tests=<N> skipped=0 aborted=0 failed=0`

### 一、改了什么
| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|

### 二、我验证了什么（**必须附负控做法**）
| 新增/改动的用例 | 负控做法（故意改坏什么） | 结果（红/绿） |
|---|---|---|

> ⚠️ 只写「测试通过」不算验证。**必须写「我改坏了什么，它红了没有」** ——
> 本项目已实证：992 全绿的同时，9 处故意破坏（含「模板桥完全不落块」）也全绿。

### 三、我**没能**验证什么（最重要的一栏）
- **需要真机的**：
- **需要真实存档的**：
- **我怀疑但没有证据的**：
- **我知道它现在会坏但没修的**：

### 四、遗留半截的东西
| 文件 | 什么状态 | 怎么补 |
|---|---|---|

### 五、下一轮我会先做什么（如果你没接手的话）

### 六、我踩到但已解决的坑（省接手方时间）
```

---

## 已完成轮次记录

### 第 0 轮 ｜ 外部独立审计 ｜ `954b6f3`

产出：`审查报告.md`（232 行）+ `复现说明.md` + `ReviewProbe.java`，
存放于 `D:\云游四海-Mod源码\RoadWeaver-954b6f3-独立审查\`。

核心产出：
- 两个 P0（索引被瞬时读失败永久剔除 / 隧道覆土符号反转）
- **15 次变异中 9 次故意破坏仍 992 全绿**（M1/M2/M4/M5/M6/M7/M8/M10/M11）
- 纠正了前任两条过时结论（`PlanningSnapshot` 已是顶层 / 通配 import 已被守卫匹配）

### 第 1 轮 ｜ 修两个 P0 ｜ `4bc34ee`

**改了什么**

| 文件 | 改动 | 性质 |
|---|---|---|
| `pathlogic/profile/ProfileFallback.java` | 覆土从 `terrainY + cover` 下界改成 `terrainY - cover` 上界 | **行为变更** |
| `persistence/chunk/RoadChunkStore.java` | `LoadedSnapshot` 加 `readFailure` 字段；单条 IO 失败 ⇒ 只读降级（拒绝一切写入） | **行为变更** |
| `ProfileFallbackTest.java` | 改正那条把缺陷固化的断言（原断言「路面应高于地表」），另补 2 条 | 测试 |
| `persistence/chunk/RoadChunkStoreReadFaultTest.java` | **新增**，用 Windows `NOSHARE_READ` 真实文件锁复现 | 测试 |
| `docs/P0-FIX-20261115.md` | 核实结论 + 修法 + 验证数据 | 文档 |

**验证记录**

| 用例 | 负控做法 | 结果 |
|---|---|---|
| `隧道站必须被压到地表以下至少覆土深度` | 符号改回 `terrainY + cover` | 🔴 红 |
| `隧道覆土与最大挖方冲突时让步的是挖方` | 同上 | 🔴 红 |
| `隧道站绝不允许被抬到地表以上` | 同上 | 🔴 红 |
| `单条道路读失败时索引必须原样不动_解锁后必须重新看到两条` | 把 `if (readFailure && !readOnlyDegraded)` 改成 `if (false && ...)` | 🔴 红 |

真实文件锁探针（一次性，未入库）：
- 修复前：`saved=2 whileFault=1 afterUnlock=1`（索引被改写，永久丢一条）
- 修复后：`saved=2 whileFault=1 afterUnlock=2 indexIntact=true`
- 拿 HEAD 旧版跑同一探针：`AssertionError: P0-1 NOT FIXED`（证明探针有判别力）

**没能验证 / 遗留**
- **需要真机**：P0-2 改后要看陆隧洞口、桥口上坡、结构端点、水线。
- **需要真实存档**：P0-1 的只读降级要在存档副本上做一次冒烟（锁一份 `.nbt` 起服 → 确认索引不变 → 解锁重启 → 确认两条都在）。
- **平台限制**：`RoadChunkStoreReadFaultTest` 的故障注入用 Windows `NOSHARE_READ`，非 Windows 上 `assumeTrue` 跳过 ⇒ 那里不提供保护。
- **9 个无守卫面一个都没补**（按用户拍板「先修 P0，守卫后补」）。

---

## 第 2 轮 ｜ 2026-11-15 19:09 → 19:41（北京时间）｜ 代码 HEAD `9c3dc4f` ｜ `PASS tests=1014 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| `BridgeSegmentPlannerNew.java`、`ExistingRoadJunctionService.java` | `8c96a3a`：模板查询、水线、保护、写块、存档查路、精修及捐赠路写回接到端口；选择与几何留在原实现 | 行为等价 |
| `BridgeTemplatePlacementTest.java`、`JunctionOrchestrationTest.java` | `f87c10a`：实际断言桥口模板写块与区块边界；批次选主、单路接网与拒绝。拒绝日志补 `structureReason` | 几何等价，诊断行为变更 |
| `PostProcessedRoadProductTest.java`、`PathMetricsOps.java`、`GeometryProtectionProductTest.java` | `a36e623`、`9c3dc4f`：生产重载真实生成；站序、宽度、桥口及施工正弦；用侵入保护列的产物验证生产择优与拒绝 | 行为等价 |
| `ArchitectureGuardTest.java`、`JavaDependencyScanTest.java` | `287f470`：Javac 解析普通、静态、通配、同行导入与显式全限定名；已有 40 条全限定跨包依赖边单独建基线，旧导入基线未放宽 | 行为等价 |
| `RoadConstructionLedger.java`、`StructureInjector.java` | `8d1469f`：台账真实写文件并重读；结构和村庄的结算复用标记后累加，测试读取实际增量 | 行为等价；台账 schema 保持 3 |
| `PipelinePlanner.java`、`RoadPipeline.java`、`MinimumSpanningTree.java`、`TopologyPlan.java`、`QualityGate.java` | `7471580`：正常规划与退化路径统一以原端点三维直线距离限长；先裁候选再选树。正常生产报告评估同一森林，显示分量；严格拓扑报告识别无孤点的断网 | 行为变更；生产仍只报告 |
| `BuildFrame.java`、`BridgeRangeCalculator.java`、`BuildFrameTest.java` | `01f529f`：计算和缓存共用完整桥参数；核对正向站序，热命中不重新扫站。修正原先可空转的区间深拷贝测试 | 行为变更；存档道路指纹保持原有算法 |
| `RoadJUnitRegressionSuite.java` | 新测试类逐一列入 FQCN 清单 | 行为等价 |

### 二、我验证了什么（必须附负控做法）

所有替换都检查锚点唯一；全跑 `:common:roadJUnitRegressionSuite`，确认目标测试失败后 `git checkout -- <file>`。以下红灯均是断言失败，非编译失败。最终在恢复后的代码上重跑回归及 `:fabric:compileJava`。

| 新增/改动的用例 | 负控做法（故意改坏什么） | 结果 |
|---|---|---|
| M1 后处理产物 | 完整重载还计算、还打日志，但把合法整路的返回值改为空 | 红：陆路、桥路产物断言 |
| M2 模板落块 | 循环仍遍历但跳过所有模板体素写入，保留 `true` 返回 | 红：陆侧桥口中心未写模板板 |
| M4 施工台账 | 新完成项写到内存但脏标记恒为 false | 红：写盘、读回断言 |
| M5 层依赖 | 在 `ElasticBandSolver` 加全限定 `pipeline.RoadPipeline` 字段 | 红：显式分层及全限定基线 |
| M6 同行 import | 在 `RoadMapScreen` 的 package 分号后同行引入 `persistence.RoadConstructionLedger` | 红：导入依赖基线 |
| M7 单路接网 | 非空运行端口同样早退，根本不查路 | 红：单路复用产物 |
| M8 批量选主 | 用原次路和空 donor 替换真实选主 | 红：选主精修、结构拒绝两例 |
| M11 注入计数 | getter 恒返回 `0L`，不改后台加法 | 红：增量读回断言 |
| 后处理宽度 | 成路时用 width + 2 | 红：截面列集合 |
| 长桥正弦 | 跳过长桥弯曲分支 | 红：中点没有偏移 5 格 |
| 保护列主动负控 | 曲线候选跳过碰撞检查，直接返回 | 红：侵入候选被下发，两例均失败 |
| 路网候选 | Kruskal 忽略准入条件 | 红：错选 AC，丢失合法 BC |
| 路网报告 | 严格模式跳过分量检查 | 红：两个双节点分量被误报通过 |
| 桥缓存键（两缺陷） | 完整换回 `01f529f^` 的旧 `BuildFrame.java` | 红：参数碰撞和反向站序两例 |
| 桥区间深拷贝 | 区间直接返回原 `int[]` | 红：修改一份结果污染了下一份 |

### 三、我没能验证什么

- **需要真机的**：真实注册表选择、模板 NBT、世界邻接更新、桥墩和灯光；隧道捐赠路的真实存储写回、路口/结构精修和线程交错；曲线保形、最终净空与过村裁切实景；后处理三个简单重载离线只测了短输入，合法输入走生产的完整重载。未做双平滑 A/B 或阈值标定。
- **需要真实存档的**：既有完工台账升级、定向撤销后的重施工效果、道路提交与完工台账之间的崩溃恢复。只在临时文件上验证 schema 3 写→重开→读；未打开玩家存档。
- **我怀疑但没有证据的**：端口化前后真机单路耗时差、采样失败退化时限长口径改变了多少条实际边。本轮没有定量数据。
- **我知道它现在会坏但没修的**：同道路指纹下仅改 `targetY` 或跨度，旧完工标记仍可能阻止重新施工（`PathFeature.processRoadSlices` 用存储指纹查台账）。直接换所有台账键会让未改动的旧路也重新施工；只在替换后清条目，又存在写盘失败、崩溃窗口和旧计划并发回写。另：简单结构注册表查无 ID 时会 warn、随后区块级标记仍清待办，但该分支没加 `droppedCount`（`StructureInjector.java:80–83,133`）；结算守卫没有声称覆盖这个遗漏。

### 四、遗留半截的东西

| 文件 | 什么状态 | 怎么补 |
|---|---|---|
| `RoadConstructionLedger.java`、`RoadChunkStore.java`、`PathFeature.java` | 桥掩码键已独立；持久化完工版本尚未切换 | 保持稳定身份与 schema 不变。先用临时存档串起「同身份换高度/跨度」「未改动道路保留完成」「替换失败不撤销」「旧切片晚到不能写回新完成」，再定向接线 |
| `TargetYComputation.java` / `ProfileDpSource` | crossing 解耦未启动 | 等用户提供桥隧真机召回率对照与口径 |
| `StructureInjector.java` | 查无结构 ID 已确认漏计 | 先用可控 registry 夹具触发缺项，确认标记成功后的增量和告警，再修这个分支 |

### 五、下一轮我会先做什么（如果你没接手的话）

先做完工版本的临时存档与旧切片并发失败夹具，再做能保留未变道路完工状态的失效设计。没有真机召回率数据就继续保留 crossing 现状。

### 六、我踩到但已解决的坑（省接手方时间）

- `main` 起初没有跟踪分支；`git pull --rebase origin main` 确认与远端同为 `c80eb0d`。直接 Gradle 8.8 + JDK 21 + daemon 定标为 995 项。
- 编辑 Windows 上的 Java 源码时要显式保留 LF；源文件行尾守卫会抓到整文件 CRLF。只看 Gradle 失败不算负控：本轮逐个确认了指定的 JUnit 类确实红。
- 桥面最终正弦由 `BridgeSegment` 产生；`process` 本身不读保护列，下游 `PathMetricsOps` 才决定采用哪份产物。测试分别打到负责相应行为的生产代码。
- 冷读故障 Windows 负控沿用前任；没有把“结算的 getter 会变”夸大为“所有真实注入分支均被跑过”。

---

## 已知的「故意破坏仍全绿」清单（第 2 轮已全部补齐）

| 编号 | 破坏方式 | 应该由什么守卫抓到 | 状态 |
|---|---|---|---|
| M1 | 后处理整路丢弃 | 直接测 `PathPostProcessor.process` 的产物 | ✅ 第 2 轮负控已红 |
| M2 | **模板桥完全不落块** | 直接测模板桥实际写入的方块集合 | ✅ 第 2 轮负控已红 |
| M4 | 施工台账永不置脏 | 台账写→重开→读的行为回归 | ✅ 第 2 轮负控已红 |
| M5 | `band` 用全限定类型依赖 `pipeline` | 层依赖守卫识别全限定名 | ✅ 第 2 轮负控已红 |
| M6 | 客户端同一行 import `persistence` | 层依赖守卫识别同行 import | ✅ 第 2 轮负控已红 |
| M7 | 非空 level 的单路接网直接返回 | 直接测单路接网产物 | ✅ 第 2 轮负控已红 |
| M8 | **批量禁用选主合并** | 直接测路口选主合并产物 | ✅ 第 2 轮负控已红 |
| M10 | 存储只读条件恒假 | P0-1 的读故障测试（**已顺带覆盖**） | ✅ 已覆盖 |
| M11 | 结构注入丢弃计数 getter 恒 0 | 计数器行为回归 | ✅ 第 2 轮负控已红 |