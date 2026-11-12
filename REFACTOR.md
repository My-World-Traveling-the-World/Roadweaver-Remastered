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

---

# 重制替换台账（机器受检）

**这份台账由 `LegacyLedgerTest` 逐行解析并校验**，不是给人看的装饰。

## 铁律（用户 2026-11-13 拍板）

> **先重制，后删除。** 重制期间旧实现原地保留、打标记、进台账；全部域做完后再照台账逐条核实删除。

**原因**：重制期间旧代码是承重的（`BandPathfinder` 出完中心线，高度/桥隧/施工全靠它们）。
边重制边删 ⇒ 中间任何一域没做完，整个 mod 就不可运行，出问题也没有可对照的基线。

## 删除判定（4 条全满足才许删）

| # | 条件 | 怎么核 |
|---|---|---|
| 1 | 该域新实现有独立测试且全绿 | 测试报告 |
| 2 | 旧类**零外部引用** | **全模块**扫描（common + fabric + api + api-fabric）+ JSON |
| 3 | 无运行时反射 / JSON 依赖 | 类名 grep + `mixins.json` / `fabric.mod.json` |
| 4 | 删完回归仍全绿 | 跑一遍套件 |

> 第 2 条的「全模块」是踩坑换来的：`StructureKeys` 被 common 里 11 处引用、
> `RoadBeardifierAccess` 只在 `fabric` 模块的 mixin 里被引用 —— 只看 common 会误判成死代码。

## 台账（改这里必须同时改代码，否则测试失败）

<!-- LEDGER:BEGIN -->
LEDGER|1|纵断面与坡度|pathlogic/grade|profile|TODO|3543
LEDGER|2|桥隧判定|pathlogic/tunnel|span|TODO|2918
LEDGER|3|桥体与施工|pathlogic/bridge|construction|TODO|4085
LEDGER|4|路口与合并|pathlogic/junction|roadnet|TODO|1877
LEDGER|5|几何后处理|postprocess|geometry|TODO|5615
LEDGER|6|结构避让|pathlogic/structures|avoidance|TODO|6291
LEDGER|7|验证与收尾|pathlogic/validation|validate|TODO|1594
<!-- LEDGER:END -->

字段：`批号|域|旧实现包(相对 net.shiroha233.roadweaver)|新实现包|状态|旧代码行数`

> 新实现包名刻意避开仓库里已有包的语义，否则「新实现包已存在」的判断会失真：
> · 路口/合并叫 `roadnet` 而不是 `network`（`network` 已是模组网络层）
> · 几何后处理叫 `geometry` 而不是 `band`（`band` 已是弹性带内核，是它的依赖不是替代物）
>
> 这两条都是 `LegacyLedgerTest` 上线当天抓到的真问题 —— 守卫第一次跑就逮到两个重名。

状态取值：`TODO` 未开始 / `IN_PROGRESS` 重制中 / `DONE` 已完成（此时新实现包必须存在，且旧实现包内每个文件必须带 `待删（批N）` 标记）。

## 旧实现的标记规范

每个待删旧类的**文件职责头**写成：

```java
/* 文件职责：<做什么> —— 旧实现。
 * ⚠ 已被 <新实现> 取代，待删（批N）。
 *   删除条件：<测试名> 全绿 + 本类零外部引用 + 回归全绿。
 */
@Deprecated(forRemoval = true)
```

拉取当前全部待删文件：

```
rg "待删（批" common/src/main/java
```

## 单文件体积约束（口径已改）

`ArchitectureGuardTest` 的行数上限按**有效代码行**统计 —— **注释与空行不计**（用户 2026-11-13 要求）。
所以「写完整中文注释」和「守住文件体积」不再互相打架。
