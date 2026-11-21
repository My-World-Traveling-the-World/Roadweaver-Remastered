# 2026-11-21 客户端道路失败与前置模组核查

> 本文保留修复前的 0.9.104 现场取证。道路 0.9.106 与长城 0.7.128 已部署，修复验证及待测范围见 [HANDOFF 第97轮](HANDOFF.md)；下文的失败计数不代表新包表现。

本轮针对正在运行的 WAYFARING 实例取证，不是模板或合成地形推测。客户端加载道路 **0.9.104**，不是已构建但未部署的 0.9.105。本轮不改游戏配置、存档、施工保护或桥隧选择策略。

## 已确认的运行结果

日志位置：`D:\minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\logs\latest.log`。

截至本轮取证，日志记录 **8 次道路终态拒绝，未出现 DP 成功日志**。首批六条有可匹配的真实地形记录，全部完成粗规划，随后在高程/施工连续性链路失败；不能概括为拓扑算法一开始就没找到路。后两条在 21:21:32、21:21:43 同样因施工连续性失败被拒。

| 模组 | 直接证据 | 可以下的结论 |
| --- | --- | --- |
| 河流 | 20:13:13 `Streams startup prewarm complete ... plates=144 ... built=144`；20:21:29 道路 `river-ready=true` | 河流规划数据预热已完成、道路收到就绪信号；不能证明全范围实际地形方块已生成 |
| 长城 | 20:21:23 `PLANNING_COMPILE_FAILED phase=missing-inputs ... fineIndex=false realization=true chunkCompiler=true; the wall will not paint in this session` | 本会话长城生产编译失败，不能声称已经正常生成 |
| 长城→道路门 | 同时 `ROAD_GATE_HELD success=false stage=FINE_SAMPLE`；20:31:23 道路 `reason=STALLED` | 长城未发布成功就绪，道路随后等待超时；未找到后续长城编译恢复成功记录 |
| 道路 | 20:38:57 起反复 `profile solver=dp infeasible`；5条连续性修复失败、1条 `invalid_finalized_road:invalid-height-step` | 首六条在终态被拒；两条追加失败也已入日志，尚未做追加地形重放 |

长城缺少 fineIndex 的上游原因尚未核实，不把“缺输入”直接归因于配置或 API 兼容；这部分仅阅读日志，未改长城仓库。

## 道路已定位的故障链

1. `PathSpanExtractor.extractSpans(...)` 初次只收集桥梁跨度，没有隧道；六条道路的 `stage=span_extraction` 日志全部 `tunnel=0`。
2. `Road.generateRoad(...)` 接着调用 `computeTargetY(...)`，之后才 `refineTunnelSpans(...)`。过水路线还会先用空跨度计算一次临时纵断面。因此首轮高程求解面对的是窄地表窗口。
3. `TargetProfileSolver.solve(...)` 按实际配置把窗口设为 `terrain-5 .. terrain+4`，一阶高差1、每两站高差1；并未因初轮缺隧道而得到可施工的地下通道窗口。
4. 六份真实地形即使去掉端点钉高、桥面下界与隧道覆土等附加约束，原 DP 仍全部无解。这证明问题已经存在于地表窗口与坡度的组合，不是仅由这些附加约束造成。
5. `ProfileFallback.terrainFollowing(...)` 遇到互斥窗口时仍返回数组。其局部夹逼会在窗口与坡度互斥时保留窗口，因而无法保证一阶/二阶坡度；“结果一定满足硬约束”的注释不成立。
6. `TargetProfileSolver` 两个失败分支都不检查返回剖面的坡度违规，就把它作为成功的 `Optional<int[]>` 交给后续跨度与施工修复。后续日志显示高度突变，最终校验拒绝，避免把坏路写入存档。

这不是单纯“DP 算法不够快”或“调大线程”的问题。准确地说，**桥隧输入时序、地表窗口适用范围与不可行结果处理没有形成闭环**。现有最终拒绝规则正在拦坏产物，不能删除这些规则掩盖失败。

源码锚点（位于 `common/src/main/java/net/shiroha233/roadweaver/`）：

| 文件:行 | 核对点 |
| --- | --- |
| `pathfinding/PathSpanExtractor.java:156` | 初次仅收集桥梁跨度 |
| `pathlogic/core/Road.java:194` | 空跨度的临时高程在隧道精修之前 |
| `pathlogic/core/Road.java:204` | 正式高程在后续隧道精修之前 |
| `pathlogic/core/TargetProfileSolver.java:144` | 地表挖填窗口；169/187直接接纳回落数组 |
| `pathlogic/profile/ProfileFallback.java:135` | 窗口互斥时局部钳位不能保证坡度；163为反向同类分支 |
| `pathlogic/profile/ProfileDpSource.java:159` | 覆土超过挖深时把下界抬到覆土上界 |

