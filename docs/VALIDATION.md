# 构建和回归

Java 21、Fabric、Minecraft 1.21.1。本机用外置 Gradle 8.8 和 `--daemon`，避免包装器握手问题；CI 可使用包装器。

## 独立前置

在同级 `plaincraft-worldgen-api` 仓库执行（自定义路径使用 `-PworldgenApiProjectDir=<路径>`）：

```powershell
& $gradle :api:contractJar :api-fabric:devModJar :api-fabric:contractIntermediaryJar :api-fabric:build --daemon --console=plain
```

这个入口不配置道路实现。玩家用 `api-fabric/build/libs/plaincraft-worldgen-api-<版本>.jar`；`contract/` 下的无版本开发件用于下游编译、dev 运行和正式烟测。GitHub 工作流在 `v<版本>` 标签下只发布正式前置 JAR。

## 道路成品

先在相邻河流工程生成 `planContractJar`，在长城工程生成 `interopJar`。它们是可选联动的开发输入，不进道路 JAR，也不要求玩家另外安装两个玩法模组。

```powershell
& $gradle :common:roadJUnitRegressionSuite :fabric:build --daemon --console=plain --dependency-verification=off
```

看 `PASS tests=`、零失败和跳过、`[API-SEPARATE]` 和 `BUILD SUCCESSFUL`。RoadJUnitRegressionSuite 按类登记；新守卫登记后用坏产物验证。

新版从 `fabric/build/libs/` 取正式道路 JAR，并安装一份独立 API；旧 `build/final/` 是先前内嵌 API 的历史成品。更新整合包时先移出旧道路 JAR。真机用同种子新档和旧档副本分开验证。
