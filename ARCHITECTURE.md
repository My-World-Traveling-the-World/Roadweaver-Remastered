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
