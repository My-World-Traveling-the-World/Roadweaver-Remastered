# RoadWeaver 开发指南与交接

## 2026-11-25 · 0.9.127 · 完整失败快照

**本轮最终交付**：实现66799ac已推GitHub main，正式0.9.127已部署WAYFARING；SHA256=`8981ae3427b6f78ca12ff7bc9031bede11a835eb29d3d3030e179057f3ea11c6`，与恢复全部负控后的1899项回归/正式构建件一致。与长城0.7.147同批备份在`D:\minecraft\Plain Craft Launcher 2\.minecraft\backups\wayfaring-profile-cap-20261125-233357`，完整清单build/profile-cap-deployment.json。部署前未检出javaw，未修改玩家存档；三条现场锁高失败仍待新快照，不因诊断已接线就宣称已修。

1. **改动**：TargetProfileSolver在实际repair抛已分类ProfileConflict.Rejected或返回empty时记录原输入；全长terrain、XZ、crossing、pin/fixed、覆土、deckFloor、cut/fill/step/perTwo和拒绝见证均保留。实际存档/roadweaver-diagnostics/profile-failures/维度内按内容摘要去重写入，不受AB配额和2048站截断影响；成功不创建快照、不写盘。损坏文件缺预算/约束时明确拒读，诊断异常不替换原施工拒绝。
2. **验证**：新增9项测试登记进显式套件，恢复四组变异后全量PASS tests=1899 skipped=0 aborted=0 failed=0，Fabric正式build、API-SEPARATE及三个验包负面对照成功，日志build/profile-failure-release.log。真实生产入口构造负锁高与覆土冲突后，写盘→重开→相同repair重放得到同一见证；4097站末端负锁高和桥下界保留。世界目录来源另用编译器AST核实。部署结果另记。
3. **未验证**：客户端最新日志仍为11月24日，现场三条LOCKED_HEIGHT尚无这版全约束快照，仍未修复或重新验收。CUT_BUDGET/SLOPE_WINDOW等还需实际物理隧道掩码，不能只拿初始约束冒充完全重放；一般运行时异常不在本快照范围。未测整合包帧率、耗时或联机地图。
4. **负控**：丢弃拒绝快照、抹掉deckFloor、生产路径改全局logs、诊断异常替换原拒绝，四组均实际红；每轮git checkout精确恢复暂存正确源码。表build/profile-failure-mutations.json，日志profile-failure-mutation-*.log。未增加架构反向依赖预算。
5. **下一步**：新普通世界复跑三条现场连接，按快照重放判定入口锁高、接坡长度、局部搜索或后续分类问题，不放宽坡限猜修。crossing解耦和双平滑裁决仍保留原决定；河流查询优化先采真实分布，避免早停查询被全量收集拖慢。
6. **历史与回退**：基线a74c7b7，行为新增（失败诊断），版本0.9.127；道路几何、配置键和存档schema均不变。只写故障诊断，不操作既有玩家存档；回退恢复旧JAR即可。

## 2026-11-25 · 0.9.126 · 独立桥组甲板不串高

**本批最终交付**：`2.3.1-1.21.1-plaincraft-direct-core.0.9.126` 已部署WAYFARING，代码提交 `317563b1`；恢复所有负控后的最终回归1890项、零跳过/失败/错误，正式构建成功。部署件与构建件SHA256一致：`e48c8dc7c745e2507e73e73469eb345ed2a3c5198d6d77120705e02216d7c5f2`。旧包备份 `D:\minecraft\Plain Craft Launcher 2\.minecraft\backups\wayfaring-optimization-20261125-204514`，四包完整清单在SeedGen补丁的build/optimization-deployment-manifest.json。未启动完整整合包验收画面或帧率，未改玩家存档。

