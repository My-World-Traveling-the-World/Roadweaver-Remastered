# 批 3（桥体与施工）逐项生效核对（2026-11-15）

> **这份文档回答**：批 3 的各个职责，在新链路上**是否真的生效**？
> 之前只核过「符号在不在」（`FEATURE-PARITY-CHECK.md` 62 项），
> 这份补的是**调用链是否活的**。
>
> **结论先说**：**10 个职责全部在活链路上，没有一个是死的**。
> 但有一件更重要的事被查出来了 —— **批 3 的「换实现」只做了一半**：
> 新包 `pathlogic/construction` 只承载「铺装 / 施工量规划 / 序列化帧 / 巨树预留」，
> **桥体与隧道建造本身仍跑在旧包 `pathlogic/bridge` + `pathlogic/tunnel`**。
> 详见第四节。

---

## 一、入口链（唯一的落脚点）

```
LoadedChunkRoadBackfill:159
  └─ PathFeature.placeChunk(level, level, chunkPos, random)
       └─ 逐站分派（PathFeature:512 起）
            ├─ :512  if (bridgeEnabled && isBridge[i])        ← 桥分支
            │    ├─ :515  BridgeSegmentPlannerNew.processSegment(...)   → placedTemplate
            │    └─ :522  BridgeSegmentPlanner.processSegment(...)      → placedFallback（仅当模板没放成）
            └─ :531  else                                     ← 非桥分支
                 └─ :532  RoadTunnelBuilder.processSegment(...)
                      └─ 回调内 :565/:568 或 :582/:585
                           SegmentPaver.planGrade + SegmentPaver.paveSegment
```

`LoadedChunkRoadBackfill:159` 是**唯一**调用点（全仓 grep 确认），
所以只要这条链活着，下面所有职责都活着。

---

## 二、10 个职责的活链核对

| # | 职责 | 触发点 | 链路 | 状态 |
|---|---|---|---|---|
| 1 | **桥体（模板）** | `PathFeature:515` | `BridgeSegmentPlannerNew.processSegment` → `BridgeTemplateStructureRegistry` | ✅ 活 |
| 2 | **桥体（程序化兜底）** | `PathFeature:522` | `BridgeSegmentPlanner.processSegment` → `BridgeBuilder.placeSegment` | ✅ 活（降级路径） |
| 3 | **桥墩** | `BridgeBuilder:165` | `BridgePierPolicy.isEnabled(cfg.bridgePierMaxHeight())` | ✅ 活 |
| 4 | **桥材调色板** | `BridgeBuilder:51/73/447` | `BridgeMaterialPalette` | ✅ 活 |
| 5 | **隧道建造** | `PathFeature:532` | `RoadTunnelBuilder.processSegment` → `PlannedSectionBuilder` / `PortalSafetyRules` / `ScenicDomeBuilder` | ✅ 活 |
| 6 | **涵洞** | `Road:408` + `PathFeature:531` else 链 | `CulvertPlanner.cross` → `RoadConstructionSafety.prepareCulvertDeck` | ✅ 活 |
| 7 | **浮标标记** | `PathFeature:430/433` | `BuoyMarkerPlanner` → `BuoyBuilder.placeBuoy`（`:506`） | ✅ 活 |
| 8 | **桥共享吸附** | `ExistingRoadJunctionService:162` | `BridgeShareService.applyInPlace` → `BridgeShareDetector.mayOverlap`(`:110`) + `BridgeShareAbsorber.blendToDeckY`(`:134`) | ✅ 活 |
| 9 | **施工量规划** | `FinalRoadStructureValidator` / `ModConfig` | `RoadConstructionVolumePlanner` | ✅ 活 |
| 10 | **路面铺装** | `PathFeature:565/568`、`:582/585` | `SegmentPaver.planGrade` → `paveSegment` → `placeOnSurface` → `RoadBlockPlacer` | ✅ 活 |
| 11 | **桥范围裁决** | `PathFeature:412` | `BridgeRangeCalculator` + `RoadConstructionSafety.promoteRuntimeBridges`(`:424`) | ✅ 活 |
| 12 | **巨树预留** | `PathFeature` / `ApiBackendImpl` / `CacheManager` | `HugeTreeReservation` | ✅ 活 |
| 13 | **序列化帧** | `PathFeature` / `RoadFingerprint` | `BuildFrame` | ✅ 活 |
| 14 | **施工安全门** | `Road:408` 前后 | `RoadConstructionSafety`（含 `promoteRuntimeBridges` / `isShallowCulvertCrossing` / `prepareCulvertDeck`） | ✅ 活 |

