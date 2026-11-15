# 批 2 具名项验证：「解除 groundBanned 循环论证」= 已完成（2026-11-15）

> **这份文档给目标里的一个具名项下结论**：
> 「批 2 桥隧判定（含**解除 groundBanned 循环论证**、ModeDpSolver 生产回写）」。
>
> **结论：「解除循环论证」这一半已经完成，可三路验证。**
> 但顺带查出**两处 javadoc 陈旧**（说「已有公路来源没接」，实际已接），
> 以及一个**与我第 52 轮那笔发现的直接耦合**（EMPTY 缺陷会让这条修复的验收日志说谎）。

---

## 一、原来那个循环论证是什么

`ModeDpBridge` 的类注释（`:32-36`）自己记着：

```
旧 spans ──► isCrossing ──► groundBanned ──► DP 只能在这些站选桥/隧道
     └────────────────────────────────────► 再拿 DP 结果与旧 spans 比对
```

即：`groundBanned`（禁地面掩码）**直接复制 `isCrossing`**，而 `isCrossing`
是调用方从**旧判定产出的 spans** 里推出来的。

⇒ DP 被告知「旧判定认为这里是桥的地方不能铺地面」，再统计它「覆盖了多少旧判定的桥」——
**数字必然偏高，不是独立验证**。这是标准的循环论证。

---

## 二、三路验证：现在真的解除了

### 验证 1：掩码来源与旧判定完全无关
`GroundBanSources`（新类）构造掩码只用**物理事实**：
- 水面（`WaterProbe`：地形场水深是否达架桥阈值）
- 保护区/结构占地（`StructureProbe`：已验证结构体与施工带相交）
- 已有公路（`RoadProbe`，见第三节）

在 `GroundBanSources.java` 里搜 `isCrossing` / `spans` / `SpanType` / `RoadSpan`：
**代码中零命中，只有 javadoc 讲历史时提到 `isCrossing`。**

### 验证 2：`isCrossing` 已降级为对照量
`ModeDpBridge` 里 `isCrossing` 的全部用法：
| 位置 | 用途 |
|---|---|
| `:291` / `:300` | 参数声明，注释写明「**只作对照**，不再参与禁地面掩码」|
| `:319-321` | 只在 `legacyCrossing++` 计数里用（写进 AB 日志）|

`:312` 的注释也写明「不再复制 `isCrossing`。两臂共用同一份掩码 ⇒ **口径可比**」。

### 验证 3：生产入口确实用新来源
`TargetYComputation:605` 是生产调用点：
```java
GroundBanSources.Result groundBans = GroundBanSources.collect(
        terrain, existingRoad, centers, n, Math.max(1, bridgeMinWaterDepth));
```
同一处的注释也写着「禁地面来源已改为「水面 + 保护区」两条独立物理事实，
`isCrossing` 只作对照 —— 这样 DP 判出的桥才不是「复述旧判定」」。

### 结论
**批 2 的「解除 groundBanned 循环论证」= ✅ 完成。** 这条可以记进总纲。

---

## 三、★ 顺带查出：两处 javadoc 陈旧（说「没接」，实际已接）

`GroundBanSources` **实现了「已有公路」来源**：
- `RoadProbe` 接口（`:78`）
- `collect(...)` 四个重载里有带 `RoadProbe` 的（`:119` / `:168` / 带 terrain 的在 `:186`/`:199`）
- 生产调用点 `TargetYComputation:600-605` 真的传了
  `ExistingRoadProbe.forCorridor(level, centers, ownerA2d, ownerB2d)`
- `GroundBanSourcesTest` 有 3 个专门用例：
  `已有公路来源能独立置位并与其它来源分别统计` /
  `已有公路探针为null时贡献恒为零` / `三个来源重叠时总置位数不重复计算`
- `ModeDpBridge:342/361` 的 AB 日志已经在打 `roadStations()`

**但有两处注释还停在旧状态**：

| 位置 | 原文 | 实际 |
|---|---|---|
| `GroundBanSources` 类 javadoc（约 `:33-42`）| 「**刻意没接的来源（如实标注，不是遗漏）**：已有公路 …… 属批 4 的范围」 | **已接**，有接口/重载/生产调用/3 个用例 |
| `ModeDpBridge:67` | 「「已有公路」这条来源**还没接**……属批 4」 | **已接** |

⇒ 这是典型的「注释落后于代码」。**它不是功能缺失，是会误导下一个读代码的人**（
读到这里会以为要补这块，实际已经有了）。

**修法是纯注释改动（零行为变化）**，但它在 `common/src/main`（生产树），
按用户铁律「生产代码改动必须先拍板」，本轮**没有改**，只记录。

---

## 四、★ 与第 52 轮发现的直接耦合：这条修复的验收日志会说谎

`ModeDpBridge:69-71` 与 `GroundBanSources` 都说：

> 保护区快照若不在作用域内会静默返回 false，
> 日志用 **`snapshot=ok/absent`** 如实区分「没有结构」与「没有证据」。

**但我第 52 轮已实证**：`StructureAvoidanceCache.planningSnapshotPresent()` 的实现是
`ACTIVE_PLANNING_SNAPSHOT.get() != null`，而 `PlanningSnapshot.EMPTY` **非 null**
⇒ **快照不存在时它返回 `true`，日志写成 `snapshot=ok`，而实际什么都没挡。**

