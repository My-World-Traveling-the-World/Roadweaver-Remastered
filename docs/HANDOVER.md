# 交接清单（2026-11-15）

> 如果你（或下一个我）接手这个仓库，先读这一页，再跑双模块闸门。
> 当前进度以 [`STATUS-20261115.md`](STATUS-20261115.md) 与 [`重制总纲.md`](重制总纲.md) 为准；
> 历史过程与误判见 `REFACTOR.md`，不要把旧快照当当前事实。

---

## 一、30 秒验证

```powershell
cd D:\云游四海-Mod源码\roadweaver-next
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\verify.ps1
```

当前基线：**ALL GREEN + PASS tests=749**，同时要求 Fabric 编译通过。

当前生产默认已经是：

- 规划：`PIPELINE`
- 寻路：`BAND`
- 纵断面：`DP`

**不需要再加 `-Droadweaver.profileSolver=dp`。** `legacy/classic` 只用于显式对照，
不能把「新实现有问题就切回旧版」当修复方案。

---

## 二、读什么（按顺序）

| 顺序 | 文档 | 读它的原因 |
|---|---|---|
| 1 | [`STATUS-20261115.md`](STATUS-20261115.md) | 当前功能、完成度与未做项 |
| 2 | [`重制总纲.md`](重制总纲.md) | 唯一权威路线图与批次台账 |
| 3 | [`README.md`](README.md) | 按问题跳到操作手册或机器台账 |

不要从头读 `REFACTOR.md`：它是追加式过程日志，里面有大量已被后续推翻的历史结论。

---

## 三、环境三个坑

| 坑 | 症状 | 解法 |
|---|---|---|
| PATH 上的 java 是 JDK 26 | `Unsupported class file major version 70` | 先设 `JAVA_HOME=jdk-21` |
| 错用 Gradle 9.x | Loom 插件创建失败 | 用项目锁定的 Gradle 8.8；verify.ps1 已写死路径 |
| `--no-daemon` 单次握手失败 | `Could not connect to the Gradle daemon` | **改用 `--daemon`**；不要先去查不存在的编译错误 |

---

## 四、当前最需要做的一局真机验收

**仓库已有早期真实地形样本，不是从零采集。** 现在缺的是最新构建的验收与样本刷新。

正确顺序：

1. 递增版本并构建当前源码。
2. 与上一版 0.9.102 做逐项差异审计：class 数、资源、类成员签名、
   fabric.mod.json/mixins、内嵌 jar、SHA-256。
3. 部署当前新包，旧活动件在客户端 mods 里改 `.bak`。
4. 开一局，建议加 `-Droadweaver.profileAb=on`；桥隧观测默认就是 AB。
5. 核查道路不崩、不穿建筑、桥隧接缝、prefer-cut、规划耗时与剖面日志。
6. 收集 `[SPAN-AB]`、`[PROFILE-REPLAY]` 与最新地形录制，刷新测试样本。

**早期 57 次真实观测不能代替当前版本验收；749 个测试也不能代替真机。**

---

## 五、绝对不能碰的清单

| 类别 | 具体 |
|---|---|
| 存档格式 | RoadData.CODEC、PlayerRoute/RoutePoint Codec、SpanType 枚举、RoadChunkStore schema=2、RoadConstructionLedger schema=3、WorldgenFingerprint schema=1 |
| 配置键名 | 键名 = Java 字段名（GSON）；改名等于清空玩家配置。删除死键则须先证明全仓零读取 |
| 跨 mod 契约 | LoadSignalBridge ownerId、GreatWallReadinessApi、WallRoadApi、mixins.json plugin、RoadPositionQuery 签名 |
| 用户冻结项 | 长桥 ±5 正弦弯、村庄裁切包围盒判据、悬空可接受 |
| 施工敏感 | RoadBlockPlacer 的无条件 below 写入、isProtected* 四层、SegmentPaver 的 SKIP/PAVE_SURFACE_ONLY 优先级、TunnelClearLedger |
| 必须保留的安全网 | promoteRuntimeBridges（运行期水位）、plannedBridge 桥口重试镜像、隧道洞身禁止向上清空 |

`LocalRouteSearch` 是局部修复基础设施；`SegmentPaver.planGrade` 不是可随手删除的性能热点。

---

## 六、verify.ps1 报错怎么读

| 输出 | 含义 | 处置 |
|---|---|---|
| `FAIL: fabric does not compile` + 行号 | 真编译错误 | 按行号修 |
| `FAIL: gradle daemon unreachable ...` | 环境问题 | 用 `--daemon` 重跑 |
| `FAIL: regression suite` + AssertionFailedError | 用例红 | 读中文断言；先判实现错还是夹具错 |
| 只有异常栈、没有断言行 | JavaExec main 抛异常 | 向上找第一帧 |

---

## 七、构建与部署：必须取 `build/final`

`verify.ps1` 只跑编译与回归，**不会产出可部署 jar**。

```text
fabric/build/libs/    remapJar 裸产物；同名但缺内嵌 API
fabric/build/final/   embedApiJar 后处理产物；部署这个
```

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
Set-Location D:\云游四海-Mod源码\roadweaver-next
# 先递增根 build.gradle 的 version
<Gradle 8.8> :fabric:embedApiJar --daemon --console=plain --dependency-verification=off
```

部署前必须验：

1. 内嵌 jar = **5 个**，含 `META-INF/jars/roadweaver-api.jar`。
2. 构建件 SHA-256 = 部署件 SHA-256。
3. mods 里活动 roadweaver-fabric jar = **1 个**。
4. 旧件已改 `.bak`；跟随更新目录不做 `.bak`，只留最新活动件。

详见 [`RELEASES.md`](RELEASES.md)。

---

## 八、当前阶段入口

| 批 | 状态 | 下一步 |
|---|---|---|
| 1 纵断面与坡度 | DONE（有边界） | 默认 DP + 旧编排块退场已完成；其他旧 RoadGradePlanner 调用仍待逐项替换，不能物理删空 |
| 2 桥隧判定 | IN_PROGRESS | ModeDpBridge 只观测；需补禁地面来源、真机召回证据，再接 spans 回写 |
| 3 桥体与施工 | IN_PROGRESS | BuildFrame 首项修复完成；继续核对桥体/施工职责，不提前标 DONE |
| 4 路口与合并 | TODO | 保留网络缝合能力 |
| 5 几何后处理 | TODO | 后处理台账与弹性带投影不是同一完成项 |
| 6 结构避让 | TODO | 迁移判据而非削弱保护 |
| 7 验证与收尾 | TODO | 统一最终验证、失败处理与物理删旧 |

另外两个独立项：N2 完整 Finsler 交叉项；MountainSwitchback/CoarseHeightAware 候选搜索取舍。

---

## 九、明确未做完

- 0.9.102 及当前源码的完整真机验收。
- DP 异常回落目前把 pin/deckFloor 传 null，与不可行分支不同参。
- 当前源码尚未重新构建部署；已部署 0.9.102 不是最新能力集合。
- 架构双向依赖、包方向规则、InitialGenManager 静态耦合仍有债。
- 深注释只做了覆盖与棘轮，不是所有方法都完成深写。
- 早期 12 页签 UI 真机不覆盖当前 13 分区，需最新版复核。

> 最容易犯的错不是写错代码，而是把旧结论当当前事实。动手前先查源码，数字变了就重采。