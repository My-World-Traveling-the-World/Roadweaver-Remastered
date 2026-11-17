"""按显式日志会话关联道路证据；观测通过与施工完成必须分别统计。"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re

MARKER = "[ROUTE-EVIDENCE]"
KEY = re.compile(r"(?:^| )(?P<key>id|stage|selected|geodesic|protection|detail|safe|reason|product|construction|fingerprint|chunk|localStations|outcome|gapRepair)=")
VALIDATIONS = ("final-water", "final-structure", "validated")
OUTCOMES = {"COMPLETED", "ABANDONED", "INCOMPLETE", "NOT_ATTEMPTED", "CONFLICT"}


def boolean(value):
    if value not in ("true", "false"):
        raise ValueError("expected true/false")
    return value == "true"


def parse(line):
    """只消费明确标记；残缺或未知格式留下错误，不能静默当成无事发生。"""
    if MARKER not in line:
        return None
    body = line.split(MARKER, 1)[1].strip()
    matches = list(KEY.finditer(body))
    fields = {}
    for index, match in enumerate(matches):
        key = match["key"]
        if key in fields:
            raise ValueError("duplicate field: " + key)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        fields[key] = body[match.end():end].strip()
    if not fields.get("id"):
        raise ValueError("missing route id")
    stage = fields["stage"]
    if stage == "coarse":
        fields["selected"] = boolean(fields["selected"])
        fields["geodesic"] = float(fields["geodesic"])
        if not math.isfinite(fields["geodesic"]) or fields["geodesic"] < 0:
            raise ValueError("invalid geodesic")
        # 当前粗候选没有保护快照，不能被汇总成最终可建性通过。
        if fields["protection"] != "unprepared":
            raise ValueError("unknown protection semantics")
    elif stage in VALIDATIONS:
        fields["safe"] = boolean(fields["safe"])
        if fields["construction"] != "not-confirmed":
            raise ValueError("unknown construction semantics")
        fields["reason"]
        fields["product"]
    elif stage == "chunk":
        fields["fingerprint"] = str(int(fields["fingerprint"]))
        parts = fields["chunk"].split(",")
        if len(parts) != 2:
            raise ValueError("invalid chunk coordinate")
        fields["chunk"] = ",".join(str(int(p)) for p in parts)
        fields["localStations"] = int(fields["localStations"])
        if fields["localStations"] < 0 or fields["outcome"] not in OUTCOMES:
            raise ValueError("invalid chunk result")
        fields["gapRepair"] = boolean(fields["gapRepair"])
    else:
        raise ValueError("unknown stage: " + stage)
    return fields


def summarize(sources):
    """sources 为(session, 文件名, 行迭代器)，调用顺序就是同会话的观测顺序。"""
    routes, errors, unlinked = {}, [], []
    for session, name, lines in sources:
        for number, line in enumerate(lines, 1):
            location = {"session": session, "file": str(name), "line": number}
            try:
                event = parse(line)
            except (ValueError, KeyError) as failure:
                errors.append({**location, "error": str(failure)})
                continue
            if event is None:
                continue
            if event["id"] == "missing-owner-pair":
                # 多条旧路共享这个占位文本，绝不能把它们合并成一条完整道路。
                unlinked.append({**location, "event": event})
                continue
            key = (session, event["id"])
            route = routes.setdefault(key, {"session": session, "id": event["id"],
                "coarse": [], "validation": {}, "chunksByFingerprint": {},
                "observations": 0, "roadCompletion": "not-proven"})
            route["observations"] += 1
            stage = event["stage"]
            if stage == "coarse":
                route["coarse"].append(event)
            elif stage in VALIDATIONS:
                route["validation"].setdefault(stage, []).append(event)
            else:
                version = route["chunksByFingerprint"].setdefault(event["fingerprint"], {})
                chunk = version.setdefault(event["chunk"], {"latestOutcome": None,
                    "observedOutcomes": [], "observations": 0, "gapRepairSeen": False})
                chunk["observations"] += 1
                chunk["latestOutcome"] = event["outcome"]
                if event["outcome"] not in chunk["observedOutcomes"]:
                    chunk["observedOutcomes"].append(event["outcome"])
                chunk["gapRepairSeen"] |= event["gapRepair"]
    for route in routes.values():
        route["missingStages"] = [stage for stage in ("coarse", *VALIDATIONS, "chunk")
            if not (route["chunksByFingerprint"] if stage == "chunk" else
                    route["coarse"] if stage == "coarse" else route["validation"].get(stage))]
        route["latestValidationPassed"] = {stage: history[-1]["safe"]
            for stage, history in route["validation"].items()}
        route["chunkOutcomeCountsByFingerprint"] = {fingerprint: dict(Counter(
            chunk["latestOutcome"] for chunk in chunks.values()))
            for fingerprint, chunks in route["chunksByFingerprint"].items()}
    # 同一坐标的重复日志只增加观测数，不增加完工区块数；无尝试ID时不虚构尝试次数。
    return {"routes": list(routes.values()), "unlinked": unlinked, "errors": errors,
            "semantics": "input-order observations; fingerprints isolated; road completion not proven"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", action="append", nargs=2, required=True,
                        metavar=("NAME", "LOG"), help="重复指定；同存档副本/重开日志须显式区分会话")
    parser.add_argument("--encoding", default="utf-8-sig", help="GBK控制台日志可指定gb18030")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    # 用生成器逐行消费大日志；明确解码错误，避免替换字符让路由身份悄悄改变。
    def sources():
        for session, name in args.session:
            with Path(name).open(encoding=args.encoding) as stream:
                yield session, name, stream
    result = summarize(sources())
    encoded = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        args.output.write_text(encoded + "\n", encoding="utf-8", newline="\n")
    else:
        print(encoded)
    # 保留部分有效报告，同时让自动化明确看到有格式不兼容，不能仅凭JSON存在判成功。
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
