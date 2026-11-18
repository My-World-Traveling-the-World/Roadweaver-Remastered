import unittest

from summarize_backfill_timings import summarize


class BackfillTimingTest(unittest.TestCase):
    def test_tail_and_exceptions_use_separate_samples(self):
        # 100 条样本可把 p95、p99 和最大值分开，避免夹具过小让三个断言等价。
        lines = [f"[CHUNK-REPLAY-TIMING] chunk=[0, 1] outcome=PLACED replayUs={i}" for i in range(100, 0, -1)]
        lines.append("[CHUNK-REPLAY-TIMING] chunk=[0, 1] outcome=exception replayUs=250000")
        metrics = summarize(lines)["metricsInMicroseconds"]
        self.assertEqual({"count": 100, "p50": 50, "p95": 95, "p99": 99, "max": 100},
                         metrics["CHUNK-REPLAY-TIMING/PLACED/replayUs"])
        self.assertEqual(250000, metrics["CHUNK-REPLAY-TIMING/exception/replayUs"]["p99"])

    def test_incomplete_pass_does_not_enter_any_metric(self):
        result = summarize([
            "[BACKFILL-PASS-TIMING] loaded=1 flushRequested=true selectionUs=3 checkAndReplayUs=6 settlementUs=12 totalUs=21",
            "[BACKFILL-PASS-TIMING] flushRequested=true selectionUs=999",
            "[CONSTRUCTION-LEDGER-TIMING] written=false gatherUs=1 sortUs=2 serializeUs=4 writeUs=21",
        ])
        self.assertEqual(1, result["malformed"])
        metrics = result["metricsInMicroseconds"]
        self.assertEqual(3, metrics["BACKFILL-PASS-TIMING/true/selectionUs"]["max"])
        self.assertEqual(21, metrics["CONSTRUCTION-LEDGER-TIMING/false/writeUs"]["max"])


if __name__ == "__main__":
    unittest.main()
