# 批 3「桥体与施工」状态更正（2026-11-15）

> **这份文档更正我此前对批 3 的判断。**
>
> 我在第 47/48 轮写过：「批 3 新包 `pathlogic/construction` 只有铺装，
> 桥隧建造仍跑旧包 4,887 行 ⇒ 未接管」。
>
> **这句话是错的。** 桥段建造**已经有新实现且在主位**，只是它不在
> `pathlogic/construction` 里，而在 `pathlogic/bridge/BridgeSegmentPlannerNew.java`。

---

## 一、真实的两级结构（`PathFeature:514-528` 原文逻辑）

```java
if (bridgeEnabled && isBridge[i]) {
    var line = bridgeSegment.getLine(i);
    int actualDeckY = baseYForThis;
    boolean placedTemplate = line != null && BridgeSegmentPlannerNew.processSegment(
            world, currentChunk, data, line, seg, middle, prev, actualDeckY,
            bridgeSegment.isLanding(i));          // ← ① 主路径：内置桥模板
    if (!placedTemplate) {
        if (!centerOwned) { continue; }
        boolean placedFallback = BridgeSegmentPlanner.processSegment(...);  // ← ② 兜底：程序化
        if (!placedFallback) {
            completed[0] = false;
            LOGGER.warn("RoadWeaver bridge segment construction failed ...");
        }
    }
}
```

⇒ **桥段是「模板优先 + 程序化兜底」的两级**，不是「只有旧实现」。

---

## 二、`BridgeSegmentPlannerNew` 是什么

| 项 | 值 |
|---|---|
| 行数 | **545** |
| 文件头职责 | 「沿长跨水段铺设内置桥模板 —— 越界的模板坐标不向下投影，只写当前区块自己的方块」 |
| 数据来源 | `BridgeTemplateStructureRegistry`（`structures/registry/`）|
| 关键调用 | `BridgeTemplateStructureRegistry.chooseOriginal(level, stableBridgeSeed(line))` |
| 何时返回 false（走兜底） | **仅当 `chooseOriginal` 返回 null**（即没有可用模板）|
| 是否受配置门控 | **否**（没有 `enabled` 开关，不读 `ConfigService` 决定走不走）|

### 模板可用性（决定真实覆盖率）
`chooseOriginal` 的白名单是**两个固定模板**：
```java
if (path.equals("bridge_bridge_0") || path.equals("bridge_bridge_1")) { originals.add(template); }
```
这两个**确实存在**：
```
data/roadweaver/worldgen/structure/bridge_bridge_0.json
data/roadweaver/worldgen/structure/bridge_bridge_1.json
```
（`data/roadweaver/structure/bridge/*.nbt` 那 8 个是**程序化路径用**的体素模板，不是这里。）

### ★ 一个真实限制：模板路径只在主世界生效
`getAll(level)` 的开头：
```java
if (level == null || !Level.OVERWORLD.equals(level.dimension())) return List.of();
```
⇒ **非主世界（下界/末地）`chooseOriginal` 恒返回 null ⇒ 所有桥段走程序化兜底。**
这是设计还是遗漏，代码里**没有说明**。本类不判定，只记录事实。

---

## 三、隧道侧：**没有**新实现

`pathlogic/tunnel/` 下 8 个文件：
`PlannedSection` / `PlannedSectionBuilder` / `PortalSafetyRules` / `RoadTunnelBuilder` /
`ScenicDomeBuilder` / `SpanState` / `TunnelClearLedger` / `TunnelPlan`。

在这些文件里搜 `TemplateStructureRegistry` / `Template`：**零命中**。
`RoadTunnelBuilder` 的文件头是「按已持久化的 TUNNEL span 保守开挖隧道」——**纯程序化**。

⇒ **批 3 是「桥新、隧旧」的不对称状态。**

---

## 四、更正后的批 3 状态