1. **改动**：enforceAdjacentBridgeContinuity在远距离断组后只保留本组甲板高度。三桥100/70/60、站区间2..5/2000..2005/2010..2015从错误100/70/100改为100/70/70；24站内相邻桥仍不降低前桥基准，非桥跨度仍忽略。
2. **验证**：新增三桥、乱序混入浅滩、24/25边界和本组新高桥测试；PASS tests=1890 skipped=0 aborted=0 failed=0。恢复变异后的完整构建结果另记部署记录，不拿负控前成品部署。
3. **未验证**：此函数生产调用位于TargetYComputation的保留入口，默认DP成功路径不调用；没有把它宣称为三条LOCKED_HEIGHT的原因。未测真实桥组画面或整合包耗时。
4. **负控**：恢复Math.max跨组累计→thirdBridgeInNewGroupNeverInheritsDistantHighDeck实际红，其他两条新组合也识别错误；git checkout精确恢复暂存正确文件。日志build/bridge-group-mutation.log。
5. **下一步**：三条现场锁高仍需新日志及完整约束录制；不能凭缺失的夹具移动端点或放宽坡限。长城动态收口补采与SeedGen模板树适配由各自仓库交接记录，不混入道路测试。
6. **历史与回退**：基线5f54e27，行为修复；不变更桥长、正弦、桥面水位公式、存档或API。0.9.125的窗口优化和0.9.124的线性支撑随0.9.126一起交付。

## 2026-11-25 · 0.9.125 · 纵断面窗口不预建代价

1. **改动**：拆出prepareBounds，沿用原锁高、桥面下界、覆土与坡限输入；修复的两次窗口检查不再各建一份随后丢弃的代价矩阵，实际DP仍用原CostModel。已有Prepared及prepare/solve签名保持不变。
2. **验证**：PASS tests=1887 skipped=0 aborted=0 failed=0；新增独立手算窗口和覆土冲突测试。六条现场地形、负锁高、桥锁冲突和洞口回归继续通过；Fabric compileJava成功。最终成品构建结果另记部署记录。
3. **未验证**：未测整合包帧率或预热耗时，不报加速倍率；三条LOCKED_HEIGHT仍缺本次失败的完整输入，不改变锁高或放宽坡限。新发现远桥分组累计甲板泄漏仅影响保留入口，默认DP成功路径不执行，不是三条失败的已证原因。
4. **负控**：让prepareBounds重新构造代价矩阵→boundsOnlyKeepsNegativePinsDeckFloorsAndCoveredWindowsWithoutCost实际红；git checkout精确恢复暂存正确文件。日志build/profile-bounds-mutation.log，脚本build/check_bounds_mutation.py。
5. **下一步**：修复独立桥组高度传播，并用三桥/乱序/混入非桥组合验证；三条现场锁高需新日志和约束录制后重放。crossing解耦和双平滑裁决仍保留原决定，不凭旧日志猜修。
6. **历史与回退**：基线87b291f，行为等价与性能改动；少两次无用途代价矩阵构造，不等于实机加速倍率。存档格式、路面几何和世界隔离不变，部署前保留旧成品。

## 2026-11-25 · 0.9.124 · 支撑高度线性计算

1. **改动**：道路宽度支撑从复制排序改为一次扫描最小/次小值，含重复值；TargetTerrainInputs复用同站结果，不再算第二次。施工高度、坡限、跨度和存档格式不变。
2. **验证**：恢复变异后全量PASS tests=1885 skipped=0 aborted=0 failed=0；Fabric build、独立API验包及3个拒绝夹具成功。新增900组冻结排序对拍，含0..257宽、重复、负数及int边界，输入数组不被修改。
3. **未验证**：未测整合包实际耗时或帧率，不能报加速倍率。三条首次LOCKED_HEIGHT仍无真实约束录制；旧TSV267行对三个端点匹配均0，不能通过放宽坡限或锁高猜修。
4. **负控**：返回最小值代替次小→linearFootprintSupportMatchesFrozenSortIncludingDuplicatesAndLimits实际红；git checkout精确恢复。变异表在相邻SeedGen补丁build/next-mutations.json。
5. **下一步**：取得新版本失败前完整terrain/pin/fixed/deckFloor/坡限与覆土录制后再重放三条锁高。纵断面crossing解耦与第二次平滑仍等真机数据；地图联机同步与实际贴合未验收。
6. **历史与回退**：基线48cbe7d，行为等价。正式0.9.124与同级前置1.3.0构建；本轮未改玩家存档，部署和推送以最终记录为准。

## 2026-11-25 · 三维地图只读接线（0.9.123）

