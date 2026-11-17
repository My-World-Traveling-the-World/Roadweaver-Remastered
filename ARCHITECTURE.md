# RoadWeaver 重制版架构

从实际调用入口读起；[开发现状](docs/STATUS.md)记录各环节的默认、可选和待真机行为。

| 范围 | 生产入口与职责 |
|---|---|
| 模块边界 | `api` 定义对外契约，`common` 实现共享规划、施工、持久化和客户端业务，`fabric` 接平台入口、Mixin 与发布打包。 |
| 粗路网 | `planning/impl/PipelinePlanner` 调用 `RoadPipeline.planTopology`，经 `topology` 的 `GeodesicMatrix` 求测地距离，再挑选可下发的连接。粗规划不展开每条边的施工曲线。 |
| 单路几何 | `bridge/BandPathfinder` 经 `BandRouteSolver` 用 FMM 和 `ElasticBandOptimizer` 求路径；`PathMetricsOps` 调 `postprocess/PathPostProcessor` 转成道路站与列。 |
| 目标高程和接路 | `pathlogic/core/TargetYComputation` 默认走纵断面 DP，失败时由 `ProfileFallback` 求新链路降级解；结构保护和 `ExistingRoadJunctionService` 的接路、共用及重定向有独立职责。 |
| 区块与落块 | `worldgen` 编译道路占地和区块地形；`pathlogic/PathFeature` 调用桥、隧道和普通路面施工。`generation` 负责调度、队列与已加载区块补建。 |
| 状态与协议 | `RoadData.CODEC` 定义道路数据；`RoadChunkStore` 持久化道路与分片索引；`RoadConstructionLedger` 记录完工；`WorldgenFingerprint` 是世界生成任务的版本边界。道路指纹与施工内容是否改变是两个问题。 |

## 职责拆分与局部复用

- `ExistingRoadJunctionService` 保留接路入口及世界适配；`JunctionBatchCoordinator` 承担批量选主、校验、捐赠写回和资源释放。`JunctionSpanIndex` 只在同一份道路输入的一次裁切内复用 X/Z 站号索引，不做跨道路缓存。
- `PathPostProcessor` 保留几何流水线与生产入口；`PathControlSimplifier` 负责控制点简化和原始点补回，`PathControlRelaxer` 负责限幅、急弯及桥头冻结。两者不读取世界，不改变双平滑默认值。
- `RoutePreloadWork` 分别约束 32 个在途加载和每 tick 8 个就绪区块消费；后者限制施工批量，不能保证单块施工的毫秒上限。

- `VillageEndpointFilter` 接收同次规划的结构表和路线快照，保留村内过滤与整批撤销阀；首次用到有效快照才建本轮位点索引，重复坐标取第一条有效 ID。
- `TargetYComputation` 仍协调地形与跨度；`TargetProfileSolver` 负责约束组装、DP 调用和新版回落。采样与求解有小端口，异常时保留硬约束，不改变 crossing 来源。

- `AccurateHeightSampler` 保留按世界实例的缓存和资源入口；`AccurateChunkBatch` 管整块的无冲突子集并行与查盘补采；`AccurateColumnBatch` 管逐列/网格的补缺、三通道结果验证与发布。两者经小缓存端口复用原分片与容量。
- `AccurateTileClaims` 统一列/网格的整批瓦片策略和重入保护：有冲突时不占新瓦片，等待与后端求值都在表锁外执行。普通/加速模式各有在途表；超时接管后旧所有者按 Future 身份条件释放，关闭先标记并唤醒等待者。网格碰撞后迭代重查缓存，不用递归累计争用。
- `AccurateSamplePublications` 在同一采样门面内为整块、逐列和网格共用 1024 个原子版本槽（槽位载荷 8 KiB）。请求在首次缓存扫描前观察版本，取得瓦片认领后仅当相关槽有新发布才重查缓存/存储。版本在完整填充缓存后、完成共享 Future 前记录，槽内取最大值，防止旧批次迟到覆盖新版本。哈希碰撞最多增加重查，记录本身不承载高度或承诺命中；两级缓存确实都丢失样本时仍正常重算。只读 API 或单纯从存储加载的缓存填充尚不单独记录，整块和逐列真正同时启动时仍可并行计算。
- 保存调用返回后才发布样本；端口抛错会传给等待者并允许新请求重试。`FileBackedAccurateSampleStore` 对部分 IO 故障只告警，因此不能将端口正常返回描述为持久化确认。写失败驱逐已原地修改的瓦片，下一次读取从磁盘旧版重建；文件格式和最佳努力语义保持不变。
- `AccurateStoreLifecycle` 为五个文件访问入口提供共享租约，`close` 独占排空后清理瓦片缓存及锁表。不同瓦片仍可并发；已进入的写入允许在关闭等待期间完成，关闭返回后拒绝全部新访问。同线程读锁内重入关闭明确报错，避免升级死锁。
- 门面的只读查询在入口和读盘返回后检查关闭，包括空的迟到结果。内存桶的读写都在桶锁内检查关闭标志；关闭先置标志再清空各桶，迟到发布不能绕过清空。诊断计数仍允许在关闭后读取。

