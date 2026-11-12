# roadweaver-next —— 重构版

**起点：`roadweaver` 仓 `6c0ec6b`（道路 `0.9.098` / SHA `60B9A6C7`）的完整副本。**
旧仓已冻结为 **oracle（验收基准）**，见旧仓 `FREEZE.md`。

## 为什么要新仓

旧仓 109,275 行里，包语义边界已经崩了，修修补补到不了根治：

- `pathlogic`（41 文件 / 20,428 行）同时装了数据模型、选线、施工、纵断面、路网合并、校验、度量 —— **6 个不相干职责**
- `pathfinding`（91 文件）里真正的寻路算法只有 ~800 行（5%），其余是采样 / GPU / 跨度提取
- `postprocess` 4 文件 4,794 行，`RoadSnapService` 单类 3,486 行
- `StructureAvoidanceService` 单类 **4,766 行 / 160 个方法**
- 六个包名 `pathlogic / pathfinding / planning / postprocess / route / search` 都在说「路径/规划」，谁也分不清

**根本病因：A\* 同时背了「拓扑」和「几何」两个职责。** 几何只能靠事后 8 个阶段去修
（DP 简化 → Catmull-Rom → 1-2-1 平滑 → relax → 锯齿检测 → 急弯冻结 → 窗口 splice → 纵断面修复），
每修一次就多一个 artifact。

## 目标

| 目标 | 现状 | 目标 |
|---|---|---|
| 每条路的几何 | `dupXz` 总量 45、`final/直线` 2.93× | `dupXz` ≤5、`final/直线` ≤1.5× |
| 规划总时长 | ~22~25 分钟（关长城后 ~9~12）| **≤6 分钟** |
| 配置 | 241 键，UI 只暴露 45 个，11 个死键 | 241 键全可见（按 Tier 分级），死键灰显 |
| 架构 | 6 个包语义重叠、2 个上帝类 | 16 个单一职责包 |

**核心思路：职责分离。** A\* 只出拓扑，几何交给弹性带变分求解。

## 分档（不丢功能的依据）

| 档 | 行数 | 处置 |
|---|---|---|
| A 原样搬 | 3,584 | 跨 mod 三件套（刚真机验过，冻结）|
| B 结构重构 | ≈40,000 | 拆上帝类、换数据结构、消重复（行为不变）|
| C 真优化 | ≈18,000 | 空间索引 / 前缀和 / 距离变换 / 批处理（可证明等价）|
| 重写 | ≈9,000 | `band/` 弹性带 + `topology/` 拓扑层 |
| 删除 | ≈15,000 | 旧几何后处理，被新内核替代 |
| 保留不碰 | ≈24,000 | 开局预热进度 / 施工铺块 / 装饰 / worldgen 落块 |

## 必须保留的功能（一行不改）

- **开局预热动态进度**：`InitialGenerationProgressTracker`(825) + `Snapshot`(142) +
  `LoadingGenerationOverlayRenderer`(585) + `TrackerTest`(135) —— 已经是「状态 → 快照 → 渲染」三件套，有单测
- **桥梁 / 隧道建造**：`RoadTunnelBuilder` / `BridgeSegment` / `SegmentPaver` / `RoadConstructionVolumePlanner`
- **树木抑制**：`GenericVegetationFeatureMixin`（`@Mixin(ConfiguredFeature.class)`）+ `RoadSpatialIndex`
- **村庄端点筛选**：`StructureAvoidanceService` 的 `route filtered` 逻辑 —— 它在选线**之前**，几何重构碰不到
- **结构/建筑避让判据**：`StructureAvoidanceService` 的判定规则（用户已拍板「村庄裁切包围盒保留原判据」）
- **装饰**：路灯 / 栏杆 / 海晶灯
- **跨 mod 契约**：`api` / `apiimpl` / `compat` / `runtime`

## 里程碑

| # | 内容 | 验收 |
|---|---|---|
| **M0** | 本副本 + 干净 git + 构建通过 → **证明与 0.9.098 逐项一致** | 对照表全绿 |
| **M1** | 配置元数据表（241 键）| **241/241 键往返全等** |
| **M2** | UI 状态/渲染分离 + 配置页自动生成（保留预热进度）| 逐页手动验收 |
| **M3** | `StructureAvoidanceService` 拆分（160 方法 → 4~5 类）| 回归全绿 + 行为不变 |
| **M4** | 结构查询换空间索引 | `O(n·m) → O(n log m)`，结果一致 |
| **M5** | `band/` 弹性带内核（替代 DP/样条/relax/平滑/锯齿修复）| `dupXz` 45→≤10 |
| **M6** | `topology/` 拓扑层（替代 merge/braid/parallel/fine regenerate）| `dupXz` ≤5，总时长 ≤6 分钟 |
| **M7** | 收尾、删死代码、多局真机 | 全套指标 |

## 与原仓库的关系

```
roadweaver       冻结（2c6547a / tag roadweaver-frozen-0.9.098）→ 只修 bug，做 oracle
      │
      └──► roadweaver-next   全部新工作在这里
```

---

# 重制替换台账（机器受检）

**这份台账由 `LegacyLedgerTest` 逐行解析并校验**，不是给人看的装饰。

## 铁律（用户 2026-11-13 拍板）

> **先重制，后删除。** 重制期间旧实现原地保留、打标记、进台账；全部域做完后再照台账逐条核实删除。

**原因**：重制期间旧代码是承重的（`BandPathfinder` 出完中心线，高度/桥隧/施工全靠它们）。
边重制边删 ⇒ 中间任何一域没做完，整个 mod 就不可运行，出问题也没有可对照的基线。

## 删除判定（4 条全满足才许删）

| # | 条件 | 怎么核 |
|---|---|---|
| 1 | 该域新实现有独立测试且全绿 | 测试报告 |
| 2 | 旧类**零外部引用** | **全模块**扫描（common + fabric + api + api-fabric）+ JSON |
| 3 | 无运行时反射 / JSON 依赖 | 类名 grep + `mixins.json` / `fabric.mod.json` |
| 4 | 删完回归仍全绿 | 跑一遍套件 |

> 第 2 条的「全模块」是踩坑换来的：`StructureKeys` 被 common 里 11 处引用、
> `RoadBeardifierAccess` 只在 `fabric` 模块的 mixin 里被引用 —— 只看 common 会误判成死代码。

## 台账（改这里必须同时改代码，否则测试失败）

<!-- LEDGER:BEGIN -->
LEDGER|1|纵断面与坡度|pathlogic/grade|profile|TODO|3543
LEDGER|2|桥隧判定|pathlogic/tunnel|span|TODO|2918
LEDGER|3|桥体与施工|pathlogic/bridge|construction|TODO|4085
LEDGER|4|路口与合并|pathlogic/junction|roadnet|TODO|1877
LEDGER|5|几何后处理|postprocess|geometry|TODO|5615
LEDGER|6|结构避让|pathlogic/structures|avoidance|TODO|6291
LEDGER|7|验证与收尾|pathlogic/validation|validate|TODO|1594
<!-- LEDGER:END -->

字段：`批号|域|旧实现包(相对 net.shiroha233.roadweaver)|新实现包|状态|旧代码行数`

> 新实现包名刻意避开仓库里已有包的语义，否则「新实现包已存在」的判断会失真：
> · 路口/合并叫 `roadnet` 而不是 `network`（`network` 已是模组网络层）
> · 几何后处理叫 `geometry` 而不是 `band`（`band` 已是弹性带内核，是它的依赖不是替代物）
>
> 这两条都是 `LegacyLedgerTest` 上线当天抓到的真问题 —— 守卫第一次跑就逮到两个重名。

状态取值：`TODO` 未开始 / `IN_PROGRESS` 重制中 / `DONE` 已完成（此时新实现包必须存在，且旧实现包内每个文件必须带 `待删（批N）` 标记）。

## 旧实现的标记规范

每个待删旧类的**文件职责头**写成：

```java
/* 文件职责：<做什么> —— 旧实现。
 * ⚠ 已被 <新实现> 取代，待删（批N）。
 *   删除条件：<测试名> 全绿 + 本类零外部引用 + 回归全绿。
 */
@Deprecated(forRemoval = true)
```

拉取当前全部待删文件：

```
rg "待删（批" common/src/main/java
```

## 单文件体积约束（口径已改）

