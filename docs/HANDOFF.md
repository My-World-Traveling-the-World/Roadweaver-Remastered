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

第42轮已等价拆出样条/按站序高度投影与宽度落列：门面从660/481降至474/320行，两个纯计算组件148/117、101/84行（物理/有效）。先冻结bc69e3b加22项守卫，再迁出7个方法体并核对去注释/空白后完全一致；180组种子曲线与54组真实生产入口逐站/逐列对拍，1516全绿、23组变异均红。源码/测试2a6fc2d、6a2a608已推送。双平滑、桥头、急弯、长桥正弦和存档均不改；真机FPS/加载耗时未量。下一项先测宽度落列的临时分配与站号查询成本，再决定有序站号存储优化。详见末尾第42轮与[路线](ROADMAP.md)。

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

本轮CPU/粗路径接线及产物、取消、超时守卫已完成。源码提交e113ee3；文档随本条提交。河流82cc818、长城5ade8e9b只读，测试均放道路。代码push已尝试两次，均因无法连接github.com:443退出128（约21秒），日志round35-code-push.log、round35-code-push-retry.log。随后含文档提交d5c8d9d的推送成功，远端main从f78c6c0推进到d5c8d9d；本次补记单独提交，停下前再核对最终HEAD与origin/main及工作区。

### 五、下一轮我会先做什么

先用真实排队/前置闸门核对通用RoleExecutor的FutureTask、CompletableFuture停止终态，保证取消不执行世界动作；然后做4096分配前长整型估算，再对拍按需瓦片、稀疏求解工作区。READY增量和写盘优化继续按计时推进；需真机的几何/跨度裁决不改默认。

### 六、我踩到但已解决的坑

Windows Python默认CRLF被守卫抓到，改为write_bytes UTF-8/LF。普通Executor线程没有道路角色，实际是null而不是PLANNING，夹具已纠正。CompletableFuture内部取消直接抛出，FutureTask会包装ExecutionException，分别实测。只验普通父/INITIAL子会漏掉开局同池父，真实缩池才复现。ForkJoin取消状态不代表中断，必须断言真实阻塞线程。PowerShell带点的-PtargetClass需整参加引号，命令错误不记作JUnit红。

## 第36轮 ｜ 2026-11-16 ｜ 通用任务终态、内层Future与池退役

### 一、改了什么

源码行为变更提交d035b93，守卫增强fa70cb2、d5f417c。ThreadPoolManager分出PrioritizedTask（86行）、LifecycleWorkerPool（73行）、LifecycleCompletableTask（43行）、PoolRetirements（35行）；门面923物理行，RuntimeLoadPolicy947物理行，本轮生产文件均小于1000行，683个生产Java文件继续通过有效行守卫。新增注释解释中文原因。

- 六个物理池同时追踪接收的包装与标准完成服务newTaskFor创建的内层结果。排队、闸门中和运行中的Future，在停服、世界换代和池替换时进入终态；不把取消QueueingFuture误当成内层结果完成。
- 前置闸门接取消判据，暂停循环可以退出；角色上下文、优先级/FIFO、普通闸门故障告警后放行保留。停止、换代及取消的未开始任务不进入世界动作。
- 自有FutureTask关联对外CF，cancel(true)先抵达实际线程再发布回调；cancel(false)不打断已开始的动作。异常原因原样、null返回和手动complete语义保留。
- 六路惰性建池在锁内复核停止；已graceful关闭的池也升级强停。旧池先换引用，再由最外层finally锁外取消，避免同步回调看见旧池或占着管理锁。物理池在释放运行任务前关闭旧执行闸门，防worker抢先消费旧队列。
- 地图服务端两处、单机地图一处、道路终态一处、路口精修一处、道路吸附两处，共七处完成链迁到跟踪工厂。JDK AsynchronousCompletionTask缺少公开关联结果口，角色Executor明确拒收；能力经ThreadPoolManager/ComputeService工厂保留，不靠反射读JDK私有字段。
- 未改API模块协议、GSON配置键、枚举持久化或存档schema，未新增物理池/总调度器；FMM、DP、BAND、长桥弯和村庄裁切规则不改。

### 二、我验证了什么（附负控）

开工41701c9，git status空，pull --rebase已是最新；基线1274零跳过/中止/失败，Fabric通过。新增62项（61行为、1项七处接线语法树），TaskLifecycleTest已按FQCN登记。最初真实失败：排队4项、闸门4项、回调反序2项、完成服务内层1项、懒创建6项、graceful遗漏1项及重建回调1项；均是JUnit失败，不把编译失败计入。

最终PASS tests=1336 skipped=0 aborted=0 failed=0且BUILD SUCCESSFUL，Fabric成功；日志.analysis_tmp/round36-final-after-mutations.log。标准完成服务、invokeAny/invokeAll均通过真实角色池；非合作计算夹具证明结果取消与真实退出的区别，两份追踪表的成功/失败清理均断言为零。

28组生产变异（27行为、1静态）每次断言锚点次数，定向目标必须产生JUnit失败且零跳过/中止；finally git checkout --精确文件，再逐字核对HEAD，最终全量复验。脚本/记录为.analysis_tmp/round36_mutations.py、round36-mutations.json；日志round36-mut-*.log。第一轮锁内回调变异误绿，记录另存round36-mutations-initial.json；修正占位夹具后全部28组重跑，不把初始绿说成红。

| # | 改坏了什么 | 目标用例 | 结果 |
|---|---|---|---|
| 1 | 闸门拒绝时不取消真实Future | `taskInsideAdmissionGateCompletesWithoutWorldActionOnStop(int)` | 红 |
| 2 | 真实Future已取消但不结束对外CF | `queuedTaskCompletesWithoutExecutingWorldActionOnStop(int)` | 红 |
| 3 | 先发取消回调再中断计算 | `cancellationInterruptsCalculationBeforeInvokingCompletionCallbacks(int)` | 红 |
| 4 | 旧池取消回调直接在管理锁内执行 | `rebuildingPublishesTheNewPoolBeforeCompletingOldResultsOutsideTheMonitor` | 红 |
| 5 | 池退役队列从不排空 | `rebuildCancelsOldQueueAndFreshTaskCanStillRun(int)` | 红 |
| 6 | 角色优先级倒置 | `priorityFifoAndRoleContextSurviveTheExtractedWrapper` | 红 |
| 7 | 同优先级FIFO倒置 | `priorityFifoAndRoleContextSurviveTheExtractedWrapper` | 红 |
| 8 | 完成后不恢复角色上下文 | `admissionFaultStillRunsButCancellationAndLateStopDoNotRun` | 红 |
| 9 | 普通闸门异常改成丢任务 | `admissionFaultStillRunsButCancellationAndLateStopDoNotRun` | 红 |
| 10 | 故障放行绕过迟到停止复核 | `admissionFaultStillRunsButCancellationAndLateStopDoNotRun` | 红 |
| 11 | 暂停循环忽略单任务取消 | `cancelWithoutInterruptReleasesAdmissionWorker(int)` | 红 |
| 12 | 完成服务内层Future绕开登记 | `completionServiceKeepsItsInnerFutureTerminalWhenQueueIsStopped` | 红 |
| 13 | 停止不取消登记的内层结果 | `completionServiceKeepsItsInnerFutureTerminalWhenQueueIsStopped` | 红 |
| 14 | 世界换代只改代次不取消结果 | `completionServiceInnerFutureIsCancelledWhenWorldChanges(boolean)` | 红 |
| 15 | 六个懒创建入口移除锁内停止复核 | `stopWinsAgainstLazyCreationWaitingForTheManagerMonitor(java.lang.String)` | 红 |
| 16 | 跳过已经graceful关闭的池 | `stoppingAlsoCancelsAnAlreadyGracefullyShuttingDownPool` | 红 |
| 17 | SNAPSHOT漏接生命周期物理池 | `everyPhysicalPoolCancelsBothFutureTypesInItsQueue(java.lang.String)` | 红 |
| 18 | 直接接收不透明JDK完成任务 | `opaqueJdkRootSubmissionIsRejectedInsteadOfLeavingAnUntrackedResult(boolean)` | 红 |
| 19 | 手动complete后仍执行排队supplier | `completingQueuedResultManuallySkipsTheSupplierAndKeepsTheValue` | 红 |
| 20 | 对外异常额外包一层原包装 | `successfulValuesFailuresAndNullResultsKeepTheirOriginalMeaning` | 红 |
| 21 | 任务完成不清执行追踪表 | `priorityFifoAndRoleContextSurviveTheExtractedWrapper` | 红 |
| 22 | 真实Future完成不清结果追踪表 | `successfulValuesFailuresAndNullResultsKeepTheirOriginalMeaning` | 红 |
| 23 | 物理拒收不取消提交的Future | `physicalRejectionCancelsTheSubmittedFutureAndRemovesTracking` | 红 |
| 24 | cancel(false)也中断实际计算 | `cancellingWithoutInterruptKeepsAnAlreadyStartedCalculationRunning` | 红 |
| 25 | 停服方法重新持整个管理锁 | `stoppingCompletesCleanupChainOutsideTheManagerMonitor` | 红 |
| 26 | 执行体不登记导致在途结果遗漏 | `runningTaskIsCancelledEvenWhenCalculationHasNotExited(int)` | 红 |
| 27 | 路口精修改回JDK不透明工厂 | `allSevenProductionCompletionSitesUseTrackedFactories` | 红（静态） |
| 28 | 公开CF工厂绕开关联任务 | `successfulValuesFailuresAndNullResultsKeepTheirOriginalMeaning` | 红 |

### 三、我没能验证什么（最重要）

- 需真机/真实存档：最终Mixin与服务端事件、三个mod启动/停止/重开、FPS/MSPT、加载尾延迟、4096堆占用和吞吐。本轮没有部署jar，不给不卡顿或内存安全保证。
- 取消结果不会强停不响应中断的NoiseChunk/FMM或外部世界计算。运行中夹具在取消后仍等待释放，真实线程退出单独验证；世界写闸门及代次复核不能删除。
- CF取消回调仍可能同步阻塞调用方，锁外完成不等于任意阻塞回调都安全。外部自行嵌套Runnable/Future、完成服务单任务取消的全部组合未穷举；已验的完成服务范围是停服、换代，以及invokeAny/invokeAll结束。
- 角色Executor对不透明JDK CF任务的明确拒收是有意行为变更。内部七处已迁移并编译；第三方若直接拿运行时角色Executor给JDK异步工厂，须改用跟踪工厂。api模块公开协议未变，但不能把内部运行时类的行为写成完全无变化。
- 真正ServerLevel下七条业务链的世界内容没有在本轮Fake中重放；语法树守卫只验证生产调用接线。原几何产物回归仍全量执行，不能代替真机桥隧/保护A/B。
- 4096工作集尚未实现；crossing、双平滑最终裁决、跨度回写、QualityGate及旧区块自动重铺继续保持现状。

### 四、遗留半截的东西

通用任务生命周期这一项完成离线实现和守卫。源码/测试基线d5f417c，本节与架构、现状、验证和路线同步提交。源码push两次均无法连接github.com:443，约21秒后退出128，日志round36-code-push.log、round36-code-push-retry.log；当时origin/main仍为41701c9，本地领先3项提交。随后第三次推送成功，远端main从41701c9推进至670bd83（含源码、守卫和五份文档）；本条同步结果单独补记，停下前核对最终HEAD与origin/main一致及工作区干净。河流与长城本轮只读，测试放道路。

### 五、下一轮我会先做什么

转入4096分配前工作量/内存估算：先盘点采样网格、FMM数组与坐标乘法，提取长整型尺寸计算并做边界/溢出测试，不缩核心、不丢候选；然后对拍按需瓦片与稀疏工作区。READY增量和落盘优化继续依实测推进；现场启动及真实中断尾延迟并列待验收。

### 六、我踩到但已解决的坑

取消JDK内部包装不等于结束关联CF，取消QueueingFuture也不等于内层结果完成；两者必须分别建模。CF先回调再中断会让清理等计算互等。只检查锁外stopping挡不住等待管理锁的旧调用。池重建若先取消旧池再发布新引用，同步回调会重入旧池。初版持锁守卫夹具在中断后释放worker，worker可能抢先取消，使负控误绿；改用忽略中断且必须手动释放的占位任务后，该变异红。Future已取消不意味着实际线程退出，二者分别断言。

## 第37轮 ｜ 2026-11-17 ｜ 大范围布局、FMM状态压缩与不可达常量场

### 一、改了什么

三笔源码提交：af5cc72（行为变更：非法布局提前拒绝、边界算术修正及载荷报告），b2e6a0a（正常规划结果等价：压缩状态及目标集合），726105c（行为等价：不可达常量快照）。新GridLayout 73行、DenseGridWorkload 22行、GridFlags 22行、FmmTargets 76行；FastMarchingSolver 420行、ArrivalTimeField 192行、采样桥接200行、PipelinePlanner 321行，均为物理行。687个生产Java文件，有效代码超过1000行的仍为0；新增注释说明中文原因。

- 全局包围盒的加余量、采样尺寸和坐标乘积先用long验证。采样适配、函数网格、精确请求及FMM三个入口在读世界/面积分配前检查；跨零点的合法大偏移不能被中间int乘法误拒，快照半开边界不再回绕。
- `[PLANNING-WORKSET]`在采样前报告采样三图、保留高度、固定求解数组和目标存储的原始载荷，明确排除前沿、对象、缓存、其他任务。只诊断，不根据剩余堆临时缩小核心或删候选。
- 通行/冻结状态按64位字存一位；目标按单字段、稀疏整数哈希表或密集位图选型。公式、传播顺序、同权堆次序和回溯规则保留。全不可达快照保留布局与查询，免去整幅double数组。
- 修复独立FMM探针漏掉当前拓扑类型和fastutil编译依赖，增加WorkingSetOnly模式。首版JDK BitSet探针耗时偏高，改成固定字数组后重新全量对拍和测量；没有凭分配下降宣称FPS提升。
- bridge到metric新增两处既有方向的值对象引用，import基线10→12，提交说明原因；未引入逆向层依赖。只读核对河流82cc818、长城5ade8e9b，二者工作区干净，未把道路守卫放到外部模组。顺带修正架构和验证文档中已被河流结果门推翻的旧结论。
- 未改存档schema、配置键、枚举名、长桥弯、村庄裁切、FMM/DP/BAND选线策略或有意废弃的GPU精确采样。

### 二、我验证了什么（附负控）

开工8e101c1，status空并执行pull --rebase；基线1336零跳过/中止/失败、Fabric通过（round37-baseline.log）。新增47项：GridLayoutTest 15项、FmmWorkingSetTest 32项，显式登记总套件。分步1351、1379、1383全绿；源码最终726105c，23组变异后再次全量回归与Fabric编译，收尾日志.analysis_tmp/round37-final-after-mutations.log。

DenseFmmReference与git show af5cc72的原布尔数组实现做类型重命名后逐字相等。正常夹具包含8种地形/步长/软硬障碍、三个交替源、全场/单/多目标；比较全部临时及最终double位、回溯点序，再用独立权重/分量标记核对选树。空目标仍是原来的全场求解；重复目标按吸附格去重，源越界/硬禁/吸附禁行保持不可达。当前线程分配预算实际量到生产返回，不用“非负”断言代替收益守卫。

23组均是目标JUnit失败，编译/命令失败不算红；每项finally精确git checkout单文件并比较原字节。最终脚本和结果为.analysis_tmp/round37_mutations.py、round37-mutations.json、round37-mutation-run-final.log，各项日志round37-mut-*.log。第一轮19组先全红，补上不可达优化后23组全部重跑、全红、恢复。

