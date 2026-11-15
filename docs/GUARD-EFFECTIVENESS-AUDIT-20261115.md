# 架构守卫有效性审计（2026-11-15）

> **这份文档回答**：`architecture/` 下那 16 个守卫，**真的在守吗**？
>
> **结论先说**：
> ① 有 **10 个守卫用 `Assumptions` 静默跳过**，其中 3 个是**全部用例**都能跳过 ——
>    一旦根解析失效，它们会**全部变绿而构建不报警**。本轮补了元守卫堵住这个洞。
> ② 判据本身的「能不能变红」只能靠**负控**逐个证明，本轮**没有**全部做完。

---

## 一、扫描结果（16 个守卫 / 54 个用例）

| 守卫 | @Test | assume | 风险 |
|---|---|---|---|
| `ArchitectureGuardTest` | 9 | 0 | OK |
| `ConfigStatusHonestyTest` | 1 | 1 | 静默跳过 |
| `ConfigUiHonestyTest` | 1 | 1 | 静默跳过 |
| `DeadCodeGuardTest` | 1 | 1 | 静默跳过 + 几乎全靠基线 |
| `DutyHeaderGuardTest` | 2 | 0 | OK |
| `GradleTestEntryTest` | 2 | 0 | OK |
| `LegacyLedgerTest` | 7 | 1 | 静默跳过 |
| `MaintainabilityReportTest` | 1 | 1 | 静默跳过 |
| `PostprocessLedgerTest` | 1 | 1 | 静默跳过 |
| `RegressionRegistrationTest` | 9 | 0 | OK |
| `S5MethodMapTest` | 2 | 2 | ★ **全部可跳过** |
| `SelfTestRunnerTest` | 4 | 0 | OK |
| `SingleSourceOfTruthTest` | 2 | 2 | ★ **全部可跳过** |
| `SourceFileSizeGuardTest` | 2 | 0 | OK |
| `SourceLineEndingGuardTest` | 4 | 0 | OK |
| `UiFeatureGuardTest` | 6 | 6 | ★ **全部可跳过** |

**10 个守卫共 15 处 `Assumptions.assumeTrue(...)`，跳过条件清一色是
「找不到源码根 / 仓库根就跳过」。**

### 为什么这是真风险
JUnit 里**被 assume 跳过的测试算「通过」**。而构建输出只有一行
`PASS tests=N` —— 没有任何提示说「守卫其实没跑」。

实测三个最危险的：
- `UiFeatureGuardTest`：**6/6 用例**可跳过
- `S5MethodMapTest`：**2/2**
- `SingleSourceOfTruthTest`：**2/2**

⇒ 如果哪天 Gradle 的 test working dir 变了，这 10 个守卫会**集体静默失明**，
而 CI 依然全绿。

---

## 二、本轮补的元守卫：`GuardPreconditionTest`

4 个用例：

| 用例 | 断言什么 |
|---|---|
| `仓库根必须能被解析到` | 用与各守卫**完全相同**的算法（`user.dir` 向上找 `settings.gradle`）解析，断言成功 |
| `每个守卫依赖的文件都必须存在` | 11 个具体路径逐条核（`settings.gradle` / `REFACTOR.md` / 4 个基线 / 2 个 docs / 2 个源码根），少一个就点名哪个守卫会跳过 |
| `基线目录里不许出现未被任何守卫使用的孤儿文件` | 反向：基线目录里每个文件都必须被某个守卫源码提到（防「文件名写错 ⇒ 守卫读到空基线」）|
| `守卫目录下的类都必须被回归套件登记` | 套件按 FQCN 登记、无包扫描 ⇒ 没登记的守卫永远不会跑 |

**负控**（已实测）：把 `duty-header-baseline.txt` 改名 ⇒
`DutyHeaderGuardTest` + 本守卫的 2 个用例**同时红** ✅

### 它守不了什么（已写进类注释）
它证明的是「**前置条件成立、所以那些守卫会跑**」，
**不是**「那些守卫的判据本身有效」。后者只能靠给每个守卫做负控。

---

## 三、★ 我在本会话已经三次撞见「守卫的判据本身抓不到」

这三个案例都记在项目里，作为「守卫也会假阴性」的实证：