`ArchitectureGuardTest` 的行数上限按**有效代码行**统计 —— **注释与空行不计**（用户 2026-11-13 要求）。
所以「写完整中文注释」和「守住文件体积」不再互相打架。

---

# 现状体检与守卫缺口（2026-11-13）

详细证据见 **`docs/AUDIT-20261113.md`**（三路并行代码审查 + 主线交叉实测）。

## 一句话结论

**当前最大风险不是「算法旧」，而是「没有权威中间表示 + 指标不进决策 + 静默降级」** —— 这三条不解决，
换任何新算法都会被旧的修补链吃掉。

## 六道守卫缺口（优先补，这是可维护性的抓手）

| # | 缺口 | 实测 |
|---|---|---|
| 1 | 分层规则只覆盖 5 个包 | 其余 22 包零规则 ⇒ 39 对双向依赖 |
| 2 | import 正则漏通配导入 | 420 处 / 116 文件隐形；来源前六全是重构抽出的 `pathlogic.structures.*` |
| 3 | 职责头基线陈旧 | 基线 172，实际 298 ⇒ ratchet 失效 |
| 4 | 无「只算一次」不变量 | `computeTargetY` 调 6 次、`extractSpans` 14 次 |
| 5 | 无死代码检测 | `topology`+`pipeline` 传递性死代码、`FinslerMetric.cost()` 零调用 |
| 6 | 无可维护性指标 | 参数表 / 分支密度 / 重复实现均无度量 |

## 重制顺序（依赖驱动，非按域平铺）

`S1 立 RoadSpec` → `S2 判定/施工解耦` → `S3 纵断面全局 DP` → `S4 桥隧并入同一 DAG`
→ `S5 后处理降级为缝合器` → `S6 避让 + UI 接线` → `S7 删旧 + 补守卫`

> **S1 必须先做**：不立中间表示就换算法 = 在流沙上盖楼。

---

# S1 进度：RoadSpec 已立（2026-11-13）

`spec/RoadSpec` 是「一条路的唯一权威表示」：中心线 + 剖面 + 区间，**且区间带站索引**。

## 它解决的两件事

1. **三套事实从此有一个校验点**：剖面长度必须等于站数、区间必须能唯一解析到站、
   区间之间不许重叠 —— 任何一条不满足就 `SpecViolationException`，带精确上下文。
2. **消灭「BlockPos 反查站索引」这一整类 bug**：`RoadSpan` 只存 BlockPos、没有站索引，
   任何需要知道「这段桥覆盖第几站」的代码都得反查，而路网合并会重建站序列 ⇒
   Y 漂一格整座桥被静默丢掉（0.9.036 的 `bridgeShareCandidates=0`）。
   现在构造时一次性解析，同 XZ 多候选直接失败而不是任选一个。

## 配套守卫 G4：SingleSourceOfTruthTest

统计重算热点的**触发文件数**（排除定义它的文件），只许减少不许新增。

> **口径演进（第一版是错的，记在这里免得再走回头路）**：
> 第一版数「源码里 `符号(` 出现的次数」，实测没有意义 —— 声明、同族重载委托、
> 以及收敛后新加的入口全都会被算进去。把两处重复调用收成一个入口时，
> 数字只降 1 甚至不降。改成「有多少个文件在触发它」之后语义才清晰：
> 直接对应「重算散落在几个模块里」。

| 热点 | 触发文件数 | 是谁 |
|---|---|---|
| `extractSpans(` 区间提取 | **2** | `Road` / `RoadPlanConsistencyService` |
| `computeTargetY(` 剖面计算 | **1** | `Road` |
| `refineTunnelSpans(` 隧道区间细化 | **2** | `Road` / `RoadPlanConsistencyService` |
| `repairFinalHeightConflict(` 高度冲突修复 | **1** | `Road` |

**S1 的静态验收（全链 ≤2 处）已达成。**

## 本轮在 Road.java 里收敛的两处重复形状

1. 两处 `extractSpans(segments, level, cache, terrain, streams, routeBridgeMinWaterDepth())`
   参数完全相同 ⇒ 收成 `extractPhysicalSpans(...)` 单一入口。
2. 「复制可变列表 → 算剖面 → 按剖面细化隧道区间」这段三连出现两次 ⇒
   收成 `settleProfile(...)` 单一入口。

**但要诚实说明**：收敛的是**代码形状**，不是计算次数 —— 各阶段的输入（`spans`）确实不同，
所以仍然各算一次。真正的「只算一次」要靠 **S4**：把剖面与跨度并进同一张 DP，
一次求解同时产出两者。S1 的贡献是「有权威表示 + 有唯一入口 + 有护栏盯着不倒退」。

---

# S2 计划：判定 / 施工解耦为纯 BuildPlan（2026-11-13 立）

## 实测：施工期在哪些地方「自己又判了一遍」

| 位置 | 触发频率 | 干什么 |
|---|---|---|
| `PathFeature:406` `BridgeRangeCalculator.compute(...)` | **每个区块一次** | 遍历整条中心线重算桥范围 |
| `PathFeature:407` `promoteRuntimeBridges(...)` | **每个区块一次** | 探世界、按运行期水位再成长桥范围 |
| `PathFeature` 里 `SegmentPaver.planGrade(...)` | **每个区块一次** | 重新插值坡度 + 压平横断面 |
| `RoadConstructionVolumePlanner:243/317` `BridgeRangeCalculator.compute(...)` | 每条路一次 | 体积规划与校验各自再算一遍 |

前三条是真正的热点：`O(区块数 × 中心线站数)`。一条 2000 站的路铺满 30×30 区块，
仅桥范围判定就要把 2000 个站遍历 **900 次**。

## 目标形态

1. **判定侧**（规划/校验期，每条路一次）输出不可变 `BuildPlan`：
   每列 `(x, z, targetY, kind, spanIndex, protectedMask)`，其中 `kind` ∈
   `SKIP / PAVE_SURFACE_ONLY / PAVE_NORMAL / BRIDGE_DECK / TUNNEL_BORE`。
2. **施工侧**（每区块）只做三件事：按 chunk 过滤 `BuildPlan` 里的列 → 放方块 → 记账。
   **不再调** `BridgeRangeCalculator.compute` / `promoteRuntimeBridges` / `planGrade`。

## 守卫基线（本轮已入 ratchet，只许减少）

| 热点 | 触发文件数 |
|---|---|
| `BridgeRangeCalculator.compute(` | 2（`PathFeature` / `RoadConstructionVolumePlanner`）|
| `promoteRuntimeBridges(` | 1（`PathFeature`）|
| `SegmentPaver.planGrade(` | 1（`PathFeature`）|

S2 做完时这三个数字应当全部变成 **0**（施工路径上不再出现），
判定侧则收进 `BuildPlanCompiler` 一处。

## 迁移顺序（每步回归必须全绿）

1. `spec/BuildPlan` + `spec/BuildPlanCompiler`：纯函数，输入 `RoadSpec` + 桥范围 + 保护掩码，
   输出每列计划。先只被测试用（正控 + 负控）。
2. `PathFeature` 改为「首次进入某条路时编译一次 BuildPlan 并缓存，后续区块读缓存」。
   缓存 key = 路指纹（已有 `roadFingerprint`）。
3. 把 `segmentPaver` 的列决策换成读 `BuildPlan.kind()`，`planGrade` 降级为
   「没有任何 BuildPlan 时的兜底」（旧存档路径）。
4. 最后删掉施工期的 `promoteRuntimeBridges` 调用（判定期已把运行期水位考虑进去）。

---

# S3 第一步：纵断面全局 DP 内核（2026-11-13）

`pathlogic/profile/ProfileDpSolver` —— 把整条路的剖面当成一个**有约束的最优化问题**，一次解出来。

## 与旧版 15 道 pass 的根本差别

旧：`平滑 → 夹坡度 → 修桥头 → 修隧道 → 再平滑 → 再夹 ……` 最后靠
`repairFinalHeightConflict` 补救 pass 之间的冲突。
**没有目标函数** ⇒ 「更好」无法定义 ⇒ pass 之间互相打架 ⇒ 只能再加一道 pass 调停。

新：

    minimize    Σ cost(i, y_i)                    每站放 y 的代价（挖填方）
    subject to  |y_{i+1} − y_i| ≤ maxStep          坡度上限
                lo_i ≤ y_i ≤ hi_i                  允许窗口
                y_i = pin_i（若钉住）               桥面 / 隧道 / 接缝锁定

