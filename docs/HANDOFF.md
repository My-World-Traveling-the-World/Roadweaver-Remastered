# 交接台账（HANDOFF）

> **规则：每次停下前必须更新本文件。** 接手方靠它判断「什么做完了、什么没验证、哪里是半截」。
> **「我没能验证什么」这一栏是核心** —— 写得越老实，接手方越快。

---

## 模板（复制这一段填写）

```markdown
## 第 N 轮 ｜ <开始时间> → <停止时间> ｜ HEAD `<commit>` ｜ `PASS tests=<N> skipped=0 aborted=0 failed=0`

### 一、改了什么
| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|

### 二、我验证了什么（**必须附负控做法**）
| 新增/改动的用例 | 负控做法（故意改坏什么） | 结果（红/绿） |
|---|---|---|

> ⚠️ 只写「测试通过」不算验证。**必须写「我改坏了什么，它红了没有」** ——
> 本项目已实证：992 全绿的同时，9 处故意破坏（含「模板桥完全不落块」）也全绿。

### 三、我**没能**验证什么（最重要的一栏）
- **需要真机的**：
- **需要真实存档的**：
- **我怀疑但没有证据的**：
- **我知道它现在会坏但没修的**：

### 四、遗留半截的东西
| 文件 | 什么状态 | 怎么补 |
|---|---|---|

### 五、下一轮我会先做什么（如果你没接手的话）

### 六、我踩到但已解决的坑（省接手方时间）
```

---

## 最新进度

第 32 轮按用户明确顺序补道路消费方前置条件：有长城河流→长城→道路，无长城河流→道路；源码 `bd61dd8`，回归 1222 项，7 项变异。只改道路及其测试，河流发布与长城订阅的测试归对应仓库。下一步包括河流发布方异常语义、三模组现场时序、内部执行预算与 4096 工作集；详见末尾及 ROADMAP。

## 已完成轮次记录

> 第 0～12 轮已完整迁到[交接历史](archive/HANDOFF-ROUNDS-00-12.md)，包含 P0 修复、守卫补齐和首批性能对照；以下保留近期记录。

## 第 13 轮 ｜ 2026-11-16 ｜ 补齐 BAND 的巨树保护和水域成本 ｜ 源码 `e3f262d` ｜ `PASS tests=1053 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 性质 |
|---|---|---|
| `HugeTreeReservation`、`BandPathfinder` | 每区块一份预留位图，同时服务 API/施工和选线快照；按路宽扩展，在 FMM、回溯和弹性带复用跨边禁行判断；落块仍查实时预留 | 行为变更 `dd24a93` |
| `BandPathfinder`、`PathTerrainField` 及粗/精地形场、`RawPathSearch` | 用列水体和河/海标记复用已有采样；按水陆价差为较贵一侧附加有限倍率，价差或权重为零不读取水标志；桥隧判定仍走原规则 | 行为变更 `e3f262d` |
| `PlannedPathFingerprintService`、配置元数据、语言和回归套件 | 只失效未施工候选路径缓存；对齐现有水陆配置语义，保留 GSON 字段名；新增长树、跨边、宽度、成本等九项用例 | 分别随上述提交；存档编码和施工完工键保持原样 |

### 二、我验证了什么

干净的 `2afe71f` 同步后基线 1044 项，全量回归最终 1053 项通过，`:fabric:compileJava` 同跑通过。默认水域成本在 21×9 格合成湖面减少至少一半的涉水站；水柱和仅河/海群系两类均有效。启用时遍历 189 个网格水标记，禁用时不查；高价情况下唯一过水路线依然可达。

| 负控 | 目标回归 | 结果 |
|---|---|---|
| 删掉巨树跨边禁行，仅保留格点与原走廊约束 | `treeColumnBetweenGridPointsClosesOnlyPassage` | 红：路线越过格间唯一的预留列 |
| 把巨树膨胀半径设零 | `roadWidthAndSmoothingClearTheLiveReservationFootprint` | 红：道路边缘侵入预留 |
| 把水陆代价倍率恒置 1.0 | `actualRouteUsesWaterColumnAndBiomeFlagsToLimitLakeExposure` | 红：默认成本不再避开合成湖面 |
| 默认水体判断只读水柱、漏掉河海群系 | 同上 | 红：仅有河/海群系标记的夹具失效 |

每条变异独立执行、校验替换锚点和目标断言，然后 `git checkout -- <file>` 从索引恢复；还原后重跑正向。拓扑另做无生产改动的 Python 原型：900 点 `(48x, (31x+17z) mod 53-26, 48z)`，`x,z=-15…14`，三维限长 76；全对枚举检查 404,550 次，九桶法检查 8,798 次，两者准入集合完全相同（3,119 对）。密集 400 点都在一个桶内，两者各 79,800 次。该原型没测测地场、MST 或真机耗时。

### 三、我没能验证什么

- **需要真机的**：Erdtree 实际回调和落块、真实地形下的绕行及桥隧召回，首开和 Chunky 的帧时/TPS；离线不推断进档提速。
- **需要真实存档的**：旧世界加载及新旧道路衔接，水陆代价变化不会自动重铺已有完工区块。
- **怀疑但没有证据的**：连续回填时台账排序与写盘可能拉长主线程；已确认调用链及全量排序，尚缺现实条目规模和耗时。
- **已知未改的**：粗拓扑仍以高度评分；`crossing` 按用户决定保持现状；折返、宽度栅格化和路口合并的最终占地还没有巨树专项守卫。

### 四、遗留半截的东西

巨树快照只给 BAND 及其端点和平滑，不跟随后续每次几何重写；施工实时保护仍在。小于粗格步长的水带不保证在 FMM 水成本里被采到，后段的精确过水检查不变。

### 五、下一轮我会先做什么

先量延迟回填重复检查和台账耗时，再推进拓扑稀疏候选的产物对拍，另给最终道路占地加保护守卫。详细入口和验收标准在[下一阶段路线](ROADMAP.md)。

### 六、我踩到但已解决的坑

只封格点会漏掉两个 FMM 粗格之间的窄障碍，只测中心线会漏掉宽路。水体若只判海平面以上的湿列，则河流或海洋群系标记会被漏掉；两路用同一个地形场接口和实际 BAND 入口守卫。

## 第 14 轮 ｜ 2026-11-16 ｜ 审核保留能力并消除装饰的重复里程计算 ｜ 源码 `54c6248` ｜ `PASS tests=1054 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动 | 性质 |
|---|---|---|
| `BuildFrame`、`PathFeature`、`DecorationPlanner` | 建造帧按有序中心线累加一次水平里程，各区块常数时间查询候选；保持距离牌优先、候选并列、坐标及装饰随机调用顺序 | 行为等价 `54c6248` |
| `BuildFrameTest` | 复用已登记测试类，与旧判定逐站对照，覆盖反向、重合、坡度、斜向、多档间距和目标两侧并列 | 测试基建 |
| `ROADMAP.md`、`STATUS.md` | 把保留能力的可核实优化点汇到现有路线和现状 | 文档 |

### 二、我验证了什么

同一条 257 站路线，正反两向、5 档间距，共 2,570 次候选判定与旧算法相同；另验证间距 5 时相邻的 4、6 两站都保留。修复夹具误设的条件后，从索引恢复负控，再跑外置 Gradle 8.8 / JDK 21 daemon：1054 项通过、无跳过，`:fabric:compileJava` 成功。

| 负控 | 目标回归 | 结果 |
|---|---|---|
| 候选与下一站等距时把 `<=` 改成 `<` | `cachedLampSpacingPreservesLegacyChoicesForIrregularStationOrder` | 红：间距 0（归一为 1），站 5 预期保留，改后丢失；锚点唯一，随后 `git checkout -- BuildFrame.java` 恢复该生产文件（以完整仓库路径执行） |

操作数上界：4096 站且各内站都做候选判断时，旧算法调用水平距离 8,390,653 次；新算法建索引加查询最多 8,189 次，额外里程数组 32 KiB。这是按调用路径计算的合成上界，不是 Java 耗时或真机加速比。

### 三、我没能验证什么

- **需要真机的**：手绘预加载最长等待、地图缩放帧时、桥隧及装饰实际落块、Chunky/Streams/C2ME 混合负载。
- **需要真实存档的**：旧档重开、进度负载和写盘次数；本轮没有改 Codec 或任何持久化格式。
- **怀疑但没有证据的**：大量 READY 区域时文件排序与写盘耗时会拉长 tick；代码确认每次增量重写，缺真实耗时。
- **已知未改的**：手绘仍同步等 FULL；共用桥仍逐对发 INFO；地图折线仍逐帧抽点。`crossing` 继续按用户决定保持现状。

### 四、遗留半截的东西

桥模板的占地预测和落块仍各自投影；水位和保护依赖施工时状态，只能共用纯坐标。村庄快照已有分块，后续应复用未变化的桶；不能略过村庄包围盒、端点特例或保护缺口重验。

### 五、下一轮我会先做什么

