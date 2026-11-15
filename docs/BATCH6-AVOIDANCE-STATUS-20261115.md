# 批 6「结构避让」现状与方案（2026-11-15）

> **一句话**：批 6 的**代码拆分和端口外置都做完了**，唯一没做完的是
> **「5 条可观测」只在单元测试里成立，生产侧一个读者都没有** ——
> 真机上出现「避让失明」时，日志里看不到任何线索。

---

## 一、批 6 声称的状态（`docs/重制总纲.md` 批表原文）

> **6** | 结构避让 | 🟢 **就地拆分完成（未搬新包）+ 5 条可观测** |
> **23 类 / 6,333 行**（oracle 4 类 / 5,091 行，其中 `StructureAvoidanceService` 是
> **4,766 行上帝类**）；8 个测试文件。可观测：`blindQueryCount` /
> `fallbackBoxOnlyQueries` / `droppedPendingCount` / `fullPassBatchCount` /
> `unresolvedEndpointOwnerCount`

### 实测复核（本轮）

| 项 | 总纲写的 | 实测 | 结论 |
|---|---|---|---|
| 文件数 | 23 类 | **23 个文件** | ✅ 一致 |
| 行数 | 6,333 | **6,313** | ✅ 基本一致（统计口径差 20）|
| 上帝类 | 4,766 行 | **`StructureAvoidanceService` = 1,000 行** | ✅ **已拆掉 3,766 行** |
| 测试文件 | 8 个 | **15 个** | ✅ **比声称的还多** |
| 5 条可观测 | 「可观测」 | **5 条确实存在且被单测读** | ⚠️ **但生产侧零读者**，见第二节 |

⇒ **批 6 的「拆分」是实打实完成的**，不是虚报。这条要给它记上。

---

## 二、★ 真正的问题：5 条「可观测」在生产侧是**不可观测**的

### 实测（不截断，全量计数）

| 计数器 | 生产侧出现 | 测试侧出现 |
|---|---|---|
| `blindQueryCount` | **1 处** | 7 处 |
| `fallbackBoxOnlyQueries` | **1 处** | 8 处 |
| `droppedPendingCount` | **1 处** | 1 处 |
| `fullPassBatchCount` | **1 处** | 3 处 |
| `unresolvedEndpointOwnerCount` | **1 处** | 7 处 |

**那「1 处」是什么**：逐条核对后全部是**它自己的 getter 定义**：
```
StructureAvoidanceCache:117      public static long blindQueryCount() {
StructureAvoidanceDiagnostics:63 public static long fallbackBoxOnlyQueries() {
StructureInjector:49             public static long droppedPendingCount() {
StructureIndexService:48         public static long fullPassBatchCount() {
EndpointOwnerResolver:352        public static long unresolvedEndpointOwnerCount() {
```

⇒ **五个计数器全部「只写不读」**：计数在累加，**但没有任何一行日志、命令或诊断输出把它们打出来**。

### 这意味着什么（具体场景）

`blindQueryCount` 的累加点（`StructureAvoidanceCache.noteBlindQuery`）有一段 WARN：
> RoadWeaver structure avoidance queried WITHOUT a planning snapshot (first of possibly many) —
> each such query returns false, which is indistinguishable from "no structure here".
> If routes cut through buildings, check the planning-snapshot thread scope.

这段 WARN **只在第一次触发时打一行**，之后**只有每满 100,000 次再打一行**。
而 `blindQueryCount()` 本身**没有人读** ⇒
**真机上你无法知道「这一局到底盲查了多少次」** —— 只能看到「第一次发生过」和「到过 10 万次」。

于是：
- 「避让完全正常（盲查 0 次）」与「避让一直失明（盲查 50 万次但没到整十万）」在日志里**同形**；
- 这正是**病根 C（静默降级）**在**观测层**的残留 —— 代码已经不静默返回值了，
  但**它的证据链仍然不落地**。

### 同样的形态在另 4 条上
`fallbackBoxOnlyQueries` / `droppedPendingCount` / `fullPassBatchCount` /
`unresolvedEndpointOwnerCount` 全部如此：**单测能断言，真机看不到**。

---

## 二之二、顺手抓到的第 6 类假断言：`assertTrue(n >= 0)` 对计数器恒真

核 `droppedPendingCount` 的「1 处测试引用」时发现，它唯一的断言是：

```java
// StructureInjectorDropCountTest:114-117
// 计数器是进程级静态的，测试之间可能被别的用例累加过，
// 所以只断言「可读且非负」，不断言恰好为 0。
long n = StructureInjector.droppedPendingCount();
assertTrue(n >= 0, "丢弃计数必须可读且非负，实际 " + n);
```

**`long` 计数器恒 ≥ 0**（除非溢出到负数，实际不可能）⇒ **这条断言恒真**。

作者的注释解释了「为什么不断言为 0」（计数器是进程级静态、测试间会互相污染），
**这个理由是成立的**；但结论选错了 —— 正确的做法是**先 `reset` 再触发再断言增量**，
而不是退化成一条恒真断言。

⇒ **第 6 类假断言：对「天然非负的量」断言 `>= 0`。**
同族还有：对 `size()` 断言 `>= 0`、对 `length` 断言 `>= 0`、对 `elapsedMs` 断言 `>= 0`。
**判据：断言里出现 `>= 0` 就要停下来问「这个量有没有可能为负」；不可能为负 ⇒ 断言无效。**

---

## 三、批 6 的「换实现」到底做没做