站是时间维、高度是状态维、坡度是转移约束 ⇒ 标准最短路径 DAG。
单调队列把转移从 `O(H·d)` 压到 `O(H)`，整体 `O(n·H)`，**一次遍历得到全局最优**。

## 关键设计取舍

| 决定 | 理由 |
|---|---|
| 代价与约束分离 | 约束管「合法」、`CostModel` 管「偏好」；旧版把两者混在 pass 里 |
| 不可行时返回 `Optional.empty()` | 绝不返回一条「看着还行」的坏剖面 —— 旧版的静默降级正是问题源头 |
| 纯函数、不采样、不判桥隧 | 地形由调用方给成 cost+窗口；桥隧由 span 判定给成 pin。职责不交叉 |
| 平顺性不做软惩罚 | 它已经是硬约束 `maxStep`；再加软惩罚会造成双重口径 |

## 正确性证据（不是"看起来对"）

`ProfileDpSolverTest` 里有一条**与暴力枚举对拍**：小规模问题（站数 ≤6、窗口 ≤4、步长 ≤3）
用穷举求真最优，与 DP 解逐项比 `totalCost`。**300 组固定种子随机问题全部一致**，
且逐组校验 DP 解本身合法（不越窗、不超坡）。

> 这类验证是必须的：DP 的典型失败不是崩，而是<b>算出略微不是最优的解</b> ——
> 不崩、不报错、剖面看着也正常，只是每条路多挖两格土。人肉 review 抓不到。
> 单调队列的窗口边界与相等值弹出是最容易写错的地方，对拍能在写错当下就抓住。

## 现状与下一步

- 内核已就绪且被证明正确，**尚未接线**（当前只被测试使用）。
- 下一步：写 `ProfileDpSource`，把现有 `TargetYComputation` 的输入
  （地形采样、桥隧 pin、坡度上限）翻译成 `Problem`，然后
  **AB 双跑**：旧 15 道 pass 与新 DP 各算一条，比对「坡度分布 / 挖填方总量 / 桥头台阶数」，
  差异可解释后再切默认。

---

# S3 第二步：AB 双跑接通生产链（2026-11-13）

DP 不再只是测试里的东西 —— 它现在**跑在真实规划链上**，但只观察、不改行为。

## 开关

```
-Droadweaver.profileAb=on
```

默认关闭。关闭时 `ProfileAbObserver.enabled()` 一次布尔判断就返回，零开销。

## 三个新类

| 类 | 职责 | 为什么不放在 TargetYComputation 里 |
|---|---|---|
| `ProfileDpSource` | 把「地形 + 桥段」翻译成 DP 问题 | 翻译逻辑必须能脱离世界单测 |
| `ProfileMetrics` | 把剖面压成 4 个可比较数字 | AB 判断的依据必须可复核，不能靠肉眼看 |
| `ProfileAbObserver` | 双跑 + 记录，绝不改返回值 | 观察器出错绝不能影响主流程（catch Throwable）|

## ★ 关键约束：新解的输入里不许掺旧解

`ProfileDpSource` 处理桥段时只用**地形**信息 —— 把桥段钉成两端地形的高线内插，
而不是借用旧剖面在桥两端的锚点。

如果借用了旧解，新解就只是在复现旧解，AB 对比失去意义。这条写进了类 javadoc。

## 度量口径与它对应的真实抱怨

| 指标 | 对应的抱怨 |
|---|---|
| `p95Grade` 坡度 95 分位 | 「这条路太陡了」|
| `maxStep` 单站最大落差 | 「路面一格一格像楼梯」|
| `cut` / `fill` 挖填方总格数 | 「路像被切进山体 / 浮在空中」|
| `steps` 超 1 格的相邻落差处数 | 0.9.027「桥头逐列阶梯」|

`versus()` 只做胜负计数、**不做加权总分** —— 权重是产品决策，不该藏进代码里。

## 测试（9 条）

- 平地 → 解就是地面本身
- 剖面不得越出允许挖填窗口 + 坡度硬约束
- 桥段被钉成两端地形的直线（且首尾精确落在两端地形）
- 陡地形（每站抬 10 格）+ 窄窗口（±1）+ maxStep=1 ⇒ **必须报不可行**，不许硬凑
- 度量口径（fill 总和、maxStep、steps 计数）
- 桥段内的落差**不计**入「台阶」数
- 胜负计数四指标
- 观察器默认关闭时不做事
- 观察器开启后**只观察不改输入**（对输入做快照比对）

## 下一步

拿真实存档跑一批路线（`-Droadweaver.profileAb=on`），收集 `[PROFILE-AB]` 日志，
按「新解更差」的样本定位原因（大概率是桥段直线内插太朴素、或实验窗口参数不合适），
解释清楚差异后再决定是否切换默认。**在拿到数据之前不切。**

---

# S4：桥隧判定并进同一张 DP（2026-11-13）

`pathlogic/profile/ModeDpSolver` —— 状态从 `(站, 高度)` 扩成 `(站, 高度, 模式)`，
**一次求解同时决定**「每站多高」与「这段是地面 / 桥 / 隧道」。

## 要打破的死结

旧版两步走：先由 `PathSpanExtractor` 判桥判隧道（只看水和高度差），再让
`TargetYComputation` 在那些区间里凑剖面。必然出现两类打架：

1. **判定先给了桥，剖面却想往下切** —— 桥面被压进地里，或山坡被切成桥；
2. **剖面先压低了，判定才发现该是桥** —— 补一道 pass 去「提升成桥」，
   而那道 pass 又改高度，前面的坡度约束全部失效。

根因不是哪一步判错，而是**两个决策互相依赖却分两次做**：
桥/隧道是否划算取决于那个高度要挖多少土，而高度又取决于这段是不是桥。
这个循环只能靠联合求解打破。

## 模型

```
状态   (站 i, 高度 y, 模式 m)     m ∈ {GROUND, BRIDGE, TUNNEL}
转移   |y_{i+1} − y_i| ≤ maxStep                      坡度上限（与旧版同口径）
       模式：GROUND ↔ BRIDGE、GROUND ↔ TUNNEL 允许
             BRIDGE ↔ TUNNEL 禁止（必须先落地，否则是悬空洞口）
代价   每站：GROUND 按挖填方；BRIDGE/TUNNEL 按每站单价
       每进入一次桥/隧道：一次性建造费（桥台 / 洞口）
约束   地面：terrain − maxCut ≤ y ≤ terrain + maxFill，且该站 groundBanned 时必须为 false
       桥  ：terrain ≤ y ≤ terrain + maxBridgeHeight
       隧道：terrain − maxTunnelDepth ≤ y ≤ terrain − minTunnelCover
```

复杂度 `O(n · H · 9)`：三个模式各一条单调队列，模式间只有 9 种组合。

## ★ 测试逼出来的两个建模发现

### 发现一：必须加 `groundBanned`，否则 DP 永不选桥也不选隧道

第一版没有这个维度，写完测试才发现 DP 永远走地面。原因很朴素：
**「跟着地形走」在纯挖填模型里是零成本的** —— 地形下降剖面跟着下、上升跟着上，
既然不要钱，凭什么架桥？

桥和隧道被需要，不是因为它"更省土方"，而是因为**地面本身不可用**：
水面（不能在水里铺路）、保护区/遗产地（不许开挖）。这些是**物理/法规约束**，
不是成本偏好，所以必须表达成掩码而不是单价。加上之后水面和保护区场景立刻正确。

### 发现二：「保护区里必须打隧道」这条断言是错的

第一次写这条测试就失败了：DP 选了 `bridge=3`（造价 7.50）而不是 `tunnel=3`（17.50）。
**DP 没算错** —— 在那组单价下，「高架桥跨过山脊」确实比「打洞」便宜。

是我的断言把「保护区 ⇒ 隧道」当成了规则，其实它只是**某组单价下的结果**。
所以拆成两条各自成立的测试：

- `保护区内禁用地面时不许走地面` —— 只钉真正的不变量（禁用段不走地面 + 必须有跨越方式）；
- `打洞比架桥贵时会选桥而反过来会选隧道` —— 用两组极端单价证明**价格真的驱动决策**，
  这才是联合求解的意义所在。