| # | 故意破坏 | 指定守卫（metric包） | JUnit失败数 |
|---|---|---|---|
| 1 | `cell-product-overflows`，GridLayout.java | `GridLayoutTest#cellCountCannotWrapIntoAnAcceptedEmptyOrTinyHeightArray` | 1，红 |
| 2 | `grid-coordinate-not-checked`，GridLayout.java | `GridLayoutTest#rejectsWorldCoordinateOverflowBeforeSamplingOrAllocating` | 1，红 |
| 3 | `request-coordinate-not-checked`，AccurateHeightGridRequest.java | `GridLayoutTest#rejectsWorldCoordinateOverflowBeforeSamplingOrAllocating` | 1，红 |
| 4 | `request-offset-int-overflow`，AccurateHeightGridRequest.java | `GridLayoutTest#negativeOriginAllowsLargeIntermediateProductsWhenFinalCoordinatesAreValid` | 1，红 |
| 5 | `sampling-validates-after-world-read`，SampledTerrainField.java | `GridLayoutTest#cellCountCannotWrapIntoAnAcceptedEmptyOrTinyHeightArray` | 1，红 |
| 6 | `factory-allocates-int-product`，GridTerrainField.java | `GridLayoutTest#cellCountCannotWrapIntoAnAcceptedEmptyOrTinyHeightArray` | 1，红 |
| 7 | `solver-queries-before-validation`，FastMarchingSolver.java | `GridLayoutTest#allSolverEntrypointsRejectOversizedExternalFieldsBeforeQueryingTheMetric(int)` | 1，红 |
| 8 | `snapshot-boundary-wraps`，ArrivalTimeField.java | `GridLayoutTest#smallExtremeCoordinateFieldsStillProduceReachableSnapshots` | 1，红 |
| 9 | `sampling-estimate-truncated`，DenseGridWorkload.java | `GridLayoutTest#radius4096EstimateUsesLongBeforeMultiplicationWithoutAllocatingTheGrid` | 1，红 |
| 10 | `target-budget-still-full-grid`，DenseGridWorkload.java | `GridLayoutTest#radius4096EstimateUsesLongBeforeMultiplicationWithoutAllocatingTheGrid` | 1，红 |
| 11 | `early-stop-disabled`，FastMarchingSolver.java | `FmmWorkingSetTest#duplicateSourceAndSingleCellTargetsStopAtTheOriginalFrozenEvent` | 1，红 |
| 12 | `duplicates-counted-more-than-once`，FmmTargets.java | `FmmWorkingSetTest#duplicateSourceAndSingleCellTargetsStopAtTheOriginalFrozenEvent` | 1，红 |
| 13 | `sparse-zero-sentinel-lost`，FmmTargets.java | `FmmWorkingSetTest#hashCollisionsDuplicatesMissingCellsAndWordBoundariesMatchAnIndependentSet` | 1，红 |
| 14 | `sparse-query-skips-collision-chain`，FmmTargets.java | `FmmWorkingSetTest#hashCollisionsDuplicatesMissingCellsAndWordBoundariesMatchAnIndependentSet` | 1，红 |
| 15 | `dense-target-word-alias`，FmmTargets.java | `FmmWorkingSetTest#hashCollisionsDuplicatesMissingCellsAndWordBoundariesMatchAnIndependentSet` | 1，红 |
| 16 | `word-boundaries-alias`，GridFlags.java | `FmmWorkingSetTest#fixedGridFlagsMatchIndependentBooleanStatesAcrossWordBoundaries(int)` | 3，红 |
| 17 | `flags-still-byte-sized-allocation`，GridFlags.java | `FmmWorkingSetTest#hotNearTargetAllocatesArrivalValuesAndPackedFlagsWithoutAFullTargetMask` | 1，红 |
| 18 | `hard-grid-cache-all-passable`，FastMarchingSolver.java | `FmmWorkingSetTest#outOfBoundsAndHardBlockedSourcesKeepTheUnreachableContract(int)` | 1，红 |
| 19 | `freezes-an-extra-cell`，FastMarchingSolver.java | `FmmWorkingSetTest#compressedStateMatchesTheFrozenDenseSolverIncludingTemporaryValuesAndPaths(int)` | 8，红 |
| 20 | `unreachable-still-allocates-dense-array`，ArrivalTimeField.java | `FmmWorkingSetTest#unreachableSnapshotsKeepTheirGeometryWithoutAllocatingArrivalArrays(int)` | 4，红 |
| 21 | `unreachable-at-no-constant-guard`，ArrivalTimeField.java | `FmmWorkingSetTest#unreachableSnapshotsKeepTheirGeometryWithoutAllocatingArrivalArrays(int)` | 4，红 |
| 22 | `unreachable-path-no-constant-guard`，ArrivalTimeField.java | `FmmWorkingSetTest#unreachableSnapshotsKeepTheirGeometryWithoutAllocatingArrivalArrays(int)` | 4，红 |
| 23 | `unreachable-layout-no-validation`，ArrivalTimeField.java | `GridLayoutTest#allSolverEntrypointsRejectOversizedExternalFieldsBeforeQueryingTheMetric(int)` | 3，红 |

固定512MiB JVM、1050625格、30目标的探针，全部场值和1025个回溯点相等；8对热身后11对交替测量。数据来自round37-workset-final-probe.log：

| 场景 | 每次线程分配 p50（B） | 耗时 p50（ms） | 耗时 p95（ms） |
|---|---|---|---|
| workset-hot-near-single | 10506448 → 8536592 | 1.168 → 1.142 | 1.595 → 2.314 |
| workset-hot-far-multi | 10506448 → 8536816 | 107.255 → 111.500 | 110.093 → 125.237 |
| workset-cold-far-multi | 28441248 → 25552336 | 127.718 → 130.352 | 130.994 → 140.906 |

探针命令和解释见VALIDATION。原DirectionOnly模式实际编译运行成功（round37-direction-probe.log）。这些是合成地形线程分配及核内耗时，不是服务器堆峰值、全流程耗时或帧率。

### 三、我没能验证什么

- 4096真实世界、真实存档、进档等待、MSPT/FPS、停服/跨世界的真实中断尾延迟和三模组最终Mixin时序未验证，未构建或部署新jar。
- 数组估算不含对象头、前沿堆、采样缓存/缺列列表/Map和其他任务。AccurateColumnBatch仍有整场missingIndexes、missingKeys以及后续缺列产物；采样仍返回三张高度图，FMM仍有三张全场double数组。131072×131072格按step=8的三图原始载荷即3GiB；压缩状态后仍不能把4096当作安全范围。
- 百万格固定堆远目标耗时略升：热p50 107.255→111.500 ms、冷127.718→130.352 ms；分配减少不等于吞吐提高。首版BitSet与最终固定字数组都留下独立日志，未只挑更好结果。线程计数器只量当前线程分配，不量存活峰值或全部采样线程。
- 不可达常量场的合法范围、无穷查询及空路径已验；有道路的到达快照仍保留独立数组，尚未分页。越界布局提前拒绝是有意行为变更；PipelinePlanner既有失败回退未因本轮新增，不将其算作大范围解决方案。
- crossing解耦缺用户真机召回数据，继续保留；双平滑最终裁决、跨度回写默认、QualityGate阈值和旧区块重铺仍未动。

### 四、遗留半截的东西

本轮分配前估算与状态压缩完成离线实现和守卫；全局包围盒及整场高度/双轴代价/到达数组没有解决。路线第8项仅推进一阶段，不能写成4096工作集已完成。源码分三次小步提交，文档同步更新。源码首次push无法连接github.com:443，约21秒后退出128（round37-code-push.log）；当时本地领先origin/main三项。恢复后最终回归PASS tests=1383 skipped=0 aborted=0 failed=0、BUILD SUCCESSFUL（round37-final-after-mutations.log）。文档提交c7a0cc8后重试push成功，远端main从8e101c1推进到c7a0cc8（round37-final-push.log）；随后pull --rebase显示Already up to date，本地HEAD与origin/main均为c7a0cc8，status空。本条同步结果单独提交后再次推送并核对。

### 五、下一轮我会先做什么

1. 同一GridLayout上建立按需瓦片读口，保留原采样原点、步长、三通道及跨瓦片梯度邻域。先用Fake与原整场采样逐格对拍，不能把缓存未命中当零高度或改变核心。
2. 再让FMM代价按访问格准备，并把到达值/冻结状态改为按需页；否则全场预计算仍会把瓦片全部拉满。保留快照寿命、全局绕行和跨分区连接，逐位/逐路径/逐候选权重/逐最终边对拍后才接生产。
3. 用固定小堆覆盖稀疏到达、密集访问、封闭障碍和远端连接；继续记录分配与耗时的取舍，再做真实4096存档压力验收。READY增量/落盘等待计时证据，三模组现场次序并列待验。

### 六、我踩到但已解决的坑

Math.multiplyExact(int,int)仍会误拒“中间偏移超int、加负原点后合法”的坐标，必须整条算式升long。只在数组构造时校验挡不住此前世界查询，测试同时让硬障碍回调在前置校验前调用时报错。BitSet的可变有效长度与每次查询检查不是固定网格所需，替换为小型固定字数组后重新测量；状态压缩依然有算术开销，耗时未据分配强行推断。不可达也分配全场数组会在错误返回路径制造大内存峰值，常量快照必须同时守住at和pathTo，不能只把数组设null。

## 第38轮 ｜ 2026-11-17 ｜ FMM大域惰性代价与分页工作区 ｜ 源码 `ad22c93` ｜ `PASS tests=1414 skipped=0 aborted=0 failed=0`

### 一、改了什么

源码三笔提交：833c09b（测试行为等价，粗路径夹具显式安排预算等待线程），90992e6（行为等价，大网格分页代价和传播工作区），ad22c93（行为等价，按密集实测调整公共存储阈值并保留强制分页探针）。同一FMM公式、队列同权顺序、吸附、候选和全局域保留，没有新增配置、持久化键、旧算法回退或缩小核心。

| 文件 | 改动摘要 | 类型 |
|---|---|---|
| metric/FmmPages.java，39物理/30有效行 | 1024格页、两级目录，缺页读取不分配；顶层每项覆盖262144格，尾页只分配实际长度 | 行为等价 |
| metric/FmmValues.java，75/60行（伴随FmmWorkspace） | 到达页缺省无穷；冻结位只属于当次工作区，快照仅接管独占到达值 | 行为等价 |
| metric/FmmCostCache.java，150/129行 | 原稠密预计算迁入；分页按遇到的格准备通行/轴代价，规范东/南方向只查一次无向边，成功后才标已知 | 行为等价 |
| metric/FastMarchingSolver.java，375/209行 | 同一传播循环接两种存储；小于4194304格用顺序数组，大网格按需页，多源复用同布局缓存 | 行为等价 |
| ArrivalTimeField.java，203/108行 | 只读API读取独占到达存储；下一源不清空旧页，常量不可达保留 | 行为等价 |
| DenseGridWorkload / PipelinePlanner | 日志区分solverDenseReferenceBytes与solverPagedFullVisitBytes，明确排除目录等成本 | 诊断口径变更，规划产物等价 |
| FmmPagedWorkspaceTest / 总套件 / 原状态与布局测试 | 新31项显式登记，原one-bit实际存储守卫跟随搬迁；新增全访问分页载荷断言 | 测试基建 |
| Measure-Fmm.ps1 / FmmProbe / FmmPagingProbe | 固定小堆、强制分页独立对照，旧入口保留 | 测试基建 |

生产Java文件690，有效代码超过1000仍为0、超过800为2，公共模块文件职责头100%。均加中文原因注释；不修改桥弯、村庄包围盒、crossing、双平滑、存档schema或GPU弃用决策。只写道路仓库，未修改河流/长城。

### 二、我验证了什么（附负控）

开工HEAD03a77af且status空；首次pull连接重置失败、第二次443连接失败，基线1383全绿；源码分步提交后再次pull --rebase成功，Current branch main is up to date。原1383项首先保持通过；新增后1410、1413、最终1414全绿，Fabric通过。24组变异恢复后再次完整回归（round38-final-after-mutations.log）。

新增31项涵盖：10组地形/步长/软硬障碍/规范边/单行单列，三源交替与全/单/多/空/重复目标；全部最终及早停临时值double位、跨页回溯；百万格公共与强制分页两模式；两级目录/位字/最大int尾页；缺页纯读不分配；真实公共阈值的分配和远端查询；2.68亿格近目标、全域16383格走廊、封闭分量；实际测地矩阵、有限候选、独立Kruskal最终边与分量；失败重试与多源代价/边只准备一次。比较集合前有非空/可达断言。

完整回归中CoarsePathTasksTest曾因固定threads.get(1)失败（round38-before-mutations.log）。确认创建顺序不决定许可持有方后，用锁存器让另一个线程确定先拿许可，再释放已占线程成为预算等待方；没有延时放宽或只重跑掩盖。复原旧索引现能确定性失败，绕过实际许可也失败。

23组生产变异+1组夹具变异都出现指定JUnit失败，命令/编译错误不算红。每次finally精确git checkout单文件并比较原字节；结束status空。脚本.analysis_tmp/round38_mutations.py，结果round38-mutations.json，日志round38-mutation-run-final.log及round38-mut-*.log。首次脚本目标包名重复metric，无JUnit结果，已拒绝计数并恢复；修正后全部24组重跑。

| # | 故意破坏 | 文件（common下） | 指定守卫 | JUnit失败数 |
|---|---|---|---|---|
| 1 | `public-threshold-keeps-eager-costs` | `metric/FastMarchingSolver.java` | `metric.FmmPagedWorkspaceTest#publicThresholdAvoidsEagerCostsArrivalArraysAndDistantTerrainQueries` | 1，红 |
| 2 | `arrival-still-dense-when-paged` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#publicThresholdAvoidsEagerCostsArrivalArraysAndDistantTerrainQueries` | 1，红 |
| 3 | `missing-page-free-source` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 4 | `new-page-not-filled-with-infinity` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 5 | `directory-groups-alias` | `metric/FmmPages.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 6 | `pages-in-group-alias` | `metric/FmmPages.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 7 | `frozen-words-alias` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 8 | `frozen-write-no-op` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 9 | `hard-cells-all-passable` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#pagedPropagationMatchesDenseBitsAndPathsIncludingEarlyStopAndSourceReuse(int)` | 6，红 |
| 10 | `costs-not-retained` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#cachedCellCostsAndUndirectedEdgesArePreparedOnceAcrossSourceChanges` | 1，红 |
| 11 | `cache-rebuilt-for-every-source` | `metric/FastMarchingSolver.java` | `metric.FmmPagedWorkspaceTest#cachedCellCostsAndUndirectedEdgesArePreparedOnceAcrossSourceChanges` | 1，红 |
| 12 | `undirected-edge-read-backwards` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#cachedCellCostsAndUndirectedEdgesArePreparedOnceAcrossSourceChanges` | 1，红 |
| 13 | `edge-mask-disabled` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#pagedPropagationMatchesDenseBitsAndPathsIncludingEarlyStopAndSourceReuse(int)` | 4，红 |
| 14 | `cost-failure-marked-ready` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#callbackFailuresRemainFailuresAndRetryDoesNotCacheThemAsSuccess(int)` | 1，红 |
| 15 | `passability-failure-marked-known` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#callbackFailuresRemainFailuresAndRetryDoesNotCacheThemAsSuccess(int)` | 1，红 |
| 16 | `edge-failure-marked-known` | `metric/FmmCostCache.java` | `metric.FmmPagedWorkspaceTest#callbackFailuresRemainFailuresAndRetryDoesNotCacheThemAsSuccess(int)` | 1，红 |
| 17 | `target-early-stop-disabled` | `metric/FastMarchingSolver.java` | `metric.FmmPagedWorkspaceTest#sourceTargetsAndDuplicatesStopBeforePreparingNeighborPages` | 1，红 |
| 18 | `snapshot-discards-reached-pages` | `metric/ArrivalTimeField.java` | `metric.FmmPagedWorkspaceTest#pagedPropagationMatchesDenseBitsAndPathsIncludingEarlyStopAndSourceReuse(int)` | 10，红 |
| 19 | `arrival-offset-aliases-zero` | `metric/FmmValues.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |
| 20 | `paged-payload-omits-cell-state` | `metric/DenseGridWorkload.java` | `metric.GridLayoutTest#radius4096EstimateUsesLongBeforeMultiplicationWithoutAllocatingTheGrid` | 1，红 |
| 21 | `coarse-path-bypasses-permit` | `planning/terrain/CoarsePathTasks.java` | `planning.terrain.CoarsePathTasksTest#queuedPathsRespectOneSharedWorkerAndAllResultsSurvive` | 1，红 |
| 22 | `fixture-restores-creation-order-assumption` | `planning/terrain/CoarsePathTasksTest.java` | `planning.terrain.CoarsePathTasksTest#queuedPathsRespectOneSharedWorkerAndAllResultsSurvive` | 1，红 |
| 23 | `propagation-clips-the-global-domain` | `metric/FastMarchingSolver.java` | `metric.FmmPagedWorkspaceTest#aFarEndCorridorStillCrossesTheWholeGlobalDomain` | 1，红 |
| 24 | `last-page-loses-final-cell` | `metric/FmmPages.java` | `metric.FmmPagedWorkspaceTest#pagesAndFrozenBitsCannotAliasAcrossWordsPagesDirectoriesOrTheLastIntIndex` | 1，红 |

固定堆对照、命令与数据见VALIDATION第38轮。最终公共百万格远目标116.705→117.894 ms、冷136.845→138.991 ms；强制分页分别128.431→222.893、147.983→247.157 ms，明确记录密集CPU代价，没有只选近目标收益。固定64MiB解析大域近目标线程分配p50=171752 B；全域走廊、封闭分量、百万格强制分页逐格/逐路径通过。没有真实世界采样，也没有宣称加载/FPS提升。

