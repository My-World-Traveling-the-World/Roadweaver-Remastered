# roadweaver-next 发布台账

## 0.9.114 改线补铺与区块刷新（2026-11-22，已部署 WAYFARING）

源码 ba9c96c，含 898df07、54f88f2。已标空区块在换线后重入施工队列，开局施工与存储复用道路占地，合并相邻缓存刷新范围。回归 1851/0/0/0，六次变异均命中，API 验包通过；无存档格式或配置变化。成品 7201883 B，SHA-256：bc43db7822056838df90e8113510d4ca59d51c4f5e50cdb0ffe816ddab53784f。回退：退出游戏、移出 0.9.114，再从 build/wayfaring-road-round114 恢复 0.9.113。未实机验证玩家原档跨海桥。

## 0.9.113 补边、施工恢复与路牌分片（2026-11-22，已部署 WAYFARING）

成品源码 cf5855b，含 16a8b09、15e9b69。修复补边资格与失败记忆、道路库同会话重读、施工区块 X/Z 对调及路牌文件被占用时的处理。回归 1848/0/0/0，五处变异均红；内嵌 API 验包通过。成品 7198697 B，SHA-256：55e01515f34b95a08f625ef649e8a7101517f3253ebc39d4cf13b416d781584b。需回退时退出游戏，移出 0.9.113，从 build/wayfaring-road-round112 恢复 0.9.112。未做玩家世界实机验收。

## 0.9.112 私人地图笔记（2026-11-22，已部署 WAYFARING）

源码 `8ed9d63`。单人随存档、联机按周目与玩家分开；备份恢复保持周目标识。回归 1842/0/0/0，两个隔离负控报红。成品 7196838 B，SHA-256 `e30032318015478822949360fb6cc54b664186241c5cc46b0b9519cd499c8b59`。关服退出游戏后可移出 0.9.112，从 `build/wayfaring-road-round110` 恢复已核验的 0.9.111。尚未做客户端和专服联机实测。

> 本节是**持久台账**（不是临时文档）：每次出可部署 jar 都必须记一条，含 commit / 改动 / 回归 / jar 体积 / SHA-256 / 回退方式。
> 旧仓库（`D:\云游四海-Mod源码\roadweaver`）的历史版本条目在它自己的 `roadweaver-变更历史.md` 里，本项目从 0.9.099 起独立记账。

## 0.9.111 道路开局验收与高程修复（2026-11-22，第109轮，已安装待实测）

| 项 | 值 |
| --- | --- |
| 成品源码 | `a8a14d2`，包含 `0e0e59c`、`c4c4ab5`、`51f175d`、`27c5744`、`04fb7e8`、`943af6c` |
| 成品 | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.111.jar` |
| 体积 | 7192652 B |
| SHA-256 | `98e31dc8de22a7e3b10beb82e7927ea141f1b3480621446f9a8887e30f4c7211` |
| 改动 | 负高程锁定和失败见证；按安全终态发布计划连通性；有界补边；唯一施工列；改正预热阶段与端点计数 |
| 回归 | 1840/0/0/0；Fabric 构建、内嵌 API 核验与 7 类坏包负控通过；8 项生产变异均触发断言失败 |
| 安装 | 在游戏关闭时替换 WAYFARING 中唯一启用的道路 jar，核对安装件哈希；未改河流、长城、配置或存档 |
| 回退 | 关闭游戏；移出 0.9.111，从工作区 `build/wayfaring-road-round109` 复制已核验的 0.9.110 到 mods。回退包不会撤销已落块的道路 |
| 证据 | [交接台账](HANDOFF.md)；本机回执 `build/wayfaring-road-round109/deployment.json`，构建日志 `build/round109-release.log` |

## 0.9.106 现场高程修复（2026-11-21，第97轮，已部署待实测）

| 项 | 值 |
| --- | --- |
| 生产提交 / 测试提交 | `38c159b` / `7816397` |
| 成品 | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.106.jar` |
| 体积 | 7153544 B |
| SHA-256 | `be5810eab430cc5c889bd715840d34beda49745cf56b86c0b6ca33828cd68dcf` |
| 改动 | 地表约束无解时按物理判据选隧道后重算；保留洞口和深挖引道，拒绝不可施工剖面；含0.9.105的新版配置菜单 |
| 回归 | 1819/0/0/0；标准Fabric构建和API验包通过，7类错误包被拒；8组高程负控均红，逐组恢复后重跑全套 |
| 安装 | 23:54已装WAYFARING，与长城0.7.128同批更新；河流fix10、配置及存档未变，待新世界实测 |
| 回退 | 关闭游戏后到工作区 `build/wayfaring-generation-fix-20261121`，先预演 `Rollback.ps1`；加 `-Apply` 恢复已验SHA的道路0.9.104和长城0.7.127。回退包不撤销已生成的方块 |
| 证据 | [高程变异](evidence/road-profile-repair-mutations-20261121.json) · [部署核验](evidence/wayfaring-generation-fix-deployment-20261121.json) |

