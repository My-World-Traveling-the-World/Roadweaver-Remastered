# 涵洞阈值「口径冲突」的精确分析（2026-11-15）

> **一句话结论**：这不是「两个阈值不一致导致某些路不处理」，
> 而是「**施工期的站数上限检查从未生效**」，而它**实际无害**。
> 三个可选方案在第三节，我倾向 B（诚实标注为死常量）或 A（对齐到 6）。

---

## 一、冲突是怎么被描述的

`docs/functional-inventory/03-跨度与桥隧.md` 第九节原文：

> **口径冲突**：施工期判据「≤2 站」，规划期按 `culvertMaxStations=6`
> ⇒ 3..6 站窄浅水会落 `CULVERT` 跨度、甲板写进 `targetY`，
> 但 `isShallowCulvertCrossing(start,end)` 只查单站不认（高度已在 `targetY`，实际不塌）。

这个描述是**准确的**，但后半句「只查单站」容易读成「所以 3..6 站没被处理」。

**实际不是**。下面把链路拆开。

---

## 二、链路拆解（每个环节都读过源码）

### 1. 规划期：`CulvertPlanner.cross`

- 触发很窄：只在 `FinalRoadWaterValidator` 报 `ordinary_construction_in_water` 时调用。
- 量的是**整段连续水**（不因中间深站截断）。
- 逐站 `isCulvertStation`（本站有水、水深 ≤ `culvertMaxWaterDepth=2`、原路面低于水面），
  **任一不合格整段放弃**。
- 段长上限：`culvertMaxStations = 6`（`RoadAppearanceConfig:30`，夹 1..16）。
- 产物：`deck = max(各站水面+2, 原路面)` 写进 `targetY`；两侧 `ramp` 每站 1 格回原剖面。

### 2. 施工期：`SegmentPaver.planGrade` → `prepareCulvertDeck`

```
positions = seg.positions()          // ★ 一个「站」的横截面方块列表
heights   = batchInterpolate(positions, segmentIndex, centers, targetY)
RoadConstructionSafety.prepareCulvertDeck(world, positions, heights, segmentIndex, centers);
```

`prepareCulvertDeck` 做两件事：

1. **门禁**：`isShallowCulvertCrossing(world, centers, segmentIndex, segmentIndex)`
   —— 注意两个实参**都是 `segmentIndex`**，即 `start == end`，查的是**单站**。
2. **压平**：`deck = max(该站各列 profile[i], 水面+2)`，然后
   `for (i) profile[i] = deck` —— 把**这个站的横截面**压平到 deck。

（`positions` 是横截面，所以 `profile[i] = deck` 是**横向压平**，不是纵向把整条路压平。
两者语义完全不同，这一点容易误读。）

### 3. 于是 `CULVERT_MAX_SEGMENTS = 2` 从未生效

`isShallowCulvertCrossing` 的设计**是支持多站的**（签名有 `start, end`，内部有
`if (end - start + 1 > CULVERT_MAX_SEGMENTS) return false;`）。

但因为 `prepareCulvertDeck` 永远传 `(segmentIndex, segmentIndex)`：

```
end - start + 1 = 1
1 > 2 ?          → false         ⇒ 这一步恒通过
```

⇒ **这个上限检查是死代码**。全仓 `CULVERT_MAX_SEGMENTS` 只有两处引用
（`:97` 定义、`:787` 与 `:810` 的判断），而 `:810` 那条走的是另一个函数
（`isCulvertGeometry`，由 `isShallowCulvertCrossing` 间接调用），同样拿不到多站范围。

### 4. 所以 3..6 站的窄浅水**会被逐站处理**，不是被忽略

每一站各自跑一遍 `prepareCulvertDeck`，各自的横截面被压平到
`max(该站 profile, 该站水面+2)`。

由于 `profile` 是从 `targetY` 插值来的、而规划期已经把统一的 `deck` 写进了 `targetY`，
所以施工期算出的 deck 与规划期一致 ⇒ **不会出现台阶或塌陷**。

---

## 三、三个方案

| 方案 | 做法 | 代价 | 我的看法 |
|---|---|---|---|
| **A 对齐到 6** | `CULVERT_MAX_SEGMENTS` 改 6，并让 `prepareCulvertDeck` 支持传入整段范围 | 要把 `SegmentPaver` 的逐站调用改成「段级」处理（需新建一次段扫描），改动中等；而且**当前无害**，改它不解决任何已知问题 | 可做，但收益是「让常量不再是死的」 |
| **B 诚实标注为死常量** | 不动行为，在 `CULVERT_MAX_SEGMENTS` 上加注释说明「因调用方只传单站，本上限从未生效」；并在盘点/STATUS 里登记 | 零风险、零行为变化 | **推荐** —— 与仓库既有的「死键诚实标注」约定一致（见 `BridgeKeys` / `PlanningKeys`） |
| **C 规划期改 2** | `culvertMaxStations` 6 → 2，与施工期「看起来」一致 | **会减少能力**：3..6 站的窄浅水将不再算涵洞，退化成填方或桥。违反「旧功能不能丢」 | 不推荐 |

**为什么我倾向 B 而不是 A**：这个上限从来没生效过，也就意味着**从来没有路因为「超过 2 站」
而不被处理**。改 A 是「让一个不影响行为的检查开始影响行为」——反而可能引入新问题
（3..6 站突然开始被拒绝）。除非有真机数据证明「3..6 站涵洞会出问题」，否则不该动。

---

## 四、这段分析本身的局限

1. **只读了代码，没有真机数据**。3..6 站涵洞在游戏里的实际观感（水道是否通畅、
   两侧 ramp 是否自然）**未经真机验证**，这一条必须如实标注。
2. `hasWatercourseAcrossRoad` / `waterOverhead` / `waterDepth` 都**读世界**，
   它们的判据无法离线复现，所以「每站各自判定」的实际结果可能与规划期不一致
   （规划期用的是采样数据，施工期读的是当前方块）。
3. 本文只覆盖「站数上限」这一条口径；水深（≤2）与净空（≤6）两条**没有做同样的追踪**。

---

## 五、给决策用的最短版本

- **不改也安全**：现状无害，`CULVERT_MAX_SEGMENTS` 是死常量而已。
- **想干净**：方案 B，加注释 + 登记，零风险。
- **别选 C**：会砍掉 3..6 站涵洞这个能力。
- **A 只在有真机证据说「多站涵洞有问题」时才做。**