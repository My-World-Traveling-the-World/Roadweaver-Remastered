# C 组：纵断面与坡度 —— 方法体逻辑等价性核查

- 旧仓库（oracle）：`D:\云游四海-Mod源码\roadweaver` @ `2c6547a`
- 新仓库（重构版）：`D:\云游四海-Mod源码\roadweaver-next` @ `7bc6023`
- 负责范围：`planning/terrain/AccurateGradeRepairPlanner`、`pathlogic/grade/RoadGradePlanner`、`pathlogic/core/Road`，及其拆分/新增件 `SearchBudgetPolicy`、`TargetYComputation`、`RoadSpanProfileOps`、`EndpointProfileOps`、`pathlogic/profile/*`、`SlopeContinuityEnforcer`、`GradeOptimizer`、`BridgeDeckProfile`
- 全程只读：未改/建/删任何 `.java`，未 git 操作，未编译。唯一写入是本文件。
- 两边对照：每个判定都同时给出旧版与新版的 `文件:行号`。**行数差异不作为任何判定的依据**。

---

## 结论摘要

- **逻辑等价 8 项 / 逻辑改变 14 项 / 仅 LEGACY 生效 10 项 / 无法判定 4 项**
- 其中「逻辑改变」的 14 项里有 10 项的本质就是「那个 pass 的效果只在 `LEGACY` 生效」，两栏是同一现象的两个视角，**不重复计数**。
- **最高风险：默认已经是 DP（`ProfileSolverMode.parse(null)` 返回 `DP`），而 DP 在 `TargetYComputation.java:463` 把旧流水线算出的整条剖面整体丢弃重解；DP 的输入里没有任何水位、甲板净空、引道段数、坡度档位信息 —— 于是桥面从「水位 + 净空」的平甲板变成「两端地形」的插值弦，浅滩抬升、相邻桥连续、峡谷稳岸、海底隧道弦、隧道选线、坡度档位、1-2-1 平滑在默认路径上全部不再生效。用户真机反馈过的「桥头台阶」「河岸跳变」对应的修复代码都还在，但仍然会被 DP 覆盖掉。**

判定口径说明：本项目里「同一输入」= 同一条 `segments` + `spans` + 同一份配置 + 同一个世界。若两组代码对这条输入产出的 `RoadData.targetY` 不同，就算逻辑改变。

### 「逻辑等价 8 项」逐条（每项都有两边位置）

| # | 等价项 | 旧版位置 | 新版位置 |
|---|---|---|---|
| E1 | `RoadGradePlanner` 全部同名方法体 | `pathlogic/grade/RoadGradePlanner.java`（`stabilizeSurfaceProfile` `:287`、`constrainProfile` `:172`、`smoothProfileWithinBudget` `:462`、`applyConfiguredGradeCap` `:329`、`removeSingleStationSpikes` `:1217`…） | `pathlogic/grade/RoadGradePlanner.java`（`:295`、`:180`、`:470`、`:337`、`:777`…），27/27 方法体完全一致 |
| E2 | `AccurateGradeRepairPlanner` 方法体 | `planning/terrain/AccurateGradeRepairPlanner.java` | 同名文件 + 拆出 8 个新类，46/47 一致，1 项仅静态导入改写 |
| E3 | 旧 `Road.java` 迁出的 26 个剖面 helper | `pathlogic/core/Road.java`（`closeTunnelWaterBoundary` `:1382`、`applyCrossingProfiles` `:1442`、端点三件套 `:1179`/`:1192`/`:1209` 等） | `RoadSpanProfileOps.java`、`EndpointProfileOps.java`、`SlopeContinuityEnforcer.java`、`GradeOptimizer.java`，25 项逐字相同 + 1 项仅静态导入 |
| E4 | `SurfaceGradeLimits.capture`（含 O-07 / D-24 修复） | `RoadGradePlanner.java:84-131` | `RoadGradePlanner.java:92-139`，逐字相同 |
| E5 | `RouteProfileTendency.effectiveMode`（AUTO→`BRIDGE_FIRST`/`TUNNEL_FIRST`） | `planning/RouteProfileTendency.java:58` | `planning/RouteProfileTendency.java:59`，方法体逐字相同 |
| E6 | `isSharedElevationEndpoint` + `isVillageEndpoint`（含 `boxesByChunk` 超集索引） | `Road.java:1290-1316`；`StructureAvoidanceService.java:3251-3266` | `RoadSpanProfileOps.java:93`；`EndpointOwnerResolver.java:148-175` |
| E7 | 急弯冻结 + 急弯 lerp（`SHARP_TURN_FREEZE_DEG=60`、`turningAngleDeg`、`catmullRom*`） | `postprocess/PathPostProcessor.java:892/923-929/378/989` | `PathPostProcessor.java:797/828-834/379/894`，30/30 方法体一致 |
| E8 | 9 个迁移常量及其全部消费函数 | `AccurateGradeRepairPlanner.java:1860-1926` | `SearchBudgetPolicy.java:113-179` + 消费者（`chordIsSteep` 等）逐字相同 |

### 「逻辑改变 14 项」逐条

C1 默认切 DP 且整体替换 / C2 桥面甲板改地形弦 / C3 浅滩抬升消失 / C4 相邻桥连续消失 / C5 峡谷稳岸 / C6 海底隧道弦 / C7 隧道主动选线 / C8 针尖与河岸收口 / C9 `GradeCap` 档位 / C10 1-2-1 平滑与 `averagingRadius` / C11 山区 cut-fill / C12 多站共享剖面 / C13 桥引道段数 / C14 每路多次 DP。详见下文 `★ 逻辑改变清单`。