## 0.9.105 配置统一接线与分类（2026-11-21，第95轮，未部署）

| 项 | 值 |
| --- | --- |
| 主实现 / 版本 / 中文说明守卫 | d4f954b / fa58a5c / f039bf7；首步缓存修复dfe682d |
| 成品 | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.105.jar` |
| 体积 | 7147217 B |
| SHA-256 | `140fad6666027b6c1e0fa251ceeec2197157380b1d10dcb74f64b21ea28f66aa` |
| 回归 | 1800/0/0/0；标准Fabric构建通过；内嵌API通过，7类错误包均拒绝 |
| 负控 | 10组均为真实断言失败，每组35项、跳过/终止0；逐组checkout及SHA恢复 |
| 改动 | 196项完整限定菜单绑定、8功能页、高级/专家折叠；4个数学/桥灯控制保持原默认值；删除无效精确GPU开关与旧迁移，补缓存身份、范围和非有限数校验 |
| 包内复核 | 中英各196个限定标题，无已删除Section类，API仍内嵌；存档schema和持久枚举名未改 |
| 安装状态 | 未部署；只读核对WAYFARING仍启用0.9.104；未修改游戏配置/存档，未启动游戏 |
| 回退 | 安装件未变。试装前保留0.9.104及配置；无效旧配置不提供兼容迁移；已生成道路不会随参数自动重建 |
| 证据 | [构建](evidence/config-menu-build-20261121.json) · [变异](evidence/config-menu-mutations-20261121.json) · [配置说明](CONFIGURATION.md) |

## 0.9.103 新版配置清理（2026-11-21，第89轮，未部署）

| 项 | 值 |
| --- | --- |
| 生产代码 / 负控工具 | b218b6a / 72d3a86 |
| 成品 | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.103.jar` |
| 体积 | 7150448 B |
| SHA-256 | `e1e8d0d746e2a0320c5a76e43b99c238466eea1b5e37ccd6221749ff65735f6b` |
| 回归 | 1779/0/0/0，标准Fabric构建通过，内嵌API唯一，7类错误包拒绝 |
| 负控 | 5组指定守卫报错，逐组checkout逐字恢复；可复放工具及机器证据见第89轮HANDOFF |
| 改动 | 删除29个无效/固定选择键及旧页；有效网格间距改名pathGridStep；桥隧真实计价及水陆选线保留 |
| 兼容决策 | 用户明确不做旧配置兼容；旧aStarStep不读取，缺新键时默认8；存档schema不改 |
| 安装状态 | 未部署，现场仍0.9.102；未修改游戏配置/存档；旧界面须换包重启后更新 |
| 回退 | 尚无安装改动可回退；试装前应保留原jar/配置，较新配置不保证能还原已删除旧键 |

## 0.9.102 开发实机件（2026-11-21，第86轮，已部署待实测）

| 项 | 值 |
| --- | --- |
| 生产代码 | 43420bc；部署前文档HEAD为1bfb833 |
| 成品 | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 7169473 B |
| SHA-256 | `e261b5eb52a1108c44a8e772eef3558fbc4e625f6e62abd27059dd31f4371882` |
| API | 内嵌roadweaver_api 1.0.0；安装目录顶层及一层嵌套仅此一份 |
| 最近完整验证 | 1772/0/0/0，标准Fabric构建成功，7类错误包拒绝；本轮无生产改动，复用该已测试件 |
| 改动 | 累积开发件含二阶剖面线性转移及前轮修复；版本号未递增，须按SHA识别 |
| 部署 | WAYFARING实例mods，替换11月14日旧件；同批更新河流并新增长城 |
| 备份 | 工作区`build/three-mod-playtest-20261121-091158/backup`；原道路SHA `22751e3b3f0954dbe19589a607303d699c245348d4289d1c1d3ace1cfe62cb16` |
| 回退 | 退出游戏后该目录Rollback.ps1检查，显式-Apply才恢复；未实际回退，备份SHA已验 |

三个安装件安装后完整验hash，目标ID唯一；未启动游戏，配置/存档/第三方包未改。
部署时发现的polytone/chestfill/punchy重复ID，随后已由第87轮按Loader原选中版本备份清理；
详见[目录审计](MODPACK-AUDIT-20261121.md)。原始[部署回执](evidence/three-mod-playtest-deployment-20261121.json)保留当时状态。

## 0.9.102 开发复打（2026-11-20，第78轮，未部署）

本地同名开发成品已覆盖第77轮文件，下方历史hash继续保留；本轮不代表游戏安装件已更新。

