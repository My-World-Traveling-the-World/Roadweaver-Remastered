# E 组：隧道构建与路口合并 —— 方法体逻辑 + 历史修复继承性核查

- oracle（旧）：`roadweaver` @ `2c6547a`（实测 HEAD `2c6547a683f6219a5b7c9c4d9f871a77cf3751e0`，工作树干净）
- 重构（新）：`roadweaver-next` @ `7bc6023`（实测 HEAD `7bc60233feb4119e1796be562a7136da6b91ed43`，工作树干净）
- 核查范围：`pathlogic/tunnel/*`（旧 2 文件 → 新 6 文件）、`pathlogic/junction/*`（旧 3 文件 → 新 4 文件）、`pathlogic/construction/SegmentPaver.java`、`decoration/system/{AboveColumnClearer,RoadBlockPlacer,SurfacePlacementUtil}.java`、`pathlogic/bridge/{BridgeShareService,BridgeSegmentPlannerNew,BridgeBuilder,BuoyBuilder}.java`、`pathlogic/PathFeature.java`（仅隧道/铺装入口）、`pathfinding/TunnelEnvelopeCriterion.java`、`pathlogic/metrics/RoadQualityMetrics.java`、`postprocess/{RoadSnapService→JunctionConnector,JunctionTrimOps}.java`（仅被搬走的 4 个方法）、`spec/RoadSpec.java`（新增）
- 全程只读：未改动任何 `.java`、未编译、未跑 gradle、未做任何写 git 操作（对比用 `diff -u` / `diff -q` + 自写脚本）

---

## 结论摘要

- **逻辑等价 34 项 / 逻辑改变 0 项 / 无法判定 0 项**（方法级）；另有 5 项「接口面 / 清洁度 / 新增自检」变更，不构成生成行为改变，单列在「其它逻辑改变」。
- 方法级证据：旧 3 个文件共 **147 个可抽取方法体**（脚本按大括号配对抽取、去注释、空白规范化，按「方法名 + 方法体」比对），其中 **141 个在重构版找到同名同体**；**6 个不同体**，逐个人工核对后全部只差「修饰符 / 可见性 / 被搬走的静态调用改名」，**没有一条语句变化**。
- 类级证据（拆分账目闭合，一行不差）：
  - 旧 `RoadTunnelBuilder` 88 方法 = 新 `RoadTunnelBuilder` 53 + `PlannedSectionBuilder` 11 + `PortalSafetyRules` 17 + `ScenicDomeBuilder` 5 + `SpanState.java` 2；
  - 旧 `ExistingRoadJunctionService` 44 方法 = 新同名类 38 + `ParallelOverlapResolver` 6；
  - 新仓这几个文件里**没有一个「旧版不存在的新方法」**（反向比对 0 命中）。
- **5 个历史修复的继承状态：全部已继承，0 项退回 bug。**

| # | 历史修复 | 状态 | 关键行号（旧 ↔ 新） |
|---|---|---|---|
| ① | 隧道洞身禁止向上清空 | **已继承（逐字一致）** | `AboveColumnClearer:223-226,242-254` ↔ `:228-231,247-259`；`SegmentPaver:293-299` ↔ `:294-301`；`RoadBlockPlacer:88-95` ↔ `:89-96` |
| ② | 陆侧桥口甲板重试 + `plannedBridge` 镜像 | **已继承（整文件只多 5 行文件头注释）** | `BridgeSegmentPlannerNew:110-132` ↔ `:115-137`；`plannedBridge:363-372` ↔ `:368-379` |
| ③ | 单列保护三态判据 | **已继承（优先级正确；窄路径未误用 `placeOnSurface`）** | `SegmentPaver:49-71,287-309` ↔ `:51-73,289-311` |
| ④ | 路口合并后跨度端点对齐 | **已继承（整文件只多 1 行文件头注释）** | `BridgeShareService:55,176-187,258-274` ↔ `:56,177-188,259-274` |
| ⑤ | 洞口段长按坡度推 | **已继承（逐字一致）** | `RoadTunnelBuilder:557,650-688,692-707` ↔ `:566,659-697,701-716` |

- **最高风险：本组没有任何「修复退回 bug」。唯一需要盯的结构性隐患是「洞身不向上清空」靠的是调用点纪律而不是类型隔离 —— 无参重载 `clearAboveColumn(world,pos,cfg)` 仍然会无条件 `flattenBumpsAboveSurface`（旧 `AboveColumnClearer:67-82` / 新 `:72-87`），隧道路径今天确实不经过它（全仓调用点已核，见修复①），但任何人把它接进洞身就会重演「隧道没顶」。旧版与新版这一点完全相同。**

---

## ★ 历史修复退回 bug 的项（最严重，置顶）

**无。** 本节按「旧修复后代码（贴）→ 新版代码（贴）→ 会重现什么现象」的格式逐条给出**正面证据**；五条全部显示新版与修复后代码一致。

### ① 隧道洞身禁止向上清空 —— 已继承

**修复机制**：把「可清范围」抽成 `ClearExtent(topDy, clearsAboveBore)`；洞身段 `tunnelBody=true` ⇒ `clearsAboveBore=false`，并加运行时断言。

旧 `roadweaver/.../decoration/system/AboveColumnClearer.java:223-226`：

```java
public static ClearExtent clearExtent(boolean tunnelBody, int requestedHeight) {
    int height = Math.max(1, Math.min(16, requestedHeight));
    return new ClearExtent(height - 1, !tunnelBody);
}
```

旧 `AboveColumnClearer.java:242-254`（洞身专用入口：只清净空，不削凸起、不清落方块顶）：