---

## 第一步：旧 15 道 pass 的归宿

「15 道」的口径：旧 `Road.computeTargetY`（`roadweaver/.../pathlogic/core/Road.java:713-1131`）的顶层调用 12 处，其中 `stabilizeSurfaceProfile` 内部含 3 个语义独立的子 pass（挖填钳位 / 隧道走廊 6 轮收敛 / 足迹支撑钳位），按语义拆开共 15 道。下表逐条给位置。

| # | 旧 pass 名 | 旧版位置 | DP 内核等价物 | 判定 |
|---|---|---|---|---|
| 1 | `robustWindowHeight` 中值窗口基线 | `Road.java:837`（实现 `RoadGradePlanner.java:193`） | **无**。DP 直接吃 `terrainY`，从不读 `base`（`TargetYComputation.java:601` 传的是 `terrainY`） | **逻辑改变**（见 C10）→ 实际「仅 LEGACY」 |
| 2 | `constrainProfile` 挖填钳位 | `RoadGradePlanner.java:182-184` | 有：`ProfileDpSource.java:126-127` 的 `lo/hi` 窗口 | **部分等价**：机制等价（钳位），但上限口径从 `cut(mountain)` 变成 `plainCut`（见 C11） |
| 3 | `findTunnelCorridors` + `constrainProfile` 的 6 轮收敛 | `RoadGradePlanner.java:299-313` | **无**。DP 只吃由 `spans` 派生的 `tunnelMask` 当覆土上限 | **仅 LEGACY** |
| 4 | `constrainProfileToFootprintSupport` 整宽支撑 | `RoadGradePlanner.java:240` | **无** | **仅 LEGACY** |
| 5 | `smoothProfileWithinBudget`（4 轮 1-2-1 且钳回带宽） | `Road.java:840` → `RoadGradePlanner.java:462` | **无**。平顺性改由 `maxStep` + 二阶约束保证，代价函数里没有平滑项 | **逻辑改变**（见 C10） |
| 6 | `applyConfiguredGradeCap` / `enforceGradeCap`（坡度档位） | `RoadGradePlanner.java:329`、`:375`/`:398` | **无**。全仓 `GradeCap` 只被 `RoadGradePlanner` 自己读 | **仅 LEGACY**（见 C9） |
| 7 | 结构边界锁定 #1（`applySharedApproachProfile` / `applySingleBoundaryGrade` / `applyPreferCutBoundaryGrade`） | `Road.java:869-916` | **仅端点单站**：`TargetYComputation.java:580-583` 只写 `pin[0]`/`pin[n-1]` | **部分等价 / 部分改变**（见 C12） |
| 8 | `applyProactiveTunnelProfile`（含 riverside guard） | `Road.java:931`、`:947` | **无**。DP 不做隧道选线，只把 `tunnelMask` 当覆土上限（`ProfileDpSource.java:135-142`） | **仅 LEGACY**（见 C7） |
| 9 | 结构边界锁定 #2（隧道后重坐） | `Road.java:960-990` | **无**（DP 之后无任何重坐） | **仅 LEGACY** |
| 10 | `applyCrossingProfiles` 主循环（桥甲板基准） | `Road.java:1001`（实现 `:1442-1524`） | 有但语义不同：`ProfileDpSource.java:151-178` 把桥段钉成 `terrainY` 直线内插 | **逻辑改变（最高风险）**（见 C2） |
| 11 | `applyShortFordClearance` 浅滩抬升 + 岸切 | `Road.java:1526-1559` | **无** | **仅 LEGACY / 逻辑改变**（见 C3） |
| 12 | `convertLongOceanBridgesToUnderseaTunnels` + `applyUnderseaTunnelProfile` | `Road.java:1003`、`:1014` | 前半仍跑并原地改 `spans`；后半的剖面**被覆盖** | **部分等价 + 部分改变**（见 C6） |
| 13 | `enforceContinuousSlopePreferCut`（1 格/站收口） | `Road.java:1025`（实现 `RoadGradePlanner.java:1027/1051`） | 有：DP 的 `maxStep`/二阶约束 | **约束等价，方向改变**（见 C1 的 prefer-cut 一节） |
| 14 | `closeTunnelWaterBoundary`（水面跨端收口 = 河岸跳变补丁） | `Road.java:1029`（实现 `:1382-1440`） | **无** | **仅 LEGACY**（见 C8） |
| 15 | `removeSingleStationSpikes` + 尖峰后再铺边界坡 | `Road.java:1051`、`:1067-1086` | **无**，但 DP 构造上不产生「隔站尖峰」，效果等价；边界坡重坐丢失 | **效果等价（尖峰）/ 改变（重坐）** |

**关键结构性事实（这是整组的根）**：`applyProfileSolver` 的调用点在 `TargetYComputation.java:463`，即上表 15 道**全部跑完之后**；DP 分支对 `smoothed` **只读长度、不读一个元素**：

```java
// roadweaver-next/.../pathlogic/core/TargetYComputation.java:567-583
private static int[] applyProfileSolver(int[] smoothed, int[] terrainY, boolean[] isCrossing,
                                        boolean[] tunnelMask, boolean[] pinnedMask, ...) {
    if (ProfileSolverMode.fromProperty() != ProfileSolverMode.DP) {
        return smoothed;                       // LEGACY：原样返回
    }
    if (!cfg.slopeLimitEnabled()) return smoothed;
    int n = smoothed.length;                   // ← 唯一一次使用 smoothed
```

