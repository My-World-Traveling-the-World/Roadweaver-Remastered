"""核对冻结的旧版外部契约；只证明资源/接线/API保留，不替代游戏产物验收。"""
import argparse
import hashlib
import json
from pathlib import Path
import re

TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\r\n]*|/\*[\s\S]*?\*/|\s+|[^\s]', re.M)


def tokens_digest(path):
    # 保留字符串和字符常量，不能把字符串里的URL截成注释。
    values = [m[0] for m in TOKEN.finditer(path.read_text(encoding="utf-8-sig"))
              if not m[0].isspace() and not m[0].startswith(("//", "/*"))]
    return hashlib.sha256("\0".join(values).encode()).hexdigest()


def verify(root, baseline, api_root=None):
    errors = []
    for group in ("apiTokenSha256", "resourceSha256"):
        for relative, expected in baseline[group].items():
            if group == "resourceSha256":
                # 有意改变发包元数据时登记新摘要，保留原始旧版值，也不放开任意变更。
                upgrade = baseline.get("approvedResourceChanges", {}).get(relative)
                if upgrade is not None:
                    expected = upgrade["sha256"]
            # 路径键保持旧快照口径，仅在提供独立契约路径时分流。
            path = (api_root if api_root is not None and relative.startswith(("api/", "api-fabric/"))
                    else root) / relative
            if not path.is_file():
                errors.append("缺少旧版契约文件: " + relative)
                continue
            actual = tokens_digest(path) if group == "apiTokenSha256" else hashlib.sha256(
                path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
            if actual != expected:
                errors.append("契约内容变化，需明确审核: " + relative)
    for relative, spec in baseline["registrations"].items():
        path = root / relative
        if not path.is_file():
            errors.append("缺少注册入口: " + relative)
            continue
        actual = sorted([list(x) if isinstance(x, tuple) else x
                         for x in re.findall(spec["pattern"], path.read_text(encoding="utf-8"))])
        if actual != spec["values"]:
            errors.append("网络/键位注册变化: " + relative)
    for relative, spec in baseline["languages"].items():
        path = root / relative
        if not path.is_file():
            errors.append("缺少语言文件: " + relative)
            continue
        keys = set(json.loads(path.read_text(encoding="utf-8")))
        missing = set(spec["keys"]) - set(spec["retiredAlgorithmOptions"]) - keys
        if missing:
            errors.append("缺少仍受支持的语言键: " + relative + " " + str(sorted(missing)))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--api-root", type=Path, help="独立世界生成 API 仓库路径")
    args = parser.parse_args()
    baseline = json.loads(Path(__file__).with_name("legacy-contract.json").read_text(encoding="utf-8"))
    errors = verify(args.root, baseline, args.api_root or args.root.parent / "plaincraft-worldgen-api")
    print(json.dumps({"legacyCommit": baseline["legacyCommit"], "errors": errors,
                      "apiFiles": len(baseline["apiTokenSha256"]),
                      "resources": len(baseline["resourceSha256"]),
                      "semantics": "contract-preservation-only; gameplay-not-proven"}, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
