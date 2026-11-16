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
- 复现内核数据：[FMM 基准](../scripts/benchmarks/Measure-Fmm.ps1)和[区块编译基准](../scripts/benchmarks/Measure-ChunkRaster.ps1)。参数及输入、预热、对拍和数据见[交接记录](HANDOFF.md)。记录单位、样本和是否包含调度、查盘或落块。

## 最新构建的真机对照

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
