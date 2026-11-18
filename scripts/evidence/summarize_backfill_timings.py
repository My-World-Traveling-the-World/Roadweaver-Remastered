"""按文件统计道路回填尾延迟。每个文件应只包含一次场景运行。"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re


# 分开单位相同、语义不同的时长；失败样本不与成功施工混在一个分位数里。
FIELDS = {
    "CHUNK-REPLAY-TIMING": ("outcome", ("replayUs",)),
    "BACKFILL-PASS-TIMING": ("flushRequested", ("selectionUs", "checkAndReplayUs", "settlementUs", "totalUs")),
    "DEFERRED-CHUNK-TIMING": (None, ("selectUs",)),
    "CONSTRUCTION-LEDGER-TIMING": ("written", ("gatherUs", "sortUs", "serializeUs", "writeUs")),
}
MARKER = re.compile(r"\[(" + "|".join(FIELDS) + r")\]\s*(.*)")


def summarize(lines):
    samples = defaultdict(list)
    malformed = 0
    for line in lines:
        match = MARKER.search(line)
        if not match:
            continue
        marker, body = match.groups()
        fields = dict(re.findall(r"\b(\w+)=(\S+)", body))
        group, metrics = FIELDS[marker]
        try:
            label = fields[group] if group else "all"
            if group in {"flushRequested", "written"} and label not in {"true", "false"}:
                raise ValueError(label)
            if group == "outcome" and label not in {"PLACED", "EMPTY_PLAN", "NO_PLACEMENT", "exception"}:
                raise ValueError(label)
            values = [int(fields[key]) for key in metrics]
            if min(values) < 0:
                raise ValueError(values)
        except (KeyError, ValueError):
            # 一行记录要么完整计入，要么标错；避免截断日志只污染部分指标。
            malformed += 1
            continue
        for metric, value in zip(metrics, values):
            samples[f"{marker}/{label}/{metric}"].append(value)

    result = {}
    for key, values in sorted(samples.items()):
        values.sort()
        count = len(values)
        result[key] = {"count": count, "max": values[-1],
                       **{f"p{p}": values[(count * p + 99) // 100 - 1] for p in (50, 95, 99)}}
    return {"malformed": malformed, "metricsInMicroseconds": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", type=Path, nargs="+")
    parser.add_argument("--encoding", default="utf-8", help="日志编码，例如 gbk")
    args = parser.parse_args()
    runs = {}
    for path in args.logs:
        with path.open(encoding=args.encoding, errors="replace") as lines:
            runs[str(path)] = summarize(lines)
    print(json.dumps(runs, indent=2, ensure_ascii=True))
    return 1 if any(not run["metricsInMicroseconds"] or run["malformed"] for run in runs.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