1. **改动**：注册RoadOverlayApi提供方，仅消费已加载的不可变RoadSnapshot；使用施工targetY和统一跨度掩码，保护缺口/共用段断开，桥/隧道/交叉分类。三维同向共线站精确压缩，保留坡折、回头和类型边界；视口顶点预算达到后不再转换剩余所有道路。观察不启动读盘、区块加载、精采、规划或施工。独立Diorama客户端适配包消费API，H/J原入口不变。
2. **验证**：新增6项产物/预算测试登记进RoadJUnitRegressionSuite；最终PASS tests=1884 skipped=0 aborted=0 failed=0，Fabric正式build及独立前置验包通过。API11项检查，Diorama4项JUnit与真实四向地图原生夹具通过。夹具提供合成XYZ，不是实际路网验收。
3. **未验证**：真实整合包道路在Diorama的贴合与帧率、大规模地图缓存命中率、远端服务器同步。道路三条LOCKED_HEIGHT及长城CAP_EMPTY/新增收口断点漏采未在本轮解决；不能将显示接线当作规划失败已修复。
4. **负控**：地图targetY改回原地形Y→高度测试红；跳过保护/共用掩码→两条独立线断言红；预算前预取全部输入→触碰次数由2变100，测试红。git checkout精确恢复，日志在相邻plaincraft-roadweaver-diorama-compat/build/mutation-*.log。Diorama另有实际Mixin、跨世界及撤权负控。
5. **下一步**：用户重启完整整合包，在实际已存道路中核四向投影、桥高、缺口和H/J入口；联机另做授权协议。先采路网视口转换耗时，再决定是否增加按路分片索引，不写未经测量的性能倍数。
6. **历史与回退**：基线8b8de66，行为新增，施工几何及存档格式不变。依赖外置前置1.3.0，Diorama适配固定发行0.11.0。Loader/API升级及成品备份在对应适配仓库HANDOFF记录，未改玩家存档。

本轮最终记录：实现c8b2597已推GitHub main；道路0.9.123已部署WAYFARING，SHA256为`6b65a462ae4df0110ec3913ae28c2eec7f61f92b43cb10807fa38451006372f9`，与恢复全部负控后的构建件一致。外置API1.3.0和Diorama适配1.0.0同时部署，旧道路/API在.minecraft/backups/wayfaring-diorama-20261125-183242。道路最终1884项零跳过/中止/失败；原生地图四向/撤权验证通过，实际路网和整包启动未验收。

## 2026-11-25 · 只读远景与Diorama调查（0.9.122）

1. **改动**：注册roads，复用已有区块计划或已加载的不可变道路快照及空间索引，不为远景创建存储、读盘、加载区块或精采。保留保护缺口与复用站规则；targetY是可站立顶面，普通路面预览在y-1。真实切片全部完成才发完工通知。几何预览不冒充模板桥、半砖、隧道和装饰最终体素。
2. **验证**：全量PASS tests=1878 skipped=0 aborted=0 failed=0，Fabric build、API-SEPARATE及三个负面对照通过，日志build/preview-release-final.log。新增两项预览和一项未加载存储观察测试。
3. **未验证**：未新档复验八条现场失败；三条首次LOCKED_HEIGHT、crossing解耦、第二次平滑仍未修或未裁决。未实现Diorama叠线，未验收完整整合包画面/性能；源码peek不在旧部署0.9.121中。
4. **负控**：预览y-1改y、未加载快照直接返回，两类实际回归红；git checkout精确恢复后1878项及构建通过。日志build/preview-mutation-height-and-unloaded.log。
5. **下一步**：Diorama只读调查确认H地图自有路线绘制仅借Xaero底图。独立客户端适配应从peekSnapshot提供XYZ中心线与targetY/span；旧RoadMapGeometry归零Y不能直接用。在Diorama MapScreen的MapViewport.draw之后用Projection.u/v和View.screenX/screenY叠线，不写会持久化且假定Y=64的DrawingStore。先全屏只读，再补多人网络、洞穴及小地图；H/J编辑保留。内部接口需锁Diorama0.11.0。
6. **历史与回退**：基线14e4d30，行为新增，API最低1.2.0，不改存档或施工几何。5593fc9已推送GitHub main；0.9.122已部署WAYFARING，SHA256为f07e254d193bbc41790969e4562bd261cec1f0edd03f0b2066fec5f14ba4fe66。两个子智能体只读调查。旧包及配置备份在相邻plaincraft-voxyseedgen-l10n/build/deployment-backups/preview-1.2.0-20261125-162224，未修改玩家存档。

## 2026-11-25 · 旧版契约差异逐项审核

