# 开发约定

## 开工与提交

1. 保持单一写入方。先 `git status --porcelain` 确认干净，再 `git pull --rebase`；网络失败如实记录。
2. 小步提交。`git add` 或 `git rm` 逐个列文件；单文件有效代码不超过 1000 行（注释和空白行不计）；新增协作类优先控制在 200–400 行，不能靠删注释或把多个职责塞入同文件规避。新增或改动的代码用中文注释解释为什么这样做。
3. 守卫须用负控验证：修改脚本应先断言锚点唯一；记录改坏了什么、哪个断言红；用 `git checkout -- <file>` 恢复临时破坏，再全量跑。不要把临时破坏带进提交。
4. 按[验证手册](docs/VALIDATION.md)记录双模块回归；更新[交接台账](docs/HANDOFF.md)六栏后提交、推送。提交信息写明行为等价还是行为变更。

## 不能改变的边界

- 旧版能力可以换实现；新版失败应给出同一套新实现的可用解或明确原因，不能自动退回旧算法。历史观测和显式诊断开关另记。
- `RoadData.CODEC`、`PlayerRoute` / `RoutePoint` 的 Codec、`RoadChunkStore.SCHEMA_VERSION=2`、`RoadConstructionLedger` schema 3、`WorldgenFingerprint` schema 1 保持兼容。GSON 字段名是配置键，持久化枚举用 `name()`。读失败不是空数据，不能拿部分读到的路网覆盖索引。
- 长桥弦长 ≥96 时保留 ±5 格正弦；村庄按包围盒裁切；悬空可以接受。`SegmentPaver` 路肩有意关闭，`openclAccurateSamplingEnabled` 有意废弃，精确采样走 CPU。
- 维持 `LoadSignalBridge` ownerId、`GreatWallReadinessApi`、`WallRoadApi`、Mixin plugin 和 `RoadPositionQuery` 的跨模组契约。施工保留运行期水位晋级、桥口镜像重试、`SKIP` / `PAVE_SURFACE_ONLY` 优先级及隧道洞身禁止向上清空；`LocalRouteSearch` 仍是局部修复基础设施。
- `c2meqos`、`biome-optimizer`、`erdtree-sapling` 的反射是兼容层。不要把缺少静态引用当成可删证据。死代码核查要数完整匹配并排除注释、声明、Mixin/资源、反射和下游调用。
- 旧链路下线须有已接入的等价能力、产物验证和零外部引用的证据。保留[重制台账](REFACTOR.md)及两份受测试读取的 S5 台账原路径。

## 文档归属

入口和归属见 [docs/README.md](docs/README.md)。改默认或生产接线更新现状；改后续顺序更新路线；有验证数据追加交接或发布台账。单轮排查草稿不另立一个「当前状态」。
