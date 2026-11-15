# 批 4「路口与合并」现状与方案（2026-11-15）

> **这份文档纠正此前对批 4 的判断。**
>
> 之前两轮的结论是「批 4 没有新引擎，需要先定设计」。
> **那句只说对了一半。** 真正的问题是：
> **批 4 的 1,882 行里，有 1,228 行（65%）实质没有测试 —— 包括两个生产主入口。**
> 在这种状态下，「换实现」不是可选项，是**危险动作**。

---

## 一、批 4 的代码构成（4 类 / 1,882 行）

| 类 | 行数 | 职责（文件头原文） |
|---|---|---|
| `ExistingRoadJunctionService` | **842** | 新路到达已连通网络时复用既有路 —— 避免在已有路上再铺一条 |
| `ParallelOverlapResolver` | **386** | 并行走廊重叠的消解与改道 —— 只判「并排了没」并给出改道方案 |
| `CrossingNegotiator` | 304 | 把同一批规划的「过河点」按 XZ 邻近聚簇，为每簇选一个共享过河点（纯函数） |
| `RoadBraidDetector` | 350 | 全批次两两「并排重叠（麻花）」判定 —— 只读、无世界访问、可单测 |

**全部在活链路上**（不是死代码）：
- `ExistingRoadJunctionService` 被 **17 个生产文件**引用，两个生产主入口是
  `Road.java:304` → `connectToExistingNetwork(...)` 与
  `InitialRoutePersistOps:152-153` → `connectPlannedBatchDetailed(...)`
- `CrossingNegotiator` 3 个生产引用 + 3 个测试文件
- `ParallelOverlapResolver` 2 个生产引用
- `RoadBraidDetector` 3 个生产引用 + 4 个测试文件

---

## 二、★ 真正的问题：测试覆盖

### 方法级实测（在全部 `common/src/test/**` 里搜方法名）

| 类 | 方法数 | 被测试实际调用的方法 | 判定 |
|---|---|---|---|
| `ExistingRoadJunctionService` | 36 | **只有 `alignJunctionProfile`（1 个）** | **842 行里约 841 行无覆盖** |
| `ParallelOverlapResolver` | 9 | **0 个** | **386 行全无覆盖** |
| `CrossingNegotiator` | ~10 | 4 个用例覆盖核心判定 | 可以 |
| `RoadBraidDetector` | ~18 | 8 个用例（两个测试文件）| 可以 |

### 关键细节：两个生产主入口零测试
- `connectPlannedBatch` / `connectPlannedBatchDetailed` —— **测试里搜不到**
- `connectToExistingNetwork` —— **测试里搜不到**

而这两个方法正是 `InitialRoutePersistOps` 与 `Road` 生产链上**唯一**的合并入口。
换句话说：**道路之间怎么合并、怎么接既有网络，目前没有任何自动化验证。**

### 唯一的「测试提及」其实是假信号
`ParallelOverlapResolver` 在测试源码里只出现 **1 次**，而且是在
`RoadBoundsGapTest:14` 的 **javadoc 注释**里：
> `{@code ParallelOverlapResolver.boxMayOverlap} 直接拿它跟阈值比`

**那不是测试，是文档。** 我上一轮按「文件名提及数」统计得出「生产 2 / 测试 1」，
容易被读成「有 1 个测试文件覆盖」—— 实际是 0。

同理 `ExistingRoadJunctionService` 的「测试 2」里，其中 1 个是
`ArchitectureGuardTest` 的一行字符串常量（受管文件清单），也不是测试。

---

## 三、这如何改变了批 4 的定性

| 维度 | 我之前说的 | **修正后** |
|---|---|---|
| 代码是否活着 | ✅ 活着 | ✅ 活着（17 个生产引用） |
| 有没有新引擎 | ❌ 没有 | ❌ 没有，**而且现在也不该有** |
| 能不能安全换实现 | 未评估 | **❌ 不能** —— 1,228 行没有安全网 |
| 真正的阻塞 | 「需先定设计」 | **「需先建测试」** |

⇒ **批 4 的正确顺序是「先覆盖、后重构」，不是「先设计新引擎」。**
在没有测试的情况下重写合并逻辑，等于闭着眼睛改道路拓扑 ——
而合并逻辑出错的表现是「路接错/路上叠路/端点漂移」，**不会崩溃，只会难看**，
这类缺陷最难从日志里发现。

