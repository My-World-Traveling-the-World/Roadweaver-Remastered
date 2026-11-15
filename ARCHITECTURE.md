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