## CPU执行边界

- CpuAccurateHeightBackend编排请求列及三通道产物，WorldCpuAccurateHeightSource保留原版读取，CpuSamplingWork连接执行口。BackgroundCpuWork复用RuntimeLoadPolicy已有粗采样许可；RuntimeSignalMethods隔离API调用键包装，不新增总调度器。
- 普通游玩精确批次在调用线程逐块/逐列执行，只有实际单元持许可；同步嵌套只占一份。开局/preflight保留并行。NoiseChunk当前单元未结束时不承诺立即暂停；线程许可不是毫秒预算。
- CoarsePathTasks统一首遍和共享渡口重算的提交/等待。普通父用INITIAL子池；INITIAL父用已有公共计算池，防单线程同池互等。自有FutureTask让cancel(true)中断实际线程。角色、代次和预热阶段在提交方捕获，不用物理INITIAL角色推断豁免。
- 物理队列等待保留限时；进入worker后主动等许可不耗搜索期限，获许可后开始搜索计时。不限时仍检查取消/停止/代次。失败、中断和取消都取消兄弟任务；算法实际中断尾延迟仍需真机。

## 任务生命周期与结果

- ThreadPoolManager保留角色到六个物理池的映射。LifecycleWorkerPool分别追踪已接收的PrioritizedTask和newTaskFor产生的真实Future，后者用于ExecutorCompletionService及invokeAny/invokeAll，不能只取消QueueingFuture。
- PrioritizedTask负责原优先级/FIFO、角色上下文和可取消前置闸门。停服、旧代次、用户取消或物理池关闭均拒绝新动作并结束可见结果；正常的闸门故障仍告警后放行，取消及停止不放行。
- LifecycleCompletableTask绑定真实FutureTask与对外CompletableFuture。cancel(true)先中断计算再发布取消回调，cancel(false)不打断正在执行的动作；提前complete的排队结果跳过supplier。七处地图读取、路口精修及终态道路完成链已迁到跟踪工厂。
- 角色Executor的submit/execute保留；JDK CompletableFuture.AsynchronousCompletionTask缺少公开关联结果口，现明确拒收。完成阶段使用ThreadPoolManager.runAsync/supplyAsync或ComputeService，后继异步动作用thenCompose接跟踪工厂。运行时内部入口的此项行为变更不改api模块协议；第三方直接把角色Executor传给JDK异步工厂需要迁移。
- 停服/换代先在管理锁内更新状态和引用，再锁外取消。池重建由PoolRetirements在最外层管理调用的finally排空，保证新池已发布且不持管理锁触发旧完成回调。物理池先关执行闸门，再取消结果、强停；任务完成和拒收均清理追踪表。没有新增物理线程池或总调度器。

以上保证所测任务结果的结束，不保证任意世界计算可被强停，也不把结果取消当成真实线程已退出。服务端的世界写闸门仍不可删除；真实停服重开及完成回调阻塞风险须现场验收。

## 跨模组资源协调