```java
public static int clearTunnelBoreHeadroom(WorldGenLevel world, BlockPos surfacePos,
                                          ModConfig cfg, RoadData road, int requestedHeight) {
    if (world == null || surfacePos == null) return 0;
    if (...GreatWallRoadGuard...blocksFor(...Ch.CLEAR...)) return 0;
    ClearExtent extent = clearExtent(true, requestedHeight);
    // 契约守卫：洞身段永远不许向上清空。这里显式断言，改坏了立刻炸在本地而不是在山体里。
    if (extent.clearsAboveBore()) {
        throw new IllegalStateException("tunnel bore clear must never clear above the bore");
    }
    return clearNetCells(world, surfacePos, extent);
}
```

新 `roadweaver-next/.../decoration/system/AboveColumnClearer.java:228-231` 与 `:247-259`：**逐字相同**（`clearExtent` 223→228，`clearTunnelBoreHeadroom` 242→247，`IllegalStateException` 守卫 250→255，`clearNetCells` 257→262）。

**洞身段怎么走到这里（两版一致）**：`tunnelBody = tunnelBodyStation(grade.preserveTunnelGrade(), openCutStation)`，而 `openCutStation` 的实参是 `tunnel.portal()`。

旧 `pathlogic/PathFeature.java:575-580`：

```java
SegmentPaver.SegmentGrade grade = SegmentPaver.planGrade(
        world, seg, i, middlePositions, baseYArr, tunnel.active(),
        preserveCrossingGrade[i]);
boolean paved = SegmentPaver.paveSegment(world, data, seg, i, middlePositions, baseYArr, grade,
        roadType, materials, slabMaterials, random, cfg, refusalSink.stations(),
        tunnel.portal());
```

新 `pathlogic/PathFeature.java:580-585`：**逐字相同**（整文件 `diff` 该处只有 +5 行偏移）。

旧 `SegmentPaver.java:287-299` ↔ 新 `SegmentPaver.java:289-301`（窄路径同样按 `tunnelBody` 分档）：

```java
if (columnAction == ColumnAction.PAVE_SURFACE_ONLY) {
    ...
    if (tunnelBody) {
        // 0.9.065：洞身段只清洞身净空 —— 上方岩体就是顶板，不能往上削。
        AboveColumnClearer.clearTunnelBoreHeadroom(world, pos, cfg, road, clearHeight);   // 旧294 / 新296
    } else {
        AboveColumnClearer.clearAboveColumn(world, pos, cfg, road, clearHeight);         // 旧296 / 新298
    }
    noteClear(tunnelScope, tunnelBody);
    world.setBlock(pos, baseMats.get(random.nextInt(baseMats.size())), 3);               // 旧299 / 新301
```

旧 `decoration/system/RoadBlockPlacer.java:88-95` ↔ 新 `RoadBlockPlacer.java:89-96`：**逐字相同**，`flattenBumps=true` 被限制在 `else`（明挖段 / 普通路段）：

```java
if (tunnelBody) {
    AboveColumnClearer.clearTunnelBoreHeadroom(world, surfacePos, cfg, road, clearHeight);
} else {
    // 0.9.005-C：地表铺路要求凸起削平（台地断头路），显式传 true。
    AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg, road, clearHeight,
            stabilizeFallingCap, true);
}
```

**关于「`AboveColumnClearer` 里那条『隧道不走这里』的注释是错的」这个已知可疑点**：该注释旧在 `:79-80`、新在 `:84-85`，一字未改保留，它说的是**无参 3 参重载**（旧 `:67-82` / 新 `:72-87`，末尾第 81 / 86 行无条件调 `flattenBumpsAboveSurface`）。我用全仓调用点核实，这个断言在**本快照**上成立：

| 重载 | 旧调用点 | 新调用点 | 谁在用 |
|---|---|---|---|
| `clearAboveColumn(world,pos,cfg)`（含削凸起） | `RoadBlockPlacer:34`、`SurfacePlacementUtil:27`、`BuoyBuilder:37` | `RoadBlockPlacer:35`、`SurfacePlacementUtil:28`、`BuoyBuilder:37` | 仅经 `SurfacePlacementUtil.placeOnSurface(6 参)` ← `DecorationPlanner:53/54` 的**装饰系统** + 浮标 |
| `clearAboveColumn(...,flattenBumps)` | `RoadBlockPlacer:93`、`BridgeBuilder:130` | `RoadBlockPlacer:94`、`BridgeBuilder:135` | 铺装（`true`）/ 桥面（显式 `false`） |

结论：**隧道路径（`SegmentPaver` → `RoadBlockPlacer` 10 参 → `clearTunnelBoreHeadroom`）不经过那个会削凸起的重载，两版一致**。风险只在于它靠「调用点不这么接」保证，而不是靠类型隔离（见「最高风险」）。

**会重现什么现象**（若被改回去）：`flattenBumpsAboveSurface` 会把「高于路面」的地形整段削到与路面齐平；洞身站上方每一格岩体都是隧道顶板，被削到地表后 `RoadTunnelBuilder` 量到「覆土 < 1」放弃封顶 —— 就是注释原话写的「隧道没顶」（旧 `AboveColumnClearer:232-237` / 新 `:237-242`）。

---

### ② 陆侧桥口缺甲板：桥身映射重试 + `plannedBridge` 镜像 —— 已继承

**新旧整文件只差 5 行文件头注释**（`diff -u` = `+5 -0`；540 行 → 545 行）。

修法第一处（施工侧），旧 `pathlogic/bridge/BridgeSegmentPlannerNew.java:102-132`：