| 项 | 值 |
| --- | --- |
| 代码提交 | c9ef67f（首末站索引）、c13b07f（施工阶段计时及验证底线） |
| jar | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 7160557 B |
| SHA-256 | `faf6b5a5655d9c09cd730a8704869cf67e7dcc3a1cb308014a3b96790382dc9f` |
| API | 仍内嵌roadweaver_api 1.0.0，标准build的七类错误包守卫通过 |
| 验证 | 完整1753/0/0/0、真实verify、标准Fabric构建、6组道路分层负控 |
| 变更 | 单次归一化复用跨度首末站；诊断开关量真实同步事务各阶段 |
| 回退 | 未部署；如试装需自留原安装件/存档副本，schema不改，不自动重铺完工区块 |

## 0.9.102 开发复打（2026-11-20，第77轮，未部署）

这是当前源码开发件，覆盖本地同名成品文件但不改下方历史记录；尚未安装游戏，版本号未递增。

| 项 | 值 |
| --- | --- |
| 代码提交 | dc282de（分层隧道归属）、bfc88b6（严格验证入口） |
| 版本 | `2.3.1-1.21.1-plaincraft-direct-core.0.9.102` |
| jar | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 7151647 B |
| SHA-256 | `eec42a56c1cfd6fad6dd89c5c7aecbec8995ad053aef9da74a0500a32c9485a3` |
| API | 内嵌roadweaver_api 1.0.0，与本次api-fabric生产件逐字一致 |
| 回归 | 1744/0/0/0、真实verify.ps1、标准Fabric构建、七类错误包、12组分层负控通过 |
| 变更 | 异层隧道不互相让出，旧共用复核也校验高度；验证工具不再用局部PASS子串判绿 |
| 回退 | 未部署，无线上回退动作；试装须保留安装件和存档副本，不自动清除既有完工台账 |

外层原文件与本次remapJar逐字一致，仅增加API；按实际编译测试class全集确认未混入测试。证据见[evidence/reuse-height-verify-package-20261120.json](evidence/reuse-height-verify-package-20261120.json)。没有世界落块或FPS验收。

## 0.9.102 开发复打（2026-11-20，第76轮，未部署）

本条是同版本最新源码的开发构建，不是下方11月14日历史发布件；文件名相同，必须按日期/提交/SHA区分，历史hash不替换。API嵌套成品由标准:fabric:build生成并验包，游戏安装目录未改。