**方法**：对 `pathlogic/bridge`（14 类）、`pathlogic/tunnel`（8 类）、
`pathlogic/construction`（4 类）逐个 grep「生产引用者」，再用调用点回溯到
`PathFeature` / `Road` / `ExistingRoadJunctionService` 这些已确认活的入口。
**没有发现任何「只被测试引用」或「只被自身引用」的类。**

---

## 三、旧包有没有被削弱（铁律①的核对）

拿旧仓库 oracle 的符号与当前实现对照，批 3 范围 62 项里
**61 个真符号全部存在**（另 1 项 `f18492b` 是 commit hash 误提取，
已在 `FEATURE-PARITY-CHECK.md` 更正）。
`BridgeSegmentPlannerNew` 与 `CulvertPlanner` 的**新增**能力（模板桥 / 涵洞规划）
也不是旧版没有的替代品，而是在旧能力之上叠加的。

⇒ **「旧功能没丢」这一条在批 3 范围内成立。**

---

## 四、★ 真正的问题：批 3 的「换实现」只做了一半

### 4.1 规模对比（`wc -l` 实测）

| 包 | 行数 | 性质 |
|---|---|---|
| `pathlogic/bridge`（旧） | **2,831** | 桥体建造、桥墩、桥材、共享吸附、涵洞规划、浮标 —— **全部在实际写方块** |
| `pathlogic/tunnel`（旧） | **2,056** | 隧道建造、洞口安全、景观穹顶 —— **同样在实际写方块** |
| `pathlogic/construction`（新） | **1,426** | 只有 `SegmentPaver` 写方块；其余三个是 `BuildFrame`（序列化帧）、`HugeTreeReservation`（预留）、`RoadConstructionVolumePlanner`（施工量估算） |

### 4.2 这意味着什么

台账（`REFACTOR.md` 的 `LEDGER|3`）写的是 `pathlogic/bridge → pathlogic/construction`。
按「新包应承载旧职责」的读法，批 3 应当把桥体/隧道建造搬进 `pathlogic/construction`，
**但实际上没搬** —— 新包里没有桥、没有隧道、没有桥墩。

`BridgeSegmentPlannerNew` 名字里带 `New`，但它住在**旧包** `pathlogic/bridge` 里，
而且它是「沿长跨水段铺**内置桥模板**」的模板放置器，
**不是**对 `BridgeSegmentPlanner` + `BridgeBuilder` 那 720 行的替代 ——
模板放不成时仍然回落到程序化建造（`PathFeature:522`）。

### 4.3 所以批 3 的真实状态

| 维度 | 状态 |
|---|---|
| 符号保真 | ✅ 61/61 |
| 职责生效 | ✅ 14/14 全在活链路上 |
| 旧能力未削弱 | ✅ 成立 |
| **新实现取代旧实现** | ❌ **没有**。桥体/隧道建造仍跑旧包 4,887 行 |
| 真机验收 | ❌ 未做 |

⇒ **批 3 不能标 DONE。** 它与批 2 不同：批 2 是「新内核已接管、差真机验证」，
批 3 是「**新内核根本没接管**，旧实现还是主力」。

---

