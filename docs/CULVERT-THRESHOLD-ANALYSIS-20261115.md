# 涵洞阈值「口径冲突」的精确分析（2026-11-15，**已修正一次**）

> **一句话结论（修正后）**：这不是「某些路不处理」，也不是「无害的死常量」。
> 真实后果是 **规划期判成涵洞、施工期却架桥** —— `CULVERT_MAX_SEGMENTS = 2` 在
> `promoteRuntimeBridges` 里**真的生效**，它会让 3..6 站的窄浅水被提升成运行时桥。
> **修法应选 A（把施工期上限对齐到 6）**。

---

## 零、我上一版写错了什么（必读）

本文件的第一版结论是「施工期站数上限从未生效，实际无害」，并推荐「B 标注为死常量」。
**那是错的**，原因是我只查了 `prepareCulvertDeck`（它确实只传单站），
**漏了同一个判据还有第二个调用点 `promoteRuntimeBridges`，那里传的是完整站段**。

这件事本身就是一条教训：**同一个判据（`isShallowCulvertCrossing`）有多个调用点，
只核一个就下结论会得出相反答案。** 修正过程见第六节。

---

## 一、冲突是怎么被描述的

`docs/functional-inventory/03-跨度与桥隧.md` 第九节原文：

> **口径冲突**：施工期判据「≤2 站」，规划期按 `culvertMaxStations=6`
> ⇒ 3..6 站窄浅水会落 `CULVERT` 跨度、甲板写进 `targetY`，
> 但 `isShallowCulvertCrossing(start,end)` 只查单站不认（高度已在 `targetY`，实际不塌）。

描述里的「只查单站」指的是 `prepareCulvertDeck` 那条路径 —— **这部分是准确的**。
但它遗漏了另一条路径，而那一条才是真正产生后果的地方。

---

## 二、两条独立路径（这是关键）

`isShallowCulvertCrossing(world, centers, start, end)` 有两个调用点，参数完全不同：

### 路径 1：`prepareCulvertDeck`（施工铺路，传单站）

```
SegmentPaver.planGrade
  → prepareCulvertDeck(world, positions, heights, segmentIndex, centers)
      → isShallowCulvertCrossing(world, centers, segmentIndex, segmentIndex)   // start == end
```

`end - start + 1 = 1`，`1 > 2` 恒 false ⇒ 这一步的上限检查**确实不生效**。
（这一条我第一版看对了。）

### 路径 2：`promoteRuntimeBridges`（运行期桥提升，传完整站段）★

`RoadConstructionSafety:452-465`：

```java
if (!inWindow(processingChunk, centers.get(i)) || !hasWatercourseAcrossRoad(world, centers, i)) { i++; continue; }
int start = i;
while (i + 1 < centers.size() && inWindow(...) && hasWatercourseAcrossRoad(world, centers, i + 1)) i++;
int end = i;                                  // ← 整段连续水的范围
if (isShallowCulvertCrossing(world, centers, start, end)) {
    // 窄浅涵洞：保持开敞、不架桥、不填河床
    i++; continue;
}
int waterY = waterSurfaceY(...);
RuntimeRange stable = expandRuntimeBridgeRange(centers, start, end, waterY);
detectedNow.add(stable);                       // ← 提升为桥
```

**这里 `end - start + 1` 就是整段水的站数** ⇒ `3..6 > 2` ⇒ 返回 `false`
⇒ 走不到「保持开敞」分支 ⇒ **被提升为运行时桥**。

**所以 `CULVERT_MAX_SEGMENTS = 2` 不是死常量，它在这里真的决定了「架桥还是做涵洞」。**

---

## 三、真实后果链

```
规划期：3..6 站窄浅水
  → CulvertPlanner.cross 按 culvertMaxStations=6 判定为涵洞
  → deck = max(各站水面+2, 原路面) 写进 targetY
  → spans 里记 SpanType.CULVERT

施工期（每区块）：
  → PathFeature:411  promoteRuntimeBridges(...)
      → 该段 3..6 站 > CULVERT_MAX_SEGMENTS(2)
      → isShallowCulvertCrossing = false
      → isBridge[i] = true                    ← ★ 提升为桥

  → PathFeature:510  if (bridgeEnabled && isBridge[i]) → 桥体建造分支
  → 永远进不到 PathFeature:528 的 else 分支
  → 因此 prepareCulvertDeck 根本不会被调用
```