### 三、我没能验证什么

- **需要真机的**：4096完整开局、加载耗时、FPS/MSPT、实际搜索访问密度、跨模组最终Mixin启动次序和停止/重开尾延迟均未测，未打包或部署jar。
- **需要真实存档的**：高度缓存命中/驱逐、缺列工作区峰值、文件锁/磁盘尾延迟、旧存档重开及大范围全部访问峰值。64MiB夹具是解析高度和稀疏连通，不含真实三通道采样器。
- **我怀疑但没有证据的**：后续瓦片缓存合适上限、驱逐重采样比例和对玩家帧时的净收益尚无分布数据，不将其当成已确认缺陷或收益。
- **我知道尚未修的**：SampledTerrainField.sampleSurface仍请求全场；AccurateColumnBatch仍按sampleCount建missingIndexes、missingKeys及后续缺列产物，三图在131072格边长/step8时仅原始载荷3GiB。4096不能写成安全。大范围密集访问会拉满分页，单纯数组载荷6744440832 B高于稠密参考6509559808 B，目录/对象另计。
- 多源代价页和前沿数组保留到求解器结束，热近目标44080 B不是总占用；没有跨规划逐出或新FMM协作中断协议，取消尾延迟沿用现有边界，不能强停不合作计算。
- crossing解耦仍缺用户召回数据；双平滑最终裁决、跨度回写默认、QualityGate标定及旧区块重铺不动。

### 四、遗留半截的东西

| 文件 | 什么状态 | 怎么补 |
|---|---|---|
| bridge/SampledTerrainField、planning/impl/PipelinePlanner | FMM已不必扫描全域，真实采样仍先整场 | 接同布局三通道瓦片，保留梯度邻域与影子水代理后接生产 |
| pathfinding/sampling/AccurateColumnBatch | 单请求临时内存仍随请求面积增长 | 瓦片入口控制单请求面积，保留缓存/磁盘/认领/版本/预算；对拍冷、部分命中与失败 |
| metric/FmmCostCache / FmmValues | 大域按需页已接，密集访问有额外CPU成本 | 用真实访问分布决定页布局或密度迁移，不凭分配减少宣称吞吐提升 |


同步结果：代码及五份文档已推送至3f97eab，远端main从03a77af推进，日志.analysis_tmp/round38-final-push.log。推送后status空，HEAD与origin/main均为3f97eab；最终回归于2026-11-17 01:54:09结束，PASS tests=1414 skipped=0 aborted=0 failed=0、BUILD SUCCESSFUL。河流82cc818及长城5ade8e9b另作只读状态复核，两仓均干净，未改源码。本条补记的首次push及三次重试分别连接失败、低速超时、连接失败、连接重置（round38-sync-note-push.log及retry/retry2/retry3.log），均未成功；代码和完整验收文档已在远端3f97eab。结束时仅本条同步补记有1笔本地提交尚未推送，工作区干净；下一轮网络恢复后先pull --rebase并push这笔补记。

### 五、下一轮我会先做什么

1. bridge层建立可注入三通道瓦片读口，复用GridLayout的原点、步长及点数；跨瓦片梯度取真正邻点，边缘仍单侧差分，未知/失败不填零。先与整场GridTerrainField逐高度、梯度和水代理对拍。
2. 接SampledTerrainField与PipelinePlanner生产入口，小范围保留整场批次，较大域按需请求有限瓦片；同一FMM、路径、双向权重及最终边端到端对拍，保留跨瓦片全局绕行和影子模式。沿用CPU预算、关闭/取消及在途认领，禁止生命周期旁路。
3. 固定堆覆盖近/远/封闭/密集、驱逐重算和失败重试，再做真实4096存档压力。READY计数、落盘计时与三模组最终次序依路线等待证据，不无测量异步化。

### 六、我踩到但已解决的坑

分页缺省零会制造免费源点，缺页读和新页都必须为无穷；冻结位同时守住字、页和目录边界。边缓存必须保持旧预计算西/北→东/南规范方向，单列网格delta=1仍是南边。已知位在回调成功后发布，防止一次故障被永久缓存成正常值。源之间共享代价、不共享到达工作区，防后续源污染旧快照。小堆解析夹具不能代表真实采样器，密集分页退化也不能藏在近目标收益后，因此保留两种独立对照并调整公共切换点。


## 第39轮 ｜ 2026-11-17 09:01 → 09:45 ｜ 大域三通道瓦片与梯度复用 ｜ 源码 `a1e21de` ｜ `PASS tests=1442 skipped=0 aborted=0 failed=0`

### 一、改了什么

分步提交：fc24f23（性能优化、几何行为等价，有界三通道瓦片和真实规划接线），6e20afa（测试基建、行为等价，真实采样门面交错），e109b9d（测试基建、行为等价，读口只注入世界读取与固定堆探针），a1e21de（性能优化、行为等价，同块梯度一次取图及同JVM冻结版本对照）。不新增配置或持久化字段，不改桥弯、包围盒、crossing、双平滑、QualityGate或GPU弃用决定；既有失败日志和MST降级分支原样保留，本轮未新增算法回退。

| 文件 | 改动与边界 | 类型 |
|---|---|---|
| bridge/TiledTerrainField.java，145物理/117有效行 | 全局布局上64×64列三通道瓦片、64块LRU；跨块全域差分，同块一次取图；失败不发布；实际请求/列数/驱逐/载荷统计 | 性能，几何行为等价 |
| bridge/SampledTerrainField.java，221/113行 | 新planningSurface与FMM共享4194304格分界；小域整场接口保留；水代理兼容并修正合法跨int偏移的long运算 | 行为等价与边界正确性修复 |
| metric/FastMarchingSolver.java，380/212行 | 暴露只选存储的规模判据，求解循环与全局范围不变 | 行为等价 |
| planning/impl/PipelinePlanner.java，337/219行 | 只注入GridSampler工厂；布局/策略仍由真实plan决定，生产经原精确采样门面；稠密参考和实际瓦片日志分开 | 性能、诊断口径变更，正常几何等价 |
| 四个新测试类与总套件 | 15+8+2+3=28项，逐格/路径/权重/最终边与真实门面行为守卫，显式FQCN登记 | 测试基建 |
| scripts/benchmarks/Measure-TerrainTiles.ps1 / TiledTerrainProbe.java | 64MiB大域夹具；512MiB同JVM整场、首版瓦片、当前瓦片三方对照；首版只重命名类型 | 测试基建 |

独立统计691个生产Java文件，超过1000有效行仍0、超过800仍2；新增类未成为大类。所有新增/改动逻辑配中文原因注释。bridge→metric 12→15、bridge→pathfinding 11→13、planning→bridge 1→2均为原允许方向，并在依赖次数基线逐项说明；没有新增反向白名单。只写道路仓库，河流82cc818和长城5ade8e9b状态空，未改、未重跑外部回归。

### 二、我验证了什么（附负控）

开工status空、pull --rebase成功，HEAD c8e7481；基线1414全绿。先push上轮未同步的c8e7481补记成功。新增后1438、1441、最终1442全绿，Fabric编译通过；最终26组生产变异全部恢复后再次完整回归。新测试直接调用真实plan，只有世界读Fake，不能由测试替生产选择瓦片；合法近边输出非空且原坐标/状态保留，远孤立点不触发整场请求。

高度/梯度/水代理对GridTerrainField逐点比较；场值、早停临时值、三源交替和路径对冻结DenseFmmReference；实际双向GeodesicMatrix和独立Kruskal森林/分量对拍。真实门面验本地驱逐后仍复用原列缓存，两规划对象真实进入AccurateTileClaims等待后后端仅算一次，保存期间关闭不发布可用瓦片。并发夹具等待实际调用栈，不以线程创建次序或立即放行侥幸通过。

首版23组变异全红；同块快路径加入后最终26组完整重跑均红，编译/命令/锚点失败不计。扩展脚本一度因尾块锚点出现两处而中止，finally已恢复；改为完整请求上下文并增加全组预检后从第一项重跑。每项finally精确git checkout单文件并逐字比对。记录：.analysis_tmp/round39_mutations.py、round39-mutations.json、round39-mutation-run-final.log和round39-mut-*.log。

| # | 故意破坏 | 文件（生产包下） | 指定守卫 | JUnit失败数 |
|---|---|---|---|---|
| 1 | `factory-still-samples-whole-domain` | `bridge/SampledTerrainField.java` | `bridge.TiledTerrainFieldTest#planningFactoryKeepsSmallBatchAndSelectsBoundedLazyTilesAtTheSolverThreshold` | 1，红 |
| 2 | `tiled-shadow-water-discarded` | `bridge/SampledTerrainField.java` | `metric.TiledTerrainFmmTest#evictionStillAllowsFullDomainTravelAndOldSnapshotSurvivesReverseSource` | 1，红 |
| 3 | `walking-height-uses-canopy` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 6，红 |
| 4 | `tile-key-loses-z` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 4，红 |
| 5 | `tile-origin-loses-step` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 2，红 |
| 6 | `tail-tile-keeps-full-width` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 6，红 |
| 7 | `gradient-clamped-at-tile-seam` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 4，红 |
| 8 | `gradient-denominator-loses-step` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 2，红 |
| 9 | `height-snaps-down-instead-of-nearest` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 2，红 |
| 10 | `tile-cache-unbounded` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#leastRecentlyUsedTilesAreEvictedAndRereadWithoutChangingHeight` | 1，红 |
| 11 | `sampling-failure-replaced-with-zero-height` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#failedNullAndMismatchedTilesAreNotPublishedAndCanBeRetried(int)` | 2，红 |
| 12 | `mismatched-tile-not-rejected` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#failedNullAndMismatchedTilesAreNotPublishedAndCanBeRetried(int)` | 1，红 |
| 13 | `mismatched-tile-published-before-validation` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#failedNullAndMismatchedTilesAreNotPublishedAndCanBeRetried(int)` | 1，红 |
| 14 | `concurrent-misses-not-serialized` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#sharedTileIsPublishedOnceToConcurrentReaders` | 1，红 |
| 15 | `water-proxy-uses-walking-height` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 6，红 |
| 16 | `water-proxy-accepts-nonaligned-columns` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 3，红 |
| 17 | `real-tile-stats-return-zero` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#leastRecentlyUsedTilesAreEvictedAndRereadWithoutChangingHeight` | 1，红 |
| 18 | `evicted-payload-never-subtracted` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#leastRecentlyUsedTilesAreEvictedAndRereadWithoutChangingHeight` | 1，红 |
| 19 | `tile-request-loses-step` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 3，红 |
| 20 | `small-factory-uses-wrong-height-channel` | `bridge/SampledTerrainField.java` | `bridge.TiledTerrainFieldTest#planningFactoryKeepsSmallBatchAndSelectsBoundedLazyTilesAtTheSolverThreshold` | 1，红 |
| 21 | `production-plan-does-not-use-world-port` | `planning/impl/PipelinePlanner.java` | `planning.impl.PipelinePlannerSamplingTest#planUsesInjectedSamplingBoundaryAndPreservesActualConnections(int)` | 2，红 |
| 22 | `propagation-clips-global-domain` | `metric/FastMarchingSolver.java` | `metric.TiledTerrainFmmTest#evictionStillAllowsFullDomainTravelAndOldSnapshotSurvivesReverseSource` | 1，红 |
| 23 | `sampling-and-solver-threshold-changed-to-eager` | `metric/FastMarchingSolver.java` | `planning.impl.PipelinePlannerSamplingTest#planUsesInjectedSamplingBoundaryAndPreservesActualConnections(int)` | 1，红 |
| 24 | `same-tile-gradient-uses-canopy` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 6，红 |
| 25 | `same-tile-z-gradient-loses-row-stride` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#heightsGradientsAndWaterMatchWholeGridAcrossSeamsAndEviction(int)` | 2，红 |
| 26 | `single-cell-gradient-forces-an-extra-sample` | `bridge/TiledTerrainField.java` | `bridge.TiledTerrainFieldTest#singleCellGradientNeedsNoSamplingButHeightAndWaterStillUseRealChannels` | 1，红 |

固定64MiB：2.68亿格近目标仅1请求/4096列，线程分配p50=224024 B、p50=0.235 ms、p95=0.771 ms；全域走廊16384点、256请求、192次驱逐，最终三图3 MiB；封闭分量只采一块。512MiB同JVM三方（整场/首版e109b9d/当前）：4194304格近目标分配50491656/211944/211928 B、p50 5.610/0.093/0.062 ms；百万格强制分页密集p50 275.554/321.429/295.813 ms，分配72821752/72856792/72856776 B。同块梯度降低首版开销，仍不如整场数组；故小域快路径保留。3轮热身、7轮旋转测量，近目标小堆另8次热身11次测量。均为假地形读口，不含实际世界/文件IO，不能作FPS、加载时间或整合包总堆结论。完整命令与数据见VALIDATION第39轮。

### 三、我没能验证什么

- **需要真机的**：原版NoiseChunk/模组生成器同列高度是否完全一致，后台预算等待与实际中断尾延迟，三模组最终Mixin顺序、停服/重开、FPS/MSPT和桥头实际落块。现有世界读取路径没改不等于现场验收已通过。
- **需要真实存档的**：4096候选密度、FMM全域密集访问、采样器两级缓存/文件瓦片/临时集合及其他模组的共同峰值；驱逐后磁盘读取、重算与首次生成/重开的尾延迟。
- **我怀疑但没有证据的**：真实地形上64块是否合适、密集场驱逐是否重复触发NoiseChunk或IO、全部结构扫描是否仍主导等待。已有实际请求/列数/驱逐日志，未据猜测继续调容量或新增空间裁剪。
- **我知道现在有代价但没消除的**：分页全访问仍受全域工作量限制，FMM页和前沿未设置总硬预算；新瓦片密集夹具仍比整场数组慢。3 MiB只约束本适配器保留的三图载荷，并非整个规划或游戏内存上限。既有QualityGate不拦、不可用世界/异常时MST降级口径及按原道路身份跳过旧完工区块保持。

### 四、遗留半截的东西

没有未提交实现或临时破坏，代码/测试已小步提交；未打包、未部署。最终回归日志round39-final-after-mutations.log，固定堆和三方数据为round39-terrain-smallheap-final.log、round39-terrain-three-way.log。源码及本轮文档按正常main推送，停下前核对工作区为空且与origin/main一致；若网络未同步须在答复单独列明。

### 五、下一轮我会先做什么

1. 可独立推进READY：集中RoadPlanningReadinessIndex状态转换，对拍现有全量扫描、失败重试、代次与停服行为，再决定增量计数；先不无计时证据异步落盘。
2. 等现场数据核对[PLANNING-WORKSET]、[PLANNING-HEIGHT-TILES]和采样/拓扑/单路/区块施工阶段p50/p95，逐档存档副本验证128到4096。稠密传播/采样器缓存仍大时先定位实际持有者，不裁核心或合法绕行域。
3. 保持三模组启动的测试归属；真实Mixin次序和重开由各发布/消费工程及整合场景取证。crossing、双平滑默认切换和QualityGate继续等用户尚无的真机数据。

### 六、我踩到但已解决的坑

瓦片梯度若在接缝按本块边界裁切会改变坡度，必须始终按全域邻点；单点零梯度快路径不能为了取中心块额外采样。首轮FMM源格不准备自身代价，反向把它当目标时需补读原源块：全域夹具恰好多1块，不能错误要求零新增。初测密集退化后改同块梯度，并在同JVM冻结e109b9d首版，避免跨进程噪声被当优化收益。请求锚点因快路径复用出现两处时让脚本明确失败、恢复后重定位，禁止静默替换或把脚本失败算守卫红。


---

## 第40轮 ｜ 2026-11-17 11:03 → 11:25 ｜ READY即时计数与同tick准入 ｜ 源码 `cc0c569`、探针 `d69b5f3` ｜ `PASS tests=1459 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 性质 |
|---|---|---|
| planning/ReadinessRegionTable.java，91物理/73有效行 | 集中状态与派发标志的写入，条目身份防迟到回调；四项计数与转换同锁更新，读取不扫区域 | 先行为等价搬迁89666f6，再增量实现cc0c569 |
| planning/RoadPlanningReadinessIndex.java，756/579行（原865/652） | 重试、暂停、开始、失败和完成接同一运行态表；进度/供给共用快照，准入立即看唯一QUEUED+PLANNING | 同tick饱和与重复队列计数修复属行为变更；反射签名、状态名称和文件格式未变 |
| 两个新测试类与RoadJUnitRegressionSuite | 表模型12项、实际门面状态5项，逐FQCN登记；真实世界读写仍未伪造成离线世界 | 测试基建 |
| scripts/benchmarks/Measure-Readiness.ps1、ReadinessStateProbe.java | 冻结89666f6全量表，同JVM精确对拍并分别测500次读取、1500次转换 | 测试基建d69b5f3 |

