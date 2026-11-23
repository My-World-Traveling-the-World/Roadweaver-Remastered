# RoadWeaver 开发指南与交接

[项目介绍](README.md) · [版本更新](CHANGELOG.md)

## 构建和验证

使用 JDK 21 与外置 Gradle 8.8。先在同级 `plaincraft-worldgen-api` 构建 named 契约、dev mod 和 intermediary 契约，再在 `streams-reflowing-src` 运行 `planContractJar`、在 `plaincraft-great-wall` 运行 `interopJar`。`-PworldgenApiProjectDir=<路径>` 可指定前置源码的位置。河流、长城的契约 JAR 是开发输入，玩家可单独安装道路和前置。

```powershell
& $gradle :common:roadJUnitRegressionSuite :fabric:build --daemon --console=plain --dependency-verification=off
python -B -m unittest discover -s scripts/parity -v
python -B -m unittest discover -s scripts/evidence -v
```

成品位于 `fabric/build/libs/`，使用无 `-sources`、`-dev` 后缀的 JAR。检查 `PASS tests=`、零跳过/失败、`API-SEPARATE` 与 `BUILD SUCCESSFUL`。新 JUnit 类须加入 `RoadJUnitRegressionSuite`。历史替换和几何台账由 `common/src/test/resources/architecture/` 提供，现场纵断面样本在 `common/src/test/resources/profile/`。

自动构建检查见 `.github/workflows/contract-check.yml`。旧版契约全量比对目前仍报告五处差异，涉及两处长城就绪接口、地图网络注册及两份语言文件。修订该基线前需逐项确认这些变更，回归绿灯不能代替旧版契约对照。

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