| 案例 | 表面上在守什么 | 实际抓不到 |
|---|---|---|
| 通配 import | 跨包依赖方向 | 正则 `[\w.]+` 直接接 `;`，**匹配不到** `import X.*;` 与 `import static X.*;` ⇒ 全仓 420 处通配 import / 116 文件对守卫隐形（2026-11-13 已修）|
| 千行守卫 | 单文件 ≤1000 行 | 按 `\n` 切行，而 `RouteRefinementOps.java` 有 **323 个孤立 CR** ⇒ 编辑器 701 行、守卫只数到 379 行，**少算 46%**（2026-11-15 已修）|
| 「至少一处」的宽松断言 | `findRawPath` 在 HARD scope 内 | 写成 `insideScope >= 1` 时，**负控（删掉主搜索的 scope）仍然绿** —— 因为隧道重搜还在里面；改成 `>= 2` 才真正抓到 |

⇒ **规律：判据越宽松、断言越少，越容易造出「看起来在守」的假守卫。
每加一个守卫必须配一个负控 —— 证明「把被测行为改坏，它会红」。**

---

## 三之二、★ 补做：给 3 个「全部可跳过」的守卫各做负控（结论：**都是真守卫**）

上一版这份文档说「没核这 3 个守卫的判据是否有效」。本轮补做，**4 个负控全部成功变红**：

| # | 负控做法 | 结果 |
|---|---|---|
| 1 | `PathFeature.java` 里插入被禁串 `BridgeRangeCalculator.compute(` | ✅ `SingleSourceOfTruthTest.施工热路径不许直接重算全路` 红 |
| 2 | 从 `zh_cn.json` 删掉 `gui.roadweaver.map.dock.manual_connect` 键 | ✅ `UiFeatureGuardTest.所有UI文案在中英文里都要有` 红 |
| 3 | 往受管文件 `GradeOptimizer.java` 加一个未登记方法 `negctlUnregisteredMethod` | ✅ `S5MethodMapTest.台账必须覆盖受管文件的每个方法` 红 |
| 4 | 给 `PathFeature.java` 加一行含 `computeTargetY(` 的注释（基线上限 1，当前正好 1）| ✅ `SingleSourceOfTruthTest.重算热点只许减少不许新增` 红 |

⇒ **这 3 个「全部可跳过」的守卫，判据本身是有效的**；它们的问题**只是**
「前置条件失效时会静默跳过」，而这个洞已由 `GuardPreconditionTest` 堵上。

### ★ 负控本身踩的坑（重要）
第一次做负控 #1 时结果报 `BUILD SUCCESS`，我一度以为抓到了假守卫 ——
**真相是我的负控脚本自己失效**：锚点写成了 `public final class PathFeature`，
而真实声明是 `public class PathFeature extends Feature<PathFeatureConfig> {`，
Python 的 `str.replace` 在找不到锚点时**静默返回原串**，
于是「改坏了」这件事根本没发生，测试自然是绿的。

⇒ **铁律：负控脚本必须断言「目标确实被改坏了」**（`assert anchor in src` /
`assert 'NEGCTL' in src`），否则你验证的是「没改的代码仍然通过」，
那什么也证明不了。本轮 4 个负控全部加了这种断言后才可信。

---

## 四、这份审计**没有**做什么

- **没有**给 54 个用例逐个做负控（工作量太大，且很多需要改生产代码才能触发）。
- **没有**改任何生产代码，只新增了一个守卫类（`GuardPreconditionTest`）。
- **没有**动那 10 个用 `Assumptions` 的守卫本身 —— 它们的假设是合理的
  （在仓库外跑测试时跳过是对的），问题只是「跳过不可见」。
  元守卫解决了「不可见」，但**没有**解决「跳过时构建仍报全绿」这个 JUnit 语义问题。
- **没有**核对 `UiFeatureGuardTest` / `S5MethodMapTest` / `SingleSourceOfTruthTest`
  这三个「全部可跳过」的守卫，其判据本身是否有效。
- 行号/文件名会随改动漂移，引用前请重新 grep。

---

## 五、建议的后续（按性价比）

1. **给 3 个「全部可跳过」的守卫各做一次负控**（`UiFeatureGuardTest` /
   `S5MethodMapTest` / `SingleSourceOfTruthTest`）—— 这是当前最可能藏着假守卫的地方。
2. **把「守卫跑了几条、跳了几条」打进构建输出** —— 需要改 `RoadJUnitRegressionSuite`
   的 `PASS tests=` 行，让它同时报 skipped 数。这样静默跳过会在输出里显形。
3. **不做**：给 54 个用例全部补负控（成本过高，收益递减）。