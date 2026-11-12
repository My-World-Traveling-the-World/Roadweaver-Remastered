# RoadWeaver 最终仓库 —— 协作约定

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
