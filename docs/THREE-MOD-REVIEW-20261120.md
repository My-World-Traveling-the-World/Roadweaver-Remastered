# 河流、长城、道路联合源码复查与后续顺序

日期：2026-11-20。范围：启动前置、运行期预热、施工预算、局部算法、API和职责边界。本轮是审查和复现，不修改生产行为，不把待办写成已经完成。

## 后续实施进度（2026-11-20，覆盖下方历史待办状态）

下文保留原审查时的源码现象和基线，不能作为当前缺陷清单。实施以各仓HANDOFF及证据为准：

| 项目 | 当前交付 | 提交与证据 |
| --- | --- | --- |
| 1 会话归属 | 已接河流生产，旧回调隔离、进度所有者、排队取消、幂等模式租约 | f94507f；河流startup-session-mutations-20261120.json |
| 2 原地失败重试 | 已接真实worker，有界退避、撤回/失败/拒绝区分、停止清理 | 7417352；runtime-retry-mutations-20261120.json |
| 3 区块计划缓存 | 已替换为32项完整键/引擎身份表；对角32项全部命中；含删除/代次及随机oracle | c29c2f9；chunk-plan-cache-mutations/benchmark-20261120.json |
| 4 细湖触及掩码 | 已接生产，稀疏局部清理、密集批量清理；提前返回/异常归还 | 0afeca8；fine-mask-mutations/touched-mask-benchmark-20261120.json |
| 5 大类阶段拆分 | 阶段完成：细湖、原始堆、链编号；其他大类及1247行build尚未拆完 | c540df5、eac79a1；冻结湖形、旧链与PriorityQueue对拍 |
| 6 公开契约 | 提供方RiverPlanApi v1、道路新契约/旧版/缺席适配、长城非阻塞失败状态；真实named/intermediary消费者验证 | 河流4ec0b9b、道路a235863、长城92425c47；三仓API证据 |
| 7 外层施工事务 | 两仓离线恢复/取消边界已接同步生产门面；道路960组、长城288组独立冻结对拍；仍未启用跨tick | 道路8bc918b、长城d8364723；各仓transaction/settlement-mutations-20261120.json |

最新完整回归：河流63、道路1767、长城988，失败/错误/跳过（道路含aborted）均0。不是把冻结对拍组合数计成JUnit项数。道路另复现并修复“预热即时失败在入表前清理，失败Future留表导致重试复用失败”的竞态；详见HANDOFF第83轮，新增4项真实协调器时序守卫。

缓存探针证明消除了方向性容量坍缩，查找内核本身并非普遍更快；细湖密集夹具曾否定纯局部清理，最终采用混合策略。保留原始微基准，不把调用/分配变化换算成游戏提速倍数。

剩余验收：真实新建/重开/退出和三模组联合启动，4096工作集，施工/写盘p95与最长单次，模板实景。先量真实阶段尾延迟再决定跨tick接线；现有同步入口和区块票据归属保留。河流BiomeColors三条重映射告警尚未调查；构建成功不证明所有Mixin运行时成功。crossing、双平滑、长桥正弦和持久化格式未改。没有部署；河流无remote，另两仓push仍受此前自动审批拒绝阻挡，未绕过。

## 基线和结论

| 仓库 | 开工HEAD | 本轮验证 |
| --- | --- | --- |
| streams-reflowing-src | 82cc818 | 实际重跑test，20项，失败/错误/跳过0；compileJava成功 |
| plaincraft-great-wall | 02230ef8 | 实际重跑test，982项，失败/错误/跳过0；compileClientJava成功 |
| roadweaver-next | c2cfc82 | 实际完整回归1753项，skipped/aborted/failed均0；fabric:compileJava成功 |

道路、长城pull --rebase成功且没有远端新提交；河流没有remote。本轮没有新守卫、生产变异、成品部署或游戏性能测量。河流审查探针不计入JUnit数量。

本轮相邻本地审查提交：河流`6f838f6`，长城`2586f745`。两者均未改生产Java，未推送或部署。

有继续提升空间。优先修正河流任务的会话归属和失败后的重试，再处理缓存冲突与可独立对拍的局部算法。道路与长城仍需完成单块施工外层事务的可恢复化；不能用“已有游标”推断已经消除单块长帧。当前证据不支持整体替换FMM、纵断面DP或弹性带。

