# 验证与构建手册

## 第77轮分层隧道与严格验证入口

完整 **PASS tests=1744 skipped=0 aborted=0 failed=0** 与Fabric编译通过；新增五项按FQCN显式登记。原代码先复现4项JUnit失败，修复后23项定向回归及全量通过。同层/反向正例、±1格容差、±2/8格分层、分层后重合、旧共用回收与稀疏候选/缺高度兼容均覆盖。原冻结实现不改，等价对拍排除已经证明错误的隧道分层输入，独立新断言负责正确契约。4组实际生产变异见[高度负控](evidence/reuse-height-mutations-20261120.json)，日志`.analysis_tmp/round77-height-final.log`。

实际Windows PowerShell 5.1执行以下三个入口，均退出0：

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/audit/Test-VerifyRetry.ps1
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/audit/Test-VerifyGate.ps1
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File verify.ps1
```

重试测试3场景，进程查询/终止0；阶段测试18场景，直接执行真实脚本AST，不自写第二份判据。拒绝跳过/终止/失败、低于1744、零/溢出/截断/重复摘要、缺BUILD成功、先PASS后失败、两阶段非零退出及诊断文字中的伪成功。失败场景必须走正常FAIL出口，语法/运行异常不算正常拒绝。8组脚本变异均红，见[入口负控](evidence/verify-gate-mutations-20261120.json)。真实verify日志`.analysis_tmp/round77-verify-final.log`确认完整1744/0/0/0和ALL GREEN。

标准:fabric:build及API named/intermediary契约件再次通过，七类真实错误包均拒绝。最新开发成品与本次remapJar外层逐字一致、仅多嵌套API；API与本次生产件逐字一致，编译测试类全集与主包class集合无交集。hash/体积及日志SHA见[机器证据](evidence/reuse-height-verify-package-20261120.json)和RELEASES。本轮不包含真机落块、已有完工区块修复或性能测量；无游戏部署、无schema变化。

## 第76轮密集归属与API成品验收

恢复14组变异后完整 **PASS tests=1739 skipped=0 aborted=0 failed=0**，标准:fabric:build、API named/intermediary契约构建成功，日志`.analysis_tmp/round76-final.log`。新增七项按FQCN登记；负控分9组JUnit、4组Gradle验包/接线、1组PowerShell模拟，不混写成真实游戏或全为JUnit。首次方向破坏仍绿的不足长度夹具已被终态合法宽路交叉替换，随后全组重跑。见[完整记录](evidence/geometry-package-mutations-20261120.json)。

复现独立基准（按下方配置JDK21和安装版Gradle）：

```text
-I scripts/benchmarks/remaining-optimizations.gradle :common:reuseOptimizationProbe -PreuseBaseline=488214d -PprobeTag=round76-20261120 --max-workers=1 --daemon --console=plain --dependency-verification=off
```

基线为上一轮真实生产实现；源码从git对象生成到build，仅改类名。512MiB、3预热、9次交替样本，先比较完整产物；完整构建后单独运行避免打包并行干扰。原始[结果](evidence/reuseOptimizationProbe-round76-20261120.json)，日志`.analysis_tmp/round76-benchmark-only.log`。不覆盖第75轮JSON，默认旧基准及其标签仍可复现。

| 场景 | 旧→新p50（ms/4次归一化） | 旧→新调用线程分配（B/次） |
| --- | --- | --- |
| 稀疏96路 | 2.2233→2.3280 | 153168→153176 |
| 密集96路 | 82.8347→48.3170 | 54268424→2752800 |
| 小批8路 | 0.1677→0.1642 | 51640→52952 |

减少的是重复开方/数组及装箱集合，数组首次实际比较才分配。密集受益，稀疏略慢、小批有少量分配开销；未测真实分布、存活堆峰值、世界施工、帧率或总加载。

标准打包现在用`:fabric:build`；`:fabric:check`同样执行合包及错误包回归，`:fabric:verifyEmbeddedApi`可单独验本次输出。检查外层声明/依赖、API包类全集、独立身份/版本、无外层重复定义及与本次remapJar逐字一致；合法Architectury注入类保留。七类副本为缺API、未声明、缺依赖、空API、错身份、缺契约class和合法zip但生产字节不同。脱离标准build/check的负控也会失败。`:fabric:remapJar`仍仅是中间产物，安装取`fabric/build/final`，无须另装API。

Windows脚本守卫用`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/audit/Test-VerifyRetry.ps1`：实际从verify.ps1语法树提取原重试函数，模拟恢复/耗尽/编译错误三种场景，计数进程查询/终止均0；恢复旧全局kill后两个模拟计数均1并失败。替身不会碰真实进程，UTF-8 BOM已由Windows PowerShell 5.1解析验证；CI加Windows任务但未在远端实际运行。旧版契约检查errors=[]，Python2项契约和21项证据工具通过。成品大小、SHA、外层与API逐字对照见[机器证据](evidence/api-package-round76-20261120.json)及RELEASES；没有安装或真实Loader运行验收。

## 第75轮四项剩余优化与测量

恢复全部负控后完整 **PASS tests=1732 skipped=0 aborted=0 failed=0**，Fabric编译、API/Fabric remapJar成功，日志`.analysis_tmp/round75-final.log`。新增20项显式登记。13组道路生产变异（11行为、2 AST接线）逐项实际JUnit失败并精确恢复，锚点、失败数和日志SHA256见[变异记录](evidence/remaining-optimization-mutations-20261120.json)。长城独立972项零失败/跳过及3组行为变异记录属于其仓库HANDOFF第89轮。

按下方离线回归配置JDK21和安装版Gradle，附加以下参数可重跑三项道路基准；会覆盖对应JSON：

```text
-I scripts/benchmarks/remaining-optimizations.gradle :common:reuseOptimizationProbe :common:streamsBatchProbe :common:heightWindowProbe --daemon --console=plain --dependency-verification=off
```

工具在build目录从git对象cf128a0生成改动前归一化对照，只替换类名；不是更早缺Preparation的实现。固定512MiB堆，3轮预热、9次新旧交替样本，完整产物先对拍。分配使用ThreadMXBean当前调用线程，不是存活堆峰值，也不含查询工作线程分配。

| 夹具 | 旧→新耗时p50 | 旧→新调用线程分配 |
| --- | --- | --- |
| 稀疏96路 | 10.2591→3.0789ms / 4次归一化 | 1255536→152528B / 次 |
| 密集96路 | 80.3032→84.2803ms / 4次归一化 | 54238160→57546808B / 次 |
| 小批8路 | 0.1296→0.1553ms / 4次归一化 | 51288→51576B / 次 |
| 河流纯计算替身16次查询 | 0.3172→0.2572ms / 次运行 | 1568→1152B / 次运行 |
| 河流纯计算替身256次查询 | 2.9363→2.7808ms / 次运行 | 24608→12960B / 次运行 |
| 河流纯计算替身1024次查询 | 9.8670→9.5581ms / 次运行 | 98336→50976B / 次运行 |
| 稳定高度4096站、16独立列 | 7.6988→0.6693ms / 16剖面 | 未测 |

道路的密集/小批两类并无提速结论，准备/调用成本仍在；不能仅展示稀疏结果。河流提交数分别16→1、256→4、1024→16，只适用于无排队同伴的该基准，有同伴时主动让出会增加提交数；湖泊/河道查询按需求执行，不跨列预取。稳定高度每剖面读取69560→16次，但只覆盖Fake稳定读口；动态路径与主线程接线另有守卫。

原始结果：[道路对](evidence/reuseOptimizationProbe-20261120.json)、[查询任务](evidence/streamsBatchProbe-20261120.json)、[高度窗口](evidence/heightWindowProbe-20261120.json)。长城每10000查询旧→新p50：远未命中1.3823→0.2439ms、近未命中2.2435→0.2218ms、边缘命中0.2169→0.2167ms；工具/JSON放长城。本轮未测实际引擎、写块、总加载、FPS/MSPT或4096工作集，性能数值不可直接外推。

## 第73轮巨树占地发布与长城补铺保护

恢复全部变异后，完整回归 **PASS tests=1712 skipped=0 aborted=0 failed=0**；Fabric编译、API/Fabric remapJar与BUILD SUCCESSFUL，日志`.analysis_tmp/round73-final.log`。本批新增18项：长城真实铺设循环3项及公开入口AST绑定1项、巨树真实API/反射目标/索引发布14项。第72轮3组及第73轮11组负控均为实际JUnit失败，非编译失败，并经git checkout及原字节校对恢复。记录分别为[evidence/wall-pavement-guards-20261120.json](evidence/wall-pavement-guards-20261120.json)、[接线负控](evidence/wall-pavement-wiring-20261120.json)和[巨树负控](evidence/tree-reservation-guards-20261120.json)。

巨树基准运行当前生产TreeColumnProjection和LevelReservations，旧对照冻结默认旋转/未倒伏的逐体素投影并使用旧逐列登记。配置512MiB堆、5轮预热、9次新旧交替样本，每个计时样本128次“投影+新索引发布”；分配为ThreadMXBean当前线程字节/操作。结果见[机器记录](evidence/tree-projection-benchmark-20261120.json)。

| 夹具 | 体素/最终列/合并区块 | 旧→新 p50（毫秒/128操作） | 旧→新分配（字节/操作） |
| --- | --- | --- | --- |
| 高树干重复列 | 4096 / 1 / 1 | 4.2760 → 1.4856 | 101008 → 1824 |
| 分层树干 | 8192 / 1024 / 9 | 8.9515 → 3.8134 | 226536 → 2976 |
| 独立列 | 4096 / 4096 / 25 | 4.8648 → 1.9240 | 104088 → 7984 |

复现：按下方“离线回归”设置JDK21/GRADLE_OPTS后，调用同一安装版Gradle并附加：

```text
-I scripts/benchmarks/tree-projection.gradle :common:treeProjectionProbe --daemon --console=plain --dependency-verification=off
```

运行会覆盖上述JSON为本机新结果。耗时不含外部形状读取、真实世界、GC全停顿、其他模组调度和实际落块；不能据此推断FPS或总加载倍数。形状变换/首次顺序/容量淘汰另由回归对拍，微基准仅测默认变换的三类形状。测试侧Caches类只用于反射边界故障注入，不属于发布包；ServerLevel只作身份键，不能称TreePlacer完整跨模组集成验收。墙脚所有桥隧/装饰通道及安装jar版本仍需现场核对。

## 第52轮同步区块施工事务

代码4abfd3e、守卫53721f7；恢复全部变异后安装版Gradle8.8执行:common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off，PASS tests=1657 skipped=0 aborted=0 failed=0、BUILD SUCCESSFUL（35s）。日志.analysis_tmp/round52-restored-final.log。基线1651，新增5项事务行为及1项真实世界适配器冻结AST比较；Python19+2、旧版契约25API/82资源errors=[]，713生产Java无有效千行超限，最大物理998。

六组行为破坏实际各1断言红：不释放、台账早于装饰、跳过新计划、旧证据、旧拒绝不清、异常仍写台账。世界适配器平均半径加1为第七组静态AST红。详表HANDOFF第52轮，记录round52-mutations.json和round52-mut-world-adapter.log。全部精确checkout并逐字恢复，最终再全量跑绿；不计编译失败。冻结原5b399d5入口逐段比较调用、参数、循环和终态证据，未忽略业务分支。独立VariableTree拼接补分号只是Javac打印差异。

这是行为等价的同步生命周期拆分，不是跨tick生产交付、ServerLevel落块证明或游戏提速证据。没有本轮MSPT/FPS/加载时长测量，协议、几何和配置默认保持。

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

## 第44轮五项后续工作

基线4dc3ef0，先确认status空、pull --rebase最新，再跑1543全绿和Fabric编译。提交依次为33e9583（采样发布修复）、9406227（落列分配）、0b4b1dc（汇总工具）、ab06e08（编排等价迁移，1549全绿单独提交）、44d3583（旧模式守卫）、e7de0fe（离线方向实验）、6d9432b（拒绝假接线）、7f65f65（脚本检出行尾）、d3b291b（真实detail格式修复）。最终完整恢复后回归1570全绿、Fabric通过，日志round44-final-after-mutations.log。新增27项Java=采样5+落列1+旧模式/接线12+方向9；三个新JUnit类均按FQCN登记。独立Python13项不混入Java计数。

### 采样与编排

AccurateColumnBatchTest的门面只读两种入口，在旧生产实现先失败2项（round44-facade-before.log）；修复为共享同一AccurateSamplePublications，仅样本入缓存后通知，三通道数值/容量/模式隔离不变。部分只读网格填入首列后因缺样返回，外层过期空快照只能补剩余三列。生产COLUMN_SHARD_CAPACITY=16384、同桶坐标真实触发LRU，热首列保留、次列确实驱逐、查盘重载不增后端或保存次数；不是手工clear。另验活动列所有者关闭时，网格等待者先于被阻塞的所有者结案。存储端口受控，不代表真实磁盘争用或全堆压力。

LegacyTargetProfile 138/124行（物理/有效），TargetYComputation 526/346→395/274。冻结0b4b1dc旧分支，三次世界操作仅经同顺序端口替代；不共享新编排入口，底层未动的grade算子仍共享，不能把这份对拍说成独立数学证明。八组地形/桥隧/村庄/未知端点/共用剖面/关闭坡限产物、输入交错及跨度副作用相等；另钉住返回剖面、隧道尖峰、末次端点重座和异常传播。AST仅证明DP空臂与直接世界调用接线，false分支中的同名调用会红，不冒充真实ServerLevel验证。清除相邻EndpointProfileOps实际未用的三条导入，依赖基线未放宽。

### 落列分配对照

同JVM/JDK21/512MiB，冻结旧Map、上一轮List和当前实现三方先逐站比较中心及有序位置，再30轮预热、15组轮转交替，ThreadMXBean当前线程分配和耗时p50。轴向四分之一格样条、宽5、负起点；包围盒访问不变。下表只比较上一轮List与当前，历史Map数据仍由探针输出（round44-width-after.log）。

| 站数 | 命中插入尝试 / 最终逐站列条目 | 上轮分配B | 当前分配B | 上轮p50 ms | 当前p50 ms |
|---|---|---|---|---|---|
| 128 | 11430 / 656 | 333200 | 74648 | 0.9545 | 0.9920 |
| 512 | 45990 / 2576 | 1334696 | 292784 | 3.3474 | 3.1067 |
| 2048 | 184230 / 10256 | 5348960 | 1173608 | 13.0233 | 12.5763 |

可变坐标只作contains查询；新列仍new不可变BlockPos，保留HashSet原插入/扩容/输出顺序。新增密集折返夹具断言不可变并在后续调用后再次对拍。单轮微基准：长路分配约减78.1%，短路耗时略增，不能推断FPS、GC停顿或加载收益。

### 方向候选实验

DirectionalLocalCost从真实metric.cost三个方向取当前二次型，保留障碍势和位置倍率；每节点本次求解只准备一次，不改生产FinslerMetric/FMM。2000组方向值与直接公式对拍；四种角度的旋转山地含窄口、优惠倍率的全到达场与冻结候选误差<=1e-8。探针先验整场，再4轮预热/9组交替取p50，计数另跑，避免AtomicInteger成本混进耗时；512MiB。日志round44-directional-probe.log。

| 地形 | 生产p50 ms | 冻结候选p50 ms | 缓存候选p50 ms | 梯度求值 冻结→缓存 |
|---|---|---|---|---|
| 0°平面 | 0.8665 | 7.7013 | 5.2360 | 403346→3264 |
| 45°平面 | 0.3440 | 9.0648 | 4.9607 | 403279→3264 |
| 0°山地 | 0.3135 | 9.6234 | 3.2395 | 224591→1872 |
| 30°山地 | 0.2121 | 11.0761 | 3.0515 | 224859→1872 |
| 90°山地 | 0.1858 | 11.1931 | 3.0582 | 224591→1872 |

冻结/缓存候选全场最大差7.1e-15～1.8e-14。30°山地线程分配7232400→124296 B，但0°平面61584→109480 B（JIT可能消除旧临时对象，新增缓存也有成本），不宣称所有场景都减分配。三解析面目标(20,12)，步长4→2→1，绝对到达误差：0° 0.488511→0.321491→0.202834；30° 0.469263→0.308153→0.194044；45° 0.476815→0.312784→0.196783。测试钉住两次误差均下降，仍无一般非凸度量收敛、真实桥隧或挖填收益证明。候选仍明显慢于生产且缺分页/早停/取消治理，仅在测试目录，禁止切默认或作回退。

复现落列和方向实验：

```powershell
& $gradle -I scripts/benchmarks/width-raster.gradle :common:widthRasterProbe --daemon --console=plain --dependency-verification=off
& $gradle -I scripts/benchmarks/directional-cost.gradle :common:directionalStudyProbe --daemon --console=plain --dependency-verification=off
```

### 证据汇总工具

```powershell
python -B -m unittest discover -s scripts/evidence -p 'test_*.py' -v
python -B scripts/evidence/summarize_route_evidence.py --session new-save latest.log --encoding utf-8-sig --output summary.json
```

--session NAME LOG可以重复；相同会话的轮转文件按旧到新提供，不同存档副本/重开分开命名。GBK控制台产物显式--encoding gb18030。逐行读取，格式错误保留位置并返回非零；输出仍包含有效记录。按会话+完整观测ID关联，区块按施工指纹隔离，重复只计观测不多计完工块，保留放弃后成功和后来失败；未知owner逐条保留不拼接。detail作为尾部原文，防止内嵌Choice重复geodesic/selected；该缺陷先加夹具红，再修。终态校验未携带施工指纹，不能自动归到某个版本；没有完整尝试ID不编造尝试次数，缺日志不代表没有生成，阶段齐全也不宣称全路落成或可通行。

临时Java探针实际执行RoadPipeline候选回调和RoadBuildEvidence格式器，产出3个真实端点ID，再由Python CLI读入：errors=0、1条齐全链、2个隔离指纹，完成区块只计1，修补后NOT_ATTEMPTED不继承旧完成，roadCompletion仍not-proven。记录round44-evidence-actual.log/round44-evidence-summary.json；这是受控输入，不是现场日志，未运行PipelinePlanner的ServerLevel适配。

### 变异、恢复和未验证项

23组全部实际红：12组生产（其中2组AST窄接线）、2组测试目录方向候选、9组工具；14组Java共产生21个目标JUnit失败，9组Python共14个断言失败（含subTest），无编译失败冒充红。第一次Python检出因Git自动CRLF被逐字检查拦下，新增限定LF后完整重跑；修复真实detail格式后又重跑全部9组Python。每项finally精确git checkout并与原字节比较。表见HANDOFF第44轮，记录round44-cases.json、round44_mutations.py、round44_python_mutations.py、round44-mutations.json、round44-mutation-run.log、round44-python-mutation-run.log及round44-mut-*.log。

最终701个生产Java，有效行>1000为0、>800为2。未验证4096真实堆/MSPT/加载尾延迟、真实磁盘/卸载/跨实例压力、完整世界施工与三模组Mixin次序；未变更存档、配置键、持久枚举、长桥弯、村庄包围盒、crossing、双平滑默认、spanWriteback/QualityGate默认或废弃GPU。未改外部模组，未打包部署。

## 第45轮中文诊断与READY提交测量

开工基线1570/0/0/0，Fabric编译通过；本轮pull --rebase因GitHub443连接超时失败，交付前重试，不将本地提交误报远端同步。新增20项Java至1590（日志5、隔离2、READY计时2、有序对拍2、内容摘要6、中文Javadoc3），新类显式登记；独立Python17项。最终完整回归记录在round45-final-after-mutations.log，零skip/abort，Fabric BUILD SUCCESSFUL。

### 中文日志与注释

701个生产Java、168个日志文件、842处调用全部有中文格式说明。保留原标记/字段/占位符/等级/节流，不为翻译增加逐列INFO。修正CPU-only迁移、长城兼容等待和隔离成功/失败措辞；FileStorageLogTest用真实Windows删除共享锁验证失败，成功后检查原字节与移走结果。

扫描635段原仅英文Javadoc，保留仍有价值的公式/历史解释，修正旧A*、精确GPU、缓存深不可变、清表O(1)、线程预算与启动门描述。另补23处只有标签的正文，清除52段迁出后挂错成员的残留。168个生产文件去注释后的内容与181909a逐项一致；编译器提取3262处Javadoc正文均有中文。守卫只证明正文中文存在，不证明语义完整或所有成员都有文档；行注释与脚本资源没有逐句穷尽审查。详见[日志与源码说明](LOGGING.md)。

### 临时文件实盘测量

JDK21同JVM、512MiB，3轮预热9组样本；READY冻结2b4d06e与当前成对交替，先做一次真实提交形成有序快照。每组真实原子文件替换、重开并比较字节；每阶段分别取p50，不能相加中位数冒充端到端。sort阶段包含二分插入与ArrayList移动，算法仍需O(n)复制和序列化。

| READY区域数 | 冻结构建p50 ms | 当前构建p50 ms | 冻结排序ms | 当前插入ms | 冻结写盘ms | 当前写盘ms |
|---|---|---|---|---|---|---|
| 514 | 0.1968 | 0.0969 | 0.0678 | 0.0061 | 1.5958 | 1.5880 |
| 8194 | 1.5935 | 0.4619 | 1.0110 | 0.0127 | 2.0698 | 1.9579 |
| 65538 | 11.6632 | 4.7581 | 8.2096 | 0.0510 | 3.9516 | 3.8552 |

当前最大组写盘p95=7.1049ms。连续1024次增长，两版都写1024次，累计8,308,062字节，最终16,335字节；累计构建86.392→42.212ms，写盘1550.632→1558.322ms。构建节省不等于磁盘改善，保留同步提交。额外有序列表保留每区域一个引用；Long与集合共用。只在writer成功且开放时发布该快照，故障/重开随机96步逐字对拍，非ASCII指纹验证UTF-8字节计数。

| 台账条目 | 收集p50 ms | 排序p50 ms | 编码p50 ms | 写盘p50 ms | 写盘p95 ms |
|---|---|---|---|---|---|
| 512 | 0.1954 | 0.1254 | 0.1555 | 2.5158 | 3.0649 |
| 8192 | 1.4488 | 0.8627 | 0.8777 | 3.8349 | 4.7780 |
| 65536 | 8.8738 | 6.9050 | 4.7784 | 5.3451 | 7.7244 |

台账每次flush后重开逐项验证，干净状态不重复写。以上都是临时文件，无真实存档竞争、设备flush或停服尾延迟保证。没有据此改台账异步。ACTIVE真实入口只在主世界、preflight最多8活动；合成扫描p50=0.02181微秒/次，索引0.00153；16世界128活动压力原型扫描0.48987微秒不代表生产规模。决定不增加ACTIVE世界分桶。

```powershell
& $gradle -I scripts/benchmarks/persistence-stages.gradle :common:readinessActiveProbe :common:readinessWriteProbe :common:constructionLedgerProbe --daemon --console=plain --dependency-verification=off
```

### 内容版本与端到端

RoadBuildEvidence的c1-SHA256诊断摘要包含有序站中心XYZ、铺装列XYZ和分站边界、targetY、跨度端点/类型/顺序、材料状态及属性、宽度/类型/所有者。不修改RoadFingerprint、台账键、CODEC或schema；没有缓存摘要，因为嵌套positions未保证深不可变。仅原影子诊断开启时生产计算，但每条观测仍扫描整路。它不含实际世界/配置/模板内容，不能替代完整施工环境版本。

Python将同摘要校验和区块记录放versionsByContent，旧无摘要记录独立保存，不继承给新内容；身份指纹汇总明确只表示身份。17项独立测试通过。受控Java探针调用真实粗候选回调及格式器→Python CLI：3条连接ID、2个内容摘要、旧内容有校验及ABANDONED→COMPLETED历史、新改高内容无校验且NOT_ATTEMPTED，错误数0，整路仍not-proven。UTF-8输出须使用utf-8-sig，误按GB18030读取会失败；没有用替换字符吞掉解码错误。记录round45-evidence-actual.log/summary.json，不冒充真实ServerLevel执行。

### 变异与边界

25组全部红，共29个目标断言失败：21组Java（含6日志守卫实现及1注释守卫实现）和4组Python；编译失败不计红。每次精确checkout并逐字比较，最终全量恢复。逐项表见HANDOFF第45轮。新增注释守卫先用正反夹具发现Javac.toString会转义中文，改读TextTree原文；标签中文不能代替正文。首次行数守卫拦下一处重复说明超物理1000，压缩重复注释而未放宽守卫。

有效代码>1000为0、>800为2，全部生产文件物理行也≤1000。未验证真实4096堆/NoiseChunk、FPS/MSPT/加载尾延迟、完整桥隧/保护落块、三模组最终Mixin次序、真实存档磁盘争用。未改外部仓库、crossing、双平滑、跨度回写/QualityGate默认、长桥正弦、村庄包围盒或存档协议，未制作部署发布件。


## 第46轮：冻结、缓存、施工让出与旧版契约（2026-11-17）

基线1590/0/0/0。完整回归命令仍为已安装Gradle8.8的 :common:roadJUnitRegressionSuite :fabric:compileJava --daemon --console=plain --dependency-verification=off。最终日志round46-final-after-mutations.log；Python证据19项、旧版契约拒绝夹具2项，独立契约核对errors=0。测试数量与实际结论在HANDOFF第46轮收口。

缓存探针：加 -I scripts/benchmarks/persistence-stages.gradle :common:roadValidationCacheProbe，512MiB，同JVM3组预热、9组交替顺序、每组10000次get。最终32/1024/8192站内容键p50为493.0/14094.1/116143.7ns，弱身份键26.0/18.6/19.0ns，p95为818.1/14562.6/135034.5对45.8/25.7/19.6ns；两边热get分配均0B（初版弱查询键32B已改复用并守卫释放）。5列分站冻结构造一次测量29.8ns/24B→85.3ns/131.9B；同不可变坐标不复制对象，但仍复制列表。此项是防发布后改动的一次成本，不能只报缓存收益；此构造单组数据不是稳定p95。日志round46-final-noallocation.log。

python scripts/benchmarks/evidence_history_probe.py：同一路线重复1000/10000/50000条校验日志，detail峰值1048250/10109472/50427919B，summary(16)39443/39161/41041B；各次核对计数、最新结果和not-proven。summary仍保存全部道路、版本、区块，未宣称全输入常量空间；原始耗时受tracemalloc影响，不当现场性能。

生产施工默认4ms在区块之间让出，原8块限额仍在；首个未结案块至少推进一次，已结案前缀不饿死施工；等待预算不算失败、不撤手绘票据、不扣重试。单块、候选探查、台账flush不受强中断约束，可能超预算。可选deferredChunkTimings逐块记录replayUs及exception；需真机量尾延迟后才评估进一步切片，不能保证4ms总MSPT。

旧版基线2c6547a。python scripts/parity/verify_legacy_contract.py核对25个API签名token、82个非语言资源、11个网络接收注册、13个实际手绘键位注册及旧语言键（zh仅豁免10个退役算法选项）。38个删除Java路径另全量查旧生产token引用并手核迁移链，见FEATURE-PARITY-CHECK。没有真实存档的游戏等价结论。新CI已接Python工具，不声称已在GitHub实际运行。

变异共17组：12组Java、5组Python，每组目标断言实际失败，编译失败不计，全部精确checkout逐字恢复。分组和文件见HANDOFF，原记录round46-mutations.json、round46-extra-mutations.json、round46-python-mutations.json、round46-parity-mutations.json。初始增加“桥跨尖峰必须失败”夹具错误，被正向回归拦下；改为target-height-size的明确失败，不改生产规则。


## 第47轮：摘要缓存、共桥宽阶段与冻结分站复用（2026-11-17）

开工main@8f3cb2a工作区空，pull --rebase成功；基线1600/0/0/0，Fabric通过。本轮新增10项，完整回归1610/0/0/0及Fabric编译通过；最终收口日志见HANDOFF第47轮。Python证据19项、旧版契约拒绝2项及独立冻结契约errors=0。全部704个生产Java物理行≤1000（最大998），有效行超1000为0；中文日志/文档、架构、回归登记守卫同套执行。

复现命令：沿用JDK21、已安装Gradle8.8与--daemon --console=plain --dependency-verification=off，添加-I scripts/benchmarks/persistence-stages.gradle，执行:common:bridgePairProbe :common:roadContentProbe。512MiB；三组预热、九组交替比较，候选含建树/查询/排序/摘要校验。bridge三策略共用枚举内核，避免把不同JIT调用点误报成算法差异。每组32/128/512规模重复80次，1024规模8次；p95为九组中最大值，只是合成批次统计，不是现场尾延迟。

| 桥数/夹具 | 原逐对p50（微秒） | 自适应p50（微秒） | 全对检查 | BVH叶子检查 | 实际候选 |
|---|---:|---:|---:|---:|---:|
| 512/稀疏 | 99.98 | 44.02 | 130816 | 1792 | 0 |
| 512/8座分簇 | 102.38 | 60.86 | 130816 | 1792 | 1792 |
| 512/全重叠 | 328.38 | 333.90 | 130816 | 130816 | 130816 |
| 1024/稀疏 | 377.48 | 92.31 | 523776 | 3584 | 0 |
| 1024/8座分簇 | 413.69 | 131.69 | 523776 | 3584 | 3584 |
| 1024/全重叠 | 1305.56 | 1304.49 | 523776 | 523776 | 523776 |
| 1024/20000格长桥重叠 | 1300.58 | 1302.66 | 523776 | 523776 | 523776 |

日志round47-final.log。最后两列叶子检查是在强制索引的对照支路计数；密集/长桥生产选择直接枚举，不能把该列说成生产减少。512以下不建树；64个确定性探测对中命中超过8个也不建树。探测仅选择等价枚举方式，误判影响耗时不影响桥候选。密集强制索引1024场景13.25ms、长桥47.48ms，已据此避免一律建树。阈值是本夹具上的保守交点，非任意分布收益保证。BVH逐层排序建树O(n log²n)，逐源只保存当前候选，密集最坏仍二次枚举与排序。DEBUG保留原全对诊断；不能把减少包围盒比较外推FPS或整局加载倍数。

候选守卫：100组100盒、五种半径、负坐标/长盒/密集对照原有闭区间判据与原索引顺序；极端int输入保持旧溢出口径。真实BridgeShareService对冻结的8f3cb2a全对实现：512道路稀疏/局部密集逐道路、逐站、跨度和完整Stats一致，局部密集确有吸收，不以空候选证明等价。固定采样密度策略仍可能错判异构分布，此处只承诺候选/产物等价。

诊断摘要：32/1024/8192站、每站5列，热缓存p50分别0.0906/0.0891/0.0586微秒，未缓存20.50/253.52/2172.39微秒；热get分配0B，未缓存约2867/58120/459528B。摘要编码c1逐字不变；弱键有界，不强留道路，首次并发允许重复纯计算，64任务覆盖冷实例。仅影子证据开启的调用链受益，不是生产默认寻路优化。

分站引用复用探针同夹具：8192站复制335.13微秒/1409064B，复用31.05微秒/32840B；1024站30.76微秒/118856B对16.61微秒/4168B。此构造环节是三轮预热后单组128次均值，不当稳定p95，分配含新外层列表。只改存储冻结、锚点、影子快照三处；模型构造仍冻结调用方可变列表和游标，存储null列表归一与观测拒绝null元素保持。

变异12组均实际断言红，精确checkout后逐字比较恢复。表见HANDOFF；11组记录round47-mutations.json，补充非法列记录round47-extra-mutations.json。半径最初夹具两桥同叶，开区间变异仍绿；改成9盒跨叶临界夹具后才红，不把首轮全绿算成功。生产接线一组为AST守卫，其余行为/引用复用/操作数守卫，不混称真机产物守卫。

新增盲点探针:common:shardStampProbe实测STAMP_BLIND_SPOT contentChanged=true maxMtimeUnchanged=true aggregateAccepted=true：非最新分片同长改写mtime1000→2000，另一分片mtime3000，真实computeShardStamp与matches仍接受。只证明统计戳非内容证明；没有修改schema或快速通道，也没完成真实加载后分片修复回归。当前查询从NBT道路聚合重建内存索引，不能因此推断道路已经丢失。此存量缺口列ROADMAP优先任务，代码误导性注释已纠正。


## 第48轮：派生分片流式验证与故障修复（2026-11-17）

基线1610/0/0/0，pull --rebase因连接重置未成功，沿本地0cbce38继续。先1926e46等价迁出原验证器及伴随文档类型，1610与Fabric通过；再3ca53ba修复内容验证/IO/重试。新增11项显式登记，最终PASS tests=1621 skipped=0 aborted=0 failed=0，Fabric BUILD SUCCESSFUL，日志round48-delivery.log。Python证据19项、旧版契约拒绝2项及独立契约errors=0。

真实建库先红：同长非最新分片version2→9、mtime1000→2000，另一文件mtime3000且旧统计戳命中；内存道路可读但磁盘仍9（round48-before-fix-stamp.log）。不据此称道路丢失。普通启动原实现传空旧快照，不受引用相等跳过影响；实际重试缺口用NOSHARE_DELETE阻止首次替换、解锁再新增道路复现，现修复意图保留至成功。

性能复现：安装版Gradle8.8/JDK21，添加 -I scripts/benchmarks/persistence-stages.gradle，执行 :common:shardVerificationProbe --daemon --console=plain --dependency-verification=off。512MiB，每个真实临时文件一条long引用；冻结1926e46旧验证器，两组预热、五组交替全量比较，旧全量每轮删统计戳。旧完整含统计扫描与内容验证，新单次列目录后流式核对，引用逐项相同。

| 文件数 | 旧统计扫描p50 ms | 旧完整验证p50 ms | 新流式验证p50 ms | 旧完整最大ms | 新流式最大ms |
|---|---:|---:|---:|---:|---:|
| 1000 | 53.74 | 118.36 | 39.77 | 126.14 | 40.53 |
| 10000 | 568.77 | 1137.34 | 369.26 | 1163.65 | 387.23 |
| 60000 | 6017.65 | 11184.56 | 2970.12 | 15588.90 | 3218.18 |

日志round48-probe.log。新建后首次读旧/新分别187.44/52.32、1317.79/383.42、14258.90/3521.06ms；未清OS缓存，不能叫冷盘。期间有本地Gradle活动，存在主机干扰；五组最大值不当p95，不外推FPS/总进档倍数或任意磁盘收益。新任务/Future最多8个、旧每文件一个；文件列表/预期表/seen仍O(文件数)。

按schema2、坐标、版本与有序long引用核对，拒绝重复必填字段、小数、尾随文档；generation沿用旧口径忽略，未知扩展跳过。旧shard-verify.json留盘但不信任。Windows NOSHARE_READ验证索引/分片IO不隔离、不改原索引、拒绝新写；NOSHARE_DELETE验证修复重试。12组变异全部实际断言红且逐字恢复，表见HANDOFF，round48-mutations.json及逐项round48-mut-*.log。新建库/重开9项、解析器2项均实际执行。

正常磁盘读取没有硬超时；取消后等待工作者退出最多5秒，迟到工作者只读且存储整体只读降级。无真实冷盘/玩家存档/外部并写原子快照验证。CODEC、chunk2、ledger3、fingerprint1和字段名保持，未制作部署件。


## 第49轮：施工只读跨度与输入阶段测量（2026-11-17）

开工main@9fa7b6e干净，pull --rebase成功；基线1621/0/0/0及Fabric通过。61eb191先迁出PathFeature原五类跨度算法，4项新增至1625；1d20287共用索引/弱身份只读复用，最终1631/0/0/0及Fabric通过，round49-delivery.log。新增9项掩码行为/性能/缓存测试和1项生产AST接线。Python证据19、契约反例2及旧版契约errors=0。707生产Java物理最大998，有效/物理均无千行超标。

复现：JDK21、安装版Gradle8.8，加 -I scripts/benchmarks/persistence-stages.gradle :common:constructionInputsProbe --daemon --console=plain --dependency-verification=off。512MiB，32/1024/8192直线站、8/128/1024跨度（全部枚举类型）；五类逐项对照冻结9fa7b6e算法。三组预热、九组交替正反执行四策略，每组128次；当前线程分配ThreadMXBean。冷键模式每次清表并重新提取中心/建索引/缓存，热模式同一实例，容量128。结果日志round49-precommit.log：

| 站数 | 原算法p50 μs | 新单索引计算p50 μs | 新含缓存冷构建p50 μs | 热命中p50 μs | 原分配B | 新含缓存冷构建B | 热命中B |
|---|---:|---:|---:|---:|---:|---:|---:|
| 32 | 8.139 | 2.379 | 3.936 | 0.092 | 12280.0 | 3160.1 | 0.0 |
| 1024 | 76.634 | 23.605 | 34.993 | 0.053 | 450600.0 | 98251.9 | 0.0 |
| 8192 | 561.470 | 137.738 | 194.193 | 0.027 | 3672856.0 | 797176.0 | 0.0 |

原算法精确端点路径读取中心表5n次，新单索引n次，实际计数守卫为5120→1024。重叠区间仍逐站标记；非精确端点仍按原全站扫描找近站，最坏复杂度没有变成线性。热命中不取出数组、不建立中心列表；私有byte[n]承载5标志，结果附预计算计数。按对象身份缓存，不按持久指纹/内容摘要；只存最近一套坡道配置，值没有道路/中心线反向引用。冷并发允许重复计算，异配置会替换但不会返回错误版本。

其余阶段单独七批测量，用于判断边界而非稳定跨方案比较：8192站targetY拆箱40.690μs/32976B、copyForUser 1.988μs/16496B、已持久高度服务0.013μs/48B；无持久高度Fake采样212.931μs/268944B，每次134次getHeight。32/1024/8192全路中仅当前16站属同一块，半径4，因此134次不是整路真实采样负载。Fake只返回确定性高度，不含真实区块/锁/磁盘开销；各规模测量有JIT/GC波动，不据此推导游戏规模曲线。桥副本此处bridge disabled/null配置，没有多桥区间数组复制，不能代表模板桥负载。targetY会被拒绝隧道退让原地修改，绝不作为只读数组跨区块共享；HeightProfile仍世界相关，不进本缓存。稳定性能结论仅限上方交替掩码对比。

100组80站随机曲线×正反序×4坡道（800对拍），含全部跨度类型、端点漂移和缺失；另显式断言同XZ最后站/近站并列最早站、4格闭区间、桥头精确端点及闭坡道扩张、空路/空端点/极端int保留旧口径。缓存测试覆盖改跨度新对象、结构相同不同对象、反站序、配置往返、诊断副本隔离、128容量、真实弱引用clear/enqueue重建及64任务4配置并发。缺失跨度计数在热命中仍按区块累计，不因缓存改变口径。物理归一化会生成新RoadData（RoadPhysicalReuseNormalizer.withPhysicalReuseSpans/rebuildReuseSpans），这些路径可能持续冷构建；未测真实命中率/争用，不声称每区块都热命中。

11组变异各1实际断言红，精确checkout并逐字恢复，round49-mutations.json及逐项round49-mut-*.log；表见HANDOFF。生产接线一组是AST静态守卫，不能冒充实际ServerLevel产物。未量FPS/MSPT/真实进档、4096完整工作集或跨模组最终事件；未改变schema、几何、crossing或施工预算/并发默认。


## 第50轮：六项后续的冻结对拍、性能与游标边界（2026-11-17）

开工main@3e2af6a干净；pull --rebase连接重置，本地已验证基线1631/0/0/0及Fabric通过。源码分项提交2eb8aa6、8ed6c1f、8124a93、d084c61、60d1102；探针5f29f98、站正文冻结8dc77e5。最终完整PASS tests=1646 skipped=0 aborted=0 failed=0，Fabric BUILD SUCCESSFUL，round50-delivery.log；Python证据19、契约拒绝2及旧版errors=0。709生产Java物理最大998，有效/物理均无千行超标。没有部署游戏。

新增15项显式登记：桥2、编译/归属4、高度2、游标/状态4、异构候选1、生产接线1、冻结站正文1。桥80组随机300站及95/96/97/240弦长，短targetY/无目标、空站、负/越界、曲线pointAt/映射/桥头和同段引用一致。编译对12组×7负/正区块×2桥开关完整比较slice道路/站序/路口和256列的目标/密度/占用；每列40高度采密度。归属30组混合类型/反道路序与冻结旧归一化完整RoadData一致；Preparation两模式、同实例及结构相同不同实例隔离。单精确索引80次get，不是原每类80次。

高度5窗口半径×6坡限×2开关=60组，Fake每次读随事件数改变；逐次getHeight事件、Math.round和双向坡限输出一致。没有把世界读结果缓存，短路持久targetY零世界读取。候选600盒三异构/长瘦分布逐源逐项有序对拍冻结树，原生产吸收对拍继续通过。游标1..32批量全部恢复对拍站操作/随机/拒绝/装饰序列；零预算不推进、取消/异常不可重放、暂停不能结案。假操作不是实际ServerLevel写块；额外Javac AST将冻结原站循环与生产闭包正文仅替换循环控制/状态载体后比较，站正文逐句相同。外层finally资源释放/装饰/台账有AST接线，非跨tick真机证明。

探针命令：JDK21安装版Gradle8.8，加-I scripts/benchmarks/persistence-stages.gradle，运行:common:bridgeStageProbe :common:compileStageProbe :common:heightStageProbe --daemon --console=plain --dependency-verification=off。512MiB，同JVM3预热7交替批次，ThreadMXBean线程分配，冻结3e2af6a旧实现。桥构建64次/批、全路查询128次/批；候选4次/批、compile8次/批、normalize16次/批、高度64次/批。日志round50-probes-fixed.log：

| 场景 | 原p50 μs | 新p50 μs | 原B/次 | 新B/次 |
|---|---:|---:|---:|---:|
| 桥4096站64段构建 | 126.039 | 29.820 | 261936 | 27704 |
| 桥4096站全路查询 | 1575.296 | 10.423 | 3092992 | 0 |
| 候选600盒稀疏 | 108.975 | 108.850 | 14400 | 14410.3 |
| 候选600盒混合簇 | 752.250 | 389.500 | 277936 | 184896 |
| 候选600盒混合长盒 | 1594.250 | 911.075 | 625576 | 337040 |
| 完整compile八路80站 | 10510.600 | 10478.938 | 38976757.7 | 38718926.9 |
| normalize八路80站 | 12135.250 | 9546.869 | 45556392.6 | 38300336 |
| 高度32站 | 11.628 | 3.100 | 15024 | 336 |
| 高度8192站 | 179.708 | 163.683 | 268944 | 65616 |

桥查询含全部非桥站，不能当生产只查桥站的成本；完整compile旧冻结编译器仍调用当前归一化，两边共用当前归一化以隔离索引差异，normalize另对冻结旧版，不叠加两项收益。完整compile耗时基本持平，只支持少量分配减少。稀疏候选基本持平，无“所有场景更快”结论；候选探针不含建树，Integer[]建树未改。高度Fake只在当前16站返回确定高度，不代表真实区块IO；没有帧时/进档倍数。首次探针因独立JVM漏Bootstrap失败，补引导后重跑，失败不算结果。

14组实际变异均各1断言失败，精确checkout逐字恢复：13组round50-mutations.json及站邻点改坏round50-extra-mutations.json。完整表见HANDOFF。静态接线及正文比较共2组，不冒充真机行为。正常全部恢复后再完整回归1646。未改schema/CODEC/字段/枚举名、长桥弯、包围盒、crossing、双平滑或跨度/QualityGate/CPU-only默认。施工仍同步，未取消4ms区块间预算，单站/单块无毫秒上限。


### 第50轮追加：生产站动作端口化验收

最终代码97c4228：PASS tests=1651 skipped=0 aborted=0 failed=0，Fabric BUILD SUCCESSFUL（round50-final.log，39s）。相比本轮基线1631新增20项，全部显式selectClass登记。Python证据19+契约拒绝2通过；旧版契约errors=[]、25API/82资源。712生产Java有效>1000为0，物理最大998；PathFeature门面已迁出约190行站动作。依赖只增加既有pathlogic边的重复类型引用（config78→81/core196→202/decoration21→26/util9→10/worldgen3→4），基线注明拆分原因，无新方向。

ConstructionStationWorkTest用真实生产编排+Fake世界端口，与独立冻结3e2af6a原for循环比较4模式×24批量：模板桥/程序桥、外区块中心、跳过/浮标、复用/保护站、硬保护隧道、地表退让、退让再拒绝、普通铺路拒绝及装饰。比较完整事件、随机余量、拒绝映射/站标记、可变高度和最终状态；另测成功末站结案、取消与半写异常不可重放。FrozenConstructionStationWork仅边界替换，分支不跟新代码共用；正文AST仍对原资源，适配器13个方法/12种操作有AST一一转发守卫。它证明操作委托次序，不等于每个桥隧内部真实方块序列或ServerLevel集成。

补6组变异见HANDOFF：跳过全部站/模板甲板+1/地表退让拒绝不标记/退让高度+1/关闭装饰均为生产分支断言红；真实高度转发+1为静态AST红。全部精确checkout逐字恢复。初次“地表退让拒绝不标记”仍绿，因为夹具只让普通站10拒绝、退让站7总成功；补站7真拒绝并明确断言后重跑红，不将首次绿计作交付。新增5项验收（实际分支/成功/取消/失败4+适配器1），完整1651。生产仍runAll，外层事务未暂停，未启用新默认，没有游戏内帧时/加载时间结论。