## 正确性证据

`ModeDpSolverTest` 的对拍与 `ProfileDpSolverTest` 口径不同：状态多了模式一维，
暴力枚举的是 **(高度, 模式) 序列**，所以压到 n ≤ 5。更关键的是
**暴力解独立重写了 allowed / transitionAllowed / perStationCost / entryCost 的语义**
（不复用被测代码），两条实现互相对照才有意义。

- 200 组固定种子随机问题（含约 25% 随机 `groundBanned`）逐组比对 `totalCost`；
- 逐组校验解自洽：高度在对应模式窗口内、坡度合法、模式转移合法。

## 现状

纯内核，只被测试使用。接线时的定位是**候选解生成器**：
旧判定给出硬约束（已有公路必须桥、河面必须桥、保护区不许挖）作为**模式约束**钉进来，
剩下的自由段交给 DP 自己权衡桥 / 隧道 / 挖填。

---

# S4 第二步：模式 AB 也接进真实规划链（2026-11-13）

`ProfileAbObserver.observeModes(...)` 在真实路线上跑联合 DP，并并排记录决策数：

```
[PROFILE-AB-MODE] #1 stations=812 tag=(0,0)->(1200,0)
    legacy[bannedGround=41 tunnel=3]
    dp[bridge=12(2座) tunnel=1(1条) cost=1843.2]
```

## ★ 它喂进去的是「物理约束」，不是「旧版的决策」

- **喂**：`groundBanned` = 旧版判出的「这里不能铺地面」（水面 / 已有公路 / 保护区）；
- **不喂**：旧版判出的桥/隧道选择（只作为对比展示）。

这样一来问题变成：**在同样的物理约束下，联合求解认为需要几座桥、几条隧道？**
如果旧版判了 3 座桥而联合求解认为 2 座就够，那 1 座就是「判定与剖面互相打架」的产物。

如果连桥/隧道选择也喂进去，DP 只会复现旧决策 —— 这是最容易犯的错，写在 javadoc 里。

## 单价是实验值（已在代码注释声明）

| 参数 | 实验值 | 含义 |
|---|---|---|
| `bridgePerStation` | 5.0 | 桥每站 |
| `tunnelPerStation` | 14.0 | 隧道每站（明显更贵，符合直觉：能架桥就架桥）|
| `abutmentCost` | 3.0 | 每进一次桥 |
| `portalCost` | 6.0 | 每进一次洞 |
| 地面窗口 | 挖 24 / 填 12 | 故意放宽，避免陡地形不可行掩盖一切 |

## 测试

- 模式观察器默认关闭时不做事；
- 开启后只观察不改输入（对 terrain / legacy 做快照比对）。

回归 PASS tests=649。

---

# S5：后处理降级为网络缝合器 —— 先分类（2026-11-13）

## ★ 先澄清一个命名误解

任务里写「后处理降级为网络缝合器」，但 `postprocess` 包里装的**不是「修路」**，
而是 **raw path → 站点 → 栅格化** 的几何流水线（`PathPostProcessor.process(...)`，
每个寻路器都调它）。这条流水线不存在「降级」的余地 —— 没有它就没有站序列。

真正在做「修路」的是这三处：

| 位置 | 干什么 |
|---|---|
| `pathlogic/grade/RoadGradePlanner` | 15 道剖面 pass |
| `pathlogic/grade/GradeOptimizer` + `SlopeContinuityEnforcer` | 局部优化与坡率强制 |
| `planning/terrain/AccurateGradeRepairPlanner` | 精确高度修复，必要时整条改线 |
| `pathlogic/pathcalc/RoadPathCalculator.repairFinalHeightConflict` | 跑完之后补救各 pass 的冲突 |

所以 **S5 的真实内容** = 把上面这些「事后修」降级/移除，让 `RoadSpec` + 全局 DP 一次算对；
`postprocess` 里只保留与**网络缝合**有关的部分。

## 台账（`docs/S5-POSTPROCESS-LEDGER.md`，机器校验）

| 类别 | 数量 | 含义 |
|---|---|---|
| `KEEP_SEAM` | 10 | 网络缝合：路口 / 端点引道 / 吸附 / 复用。**永远保留** |
| `KEEP_INPUT` | 5 | 给判定提供输入：地形采样、保护判定、几何工具。**保留** |
| `REPLACE_BY_DP` | 3 | `RoadGradePlanner` / `GradeOptimizer` / `SlopeContinuityEnforcer` |
| `DELETE_LATER` | 2 | `AccurateGradeRepairPlanner` / `RoadPathCalculator` 的补救入口 |

## 三条机器校验（`PostprocessLedgerTest`）

1. **覆盖面**：受管目录下每个 `.java` 必须在台账里出现**恰好一次**（新增文件忘登记会红）；
2. **存在性**：台账每行的文件必须真实存在 —— **删了文件就必须删台账行**；
3. **词汇表封闭**：类别只能是四选一，且 `REPLACE_BY_DP` **必须写明由谁取代**
   ——「将来再说」这种没有信息量的话过不了检。

> 第 3 条的理由：写清「被谁取代」才让人能判断那件事到底做完了没有。
> 这是把「可维护性」落到可执行层面的最小手段。

## 为什么现在不删

直接删 `REPLACE_BY_DP` / `DELETE_LATER` 会立刻改变世界生成结果，而新的全局 DP
还没切默认（`roadweaver.profileAb` 还在收集数据）。顺序必须是：
**先分类 → 等数据 → 差异可解释 → 逐块删**。

回归 PASS tests=650。

---

# S6：UI 功能保全守卫（2026-11-13）

用户对这次重制最硬的一条要求是「**原有的功能不能丢**」。这句话没法靠人记 ——
重构 UI 时删掉一个 dock 按钮、改掉一个键位名、漏掉一个语言键，游戏里表现都很轻微
（少个按钮、按钮无提示），但**确实是功能丢失**。

所以把功能清单写成断言。**清单来自旧版实测，不是从当前代码反推** ——
反推的话，删了功能清单也跟着缩，守卫永远绿。

## 六条断言（`UiFeatureGuardTest`）

| # | 钉什么 | 失败意味着 |
|---|---|---|
| 1 | Dock 恰好 9 个动作，名字精确匹配 | 删/改名就是丢功能 |
| 2 | `switch (action)` 每个动作都有分支 | 按钮点了没反应 |
| 3 | 手绘路线恰好 15 个键位 | 键位丢了 |
| 4 | 9 个 dock 文案 + 15 个键位在 **en_us 与 zh_cn 都齐** | 界面显示原始键名 |
| 5 | 四个 UI 区的文件数不低于下限 | map 56 / editor 12 / config 12 / loading 3 |
| 6 | 配置界面仍可达（common 接口 + fabric 实现且 ≥1500 行） | 实现从 1791 行缩水 |

**首次运行六条全绿** —— 这是「当前功能确实一个没丢」的证据，不是声明。

## 为什么全部用源码扫描而不是反射

这些类（`KeyMapping`、`GuiGraphics`）一加载就要 Minecraft 的注册表，纯 JUnit 里直接炸。
源码扫描虽然土，但**恒真、零依赖、能定位到文件** —— 对这个场景是正确工具。

## 顺带确认的现状（不需要改）

- `MapDockRenderer` 已经是数据驱动的：`ACTIONS` 遍历渲染，`RoadMapScreen` 只负责算
  `activeActions`/`disabledActions`。这块**不需要"外置"**，它本来就没耦合。
- 真正的"接线"是 `RoadMapScreen:540` 的 `switch (action)`，已被第 2 条断言锁死。

## 关于「UI 抽取独立」

四个 UI 区的文件分布已经比较干净（map 56 / editor 12 / config 12 / loading 3）。
唯一的硬骨头是 `fabric/ConfigScreenFactoryImpl.java` **1791 行**，占 fabric 模块的 41%，
且**在 common 里完全测不到**（它是 fabric 侧实现）。这才是真正该拆的地方，
但它属于「拆分 + 迁移」工作量，不适合在本轮和守卫一起做 ——
守卫已经先把它锁成「不低于 1500 行」，拆的时候不会顺手丢功能。

回归 PASS tests=656。

---

# S2 第一步：把「每区块重算桥范围」改成「每条路算一次」（2026-11-13）

## 问题

`PathFeature` 是区块生成入口，**每个区块**都跑：