负载协作继续复用 `roadweaver-api`，不在道路中再建整合包总调度器。API 注册表表达意见，各消费者约束自己的任务；这不是一张能自动限制所有线程的全局许可证表。

| 相邻工程 | 已核实的职责与接线 | 道路侧的边界 |
|---|---|---|
| `plaincraft-autotune` | `PlainCraftAutoTune.onPreLaunch` 按硬件写配置；`GenerationHardwareCaps` 提供硬件档。 | 启动配置不等于游玩时并发准入；没有因此移除道路自己的执行预算。 |
| `plaincraft-chunky-autogen` | `RoadWeaverLoadSignals` 注册 `chunky-autogen`，提供 worker/lane、交互及辅助生成保护；`ChunkyTeleportLoadSignals` 另注册 `chunky-teleport`。`ChunkyRoadAwareCoordinator` 通过 readiness API 交接独占。 | 保留原 owner 与专属所有权；preflight 不咨询外部压力，独占时 readiness lane 仍能前进。 |
| `plaincraft-hyperspeed-lod-cruise` | `RoadWeaverLoadSignals` 注册 `hyperspeed`，`busy` 和 `cpuProtection` 均读原 `CruiseState` 的 CPU 保护。 | 保护按 CPU 信号消费，不把“高速飞行本身”另算一种保护。 |
| `plaincraft-twilight-teleport-intent-filter` | 注册 `twilight-teleport`，`TELEPORT.busy` 合并目的地预热与流送优先状态。 | 当前只报告 busy，没有给道路显式暂停建议；不把该状态擅自解释为 Chunky 或停掉全部道路。 |
| `streams-reflowing-src` | `PlainCraftRoadWeaverBridge` 注册生命周期监听及 `StreamsThreadControl`；`StreamsRuntimeLoadPolicy` 已遍历全部负载源，独立消费河流线程建议。 | 道路设置供应线程和独占；不直接改 Streams 内部线程池。供应值 0 表示交还策略，不等于停止河流。 |
| `plaincraft-c2me-runtime-qos` | 注入式补丁的 `RuntimeLimiter.currentProfile` 仍用内部类反射，优先级是传送、Chunky、巡航、普通；普通档另叠前台活动上限。 | 当前不能声称 QoS 已消费 API。迁移前要保留无 API 安装场景，并补齐 Gateway 信号或保留对应兼容路径。道路现有三处有意兼容反射不属本次迁移范围。 |

道路适配仍收在 `apiimpl.LoadSignalBridge`。`RuntimeSignals` 按或合并显式保护，按最小非负数合并带现场参数的 worker/lane 建议；负值弃权，0 lane 可暂停，worker 仍按原策略保留至少一条进展线程。保护来源失败只隔离该次查询并限频告警。既有预热、Chunky 所有权、玩家保护和配置上限的优先分支保持，普通协调器的显式暂停优先于另一来源的正数建议。`foregroundInteraction` 与 `foregroundActivity` 不合并，诊断 `snapshot()` 的未知参数不参与实际派发。

道路启动诊断只读取 owner、kind、busy，不调用带未知参数的预算快照。当前 Chunky 的 worker 建议会推进并写回自适应状态，`-1` 经夹取后可把预算压成 1；只打印负载类别不应触发这种决策。API 中通用 `snapshot()` / `describeAll()` 的既有建议查询仍保留，其他消费者使用时须单独核查；本轮只移除了道路日志不需要的调用，并将适配包到 API 的 import 基线从 14 收紧为 13。

相邻工程目前仍通过旧仓库的 named contract 编译。四份已核对的 `RoadLoadSignals`、注册表、`LoadKind`、`StreamsThreadControl` 在新旧源码中的差异仅为中文文件职责注释；本轮未改 `api/` 或外部工程。源码核对不能证明玩家已安装的 jar 一致；发布时仍须核实运行中只有预期的 API 提供件及这些适配器。

