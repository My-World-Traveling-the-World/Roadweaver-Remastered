import json
import os
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

# 路径由脚本自身确定，防止从别处启动时改错同名工程；仅用于回归盲区审计。
road_root = pathlib.Path(__file__).resolve().parents[3]
workspace = road_root.parent
(road_root / ".analysis_tmp").mkdir(exist_ok=True)
os.environ["JAVA_HOME"] = r"C:\Program Files\Java\jdk-21"
os.environ["GRADLE_OPTS"] = "-Xmx2G -Dfile.encoding=UTF-8"
cases = [
    (
        "road-compile-always-fails",
        workspace / "roadweaver-next",
        "common/src/main/java/net/shiroha233/roadweaver/worldgen/road/RoadWorldgenPlanCache.java",
        "var roads = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);",
        '// 审计负控：编译全部失败也不应被当作正常无路；执行后立即恢复。\n'
        '            if (System.nanoTime() != Long.MIN_VALUE) throw new IllegalStateException("审计注入：区块编译失败");\n'
        '            var roads = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);',
        r"C:\Users\18364\.gradle\wrapper\dists\gradle-8.8-bin\4u0rgm4geyrm56fyhoco0c9in\gradle-8.8\bin\gradle.bat",
        [":common:roadJUnitRegressionSuite", ":fabric:compileJava", "--daemon", "--console=plain",
         "--dependency-verification=off"],
        ".analysis_tmp/deep-audit-mutant-compile.log",
    ),
    (
        "wall-away-from-ports-all-gaps",
        workspace / "plaincraft-great-wall",
        "src/main/java/local/plaincraft/greatwall/component/ImportedRibbonSection.java",
        "int phase = Math.floorMod(Math.floorDiv(s, 256), 4);",
        "int phase = 3; // 审计负控：接口外的普通墙丢失全部垛口，随后恢复。",
        r"C:\Users\18364\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat",
        ["test", "--offline", "--daemon", "--console=plain", "--dependency-verification=off",
         "-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=GBK"],
        "build-deep-audit-mutant-phase.log",
    ),
]
report = []
for name, root, relative, anchor, replacement, gradle, arguments, log_relative in cases:
    path = root / relative
    assert subprocess.run(["git", "diff", "--quiet", "HEAD", "--", relative], cwd=root).returncode == 0, path
    original = path.read_bytes()
    text = original.decode("utf-8")
    assert text.count(anchor) == 1, (name, text.count(anchor))
    print("MUTATION_BEGIN", name, flush=True)
    try:
        path.write_bytes(text.replace(anchor, replacement).encode("utf-8"))
        log = root / log_relative
        with log.open("wb") as output:
            result = subprocess.run([gradle, *arguments], cwd=root, stdout=output, stderr=subprocess.STDOUT)
        value = dict(name=name, exit=result.returncode, log=log_relative)
        contents = log.read_text(encoding="utf-8", errors="replace")
        if name.startswith("road"):
            value["summary"] = re.findall(r"PASS tests=\d+ skipped=\d+ aborted=\d+ failed=\d+", contents)
        else:
            roots = [ET.parse(p).getroot() for p in (root / "build/test-results/test").glob("TEST-*.xml")]
            value["tests"] = {key: sum(int(r.get(key, 0)) for r in roots)
                              for key in ["tests", "failures", "errors", "skipped"]}
        value["buildSuccessful"] = "BUILD SUCCESSFUL" in contents
        report.append(value)
        print("MUTATION_RESULT", json.dumps(value), flush=True)
    finally:
        subprocess.run(["git", "checkout", "--", relative], cwd=root, check=True)
        assert path.read_bytes() == original, (name, "恢复字节不同")
        print("MUTATION_RESTORED", name, flush=True)
    (workspace / "roadweaver-next/.analysis_tmp/deep-audit-mutations.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8")
