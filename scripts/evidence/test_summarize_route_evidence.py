"""离线夹具守住重复、重试、会话和版本边界，不把一条完成日志当整路落成。"""
import json
from pathlib import Path
import tempfile
import unittest
from summarize_route_evidence import main, summarize

ROUTE = "minecraft:overworld@42/-1,0~1,0"


def line(fields, route=ROUTE):
    return "[12:00:00] [INFO] [ROUTE-EVIDENCE] id=" + route + " " + fields


def chunk(outcome="COMPLETED", fingerprint="-99", coord="-1,2", repair="false"):
    return line(f"stage=chunk fingerprint={fingerprint} chunk={coord} localStations=5 outcome={outcome} gapRepair={repair}")


def validation(stage="validated", safe="true"):
    return line(f"stage={stage} safe={safe} reason=reason with spaces product=Product[stations=5, pavementEntries=10] construction=not-confirmed")


class EvidenceSummaryTest(unittest.TestCase):
    def report(self, *lines):
        return summarize([("world-new", "test.log", lines)])

    def test_complete_observation_chain_still_does_not_prove_whole_road(self):
        report = self.report(line("stage=coarse selected=true geodesic=12.5 protection=unprepared detail=proxy only"),
            validation("final-water"), validation("final-structure"), validation(), chunk())
        self.assertEqual([], report["errors"])
        route = report["routes"][0]
        self.assertEqual([], route["missingStages"])
        self.assertEqual("not-proven", route["roadCompletion"])
        self.assertEqual({"COMPLETED": 1}, route["chunkOutcomeCountsByFingerprint"]["-99"])
        self.assertEqual("reason with spaces", route["validation"]["validated"][0]["reason"])

    def test_real_coarse_detail_keeps_nested_same_named_fields_as_opaque_text(self):
        detail = "Choice[from=0, to=1, geodesic=12.5, selected=true] status=SCORED columns=5 wetRunBlocks=0.0"
        report = self.report(line("stage=coarse selected=false geodesic=15.0 protection=unprepared detail=" + detail))
        self.assertEqual([], report["errors"])
        event = report["routes"][0]["coarse"][0]
        self.assertFalse(event["selected"])
        self.assertEqual(15.0, event["geodesic"])
        self.assertEqual(detail, event["detail"])

    def test_copies_with_same_seed_and_endpoints_remain_separate_sessions(self):
        report = summarize([("copy-a", "a", [chunk()]), ("copy-b", "b", [chunk("ABANDONED")])])
        self.assertEqual(2, len(report["routes"]))
        self.assertEqual(["copy-a", "copy-b"], [r["session"] for r in report["routes"]])

    def test_duplicate_rotated_log_does_not_inflate_completed_chunk_count(self):
        report = summarize([("world", "old", [chunk()]), ("world", "new", [chunk()])])
        route = report["routes"][0]
        self.assertEqual(2, route["observations"])
        self.assertEqual({"COMPLETED": 1}, route["chunkOutcomeCountsByFingerprint"]["-99"])

    def test_abandoned_then_retry_complete_keeps_both_observations(self):
        route = self.report(chunk("ABANDONED"), chunk())["routes"][0]
        state = route["chunksByFingerprint"]["-99"]["-1,2"]
        self.assertEqual(["ABANDONED", "COMPLETED"], state["observedOutcomes"])
        self.assertEqual("COMPLETED", state["latestOutcome"])

    def test_later_incomplete_is_not_hidden_by_historical_success(self):
        route = self.report(chunk(), chunk("INCOMPLETE"))["routes"][0]
        self.assertEqual({"INCOMPLETE": 1}, route["chunkOutcomeCountsByFingerprint"]["-99"])

    def test_gap_repair_fingerprint_never_inherits_old_completion(self):
        route = self.report(chunk(), chunk("NOT_ATTEMPTED", "100", repair="true"))["routes"][0]
        self.assertEqual(2, len(route["chunksByFingerprint"]))
        self.assertEqual({"NOT_ATTEMPTED": 1}, route["chunkOutcomeCountsByFingerprint"]["100"])
        self.assertTrue(route["chunksByFingerprint"]["100"]["-1,2"]["gapRepairSeen"])

    def test_missing_stages_are_explicit_and_coarse_is_only_proxy(self):
        route = self.report(line("stage=coarse selected=false geodesic=0 protection=unprepared detail=no route"))["routes"][0]
        self.assertEqual(["final-water", "final-structure", "validated", "chunk"], route["missingStages"])
        self.assertEqual({}, route["latestValidationPassed"])
        self.assertFalse(route["coarse"][0]["selected"])

    def test_old_roads_without_owners_cannot_be_merged(self):
        report = self.report(line("stage=validated safe=true reason=ok product=Product[] construction=not-confirmed", "missing-owner-pair"),
            line("stage=validated safe=false reason=failed product=Product[] construction=not-confirmed", "missing-owner-pair"))
        self.assertEqual([], report["routes"])
        self.assertEqual(2, len(report["unlinked"]))

    def test_each_chunk_outcome_remains_distinct(self):
        states = ["COMPLETED", "ABANDONED", "INCOMPLETE", "NOT_ATTEMPTED", "CONFLICT"]
        route = self.report(*(chunk(state, coord=f"{i},2") for i, state in enumerate(states)))["routes"][0]
        self.assertEqual({s: 1 for s in states}, route["chunkOutcomeCountsByFingerprint"]["-99"])

    def test_malformed_events_report_location_and_do_not_destroy_valid_rows(self):
        for bad in ("stage=unknown", "stage=chunk", "stage=coarse selected=true geodesic=NaN protection=unprepared",
                    "stage=coarse selected=maybe geodesic=1 protection=unprepared", "stage=chunk stage=chunk",
                    "stage=chunk fingerprint=1 chunk=1 localStations=1 outcome=COMPLETED gapRepair=false"):
            with self.subTest(bad=bad):
                report = self.report(line(bad), chunk())
                self.assertEqual(1, len(report["errors"]))
                self.assertEqual(1, report["errors"][0]["line"])
                self.assertEqual(1, len(report["routes"]))

    def test_latest_validation_failure_keeps_reason_and_never_means_built(self):
        route = self.report(validation(), validation(safe="false"))["routes"][0]
        self.assertFalse(route["latestValidationPassed"]["validated"])
        self.assertEqual(2, len(route["validation"]["validated"]))
        self.assertEqual("not-proven", route["roadCompletion"])

    def test_cli_handles_gbk_files_and_returns_nonzero_for_malformed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "game.log", Path(directory) / "summary.json"
            source.write_text("中文日志\n" + chunk() + "\n" + line("stage=unknown"), encoding="gb18030")
            status = main(["--session", "world", str(source), "--encoding", "gb18030", "--output", str(output)])
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, status)
            self.assertEqual(1, len(report["routes"]))
            self.assertEqual(1, len(report["errors"]))


if __name__ == "__main__":
    unittest.main()
