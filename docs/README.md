# docs/ 文档地图

> 想知道「从哪读起」看这一页就够了。每份文档都标了**性质**与**什么时候该读它**。

---

## 一、按"你想知道什么"找

| 你想知道 | 读这份 |
|---|---|
| 现在这个仓库能做什么、怎么用、还差什么 | [`STATUS-20261113.md`](STATUS-20261113.md) ← **从这开始** |
| 重构的过程、每一次判断与误判 | [`REFACTOR.md`](../REFACTOR.md) |
| 最初为什么重构、当时的体检数据 | [`AUDIT-20261113.md`](AUDIT-20261113.md)（**先读它的「时效性标注」节**）|
| 怎么把新内核切成默认 | [`S7-SWITCH-CHECKLIST.md`](S7-SWITCH-CHECKLIST.md) |
| 哪些旧代码可以删、删到什么粒度 | [`S5-METHOD-MAP.md`](S5-METHOD-MAP.md) |
| 后处理 20 个文件分别是留还是删 | [`S5-POSTPROCESS-LEDGER.md`](S5-POSTPROCESS-LEDGER.md) |
| 死代码怎么审（以及为什么不能批量删） | [`DEAD-METHOD-AUDIT.md`](DEAD-METHOD-AUDIT.md) |
| 维护性指标快照 | [`METRICS-20261113.md`](METRICS-20261113.md) |

---

## 二、性质分类（决定它会不会过期）

| 文档 | 性质 | 会过期吗 |
|---|---|---|
| `STATUS-*` | **当前快照** | 会 —— 有新进展就作废，需要重写一份新的 |
| `REFACTOR.md` | **过程日志** | 不会 —— 追加式，历史事实 |
| `AUDIT-*` | **历史体检** | 会 —— 已有「时效性标注」节区分「仍成立/已修复/已更正」|
| `S7-SWITCH-CHECKLIST` | **操作手册** | 不会（除非流程变）—— 照着做就行 |
| `S5-*` | **台账** | 会，但**由测试守着**：`PostprocessLedgerTest` / `S5MethodMapTest` 会因代码漂移而红 |
| `DEAD-METHOD-AUDIT` | **方法论 + 一次性清单** | 清单会过期，方法论不会 |
| `METRICS-*` | **指标快照** | 会 —— 数值会变 |

> 一句话：**带日期的是快照（会过期），不带日期的是手册（稳）**。

---

## 三、文档依赖关系

```
STATUS（当前快照，入口）
  ├─► S7-SWITCH-CHECKLIST（怎么切默认）
  │     ├─► S5-METHOD-MAP（删什么，方法级边界）
  │     │     └─► S5-POSTPROCESS-LEDGER（文件级分类）
  │     └─► verify.ps1（每步验收）
  ├─► DEAD-METHOD-AUDIT（死代码方法论）
  └─► METRICS（指标）

AUDIT（历史体检 + 时效性标注）──► STATUS（最新数字以 STATUS 为准）
REFACTOR.md（过程日志）──► 引用以上全部
```

---

## 四、四份机器可检的台账（改了代码就必须同步）

| 台账 | 守着它的测试 |
|---|---|
| `S5-POSTPROCESS-LEDGER.md` | `PostprocessLedgerTest` |
| `S5-METHOD-MAP.md` | `S5MethodMapTest` |
| `REFACTOR.md` 里的重制替换台账 | `LegacyLedgerTest` |
| `architecture/deps-baseline.txt` | `ArchitectureGuardTest` |

**这四份不是"文档"，是"配置"** —— 它们与代码一起被断言，漂移就红。

---

## 五、不在 docs/ 但同样重要的

| 路径 | 作用 |
|---|---|
| [`verify.ps1`](../verify.ps1) | 双模块闸门（fabric 编译 + common 回归），**每次提交前必跑** |
| [`ARCHITECTURE.md`](../ARCHITECTURE.md) | 分层与包职责 —— **先读它顶部的「时效性更正」**：`foundation.*` 那套分层从未落地，真实架构是保留旧顶层包就地重构 |
| [`CONVENTIONS.md`](../CONVENTIONS.md) | 编码约定 —— **先读顶部「时效性更正」**：它描述的是旧仓；仍然成立的是 §4.1 三个环境坑（JDK21 / Gradle 8.8 / 离线）与 §4.3 部署规矩 |
| `common/src/test/java/.../architecture/` | 8 个守卫类的实现 |