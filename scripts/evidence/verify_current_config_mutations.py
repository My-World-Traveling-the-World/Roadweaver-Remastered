"""逐组破坏新版配置契约，必须由指定守卫报错，并逐字恢复生产文件。"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
JAVA = "common/src/main/java/net/shiroha233/roadweaver/"
GRADLE = Path(os.environ.get(
    "ROADWEAVER_GRADLE",
    "C:/Users/18364/.gradle/wrapper/dists/gradle-8.8-bin/"
    "4u0rgm4geyrm56fyhoco0c9in/gradle-8.8/bin/gradle.bat"))
CASES = [
    ("retired_cost", JAVA + "config/sub/PathfindingCostConfig.java",
     "public final class PathfindingCostConfig implements SubConfig {",
     "public final class PathfindingCostConfig implements SubConfig {\n"
     "    // 负控：重新塞回退役字段，必须被序列化与元数据守卫拒绝。\n"
     "    private double contourDiscount = 0.55;",
     "retiredKeysAreAbsentFromSerializationAndMetadata"),
    ("retired_knn", JAVA + "config/sub/PlanningConfig.java",
     "public final class PlanningConfig implements SubConfig {",
     "public final class PlanningConfig implements SubConfig {\n"
     "    // 负控：只进配置却没有生产读点的KNN字段不能复活。\n"
     "    private int knnK = 2;",
     "retiredKeysAreAbsentFromSerializationAndMetadata"),
    ("lost_grid_snapshot", JAVA + "config/sub/PathfindingCostConfig.java",
     "        copy.pathGridStep = this.pathGridStep;",
     "        // 负控：丢弃玩家的非默认网格间距。",
     "currentGridStepSurvivesJsonSnapshotAndClamp"),
    ("lost_active_fingerprint", JAVA + "config/ConfigService.java",
     '        root.addProperty("softGradePenalty", INSTANCE.pathfindingCost().softGradePenalty());',
     "        // 负控：漏掉仍决定桥隧经济比较的软坡单价。",
     "activeControlsStillChangeActualFingerprints"),
    ("lost_ui_binding",
     "fabric/src/main/java/net/shiroha233/roadweaver/client/fabric/PathfindingCostsSection.java",
     "                .setSaveConsumer(cfg::setHardGradePenalty)",
     "                .setSaveConsumer(value -> { /* 负控：修改界面但不保存。 */ })",
     "currentUiKeepsRealControlsAndDropsRetiredPage"),
]


def run():
    # 只在干净基线上跑，checkout不能吞掉开发者未提交的工作。
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)
    if status.strip():
        raise RuntimeError("工作区必须干净；先提交生产实现及守卫再执行负控")
    if not GRADLE.is_file():
        raise RuntimeError(f"Gradle不存在：{GRADLE}")
    env = dict(os.environ, JAVA_HOME="C:/Program Files/Java/jdk-21",
               GRADLE_OPTS="-Xmx2G -Dfile.encoding=UTF-8")
    output = ROOT / "build/config-cleanup-mutations"
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for name, relative, old, new, expected in CASES:
        path = ROOT / relative
        # 恢复目标必须是显式清单中的仓库内文件，不能操作调用方传入的外部路径。
        assert path.resolve().is_relative_to(ROOT)
        before = path.read_bytes()
        source = before.decode("utf8")
        assert source.count(old) == 1, (name, "锚点必须唯一")
        try:
            path.write_bytes(source.replace(old, new).encode("utf8"))
            log_path = output / (name + ".log")
            with log_path.open("wb") as stream:
                result = subprocess.run(
                    [str(GRADLE), ":common:roadJUnitRegressionSuite",
                     "--daemon", "--console=plain", "--dependency-verification=off"],
                    cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                    timeout=300)
            text = log_path.read_bytes().decode("utf8", errors="replace")
            marker = "JUnit Jupiter:CurrentConfigContractTest:" + expected + "()"
            caught = result.returncode != 0 and marker in text and "PASS tests=" not in text
            records.append(dict(case=name, file=relative, expected=expected,
                                failed_as_expected=caught, exit_code=result.returncode,
                                log=log_path.relative_to(ROOT).as_posix()))
            assert caught, (name, "未由指定守卫检出", result.returncode)
        finally:
            subprocess.run(["git", "checkout", "--", relative], cwd=ROOT, check=True)
            assert path.read_bytes() == before, (name, "未逐字恢复")
        records[-1]["restored_sha256"] = hashlib.sha256(before).hexdigest()
        print(f"MUTATION {name}: RED; restored", flush=True)
        (output / "results.json").write_text(
            json.dumps(records, ensure_ascii=False, indent=2) + "\n",
            encoding="utf8", newline="\n")
    assert not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip()
    print(f"PASS mutations={len(records)} restored={len(records)}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf8")
    run()