692个生产Java文件，超过1000有效行0、超过800行2；独立词法计数与架构守卫均通过。新增逻辑写中文原因注释，未新增层依赖方向、线程、配置或schema；道路几何、crossing、长桥正弦、村庄包围盒不变。只写道路仓库，本轮未读写或重跑相邻模组。

### 二、我验证了什么（附负控）

开工status空、pull --rebase已是最新，HEAD24bb4c0；基线1442全绿。先以全量统计集中转换，1453全绿并单独提交/推送89666f6。随后新用例在该版本直接调用真实backlogSaturated：同一tick先查询空队列、再加入512项，实得false，TARGET_RESULT passed=0 failed=1 skipped=0 aborted=0。修复后1459全绿、Fabric通过。源码与探针三个小提交均已推送。

独立模型按固定种子跑12000次插入、重复键、转换、派发、清理，每步精确核验三个世界四项统计与条目状态；六种初态和提前/重复派发均验。并发转换期间总数守恒、生命周期取消回调在锁外读表、清理后同坐标替代条目不受旧回调影响均有直接断言。真实门面另外验同tick增长/释放、分世界供给、重复队列/ACTIVE重叠、暂停重排和clearRuntime取消；尚未执行真实ServerLevel下的规划/持久化完成链。

18组生产变异全部产生指定JUnit失败，不把编译或命令失败计为红；包括整段恢复原tick缓存和恢复全量扫描。脚本全组预检替换锚点，每项finally精确checkout一个文件并比较原字节，末尾status为空；源码恢复后再次完整回归。记录：.analysis_tmp/round40_mutations.py、round40-mutations.json、round40-mutation-run.log、round40-mut-*.log；原缺陷复现round40-cache-reproduction.log，最终回归round40-final-after-mutations.log。

| # | 故意破坏 | 指定守卫 | JUnit失败数 |
|---|---|---|---|
| 1 | `new-entry-not-counted` | `planning.ReadinessRegionTableTest#everyStateAndDispatchOrderMatchesIndependentFullScan(net.shiroha233.roadweaver.planning.ReadinessRegionTable$State)` | 4，红 |
| 2 | `duplicate-insertion-counted-again` | `planning.ReadinessRegionTableTest#retriesPauseResumeAndFailureUseTheSameStateAccounting` | 1，红 |
| 3 | `old-state-not-removed` | `planning.ReadinessRegionTableTest#retriesPauseResumeAndFailureUseTheSameStateAccounting` | 1，红 |
| 4 | `next-state-not-added` | `planning.ReadinessRegionTableTest#retriesPauseResumeAndFailureUseTheSameStateAccounting` | 1，红 |
| 5 | `dispatch-does-not-remove-ready-ahead` | `planning.ReadinessRegionTableTest#retriesPauseResumeAndFailureUseTheSameStateAccounting` | 1，红 |
| 6 | `dispatch-flag-lost` | `planning.ReadinessRegionTableTest#everyStateAndDispatchOrderMatchesIndependentFullScan(net.shiroha233.roadweaver.planning.ReadinessRegionTable$State)` | 6，红 |
| 7 | `clear-keeps-old-counters` | `planning.ReadinessRegionTableTest#clearedEntriesCannotChangeReplacementAndSnapshotsStayImmutable` | 1，红 |
| 8 | `stale-completion-accepted` | `planning.ReadinessRegionTableTest#clearedEntriesCannotChangeReplacementAndSnapshotsStayImmutable` | 1，红 |
| 9 | `world-counters-mixed` | `planning.ReadinessRegionTableTest#retriesPauseResumeAndFailureUseTheSameStateAccounting` | 1，红 |
| 10 | `planning-not-in-pending` | `planning.ReadinessIndexStateTest#queueDuplicatesAndActiveOverlapCannotConsumeExtraAdmissionSlots` | 1，红 |
| 11 | `actual-supply-disconnected` | `planning.ReadinessIndexStateTest#actualSupplySeparatesWorldsDispatchAndUniqueBacklog` | 1，红 |
| 12 | `saturation-disabled` | `planning.ReadinessIndexStateTest#sameTickAdmissionSeesGrowthAndCompletionWithoutWaitingForAnotherTick` | 1，红 |
| 13 | `saturation-off-by-one` | `planning.ReadinessIndexStateTest#sameTickAdmissionSeesGrowthAndCompletionWithoutWaitingForAnotherTick` | 1，红 |
| 14 | `pause-leaves-planning-state` | `planning.ReadinessIndexStateTest#actualPauseRequeuesPlanningAndKeepsOtherStatesAndDispatch` | 1，红 |
| 15 | `runtime-clear-keeps-table` | `planning.ReadinessIndexStateTest#actualClearCancelsWorkAndLateCompletionCannotChangeNewSession` | 1，红 |
| 16 | `runtime-clear-does-not-cancel` | `planning.ReadinessIndexStateTest#actualClearCancelsWorkAndLateCompletionCannotChangeNewSession` | 1，红 |
| 17 | `full-scan-restored` | `planning.ReadinessRegionTableTest#repeatedSnapshotsDoNotRevisitAnyHistoricalEntry` | 1，红 |
| 18 | `original-tick-cache-restored` | `planning.ReadinessIndexStateTest#sameTickAdmissionSeesGrowthAndCompletionWithoutWaitingForAnotherTick` | 1，红 |

同JVM、固定256MiB、两个世界、5次热身/11次交替顺序测量：65536项的500次快照读取p50为全量143.3067 ms、增量0.0452 ms，p95为144.9236/0.0504 ms；同规模1500次转换p50为0.0435/0.0590 ms。读取批次两者均分配16000 B，转换批次均0 B（不含探针结果对象）；这是以少量转换成本换掉历史区域扫描，不宣称零分配或游戏FPS改善。512/8192规模和完整命令见VALIDATION第40轮，日志round40-readiness-benchmark.log。

### 三、我没能验证什么

- **需要真机的**：实际Chunky逐tick请求、服务端ServerLevel的规划与Streams完成链、暂停/恢复的完整代次交错、FPS/MSPT、首次生成和重开尾延迟；离线测试只直接驱动真实门面的运行态部分。
- **需要存档的**：READY及施工台账排序/原子替换的真实耗时，4096规模持有者与磁盘压力；纯状态表探针不含IO、世界采样、候选图或其他模组。
- **我怀疑但没有证据的**：真实现场在这三条读取路径中消耗多少毫秒、每世界短锁在非标准并发调用下是否形成争用，未据猜测调整lane/队列/缓存容量。
- **我确认代码分支存在但尚未故障注入的**：RoadPlanningReadinessIndex:534–550先写内存READY集合/状态；persistReady:652–684捕获IOException只记日志、不通知调用方或设置重试。READY索引写失败后内存仍能报告READY，重新打开时缺少未落盘的就绪记忆；这不证明道路分片本身未保存，也未证明已损坏真实存档。本轮保持同步持久化流程，优先在下一轮为此加真实文件故障、重试及重开守卫。
- 512是服务端线程逐次准入的即时判据；本轮没有把“检查饱和→adoption→插入”改为跨线程原子准入，不声称任意并发反射调用也有严格总额。ACTIVE查询、暂停/清理枚举和READY文件全表序列化仍存在，不以增量统计冒充全部扫描已清零。

### 四、遗留半截的东西

没有未提交实现或临时破坏；未制作发布包、未部署。阶段1、阶段2、探针已分别小步提交并推送；最终回归后仅更新本轮五份文档。停下前核对status为空、与origin/main为0/0。READY写盘故障契约仍是明确后续项，不能把本轮状态转换守卫称为持久化已验收。

### 五、下一轮我会先做什么

1. 在不改schema2、文件名和指纹的前提下，抽最小同步READY文件读写边界；真实临时文件复现写失败→内存发布/重复调用→重开，再确定如何把失败送回已有重试和最终失败路径，防止只改返回值却让adoption仍报READY。
2. 对拍成功文件内容、已保存区域复用、并发发布顺序、失败后重试与清理迟到回调。先保持同步；异步/批量合并写入必须另有实际耗时、版本顺序及停服封账证据。
3. 4096真实工作集、三模组最终Mixin次序与几何/桥隧现场继续按原路线；不以纯内核计时切默认、裁候选或改变crossing。

### 六、我踩到但已解决的坑

原注释声称同tick饱和不会变，与同tick逐个入队矛盾；必须用真实连续插入复现，而不能只测tick键会失效。计数必须同时覆盖插入、重复键、转换、提前派发、暂停重排与clear；只测最终值漏掉中途重复扣减。取消依赖Future的异常也不能证明回调内读取成功，本轮直接等待回调内部的查询结果。基准分别报告读与写，避免只展示读取收益、隐藏转换的额外成本。


---

## 第41轮 ｜ 2026-11-17 11:49 → 12:18 ｜ READY同步提交与故障重试 ｜ 源码 `afb418e`、接线守卫 `812dcb5` ｜ `PASS tests=1494 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| planning/ReadinessCoverageStore.java，143/123行（物理/有效） | 迁出同步文件边界；提交前不改READY集合，读失败先重读；关闭拒收并排空旧写入 | 先行为等价9a45ab6，后行为变更afb418e |
| planning/RoadPlanningReadinessIndex.java，721/537行 | 正常完成写失败进入原三次重试/FAILED并保留原因；adoption失败返回false走通常入队；初始覆盖失败告警且不发布；清理关闭世界文件边界，写后复核条目/代次 | 行为变更afb418e；保留成功几何、状态名与反射接口 |
| planning/ReadinessRegionTable.java，93/74行 | 条目保留lastFailure，成功完成清除；只属于运行态 | 行为变更，无存档字段变化 |
| ReadinessCoverageStoreTest、ReadinessCoverageFaultTest、ReadinessPersistenceCompletionTest、ReadinessPersistenceWiringTest及套件 | 9+10+14+2项，四类均按FQCN selectClass登记；最后2项仅AST接线 | 测试基建，累计新增35项 |

全量独立词法计数693个生产Java文件，超过1000有效行0、超过800行2；新逻辑写中文原因注释。READY文件schema=2、chunky-ready-regions-v2.txt、指纹输入、UTF-8/LF、带符号packed long排序及重复区域复用不变；RoadData、区块库/施工台账/WorldgenFingerprint格式未动。只改道路仓库，未重新核对或修改河流/长城；没有打包部署。

### 二、我验证了什么（附负控）

开工status空、pull --rebase已是最新，HEAD c32d567；基线1459全绿、Fabric通过。先等价迁出文件边界并加8项成功格式/重开回归，1467全绿后单独提交并推送9a45ab6。随后在这个仍保留原故障的实现上，用Windows NOSHARE_DELETE锁真实目标：先确认真实原子替换辅助失败，再调用markReady，指定JUnit因“未抛异常”真实红1项（round41-write-fault-reproduction.log）。这证明READY记忆提交失败，不等于道路分片损坏。

修复后真实文件替换失败保持旧字节、内存不发布；解锁后同对象重试并重开看到旧+新区域。NOSHARE_READ真实读锁、损坏合法前缀、非普通路径及缺父目录失败均验；只有已确认文件不存在才从空索引开始。串行提交用实际线程BLOCKED且栈帧位于markReady/close确认，不用执行器线程创建顺序猜测已进入。关闭返回前排空已接受写入，关闭期间已开始的物理写入允许完成；关闭后旧实例不能再IO或发布内存。真实clearRuntime、替换条目和迟到成功/失败均直接驱动生产逻辑；服务端外层接线单独用Javac AST验证，不伪装成ServerLevel整链。

29组独立变异均触发指定JUnit失败，skipped=0、aborted=0；编译、脚本或命令错误不算红。先预检全部替换锚点，每项finally精确git checkout一个文件并比较原字节；还原后完整回归1494全绿、Fabric通过。记录：.analysis_tmp/round41_cases.py、round41_mutations.py、round41-mutations.json、round41-mutation-run.log、round41-mutation-resume.log、round41-mut-*.log；最终回归round41-final-after-mutations.log。

另亲手测出并修正新增接线守卫的一处盲点：把生产完成条件改为false && finishPlanning，原AST只找调用会绿（TARGET_RESULT passed=1 failed=0，round41-shortcircuit-green-before.log）。812dcb5改为要求条件直接使用完成结果后，同一生产变异真实红1项（round41-shortcircuit-red-after.log），计入下表第29项。第16项首次直接删除IO调用导致checked catch编译失败，明确不算红；改为可编译但不会进入的提交条件后，真实JUnit红1项。

| # | 故意破坏 | 指定守卫 | JUnit失败数 |
|---|---|---|---|
| 1 | 写前发布内存READY：`premature-memory-publication` | `planning.ReadinessCoverageStoreTest#failedReplacementKeepsMemoryAndFileUnchangedAndRetriesAfterUnlock(java.nio.file.Path)` | 1，红 |
| 2 | 吞掉真实IOException：`ioexception-swallowed` | `planning.ReadinessCoverageStoreTest#failedReplacementKeepsMemoryAndFileUnchangedAndRetriesAfterUnlock(java.nio.file.Path)` | 1，红 |
| 3 | 提交不保留旧覆盖：`old-coverage-lost-on-write` | `planning.ReadinessCoverageStoreTest#goldenSchemaTwoBytesAndNegativeCoordinatesSurviveReopen(java.nio.file.Path)` | 1，红 |
| 4 | 重复区域再次写整表：`duplicate-rewritten` | `planning.ReadinessCoverageStoreTest#goldenSchemaTwoBytesAndNegativeCoordinatesSurviveReopen(java.nio.file.Path)` | 1，红 |
| 5 | 删除提交串行锁：`concurrent-writers-not-serialized` | `planning.ReadinessCoverageFaultTest#inFlightWritesAreInvisibleAndConcurrentCommitsAreSerialized(int,java.nio.file.Path)` | 2，红 |
| 6 | 读失败按空表提交：`unreadable-index-treated-as-empty` | `planning.ReadinessCoverageFaultTest#unreadableOldFileCannotBeOverwrittenAndUnlockRetriesTheRead(java.nio.file.Path)` | 1，红 |
| 7 | 不保留读失败：`read-failure-not-retained` | `planning.ReadinessCoverageFaultTest#malformedPrefixCannotBeReplacedUntilTheFileIsRepaired(java.nio.file.Path)` | 1，红 |
| 8 | 解锁后不重新读取：`read-not-retried-after-unlock` | `planning.ReadinessCoverageFaultTest#unreadableOldFileCannotBeOverwrittenAndUnlockRetriesTheRead(java.nio.file.Path)` | 1，红 |
| 9 | schema由2改3：`schema-changed` | `planning.ReadinessCoverageStoreTest#goldenSchemaTwoBytesAndNegativeCoordinatesSurviveReopen(java.nio.file.Path)` | 1，红 |
| 10 | 颠倒packed long排序：`signed-order-reversed` | `planning.ReadinessCoverageStoreTest#goldenSchemaTwoBytesAndNegativeCoordinatesSurviveReopen(java.nio.file.Path)` | 1，红 |
| 11 | 忽略指纹：`fingerprint-check-disabled` | `planning.ReadinessCoverageStoreTest#differentFingerprintDoesNotAdoptOldCoverage(java.nio.file.Path)` | 1，红 |
| 12 | 关闭不置失效：`close-does-not-invalidate-store` | `planning.ReadinessCoverageFaultTest#closedInstanceCannotWriteOrReportReady(java.nio.file.Path)` | 1，红 |
| 13 | 关闭不排空在途写入：`close-does-not-drain-writer` | `planning.ReadinessCoverageFaultTest#closeDrainsAnAcceptedWriteBeforeReopenAndSuppressesOldPublication(java.nio.file.Path)` | 1，红 |
| 14 | 提交后省略关闭复核：`closed-write-still-publishes` | `planning.ReadinessCoverageFaultTest#closeDrainsAnAcceptedWriteBeforeReopenAndSuppressesOldPublication(java.nio.file.Path)` | 1，红 |
| 15 | adoption失败仍报成功：`adoption-hides-persist-failure` | `planning.ReadinessPersistenceCompletionTest#adoptionReturnsFalseOnRealWriteFailureAndDoesNotPublishEntry(java.nio.file.Path)` | 1，红 |
| 16 | 正常完成跳过文件提交：`finisher-does-not-persist` | `planning.ReadinessPersistenceCompletionTest#realReplacementFailureRequeuesAndUnlockAllowsDurableReady(java.nio.file.Path)` | 1，红 |
| 17 | 忽略最终路径失败：`invalid-path-published` | `planning.ReadinessPersistenceCompletionTest#planningOrFinalPathFailureNeverAttemptsToWriteCoverage(int,java.nio.file.Path)` | 1，红 |
| 18 | 重试不入队：`retry-queue-omitted` | `planning.ReadinessPersistenceCompletionTest#writeFailureUsesOriginalThreeAttemptLimitAndRetainsExactCause(int,java.nio.file.Path)` | 2，红 |
| 19 | 错误允许第四次重试：`fourth-attempt-allowed` | `planning.ReadinessPersistenceCompletionTest#writeFailureUsesOriginalThreeAttemptLimitAndRetainsExactCause(int,java.nio.file.Path)` | 1，红 |
| 20 | 丢掉真实失败原因：`failure-cause-lost` | `planning.ReadinessPersistenceCompletionTest#writeFailureUsesOriginalThreeAttemptLimitAndRetainsExactCause(int,java.nio.file.Path)` | 3，红 |
| 21 | 写失败重排成READY：`retry-publishes-ready` | `planning.ReadinessPersistenceCompletionTest#writeFailureUsesOriginalThreeAttemptLimitAndRetainsExactCause(int,java.nio.file.Path)` | 2，红 |
| 22 | 成功不清ACTIVE：`successful-completion-keeps-active` | `planning.ReadinessPersistenceCompletionTest#realReplacementFailureRequeuesAndUnlockAllowsDurableReady(java.nio.file.Path)` | 1，红 |
| 23 | 成功写后不复核旧条目：`post-write-stale-success-accepted` | `planning.ReadinessPersistenceCompletionTest#replacementDuringWriteCannotPublishOrRequeueIntoTheNewEntry(int,java.nio.file.Path)` | 1，红 |
| 24 | 失败写后不复核旧条目：`post-write-stale-failure-requeued` | `planning.ReadinessPersistenceCompletionTest#replacementDuringWriteCannotPublishOrRequeueIntoTheNewEntry(int,java.nio.file.Path)` | 1，红 |
| 25 | 实际clear不关闭文件边界：`clear-does-not-close-world-store` | `planning.ReadinessPersistenceCompletionTest#actualClearDrainsWorldStoreAndLateFailureCannotRequeueOldKey(java.nio.file.Path)` | 1，红 |
| 26 | 服务端回调绕过文件提交（AST）：`server-callback-bypasses-finisher` | `planning.ReadinessPersistenceWiringTest#realServerCompletionCallsTheTestedFinisherInsideTheServerTask` | 1，红 |
| 27 | 生产adoption忽略失败返回（AST）：`adoption-ignores-boundary-result` | `planning.ReadinessPersistenceWiringTest#realAdoptionAndWorldLoadReturnTheTestedPersistenceBoundary` | 1，红 |
| 28 | 生产加载传错指纹（AST）：`world-loader-wrong-fingerprint` | `planning.ReadinessPersistenceWiringTest#realAdoptionAndWorldLoadReturnTheTestedPersistenceBoundary` | 1，红 |
| 29 | 生产完成条件以false短路（AST）：`server-finisher-short-circuited` | `planning.ReadinessPersistenceWiringTest#realServerCompletionCallsTheTestedFinisherInsideTheServerTask` | 1，红 |