```java
// TargetYComputation.java:601-604
var solved = ProfileDpSource.solve(terrainY, isCrossing,
        maxCut, maxFill, maxStep, perTwo, constraints);
// ... 返回全新解，覆盖掉上面 15 道 pass 的结果
```

所以默认路径下这 15 道 pass 的计算结果**只用于回退与 AB 观测，不进入 `RoadData.targetY`**；它们仍然被完整执行（CPU 照付）。

---

## 第二步：5 个历史修复的现状

| # | 历史问题 | 旧修复位置 | 新版位置 | 判定 |
|---|---|---|---|---|
| 1 | **桥头台阶**（桥面与路面衔接高度连续） | `Road.java:1442-1524` + `BridgeDeckProfile.applyFixedRange`（自适应引道 `:30-69`） | 代码逐字搬到 `RoadSpanProfileOps.java:245-313` + `BridgeDeckProfile.java:31-70`；但调用点在 `TargetYComputation.java:365`，结果在 `:463` 被 DP 覆盖。DP 侧把桥段钉成地形弦（`ProfileDpSource.java:151-178`） | **逻辑改变**（C2/C13）。桥面高度来源从「水位+净空」变成「两端地形插值」，自适应引道长度不再被读取 |
| 2 | **河岸跳变**（`water-span boundary height jump` 检测） | `Road.java:1124` 的日志（只检测、不改） | `TargetYComputation.java:503-514` 逐字保留，跑在 DP 输出上 | **检测等价**；但触发概率上升，因为 C2 让桥面低于水面，`abs(yStart-yBefore)>1` 更容易成立。对应的**修复** `closeTunnelWaterBoundary`（`Road.java:1029`）是仅 LEGACY（C8） |
| 3 | **AUTO 地形模式压缩 fill/cut** | `RoadGradePlanner.SurfaceGradeLimits.capture`（`RoadGradePlanner.java:92-135`） | `RoadGradePlanner.java:92-135` 逐字相同 | **★ 前提需要修正，见下方专项**。另外 DP 分支根本不读这些值 → AUTO 倾向在 DP 下完全不生效 |
| 4 | **1-2-1 平滑** | `smoothProfileWithinBudget`（`RoadGradePlanner.java:462-500`，`PROFILE_SMOOTH_PASSES=4`） | 方法体逐字保留在 `RoadGradePlanner.java:470-508`，但输出被 DP 丢弃 | **LEGACY 下等价 / DP 下不需要也没有替代**。DP 用坡度硬约束保证合法，但没有任何等价于「多轮 1-2-1 摊平锯齿」的机制（C10） |
| 5 | **急弯冻结 / 样条** | `PathPostProcessor.java:892`（`SHARP_TURN_FREEZE_DEG=60.0`）、`:923-929`（急弯不参与平滑）、`:378`（急弯用直线代替 Catmull-Rom）、`:989`（`turningAngleDeg`） | `PathPostProcessor.java:797`、`:828-834`、`:379`、`:894`，逐字相同 | **逻辑等价**。该组 30 个同名方法体 30/30 完全一致（机械核对，见文末）。注：这段不在 `Road.java` 而在 `PathPostProcessor`，`Catmull-Rom` 在 `SplineHelper`/`RoadPathCalculator` |

### 第 3 项专项：AUTO 减配的真相与任务前提的出入

任务描述里说旧版会 `fill = min(1, 配置)`，实测**该 commit 里已经不是这样了**，改动编号 O-07：

```java
// OLD  roadweaver/.../pathlogic/grade/RoadGradePlanner.java:108-113
int effectivePlainCut = reducedCut ? Math.min(3, plainCut) : plainCut;
int effectivePlainFill = reducedFill && plainFill > 0 ? Math.max(1, plainFill - 1) : plainFill;
int effectiveMountainCut = reducedCut ? Math.min(3, mountainCut) : mountainCut;
int effectiveMountainFill = reducedFill && mountainFill > 0 ? Math.max(1, mountainFill - 1) : mountainFill;
```

```java
// NEW  roadweaver-next/.../pathlogic/grade/RoadGradePlanner.java:116-121
int effectivePlainCut = reducedCut ? Math.min(3, plainCut) : plainCut;
int effectivePlainFill = reducedFill && plainFill > 0 ? Math.max(1, plainFill - 1) : plainFill;
int effectiveMountainCut = reducedCut ? Math.min(3, mountainCut) : mountainCut;
int effectiveMountainFill = reducedFill && mountainFill > 0 ? Math.max(1, mountainFill - 1) : mountainFill;
```

逐字相同。**结论分三层**：

1. 「fill 被压到 1」这个 bug **在两版里都没有**：`reducedFill` 时是「比配置少 1，下限 1」。用户配 4 → AUTO/BRIDGE_FIRST 路线上得 3，不是 1。所以这不是「一致保留的 bug」，而是**旧版已修、新版原样继承**。
2. 「cut 被绝对压到 3」**两版都在**：`min(3, plainCut)`。用户把 `plainSurfaceCutMaxBlocks` 调到 8，在这种路线上仍只按 3 算 —— 这是**一致的既有语义**（注释明确说 cut 侧是刻意保留的既有语义）。
3. **新问题**：`SurfaceGradeLimits` 在 DP 分支里**只被取 `plainCut()`/`plainFill()`**（`TargetYComputation.java:598-599`），而 `reducedCut/reducedFill` 的减配结果**就写在 plain 值里**，所以减配本身仍然生效；但 `mountainCut/mountainFill` 与 `GradeCap` 在 DP 下完全不生效。也就是说：**配置「调了没反应」的问题在 DP 下换了个位置，没有消失**（见 C9/C11）。

