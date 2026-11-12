# RoadWeaver 最终仓库 —— 协作约定

## ⚠ 时效性更正（2026-11-13 补，**读下文前先读这一节**）

**这份文档描述的是旧仓 `roadweaver` 的约定**，它被一起搬进了 `roadweaver-next`，
但里面多数具体条目在新仓已经不成立。逐条核对结果：

### 已经不成立（照做会错）

| 下文写的 | 新仓实际 |
|---|---|
| §1 仓库路径 `D:\云游四海-Mod源码\roadweaver`，分支 `0.8.374-route-editor` | 实际是 `roadweaver-next`，分支 **`main`**，**没有 remote**（纯本地仓）|
| §2 版本号 `0.8.XXX` | 实际 **`2.3.1-1.21.1-plaincraft-direct-core.0.9.098`**（`build.gradle:26`）|
| §2 commit message 用 `RoadWeaver <version>: ...` | 实际用 `type(scope): 中文说明`（如 `feat(profile): ...`、`docs(s7): ...`）|
| §4.2「测试**不是** JUnit 注解，23 个 regression 用 `main` + 自定义 `require(...)` 断言」 | **两半都变了**：① `net.shiroha233.roadweaver.RoadJUnitRegressionSuite` 是**真正的 JUnit 5**（用 JUnit Platform Launcher 的 `DiscoverySelectors` 发现用例）；② 注册的 regression 任务是 **36 个**，不是 23 个 |
| §4.2「已聚合全部 23 个」 | 实际 `tasks.named('test')` 聚合 **32 / 36**；未聚合的 4 个是重装备：`routeQualityHarness`、`routeQualityCorpus`、`topologyHarness`、`roadCenterlineAudit`（本就不该进日常回归）|
| §5 版本基线与文档路径（`D:\后续任务清单\roadweaver\...`） | 已换成新仓内的 `REFACTOR.md` / `docs/STATUS-20261113.md` / `docs/S7-SWITCH-CHECKLIST.md` |

### 仍然成立（很重要，别丢）

| 下文写的 | 说明 |
|---|---|
| §4.1-1 `PATH` 上的 `java` 是 JDK 26，必须先设 `JAVA_HOME=jdk-21` | **成立**，否则 `Unsupported class file major version 70` |
| §4.1-2 不要用 Gradle 9.x，用 wrapper 锁定的 **8.8** | **成立**（`architectury-loom 1.7.435` 与 9.x 不兼容）|
| §4.1-3 离线构建 `--offline` | 成立（依赖已缓存）|
| §4.2-1 `common/build.gradle` 里 `tasks.withType(Test) { exclude '**/*' }` 清空 JUnit 发现 ⇒ `:common:test` 显示 `NO-SOURCE` | **成立**（`common/build.gradle:33-35` 仍是这样，所以别被 `NO-SOURCE` 骗）|
| §4.3 部署：先关游戏、只给客户端 mods 做 `.bak`、记 SHA-256、三处一致 | **成立** |
| §3 回退：`git checkout` / `reset --hard`，不要丢弃改动 | 成立（但没有 remote，所以不存在 push 语义）|

### ★ 新仓正确的验证方式

```powershell
.\verify.ps1
```

它跑两件事：`:fabric:compileJava`（**common 的回归任务不编译 fabric**，这个洞真出过事）
+ `:common:roadJUnitRegressionSuite`（684 个用例）。全绿才算过。

只想跑测试时：`:common:roadJUnitRegressionSuite`（比 `:common:test` 快，
且它是 JUnit 用例的唯一权威入口）。

> **另有一条新仓踩过的坑**：Gradle daemon 会间歇性「起来了但客户端连不上」。
> `verify.ps1` 已内建重试，并且**不会**把它报成"编译失败"——
> 看到 `daemon unreachable (NOT a compile error)` 就只是重跑，别去翻源码。

---


## 1. 本仓库是唯一权威源码

- 仓库路径：`D:\云游四海-Mod源码\roadweaver`（分支随版本而定，当前 `0.8.374-route-editor`）
- 这是 RoadWeaver 的最终/当前源码。需要历史版本回退一律走 git commit。

## 2. 每次改动都必须提交版本（硬性约定）