**执行守卫的范围：** 第35轮已接入CPU精确采样及两轮粗路径的预算、单元让路、取消和执行期限，第36轮再补六池的任务终态。API约束与实际执行分别验证；其他结构解析、采样入口、外层等待及整合包总线程不由这些局部守卫一概覆盖。帧时间/MSPT需现场验收，大范围采样/FMM工作集仍待治理。

## 开局河流、长城、道路流程

2026-11-16 只读核对 Streams `315a44f`、长城 `01408e65` 与道路 `59ac4f7`。正常依赖顺序是：

1. Streams 的 `StreamsServerLifecycleMixin` 在新世界初始出生点生成前做 startup 预热，在 `prepareLevels HEAD` 也会等待并广播，因此重开世界同样有通知。`RiverReadinessBroadcast.publishReady` 才是开局河流就绪发布。
2. 长城的 `RoadWeaverLifecycleBridge` 订阅 `RiverReadinessApi`，调用 `WorldServices.riversPrewarmed`；已就绪时的晚订阅会补发。`GreatWallReadinessProvider` 单独发布长城状态，未装河流时长城还有自己的启动路径。
3. 道路的 Fabric `MinecraftServerMixin` 通过 `RoadStartupGate` 显式先读河流 API，再确认长城完成或缺席；都满足才进入初始规划。前置条件未就绪则返回当前注入，异步等候后向服务端提交，避免挡住其他模组启动钩子。有长城是河流→长城→道路；无长城是河流→道路；未装河流才略过河流条件。`InitialPlanWaiter` 在逐路生成之前再次等长城，然后沿选定道路补热 Streams 板块及预取高度。沿线补热不是开局河流完成通知；旧 `PlanningLifecycleListener.onRiversPrewarmed` 回调保留兼容，现行长城不靠它启动。

长城的 `prepareLevels HEAD` priority=950、Streams=900、道路=1000；源码注释声称最终是 Streams→长城→道路，但不能只按优先级数字或注释证明最终注入次序，须验转换后的字节码及新档/重开实测。道路内部的长城门也不代表此前的结构扫描、粗采样完全没有执行。

`GreatWallPrewarmCompat` 只接安装状态、标准 API、时钟和轮询器；`PrewarmGateWork` 管等待与结案。无长城且无提供方不排队；有提供方的等待保留五秒首次 ready 宽限。默认无进展十分钟、总等待三十分钟以 STALLED/ABSOLUTE_CAP 结束旧门，正超时按 TIMEOUT 结束；系统属性置非正值仍可禁用对应上限。首次或后续调度失败按 POLLER_UNAVAILABLE 结案。道路启动门结束等待后重新检查真实长城就绪，未就绪会失败并记录 ROAD_INITIAL_GEN_NOT_STARTED，不再把旧门超时当成允许提前生成。

停服取消道路启动门及其自建的长城轮询 Future；回调真正执行道路前再检查服务端已停止、门取消及 RoadLifecycle 停服标志。尚未验证完整跨存档交错或真实加载时序。测试归属：河流自己的预热/发布在 Streams 仓库，长城自己的订阅/发布在长城仓库，道路读取前置条件和等待顺序在道路仓库；API 总线自身语义属于契约模块。道路 Fake 用例仅模拟信号，不代表其他模组已通过行为验收。

第37轮只读复核Streams 82cc818：发布已由RiverStartupReadiness.publishIfReady限制为COMPLETE或DISABLED，FAILED/STOPPED不会直接发布；此前“吞异常后照发ready”的描述属于修复前状态。长城当前5ade8e9b，两仓干净且本轮未改。下一步仍需最终Mixin顺序与跨存档现场证据；发布方、消费方的离线守卫不能代替三模组真实启动验收。

## 大范围网格与工作集

`metric.GridLayout`负责点数、坐标、吸附及分配前校验，保留全局端点包围盒。`DenseGridWorkload`提供整场采样三图、保留高度、稠密求解参考和分页全访问载荷；`[PLANNING-WORKSET]`用`solverDenseReferenceBytes`与`solverPagedFullVisitBytes`区分两者，均不等于即时堆占用，排除目录、对象、前沿、采样缓存和其他任务。桥接层仍只沿既有方向依赖metric，未新增逆向依赖。