```java
// 0.9.033（O-14「陆侧桥口缺甲板」）：模板的**桥台段**在甲板高度上是空的
// …水上桥口靠「把列映射压回桥身」绕开了这一点，陆侧桥口没有 …
// 修法：陆侧落桥站第一次拿不到甲板时，改用水上桥口那套桥身映射再走一遍；
// 若仍拿不到，逐字节退回第一次的结果（行为与 0.9.032 完全一致）。
if (!blocked[0] && landingStation && !clampToBody && !visit.handles(landingStation)) {   // 旧110 / 新115
    List<TemplatePlacement> firstAttempt = List.copyOf(placements);
    placements.clear();
    blocked[0] = false;
    TemplateVisit forced = visitTemplateBlocks(                                                // 旧114 / 新119
            bridge, line, segment, deckY, minX, maxX, minZ, maxZ, true, collect);
    if (!blocked[0] && forced.handles(landingStation)) {
        visit = forced;
        long filled = LANDING_MOUTHS_FILLED.incrementAndGet();
        ...
    } else {
        placements.clear();
        placements.addAll(firstAttempt);      // ← 逐字节退回        旧127 / 新132
        blocked[0] = false;
    }
}

if (blocked[0] || !visit.handles(landingStation)) return false;        // 旧132 / 新137
```

新 `BridgeSegmentPlannerNew.java:107-137`：**逐字相同**。

修法第二处（`plannedBridge` 镜像；丢了会「双铺桥面」），旧 `BridgeSegmentPlannerNew.java:363-372`：

```java
// 0.9.033：与 processSegment 保持**逐条一致**的重试，否则存档期校验算出的
// centerChunkHandles 会和真正生成的体素对不上（退回程序化桥面会双铺）。
if (landingStation && !clampToBody && !visit.handles(landingStation)) {       // 旧363 / 新368
    List<BlockPos> firstAttempt = List.copyOf(blocks);
    blocks.clear();
    TemplateVisit forced = visitTemplateBlocks(                              // 旧366 / 新371
            bridge, line, segment, deckY, minX, maxX, minZ, maxZ, true, collectAll);
    if (forced.handles(landingStation)) {
        visit = forced;
    } else {
        blocks.clear();
        blocks.addAll(firstAttempt);                                         // 旧372 / 新377
    }
}
if (!visit.handles(landingStation)) return PlannedBridge.none();             // 旧375 / 新380
```

> 两处接受条件本来就**有意不对称**：施工侧判 `!blocked[0] && forced.handles(...)`，`plannedBridge` 的 collector 恒返回 `true`（`collectAll`）没有 `blocked` 概念，只判 `forced.handles(...)`。**这个不对称旧版就是这样**（旧 `:110` vs 旧 `:363`），不是重构引入。

**会重现什么现象**：删掉第一处 ⇒ 陆侧桥台段在 `deckY` 上空 ⇒ `handles(landingStation)=false` ⇒ `processSegment` 返回 false ⇒ `PathFeature` 在「本 chunk 不拥有该站」时 `continue` ⇒ 那一站在邻接 chunk 里完全没有甲板 = **1 站宽的桥口缺口**。只删第二处 ⇒ 存档期校验算出的 `centerChunkHandles` 与真实体素不一致 ⇒ **退回程序化桥面 = 双铺**。

---

### ③ 单列保护三态判据（含「窄路径不得走 placeOnSurface」硬约束）—— 已继承

判据本体，旧 `SegmentPaver.java:49-71` 与 新 `SegmentPaver.java:51-73`：`ColumnAction` 三态 + 优先级**逐字相同**：

```java
public enum ColumnAction {
    SKIP,               // 路面格本身受保护：跳过
    PAVE_SURFACE_ONLY,  // 只有路基受保护：铺路面，但绝不能重写 pos.below()
    PAVE_NORMAL
}

static ColumnAction classifyProtectedColumn(boolean surfaceProtected, boolean roadbedProtected) {
    if (surfaceProtected) return ColumnAction.SKIP;        // ← 优先级：路面格 > 路基（旧68-71 / 新70-73）
    if (roadbedProtected) return ColumnAction.PAVE_SURFACE_ONLY;
    return ColumnAction.PAVE_NORMAL;
}
```

**硬约束核实：窄路径没有误用 `placeOnSurface`。** 旧 `SegmentPaver.java:287-309` ↔ 新 `:289-311`：

```java
if (columnAction == ColumnAction.PAVE_SURFACE_ONLY) {      // 旧287 / 新289
    // 0.9.033：受保护的是 pos.below()，所以这一列**绝不能**走 placeOnSurface ——
    // RoadBlockPlacer.placeRoadBlock 会无条件 setBlock(pos.below(), chosen) …
    if (tunnelBody) { … clearTunnelBoreHeadroom(…) } else { … clearAboveColumn(…) }
    noteClear(tunnelScope, tunnelBody);
    world.setBlock(pos, baseMats.get(...), 3);              // 旧299 / 新301 —— 只写 pos
    … PAVED_OVER_PROTECTED_ROADBED 计数 + INFO 日志 …
} else {
    SurfacePlacementUtil.placeOnSurface(world, pos, baseMats, roadType, random, cfg, road,
            clearHeight, !grade.preserveTunnelGrade(), tunnelBody);   // 旧309 / 新311 —— 只有 PAVE_NORMAL 走这里
    noteClear(tunnelScope, tunnelBody);
}
```

「不能走 placeOnSurface」的理由在代码里仍然成立：旧 `RoadBlockPlacer.java:87` / 新 `:88` 都是 `world.setBlock(below1, chosen, 3);`（`below1 = surfacePos.below()`），且这一句在 `tunnelBody` 判断**之前**、无条件执行（旧 `:87`、新 `:88`）。

**回归测试**：旧新 `ProtectedColumnActionTest.java` **逐字节相同**（74 行，`diff -q` 无输出），钉的正是优先级与「只有窄路径允许绕开 `placeOnSurface`」：

```java
assertEquals(SegmentPaver.ColumnAction.SKIP, classify(true, true));                // :37
assertEquals(SegmentPaver.ColumnAction.PAVE_SURFACE_ONLY, classify(false, true));  // :48  ← O-14 修复点
assertEquals(SegmentPaver.ColumnAction.PAVE_NORMAL, classify(false, false));       // :53
// 行为契约：只有 PAVE_SURFACE_ONLY 允许绕开 placeOnSurface。                    // :57-73
```