---

## ★ 逻辑改变清单

> 每条格式：旧代码 → 新代码 → 同输入下结果怎么不同 → 玩家能观察到什么。
> 按「是否与 15 道 pass 存废重叠」分两组：C1–C4 是 DP 内核**自身定义**不同；C5–C14 是某个 pass 的**效果**被 DP 整体取代。

### C1 总开关：默认已是 DP，且 DP 在所有旧 pass 之后整条替换

**旧版**（`computeTargetY` 的返回值就是流水线最后结果，没有第二个求解器）：

```java
// roadweaver/.../pathlogic/core/Road.java:1088-1090
List<Integer> out = new ArrayList<>(n);
for (int v : smoothed) out.add(v);      // ← 直接返回 15 道 pass 的结果
return out;
```

**新版**：

```java
// roadweaver-next/.../pathlogic/core/TargetYComputation.java:458-464
int[] legacySnapshot = smoothed.clone();                          // 只给 AB 观测器当 legacy 臂
RoadGradePlanner.SurfaceGradeLimits gradeLimits = RoadGradePlanner.SurfaceGradeLimits.capture();
smoothed = applyProfileSolver(smoothed, terrainY, isCrossing, tunnelMask, pinnedMask,
        startBoundary, endBoundary, cfg, gradeLimits, connection);   // ← 默认 DP，整条替换
```

```java
// roadweaver-next/.../pathlogic/profile/ProfileSolverMode.java:65-71
public static ProfileSolverMode parse(String raw) {
    if (raw == null || raw.isBlank()) return DP;     // ← 未设属性 = DP
    ...
    return DP;                                        // ← 非法值也 DP
}
```

**同输入下怎么不同**：旧版输出 = 15 道 pass 的合成结果；新版输出 = 以 `terrainY` + 坡度约束 + 挖填代价 求出的全局最优解。**两者唯一的共同输入是 `terrainY`、`isCrossing`、`tunnelMask` 与端点 pin。**
默认真的会走 DP：`slopeLimitEnabled` 两版默认都是 `true`（`RoadGenerationConfig.java:18`、`RoadAppearanceConfig.java:25`），`maxSlopeStepPerTwoSegments` 默认 `1`（`RoadConstants.java:117`），所以 `perTwo=1 → maxStep=1`，DP 分支不会被这两道 guard 挡掉。

**玩家能观察到**：所有依赖 pass 的地形补丁在默认路径上消失（C2–C14 逐条）。

### C2 桥面甲板：从「水位 + 净空」的平甲板，变成「两端地形」的斜弦

**旧版**（甲板基准 = 水面 + 净空，且 `max()` 保证永不低于该基准）：

```java
// OLD roadweaver/.../pathlogic/core/Road.java:1466-1473
hwByRange[ri] = PathSpanExtractor.waterSurfaceYForRange(
        segments, level, cache, terrain, streams, bridgeMinWaterDepth,
        range.start(), range.end());
deckByRange[ri] = BridgeDeckProfile.minimumDeckY(
        hwByRange[ri],
        centers.get(range.start()).getY(),
        centers.get(range.end()).getY(),
        deckClearance);
```

```java
// OLD roadweaver/.../pathlogic/bridge/BridgeDeckProfile.java:86-92
// NEW roadweaver-next/.../pathlogic/bridge/BridgeDeckProfile.java:87-93（逐字相同）
public static int minimumDeckY(int highestWaterY, int bankStartY, int bankEndY, int deckClearance) {
    int clearance = Math.max(1, deckClearance);
    if (highestWaterY == Integer.MIN_VALUE) {
        return Math.max(bankStartY, bankEndY) + clearance;
    }
    return highestWaterY + clearance;        // ← 水面 + 净空
}
```

```java
// OLD BridgeDeckProfile.applyFixedRange:44-45（NEW 同方法 :45-46，逐字相同）
int bankGrade = (int) Math.round(leftY + (rightY - leftY) * progress);
profile[i] = Math.max(minimumDeckY, bankGrade);   // ← 平甲板下界
```

`waterSurfaceYForRange` 返回的确实是**水面上表面**的最大值（`PathSpanExtractor.java:213-221`：`highest = max(sample.waterSurfaceY())`）。`DEFAULT_BRIDGE_DECK_CLEARANCE = 2`（`RoadConstants.java:66`）。

**新版**（桥段钉成**两端地形**的直线内插，水位完全不参与）：

```java
// NEW roadweaver-next/.../pathlogic/profile/ProfileDpSource.java:151-178
// 桥段钉成两端地形直线内插
int start = i;
while (i < n && crossing[i]) i++;
int end = i - 1;
int a = terrainY[start];                 // ← 端站【地形】高度
int b = terrainY[end];
int len = end - start;
for (int k = start; k <= end; k++) {
    if (constraints.pinned(k)) continue;
    int chord = len == 0 ? a : a + Math.round((b - a) * (k - start) / (float) len);
    pin[k] = chord;                      // ← 钉死，DP 必须用这个值
    lo[k] = chord;
    hi[k] = chord;
}
```

**同输入下结果不同，给出可手算的例子**：

- 海平面附近的河（`seaLevel=63`，河床 `60`，水面 `63`）。`surfaceHeight` 的判据是 `motionBlockingNoLeaves > seaLevel+2 ? motion : worldSurfaceWg`（`AccurateHeightSampler.java:922-926`）；河床 60 不满足 `>65`，所以 **`terrainY` = 水面 63**。
  - 旧：`minimumDeckY = 63 + 2 = 65`，`applyFixedRange` 把整段抬到 65。
  - 新：`chord = 63`，整段钉在 63。
  - **桥面整段下沉 2 格，与水面齐平。**