先给 `RouteChunkPreloader` 的等待、取消和重试抽离线调度，验证慢块不能阻断普通 tick 后再接生产；低风险独立项是共用桥的边界缓存和按需诊断。地图、隧道、台账和跨模组的验证入口见[深化路线](ROADMAP.md#已保留能力的深化路线)。

### 六、我踩到但已解决的坑

旧判定的前一站是从当前累加结果减去一段，不能直接读前缀数组的上一项，否则浮点近似平局可能改选灯位。测试也不能笼统要求每档都必须出现正反例：最小间距的输入确实可以选中所有内站，普通站由其他档覆盖。

## 第 15 轮 ｜ 2026-11-16 ｜ 合并后续任务清单 ｜ 源码未变 `54c6248`

### 一、改了什么

文档：把离线三项与各功能的优化建议合到 [ROADMAP 的统一任务清单](ROADMAP.md#统一任务清单)，标出 12 个任务的顺序、状态、依赖和验收；现场待定改动独立列出。

### 二、我验证了什么

逐项核对前两轮的任务和验证边界。纯文档改动，无生产变异；最近一轮源码回归 `PASS tests=1054 skipped=0 aborted=0 failed=0`、Fabric 编译通过，本轮未重跑。

### 三、我没能验证什么

尚未新采区块主线程等待、写盘、地图真机耗时，也未验证真实存档衔接和桥隧召回。稀疏候选的数字仍来自合成原型，未接入 Java。

### 四、遗留半截的东西

手绘加载仍阻塞普通 tick；常规回填同轮计划仍重复查询；终态几何未补统一保护守卫；拓扑候选仍未稀疏化。灯位里程优化已交付，灯座探测尚未测量。

### 五、下一轮我会先做什么

先做 RW-01 手绘预加载的离线调度，再分别推进 RW-02 回填、RW-03 终态保护、RW-04 稀疏候选。台账与 Chunky 写盘可先加计时，测到热点再改落盘。

### 六、我踩到但已解决的坑

手绘主动加载和自动回填不能合成同一种策略；前者保留主动请求，后者不能请求新块。测地候选稀疏化也与按可建性换边分开验收，避免把提速与行为变化混在一次提交。

## 第 16 轮 ｜ 2026-11-16 ｜ 手绘主动预加载按 tick 推进 ｜ 源码 `79c6ad0` ｜ `PASS tests=1059 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为变更：`RouteChunkPreloader`、`RoutePreloadWork`、`RouteBuildService` 把主动 FULL 预加载拆成每世界一条任务、最多 32 个在途请求；就绪才消费，保留近处优先发起、进度和有限重试。运行时暂停会撤票，下次继续已排工作；路线删除、替换或停服取消当前会话。
- `ServerPlanningHooks` 走已有的非阻塞区块 Future 入口并持有独立票据，`RoadTaskExecutor` 在普通规划暂停前处理手绘任务；地图重试重新登记正确的失败路线。
- `RoutePreloadWorkTest` 增加 5 个离线测试并登记进回归套件。架构基线只减少，存档和配置格式不变。

### 二、我验证了什么

同步后的基线 1054 项；正向及逐条恢复变异后，外置 Gradle 8.8 / JDK 21 全回归 1059 项通过，Fabric 编译成功。用 `javap -c` 查本地 1.21.1 `ServerChunkCache.getChunkFuture`，主线程分支内确实调用 `managedBlock`。夹具覆盖在途上限、慢首块和后续补位、失败和缺 FULL、暂停保留队列、迟到请求不铺路。

| 负控 | 目标回归 | 结果 |
|---|---|---|
| 遇到首个未完成请求就返回 tick | `slowNearestChunkDoesNotBlockReadyChunksOrRefillingTheWindow` | 红：后续已就绪块未施工 |
| 停止时删掉释放在途票据的循环 | `stopReleasesAllTicketsAndIgnoresLateCompletion` | 红：票据未释放 |

每条变异唯一锚点后单独跑，使用 `git checkout --` 恢复生产文件并复验。

### 三、我没能验证什么

- **需真机**：集成服务端和独服票据实际时序、暂挂期间的多维度交互、C2ME/Chunky 混合负载及 p95；离线结果不能换算成帧率或进档速度。
- **需存档**：中途退出后真实存档上常规回填的进度。手绘在途会话自身不持久化，重开不保证恢复同一轮主动预加载；道路和已施工状态仍按原存档规则恢复。
- **未测**：一轮多个立即就绪块的落块成本；每世界限 32 个在途，不代表每个 tick 都有固定耗时。`crossing`、真实几何 A/B 与台账算法均未动。

### 四、遗留半截的东西

手绘主动加载已由服务端 tick 推进；普通延迟施工仍有重复加载状态和计划查询。真实旧档重开、停服时序以及地图状态仍需现场验收。

### 五、下一轮我会先做什么

继续常规回填的同轮复用及最终占地守卫，穿插独立的共用桥包围盒缓存。详情见 [任务清单](ROADMAP.md#统一任务清单)。

### 六、我踩到但已解决的坑

只删外层等待还不够：Minecraft 的公开 Future 入口会在服务端线程内等待。取消或停服也不能取消共享的引擎 Future，只撤销本任务持有的票据，并丢弃该会话对迟到 Future 的消费。

## 第 17 轮 ｜ 2026-11-16 ｜ 共用桥、稀疏拓扑和地图折线 ｜ 源码 `a6fc907` ｜ `PASS tests=1064 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 任务 | 文件 | 改动 |
|---|---|---|
| RW-05 | `BridgeShareService`、`BridgeShareDetector` | 行为等价 `8f8dc47`：每桥计算一份包围盒，保持原桥对顺序、选主和吸收；逐桥/逐对细节降为 DEBUG，调用方汇总及缺端点告警仍有。 |
| RW-04 | `TopologyCandidates`、`GeodesicMatrix`、`MinimumSpanningTree`、`PipelinePlanner`、`RoadPipeline` | 行为等价 `c83f5c7`：有限长且不密集时，仅枚举邻桶再按原三维限长判断，存储双向测地权重并取原较小值，Kruskal 和影子选网按原同权顺序。无限长、密集区域仍用密集枚举。 |
| RW-06 | `MapRenderers`、`RoadMapScreen` | 行为等价 `a6fc907`：地图屏幕按不可变折线列表身份缓存两档抽样世界坐标；缩放档、短尾、每帧视口及图层顺序不改。 |

### 二、我验证了什么

外置 Gradle 8.8 / JDK 21 daemon 顺次通过 1060、1062、1064 项无跳过；每轮同时通过 Fabric 编译。稀疏 Java 样本与全对版都保留 3,119 边，准入判据调用从 404,550 降到 8,798。有限长度样本的 6 条边与单独双向 FMM 输出逐项相同，最终森林 4 边 / 4 分量，两个孤点一致。候选单阶段微测：同一 900 点，各 JVM 暖机 100 次，11 组每组 30 次交替，3 次 JVM 中位数范围由全对 538–543 µs 降到邻桶 192–205 µs；只含候选枚举和邻接构建，不含 FMM、选树、存档和真机耗时。

| 负控 | 案例 | 结果 |
|---|---|---|
| 共用桥半径等距比较从 `<=` 改 `<` | `sharesAtExactRadiusAndPreservesFirstOwnerAndDistantBridge` | 红，丢掉恰好在半径上的合法桥 |
| 稀疏候选漏掉对角桶 | `sparseForestMatchesAllPairsAcrossNegativeBucketsAndUsesBothDirections` | 红，跨双轴桶的合法边丢失 |
| 稀疏权重只保留正向 | 同上 | 红，起伏地形的选边权重变了 |
| 地图丢掉不足一个缩放步长的短尾 | `cachedFrameRechecksViewportAndPreservesCrossScreenAndTailOrder` | 红，缺短尾 |
| 地图换快照时保留缓存 | `replacingOrDeletingSnapshotNeverDrawsPriorRoadsAtSameZoom` | 红，仍显示旧路 |

每条都独立命中唯一锚点、从索引 `git checkout --` 恢复，恢复后正向重新通过。

### 三、我没能验证什么

- **需真机**：各阶段共同耗时、实际地图帧时与 Xaero 叠图、共用桥真实落块和不同加载顺序、密集路网的 FMM 总耗时。
- **需实际存档**：大范围老地图重开与路线删除后的实景；本轮没改存档格式。
- **尚无证据**：共用桥 O(桥对) 的常数比较是否值得换空间索引；暂未上线额外索引。

### 四、遗留半截的东西

候选阶段削减的是枚举、存储和扫描，不声称减少多数源的 FMM 求解次数。地图首个缩放档仍需同步抽样；两个缓存档的实际内存峰值未测。最终占地守卫、施工查询复用及写盘计时另有路线条目。

### 五、下一轮我会先做什么

优先合并回填同一次调用里重复的 FULL 检查和计划 lookup，同时保持台账更新、空计划与保护缺口写回的判据。继续处理 [路线列表](ROADMAP.md#统一任务清单) 的终态保护和数据测量。

### 六、我踩到但已解决的坑

负坐标必须向下取整，同权边必须按原来节点下标处理；稀疏时也不能省略 FMM 反向权重。地图短尾不依赖达到抽样阈值，且两端都在屏幕外也可以形成跨屏线。长度守卫抓到地图主文件超过 1000 行，接入保留在 998 行。

## 第 18 轮 ｜ 2026-11-16 ｜ 回填、结构快照与模板桥投影 ｜ 源码 `6590908` ｜ `PASS tests=1070 skipped=0 aborted=0 failed=0`

### 一、改了什么

- RW-02 行为等价 `257d3be`：延迟回填复用本轮已确认 FULL 的区块及初始计划，落块前仍重查台账，保护缺口写回后重新取计划，保留无主动加载边界。
- RW-07 行为变更 `abd95b4`：包围盒及顺序和路宽半径不变时复用结构桶，端点归属独立更新；同一批改变路宽也能正确重建。
- RW-08 行为等价 `6590908`：模板桥预计占地和施工使用同一坐标投影；干端头重试时复用投影，保留原浮点运算次序、材质映射及写前保护检查。

### 二、我验证了什么

外置 Gradle / JDK 21 顺次通过 1067、1069、1070 项无跳过，Fabric 编译成功。回填夹具覆盖撤销前一个候选后继续施工、空计划与保护写回；结构测试包含负坐标和纵向占用；曲桥夹具以旧投影为对照，比较新预计列、实际位置/材料和相邻两块反序施工。

| 负控 | 结果 |
|---|---|
| 回填恢复第二次 FULL 检查 | 红：同一候选查了两次 |
| 路面施工再取一次初始计划 | 红：会读取第二个版本 |
| 结构快照无条件重建桶 | 红：无几何变化时未复用 |
| 结构快照忽略半径变化 | 红：变宽以后桶仍旧 |
| 模板桥把横向投影固定为 0 | 红：曲桥侧材及方块位置偏离 |

各负控使用唯一锚点，单独执行；使用 `git checkout --` 恢复后全量正向再通过。

### 三、我没能验证什么

真实存档的回填完工状态、区块尾等待、模板桥接缝和开销待测。模板投影只在一次访问复用，不跨存档或世界写块缓存；不能据此推算进档速度。桥隧召回数据仍无，`crossing` 保持现状。

### 四、遗留半截的东西

终态巨树保护与精确样本复用还未完成；台账、Chunky、洞身和灯位探测需分开计时。现有落块期逐块结构保护仍有效。

### 五、下一轮我会先做什么

给实际终态占地补回归，再接可选的阶段测量；暂不改变台账落盘频率、READY 判据和灯位。

### 六、我踩到但已解决的坑

回填复用的世界对象不能跨线程或 tick；修复保护缺口后也不能复用旧计划。结构桶是否可复用与端点是否变更是两件事，且旧桶回传不能漏掉新路宽。桥的 Y 轴乘加次序保留，避免模板体素刚好落在舍入边界时漂到相邻列。

## 第 19 轮 ｜ 2026-11-16 ｜ 终态占地守卫与真机计时 ｜ 源码 `9eba192` ｜ `PASS tests=1073 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为等价 `acaa0e2`：RW-10 与 RW-11 增加可选的台账收集/排序/序列化/原子替换计时及 Chunky 供给扫描、READY 快照和原子写入计时；不改协议、落盘节奏、READY 判据。
- 行为等价 `25cfe76`：RW-03 用合法曲线道路守外侧施工列，覆盖显式保护缺口及共用路段；巨树预留只在同一终态体积遍历作影子观察，不改路线取舍。
- 行为等价 `9eba192`：RW-09 探测、落块各计地形列/岩层列和显式读块，RW-12 计灯座尝试次数、显式读块及搜索时间。存档和默认施工行为不变；开关和采样口径见[验证手册](VALIDATION.md)。

### 二、我验证了什么

外置 Gradle 8.8 / JDK 21 daemon；台账阶段通过 1071 项，终态及最终代码通过 1073 项，均无跳过、终止或失败，Fabric 编译通过。台账夹具让文件系统真实拒绝写入，重试后重新读文件验证两条记录和大小。终态测试保证中心线未撞、站距合法、建筑是实际占用而不只是元数据盒；保护缺口与 NETWORK_REUSE 仍然明确免检对应施工列。

| 负控 | 目标回归 | 结果 |
|---|---|---|
| 台账写入故障后不再置脏 | `failedWriteRemainsDirtyAndCanBeRetriedAndReopened` | 红：写盘未重试 |
| 终态结构校验忽略道路本体 | `structureAtOuterColumnIsRejectedUnlessStationIsAnExplicitGap` | 红：占用外列的建筑被放行 |
| 巨树观察只读中心 Z 对齐的列 | `observesTreeReservationAtFinalOuterColumnWithoutChangingTheRoadDecision` | 红：外侧预留未检出 |

各负控单独使用唯一锚点，恢复生产文件并重跑；最终 `git diff --check` 无差异错误。

### 三、我没能验证什么

没有本轮真实世界或存档副本。未验证手绘与 Chunky 同跑、Xaero 帧时、折返或路口改线遇巨树时的实际施工结果、模板实景接缝、隧道保护方块及灯座真实耗时。日志接线与离线计数不能代替这些证据。没有桥隧召回率数据，保留 `ProfileDpSource.crossing`、跨度回写和 QualityGate 默认值。

### 四、遗留半截的东西

RW-03 还需要对照真实预留相交与落块拦截，再决定如何保护终态且保持路线可通行；没有上线精确采样的跨阶段复用。RW-09～12 完成计时入口，尚无现场热点，没改隧道探测、台账/Chunky 写入和灯座搜索。共用桥空间索引也留待规模数据。

### 五、下一轮我会先做什么

按 [真机手册](VALIDATION.md#最新构建的真机对照) 固定种子、路线和模组集，分别跑首次生成、存档副本重开、Chunky 同跑。先比较实际落成与 p95，定位一项证据最强的热点，再决定改变哪个算法。

### 六、我踩到但已解决的坑

预留只有 X/Z，桥面或洞身投影重合不等于会改树块；结构校验提前停止时也不能报整条路无冲突。隧道计时必须在调用可能立即落块的消费者之前结束探测计时。台账写盘计时不纳入写失败后的日志开销。

## 第 20 轮 ｜ 2026-11-16 ｜ 终态样本、手绘结案与灯座搜索 ｜ 源码 `3ba2247` ｜ `PASS tests=1078 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为等价 `2055a1d`：终态校验把刚完成的批量精确采样交给占地体积遍历，同一次尝试遇到新命中的精确样本也复用。空值和实时高度不缓存，整区块/稀疏采样选择规则不变；静态依赖数仅下调。
- 行为变更 `06dc39a`：手绘经回填确认结案才推进；已确认 FULL 的区块只在当次服务端 tick 直接传基于台账的回填，不再重复确认。需要重试的区块不会带上同批已结案项；保留请求窗口、有限重试和撤票。
- 行为等价 `3ba2247`：灯座横向带按原分数顺序枚举，带内一次稳定排序；找到首个支撑、干燥且净空合格的位置即停止。距离牌、随机取侧和过滤条件保持原状。

### 二、我验证了什么

工作区起初干净，`git pull --rebase` 后外置 Gradle 8.8 / JDK 21 运行基线 1073 项；各提交恢复变异后为 1074、1076、1078 项，无跳过、终止或失败，Fabric 编译通过。合成样本里两组各 80 次精确列读取，仅为“先缺一次、再找到”的列调用共享采样两次；后续独立校验重新查询。灯座默认平坦夹具由 84 次支撑检查降至 1 次，对拍 2,040 个固定布局及单独的并列、无可用位置夹具；这不是直接的块读取或耗时倍数。

| 负控 | 结果 |
|---|---|
| 绕过预取的精确样本 | 红：命中列读取了错误来源 |
| 不记住本轮补到的精确样本 | 红：同列重复查共享采样 |
| 手绘把所有已加载的候选都推进 | 红：未结案的块被报成功 |
| 回填把部分落块和没落块都视作已经结案 | 红：未完成区块被纳入已结案集合 |
| 灯座去掉评分排序但继续早停 | 红：平地/边界灯位与全枚举不等 |
| 灯座把并列切向的正负次序颠倒 | 红：原先获选的负切向候选被越过 |

每条变异唯一锚点、单独执行并从索引恢复；反复构建未更改测试注册或保护默认值。

### 三、我没能验证什么

未跑真机或存档副本。未计手绘大批就绪区块每 tick 的落块和台账开销，也未验证整条路线跨会话的实际重试；1,078 项离线通过不代表真实 TPS。灯座检查量来自固定夹具，真实摆放受支撑、侧向、净空和区块边界影响。巨树终态处置、桥隧召回、`crossing` 和跨度回写仍保持原口径。

### 四、遗留半截的东西

32 个手绘请求仍会有同一 tick 同时就绪的情况，未调整已加载的施工预算或台账刷盘频率。RW-09～11 已计时接线，去重和异步写入还需现场数据；终态精确样本只在一次校验内复用。

### 五、下一轮我会先做什么

代码可继续从隧道两处对同一体素的流体重复读入手，守住空、允许的水和应拒绝的液体；独立梳理手绘施工突发量、READY 扫描与写盘样本，分别验证后再动调度或持久化。装饰和其他落块对照按 [VALIDATION](VALIDATION.md) 运行。

### 六、我踩到但已解决的坑

回填 `PLACED` 只说明至少有道路过了施工，不能代替整个区块的台账结案。精确采样命中可以在当次校验重用，但不能缓存缺失造成后续误判。灯座稳定顺序必须按总分而非只按切向排序；零 Y 偏移和正负切向原有的并列规则要一起保留。


## 第 21 轮 ｜ 2026-11-16 ｜ 隧道封水读写去重 ｜ 源码 c4ac5b0 ｜ PASS tests=1082 skipped=0 aborted=0 failed=0

### 一、改了什么

- 行为等价 dd73190：把 PortalSafetyRules.sealBore 的逐格封水循环搬到 TunnelBoreSealer，世界读取、写入、保护和长城归属由最小端口提供。生产仍用 FluidTags.WATER、原保护方法和 setBlock flags=3，计数及日志保留在原入口。
- 行为等价 c4ac5b0：只复用同次访问、写入之前的洞内及邻接壳层 BlockState，避免判断水后再次读同一位置。不开跨站缓存，不改施工和保护顺序。
- TunnelBoreSealerTest 新增 4 项并登记到 RoadJUnitRegressionSuite；ROADMAP 和 STATUS 同步进度。

### 二、我验证了什么（附负控）

开工工作区干净；第一次 pull 遇连接重置，使用 HTTP/1.1 重试后确认 main 已同步。外置 Gradle 8.8 / JDK 21 / daemon：基线 1078 项，端口化、优化及恢复所有变异后均为 1082 项，0 跳过、0 中止、0 失败，Fabric 编译通过。

固定种子 256 组水、空气、岩浆、石块和保护分布，包含邻接及重复施工列，对拍旧循环的最终方块、写入顺序、计数和保护参数；断言实际发生施工的组数大于 200，避免空转。单独洞内水加四面邻水夹具仍排 1 格水、封 4 格壳层，显式读块从 11 次减至 6 次；这不是整体性能倍数。

| 故意破坏 | 目标测试 | 结果 |
|---|---|---|
| 不写洞内空气但仍增加排水计数 | sealedBoreDriesInteriorAndSealsNeighborsInOriginalOrder | 红，实际写入缺失 |
| 洞口也强制封壳层 | portalOnlyDriesWaterAndPreservesOtherFluidsAndShell | 红，洞口水壳被封 |
| 跳过结构保护及长城归属检查 | bothProtectionsRetainWaterAndPassItsOriginalStateToGuard | 红，受保护水块被替换 |
| 判断空气时恢复一次重复读块 | sealedBoreDriesInteriorAndSealsNeighborsInOriginalOrder | 红，预期 6 次实际 7 次 |

每条负控先断言唯一锚点，独立跑全量回归，确认目标方法出现 AssertionFailedError，随后 git checkout -- 精确生产文件恢复。最后重跑双模块通过，git diff --check 通过。

### 三、我没能验证什么

- **需要真机的**：流体 tick、邻接更新、模组水标签、涌水后再封堵和实际 TPS/p95；本轮不把 11→6 次读块外推成加载提速。
- **需要真实存档的**：海底隧道重开后的实际封水、长城共建和保护缺口。未修改持久化格式。
- **怀疑但没有证据的**：该循环是否为当前整合包的主要热点，需 tunnelTimings 样本。
- **已知未覆盖的**：离线 Fake 显式分类 WATER 方块，不替代服务端数据包标签加载；生产适配器的真实世界方法及流体更新仍需真机。sectionSafe 两处重复 getFluidState 尚未改，地形/岩层查询仍不缓存。crossing 按用户决定保留现状。

### 四、遗留半截的东西

没有未完成的生产修改。RW-09 只完成封水同次访问的去重；整阶段地形列复用、主线程施工突发预算、RW-10/11 写盘策略继续等待对应样本。

### 五、下一轮我会先做什么

先给 sectionSafe 补离线安全输入及拒绝原因覆盖，再处理两处重复流体状态读取。继续把手绘每 tick 落块预算与异步预加载窗口分别计量，不能把 32 个在途请求当成已经限制了施工峰值。

### 六、我踩到但已解决的坑

重叠施工列会读到本轮刚写的石砖或空气，不能缓存整个封水阶段的方块状态。水的流体标签由运行时数据包绑定，离线方块夹具与真实服务端标签验收必须分开。先提交等价搬迁，再提交读去重，便于单独回退性能部分。


## 第 22 轮 ｜ 2026-11-16 ｜ 后续代码与架构复核 ｜ 源码未改 db61466

### 一、改了什么

只改文档：ROADMAP 增加六项后续代码工作的依据、先后与验收；CONVENTIONS 明确千行上限不计注释和空白，优先按职责拆分。保留现有求解内核。

### 二、我验证了什么

只读检查手绘就绪消费、共用桥距离双扫描、路口重复站号索引、台账全量排序写盘与 READY 扫描。独立词法扫描保留字符串、移除注释并计非空行：660 个生产 Java 文件，0 个超过 1000 有效行、3 个超过 800。源码未改，本轮未重跑或做生产变异；最近已验证源码仍为 1082 项全过、Fabric 编译通过（第 21 轮）。

### 三、我没能验证什么

后续方案都未实施，未测优化耗时，不声称能提高多少 FPS/TPS 或加载速度。没有真机与存档数据，不改 crossing、几何默认、重铺及持久化协议。文件规模数字来自独立扫描，不等于方法复杂度或职责单一。

### 四、遗留半截的东西

没有生产代码半成品。两次 pull 因 GitHub 443 连接失败，当前审查基于本地 db61466；远端同步结果以交付说明为准。

### 五、下一轮我会先做什么

优先独立施工消费预算，再做共用桥双扫描合并及路口上下文；每项单独提交、守住产物与拒绝原因。大范围采样共享和异步持久化先建一致性夹具。

### 六、我踩到但已解决的坑

ArchitectureGuardTest 的千行规则本来就是有效代码口径，不能把 1000 物理行误报成违规；现有独立行尾守卫阻止孤立 CR 入库。32 个加载在途窗口不等于 32 个区块施工都有可接受的 tick 耗时。


## 第 23 轮 ｜ 2026-11-16 ｜ 施工预算、局部去重与职责拆分 ｜ 源码 `0376ac2` ｜ `PASS tests=1090 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为变更 `5c7e34b`：手绘生产构造器每 tick 最多消费 8 个就绪区块，加载窗口仍 32；延期保留票据、刷新 FULL 句柄，不消耗重试。`4c5e0d6` 补生产默认接线守卫。
- 行为等价 `d2cd6df`：共用桥最近距离与重叠计数合并扫描；修复反向桥测试夹具的空集合，保持几何规则。
- 行为等价 `8e82876` / `5d13cef`：拆 JunctionSpanIndex 和 JunctionBatchCoordinator，裁切内共用索引；ExistingRoadJunctionService 从 912/718 降到 672/516 行（物理/有效）。
- 行为等价 `0376ac2`：拆 PathControlSimplifier（191/136）与 PathControlRelaxer（199/111），PathPostProcessor 从 962 物理行降到 660/481。保留全部流水线步骤，补正缓弯测试的产物断言及 90° 注释；同步 S5 台账。
- 改动处保留/补充中文原因注释；未把全库历史英文注释宣称为已完成。清理无用跨层导入，依赖基线只将 pathlogic→generation 从 67 降到 66。

### 二、我验证了什么（附负控）

开工树干净，pull --rebase 成功且 main 已同步。JDK 21 / 外置 Gradle 8.8 / daemon 基线 1082；新增 8 项后，恢复变异的最终全量为 1090，0 跳过/中止/失败，Fabric 编译通过。

| 故意破坏 | 目标守卫 | 结果 |
|---|---|---|
| 移除就绪施工预算判断 | RoutePreloadWorkTest 突发、撤票和失败 Future | 红 |
| 生产构造器把预算接为 window | productionConstructorConsumesAtMostEightReadyChunksPerTick | 红 |
| 半径由 <= 改 < | BridgeDistanceScanTest 与共享桥边界 | 红 |
| 恢复第二次点列表遍历 | scansEachPairOnceAndKeepsClosedRadiusBoundary | 红 |
| 重复 X/Z 改取首站 | reusesOneIndexAndKeepsLastStationAtDuplicateXZ | 红 |
| 路口覆盖物理桥/硬缺口保护 | preservesBridgeHeadsAndHardGapsAcrossTunnelJunctions | 红 |
| 批量跳过选主合并 / 绕过结构安全校验 | JunctionOrchestrationTest（分别运行） | 两次红 |
| 跳过密度补点 | densityFloorIsMetWithoutShrinkingTolerance | 红：需要 26 点却仅 2 点 |
| 取消急弯冻结 | relaxFreezesASharpTurnVertex / relaxFreezesHairpinVertex | 红 |
| 整段跳过松弛 | relaxStillSmoothsGentleBend | 红：预期 z=4，实际 z=1 |

每条唯一锚点替换、独立运行，确认目标断言失败后以 git checkout -- 精确恢复；没有把编译失败当红。桥扫描最初仅重复局部 sqrt 的试探仍绿，说明访问计数不衡量纯算术重复，之后改为真实第二次遍历才红。256 组双向桥曲线对拍旧规则，小夹具点列表访问 9→6；不外推 FPS/TPS。

### 三、我没能验证什么

没有真机、存档副本或现场耗时。每 tick 8 块只限制数量，单个重区块及多调用方合计的毫秒预算未解决；桥热点占比、跨区块加载顺序和真实桥头产物仍需现场验收。crossing、双平滑默认、跨度回写、QualityGate 和旧区块重铺策略未改。不能断言整体加载更快。

### 四、遗留半截的东西

没有未完成的生产修改。已同步 STATUS、ROADMAP、ARCHITECTURE 和 S5 台账；提交及推送结果以本轮交付为准。全库 664 个生产 Java 文件，0 个超过 1000 有效行、3 个超过 800；行数达标不代表职责拆分结束。

### 五、下一轮我会先做什么

优先拆 StructureAvoidanceService 的端点/快照职责及 TargetYComputation 的输入约束编排，再分离 AccurateHeightSampler 的调度生命周期；先行为等价与拒绝分支守卫，之后才合并采样。READY 增量计数、异步写盘仍需一致性夹具和阶段样本。

### 六、我踩到但已解决的坑

反向桥旧夹具生成空列表，原默认半径 24 也不满足“只一站重合”的假设，已改成真实 115 站/半径 3/重叠 1 的拒绝用例。旧缓弯测试只断言长度，无法守住平滑行为，已钉住坐标。新增后处理文件必须同步 S5 分类，否则文档守卫正确报红。


## 第 24 轮 ｜ 2026-11-16 ｜ 端点索引与高程约束编排 ｜ 源码 `34a5240` ｜ `PASS tests=1105 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为等价 `4ed56ff`：村庄未知端点过滤、两道整批撤销阀和原因日志移到 VillageEndpointFilter；世界、配置和路线快照仍由原入口读取。
- 行为等价 `ac333b4`：首次遇到可用快照才建立本轮 X/Z→第一条有效结构 ID 索引，消除逐连接重复扫描。保留 Y 无关、大小写敏感 ID、首条有效记录及阈值；不缓存跨轮数据。
- 行为等价 `7a6f777`：拆 TargetProfileSolver，注入桥面下界采样和 DP 求解端口，生产仍调用原采样与 DP。修正过时注释：TargetYComputation 会读世界，并非纯函数；不可行/异常走新约束回落，不回旧 pass。
- 测试 `aec7c7b` / `34a5240`：新增并登记 9 项端点测试与 6 项高程测试。新增逻辑有中文原因注释。结构入口从 1000/733 降到 831/622 行，高程编排从 825/521 降到 613/421 行（物理/有效）；新类分别 156/122、194/125。
- 清理零使用导入，依赖基线仅下降：pathlogic→config 82→78、core 200→198、generation 66→65、pathfinding 78→77；planning 保持 59。第 0～12 轮完整归档，近期记录留在本文件。

### 二、我验证了什么（附负控）

开工树干净，pull --rebase 成功且远端同步。JDK 21 / 外置 Gradle 8.8 / daemon：基线 1090；新测试登记后 1105，跳过、中止、失败均 0，Fabric 编译通过。

256 条含重复/负坐标的固定种子结构记录，对 1024 个查询与旧扫描独立对拍，断言已知和未知均实际命中。20 条连接/200 条记录夹具，读取恰为 200 次；无快照时 0 次，不外推 TPS。

| 故意破坏 | 目标守卫 | 结果 |
|---|---|---|
| 已知位点不核对结构 ID | endpointOwnershipRequiresExactPositionChunkAndFirstVerifiedId | 红 |
| 撤掉一轮最多 4 条的撤销阀 | fifthCandidateRevertsWholeRoundAndRetainsReason | 红 |
| 撤掉孤立端点撤销阀 | removingLastExternalConnectionRevertsWholeRound | 红 |
| 每条连接重新建索引 | readsKnownPositionsOncePerRoundAndSkipsIndexWithoutSnapshots | 红：预期 200，实际 4000 次 |
| DP 异常回落丢掉 pin/deckFloor | throwingDpRetainsExactlyTheSameFallbackConstraints | 红 |
| 把 tunnelMask 当 crossing | passesSeparateBridgeTunnelPinsAndProductionLimitsThenReturnsSolvedProfile | 红 |
| 水位采样异常后保留部分下界 | samplingFailureClearsPartialDecksButPreservesOtherConstraints | 红 |
| 生产适配器绕开 DP、恒不可行 | productionAdapterUsesActualDpRatherThanUnconditionallyFallingBack | 红：预期高度 72，实际 73 |

全部变异唯一锚点、独立运行，确认目标 AssertionFailedError 后 git checkout -- 精确恢复。生产接线用例只比较 DP 与回落确有差异的固定种子地形，断言实际命中过，防止平地相同产物造成假守卫。断言在端口回调外检查，避免被生产 catch(Throwable) 捕获而掩盖。

### 三、我没能验证什么

- 没有真机、真实存档副本、过滤现场耗时或采样 p95，不宣称加载/TPS 提升。
- 未离线构造真实 ServerLevel；水位标签、真实快照接线和整条 computeTargetY 产物仍需服务端验证。
- 保持现状的已知限制：某站水位采样失败时整批桥面下界清空并告警；本轮只守住既有语义。缺有效规划快照的连接仍保留。
- crossing、双平滑、跨度回写、QualityGate、存档格式和旧区块重铺策略未改；全库历史中文注释尚未补完。

### 四、遗留半截的东西

没有未完成的生产修改。结构服务的快照准备/作用域、高程的地形输入/旧模式编排仍待拆，本轮只迁出端点过滤与 DP 模块。全库 666 个生产 Java 文件，0 个超过 1000 有效行，3 个超过 800。

远端同步阻塞：本地改动均已提交；两次 HTTP/1.1 推送遇连接重置，HTTP/2 重试无法连接 github.com:443。已记录后继续重试，最终同步结果以交付说明为准；若未成功，接手先核对本地 main 与 origin/main，保留本轮提交。

### 五、下一轮我会先做什么

先隔离 AccurateHeightSampler 的批次调度与生命周期，守住取消、失败、重试和世界切换，再判断同语义在途请求能否合并；READY 状态转换集中后，再做可与全量扫描对拍的增量计数。异步写盘继续等计时和一致性夹具。

### 六、我踩到但已解决的坑

索引必须 putIfAbsent：旧扫描返回首条有效 ID，不能变成最后一条或任意匹配。村庄原点可能在聚合盒外，不能把所有待过滤连接当村内豁免。DP/回落的平地产物相同，不能证明生产接了 DP；只断言假端口也不能守住真实适配器。


## 第 25 轮 ｜ 2026-11-16 ｜ 精确采样调度、并发修复与守卫 ｜ 源码 `e77c654` ｜ `PASS tests=1119 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为等价 `53265b1`：整块 sampleChunks 的认领、查盘、批量校验、等待和失败结案迁到 AccurateChunkBatch，经缓存小端口继续使用原分片与容量；没有修改高度计算。
- 行为变更 `902c6be`：等待超时只释放认领，未得到结果的键保留在 unresolved，下一轮接管；等待线程中断不再 cancel 共享 Future；重入检测在整批写入认领前完成，防止前半批留下孤儿。
- 行为变更/减少重复工作 `e77c654`：读盘后在认领锁内复查缓存，避免原所有者刚完成时重复计算；逐列瓦片取消同样只影响当前线程；关闭资源改用逆序资源关闭，存储失败也关闭后端，两次失败保留首错及 suppressed。
- 新增并显式登记 11 项整块批次测试、3 项生命周期测试（共 +14）。改动处有中文原因注释。采样门面从 981/823 降到 829/699 行，新调度类 215/182 行（物理/有效）。独立统计 667 个生产 Java 文件，0 个超过 1000 有效行、2 个超过 800；依赖基线未放宽。

### 二、我验证了什么（附负控）

开工树干净，首次 pull --rebase 遇网络超时；本地基线 1105，Fabric 编译通过。后续网络恢复，pull --rebase 确认 main 无需变基，并已将上一轮积压及本轮代码推到远端。最终恢复负控后的 JDK 21 / 外置 Gradle 8.8 / daemon 全量为 1119，0 跳过/中止/失败，Fabric 编译通过。

先将真实并发错误写成测试：第一批复现超时空返回、取消连带健康等待者失败和递归孤儿；第二批复现重复求值、瓦片共享取消及后端未关闭。修复后全绿。固定交错夹具中，后端调用从 2 次降到 1 次；这是避免同区块重复求值的计数证据，不代表真机耗时减半。

| 故意破坏 | 目标守卫 | 结果 |
|---|---|---|
| 超时后仍从 unresolved 移除全部 waiting 键 | timeoutReclaimsChunkAndReturnsEveryRequestedKey | 红：预期完整键集，实际空 |
| 整块等待者中断时 cancel 共用 Future | interruptedWaiterDoesNotCancelSharedOwnerOrHealthyWaiter | 红：健康等待者收到取消 |
| 把重入检查放回逐键认领循环 | recursiveBatchDoesNotLeakEarlierPartialClaims | 红：下一次请求发现无人完成的认领 |
| 撤掉认领锁内的内存复查 | rechecksMemoryAfterAnotherOwnerFinishesDuringStoredLookup | 红：预期 1，实际 2 次后端调用 |
| 瓦片等待者中断时 cancel 共用 Future | tileWaiterInterruptionLeavesSharedFutureUsable | 红：共享 Future 被取消 |
| 关闭恢复为 store.close 后才 backend.close | backendStillClosesWhenStoreCloseThrows | 红：后端未关闭且次错未保留 |
| 关闭时不完成在途 Future | closingBatchImmediatelyFailsAnAlreadyRegisteredWaiter | 红：等待者未被及时唤醒 |
| 接收后端结果时跳过开放性检查 | closingFacadeWakesWaiterAndRejectsLateBackendResult | 红：关闭后迟到结果仍成功发布 |

8 个变异均断言替换锚点唯一命中，逐项独立运行全量；只有包含对应 JUnit 方法失败才算红，不把编译失败计入。每项 finally 执行 git checkout -- 精确恢复，并核对恢复字节与变异前相同。恢复后重新跑完整回归及 Fabric 编译。详细运行日志保留在忽略目录 .analysis_tmp/round25-*，变异表是持久交接记录。

另外验证：去重与请求顺序、内存/持久化命中仅计算剩余项、重叠批次先处理无冲突子集、后端失败传播给所有者与等待者后可重试、缺块整批不发布、保存失败可重试、资源重复关闭仅一次、不同采样器实例不共享样本。

### 三、我没能验证什么

- 没有真机、真实 ServerLevel 卸载或存档副本；实例隔离夹具不能替代跨维度/重进世界测试，不声称 TPS、加载速度或 p95 改善。
- 本轮关闭夹具覆盖后端尚未返回、已注册等待和资源关闭抛错；未验证关闭与已经进入持久化保存操作的全部竞争交错。
- 逐列/网格认领仍用原整批瓦片策略；混合 sampleGrid/samplePositions 的重入、超时接管、进度回调取消及缓存驱逐交错尚未全面验收。未跨采样器、世界版本或后端语义扩大共享。
- crossing、双平滑默认、跨度回写、QualityGate、旧区块重铺和存档协议未改；全库历史中文注释尚未补完。

### 四、遗留半截的东西

没有未提交的生产半成品。整块调度已独立，逐列/网格与缓存生命周期尚未全部拆完；不把本轮修复说成所有取消与关闭风险都已消除。同步 STATUS、ROADMAP、ARCHITECTURE、VALIDATION；本轮文档提交和最终推送结果以交付为准。

### 五、下一轮我会先做什么

先拆逐列/网格的共同认领生命周期，补混合请求、重入、取消及关闭交错；明确部分缓存命中和持久化失败的发布口径，再决定改整批瓦片策略。随后集中 READY 状态转换，与全量扫描对拍后再评估增量统计；异步写盘继续等现场计时和一致性夹具。

### 六、我踩到但已解决的坑

等待过不等于拿到结果：只按 waiting.keySet 结案会把超时漏项掩盖。Future 的 cancel 会影响所有共用者，不能把调用线程的取消等同于共享计算取消。重入检查要先遍历整批，否则报错前创建的认领没有所有者负责完成。仅用 facade 关闭用例可能在等待注册前就退出，因此另加超时供给器打点，确认真正持有共享 Future 的等待者会被主动唤醒。


## 第 26 轮 ｜ 2026-11-16 ｜ 列/网格采样与瓦片生命周期 ｜ 源码 `a7e9871` ｜ `PASS tests=1142 skipped=0 aborted=0 failed=0`

### 一、改了什么

- 行为等价 `625d917`：逐列、规则网格、查盘与补缺迁到 AccurateColumnBatch；仍使用原门面的分片缓存、容量和高度后端，迁移提交单独验证 1119 全过。
- 行为变更 `e1e329e`：网格也登记并检查线程持有的瓦片；三种混合重入及时报错。保存调用返回后才发布网格缓存，失败不会靠内存命中吞掉重试；拒绝后端返回不同坐标/步长的请求；两条网格读盘路径返回后检查关闭。等待重试改循环，避免冲突次数成为递归深度。
- 行为变更 `8a8359c`：AccurateTileClaims 集中整批认领、同线程重入、等待、异常结案和条件释放；普通/加速两表沿用。关闭标志及认领表锁阻止清表后又登记新 Future；超时后的旧所有者不能误删新所有者。
- 行为变更 `a7e9871`：补逐列保存返回后的关闭检查，防止保存过程中已关闭却继续填缓存；追加整网格、逐列保存交错及原故障传播测试。新增/改动逻辑有中文原因注释。
- 新增并按 FQCN 登记 18 项 AccurateColumnBatchTest、5 项 AccurateTileClaimsTest，共 +23。门面 829/699→489/380 行，新列/网格类 367/325、瓦片认领类 106/85 行（物理/有效）；独立统计 669 个生产 Java 文件，0 个超过 1000 有效行、2 个超过 800。依赖基线未放宽。

### 二、我验证了什么（附负控）

开工工作区干净，HEAD bacb3f8；pull --rebase 网络超时，本地基线 1119、Fabric 编译通过。修复前先跑夹具，确认 7 个用例失败（三种混合重入、网格保存失败、错误请求、两条读盘关闭），另一次补测确认逐列保存中关闭会复活缓存。修复后 1142，0 跳过/中止/失败，Fabric 编译通过。

| 故意破坏 | 目标守卫 | 结果 |
|---|---|---|
| 恢复网格先缓存、后保存 | gridSaveFailurePublishesNothingAndNextRequestRetriesPersistence | 红 |
| 撤掉网格请求坐标校验 | mismatchedBackendGridIsRejectedBeforeSavingOrPublishing | 红 |
| 不登记线程已持有瓦片 | gridToGridReentryFailsBeforeWaitingAndLeavesNoClaim 等 | 红 |
| 撤掉 loadStoredGrid 读盘后的关闭检查 | storedGridReadRejectsDataArrivingAfterClose | 红 |
| 撤掉 sampleGrid 读盘后的关闭检查 | samplingGridRejectsStoredDataArrivingAfterClose | 红 |
| 撤掉逐列保存后的关闭检查 | closingDuringColumnSaveCannotRepublishCache | 红 |
| 旧认领按 key 无条件删除 | lateOwnerReleaseCannotEraseReplacementAfterTimeout | 红 |
| 忽略认领组件自身关闭标志 | closeWakesRegisteredWaiterAndRejectsClaimsEvenIfCacheStillLooksOpen | 红 |
| 异常不完成共享 Future | gridFailureReachesWaitingColumnsAndFreshRequestRetries | 红 |
| 普通/加速请求误用同一张在途表 | modeLanesAreIndependentButSameThreadCannotReenterAcrossModes | 红 |

10 项唯一锚点替换、逐项独立跑完整回归，核对目标 JUnit 方法实际失败，不把编译失败计红；finally 逐文件 git checkout -- 恢复，并核对原字节。恢复后全量及 Fabric 再跑一次。日志与逐项 JSON 在忽略目录 .analysis_tmp/round26-*；本表是持久记录。

另外对拍：负坐标且跨四瓦片的三通道高度；混合请求两个方向都真实等待、仅补剩余列；缓存/存储部分命中；缺列整批拒绝后重试；进度回调取消后重试；真实门面网格产物被逐列缓存复用；真实超时接管；已登记等待者关闭唤醒；冲突不占空闲瓦片、认领顺序不变及跨模式重入。未启用真实 GPU。

### 三、我没能验证什么

- 无真机、真实 ServerLevel 卸载、存档副本或现场性能样本；不把少一次递归/职责拆分说成 TPS、进档或尾延迟改善。
- 实际 FileBackedAccurateSampleStore 的 loadTileForWrite 与 writeTile 会告警并吞掉部分 IO 错误，save 正常返回不是落盘确认；本轮保持其可再生成缓存的最佳努力语义，没有把 Fake 抛错守卫冒充真实 IO 故障恢复。跨瓦片写入也不是整批事务。
- 保存中关闭的夹具覆盖“保存已进入，返回前发生关闭”；读写返回后加检查不等于用单一锁串行化所有关闭与缓存发布交错。缓存驱逐、门面只读 API 与关闭、世界/版本切换仍待进一步验证。
- 混合请求守住原有的等待后补缺，不声称已优化所有“先未命中、随后别人完成”的重复请求；整批瓦片策略未改。两模式表隔离只做 Fake 验收，废弃 GPU 精确采样开关不改。
- crossing、双平滑默认、跨度回写、QualityGate、旧区块重铺和持久化 schema 均未改；未把全库历史注释宣称为已补齐中文。

### 四、遗留半截的东西

生产修改均独立提交，没有临时变异残留。首次及中途同步 GitHub 443 失败；收尾继续重试，最终推送结果以交付为准。缓存仍在门面，原只读查询、文件缓存的故障/关闭协议没有整体重写；不能宣称所有缓存生命周期已解决。STATUS、ROADMAP、ARCHITECTURE、VALIDATION 同步本轮边界。

### 五、下一轮我会先做什么

先用真实文件后端厘清“可再生成缓存”读写失败、重试与关闭的契约，并覆盖门面只读查询；再看是否值得用批次完成版本避免重复未命中，不能为省一次高度生成给所有冷网格增加无条件二次全扫描。随后集中 READY 状态转换并与全量扫描对拍；增量计数和异步落盘仍分别验版本、失败与停服。

### 六、我踩到但已解决的坑

端口抛错可重试不等于实际文件后端确认落盘：亲自读过 writeTile 的 catch(IOException) 后修正注释与文档。网格等待不能只测试缓存预热场景，夹具必须打点确认读到了在途 Future。超时只释放认领，不会停止旧计算，因此旧 finally 必须核对 Future 身份。保存前检查关闭也不够，保存返回后还要查；不过两次检查仍不能被描述为全部并发交错已经串行化。

---

## 第 27 轮 ｜ 2026-11-16 ｜ 真实文件缓存故障与关闭契约 ｜ 源码 `7884416` ｜ `PASS tests=1159 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| AccurateStoreLifecycle；FileBackedAccurateSampleStore | 独立生命周期组件 41/32 行，五个文件入口持共享租约；关闭独占排空后清缓存及锁表，同线程访问内重入关闭明确拒绝。文件入口 715/625 行（物理/有效）。写失败驱逐被原地修改的内存瓦片，下一次从磁盘旧版重读。 | 行为变更，文件格式不改 |
| AccurateHeightSampler | 只读查询入口及读盘返回后检查关闭，包括空返回；分片桶在持锁时检查关闭，拒绝迟到发布及过期读取。门面 501/388 行。 | 行为变更，高度算法和容量不改 |
| AccurateSampleStoreFileTest / AccurateSampleStoreCloseTest / AccurateSamplerCacheLifecycleTest；RoadJUnitRegressionSuite | 新增 17 项并按 FQCN 登记，真实临时文件读锁、替换锁、关闭并发与只读查询守卫。 | 测试基建 |

提交 `5a163b0`、`7884416`。先确认干净并 pull --rebase，Already up to date；基线 1142 全绿，第一步文件后端 1152 全绿，门面修改后 1159 全绿且 Fabric 编译通过；全部变异恢复后再次执行全量及 Fabric。全库 670 个生产 Java，超过 1000 有效行 0，超过 800 有效行仍为 2。

### 二、我验证了什么（附负控）

| 故意改坏生产代码 | 目标测试（类:方法） | 结果 |
|---|---|---|
| 写失败不驱逐被原地修改的瓦片 | AccurateSampleStoreFileTest:failedWarmColumnWriteEvictsUnpersistedMutationAndCanRetry；同次整块/网格用例也红 | 红 |
| 读写合并遇 IO 异常返回空瓦片继续写 | AccurateSampleStoreFileTest:unreadableTileIsNeverReplacedAndSameInstanceRetriesAfterUnlock | 红 |
| loadChunks 撤掉访问租约 | AccurateSampleStoreFileTest:everyFileApiRejectsAfterCloseIncludingEmptyRequests | 红 |
| loadSamples 撤掉访问租约 | 同上 | 红 |
| saveChunks 撤掉访问租约 | 同上 | 红 |
| saveSamples 撤掉访问租约 | 同上 | 红 |
| saveGrid 撤掉访问租约 | 同上 | 红 |
| close 不排空，直接置关闭并清缓存 | AccurateSampleStoreCloseTest:closeDrainsRealWriteAndDoesNotSerializeIndependentAccesses | 红 |
| cachedSamples 撤掉入口关闭检查 | AccurateSamplerCacheLifecycleTest:closedCacheApisRejectBeforeReadingStoreIncludingEmptyBatch | 红 |
| 单列只读查询撤掉读盘后关闭检查 | AccurateSamplerCacheLifecycleTest:singleStoredMissArrivingAfterCloseIsRejected | 红 |
| 批量只读查询撤掉读盘后关闭检查 | AccurateSamplerCacheLifecycleTest:batchStoredMissArrivingAfterCloseIsRejected | 红 |
| 桶内写入撤掉关闭检查 | AccurateSamplerCacheLifecycleTest:lateColumnAndChunkPublicationAreRejectedAtShardLock | 红 |
| 桶内读取撤掉关闭检查 | 同上 | 红 |

13 项均唯一锚点替换、独立执行完整回归，核对目标 JUnit 方法实际失败，不把编译错误计红；每项 finally 用 git checkout -- 精确文件恢复并核对原字节。日志及逐项 JSON 在忽略目录 .analysis_tmp/round27-*，本表是持久变异记录。

真实锁先断言 Files.readAllBytes 或原子替换确实报 IOException，再检查原文件逐字不变。写失败覆盖预热后的列/块/网格三入口；解锁后重试并重开确认旧列和新列都在，失败无临时文件残留。另验负坐标跨九瓦片三通道、完整 256 列区块、损坏原字节隔离和指纹 A→B→A。关闭夹具在真实文件入口的输入遍历暂停两名访问者，确认同时进入和关闭线程实际等待；不是依赖随机交错或空集合空转。

### 三、我没能验证什么

- **需要真机的**：世界卸载或停服时排空文件访问的等待、首次生成/重开/Chunky 的尾延迟、帧时间和 TPS；本轮没有速度倍数或加载改善结论。关闭会等待已进入的同步文件操作，不承诺文件系统挂起时仍能立即结束。
- **需要真实存档的**：真实整合包切换世界指纹、多个世界切换和停服压力；临时文件不是玩家存档验证。未构建或部署发布 jar。
- **怀疑但没有证据的**：跨实例同目录并写、所有驱逐/关闭组合和网络文件系统的行为。本轮租约只协调单个文件后端实例，不提供跨实例事务或进程锁。
- **已知仍保留的限制**：文件 IO 异常仍 WARN 后跳过，本次 save 正常返回不承诺落盘，未保存样本须由后续请求重采或重试；跨瓦片写入非整批事务。瓦片锁表仍随访问过的瓦片增长，直到关闭才释放，本轮没有改锁分配策略。
- crossing、双平滑默认、跨度回写、QualityGate、旧区块重铺和所有持久化 schema 不变；废弃 GPU 精确采样不开启。只补本轮改动的中文原因注释，不宣称全库历史英文注释已改完。

### 四、遗留半截的东西

无临时变异残留，生产改动分别提交。文件入口和缓存桶的关闭契约已补齐离线守卫，不能据此宣称真实世界关闭的全部并发交错已经覆盖。重复未命中、驱逐压力与 READY 状态收敛仍待后续；STATUS、ROADMAP、ARCHITECTURE、VALIDATION 已同步范围。

### 五、下一轮我会先做什么

按路线评估批次完成版本能否减少“先读到未命中、别人完成后仍重算”的请求：先固定复现并核对三通道结果与后端调用集合，再比较版本检查和无条件二次扫描的工作量，不给所有冷网格平添全扫描。随后封装 READY 状态转换，与现有全量统计对拍；增量计数和异步落盘须各自验证版本顺序、失败重试与停服封账。

### 六、我踩到但已解决的坑

NOSHARE_READ 不能冒充写失败，替换故障要单独用 NOSHARE_DELETE 并证明原子替换实际被阻止。暖缓存比冷缓存更容易掩盖写失败：SparseTile 已被原地修改，catch 只告警会让本实例读到磁盘没有的数据，故失败须驱逐。空的迟到读盘结果没有 put，不会偶然经过桶内守卫，必须单独检查关闭。共享租约不能升级成关闭的独占锁，同线程重入要在等锁之前拒绝；真实关闭可以等待已进入的写入完成，不能把“关闭返回后无新 IO”写成“开始关闭即取消所有写入”。

---

## 第 28 轮 ｜ 2026-11-16 ｜ 精确采样过期未命中复用 ｜ 源码 `e296f04` ｜ `PASS tests=1178 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| AccurateSamplePublications；AccurateColumnBatch | 固定 1024 个原子版本槽，载荷 8 KiB；首次缓存扫描前观察，拿到瓦片认领后仅对相关发布重查。部分命中重新分组，全部命中不调用空后端；重查异常传给已登记等待者。发布组件 41/29 行，列/网格编排 405/355 行（物理/有效）。 | 高度、顺序及协议行为等价的复用优化；故障通知补守卫 |
| AccurateChunkBatch；AccurateHeightSampler | 同一门面的整块、逐列和网格共享发布记录；整块先完整填充缓存，再记录版本并通知等待者。门面 503/389 行，整块编排 229/193 行；缓存容量及原后端不改。 | 高度和格式行为等价；批次完成通知集中到缓存发布之后 |
| AccurateSampleRecheckTest；AccurateSamplePublicationsTest；RoadJUnitRegressionSuite | 新增 15+4 项，按 FQCN 登记。受控旧快照、实际门面跨路径、部分补缺、缓存驱逐、故障传播、槽碰撞和版本倒序均直接运行。 | 测试基建 |

源码提交 `31fa620`、`e296f04`。独立统计 671 个生产 Java 文件，超过 1000 有效行 0、超过 800 有效行 2；新增/改动逻辑有中文原因注释。不调整初始规划半径、道路几何或持久化格式。

### 二、我验证了什么（附负控）

开工树干净，main / `96a9b59`，pull --rebase 为 Already up to date；基线 1159 和 Fabric 编译通过。先用锁存器暂停已经取得的存储空快照，待另一批发布并释放认领再继续：修复前 7 项列/网格用例实际失败；初步修复 1169，补故障和版本守卫后 1174。随后实际 AccurateHeightSampler 跨整块/列/网格又复现 3 项失败，共用发布记录后 1178 全过。全部变异恢复后再次执行完整回归与 Fabric 编译，仍为 1178、0 跳过/中止/失败。

| 故意改坏生产代码 | 目标守卫（类:方法） | 结果 |
|---|---|---|
| 逐列完成后不记录发布 | AccurateSampleRecheckTest:columnsReuseColumnsPublishedDuringTheirStoreRead | 红 |
| 整网格完成后不记录发布 | AccurateSampleRecheckTest:columnsReuseGridPublishedDuringTheirStoreRead | 红 |
| 逐列认领后绕过相关版本重查 | AccurateSampleRecheckTest:columnsReuseGridPublishedDuringTheirStoreRead | 红 |
| 整网格认领后绕过相关版本重查 | AccurateSampleRecheckTest:gridReusesGridPublishedDuringItsStoreRead | 红 |
| 读盘返回后才观察网格版本，漏掉期间发布 | AccurateSampleRecheckTest:gridReusesGridPublishedDuringItsStoreRead | 红 |
| 部分复用后不重新分组剩余列 | AccurateSampleRecheckTest:staleColumnsOnlySampleUnpublishedRemainderAcrossTiles | 红 |
| 全量复用后仍调用空后端并保存 | AccurateSampleRecheckTest:columnsReuseGridPublishedDuringTheirStoreRead | 红 |
| 重查异常只释放认领，不通知已登记等待者 | AccurateSampleRecheckTest:recheckReadFailureReachesRegisteredWaiterAndFreshRequestRetries | 红 |
| 整块完成后不记录发布 | AccurateSampleRecheckTest:facadeGridReusesChunkPublishedDuringItsStoreRead | 红 |
| 实际门面给列/网格另建发布上下文 | AccurateSampleRecheckTest:facadeGridReusesChunkPublishedDuringItsStoreRead | 红 |
| 任何区域发布均按全局版本强制重查 | AccurateSampleRecheckTest:unrelatedPublicationDoesNotRescanColdGrid | 红 |
| 槽内直接 set，允许迟到旧版本覆盖新版本 | AccurateSamplePublicationsTest:lateOlderPublicationCannotRegressStripeVersion | 红 |

12 项逐项唯一锚点替换，独立运行完整回归并确认对应 JUnit 方法失败，不把编译失败计入；每项 finally 执行 git checkout -- 精确文件恢复，并核对字节相同。日志和逐项 JSON 留在忽略目录 .analysis_tmp/round28-*，本表为持久记录。

工作量对照：同一覆盖范围的旧快照从两次后端采样降为一次，且不重复保存；网格 255..257 仅补未发布的 257 列；跨瓦片完全解决一侧时不重新带回已命中列。256 列无争用冷网格为 256 次缓存读取、1 次存储读取、1 次整网格生成；另一区域发布夹具含生产者共 257 次缓存读取、2 次存储读取，改为全局失效会增加一次整网格扫描并触发守卫。内存驱逐后可从存储复用；两级缓存确实丢失时允许重新生成。稀疏列发布不能替代完整区块，守卫逐列核对 256 列三通道。

### 三、我没能验证什么

- **需要真机的**：首次生成、重开、Chunky 同跑、真实卸载与停服时延。没有真实种子或 ServerLevel 现场样本，不把夹具减少的后端次数换算为 TPS、加载速度或倍数。
- **需要真实存档的**：大范围缓存驱逐、跨世界/维度切换、整合包长期运行和文件后端吞错后的实际重采。未打包或部署发布 jar。
- **怀疑但没有证据的**：1024 槽碰撞的现场比例、热点瓦片连续发布下的重查次数及版本记录自身的真实开销；当前仅有固定布局的工作量计数，不宣称已经测得耗时收益。
- **已知仍保留的限制**：本轮记录成功后端批次发布，仅从磁盘加载或门面只读 API 填充缓存不单独记录，仍可能留下重复未命中。整块与逐列真正同时启动仍可并行重算；普通/加速两张在途表和超时接管策略保持。发布记录不是样本缓存或持久化确认，文件 IO 仍是最佳努力，跨瓦片非事务。未穷尽关闭、驱逐与发布的全部交错。
- crossing 仍按用户要求保留现状；双平滑默认、跨度回写、QualityGate、旧区块重铺和所有 schema 不改，废弃 GPU 精确采样不开启。只补本轮代码的中文原因注释，历史全库注释未全部重写。

### 四、遗留半截的东西

无临时生产变异残留，两个源码步骤已分别提交。采样复用的离线边界已交代，不将其称为所有并发重复请求均已消除。READY 状态收敛尚未开工。STATUS、ROADMAP、ARCHITECTURE、VALIDATION 已同步；文档提交与最终推送以交付记录为准。

### 五、下一轮我会先做什么

按路线先收拢 RoadPlanningReadinessIndex 状态转换入口，钉住 READY、失败、取消、重试和持久化读取的行为，并与现有全量扫描对拍；确认版本和状态边界后再评估增量计数。文件锁表增长、查盘填充的重复未命中和驱逐压力另建定向夹具，不用无条件二次全扫描掩盖。异步写盘继续等实际计时与失败重试、停服封账证据。

### 六、我踩到但已解决的坑

观察版本必须早于首次缓存扫描，放到旧存储快照返回后会漏掉整个竞争窗口。全局任意发布都重查会让无关冷网格多扫一遍，因此只查所需瓦片对应槽。版本不是数据，即使记录显示发布过，也必须实际查到样本；碰撞只能多查，不能伪造命中。旧发布晚到会把直接 set 的槽版本写小，须按原子最大值推进。重查时持有新认领，异常若只走 close/remove 会把已登记等待者留成未完成 Future。真实门面必须共用整块与列/网格的发布记录，单独测小组件无法发现接线遗漏。

---

## 第 29 轮 ｜ 2026-11-16 ｜ 开局半径上限 4096 与游玩负载复核 ｜ 源码 `fe48172` ｜ `PASS tests=1186 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| RoadConstants；PlanningConfig；PlanningKeys | 新增开局专用上限 INITIAL_PLAN_MAX_RADIUS_CHUNKS=4096，界面和 sanitize 统一；默认仍为 128，动态半径与地图 AOI 的既有 2048 上限不变。原 GSON 字段名及存档协议不改，新增/改动代码有中文原因注释。 | 行为变更，按用户要求放宽开局配置 |
| PlanningConfigTest；AdaptiveCandidateBoundsScaleTest | 新增 7 项配置断言用例和 1 项 4096 参数化几何档。两个类已在套件登记，本轮无需新增类；校验范围、默认、旧键读回、快照、UI 一致和动态边界。 | 测试基建 |
| STATUS；ROADMAP；VALIDATION | 明确配置上限不等于已验证安全规模；优先路线改为游玩预算遗漏、大范围工作集，再做 READY 状态收敛。 | 文档同步，不宣称帧率改善 |

源码提交 fe48172。这次没有改实际当前配置值，没有打包、安装或部署 jar；用户要在配置中主动选择更大范围才会扩大工作量。原算法、道路质量判据和几何结果不改。

### 二、我验证了什么（附负控）

开工干净，main / 7489971，pull --rebase 为 Already up to date；基线 1178 全绿且 Fabric 编译通过。修改后 1186、0 跳过/中止/失败，Fabric 编译通过；变异恢复后重新执行完整回归与 Fabric。生产 Java 仍为 671 个，超过 1000 有效行 0。

| 故意改坏生产代码 | 目标测试 PlanningConfigTest | 结果 |
|---|---|---|
| 开局专用常量退回 2048 | initialRadiusUiAllows4096WithoutIncreasingDynamicRadiusRange | 红 |
| UI 改回动态用的旧上限 | initialRadiusUiAllows4096WithoutIncreasingDynamicRadiusRange | 红 |
| sanitize 改回旧上限 | initialRadius4096SurvivesExistingJsonKeyAndSnapshot | 红 |
| snapshot 不复制开局半径 | initialRadius4096SurvivesExistingJsonKeyAndSnapshot | 红 |

四项均唯一锚点替换、独立跑全量并确认目标 JUnit 方法实际失败；finally 用 git checkout -- 精确恢复并逐字节核对，不把编译失败算红。日志及逐项 JSON 在忽略目录 .analysis_tmp/round29-*，本表为持久记录。

另外只读核对旧仓库 2c6547a 的干净工作区：RoadConstants:42 仍为 2048，PlanningConfig:314 会夹取；没有改动旧仓库，也未核对用户当时运行的发布件。2048 是区块半径，本身对应约 65536 格边长，不能混成方块半径。新版 4096 对应约 131072 格边长。几何测试只说明坐标与面积判据正常，不实际申请该大小的采样场。

### 三、我没能验证什么

- **需要真机的**：新版在同种子下的 FPS、帧时间 p95/p99、MSPT、GC、首次生成/重开及 Chunky 同跑，尤其玩家移动、打开界面时正在进行的采样和路径计算。当前没有任何“后台已经不卡”结论。
- **需要真实存档的**：4096 档完整预热的耗时、峰值堆、落盘量、恢复和实际覆盖；未运行实际大范围世界，未部署。不能把 1186 项离线全绿当成该范围的安全证书。
- **我怀疑但没有证据的**：本次没有运行并发采样性能探针，不能量化以下预算遗漏实际增加多少工作线程或掉多少帧；旧发布件的配置范围没有验证。
- **已知未修的预算边界**：CpuAccurateHeightBackend:59/90/127 使用 parallelStream，未在该类或 NoiseChunkHeightSampler 中接入 RuntimeLoadPolicy 预算/让路；CoarseCorridorPlanningAdapter:70/178 的两轮子任务均提交 INITIAL，RuntimeLoadPolicy.beforeTask 对此角色直接豁免。开局放宽不代表这些游玩期路径已治理。帧率影响未实测，但不能宣称 gameplayThreadCap=2 约束全部线程。
- **已知未修的大范围工作集**：PipelinePlanner:145..159 按全端点包围盒采样；SampledTerrainField:138..156 直接请求整网格；CpuAccurateHeightBackend:121..123 分配三张 int 数组，FMM 还有整场工作数组。131072 格边长按 step=8 满铺约 2.68 亿采样，仅三张 int 数组载荷约 3 GiB，不含缺列 Map 和求解内存。此为尺寸推算，不是真机内存测量。候选面积守卫仅缩外扩余量，不裁核心，所以不会替这个工作集兜底。
- crossing、双平滑默认、跨度回写、QualityGate、旧区块重铺、GPU 精确采样禁用和持久化 schema 都保持。

### 四、遗留半截的东西

开局范围修改已独立提交，临时变异全部恢复；配置可用性守卫完成，4096 安全运行未完成，不能声称“扩上限就解决卡死”。预算和工作集问题仅完成源码复核及任务拆分，没有混入未经验证的调度改法。文档已同步，最终提交和推送以交付为准。

### 五、下一轮我会先做什么

按用户最新关注把游玩不卡放在 READY 拆分类之前：先给 CPU 精确采样和粗路径子任务加执行策略端口，用受控夹具验最大并发、前台让路、取消和开局豁免；再换有界调度并对拍高度、键集合和路径，严防父子同池同步等待。随后做分配前工作量估算及按需瓦片/稀疏求解工作集，与原求解结果对拍，保留核心完整覆盖和跨分区连边。没有真机证据前，不把提高预热上限当成游玩负载治理。

### 六、我踩到但已解决的坑

COARSE_REGION_MAX_RADIUS_CHUNKS 同时被开局、动态配置和地图 AOI 使用，直接改成 4096 会把无关游玩范围一起放大，因此必须分开常量。配置 setter 存得下并不代表加载后保留，需经过 GSON 旧键读回、sanitize 和 snapshot。新算法加稀疏候选不代表采样场与 FMM 工作数组也已稀疏；“外层最多两线程”和“所有内部计算最多两线程”不是同一契约。源码中的历史帧率注释不能代替新版现场数据。

---

## 第 30 轮 ｜ 2026-11-16 ｜ 调度模组 API 协作与诊断副作用 ｜ 源码 `d960f8a`、`4f1923a` ｜ `PASS tests=1201 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| LoadSignalBridge.RuntimeSignals；RuntimeLoadPolicy | 沿用现有 API 注册表，对预算建议取最小非负值，对显式保护按或合并；保留 worker/lane 参数、road 消费者重载、负值弃权和 0 lane。普通协调器显式暂停先于别的来源的正数建议。故障按查询隔离并限频告警。 | 行为变更；修复非旧 owner 的意见未被消费及暂停被建议短路 |
| LoadSignalBridge.describeResolution | 只取日志需要的 owner/kind/busy，取消有状态的未知参数预算查询；保留最高忙碌类别和原日志字段。 | 行为变更；诊断不再推进 Chunky 自适应预算 |
| RuntimeLoadSignalsTest；ApiLifecycleAndLoadBridgeTest；RoadJUnitRegressionSuite | 新类 14 项按 FQCN 登记；既有类加 1 项日志副作用测试，并清理原“some-other-mod”夹具注册。 | 测试基建，共增加 15 项 |
| architecture/deps-baseline.txt | 不再依赖 RoadLoadSnapshot，将 apiimpl -> api 的 import 次数 14 收紧到 13；其他依赖基线不放宽。 | 行为等价的依赖收紧 |
| ARCHITECTURE；STATUS；ROADMAP；VALIDATION | 合并记录六个调度相关工程的职责、现有 API 消费、诊断边界及下一步执行预算。没有另建一份当前状态文档。 | 文档同步 |

先提交 d960f8a，再独立提交诊断修复 4f1923a。适配桥 317/223 行、RuntimeLoadPolicy 973/565 行（物理/有效）；生产文件仍 671 个，超过 1000 有效行 0、超过 800 行 2。新逻辑有中文原因注释。没有改 api/ 的签名、ownerId、持久化 schema、配置键、道路几何或外部工程，也没有新建总调度器；未打包或部署发布 jar。

### 二、我验证了什么（附负控）

开工 main / 5d2e2ca，git status --porcelain 为空。开工 pull 和 HTTP/1.1 重试、后续 fetch 曾因连接失败/超时未成功，未冒充拉取完成；稍后 GitHub 连接恢复，源码已经成功推送。基线 1186 和 Fabric 编译通过。

先加入 11 项用例，实际复现 6 个生产入口行为失败；临时协作类还被依赖与死代码守卫拦住，随后把消费边界收回已有 apiimpl 适配器，没有增加跨层基线。补齐配置上限、零 worker 与 Chunky 身份三项后 1200 全绿。再加入日志副作用夹具，修复前实际把预算从 6 写成 1；取消预算查询后 1201 全绿。11 项变异全部恢复后，再执行完整回归与 Fabric 编译，仍为 1201、零跳过/中止/失败，BUILD SUCCESSFUL。

| 故意改坏生产代码 | 目标测试（RuntimeLoadSignalsTest，另注明者除外） | 结果 |
|---|---|---|
| worker 包装器丢弃 API 建议 | nonLegacyWorkerVotesReachProductionWithRoadContext | 红 |
| lane 包装器丢弃 API 建议 | nonLegacyLaneVotesReachProductionWithAllContextAndKeepZero | 红 |
| 布尔保护退回只认旧 owner 的入口 | nonLegacyCpuProtectionControlsPhaseWorkersLanesAndPauseReason | 红 |
| 多源预算取最小改成取最大 | nonLegacyWorkerVotesReachProductionWithRoadContext | 红 |
| 把 0 lane 当成无建议丢弃 | nonLegacyLaneVotesReachProductionWithAllContextAndKeepZero | 红 |
| 删除协调器显式暂停的优先判定 | explicitCoordinatorPauseWinsOverAnotherProvidersPositiveLaneVote | 红 |
| 不调用 road 消费者重载 | nonLegacyWorkerVotesReachProductionWithRoadContext | 红 |
| lane 上下文改传 -1/-1/-1 | nonLegacyLaneVotesReachProductionWithAllContextAndKeepZero | 红 |
| 前台交互偷换为移动或交互 | movementSignalIsNotSilentlyReinterpretedAsInteraction | 红 |
| 故障源直接打断预算聚合 | brokenProviderCannotSuppressOtherProvidersOrOtherQueries | 红 |
| 启动诊断改回调用完整预算快照 | ApiLifecycleAndLoadBridgeTest:describeResolutionDoesNotAdvanceAdaptiveBudgets | 红 |

每项唯一锚点替换，运行完整套件并确认目标 JUnit 方法真实失败；不把编译失败计入。finally 用 git checkout -- 精确文件恢复，逐字节核对。前 10 项在 d960f8a 验证，诊断项在 4f1923a 验证；最终两步合并状态重新跑全量。日志、逐项 JSON 在忽略目录 .analysis_tmp/round30-*，本表为持久变异记录。

只读核对相邻六个干净工程：Chunky dceba77、HyperSpeed d793b5a、Twilight 55c0f5f、Streams 315a44f、C2ME QoS 0868288、AutoTune b91490a。核对了初始化注册、信号实现、实际消费者与构建依赖。四个相关 API 源文件在新旧仓库中的差异仅为中文职责注释，不把字节不同误报为接口不兼容。

诊断链的源码证据：原 LoadSignalBridge.describeResolution 只使用快照的最高忙碌类别，却会经 RoadLoadSignalsRegistry.snapshot:92..93 询问未知预算；Chunky RoadWeaverLoadSignals:74..76 原样委托；AdaptiveChunkController:250..251 将 -1 夹为 1，:307..309 再夹取并写回 roadBackgroundBudget。当前道路日志已移除这条预算调用。测试复刻这一写回，不宣称实际启动时已经测得多少延迟。

### 三、我没能验证什么

- **需要真机的**：六个模组实际同装时的 FPS、帧时间 p95/p99、MSPT、GC、玩家移动/GUI/传送、Chunky readiness 交接与取消。没有测得本轮减少多少卡顿，不能把 1201 项全绿换算成帧率改善。
- **需要真实安装件和存档的**：运行中 API 的唯一提供件、相邻 mod 是否为上述源码构建、4096 预热峰值内存/耗时、首次进入与重开、长期切换维度和停服。不把 compileOnly 指向旧 contract 的源码兼容当成安装件已验收；没有重编或部署外部 mod。
- **已核实但本轮未修的执行旁路**：CpuAccurateHeightBackend 三处 parallelStream 及 CoarseCorridorPlanningAdapter 两轮 INITIAL 子任务仍未统一遵守普通游玩预算；改变 API 消费不能自动收住这些内部计算。全域采样/FMM 工作集也未改，4096 上限仍不代表安全规模。
- **外部协作仍保留的边界**：C2ME QoS 的 RuntimeLimiter:311..319、:518..558 仍反射查询内部类，源码中的“已经迁到 API”描述不能当事实。其 Gateway 信号当前没有对应标准 provider，迁移不能直接删掉这条能力；也须保留无 API 安装场景。道路三处已拍板保留的兼容反射不动。TELEPORT.busy 本身没有提出道路全停要求，先验证预热依赖再决定政策。
- **诊断未覆盖的公共入口**：API 的通用 snapshot()/describeAll() 仍会询问 -1 上下文的建议，本轮只删除道路启动日志不需要的调用。其他消费者若使用它们，仍须查提供方是否纯读/正确弃权，未把公共 API 整体称为无副作用。
- **我怀疑但没有证据的**：现有多控制器在现场的振荡程度、故障源出现频率与 API 查询自身占比；不据此调阈值或宣称性能倍数。crossing 依用户要求保持现状，双平滑、跨度回写、QualityGate、旧区块重铺和废弃 GPU 精确采样都不改默认。

### 四、遗留半截的东西

没有临时生产变异残留；两步源码已提交并推送，生产文件与 4f1923a 一致。文档和本交接完成后按停止前流程再提交、pull --rebase、push，并确认工作区为空；最终提交及远端核对以交付记录为准。本轮闭合 API 消费和道路诊断两处边界，执行旁路、大范围工作集及外部 QoS 迁移未开工，不标成已完成。

### 五、下一轮我会先做什么

沿用本次核对的信号通道，先为 CPU 精确采样和粗路径子任务注入最小执行策略端口，用受控任务验最大并发、前台让路、取消和开局豁免；再换有界执行并对拍三通道、完整键集合与路径，防止父任务同步等待同池子任务。随后解决全域采样/FMM 工作集，再回到 READY 收敛。QoS API 迁移单独保留任务：先补齐 Gateway 与无 API 场景的守卫，保持传送/Chunky/巡航优先级，再替换读取边界；不再额外造一个总调度器。

### 六、我踩到但已解决的坑

注册表里有来源不等于生产策略真的消费它，必须直接走 RuntimeLoadPolicy 断言。诊断快照没有现场参数，不能拿近似推荐决定派发；更不能假定建议方法是纯 getter，当前 Chunky 的自适应调用会写状态。多源正数建议不能盖过显式暂停，0 lane 与负值弃权必须分开；worker 原进展下限与 preflight 豁免不能顺手改掉。把消费类直接放 runtime 会新增不必要的 API 依赖，现有 apiimpl 适配器足够容纳这一职责，最终跨层基线只减不增。

---

## 第 31 轮 ｜ 2026-11-16 ｜ 预热门故障结案与三模组流程复核 ｜ 源码 `59ac4f7` ｜ `PASS tests=1212 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| GreatWallPrewarmCompat | 无模组且无 API 提供方立即通过；首次/后续调度失败统一结案并区分原因。默认五秒启动宽限、十分钟停滞、三十分钟上限及系统属性保留。清理与实际默认值相反的旧注释。 | 行为变更：修复空等和首次调度失败时 Future 永不完成 |
| PrewarmGateWork | 拆出时钟、探针和调度边界，取消后不再轮询；非正常放行有原因。期限使用经过时间比较，避免相加溢出。117 物理行，新增逻辑有中文原因注释。 | 编排等价搬迁与故障修复同属上述小提交 |
| PrewarmGateWorkTest；RoadJUnitRegressionSuite | 新增 11 项，按 FQCN 登记。直接调用公开生产入口与注入入口，断言非空调度及具体结案原因，不以编译失败算负控。 | 测试基建 |
| ARCHITECTURE；STATUS；ROADMAP；VALIDATION；HANDOFF | 记录三模组真实信号归属、正常依赖链和未完成边界；同步当前回归与后续清单。 | 文档 |

源码已独立提交。生产 Java 文件 672 个，超过 1000 有效行 0、超过 800 有效行仍 2；本轮未改几何、配置键、API 签名或持久化格式。没有打包、安装和部署。

### 二、我验证了什么（附负控）

开工 main / ee4b9c8、工作区干净。第一次 pull 因连接重置失败，HTTP/1.1 重试成功 Already up to date。先跑完整回归及 Fabric，基线 1201。首次改动的功能用例通过，但文件行尾守卫抓住 Set-Content 留下的一个 CRLF；归一 LF 后 1212 全过。7 组变异全部恢复后再次全回归及 Fabric 编译，仍为 1212，零跳过、中止、失败，BUILD SUCCESSFUL。

| 故意改坏生产代码 | 目标 PrewarmGateWorkTest 方法 | 结果 |
|---|---|---|
| 删无长城直接通过分支 | publicEntryWithoutInstalledWallReturnsAlreadyCompletedFuture | 红 |
| 首次调度失败不结案、只对后续失败结案 | firstSubmissionFailureAlwaysCompletesReturnedFuture | 红 |
| required 只看安装状态、忽略已注册 API | registeredProviderStillRequiresWaitingWithoutFabricModInventory | 红 |
| 五秒首次 ready 宽限改为 0 | installedWallKeepsStartupGraceAndWaitsForRealCompletion | 红 |
| 取消后仍执行探针查询 | cancelStopsQueuedPollBeforeQueryingOrRearming | 红 |
| 有真实进展也不刷新停滞计时 | actualProgressResetsDefaultTenMinuteStallWindow | 红 |
| 删除绝对期限 | movingProgressCannotBypassDefaultThirtyMinuteCap | 红 |

每项唯一锚点、独立执行全回归并确认目标 JUnit 方法失败；finally 用 git checkout -- 精确文件从暂存索引恢复并逐字节核对。脚本、日志和 JSON 保留在忽略目录 .analysis_tmp/round31-*。新代码先精确暂存作为恢复源，完成全部变异及正向复验后提交；未覆盖任何用户未提交改动。

只读核对相邻 Streams 315a44f、长城 01408e65，二者工作区干净、没有修改。StreamsServerLifecycleMixin 在新档 createLevels 初始出生点前和 prepareLevels HEAD 等预热并广播 RiverReadinessBroadcast；长城 RoadWeaverLifecycleBridge 订阅 RiverReadinessApi，GreatWallReadinessProvider 另提供长城门。道路 InitialPlanWaiter 的沿线板块补热及旧 fireRiversPrewarmed 回调更晚，现行长城不靠该旧回调启动。

### 三、我没能验证什么（最重要）

- **需要真机的**：新建/重开最终 Mixin 注入次序、完整三模组流程、长城实际发布与失败/超时、C2ME/Chunky 同跑、帧时/MSPT 和首次加载尾延迟；源码 priority 不等于已证明最终执行顺序。
- **需要真实存档/安装件的**：停服和重开、跨维度旧回调、4096 档内存/耗时、各模组 jar 与 API 唯一提供件。未部署本轮代码。
- **我怀疑但没有证据的**：现有 Fabric 门已开快路径在启动 ready 窗口里的实际竞态；没有最终变换字节码或现场日志，本轮保留并单列后续验证，不能称为已复现穿越。Streams awaitStartup 故障出口与广播成功是否一致尚未穷尽。
- **已核实未修的生命周期边界**：Fabric MinecraftServerMixin 的后台线程仍捕获 server/level，等待后直接 server.execute；停服没有主动取消所有门，也没有旧世界代次校验。单组件取消能力已测，不等于生产停服接线已完成。
- **已核实未修的负载与工作集**：CPU 精确采样三处 parallelStream、粗路径 INITIAL 子任务仍有游玩预算旁路；全域采样/FMM 工作数组未治理，4096 仍不能宣称安全。没有任何新性能倍数或“后台已不卡”的结论。
- crossing 按用户决定保留；双平滑默认、跨度回写、QualityGate、旧区块重铺和废弃 GPU 精确采样均不改。改动代码有中文注释，本轮未给历史全库补写注释。

### 四、遗留半截的东西

本轮单门修复与测试完整；临时变异全部恢复。生产停止生命周期、三模组最终时序、河流超时语义仍待做，不用单门测试代替整组验收。文档提交后执行 pull --rebase、push 和空工作区检查，最终远端核对以交付为准。

### 五、下一轮我会先做什么

先为 CPU 采样和粗路径子任务接可测执行端口，确认最大并发、前台让路、取消及开局豁免，再上线有界执行，防父子同池等待。并行的下一小项是预热门会话代次与停服取消，用 Fake 验旧 Future 迟到不会启动旧世界道路；再验证最终 Mixin 顺序。大范围工作集随后按分配前估算、按需瓦片和等价求解逐步推进，保留完整核心和合法连边。READY 入口收敛、文件锁表及写盘优化继续按 ROADMAP，不无实测直接异步化。

### 六、我踩到但已解决的坑

旧 schedule 首次失败仅打日志而没有拿到 gate 去 complete，后续 trySchedule 有结案并不能补首次出口。无长城也走五秒宽限来自删除 fast path 后没有按“是否需要门”区分。已有提供方必须也算需要等待，不能只依赖 Fabric 安装清单。系统属性默认实际是十/三十分钟，旧注释写零默认已漂移；本轮保留实际行为。PowerShell Set-Content 最后会带 CRLF，Java 文件写后必须统一 LF，守卫确实会红。暂存索引可用作变异恢复源，但必须先确认其中是本轮已验证代码再 checkout。

---

## 第 32 轮 ｜ 2026-11-16 ｜ 道路显式等待河流及长城，明确测试归属 ｜ 源码 `bd61dd8` ｜ `PASS tests=1222 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 性质 |
|---|---|---|
| LoadSignalBridge | initialRiversReady 读取已有 RiverReadinessApi；仅未安装河流时略过。原 API 不变，不让道路代替河流发布 ready。 | 行为变更 |
| RoadStartupGate | 道路消费方先等河流，再确认长城完成或缺席。两者完成同步返回；否则非阻塞轮询。长城旧门正常返回后再问 ready，超时但未 ready 结束为失败。停服可取消本次门及其创建的长城轮询。98 物理行，中文原因注释。 | 行为变更 |
| Fabric MinecraftServerMixin；ServerPlanningHooks | 用上述前置门替换“只问长城”的入口，去掉单独后台等待线程；失败记 ROAD_INITIAL_GEN_NOT_STARTED；停服撤等待，服务端真正执行前查 stopped/取消/stopping。 | 行为变更 |
| RoadStartupGateTest；RoadJUnitRegressionSuite | 10 项按 FQCN 登记。测试道路消费行为，Fake 不冒充河流或长城实现；Fabric 自有文件的静态接线守卫单列。 | 测试基建 |
| ARCHITECTURE；STATUS；ROADMAP；VALIDATION；HANDOFF | 说明两种模组组合的顺序及发布/消费职责，移除已经失效的旧状态；保留待验边界。 | 文档 |

用户明确：有长城时河流→长城→道路，没长城时河流→道路；测试必须在对应模组。本轮只修改道路仓库，道路自己读取前置条件的测试留在道路。未移动第 31 轮单门测试，因为它测的是道路如何等待长城，不是长城自己的预热。生产 Java 673 个，有效行超过 1000 为 0，超过 800 仍 2；未改配置键、持久化 schema、API 签名、几何及外部仓库。

### 二、我验证了什么（附负控）

开工 main/f78c6c0 干净，git pull --rebase Already up to date。基线完整 1212 + Fabric；新增前八项后 1220，补完成回调故障与 Fabric 接线后 1222。逐项变异并恢复后完整双模块仍 1222、0 跳过/中止/失败，BUILD SUCCESSFUL。

| 故意改坏生产代码 | RoadStartupGateTest 目标 | 结果 |
|---|---|---|
| 不读河流，直接进入长城阶段 | withoutWallRoadStillWaitsForRivers | 红 |
| 长城条件恒满足 | installedWallIsOnlyAwaitedAfterRiversAndRoadWaitsForBoth | 红 |
| 长城旧门超时也当 ready | wallTimeoutReleaseCannotPretendTheWallIsReady | 红 |
| 道路河流 API 适配恒 true | roadAdapterReadsTheSharedRiverApiAndOnlyBypassesAbsentMod | 红 |
| 停服 cancelPending 不取消 | stopCancelsRiverWaitAndLateRiverReadinessCannotAdvance | 红 |
| Fabric 启动入口把河流谓词改 true | fabricStartupActuallyUsesOrderedGateAndStopCancelsIt | 红：静态接线守卫 |
| Fabric 停服去掉 cancelPending 调用 | fabricStartupActuallyUsesOrderedGateAndStopCancelsIt | 红：静态接线守卫 |

每项断言唯一替换锚点；全套运行确认目标 JUnit 实际失败，finally git checkout -- 精确文件从本轮暂存索引恢复，并逐字节核对。两条 Fabric 检查是静态接线证据，不宣称运行了 Minecraft Mixin。日志/JSON/脚本在忽略目录 .analysis_tmp/round32-*。源码已提交，文档另提交。

只读核查 Streams 315a44f 与长城 01408e65，未修改、未构建、未部署。长城确实 addListener 到 RiverReadinessApi；道路此前该 API 仅用于 LoadSignalBridge 日志，启动入口只问长城，现已补消费。河流广播契约目前由 roadweaver_api 承载，提供方是 Streams；名字和 Jar 宿主不改变职责归属。

### 三、我没能验证什么（最重要）

- **需要真机的**：三模组最终 Mixin 注入次序，新建和重开完整加载时序、无长城组合、真实退出与旧回调交错；静态接线守卫不能替代现场。没有 FPS/MSPT/进档时间结论。
- **需要真实存档/安装件的**：API 唯一提供件、跨存档残留、4096 内存/耗时及部署后行为。本轮未打包或安装。
- **已核实未修的河流发布方问题**：StreamsPrewarmService.awaitStartup:197 起捕获 CompletionException 后返回，而 StreamsServerLifecycleMixin 随后仍 publishReady；beginStartup 对禁用/上下文缺失/异常也有无 Future 出口。必须在 Streams 仓库补其生产出口测试并定义禁用、失败和真正完成的语义，不能在道路 Fake 中宣称修好。
- **长城自己的测试未运行**：已确认订阅代码及 provider，尚未验证自身状态机异常、启动与重开；对应守卫应放长城仓库。若只依赖其现有 ready API，启动 request 之前暂时 ready 的竞态仍需真实时序核验。
- **本轮政策边界**：道路入口不再因为长城门超时就提前开工；会记录明确失败并停止这轮初始生成，没有自动失败重试或错误 UI。河流 API 若永不 ready，会继续非阻塞等待直至停服取消，尚无提供方失败状态可消费；这不阻塞服务端线程，也不代表道路一定完成。
- **已知待做**：CPU parallelStream/粗路径 INITIAL 的预算旁路、4096 全域采样/FMM 工作集、READY 收敛与写盘现场证据。crossing 按用户决定不改，其他几何和格式边界不改。

### 四、遗留半截的东西

本轮道路消费顺序与停服取消已接生产，10 项及7组变异完成；没有临时破坏。三模组整个流程未称为全部验收，发布方故障语义列为外部仓库后续。PENDING 在停止时取消未完成道路门，已完成但尚未执行的回调另靠原 server.isStopped 和 RoadLifecycle.isStopping 检查；真实并发停止交错未穷尽。

### 五、下一轮我会先做什么

优先在 Streams 仓库给启动成功/失败/禁用及广播的实际出口补可测端口和负控，明确哪些状态表示就绪；长城订阅与完成契约守卫放在长城仓库。然后用三模组的新建/重开/缺长城样本验证整条顺序。内部执行预算和大范围工作集仍按 ROADMAP 推进，不用另建总调度器。

### 六、我踩到但已解决的坑

没有长城只说明长城依赖满足，不代表河流已好；道路必须直接消费河流 API。既有长城门 complete(null) 包含成功与超时放弃，若不重查会把“结束等待”混成“已就绪”。whenComplete 回调中探针再抛错会只让衍生 Future 失败，原门仍悬挂，因此必须完成原门异常。Fabric 类只编译不进入 common 的 JUnit，故用行为端口测决策、单列静态接线守卫验入口，不能把后者叫运行时验证。

交付同步记录：源码 bd61dd8、文档 b93f4e8 已本地提交，工作区干净。停止前 pull --rebase 成功；随后 push 及 HTTP/1.1 重试分别因 github.com:443 连接失败、连接重置未成功。当前 origin/main=f78c6c0，未声称已推送；网络恢复后执行 git push 并核对远端。


---

## 第 33 轮 ｜ 2026-11-16 ｜ 河流发布方失败语义 ｜ Streams源码82cc818 ｜ 道路PASS tests=1222 skipped=0 aborted=0 failed=0

### 一、改了什么

本轮实际代码在河流仓库 streams-reflowing-src，测试也在河流仓库。新增 RiverStartupReadiness 每世界结果门，StreamsPrewarmService 的成功、明确禁用、初始化失败、异步失败和停服出口不再混淆。两个 Fabric 生命周期入口改走 publishStartupReadiness；失败不发布，禁用作为无前置工作明确放行，停服后成功迟到也不发布。保留旧 awaitStartup 签名、API/配置键、缓存格式、几何和 Mixin 优先级。新增10项行为测试和2项静态接线守卫，零测试发现改为失败。河流HANDOFF.md完整记录同一轮。

道路本轮仅同步 STATUS、ROADMAP、HANDOFF，不把外部守卫塞进道路。长城只读，未修改。当前清单：河流出口离线完成 → 长城订阅/晚订阅/重开/完成守卫 → 三模组现场顺序 → 道路CPU采样与粗路径预算 → 4096工作集 → READY状态转换 → 继续拆职责 → 按计时证据优化写盘等热点。

### 二、我验证了什么（附负控）

河流基线8项，完成后20项，skipped/failures/errors均0，test compileJava BUILD SUCCESSFUL。8组生产变异全部目标JUnit变红：异步失败改成功、初始化失败改成功、所有结果都发布、禁用不放行、忽略停止；另外重开入口漏接门、服务停止漏停门、初始化异常漏记录失败3组为静态接线。唯一锚点计数；finally git checkout -- 精确文件从本轮暂存索引恢复，并核对原字节。正向复验通过，日志/脚本/JSON在河流忽略目录build/round33-*。道路完整双模块回归1222、0跳过/中止/失败，Fabric编译成功，日志.analysis_tmp/round33-road-final.log。

### 三、我没能验证什么（最重要）

- 需真机：新建、重开、无长城的最终Mixin顺序与实际发布；没有部署，没有FPS/MSPT或加载耗时结论。
- 需真实ServerLevel：初始化/构建故障与广播实际联动。本轮以生产结果组件行为和静态入口守卫确认，未宣称端到端通过。
- 已知未修：河流API只有ready布尔值，没有失败状态；失败后道路非阻塞等待直到停服，无重试或失败UI。河流旧构建仍可能迟到清理全局采样模式/进度，跨世界清理代次待验。
- 长城自身订阅/晚订阅/重开和完成守卫待做，必须在长城仓库。道路子任务预算、4096全域内存仍待做；crossing等依赖真机的项目继续保持现状。

### 四、遗留半截的东西

本轮河流结果门与守卫完整，变异全部恢复并已提交82cc818。河流仓库没有remote/upstream，pull无法同步、push无目的地，未擅自设置。道路之前两个提交因GitHub网络失败仍待推送；本轮文档提交后再尝试，以最终同步结果为准。

### 五、下一轮我会先做什么

在长城仓库验证河流订阅与晚订阅、世界切换和自身就绪完成/失败出口，基线先行，再做端口/负控。之后推进道路CPU精确采样和粗路径内部任务预算，不重新造总调度器。

### 六、我踩到但已解决的坑

河流Gradle9.4.1测试worker ClassNotFound源自本机JDK把含中文路径的UTF-8参数文件按GBK读取，独立java @args同classpath对照已复现：UTF-8失败、GBK LOAD_OK。该仓命令需GRADLE_OPTS和Gradle参数同时指定-Dfile.encoding=GBK（源码编译仍UTF-8），再执行test compileJava --offline --daemon；道路8.8命令不变。原源码实际LF，保留行尾避免整文件diff。await返回不表示完成，失败记录必须在同步初始化和异步回调两处覆盖。

本轮同步结果：文档提交后再次 pull --rebase、push 均因 github.com 低速超时失败，origin/main 仍 f78c6c0，本地领先3个提交；未声称推送成功。河流82cc818及道路文档已本地提交，两个工作区git status --porcelain为空。


---

## 第34轮 ｜ 2026-11-16 ｜ 长城订阅与严格就绪守卫 ｜ 长城928d3302 ｜ 道路PASS tests=1222 skipped=0 aborted=0 failed=0

### 一、改了什么

本轮代码与测试均在plaincraft-great-wall，原分支refactor/planning-frozen-feature-paint，不改main。先提交行为等价端口搬迁92720388：订阅回调和就绪读口可注入，公开默认仍接WorldServices；再提交行为变更928d3302：

- 严格等待被clear唤醒后须确认仍属同一闩锁，旧世界退出不再被当成发布成功。
- clear清除旧阶段、子进度及阶段计时，防止新档读取旧COMPILE快照。
- RetainedRiverSignal同锁交接早到河流信号与会话注册，移除构造器先检查、再SESSIONS入表的漏信号窗口；停服先detach，STOPPED清保留状态。重复通知仍由生产会话幂等。
- 订阅回调失败新增可辨识WARN；失败不连累河流发布者。未改API签名、配置键、存档、几何或Mixin优先级。
- 新增24项测试：10契约、6严格门、6保留信号、2静态接线；都留在长城。新协作类32行，改动生产文件物理行均小于1000（最大858）。原依赖锁只补本地API开发件testRuntimeClasspath坐标一条。

道路本轮只更新STATUS、ROADMAP、HANDOFF。河流82cc818只读，工作区干净。长城完整交接及16组变异表见相邻仓库HANDOFF.md。

### 二、我验证了什么（附负控）

开工长城01408e65干净。默认pull因无upstream未执行，随后显式pull --rebase origin refactor/planning-frozen-feature-paint确认无需更新。基线测试先因本地API重映射坐标未锁失败，补一条锁记录后全量745项通过；端口迁移后既有Prewarm系列与compileJava通过。新回归先复现旧严格等待误报ready和旧阶段残留，16项里2项失败，修后转绿。

最终长城test compileJava为769项（745+24），failures/errors/skipped均0，BUILD SUCCESSFUL；日志build-round34-full-final.log。道路完整双模块回归1222、0跳过/中止/失败，Fabric编译通过，日志.analysis_tmp/round34-road-final.log。

| 生产变异分组 | 结果 |
|---|---|
| 漏通知长城、回调Error逃逸、isReady恒true、等待单位错误、进度签名含时钟 | 5组目标行为测试红 |
| clear旧闩锁当成功、旧阶段不清、旧子进度不清、失败打开世界门 | 4组目标行为测试红 |
| 早到信号不重放、不保留ready、detach不注销、clear不清会话表 | 4组目标行为测试红 |
| WorldServices实际出口改非严格门、忽略Showcase等待 | 2组经真实注册提供方的行为测试红 |
| 实际启动漏attach | 1组静态接线守卫红 |

16组逐条唯一锚点，编译成功且指定JUnit目标实际失败才算红；finally git checkout -- 精确文件从本轮暂存索引恢复，内容与原行尾字节核对。完整JSON/脚本/日志在长城忽略目录build/round34-*，临时变异全部还原。使用真实RiverReadinessApi和GreatWallReadinessRegistry；Fake只替换内部端口，不能替代真实Minecraft生命周期。

### 三、我没能验证什么（最重要）

- **需真机**：新建、重开、缺长城/缺河流的实际最终Mixin时序与真实停服。未部署，没有FPS/MSPT/加载耗时结论。
- **真实事件未运行**：长城会话attach/detach有静态接线；同锁组件有行为和128轮并发交接，但未构造完整ProductionSession或运行SERVER_STARTING/STOPPING。不能称真实存档验收。
- **已核实仍有全局边界**：旧ProductionSession异步任务仍可写静态PrewarmProgress。本轮解决旧严格等待的误成功和河流通知的会话注销，不代表所有异步写入都带世界令牌；根治需所有进度发布回调接令牌。
- **既有失败政策保持**：长城失败不开放门；河流只有bool就绪，失败传播、重试UI仍未新增。道路不会把超时结案当ready，但可能不再启动本轮初始生成。
- CPU三处parallelStream、粗路径两轮INITIAL子任务、4096工作集和READY状态收敛仍待做。crossing按用户决定保留，其他需真机裁决项未改。

### 四、遗留半截的东西

本轮长城守卫和三处边界修复已完整提交并push到现有E盘备份origin的原分支，文档后续提交5ade8e9b也已push；工作区干净。未合并长城main。道路本轮开工pull已成功，文档提交后push并核对远端，结果追加在末尾。河流没有remote的状态未改。

### 五、下一轮我会先做什么

三模组完整顺序排为现场验收，继续推进无需现场数据的道路执行预算：先给CPU精确采样与粗路径内部子任务接可测执行端口，验证总并发、前台让路、取消及父子等待，再替换并行流/INITIAL预算旁路。保留开局豁免和全部样本、路径产物。随后做4096分配前估算与按需工作集，不缩用户核心或丢候选。

### 六、我踩到但已解决的坑

负控发现本轮一条测试先detach再clear，使“clear不清会话表”仍绿；已新增不先detach的独立用例，原变异确实转红。旧测试保留，继续守卫detach本身。Windows新文件checkout会把LF转CRLF；先核对内容再恢复原始行尾字节，修正脚本后完整重跑16组。定向锁更新会移除未解析配置的锁归属，只合入新坐标。严格门的清理唤醒与成功发布必须区分，不能靠“等到了”推断“成功了”。

本轮同步结果：道路pull成功；普通push与HTTP/1.1重试均因GitHub低速超时失败，origin/main仍f78c6c0，本地领先4个提交。长城HEAD及其远端均为5ade8e9b，已同步。河流仍无remote。本条并入道路文档提交后保持工作区干净，未声称道路push成功。

---

## 第35轮 ｜ 2026-11-16 ｜ 游玩期CPU预算与粗路径任务边界

### 一、改了什么

先行为等价fa17851拆CPU世界读取与执行口，新增9项、1231全绿；再行为变更95253e5接预算。粗路径先行为等价3007411统一提交器，1251全绿；随后行为变更e113ee3完成下面边界。

- CPU三入口及非噪声顺序路径共用已有粗采样许可；普通游玩每批在调用线程逐单元执行，多批共用活跃数。开局/preflight保留并行。前台在单元边界复核，同步嵌套只占一份许可。
- 异步搜索在提交方捕获角色、世界代次及预热状态，MAP_READ界面豁免保留，Chunky独占仍阻挡；中断/停止/换代在暂停循环内可退出，最后一次读取中换代也拒绝成功。
- 普通父用INITIAL子池。实际启动链AsyncPlanningOps又将父任务发到INITIAL，真实池缩为1先红两项；INITIAL父改借已有公共计算池，避免同池互等，不新增物理池。
- 公共池直接submit返回的取消状态没有中断实际搜索，先红一项，再改自有FutureTask经execute提交，使cancel(true)到达真实线程。
- 许可等待不耗搜索期限；物理排队和实际搜索仍分别限时，0不限时也检查停止/代次。内部取消透过Future包装正确上抛；顺序等待的失败/中断/直接取消也取消兄弟。
- 五类API调用键包装器迁到RuntimeSignalMethods。新增中文原因注释；两条合法执行端口依赖在基线逐项说明，未放宽求解层规则。配置键/默认、存档schema、几何算法未变；更新超时说明。
- 生产Java文件679个，有效行超1000为0；本轮修改文件物理行也均小于1000，RuntimeLoadPolicy约941行，新增协作类均小于200行。

### 二、我验证了什么（附负控）

开工e7496e9，git status空，pull --rebase成功；基线1222零跳过/中止/失败，Fabric成功。新增52项：CPU29、粗路径23，含2项语法树接线，三类都已显式登记。真实INITIAL=1先红2项，公共池取消先红1项；编译/命令参数失败不算复现。

最终PASS tests=1274 skipped=0 aborted=0 failed=0且BUILD SUCCESSFUL，Fabric编译通过；日志.analysis_tmp/round35-final-after-mutations.log。31组（28行为、3静态）均断言唯一锚点、编译成功、指定JUnit失败；finally git checkout -- 精确文件，从已验证暂存索引逐字恢复，再跑全量。脚本/JSON为.analysis_tmp/round35_mutations.py、round35-mutations.json。

| # | 改坏了什么 | 目标用例 | 结果 |
|---|---|---|---|
| 1 | 公开工厂绕开预算出口 | `publicFactoryActuallyUsesTheBudgetedSourceFactory` | 红（静态） |
| 2 | 默认采样改无限制执行 | `everyDefaultEntryRunsGameplayUnitsOnCallerAndKeepsChannels(int)` | 红 |
| 3 | 读世界前不取得共享许可 | `everyDefaultEntryRunsGameplayUnitsOnCallerAndKeepsChannels(int)` | 红 |
| 4 | 超过活跃预算仍放行 | `accurateSamplingSharesTheExistingCoarsePermit` | 红 |
| 5 | 跳过前台暂停 | `foregroundPauseIsRecheckedBetweenChunks` | 红 |
| 6 | 完成/异常后不释放许可 | `readFailureReleasesPermitAndNextRequestStillWorks` | 红 |
| 7 | 忽略世界代次 | `epochChangingDuringLastReadDoesNotReportGridSuccess` | 红 |
| 8 | 暂停循环丢失取消条件 | `cancellationEscapesForegroundWaitWithoutReadingWorld(java.lang.String)` | 红 |
| 9 | 预热丢失实际并行 | `startupAndPreflightKeepParallelExecutionWithoutGameplayPermit(boolean)` | 红 |
| 10 | MAP_READ被自身界面暂停 | `mapReadKeepsScreenExemptionButStillWaitsForChunky(boolean)` | 红 |
| 11 | 非噪声顺序路径绕开预算 | `everyDefaultEntryRunsGameplayUnitsOnCallerAndKeepsChannels(int)` | 红 |
| 12 | 逐列请求读错列 | `positionsDeduplicateXZAndOnlyReadRequestedColumns(boolean)` | 红 |
| 13 | 网格运动阻挡通道读成海床 | `gridKeepsSignedCoordinatesAndReportsCompletionOnce(boolean)` | 红 |
| 14 | 粗路径直接提交无预算任务 | `productionDefaultUsesInitialPhysicalPoolButStillTakesGameplayPermit` | 红 |
| 15 | 普通父子池误接同一共享池 | `productionDefaultUsesInitialPhysicalPoolButStillTakesGameplayPermit` | 红 |
| 16 | 第一遍绕开提交器 | `bothProductionPassesUseTheSharedSubmissionBoundary` | 红（静态） |
| 17 | 第二遍绕开提交器 | `bothProductionPassesUseTheSharedSubmissionBoundary` | 红（静态） |
| 18 | 排队后才捕获代次 | `epochIsCapturedBeforeQueueingAndFreshSubmissionCanStillRun` | 红 |
| 19 | 丢失提交方角色 | `submittingMapReadRoleSurvivesDifferentWorkerRole` | 红 |
| 20 | 同步嵌套重复占许可 | `nestedSynchronousCpuUnitsUseTheSamePermit` | 红 |
| 21 | 主动让路算入搜索超时 | `waitingForPermitDoesNotSpendSearchTimeout` | 红 |
| 22 | 实际搜索不限时 | `actualSearchStillHasATimeout` | 红 |
| 23 | 物理队列不限时 | `physicalQueueStillHasATimeout` | 红 |
| 24 | 未执行任务停服后无限等待 | `stoppedTaskThatNeverRanDoesNotLeaveAnUnlimitedWaitPending` | 红 |
| 25 | 并行等待把内部取消当失败 | `wrappedCancellationDoesNotBecomeOrdinaryFailureAndCancelsSiblings(boolean)` | 红 |
| 26 | 顺序等待把内部取消当失败 | `wrappedCancellationDoesNotBecomeOrdinaryFailureAndCancelsSiblings(boolean)` | 红 |
| 27 | 顺序失败不取消兄弟 | `ordinaryFailureCancelsAllSiblingTasks(boolean)` | 红 |
| 28 | 顺序中断不取消兄弟 | `interruptedParentCancelsAllSiblingTasks(boolean)` | 红 |
| 29 | 等待绕过执行期限口径 | `waitingForPermitDoesNotSpendSearchTimeout` | 红 |
| 30 | INITIAL父子回同一单线程池 | `initialParentWithOnePhysicalWorkerCanWaitForItsChildren(boolean)` | 红 |
| 31 | 公共池直接submit导致取消不打断线程 | `cancellingCommonPoolChildInterruptsActualSearch` | 红 |

### 三、我没能验证什么（最重要）

- 需真机/存档：FPS/MSPT、游玩单批吞吐、公共池与其他mod争用、公平性、NoiseChunk单元耗时、FMM中断尾延迟。无性能倍数或不卡顿保证。读口Fake不等于真实NoiseChunk/ServerLevel已运行。
- 固定非空路径验证任务传递和异常，原FMM/DP/BAND回归继续跑；未做真实整路几何A/B，未部署jar。一次已开始的噪声计算及不响应中断的代码不能强停。
- 共享的是本轮接入的执行边界，不是整合包所有线程。其他结构解析、采样入口及外层等待仍须盘点；gameplayThreadCap=2不是整合包总共两线程。
- 源码核对ThreadPoolManager:870在runDelegate=false时跳过delegate，其他Future类型的终态未逐条实跑。本轮Coarse Task主动取消只能局部兜住；下一项先复现通用FutureTask/CompletableFuture链，不声称全局解决。
- 4096全域采样和FMM工作集未解决，仍无OOM安全验收。没缩核心、丢候选或重开废弃GPU精确采样。
- 三模组真实Mixin/启动重开、旧异步进度的世界令牌仍待验；crossing、双平滑、跨度回写、QualityGate和自动重铺保持现状。

### 四、遗留半截的东西

本轮CPU/粗路径接线及产物、取消、超时守卫已完成。源码提交e113ee3；文档随本条提交。河流82cc818、长城5ade8e9b只读，测试均放道路。代码push已尝试两次，均因无法连接github.com:443退出128（约21秒），日志round35-code-push.log、round35-code-push-retry.log。远端跟踪仍为f78c6c0，本地源码领先8项提交；文档提交后再次推送，未成功前不称远端已同步。

### 五、下一轮我会先做什么

先用真实排队/前置闸门核对通用RoleExecutor的FutureTask、CompletableFuture停止终态，保证取消不执行世界动作；然后做4096分配前长整型估算，再对拍按需瓦片、稀疏求解工作区。READY增量和写盘优化继续按计时推进；需真机的几何/跨度裁决不改默认。

### 六、我踩到但已解决的坑

Windows Python默认CRLF被守卫抓到，改为write_bytes UTF-8/LF。普通Executor线程没有道路角色，实际是null而不是PLANNING，夹具已纠正。CompletableFuture内部取消直接抛出，FutureTask会包装ExecutionException，分别实测。只验普通父/INITIAL子会漏掉开局同池父，真实缩池才复现。ForkJoin取消状态不代表中断，必须断言真实阻塞线程。PowerShell带点的-PtargetClass需整参加引号，命令错误不记作JUnit红。