**会重现什么现象**：判据退回两态 ⇒ 路面下只要有一个箱子/告示牌（`isProtectedPreparedBlock` 覆盖 `hasBlockEntity`），那一列路面被整格跳过、左右邻列照铺 ⇒ **驾驶面 1×1 的坑**。若把 `PAVE_SURFACE_ONLY` 接到 `placeOnSurface` ⇒ 受保护方块被路面替换，**静默、不可回滚、质量指标不报警**（测试类注释 `:19-26` 原话）。

---

### ④ 路口合并后跨度端点对齐（`stationIndexNearXz` + `STATION_XZ_FALLBACK_MAX_DY`）—— 已继承

**新旧 `BridgeShareService.java` 整文件只差 1 行文件头注释**（`diff -u` = `+1 -0`；275 行 → 276 行），所以常量与兜底函数逐字一致。

旧 `BridgeShareService.java:55` / 新 `:56`：

```java
private static final int STATION_XZ_FALLBACK_MAX_DY = 4;
```

旧 `BridgeShareService.java:258-274` / 新 `:259-274`：

```java
/**
 * 0.9.036 收紧兜底：**同一 XZ 上恰好只有一个站**，且 {@code |ΔY| ≤ STATION_XZ_FALLBACK_MAX_DY}
 * 才认；同 XZ 出现多个站视为歧义，直接返回 -1（宁可丢，不可错配）。
 */
static int stationIndexNearXz(List<RoadSegmentPlacement> stations, BlockPos target) {
    if (stations == null || target == null) return -1;
    int hit = -1;
    int hitDy = Integer.MAX_VALUE;
    for (int i = 0; i < stations.size(); i++) {
        ...
        if (pos.getX() != target.getX() || pos.getZ() != target.getZ()) continue;
        if (hit >= 0) return -1;                      // 旧268 / 新269 —— 同 XZ 多站 ⇒ 歧义
        hit = i;
        hitDy = Math.abs(pos.getY() - target.getY());
    }
    if (hit < 0 || hitDy > STATION_XZ_FALLBACK_MAX_DY) return -1;   // 旧272 / 新273
    return hit;
}
```

调用侧顺序未变（先精确 `stationIndexOf`，失败才兜底），旧 `:176-187` ↔ 新 `:177-188`：

```java
int from = stationIndexOf(stations, span.start());
int to = stationIndexOf(stations, span.end());
boolean recovered = false;
if (from < 0) { from = stationIndexNearXz(stations, span.start()); recovered |= from >= 0; }
if (to < 0)   { to   = stationIndexNearXz(stations, span.end());   recovered |= to >= 0; }
```

**「宁可丢不可错配」在新版仍然成立**：`if (hit >= 0) return -1;`（新 `:269`）与 `hitDy > 4 ⇒ -1`（新 `:273`）两条都在。

**触发链也在**：合并（`selectSingleNetworkJunction` → `refineMergedCandidate` 重建站序列）**之后**才调 `BridgeShareService.applyInPlace` —— 旧 `ExistingRoadJunctionService.java:152-154` ↔ 新 `:162-164`：

```java
BridgeShareService.Stats bridgeShare =
        BridgeShareService.applyInPlace(current,
                net.shiroha233.roadweaver.config.ConfigService.get().bridgeShareRadius());
```

**回归测试**：旧新 `BridgeShareEndpointFallbackTest.java` **逐字节相同**（85 行），钉住四种情形：`:42-45` 3 格漂移认回、`:49-51` 5 格拒绝、`:55-61` 同 XZ 两站 ⇒ -1、`:65-67` 无 XZ 命中 ⇒ -1。

**会重现什么现象**：`RoadSpan` 只存 `BlockPos`，`mergeRoads` 重建站序列后 Y 漂一格 ⇒ `collectBridges` 反查失败 ⇒ **整座桥被静默丢掉**，表现为 `bridgeShareCandidates=0`（`spec/package-info.java:17-20` 记了这条历史）。若放宽成「同 XZ 取最近」⇒ 错配桥段归属。

---

### ⑤ 洞口段长按坡度推 —— 已继承

旧 `RoadTunnelBuilder.java:557` / 新 `:566` 都保留同一行分区注释：

```java
// ── 0.9.064：洞口段长改按坡度推（本版唯一一处行为改动）────────────────────
```

旧 `RoadTunnelBuilder.java:650-688` ↔ 新 `:659-697`：**逐字相同**。核心公式：

```java
double risePerStation = RoadConstants.DEFAULT_SOFT_GRADE_LIMIT * step;                 // 旧682 / 新691
if (!(risePerStation > 0.0D)) {
    return new PortalAdvance(PORTAL_MAX_SEGMENTS, null);
}
int needed = (int) Math.ceil(depth / risePerStation);                                  // 旧686 / 新695
int segments = Math.max(PORTAL_MIN_SEGMENTS, Math.min(PORTAL_MAX_SEGMENTS, needed));   // 旧687 / 新696
return new PortalAdvance(segments, null);        // reason=null ⇒ 日志打 "slope"
```

配套常量值全部未变（只去 `private` 以便跨类访问）：`PORTAL_TRANSITION_SEGMENTS=14`（旧 `:87` / 新 `:96`）、`PORTAL_MAX_SEGMENTS=32`（旧 `:95` / 新 `:104`）、`PORTAL_MIN_SEGMENTS=1`（旧 `:97` / 新 `:106`）、`STATION_XZ_FALLBACK_TOLERANCE=4`（旧 `:110` / 新 `:119`）。

「判出来是隧道、挖出来也是隧道」两端都在：