## 实际重放

运行：`python scripts/evidence/replay_client_profile_failures.py`。

直接用 JDK21 编译并调用生产 `ProfileDpSource`、`ProfileDpSolver`、`ProfileFallback` 及其两个纯数学依赖，没有替身求解器。六份完整地形已保存于 [取证数据](evidence/client-road-profile-failures-20261121.json)，以后不需要用户重开游戏即可重放。

| 端点 X/Z | 站数 | 放宽到无附加约束后 DP | 修复结果坡度违规计数 |
| --- | --- | --- | --- |
| -672/-432 → -240/-1376 | 1010 | 无解 | 92 |
| -672/-432 → 832/304 | 1716 | 无解 | 277 |
| 2128/-1328 → 2016/-816 | 528 | 无解 | 78 |
| 1056/-1200 → 2016/-816 | 1124 | 无解 | 40 |
| 2128/-1328 → 2864/-1216 | 757 | 无解 | 380 |
| 832/304 → 1056/-1200 | 1563 | 无解 | 173 |

计数沿用 `ProfileFallback.violations`：同站可同时违反一阶与二阶，因此不是违规站数，也不等于生产日志只统计的相邻大台阶数。这里未录到完整端点钉高、水位下界、隧道掩码和 X/Z 站序，**不声称是最终施工链的完整重放**。

一个无需相信求解器的反例：第一条地形记录第239、240站分别为124、143；前站窗口最高128，后站窗口最低138。相邻至少差10格，与最大1格互斥。任何算法都不能同时满足这组约束。

## 另一个已确认的隧道窗口缺陷

`ProfileDpSource.prepare` 与 `ProfileFallback.terrainFollowing` 都先设 `lo=terrain-cut`，再设隧道 `hi=terrain-cover`；当 `cover>cut` 时用 `lo=hi` 消除冲突。实际 cut5、cover8 会把每个已标记隧道站锁成 `terrain-8`，迫使隧道跟着山体起伏，无法选择更深的平缓剖面。

这是源码确定存在的约束问题，但不是六条首轮失败的必要解释：首轮隧道掩码为空，本轮不把它误报成唯一根因，也未改变窗口或桥隧几何。

## 本轮已经修正的诊断错误

- `RoadDataValidator` 保护缺口日志传入每端 X/Y/Z，但格式串只有两个坐标槽，导致第二端、高差、道路身份全错位。现在每端完整输出三维坐标。
- `RoadPathCalculator` 连续性预检传入冲突范围起止两个值，但格式串只有一槽，导致宽度和道路身份错位。现在完整输出 `unsafeFillRange=start..end`。

守卫解析真实 Java 日志调用，分别断言11和12个格式槽与参数一一对应；不能靠中文注释或空集合通过。L1、L2分别恢复旧错误格式，各有1个断言失败，跳过/终止0；逐文件 checkout 恢复且 SHA 一致。这两项是**日志修正，生成几何未修复**。

## 后续修复顺序

1. 以本轮真实地形为固定输入，补录完整端点、桥面下界和隧道边界；先建立高程求解到最终跨度的可离线验收，不能只有纯数学解。
2. 把已存在的独立山体/洞口约束接到高程求解之前；明确普通地表、洞口、洞身各自允许的窗口。先复用既有桥隧判据，不顺便改变 crossing 来源或开启 spanWriteback。
3. 修正已标记隧道的可选下界；保持覆土上界、端点钉高与水位下界，不能把山体表面直接平移当洞身。
4. 修复器必须区分“合法修复”与“仍然不可行”，返回可定位的首个冲突，不把坏数组当成功。不可行应交回同一新版规划链处理，不能回退旧算法。
5. 六条真实输入逐条验证非空、端点、坡度、施工占地、桥面和覆土，再做新世界实机确认；不以调大挖填上限或放松最终校验代替修复。
6. 长城在其自身仓库独立追踪 fineIndex 的构造/发布/失败路径；河流预热完成不作为长城或道路生成成功的证明。

本轮未部署、不改现场存档。主故障尚未修复；不能把新增两项日志守卫全绿或配置版0.9.105构建成功当成“道路现在能正常生成”的交付。