### 三、我没能验证什么

- **需要真机的**：真实ServerLevel/AsyncPlanningOps/Streams及Chunky逐tick完成链、退出重开的服务端事件与Mixin时序；2项AST只能证明窄接线结构。三模组预热、FPS/MSPT和首次加载尾延迟未验；本轮没跑外部模组测试。
- **需要真实存档的**：4096工作集、实际磁盘/文件系统与整合包争用、索引及施工台账耗时；临时文件重开不代替玩家存档。底层仍是原FileStorageIO的原子move及普通替换回退，没有新增fsync，不宣称突然断电的持久性。
- **怀疑但没有证据的**：同步全表排序/写盘在现场占多少主线程时间，尚未量；不以此故障修复宣称进档加速或改异步。
- **已知边界未改的**：第三次失败后进入FAILED，沿用原预算，本会话不无限重试；修复文件/解锁不承诺让耗尽预算的FAILED自动复活，正常重启会重新加载。损坏/不可读旧索引拒绝覆写，修复文件后才可正常重读合并。readiness文件不是道路分片完工证明。512准入仍非任意并发反射调用的原子总额；生命周期、ACTIVE枚举和同步全表写盘仍在。crossing、双平滑默认、QualityGate及旧区块自动重铺按原决定不动。

### 四、遗留半截的东西

本轮无半截实现；临时变异均逐字恢复。源码/测试与文档小步提交推送；停下前核对git status为空、origin/main与HEAD为0/0。没有发布新二进制，RELEASES不增加虚假部署记录。

### 五、下一轮我会先做什么

1. 可独立继续路线第4项：先对PathPostProcessor样条/宽度栅格化固定生产输出、保护列、端点与桥头，再等价迁出纯计算边界；拆分与默认几何改动分开提交，不以文件达标代替职责审查。
2. 有现场日志后再优化READY/施工台账写盘：分别看收集、排序、编码、替换耗时及条目规模；若确认为热点，必须保留已验发布顺序、失败重试及关闭封账，再评估批量或异步。
3. 逐档存档副本检查4096与三模组启动顺序；没有桥隧召回/A-B数据不改crossing和二次陆地松弛默认。

### 六、我踩到但已解决的坑

文件格式搬迁先行为等价提交，故障用例在旧实现先红；不能把“加了异常”当故障已经复现。文件读锁不同于不存在，解析合法前缀也不能让后半段损坏的文件变成可覆写快照。写入失败与成功都须写后复核旧条目，少复核失败分支会把清理后的旧key塞回队列。关闭先失效再等同步写锁，允许已开始的物理提交完成，并明确关闭返回才是旧IO排空边界；不能声称关闭一开始就撤销已接受磁盘操作。变异预检遇到非唯一锚点立即停，重定位后才执行，不把预检错误计为守卫红。


---

## 第42轮 ｜ 2026-11-17 13:48 → 14:10 ｜ 后处理样条与落列等价拆分 ｜ 测试 `2a6fc2d`、源码 `6a2a608` ｜ `PASS tests=1516 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| postprocess/PathPostProcessor.java，474/320行（原660/481，物理/有效） | 保留世界采样、掩码、简化/松弛/密化和影子编排；公开及包内入口原样透传曲线模式、目标Y、里程和宽度 | 行为等价6a2a608 |
| postprocess/PathSplineSampler.java，148/117行 | 迁入样条、中心站及按原站序向前32段的高度投影；桥腿/前后岸腿、急弯直线和3D采样步数不改 | 行为等价6a2a608，无世界读取 |
| postprocess/PathWidthRasterizer.java，101/84行 | 曲线、线性保护通道和剖面一站一段重建共用原宽度落列；保留闭区间、±2站邻域、并列提示与空站锚点 | 行为等价6a2a608，无世界读取 |
| BridgeMaskTool.java、S5-POSTPROCESS-LEDGER.md | 删除确实零使用的旧RoadSegmentPlacement导入，新增组件登记KEEP_INPUT；依赖基线未放宽 | 行为等价6a2a608 |
| LegacyCurveRasterReference、PathCurveRasterParityTest、PathWidthRasterizerContractTest、PostProcessedRoadProductTest与套件 | 冻结本轮算法及数学依赖，新增9+10+3=22项；新测试类按FQCN selectClass登记 | 测试基建2a6fc2d，6a2a608补第三种生产路形和终点取整重复夹具 |

695个生产Java文件，超过1000有效行0，超过800行2；新增逻辑写中文原因注释。7个算法方法体剥除注释和空白后与bc69e3b完全相同（round42-body-verification.log），只调整宿主/静态导入和门面透传。移走的私有HeightProjection及其方法没有外部限定引用，api/common/fabric源码全量匹配计数0；没有动数据编码、配置键、枚举、河流或长城工程，没有打包部署。

### 二、我验证了什么（附负控）

开工status空、pull --rebase已是最新，HEAD bc69e3b；基线1494全绿、Fabric通过。先加独立冻结参照和22项守卫，1516全绿后单独提交2a6fc2d。冻结代码来自明确git基线，包含样条/高度/落列及角度、距离、Bezier数学；不能让参照侧转调新实现来循环对拍。数学函数移出Vec2d所在类后仅将22处私有字段读改为等价record访问器。简化、松弛、掩码和密化仍共享当前入口，本轮不以此宣称这些未修改阶段都有独立旧实现对照。

每个CurveMode固定60条种子路线，共180组，逐点比较双精度样条、中心站及里程，再逐站比较铺装位置列表和顺序；含负坐标、高度变化、短掩码、桥/陆、重合点和宽1/2/5。真实PathPostProcessor.process完整重载另对三路形×水陆×三宽×三模式共54组，使用原平地Backend替代世界读，桥掩码及后处理实际执行。独立闭胶囊夹具不调用生产距离函数，核验宽0/1/2/5/6、负坐标、端帽和紧邻保护列。还直接验桥头岸腿、急弯、3D步数、同X/Z两次经过不同高程、t=0.5归后站、等距交叉按hint、空站锚点和一站一段。

拆分后首次全套几何用例均通过，架构依赖棘轮与S5台账各红1项：补登记，并删除BridgeMaskTool中唯一一次出现且未使用的RoadSegmentPlacement导入后恢复1516全绿，未增大deps-baseline；日志round42-extraction.log、round42-extraction-fixed.log。第三路形与最终重复取整夹具分别指定JUnit正向通过，再提交6a2a608。最终恢复全部变异后再次1516全绿、Fabric通过，日志round42-final-after-mutations.log。

23组变异均触发目标JUnit失败，零skip/abort，不将编译或脚本失败算红。全部锚点先预检，逐项finally精确git checkout一个文件并比较原字节；主脚本末尾再次status空。脚本/JSON/日志：.analysis_tmp/round42_cases.py、round42_mutations.py、round42-mutations.json、round42-mutation-run.log、round42-mut-*.log。表中后三项直接在门面改坏参数，由完整生产入口产物对拍捕获。

| # | 故意破坏 | 指定守卫 | JUnit失败数 |
|---|---|---|---|
| 1 | 丢弃全部样条点：`drop-all-spline-samples` | `postprocess.PathCurveRasterParityTest#seededCurvesPreserveEverySampleCenterAndOrderedColumn(net.shiroha233.roadweaver.postprocess.SplineHelper$CurveMode)` | 3，红 |
| 2 | 陆地缓弯强制直线：`flatten-gentle-land` | `postprocess.PathCurveRasterParityTest#gentleLandCurveActuallyUsesTheRequestedCurveMode` | 1，红 |
| 3 | 漏掉桥前后岸腿对齐：`omit-bridge-approach-alignment` | `postprocess.PathCurveRasterParityTest#bridgeApproachOnBothSidesRemainsLinearWithoutFlatteningAllLand` | 1，红 |
| 4 | 桥腿错误要求两端都湿：`bridge-needs-both-wet-endpoints` | `postprocess.PathCurveRasterParityTest#bridgeApproachOnBothSidesRemainsLinearWithoutFlatteningAllLand` | 1，红 |
| 5 | 不冻结急弯：`ignore-sharp-turns` | `postprocess.PathCurveRasterParityTest#hairpinHasActualSamplesAndNeverLeavesTheTwoStraightLegs` | 1，红 |
| 6 | 3D步数改为水平步数：`sample-only-horizontal-distance` | `postprocess.PathCurveRasterParityTest#samplingDensityStillUsesThreeDimensionalControlDistance` | 1，红 |
| 7 | 丢掉最终样条点：`omit-final-sample` | `postprocess.PathCurveRasterParityTest#samplingDensityStillUsesThreeDimensionalControlDistance` | 1，红 |
| 8 | Bezier错走Catmull：`replace-bezier-with-catmull` | `postprocess.PathCurveRasterParityTest#seededCurvesPreserveEverySampleCenterAndOrderedColumn(net.shiroha233.roadweaver.postprocess.SplineHelper$CurveMode)` | 1，红 |
| 9 | 交叉投影退回第一遍：`project-back-to-first-pass` | `postprocess.PathCurveRasterParityTest#elevatedCrossingKeepsTheHeightOfTheCurrentPass` | 1，红 |
| 10 | 截断向前高度搜索窗口：`truncate-forward-projection-window` | `postprocess.PathCurveRasterParityTest#elevatedCrossingKeepsTheHeightOfTheCurrentPass` | 1，红 |
| 11 | 不插值目标Y：`drop-interpolated-target-height` | `postprocess.PathCurveRasterParityTest#elevatedCrossingKeepsTheHeightOfTheCurrentPass` | 1，红 |
| 12 | 保留取整重复中心站：`keep-duplicate-rounded-center` | `postprocess.PathCurveRasterParityTest#repeatedRoundedCentersAreSkippedButTheLastDistinctEndpointRemains` | 1，红 |
| 13 | 中心站距错误加倍：`double-center-spacing` | `postprocess.PathCurveRasterParityTest#repeatedRoundedCentersAreSkippedButTheLastDistinctEndpointRemains` | 1，红 |
| 14 | 缩小半宽：`shrink-road-half-width` | `postprocess.PathWidthRasterizerContractTest#straightRoadMatchesTheClosedCapsuleIncludingNegativeCoordinatesAndCaps(int)` | 3，红 |
| 15 | 排除闭区间边界列：`exclude-closed-width-boundary` | `postprocess.PathWidthRasterizerContractTest#straightRoadMatchesTheClosedCapsuleIncludingNegativeCoordinatesAndCaps(int)` | 3，红 |
| 16 | 中点列错归前站：`midpoint-goes-to-earlier-station` | `postprocess.PathWidthRasterizerContractTest#midpointColumnsStayWithTheLaterStationAtHalfwayAndKeepTargetY` | 1，红 |
| 17 | 等距交叉忽略hint：`ignore-hint-on-equal-distance` | `postprocess.PathWidthRasterizerContractTest#overlappingPassesUseTheCurrentStationHintForEqualDistances(int)` | 1，红 |
| 18 | 空站不保留中心锚点：`drop-empty-station-anchor` | `postprocess.PathWidthRasterizerContractTest#repeatedStationsKeepOneToOneOutputAndAnAnchorForAnOtherwiseEmptyStation` | 1，红 |
| 19 | 铺装列错误携带目标Y：`columns-persist-target-y` | `postprocess.PathWidthRasterizerContractTest#midpointColumnsStayWithTheLaterStationAtHalfwayAndKeepTargetY` | 1，红 |
| 20 | 剖面重建整路丢弃：`drop-station-rebuild-product` | `postprocess.PathWidthRasterizerContractTest#straightRoadMatchesTheClosedCapsuleIncludingNegativeCoordinatesAndCaps(int)` | 5，红 |
| 21 | 生产门面传错路宽：`facade-passes-wrong-road-width` | `pathfinding.sampling.PostProcessedRoadProductTest#productionCurveEntryMatchesFrozenSuffixForDryAndWetRoads(net.shiroha233.roadweaver.postprocess.SplineHelper$CurveMode)` | 3，红 |
| 22 | 生产门面忽略曲线模式：`facade-ignores-requested-mode` | `pathfinding.sampling.PostProcessedRoadProductTest#productionCurveEntryMatchesFrozenSuffixForDryAndWetRoads(net.shiroha233.roadweaver.postprocess.SplineHelper$CurveMode)` | 2，红 |
| 23 | 生产门面丢目标Y：`facade-loses-target-y` | `pathfinding.sampling.PostProcessedRoadProductTest#productionCurveEntryMatchesFrozenSuffixForDryAndWetRoads(net.shiroha233.roadweaver.postprocess.SplineHelper$CurveMode)` | 3，红 |

### 三、我没能验证什么

- **需要真机的**：真实地形/结构保护、桥头与长桥最终落块、BAND后续修坡及合流、FPS/MSPT、首次加载和Chunky尾延迟；平地Backend与纯几何对拍不能代替这些。
- **需要真实存档的**：4096规模、旧路重开与施工、三模组最终Mixin和退出重开次序。本轮未读写或重跑河流/长城，守卫均归道路仓库。
- **怀疑但没有证据的**：宽度落列重复列访问、连续站号HashMap查找和临时对象在现场占多少时间，未做分配/耗时对照，不能声称本轮拆分减少卡顿或加快加载。
- **已知边界未改的**：桥头邻腿与急弯仍按原规则直线，采样步数仍用3D控制点距离；第二次陆地松弛、crossing、跨度回写/QualityGate默认、长桥正弦、村庄包围盒和存档协议都保留。闭区间使偶数width的轴向截面可含width+1列，这是现行规则，等价拆分没有顺带改宽度含义。保护列测试核验既定安全走廊，不代表纯栅格器新增了世界保护查询。

### 四、遗留半截的东西

无半截实现或临时破坏；两个源码/测试提交和本轮文档按main正常推送，停下前核对工作区空、origin/main与HEAD为0/0。S5两份机器读取路径保持，方法级删除台账无需修改。没有制作/部署新发布件。