⇒ **顺序上很关键**：
1. 循环论证 → 解除需要「独立来源」；
2. 独立来源里「保护区」这一条**是否真的生效**，只能靠 `snapshot=ok/absent` 判断；
3. 而这个判断**当前是错的**（EMPTY 被当成 ok）。

**所以「解除循环论证」这件事本身做完了，但它「是否在真机上真的成立」目前无法从日志确认** ——
不是因为没做，而是因为那行日志不可信。

⇒ **这给第 52 轮那笔 EMPTY 缺陷加了一条更硬的理由**：它不是「可观测性不完整」，
而是**直接污染批 2 已完成项的验收证据**。

---

## 四之二、★ 还有第二条「旧判定 → 新内核」的数据依赖：剖面 DP 的 crossing

总纲批 2 那行末尾留了一句「`ProfileDpSource` 的 crossing 输入仍来自旧判定」。
本轮**逐行核实，成立**：

```
TargetYComputation:126-146
    boolean[] isCrossing = new boolean[n];           // 新数组
    for (RoadSpan sp : spans) {                       // ← spans 是【旧判定】的产物
        if (sp.type() != BRIDGE && sp.type() != ROAD_CROSSING) continue;
        ... isCrossing[k] = true;                     // 按旧 spans 的区间置位
    }

TargetYComputation:756-757
    for (int i = 0; i < n; i++) {
        if (!isCrossing[i]) continue;                 // ← deckFloor 只对旧判定说「是桥」的站采样
        deckFloor[i] = RoadConstructionSafety.bridgeDeckSurfaceY(...);
    }

TargetYComputation:777
    var solved = ProfileDpSource.solve(terrainY, isCrossing, ...);   // ← 同一个数组交给剖面 DP
```

**这意味着什么**：
- **ModeDp（桥隧判定）的循环论证确实解除了** —— 它的掩码独立、`isCrossing` 只作对照；
- **但剖面 DP（决定 `targetY`）仍然被旧 spans 牵着**：
  同样是「旧判定说这里是桥」→「剖面 DP 把这段钉成直线并套上桥面下界」。
  `deckFloor` 更是**只对旧判定的桥段采样**，新判出的桥段拿不到水位下界。

**所以两条循环要分开看**：

| 循环 | 状态 |
|---|---|
| 桥隧判定：`groundBanned ← isCrossing ← 旧 spans`，然后拿结果与旧 spans 比一致率 | ✅ **已解除**（掩码改独立物理来源，`isCrossing` 降级为对照量）|
| 剖面形状：`crossing ← 旧 spans` → `ProfileDpSource.solve` / `deckFloor` | ❌ **仍在**（本次核实成立，与总纲记载一致）|

**为什么不建议现在就去掉第二条**：`isCrossing` 在剖面侧的作用是「这段是桥，剖面要钉直且不得低于水位」——
这是**约束注入**，不是「验证基准」。要在没有旧 spans 的前提下表达同一约束，
需要剖面 DP 自己先知道「哪段是桥」——也就是要等 ModeDp 的结果成为剖面 DP 的输入（依赖顺序反转）。
那一步属于「批 2 真正接管」的一部分，**必须先有真机召回率数据**才敢动。

⇒ **结论：「解除 groundBanned 循环论证」按目标原文是**特指 `groundBanned` 那条**，已完成；
但「桥隧判定彻底不再依赖旧判定」还没完成 —— 第二条路径在剖面侧，属后续阶段。**

---

## 五、批 2 剩余的真实缺口（全部如实标注）

| 项 | 状态 | 阻塞 |
|---|---|---|
| 解除 groundBanned 循环论证 | ✅ **完成**（本文明三路验证） | — |
| `ModeDpSolver` 生产回写 | 🟡 代码就绪，`-Droadweaver.spanWriteback=on` 默认**关** | 真机召回率 |
| 召回率 100%（S7 第 2 级通过标准） | ❌ 未测 | **必须真机**（合成地形没有真实河宽/保护区/历史约束）|
| `snapshot=ok/absent` 可信 | ❌ **当前不可信**（EMPTY 缺陷） | 需拍板修 A/B |
| 两处 javadoc 陈旧 | ⚠️ 已记录 | 纯注释，需拍板 |
| 「已有公路」来源 | ✅ **已实现并接线**（注释陈旧） | — |

---

## 六、这份核查**没有**做什么

- **没有**核 `ExistingRoadProbe.forCorridor` 的**排除自身路口**逻辑是否真的能避免
  「把整条走廊判成已有公路」（`TargetYComputation:595-598` 的注释说有这个风险并声称已处理）
- **没有**核「水面」来源与 `CulvertJudge` / 桥隧阈值是否同口径
- **没有**真机跑 `[SPAN-AB]` 拿召回率
- **没有**改任何代码（包括那两处明显陈旧的 javadoc）
- 行号会随改动漂移，引用前请重新 grep

---

## 七、方法论：这次是按「目标里的具名项」去核，而不是按「我上次查到哪」

之前几轮我一直在追自己上一轮的线索（批 5 → 批 6 → 批 4），
**从未回到目标原文去逐个点验具名项**。这次这么做了，立刻发现：
- 一个具名项（解除循环论证）**其实早就完成了**，我却一直没给过它结论；
- 顺带发现两处注释陈旧。

⇒ **教训：审计要定期回到「需求原文」逐项点验，不能只沿着自己的线索链走。
线索链会越走越细，但可能整条都偏离了「目标里到底列了哪几件事」。**