```java
middlePositions     = segments.map(middlePos).toList()      // O(站数)
plannedBridgeResult = BridgeRangeCalculator.compute(...)    // O(站数)，扫全条路
```

而这两个结果**对同一条路永远一样** —— 输入是 `data.spans()` 与配置，路一旦落盘就不再变。
于是 `O(区块数 × 站数)`：一条 2000 站的路铺满 30×30 区块，桥范围判定要扫 900 次。

## 做法：`pathlogic/construction/BuildFrame`

按 **内容指纹 + 配置** 缓存「中心线 + 规划期桥范围」，每条路只算一次。

正确性依据三条：

1. `BridgeRangeCalculator.compute` 是**纯函数**（不读世界、不写参数、无随机）；
2. 缓存 key = `RoadShardStorage.computeFingerprint(data)` —— **内容指纹**，
   不是实例身份；内容变了指纹就变；
3. `RoadData` 是 record 且紧凑构造器做了 `List.copyOf`，**不存在同一条路被就地改写**。

## ★ 最关键的细节：必须深拷贝出去

`RangeResult` 里装的是 `boolean[]` 与 `List<int[]>` —— **可变数组**。
区块生成会按运行期水位就地改这些掩码。

如果把缓存里的同一份数组发出去，第二个区块就会读到被第一个区块改过的掩码 ——
这种 bug 只在「同一片区域生成顺序特殊」时出现，**几乎不可能靠试玩复现**。

所以 `copyForUser()` 每次都深拷贝。拷贝量每区块 2n+b 个基本类型，
相对重算可以忽略，换来「共享的是不可变值」这条硬保证。
`BuildFrameTest` 里有专门一条测试：**改副本不影响缓存**。

## 缓存策略

上限 512 条路，超了整表清空。**不做 LRU** —— 清空 O(1) 且不会出现
「刚淘汰立刻又要用」的抖动放大；区块生成的访问模式本来就是专注一片区域。

## 验收怎么钉住（G4 补一条）

原来的「触发文件数」口径**看不见**这次改进：把调用挪进缓存类之后文件数甚至不变
（PathFeature 不调了，BuildFrame 开始调）。

所以新增一条独立断言 **`施工热路径不许直接重算全路`**：

- `PathFeature.java` 里**不得**出现 `BridgeRangeCalculator.compute(`；
- 同时要求 `BuildFrame.java` 里**必须**出现它 —— 防止「删了调用、功能也没了」这种假修复。

回归 PASS tests=661。

---

# depth-D 注释：第一层已完成，后三层的取舍（2026-11-13）

用户选的 D 档包含四项。现状：

| 层 | 规模 | 已覆盖 | 状态 |
|---|---|---|---|
| 文件头「文件职责」 | 595 | **595（100%）** | ✅ 完成，已锁成硬要求 |
| public 方法说明 | **3,338** | 906（27%） | 🔄 ratchet 906 |
| private 方法注释 | **1,549** | 271（17%） | 🔄 ratchet 271 |
| public 类型 javadoc | 82（嵌套）/ 约 590（含顶层） | 31（38%） | 未单独设线 |

## ★ 必须说清的取舍

后三层合计 **4,887 个元素**。按前几轮的实际速度（大文件深写 ~13 个/轮），
全部做完需要 **几十轮**，而 goal 只剩 20 出头，且 S2/S7 的收尾更贴近「重制版完全体」的目标。

更关键的是**质量风险**：机械地给 3,338 个 public 方法各加一行注释，
大概率产出「Returns the value of x」这类零信息量的话 —— 那正是用户明确反感的。
有信息量的注释必须知道「为什么这么写、踩过什么坑」，一件慢活。

## 所以本轮做的是

1. **量化**并写进每轮报告（`MaintainabilityReport` 现在会打印
   `public 906/3338，private 271/1549`）；
2. 锁成 **ratchet**（只许升不许降），保证不会有人边改边删注释；
3. 把取舍写在这里，**由用户决定**要不要把剩余轮次投进这 4,887 个元素。

## 如果用户要求继续

建议的优先级（信息量/工作量比最高）：
1. **新架构包的 public 方法**（`spec` / `pathlogic/profile` / `pipeline`）——
   这些是 Reviewer 必须读懂的入口；
2. **`api` / `apiimpl` 的对外契约** —— 别的模组依赖它们，文档就是接口；
3. 其余按包规模递减。

不建议按「文件顺序」或「字母序」推进 —— 那样前几轮就耗在无关紧要的 getter 上。

---

# AB 数据怎么收集、怎么读（2026-11-13）

## 收集（一次游戏会话就够）

```
-Droadweaver.profileAb=on
```

普通玩法即可，规划一批路线（开局预热就会跑几十条）。每 32 条会打一次总结：

```
[PROFILE-AB-SUMMARY] 总计=64 dpWin=41 legacyWin=15 tie=8 不可行=0 失败=0 → 新解占优，可考虑切默认
```

逐条明细（前 8 条 + 每 16 条一条）：

```
[PROFILE-AB] #17 stations=812 tag=(0,0)->(1200,0)
    legacy[p95=1.42 maxStep=3 cut=812 fill=190 steps=7]
    dp[p95=1.00 maxStep=1 cut=640 fill=155 steps=0] win=4 lose=0
```

模式决策对比（桥隧）：

```
[PROFILE-AB-MODE] #9 stations=812 tag=(0,0)->(1200,0)
    legacy[bannedGround=41 tunnel=3] dp[bridge=12(2座) tunnel=1(1条) cost=1843.2]
```

## 怎么读

| 信号 | 含义 | 该做什么 |
|---|---|---|
| `dpWin` 明显多于 `legacyWin` | 新解在坡度/台阶/土方上普遍更好 | 可以准备切默认 |
| `legacyWin` 更多 | 新解有系统性缺陷 | 看 `worstCases()`（最多 8 条最差样本），定位是哪一类路 |
| `不可行` 很多 | DP 的窗口/坡度参数太紧 | 调 `PROFILE_AB_MAX_CUT/FILL` 或 maxStep 口径 |
| `dp[bridge=N]` 远少于 `legacy[bannedGround=M]` | 旧判定判多的桥可能并不必要 | 这是 S4 的收益上限 |

## `summarize()` / `worstCases()`

`ProfileAbObserver.summarize()` 返回一句话结论（含 verdict）；
`worstCases()` 返回新解输得最明显的若干条，带完整的 legacy/dp 度量 —— 
**这是回答「为什么输」的入口**，不用去翻散日志。

`resetStats()` 用于重新开始一轮统计（测试也用）。

回归 PASS tests=662。

---

# ★ 2026-11-13 重大发现：fabric 模块一直编译不过，验收口径有洞

## 现象

做配置界面拆分时顺手编译 fabric，报错：

```
ServerPlanningHooks.java:124: 找不到符号  RoadGenerationService.tick(level)
ServerPlanningHooks.java:160: 找不到符号  RoadPlanningService.resetAll()
```

## 根因

这两个方法在早前重构中被**搬到了别的类**：

| 老位置 | 新位置 |
|---|---|
| `RoadGenerationService.tick(ServerLevel)` | `RoadTaskExecutor.tick(ServerLevel)`（逐行一致，纯搬家）|
| `RoadPlanningService.resetAll()` | `PlanningBoundsOps.resetAll()`（逐行一致，纯搬家）|

搬家本身是对的，**但调用方 `ServerPlanningHooks`（fabric 侧）没跟着改**。

## ★ 为什么一直没被发现

`:common:roadJUnitRegressionSuite` **只编译 common 模块**。fabric 的编译错误它一个都看不到。
而之前所有验收都是跑这个套件 —— 于是「测试全绿」和「模块编译不过」可以同时成立。

## 这暴露了守卫体系的一个洞

现有 7 类守卫全是**静态源码扫描**：分层依赖、死代码类型、重算热点、台账、UI 清单……
它们能发现「类型被删了没人引用」，但**发现不了「方法被搬家、调用方没改」** ——
因为那需要真正的编译。

## 修法（两层）

1. **立即修**：`ServerPlanningHooks` 改调新入口（两处 + 两个 import）。
2. **补闸门**：新增 `verify.ps1`，**必须同时跑**
   - `:fabric:compileJava`
   - `:common:roadJUnitRegressionSuite`

   实测 95 秒全绿。此后每次提交前跑它 —— 只跑测试的验收不再算数。