**结论：3..6 站的窄浅水最终会架桥，而不是按规划意图做涵洞。**

另外 `preserveCrossingGrade` 也帮不上：它由 `buildPreserveCrossingGradeMask` 从
`road.spans()` 里只取 `SpanType.BRIDGE` / `ROAD_CROSSING` 生成
（`PathFeature:685-694`），**CULVERT 不在其中**，运行期提升的桥也不在 spans 里。

**不会塌、不会穿村**（桥本身是更保守的做法），但代价是：
**多建了一座本不该建的桥** —— 材料浪费、视觉上不必要，而且规划期的甲板高度计算白做。

---

## 四、三个方案（修正后）

| 方案 | 做法 | 代价 | 结论 |
|---|---|---|---|
| **A 对齐到 6** ★ | `CULVERT_MAX_SEGMENTS` 2 → 6（或改为读 `culvertMaxStations`）| 改一个常量，1 行；让施工期与规划期同口径，3..6 站恢复成涵洞 | **推荐** —— 这是唯一「既修好冲突又不减少能力」的方案 |
| **B 标注为死常量** | 不动行为，只加注释 | 我上一版推荐的这个 —— **错了**，它不是死常量 | **撤回** |
| **C 规划期改 2** | `culvertMaxStations` 6 → 2 | 3..6 站规划期就标 BRIDGE，口径也一致 | 能修一致，但**减少了涵洞适用面**，违反「旧功能不能丢」 |

**为什么 A 最优**：`culvertMaxStations` 的默认值就是 6，说明旧版
**本来就打算支持到 6 站涵洞**；施工期那个 2 更像是早期只考虑了单站/双站时的遗留。
把施工期上限对齐到规划的 6，等于让施工跟上规划的设计意图。

**A 的一个前置**：`CULVERT_MAX_SEGMENTS` 改成 6 后，`promoteRuntimeBridges` 里
1..6 站的窄浅水都不会再架桥。要确认这是期望行为 —— 从规划期 `culvertMaxStations=6`
的语义看，是的。

---

## 四之二、★ 补做水深与净空的同口径排查（上一版留下的局限）

第五节原本写着「水深（≤2）与净空（≤6）两条没有做同样的多调用点排查」。
按第四节的教训（**要 grep 判据函数名，不是常量名**）补做了一遍，结果如下。

**三条判据的完整对照**：

| 判据 | 规划期（`CulvertPlanner`）| 施工期（`RoadConstructionSafety`）| 一致性 |
|---|---|---|---|
| **站数** | `culvertMaxStations = 6`（夹 0..16）| `CULVERT_MAX_SEGMENTS = 2` | ❌ **规划松、施工紧** |
| **水深** | `culvertMaxWaterDepth = 2`（夹 1..4）| `CULVERT_MAX_WATER_DEPTH = 2` | ✅ **一致** |
| **净空** | **无此判据** | `CULVERT_MAX_OVERHEAD = 6` | ❌ **规划缺、施工有** |

规划期判据（`CulvertPlanner.isCulvertStation`，`:151-157`）：

```java
return water.available() && water.water()
        && water.depth() <= maxWaterDepth
        && plannedY < water.surfaceY();
```

施工期判据（`RoadConstructionSafety.isCulvertGeometry`，`:808-812`）：

```java
return segmentCount > 0 && segmentCount <= CULVERT_MAX_SEGMENTS
        && waterDepth > 0 && waterDepth <= CULVERT_MAX_WATER_DEPTH
        && overhead >= 0 && overhead <= CULVERT_MAX_OVERHEAD;
```

### ★★ 关键结论：两条冲突的**修法方向相反**

- **站数**：施工期（2）比规划期（6）**严** ⇒ 应当**放宽施工期到 6**（方案 A）。
- **净空**：规划期**根本没有这条判据** ⇒ 应当**给规划期补上 `overhead <= 6`**，
  而不是放宽施工期。