- 海平面以上的山间河（河床 `100`，水面 `103`）。`motion=100 > 65` → **`terrainY` = 河床 100**。
  - 旧：`minimumDeckY = 103 + 2 = 105`。
  - 新：`chord = 100`。
  - **桥面比旧版低 5 格，且比水面低 3 格 —— 泡在水下。**

**玩家能观察到**：桥面贴水/入水（就是「桥泡在水里」）；两岸要下坡 2~5 格才能上桥，形成新的桥头落差；跨水区间两端若岸比弦高超过 `plainCut`(=5)，DP 直接判不可行并静默回退 LEGACY，于是同一条路网里有的桥按旧法建、有的按新法建。

### C3 浅滩：`highestWater + FORD_DECK_CLEARANCE` 抬升与两岸切平在 DP 路径消失

**旧版**：

```java
// OLD roadweaver/.../pathlogic/core/Road.java:1533-1556
int required = highestWater + FORD_DECK_CLEARANCE;      // FORD_DECK_CLEARANCE = 2（新版 Road.java:85 同值）
for (int i = range.start(); i <= range.end(); i++) {
    if (profile[i] < required) { profile[i] = required; lifted = true; }
}
int left = range.start() - 1;
int right = range.end() + 1;
if (left >= 0 && profile[left] > required) { profile[left] = required; lifted = true; }
if (right < profile.length && profile[right] > required) { profile[right] = required; lifted = true; }
...
rampOntoBank(profile, range.start() - 1, -1, ramp);
rampOntoBank(profile, range.end() + 1, +1, ramp);
```

该段注释（新版 `RoadSpanProfileOps.java:318` 附近，逐字）明确写它修的是「旧 +16 甲板把两岸抬 6 格、留下 prefer-cut 关不掉的边界台阶」——就是**桥头台阶**那个真机问题。

**新版**：浅滩 `ROAD_CROSSING` 区间同样走 C2 的 chord 分支（`ProfileDpSource.java:151-178`）；`applyShortFordClearance` 逐字存在（`RoadSpanProfileOps.java:329-375`）但结果被 `TargetYComputation.java:463` 覆盖。

**同输入下结果不同**：浅滩不再被抬到水面 +2，也不再把高岸切到浅滩高度；取两岸地形插值。
**玩家能观察到**：浅滩段可能沉到水面以下（可涉水处变成水下），两岸高差变成一段陡下坡；「桥头台阶」这个被修过的现象以新形态回来。

### C4 相邻桥甲板连续性（0.9.066 B2 修的 71→73 跳变）消失

**旧版**：`BridgeDeckProfile.enforceAdjacentBridgeContinuity(deckByRange, ...)` 在 `Road.java:1483` 调用 —— 把 `gap <= BRIDGE_SPAN_CONTINUITY_GAP` 的相邻 BRIDGE 跨度视为同一片水，后跨甲板不得低于前跨。

**新版**：调用逐字搬到 `RoadSpanProfileOps.java:286`，仍在 `TargetYComputation.java:365` 执行，但结果在 `:463` 被覆盖。DP 对每跨**独立**按 `terrainY[start]`/`terrainY[end]` 钉弦（`ProfileDpSource.java:151-178`），跨与跨之间没有任何「后跨不低于前跨」的约束。

**玩家能观察到**：同一条路被水切成两跨时，两段桥面重新出现高度台阶（正是 0.9.066 修掉的那个真机现象）。

### C5–C14 效果被 DP 整体取代的 pass（详见下一节「仅 LEGACY 保留」）

| 编号 | 内容 | 判定依据 |
|---|---|---|
| C5 | 峡谷稳定岸 `applyStableBankRanges` | 旧 `Road.java:1497-1500`；新版逐字在 `RoadSpanProfileOps.java:301`，被覆盖 |
| C6 | 跨海长桥转海底隧道后的剖面弦 | 旧 `Road.java:1003-1017`；新版 `TargetYComputation.java:367-381` 逐字，但剖面被覆盖；**副作用仍在**（`spans.clear()/addAll()` 真的把 span 改成 TUNNEL） |
| C7 | 隧道主动选线 | 旧 `Road.java:931/947`；新版 `TargetYComputation.java:293-323` 逐字，被覆盖。DP 侧只有 `new ProfileDpSource.Constraints(pin, tunnelMask, minCover)`（`:592`） |
| C8 | 隧道针尖 / 河岸跳变收口 | 旧 `Road.java:1029`、`:1051`；新版 `TargetYComputation.java:393`、`:415` 执行后被覆盖，`:465` 起再无任何改高度的 pass |
| C9 | `GradeCap` 坡度档位 | 全仓 `GradeCap.*` 只有 `RoadGradePlanner` 读（`:339/353/387/390/391/407/415/428/699`），DP 侧零引用 |
| C10 | 1-2-1 平滑 + `averagingRadius` 基线 | DP 输入是 `terrainY`（`:601`），`base = robustWindowHeight(terrainY, i, avg)`（`:201`）完全不被读 → **平均半径配置在默认路径失效** |
| C11 | 山区 cut/fill 限额 | 旧按站取 `limits.cut(mountain)`（`RoadGradePlanner.java:182-184`、`:473-474`）；DP 一律 `plainCut/plainFill`（`TargetYComputation.java:598-599`） |
| C12 | 结构边界多站共享剖面 | 旧 `applySharedApproachProfile` 逐站拷 N 个高度（`Road.java:1179-1190` = `EndpointProfileOps.java:122-133`）；DP 只 `pin[0]=startBoundary`（`TargetYComputation.java:580-583`） |
| C13 | 桥引道段数 `bridgeRampSegments` | `ProfileDpSource` 全文件无该配置引用；旧 `BridgeDeckProfile.applyFixedRange:48-58`（新 `:49-59`）的 `max(configuredRamps, difference)` 丢失 |
| C14 | 每路 DP 求解次数 | `computeTargetY(` 在 `Road.java:187/197/664/708` 共 4 个调用点，其中 `settleProfile` 在 `:213-216` 的 3 轮循环里被调 → 单条路最多 6 次；**旧 15 道 pass 照跑不误**，等于两套算法都付钱（纯性能，未实测） |