> 这条也回答了「为什么不能只靠守卫」：守卫检查的是**我们想到要检查的东西**，
> 编译器检查的是**所有东西**。两者互补，缺一不可。

---

# S6：配置界面拆分（2026-11-13）

`ConfigScreenFactoryImpl` **1791 行**，占 fabric 模块 41%，且在 common 里完全测不到 ——
是仓库里最后一个「又大又零测试」的块。

## 拆法：纯搬运，零逻辑改动

原文件结构天然可拆：**1 个入口 + 12 个独立的分区方法**（签名完全一致）。

拆成 `ConfigScreenFactoryImpl`（62 行，只剩 `createConfigScreen`）+ 12 个 `*Section` 类：

| 分区 | 行数 | 分区 | 行数 |
|---|---|---|---|
| `PlanningSection` | 455 | `RoadGenerationSection` | 114 |
| `PathfindingCostsSection` | 391 | `StructurePredictionSection` | 78 |
| `BridgeSection` | 216 | `RoadsideStructuresSection` | 75 |
| `PerformanceSection` | 195 | `SurfaceSettingsSection` | 66 |
| `RoadsideVillageSection` | 181 | `ClientSection` | 50 |
| `RuntimeGpuSection` | 128 | `TerrainProfileSection` | 126 |

每个类都带中文「文件职责」头，并在 javadoc 里写明「从 ConfigScreenFactoryImpl 抽出、
纯搬运、零逻辑改动」，避免后人误以为是新写的实现。

## 守卫口径同步升级

`UiFeatureGuardTest` 原来断言**单文件 ≥1500 行**。拆完必然红，所以口径改成
**「配置界面实现类的总行数 ≥1500」+「分区数 ≥12」+「每个分区 ≥30 行」**：

- 拆开 → 合法（总和不变）；
- 缩水 → 仍然被拦；
- 某个分区被清空成空壳 → 被拦。

## 中途踩的坑（值得记）

第一版抽取脚本的括号扫描器有个 bug：`in_line_comment` 标志**在行尾没复位**，
于是文件里第一个 `//` 注释之后所有大括号都被当注释跳过，一路吃到文件末尾 ——
12 个方法被当成 1 个，生成了一个 1754 行的巨型类。

好在我先看输出（「找到 1 个可抽取方法」）就发现不对，`git checkout` 回滚后修好扫描器重跑：
13 个块全部正确配平。

**教训**：自动改写代码时，**必须校验「抽取块数/总行数」这类守恒量**，
不能只看"生成成功"。

---

# 离线 AB 研究：不启动游戏拿到的第一批数字（2026-11-13）

## 为什么能离线做

旧流水线里真正决定剖面形状的那几道 pass（`stabilizeSurfaceProfile` /
`constrainProfile` / `applyProactiveTunnelProfile` / `removeSingleStationSpikes`）
**都是 int[] → int[] 的纯函数**，只吃地形与掩码。新 DP 更是纯函数。
所以两者能在没有世界的情况下并排跑 —— `ProfileAbStudyTest`。

## 结果（8 个合成地形，40 站）

| 场景 | 旧 pass | 新 DP | 胜负 |
|---|---|---|---|
| 平地 | 全 0 | 全 0 | 平 |
| 缓坡上升 | cut=85 | **cut=0** | DP |
| **陡坡上升（每站 +4）** | **照常输出剖面** | **DP 判定不可行** | — |
| 宽谷 | cut=193 fill=4 | **cut=20 fill=30** | DP |
| 山脊 | **p95=0 maxStep=0**、cut=182 | p95=1 maxStep=1、cut=30 fill=72 | 旧（2:1）|
| 阶梯 | cut=185 | **cut=0 fill=7** | DP |
| 噪声 | cut=38 fill=4 | **cut=3 fill=12** | DP |
| 长缓坡带凸起 | cut=150 | **cut=9 fill=4** | DP |

**汇总：8 场景 / DP 可行 7 / DP 胜 5 / 旧胜 1 / 平 1。**

## 两个必须讲清的结论

### ① 土方量是 DP 的大幅优势（不是小优势）

`cut` 普遍从 85~193 降到 0~30。原因很直接：旧 pass 是「先按地形铺，再夹坡度」，
夹出来的高度差全部变成挖方；DP 是把挖填方写进目标函数后**一次性求解**，
它自然会选「浅挖 + 少量填方」而不是「深挖走平」。

### ② ★「陡坡」这一格暴露了两种实现最根本的差别

- **旧 pass 永远会输出一条剖面** —— 哪怕它要挖穿山体一百多格；
- **DP 明确报不可行** —— 因为在「每站最大抬 1 格」的约束下，确实不存在满足条件的地面剖面。

这不是 DP 的缺陷，是**它拒绝假装成功**。真实答案有两个，且都已有对应实现：
- 盘山回头弯（`planning/terrain/MountainSwitchbackPlanner`）；
- 模式感知求解（`ModeDpSolver`，走隧道）。

而旧实现「总能输出」正是需要 `AccurateGradeRepairPlanner` 那套事后修线的原因。

### 山脊那一格：旧解赢在"平"，代价是 182 格挖方

旧解给出 p95=0 / maxStep=0（完美平），代价是挖掉 182 格；DP 给 p95=1、只挖 30 填 72。
**这是产品取舍，不是缺陷** —— `ProfileMetrics.versus` 只做胜负计数、不做加权，
就是不想把这个决定藏进代码里。

## ★ 局限（不能当最终依据）

- 只跑了 15 道 pass 里的 **4 道**，真实链路还有世界采样、桥隧判定、边界锁定、
  端点共享高度，都会再改一遍剖面；
- 地形是**合成**的；
- 所以这是**早期信号**。能否切默认仍然要真实会话的 `[PROFILE-AB-SUMMARY]`。

但这个信号已经很明确：**在纯函数层、温和地形上，新解在土方量上呈压倒性优势**，
唯一输的一格是「用 182 格挖方换完美平整」这种旧解自己也会被玩家抱怨的行为。

---

# 第二轮离线研究：模式感知 DP 的能力边界（2026-11-13）

## 先补齐第一轮缺失的数字

之前"陡坡"那格只写了「DP 不可行」，现在旧解的数字也拿到了：

| 场景 | 旧 pass | 新 DP |
|---|---|---|
| 陡坡上升（每站 +4） | p95=1 maxStep=1 **cut=3025** | 不可行 |

**旧实现为了在每站 +4 格的坡上凑出 p95=1，挖掉了 3025 格方块。** 这就是
「总能输出一条剖面」的真实代价 —— 也是 `AccurateGradeRepairPlanner` 那套事后修线
存在的根本原因。

## 模式感知 DP 的结果

| 场景 | 纯地面 DP | 模式感知 DP |
|---|---|---|
| 陡坡 **+2/站** | 不可行 | **地面 13 / 桥 8（1 座）/ 隧道 19（1 条）**，cost=358 |
| 陡坡 **+4/站** | 不可行 | **不可行** |
| 断崖（12 格跳） | 可行 | 地面 40，cost=44 |

## ★ 发现一：S4 确实带来了旧实现做不到的能力

`+2/站` 的坡上，纯地面剖面不存在，而模式感知 DP 用
**「1 座桥（8 站）+ 1 条隧道（19 站）」** 的组合解决了它。

这正是旧代码里最难缠的那类地形 —— `TargetYComputation` 里专门有一大段注释在处理
「山坡紧邻桥时不要在河两边织出交替的桥和隧道」。**联合求解天生不会织**，
因为它在同一张图里一次性决定了模式与高度。

## ★ 发现二：「+4/站」谁都救不了 —— 问题在路径层，不在剖面层

隧道埋深每站增加 3 格，16 站就撞上 48 格上限。所以模式感知也失败。

这说明：**这条路本身太陡了**，正确做法是让路线盘山（`MountainSwitchbackPlanner`），
而不是让剖面求解器去硬凑。把这条写进测试，是为了防止以后有人看到「模式 DP 也失败」
就误以为 S4 没用 —— 它救的是「该架桥/打洞但被判成地面」，不是「路线本身该改」。

## ★ 发现三：断崖那一格暴露了代价模型的缺口

12 格断崖，模式感知 DP **选了地面**（cost=44），而不是桥或隧道。