---

## 四、三个方案

### 方案 A：先补覆盖，再谈重构（**推荐**）
**做法**：给 `ExistingRoadJunctionService` 与 `ParallelOverlapResolver` 建测试。
优先覆盖这几个纯函数面（不需要 `ServerLevel`）：
- `alignJunctionProfile` —— 已有 9 个用例，可作为参照样板
- `MergeBatchResult` 的合并判定（`connectPlannedBatch(List<RoadData>)` 这个**不带 level** 的重载）
- `ParallelOverlapResolver.boxMayOverlap` / `resolveParallelOverlaps` —— 判断「并排了没」
- `RoadBraidDetector` 已有的 8 个用例可作为「怎么构造合成 RoadData」的样板

**能不能不碰生产代码**：**能**。上面这几个都是纯函数/纯判定，
`RoadBraidDetector` 的文件头自己写着「只读、无世界访问、可单测」。
**风险**：零（只加测试）。**需要真机**：不需要。
**代价**：要造 `RoadData` 合成夹具，工作量在夹具不在断言。

### 方案 B：引入路口图模型（`topology/JunctionGraph`）
**做法**：把四类统一到一个「路口图」中间表示上，让合并/去重/交叠判定都读它。
**问题**：这正是 S1「单一中间表示」的思路，但 **S1 至今没落地**（`RoadSpec` 未成为全链唯一事实）。
在没有 S1 的情况下先做路口图，会变成「第 N 套并行事实」，
与「病根 A：无唯一中间表示」正面冲突。
**风险**：高。**需要真机**：需要。**不建议现在做。**

### 方案 C：宣布批 4 完成（定义为就地重构 + 现有 44 个用例）
**做法**：把批 4 的完成标准改成「代码就地拆分 + 符号保真 + 现有测试」，
不要求「新引擎接管」。
**问题**：现有 44 个用例里，真正覆盖批 4 核心的约 12 个
（`CrossingNegotiatorTest` 4 + `CrossingSharedDeckTest` 4 + `RoadBraidDetectorTest`/
`BraidRunExtractionTest` 的一部分），而 **1,228 行的合并主逻辑等于没测**。
宣布完成会把这个洞写进文档当「已完成」，以后更难补。
**风险**：中（把债务合法化）。**不建议。**

---

## 五、建议

**选 A。** 理由：
1. **零风险**（只加测试，不改生产代码），符合「暂不做真机验证」的当前阶段；
2. 它是 B 的**前置条件** —— 没有测试就换实现，出了问题无法判断是新实现错还是本来如此；
3. 它能把「批 4 已完成」这件事变成**有依据的**，而不是靠符号计数。

**具体到下一轮可做的第一批**（不需要任何拍板）：
给 `ParallelOverlapResolver` 建 `ParallelOverlapResolverTest`，
覆盖 `boxMayOverlap` 与 `resolveParallelOverlaps` 的纯判定分支。
它的 386 行目前**完全没有安全网**，而且不依赖 `ServerLevel`。

---

## 六、这份文档**没有**做什么

- **没有**统计「行覆盖率」（没接 JaCoCo，只用方法名扫描，是**下界**估计 ——
  某个方法名没出现，也可能被间接调用到，但**这种可能性对纯函数类很小**）
- **没有**核 `ConnectingPlannedBatch` 的 `ServerLevel` 重载能否用 mock 覆盖
- **没有**核 `RoadData` 的构造复杂度（决定夹具成本）
- **没有**改任何代码
- **没有**解释「为什么当年没测」 —— 那段历史已不可考，只能记录现状

---

## 七、方法论：符号覆盖 ≠ 行为覆盖

我此前的「符号保真核查」（131/131 符号在、0 丢弃）得出的结论是**只关于名字**：
`connectPlannedBatch` 这个名字确实还在代码里。**但「名字在」和「被验证过」是两件事。**

⇒ **教训：`符号在` + `生产引用数 > 0` 只能证明「没丢」，不能证明「能用」。
要判断一个批次能不能换实现，必须额外看「它的行为有没有测试接住」。**
这三层是独立的：**存在 → 接线 → 被验证**。批 4 过了前两层，第三层大面积缺失。