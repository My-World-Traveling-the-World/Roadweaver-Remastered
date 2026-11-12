# roadweaver-next —— 重构版

**起点：`roadweaver` 仓 `6c0ec6b`（道路 `0.9.098` / SHA `60B9A6C7`）的完整副本。**
旧仓已冻结为 **oracle（验收基准）**，见旧仓 `FREEZE.md`。

## 为什么要新仓

旧仓 109,275 行里，包语义边界已经崩了，修修补补到不了根治：

- `pathlogic`（41 文件 / 20,428 行）同时装了数据模型、选线、施工、纵断面、路网合并、校验、度量 —— **6 个不相干职责**
- `pathfinding`（91 文件）里真正的寻路算法只有 ~800 行（5%），其余是采样 / GPU / 跨度提取
- `postprocess` 4 文件 4,794 行，`RoadSnapService` 单类 3,486 行
- `StructureAvoidanceService` 单类 **4,766 行 / 160 个方法**
- 六个包名 `pathlogic / pathfinding / planning / postprocess / route / search` 都在说「路径/规划」，谁也分不清

**根本病因：A\* 同时背了「拓扑」和「几何」两个职责。** 几何只能靠事后 8 个阶段去修
（DP 简化 → Catmull-Rom → 1-2-1 平滑 → relax → 锯齿检测 → 急弯冻结 → 窗口 splice → 纵断面修复），
每修一次就多一个 artifact。

## 目标

| 目标 | 现状 | 目标 |
|---|---|---|
| 每条路的几何 | `dupXz` 总量 45、`final/直线` 2.93× | `dupXz` ≤5、`final/直线` ≤1.5× |
| 规划总时长 | ~22~25 分钟（关长城后 ~9~12）| **≤6 分钟** |
| 配置 | 241 键，UI 只暴露 45 个，11 个死键 | 241 键全可见（按 Tier 分级），死键灰显 |
| 架构 | 6 个包语义重叠、2 个上帝类 | 16 个单一职责包 |

**核心思路：职责分离。** A\* 只出拓扑，几何交给弹性带变分求解。

## 分档（不丢功能的依据）

| 档 | 行数 | 处置 |
|---|---|---|
| A 原样搬 | 3,584 | 跨 mod 三件套（刚真机验过，冻结）|
| B 结构重构 | ≈40,000 | 拆上帝类、换数据结构、消重复（行为不变）|
| C 真优化 | ≈18,000 | 空间索引 / 前缀和 / 距离变换 / 批处理（可证明等价）|
| 重写 | ≈9,000 | `band/` 弹性带 + `topology/` 拓扑层 |
| 删除 | ≈15,000 | 旧几何后处理，被新内核替代 |
| 保留不碰 | ≈24,000 | 开局预热进度 / 施工铺块 / 装饰 / worldgen 落块 |

## 必须保留的功能（一行不改）

- **开局预热动态进度**：`InitialGenerationProgressTracker`(825) + `Snapshot`(142) +
  `LoadingGenerationOverlayRenderer`(585) + `TrackerTest`(135) —— 已经是「状态 → 快照 → 渲染」三件套，有单测
- **桥梁 / 隧道建造**：`RoadTunnelBuilder` / `BridgeSegment` / `SegmentPaver` / `RoadConstructionVolumePlanner`
- **树木抑制**：`GenericVegetationFeatureMixin`（`@Mixin(ConfiguredFeature.class)`）+ `RoadSpatialIndex`
- **村庄端点筛选**：`StructureAvoidanceService` 的 `route filtered` 逻辑 —— 它在选线**之前**，几何重构碰不到
- **结构/建筑避让判据**：`StructureAvoidanceService` 的判定规则（用户已拍板「村庄裁切包围盒保留原判据」）
- **装饰**：路灯 / 栏杆 / 海晶灯
- **跨 mod 契约**：`api` / `apiimpl` / `compat` / `runtime`

## 里程碑

| # | 内容 | 验收 |
|---|---|---|
| **M0** | 本副本 + 干净 git + 构建通过 → **证明与 0.9.098 逐项一致** | 对照表全绿 |
| **M1** | 配置元数据表（241 键）| **241/241 键往返全等** |
| **M2** | UI 状态/渲染分离 + 配置页自动生成（保留预热进度）| 逐页手动验收 |
| **M3** | `StructureAvoidanceService` 拆分（160 方法 → 4~5 类）| 回归全绿 + 行为不变 |
| **M4** | 结构查询换空间索引 | `O(n·m) → O(n log m)`，结果一致 |
| **M5** | `band/` 弹性带内核（替代 DP/样条/relax/平滑/锯齿修复）| `dupXz` 45→≤10 |
| **M6** | `topology/` 拓扑层（替代 merge/braid/parallel/fine regenerate）| `dupXz` ≤5，总时长 ≤6 分钟 |
| **M7** | 收尾、删死代码、多局真机 | 全套指标 |

## 与原仓库的关系

```
roadweaver       冻结（2c6547a / tag roadweaver-frozen-0.9.098）→ 只修 bug，做 oracle
      │
      └──► roadweaver-next   全部新工作在这里
```