1. **改动**：审核旧契约比对的五组差异，保留冻结旧摘要，单列允许的新摘要、新增网络接收器和退役配置语言键。两处长城接口仅新增isTerminalFailure及默认实现；MAP_WORLD_ID为8ed9d63的私人地图存档身份接线；缺失的配置语言键全部对应b218b6a和d4f954b的已批准配置清理。非配置语言键、原公开方法和原注册清单继续强制保留。新增零匹配拒绝与实际注册逐项删除回归，不改生产行为。
2. **验证**：道路全量PASS tests=1875 skipped=0 aborted=0 failed=0，Fabric成品及API-SEPARATE和三个负面对照通过。Python契约工具5项、证据工具21项通过；实际25份API、82份资源和全部网络/键位注册对照errors为空。独立API b696c41与当前1aaa7cb的两处契约源码无差异，CI固定前置提交仍适用。
3. **未验证**：没有重启整合包复跑八条现场失败路线，不保证五条干谷修复已通过全部后续验收；三条首轮LOCKED_HEIGHT仍待新地形输入。纵断面crossing与第二次平滑保持原状；没有真实世界性能倍率。长城本轮只改采样进度，不能据此结案收口或道路失败。
4. **负控**：测试直接读取仓库JSON正则和正式注册源码，先断言完整匹配数，再在独立副本中逐个删除12个网络接收器及13个键位登记，每次必须拒绝；API批准新摘要后删旧方法仍拒绝。原有删资源、改API、删语言键和回退元数据的拒绝夹具保留。此前怀疑正则双转义，实际字符与完整匹配均已证伪，不作为缺陷报告。
5. **下一步**：部署现有干谷修复后新世界采集失败前地形与各半径搜索诊断，按首个拒绝阶段修复三条锁高路线；不降低保护或坡限来掩盖失败。
6. **历史与回退**：基线ebe443f；本轮仅测试基建与审核台账，不改变JAR内容或存档格式。临时样本与日志不提交；GitHub连接间歇失败，推送结果以最终交付为准。

## 2026-11-24 · 八条失败路线现场定位与干谷分类修复

1. **改动**：本次开局原始连接 20 条，完成 12、失败 8，最终失败均为 `LOCKED_HEIGHT`。五条已通过初次剖面，却在干谷补判后按原地形重新锁高失败；现将河岸延伸与干谷分类拆开，河岸变化仍重解，干谷只更新跨度、保留已验收高程。两步纳入原有同端点绕行恢复边界。施工端仍按逐站 `targetY` 落块，最终保护、水位与坡度校验保留。
2. **验证**：基线 `PASS tests=1871 skipped=0 aborted=0 failed=0`；最终 `PASS tests=1875 skipped=0 aborted=0 failed=0`。Fabric 构建、`API-SEPARATE` 及三个验包负面对照通过。新增三例覆盖真实干谷分类接生产剖面、河岸变化先求解、河岸无解继续拒绝；另用编译器语法树确认真实生成入口在恢复范围内调用最终分类。失败前即录制精采地形（沿用原 AB 开关）；拒绝时打印精采入口高度、锁高与锁高标记，每个局部半径打印搜索诊断和拒绝阶段。
3. **未验证**：尚未在同种子新档复跑这八条，不承诺五条已经通过所有后续验收。另三条首轮锁高冲突尚未修复：`(-32,-208)→(832,304)`、`(1632,-2176)→(2128,-1328)`、`(1280,1808)→(1840,464)`；当前低洼点附近 48/96 格局部搜索没有交出可供剖面重验的候选，入口高程来源与更长接坡需求仍待核实。本轮没有改坡限、桥掩码来源、端点锁高及存档格式。
4. **负控**：临时将 `RoadProfileRecovery.finish` 的最终分类改为 `solve.apply(classifyDry.apply(current))`，恢复旧式干谷重锁。全量回归红两例：干谷产物报 `LOCKED_HEIGHT station=4 limitingStation=2 requiredMinY=108 reachableMaxY=107`；河岸例检测到多求解一次。使用 `git checkout --` 恢复生产文件后全量 1875 项通过。变异日志在 `build/profile-mutation-dry-relock.log`，最终回归在 `build/profile-wiring-final.log`，均不提交构建日志。
5. **下一步**：新档复核八条路线。后三条需要新诊断区分入口锁高、跨谷需求、搜索预算或候选验收；当前会话失败路线未被旧录制器记录，存档中的相关区块缺失或仅生成到结构引用阶段，没有高度图，无法离线恢复完整真实输入。不能凭现场可步行就认定配置坡限下同线可施工。
6. **历史与回退**：基线 `b235863`，开工工作区干净；`git pull --rebase` 因本机 `127.0.0.1` 代理连接失败未同步。绕开失效代理后本机 Git 重试仍 `curl 56 Connection was reset`，两笔本地提交尚未推送。改动可独立回退，未修改玩家存档、未部署。最终成品构建成功，文件仍使用当前 `0.9.121` 编号，不能仅凭文件名区分新旧。