### 五、下一轮我会先做什么

1. 先量PathWidthRasterizer的临时分配与列访问工作量，固定同一份点/站/宽度、同JVM交替对照。若连续站号HashMap查找确有成本，用按站号的数组/列表替代，并保持每站HashSet插入、邻域、并列和最终列表顺序；以本轮冻结产物为验收，独立性能提交。
2. 后处理剩余控制点密化仍在门面，单纯为行数再拆价值较低；只有职责或实际热点证据足够再动。世界采样和影子编排继续留门面，不更换整个架构。
3. READY/施工台账异步写盘、4096工作集及三模组整链、双平滑默认与crossing继续按路线等现场证据。

### 六、我踩到但已解决的坑

只比较最终铺装并集会漏掉站点归属与目标Y漂移，必须逐站比较中心和位置列表；只看“样条非空”也会让所有曲线变直仍绿。冻结参照若调用新组件就变成自证，数学依赖也需一并冻结。嵌套Vec2d私有字段不能搬到另一个类后直接读，改record访问器并保留浮点运算顺序。新postprocess文件必须登记S5；架构棘轮按文件计入依赖，不能靠放宽基线过关，删除旧的真实未用导入保持依赖计数不增。类拆小本身不等于运行更快，后续优化仍先测工作量与分配。


---

## 第43轮 ｜ 2026-11-17 ｜ 五项代码推进与离线证据 ｜ 源码/测试b8b7194～c1805a8 ｜ `PASS tests=1543 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件/提交 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| PathWidthRasterizer 108/88行；b8b7194、c1805a8 | 连续站号改列表，保留HashSet及原插入/输出顺序；新增同JVM分配探针及512站对拍 | 性能、行为等价 |
| StructureAvoidanceService 661/458行；PlanningSnapshotPreparation 167/156、Activation 19/15；18ed8f1 | 整段准备/发布与线程作用域等价迁移；嵌套公开类型仍留原处 | 行为等价，先1516全绿单独提交 |
| TargetYComputation 526/346行；TargetTerrainInputs 71/62；18ed8f1、1815686、c1805a8 | 输入批采样、缺样回落及支撑统计独立端口；保留旧跨度及模式编排；新增中文原因注释与直接守卫 | 行为等价 |
| AccurateColumnBatch、AccurateChunkBatch及原测试类；ee69961、d95034b | 四条只读盘缓存填充在实际放入后发布；补64轮驱逐与关闭期间列等待者回归 | 行为变更：减少旧未命中导致的重复计算，不改高度值 |
| DirectionalUpwindCandidate/Test及DirectionalCostProbe；e680bfb | 仅测试目录候选三角迎风与同JVM方向/路径/耗时对照；明确不采用慢原型 | 测试基建，生产行为等价 |
| RouteEvidenceId、RoadBuildEvidence 17/12及60/49行；RoadPipeline/PipelinePlanner/Road/PathFeature；827b69d | 受原影子开关控制，完整端点ID串起有限粗候选、终态校验与区块处理结果，放弃独立于完成 | 观测接线，选网/施工决策等价 |
| 测试套件、三个新证据测试类及架构基线 | 新类按FQCN登记；清理实际未用导入，基线仅减不增 | 测试基建 |

行数均为物理/有效，700个生产Java文件，有效行>1000为0、>800为2。Road当前893/693、PathFeature 813/646，不因新观测接线越线。没有修改CODEC/schema/配置键/持久枚举，没有更改河流长城或其他相邻工程，没有打包部署。

### 二、我验证了什么（附负控）

开工status空，pull --rebase已是最新，HEAD f67e70b；基线1516全绿、Fabric通过。宽度先量原实现与冻结参照相同分配，再改列表并全绿提交。快照准备完整方法与b8b7194原文一致；依赖守卫曾要求收紧减少的基线，按实际减少更新，没有放宽。三个查盘交错在原实现先红3项（round43-disk-fill-before.log），整块填充先红1项（round43-chunk-fill-before.log），修复后全绿。六项拆分守卫、六项采样守卫、六项候选守卫、八项证据守卫及一项长路线对拍，共27项；2项证据AST只证明世界边界窄接线，不能算真实世界运行。

宽度固定输入同JVM/512MiB、30轮预热/15组交替p50，逐站中心和位置列表先对拍；数据见下表。2048站分配约减35.3%，没有少访问列或改变几何。单轮微基准不等于FPS/加载耗时收益。

| 站数 / 宽度 | 包围盒列访问次数（两版相同） | 冻结基线分配 B | 当前分配 B | 基线p50 ms | 当前p50 ms |
|---|---|---|---|---|---|
| 128 / 5 | 41,148 | 338,856 | 333,200 | 1.0652 | 0.9762 |
| 512 / 5 | 165,564 | 1,921,864 | 1,334,728 | 3.6509 | 3.2337 |
| 2048 / 5 | 663,228 | 8,264,704 | 5,348,960 | 14.8574 | 12.9518 |

方向原型33×33解析面：45°时分开上坡1.0625与等高线1.0000，但生产/候选p50 0.3511/7.8754 ms，平面回溯折线相同。没有桥隧/实际挖填或非凸度量收敛证据，故只留离线实验，生产FMM不变。可复现命令、0°数据与范围见VALIDATION第43轮。观测输出只关联证据，不从粗湿格或台账反推成功施工。

恢复全部临时变异后再次完整执行:common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off，1543全绿、零skip/abort，Fabric BUILD SUCCESSFUL；日志round43-final-after-mutations.log。21组均真实目标JUnit红，共24次失败，19组生产代码变异（含3组AST守卫）、2组测试目录候选变异；不将编译/脚本失败记为红。脚本先断言所有锚点唯一/指定次数，每项finally精确git checkout且逐字恢复；末尾status空。脚本/输入/JSON/日志位于.analysis_tmp/round43_mutations.py、round43-cases.json、round43-mutations.json、round43-mutation-run.log及round43-mut-*.log。

| # | 故意破坏 | 守卫 | JUnit失败数 |
|---|---|---|---|
| 1 | 长路线站号截到127：`station-index-wraps-at-127` | `postprocess.PathWidthRasterizerContractTest#longRoadAboveIntegerCachePreservesEveryOrderedStationProduct` | 1，红 |
| 2 | 全部列归首站：`all-columns-go-to-first-station` | `postprocess.PathWidthRasterizerContractTest#midpointColumnsStayWithTheLaterStationAtHalfwayAndKeepTargetY` | 1，红 |
| 3 | 排除路宽闭边界：`width-boundary-excluded` | `postprocess.PathWidthRasterizerContractTest#straightRoadMatchesTheClosedCapsuleIncludingNegativeCoordinatesAndCaps(int)` | 3，红 |
| 4 | 完整样本仍读世界：`complete-samples-still-read-world` | `pathlogic.core.TargetTerrainInputsTest#completeBatchIncludesCentersAndNeverReadsWorld` | 1，红 |
| 5 | 缺中心改用Y=0：`missing-center-flattened-to-zero` | `pathlogic.core.TargetTerrainInputsTest#missingCenterUsesWidthSupportAndMissingWidthUsesSampledCenter` | 1，红 |
| 6 | 删除输入中断检查：`input-cancellation-ignored` | `pathlogic.core.TargetTerrainInputsTest#interruptionStopsBeforeCallingSamplingOrWorldPorts` | 1，红 |
| 7 | 内层退出丢外层快照：`nested-snapshot-not-restored` | `pathlogic.structures.PlanningSnapshotActivationTest#nestedExpansionRestoresTheExactOuterSnapshotEvenOnFailure` | 1，红 |
| 8 | 忽略临时扩宽：`temporary-radius-ignored` | `pathlogic.structures.PlanningSnapshotActivationTest#nestedExpansionRestoresTheExactOuterSnapshotEvenOnFailure` | 1，红 |
| 9 | 漏列查盘发布：`column-disk-publication-omitted` | `pathfinding.sampling.AccurateColumnBatchTest#diskOnlyFillDuringStaleMissDoesNotRecompute(int)` | 1，红 |
| 10 | 漏网格查盘发布：`grid-disk-publication-omitted` | `pathfinding.sampling.AccurateColumnBatchTest#diskOnlyFillDuringStaleMissDoesNotRecompute(int)` | 1，红 |
| 11 | 漏只读网格查盘发布：`read-only-grid-publication-omitted` | `pathfinding.sampling.AccurateColumnBatchTest#diskOnlyFillDuringStaleMissDoesNotRecompute(int)` | 1，红 |
| 12 | 漏整块查盘发布：`chunk-disk-publication-omitted` | `pathfinding.sampling.AccurateChunkBatchTest#diskOnlyChunkFillPublishesTheSharedColumnTileVersion` | 1，红 |
| 13 | 关闭不唤醒普通列等待者：`shutdown-does-not-wake-column-waiters` | `pathfinding.sampling.AccurateColumnBatchTest#closeDuringActiveGridWakesColumnWaiterBeforeOwnerReturns` | 1，红 |
| 14 | 候选退化各向同性：`candidate-loses-directional-cost` | `metric.DirectionalUpwindCandidateTest#directionalCandidateDistinguishesUphillFromContourAfterRotation(int)` | 2，红 |
| 15 | 候选忽略边硬禁行：`candidate-crosses-edge-only-barrier` | `metric.DirectionalUpwindCandidateTest#edgeOnlyBarrierCannotBeBypassedWithDiagonalStencil` | 1，红 |
| 16 | 观测ID丢第二端点：`evidence-collapses-distinct-endpoints` | `pathlogic.metrics.RoadBuildEvidenceTest#fullEndpointsSurviveReversalNegativeCoordinatesAndWorldChanges` | 1，红 |
| 17 | 把放弃当完工：`abandoned-counts-as-completed` | `pathlogic.metrics.RoadBuildEvidenceTest#durableAbandonmentAndUntouchedSliceAreNotCompletion` | 1，红 |
| 18 | 生产水校验结果伪造：`real-water-result-forged` | `planning.RouteEvidenceWiringTest#realValidationCallsUseFinalResultsBehindTheExactDiagnosticGate` | 1，红 |
| 19 | 生产观测false短路：`diagnostics-short-circuited` | `planning.RouteEvidenceWiringTest#realValidationCallsUseFinalResultsBehindTheExactDiagnosticGate` | 1，红 |
| 20 | 生产完工集合参数伪造：`chunk-completed-input-forged` | `planning.RouteEvidenceWiringTest#chunkEvidenceUsesActualAttemptSetsAndCoarseObserverUsesRealCoordinates` | 1，红 |
| 21 | 粗候选回调永不执行：`coarse-observer-never-called` | `pipeline.ConnectionEvidenceTest#selectedAndAlternativeEvidenceUsesRealEndpointsAndLeavesTopologyUnchanged` | 1，红 |

### 三、我没能验证什么

- **需要真机的**：首次生成/重开/Chunky同跑、FPS/MSPT与加载尾延迟；真实ServerLevel的快照准备发布、保护/桥头/隧道施工和三模组最终Mixin顺序未验。新观测仅接线，没有声称已采集现场样本；本轮未重跑任何外部模组回归。
- **需要真实存档的**：4096预热工作集、真实缓存容量压力及NoiseChunk持有、实际磁盘争用/卸载等待、旧路重铺和跨实例并写；受控存储与强制一级驱逐不代替这些。
- **怀疑但没有证据的**：列对象重复生成和HashSet尝试在现场的占比、采样端到端节省多少时间、同步READY/施工台账写盘是否为主要卡顿源，均未量；不据此改异步或预算。
- **已知边界未改的**：方向原型慢、没有任意非凸度量收敛与复杂地形召回证明，明确不用于生产；逐站pavementEntries不是唯一列/方块写入，COMPLETED是当前施工函数结案而非可通行性实测。NOT_ATTEMPTED不代表已从台账验证完工。只有旧数据无owner时标missing-owner-pair，无法与粗端点自动关联。ID在相同维度/种子/端点的存档副本相同，汇总须区分日志会话；它不是持久唯一身份。crossing、双平滑默认、长桥正弦、村庄包围盒、spanWriteback/QualityGate默认及所有存档协议保持。

### 四、遗留半截的东西

五项均有本轮可编译交付：性能有数据、拆分有等价提交、采样缺口有先红后绿、方向有明确不采用结论、证据有生产接线和窄守卫；没有未恢复变异或半写源码。真实数据采集、证据汇总工具及按可建性真正换边属于下一阶段，没有伪装成本轮完成。代码小步提交；文档提交后正常push，最终复核工作区空和origin/main对齐。未制作发布件，不新增虚假RELEASES记录。

### 五、下一轮我会先做什么

1. 继续离线采样压力：补只读网格部分前缀填充交错、真实容量驱逐及关闭/等待的组合，不把版本号当命中。再按实际重复路径决定是否扩大复用。
2. 宽度探针量重复列对象分配；如果仍占主导，再试只作查询的可变坐标，最终存入集合仍不可变且保持原插入/输出顺序，分配与耗时都要对照。
3. 可建性先做按ID聚合日志，区分粗代理、终态校验、区块放弃和实际完成；收到存档样本前不换边。方向原型先补复杂地形/收敛与局部求值成本，未优于当前实现前不接生产。
4. 继续分离TargetY旧模式编排时先冻结产物；READY/台账异步、4096与三模组整链、crossing和双平滑默认继续等现场证据。

### 六、我踩到但已解决的坑

Windows Python写文本默认会变成CRLF，新Java必须显式newline='\n'；宽度第一次完整回归因此只被行尾守卫拦住，修正后1516全绿。迁方法要补真实静态工具宿主，不能假定同名方法仍在Road或Service；初次编译错误均修好后才计回归。架构棘轮要求减少也登记，删除未用导入后基线只能收紧，不能用放宽预算换拆分。只测短路线看不到127号之后的站点；仅验证日志格式又看不到生产从未调用，因此补长路线对拍和AST窄接线。只读盘缓存命中也是发布，忽略它会让过期空快照重复计算；关闭测试必须证明等待者先于阻塞所有者结案。方向场值更准确但路线不变且更慢，必须如实保留不采用结论。

## 第44轮 ｜ 2026-11-17 ｜ 五项后续代码工作 ｜ 源码/测试HEAD `d3b291b` ｜ `PASS tests=1570 skipped=0 aborted=0 failed=0`

### 一、改了什么

| 文件/提交 | 改动摘要 | 行为变更 / 行为等价 |
|---|---|---|
| AccurateHeightSampler、AccurateColumnBatchTest；33e9583 | 门面单列/批量只读入缓存后共享发布；补前缀、真实容量驱逐和反向关闭 | 行为变更：避免旧未命中重复整网格计算，不改三通道值 |
| PathWidthRasterizer 112/91行、Round43WidthRasterReference及探针；9406227 | 可变坐标仅查询，新列才分配不可变对象；保留集合插入及输出顺序 | 性能、行为等价 |
| scripts/evidence两个脚本；0b4b1dc、d3b291b | 按会话/ID/指纹汇总实际日志，区分校验、放弃、完成、未尝试和缺失；修复真实detail嵌套字段误扫 | 工具新增及工具行为修复，生产决策等价 |
| TargetYComputation 395/274、LegacyTargetProfile 138/124、独立冻结参照；ab06e08 | 保留模式编排与世界操作边界迁出；相邻EndpointProfileOps清除三条实际未用导入，基线不放宽 | 行为等价，1549全绿后单独提交，再补守卫 |
| LegacyTargetProfileTest/WiringTest、测试套件；44d3583、6d9432b | 八组产物、锁高、尖峰、端口顺序与异常直接实测；AST拒绝false假接线 | 测试基建 |
| DirectionalLocalCost/StudyTest、冻结候选与探针；e7de0fe | 每节点三次度量取值缓存；旋转山地全场对拍、解析面细化和真实分配/求值计数 | 测试目录实验，误差容限内等价；生产不采用 |
| .gitattributes/.gitignore；7f65f65 | Python工具固定LF检出，解释器缓存不进源码 | 构建行为等价，保证逐字恢复 |

行数为物理/有效。701个生产Java，有效行>1000为0、>800为2。旧能力和所有存档协议/配置键/持久枚举保持；没有改相邻河流、长城或调度模组，没有打包部署。

### 二、我验证了什么（附负控）

开工status空，pull --rebase最新，HEAD4dc3ef0；先跑1543全绿和Fabric。门面只读两参数用例修复前失败2项；部分前缀、生产单桶16384容量真实驱逐后查盘重载、列所有者关闭唤醒网格均有直接断言。宽度三方有序对拍先于计时；2048站相对上一轮每次分配5348960→1173608 B（约减78.1%），p50 13.0233→12.5763 ms；128站0.9545→0.9920 ms，不宣称短路也提速。细节见VALIDATION。