| 子项 | 之前我说的 | **实际** |
|---|---|---|
| 桥段建造 | ❌ 未接管，跑旧包 | ✅ **模板新实现（545 行）在主位**，程序化旧版仅兜底 |
| 隧道建造 | ❌ 未接管 | ❌ **确实未接管**（无模板实现，纯程序化）|
| `pathlogic/construction` 的定位 | 「只有铺装」 | ✅ 大体对 —— 那 1,440 行是**施工侧体积规划**，不是建成块 |
| 旧包 4,887 行是否仍在必经之路 | 「仍在全跑」 | ⚠️ **桥段只在模板失败时跑**；隧道段**全跑** |

### 一个需要澄清的计数问题
「批 3 旧包 4,887 行」里的 4,887 = `bridge` 2,831 + `tunnel` 2,056。
但 `bridge` 那 2,831 行里**包含了新实现** `BridgeSegmentPlannerNew`（545 行）
和一堆与新实现共用的协作类（`BridgeMaterialPalette` / `BridgePierPolicy` /
`BridgeRangeCalculator` / `BridgeShareService` 等）。
**所以不能把这 2,831 行整体算成「旧实现」。**

⇒ 更准确的拆分（按「是否服务于旧程序化路径」分）**本轮没有逐行做完**，
只确认了 `BridgeSegmentPlanner`（程序化铺块器）是真正的「旧路径独占」部分。

---

## 五、这对批 3 的完成度意味着什么

**批 3 不是「一点没做」，而是「做了一半且做的那半没被记录」。**

| 判据 | 桥 | 隧 |
|---|---|---|
| 有新实现 | ✅ `BridgeSegmentPlannerNew` 545 行 | ❌ |
| 新实现在生产主位 | ✅ `PathFeature:515` 先调它 | — |
| **有测试** | ❌ **零测试**（见下一节）| 待查 |
| 真机验证 | ❌ | ❌ |

### ★ 补一个更要紧的发现：新实现零测试

实测（`common/src/test` 全文搜类名）：

| 类 | 行数 | 测试文件数 |
|---|---|---|
| **`BridgeSegmentPlannerNew`** | **545** | **0** |
| `BridgeMaterialPalette` | — | **0** |
| `BridgePierPolicy` | — | **0** |
| `BridgeDeckProfile` | — | 5 |
| `BridgeTemplateStructureRegistry` | — | 1（且那 1 个是 `BridgeDeckYConsistencyTest` —— **我自己前几轮写的**，只验 `heightOffset + 2` 的口径，不验放置逻辑）|

⇒ **批 3 的「新实现」是生产主路径上 545 行零测试的代码。**
它决定了真机上每一座桥长什么样，而**没有任何自动化验证能发现它改坏了**。
这比「新实现不存在」更危险 —— 不存在的东西不会出错，存在但没验证的会。

⇒ **总纲「批 3 换实现只做了一半」这个定性是对的，但「哪一半」记错了**：
不是「一点没接管」，而是「**桥接管了、隧没接管**」。

---

## 六、这份核查**没有**做什么

- **没有**核 `BridgeSegmentPlannerNew` **能不能离线测**（它吃 `WorldGenLevel` +
  `ChunkPos`，可能需要 mock 或提取纯函数；这是下一步该做的判断）
- **没有**把 `bridge` 包 2,831 行按「新路径独占 / 旧路径独占 / 共用」三类分开计数
- **没有**核模板桥与程序化桥在真机上的观感差异（需真机）
- **没有**解释 `getAll` 为什么限制只在主世界（代码无注释）
- **没有**改任何代码

---

## 七、方法论：为什么我连着两次把批 3 判错

第 47/48 轮我按包名找「新实现」：`pathlogic/construction` 是**我知道的新包**，
于是「新包只有铺装 ⇒ 桥隧建造没接管」。
**漏掉了：新实现可以住在旧包里**（`pathlogic/bridge/BridgeSegmentPlannerNew`）。

这和第 46 轮「按文件名 grep 判死代码」、第 58 轮「`head -8` 截断当全量」是**同一族错误**：

> **用一个不完整的检索面去找东西，找不到就宣布「不存在」。**

⇒ **判「某功能有没有新实现」必须按「行为」找（谁在主位被调用），
不能按「包名/文件名」找。** 主位调用点（`PathFeature:515` vs `:522` 的先后）
才是唯一可信的判据。