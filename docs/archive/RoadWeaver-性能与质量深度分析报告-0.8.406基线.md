# RoadWeaver 深度性能与质量分析报告

- **工程**：`D:\云游四海-Mod源码\roadweaver`
- **分支 / HEAD**：`0.8.374-route-editor` / `821511c`
- **分析基线**：`0.8.406-perf-batch`
- **分析日期**：2026-10-04
- **性质**：**纯只读分析**。全程未修改、新建、删除工程内任何文件，未运行 gradle，未做任何 git 写操作。
  （取证前后各核对一次 `git status --porcelain` = 0 行。）
- **方法**：4 个子智能体并行分域（A 寻路热路径 / B 结构解析+IO / C 施工写方块+校验 / D 运行时调度+GPU+卡顿）
  + 主控独立复核归因。原始长文证据见 `D:\_临时分析\roadweaver-perf-080407\`（临时目录，非交付物）。

> **本报告与任务书给定的前提有一处重要冲突，见 §1 M-1。请先读该节再读候选清单。**

---

## 1. ★ 归因复核：对既有前提的独立验证结果

任务书 §9.5 要求「每条优化候选都要先核实归因，第三方审计报告出现过错误归因，不能直接采信」。
主控对 4 份分域报告里**决定优先级排序的关键论断**逐条复核，结果如下。

### M-1 ⚠️ 「结构解析占 70%、粗采样仅 29s」这个排序**已过期**，且新证据方向相反

| 项 | 内容 |
|---|---|
| 前提来源 | 任务书 §6 与 `PerformanceConfig.java:141`、`RuntimeLoadPolicy.java:565` 的注释：「真机取证（**20260911**）：游玩期 `RW-NativeStructure` 8 线程累计 **1735s** CPU（占 RoadWeaver 总量 ~70%），对比粗采样 `RW-CoarseTile` 仅 **29s**」 |
| 复核结论 | 这是 **2026-09-11 / 0.8.344 时代**的一次 profiler 采样，**此后未再复核**，全部日志与源码里没有任何可复现该口径的原始数据（`docs/` 与全部 `*.log` 中 grep `1735`/线程 CPU 均为巧合命中）。它被当作「已确认结论」沿用了 20 多个版本。 |
| 新的实测 | 最近一次重负载真机会话 `logs\2026-10-01-2.log.gz`（0.8.403 期，491,893 行）里 `coarse terrain load timing` 共 **29 行**，每行是**一次区域加载的增量**（源码 `CoarseTerrainRegionSampler.java:77-85` 用 `TimingSnapshot.minus()`，计数器为 `CoarseTerrainTileCache.java:229 TIMING_SAMPLE_NANOS.addAndGet(...)` 静态跨线程累加）。<br>**Σ `sampleWorkerMs` = 55,242,186 ms ≈ 55,242 s**；其中 **Σ `heightBatchWorkerMs` = 54,371,393 ms ≈ 54,371 s（占 98.4%）**；Σ `wallMs` = 4,424,420 ms ≈ 4,424 s；Σ `tiles`=758、Σ `sampled`=867。 |
| **口径警告（必读）** | 这两个数**不是同一种量**：`sampleWorkerMs` 统计的是**线程被占用的挂钟时间（含在间隔闸上 park 的时间）**，profiler 的 1735s 是**真实 CPU 时间**。因此 **55,242 s 不能直接读作「粗采样比结构解析贵 32 倍」**。 |
| 但能确定的结论 | ① 两套数字**跨会话、跨版本、跨口径**，「70% vs 29s」这个比例**没有任何当前证据支撑**，不应继续作为立项依据；② 粗采样路径**把 worker 槽位占住的时间**远大于结构解析的 CPU 时间，说明它的主要问题是**「在闸门上等」而不是「在算」**（与 §3.2 的 D2 互为印证：`heightBatchWorkerMs` 的计时区间 `CoarseTerrainTileSampler.java:221` **正好包住** `RuntimeCoarseGpuBudget.acquire` 的 14 秒间隔闸）。 |
| **行动要求** | **在决定是否投入 B-1（结构模板级记忆化）这类大改动之前，必须先做一次同口径重测**（JFR 或同一次 profiler 同时采样 `RW-NativeStructure-*` 与 `RW-CoarseTile-*` 的真实 CPU）。否则可能把几十小时的工程量投在已经不是第一热点的地方。 |

### M-2 ✅ （主控新增，已用源码 + 真机日志双向坐实）E 阶段结构预筛是**同义反复**，其安全护栏**结构性失效**

这是本项目历史上已记录过的同一类缺陷（0.8.335「洞口贴地闸门从未生效」：*闸门必须校验「最终结果」，不能校验「意图」*），这次换了一个位置重新出现。

| 环节 | 证据 |
|---|---|
| 生产者 | `search\StructurePredictor.java:113 / 189 / 360 / 478` —— 候选**全部**由 `rssp.getPotentialStructureChunk(seed, baseX, baseZ)` 生成，即每个候选**天然已经是**它的 region 的潜在起点区块 |
| 预筛 | `StructureAvoidanceService.java:899-914 isPotentialStructureChunk` —— 用**同一个** `getPotentialStructureChunk` 再判一次「候选是否等于潜在起点」，对上述候选**恒为 true**。注释 `:891-894` 自己也写明「`isPlacementChunk` 就是这条判定」 |
| 实测 | 真机日志 `prefilterKept` 累计 **50,429**、`prefilterSkipped` **恒为 0** |
| 护栏 | `:926-965 verifyPrefilterExactness` —— 它在 `resolvePreparedStartsAsync` 收到的 `starts` 列表上重跑预筛；而该列表**已被** `collectPlanningStartKeys:2265-2272` 预筛过（不通过的根本不在列表里）⇒ `skipped` **恒为空** |
| 实测 | 真机日志恒定输出 `structure prefilter guard: skipped=0 checked=0 boxes=0 verdict=match` —— **它一次都没有校验过任何东西**，却给出了「match」的假保证 |

**影响**：① 该护栏不能作为「预筛安全」的证据（对应 0.8.403/0.8.405 把「预筛护栏每世界复位」当作强化措施，实际强化的是个空操作）；② **不能**靠预筛去砍掉 §2.1 里那 **94.7% 的空白解析**——因为预筛要砍的东西，生产者早就已经砍过了。

**候选**：本报告**不提出**「把预筛挪到枚举层」这类候选 —— 唯一等价的早退谓词已被上游用掉，**目前不存在可证明等价的更强早退**（要砍掉 94.7% 空白必须复刻 `structure.generate()` 内的生物群系/生成点判定，属新立项）。**判：暂缓 + 补一个「护栏失效」的守卫测试**（低风险、零行为改动）。

### M-3 ✅ D2 的机制已按代码逐跳核实（这是本轮**最大且最容易兑现**的卡顿候选）

链路：`pathfinding\terrain\region\CoarseTerrainTileSampler.java:117-119`（`bands = bandCount()*2`）→ `:127-154` 分带循环，**每带**调一次 `:146 heightSampler.sampleHeights(...)`
→ `pathfinding\cache\opencl\OpenCLCoarseHeightBatchSampler.java:350-355` 每次 `sampleHeights` 都调 `RuntimeCoarseGpuBudget.acquire(runtime)`
→ `RuntimeCoarseGpuBudget.java:117` **每次成功 acquire 都刷新 `LAST_GPU_SUBMIT`**，而 `:98-107` 用 `gpuActiveMinIntervalMs()` 卡这个时间戳。
⇒ **分带把「提交间隔闸」按带数等比放大了**。真机 live 配置 `coarseHeightBandCount=6`、`runtimeCoarseGpuActiveMinIntervalMs=14000` ⇒ 单瓦片墙钟 ≈ 11×14s + 12×40ms ≈ **154 s**，其中 GPU 真正在跑约 1 s。
⇒ 0.8.403 的 A1（分带翻倍）本意是「缩短 GPU 独占窗口」，实际**每瓦片净亏约 +84 s**。**判：需真机实测确认后立刻改（见 §4 第二档 → 实际建议按第一档推进）。**

### M-4 ✅ A-5 已核实：`BasicAStarPathfinder.java:114` 的重复判定在 HARD 模式下**恒为假**
`StructureAvoidanceService.java:1357-1364 blocksPlanningObstacle` 第一句就是 `shouldAvoidPlanning(pos, r)`；
调用点 `BasicAStarPathfinder.java:109-112` 在它为 true 时直接 `continue` ⇒ 走到 `:114` 时 `shouldAvoidPlanning` **必然为 false** ⇒ `:115 obstaclePenalty += PLANNING_OBSTACLE_COST` 是死分支，只剩一次白算。舍去白算**不改变任何输出**。

### M-5 ✅ C1 已核实：5 个 mask 确实各建一次**完全相同**的索引表
`features\path\PathFeature.java:411-416` 连调 5 个构造函数；
`buildPreserveCrossingGradeMask:676-677` 与 `buildSpanMask:695-696` **各自**对同一个 `centers` 建 `Map<Long,Integer> indices`。5 次全同建表，可合并为 1 次。

### M-6 ✅ 结构解析的真实规模（主控独立统计，会话 `2026-10-01-2.log.gz`）

| 指标 | 实测值 | 来源 |
|---|---|---|
| `exact structure start plan` 调用次数 | **39** | 日志 grep |
| Σ `total`（被规划的 start 查询） | **40,982** | 同上 |
| Σ `cached`（缓存命中，**省掉的重复解析**） | **12,311（30.0%）** | 同上 |
| Σ `unresolved`（真正要解析） | **28,671（70.0%）** | 同上 |
| Σ `exact structure window timing` 的 `elapsedMs` | **≈ 1,009,244 ms ≈ 1,009 s** | 39 行求和 |
| 主缓存状态（10:17:50） | `capacity=32768 size=4183 evictions=0 hits=62`，总命中率 **1.5%** | 日志 |
| Holder identity 漏命中 | **累计 2 例**（`stableFeldsMatched=1 holderEqualsMatched=0`） | 日志 |
| 冷 miss 中的空结果 | `emptyResults=3960 / 4183 = 94.7%` | 日志 |

**对任务书 §6 的正面回答**：
- 「同一结构/模板/起点被重复解析多少次」——**start 级的重复已经被现有缓存吃掉了 30%，且 `evictions=0`（容量只用 12.8%）**。
  ⇒ **「加大缓存 / 持久化解析结果」整条线可以正式否决**（源码 `StructureAvoidanceService.java:4499-4504` 预先写下的判据，本轮实测确认）。
- 剩下的 **28,671 次是互不相同**的解析，砍不掉「重复」，只能砍「次数」或「单次成本」。
- **真正被重复的是「模板」不是「起点」**：一个 NBT 模板被成千上万个 piece 实例引用，而 `PieceOccupancy` 的缓存只到「piece 实例」粒度（`:3596-3634`，结果落在实例字段 `:3427-3430`）。**缓存粒度错了，不是缓存键错了。** 这是 B-1 的依据。
- **Holder identity 漏命中实测只有 2 例（0.05% 量级）** ⇒ P2-1「同 chunk 双键重复解析」**建议正式降级为正确性修复，不要当性能项排期**。

---

## 2. 目标一：减少计算量

### 2.1 结构解析链（当前最大的一块，但方向需要修正）

| 编号 | 证据（文件:行号 + 实测） | 优化候选 | 数学等价性 | 预期收益 | 影响质量 | 红线 | 风险 | 档位 |
|---|---|---|---|---|---|---|---|---|
| **B-1** | `StructureAvoidanceService.java:3596-3634 ensureResolved()`：对**每个 piece** 逐方块跑 `StructureTemplate.transform`(`:3610`)、`state.mirror().rotate()`(`:3614`)、`getCollisionShape`+`toAabbs()`+`move`(`:3615-3620`)；结果只存在**实例字段** `:3427-3430`。实测：同批 16 start 耗时 **2,652 → 16,789 ms（8.2×）**；`exactPieceBoxes` 累计 **37,659** | 新增**模板级**记忆化：键 `(模板 identity, Mirror, Rotation, rotationPivot)`，缓存**模板局部**的占用图与 AABB 列表，查询时套 `origin` 平移 | **需等价性验证**（见 §5 的验证要点：`getCollisionShape` 的**位置无关性**是本候选唯一未证前提） | **大**（估算：若模板级命中率高，可消掉 `ensureResolved` 的大部分逐方块几何计算；具体倍数**待实测**） | 不影响逐格一致（前提成立时） | 不碰 | 中（缓存失效须与 `clearAll` / 世界切换同步） | **需真机实测再定**（先补 T-1/T-2 计时） |
| **B-2** | `:3518-3534 intersects()` 每次对全部 `shapes` 做 **O(n) 全扫**；该方法是 `shouldAvoid*` 的最终判定，寻路**逐体素**调用 | 预存 `collisionBoxes` 的 **AABB 并集**，进逐 shape 扫描前先做一次并集快速拒斥 | **已证逐格一致**（`B_i ⊆ U` ⇒ 并集外 `roadBox ∩ (∪B_i) = ∅` ⇒ 原扫描必返 false；并集内行为完全不变。纯集合论蕴含，与浮点无关） | **中**（估算） | 不影响逐格一致 | 不碰 | 低（须保留 `:3526` 对「空 shapes / null shapes」的语义区分） | **立即做** |
| **B-3** | `:3427 Map<Long,Byte> occupied` + `:3605-3613` `new HashMap`/`merge`/装箱 λ + `:3625 Map.copyOf` | 换 primitive `Long2ByteOpenHashMap`（fastutil 已在依赖内），手写 `get`+位或合并 | 需等价性验证（须覆盖 null 与并发；当前有 `synchronized` 兜底） | 中（估算） | 不影响逐格一致 | 不碰 | 低（`occupied` 有 4 处读点需同改） | 需真机实测再定 |
| **B-4** | `:3448-3467` 构造器为 `occupied` 每 long 键 `new AABB(pos)` | 该构造器**仅 `forTest` 可达**（生产构造点唯一为 `:513`），可移除逐键展开 | 已证逐格一致（须先确认测试未走两参版） | 小 | 不影响逐格一致 | 不碰 | 低 | 立即做（清理项） |
| **B-5** | `:2723-2746 probeIdentityLeak` 每 64 次 miss **全表线性扫描** `keySet()` | 预聚合「4 稳定字段 → 条目数」，或生产构建直接关闭探测 | 已证逐格一致（纯诊断） | 小 | 不影响逐格一致 | 不碰 | 低 | 立即做（清理项） |
| **B-6** | `prefilterSkipped=0` / `prefilterKept=50,429`；护栏恒 `skipped=0 checked=0 boxes=0 verdict=match` | **见 M-2**：预筛是生产者谓词的同义反复；**不提出**「前移预筛」候选 | 无可证明等价的更强早退 | **待实测**（若真能砍掉 94.7% 空白，量级最大） | — | — | — | **暂缓 / 需单项立项** |
| **M-2 补丁** | 同上 | 为「护栏恒 skipped=0」加一个守卫测试（钉住它确实校验了东西）；**零行为改动** | 已证逐格一致 | 0（正确性/可观测性） | 不影响 | 不碰 | 低 | **立即做** |
| **B-7** | `:2435-2447` batch timing 仅 `offset%1024==0` 打 INFO，其余 DEBUG ⇒ 81 条 INFO 覆盖不到 28,671 次解析 | 批次耗时**直方图**提 INFO（按模板/piece 数分桶） | 已证逐格一致（纯日志） | **待实测**（这是给 B-1 定收益上限的唯一手段） | 不影响 | 不碰 | 低 | **立即做**（配合 B-1 的前置） |

### 2.2 寻路 / 规划热路径

| 编号 | 证据 | 优化候选 | 数学等价性 | 预期收益 | 影响质量 | 红线 | 风险 | 档位 |
|---|---|---|---|---|---|---|---|---|
| **A-1** | `planning\terrain\MountainSwitchbackPlanner.java:195-235,367-370`；javap 实证 `findContourRoute` 内 `new StateKey` + `Double.valueOf`×2 + `new BlockPos`×2，`$Node.key()` 字节码即 `new StateKey`；预算 180,000(`:28`)/260,000(`:338`)/4×70,000(`:39`) | `Map<StateKey,Double> best` → `Long2DoubleOpenHashMap` + 打包 long 状态键（复用 0.8.406 给 `CoarseHeightAwarePlanner` 的同款位布局），`Node` 增 `final long key` | 已证逐格一致（位布局已验证；用 `containsKey` 保留「缺失 vs 0.0」语义） | **大**（估算：18 万展开 ≈ 700 万对象/次重试） | 不影响逐格一致 | 不碰 | 低 | 需真机实测再定 |
| **A-2** | `planning\terrain\AccurateGradeRepairPlanner.java:1385-1445,2229-2236`；预算 90,000(`:69,1525`)/240,000(`:70`)/24,000(`:72`) | 同 A-1；`Map<Long,Integer> heights`→`Long2IntOpenHashMap`；`:1434` 水惩罚是 (nextX,nextZ) 纯函数→提出 nextY 循环；`tunnelHeights()` 每方向 `new int[]`(`:1419`)→复用缓冲 | 已证逐格一致 | **大**（估算：每候选去 6 个分配 + 2 次装箱查表） | 不影响逐格一致 | 不碰 | 中（返回数组未逃逸可复用） | 需真机实测再定 |
| **A-3** | `pathfinding\terrain\region\CoarseTerrainRegion.java:212-234 tileAt` + `:47-50`；瓦片键来自 `CoarseTerrainRegionSampler.java:145-155` 的连续矩形 | `Map<Long,CoarseTerrainTile>` → **稠密二维数组**（或退一步 `Long2ObjectOpenHashMap`） | 已证逐格一致（缺瓦片仍返回 null 保持 seaLevel 回退） | **大**（估算：每邻居≥6 次 tileAt，每展开省 ≈170 ns） | 不影响逐格一致 | 不碰 | 低 | 需真机实测再定 |
| **A-4** | `CoarseTerrainRegion.java:126-146`（(2s+1)² 回退循环）+ `CoarseTerrainTileSampler.java:204-206,270-295`（halo=`ceilDiv(16,step)` 前缀和写 NEAR_WATER） | 瓦片命中且 `tile.isNearWater==false` 时**直接 return false**；加守卫 `neighborDistance <= haloBlocks` | 需等价性验证 | **大**（估算：内陆为常见情形，每展开省 ≈64 次装箱查表 ≈220 ns） | 不影响逐格一致 | 不碰 | 中（依赖 halo 覆盖性与瓦片网格对齐） | 需真机实测再定 |
| **A-5** | `BasicAStarPathfinder.java:109-119`；`StructureAvoidanceService.java:1357-1364`（见 M-4） | 只算一次 `boolean avoid`，再按原短路顺序分支；HARD 模式下 `:114` 的白算消除 | **已证逐格一致** | **中**（估算：每展开省 8 次结构查询） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **A-6** | `BasicAStarPathfinder.java:202-207,123-124`；`BidirectionalAStarPathfinder.java:222-227,141-142`；本机实测 `Math.hypot` = 13.78 ns | `den = Math.hypot(bx-ax, bz-az)` 只依赖搜索端点 → 算一次传入。**保持同一个 `Math.hypot` 调用，不做 hypot→sqrt 替换** | **已证逐格一致**（值为同一函数同参数） | **中**（估算：每展开省 8×13.78 ≈ 110 ns） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **A-7** | `MountainSwitchbackPlanner.java:251-253`（`calculateTerrainStability` 入参与本邻居无关却逐邻居重算）、`:246`（hypot 仅 8 种取值）、`:225`（`planningRoadRadius()` 在循环内） | 三项提到 direction 循环外；hypot 换 8 项**由同一 `Math.hypot` 生成**的预计算表 | **已证逐格一致** | **中**（估算 ≈195 ns/展开） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **A-8** | `GradientDescentPathfinder.java:107-111`(3 次)、`Bidirectional:135-138`(3)、`PotentialField:111-115`(3)、`BasicAStar:105`(1)、`MountainSwitchback:225`(1)、`CoarseHeightAware:105-108`(1)；实现 `StructureAvoidanceService.java:1474-1478` | 邻居循环前算一次存局部（`RawPathSearch.java:361` 已有同样做法） | 需等价性验证（唯一差异是搜索中途热重载 roadWidth，本身即非确定） | **中**（估算：最坏 24 次/展开 → 1 次） | 不影响逐格一致 | 不碰 | 低 | 立即做（附等价性论证） |
| **A-9** | `CoarseHeightAwarePlanner.java:93-139`：`hypot`(`:97`)、`sampleBundle`(`:111`)、`isNearWater`(`:114`)、`distanceFromLine`(`:120`) 只依赖 (nextX,nextZ) 却在竖直候选循环内重复；`Map<Long,Double> best`(`:60,133-137`) | 4 项提到 candidateIndex 循环外；`best` → `Long2DoubleOpenHashMap` | 已证逐格一致（前提：terrain 单次搜索内不可变 —— `CoarseTerrainRegion` 构造后不可变） | **中**（估算：默认 7 层，每方向省 6×(...)） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **A-10** | `StructureAvoidanceService.java:3968-4024`（5 处 `boxesByChunk.get(ChunkPos.asLong(...))`）、`:3702`、`:3760-3777`（HashMap→`Map.copyOf`，运行期为 `ImmutableCollections.MapN`，装箱无法被同类内联消除）；调用点 `BasicAStar:111/114/117`、`Bidir:135-138`、`Grad:108-111`、`PF:112-115`、`PathfindingHelper.java:77` | `boxesByChunk` → `Long2ObjectOpenHashMap`。**改动落在结构域文件，跨域** | 已证逐格一致 | **大**（估算：每展开 24–40 次装箱查表 × 3.5 ns ≈ 100–140 ns） | 不影响逐格一致 | 不碰 | 中（跨域 + `PlanningSnapshot` 字段类型） | 需真机实测再定 |
| **A-11** | `BasicAStarPathfinder.java:59-60,89-90,121,126,131,209-213`；javap 实测该类 **8 处 `Long.valueOf`** | `Map<Long,Node>`→`Long2ObjectOpenHashMap`、`Set<Long> closed`→`LongOpenHashSet`；`Node` 存 `final long key` | 已证逐格一致（fastutil 已在依赖内） | **中**（估算：每展开 24–26 次装箱 → 省 ≈85 ns） | 不影响逐格一致 | 不碰 | 低 | 立即做（与 A-12 同批） |
| **A-12** | `BasicAStar:94,97`、`Bidir:127,130`、`Grad:101,106`、`PF:105,110`：每邻居 `pos.offset()` + `new BlockPos()`；0.8.406 已加原语重载（`PathfindingHelper.java:64-82`、`StructureAvoidanceService.java:1308-1311`）但调用点仍造 `np` | 邻居探测全程用 int；仅 `HugeTreeReservation.isReserved(level,np)`(`:98`) 仍需 BlockPos（须加原语重载或复用探针） | 已证逐格一致 | 小~中（估算：每展开省 16 个 BlockPos） | 不影响逐格一致 | 不碰 | 低 | 立即做（与 A-11 同批） |
| **A-13** | `BasicAStar:209-213`；`:89-90` 关闭时已 `allNodes.remove(k)` 且 `:121` 保证 closed 键不复入。**`Bidir:121-155` 不能照做**（关闭时不从 `nodesThis` 移除，closed 判断承重） | 去掉 `isActiveOpenNode` 里的 `closed.contains` 项 | 需等价性验证（依赖两条不变量） | 小（估算 ≈3.5 ns/次 poll） | 不影响逐格一致 | 不碰 | 低~中 | 暂缓 |
| **A-14** | `pathfinding\cache\AccuratePathHeightResolver.java:36` 与 `:55` 对同一 position 各调一次 `hasAccurateTerrainSample` | 一趟循环同时填 `missing` 与 `exactHeights`；`sampled`→`Long2ObjectOpenHashMap` | 已证逐格一致 | 小（路径点级 O(n)，相对 A* 百万级展开可忽略） | 不影响逐格一致 | 不碰 | 低 | 暂缓 |
| **A-15** | `planning\impl\MSTPlanner.java:37,39-55`（O(n²) `record Edge`+sort）；`KNNPlanner.java:64-75` | 建边改并行原语数组 + 索引排序，**比较键与稳定性不变** | 需等价性验证（必须保持 TimSort 稳定序，否则改拓扑） | 小~中（估算：n=400 → 8 万对象/区域，一次性） | 若破坏稳定序则**改变道路拓扑** | 不碰 | 中 | 暂缓 |

### 2.3 后处理 / 校验链

| 编号 | 证据 | 优化候选 | 数学等价性 | 预期收益 | 影响质量 | 红线 | 风险 | 档位 |
|---|---|---|---|---|---|---|---|---|
| **C1** | `features\path\PathFeature.java:411-416`（4×`buildSpanMask` + 1×`buildPreserveCrossingGradeMask`）+ `:671-707`：见 M-5，5 次各建同一张 `centers` 索引表；`centers`=整条路（注释引用「实测 1804 站」），**每 chunk 调一次** | 5 个 mask 合成一次遍历：建一次 `indices`（改 `Long2IntOpenHashMap` 免装箱），只遍历一次 spans，按 `SpanType` 写 5 个 `boolean[]`（各 mask 互不相交、写 true 幂等） | **已证逐格一致** | 中（估算：省 4/5 建表 ≈7200 次 HashMap put+装箱/chunk） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **C2** | `FinalRoadStructureValidator.java:231-257`：`retainVerifiedBuildingGaps` 上限 = 站点数 n，**每轮 `:242` 重跑整条 validate**（内 `:53` prefetch + `:54-56` 完整 volume walk）；外层 `Road.java:584-713` 再 ×4~9 | ① prefetch 提到循环外、循环内只补新增站；② `Context.forWorld` 提出循环；③ `:255` 的 `validateFinalRoad` 与 volume walk 共享一次 `segmentList` 快照 | ①已证（预热不改值）②已证 ③需等价性验证 | 大（估算：省 N−1 次全路 footprint 三遍扫描 + `terrainSamplePositions` 整路重算） | 不影响逐格一致 | 不碰 | 中（prefetch 失败会抛 `IllegalStateException`，跳过冗余预热=少一条异常路径） | **立即做**（①②先做，③单独评估） |
| **C3** | `FinalRoadStructureValidator.java:82-89 / :113-118 / :119-123`：对同一 `positions` **遍历三遍**，key 重算 2 次 + `HashSet<Long>` 装箱 | 三遍合一遍（**保序**），key 只算一次存局部数组 | **已证逐格一致** | 中（估算：省 2 遍全 footprint + ≈4 万次 key 计算/次校验） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **C4** | `FinalRoadWaterValidator.java:60` 与 `:77`：**同一全量 footprint 查两遍** `cachedSamples`；`:62-65` 又重算一遍 key | 第一次结果 Map 与 `samplePositions(missing)` 返回值合并，去掉第二次全量查 | 需等价性验证 | 中（估算：省一次全 footprint `cachedSamples` ≈2 万次分片加锁查表） | 不影响逐格一致 | 不碰 | 中（两次查表间 LRU 逐出会改变 `unavailable` 分支） | 需真机实测再定 |
| **C5** | `features\path\decoration\system\AboveColumnClearer.java:139-161` + `:163-205`：每列 2 次 `getHeight` + 从 capY 扫到 capY+128，**每次迭代 `new BlockPos`**；预检(`:117`)与写入(`:65→:74`)各跑一遍 | `stateAtY` lambda 用复用的 `MutableBlockPos`（只读、不逃逸）；`removable.add` 仍用真 `BlockPos` | **已证逐格一致**（已读 `RoadConstructionSafety:756-759/439-450`，不保留 pos） | 小~中（上限 230 万分配/路，实际命中率待实测） | 不影响逐格一致 | 不碰 | 低 | 立即做 |
| **C6** | `RoadConstructionSafety.java:588-599 clearColumn` / `:601-615 fillColumn` 两遍各 `new BlockPos`；`:233-249` 中 `:176` 与 `:235` **各调一次 `hasWatercourseNearby`** | ①两遍循环复用 `MutableBlockPos`；②`fillColumn` 复用第一遍已读状态；③`hasWatercourseNearby` 去重 | ①已证 ②③需等价性验证 | ①小 ③中（每站省一次 5 列水面探测） | ①不影响 ③需先证明 | 不碰 | 中（③跨越同循环内相邻列写方块，可能真改判定） | ①立即做；③暂缓 |
| **C7** | `route\RouteRoadDataPatcher.java:221-320 convergeHeightProfile`：`maxRounds=min(n+1,4096)` 轮 × 2 趟 O(n)，全程 `ArrayList<Integer>` get/set 拆装箱 | ①`List<Integer>`→`int[]`；②循环内省 `isAnchor` 边界检查。**禁止**改成「前向+后向两趟」 | ①需等价性验证 ②已证 | 最坏 O(n²)（n=1804 时 ≈650 万次 get/set+装箱） | 不影响（若①过验证） | 不碰 | 高（①的 `null` 分支在 `int[]` 下需哨兵；当前调用点不可达但无第二调用方证明） | 需真机实测再定 |
| **C8** | `RoadDataValidator.java:31/46/52`：`ConcurrentHashMap<RoadData,…>`，而 `core\model\RoadData.java:12` 是 **record** ⇒ 每次 lookup 对**整条路所有列**做哈希；静态 Map 最多 4096 条，**跨存档不清**（全工程仅此 5 处引用） | ①改用预计算内容指纹(long)当 key；②`onServerStopping`/`CacheManager` 清缓存 | ①需等价性验证 ②已证 | ①中 ②待实测堆占用 | 不影响 | 不碰 | ①高（long 指纹碰撞 → 返回错误校验结果，直接决定「建不建路」）②低 | ②立即做；①暂缓 |
| **C9** | `pathfinding\cache\AccurateHeightSampler.java:317` sparse 路径返回值**被丢弃** + `:324-329` 全量重读 | 返回值 `putAll` 进 result，只对 wholeChunks 命中列重读 | 需等价性验证 | 小~中 | 不影响 | 不碰 | 低（同 C4 的 LRU 边界） | 需真机实测再定 |
| **C10** | `features\path\pathlogic\pathfinding\HeightProfileService.java:42-64`（targetY 缺失路径）：每站 `new ArrayList` + 每邻居 `new ChunkPos(sp).equals(chunk)` + Integer 装箱 + 每站一个 Stream | `:48` 改位运算比较；窗口求和改 `long sum+count` 或前缀和（掩码 = 本 chunk ? y : 0）。`Math.round((double)sum/count)` 与 `IntStream.average()` 一致 ⇒ O(n(2r+1)) → O(n) | **已证逐格一致** | 中（仅 targetY 缺失时） | 不影响逐格一致 | 不碰 | 低 | **立即做** |
| **C11** | `generation\RoadGenerationService.java:635`(同步) 与 `:996`(异步) **互斥**，各 1 次 `applyPlayerRouteOverrides`；`:1111-1120` 第二次校验仅在第一次失败时 | **结论性发现：不存在 override 重复应用，无可省的重复校验** | 已证（现状无重复） | 0（**排除项**，防止被重复讨论） | — | — | — | 已排除 |
| **C12** | `PathFeature.java:627-631`：每 chunk-slice 无条件 `LOGGER.info`，实参含 **4 次 `countMask` O(n)**；`:639-641` 再遍历一遍 | 4 个 `countMask` 合一次遍历；或挂 `isDebugEnabled()` | **已证逐格一致** | 小（估算：每 chunk 省 ≈7200 次数组读 + 一次格式化） | 不影响 | 不碰 | 低（会少一行观测日志） | 立即做 |
| **C13** | `PathFeature.java:149-153` + `:464`：每站 `new ProcessedSegment`(record) 入 HashSet；`Set<Long>`/`Map<Long,…>` 装箱 | `processedSegments` 改 `Long2ObjectOpenHashMap<BitSet>`；其余改 fastutil | **已证逐格一致** | 小（估算：每 chunk ≈1804 次 record 分配+哈希） | 不影响 | 不碰 | 低 | 立即做 |
| **C14** | `pathlogic\pathfinding\BridgeTransitionAdjuster.java:19-20`：`original = baseY.clone()` 但 `original` **全程只读**（`:29/30/37/47`） | `original` 直接别名 `baseY`（`adjusted` 仍独立） | **已证逐格一致** | 小（每次省 1 次 `int[n]` 分配+复制） | 不影响 | 不碰 | 低 | 立即做 |
| **C15** | `RoadConstructionSafety.java:558-580 gradeShoulderSide` 有装箱，但 `gradeShoulders`(`:296`) **无调用方**（`FinalRoadStructureValidator:211-214` 注释明说） | **死代码，不做** | — | 0 | — | — | — | 已排除 |
| **C16** | `core\SegmentPaver.java:170-175`：`roadType==1` 每列 `getBiome(pos).unwrapKey().map().orElse()` | 消 Optional 链 | 需等价性验证 | 小（`roadType==1` 占比待实测） | 不影响 | 不碰 | 中（按 chunk 缓存 biome 须先证明与逐列 `getBiome` 同值） | 暂缓 |

---

## 3. 目标二：优化游玩卡顿

### 3.1 必须先做的一件事：排除混淆项

| 编号 | 证据 | 说明 |
|---|---|---|
| **D1** | `logs\latest.log`：`World Alive scan gate` **3,748 行**，**全部 `[Render thread/INFO]`**，`11:20:04 → 11:27:12`（**428 s**）⇒ **8.76 行/秒写在渲染线程上**（占该会话 Render thread 总行 7161 的 52%）。`blocked` 从 2998 涨到 **11,243,989**（≈26,270 次/秒被拒）。来源是 `worldalive 1.2.1` + 打包方补丁 `plaincraft_world_alive_optimized` 的扫描闸 | **不在本次范围内（非 roadweaver）**，但它**必须**先被消除，否则 0.8.404 那次「改 `runtimeCoarseUseCpuInGameplay` 后掉帧消失」的 A/B **不可信**。该闸只按「每 3000 次拒绝」分频，在 26k/s 输入下等于无节流 |

### 3.2 运行时 / GPU 候选

| 编号 | 证据 | 优化候选 | 数学等价性 | 预期收益 | 影响质量 | 红线 | 风险 | 档位 |
|---|---|---|---|---|---|---|---|---|
| **D2** | 见 **M-3**（链路已逐跳核实）。真机 live：`coarseHeightBandCount=6`、`runtimeCoarseGpuActiveMinIntervalMs=14000`、`runtimeCoarseUseCpuInGameplay=false` | **间隔闸的计费点从「每次 `acquire`」上移到「每个 `CoarseHeightBatchRequest`」**（请求级令牌；**不改任何配置值、不写死上限**） | **已证逐格一致**（闸只决定「何时提交」，不参与任何采样值计算；`CoarseTerrainTileSampler.java:77-81` 已证分带逐样本等价） | **大**（估算：单瓦片墙钟 154 s → ≈15 s）；直接修复 0.8.403 A1 的副作用 | 不影响逐格一致 | 不碰 | 中（12 个 kernel 压进同一窗口，瞬时 GPU 负载升高，需看 1% low） | **立即做**（先按 §6 的 T-D2 做一次确认，再落地） |
| **D3** | `CoarseTerrainTileSampler.java:177-187`：`GPU_YIELD_NANOS = 40ms` **硬编码**，`:145` 每带**无条件** park；`sampleHeights` 返回即 kernel 已完成（同步 `readInts`） | ① 机制质疑：park 发生在 kernel **已结束之后**，买不到「缩短独占窗口」；② 改**占空比令牌桶** `yield = k × measuredBandNanos`（k 走配置，不写死） | 已证逐格一致 | 小（本配置 480 ms/瓦片 = 0.3%） | 不影响 | **现状的硬编码 40 ms 碰红线第 2 条**；候选即消除它 | 低 | 立即做（随 D2 同批） |
| **D4** | `pathfinding\cache\opencl\OpenCLCoarseHeightBatchSampler.java:371-375`：`synchronized (cachedProgram)` 是**全局**锁，把多 lane 串行化；而 `:613-614` workspaces 本就按 runtime 分，`OpenCLRuntime.java:208-224` 已有 per-runtime `operationLock()` | 全局监视器**收窄为 per-runtime** | 已证逐格一致（每 lane 独立缓冲，program 不可变） | 中（估算：预热期吞吐最高 ×2；游玩期默认走 CPU ⇒ 0） | 不影响逐格一致 | 不碰 | 中（显存 ×lane，≈97 MiB/lane） | 需真机实测再定（先 jstack 取证） |
| **D5** | `RuntimeCoarseGpuBudget.java:203-204,262-276,289-301`；live `openclCoarseGpuConcurrencyCap=4` | budget 上限（15）与 lane 数（2）量纲对齐 + 诊断标签如实标注「被 minThreads 覆盖」 | 已证逐格一致 | 小（诊断） | 不影响 | 不碰 | 低 | 立即做（**同时纠正认知：0.8.402 C3 在游玩期完全无效**） |
| **D6** | `CpuCoarseHeightBatchSampler.java:28-37`：内层每样本 `request.blockXAt(x)` + `z*request.sampleWidth()` | 循环不变量外提 + 强度削减（`x+=step`、`rowBase+=width` 替代乘法） | 已证逐格一致（int 精确，无溢出） | 小（估算 ≤5% 粗采样；**总 CPU <0.1%**，因粗采样量级远小于结构解析） | 不影响 | 不碰 | 低 | 暂缓（按用户「不做秒级微优化」口径） |
| **D7** | `runtime\RuntimePlayerActivity.java:15,23-42`：每 tick `new HashSet`/`new Position`/`removeIf` lambda；调用点 `ServerPlanningHooks.java:74` | 原地复用容器 + `(x,z)` 打包进 `long` + `Iterator.remove()` | 已证逐格一致（仅限流判据） | 小（几十 ns/tick，主要降 GC 抖动） | 不影响 | 不碰 | 低 | 暂缓 |
| **D8** | `RuntimeLoadPolicy.java:664-668,769-772`（nanoTime+CAS）；`CpuCoarseHeightBatchSampler.java:30` 每采样行触发 | 时间闸 → **版本闸**（server tick 序号），per-call nanoTime 降为 volatile 读 | 需等价性验证 | 小（估算 10–20 µs/瓦片） | 不影响 | 不碰 | 中（时序语义改动） | 暂缓 |
| **D9** | `RuntimeLoadPolicy.java:455-465` 自旋内每轮重算 `recommendedRoadWorkerThreads()`（`:472-579` 含 3–6 次 `Method.invoke`） | limit 提到循环外 / volatile 缓存快路径 | 需等价性验证 | 小（估算 0.2–0.6 µs/轮，仅预算耗尽时） | 不影响 | 不碰 | 低 | 暂缓 |
| **D10** | `opencl\OpenCLSubmissionGate.java:14,62-79,93`（公平锁 + `signalAll` 惊群） | 双条件队列 + 显式优先级交接 | 已证逐格一致 | 小（budget=1 时 N≤2，几乎无感） | 不影响 | 不碰 | 低 | 暂缓 |
| **D11** | `RuntimeLoadPolicy.java:219-231,260-271,769-796`；`ServerPlanningHooks.java:85,129`（同 tick 两次 `isChunkyActive()`） | 相位判定合并为**每 tick 一次快照**（volatile），消 4–8 次反射/tick | 需等价性验证（滞后 1 tick） | 小（0.3–0.8 µs/tick）——**不构成掉帧解释** | 不影响 | 不碰 | 中 | 暂缓 |
| **D12** | `RuntimeCoarseGpuBudget.java:66,79,89,104,113` | `TimeUnit.MILLISECONDS.toNanos(...)` 常量循环外提 | 已证逐格一致 | 极小 | 不影响 | 不碰 | 低 | 暂缓 |
| **D13** | `RuntimeLoadPolicy.java:523` 已 return，`:577` 同条件 ⇒ **不可达死分支**；`:198` 冗余条件 | 删死分支/冗余条件 | 已证逐格一致 | 0（可读性/审计正确性） | 不影响 | 不碰 | 低 | 立即做（清理项） |

### 3.3 本域的两条**否定结论**（可直接采信，避免重复排查）

1. **服务端 tick 不会被 RoadWeaver 的 sleep/park 阻塞**：全仓 grep，`throttle()` 仅 `RuntimeLoadPolicy.java:723/743` 两个 worker 侧调用点；`IdleRoadGenerationService.java:393-400` 在 IDLE 执行器线程。
2. **游玩期线程池不会 churn**：`recommendedRoadWorkerThreads()` 在 CRUISE / 界面 / 交互 / 移动 / 静止**各分支都返回 2**（都走 `clampGameplayThreadCap(2)`，`gameplayThreadCap=2`）⇒ 日志里 17 条 `streams=1↔2` 抖动**不触发** `prestartAllCoreThreads`（只影响 Streams 侧，属 R6 配置层）。
   另：`ConfigService.get()` 是 volatile 读 + 字段读，**不是**防御性拷贝 ⇒ 排除「配置读取太贵」。

---

## 4. 目标三：提升道路质量

### 4.1 ★ 先给结论：本报告的**全部性能候选都不以质量换速度**

任务书要求「禁止牺牲道路质量换性能」并「明确标出哪些候选会以质量换速度」。
经逐条核对：**§2、§3 中没有任何一条候选属于「以质量换速度」**。所有被判定「不等价」的方向都已在 §5.2 明确否决，没有被混进候选清单。
道路质量相关的红线（形态/坡度/隧道/桥梁/路灯逐格一致、不改判定逻辑与迭代顺序）在 4 个分域中**全部保持**。

### 4.2 质量提升候选（源自「已确认结论」里**尚未闭环**的项）

| 编号 | 证据 | 优化候选 | 数学等价性 | 预期收益 | 影响质量 | 红线 | 风险 | 档位 |
|---|---|---|---|---|---|---|---|---|
| **Q-1** | 已确认结论：「**0.8.336 的爬升闸门生效但不够** —— 9 条日志 `climbSkipped>0`（最高 319）说明爬坡窗口确实被拦；但目标路 `climbSkipped=0` 却仍被削 456 站 ⇒ **逐窗口坡度都 ≤0.5，整段累计却变陡**。结论：单窗口判据不够，需**整段累计里程/坡度判据**」 | 在既有单窗口闸之外，增加**整段累计**的里程/坡度判据（前缀和 / 滑动窗口增量维护，O(n)） | 新增判据 ⇒ **会改变道路剖面**（这是**有意的质量改动**，不是性能优化） | 质量：减少「逐窗口合格、整段变陡」的削站 | **会影响**（预期改善；须真机对比） | 不碰红线（不改既有判定，只**增加**闸门） | 中（新增闸门可能误伤本来就合格的路段，需按线程+站数配对日志验收） | 需真机实测再定 |
| **Q-2** | 已确认结论：「**0.8.334 隧道平缓化未触及陡段**：23_32 局 59 段 TUNNEL 中 **34 段坡度仍 > 0.35**（最陡 1.296），7 次 flatten 全是 0.008/0.017/0.020/0.030 → 0.000 的水平段收尾」 | 隧道段坡度平缓化覆盖「陡段」而非只收尾水平段；相关常量见 `RoadGradePlanner.java:18-37`（`TUNNEL_CORE_OVERBURDEN` / `TUNNEL_CHORD_SEGMENT_STATIONS` 等）、`RoadTunnelBuilder.java` | 会改变隧道剖面 ⇒ **有意质量改动** | 质量：隧道坡度 | **会影响** | 不碰红线 | 中~高（隧道剖面牵动洞口/埋深，历史上有「洞口埋地」副作用链） | 暂缓（需专项立项，先离线复算） |
| **Q-3** | 0.8.387 `convergeHeightProfile` 锚点保护（短桥不再被压平）。已确认结论标注 **⚠️ 待真机验证**；日志关键字 `applied player-route overrides` 且无 `produced invalid RoadData` | **不需要新优化**，属**验收动作** | — | 验证既有改动是否达成 | — | 不碰 | 低 | 立即做（验收，非开发） |
| **Q-4** | `PathFeature.java:185` 注释：`tunnel rejected` 单存档累计曾达 13,224 次；本轮实测 2026-10-01 会话仍有 **203 次 `tunnel rejected`** 与 **111 次 `tunnel built`**（拒绝率 203/314 ≈ 65%），拒绝原因全部为 `see_blocked_tunnel_section`；`insufficient-overburden` / `insufficient-wall-overburden` 均为 **0** | 先**量化**「被拒的隧道改走地表切方后，路面质量实际差多少」，再决定是否值得优化 `see_blocked_tunnel_section` 的判定 | — | 待实测 | 若改判定 ⇒ 会影响 | 不碰 | 中 | 需真机实测再定 |

---

## 5. 三档结论

### 5.1 第一档 · 立即做

**判据**：数学等价已证（或等价性论证充分且风险低）+ 不碰任何红线 + 不影响逐格质量 + 风险低。

| 序 | 编号 | 一句话 | 数学等价性自证要点（一句话） |
|---|---|---|---|
| 1 | **D2** | 间隔闸计费点从「每次 `acquire`」上移到「每个 `CoarseHeightBatchRequest`」 | 闸门只决定**何时**提交 kernel，从不参与任何采样值计算；分带与整块的逐样本等价已由 `CoarseTerrainTileSampler.java:77-81` 证明（kernel 内 `index` 唯一决定 x/z，`scratch_base = index*node_count`，`align_to_cpu_grid` 无漂移） |
| 2 | **D3** | 去掉 40 ms 硬编码 park，改自适应占空比（顺带消除红线第 2 条隐患） | park 只影响**何时**继续，不改变任何返回值；`sampleHeights` 返回即 kernel 已完成，park 时长与结果无关 |
| 3 | **C1** | 5 个 mask 合并为一次遍历、建一次索引表 | 5 个 mask 由**同一个** `centers` 与**同一个** `road.spans()` 派生，索引表逐元素相同 ⇒ 合并不改变任何 mask 的取值；各 mask 写入互不相交且写 true 幂等 ⇒ 遍历顺序无关 |
| 4 | **C2（①②）** | prefetch 与 `Context.forWorld` 提到 `retainVerifiedBuildingGaps` 循环外 | 预热按定义只做「填充缺失样本」，对已就绪项是幂等写入；把幂等操作从 N 次降到 1 次不改变任何读到的值 |
| 5 | **C3** | `FinalRoadStructureValidator` 中同一 `positions` 的三遍遍历合并为一遍（保序） | 三遍都是对**同一不可变** `positions` 的只读遍历，合并且在同一次序下产生相同的中间集合与相同的短路结果 |
| 6 | **C10** | `HeightProfileService` 窗口求和改前缀和 + 去 `new ChunkPos(...).equals(...)` | 整数掩码求和与逐项累加在 `long` 范围内严格相等（无溢出）；`Math.round((double)sum/count)` 与 `IntStream.average()` 在同样输入下同值（两者都是同一有理数的最近整数，且 count>0） |
| 7 | **A-5** | 消除 `BasicAStarPathfinder:114` 的白算（M-4） | `blocksPlanningObstacle` 首句即 `shouldAvoidPlanning`，其返回 true 时 `:111` 已 `continue` ⇒ `:114` 求值必为 false ⇒ `:115` 从未执行 ⇒ 删除白算不改变 `obstaclePenalty` |
| 8 | **A-6** | `Math.hypot(bx-ax,bz-az)` 提到端点级只算一次 | 入参只依赖搜索端点（循环不变量），调用同一 `Math.hypot` 同参数 ⇒ 返回值逐位相同 |
| 9 | **A-7** | `MountainSwitchbackPlanner` 三项循环不变量外提 + hypot 8 项预计算表 | 被外提的三项入参均与本邻居无关（循环不变量）；预计算表由**同一个 `Math.hypot`** 生成 ⇒ 查表值与现算值逐位相同 |
| 10 | **A-9** | `CoarseHeightAwarePlanner` 4 项提到竖直候选循环外 + `best` 换 primitive map | 4 项只依赖 (nextX,nextZ)，与 nextY 无关；`CoarseTerrainRegion` 构造后不可变 ⇒ 外提不改变取值；primitive map 的键值语义与 `Map<Long,Double>` 相同 |
| 11 | **A-11 / A-12** | A* 内部容器换 fastutil + 邻居探测全程 int | 容器替换只改「怎么存」，`Long2ObjectOpenHashMap` 与 `HashMap<Long,V>` 对相同的 long 键给出相同的值语义；int 探测与原 `BlockPos.offset()` 的坐标算术逐位相同 |
| 12 | **B-2** | 结构 AABB 并集快速拒斥 | `B_i ⊆ U` ⇒ `roadBox ∩ (∪B_i) = ∅` ⇔ 并集外；并集内的分支与原实现逐条相同 ⇒ 返回 false 的集合完全一致（纯集合论，无浮点参与） |
| 13 | **B-7 / B-4 / B-5 / D5 / D13 / M-2 补丁 / C8② / C12 / C13 / C14 / D1(移交)** | 诊断直方图、死测试路径清理、诊断探测降频、诊断标签诚实化、死分支删除、护栏守卫测试、`VALIDATION_RESULTS` 清理、日志 `countMask` 合并、record 分配消除、`clone` 别名 | 均为「不改计算、只改日志/死代码/存储方式」⇒ 逐格一致（C13/C14 的等价性在于：集合成员语义与数组内容不变，`original` 全程只读） |

> **配批建议**：第 1–2 项（D2/D3）单独成一批（动 GPU 提交时机，需真机看 1% low）；第 3–14 项可合成一两个「零行为改动」批，按项目既有惯例配一个守卫测试 + 全量 `:common:test`。

### 5.2 第二档 · 需真机实测再定

**判据**：方向明确、收益量级大，但**收益或等价性缺一环**，必须先拿实测。

| 序 | 编号 | 缺什么 | 拿到什么就能定 |
|---|---|---|---|
| 1 | **M-1 重测**（**最高优先**） | 同一次、同口径的真实 CPU 采样（结构解析 vs 粗采样 vs 其余） | 决定后续所有「减少计算量」投入的方向；若结构解析不再是第一热点，B-1/B-3/B-6 全部降级 |
| 2 | **B-1** | T-1（`ensureResolved` 调用/去重后键数/命中率）+ T-2（单次绝对耗时） | `blockVisits / distinctKeys ≫ 1` ⇒ 模板级记忆化收益大，值得做；≈1 ⇒ 否决 |
| 3 | **A-1 / A-2** | G-1（每类搜索真实展开节点数）+ G-4（switchback 调用频次） | 把「ns/展开」收敛成「ms/局」，判定是开局大头还是游玩卡顿大头 |
| 4 | **A-3 / A-4 / A-10** | G-2（`tileAt`/`boxesByChunk.get` 调用数 + JFR 分配采样）+ G-3（NEAR_WATER 命中率） | 装箱是否真被标量替换（本机 3.5 ns/次只是上界）；A-4 还须用真实瓦片集做旧/新逐点对拍 |
| 5 | **D4** | 预热期连采 5 次 jstack，统计阻塞在 `OpenCLSubmissionGate.acquire` vs `synchronized (cachedProgram)` 的线程数 | ≥2 线程阻塞在后者且日志显示 `lanes=2` ⇒ D4 成立 |
| 6 | **C4 / C6③ / C7 / C9 / C16** | 各自的「调用次数 / LRU 边界 / 轮数」计数器 | 见 §6 待实测清单 |
| 7 | **Q-1 / Q-2 / Q-4** | 真机剖面对拍（同 seed、同结构集、同路线） | 质量改动的验收 |

### 5.3 第三档 · 暂缓（含明确否决）

| 类别 | 项 | 理由 |
|---|---|---|
| **前提被本轮实测推翻** | 「加大 `PREPARED_START_CACHE_CAPACITY`」、「持久化结构解析结果落盘」 | `evictions=0`、容量只用 12.8%（M-6）⇒ **零收益且改红线**；源码 `StructureAvoidanceService.java:4499-4504` 预先写下的判据已被实测确认 |
| **前提被本轮归因复核推翻** | 「P2-1 同 chunk 双键重复解析」当性能项排期 | 实测仅 **2 例**（M-6）⇒ 降级为**正确性修复** |
| **可行方向不存在** | 「把 E 阶段预筛前移到枚举层以砍掉 94.7% 空白」 | M-2：预筛与生产者谓词是同义反复，**不存在可证明等价的更强早退**；要做得复刻 `structure.generate()` 内的生物群系/生成点判定 ⇒ 需单项立项 |
| **踩红线** | 降 `BLOCK_UPDATE_FLAG`(3→2/0)、`hypot→sqrt` 或任何 1-ulp 数值改写、`convergeHeightProfile` 改两趟 Lipschitz、加宽桥锚点判定、任何写方块顺序调整、陡坡之字形根因（A* 坡度惩罚形状）、`DelaunayPlanner` 算法升级与 `MST/KNN` 比较键改写、子集 snap、精采样 GPU 绕 tolerance=1、固定线程数/固定间隔、重开拉直、Chunky/Streams/Voxy 代码级改造 | 分别踩红线 1 / 1 / 1 / 1 / 1 / 1 / 1 / 1 / 3 / 4 / 2 / 7 / 6 |
| **已有实测反证 / 收益为零** | 整块阈值 2→64 或改批量大小；`AccuratePathHeightResolver.ResolveMemo`；`AccurateSampleStore.saveGrid` 默认实现（已被 `FileBackedAccurateSampleStore:213-244` 覆写）；`clusterBoxes` 重建（实为有 ThreadLocal 缓存）；`Math.pow(v,2.0)→v*v`（本机实测 1.59 vs 1.62 ns，同速且带 1 ulp 风险）；`planningFingerprintPayload` 缓存（0.8.403 P2-2 已暂缓） | 见分域报告证据 |
| **复核后不成立** | 「`applyPlayerRouteOverrides` 重叠应用两次」、「写入值与原值相同则短路（加 `getBlockState`）」、`gradeShoulders` 优化（死代码）、「服务端线程被 sleep/park 阻塞」、「游玩期线程池 churn」 | C11 / C 域否决 / C15 / D 域否定结论 1-2 |
| **秒级微优化（按用户口径不做）** | D6 / D7 / D8 / D9 / D10 / D11 / D12、A-13 / A-14 | 量级为 ns~µs，不构成 10~20 分钟生成耗时的解释 |

---

## 6. 待实测清单（按优先级）

| # | 要回答的问题 | 怎么做 | 判据（日志该出现什么） |
|---|---|---|---|
| **T-M1** | 结构解析与粗采样**谁才是当前第一 CPU 消费者** | 同一次会话、同口径采样（JFR 或 profiler）同时统计 `RW-NativeStructure-*`、`RW-CoarseTile-*`、`RW-Worker-*`、`RW-Initial-*` 的真实 CPU | 两者占比之差 >2× 才有立项意义；否则「70%/29s」作废 |
| **T-D2** | D2 是否真在真机发生 | 跑到新区域触发粗采样，grep `coarse terrain load timing`；**对照组**把 `coarseHeightBandCount` 临时设为 1 | `heightBatchWorkerMs ≈ (bands−1) × runtimeCoarseGpuActiveMinIntervalMs` ⇒ 成立（本轮 2026-10-01 会话已见 `heightBatchWorkerMs / Σsampled ≈ 62.7 s/瓦片`，与模型同量级） |
| **T-B1** | B-1 的真实收益上限 | 给 `ensureResolved` 加计时 + 计数 | `RoadWeaver template occupancy: resolves=<> distinctKeys=<> blockVisits=<> aabbs=<> elapsedMs=<>`；`blockVisits/distinctKeys` 即复用倍数，**≫1 才有收益** |
| **T-B2** | 单次 `ensureResolved` 绝对成本（目前**零观测**） | 两端 `nanoTime()`，按模板分桶 | `RoadWeaver occupancy resolve timing: templateId=<> blocks=<> elapsedUs=<>` |
| **T-B3** | P2-1 真实幅度是否被 64 采样门控低估 | 把 `DIAG_PROBE_INTERVAL` 临时放宽到 1，或改按 chunkKey 计数 | 仍 ~10⁰ ⇒ 确认无重复解析大头；跳到 ~10³ ⇒ 重估 Holder 修复优先级 |
| **T-B6** | 为何预筛 `skipped=0`（且护栏恒空跑） | 给 `isPotentialStructureChunk` 加 调用/potential/nonPotential 三计数 + `placement.getClass().getSimpleName()` 分布 | `RoadWeaver prefilter probes: calls=<> potential=<> nonPotential=<> placements={...}`；非 `RandomSpread` 占多数 ⇒ 保守放行是主因（**注**：M-2 已证明对 `StructurePredictor` 的候选它必然恒 true） |
| **T-S1** | `emptyResults=3960/4183`（94.7%）的成因分布 | 在 `resolvePredictedStructureSetBoxes` 的 `:2546` 早退与 `generate` 返回空两处分桶计数 | `RoadWeaver structure resolve outcome: notPotential=<> emptyAfterGenerate=<> generated=<>` |
| **T-G1** | 每类搜索的真实展开节点数（**四寻路器目前无任何计数**） | 加计数器 | 这是把「ns/展开」收敛成「ms/局」的唯一依据，缺它则 A-1/A-2/A-3/A-4 的优先级只能停在估算 |
| **T-C2** | `retainVerifiedBuildingGaps` 真机平均轮数 | grep 日志 `retained all internal verified-building protection gaps` / `persisted construction refusal gaps`，配同次 route 的 `finalValidationMs` | 平均轮数 ≫1 ⇒ C2 收益大 |
| **T-Q1** | 被拒隧道（203 次 `see_blocked_tunnel_section`，本轮实测）改走地表切方后的路面质量差多少 | 同 seed 同结构集，对被拒段做地表/隧道两条剖面离线对比 | 差异显著 ⇒ Q-4 值得立项 |
| **T-D4** | 全局 `synchronized (cachedProgram)` 是否真在预热期串行化 GPU | 预热期连采 5 次 jstack（间隔 2 s） | ≥2 线程阻塞在 `sampleHeightsOpenCLLocked` ⇒ D4 成立 |

---

## 7. 阶段性与范围说明

1. **API 签名变更标记**：本报告**没有任何候选**需要修改 `net.shiroha233.roadweaver.api`（含 197cc1b 新增的 `RoadNetworkApi` / `registerStructureEndpointOnce`）。若后续实现阶段确有需要，须在报告外单独标记，因为长城 mod 通过 **SHA 白名单 + 反射**依赖它（`plaincraft_great_wall/roadweaver-interop.sha256`，当前白名单 = `0.8.406-perf-batch` 的 SHA `bed13208…54dd1`），签名或 SHA 变更会导致联动失败。
2. **覆盖缺口**（4 个子智能体抽样 grep、未逐行读，建议后续单独立项）：`RoadPlanningService`(100KB)、`RoadPlanningReadinessIndex`(43KB)、`SharedEndpointPathReuse`(33KB)、`ConnectionFallbackService`、`PlannedPathCache`、`PathSpanExtractor`(78KB)、`AccurateHeightSampler`(50KB)、`opencl/**` 的其余部分。
3. **本报告未做的事**：未运行 gradle（未 build / 未 test / 未 deploy）、未改任何源码或配置、未做任何 git 写操作。
4. **真机版本提醒**：`logs\latest.log` 显示客户端当前装的是 **`roadweaver 2.3.1-…-0.8.405-audit-io-degradation-and-counter-cleanup`**，**不是 0.8.406-perf-batch** ⇒ 0.8.406 的 6 项优化**尚未被真机验证**。

---

## 附录 A · 取证方法与命令（可复现）

- **分支/HEAD/工作区**：`git rev-parse --abbrev-ref HEAD`、`git log --oneline -8`、`git status --porcelain`（= 0 行）、`git show --stat 821511c`
- **真机日志**：`D:\minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\logs\`
  - `2026-10-01-2.log.gz`（491,893 行，0.8.403 期，本报告主要数据源）：用 PowerShell `GZipStream`+`StreamReader` 解压后按关键字聚合
  - `latest.log`（11,281 行）：第三方闸日志统计
- **反编译**：`C:\Program Files\Java\jdk-21\bin\javap.exe -c -l -p`，class 取自 `common\build\classes\java\main`（**未构建**）
- **微基准**：JDK 21.0.5，N=2×10⁷，best-of-5（仅供量级参考，非 mod 实测）
- **计数口径**：所有「Σ」均为对日志行的求和；`coarse terrain load timing` 与 `structure snapshot timing` 的单行值都是**增量**（源码用 `TimingSnapshot.minus()`），因此可直接相加

## 附录 B · 四份分域原始报告

| 域 | 文件 | 负责范围 |
|---|---|---|
| A | `D:\_临时分析\roadweaver-perf-080407\agentA-pathfinding.md` | 寻路 / 规划热路径 |
| B | `D:\_临时分析\roadweaver-perf-080407\agentB-structure-io.md` | 结构解析 + 持久化 IO |
| C | `D:\_临时分析\roadweaver-perf-080407\agentC-construction.md` | 施工写方块 + 后处理校验链 |
| D | `D:\_临时分析\roadweaver-perf-080407\agentD-runtime-gpu.md` | 运行时调度 + GPU + 卡顿 |

> 该目录为临时取证目录（非交付物），按项目惯例可在实施阶段结束后清理。
