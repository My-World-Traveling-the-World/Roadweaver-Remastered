"""串行故障注入，每组以已提交代码为基线，checkout并校验SHA后才进入下一组。"""
from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
os.chdir(root)
# 回放工具自带定向JUnit入口；只写忽略的build目录，不改项目构建文件。
(root / "build/config-checks").mkdir(parents=True, exist_ok=True)
(root / 'build/config-checks/ConfigChecks.java').write_text('package local.configchecks;\n\nimport org.junit.platform.engine.discovery.DiscoverySelectors;\nimport org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;\nimport org.junit.platform.launcher.core.LauncherFactory;\nimport org.junit.platform.launcher.listeners.SummaryGeneratingListener;\n\n/** 变异只选择真实回归类，失败必须是断言，不能把编译错误当成守卫成功。 */\npublic final class ConfigChecks {\n    public static void main(String[] args) {\n        net.minecraft.SharedConstants.tryDetectVersion();\n        net.minecraft.server.Bootstrap.bootStrap();\n        var request = LauncherDiscoveryRequestBuilder.request();\n        for (String type : args) request.selectors(DiscoverySelectors.selectClass(type));\n        var listener = new SummaryGeneratingListener();\n        LauncherFactory.create().execute(request.build(), listener);\n        var summary = listener.getSummary();\n        summary.printFailuresTo(new java.io.PrintWriter(System.out, true));\n        for (var failure : summary.getFailures()) {\n            System.out.println("CONFIG-FAILURE type=" + failure.getException().getClass().getName());\n        }\n        System.out.printf("CONFIG-CHECK tests=%d skipped=%d aborted=%d failed=%d%n",\n                summary.getTestsFoundCount(), summary.getTestsSkippedCount(),\n                summary.getTestsAbortedCount(), summary.getTestsFailedCount());\n        if (summary.getTestsFoundCount() == 0 || summary.getTestsSkippedCount() != 0\n                || summary.getTestsAbortedCount() != 0 || summary.getTotalFailureCount() != 0) {\n            throw new AssertionError("配置变异回归失败");\n        }\n    }\n}\n', encoding="utf-8", newline="\n")
(root / 'build/config-checks/checks.gradle').write_text("gradle.projectsEvaluated {\n    def p = gradle.rootProject.project(':common')\n    def output = new File(gradle.rootProject.projectDir, 'build/config-checks/classes')\n    def compileChecks = p.tasks.register('compileConfigChecks', JavaCompile) {\n        dependsOn p.tasks.named('testClasses')\n        source new File(gradle.rootProject.projectDir, 'build/config-checks/ConfigChecks.java')\n        classpath = p.sourceSets.test.runtimeClasspath\n        destinationDirectory = output\n        options.encoding = 'UTF-8'\n    }\n    p.tasks.register('configChecks', JavaExec) {\n        dependsOn compileChecks\n        classpath = p.sourceSets.test.runtimeClasspath + p.files(output)\n        mainClass = 'local.configchecks.ConfigChecks'\n        args 'net.shiroha233.roadweaver.config.ConfigMenuBindingTest',\n             'net.shiroha233.roadweaver.bridge.ConfiguredRouteSolversTest',\n             'net.shiroha233.roadweaver.config.CurrentConfigContractTest',\n             'net.shiroha233.roadweaver.pathlogic.core.TargetProfileSolverTest',\n             'net.shiroha233.roadweaver.pathlogic.bridge.BridgeTemplatePlacementTest',\n             'net.shiroha233.roadweaver.planning.impl.PipelinePlannerSamplingTest',\n             'net.shiroha233.roadweaver.architecture.ConfigUiHonestyTest'\n    }\n}\n", encoding="utf-8", newline="\n")
base = "common/src/main/java/net/shiroha233/roadweaver/"
cases = [
    ("C1", "算法载荷遗漏地形配置", "config/ConfigService.java",
     '        root.add("terrainProfile", GSON.toJsonTree(INSTANCE.terrainProfile()));\n', "", 2, "last"),
    ("C2", "持久配置重新接受零坡度", "config/sub/RoadAppearanceConfig.java",
     "maxSlopeStepPerTwoSegments = Math.max(1,", "maxSlopeStepPerTwoSegments = Math.max(0,", 1, "first"),
    ("C3", "菜单setter保存原值而非请求值", "config/meta/ConfigOption.java",
     "setter.invoke(owner, value instanceof List<?> list ? new ArrayList<>(list) : value);",
     "setter.invoke(owner, field.get(owner));", 1, "first"),
    ("C4", "动态菜单错误放宽为开局上限", "config/meta/PlanningKeys.java",
     'ConfigKey.integer("dynamicPlanRadiusChunks", RoadConstants.DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS, 1, RoadConstants.COARSE_REGION_MAX_RADIUS_CHUNKS,',
     'ConfigKey.integer("dynamicPlanRadiusChunks", RoadConstants.DEFAULT_DYNAMIC_PLAN_RADIUS_CHUNKS, 1, RoadConstants.INITIAL_PLAN_MAX_RADIUS_CHUNKS,',
     1, "first"),
    ("C5", "坡度权重接回硬编码", "bridge/ConfiguredRouteSolvers.java",
     "config.finslerGradeWeight()", "FinslerMetric.DEFAULT_GRADE_WEIGHT", 1, "first"),
    ("C6", "保形权重接回硬编码", "bridge/ConfiguredRouteSolvers.java",
     "config.elasticBandStiffness()", "ElasticBandSolver.DEFAULT_STIFFNESS", 1, "first"),
    ("C7", "桥灯间隔接回固定16格", "pathlogic/bridge/BridgeSegmentPlannerNew.java",
     "ConfigService.get().bridge().lightIntervalBlocks()", "16", 1, "first"),
    ("C8", "拓扑入口旁路配置度量", "planning/impl/PipelinePlanner.java",
     "ConfiguredRouteSolvers.metric(\n                routeConfig,\n                terrain, ObstacleField.NONE, null, null)",
     "FinslerMetric.of(terrain, ObstacleField.NONE)", 1, "first"),
    ("C9", "JSON夹取不再拦截非有限数", "config/sub/PlanningConfig.java",
     "Double.isFinite(value) ? value : fallback", "value", 1, "first"),
    ("C10", "软避障权重接回硬编码", "bridge/ConfiguredRouteSolvers.java",
     "config.finslerObstacleWeight()", "FinslerMetric.DEFAULT_OBSTACLE_WEIGHT", 1, "first"),
]
gradle = r"C:\Users\18364\.gradle\wrapper\dists\gradle-8.8-bin\4u0rgm4geyrm56fyhoco0c9in\gradle-8.8\bin\gradle.bat"
environment = dict(os.environ, JAVA_HOME=r"C:\Program Files\Java\jdk-21",
                   GRADLE_OPTS="-Xmx2G -Dfile.encoding=UTF-8")