FMM在小于4194304格时保留顺序数组，较大域使用`FmmPages`两级目录，每页1024格。`FmmCostCache`把与源无关的轴代价、通行性和规范方向的无向边按遇到的格准备，查询成功后才发布已知位，后续源共享已有结果。`FmmValues`的缺页读作无穷；`FmmWorkspace`的冻结状态只属于当次传播，返回的`ArrivalTimeField`仅接管该次到达值，下一源不会清空旧快照。无结果场仍为常量空间。多源共享要求度量和地形在求解器寿命内不可变，求解器仍为串行使用。

阈值只选择存储，不裁剪传播域、不限制绕行、不改变目标和Kruskal规则。小网格保留快路径是基于百万格密集探针的CPU代价，不是换旧寻路器；大范围密集访问仍会把所有页拉满，分页全访问原始载荷还高于压缩稠密参考。解析近目标小堆验证不能代替4096真实世界验收。

`SampledTerrainField.planningSurface`与`FastMarchingSolver.usesPagedStorage`共用4194304格分界。小域仍一次精确批采样，大域接`bridge.TiledTerrainField`；每块最多64×64个原采样列，每规划实例以原始long键LRU保留64块三通道数组，最多3 MiB原始高度载荷。尾块按实际点数请求，不改全局原点、步长或边界。梯度始终按全域索引取真正邻居，同块一次读图，接缝才跨块；水代理只接受精确对齐列。异常、null或错请求不得缓存成功，驱逐后可重读。

规划器的最小读口只注入`GridSampler`工厂，布局和读入策略仍由真实`plan`决定；公开构造器把它接到`AccurateHeightSampler.create(level)::sampleGrid`。沿用原CPU预算、持久缓存、在途认领与关闭检查，无新增线程或世界旁路。两规划共享门面的认领、驱逐后命中原列缓存及关闭期间不发布均有离线行为测试；已加载瓦片与旧整场数组一样是只读快照，新缺页仍须经过门面。

`[PLANNING-WORKSET]`将高度估算改名为`samplingDenseHeightBytes`与`retainedDenseHeightBytes`，它们是稠密参考。`[PLANNING-HEIGHT-TILES]`记录实际请求尝试、成功块、采样列、驱逐、保留块及三图原始字节，不能当作总堆峰值；加载一块时还有额外在途数组与下层工作区。密集分页和缓存查询仍有CPU成本，真实采样工作集、驱逐重算、加载尾延迟与4096存档仍需现场验收。

## 新内核依赖边界

`ArchitectureGuardTest` 按类型和包检查依赖，覆盖普通、静态、通配导入及源码中的全限定引用。下表是内核的出向规则；包内引用另计。`foundation-core` 是守卫使用的类型分组，并非源码目录。

| 包 | 职责 | 允许依赖 |
|---|---|---|
| `metric` | 把坡度、地形与障碍表示为网格通行代价 | `foundation-core`、`api`、`util` |
| `topology` | 求传播场、测地距离并选连边 | `metric`、`foundation-core`、`api`、`util` |
| `band` | 在障碍约束内优化单条曲线 | `topology`、`foundation-core`、`api`、`util` |
| `pipeline` | 编排测地选网与质量评估 | `metric`、`topology`、`band`、`foundation-core`、`api`、`util` |
| `bridge` | 将新内核接到游戏的寻路与采样接口 | `metric`、`topology`、`band`、`pipeline`、`core`、`util`、`api`、`pathfinding`、`pathlogic`、`config` |

既有跨层引用和双向依赖分别由 `common/src/test/resources/architecture/` 下的 `deps-baseline.txt`、`bidirectional-baseline.txt` 管控。`client` 不新增指向 `persistence`、`worldgen`、`generation` 的直接依赖。施工或路口编排通过小端口读取世界、写方块、写道路；离线测试验证产物和拒绝结果。

旧的 `foundation.*` 整体迁包图从未落地。七批替换的机器台账保存在 [REFACTOR.md](REFACTOR.md)，它不代替生产调用链。