- 判定侧：`portalSplit`（旧 `:692` / 新 `:701`）算 `bodyStart/bodyEnd/openCutOnly`，`openCutOnly = bodyEnd - bodyStart + 1 <= 0`（旧 `:703` / 新 `:712`）；`resolvePortalSplit`（旧 `:710` / 新 `:719`）用 `terrainOf`（旧 `:731` / 新 `:740`）+ `mouthRoad`（旧 `:736` / 新 `:745`）+ **实测** `stationStep`（旧 `:746` / 新 `:755`）；`announcePortalSplit`（旧 `:765` / 新 `:774`）对 `openCutOnly` 打 `WARN`。
- 施工侧：`SpanState.preparePortal`（旧 `:1496` / 新 `SpanState.java:76`）每 span 只算一次并缓存；`isSpanMouthSegment`（旧 `:544` / 新 `:553`）按 `portalSplit` 判明挖段。
- 规划侧口径：`pathfinding/TunnelEnvelopeCriterion.java` **旧新字节级相同**（`diff -u` 无输出；`requiredRampStations` 在两边都是 `:464` / `:477`），与 `portalAdvance` javadoc 声明「完全同口径」（旧 `:641` / 新 `:650`）一致。

**回归测试**：`TunnelPortalAdvanceTest.java` 只差 2 行类引用（`RoadTunnelBuilder.plannedSection` → `PlannedSectionBuilder.plannedSection`），**断言零改动**。

**会重现什么现象**：退回固定 14 站 ⇒ 洞口埋得深时引道过短，判定说「隧道」而施工端整段明挖（`openCutOnly=true` 那一档）—— 判出来是隧道、挖出来是明挖。

---

## 逐项对照

> 行号＝**声明行**（实测），冒号后为方法名，多行用逗号分隔。判定用词：**等价**＝方法体规范化后相同；**移动**＝整段搬到新类、方法体相同；**修饰符**＝只有可见性/记录字段修饰符变化；**改名**＝只换被调静态方法的类名。

