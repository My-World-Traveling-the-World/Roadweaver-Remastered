# 跨度回写设计（2026-11-15）

> **✅ 已实施（2026-11-15）**：用户在 2026-11-15 拍板 **方案 A**，本文档记录的方案已落地到 `ModeDpBridge` / `TargetYComputation` / `Road` 三个文件，
> 默认关闭（`-Droadweaver.spanWriteback=on` 才生效），带三道保护，`SpanWritebackTest` 8 个用例 + 负控守着。
> 本文保留的是**设计推理过程**，实施细节见 `STATUS-20261115.md`。
>
> **★ 2026-11-15 文档更正**：此前这里写着「状态：**仅设计，未实施**」—— 那是加实现之前的说法，
> 已经**过期**。实现已于同日落地（提交 `5024a1d` 实施 + `123ac7d` 收口）。
> 前置也早已全部到位：三条禁地面来源（水面/保护区/**已有公路**）+ `mergeByModes` 纯函数（11 钉子）。
>
> **三道保护的最终定义以代码为准**（本文第四节写的是设计时的设想，与实现略有出入，
> 差异见下方「实施与设计的出入」）。

---

## 一、要解决的问题

`ModeDpSolver` 已经能判出每站的 `GROUND / BRIDGE / TUNNEL`，`mergeByModes` 也能把它
安全地合进旧跨度列表（保留另外五种 span 类型）。**缺的是「把 DP 结果送到 spans 定稿点」**。

难点在于信息流方向：

```
Road.generateRoad:
  :197  computeTargetY(...)            ← terrainY / groundBans / modes 全在这里面产生
  :200  refineTunnelSpans
  :205  for 3 轮 { 桥精修 + settleProfile → 再调 computeTargetY }
  :218  + protectedGaps
  :227  + plannedReuse                 ← spans 在这里才定稿
  ...
```

**modes 在 `computeTargetY` 内部，spans 定稿在它外面**，中间还隔着 3 轮重算。
不给 modes 一条出口，就接不上。

## 二、已确认的硬约束

| 约束 | 内容 |
|---|---|
| **签名不能乱改** | `SingleSourceOfTruthTest` 按**触发文件数**数 `computeTargetY(`（排除定义文件），基线 1。改方法名会触发 `stale` 报错 |
| **观测开关不等于求解开关** | `ModeDpBridge.observe` 在 `mode()==OFF` 时**整个跳过**（`:140`）。若把求解塞在 observe 里，关掉观测就断供 |
| **`settleProfile` 会重算** | `Road.settleProfile` 内部也调 `computeTargetY` ⇒ 快照会被更新为最新值（好事，但要保证是**最后一次**） |
| **必须能一键回退** | 回写会改路形，出问题要能立刻回到旧判定 |
| **无法真机验证** | 当前约定「暂时不上实机」⇒ 只能做「默认关闭 + 开关 + 差异日志」 |

## 三、三个方案

### 方案 A：ThreadLocal 快照（推荐）

```
computeTargetY 内部：
  ModeDpBridge.solveAndStash(...)   // 求解 + 存 ThreadLocal（与观测开关无关）
  ModeDpBridge.observe(...)         // 复用求解结果打日志

Road.generateRoad：
  开场   ModeDpBridge.clearStash();          // 防残留
  :234后 if (writebackEnabled()) {
             Stash s = ModeDpBridge.consumeStash();   // 读并清除
             if (s != null) spans = mergeByModes(spans, s.modes(), s.centers(), s.n());
         }
```

- **改动面**：`ModeDpBridge` +80 行（求解/存取/清理），`TargetYComputation` 改 1 处调用，
  `Road` +6 行。签名零变更。
- **风险**：ThreadLocal 生命周期。用「开场清理 + 消费即清除」双保险；
  本仓库已有 `LAST_SEGMENT_FAILURE`、`ACTIVE_PLANNING_SNAPSHOT` 等先例。
- **回退**：开关默认关，关掉时 `clearStash`/`consumeStash` 都是空操作。
- ⚠️ **本仓库刚吃过 ThreadLocal 的亏**（`shouldAvoidPlanning` 静默失明，第 6 轮才补观测），
  所以用这个方案必须同时上「stash 命中/未命中计数器」，让「没数据」可见。

### 方案 B：扩展 `computeTargetY` 返回值

新增 `record ProfileResult(List<Integer> targetY, ModeDpSolver.Mode[] modes)`，
`computeTargetY` 改返回它，6 个调用点全改。

- **改动面**：`TargetYComputation` + `Road`（4 处调用）+ `RoadPathCalculator`/`PathMetricsOps` 等，
  还要同步 `SingleSourceOfTruthTest` 基线。
- **风险**：面大、守卫要改、容易漏改。
- **优点**：无 ThreadLocal，信息流显式，最干净。
- **回退**：同样靠开关，但改动面大意味着回退成本高。

### 方案 C：spans 定稿后重跑求解

在 `:234` 之后用最终地形重新采样 + 重跑 `ModeDpSolver`。

- **改动面**：小（只动 Road）。
- **致命缺点**：要重新采样地形（贵），而且「重跑的输入」与「computeTargetY 内部的输入」
  未必一致（采样缓存、水位探针、结构快照作用域都可能不同）⇒ **又造一个两套事实**。
- **结论**：不推荐，与批 2/批 3 一直在消除的「两套事实」背道而驰。

## 四、推荐

**方案 A，但必须带三个保护**：

1. **默认关闭**：`-Droadweaver.spanWriteback=on` 才生效；关闭时零开销、零行为变化。
2. **stash 可观测**：`stashHit` / `stashMiss` 计数器 + 日志，让「没拿到数据」不再静默
   —— 这正是第 6 轮那个教训的直接应用。
3. **差异日志**：回写前后各打一行「桥 N 段/M 站、隧道 K 段」，与旧判定并排，
   真机时能一眼看出改了多少。

### 实施与设计的出入（2026-11-15 补记）

设计时的三道保护是「默认关闭 / 计数器 / 差异日志」，**实现时第三道换掉了**：

| 道 | 设计设想 | 实际实现 |
|---|---|---|
| ① 默认关闭 | `-Droadweaver.spanWriteback=on` | ✅ 一致（另外接受 `=true`） |
| ② 计数器 | `stashHit` / `stashMiss` | ✅ 扩成四个：`solved/consumed/missed/applied` |
| ③ **差异日志** | 「回写前后桥 N 段/M 站」并排 | ❌ **换成了「生命周期双保险」** |

**为什么换**：单纯打日志解决不了「一条路里 `computeTargetY` 被调用多次」带来的
跨次串味问题（`Road.java` 实测主路径 4 次 + `tryRiverRepair` + `settleProfile`）。
日志只在真机上有用，而生命周期漏洞在**开启的第一天就会出错**。
所以第三道改成：**开场 `clearStash` 防残留 + `solveAndStash` 入口统一清理 + 取用即清**
（保证「每次求解至多被消费一次」）。
日志仍然保留（`[SPAN-WB] phase=main` / `phase=post-repair`），只是不再算作「一道保护」。

**日志标签也不是设计里写的 `[SPAN-WRITEBACK]`，实际是 `[SPAN-WB]`** —— 见第六节。

## 五、~~本轮**不实施**的理由~~（**已过期，仅存档**）

> **★ 2026-11-15**：用户当日拍板方案 A，本节的「不实施」已不成立。
> 保留它是因为其中列出的三条风险**依然真实**，只是通过「默认关闭 + 三重保护」
> 把风险降到可接受；尤其第 2 条（实验室无法验证桥判得对不对）**至今仍成立**。

1. 回写会**改变生产 spans** ⇒ 改变桥隧位置 ⇒ 改变施工结果。这是整个重构里
   风险最高的单点之一。
2. 既定约定「暂时不上实机」，而这类改动**实验室无法验证对错**
   —— 749→811 个测试都不覆盖「桥判得对不对」。
3. 用户规则：改动生产代码前必须先给方案并得到确认。

## 六、如果要真机验证，需要看什么

| 日志 | 判断 |
|---|---|
| `[SPAN-AB] legacy[bridge=..] dp[bridge=..]` | DP 与旧判定差多少（**实现在用 `[SPAN-AB]`，不是本文设计的 `[SPAN-WRITEBACK]`**） |
| `stash miss` 计数 | 快照传递是否可靠 |
| `[SPAN-AB] ... road=N` | 已有公路来源是否异常偏大（自排除是否完备）|
| 实际存档的桥/隧道位置 | 有没有「该架桥没架」「不该挖的挖了」 |

## 七、备选：不接线也能完成的批 2 收尾

如果决定**暂不接线**，批 2 可以这样收口：

- 把「三条禁地面来源 + 合并纯函数 + 全面观测」定为批 2 的**交付边界**；
- 在台账里明确写「桥隧决策权仍在 `PathSpanExtractor`，DP 为观测 + 待接线」；
- 批 2 标为「观测完成、决策未接管」，而不是含糊的 DONE。

这条路的代价是：**批 2 的核心目标（DP 接管桥隧）没有达成**，
但收益是零风险，且后续任何接线尝试都有现成的纯函数与观测基础。