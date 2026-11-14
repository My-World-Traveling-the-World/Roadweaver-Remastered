# 死码核实清单（2026-11-15）

> **本文回答一个问题**：功能盘点子代理从**旧仓库 oracle** 报出的一批「死常量 / 无效声明」，
> 在 `roadweaver-next` 里到底还成不成立？
>
> **为什么必须逐条核实**：oracle 是冻结的旧代码。`next` 是重制版 —— 有的死码已经被删，
> 有的**反而被接上了**。照单全收去删，就会删掉重制版正在用的东西。
> 本文每条都给出「旧仓库状态 / next 状态 / 建议」。

---

## 一、总表

| # | 符号 | 旧仓库 | next 状态 | 建议 |
|---|---|---|---|---|
| 1 | `CANYON_MIN_LENGTH` / `CANYON_MAX_LENGTH` | 有声明无消费 | **已不存在** | 无需处理（重制时已删）|
| 2 | `CANYON_MIN_DEPTH_BLOCKS` | 有声明无消费 | **存在，1 处 = 仅声明** | 标注或删除（见 §二）|
| 3 | `UNDERSEA_TUNNEL_SELECTION_PROBABILITY` | 未被读 | **存在，1 处 = 仅声明** | 标注或删除（见 §二）|
| 4 | `bridgeDeckSurfaceY` | 全仓无调用点 | **4 处，且被真实消费** | ❌ **绝不能删**（见 §三）|
| 5 | `bridge.minLength` | 无消费者 | 7 处引用，**已诚实标注为死键** | 无需处理（见 §四）|
| 6 | `bridge.keepLamps` | 无消费者 | 4 处引用，**已诚实标注为死键** | 无需处理（见 §四）|
| 7 | `isWallOwned` 注释与实现不符 | 命名与实现不一致 | 已搬到 `PortalSafetyRules:145` | 低价值，暂不处理 |

---

## 二、两处「未标注的真死常量」

都在 `pathfinding/PathSpanExtractor.java`，且都在常量集中区（`:64`、`:69`）：

```java
static final int CANYON_MIN_DEPTH_BLOCKS = 10;                 // 零消费
static final double UNDERSEA_TUNNEL_SELECTION_PROBABILITY = 0.5D;  // 零消费
```

**核实方法**：`grep -rn "<符号>" --include=*.java common/src/main` 命中的唯一一条就是声明行本身
（`static final ... = ...;`），没有任何读取点。

**判断**：这两个是旧版峡谷/海底隧道判定留下的**行为参数**，重制后判定路径改变，
参数没跟着删。它们**不影响任何行为**（编译器会证明零引用）。

**两种处理方式**（需要拍板）：

| 方式 | 优点 | 缺点 |
|---|---|---|
| **A. 删除** | 代码干净，`PathSpanExtractor` 从 981 行降到 979（更远离 1000 上限）| 丢失旧版设计意图（为什么峡谷判 10 格深）|
| **B. 标注为历史常量** | 保留设计信息，与项目已有「诚实标注」惯例一致 | 多两行注释 |

**倾向 B**：本项目 `BridgeKeys` 已经建立了「死键必须诚实标注」的惯例
（`BridgeKeys.java:15` 原文写着 `keepLamps / minLength —— 死键（代码里不再读取…）`），
两个行为参数也该遵循同一惯例，而不是单独删掉。而且 `PathSpanExtractor` 981 行已是
「贴线预警」名单里的文件之一，删两行对这个数字没有实质影响。

---

## 三、❌ `bridgeDeckSurfaceY` 绝不能删 —— 这是一次「反向核实」的典型

子代理报「`bridgeDeckSurfaceY` 全仓无调用点」。**在旧仓库里这是真的**（它只有定义）。

但在 `roadweaver-next` 里：

```
pathlogic/grade/RoadConstructionSafety.java:640   ← 定义
pathlogic/core/TargetYComputation.java:753        ← 真实消费，算 deckFloor[i]
pathlogic/core/TargetYComputation.java:740        ← 注释说明「下界来源是旧版的权威算法」
pathlogic/profile/ProfileDpSource.java:64         ← javadoc 引用
```

`TargetYComputation:753` 是**桥面水位下界**的唯一来源：DP 纵断面求解时
`deckFloor[i]` 作为硬约束喂进 `ProfileDpSource.Constraints`，保证桥面不会插进水里。
这正是「旧版能力变成新内核求解约束」的范例 —— **旧版里没人用的函数，重制版接上了**。

> **教训**：外部审计报「无调用点」时，必须回到**当前仓库**再核一次。
> 旧仓库的死码，可能是新仓库的地基。

---

## 四、`keepLamps` / `minLength` 已经是「诚实标注的死键」

`config/meta/BridgeKeys.java:15` 的类 javadoc 原文：

> `keepLamps` / `minLength` —— **死键**（代码里不再读取，…

而且 `config/meta/PlanningKeys.java:20-21` 也列了同类清单
（`chunkyRoadThreadCap`、`openclAccurateSamplingEnabled`、`keepLamps`、
`minLength`、`oceanBridgeMinDepth`、`placeWaypoints`）。

说明重制版**已经制度化处理**了这类死键：保留键（老配置不炸）但在 UI/文档上诚实标注。
这比直接删更好 —— 删了老存档的配置键会导致解析失败。

⇒ **无需处理**。

---

## 五、方法论：怎么判断一个符号是「真死」还是「新地基」

```
1. grep 当前仓库，数命中数
2. 命中数 == 1 且该行是声明     ⇒ 真死（零消费）
3. 命中数 > 1                   ⇒ 逐条看是不是「定义 + 拷贝 + 访问器」的假活
                                   （纯 record/bean 的 getter/setter/copy 不算消费）
4. 命中数 == 0 但旧仓库有        ⇒ 重制时已删，无需处理
5. 旧仓库 0 消费、当前仓库有消费 ⇒ 反向：重制版把它接上了，绝不能删
```

**第 3 步最容易误判**：`Java Bean` 的 `minLength()` / `setMinLength()` / `copy.minLength =`
会让命中数变成 7，看起来「有人用」，实际全是同一个字段的自循环。
判据是「有没有**非本类**的读取点，且读取结果参与了分支或计算」。

---

## 六、本轮结论

- **可动**：2 个零消费行为常量（建议标注而非删除，见 §二）
- **不可动**：`bridgeDeckSurfaceY`（新地基）、`keepLamps`/`minLength`（已诚实标注）
- **已消失**：`CANYON_MIN_LENGTH`/`CANYON_MAX_LENGTH`（重制时删掉了）
- 本文**不包含任何代码改动**，只做核实与建议；两个常量的处理方式待拍板。