| # | 逻辑点 | 旧版位置 | 新版位置 | 判定 | 依据 |
|---|---|---|---|---|---|
| 1 | 净空几何 `clearHeight` / `roofOffset` / `minOverburden` | `RoadTunnelBuilder:55,61,64` | `:64,70,73` | 等价 | 三方法均单行 return，名字+体命中 |
| 2 | `processSegment`（3 个重载/委托） | `:136,138,141` | `:145,147,150` | 等价 | 方法体相同 |
| 3 | `prepareSpanState` | `:161,166` | `:170,175` | 等价 | 方法体相同 |
| 4 | `preflight`（隧道/明挖/路口/观光分派） | `:180` | `:189` | 等价 | 方法体相同（约 110 行） |
| 5 | `sectionSafe`（覆土 / 流体 / T-22 分级净空） | `:293`（`dy>=3 continue` 在 `:325`） | `:302`（`:334`） | 等价 | 方法体相同 |
| 6 | `apply`（挖洞身 + 砌壳 + 灯 + 日志） | `:363` | `:372` | 等价 | 方法体相同 |
| 7 | `isPortalSection` / `isTunnelEndpointPortal` / `isSpanMouthSegment` | `:528,532,544` | `:537,541,553` | 等价 | 方法体相同；`isPortalSection` 仍是恒 `false` 兼容空壳 |
| 8 | `PortalAdvance` / `PortalSplit` 记录（含 `logLine`/`openCutOnlyLine`） | `:579,591` | `:588,600` | 等价 | 组件与两个 `logValue()` 逐字相同 |
| 9 | `portalAdvance`（**修复 ⑤**） | `:650` | `:659` | 等价 | 见上文贴码 |
| 10 | `portalSplit` / `resolvePortalSplit` / `terrainOf` / `mouthRoad` / `stationStep` / `announcePortalSplit` | `:692,710,731,736,746,765` | `:701,719,740,745,755,774` | 等价 | 6 个方法全同名同体 |
| 11 | `requiresOpenPortal` | `:786` | `PortalSafetyRules:65` | 移动 | 整段搬出，体相同 |
| 12 | `sealBore` / `isWaterCell` / `isWallOwned` / `isWaterAboveRoof` | `:819,861,866,880` | `PortalSafetyRules:98,140,145,159` | 移动 | 4 个体相同 |
| 13 | `shouldOpenPortalTransition`(2 个) / `isContinuityOnlyFailure` / `hasNearbyPortalSection` | `:893,899,907,915` | `PortalSafetyRules:172,178,186,194` | 移动 | 体相同，含 `PORTAL_TRANSITION_SEGMENTS` 窗口 |
| 14 | `portalSafe` / `junctionSafe` / `roadbedSafe` / `isProtectedTunnelBlock` / `isOwnTunnelLantern` / `isOwnLegacyPortalWall` / `clearLegacyPortalWalls` / `isOwnedTunnelLantern` | `:927,964,1001,1016,1024,1030,1043,1060` | `PortalSafetyRules:206,243,280,295,303,309,322,339` | 移动 | 8 个体相同（含 `checkAboveColumnExact(..., true, predicate)` 白名单） |
| 15 | `columnAvailableWithoutLoading` / `plannedSection` / `lateral` / `tunnelScope` / `findSpan` / `isInSpan`(2) / `indexOf` / `xzKey` / `unique` / `add` / `spanState` | `:1257,1262,1308,1325,1332,1345,1367,1399,1403,1409,1413` | `PlannedSectionBuilder:60,65,111,128,135,148,170,202,206,212,216` | 移动 | 11 个体相同；`indexOf` 的 `STATION_XZ_FALLBACK_TOLERANCE` 兜底在 `PlannedSectionBuilder:187` |
| 16 | `containsWater` / `scenicSpanLength` / `scenicProgress` / `scenicGlassState` / `placeScenicDome` + `nearestInterior` | `:1159,1170,1183,1197,1201,1239` | `ScenicDomeBuilder:59,70,83,97,101`；`RoadTunnelBuilder:928` | 移动 | 6 个体相同 |
| 17 | `SpanState` 类 + `Decision` 枚举 + `SpanKey` 记录（含 `preparePortal`/`prepareLanterns`） | `:1483,1485,1487,1496,1503` | `SpanState:65,67,76,83`；`SpanKey` 在 `RoadTunnelBuilder:1001` | 移动（修饰符） | 字段与两个方法体相同；`private` 字段去 `private` |
| 18 | `TunnelPlan` 记录（`NONE`/`BLOCKED`/`PROTECTED_GAP`/`shell()`） | `:1440` | `:958` | 修饰符 | 三个缓存常量 `private static final`→`static final`，其余逐字相同 |
| 19 | `SafetyCheck` 记录 | `:1467` | `:985` | 修饰符 | `private record`→`record`，`success/failure` 体相同 |
| 20 | `clearForLevel` / `clearAll` | `:1428,1434` | `:946,952` | 等价 | 体相同（含 `TunnelClearLedger.reset()`） |
| 21 | `TunnelClearLedger` 全部 13 个方法 | `TunnelClearLedger:1-133` | `TunnelClearLedger:1-135` | 等价 | 文件只多 2 行（文件头 + 一条 static import），13/13 命中 |
| 22 | 「判定→施工」闸门 `openCutStation = tunnel.portal()` | `PathFeature:575-580` | `PathFeature:580-585` | 等价 | 见修复①贴码 |
| 23 | `connectPlannedBatchDetailed`（合并主流程 + BridgeShare + braid + 精修 + 提交） | `ExistingRoadJunctionService:100` | `:110` | 改名 | 唯一差异 `:236`→`:246`（`lowerPrimaryTunnelGradeAtJunction` 换类） |
| 24 | `selectSingleNetworkJunction`（单路口约会） | `:677` | `:376` | 改名+修饰符 | `:687/:698` `networkReuseStationCount`→`JunctionTrimOps`；`:691` `connectSecondaryToPrimary`→`JunctionConnector` |
| 25 | `refineMergedCandidate` | `:567` | `ParallelOverlapResolver:315` | 移动+改名+修饰符 | `:622`→`:370` `createSecondaryReuseAtCorridor`；`:630`→`:378` 异常类换包 |
| 26 | `connectToExistingNetwork`（2 参） | `:640` | `:339` | 等价 | 体命中 |
| 27 | `connectToExistingNetwork`（3 参） | `:649` | `:348` | 改名 | 唯一差异 `:661`→`:360`（同一个方法换类） |
| 28 | `resolveParallelOverlaps`（并排预检 + 改道主循环） | `:328` | `ParallelOverlapResolver:76` | 移动 | 116 行方法体相同 |
| 29 | `boxMayOverlap` / `rerouteAvoiding` / `connectionOf` / `elapsedMs` | `:444,467,552,563` | `ParallelOverlapResolver:192,215,300,311` | 移动 | 4 个体相同 |
| 30 | 其余 26 个路口工具方法（`loadNearbyPersisted`…`xzKey`） | `:718…1140` | `:417…839` | 修饰符 | 26 个体全部命中，仅 `private`→package-private |
| 31 | `BridgeShareService` 整文件（**修复 ④**） | `:1-275` | `:1-276` | 等价 | `diff -u` 只有 `+1` 行文件头注释 |
| 32 | `BridgeSegmentPlannerNew` 整文件（**修复 ②**） | `:1-540` | `:1-545` | 等价 | `diff -u` 只有 `+5` 行文件头注释 |
| 33 | `SegmentPaver` 整文件（**修复 ③**） | `:1-423` | `:1-425` | 等价（+删一个无人调用的 getter） | `diff -u` 共 47 行：imports 2 行、注释扩写、`RoadTunnelBuilder.tunnelScope`→`PlannedSectionBuilder.tunnelScope`、`StructureAvoidanceService.isVerifiedPreparedStructureBlock`→static import |
| 34 | `RoadBlockPlacer` / `SurfacePlacementUtil` / `BuoyBuilder` / `BridgeBuilder` / `RoadQualityMetrics` | 各 1 文件 | 各 1 文件 | 等价 | 全部只多 1~5 行文件头注释（`BridgeBuilder` 唯一变化就是文件头） |
| 35 | `AboveColumnClearer` 整文件（**修复 ①** 载体） | `:1-612` | `:1-617` | 等价 | 只多 5 行文件头注释；`clearExtent`/`clearTunnelBoreHeadroom`/`clearNetCells` 逐字相同 |
| 36 | `CrossingNegotiator` | 304 行 | 304 行 | 等价 | `diff -q` 无输出（字节相同） |
| 37 | `TunnelEnvelopeCriterion`（**修复 ⑤** 规划侧口径） | `pathfinding/…` | 同名 | 等价 | `diff -u` 无输出（字节相同） |
| 38 | `RoadBraidDetector` | `:1-348` | `:1-350` | 等价 | 只多 2 行 **未使用** import，方法体未变 |
| 39 | 被搬走的 4 个 `RoadSnapService` 方法 | `RoadSnapService:332,517,667,908` | `JunctionConnector:71,256,406`；`JunctionTrimOps:413`；`RoadSnapService:434` | 移动 | 4 个方法体**逐字相同** |
| 40 | 拆分新增的方法 | — | — | **0 个** | 反向比对：新 `pathlogic/tunnel/*` + `pathlogic/junction/*` 无「旧版不存在的新方法」 |

---

## ★ 其它逻辑改变

（下列 5 条**不改变生成行为**，但都属于「新版与旧版不完全一样」的事实，逐条留痕。）

### 1. 删掉一个 public getter：`SegmentPaver.pavedOverProtectedRoadbed()`

- 旧 `SegmentPaver.java:43-46`：

```java
/** {@return how many road-surface cells this process filled over a protected roadbed} */
public static long pavedOverProtectedRoadbed() {
    return PAVED_OVER_PROTECTED_ROADBED.get();
}
```

