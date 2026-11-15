# 静默降级「只修了 null，没修 EMPTY」（2026-11-15 核查）

> **这份文档报告一个具体缺陷**：2026-11-13 审计定的「病根 C：静默降级」只修了一半。
> `snapshot == null` 现在会告警了，但 **`snapshot == PlanningSnapshot.EMPTY` 仍然完全静默**，
> 而且**恰好把「保护区来源没生效」误报成「保护区没有贡献」**——
> 正是那段 javadoc 自己警告过的情况。

---

## 一、缺陷一：`shouldAvoidPlanning` 只认 `null`，不认 `EMPTY`

### 代码（`StructureAvoidanceCache:87-94`）
```java
public static boolean shouldAvoidPlanning(BlockPos pos) {
    PlanningSnapshot snapshot = ACTIVE_PLANNING_SNAPSHOT.get();
    if (snapshot == null) {
        noteBlindQuery();          // ← 只有 null 才记
        return false;
    }
    return snapshot.blocks(pos);   // ← EMPTY 在这里返回 false，且什么都不记
}
```

### `EMPTY` 从哪来（`StructureAvoidanceService.activatePlanningSnapshot:601-613`）
```java
PlanningSnapshot selected = level == null || connection == null
        ? PlanningSnapshot.EMPTY
        : StructureAvoidanceCache.PLANNING_SNAPSHOTS.getOrDefault(
                routeKey(level, connection), PlanningSnapshot.EMPTY);   // ← 兜底是 EMPTY
...
StructureAvoidanceCache.ACTIVE_PLANNING_SNAPSHOT.set(selected);         // ← 设进去的是 EMPTY
```

`PLANNING_SNAPSHOTS` 只在 `SnapshotOrchestrator:411` 写入。
⇒ **若某条路线在快照准备好之前就进入规划作用域，拿到的就是 `EMPTY`。**
此时 `shouldAvoidPlanning` 一路 `return false`，**不告警、不计数、不留任何痕迹**。

### 后果
「**路线快照没准备好**」与「**这条路附近真的没有建筑**」在日志与计数上**完全同形**。
这正是 2026-11-13 审计里病根 C 的原话，只是入口从 `null` 换成了 `EMPTY`。

---

## 二、缺陷二（更严重）：`planningSnapshotPresent()` 把 `EMPTY` 判成「可用」

### 它的 javadoc 明确要求区分两件事（`StructureAvoidanceCache:38-47`）
> 静默返回 `false` —— 与「这个位置确实没有结构」完全同形。
> 桥隧观测需要区分这两件事：前者是**没有证据**，后者是**有证据说明没有**。
> 混为一谈会把「保护区来源没生效」误读成「保护区没有贡献」。
>
> `@return` `true` 表示本线程处于规划快照作用域内，**查询结果可信**

### 但实现是
```java
public static boolean planningSnapshotPresent() {
    return ACTIVE_PLANNING_SNAPSHOT.get() != null;   // ← EMPTY 也返回 true
}
```

⇒ **`EMPTY` 时它返回 `true`，也就是宣称「查询结果可信」，而实际上什么都没挡。**

### 影响链（实测调用点）
```
GroundBanSources.safeSnapshotPresent()       ← 读它当 structureSnapshotAvailable
  → GroundBanSources.Result.structureSnapshotAvailable
    → ModeDpBridge:343 / :362  打日志
         (bans.structureSnapshotAvailable()) ? "ok" : "absent"
```

⇒ **批次 2 的跨度 AB 观测会把这些情况写成 `snapshot=ok`**，
而实际上是「保护区来源本次没生效」。
**批 2 的所有 AB 结论（「DP 判出的桥不是复述旧判定」）都建立在这个字段上。**

---

## 三、为什么这算真缺陷而不是「可接受的降级」

| 判据 | 结论 |
|---|---|
| 会造成崩溃吗 | 不会 —— 返回 false 是 fail-open，世界照常生成 |
| 会静默改变行为吗 | **会** —— 该挡的建筑不挡，路穿过去 |
| 能被发现吗 | `null` 路径能（WARN + 计数）；**`EMPTY` 路径不能** |
| 会污染观测结论吗 | **会** —— `snapshot=ok` 是假的 |

⇒ 与「病根 C」的定义完全吻合：**降级本身可以有，但不能与「正常无结构」同形。**

---

## 四、建议的修法（最小、两处）