[项目介绍](README.md) · [版本更新](CHANGELOG.md)

## 构建和验证

使用 JDK 21 与外置 Gradle 8.8。先在同级 `plaincraft-worldgen-api` 构建 named 契约、dev mod 和 intermediary 契约，再在 `streams-reflowing-src` 运行 `planContractJar`、在 `plaincraft-great-wall` 运行 `interopJar`。`-PworldgenApiProjectDir=<路径>` 可指定前置源码的位置。河流、长城的契约 JAR 是开发输入，玩家可单独安装道路和前置。

```powershell
& $gradle :common:roadJUnitRegressionSuite :fabric:build --daemon --console=plain --dependency-verification=off
python -B -m unittest discover -s scripts/parity -v
python -B -m unittest discover -s scripts/evidence -v
```

成品位于 `fabric/build/libs/`，使用无 `-sources`、`-dev` 后缀的 JAR。检查 `PASS tests=`、零跳过/失败、`API-SEPARATE` 与 `BUILD SUCCESSFUL`。新 JUnit 类须加入 `RoadJUnitRegressionSuite`。历史替换和几何台账由 `common/src/test/resources/architecture/` 提供，现场纵断面样本在 `common/src/test/resources/profile/`。

自动构建检查见 `.github/workflows/contract-check.yml`。旧版契约全量比对的五组差异已逐项审核，原摘要保留，允许变更单独登记；回归绿灯和契约对照仍不能代替实机产物验收。

## 当前架构

开局读取当前存档的索引与台账，等待已安装的河流、长城。收集结构端点，用粗地形选边和规划走廊。默认精采样限于走廊，再生成站列、纵断面和桥隧跨度。合并路口后按最终占地验证，再发布计划。H 地图显示计划，区块回填与新区块生成负责铺设。

粗阶段的桥隧只是候选：不能用稀疏网格定稿桥头、洞口和路面高度。开局精规划确认后，将站列、`targetY` 和 `spans` 一起写入道路记录。施工按记录编译区块占地，仍复核保护列和落块条件。

| 包 | 职责 |
| --- | --- |
| `metric` | 地形、坡度与障碍的通行代价 |
| `topology` | 传播场、测地距离与选边 |
| `band` | 在硬约束内优化单路曲线 |
| `pipeline` | 编排路网选边和质量检查 |
| `bridge` | 将新内核接到游戏采样和寻路接口 |

内核依赖和历史跨层例外由 `ArchitectureGuardTest` 管理。七批替换涉及纵断面、桥隧、施工、路口、几何、避让与验证，后四项在原包就地演进。退役旧代码须核对生产入口、所有外部引用和产物回归。

## 修改边界

- 保持单写入方、开工前同步、精确暂存文件。代码中文注释说明取舍，单文件有效代码至多 1000 行。
- 保持 `RoadData.CODEC`、手绘路线编解码、道路分片 schema 2、施工台账 schema 3、世界生成指纹 schema 1。配置字段名和持久枚举名沿用现行契约。
- 保留村庄包围盒保护与长桥 ±5 格正弦弯。路肩有意关闭，精确采样使用 CPU。兼容层的反射引用需纳入删代码前的核查。
- 配置从当前绑定元数据核对。开局规划半径上限 4096 区块，动态规划半径上限 2048，局部修复网格间距单独设置。
- 复盘取 `latest.log` 的首个失败及相关端点。`[ROUTE-EVIDENCE]` 跟踪候选、验证与施工，`[INITIAL-REALIZED-TOPOLOGY]` 统计已验收计划连通性。[证据汇总工具](scripts/evidence/summarize_route_evidence.py) 按会话和道路内容区分结果。

