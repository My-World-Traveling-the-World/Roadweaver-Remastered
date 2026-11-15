# 旧版能力对照

旧版基线：同级 `roadweaver` 的 `refactor/structure-20261112@2c6547a`。重制版最近对照以 `818b076` 为起点，包含 `3a4327a` 修复。这里分清生产接线、行为回归和真机验收，不以「找到同名符号」判断能力保留。

| 场景 | 证据与结论 |
|---|---|
| 禁行走廊、共用过河点 | 旧 `PotentialFieldPathfinder` 在扩展时禁入或给予代价优惠。新版曾产出这些走廊但 `BAND` 未消费，`3a4327a` 已接回 FMM。窄禁带、单格绕行和优惠吸引均有离线走生产入口的回归。 |
| 群系偏好 | 旧 `PotentialFieldPathfinder` 消费 `biomeCostFor`；重制版仍有配置、分类和指纹但选线不消费。若恢复，应测量附加成本的影响；单点群系结构预筛已经证伪。 |
| 特大树预留 | 旧版选线查 `HugeTreeReservation.isReserved`；重制版的选线未接回，API 和施工保护仍在。应先测实际命中率。 |
| 普通路、桥隧与复用 | 两版 `PathFeature` 均进入模板桥、路面坡度处理、隧道施工和 `NETWORK_REUSE`。已加载区块可由 `LoadedChunkRoadBackfill` 补建；仅铺路面和洞身只清净空的分支仍在。接线相同不代表同一世界施工结果等价。 |
| 地图底图 | 两版 `RoadMapScreen` 均调用 Xaero 底图。旧 `MapTerrainSamplingService` 仅有类内引用；没有证据显示移除它使可用底图丢失。 |
| 存档兼容 | 两版 `RoadData.CODEC` 初始化文本一致；道路索引 schema 2、施工台账 schema 3、世界生成指纹 schema 1 一致。没有拿实际玩家存档重开验证。 |

旧版的八域盘点见[冻结基线](functional-inventory/README.md)。历史符号对照详表可用 `git show 55184e4:docs/FEATURE-PARITY-CHECK.md` 查阅。后续每发现一项退化，保留旧入口、新入口、可复现的最小场景及是否实地验证；优先看[技术路线](ROADMAP.md)中对重制版更有收益的工作。