### 修 A：让 `EMPTY` 也算「无证据」
```java
private static boolean isUsable(PlanningSnapshot s) {
    return s != null && s != PlanningSnapshot.EMPTY;
}
// shouldAvoidPlanning(pos) / shouldAvoidPlanning(pos, clearance)：
PlanningSnapshot snapshot = ACTIVE_PLANNING_SNAPSHOT.get();
if (!isUsable(snapshot)) { noteBlindQuery(); return false; }
```
- 行为**不变**（仍然返回 false，仍是 fail-open）
- 唯一变化：**开始计数并告警**
- 另外把 `blindQueryCount()` 接到一个真出口（目前它**零调用者**）

### 修 B：让 `planningSnapshotPresent()` 返回真实可用性
```java
public static boolean planningSnapshotPresent() {
    return isUsable(ACTIVE_PLANNING_SNAPSHOT.get());
}
```
- 行为变化：`EMPTY` 时 `structureSnapshotAvailable` 变成 `false`
- ⇒ `ModeDpBridge` 的 AB 日志从 `snapshot=ok` 改成 `snapshot=absent`（**变正确**）
- ⇒ **批 2 的 AB 结论需要重新解读**：以前把「来源没生效」算成了「来源无贡献」

### 风险
- 修 A **零行为变化**（只加观测）
- 修 B **会改变 AB 日志的含义**，但那是纠正错误含义，不是改变生产决策
- 两者都**不改世界生成结果**（`shouldAvoidPlanning` 的返回值不变）
- 需要真机看 `blindQueryCount` / `snapshot=absent` 出现多少 —— 但那只是读数，不是验证

---

## 四之二、★ 缺陷扩散到 7 处（本轮已逐个核实）

`null` vs `EMPTY` 的混淆不是孤例，**同一个模式出现在 7 个地方**：

| # | 位置 | 判断 | `EMPTY` 时的后果 |
|---|---|---|---|
| 1 | `StructureAvoidanceCache.shouldAvoidPlanning(pos)` | `snapshot == null` | 返回 false，**不记盲查** |
| 2 | `StructureAvoidanceCache.shouldAvoidPlanning(pos, clearance)` | 同上 | 同上 |
| 3 | `StructureAvoidanceCache.planningSnapshotPresent()` | `!= null` | **返回 true（谎称查询可信）** |
| 4 | `ObstacleJudge.blocksVillageCluster`（`:138`）| `snapshot == null` | 村庄群不挡，静默 |
| 5 | `ObstacleJudge.describePlanningCollision(pos)`（`:197`）| `snapshot == null` | 返回 `describeCollision` 的结果（空的），与「确实没撞」同形 |
| 6 | `ObstacleJudge.describePlanningCollision(from,to)`（`:203`）| 同上 | 同上 |
| 7 | `ObstacleJudge.planningCollisionBox`（`:209`）| `snapshot == null` | 返回 null，与「确实没撞」同形 |

**注意 #3 是唯一的「反向」错误**：前 6 处是「该报警时没报」，#3 是「该说不可用时说了可用」——
后者更危险，因为它直接污染 `ModeDpBridge` 的 AB 观测。

⇒ 修法建议里的 `isUsable(s) = s != null && s != EMPTY` 应当**这 7 处统一替换**，
并给每处写负控（构造一个 `EMPTY` 快照作用域，断言观测/日志能区分出来）。

---

## 五、这份核查**没有**做什么

- **没有改任何代码**（按用户规矩，生产代码改动需先拍板）。
- **没有**统计线上 `EMPTY` 的真实发生率（需要真机日志，目前 `blindQueryCount()` 还没接线）。
- **没有**核 `ObstacleJudge` 的 4 处 `ACTIVE_PLANNING_SNAPSHOT.get()`（`:138/197/203/209`）
  是否也有同样的 `null` vs `EMPTY` 混淆 —— **从调用形态看很可能有**，下一轮该核。
- **没有**核 `WorldgenAvoidance:245` 的 `remove()` 路径（那是运行期避让，不是规划期）。

---

## 六、这条缺陷是怎么被发现的（方法论）

不是在审查代码时"看"出来的，而是**从 2026-11-13 审计的病根 C 出发**，
逐个核对「当时的修法覆盖了哪些分支」：
- 病根 C 原文：「取不到 ThreadLocal 快照时返回 `PlanningSnapshot.EMPTY` ⇒ 全链静默什么都不挡」
- 当时的修法：在 `snapshot == null` 时加 `noteBlindQuery()`
- **但原文说的是 `EMPTY`，修的是 `null`** —— 修的不是同一个分支

⇒ **教训：修「静默降级」时必须把该状态的**所有**表现形式（null / EMPTY / 布尔标志 / 空集合）
逐个覆盖，并给每种写一个负控。只修最常见那一种，剩下的会以更隐蔽的形态留下。**