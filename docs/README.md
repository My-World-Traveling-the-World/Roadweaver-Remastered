# docs/ 文档地图

> 想知道「从哪读起」看这一页就够了。每份文档都标了**性质**与**什么时候该读它**。

---

## 一、按"你想知道什么"找

| 你想知道 | 读这份 |
|---|---|
| **重构的总方向、现状、下一步** | [`重制总纲.md`](重制总纲.md) ← **唯一权威路线图**（方案 + 现状 + 5 缺陷 + 路线图）|
| **明天接手要做什么** | [`HANDOVER.md`](HANDOVER.md) ← **只读这一页也能动起来** |
| 现在这个仓库能做什么、怎么用、还差什么 | [`STATUS-20261115.md`](STATUS-20261115.md) |
| 重构的过程、每一次判断与误判 | [`REFACTOR.md`](../REFACTOR.md) |
| 当前发布件、SHA-256、回退与真机验收清单 | [`RELEASES.md`](RELEASES.md) |
| 怎么把新内核切成默认 | [`S7-SWITCH-CHECKLIST.md`](S7-SWITCH-CHECKLIST.md) |
| 桥隧回写怎么接（待拍板的设计稿） | [`DESIGN-SPAN-WRITEBACK.md`](DESIGN-SPAN-WRITEBACK.md) |
| 旧仓库报的死码在新仓库还成不成立 | [`DEADCODE-AUDIT-20261115.md`](DEADCODE-AUDIT-20261115.md) |
| **旧版到底有哪些功能、重制时一个都不能丢** | [`functional-inventory/README.md`](functional-inventory/README.md) ← **8 个功能域的逐条盘点，核对「旧功能没丢」就看这里** |
| **旧功能保真核查做到哪了** | [`FEATURE-PARITY-CHECK.md`](FEATURE-PARITY-CHECK.md) ← **逐条符号级核对的结果 + 方法局限（先读局限）** |
| **涵洞阈值「口径冲突」到底有没有影响** | [`CULVERT-THRESHOLD-ANALYSIS-20261115.md`](CULVERT-THRESHOLD-ANALYSIS-20261115.md) ← **结论：施工期站数上限从未生效，但实际无害；三个方案在第三节** |
| 哪些旧代码可以删、删到什么粒度 | [`S5-METHOD-MAP.md`](S5-METHOD-MAP.md) |
| 后处理 20 个文件分别是留还是删 | [`S5-POSTPROCESS-LEDGER.md`](S5-POSTPROCESS-LEDGER.md) |
| 死代码怎么审（以及为什么不能批量删） | [`DEAD-METHOD-AUDIT.md`](DEAD-METHOD-AUDIT.md) |
| 维护性指标快照 | [`STATUS-20261115.md`](STATUS-20261115.md) 的「九、附录」节（原 METRICS 已并入）|
| **下一步该给哪补测试** | [`TEST-BLIND-SPOTS-20261115.md`](TEST-BLIND-SPOTS-20261115.md) ← **盲区清单，先读它的「判据局限」再看结论** |

| **批 3 到底做到哪了** | [`BATCH3-LIVENESS-AUDIT-20261115.md`](BATCH3-LIVENESS-AUDIT-20261115.md) ← **14 职责全在活链路上，但「换实现」只做了一半：桥隧建造仍跑旧包 4,887 行** |
| **UI 到底有多独立** | [`UI-COUPLING-AUDIT-20261115.md`](UI-COUPLING-AUDIT-20261115.md) ← **13 个 client 类 import 业务包，其中 5 处真越界（直连持久化 / worldgen 编译器）** |
| **代码里还有多少「说了没做」的常量** | [`DEAD-CONSTANT-TRIAGE-20261115.md`](DEAD-CONSTANT-TRIAGE-20261115.md) ← **79 个零引用常量分诊：6 个注释声称有行为但从未实现，已加警示** |
| **批 4/5/6/7 有独立新实现包吗** | [`BATCH4567-PACKAGE-AUDIT-20261115.md`](BATCH4567-PACKAGE-AUDIT-20261115.md) ← **没有，四个 newPkg 至今不存在；真实做法是就地重构** |
| **新引擎到底接管了几个批** | [`BATCH4567-TAKEOVER-AUDIT-20261115.md`](BATCH4567-TAKEOVER-AUDIT-20261115.md) ← **只有批 1/2；批 3/4/5/6 新引擎都没接管，批 7 仍 REPORT-ONLY** |
| **批 5/6 到底缺什么（含我自己的更正）** | [`BATCH56-CORRECTION-AND-PLAN-20261115.md`](BATCH56-CORRECTION-AND-PLAN-20261115.md) ← **批6 硬约束已接（我说错了）；批5 真问题是双平滑；方案 A/B/C 在第三节** |
| **那些守卫真的在守吗** | [`GUARD-EFFECTIVENESS-AUDIT-20261115.md`](GUARD-EFFECTIVENESS-AUDIT-20261115.md) ← **10 个守卫会静默跳过（3 个是全部用例可跳）；已补元守卫堵住** |
| **静默降级修全了吗** | [`SILENT-DEGRADE-EMPTY-SNAPSHOT-20261115.md`](SILENT-DEGRADE-EMPTY-SNAPSHOT-20261115.md) ← **只修了 null，EMPTY 仍静默；同一混淆扩散 7 处** |
| **我写过的假测试** | [`MY-OWN-FAKE-TESTS-20261115.md`](MY-OWN-FAKE-TESTS-20261115.md) ← **两条恒真断言，负控实证「改坏也不红」；已重写为 3 条会真红的** |
| **空集合让测试空转** | [`VACUOUS-LOOP-TESTS-20261115.md`](VACUOUS-LOOP-TESTS-20261115.md) ← **第五类假测试：改坏了反而更绿；73→19→2 逐层收窄** |
| **批 4 到底卡在哪** | [`BATCH4-JUNCTION-STATUS-20261115.md`](BATCH4-JUNCTION-STATUS-20261115.md) ← **不是没新引擎，是 1,228 行 / 65% 无测试（两个生产主入口零覆盖）** |