## 五、要把批 3 真正做完，需要什么

按依赖顺序（不能跳步）：

1. **先立桥隧的中间表示**（与批 1 的 `RoadSpec` 同思路）：
   现在桥的「范围 / 甲板高 / 模板选择 / 墩位」分散在
   `BridgeRangeCalculator`（裁决）、`BridgeSegmentPlannerNew`（模板）、
   `BridgeSegmentPlanner` + `BridgeBuilder`（程序化）、
   `RoadConstructionVolumePlanner`（估算）**四处**。

   > **★ 2026-11-15 更正（我这段原来写错了，把历史结论当成了现状）**：
   > 原文接着写「每处各有一套 Y 口径 —— 这正是旧版『先算再修』病根在新代码里的复现」。
   > **实测核对后这句不成立**，甲板 Y **早已统一到 profile Y**：
   >
   > | 路径 | 代码 | 最终甲板 Y |
   > |---|---|---|
   > | 程序化兜底 | `BridgeSegmentPlanner:80` `segDeckY = baseYForThis + 1` → `BridgeBuilder:85` `deckBlockY = deckSurfaceY - 1` | = `baseYForThis` ✅ |
   > | 内置模板 | `BridgeTemplateStructureRegistry` 用 `heightOffset + 2`，与 `PathFeature` 传的 `baseYForThis`（未 +1）配套 | = profile Y ✅ |
   > | 体积估算 | `RoadConstructionVolumePlanner` `deckBlockY = profile[index]` | = profile Y ✅ |
   >
   > 那次统一就是历史上修掉的「曲线 +2 / 直线 +0 差两格、且都跟路面差一格」问题。
   > 我上一轮看到 `RoadConstructionVolumePlanner` 里**两段互相矛盾的注释**
   > （一段说「甲板比 profile 高一格」、一段说「甲板就在 profile 上」）就下了「各有一套口径」
   > 的判断 —— **那是那段旧注释的残骸误导了我**，实际代码是同源的。
   >
   > **已做的事**：把那两段矛盾注释合并成一段权威说明 +
   > 新增 `BridgeDeckYConsistencyTest`（5 个用例，负控删掉 `+ 1` 立刻红）。
   >
   > **所以第 1 步的真实内容变了**：不是「统一四处的 Y 口径」（已统一），
   > 而是「把这四处的位置信息收敛到一个结构体里，让『谁算的甲板高』只有一个答案」，
   > 属**结构性整理**而非修 bug —— 优先级因此下降。
2. **把两个 Planner 合成一个**：模板与程序化应当是同一策略的两种实现分支，
   而不是「先试模板、失败再回落」的两条独立代码路径。
3. **搬进 `pathlogic/construction` 并删旧包**。
   注：原文写「同时把 `BridgeBuilder` 里与施工量估算重叠的部分合并（现在两边都在算甲板 Y）」
   —— 「重叠」是真的（两边都要知道甲板 Y），但**算出来的值一致**（见第 1 条更正），
   所以这不是 bug 而是「同一个事实被两个模块各自知道」，属**耦合适度问题**，不是错误。
4. **真机验收**：桥形态、墩位、材料、浮标位置都要看。

**风险提示**：这一步会动世界生成结果，**必须真机**。
在当前「暂不上实机」的约定下，**能做的最多到第 1、2 步的设计与纯逻辑重构**，
第 3、4 步必须等真机阶段。

---

## 六、这份核对**没有**覆盖什么

- **只核了静态调用链**，没有跑真机，无法证明「建造出来的桥是对的」。
- **没有核 `worldgen/road/*` 那条并发生成路径**（`RoadChunkPlanCompiler` 等），
  它与 `PathFeature` 是两条不同的落地路径，本文只核了后者。
- **没有核装饰层**（`decoration/*`）与施工的交互。
- 行号会随改动漂移，引用前请重新 grep 函数名而不是照着行号找。