- 新 `SegmentPaver.java:41-47`：方法已删，注释改成「读它的 getter … 全仓无人调用，已删。计数器本身保留 —— 它还在给日志做节流」。
- 影响：**0**。我在旧仓 main + test 全量 grep `pavedOverProtectedRoadbed`，**除定义本身外零调用点**，所以删除不断链。计数器 `PAVED_OVER_PROTECTED_ROADBED` 保留，仍用于 `paved <= 8 || paved % 64 == 0` 日志节流（旧 `:301` / 新 `:303`）。
- 唯一后果：**对外 API 面减少 1 个方法**（仓外若有反射调用会失配；仓内无）。

### 2. `RoadBraidDetector` 多了 2 个未使用的 import

- 新 `RoadBraidDetector.java:16-17`：

```java
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;
import net.shiroha233.roadweaver.postprocess.EndpointConnectOps;
```

- 旧版无。全文件 grep 这两个符号只有 import 两行，**方法体里一次都没用**。
- 影响：**0**（未使用 import 不进字节码，只有编译告警）。属清洁度遗留。

### 3. 新增 `spec/RoadSpec`（旧仓无 `spec` 包），是修复 ④ 的「根因版」补充

- 旧仓 `roadweaver/.../spec/` **不存在**；新仓有 `spec/RoadSpec.java`（221 行）+ `spec/package-info.java`（40 行），由 `pathlogic/core/Road.java:641` 调 `logSpecIntegrity(...)`、`Road.java:795-800` 里 `RoadSpec.from(rd)` 做完成期自检。
- **值得记一笔的口径差异**：`BridgeShareService.stationIndexNearXz` 是「同 XZ 唯一 **且** |ΔY| ≤ 4」，而新 `RoadSpec.resolveStation`（`RoadSpec.java:160-185`）是「同 XZ 唯一」但**没有 ΔY 上限**（`:169-185`）。用途不同（前者决定桥段归属；后者只做自检，`Road.java:800` catch 后只 warn），**不构成修复 ④ 的退化**，但同一语义存在两套阈值。
- `spec/package-info.java:30-32` 还声明「`classifyProtectedColumn` 是静态纯函数、有 `ProtectedColumnActionTest` 盯着」—— 与实测一致（见修复 ③）。

### 4. 静态调用改名 + 异常类换包（纯搬移的连带）

| 旧 | 新 | 方法体 |
|---|---|---|
| `RoadSnapService.lowerPrimaryTunnelGradeAtJunction`（旧 `RoadSnapService.java:667-775`） | `JunctionConnector.lowerPrimaryTunnelGradeAtJunction`（`:406-514`） | **逐字相同** |
| `RoadSnapService.connectSecondaryToPrimary`（旧 `:332-361`） | `JunctionConnector.connectSecondaryToPrimary`（`:71-100`） | **逐字相同** |
| `RoadSnapService.createSecondaryReuseAtCorridor`（旧 `:517-596`） | `JunctionConnector.createSecondaryReuseAtCorridor`（`:256-335`） | **逐字相同** |
| `RoadSnapService.networkReuseStationCount`（旧 `:1329`） | `JunctionTrimOps.networkReuseStationCount`（`:413`） | 方法体相同 |
| `StructureAvoidanceService.PlanningSnapshotUnavailableException` | `pathlogic/structures/PlanningSnapshotUnavailableException` | 类型搬迁 |

调用点对应：旧 `:236`→新 `:246`、旧 `:661`→新 `:360`、旧 `:687`→新 `:386`、旧 `:691`→新 `:390`、旧 `:698`→新 `:397`、旧 `:622`→`ParallelOverlapResolver:370`、旧 `:630`→`ParallelOverlapResolver:378`。

### 5. 可见性 `private` → package-private（+ 跨类 static import）

- 隧道侧 **41 行**声明去掉 `private`（字段/常量 + 24 个方法；例：旧 `RoadTunnelBuilder.java:166,180,293,528,532,544,736,746` 的 `private static` → 新 `:175,189,302,537,541,553,745,755` 的 `static`），配新文件头 `import static ...PlannedSectionBuilder.* / PortalSafetyRules.* / ScenicDomeBuilder.*`。
- 路口侧 **35 行**声明去掉 `private`：`LOGGER`、`MAX_JUNCTION_CONSTRUCTION_OVERHEAD`、`JUNCTION_REFINE_TIMEOUT_MS`、`BRAID_*` 四常量、`JunctionSelection`/`MergeWork`/`MergeResult`/`BraidPrecheck` 四个记录、22 个工具方法。
- 影响：**0 行为变化**（Java 可见性不改语义），是拆分的必要代价。

### 6. 补充事实：同一「span 端点 → 站索引」语义在本仓有 4 套实现，其中 1 套是新版新增

这条不影响任何修复的继承判定，但按任务要求（搜全仓 `stationIndexOf` / `stationIndexNearXz`）必须交代清楚：

| 实现 | 旧位置 | 新位置 | 规则 | 继承性 |
|---|---|---|---|---|
| `BridgeShareService.stationIndexOf` | `BridgeShareService:245` | `:246` | 纯 `BlockPos.equals` 精确匹配 | 逐字相同 |
| `BridgeShareService.stationIndexNearXz`（**修复 ④**） | `:258` | `:259` | 同 XZ 且**全序列唯一** + `|ΔY| ≤ 4`，多站即放弃 | 逐字相同 |
| `PlannedSectionBuilder.indexOf`（隧道 span 端点，0.9.080） | `RoadTunnelBuilder:1367` | `PlannedSectionBuilder:170` | 先同 XZ 精确命中；失配则取**最近站**（Chebyshev XZ ≤ 4），**不要求唯一** | 逐字相同 |
| `RoadSpec.resolveStation`（完成期自检） | — | `spec/RoadSpec.java:160` | 精确 → 同 XZ **唯一**，多站**抛异常**；**无 ΔY 上限** | **新增** |