## 当前交接 · 2026-11-23

1. **改动**：粗阶段和走廊的首轮精采都按真实水平距离计算升高量，加密后仍使用施工站的高差限制。纯陆且已有隧道提示时，在现有高程感知、地表偏好模式下尝试一次地表绕坡；受山地绕行倍率、最多 24,000 状态和 300 ms 搜索软限约束。过水、共用走廊和显式隧道优先保留原流程。没有合格候选时仍允许桥隧。未更改默认参数或存档格式。
2. **验证**：道路回归 `PASS tests=1871 skipped=0 aborted=0 failed=0`，Fabric 构建、独立前置验包及 3 个拒绝用例通过。合成地形覆盖缓山、山口、超预算绕行、不可绕山脊、过水和首轮精采的稀疏缓坡。
3. **未验证**：真实世界的隧道占比、盘山路质量、连通率、开局耗时及动画、跨区块长桥和游戏内铺设。不把合成测试当作实机收益。新增搜索存在限时退出，实际吃满预算的频率也待测。
4. **负控**：粗边升高量固定为 1 → `broadHillKeepsSurfaceAtEightBlockSpacing` 红；首轮精采禁用稀疏坡限 → `accurateRefreshOfSparseCorridorDoesNotFlattenALegalSlope` 红；绕行距离预算置零 → `boundedSurfaceSearchUsesAnOpenPassAndRejectsAnOverlongDetour` 红。三组源码均按原字节恢复。
5. **下一步**：用同种子和相同参数比较隧道总长、实际连通数量、半砖坡道、长桥、绕墙及开局各阶段耗时。纵断面 crossing 解耦和第二次平滑维持现状，等待现场对照。
6. **历史与回退**：本轮基于 `3db0866`，可单独回退，已有道路记录的读取格式不变。[清理前开发记录](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/tree/474cd905872a772adf3d783efddec54c4457da77/docs)。
## 2026-11-24 · 热路径优化

1. **改动**：精采样纵断面按站换算坡限并复用土方模式；回溯直接写出高程，省去每段偏移数组。候选代价和等分取舍维持原样。
2. **验证**：全量回归 1871 项，零跳过和失败；Fabric 构建及独立前置验包通过。
3. **未验证**：未在整合包量纵断面耗时或总预热耗时。
4. **负控**：本轮沿用现有剖面守卫，未新增守卫或负控。
5. **下一步**：同种子比较开局耗时与路线产物；后续优先定位走廊精采和纵断面各自耗时。
6. **历史与回退**：基线 93685cd；本轮可单独回退，存档和配置格式不变。

## 2026-11-24 · 精采与纵断面分项计时

1. **改动**：道路耗时汇总新增 `exactColumnsMs`（地形列）、`exactGradeMs`（精采阶段求坡）、`profileWindowMs`（施工纵断面窗口）和 `profileDpMs`（窗口之内的 DP）。子项不重复加入总量。重置时清零先前漏掉的修复与验收计时，避免跨世界串数。
2. **验证**：`:common:roadJUnitRegressionSuite :fabric:build` 成功，`PASS tests=1871 skipped=0 aborted=0 failed=0`。原有阶段字段保持原名。
3. **未验证**：本版未用整合包新开世界采数。旧版 0.9.118 的一次会话：道路批次墙钟 288.060 秒，线程累计精采阶段 150.678 秒、目标高度 11.994 秒、最终校验 224.231 秒，不能加总当作预热耗时；旧版精采混有求坡。
4. **负控**：未新增守卫。首次直接跨层写统计触发架构守卫；首次改变物理隧道选择器参数位置触发生产接线守卫，调整后全绿。
5. **下一步**：同种子同配置新开档，从 `RoadWeaver detailed generation timing` 对比子项与墙钟，再决定优先缩小精采还是减少纵断面重算。
6. **历史与回退**：基线 8c000f0；本次只改观测与计数重置，可独立回退，存档与配置格式不变。

## 2026-11-25 · 本轮部署记录

e60a7da及此前干谷修复f0e38ab、诊断ebe443f已推送origin/main。当前0.9.121成品已部署指定整合包，与本轮1875项回归及独立前置验包通过的构建文件SHA256一致，旧同名包保存在本地补丁build/deployment-backups。未启动或修改玩家存档，八条失败路线尚未在新档复跑；三条首轮锁高问题仍未结案。
