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

1. **改动**：统一三个文档入口，迁移测试基线和重放样本，移除旧 NeoForge 发包配置与阶段专项工具。
2. **验证**：道路回归 `PASS tests=1867 skipped=0 aborted=0 failed=0`，Fabric 构建成功，独立前置验包及 3 个拒绝用例通过。24 项 Python 工具测试通过，离线重放读完 6 条历史样本。
3. **未验证**：完整整合包的预热耗时、跨区块长桥和游戏内最终铺设。
4. **负控**：暂移 `postprocess-methods-baseline.txt` 后，`GuardPreconditionTest` 的文件存在守卫及 `S5MethodMapTest` 的两项判据报错。基线已恢复。
5. **下一步**：用同种子新档和旧档副本查连通性、坡道、长桥、绕墙及回填尾延迟。纵断面 crossing 解耦与第二次平滑仍待现场对照。
6. **历史与回退**：[清理前开发记录](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/tree/474cd905872a772adf3d783efddec54c4457da77/docs)，旧版发布状态见本仓库版本更新。