旧模式单独迁移1549全绿提交后才补12项；冻结参照不调用新编排，但共享未改底层grade算子，世界真实读取不在其覆盖内。方向30°山地冻结候选11.0761→3.0515 ms，生产0.2121 ms；梯度224859→1872，整场差约1.4e-14。三个解析面4/2/1细化误差逐步下降，不是一般收敛证明，仍不接生产。Java新增27至1570，独立Python13项；真实Java候选回调/格式器到Python的CLI汇总实际通过，受控输入不冒充真机日志。

23组变异均实际红并finally精确checkout、逐字比较；14组Java共21个目标失败，9组Python共14个断言失败，均不是编译失败。2组生产变异只由AST接线守卫抓住，另2组是测试目录候选，9组是工具，分类不混淆。

| # | 故意改坏什么 / 记录名 | 目标守卫 | 失败数 / 结果 |
|---|---|---|---|
| 1 | 只读网格部分前缀返回前漏发布：`partial-prefix-publication-missing` | `pathfinding.sampling.AccurateColumnBatchTest#partialReadOnlyPrefixInvalidatesAnOlderWholeGridMiss` | 1，红 |
| 2 | 门面批量只读填充漏发布：`facade-batch-publication-missing` | `pathfinding.sampling.AccurateColumnBatchTest#facadeReadOnlyFillInvalidatesAnOlderWholeGridMiss(boolean)` | 1，红 |
| 3 | 门面单列只读填充漏发布：`facade-single-publication-missing` | `pathfinding.sampling.AccurateColumnBatchTest#facadeReadOnlyFillInvalidatesAnOlderWholeGridMiss(boolean)` | 1，红 |
| 4 | LRU永不驱逐：`lru-eviction-disabled` | `pathfinding.sampling.AccurateColumnBatchTest#realShardCapacityEvictsLeastRecentAndReloadsWithoutBackendOrSave` | 1，红 |
| 5 | 关闭只清表不唤醒网格等待者：`close-does-not-wake-grid-waiter` | `pathfinding.sampling.AccurateColumnBatchTest#closeDuringActiveColumnsWakesGridWaiterBeforeOwnerReturns` | 1，红 |
| 6 | 把可变查询键直接存入集合：`mutable-query-inserted-into-set` | `postprocess.PathWidthRasterizerContractTest#denseRetracedColumnsKeepOrderedImmutableProductsAcrossCalls` | 1，红 |
| 7 | 交换交叉与海底世界操作：`legacy-world-pass-order-reversed` | `pathlogic.core.LegacyTargetProfileTest#representativeModesMatchFrozenOrderedProductAndPortInputs(int)` | 8，红 |
| 8 | 丢弃海底端口返回剖面：`legacy-sea-result-discarded` | `pathlogic.core.LegacyTargetProfileTest#worldPassReturnedProfileIsUsedAndSingleTunnelSpikeIsRemoved` | 1，红 |
| 9 | 删除末次端点锁高：`legacy-last-locks-disabled` | `pathlogic.core.LegacyTargetProfileTest#lateWorldPassCannotEraseVillageOrSharedApproachLocks` | 1，红 |
| 10 | 删除隧道去尖峰：`legacy-tunnel-spike-pass-skipped` | `pathlogic.core.LegacyTargetProfileTest#worldPassReturnedProfileIsUsedAndSingleTunnelSpikeIsRemoved` | 1，红 |
| 11 | DP模式分派条件颠倒：`legacy-dp-dispatch-reversed` | `pathlogic.core.LegacyTargetProfileWiringTest#legacyRunsOnlyInDpAbsentBranchAndRetainsRealWorldPortOrder` | 1，红 |
| 12 | 真实交叉调用仅放在false分支：`legacy-crossing-unreachable` | `pathlogic.core.LegacyTargetProfileWiringTest#legacyRunsOnlyInDpAbsentBranchAndRetainsRealWorldPortOrder` | 1，红 |
| 13 | 方向交叉项强置零：`direction-cross-term-dropped` | `metric.DirectionalCandidateStudyTest#localQuadraticRetainsObstaclesScaleAndAllDirectionSigns` | 1，红 |
| 14 | 每次更新重建局部代价缓存：`direction-local-cache-disabled` | `metric.DirectionalCandidateStudyTest#gradientSamplingIsBoundedPerNodeRatherThanPerObjectiveEvaluation` | 1，红 |
| 15 | 跨日志会话合并：`evidence-sessions-merged` | `EvidenceSummaryTest#test_copies_with_same_seed_and_endpoints_remain_separate_sessions` | 1，红 |
| 16 | 不同施工指纹合并：`evidence-fingerprints-merged` | `EvidenceSummaryTest#test_gap_repair_fingerprint_never_inherits_old_completion` | 1，红 |
| 17 | 结果永远停留在首次观测：`evidence-first-outcome-sticky` | `EvidenceSummaryTest#test_abandoned_then_retry_complete_keeps_both_observations` | 1，红 |
| 18 | 重复日志当新完工区块：`evidence-duplicate-counts-as-new-chunk` | `EvidenceSummaryTest#test_duplicate_rotated_log_does_not_inflate_completed_chunk_count` | 1，红 |
| 19 | 无owner旧路强行合并：`evidence-ownerless-roads-merged` | `EvidenceSummaryTest#test_old_roads_without_owners_cannot_be_merged` | 1，红 |
| 20 | 阶段观测冒充整路完成：`evidence-whole-road-claimed-complete` | `EvidenceSummaryTest#test_complete_observation_chain_still_does_not_prove_whole_road` | 1，红 |
| 21 | 格式错误静默丢弃：`evidence-malformed-silently-ignored` | `EvidenceSummaryTest#test_malformed_events_report_location_and_do_not_destroy_valid_rows` | 6，红 |
| 22 | 最终校验强报成功：`evidence-validation-forged-true` | `EvidenceSummaryTest#test_latest_validation_failure_keeps_reason_and_never_means_built` | 1，红 |
| 23 | detail内嵌字段误扫为顶层：`evidence-nested-detail-parsed-as-top-level` | `EvidenceSummaryTest#test_real_coarse_detail_keeps_nested_same_named_fields_as_opaque_text` | 1，红 |

恢复后再次完整运行common回归及fabric编译，1570/0/0/0、BUILD SUCCESSFUL，日志round44-final-after-mutations.log；Python13项再次全绿，日志round44-evidence-tests.log。变异元数据/逐项日志和探针原始数据在.analysis_tmp/round44-*，文档保留可复现命令和实际数值，不依赖临时文件才知道结论。

### 三、我没能验证什么

- **需要真机的**：FPS/MSPT、GC停顿、首次生成/重开/Chunky并行、真实终态落块和河流→长城→道路最终Mixin/事件顺序；当前汇总尚无现场样本，不能证明整路可通行。
- **需要真实存档的**：4096全工作集、全采样缓存/NoiseChunk真实堆压力、实际磁盘争用/卸载/跨实例并写及旧路重铺；本轮只超生产单个列缓存桶容量，不等于全世界压力验收。
- **怀疑但没有证据的**：READY/台账同步写盘是否主导卡顿；落列减少分配对现场GC和帧时间的贡献；没有据此改异步、预算或作倍数承诺。
- **已知边界未改的**：方向候选仍慢，0°平面还增加缓存分配；缺一般非凸收敛/分页/早停/取消治理。证据依赖文件输入顺序，会话需显式划分；终态校验未携带施工指纹，无尝试ID，不能自动关联某个版本或推断尝试数。缺日志不代表没有生成，COMPLETED仍是函数结案，不是可通行性实测。crossing及几何/跨度/QualityGate默认继续保持，用户暂无真机数据。

### 四、遗留半截的东西

五项均已提交可编译结果，没有未恢复变异或半写源码。采样补守卫并修实际重复计算、落列有三方数据、工具有真实格式对照、旧模式先等价提交再补测试、方向有明确继续不采用结论。真机验收和真实日志采集未完成；没有将其计入五项离线交付。文档随后独立提交、正常push，最终复核工作区空与origin/main对齐。

### 五、下一轮我会先做什么

1. 用首次生成/重开/Chunky的独立会话日志运行证据汇总，先定位校验通过却施工放弃的真实连接，再决定针对哪条路径修复；无数据不换边。
2. 量4096完整工作集、MSPT/p95/最长等待及READY/台账采集/排序/编码/替换分阶段耗时，再决定批量、异步或并发预算；异步方案必须包含停服排空。
3. 用真实存档验证桥头/隧道/保护落块和三模组完整启动/重开/停止流程；测试仍留各自模组。没有召回/A/B数据不动crossing、双平滑、跨度回写或QualityGate默认，不继续为行数而拆类。

### 六、我踩到但已解决的坑

Python默认检出受Git autocrlf影响，即使先写LF，checkout仍可能变CRLF；第一次变异恢复检查准确拦下，固定限定脚本LF后完整重跑。迁移SpanRange时需显式导入Road伴随record，初次编译失败不计守卫。真实粗候选detail里还嵌套Choice.geodesic/selected，平面字段正则会误报重复；新增实际格式夹具先红再修为尾部原文，并跑Java真实回调→CLI端到端。候选少调用梯度不代表所有场景少分配，0°平面反例已记录；候选变快仍不及生产，不切默认。

## 第45轮：中文日志/注释、READY实盘测量与内容版本证据（2026-11-17）

### 一、我改了什么

| 范围 / 提交 | 结果 | 类型 |
|---|---|---|
| 全模块日志；df18f82、2935bae | 补中文格式说明并保留稳定诊断字段；修正CPU-only、影子角色和兼容等待措辞；覆盖工厂与别名 | 日志行为变更，规划施工行为等价 |
| FileStorageIO；bfbbc5a | 隔离成功与失败准确区分；真实文件锁、字节、日志级别/异常验证 | 日志行为变更 |
| ReadinessCoverageStore；2b4d06e、1a0d6a2 | 可选UTF-8字节及分阶段计时；复用最后成功有序快照并插入新区域，失败不发布 | 性能，产物及提交语义等价 |
| probes；cae8d1d | READY/台账真实临时文件写、重开与字节对照；ACTIVE扫描/索引对照 | 测量基建 |
| RoadBuildEvidence及汇总；957202e、181909a | c1内容摘要关联同产物校验/施工，不改道路身份或存档；隔离无摘要旧观测 | 诊断行为变更 |
| 168生产文件；e6eb2d6 | 635段原英文Javadoc补中文、23处标签补正文、52段迁出残留删除；纠正过时算法/锁/缓存/持久说明 | 注释行为等价，注释外内容一致 |
| 中文日志/文档守卫；55e233c、e55d558 | 编译器扫701文件、842日志调用、3262成员Javadoc；正反夹具，显式登记suite | 测试基建 |

细节与稳定标记入口为LOGGING.md，不另建重复日志清单。有效代码超过1000为0、超过800为2；物理行也全部≤1000。没有修改CODEC/schema/配置字段/持久枚举或其他模组，没有打包部署。

### 二、我验证了什么（附变异）

开工工作区空，1570全绿、Fabric通过；pull --rebase遇GitHub443超时，交付前再试并如实记录远端状态。新增20项Java至1590，零skip/abort；Python从13增至17项。恢复变异后的完整日志round45-final-after-mutations.log同时含PASS与BUILD SUCCESSFUL。

READY冻结2b4d06e对拍当前，预置/空表各96随机步含负坐标、故障、重复、重开，每次字节一致。实盘3预热9样本/512MiB，65538条构建p50 11.6632→4.7581ms，其中排序/插入8.2096→0.0510ms；写3.9516→3.8552ms。1024连续增长仍1024写，累计写1550.632→1558.322ms，不宣称磁盘提速。台账512/8192/65536条写p50 2.5158/3.8349/5.3451ms，p95 3.0649/4.7780/7.7244；不是现场争用或fsync保证。ACTIVE单世界8活动扫描0.02181微秒/次，决定不做世界索引。复现命令和完整阶段数值见VALIDATION第45轮。

实际Java粗候选回调/格式器→Python CLI通过：3个ID、2个content，新改高内容不继承旧校验；保留旧ABANDONED→COMPLETED，整路not-proven。注释外比较168个生产文件一致；编译器3262处正文中文全部通过，不声称所有成员均有独立文档或每句语义已证明。

| # | 故意破坏 | 对应守卫 | 失败数/结果 |
|---|---|---|---|
| 1 | Fabric日志删中文 | ChineseLogGuardTest全项目 | 1，红 |
| 2 | 动态日志无中文说明 | ChineseLogGuardTest反例 | 1，红 |
| 3 | 仅异常/参数带中文 | ChineseLogGuardTest反例 | 1，红 |
| 4 | 解析器错误计入参数中文 | ChineseLogGuardTest反例 | 1，红 |
| 5 | 不识别Logger别名 | ChineseLogGuardTest别名/工厂 | 1，红 |
| 6 | 不识别LoggerFactory直接调用 | ChineseLogGuardTest别名/工厂 | 1，红 |
| 7 | READY插到错误排序位置 | ReadinessOrderingReuseTest | 2，红 |
| 8 | READY写前发布排序快照 | ReadinessOrderingReuseTest | 2，红 |
| 9 | 字节数错用字符数 | ReadinessCoverageStoreTest | 1，红 |
| 10 | 写失败仍报written=true | ReadinessCoverageStoreTest | 1，红 |
| 11 | 隔离成功错误报失败 | FileStorageLogTest | 1，红 |
| 12 | 内容摘要忽略targetY | RoadBuildEvidenceTest | 1，红 |
| 13 | 内容摘要忽略跨度类型 | RoadBuildEvidenceTest | 1，红 |
| 14 | 内容摘要忽略坐标Y | RoadBuildEvidenceTest | 1，红 |
| 15 | 删除分站铺装列长度前缀 | RoadBuildEvidenceTest | 1，红 |
| 16 | 忽略材料状态属性 | RoadBuildEvidenceTest | 1，红 |
| 17 | 最终校验日志漏content | RoadBuildEvidenceTest | 1，红 |
| 18 | 强合并不同content | Python EvidenceSummaryTest | 1，红 |
| 19 | 新content继承旧校验 | Python EvidenceSummaryTest | 1，红 |
| 20 | 隐藏无版本旧观测 | Python EvidenceSummaryTest | 1，红 |
| 21 | 接受畸形content | Python EvidenceSummaryTest | 3，红 |
| 22 | API成员文档删中文 | ChineseCommentGuardTest全项目 | 1，红 |
| 23 | Fabric成员文档删中文 | ChineseCommentGuardTest全项目 | 1，红 |
| 24 | 公共算法构造器正文删中文 | ChineseCommentGuardTest全项目 | 1，红 |
| 25 | 文档解析错误计入参数标签 | ChineseCommentGuardTest反例 | 1，红 |

共25组、29个目标断言失败；其中21组Java、4组Python。全部精确checkout、逐字恢复，编译失败不充红。记录round45-mutations.json及round45-comment-mutations.json，逐项round45-mut-*.log。两个最初无效变异未计入：参数toString仍Unicode转义无法引入中文，改为读取参数描述后才红；分站长度改0仍有分隔，改为删除长度调用才红。

### 三、我没能验证什么

- **需要真机的**：FPS/MSPT、GC、首次/重开/Chunky加载尾延迟，真实桥头、隧道和保护列落块，河流→长城→道路最终Mixin与事件顺序。受控Java格式器不等于真实ServerLevel执行。
- **需要真实存档的**：4096完整工作集、NoiseChunk与全部缓存堆压力、磁盘争用/卸载等待/跨实例并写。临时文件数据不够决定台账异步，未修改同步成功后发布契约。
- **怀疑但没有证据的**：现场写盘是否主导卡顿、摘要整路扫描在诊断开启时的开销、中文日志对实际排障速度的改善，均无真机量化。
- **已知边界未改的**：摘要不含世界/配置/模板，按诊断计算非零成本；无日志不代表未生成，COMPLETED不是可通行实测。Javadoc守卫只查附着正文是否有中文，不强制每个成员文档，也不证明语义；普通行注释和资源脚本未逐句穷尽核实。positions仍未深拷贝，按内容缓存的调用方必须保持发布后输入不变。crossing、双平滑、长桥弯、包围盒裁切、spanWriteback/QualityGate默认、CPU-only保持。

### 四、遗留半截的东西

源码、测试和探针已小步提交且可编译；全部变异恢复，无半写实现。未将现场验收计入完成。中文职责头/已有成员文档/日志完成机械全扫及本轮重点语义校正，“每句旧行注释都已证明正确”没有完成，也不据此加几千段泛泛注释。远端同步结果见本节末尾，不将网络失败隐藏为已push。

### 五、下一轮我会先做什么

