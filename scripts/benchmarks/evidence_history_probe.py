"""重复证据日志的峰值内存对照；唯一道路/版本/区块规模仍需另外计量。"""
import gc
from pathlib import Path
import sys
import time
import tracemalloc
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"evidence"))
from summarize_route_evidence import summarize


def run(count,limit):
    content="c1-"+"a"*64
    event="[ROUTE-EVIDENCE] id=minecraft:overworld@42/1,2~3,4 stage=validated safe=true reason=ok product=Product[stations=100] construction=not-confirmed content="+content
    gc.collect()
    tracemalloc.start()
    start=time.perf_counter()
    report=summarize([("probe","generated",(event for _ in range(count)))],history_limit=limit)
    elapsed=time.perf_counter()-start
    peak=tracemalloc.get_traced_memory()[1]
    tracemalloc.stop()
    route=report["routes"][0]
    assert route["observations"]==count and route["observationCountsByStage"]["validated"]==count
    assert route["latestValidationPassed"]=={"validated":True} and route["roadCompletion"]=="not-proven"
    assert len(route["validation"]["validated"])==(count if limit is None else min(count,limit))
    print(f"EVIDENCE_MEMORY count={count} limit={limit} peakBytes={peak} seconds={elapsed:.4f}")


if __name__=="__main__":
    for count in (1000,10000,50000):
        for limit in (None,16): run(count,limit)