---

## 二、性质分类（决定它会不会过期）

| 文档 | 性质 | 会过期吗 |
|---|---|---|
| `重制总纲.md` | **权威路线图** | 会 —— 现状变了就同步改它（不另开新文件）|
| `STATUS-*` | **当前快照** | 会 —— 有新进展就作废，需要重写一份新的 |
| `REFACTOR.md` | **过程日志** | 不会 —— 追加式，历史事实 |
| `S7-SWITCH-CHECKLIST` | **操作手册** | 不会（除非流程变）—— 照着做就行 |
| `S5-*` | **台账** | 会，但**由测试守着**：`PostprocessLedgerTest` / `S5MethodMapTest` 会因代码漂移而红 |
| `DEAD-METHOD-AUDIT` | **方法论 + 一次性清单** | 清单会过期，方法论不会 |
| `functional-inventory/` | **旧版功能清单（验收基线）** | 不会过期 —— 基线是冻结的 oracle；它的用途就是**对照检查重制版有没有丢功能** |
| `archive/` | **已归档的旧版文档** | 不会 —— 历史封存，只读 |

> 一句话：**带日期的是快照（会过期），不带日期的是手册/台账（稳）**。

---

## 三、文档依赖关系

```
重制总纲.md（权威路线图，入口）
  ├─► STATUS-20261115.md（当前状态 + 指标附录）
  ├─► S7-SWITCH-CHECKLIST（怎么切默认）
  │     ├─► S5-METHOD-MAP（删什么，方法级边界）
  │     │     └─► S5-POSTPROCESS-LEDGER（文件级分类）
  │     └─► verify.ps1（每步验收）
  └─► DEAD-METHOD-AUDIT（死代码方法论）

REFACTOR.md（过程日志）──► 引用以上全部
archive/（历史封存）──► 只读，不再更新
```

---

## 四、四份机器可检的台账（改了代码就必须同步）

| 台账 | 守着它的测试 |
|---|---|
| `S5-POSTPROCESS-LEDGER.md` | `PostprocessLedgerTest` |
| `S5-METHOD-MAP.md` | `S5MethodMapTest` |
| `REFACTOR.md` 里的重制替换台账 | `LegacyLedgerTest` |
| `architecture/deps-baseline.txt` | `ArchitectureGuardTest` |

**这四份不是"文档"，是"配置"** —— 它们与代码一起被断言，漂移就红。

---

## 五、已归档（`archive/`，历史封存）

| 归档文件 | 原定位 | 归档原因 |
|---|---|---|
| `audit-report-20260902.md` | 2026-09-02 旧版 0.8.160 审查 | 已过时（旧版 `features/path/...` 路径）|
| `fix-changes-20260902.md` | 2026-09-02 修复清单 | 同上，且引用的 audit-report 也归档 |
| `RoadWeaver-性能与质量深度分析报告-0.8.406基线.md` | 旧版 0.8.406 基线 | 已过时 |
| `AUDIT-20261113.md` | 三路并行体检（三条病根）| 内容已被 `重制总纲.md` 吸收 |

---

## 五之二、旧版功能盘点（`functional-inventory/`，8 个功能域）