原因：桥 5/station、隧道 14/station，而断崖只影响少数几站 ⇒ **切穿它更便宜**。
这个结论在纯土方模型里是对的，但它忽略了「破坏地形观感」这一项 —— 真实世界里
玩家看到一条把山崖切成槽的路会抱怨，而那不在代价函数里。

**结论：要改变这个行为，不该改求解器，该加代价项**（例如「暴露的切面面积」或
「不可逆地形改动」的惩罚）。这条留作 S4 之后的调参任务，已记在这里。

## 目前三个结论的可靠性分级

| 结论 | 可靠性 |
|---|---|
| 土方量上 DP 远优于旧 pass | **高** —— 5/8 场景压倒性，机制清楚（目标函数 vs 事后夹坡）|
| 模式感知能解「地面不可行但可桥隧」的地形 | **高** —— 机制清楚，且旧代码的注释承认这类地形难 |
| 断崖该桥还是该切取决于代价权重 | **中** —— 结论依赖我给的实验单价，需要产品决策 |

---

# 真实地形录制与 CI 重放（2026-11-13）

## 它消掉的局限

离线研究用的是**合成**地形（山坡是等差数列、断崖是阶跃）。真实地形有噪声、
有河岸、有台地，光靠想象凑场景永远不够。

`ProfileTerrainRecorder` 把真实运行里的地形录成文本；`ProfileTerrainReplayTest`
把它喂回「旧 pass vs 新 DP」。**开一次游戏，之后所有实验都在 CI 里跑。**

## 你需要做的（一次，约 10 秒操作）

1. 启动游戏时加参数：

   ```
   -Droadweaver.profileAb=on
   ```

2. 正常玩一会儿，让规划跑一批路线（开局预热就会跑几十条）。

3. 出来一个文件：`<游戏根目录>/logs/roadweaver-terrain-ab.tsv`

4. 把它复制成：

   ```
   common/src/test/resources/profile/terrain-samples.tsv
   ```

5. 跑 `.erify.ps1` —— 重放测试会自动从「跳过」变成「真实对比」。

## 记录什么、为什么这么选

| 记 | 不记 |
|---|---|
| 每站地形高度 `terrainY` | ❌ 旧剖面 |
| 每站是否跨水 `crossing` | ❌ 桥隧判定结果 |
| 端点 tag | ❌ 配置快照 |

**不记旧剖面**是关键：一旦录了旧解，重放就变成「和旧解比」，
而我们要比的是「同样的地形，两种算法各自算出的解」。

## 三条保护（绝不影响主流程）

1. 只在 `-Droadweaver.profileAb=on` 时工作；
2. 单次会话最多 120 条、每条最多 2048 站（否则 2000 站 × 几百条会写出几十 MB）；
3. **任何 IO/解析异常都吞掉**并永久停用录制 —— 观测设施绝不能让玩家掉帧或崩游戏。

## 格式

```
tag 	 地形高度(逗号) 	 跨水(0/1 逗号)
```

选 TSV 而不是 JSON：这条路径在服务端线程上跑，每站一个数字，
手写序列化比任何库都快且零依赖。坏行会被跳过并计数，不会废掉整个重放。

## 测试现状

- `录制格式可以原样往返` ✅（含坏行跳过）
- `关闭时一条都不写` ✅
- `有真实录制就重放对比` ⏸ **跳过中** —— 等上面那个文件

---

# S5 方法级删除边界（2026-11-13）

S5 台账（文件级）已经标出 3 个 `REPLACE_BY_DP` 文件。这一轮把粒度推进到**方法级**：
`docs/S5-METHOD-MAP.md` + `S5MethodMapTest`。

## 结果：44 个方法里 36 个会随旧链路一起消失（82%）

| 分类 | 数量 | 含义 |
|---|---|---|
| `DELETE_WITH_LEGACY` | **17** | 外部**零**引用，只被同包旧链路内部调用 |
| `LEGACY_ONLY` | **19** | 有外部调用，但调用方全在旧链路内 |
| `KEEP_INPUT` | 2 | 旧链路之外也在用（`RoadGradePlanner` 类本身被 `TunnelFootprintSampler` 引用；`deviationCost` 被 `PotentialFieldPathfinder` 用）|
| `AMBIGUOUS` | 6 | 通用名，按名统计会误判，需人工核 |

## 守卫怎么钉

`S5MethodMapTest` 两条断言：

1. **覆盖面**：3 个受管文件里每个方法必须在台账里出现，且分类合法；
   台账里登记了不存在的方法也算违规（**删方法要删台账行**）。
2. **可删边界恒为零**：分类为 `DELETE_WITH_LEGACY` 的方法，全模块外部引用数必须恒为 0。

> 第 2 条的意义：删旧实现最容易踩的坑不是删错，而是
> **以为某个方法没人用了，结果半个月后别处开始调它** —— 它就从「可删」悄悄变回
> 「不可删」，而没人会注意。守卫会在那一刻红。

## 为什么现在不删

这 17 个方法在同包内部还被互相调用（例如 `constrainProfileToFootprintSupport`
被 `stabilizeSurfaceProfile` 调）。**单独删任何一个都会编译失败** ——
它们必须与旧链路整体一起下线。台账的作用就是把这个整体圈出来。

## ★ 口径的诚实说明

按**方法名**统计全模块引用，这是不完美口径：通用名会被同名方法污染
（`fill` 命中 46 次，绝大多数是 UI 的 `fill(...)`；`clamp` 18 次同理）。
所以台账单列了 `AMBIGUOUS` 交人工核。

但 `DELETE_WITH_LEGACY` 的分类**恰恰不受这个缺陷影响** ——
它们外部引用是 0，通用名不可能把它们误判成 0。

## 第一波删除的顺序（等 DP 切默认后）

1. 先删 `DELETE_WITH_LEGACY` 里**叶子**那些（如 `windowRun` / `lowerRunToGradeCap`），
   每删一个跑一次 `verify.ps1`；
2. 再删 `LEGACY_ONLY` 的入口方法（`stabilizeSurfaceProfile` 等）——
   它们被 `TargetYComputation` 调，必须等 `TargetYComputation` 整体下线；
3. 最后删 3 个文件本体与对应台账行。

---

# ★ 自我更正：`promoteRuntimeBridges` 不是重算热点（2026-11-13）

## 我之前的判断（错的）

在 S2 计划里我写过：

> `PathFeature:407 promoteRuntimeBridges` —— **每个区块一次** —— 探世界按运行期水位再成长桥范围

并把它列进「施工期自己又判了一遍」的热点列表，暗示它属于该被消除的技术债。

## 复核源码后的实际情况

```java
if (StreamsReflowingCompat.isAvailable()) return original;      // 有 Streams 直接短路
...
for (int i = 0; i < centers.size(); ) {
    if (original.isBridge()[i]) { i++; continue; }              // 已规划的桥不重探
    if (!inWindow(processingChunk, centers.get(i)) || !hasWatercourseAcrossRoad(...)) {
        i++; continue;                                          // ★ 只探当前区块窗口内的站
    }
    ...
}
List<RuntimeRange> stableRanges = RUNTIME_BRIDGES.compute(key, ...);   // ★ 增量累积去重
```

三点关键事实：

1. **`inWindow` 限定范围**：只对「距当前处理区块 `RUNTIME_BRIDGE_CHUNK_RADIUS` 以内」的站做世界探测；
2. **增量 + 去重**：探到的区间累积进 `RUNTIME_BRIDGES`（按维度 + 路线身份 + 端点 + 站数做 key），
   后面的区块是合并而不是重算；
3. **已规划的桥跳过**：不会对同一段反复探测。

所以它是 **O(窗口) 的增量探测**，不是 O(路线 × 区块)。O(n) 那部分只是一次数组遍历
（跳过已规划桥 + 整数窗口比较），代价可以忽略。

## 结论：应当保留，且已从「待消除」改判为「设计必需」

它的存在理由是**信息差**，不是设计缺陷：

> 没有 Streams 时，规划期无法预知某些水体会在区块生成**之后**才出现。
> 这时若把半边河床填平，就是玩家能直接看到的错误。
> 它是「别把河床填成半边」的安全网。

这条更正的意义：**如果我当时没复核就按错误的判断去"优化"它，会删掉一个真正的安全网。**
也是为什么 S2 的台账必须逐条核对真实成本，而不是凭调用位置推断。