**为什么方向相反**：站数那条是「施工期上限写小了」，净空这条是「规划期漏了一条安全判据」。
这两件事看起来都是「口径不一致」，但一个是「收紧过头」、一个是「检查缺失」，
**不能用同一个动作修**。

> 这也说明盘点原文那句「施工期判据 ≤2 站」只点了站数一条，
> 而**净空才是更隐蔽的那条** —— 它的不一致不会让规划判错，会让
> 「规划判了涵洞、施工发现净空不足」时同样被提升成桥。

### 水深这条为什么一致

两处都是 `2`，且规划期把配置夹在 `[1,4]`、施工期是硬编码常量 2。
**潜在问题**：如果用户把 `culvertMaxWaterDepth` 配成 3 或 4，规划期会认、施工期仍只认 2
⇒ **又会产生同样的「规划涵洞、施工架桥」**。这条是**第三个隐患**，且它是**配置驱动**的，
不是常量写死。

⇒ 修法 A 若要彻底，应让施工期三个上限**全部读配置**，而不是硬编码常量：

| 施工期常量 | 应改为读 |
|---|---|
| `CULVERT_MAX_SEGMENTS = 2` | `culvertMaxStations`（默认 6）|
| `CULVERT_MAX_WATER_DEPTH = 2` | `culvertMaxWaterDepth`（默认 2，可配 1..4）|
| `CULVERT_MAX_OVERHEAD = 6` | **规划期需要先有同名参数**，否则只能保持常量 |

---

## 五、局限（如实标注）

1. **只读代码，没有真机数据**。3..6 站窄浅水目前「架桥」的实际观感、
   改成涵洞后是否真的更自然，**都需要真机验证**。
2. `hasWatercourseAcrossRoad` / `waterOverhead` / `waterDepth` 都**读世界**，
   规划期用采样数据、施工期读当前方块，两者结果可能不同 ——
   即「规划判涵洞、施工判不是」还可能由采样差异引起，不只是站数上限。
3. ~~本文只覆盖「站数上限」这一条；水深（≤2）与净空（≤6）两条没有做同样的多调用点排查。~~
   **已在「四之二」补做**：水深两处一致（但配置可改 ⇒ 第三个隐患），
   净空规划期根本没有判据（应补规划期，不是放宽施工期）。
4. 本文所有结论仍是**只读代码**得出的，没有真机数据；三条判据各自的「正确值」是多少，
   需要真机观察 3..6 站窄浅水的实际表现才能定。

---

## 六、修正过程（为什么会有第一版那个错误）

第一版的分析路径：

1. 读到盘点说「施工期判据 ≤2 站」；
2. grep `CULVERT_MAX_SEGMENTS` → 只看到 `:97` 定义与 `:787`/`:810` 两处判断；
   （**★ 2026-11-15 行号已过期**：抽取 `CulvertJudge` 后，常量定义在
   `CulvertJudge.java` 的 `maxSegments()` 里，判定在 `CulvertJudge.maxSegments()` 与
   `RoadConstructionSafety.isShallowCulvertCrossing`（`stations > CulvertJudge.maxSegments()`）；
   **不要再按本文件里的 `:460`/`:97`/`:787`/`:810` 去找**，那些是抽取前的行号）；
3. 看 `prepareCulvertDeck` 的调用 → 传 `(segmentIndex, segmentIndex)` → 得出「上限恒不生效」；
4. **没有再搜 `isShallowCulvertCrossing` 的全部调用点**就下了结论。

漏掉的是：`grep CULVERT_MAX_SEGMENTS` 只能找到**常量**的引用，
而 `isShallowCulvertCrossing` 是**函数**，它的调用点不会出现在常量的 grep 结果里。
**正确做法是搜函数名**：

```
grep -rn "isShallowCulvertCrossing(" --include=*.java
```

⇒ 会同时看到 `prepareCulvertDeck`（单站）与 `promoteRuntimeBridges`（整段）两个调用点。

**教训（可复用）**：追一条判据的影响面时，**要 grep 判据函数名，不是常量名**。
常量只出现在判据内部，调用点全在函数外面。
