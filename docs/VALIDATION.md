# 验证与构建手册

## 离线回归

Windows 开发环境使用 JDK 21、已安装的 Gradle 8.8 和常驻 daemon。当前机器的 Gradle Wrapper 握手失败，直接调用下面的 `gradle.bat`；其他机器替换为自己的安装路径。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:GRADLE_OPTS = '-Xmx2G -Dfile.encoding=UTF-8'
$gradle = 'C:/Users/18364/.gradle/wrapper/dists/gradle-8.8-bin/4u0rgm4geyrm56fyhoco0c9in/gradle-8.8/bin/gradle.bat'
& $gradle :common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off
```

- 检查 `BUILD SUCCESSFUL` 和 `PASS tests=<N> skipped=0 aborted=0 failed=0`；同分支测试数只增不减。中文控制台可能乱码，按 ASCII 标记核对。
- `Unsupported class file major version 70` 通常是误用 JDK 26，先查 `JAVA_HOME`；Gradle 9.x 会在 Loom 初始化时失败；`Could not connect to the Gradle daemon` 先确认已用 `--daemon`。
- 新测试类须按 FQCN 加到 `RoadJUnitRegressionSuite` 的 `selectClass` 列表。它不扫描包。
- 共用模块编译要求相邻的 `../plaincraft-great-wall/build/interop/plaincraft-great-wall-interop.jar`。缺少时先在长城仓库用 Gradle 跑 `:interopJar`。这是编译依赖，不会打进道路发布件。
- 守卫用负控验收：合法夹具先断言通过；迭代内断言先验证产物非空；故意丢弃产物、绕开判据或改变边界值，看对应断言是否失败。静态检查要覆盖全限定、同一行和通配导入；行数按 CR、LF、CRLF 都能正确识别的方式计算。
- 复现内核数据：[FMM 基准](../scripts/benchmarks/Measure-Fmm.ps1)、[三通道瓦片基准](../scripts/benchmarks/Measure-TerrainTiles.ps1)和[区块编译基准](../scripts/benchmarks/Measure-ChunkRaster.ps1)。参数及输入、预热、对拍和数据见[交接记录](HANDOFF.md)。记录单位、样本和是否包含调度、查盘或落块。

采样并发回归见 `AccurateChunkBatchTest`、`AccurateColumnBatchTest`、`AccurateTileClaimsTest` 与 `AccurateHeightSamplerLifecycleTest`：用锁存器钉住认领、读盘和所有者完成的交错；超时后核对完整键集合，取消后核对健康等待者仍成功，关闭时核对等待者被主动唤醒且关闭后的迟到结果没有重新发布到内存。重算优化同时断言结果非空与后端调用次数，不能只比较两份空集合。生产负控和恢复记录见 [HANDOFF 第 25、26 轮](HANDOFF.md)。网格/逐列双向冲突夹具必须真实进入等待，再检查只计算剩余列；另验读盘期间关闭、保存期间关闭、坐标网格不匹配、进度回调取消和原故障传播。文件缓存后端会吞掉部分 IO 错误并告警，Fake 端口抛错的重试不等价于真实磁盘故障已确认恢复。

真实文件缓存回归见 `AccurateSampleStoreFileTest`、`AccurateSampleStoreCloseTest`；门面只读生命周期见 `AccurateSamplerCacheLifecycleTest`。Windows / JDK 21 用 `NOSHARE_READ` 阻止读、`NOSHARE_DELETE` 阻止替换，先断言锁确实造成相应 IOException，再检查原文件逐字不变、写失败驱逐内存、解锁后同实例重试与新实例重开。没有使用跳过来掩盖平台不支持；这些故障夹具的当前验收平台仅为 Windows。另验三种写入口、负坐标跨瓦片三通道、损坏隔离和指纹 A→B→A。关闭夹具在实际文件入口的输入遍历暂停，确认独立访问可同时进入、关闭线程真实等待；已进入的写入可在关闭等待期间落盘，关闭返回后才是禁止新 IO 的边界，不承诺取消已开始的磁盘操作。第 27 轮 13 项生产变异均触发目标 JUnit 失败并逐字恢复，记录见 `HANDOFF.md`。


过期未命中回归见 `AccurateSampleRecheckTest` 和 `AccurateSamplePublicationsTest`。先暂停已经取得的存储未命中快照，等另一批完整发布并释放认领后再返回，避免用普通预热命中冒充并发复现。四种列/网格组合、实际门面的整块发布、跨瓦片部分补缺均对照完整键集合和三通道高度；另验重查异常送达已登记等待者、内存驱逐后查盘、两级缓存确实丢失后正常重算、版本槽碰撞、旧版本迟到及实例隔离。256 列冷网格仍为 256 次缓存读取、1 次存储读取；无关瓦片发布夹具含生产者共 257 次缓存读取，相关版本检查不增加一次整网格扫描。这是确定性工作量，不代表真机耗时或 TPS。第 28 轮变异记录及恢复结果见 `HANDOFF.md`。

负载 API 的离线生产接线见 `RuntimeLoadSignalsTest`：不是只调用诊断快照，而是注册非旧 owner 的来源后直接查询 RuntimeLoadPolicy 的 worker/lane、相位、暂停与恢复；断言带真实上下文的 `road` 消费者重载、多源取最小、零 lane、显式暂停优先、异常与 LinkageError 隔离、注销恢复、配置上限及 Chunky 专属进展。用会抛断言的提供方证明 preflight 完全不咨询压力；移动与交互保持不同语义。新类 14 项已按 FQCN 加入套件；另在既有 API 桥类补 1 项日志无预算副作用用例，先复现打印诊断把预算从 6 写成 1，再验证保留 6 且 worker/lane 查询为零。第 30 轮 11 项变异全部实际触发目标 JUnit 失败并恢复。外部六个工程只读核查，没有运行整合包、重编外部 mod 或部署 jar。实际联动仍需核对安装件、注册来源和现场执行线程，尤其不能用 `snapshot()` 的 `-1` 参数推荐值决定派发。

CPU与粗路径执行回归见CpuAccurateHeightBackendTest、RuntimeCpuSamplingWorkTest、CoarsePathTasksTest：新增52项，均已显式登记。世界读口Fake通过默认生产出口验证完整键、请求列、三通道及共享许可；另验暂停、MAP_READ角色、中断/代次/停止、异常释放和预热。语法树守卫分别钉住公开工厂及两轮调用，不等于真实ServerLevel已运行。真实INITIAL池缩为1先红两项；公共池取消也先复现“Future已取消、实际线程未中断”，自有FutureTask修后转绿。许可等待不耗搜索期限，物理队列/实际搜索超时、0不限时的停止取消分别验证；两种等待方式均验兄弟取消和异常分类。第35轮31组生产变异逐项目标JUnit红（3组静态），精确checkout恢复后1274全绿、Fabric通过。原版NoiseChunk、FMM实际中断尾延迟、公共池争用、吞吐及真实路网仍须现场对照，不能据此证明4096安全或FPS提高。

预热门回归见 `PrewarmGateWorkTest`：两项直接调用公开生产入口确认无长城立即完成、已注册提供方仍等待；其余用受控时钟与拒绝调度器验证五秒宽限、首次/后续提交失败、取消、显式期限、进展刷新、无进度信号及持续进展下的绝对期限。默认仍是十分钟停滞和三十分钟绝对上限，不睡眠十分钟来验边界。7 项生产变异实际触发对应 JUnit 失败，恢复后 1212 项无跳过且 Fabric 编译通过，见交接第 31 轮。未启动整合包或验证最终 Mixin 顺序，未修改相邻模组和安装件。

道路消费方回归见 `RoadStartupGateTest`：缺长城仍等河流，有长城则河流就绪后才等长城；两者完成保留同步快路径，长城超时但未 ready 不放行。另验 API 读回、停服取消、旧通知迟到、下一任务和完成回调探针异常；Fabric 自有源码接线另有静态守卫，不能等同真实 Mixin 运行测试。10 项新增、7 项变异恢复后全量 1222，Fabric 编译通过。测试只放道路仓库，河流预热/发布与长城订阅/自身发布的守卫留给对应仓库；本轮二者仅只读核查。该轮曾确认Streams异常后仍发布的旧路径；第37轮只读复核82cc818，已通过每世界结果门限制为成功或明确禁用才发布。三模组真实启动仍未验收。

任务生命周期守卫见TaskLifecycleTest：62项新增（61行为、1项语法树），显式登记在总套件。先实际复现排队四种Future、前置闸门四种Future、取消回调反序、完成服务内层结果、六路懒创建、已graceful关闭池及重建回调问题，再修复。六个真实物理池、运行中不合作夹具、用户取消true/false、旧代次、invokeAny/invokeAll、异常原样传播、优先级/FIFO和追踪表释放均覆盖。七处完成链的语法树守卫只证明接线，不代替真实ServerLevel；JDK不透明完成任务明确拒收的行为须保留。

第36轮28组生产变异（27行为、1静态）逐项触发指定JUnit失败，编译/命令失败不算红；finally精确git checkout恢复并逐字对照，再跑1336项零跳过/中止/失败和Fabric编译。锁内取消变异第一次仍绿，原因是worker抢先取消；夹具改为忽略中断并继续占位，确保取消调用方结案，修正后该变异红，最终28组全部重跑。脚本、JSON和日志位于.analysis_tmp/round36_mutations.py、round36-mutations.json和round36-mut-*.log；初始误绿记录另存round36-mutations-initial.json。结果取消不代表实际计算退出；最终Mixin、真实停止重开、外部任意嵌套Future和阻塞回调仍需独立验证。

## 第37轮大范围工作集与复现

新增GridLayoutTest 15项、FmmWorkingSetTest 32项，均已按FQCN登记；总计1383项、零跳过/中止/失败，Fabric编译通过。DenseFmmReference是af5cc72布尔数组求解器的类型重命名副本，逐字核对原实现；正常源的全场/单目标/多目标、重复吸附、空目标、硬禁行和跨边、源合法但吸附格受阻、多源快照、候选权重及Kruskal最终边序均对拍。另有JDK21当前线程真实分配预算，防止仅修改估算日志或反向扩大位图。

23组生产变异逐项触发目标JUnit失败，不把编译或命令失败计入；finally精确checkout单文件并逐字恢复。覆盖乘积回绕、坐标检查、读世界顺序、估算截断、重复目标、哈希冲突、密集位图、跨字状态、真实分配、硬禁行、提前冻结，以及不可达快照的面积分配和查询语义。明细见HANDOFF第37轮；本机脚本/JSON/逐项日志为.analysis_tmp/round37_mutations.py、round37-mutations.json和round37-mut-*.log。

可复现探针：`./scripts/benchmarks/Measure-Fmm.ps1 -JavaHome $env:JAVA_HOME -Baseline af5cc72 -WorkingSetOnly`。使用同一JVM、固定512MiB堆、1050625格、30目标；先核对全部场值和1025个回溯点，再热身8对、交替顺序测11对。热求解器和每次新建求解器分组；线程分配不是存活堆峰值，11次的p95接近最大样本，不作真机尾延迟结论。原DirectionOnly模式也已实际跑通；独立编译清单同步包含新网格类型、TopologyCandidates和已有fastutil依赖。

| 场景 | 每次线程分配 p50（B） | 耗时 p50（ms） | 耗时 p95（ms） |
|---|---|---|---|
| workset-hot-near-single | 10506448 → 8536592 | 1.168 → 1.142 | 1.595 → 2.314 |
| workset-hot-far-multi | 10506448 → 8536816 | 107.255 → 111.500 | 110.093 → 125.237 |
| workset-cold-far-multi | 28441248 → 25552336 | 127.718 → 130.352 | 130.994 → 140.906 |

上表来自.analysis_tmp/round37-workset-final-probe.log。远目标耗时略升，当前交付是减少分配，不宣称加载加速。`[PLANNING-WORKSET]`默认记录可复核载荷，并明确排除前沿、对象、采样缓存和其他任务；它是诊断，不是4096安全准入。全场高度/代价/到达数组和冷采样缺列工作区仍需按需化，之后再用存档副本逐档验收。

## 最新构建的真机对照

开局半径上限现为 4096 区块，默认仍是 128；PlanningConfigTest 验证 2048/4096 原值保留、超上限夹到 4096、UI 与归一化一致、旧 JSON 键读回与快照、动态上限和默认不变；AdaptiveCandidateBoundsScaleTest 新增半径 4096 的几何档，核对长整型面积及不裁核心。这些夹具不实际枚举结构、生成大网格或加载世界，不能证明 4096 档不会长时间等待或内存耗尽。先解决路线中的采样/求解工作集与预算遗漏，再用存档副本逐档采样；开局全域预热和游玩期按区域规划须分开评估。

1. 固定种子、模组版本、配置和选定连接。首次生成、存档副本重开、Chunky 并行三种情况分开跑。对照组和实验组只改变单项开关。
2. 首先复看村庄保护、桥头接缝、长桥、隧道顶板和工况复杂的坡地。记录计划道路、通过校验的道路和实际落成的道路；同路重复铺设和保护缺口也纳入记录。
3. 记录采样、拓扑、单路、区块编译、排队与落块耗时；看 p50/p95 和最大等待，区分计算速度、帧时间与服务端 TPS。旧版 215 秒是当时的世界记录，不能和离线微基准直接比较。
4. 用 `-Droadweaver.profileAb=on` 录制剖面，将 `<游戏根>/logs/roadweaver-terrain-ab.tsv` 复制到[重放样本](../common/src/test/resources/profile/terrain-samples.tsv)，回归看 `[PROFILE-REPLAY]`。历史已有 57 次观测。用对应连接的 `tag` 排查 DP 不可行或土方退化，结合覆土、桥面高度和坡度看质量。
5. `-Droadweaver.geometryAb=on` 记录几何偏差，不切换产物；`-Droadweaver.spanWriteback=on` 才切换 spans 回写，默认关。跨度对照另记水、建筑、既有道路等证据和漏报/误报；在获得这些数据前，不改 `crossing` 或 QualityGate 阈值。

当前源码可按下表分别启用观察。性能基线档保持诊断关闭；带诊断那档用于找原因，避免日志 I/O 混入常态耗时。

| JVM 选项 | 对照日志 | 排查方式 |
|---|---|---|
| `-Droadweaver.buildabilityShadow=on` | `buildability shadow` | 用端点 X/Z 连到下游校验与竣工。高度差是湿列代理，`INCOMPLETE` 不能当可施工。 |
| `-Droadweaver.landRelaxShadow=on` | `[LAND-RELAX-SHADOW]` | 按 `tag` 比较两份占地列、最大站距和保护缺口；快照不可用单列。确认桥头和宽度靠真机落块。 |
| `-Droadweaver.fmmTimings=on` | `[FMM-TIMING]` | 按格数、步长和目标数分组看 `totalUs` 的 p50/p95，另看冻结格、入队数和峰值。采样、区块排队和落块不在内。 |
| `-Droadweaver.deferredChunkTimings=on` | `[DEFERRED-CHUNK-TIMING]` | 按 `pending` 分组看 `selectUs` 的 p50/p95；`hintSteps` / `sweepSteps` 上限为 32 / 128，去重后的实际查询数可更少。`buildable` / `settled` 只描述候选阶段，不计回填写块或等待总时长。 |
| `-Droadweaver.finalFootprintAudit=on` | `[FINAL-FOOTPRINT-AUDIT]` | 启用结构校验时按 `roadId` 检查最终施工体积与实时巨树 X/Z 预留的首交列。遇结构碰撞提前退出时 `complete=false`，不能把未报树冲突当成干净；列交集也不能判定空中桥或地下洞一定冲突。 |
| `-Droadweaver.tunnelTimings=on` | `[TUNNEL-READ-TIMING]` | 分开看 `phase=probe/apply`，比较地形和岩层的总调用数与各自不同列数。显式方块读取不含安全辅助类或引擎内部读；`totalUs` 含该阶段完整调用。 |
| `-Droadweaver.constructionLedgerTimings=on` | `[CONSTRUCTION-LEDGER-TIMING]` | 按条目数、字节数分别统计 `gatherUs/sortUs/serializeUs/writeUs`，失败和成功分开。只在实际尝试写盘时记录。 |
| `-Droadweaver.chunkyReadinessTimings=on` | `[CHUNKY-READINESS-TIMING]` | `phase=scan` 每 20 个 tick 抽样，`entriesApprox` 是并发条目近似总数；`phase=write` 对照 READY 区域数、快照构造和原子写入。 |
| `-Droadweaver.lampBaseTimings=on` | `[LAMP-BASE-TIMING]` | 按是否找到合适位置比较实际探测的候选数、显式读块数与搜索总时间。已有序提前结束，候选数不等于几何范围内的总列数；形状检查的内部读没有计入 `directReads`。 |
| `-Droadweaver.constructionContentShadow=on` | `[CONSTRUCTION-CONTENT-SHADOW]` | 只在道路成功写入后报告同键变更；先在副本上核对实际方块。日志本身不会失效或更新完工记录。 |

方向误差的离线复现：`./scripts/benchmarks/Measure-Fmm.ps1 -JavaHome $env:JAVA_HOME -DirectionOnly`。该探针不运行 Fabric 世界，数据只说明离散代价和回溯的差异。

## 发布件

- 需要打包时用同一 Gradle 运行 `:fabric:embedApiJar`。从 `fabric/build/final/` 取件；`fabric/build/libs/` 的同名中间件不含 API。
- 确认 ZIP 条目有 5 个内嵌 jar，包含 `META-INF/jars/roadweaver-api.jar`。与上一版核对类、资源、Mixin/模组描述和公开签名；记录文件字节数与完整 SHA-256，确认构建件和部署件哈希一致。
- 装入测试环境时只保留一个启用的道路 jar，上一版保留为 `.jar.bak` 供整包回滚。只在本次确实构建可部署 jar 时追加[发布台账](RELEASES.md)，记录 commit、版本、回归、大小、哈希、实测与回退方式。

## 第38轮分页工作区与固定小堆

FmmPagedWorkspaceTest新增31项且显式登记，总计1414项、零跳过/中止/失败，Fabric通过。小场强制分页覆盖多源、单/多/空/重复目标、负原点、step=1/8、软硬障碍、非对称边回调及单行/单列，逐格比较double位并逐点比较回溯。百万格公共稠密与强制分页两种存储均逐格对拍；大域实际GeodesicMatrix与独立稠密权重/Kruskal分量及最终边对拍，含有限候选和不连通分量。独立Map守住两级目录、位字和最大int尾下标，真实线程分配守住生产阈值的按需代价和到达工作区。

23处生产实现及1处旧夹具假设故意改坏，24组指定JUnit均红，编译/命令错误不计；每次finally精确checkout并逐字验证。详表见HANDOFF第38轮；本机记录.analysis_tmp/round38_mutations.py、round38-mutations.json、round38-mutation-run-final.log和round38-mut-*.log。首次命令错误把包名拼成metric.metric，没有目标JUnit结果，已拒绝计数、恢复并修正脚本，随后24组完整重跑。

CoarsePathTasksTest的创建顺序假设已复现：等待第二个创建线程会把实际许可持有方认成预算等待方。新夹具先占用一个工作线程，让另一个确定获得许可，再释放前者成为实际预算等待方；“绕过BackgroundCpuWork”和“复原按创建顺序猜测”两组变异均红。没有增加超时或放宽线程栈判据。

独立探针同一JVM交替测量，基线03a77af、JDK21、512MiB堆；1050625格、30目标、8对预热后11对测量。各组分别运行，不把不同进程数字混作同一组：

| 存储 / 场景 | 每次线程分配p50（B，旧→新） | p50（ms，旧→新） | p95（ms，旧→新） |
|---|---|---|---|
| 公共自动 / 热近目标 | 8536592 → 8536640 | 1.064 → 1.176 | 2.545 → 3.067 |
| 公共自动 / 热远多目标 | 8536816 → 8536840 | 116.705 → 117.894 | 127.247 → 125.834 |
| 公共自动 / 冷远多目标 | 25552336 → 25552392 | 136.845 → 138.991 | 140.066 → 149.358 |
| 强制分页 / 热近目标 | 8536592 → 44080 | 1.888 → 0.074 | 2.265 → 0.096 |
| 强制分页 / 热远多目标 | 8536816 → 8580200 | 128.431 → 222.893 | 138.381 → 252.053 |
| 强制分页 / 冷远多目标 | 25552336 → 26594192 | 147.983 → 247.157 | 154.914 → 264.571 |

日志round38-workset-final-auto.log / round38-workset-final-forced-paged.log。首版百万格即分页，密集访问明显变慢，因此公共切换点改为4194304格，并保留强制分页探针。分页是稀疏访问的分配治理，密集访问有CPU和载荷代价；热近目标44080 B只计本次线程分配，求解器还保留此前触及的代价页及前沿数组，不等于总占用。

固定64MiB堆的解析地形：268435456格近目标单次分配p50=171752 B、p50=0.111 ms、p95=0.235 ms；16383格远端走廊产生16384点，封闭分量内部可达/远目标不可达，百万格强制分页与旧场全部double位及1025个回溯点相同。日志round38-paging-smallheap-final.log。此处没有真实世界、三图采样、文件缓存或其他模组；大域夹具只访问稀疏分量，不能证明全场2.68亿格都访问时能在64MiB运行。分页全访问单纯数组载荷6744440832 B，稠密参考6509559808 B，均不含目录/对象/前沿。

复现（先设JAVA_HOME）：

```powershell
& ./scripts/benchmarks/Measure-Fmm.ps1 -WorkingSetOnly -Baseline 03a77af
& ./scripts/benchmarks/Measure-Fmm.ps1 -WorkingSetOnly -ForcePaging -Baseline 03a77af
& ./scripts/benchmarks/Measure-Fmm.ps1 -PagingOnly -HeapMiB 64 -Baseline 03a77af
```

普通83521格探针也逐位/逐路径通过（round38-normal-grid.log）。其clustered-topology对照直接比较当前GeodesicMatrix的先全量后过滤与先过滤后求解，不是本轮新旧源码对照，不能计成本轮收益。源码恢复后再次全量回归与Fabric编译，日志round38-final-after-mutations.log。真实采样仍有整场三图及缺列集合，4096存档、FPS/MSPT和加载时间未验收。


## 第39轮三通道瓦片与密集代价

本轮新增28项：TiledTerrainFieldTest 15项、TiledTerrainFmmTest 8项、PipelinePlannerSamplingTest 2项、TiledSamplerIntegrationTest 3项，均按FQCN登记。1442全绿、零跳过/中止/失败，Fabric通过。小场对整场高度数组和冻结DenseFmmReference逐高度、梯度、水代理、所有场值double位、路径点序、双向权重及独立Kruskal最终边/分量对拍。覆盖负原点、step=1/8、单行单列、尾瓦片、非对齐点、极端合法坐标、断开分量与多源早停；先断言结果非空。

真实plan只注入世界读取，布局和整场/瓦片选择没有移到测试；大域近端合法边仍下发，孤立远点不触发整场采样。真实AccurateHeightSampler门面另验本地驱逐后复用原列缓存、两个规划共享认领、保存期间关闭不发布瓦片或列缓存。原CPU采样预算和世界NoiseChunk未改；这些端口测试不等于真实世界高度已对拍。

26组生产变异在最终快路径版本完整重跑，均出现指定JUnit失败，逐项finally精确checkout并比较原字节。覆盖退回整场、错高度图、键冲突、步长/尾块/吸附、接缝和同块差分、无界缓存、异常填零、错请求提前发布、并发重复加载、水代理、计数器、规划读口旁路与全域截断。快路径新增后锚点出现两处，首次扩展脚本明确拒绝该匹配；修正为请求上下文、增加全组预检后26组完整通过，未把锚点或编译失败计为红。详表见HANDOFF第39轮；本机日志.analysis_tmp/round39-mutation-run-final.log及round39-mut-*.log，脚本与JSON为round39_mutations.py、round39-mutations.json。

可复现命令（JDK21、缓存的SLF4J2与fastutil；不需要世界或MC桩）：

```powershell
./scripts/benchmarks/Measure-TerrainTiles.ps1 -JavaHome $env:JAVA_HOME -HeapMiB 64 -HugeOnly -Baseline e109b9d
./scripts/benchmarks/Measure-TerrainTiles.ps1 -JavaHome $env:JAVA_HOME -HeapMiB 512 -Baseline e109b9d
```

64MiB：268435456格近目标8次热身、11次测量，每次1请求/4096列，线程分配p50=224024 B，耗时p50=0.235 ms、p95=0.771 ms。全域16383格走廊输出16384个路径点，实际256请求/1048576列/192次驱逐，最终64块三图3145728 B；封闭分量只采一块且远点不可达。整场三图3221225472 B是同布局的算术参考，未在64MiB堆中强行分配。日志round39-terrain-smallheap-final.log。

512MiB同JVM三方对照：整场GridTerrainField／冻结首版瓦片e109b9d／当前同块快路径；共享当前FMM，只比较采样策略，非整个旧提交与整个新提交的端到端对照。先核全部密集场值和1025个回溯点、近目标权重和路径，并确认两版瓦片请求数及列数一致；3轮热身、7轮旋转顺序测量。近目标4194304格走公共分页入口；密集1050625格强制三方分页以隔离采样成本，生产百万格仍走整场数组与稠密FMM。

| 场景 | 当前线程分配p50 B（整场／首版瓦片／当前瓦片） | 耗时p50 ms | 耗时p95 ms |
|---|---|---|---|
| 4194304格近目标，含首次高度读入 | 50491656／211944／211928 | 5.610／0.093／0.062 | 8.436／0.186／0.078 |
| 1050625格密集远目标，强制分页 | 72821752／72856792／72856776 | 275.554／321.429／295.813 | 278.122／333.354／317.932 |

密集夹具实际289请求/1050625列，两版均无重复采样，225次驱逐，最终保留64块、三图2128908 B（含尾块）。同块梯度只查一次缓存减轻首版开销，仍比整场数组慢。以上7次p95接近最大样本，线程分配不是存活堆峰值；只替代世界读的固定平地不含真实NoiseChunk、存储IO、认领工作区与其他模组争用。日志round39-terrain-three-way.log；较早两方探针保留为round39-terrain-comparison-final.log、round39-terrain-fast-gradient.log，不能跨JVM直接据此推断快路径收益。

源码恢复后完整回归日志round39-final-after-mutations.log。4096真实存档安全性、世界高度与最终道路产物、FPS/MSPT、开局/游玩期等待、驱逐后磁盘和NoiseChunk成本、真实停止/重开以及河流→长城→道路最终Mixin次序仍未验证；本轮没有打包或部署。


## 第40轮READY状态转换与增量统计

开工1442全绿；先以全量扫描集中状态转换，1453全绿并单独提交89666f6，再加入即时计数与实际门面守卫，最终1459全绿、零跳过/中止/失败、Fabric编译通过。新增ReadinessRegionTableTest 12项、ReadinessIndexStateTest 5项，均逐FQCN登记。独立模型12000次变更逐步对拍三个世界；额外覆盖六种初态、提前/重复派发、插入重复键、暂停重排、并发转换总数守恒、clear取消和同坐标旧回调拒绝。

实际旧缺陷：在89666f6同一tick先查询空积压，再加入512个QUEUED，backlogSaturated仍false。修复前指定JUnit真实失败1项，日志round40-cache-reproduction.log。修复后立即随转换更新四计数；不把QUEUE重复键或ACTIVE重叠重复收费。只保证既有服务端线程逐次调用的即时判据；“检查→adoption→插入”尚非跨线程原子配额。

18组生产变异均出现指定JUnit失败，包括不计新增/重复计数、转换漏扣漏增、派发、清理、旧身份、世界混算、pending漏PLANNING、真实供给断开、饱和关闭/边界、暂停未重排、clear未取消，以及完整恢复全量扫描/原tick缓存。每项finally精确checkout并比较原字节；最终再跑完整套件，日志round40-final-after-mutations.log。清单与失败数见HANDOFF第40轮，脚本/JSON/日志在.analysis_tmp/round40_mutations.py、round40-mutations.json、round40-mutation-run.log。

复现纯状态表探针（JDK21，无MC桩或外部依赖）：

```powershell
./scripts/benchmarks/Measure-Readiness.ps1 -JavaHome $env:JAVA_HOME -Baseline 89666f6
```

脚本仅重命名冻结的89666f6状态表类型，与实际当前类在同JVM、256MiB堆中比较。基线是“集中转换后仍全量扫描”的版本，并非旧仓库完整游戏。两个世界、先精确核验全部快照字段，5轮热身、11轮交替先后测量；每次500次快照读取，另测1500次状态转换，转换循环回到原状态。结果均为整个批次，不是单次调用。

| 总区域项 | 500次读取p50 ms（全量/增量） | 读取p95 ms | 1500次转换p50 ms | 转换p95 ms |
|---|---|---|---|---|
| 512 | 0.8890 / 0.0504 | 1.1208 / 0.0826 | 0.1009 / 0.1217 | 0.2635 / 0.3007 |
| 8192 | 15.8812 / 0.0473 | 18.1612 / 0.1021 | 0.0444 / 0.0590 | 0.0446 / 0.0593 |
| 65536 | 143.3067 / 0.0452 | 144.9236 / 0.0504 | 0.0435 / 0.0590 | 0.0464 / 0.0649 |

读取批次两者当前线程分配p50均16000 B，转换批次均0 B，不含探针Measurement对象；没有声称快照零分配。计数更新有小幅CPU成本，频繁读取不再受历史区域数线性影响。11个批次的p95近于最大样本，不能等同现场帧时间尾延迟。日志round40-readiness-benchmark.log。源码工作量守卫在两万历史项上执行三万次快照，精确验证世界键提取回调被调用0次；恢复全表扫描会使该守卫红。

phase=scan诊断保留原日志标签并增加source=incremental，scanUs现在量快照读取；READY全表序列化/原子写仍用原write诊断。此次没有文件读写故障/重开验证、真实ServerLevel规划链或现场Chunky启动数据，不将即时统计改造称为持久化验收。该持久化分支已在第41轮真实复现并修复，见下节；本段1459项仅为第40轮历史结果。


## 第41轮READY同步文件提交与故障重试

开工1459全绿。行为等价迁出文件边界9a45ab6并加8项原格式用例，1467全绿后单独提交；在此版本用NOSHARE_DELETE真实阻止FileStorageIO替换，ReadinessCoverageStoreTest#failedReplacementKeepsMemoryAndFileUnchangedAndRetriesAfterUnlock因“未抛IOException”红1项，原缺陷日志round41-write-fault-reproduction.log。修复afb418e后先1468全绿，再补齐故障/运行态及AST接线，总计1494全绿、skipped=0 aborted=0 failed=0，Fabric编译通过。

四类逐FQCN登记：ReadinessCoverageStoreTest 9项、ReadinessCoverageFaultTest 10项、ReadinessPersistenceCompletionTest 14项、ReadinessPersistenceWiringTest 2项，共35项新增。其中33项驱动真实文件或生产运行态；2项只用Javac AST核对服务端完成回调、adoption返回及world load的窄接线，不算真实ServerLevel运行。

覆盖成功文件schema2/指纹/负坐标极值/UTF-8-LF/原字节顺序、重复提交不写；真实写锁/读锁先确认操作失败，再验旧字节及解锁同实例重试、重开合并。另验IO/运行时失败、损坏前缀/非普通路径、父路径失败、串行并发、重复提交、close和真实clearRuntime。阻塞断言要求实际工作线程BLOCKED且栈位于ReadinessCoverageStore的markReady/close；不用线程创建顺序代替真正进入。原三次重试、最终FAILED、具体cause、ACTIVE/QUEUE、代次/替代条目均直接断言。

29组生产变异逐项真实JUnit红，清单及目标方法/失败数在HANDOFF第41轮；没有将编译/命令失败记为红。预检全部锚点，每项finally精确checkout并逐字比较。false && finishPlanning变异曾让新增AST检查仍绿，812dcb5要求条件直接使用提交结果后同一变异红1项；前后日志为round41-shortcircuit-green-before.log、round41-shortcircuit-red-after.log。恢复后全量回归日志round41-final-after-mutations.log；脚本与JSON见.analysis_tmp/round41_cases.py、round41_mutations.py、round41-mutations.json、round41-mutation-run.log及round41-mutation-resume.log。

构建仍使用已安装Gradle8.8、JDK21和--daemon，不用gradlew；完整命令为:common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off。独立词法统计693个生产Java文件，超过1000有效行0，超过800行2；文件边界143/123、门面721/537、运行态表93/74行（物理/有效）。

当前故障夹具验收平台为Windows/JDK21的NOSHARE_READ/NOSHARE_DELETE，没有用skip掩盖不支持。关闭等待期间已开始的物理提交可完成，关闭返回后才保证旧IO排空；没有强撤销或断电fsync保证。真实世界规划、三模组最终Mixin次序、4096存档、跨实例并写、实际磁盘性能、FPS/MSPT和进档时间均未验；仍同步全表写盘，不能从本轮正确性守卫推导加载提速。


## 第42轮样条投影与宽度落列拆分

基线bc69e3b，开工1494全绿；先新增22项（PathCurveRasterParityTest 9、PathWidthRasterizerContractTest 10、PostProcessedRoadProductTest参数化3），新类逐FQCN登记。冻结bc69e3b的本轮算法及数学函数，先1516全绿提交2a6fc2d，再等价迁出PathSplineSampler与PathWidthRasterizer。7个方法体剥除注释/空白后逐项相同，日志round42-body-verification.log；冻结参照仅把Vec2d嵌套私有字段读改为22处等价record访问器。准备阶段仍共享原简化/松弛/掩码/密化，不用它自证这些未改阶段的独立等价。

三种模式各60条固定种子路线，180组逐项核对样条双精度坐标/顺序、中心站、里程和每站位置列表；另54组三路形×水陆×三宽×三模式直接驱动生产process完整重载，世界读口用原平地Backend。独立轴向闭胶囊预期不调用生产距离函数，覆盖0/1/2/5/6宽度、负坐标及端帽；桥前后岸腿、急弯直线、3D采样密度、交叉高程、重复中心、中点归后站、等距hint和空站锚点均有非空及具体结果断言。生产入口新增缓弯夹具确保忽略曲线模式不能仍绿。

首次拆分仅架构依赖棘轮和S5文件台账红，几何对拍未漂移；补登记并删BridgeMaskTool实际未使用的RoadSegmentPlacement导入，未放宽deps-baseline，1516全绿后提交6a2a608。23组生产变异全部指定JUnit红、零skip/abort；最后三项门面参数误接由完整生产入口产物对拍捕获，详细表见HANDOFF第42轮。脚本预检所有锚点、每项finally精确checkout且逐字恢复，记录.analysis_tmp/round42_cases.py、round42_mutations.py、round42-mutations.json、round42-mutation-run.log及round42-mut-*.log。

还原后完整:common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off通过，PASS tests=1516 skipped=0 aborted=0 failed=0、BUILD SUCCESSFUL，日志round42-final-after-mutations.log。695个生产Java文件，超过1000有效行0、超过800行2；门面474/320、样条148/117、落列101/84行（物理/有效）。

本轮没有性能基准、真实世界/存档或三模组整链数据；拆分不宣称减少卡顿或加载时间。零宽度/偶数宽度只锁定现行闭区间语义，不新增用户配置范围；保护列夹具验证已接受安全走廊，不代表栅格器新增世界保护查询。长桥正弦仍由BridgeSegment保留，原生产产物用例继续跑；双平滑默认、crossing、QualityGate、跨度回写和协议均未改。


## 第43轮五项代码工作与离线测量

开工f67e70b、status空、pull --rebase最新，先确认1516全绿及Fabric通过。先测后改宽度落列；快照准备/激活、纵断面输入在1516全绿后单独等价提交18ed8f1，再加6项直接守卫。查盘交错三例先在旧实现红3项，整块发布用例先红1项；修复后1528全绿。方向候选与证据接线依次到1534/1542，长路线补守卫后1543。恢复21组变异后重新执行完整回归，日志round43-final-after-mutations.log，PASS tests=1543 skipped=0 aborted=0 failed=0、BUILD SUCCESSFUL；不是将定向或编译失败计为守卫通过。

### 宽度落列测量

探针使用冻结LegacyCurveRasterReference与当前生产PathWidthRasterizer；相同输入，同JVM、512MiB堆、JDK21，先逐站比较中心及有序位置，再30轮预热、15组交替顺序取线程分配及耗时中位数。每条路负坐标起点，四分之一格密度，宽5；分配计数来自当前线程ThreadMXBean。修改前两实现分配完全相同；修改后数据如下（round43-width-before.log、round43-width-after.log）：

| 站数 / 宽度 | 包围盒列访问次数（两版相同） | 冻结基线分配 B | 当前分配 B | 基线p50 ms | 当前p50 ms |
|---|---|---|---|---|---|
| 128 / 5 | 41,148 | 338,856 | 333,200 | 1.0652 | 0.9762 |
| 512 / 5 | 165,564 | 1,921,864 | 1,334,728 | 3.6509 | 3.2337 |
| 2048 / 5 | 663,228 | 8,264,704 | 5,348,960 | 14.8574 | 12.9518 |

仅减少连续站号HashMap节点/装箱，列访问工作量与每站HashSet插入不变。新增512站JUnit对拍跨过Integer缓存边界；原180组曲线、54组生产入口和闭区间/桥头/交叉hint用例保留。微基准是一次单机受控测量，未测游戏、GC尾延迟或统计显著性；2048站分配约降35.3%，不能外推总加载速度或FPS。

```powershell
& $gradle -I scripts/benchmarks/width-raster.gradle :common:widthRasterProbe --daemon --console=plain --dependency-verification=off
& $gradle -I scripts/benchmarks/directional-cost.gradle :common:directionalCostProbe --daemon --console=plain --dependency-verification=off
```

沿用本文件开头的JAVA_HOME/GRADLE_OPTS和已安装Gradle变量，禁止用gradlew。两个探针源文件均在scripts/benchmarks，不进生产包。

### 方向候选的结论与边界

DirectionalUpwindCandidate仅在test目录、最多16384格；用相邻已冻结邻居的三角扇与完整方向代价更新，小段枚举后局部细化，按因果约束冻结。保守检查格内硬列及双向边禁行；没有任意非凸度量的全局精确/收敛证明，也没有生产分页、早停或取消治理，禁止作为生产替代/回退。

33×33解析25%坡面，梯度不经整数差分；同JVM交替9组求解，512MiB堆。0°时生产/候选都得上坡1.0625、等高线1.0000；45°时生产场每格分别1.084306/1.084306，候选1.0625/1.0000。有限距离迎风离散误差包括在该场值，不能与旧大场1.0486直接混比。0°耗时p50 0.8495/8.2100 ms、45°0.3511/7.8754 ms（生产/候选）。两版本轮平面回溯折线均相同，45°上坡方向积分18.031、等高线16.971，转角均0；只修正场值不说明路径或建造质量已提升。硬墙/单格口、独立边屏障、阻塞/越界源均有守卫。实际桥隧、挖填、复杂地形收敛和真机均未验；当前明确不切生产，日志round43-directional-probe.log。

### 采样、拆分及跨阶段证据

新增27项：TargetTerrainInputsTest 4、PlanningSnapshotActivationTest 2、AccurateColumnBatchTest 5、AccurateChunkBatchTest 1、DirectionalUpwindCandidateTest 6、RoadBuildEvidenceTest 4、ConnectionEvidenceTest 2、RouteEvidenceWiringTest 2、长路线对拍1；新类逐FQCN selectClass登记。

采样交错由受控存储端口先返回旧空快照，再触发另一请求只读盘填缓存；三种列/网格/只读网格均断言后端和保存0次。整块读盘还直接验证共享瓦片版本。64轮强制清空一级缓存后仍恢复所有三通道且不重复计算；真实活动网格持有认领时关闭，列等待者在所有者释放前收到失败。这不是实际文件耗时、缓存容量策略或整合包内存压力测试。

快照准备方法与b8b7194中原文逐字相同；作用域维持原ThreadLocal对象恢复，输入保持原批采样/缺样回落/宽度统计/窗口顺序。没有用离线作用域测试冒充真实ServerLevel快照发布验收。架构导入基线仅收紧：pathlogic到core 198→195、generation 65→60、pathfinding 77→75、planning 59→54、structures 10→8；新增证据边界以清理Road的两个确实未用导入维持总数，未放宽规则。

开启`-Droadweaver.buildabilityShadow=on`，`[ROUTE-EVIDENCE]`使用`dimension@seed/x,z~x,z`完整端点键，正反路同键；只用于日志，不改RoadData、RoadFingerprint或台账。粗候选最多沿用12边/4候补预算，保护仍标unprepared；final-water/final-structure/validated逐阶段保留真实safe和reason，pavementEntries是逐站条目而非唯一列或落块计数。PathFeature用实际处理集合报COMPLETED/ABANDONED/INCOMPLETE/NOT_ATTEMPTED/CONFLICT，不能把留缺口且记台账当成成功铺路；缺口写回后读取新计划输出。没有保留全世界映射或新增寻路。世界边界仅2项AST窄接线，行为部分验证有限候选及观测异常不改变森林；需要真实日志才可评价已建可通行性，当前不换边。

21组变异详细表见HANDOFF第43轮；19组改生产（含3组由AST守卫），2组改测试目录中的候选原型。全都有实际目标JUnit失败、零skip/abort；逐项finally精确checkout并比较原字节，记录.analysis_tmp/round43-cases.json、round43_mutations.py、round43-mutations.json、round43-mutation-run.log、round43-mut-*.log。700个生产Java文件，有效行>1000为0、>800为2。没有改存档协议、默认几何、crossing、spanWriteback/QualityGate或废弃GPU，也没有读写其他模组或打包部署。