## 连带影响

- `SingleSourceOfTruthTest` 里那段描述已同步更正；
- S2 剩下的热路径项只剩 `SegmentPaver.planGrade`（施工期重插值坡度），
  它才是真正等 S3 切默认后才能移除的那一个。

---

# ★ 第二轮自我更正：`SegmentPaver.planGrade` 也不是性能热点（2026-11-13）

S2 台账里还剩它挂了「施工期重插值坡度」的标签。复核成本后同样要更正。

## 实测它的真实成本

`planGrade` 里那段「重插值」= `RoadHeightInterpolator.batchInterpolate`：

```java
private static final int LOCAL_SEGMENT_RADIUS = 2;     // ★ 每列只扫 5 段
int searchStart = Math.max(0, anchor - LOCAL_SEGMENT_RADIUS);
int searchEnd   = Math.min(n - 2, anchor + LOCAL_SEGMENT_RADIUS);
```

- 它是**纯函数**（只碰 `BlockPos` 与 `int[]`，不读世界）；
- 每个断面列只扫 **5 个线段**、每个线段约 20 flops ⇒ **每段约 700 flops**；
- 一个区块约 30 段 ⇒ **约 2 万 flops**。

这个量级连噪音都算不上。**给它加缓存只会引入额外开销。**

## 那 `planGrade` 真正的成本在哪

在 `sampleTerrain`（逐列读世界）与 `prepareCulvertDeck`（判涵洞、可能改写剖面）——
这两件事**必须**在区块生成时做，因为它们依赖那一刻的世界状态。

## 结论：S2 没有剩余的性能热点

| 热点 | 真实结论 |
|---|---|
| `BridgeRangeCalculator.compute` | ✅ 已迁出（按路缓存）|
| `promoteRuntimeBridges` | ✅ 设计必需（O(窗口) 增量探测的安全网）|
| `SegmentPaver.planGrade` | ✅ 不是热点（插值约 2 万 flops/区块；贵的是必须每区块做的世界采样）|

**这意味着 S2 的价值从「性能」转向「结构」**：
`BuildPlan` 的意义不再是省计算，而是让「判定」成为可单测、可对照的纯数据 ——
而这正是 S3/S4 能安全切换的前提。诚实说明比留着一个好看的数字重要。

---

# S7 可执行切换清单（2026-11-13）

已落盘 `docs/S7-SWITCH-CHECKLIST.md`：**照着做就行**的操作手册，含四级切换、
每级的前置条件/要改的代码/验证命令/回滚方式，以及「三条不许做」。

关键点：

- **第 0 级（采集）已完成，只等你跑一次** —— 拿 `terrain-samples.tsv`，
  通过标准是 `样本 >= 20 且 DP 土方合计 <= 旧`；
- **第 1 级要写 100~150 行**：`ProfileSolverMode` 开关 + `computeTargetY` 分派；
  并明确记下**已知风险** —— `ProfileDpSource` 目前不含结构边界锁定与端点共享高度，
  切 `DP` 后端点高度会与旧行为不同，这可能是"看起来更差"的主因；
- **第 2 级要写 200~300 行**：旧判定翻译成模式约束、解反向生成 spans、差异日志；
- **第 3 级删除**按 S5 方法台账三级走，每步 `verify.ps1`；
- **第 4 级**清死代码与注释。

> 有了这份清单，数据到手后的切换**不需要再等我**。

---

# S7 第 1 级前置：三条硬契约已进求解器（2026-11-13）

`ProfileDpSource` 现在接受 `Constraints(pin, mustBeCovered, minCover)`，
覆盖旧 `computeTargetY` 里的三项硬契约：

| 契约 | 表达方式 | 违反的后果（玩家能直接看到）|
|---|---|---|
| 结构边界锁定 | `pin[i] = 实测地表` | 路入口悬在屋顶上方或埋进地里 |
| 未知端点共享高度 | `pin[i] = 注册表给的高度` | 两个方向的路在同一结构口落成两个高度，接缝出台阶 |
| 隧道覆土 | `mustBeCovered[i]` + `minCover` | 隧道顶离地表太近，挖穿成露天沟 |

## 为什么这三条必须是约束、不能是代价

代价可以被"更划算的方案"打败，而这三条**没有"更划算"的余地**。
把它们写进目标函数，求解器就会在某个地形上"权衡"掉一个入口高度 —— 那不可接受。

## ★ 一处刻意的语义改进

旧版 `applySingleBoundaryGrade` 会硬铺一条**无视 fill 窗口**的引道，
这正是「锁定点周围填方超限」的来源。

新实现把选择权交给调用方：**要贴住高处的锁定点，就显式放宽 `maxFill`**；
求解器不会偷偷超限。测试 `钉住高度超出窗口时_要么放宽窗口要么不可行`
把这条钉死了 —— 窄窗口时判不可行，放宽后立刻可行。

## 钉住 + 坡度上限 ⇒ 引道自动成立

钉住的值与 `maxStep` 合起来会自动生成合法引道：DP 必须从那个高度出发、
每站最多变 `maxStep`，于是它在钉住站两侧自然铺出满足坡度上限的过渡。
**这正是旧版 `applySingleBoundaryGrade` 手工在做的事**，只是 DP 顺带就得到了。

## 剩余工作量

第 1 级仍需在**调用方**接线：`computeTargetY` 要把 `connection` /
`UnknownEndpointHeightRegistry` / `spans` 翻译成 `Constraints`。这是那 100~150 行的主体。

---

# S2 结构价值落地：BuildPlan（2026-11-13）

前面两轮已经论证：S2 没有剩余的性能热点，它的价值在**结构**。
这一轮把那个价值真正建起来 —— `spec/BuildPlan` + `spec/BuildPlanCompiler`。

## 它解决什么问题

旧版把「这一列要不要铺、铺到哪一层、是不是桥面/隧道」的判定散在施工路径里：
`SegmentPaver.paveSegment` 一边遍历列、一边查世界（`isProtected*`）、一边决定动作。

后果三条：

| 后果 | 具体表现 |
|---|---|
| **测不了** | 判定依赖世界状态，纯 JUnit 里跑不起来 |
| **说不清** | 「这一列为什么被跳过」只能跑过之后从日志里猜 |
| **改不动** | 想改判定规则，得在施工循环里做手术 |

`BuildPlan` 把判定结果**物化成数据**：世界信息由调用方翻译成输入，
`BuildPlanCompiler` 只做纯函数分类，施工侧只负责按 Plan 放方块。

## 与 SegmentPaver 的对照表（将来接线用）

| `BuildPlan.Kind` | SegmentPaver 里的对应 |
|---|---|
| `SKIP` | `ColumnAction.SKIP`（地表被保护）|
| `PAVE_SURFACE_ONLY` | `ColumnAction.PAVE_SURFACE_ONLY`（路基被保护）|
| `PAVE_NORMAL` | `ColumnAction.PAVE_NORMAL` |
| `BRIDGE_DECK` | `BridgeSegment` 覆盖的站 |
| `TUNNEL_BORE` | `tunnelBody` 为真的站 |

## ★ 一条被钉住的顺序不变量

规则的**顺序**是：地表保护 → 隧道/桥 → 路基保护 → 正常铺装。

最容易改错的是第一条：**地表被保护的东西，桥也不该从它上面压过去**。
测试 `地表保护优先于桥与隧道` 专门钉这个 —— 同一列既是隧道又地表受保护时，
结果必须是 `SKIP`。

## 现状：尚未接线（刻意的）

`BuildPlan` / `BuildPlanCompiler` 目前**只被测试使用**，不在生产路径上。

接线放在 S7 第 2 级，因为那时判定（桥隧/保护）才是稳定的。现在先把数据模型定下来，
是因为第 2 级要写的「旧判定翻译成模式约束」正好需要这样一个目标结构 ——
**先定形状，后填内容，比边写边改形状便宜**。

接线要补的三个输入（都已在 `Inputs` 的注释里写明）：

| 输入 | 从哪来 |
|---|---|
| `protectionRank` | `RoadConstructionSafety.isProtected*` 系列 |
| `tunnelStation` / `bridgeStation` | `RoadData.spans()` 按 `SpanType` 分派 |
| `columnY` | `SegmentPaver.planGrade` 的横断面压平结果（**必须传真实值**，否则「对齐现状」不成立）|