**C11 的具体数值影响**（这是「配置调了没反应」的另一半）：
`TerrainProfileConfig` 默认 `mountainSurfaceCutMaxBlocks=3` / `mountainSurfaceFillMaxBlocks=1` / `plainSurfaceCutMaxBlocks=5` / `plainSurfaceFillMaxBlocks=2`（`TerrainProfileConfig.java:8-11`）。
旧版在山区站用 3/1，DP 用 5/2 —— 山区**允许挖得更深、填得更高**，配置的「山地少挖少填」倾向丢失；把 mountain 配到 8/6 也不会生效。

**C9 的日志副作用**：`GradeCap.summary(mode, cappedStations, total)` 仍会打印（来自被丢弃的 legacy 计算，`RoadGradePlanner.java:353`），所以 `cappedStations` 描述的是**没被采用的剖面**。排查时会被这行日志误导。

### C12 补充：DP 的「自动引道」主张只对单站成立

`ProfileDpSource.java` 的 javadoc（`solve` 上方）声称「钉住 + `maxStep` 合起来自动生成合法引道，这正是旧版 `applySingleBoundaryGrade` 手工在做的事」。实测这个等价性有两处缺口：

1. `applySingleBoundaryGrade` 收紧的范围是 `half = Math.max(1, Math.min(96, (len-1)/2))`（`EndpointProfileOps.java:140`，旧 `Road.java:1197`）—— 最多半边路逐站 ±1；DP 只保证 `|Δ| <= maxStep`。默认 `perTwo=1 → maxStep=1` 时两者数值上碰巧一致，但把 `maxSlopeStepPerTwoSegments` 调到 4 时，DP 的引道会变成 2 格/站，而旧版仍是 1 格/站。
2. `applySharedApproachProfile` 拷贝的是注册表定的**多站共享剖面**（每站可以是任意值），DP 只认 `shared[0]`。**两条从不同方向接入同一未知端点的路，不再共享同一条引道剖面。** 这恰好是 `ProfileDpSource.java` 自己声明的「契约②：未知端点共享高度」，实际只保留了单站等价。

### C15 回退是「静默」的，且存在一个必然抛异常的分支

```java
// NEW TargetYComputation.java:604-620
if (solved.isEmpty()) {
    LOGGER.warn("RoadWeaver profile solver=dp infeasible, falling back to legacy ...");
    return smoothed;
}
...
} catch (Throwable t) {
    LOGGER.warn("RoadWeaver profile solver=dp failed, falling back to legacy: {}", t.toString());
    return smoothed;
}
```

而 `ProfileDpSource.prepare` 会主动抛：

```java
// NEW ProfileDpSource.java:142-145
if (constraints.covered(i) && v > terrainY[i] - constraints.minCover()) {
    throw new IllegalArgumentException("第 " + i + " 站既被钉在 " + v
            + " 又要求覆土 ≥ " + constraints.minCover() + "（地表 " + terrainY[i] + "）"
            + " —— 这两个契约互相矛盾，调用方接线错了");
}
```

**同输入下的判定**：一条路的**端点**同时满足「被结构边界钉住」和「落在隧道 mask 内」且锁定高度高于 `terrain - minCover` 时，必然抛异常 → `catch` 吞掉 → 整条路静默回退 LEGACY。这正是旧版 0.8.368 注释里记录过的「端点落在 TUNNEL span 里」那类路（旧 `Road.java:991-1000`，新版对应注释在 `TargetYComputation.java:355-364`）。
**玩家能观察到**：这类路的剖面与旧版完全一致（因为回退了），但日志只有一行 WARN；「默认 DP」的实际覆盖率不是 100%，且无法从配置看出哪些路走了哪条分支。

### C16 `ProfileSolverMode` 头注释与实现互相矛盾（文档债）

```java
// NEW ProfileSolverMode.java:1
/* 文件职责：剖面求解器的模式开关 —— 默认 LEGACY，DP 仅显式开启。 */
```

```java
// NEW 同文件:65-71
public static ProfileSolverMode parse(String raw) {
    if (raw == null || raw.isBlank()) return DP;      // ← 与首行注释相反
```

同文件类 javadoc 还在说「默认值必须让行为逐字节不变」，而 `:42-43` 又说「2026-11-13 起为默认 DP」。三处口径互相矛盾。**风险**：下一次维护若按首行注释推断「默认路径逐字节不变」，会把回归基线建立在错误假设上。

---

## 仅 LEGACY 保留的 patch（默认 DP 时不生效）