| S6 的原定内容 | 现状 | 判定 |
|---|---|---|
| 上帝类 `StructureAvoidanceService` 拆分 | 4,766 → **1,000 行** + `PlanningSnapshot`(772) / `PreparedStartResolver`(793) / `PathCorridorBuilder`(490) / `SnapshotOrchestrator`(446) / `EndpointOwnerResolver`(405) 等 | ✅ **完成** |
| 避让逻辑**外置为端口** | `ObstacleField` 端口 → `StridedObstacleField`（`BandPathfinder:265` 构建）→ `FinslerMetric` → `ElasticBandSolver.smooth(BlockedCheck)` | ✅ **完成**（详见批 5/6 更正文档）|
| 避让**来源**外置 | `GroundBanSources` 三条来源（水 / 结构快照 / 已有路） | ✅ **完成** |
| **可观测** | 5 个计数器存在、有单测，**生产零读者** | ❌ **未落地** |

⇒ **批 6 剩的不是「实现没换」，而是「换完之后你看不见它在干什么」。**

---

## 三之二、★ 方案 A 已落地（2026-11-15，用户拍板「按你的方案直接做」）

上面第二节说的那个洞**已经补上了**：

`InitialRoutePersistOps` 在原有的
「`RoadWeaver initial final network ready before persistence: inputRoads=… finalRoads=…`」
那行**之后**，新增一行汇总：

```
RoadWeaver structure-avoidance counters: blindQuery={} fallbackBoxOnly={}
    droppedPending={} fullPassBatch={} unresolvedEndpointOwner={}
    (blindQuery>0 说明有避让查询发生在没有可用规划快照的线程上；
     snapshot=absent 时结构来源整段未生效)
```

五个计数器一次打在同一行 ⇒ **排查时一个 grep 就能拿到「这一局规划阶段避让到底发生了什么」**。

**改动范围（严格遵守「只加观测」）**：
- 只加 `LOGGER.info`，**没有改任何判据、返回值或调用顺序**
- 位置在 `InitialRoutePersistOps`（490 行，远低于千行上限）
- `StructureVerificationObservabilityTest:95` 那条「`fullPassBatchCount()` 不许参与判据」的守卫
  仍然成立（本次只是打印，没有比较）

**它解决了什么**：在此之前真机上
「避让完全正常（盲查 0）」与「避让一直失明（盲查 50 万）」**在日志里完全同形** ——
这正是病根 C 在观测层的残留。现在至少能在规划结束那一行读到确切数字。

**它没解决什么（如实标注）**：
- 数字**是否合理**（多少算多）要真机基线，本轮无法判定
- `snapshot=ok/absent` 本身仍不可信 —— 见
  [`SILENT-DEGRADE-EMPTY-SNAPSHOT-20261115.md`](SILENT-DEGRADE-EMPTY-SNAPSHOT-20261115.md)
  与批 2 的 B 方案（待真机）

---

## 四、三个方案

### 方案 A：把 5 个计数器接进「一次性汇总日志」（推荐）
**做法**：在初始规划阶段结束处（`InitialRoutePersistOps` 的最终网络就绪日志那一带，
已有 `inputRoads / finalRoads / refined / mergeMs` 的那行）**加 5 个字段**，
一次打出来。**不改任何判据、不改任何返回值。**
- 行为变化：**零**（只多一行日志）
- 风险：**零**
- 需要真机：只需要**读一行日志**确认字段有值，不需要验收行为
- 代价：`InitialRoutePersistOps` 已接近千行上限，需先确认加得进去（或加在它的日志行里不增行）

### 方案 B：加个 `/roadweaver stats` 命令
**做法**：让玩家/服主在游戏内查询这些计数。
- 行为变化：新增命令（可见的功能变化）
- 风险：中（新命令要注册、要有权限、要进 lang）
- 需要真机：需要（要试命令能不能跑）
- **不建议现在做** —— 收益（给人看）不如 A（给日志看），成本高得多

### 方案 C：不动，只在文档里把「5 条可观测」改写成「5 个计数器，仅单测可见」
**做法**：纠正总纲那句话。
- 行为变化：零
- 风险：零
- 需要真机：不需要
- **这是最低成本、立刻可做的一步**

### 建议
**先 C 后 A**：C 是文档纠错（本轮就能做，不需要拍板）；A 是一行日志的改动
（需要拍板，但风险为零）。

---

## 五、这份分析**没有**做什么

- **没有**核 `droppedPendingCount` 那个唯一的测试引用是「断言」还是「重置」
  （只 1 处，很可能是 `reset` 而非 `assert`）—— 若只是 reset，它连单测覆盖都不完整
- **没有**核这 5 个计数器**是否真的会在生产路径上累加**（有累加点，但频率/条件未验）
- **没有**核批 6 的 23 个类是否还有与批 4 一样的「大面积零覆盖」
- **没有**改任何代码
- 本轮我三次修正了自己的结论（见第六节），最终数字是第三节/第二节那两张表

---

## 六、本轮我自己的三次修正（记录，避免再犯）

| 次序 | 我的初判 | 真相 |
|---|---|---|
| 1 | 「5 条可观测全都零调用者」 | 实际**测试侧有 1~8 处**引用，只是**生产侧**零读者 |
| 2 | 「`blindQueryCount` 与 `fallbackBoxOnlyQueries` 零读者（连测试也没有）」 | **错**：`FallbackBoxQueryCountTest` 就在读它 —— 我上一步用 `head -8` **截断了输出**才误判 |
| 3 | 「`StructureAvoidanceService` 用方法规模解析器分析」 | 解析器被续行干扰（把 `return new ...` 当方法声明），数据不可用，已放弃该方法 |

⇒ **教训（与前几轮同源）：`head -N` 会静默截断，用它做「有没有」的判据必然出错。
判断「零引用」必须用 `wc -l` 拿全量计数，不能用「看起来没有」。**
这与第 47 轮「按文件名 grep 判死代码」是同一类错误：**取样不完整就当结论**。