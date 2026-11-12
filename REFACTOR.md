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