**必须先把措辞说准**：这些 pass **不是**「只在 `LEGACY` 分支里」。它们在 DP 模式下**仍然被完整执行**（`TargetYComputation.java:204`、`:365`、`:389`、`:393`、`:415` 都无条件跑），只是算完之后在 `:463` 被 DP 的输出整体覆盖。所以现状比「仅 LEGACY 保留」更浪费：**代码照跑、CPU 照付、结果丢弃**，只有 DP 判不可行/抛异常时才真正生效。

| # | patch | 旧版位置 | 新版代码位置（逐字保留） | DP 下为何不生效 |
|---|---|---|---|---|
| 1 | `robustWindowHeight` 中值基线（`averagingRadius` 配置） | `Road.java:837` | `RoadGradePlanner.java:201` | DP 输入是 `terrainY`，`base` 不参与（`TargetYComputation.java:601`） |
| 2 | `stabilizeSurfaceProfile` 的 6 轮隧道/足迹收敛 | `RoadGradePlanner.java:299-313` | `RoadGradePlanner.java:307-315` | DP 只看 `spans` 派生的 `tunnelMask`，不看 `findTunnelCorridors` 的结果 |
| 3 | `applyProactiveTunnelProfile`（隧道选线） | `Road.java:931/947` | `RoadGradePlanner.java:511/527` | DP 不做选线，只用覆土上限（`ProfileDpSource.java:135-142`） |
| 4 | `applyCrossingProfiles` 全家族（桥甲板 / 浅滩 / 峡谷 / 相邻跨连续 / 自适应引道） | `Road.java:1001`、`:1442-1571` | `RoadSpanProfileOps.java:245-375` | DP 改用地形弦（`ProfileDpSource.java:151-178`），`bridgeDeckClearance`/`bridgeRampSegments`/`FORD_DECK_CLEARANCE` 均不被读 |
| 5 | `applyUnderseaTunnelProfile`（海底隧道剖面） | `Road.java:1014` | `UnderseaTunnelPlanner`（`TargetYComputation.java:378`） | 剖面被覆盖（span 类型改动仍保留） |
| 6 | `enforceContinuousSlopePreferCut`（1 格/站收口 + **prefer-cut 方向契约**） | `Road.java:1025` | `SlopeContinuityEnforcer.java:102-121` | DP 用 `maxStep`/二阶约束替代「只降不升」；代价模型里 fill 更便宜（见下） |
| 7 | `closeTunnelWaterBoundary`（河岸跳变补丁） | `Road.java:1029` | `RoadSpanProfileOps.java:185` | DP 之后无此 pass，`:465` 起无任何改高度代码 |
| 8 | `removeSingleStationSpikes`（隧道针尖） | `Road.java:1051` | `RoadGradePlanner.java:777` | DP 构造上不产生尖峰 → **效果等价**；但「尖峰后再铺边界坡」的三次重坐（`Road.java:1067-1086`）彻底没有 |
| 9 | `applyConfiguredGradeCap` / `GradeCap` 坡度档位 | `RoadGradePlanner.java:329` | `RoadGradePlanner.java:337` | DP 零引用 `GradeCap` |
| 10 | `smoothProfileWithinBudget`（4 轮 1-2-1 且钳回带宽） | `Road.java:840` → `RoadGradePlanner.java:462` | `RoadGradePlanner.java:470` | 代价函数无平滑项（`CostModel.java:37` 只有挖填权重） |

### 其中一条要单独强调：prefer-cut 方向契约被翻转

旧版整条流水线的方向契约是「**只会把路压进山里，绝不会造出天桥**」（`RoadGradePlanner.java` 的 `enforceGradeCap` / `lowerRunToGradeCap` 全部只做 `min`，`applyConfiguredGradeCap` 的 `cappedStations` 只统计 `result[i] < before[i]`）。

DP 的代价函数反过来：

```java
// NEW roadweaver-next/.../pathlogic/profile/CostModel.java:37
public static Settings defaults() {
    return new Settings(1.0, 0.7, 16, 3.0);     // cutWeight=1.0, fillWeight=0.7
}
```

```java
// NEW CostModel.java:63-70 —— 代价在 y == terrain 处为 0，偏离时 fill 比 cut 便宜 30%
int d = y - terrain[i];
cost[i][k] = d >= 0 ? fillCost(d, s) : cutCost(-d, s);
```

**手算三站例子**（`perTwo=1 → maxStep=1`、二阶 `|d_i+d_{i-1}|<=1`，地形 `[99,100,101]`）：

- `y=[99,100,101]`：`d=+1,+1` → `|+1 + +1| = 2 > 1` → **不可行**（这是 DP 二阶约束的正确行为）。
- `y=[99,100,100]`：代价 `0 + 0 + cut(1)*1.0 = 1.0`（旧版 prefer-cut 会选这类）。
- `y=[100,100,101]`：代价 `fill(1)*0.7 + 0 + 0 = 0.7` ← **DP 选这个**。

**结果**：同样的地形，旧版把坡顶削掉 1 格，新版把坡底垫高 1 格（路面浮在地面之上）。
**玩家能观察到**：上坡前的低端出现 1~2 格高的填方路堤/浮空边缘；这与旧版「绝不造天桥」的契约相反。上界由 `plainSurfaceFillMaxBlocks=2` 与施工端 `MAX_FILL_DEPTH=2`（`RoadConstructionSafety.java:80`）共同限制，所以最多 2 格，**不会崩，但方向相反**。

---

## 无法判定的地方

如实列出，不猜。以下 4 项都缺真机数据或缺跨组信息。