- **任何源码/测试/资源改动，完成并确认可编译、相关回归通过后，必须 git commit。**
- commit message 必须**包含版本号**，格式建议：
  `RoadWeaver <version>: <简短改动说明>`
  例：`RoadWeaver 0.8.241: merge dual repair chain + grade pre-plan`
- `build.gradle` 的 `version = '...0.8.XXX-...'` 每次改动先 +1，再构建/部署。
- 每个 commit 就是一个可回退点。部署/打包前先确认该版本已 commit。
- ⚠️ **commit message 含中文引号/特殊字符时必须用 `git commit -F <文件>`** ——
  直接 `-m` 会被 shell 拆成多个参数而报 pathspec 错误。

## 3. 回退方式

- 回退到某个历史版本：
  `git checkout <commit>`（只读查看）或 `git reset --hard <commit>`（真回退）。
- 保留回退能力：**不要**在提交前丢弃改动，也不要 `git reset --hard` 到未提交的中间态之外的值。
- 每完成一批，可用 `git log --oneline -5` 确认版本记录连续。

## 4. 验证命令

### 4.1 环境（★ 三个坑，必读）

1. **`PATH` 上的 `java` 是 JDK 26**，直接跑 Gradle 会报
   `BUG! exception in phase 'semantic analysis' ... Unsupported class file major version 70`。
   **必须先设 `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`。**
2. **不要用 Gradle 9.x**：`architectury-loom 1.7.435` 与它不兼容，会报
   `Could not create an instance of type LoomProblemReporter`。
   请用 wrapper 锁定的 **Gradle 8.8**（`gradle/wrapper/gradle-wrapper.properties` 已指定）。
3. **离线构建**：加 `--offline`。依赖已缓存在本机，不要联网下载。

### 4.2 编译、回归与构建

```powershell
cd D:\云游四海-Mod源码\roadweaver
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# 编译
.\gradlew.bat :common:compileJava :fabric:compileJava --offline --console=plain

# 全量回归（首选：已聚合全部 23 个 regression 任务）
.\gradlew.bat :common:test --offline --console=plain

# 构建产物 → fabric\build\libs\
.\gradlew.bat :fabric:build -x test --offline --console=plain
```

> ### ★ 关于 `:common:test`（2026-09-25 更正，旧说法已作废）
>
> 本仓库的测试**不是 JUnit 注解**：`common/build.gradle` 里
> `tasks.withType(Test) { exclude '**/*' }` 清空了 JUnit 的测试发现，
> 所以 `:common:test` 自己显示 `NO-SOURCE` —— **这是正常现象，不代表没有测试**。
>
> 真正的测试是通过 `JavaExec` 跑 `main()` 的 **23 个 regression 任务**
> （用 `main` + 自定义 `require(...)` 断言，失败抛 `AssertionError`），
> 由 `tasks.named('test')` 用 `dependsOn` 聚合。
> 因此 **`:common:test` 就是全量回归的总入口，一次跑完即可。**
>
> 本文档早前写的「回归（逐个，不要跑 `:common:test`）」是 **0.8.240 时代**的状态，
> **已作废** —— 当时聚合不全（23 个里只挂了 20 个），现在已全部接线。
>
> ⚠️ `savedEndpoint528Simulation` 依赖一份**特定真机存档**（`1_6_2测试08241035`，已被删除）。
> 缺数据时它会打印 `SKIP` 并以退出码 0 正常结束，**不会**把回归弄红。
> 在有该存档的机器上可用 `-Droad.audit.root=<roads 目录>` 指定路径启用它。

### 4.3 部署

- 部署前确认游戏进程已关（jar 被占用会导致复制失败）。
- 旧活动 jar 加 `.bak`（**只给客户端 mods 目录做**），记录新 jar 的 SHA-256。
- 三处 SHA-256 必须一致。详见 `D:\云游四海-Mod项目维护指南.md`。

## 5. 当前版本

- 当前基线：**`0.8.384-route-audit3`**（HEAD `7b7db55`，分支 `0.8.374-route-editor`）。
- 之后每次改动从 **`0.8.385`** 起递增。
- 逐版本完整记录：`D:\后续任务清单\roadweaver\roadweaver-变更历史.md`
- 当前状态与待办：`D:\后续任务清单\roadweaver\roadweaver.md`
- 构建/部署/打包流程：`D:\云游四海-Mod项目维护指南.md`
