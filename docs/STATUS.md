# 开发现状

源码基线 `b12307f`（2026-11-15），Windows / JDK 21 全量回归 `PASS tests=1023 skipped=0 aborted=0 failed=0`，Fabric 编译通过。更新记录和负控详见[交接台账](HANDOFF.md)。[发布台账](RELEASES.md)里的版本有独立的源码和验证边界。

| 范围 | 默认生产行为 | 未完成或不能下结论 |
|---|---|---|
| 拓扑和选线 | 粗路网走 `PIPELINE`，单路走 `BAND`。Kruskal 前按长度过滤候选，质量日志看实际下发图。FMM 接回硬禁行与软共用走廊，冻结可用目标后可提前停。 | `PipelinePlanner` 粗拓扑仍用 `ObstacleField.NONE`；离网格端点的回溯坐标有已复现偏差。按可建性选网和坐标修复见[技术路线](ROADMAP.md)。 |
| 1 · 纵断面 | `TargetYComputation` 默认 `ProfileDpSource`；不可行时走新版 `ProfileFallback`。隧道覆土是上界，与最大挖方冲突时以覆土为准。 | `RoadGradePlanner` 仍服务局部选线、修坡等；不能把默认主剖面迁移写成所有旧坡度计算均已移除。 |
| 2 · 桥隧跨度 | 默认由 `PathSpanExtractor` 产出 spans。联合 DP 用 `GroundBanSources` 收集水、保护与既有路等证据，`modeDp` 默认只观测；`spanWriteback=on` 可选回写，默认关。 | `ProfileDpSource.crossing` / 桥面下界输入仍受旧 spans 影响；缺真机召回数据，保持现状。`PlanningSnapshot.EMPTY` 在 A/B 日志仍可能显示 `snapshot=ok`，不能据此断言已加载有效障碍；口径先维持可比。 |
| 3 · 施工 | 桥优先用 `BridgeSegmentPlannerNew` 模板，程序化桥和隧道仍在链上。模板落块已有离线生产入口守卫。`BuildFrame` 桥掩码键区分站序和输入，跨度变化不会复用旧桥掩码。 | 桥掩码缓存与施工完工是两回事：同身份改目标 Y、跨度或足迹，旧完工判断还可能跳过重铺。 |
| 4 · 路口和复用 | `ExistingRoadJunctionService` 就地拆分，有离线端口验证批量选主合并。区块编译按距离和占地复用物理主路。 | 大规模密集并行路、不同区块加载顺序还没有本轮真机验证。 |
| 5 · 几何后处理 | 弹性带后仍经过 `PathPostProcessor`；生产入口已有整路丢弃负控。保护列、桥头站距、长桥正弦和宽度是施工边界。 | 第二次陆地松弛是否应去掉，要先做离线回放和同种子真机对照。 |
| 6 · 结构避让 | 就地拆分、快照/诊断端口外置；选线和施工均有保护。 | `StructureInjector` 丢弃计数有实增回归，但无对应结构 ID 的拒绝分支仍漏记；群系软成本和特大树预留的选线退化见[功能对照](FEATURE-PARITY-CHECK.md)。 |
| 7 · 验证和存档 | `QualityGate` 发报告，不阻断；单路的几何、跨度和施工各有校验。单条 NBT 读失败触发只读降级，拒绝部分路网写回。 | QualityGate 阈值未做真机标定；Windows 文件锁测试不代表玩家真实存档已验证。 |

**配置和 UI。** 旧势场 A* 的部分配置键仍为了兼容保留；界面标记不生效。客户端直连部分业务实现是存量债务，新增跨层依赖由架构守卫约束；旧版 UI 真机验证不能代替当前界面验收。

**性能证据的范围。** 区块编译按扩张包围盒交区块扫描，路权匹配按 X/Z 分桶；FMM 路径调整和提前停也有离线结果。样本、耗时和对拍口径见 [HANDOFF 第 3–4 轮](HANDOFF.md)。这些数据不代表进档速度、TPS、帧时间或区块尾延迟。

**对照基线。** 旧版用来核查可见能力和再现退化；历史 57 次真机观测和旧发布件只说明当时采样，当前二进制仍需[刷新样本](VALIDATION.md)。