| 项 | 值 |
| --- | --- |
| 代码提交 | 7327271（几何复用）、e1bab08（标准合包）、68ddbbc（重试隔离） |
| 版本 | `2.3.1-1.21.1-plaincraft-direct-core.0.9.102` |
| jar | `fabric/build/final/roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 7151649 B |
| SHA-256 | `9ad5cdad276e5ffa2522e0067b84d0e06c277660aefa0beae836cf9c03aa8ef9` |
| API | 内嵌roadweaver_api 1.0.0；26契约类及1个Architectury注入类，与本次生产API逐字一致 |
| 回归 | PASS tests=1739 skipped=0 aborted=0 failed=0；标准Fabric构建、7类错误包、14组分层负控通过 |
| 变更 | 单轮站点方向/占地惰性复用；build/check强制含API验包；握手重试不再全局终止Java |
| 回退 | 未部署，无线上回退动作；未来试装前保留当前安装件和存档副本，版本回退不属于运行期算法回退 |

全部外层原文件与本次remapJar逐字一致，仅增加内嵌API；确认含新几何类且无测试夹具/JUnit。机器记录见[evidence/api-package-round76-20261120.json](evidence/api-package-round76-20261120.json)。未跑真实游戏/联合模组加载，不能把构建通过等同现场产物验收。

## 0.9.102（2026-11-14）

**为什么要出这一版**：本轮修完 2026-11-14 审计的 14 项缺陷 + 完成缺陷 C（纵断面去双跑）之后，
**一次真机都没跑过**。路线图的「阶段 0（采集真实地形）」是阶段 3/4 的前置，它必须在游戏里跑一局
才能产出 `terrain-samples.tsv`。所以先出一版能进游戏的 jar。

| 项 | 值 |
|---|---|
| commit | 见本文件所在提交（`build.gradle` 版本号递增提交）|
| 版本 | `2.3.1-1.21.1-plaincraft-direct-core.0.9.102` |
| jar | `roadweaver-fabric-2.3.1-1.21.1-plaincraft-direct-core.0.9.102.jar` |
| 体积 | 6,861,221 B（`fabric/build/final/` 的 `embedApiJar` 产物）|
| SHA-256 | `22751e3b3f0954db…` |
| 回归 | **PASS tests=723**（`RoadJUnitRegressionSuite`）|
| 回退 | 客户端 mods 里 `0.9.101.jar.bak` 改回 `.jar` 即可 |

> ★ **部署件必须取自 `fabric/build/final/`，不是 `build/libs/`**。
> `build/libs` 那个同名 jar 只有 4 个内嵌 jar、**缺 `META-INF/jars/roadweaver-api.jar`**，
> 装进 mods 会报「不兼容的模组：需要 roadweaver_api」并把 6 个下游模组一起拒掉。
> 判据：`Entries | Where FullName -match '\.jar$'` 必须 **5 个**且含 `roadweaver-api.jar`。
> （本次第一版就装错了 `build/libs` 的 6,858,008 B 件，核对部署产物清单后才发现并改正 ——
> 当时台账里记的 SHA `785d84b3` 是**错件**的，已作废。）

### 本版相对 0.9.101 的改动（12 个提交）

**审计缺陷修复（14 项，全部带回归钉子）**

| 缺陷 | 提交 | 一句话 |
|---|---|---|
| NEW-1（Blocker）源点碰障碍抛异常瘫痪整区 | `7acab2a` | 返回全不可达场，与「目标点不可通行」对称 |
| A（Blocker）降级重搜障碍场失效穿村穿楼 | `07b8c5a` | 硬掩码（模式门控）与软掩码（真实判定）分离 |
| NEW-8 隧道重搜绕过硬障碍 | `4766d98` | 包进 `HARD_BUILDINGS_AND_CLUSTERS` 作用域 |
| NEW-2 坐标量化 Round/Floor 错位 7 格 | `d3c3247` | 全仓统一就近吸附 + clamp |
| NEW-3 曲线算完即弃 | `8d30002` | `planTopology` 只跑拓扑轮（规划时间约减半）|
| NEW-4 曲线首尾未对齐节点 | `a7c8e11` | 首尾强制对齐；**负控实证**：不修时起点偏 6 格 |
| N1 只测格点、缺边级检测 | `cfb89e3` | 每边采 1/4、1/2、3/4 三点 + 保守膨胀 |
| B 弹性带无障碍投影（契约断裂）| `5038e62` | `BlockedCheck` 端口 + 环搜索投影；站数不变 |
| NEW-7 fillWeight=0.7 反转 prefer-cut | `f0e2bb7` | defaults → `(0.7, 1.0)`，填方严格贵于挖方 |
| NEW-5 对角偏爱 41.4% | `241ab36` | 回溯改按「单位弧长下降量」选向 |
| F 三处 fallbackMST 静默吞异常 | `2414037` | 全部补 WARN（含异常对象与上下文）|
| G AB 双跑两臂同型 | `2414037` | 检测同型即短路 + 一次性 WARN |
| H 六处文档不符 | `2414037` | 逐条更正 |
| FMM 预计算 + 目标早停 | `c289353` | **可证明逐位等价**的提速 |

**缺陷 C（纵断面双跑）主体完成**

| 提交 | 内容 |
|---|---|
| `8590d27` | 新增 `ProfileFallback.terrainFollowing`（无状态回落解，满足全部硬约束、幂等）|
| `bac8c18` | DP 先行；成功即跳过整段旧 pass（本版当时：DP 不可行仍回旧 pass）|

> 后续边界：`b7ef179` 起 DP 不可行也改走 `ProfileFallback`，但该提交**不在 0.9.102 内**。
> 本条是历史发布记录；现行行为见[开发现状](STATUS.md)，新产物检查见[验证手册](VALIDATION.md)。

### 待真机验证（本版必须验的清单）

1. **不出崩**：进世界、跑到道路生成完成，日志里不能出现 `IllegalArgumentException: 源点...是硬不可通行格`（NEW-1）
2. **不穿建筑**：路过村庄时，路面不该压在房屋上（A + N1 + B）
3. **纵断面偏好**：坡地/河边不应出现长距离人工路堤或天桥（NEW-7）
4. **曲线端点**：桥/隧道接缝处不该有错位台阶（NEW-4）
5. **性能基线**：`coarse planning timing` 里的 `pathfindingMs` 应显著低于旧版 215 s（阶段 2 的验收）
6. **剖面日志**：`RoadWeaver profile solver=dp` 应出现且**没有** `running legacy passes` 警告（C）

### 已知未做的三项（不要误认为已修）

- **C 的后续边界**：旧 pass 编排块已按铁律原地打标，不强行搬迁；但 `stabilizeSurfaceProfile` 仍无条件先跑，RawPathSearch/精确高度解析/精确坡度修复也仍调旧 `RoadGradePlanner`，不能把「编排块退场」误写成「全生产零旧调用」。
- **D**：`MountainSwitchbackPlanner` / `CoarseHeightAwarePlanner` 两处选线候选补充待评估；`LocalRouteSearch` 经核实是**局部修复基础设施**，不是残留。
- **N2**：FMM 求解的是轴对齐对角黎曼度量，不含 Finsler 交叉项 `2λ·gx·gz`；会改路线形状，必须真机 A/B 后再动。
