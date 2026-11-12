# 交接清单（2026-11-13）

> 如果你（或下一个我）明天接手这个仓库，**只读这一页 + 跑一条命令**就能动起来。
> 想知道"为什么"，再去 `docs/README.md` 的导航里找。

---

## 一、30 秒版

```powershell
cd D:\云游四海-Mod源码\roadweaver-next
.\verify.ps1          # 必须看到 ALL GREEN + PASS tests=684
```

看到这两行就说明：**仓库是好的，可以开始改**。

```powershell
# 想让新算法上生产（可选，不影响存档安全）
-Droadweaver.profileSolver=dp
```

---

## 二、读什么（按顺序，最多 3 份）

| 顺序 | 文档 | 只读它的哪一节 |
|---|---|---|
| 1 | [`docs/STATUS-20261113.md`](STATUS-20261113.md) | 全文（很短，是当前快照）|
| 2 | [`docs/README.md`](README.md) | 第一节「按你想知道什么找」|
| 3 | 依上一个问题的答案跳转 | — |

**不要**从头读 `REFACTOR.md`（1300+ 行）—— 它是过程日志，只在你想知道"当初为什么这么判断/判断错在哪"时才翻。

---

## 三、环境三个坑（必读，会浪费你半小时）

| 坑 | 症状 | 解法 |
|---|---|---|
| `PATH` 上的 `java` 是 **JDK 26** | `Unsupported class file major version 70` | `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'` |
| 用 Gradle 9.x | `Could not create an instance of type LoomProblemReporter` | 用 wrapper 锁的 **8.8**（`verify.ps1` 已写死路径）|
| Gradle daemon **间歇性连不上** | 输出 `Could not connect to the Gradle daemon` | **重跑即可**。`verify.ps1` 已内建 4 次重试，且会明确打印 `NOT a compile error` —— 别去翻源码 |

> 第三条踩过两次：它曾经被包装成 `FAIL: fabric does not compile`，害我去查根本不存在的编译错误。
> 现在脚本会说真话。

---

## 四、我要做的三步（唯一需要人类的部分）

```powershell
# 1. 跑一次游戏，启动参数加：
-Droadweaver.profileAb=on

# 2. 玩一会儿（开局预热就会跑几十条路线），然后：
#    <游戏根>/logs/roadweaver-terrain-ab.tsv
#    → 复制成 common/src/test/resources/profile/terrain-samples.tsv

# 3. 验证
.\verify.ps1
```

看输出里的 `[PROFILE-REPLAY]` 汇总行。

**通过标准**：`样本 >= 20` 且 `DP 土方合计 <= 旧`。

通过之后，`docs/S7-SWITCH-CHECKLIST.md` 的第 1 级就可以往下走了。

---

## 五、改代码时**绝对不能碰**的清单

| 类别 | 具体 |
|---|---|
| 存档格式 | `RoadData.CODEC`、`PlayerRoute`/`RoutePoint` Codec、`SpanType` 枚举、`RoadChunkStore.SCHEMA_VERSION=2`、`RoadConstructionLedger` schema=3、`WorldgenFingerprint.CURRENT_SCHEMA_VERSION=1` |
| 配置键名 | **= Java 字段名**（GSON 直接序列化），改字段名就等于清空玩家配置 |
| 跨 mod 契约 | `LoadSignalBridge` 的 ownerId、`GreatWallReadinessApi`、`WallRoadApi`、`mixins.json` 的 `"plugin": MixinConfigPlugin`、`RoadPositionQuery` 签名 |
| 用户冻结三项 | 长桥 ±5 正弦弯（`util/Line:45-57`）、村庄裁切包围盒判据（`ObstacleJudge:136-190`）、悬空可接受 |
| 施工敏感 | `RoadBlockPlacer:87`、`isProtected*` 四层、`SegmentPaver` 的 SKIP/PAVE_SURFACE_ONLY 优先级、`TunnelClearLedger` |
| **一个以为能删但其实不能删的** | `promoteRuntimeBridges` —— 它是「别把半边河床填平」的安全网（详见 `REFACTOR.md` 的自我更正段）|

---

## 六、`verify.ps1` 报错怎么读

| 它说 | 含义 | 你该做 |
|---|---|---|
| `FAIL: fabric does not compile` + 一堆 `.java:行号` | **真的编译错了** | 看行号改代码 |
| `FAIL: gradle daemon unreachable ... (NOT a compile error)` | 环境问题，与代码无关 | **重跑**；连续多次再查 loopback/防火墙 |
| `FAIL: regression suite` + `AssertionFailedError` | 用例红了 | 看断言消息（我的断言都写了中文说明）|
| `FAIL: regression suite` 但没有断言行 | 可能是 JavaExec 的 `main()` 抛异常 | 往上翻 `Exception` 栈 |

---

## 七、下一阶段（S7）的入口

| 级别 | 内容 | 现在能不能做 |
|---|---|---|
| 第 0 级 | 采集真实地形 | ✅ **只等你跑一次游戏** |
| 第 1 级 | 剖面求解器切默认 | 🟡 代码已就位（`-Droadweaver.profileSolver=dp`），等第 0 级数据 |
| 第 2 级 | 桥隧联合求解接入 | ⏳ 要写 200~300 行（`docs/S7-SWITCH-CHECKLIST.md` 有说明）|
| 第 3 级 | 删旧实现 | ⏳ **前置**：先抽 `SurfaceGradeLimits`（清单「第 3 级前置 A」有 AST 实测行号与两个陷阱）|
| 第 4 级 | 清死代码与注释 | ⏳ |

---

## 八、未完成事项（明确列出来，不含糊）

| 事项 | 状态 | 为什么没做 |
|---|---|---|
| S7 第 1 级切默认 | **未完成** | 需要真实地形数据（本会话内无法自己产生）|
| S7 第 2~4 级 | **未完成** | 依赖第 1 级 |
| `SurfaceGradeLimits` 抽取 | **未完成** | 两次尝试都改坏了源码，已回滚；正确做法（AST）已写进清单 |
| 方法级注释（depth-D 后三层） | **只量化未补** | 3,338 个 public 方法里 906 个有注释；已 ratchet 防倒退，未逐个人工补 |
| `FinslerMetric.cost()` 零生产调用 | **未处理** | 不在本次目标内（属旧审计遗留）|
| `InitialGenManager` 静态耦合 | **未处理** | 同上 |
| 路网/后处理以外的 UI 深层重构 | **未做** | 本次只保证「功能不丢失」+ 配置界面拆分 |

---

## 九、一条给自己/接手者的提醒

这个仓库里**最容易犯的错不是写错代码，是把"旧结论"当"当前事实"**。

本会话我自己踩了 5 次（详见 `docs/STATUS-20261113.md` 的「别再犯」清单）。所以：

> **动手前先查，别凭印象。**
> 查不到代码里那个类 → 它可能改名/被删了（`ARCHITECTURE.md` 里的 `PathfindingAlgorithm` 就是）；
> 查不到那个数字 → 重新采一遍（`STATUS` 的数字本轮刚复测过，改动后会漂）。