record_path = root / "build/config-checks/mutations.json"
records = json.loads(record_path.read_text(encoding="utf-8")) if record_path.exists() else []
selected = set(sys.argv[1:])
assert selected <= {case[0] for case in cases}, "负控编号拼错，不能以零组执行冒充成功"
assert not subprocess.check_output(["git", "status", "--porcelain"]), "变异开始前必须干净"
for identifier, label, relative, before, after, count, position in cases:
    if selected and identifier not in selected:
        continue
    path = root / (base + relative)
    original = path.read_bytes()
    digest = hashlib.sha256(original).hexdigest()
    source = original.decode("utf-8")
    assert source.count(before) == count, (identifier, "锚点数量不符")
    if position == "last":
        left, right = source.rsplit(before, 1)
        mutated = left + after + right
    else:
        mutated = source.replace(before, after, 1)
    assert source != mutated
    log = root / f"build/config-checks/{identifier}.log"
    try:
        path.write_text(mutated, encoding="utf-8", newline="\n")
        with log.open("wb") as out:
            completed = subprocess.run(
                ["cmd.exe", "/d", "/c", gradle, "-I", "build/config-checks/checks.gradle",
                 ":common:configChecks", "--offline", "--daemon", "--console=plain",
                 "--dependency-verification=off"],
                env=environment, stdout=out, stderr=subprocess.STDOUT)
        text = log.read_text(encoding="utf-8", errors="replace")
        match = re.search(r"CONFIG-CHECK tests=(\d+) skipped=(\d+) aborted=(\d+) failed=(\d+)", text)
        types = re.findall(r"CONFIG-FAILURE type=([^\s]+)", text)
        red = bool(match and completed.returncode != 0
                   and int(match[1]) == 35 and int(match[2]) == int(match[3]) == 0
                   and int(match[4]) > 0 and len(types) == int(match[4])
                   and all(item == "org.opentest4j.AssertionFailedError" for item in types))
    finally:
        subprocess.run(["git", "checkout", "--", base + relative], check=True)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, "checkout没有逐字恢复"
    records = [item for item in records if item["id"] != identifier]
    records.append(dict(id=identifier, change=label, file=base + relative,
                        time=datetime.datetime.now().astimezone().isoformat(),
                        baseCommit=subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
                        tests=int(match[1]) if match else None,
                        skipped=int(match[2]) if match else None,
                        aborted=int(match[3]) if match else None,
                        failed=int(match[4]) if match else None,
                        exceptionTypes=types, assertionRed=red, restored=True,
                        sourceSha256=digest, log=str(log.relative_to(root)),
                        logSha256=hashlib.sha256(log.read_bytes()).hexdigest()))
    record_path.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8", newline="\n")
    print(f"{identifier} red={red} failed={int(match[4]) if match else 'NO_TEST_RESULT'} restored=True", flush=True)
    assert red, f"{identifier}未得到纯断言失败，不能算作守卫交付"
assert not subprocess.check_output(["git", "status", "--porcelain"]), "变异结束必须干净"
