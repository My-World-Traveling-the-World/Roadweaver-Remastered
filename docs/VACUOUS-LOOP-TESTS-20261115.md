# 第五类假测试：空集合让循环空转（2026-11-15）

> 承接 [`MY-OWN-FAKE-TESTS-20261115.md`](MY-OWN-FAKE-TESTS-20261115.md)。
> 上一份记录的是「**恒真断言**」（`x == 6 || x > 0`）。
> 这一份是**另一类**：断言本身没问题，但**它被包在一个可能零次执行的循环里**，
> 集合为空时测试什么也没验证，而 JUnit 记「通过」。

---

## 一、这类假测试的形状

```java
List<X> items = computeSomething();      // ← 可能返回空
for (X x : items) {
    assertTrue(someCondition(x), "...");  // ← items 为空时一次都不执行
}
// 测试绿了，但它没有证明「computeSomething 产出了东西」，
// 也没有证明「someCondition 对有内容的集合成立」
```

它与「恒真断言」的区别：
- 恒真断言：**每次执行都不成立**（断言本身没判别力）
- 循环空转：**根本不执行**（断言有判别力，但没机会用）

两者在构建输出上都表现为「PASS tests=N」。

---

## 二、本轮实证并修掉的 2 处

### ① `TunnelRunLengthCapTest.lowProminenceStaysOutsideAnyRun`
```java
int[] runs = TunnelEnvelopeCriterion.runs(values, 2);
for (int i = 0; i < runs.length; i += 2) {
    assertTrue(runs[i + 1] < 300 || runs[i] > 319, "任何 run 都不得跨过 300..319 ...");
}
```
**负控实证**：把输入 `plateau(600, 20)` 的突起度改成 `0`（⇒ `runs` 为空）——
`PASS tests=923 / BUILD SUCCESS`，**测试照样绿**。

修法：加 `assertTrue(runs.length > 0, "构造前提：...")`。
同文件第 56/70 行本来都有 `runs.length` 断言，唯独这条漏了。

### ② `VolumePlannerVisitTest.不再产出路肩体积`
```java
List<ColumnVolume> volumes = collect(straightRoad(3, groundY), flatContext(groundY));
for (ColumnVolume v : volumes) {
    assertFalse("SHOULDER".equals(v.kind().name()), "出现了路肩体积 ...");
}
```
**负控实证**：让 `collect(...)` 恒返回 `List.of()` ⇒ 加断言前绿、加断言后**红**
（`VolumePlannerVisitTest.java:138`）。

修法：加 `assertFalse(volumes.isEmpty(), "构造前提：...")`。
同文件的 `平坦地形上路面体积的高度范围包住目标高度` **本来就有**
`assertTrue(sawRoad, ...)` 兜底 —— 说明作者知道这个手法，只是漏了一处。

---

## 三、系统扫描结果（三类口径，逐步收窄）

| 口径 | 命中 | 说明 |
|---|---|---|
| ①「方法内有 for 循环包断言、且无任何非空断言」 | **73** | 噪音太大 —— 大量是遍历**本地字面量数组**，不可能空 |
| ②「增强 for 遍历表达式 + 循环体有断言 + 无非空断言」 | **19** | 仍含 11 个遍历 `new String[]{...}` / `SELF_TESTS` / `DEAD_KNOBS` 这类构造上非空的 |
| ③ 人工核对 19 个里的**方法返回值**那批 | **2 个真问题** | 见上面两节；其中 1 个（`SimplifyDensityTest`）被后续 `simplified.get(0)` 顺带保护 |

**⇒ 静态扫描只能把候选压到 19 个，最终判定必须逐个看「遍历的东西是否可能为空」。**

---

## 四、为什么这类假测试比恒真断言更隐蔽

1. **断言本身看起来完全正确** —— `runs[i+1] < 300 || runs[i] > 319` 是有判别力的，
   审查者读一眼不会觉得有问题；问题在**它外面那层控制流**。
2. **代码评审时容易只看断言、不看容器从哪来**。
3. **它不会因为「改坏了被测代码」而红** —— 因为改坏之后集合往往变空，反而更容易绿。
   **这是最危险的性质：破坏越严重，测试越绿。**
4. 负控也不容易做对 —— 你必须**先想到让集合变空**，而不是只想「怎么让条件不成立」。

---

## 五、已加入的执行规则

**任何「遍历容器的断言」都必须先钉住容器非空。**

具体形态（三选一）：
- `assertFalse(list.isEmpty(), "构造前提：...")`
- `assertTrue(array.length > 0, "构造前提：...")`
- 用 `assertTrue(sawXxx, ...)` 在循环里记一个「见过」标志，循环后断言

**并且负控要做两次**：
1. 让**条件不成立**（验证断言有效）
2. 让**容器变空**（验证没有空转）

第 2 项是这类假测试唯一的检出方式。

---

## 六、这份文档**没有**做什么

- **没有**把 19 个候选全部逐个看完（只核了方法返回值那批里最可疑的两个）
- **没有**审计 `assertEquals` 形式的空转（例如 `assertEquals(0, list.size())` 在「本来就没东西」时是真断言、在「应该有东西」时是假断言 —— 需要读注释才知道意图）
- **没有**审计「循环体里只有 `if` 没有断言」的情形
- **没有**改任何生产代码
- 行号会随改动漂移，引用前请重新 grep。