> **基线**：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`（冻结的 oracle）。
> **用途**：这是「旧版能力一个都不能丢」的**核查工具** —— 重制版每完成一批，
> 都该对着对应域过一遍，而不是凭印象说「应该都覆盖了」。
> **读法**：入口是 [`functional-inventory/README.md`](functional-inventory/README.md)（含总表与状态）。

| # | 功能域 | 文件 | 覆盖要点 |
|---|---|---|---|
| 01 | 规划与寻路 | [`01-规划与寻路.md`](functional-inventory/01-规划与寻路.md) | 6 拓扑规划器 / 4 寻路器 / 采样系统 / OpenCL |
| 02 | 结构避让与路口 | [`02-结构避让与路口.md`](functional-inventory/02-结构避让与路口.md) | 村庄判据 / PlanningSnapshot / 结构预测 / 起点 owner / 路口合并 / 保护缺口 / 端点 / **9 条静默降级总表** |
| 03 | 跨度与桥隧 | [`03-跨度与桥隧.md`](functional-inventory/03-跨度与桥隧.md) | 跨度提取 / 桥范围 / 运行期桥提升 / 桥几何 / 桥共享 / 隧道 / 涵洞 / 浮标 / 观景穹顶 / **依赖读世界的判据清单** |
| 04 | OpenCL 加速 | [`04-OpenCL加速.md`](functional-inventory/04-OpenCL加速.md) | 上下文生命周期 / 内核缓存 / 粗采 GPU / **精确采样「一次都没用过 GPU」的实证** / 显存策略 / 一致性校验 |
| 05 | 纵断面与后处理 | [`05-纵断面与后处理.md`](functional-inventory/05-纵断面与后处理.md) | **15 道 pass 各自解决什么、为何互相打架** / 坡度 AUTO / 1-2-1 平滑 / 端点锁定 / PathPostProcessor / 锯齿度量 / 急弯冻结 |
| 06 | 施工与装饰 | [`06-施工与装饰.md`](functional-inventory/06-施工与装饰.md) | RoadBlockPlacer / 三态保护列 / 上方清空与洞身分家 / planGrade / 运行期桥提升 / 隧道清空台账 |
| 07 | 配置与 UI | [`07-配置与UI.md`](functional-inventory/07-配置与UI.md) | 配置分层 / **「GSON 字段名就是配置键」硬约束** / 界面结构 / 死键判定 / lang / 手绘路线 UI / 地图 |
| 08 | 持久化与 API | [`08-持久化与API.md`](functional-inventory/08-持久化与API.md) | RoadData Codec / 分片存储 / **写事务 5 步（index.json 是提交点）** / 台账与指纹 / 空间索引 / API 契约 |

**重要说明**：`02` 里点名的 `ObstacleJudge` / `PreparedStartResolver` / `EndpointOwnerResolver` /
`ProtectedGapEvaluator` / `WorldgenAvoidance` 在**旧仓库三模块均无同名类**（实测 grep 零命中）——
旧仓库 `pathlogic/structures/` 只有 4 个类，其中 `StructureAvoidanceService` 是 **4,766 行上帝类**。
那 5 个类名是 **重制版拆分时造出来的**，不要到 oracle 仓库里找它们。

---

## 五之三、其他目录

| 目录 | 内容 | 性质 |
|---|---|---|
| [`audit-20261113/`](audit-20261113/) | 三路并行体检的 5 份分域报告（A 结构避让 / B 跨度吸附 / C 纵断面 / D 生成链 / E 桥隧路口）| 一次性审计，结论已被 `重制总纲.md` 吸收 |
| [`plans/`](plans/) | 早期方案草稿（如 `2026-03-26-non-map-gui-refactor.md`）| 历史方案，未实施或已过期 |
| [`archive/`](archive/) | 更早的旧版文档 | 只读封存 |

---

## 六、不在 docs/ 但同样重要的

| 路径 | 作用 |
|---|---|
| [`verify.ps1`](../verify.ps1) | 双模块闸门（fabric 编译 + common 回归），**每次提交前必跑** |
| [`ARCHITECTURE.md`](../ARCHITECTURE.md) | 分层与包职责 —— **先读它顶部的「时效性更正」**：`foundation.*` 那套分层从未落地，真实架构是保留旧顶层包就地重构 |
| [`CONVENTIONS.md`](../CONVENTIONS.md) | 编码约定 —— **先读顶部「时效性更正」**：它描述的是旧仓；仍然成立的是 §4.1 三个环境坑（JDK21 / Gradle 8.8 / 离线）与 §4.3 部署规矩 |
| `common/src/test/java/.../architecture/` | 架构、台账、UI 与配置守卫的实现（数量以回归套件登记为准）|