## 1. 河流启动回调缺少会话归属：P1，旧待办现已组件级复现

证据：

- `streams-reflowing-src/src/main/java/dev/streamsreflowing/compat/streamscontrol/StreamsPrewarmService.java:167`：每块构建回调直接改静态进度，没有判断当前WorldState或会话身份。
- 同文件179–181：allOf回调无条件恢复全局采样模式并finish进度；311–320的stop只关闭结果门、GPU采样器和清进度，不取消这些启动任务。
- `StreamsStartupProgress.java:20`、30、34：begin/setCompleted/finish没有owner参数。
- `RiverStartupReadiness.java:56`的结果门已经防止停止后重新发布ready，这一部分不能再报成未修复。

实测原进度组件：A开始1000块→清理→B开始20块→A晚到setCompleted(100)，B立刻显示20/20；A晚到finish，B的active变false。探针只替代缓存签名中的空RiverEngine类型，进度和缓存用原生产源码。没有复现Minecraft中实际退出/重开竞争，不能据此断言当前成品每次重开必错。

修法顺序：

1. 在河流仓库迁出StartupSession，拥有进度、已申请的启动模式租约和任务列表，先保持同步/异步次序。
2. begin返回会话身份，更新/结案都校验owner；旧任务只释放自身租约，不能清新进度。释放幂等，防止重复扣减。
3. stop撤销本会话尚未开始的任务；已经进入引擎共享构建的工作先定义协作停止点，不盲目中断其他等待者。
4. 测A停/B开/A回调、调度中途失败、重复释放、关闭后排队任务不再进入build；随后做真实退出重开。

采样模式的跨会话泄漏尚未动态复现，不能把进度探针当作对所有全局状态的证明。

## 2. 河流运行期预热失败后原地不重试：P1，新发现，源码路径确认

`StreamsPrewarmService.java:269-273`先按玩家中心/stride跳过，并在入队前记录中心；297只在同步判断saturated时删除中心。339–363的worker遇到构建异常、awaitPermit返回false或中途让出，只在finally删除pendingPlates；没有把对应中心重新置为待检查，也没有独立失败重试记录。

因此，入队后异步失败且没有产生saturated标记时，同一玩家不移动到stride之外，下一轮仍被中心判据跳过。失败不等于永久失去河流：真实区块查询仍可触发引擎构建。这里缺失的是后台提前预热的恢复路径，不能声称世界必然丢河。

修法顺序：

1. 将worker执行迁出最小端口，在河流仓库直接注入构建异常、让出和停止，不只测一个独立状态类。
2. 覆盖分开记录“已经检查过的位置”与“仍未就绪的板块”；失败/撤回进入有界重试，不依赖玩家再次移动。
3. 按板块退避，带会话身份、失败原因和次数；停止后取消重试，避免每次扫描重放失败风暴。
4. 验收：玩家不动，首次失败、第二次成功；重复请求只构建一次；停服后不重试；成功后不重复调度。

本轮没有完整ServerLevel故障注入，不称已交付自动守卫。

## 3. 河流ChunkPlanCache方向性冲突：P2，新发现，原组件实测

`streams-reflowing-src/src/main/java/dev/streamsreflowing/worldgen/ChunkPlanCache.java:23-24`用`(chunkX ^ chunkZ) & 31`选槽。对角线`chunkX == chunkZ`全部进槽0。

同一线程、同一引擎插入32个不同计划，再按完整key/身份读取：

| 输入序列 | 插入数 | 实际仍命中 |
| --- | --- | --- |
| `(i, i)` | 32 | 1 |
| `(i, 0)` | 32 | 32 |

这是容量利用问题，不是错命中：完整键和引擎身份仍在比较。`StreamVegetationFeature.java:122`、464与`StreamSnowCleanupFeature.java:113`会读取该缓存，miss时会沿原路径重新查询计划。实际线程迁移、特征阶段是否交错尚未测量，不能把31次miss转换成游戏提速比例。

方案：

1. 先加可关闭的hit/miss/冲突覆盖统计，区分同线程冲突与跨线程必然miss。
2. 对照完整64位混合哈希、小型组相联或32项环形替换；保留每线程上限、engine身份、clear代次和take删除语义。
3. 用负坐标、对角/反对角/轴线、不同引擎及clear/take对拍；再量实际plan调用次数、分配和耗时。
4. 只有命中收益超过查找成本才接生产，不能单凭把哈希写复杂就声称更快。