1. **DP 的不可行回落比例**。`RoadWeaver profile solver=dp infeasible, falling back to legacy`（`TargetYComputation.java:605-610`）在 `latest.log` 里可数，但我没有本局的日志，无法给出「多少比例的路实际走了 DP」。这直接决定上面 14 项改变的真实影响面。**建议主控用一条真实生成的 `latest.log` 统计 `solver=dp` 与 `infeasible` 两行条数。**
2. **DP 的 `fill=2` 会不会被施工端/验证判死**。`RoadConstructionSafety.MAX_FILL_DEPTH=2`（`:80`）与 `MAX_CUT_DEPTH=5`（`:46`）正好等于 `plain*` 默认值，理论上合法；但山区站旧版用 1、DP 用 2，我无法确认 `RoadDataValidator.validateFinalRoad` 是否还有更严的逐站门槛会拒收。需要真机验证或该组负责人确认。
3. **桥面贴水是否会直接导致建桥失败**。C2 的算术结论（海平面河 -2 格、山间河 -5 格）是确定的；但施工端对「甲板在水面以下」的桥是否报错/跳过/正常铺，我没有读 `RoadConstructionSafety` 的桥体分支，无法判定下游后果。属于 B 组（施工/跨度）范围。
4. **DP 的 prefer-fill 方向在真实长坡上的整体形态**。C1 里那节的三站推演是确定的局部结论；整条 2000 站路线的最优解在长距离上如何分布填方，我没有离线重放（`ProfileTerrainRecorder` 的录制文件在本仓库不存在，`ProfileTerrainReplayTest` 会自动跳过）。需要 AB 日志或离线重放才能量化。

---

## 附：本次核查的机械证据

为避免「读了几处就下结论」，方法体等价性用脚本做了逐方法体对比（去注释、去空白后做子串/相等比较），结果如下。这也是「逻辑等价」8 项的支撑。

| 文件 | 同名方法数 | 方法体完全一致 | 不一致项及解释 |
|---|---|---|---|
| `pathlogic/grade/RoadGradePlanner.java` | 27 | **27** | 无 |
| `planning/terrain/AccurateGradeRepairPlanner.java` | 47 | **46** | `awaitLocalStructureCircle`：仅 `StructureAvoidanceService.clampCircleProbeRadius(...)` → 静态导入 `clampCircleProbeRadius(...)`，同一实现 |
| `pathlogic/core/Road.java` | 7 | **5** | `computeTargetY`（DP 分派点，应改）；`tryRiverRepair`：仅 `RoadPathCalculator.repairRiverRouteSegments` → `PathFinalizeOps.repairRiverRouteSegments`，同一实现 |
| `postprocess/PathPostProcessor.java` | 30 | **30** | 无（急弯冻结 / 急弯 lerp 全部等价） |

旧 `Road.java` / `RoadGradePlanner.java` 迁出到新类的 26 个 helper，方法体逐一核对：

`closeTunnelWaterBoundary`、`closeSpanSide`、`applyCrossingProfiles`、`applyShortFordClearance`、`rampOntoBank`、`isCanyonRange`、`toIntegerList`、`applySharedApproachProfile`、`applySingleBoundaryGrade`、`applyPreferCutBoundaryGrade`、`constrainEndpointRamp`、`crossingPreciseHeight`、`boundaryCrossingAnchor`、`logEndpointProfile`、`enforceContinuousSlopePreferCut`、`configuredStationSpacing`、`lowerRunToSlope`、`constrainRun`、`mergeMasks`、`deviationCost`、`applyFixedRange`、`minimumDeckY`、`enforceAdjacentBridgeContinuity`、`applyStableBankRanges`、`findStableBankRange` → **25 项逐字相同**；`isSharedElevationEndpoint` 唯一差异是 `StructureAvoidanceService.isVillageEndpoint` → 静态导入，同一实现（`EndpointOwnerResolver.java:148-175`，逻辑逐行相同，仅把全表扫描换成 `boxesByChunk` 分桶查询）。

关于最后这条 `boxesByChunk` 索引，我顺带验证了它不会改变结果：`PlanningSnapshot` 把每个盒登记到**它所跨的全部区块**（`box.minX-roadRadius>>4 .. box.maxX+roadRadius>>4`，`PlanningSnapshot.java:118-128`），`StructureBoxIndex.query` 又遍历查询矩形的全部桶（`:145-160`），所以返回的是**候选超集**，而精判判据 `horizontalDistanceSquared <= reach²` 一字未改 → 与全表扫描等价。这一条建议 A/B 组复核，因为它超出了我的范围。

`SearchBudgetPolicy` 的 9 个常量值与消费函数（`chordIsSteep`、`detourFactorFor`、`lengthCapFor`、`lengthBoundFor`、`withinLengthCap`、`chordSamples`、`chordSampleSpacing`、`chordLength`、`localDetourTunnelFloor`、`expandedStateLimit`、`repairWindowStateLimit`、`repairWindowBudgetNanos`）逐一核对，与旧 `AccurateGradeRepairPlanner` **逐字相同**，常量值 `1.6 / 2.6 / 0.25 / 0.20 / 12 / 5 / 256 / 24 / 200.0` 一致。

另有 1 项真删除（与常量无关，单独记录）：

```java
// NEW roadweaver-next/.../pathlogic/core/TargetYComputation.java:102-104
private static final int PROFILE_AB_MAX_CUT = 24;
private static final int PROFILE_AB_MAX_FILL = 12;
```

全仓仅这 2 行声明，**零次读取**。AB 观测实际用的是 `gradeLimits.plainCut()/plainFill()`（`TargetYComputation.java:524-527`）。属实验参数遗留死壳，不影响行为。