- 隧道那套（`indexOf`）与桥共享那套（`stationIndexNearXz`）**判据本来就不同**：前者「取最近」，后者「必须唯一，有歧义就放弃」。这是旧版既有差异（`indexOf` 旧 `:1367` 与新 `:170` 方法体逐字相同），**不是重构引入**，也不属修复 ④ 的退化。
- 但两套并存意味着：同一条路的同一个端点，在「隧道是否开挖」与「桥是否共享」两条链上可能得到不同结论（一松一紧）。旧版就是这样，本次核查不判定为缺陷，只记录事实。

---

## 无法判定的地方

如实列出，不猜：

1. **「方法体规范化后相同」≠「运行时二进制行为相同」。** 我比的是源码文本（去注释、去空白）。以下差异不在取证范围，且按要求未编译、未跑 gradle：
   - 跨类搬迁后 `static import` 解析与**类初始化顺序**变化（`RoadTunnelBuilder.SPAN_STATES` 仍是 `WeakHashMap` 静态字段，但 `SpanState`/`Decision` 从内部类升为顶层类）；
   - `private record` → `record` 会改变生成的构造器/访问器修饰符，理论上影响反射调用者（仓内 grep 无反射调用者，但未做字节码验证）；
   - 内部类升顶层（`SpanKey`/`Decision`）会新增常量池引用，只能靠编译验证。
2. **「修复前的 bug 形态」无法从仓内 git 历史取证。** 旧仓 `2c6547a` 已是修复后状态，且本次任务禁止 git 操作。修复 ①~⑤ 的「修复前形态」是由**代码内注释 + 回归测试类注释**重建的（例：`ProtectedColumnActionTest:10-14` 直接贴出修复前的两态写法 `boolean protectedColumn = …||…; if (protectedColumn) continue;`），不是我读到了修复前那次提交。**上文每条「会重现什么现象」都是这些注释/测试的自述，不是我在旧提交里看到的 diff。**
3. **`TunnelClearLedger.report()` 的实际输出行未做运行时核对。** 我只能证明新旧 `report`/`line` 方法体逐字相同（13/13 命中）；`[TUNNEL-CLEAR]` 的「一段一行」承诺需要日志验证（本组未跑游戏、未读 `latest.log`）。
4. **`RoadSpec` 是否对每条路都跑到，未逐条验证。** 只核到调用点 `Road.java:641`（在 `RoadQualityMetrics.record` 之后）与 `Road.java:797`；它的 `SpecViolationException` 是否真的只 warn 不吞异常（`Road.java:800` 的 `catch` 块），以及 `logSpecIntegrity` 的调用频率/限流，未验证。
5. **`clearAboveColumn`（无参 3 参重载）在装饰系统里的实际调用频率**：我证明了「隧道路径不走它」，但没统计它在装饰阶段被调多少次、会不会在隧道洞口附近被间接命中（`DecorationPlanner` 是路面装饰，理论上不进山体，但这属推理不属取证）。
6. **本次核查是「时点快照」，且该快照不是全仓冻结的。** 我开工时（17:00 前后）两仓工作树均为干净 HEAD；收工时（17:09）复核了我核查的全部文件 mtime，**全部仍是我开工前的 2026-11-12/13 凌晨版本，本组范围内无一文件在核查期间被改动**。但同一时刻我观察到 `roadweaver-next/.../pathlogic/profile/ProfileSolverMode.java` 的 mtime 为 `2026-11-13 17:09:20` —— **本组范围之外的另一个文件在建期被别的会话改动了**。本报告的结论只对上述 in-scope 文件负责；若需要「全仓同一快照」的结论，请与其它组的核查时点对齐。

---

## 附：核查方法与可复现命令

全程只读，用 `diff` 而非 `git`：

```bash
# 1) 两个大文件对 diff，检查「新增行」是否只有 注释/import/修饰符/改名
diff -u roadweaver/.../pathlogic/tunnel/RoadTunnelBuilder.java \
        roadweaver-next/.../pathlogic/tunnel/RoadTunnelBuilder.java          # +53 / -570（1519→1002）
diff -u roadweaver/.../pathlogic/junction/ExistingRoadJunctionService.java \
        roadweaver-next/.../pathlogic/junction/ExistingRoadJunctionService.java  # +50 / -351（1143→842）
# 2) 小文件直接看是否只多文件头
diff -u .../BridgeShareService.java .../BridgeShareService.java              # +1 / -0（275→276）
diff -u .../BridgeSegmentPlannerNew.java .../BridgeSegmentPlannerNew.java    # +5 / -0（540→545）
diff -q .../CrossingNegotiator.java .../CrossingNegotiator.java              # 无输出 = 字节相同
diff -u .../TunnelEnvelopeCriterion.java .../TunnelEnvelopeCriterion.java    # 无输出 = 字节相同
# 3) 方法体级：脚本按大括号配对抽方法，去注释+空白规范化，按「方法名+方法体」比对
#    → 旧 147 / 新 150 个抽取体；141 对同名同体；6 个不同体（人工核对为修饰符或改名）
# 4) 反向比对：新 tunnel/junction 目录无「旧版不存在的新方法」（0 命中；脚本把
#    形如 `stationStep(centers, first, 1))` 的调用点误判为声明，这几个 artifact 已人工剔除）
# 5) 回归测试逐个 diff
diff -q .../TunnelBodyClearScopeTest.java .../TunnelBodyClearScopeTest.java            # 相同（111 行）
diff -q .../ProtectedColumnActionTest.java .../ProtectedColumnActionTest.java          # 相同（74 行）
diff -q .../BridgeShareEndpointFallbackTest.java .../BridgeShareEndpointFallbackTest.java # 相同（85 行）
diff -u .../TunnelPortalAdvanceTest.java .../TunnelPortalAdvanceTest.java              # 仅 2 行类引用
```

未做的事（按要求）：未编译、未跑 gradle、未改任何 `.java`、未做写 git 操作、未启动游戏。