## 4. 河流细湖掩码仍每候选清全图：P2，局部算法候选

`NetworkTracer.java:178`已经复用一份boolean scratch；744、757、772会多次进入细湖候选计算。2486–2508每次仍对`lowResGrid.size()`执行Arrays.fill，再只给本候选list中的单元置true。

设图有N格，本轮有K个候选，当前仅清空就写K×N个布尔单元；候选很小时与实际工作集不匹配。这里没有测过总耗时，不能说它当前主导开局。

方案：

1. 先统计每板块N、候选个数及各list.size，不开全量逐格日志。
2. 将掩码做成单次build拥有的组件，调用结束在finally仅清本次触及的索引；或评估带代次的int标记数组。后者增加内存，不能忽略。
3. 对多次候选、重复索引、空/全图候选、提前返回和异常后的下一次调用做独立旧实现对拍。
4. 完整比较湖掩码、level/digDepth及河网几何，基准覆盖稀疏与满图。结果不变且有收益才替换。

## 5. 道路和长城的单块预算：P1后续工程，已知待办重新确认

道路：

- `LoadedChunkRoadBackfill.java:189`创建外层时间预算，190之后仍等待当前replay返回。
- 同文件191–198在回填之后同步结算与flush，不能用落块预算覆盖写盘耗时。
- `ConstructionChunkTransaction.java:39-54`同步做prepare/process、必要重编重铺、decorate、commitCompleted和finish，finally才释放保护。

长城：

- `WallBodyBackfill.java:148`为150ms，573只在下一块之前检查；597之后单次settle没有体素级让出。
- `PublishedPlanPainter.java:28`调用cursor.runAll，游标存在不等于生产已暂停。

共同方案：

1. 先用现有开关采集真实单块/阶段尾延迟，分离选块、写块、重编、装饰、元数据和写盘。
2. 纯重构外层事务，明确状态、计划修订、已写进度与保护/区块租约；worldgen同步入口仍运行至完成。
3. 冻结连续执行产物，再与任意暂停点恢复比较写入顺序、随机消费、实体、拒绝原因和完成标记。
4. 只对已加载回填接跨tick推进；卸载、换世界、计划变更和停服撤销旧任务。未结案不能记完工。

单次setBlock或实体处理仍可很慢，分片是协作预算，不是硬实时保证。是否改写盘策略继续等现场证据。

## 6. API：就绪通道已有契约，计划/形状仍需补，另缺失败状态

当前：

- `roadweaver-next/api/.../incoming/RiverReadinessApi.java`、outgoing/RiverReadinessBroadcast提供标准就绪订阅；长城已有晚订阅、重开与回调异常测试，不能再说三仓完全靠反射串接。
- 道路`compat/StreamsReflowingCompat.java`仍反射RiverEngine/ColumnPlan/Lake内部方法，缓存Method只减少查找，并未固定版本契约。
- 河流`build.gradle:17-19`与长城`build.gradle:35-38`仍从相邻旧roadweaver取API契约。路径本身不证明ABI不兼容。
- `LoadSignalBridge.java:164`只读river-ready；`RiverStartupReadiness`内部有FAILED，但对外ready布尔没有失败解释。

分两项做：

1. 提供方发布纯Java的计划/板块读取契约；定义AVAILABLE/ABSENT/FAILED及不可变数据，先验证named/intermediary与可选模组缺席、旧版本矩阵，再迁消费者。接口包不依赖提供方实现，不能新造模组实现依赖环。
2. 生命周期增加可选的会话/阶段/失败原因查询，保留旧ready入口；失败显示明确原因及重试入口，不能自动伪造ready放行。

巨树形状API是相邻后续，不属于本轮三模组源码已完成的迁移。三处用户确认保留的反射不纳入清理清单。

## 7. 架构：优先拆河流算法职责，避免整体重写

本轮逐文件数`splitlines()`，并先识别Java字符串/字符/text block再去除注释，计算非空非注释行：

| 河流文件 | 物理行 | 非空非注释行 |
| --- | --- | --- |
| core/river/NetworkTracer.java | 4163 | 4001 |
| worldgen/RiverCarverFeature.java | 3319 | 3213 |
| worldgen/StreamPrefetcher.java | 1829 | 1740 |
| worldgen/StreamsWorldgen.java | 1761 | 1676 |

