"""重放客户端录到的地形负证据；不把缺少世界约束的探针冒充生产整链验收。"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / "common/src/main/java/net/shiroha233/roadweaver/pathlogic/profile"
SOURCES = ("ProfileDpSolver", "ProfilePairTransitions", "CostModel", "ProfileDpSource", "ProfileFallback", "ProfileFeasibility")
PROBE = r"""
import java.nio.file.*;
import java.util.*;
import net.shiroha233.roadweaver.pathlogic.profile.*;
/** 只重放地形；去掉未录制的硬约束后仍无解，才能证明地表窗口本身已冲突。 */
public class ClientProfileFailureReplay {
    public static void main(String[] args) throws Exception {
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] columns = line.split("\t");
            int[] terrain = Arrays.stream(columns[1].split(",")).mapToInt(Integer::parseInt).toArray();
            var solved = ProfileDpSource.solve(terrain, null, 5, 4, 1, 1, ProfileDpSource.Constraints.none());
            String result;
            try {
                int[] fallback = ProfileFallback.terrainFollowing(terrain, 5, 4, 1, 1, null, null, 8, null);
                result = "violations=" + ProfileFallback.violations(fallback, 1, 1);
            } catch (IllegalArgumentException infeasible) {
                result = "rejected-infeasible";
            }
            System.out.printf("REPLAY stations=%d dpFeasible=%s fallback=%s tag=%s%n",
                    terrain.length, solved.isPresent(), result, columns[0]);
        }
    }
}
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"C:\Program Files\Java\jdk-21"))
    parser.add_argument("--evidence", type=Path,
                        default=ROOT / "docs/evidence/client-road-profile-failures-20261121.json")
    options = parser.parse_args()
    evidence = json.loads(options.evidence.read_text(encoding="utf-8"))
    if evidence["parameters"] != {"cut": 5, "fill": 4, "maxStep": 1, "perTwo": 1}:
        raise ValueError("现场参数已变化，不能把固定参数探针冒充同条件重放")
    samples = evidence["samples"]
    if len(samples) != 6:
        raise ValueError("取证文件必须包含实际核对过的六条路线，不能空集合自动通过")
    output = ROOT / "build/client-profile-replay"
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    rows: list[str] = []
    for sample in samples:
        terrain = sample["terrain"]
        if len(terrain) != sample["stations"] or sample["crossingCount"] != 0:
            raise ValueError("初轮录制站数或跨越口径不符，不能继续重放")
        rows.append(sample["tag"] + "\t" + ",".join(map(str, terrain)))
    data = output / "terrain.tsv"
    data.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    source = output / "ClientProfileFailureReplay.java"
    source.write_text(PROBE, encoding="utf-8", newline="\n")
    subprocess.run([str(options.jdk / "bin/javac.exe"), "-encoding", "UTF-8", "-d", str(classes),
                    *[str(PROFILE / (name + ".java")) for name in SOURCES], str(source)], check=True)
    # 不断言坏修复必须永远存在；后续修好后此工具仍负责显示实际结果，不冻结缺陷。
    subprocess.run([str(options.jdk / "bin/java.exe"), "-cp", str(classes),
                    "ClientProfileFailureReplay", str(data)], check=True)


if __name__ == "__main__":
    main()