1. 收集独立会话的真机首次、重开、Chunky日志，用content定位同产物校验通过后实际放弃的位置；不能用身份指纹继承旧校验。
2. 量真实4096工作集、MSPT/加载尾延迟、READY与台账分阶段；先判主要开销再决定合并提交或异步，异步必须保留故障/停服排空契约。
3. 修改代码时继续同步核对相关行注释和调用契约，优先线程/持久化/保护；不为了“全中文”机械给getter或每行赋值堆注释。无召回/A-B样本不动用户要求保留的几何和模式。

### 六、我踩到但已解决的坑

Javac的DocTree.toString会把中文转义，必须扫描TextTree.getBody原文；中文参数标签不能替代契约正文。首次扩展中文说明造成一处物理1021行，被已有守卫拦下，移除中英重复后928行，未放宽约束。原拆分有52段注释挂到下一成员，不能只翻译继续保留。UTF-8探针误按GB18030解码直接失败，按实际UTF-8重跑后errors=0。临时PowerShell/Python脚本须断言锚点，壳层转义错误不算测试红。

远端同步记录：收尾 git pull --rebase 成功（Current branch main is up to date）；随后 push 因 github.com:443 在21080ms后连接失败。本地提交待推送，工作区空；再次push遭连接重置（Recv failure: Connection was reset）。最终交付明确说明未推送，不声称origin/main已对齐。


## 第46轮：五项优化与旧版功能重新核对（2026-11-17）

### 一、我改了什么

- 5d5b0c1：分站铺装列表、中心和跨度端点冻结（含MutableBlockPos），外部工作对象不再改变已发布道路；非法空值继续交校验器诊断，CODEC/schema不变。校验改有界弱身份缓存；合流三路缓存保留线程隔离但键不再强持有整路，派生值不反向持有RoadData。新对象独立校验，失效可安全重建。
- 同提交：回填在区块间加默认4ms预算，保留8块上限；延迟施工只给已尝试块扣重试，手绘保留未尝试块票据，下一tick重查FULL。已结案前缀不能饿死首个未结案块。几何、落块顺序、台账提交契约不改，推进时机改变。
- 4ea5879：证据summary可选限历史样本，保留总计数、最新失败、版本隔离和区块结果；detail仍默认完整。
- 5eca1e8/d89696f/12e200b：逐块可选耗时及异常状态、生产预算AST接线与默认预算/饥饿守卫；弱缓存复用查询键消除32B/get分配，finally释放临时强引用；缓存回收重建产物对照。
- ed14c0c/846310e：冻结旧版外部契约、反例夹具和CI；旧API25文件token一致、非语言资源82文件一致、注册和语言保留键通过。旧38个删除路径全量核对，结果并入FEATURE-PARITY-CHECK；纠正仍写KNN与CLASSIC不参与新内核的过时注释。无邻仓改动，没有打包部署。

### 二、我验证了什么（附变异）

开工工作区空，git pull --rebase成功，1590全绿、Fabric通过。最终完整回归1600/0/0/0与BUILD SUCCESSFUL；Python证据19项、契约拒绝2项，冻结旧版契约errors=0。生产源码继续有效代码≤1000，中文日志/附着文档和架构守卫同套运行。精确最终日志round46-final-after-mutations.log。

离线性能：8192站内容键get p50 116143.7ns→弱身份19.0ns，两边get分配0B；32/1024站26.0/18.6ns。五列分站冻结增加一次构造成本，29.8ns/24B→85.3ns/131.9B。三预热九组交替缓存探针、分配和完整数值见VALIDATION。重复5万日志detail峰值50427919B，summary16为41041B，计数一致；道路/版本/区块仍随规模增长。不据此承诺FPS、MSPT或加载倍数。

| # | 故意破坏 | 目标守卫 | 结果 |
|---|---|---|---|
| 1 | 中心保留MutableBlockPos别名 | RoadPublicationTest发布冻结 | 1断言失败 |
| 2 | positions保留调用方列表 | RoadPublicationTest发布冻结 | 1断言失败 |
| 3 | 跨度起点保留游标 | RoadPublicationTest发布冻结 | 1断言失败 |
| 4 | 弱键队列不排空 | WeakIdentityCacheTest实际入队 | 1断言失败 |
| 5 | 存储键调用内容hashCode | WeakIdentityCacheTest禁止内容哈希键 | 1断言失败 |
| 6 | 时间预算永不耗尽 | DeferredChunkQueueTest假时钟 | 1断言失败 |
| 7 | 预算等待当已尝试 | RoutePreloadWorkTest保票/单次尝试 | 1断言失败 |
| 8 | 复用查询键不清强引用 | WeakIdentityCacheTest反射查询键 | 1断言失败 |
| 9 | 生产回填传null预算 | ConstructionBudgetWiringTest解析真实方法 | 1断言失败 |
| 10 | 默认预算Long.MAX_VALUE | DeferredChunkQueueTest默认4ms | 1断言失败 |
| 11 | 已结案前缀耗尽预算饿死施工 | DeferredChunkQueueTest首个未结案 | 1断言失败 |
| 12 | 校验缓存从不读取 | RoadSnapCacheLifecycleTest真实校验实例 | 1断言失败 |
| 13 | summary不截历史 | Python摘要计数/尾部对照 | 1断言失败 |
| 14 | summary失败计数恒0 | Python最新失败/计数 | 1断言失败 |
| 15 | 错误总数冒用样本长度 | Python限样本准确计数 | 1断言失败 |
| 16 | 契约核对跳过资源 | Python四类破坏拒绝夹具 | 1断言失败 |
| 17 | 契约忽略缺失语言键 | Python四类破坏拒绝夹具 | 1断言失败 |

全部精确checkout并与原字节确认恢复；17组均为实际断言红，无编译失败充数。元数据round46-mutations.json/round46-extra-mutations.json/round46-python-mutations.json/round46-parity-mutations.json；逐项round46-mut-*.log。没有依赖GC时序：主动clear/enqueue真实WeakReference检查队列路径，但真实GC负载另待现场。

### 三、我没能验证什么

- **需要真机**：首次/重开/Chunky、FPS/MSPT、单块最长回填、桥头隧道及保护列最终落块，河流→长城→道路完整Mixin/事件顺序；新CI尚未在远端执行。
- **需要存档**：4096真实工作集/NoiseChunk堆与磁盘争用、旧档重开全部能力；4ms只约束区块间让出，不限制单块、选择或flush，不承诺不掉帧。
- **怀疑但无证据**：现场卡顿是否主要是单块施工或台账；弱缓存真实命中率与锁争用；没有因此改异步或细切单块。
- **已知边界**：冻结增加列表复制成本；诊断内容摘要仍整路计算；summary保存全部唯一道路/版本/区块；旧算法选择退役、换实现不等于逐种子产物一致。crossing暂无用户数据，双平滑、跨度/QualityGate默认、CPU-only、长桥弯与包围盒不动。没有发现新的已证实旧功能缺失，不声称完美重构。

### 四、遗留半截的东西

五项均有代码、守卫和测量结果，旧版对照与可重复契约工具均交付；临时变异全部恢复，无半写源码。真机验收和外部模组实际运行未完成，独立列出。最终远端同步状态见本节末尾，不把网络失败写成已推送。

### 五、下一轮我会先做什么

先收首次/重开/Chunky独立会话日志：逐块replayUs、flush分段、MSPT和4096工作集。单块主导才设计可恢复施工切片（随机数、灯位、台账原子终态须保留），磁盘主导才讨论合并/异步及停服排空。按新冻结契约工具继续查资源/API退化，真机对照30场景组；无召回/A-B不切crossing或几何默认。

### 六、我踩到但已解决的坑

新增Java由PowerShell/Python写文件要显式newline=LF，换行守卫实际拦到CRLF。弱查询键初版每get32B，经分配探针发现并改复用；finally不清会强持有道路，新增反例实际红。新增桥跨尖峰测试错误假设校验器必拒绝，被正向回归拦下，改为明确target-height-size非法输入，不改变生产几何。初次抽共享util触发依赖棘轮，明确记录postprocess/generation各新增一条单向基础依赖、pathlogic增加一次，提交说明原因，没有给反向依赖开白名单。

远端同步：开工pull成功；收尾pull与push的GitHub HTTPS请求长时间挂起，终止对应网络子进程，未获得成功确认。加超时重试pull报github.com:443在21070ms后无法连接。代码和文档均在本地提交，最终状态见交付回复，不声称已推送。


## 第47轮：共桥候选算法、摘要缓存与冻结对象复用（2026-11-17）

### 一、我改了什么

- 624110f：诊断c1内容摘要改有界弱身份缓存4096；冷计算锁外、并发首次允许重复，不反向持有道路。编码逐字不变，新RoadData单独计算，不改持久身份和schema。
- fad9fce/ad5f0e1：新增99行BridgePairIndex，只做包围盒宽阶段。512桥以上以64对固定样本估计密度，命中超过8则直接枚举；树查询按原桥下标排序，保留等长选主与每路只吸收一次。DEBUG保持全对日志。极端整数输入不做空间剪枝以保留旧int判据。BridgeShare真实产物对拍冻结8f3cb2a全对实现，不改精判/几何。
- 同提交：存储冻结、路口锚点和影子快照复用已冻结分站，不再重复复制铺装列；调用方可变输入仍在模型构造器冻结。存储null列列表归一、观测null元素拒绝和外层隔离保持。
- 084341f：探针三策略共用枚举内核，避免调用点JIT差异误判；并发摘要改测独立冷实例，补非法观测列，新增ShardStampProbe复现现存统计戳盲点，纠正“任意修改都会改变戳”等错误注释，未改该存量验证策略。
- ROADMAP新增五个有前置条件的后续任务；ARCHITECTURE/STATUS/LOGGING/VALIDATION同步。主架构保留FMM/DP/弹性带与稀疏拓扑，下一步优先分片验证、可恢复单块施工与只读派生数据，不凭文件行数或公式复杂度整体换引擎。

### 二、我验证了什么（附变异）

开工main@8f3cb2a干净，pull --rebase成功，1600/0/0/0、Fabric通过。最终完整回归1610/0/0/0、Fabric编译BUILD SUCCESSFUL（round47-delivery.log）；Python证据19项、旧版契约拒绝2项通过，独立旧版契约errors=0。704个生产Java物理最大998，物理和有效代码均无千行超标；中文日志/附着文档、架构及回归登记守卫同套运行，未关闭守卫。新增缓存引用明确登记一条pathlogic→util基础依赖，无反向依赖。

实际100组100盒×5半径逐对对拍，负坐标、临界闭区间、长盒、密集、极端int均覆盖；512条道路的真实吸收与冻结全对产物/Stats一致，局部密集确有吸收。BVH稀疏1024盒检查523776→3584，候选逐项不变；同内核含建树查询排序p50稀疏377.48→92.31微秒、8座分簇413.69→131.69微秒；密集1305.56→1304.49微秒（自适应不建树）。强制树反而13.25ms/长桥47.48ms，所以不普遍建树。密度固定探测可误判异构输入的耗时，不影响候选正确性，无任意分布收益保证。

8192站×5列诊断摘要未缓存p50 2172.39微秒/459528B，热缓存0.0586微秒/0B。首次/驱逐仍全路算，诊断默认不开。复制分站环节单组均值335.13微秒/1409064B，复用31.05微秒/32840B；含新外层列表，不当稳定p95。完整数据/命令见VALIDATION，round47-final.log。全部为合成纯内核数据，不是FPS、MSPT或进档倍数。

| # | 故意破坏 | 目标守卫 | 结果 |
|---|---|---|---|
| 1 | 摘要缓存不读 | RoadBuildEvidenceTest缓存重复命中 | 1断言红 |
| 2 | BVH半径闭区间改开区间 | BridgePairIndexTest跨叶半径边界 | 1断言红 |
| 3 | 候选输出逆序 | BridgePairIndexTest随机全对序列 | 1断言红 |
| 4 | 树节点不剪枝 | BridgePairIndexTest稀疏操作数 | 1断言红 |
| 5 | 索引候选全丢 | BridgePairIndexTest生产吸收对拍 | 1断言红 |
| 6 | 密集输入也强制建树 | BridgePairIndexTest策略选择 | 1断言红 |
| 7 | 生产不接sparseBatch | ConstructionBudgetWiringTest编译器AST | 1断言红（静态接线） |
| 8 | 存储仍重复复制冻结分站 | RoadSnapshotReuseTest引用/内容 | 1断言红 |
| 9 | 丢存储null列表归一 | RoadSnapshotReuseTest非法输入 | 1断言红 |
| 10 | 锚点重复复制分站 | RoadSnapCacheLifecycleTest实际锚点 | 1断言红 |
| 11 | 影子观测保留可变外层别名 | RoadSnapCacheLifecycleTest快照隔离 | 1断言红 |
| 12 | 影子观测接受null铺装元素 | RoadSnapCacheLifecycleTest拒绝契约 | 1断言红 |

12组均精确checkout并逐字确认，编译失败不算红；round47-mutations.json（11）、round47-extra-mutations.json（1）及逐项round47-mut-*.log。第2组最初全绿：两个临界桥同叶，错误只在节点剪枝而未触发；增9盒跨叶夹具后真正红。第4/6/8/10是操作数/策略/复用性能契约，不能混称错误真机产物。初次新测试登记脚本锚点断言失败，登记守卫实际拦到漏注册，修正后1610全部执行。

ShardStampProbe真实调用computeShardStamp/matches：两个文件时间1000/3000，第一同长改写且时间变2000，统计戳仍一致；输出STAMP_BLIND_SPOT contentChanged=true maxMtimeUnchanged=true aggregateAccepted=true。临时四路径逐个清理。已证明统计提示非内容证明，但未声称玩家道路已丢失，当前查询从道路NBT重建内存索引。

### 三、我没能验证什么

- **需要真机**：FPS/MSPT、首次/重开/Chunky加载尾延迟，512以上桥在真实种子中的比例与密度，保护列/桥头/隧道最终落块，三模组最终Mixin/事件顺序。未打包部署。
- **需要存档**：4096完整工作集、NoiseChunk堆、磁盘争用、旧档全部可见功能及真实跨实例并写，单区块施工最长时间和暂停后随机序列。
- **怀疑但无证据**：单块施工、台账还是采样是否主导现场卡顿；固定密度探测在真实异构桥群的误判率。没有据此加线程或改异步。
- **已确认未修**：RoadChunkStore统计戳会碰撞、不能保证任意内容变化可检出；本轮仅探针和注释纠正，真实改分片→重开→修复行为未补。forceMetadata路径引用相同时是否仍跳过重写已在代码看到，实际故障后结果尚未复现，列路线首项。不能以性能优化之名隐去存量缺口。
- **保留边界**：摘要不含世界/配置/模板且冷算有成本；密集候选仍二次，BVH建树O(n log²n)。4ms仍仅区块之间让出；单块、选择、flush无硬上限。crossing暂无召回数据按用户要求保留，双平滑、长桥正弦、包围盒、CPU-only与跨度/QualityGate默认不动。

### 四、遗留半截的东西

三项优化均已实现、对拍、测量并提交；临时变异恢复，没有半写源码。五项下一阶段任务仅有清晰步骤/验收，尚未实施，不计为完成。存量统计戳缺口明确保留待下一轮修复，不能把探针绿当修复完成。远端同步结果见本节末尾。

### 五、下一轮我会先做什么

1. 先围绕真实分片故障补重开/强制修复回归，分离验证结果与修复发布；保留只读降级和schema，量多万分片冷/热启动再选方案。
2. 从PathFeature五类span mask、targetY拆箱、copyForUser和HeightProfile分开量，只有纯输入部分复用，世界相关数据不进共享缓存。
3. 按ROADMAP把单块执行状态先等价迁出，录写块顺序/随机/装饰/拒绝，再讨论可恢复游标。等单块replayUs真机证据才接生产暂停，不能把时间预算当强制中断。
4. 收现场首次/重开/Chunky独立会话与4096工作集，继续按旧版冻结契约核对退化，仍不声称完美重构。

### 六、我踩到但已解决的坑

一律BVH在密集/长桥样本明显变慢，改保守规模+密度策略；三个独立枚举方法产生JIT对照差异，改共用内核再量。半径测试同叶导致变异全绿，新增跨叶夹具；新类漏登记被已有守卫拦下后改明确selectClass。复用快照初版会放宽null元素拒绝，复核旧List.copyOf语义后补显式检查与第12组负控。BuildFrame仍写上一轮“positions未深拷贝”，查当前发布契约后纠正；存储统计戳说明将统计提示夸成内容证明，也已按最小复现改正，没有掩盖未修策略。

远端同步记录：开工pull --rebase成功；收尾pull报Recv failure: Connection was reset，push报github.com:443在21067ms后无法连接。未获得推送成功确认；本轮代码/测试/文档均在本地提交，最终git status --porcelain为空，不声称origin/main已对齐。