这是源码职责问题，不会因补中文注释解决。特别是NetworkTracer.build从137行开始，直到1384行前才出现下个方法，单个编排也已很大。

分步顺序：

1. 从NetworkTracer迁出独立原始类型集合/堆、诊断、链编号和最终assemble；保留public门面及浮点/同权顺序。
2. 定义单板块BuildContext拥有scratch，再分洪泛/路由、湖候选精修、河链后处理、跨板连接。每次只迁一阶段，固定旧实现oracle，禁止用新函数互相比来证明等价。
3. RiverCarverFeature先迁材料/高度快照和只读列计划，再分地形雕刻、河岸铺装及清理/流场发布；原随机源和调用次序保持。
4. StreamPrefetcher按队列归属、准入/压力、构建执行拆分；生命周期修复独立作为行为变更提交。
5. 长城WallBodyBackfill现910物理行，按启动请求、运行期队列、单块结案/保护、观测分开；道路继续围绕施工事务拆，不重造另一套总调度器。

每个迁移新文件控制在1000有效行内，优先留出余量；新增/改动中文注释解释约束原因。当前不承诺把所有大类一次拆完，也不改变保存格式、几何默认或历史功能。

## 不应直接接入的“优化”

- 长城固定快照近邻树已有原型，但密集、小队列查询退化且有建树成本，不能每轮重建接生产。先量selectionUs，再决定动态索引及并列次序契约。
- 河流TASKS/OPTIMIZATION包含已实现项：空河区块提前返回、LongAdder、线程名前缀大小写、原始类型堆、ThreadLocal工作区、BandCorrection二分、细湖最低4项选择等，已亲读对应实现。不能重复计入本轮收益。
- 河流文档里的线程数提升倍数和“无需回归”不作为本轮事实。缓存盲目加大、启动并发盲目增加都可能抬高工作集。
- `WallBodyBackfill.deferGateToDrain`生产源码只有定义、没有调用，已做完整引用计数。其遗留超时COMPLETE日志不作为当前热路径Bug；旧注释不代表当前开门流程。
- 无真机召回率数据不动crossing；双平滑、长桥正弦、有限/无限预热策略及schema继续保留。

## 建议开工顺序

| 顺序 | 任务 | 类型 | 离线能交付到哪里 |
| --- | --- | --- | --- |
| 1 | 河流会话归属与幂等清理 | 行为修复 | 组件/真实worker端口守卫；退出重开仍需真机 |
| 2 | 河流异步失败/撤回后的有界重试 | 行为修复 | 原地失败后重试、停止取消、去重与退避 |
| 3 | ChunkPlanCache冲突统计与替换对拍 | 性能 | 原组件边界、计划查询数与微基准 |
| 4 | 细湖掩码按触及单元清理 | 性能 | 几何对拍及稀疏/满图基准 |
| 5 | 河流大类按算法阶段迁移 | 纯重构 | 冻结旧产物和独立阶段回归 |
| 6 | 计划API和生命周期失败查询 | 契约/行为 | 提供方和消费方各自兼容矩阵 |
| 7 | 道路/长城外层可恢复事务 | 重构后调度变更 | 先迁边界/守卫；默认启用等单块现场数据 |

## 实际执行与限制

本轮未修改任何生产Java，没有故意改坏生产代码，也没有新增JUnit守卫；不能沿用此前负控表冒充本轮交付。

河流可复现探针：在streams-reflowing-src执行`python tools/review/startup_cache_probe.py`，JDK21。输出原组件源码SHA256和两组观测，存`build/reviews/startup-cache-probe.json`；存档证据为该仓`docs/evidence/startup-cache-probe-20261120.json`。探针RiverEngine只是空类型，不能验证引擎/世界构建或性能。它固定当前缺陷现象，修复后应改为正式正反行为测试，不能纳入成功验收套件。

没有启动Minecraft，没有同种子新建/重开/Chunky联合测量、FPS/MSPT/GC、4096真实工作集或模板实景验收。三个本地回归通过不等于所有联动与最终产物“完美”。部署和现场采样应另行记录实际安装jar哈希、种子、配置及相同阶段的p50/p95/最长停顿。
