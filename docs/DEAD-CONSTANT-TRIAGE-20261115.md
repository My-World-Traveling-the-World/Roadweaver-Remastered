# 死常量分诊（2026-11-15）

> **背景**：独立复核第 11 条报「启发式扫出 79 个 `static final` 声明后零引用」，
> 但明确写了「这个数是上限，里面误报要人工分诊」。这份就是那份分诊。
>
> **判据**：剥离注释后，该常量名在**全仓生产代码**（common/fabric/api 的 `src/main`）
> 里出现次数 ≤ 1（只有声明本身）。测试源码不计入 —— 测试引用≠生产在用。

---

## 一、总结

| 分类 | 数量 | 处置 |
|---|---|---|
| 注释**声称有行为**、实际零引用 | 9 | **6 个已加 `⚠️` 标注**（本文档 + 代码内） |
| 其中：仅被测试引用（不算死） | 2 | 保留，不加标注 |
| 其中：原本就带 `@SuppressWarnings("unused")` | 1 | 保留 |
| 注释**无行为声称**（噪音） | 70 | 不逐个处理，见第四节 |
| **合计** | **79** | |

**结论：真正需要处置的是 6 个，已全部在代码里加了「零引用」警示。**
剩下 70 个不影响正确性判断，但说明代码里有一批历史残留 —— 见第四节。

---

## 二、已加标注的 6 个

| 常量 | 位置 | 注释声称什么 | 实际情况 |
|---|---|---|---|
| `VILLAGE_GAP_BELOW_TOLERANCE` | `StructureAvoidanceService:99` | T-35 修复：放宽下方容忍让穿村路段成缺口 | **从未实现**（上一轮已标注；旧版同样只声明不用） |
| `MAX_ACTIONS_PER_TICK` | `RouteWorldEditor:54` | 每 tick 最多执行几次按键动作，**防止连按过快** | **这条保护不存在**。`tick()` 里没有「计数达上限就丢弃」的逻辑 |
| `DASH` / `GAP` | `RoutePreviewRenderer:50-51` | 虚线：实线段长度与间隔 | **预览线实际是实线**，两个常量没有绘制代码读取 |
| `HEIGHT_REPAIR_CONTEXT_BLOCKS` | `AccurateGradeRepairPlanner:78` | 高度异常在一个显式 50 格局部窗口内修复 | **被 `HEIGHT_ANOMALY_DETOUR_BLOCKS`（同为 50）取代**，实际生效是后者 |
| `DB_LOCK_TIMEOUT_MS` | `RoadConstants:302` | （无 javadoc）数据库锁超时 | 全仓**没有 `tryLock` / `FileLock`**；持久化走 `RoadChunkStore` / `index.json` 事务 |

**为什么标注而不是删**（与 `VILLAGE_GAP` 同一条理由）：这些多数是对旧版的
**忠实继承**，删掉会让后来人以为「曾经有过这个能力」。标注既清除了误导，
又保留了历史记录。**唯二例外**：`DASH`/`GAP` 是 `private`、`MAX_ACTIONS_PER_TICK`
也是 `private`，删掉不会有任何风险 —— 但保留 + 标注的成本更低，且能提醒
「这里原本想做虚线/限流」。

---

## 三、另外 3 个不需要处置的

| 常量 | 为什么不算死 |
|---|---|
| `DEFAULT_FLIP_COST` / `DEFAULT_CURVE_COST` | `HeadingContinuityCostTest:17-18` 直接引用它们当断言基线 ⇒ **被测试锁着**。生产侧走配置值，不读默认常量，属正常设计 |
| `LOCAL_REPAIR_ACCEPT_SLACK_BLOCKS` | 原本就带 `@SuppressWarnings("unused")`，javadoc 已写明「不参与判定」，是**诚实标注**的范例 |

---

## 四、70 个「无行为声称」的噪音：两类来源

抽查后确认它们不是随机的，而是**两次大改动留下的沉淀**：

### 4.1 已删 A* 寻路器的残留（约 8 个）
`PathfindingConstants` 里：
- `WATER_BIOME_BASE_COST = 12` —— 旧 A* 的群系基础代价
- `HEURISTIC_EPSILON = 0.2` —— 旧 A* 的启发式膨胀系数

这两个随「删旧 A* 寻路器 4 类」一起失去了读者。**同一主题已有一笔待办**：
15 个旧 A* 专属**配置旋钮**（`contourDiscount` 等）仍留在 config/meta/UI/lang，
被 `ConfigUiHonestyTest` 诚实标注「不生效」，删它们牵动 UI/meta/lang，待用户拍板。
这两个常量应当与那批配置**同批处置**，不要单独删。

### 4.2 UI 主题类的未使用项（`MapTheme` 占大头）
`client/map/MapTheme.java` 里有一批颜色/内边距常量零引用
（`COLOR_HOVER_HIGHLIGHT` / `COLOR_PLAYER_ARROW` / `DOCK_PROGRESS` 等）。
这是**主题面板重构后遗留**：配色改用别处定义，旧常量没清。
它们不影响任何判断（纯数值），删不删都行 —— **但不符合「上界清晰」的工程口味**。

---

## 五、这份分诊**没有**做什么

- **没有删任何一个常量**（除标注外零改动）。要删需先确认没有外部 mod 通过反射读它们。
- **没有核 `public` 常量是否被 API 契约承诺**（`RoadConstants` 与
  `PathfindingConstants` 里的 `public static final` 可能在 `api/` 模块的契约语义内）。
- **没有建立「常量级」的无引用守卫**。现在的 `DeadCodeGuardTest` 只管**类**，
  常量级没有守卫 ⇒ 这次扫出来的 79 个，下次改动还会慢慢变多。
  要加守卫得先决定「白名单怎么维护」，属独立改动。
- **70 个里只抽查了两类来源**，没有逐个定性。

---

## 六、建议的后续动作（按性价比）

1. **低风险、立刻可做**：把 4.1 的 2 个常量与「15 个旧 A* 死配置旋钮」
   **同批**处置（一起删或一起标注），避免两套口径。
2. **低风险**：删 `DASH` / `GAP` / `MAX_ACTIONS_PER_TICK` 这三个 `private` 常量
   —— 但保留 + 标注已足够，收益不大。
3. **中风险**：给常量加一条无引用守卫。**不建议现在做** ——
   79 个基线 + 白名单维护成本，会变成又一个「加了但没人看」的守卫。
4. **不做**：`MapTheme` 那批颜色常量，纯数值无判断价值。