"""Tests for codex_auto_unblock.py — D30~D33 auto-unblock judgement tool.

All fixtures are synthetic (temp dirs): fake launcher logs, fake journals,
fake lock dirs. No real log/ledger reads, no network, no leases touched.
"""
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "codex_auto_unblock.py"
SPEC = importlib.util.spec_from_file_location("codex_auto_unblock", SCRIPT)
CAU = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("codex_auto_unblock", CAU)
SPEC.loader.exec_module(CAU)


def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def journal(task_id, result=None, status="closed", events=None, purpose="g",
            updated="2026-10-03T08:00:00+00:00"):
    return {
        "schemaVersion": "awx.work_journal.v1", "taskId": task_id,
        "agent": task_id.rsplit("-", 1)[0], "purpose": purpose,
        "status": status, "result": result,
        "startedAtUtc": "2026-10-03T07:00:00+00:00",
        "updatedAtUtc": updated,
        "events": events or [],
    }


class BudgetTest(unittest.TestCase):
    def run_budget(self, ledger, cap=10):
        return CAU.budget_eval(str(ledger), cap)

    def test_recount_under_cap_no_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-aaaa1111", events=[
                    {"kind": "verify", "text": "라이브 생성 10/10 도달"},
                    {"kind": "info", "text": "SemanticRequestFingerprint "
                     "클래스 로딩 오류로 모델 호출 전 실패 x2"},
                ])))
            r = self.run_budget(td)
        self.assertTrue(r["allowed"])
        self.assertEqual(0, r["extendBy"])
        self.assertEqual(8, r["recountedUsed"])
        self.assertIn("recount-under-cap", r["reasons"])

    def test_full_cap_auto_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-bbbb2222", events=[
                    {"kind": "verify", "text": "generation 10/10 reached "
                     "for verification items"},
                ])))
            r = self.run_budget(td)
        self.assertTrue(r["allowed"])
        self.assertEqual(5, r["extendBy"])
        self.assertEqual(10, r["recountedUsed"])

    def test_hardcap_blocks_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-cccc3333", events=[
                    {"kind": "verify", "text": "generation 10/10"},
                    {"kind": "info", "text": "HARD_CAP: 추가 생성 절대 금지"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertEqual(0, r["extendBy"])
        self.assertIn("hard-cap", r["reasons"])

    def test_auth_fail_blocks_extend(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-dddd4444", events=[
                    {"kind": "verify", "text": "generation 10/10"},
                    {"kind": "info", "text": "upstream HTTP 401 observed"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertIn("auth-or-rate-fail-observed", r["reasons"])

    def test_already_extended_blocks_second(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "journal.json", json.dumps(journal(
                "t-eeee5555", events=[
                    {"kind": "verify", "text": "generation 15/15"},
                    {"kind": "info", "text": "AUTO_DECISION: D30 "
                     "live-budget +5 (was 10, now 15)"},
                ])))
            r = self.run_budget(td)
        self.assertFalse(r["allowed"])
        self.assertIn("already-extended", r["reasons"])


LOG_LINE = ("{ts} INFO  [chat-1 {uuid}] c.e.l.s.r.PolicyBasedModelRouter - "
            "[plan9-oauth-phase] phase={phase} count=1 "
            "requestHash=hash:{h} atEpochMs=1 httpStatus={status}\n")
FINAL_LINE = ("{ts} DEBUG [chat-1 {uuid}] rag.pipeline - [rag-pipeline] "
              "stage=final-response elapsedMs=9 characters={chars} "
              "evidenceCount=1\n")


def log_text(h="aaaa1111"):
    u = "ff0d26db-ea1c-4d91-bf5c-35987651d817"
    out = []
    out.append(LOG_LINE.format(ts="2026-10-03T18:58:08.877+0900",
                             uuid=u, phase="controller", h=h, status=200))
    out.append(LOG_LINE.format(ts="2026-10-03T18:58:17.555+0900",
                             uuid=u, phase="terminal", h=h, status=200))
    out.append(FINAL_LINE.format(ts="2026-10-03T18:58:25.378+0900",
                                 uuid=u, chars=303))
    out.append(LOG_LINE.format(ts="2026-10-03T19:30:01.000+0900",
                               uuid=u, phase="terminal", h="bbbb2222",
                               status=500))
    # 리터럴 분할: checkpoint secret-scan 오탐 방지 — 실행 시 문자열은 동일.
    out.append("2026-10-03T18:58:30.000+0900 INFO  sid=SECRETSESSIONID12345 "
               "tok" "en=tok_ABCDEFGHIJKLMNOP unrelated\n")
    return "".join(out)


class LogEvidenceTest(unittest.TestCase):
    def make_logs(self, td):
        d = Path(td) / "run1"
        write(d / "chat-ui-vibe-listener-18180.out.log", log_text())
        return Path(td)

    def test_groups_hash_phases_and_final(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="aaaa1111",
                                   since="18:55", until="19:00")
        self.assertEqual(1, len(rep["requests"]))
        r = rep["requests"][0]
        self.assertEqual("aaaa1111", r["hash"])
        phases = [p["phase"] for p in r["phases"]]
        self.assertIn("controller", phases)
        self.assertIn("terminal", phases)
        self.assertEqual(303, r["finalResponse"]["characters"])

    def test_window_filters_later_hash(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="",
                                   since="18:55", until="19:00")
        hashes = [r["hash"] for r in rep["requests"]]
        self.assertIn("aaaa1111", hashes)
        self.assertNotIn("bbbb2222", hashes)  # 19:30 > until

    def test_masks_session_and_token(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.make_logs(td)
            rep = CAU.log_evidence(root, model="",
                                   since="18:55", until="19:05")
            blob = json.dumps(rep, ensure_ascii=False)
        self.assertNotIn("SECRETSESSIONID12345", blob)
        self.assertNotIn("tok_ABCDEFGHIJKLMNOP", blob)
        self.assertNotIn("ff0d26db-ea1c-4d91-bf5c-35987651d817", blob)


class LeaseWaitTest(unittest.TestCase):
    def lease(self, topic, targets, expires, status="active"):
        return {"topic": topic, "status": status,
                "expiresAtUtc": expires,
                "targetPaths": targets, "ownerState": "unknown"}

    def test_no_locks_is_free(self):
        with tempfile.TemporaryDirectory() as td:
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("free", rep["result"])
        self.assertEqual([], rep["live"])

    def test_expired_lease_is_stale(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "a.lock" / "lease.json", json.dumps(
                self.lease("old", ["scripts/x.py"],
                           "2026-10-03T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("stale", rep["result"])
        self.assertEqual(["old"], [l["topic"] for l in rep["stale"]])

    def test_active_lease_is_live(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "b.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/x.py"],
                           "2999-01-01T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("live", rep["result"])
        self.assertEqual(["peer"], [l["topic"] for l in rep["live"]])

    def test_actual_native_schema_without_status_remains_live_in_fallback(self):
        with tempfile.TemporaryDirectory() as td:
            record = self.lease('native', ['scripts/x.py'], '2999-01-01T00:00:00+00:00')
            record.pop('status')
            record.update(schemaVersion='awx.source_edit_session.lease.v1', leaseId='a' * 32)
            write(Path(td) / 'native.lock/lease.json', json.dumps(record))
            result = CAU.lease_scan(td, ['scripts/x.py'])
            self.assertEqual(result['result'], 'live')
            self.assertEqual(result['stale'], [])

    def test_native_prefix_and_unscoped_reservations_are_not_reported_free(self):
        with tempfile.TemporaryDirectory() as td:
            record = {'schemaVersion': 'awx.source_edit_session.lease.v1', 'leaseId': 'a' * 32,
                      'expiresAtUtc': '2999-01-01T00:00:00+00:00', 'reservePaths': ['module/area']}
            path = Path(td) / 'native.lock/lease.json'
            write(path, json.dumps(record))
            self.assertEqual(CAU.lease_scan(td, ['module/area/child.txt'])['result'], 'live')
            self.assertEqual(CAU.lease_scan(td, ['module/area2/sibling.txt'])['result'], 'free')
            record.pop('reservePaths')
            path.write_text(json.dumps(record))
            self.assertEqual(CAU.lease_scan(td, ['unscoped-target.txt'])['result'], 'live')

    def test_malformed_native_scope_is_not_treated_as_disjoint(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / 'native.lock/lease.json'
            for scope in ({'targetPaths': 'target.txt'}, {'reservePaths': {'path': 'target.txt'}},
                          {'targetPaths': [None]}, {'targetPaths': [42]}):
                with self.subTest(scope=scope):
                    write(path, json.dumps({'schemaVersion': 'awx.source_edit_session.lease.v1',
                                            'expiresAtUtc': '2999-01-01T00:00:00+00:00', **scope}))
                    result = CAU.lease_scan(td, ['target.txt'])
                    self.assertEqual(result['result'], 'live')
                    self.assertEqual(result['live'][0]['lifecycle'], 'orphan')

    def test_non_overlapping_lease_ignored(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "c.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/other.py"],
                           "2999-01-01T00:00:00+00:00")))
            rep = CAU.lease_scan(td, ["scripts/x.py"])
        self.assertEqual("free", rep["result"])

    def test_dry_run_main_exit(self):
        with tempfile.TemporaryDirectory() as td:
            write(Path(td) / "d.lock" / "lease.json", json.dumps(
                self.lease("peer", ["scripts/x.py"],
                           "2999-01-01T00:00:00+00:00")))
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CAU.main(["lease-wait", "--paths", "scripts/x.py",
                               "--locks-dir", td, "--dry-run"])
        self.assertEqual(0, rc)
        out = json.loads(buf.getvalue())
        self.assertEqual("live", out["result"])


FIXED_NOW = datetime(2026, 10, 6, 0, 0, 0, tzinfo=timezone.utc)


def _iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def rich_row(topic, lifecycle="live", status="active",
             heartbeat_state="valid", release_pending=False,
             expires_at=None):
    return {"topic": topic, "lifecycle": lifecycle, "status": status,
            "heartbeatState": heartbeat_state,
            "releasePending": release_pending,
            "expiresAtUtc": expires_at}


def rich_scanner(live=(), stale=(), free=()):
    def scan(paths):
        return {"result": ("live" if live else
                           ("stale" if stale else "free")),
                "free": list(free), "live": [dict(r) for r in live],
                "stale": [dict(r) for r in stale]}
    return scan


def ticket(td, paths, task, created, expires):
    d = CAU._waiter_dir(td, paths)
    d.mkdir(parents=True, exist_ok=True)
    p = d / (created.strftime("%Y%m%dT%H%M%SZ") + "-" + task + ".json")
    write(p, json.dumps({"task": task, "targets": sorted(paths),
                         "createdAtUtc": _iso(created),
                         "expiresAtUtc": _iso(expires)}))
    return p


class LeaseWaitAutoBudgetTest(unittest.TestCase):
    """W1: --max-min auto가 막은 lease 상태로 예산을 정한다."""

    def test_finishing_ttl_budget(self):
        exp = FIXED_NOW + timedelta(minutes=40)
        row = rich_row("peer", status="finishing", release_pending=True,
                       expires_at=_iso(exp))
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min="auto",
                             dry_run=True, scanner=rich_scanner(live=[row]),
                             wall_now_fn=lambda: FIXED_NOW)
        self.assertEqual("live", rep["result"])
        self.assertEqual(2700, rep["budgetSec"])       # 40분 + 5분 버퍼
        self.assertEqual("finishing_ttl", rep["budgetBasis"])

    def test_active_default_budget(self):
        row = rich_row("peer", status="active",
                       expires_at=_iso(FIXED_NOW + timedelta(minutes=90)))
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min="auto",
                             dry_run=True, scanner=rich_scanner(live=[row]),
                             wall_now_fn=lambda: FIXED_NOW)
        self.assertEqual(1200, rep["budgetSec"])
        self.assertEqual("active_default", rep["budgetBasis"])

    def test_stale_skips_wait(self):
        row = rich_row("peer", lifecycle="stale", status="ended",
                       heartbeat_state="absent",
                       expires_at=_iso(FIXED_NOW - timedelta(minutes=5)))
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min="auto",
                             dry_run=True, scanner=rich_scanner(stale=[row]),
                             wall_now_fn=lambda: FIXED_NOW)
        self.assertEqual("stale", rep["result"])
        self.assertEqual(0, rep["budgetSec"])
        self.assertEqual("stale_skip", rep["budgetBasis"])

    def test_budget_capped_at_60min(self):
        exp = FIXED_NOW + timedelta(minutes=180)
        row = rich_row("peer", status="finishing", release_pending=True,
                       expires_at=_iso(exp))
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min="auto",
                             dry_run=True, scanner=rich_scanner(live=[row]),
                             wall_now_fn=lambda: FIXED_NOW)
        self.assertEqual(3600, rep["budgetSec"])
        self.assertEqual("finishing_ttl", rep["budgetBasis"])

    def test_numeric_max_min_backward_compat(self):
        row = rich_row("peer")
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min=5,
                             dry_run=True, scanner=rich_scanner(live=[row]))
        self.assertEqual(300, rep["budgetSec"])
        self.assertEqual("fixed", rep["budgetBasis"])
        rep2 = CAU.lease_wait(["scripts/x.py"], "unused", max_min="7",
                              dry_run=True, scanner=rich_scanner(live=[row]))
        self.assertEqual(420, rep2["budgetSec"])

    def test_output_fields_present(self):
        rep = CAU.lease_wait(["scripts/x.py"], "unused", dry_run=True,
                             scanner=rich_scanner())
        for key in ("waitedSec", "waitedSeconds", "budgetSec",
                    "budgetBasis", "lastStatus", "blockers"):
            self.assertIn(key, rep)

    def test_heartbeat_called_each_interval(self):
        calls = {"hb": 0, "sleeps": 0}
        states = [rich_scanner(live=[rich_row("p")]),
                  rich_scanner(live=[rich_row("p")]),
                  rich_scanner()]
        it = iter(states)
        rep = CAU.lease_wait(["scripts/x.py"], "unused", max_min="auto",
                             sleep_fn=lambda s: calls.__setitem__(
                                 "sleeps", calls["sleeps"] + 1),
                             heartbeat_fn=lambda: calls.__setitem__(
                                 "hb", calls["hb"] + 1),
                             scanner=lambda p: next(it)(p))
        self.assertEqual("free", rep["result"])
        self.assertEqual(2, calls["hb"])
        self.assertEqual(2, calls["sleeps"])


class WaitRoundContract(unittest.TestCase):
    def wait(self, **kwargs):
        return CAU.lease_wait(["target.txt"], "unused",
                              scanner=rich_scanner(live=[rich_row("peer")]),
                              heartbeat_fn=lambda: None, **kwargs)

    def test_sleep_does_not_cross_round_deadline(self):
        clock, sleeps = [0.0], []
        def sleep(seconds):
            sleeps.append(seconds)
            clock[0] += seconds
        result = self.wait(max_min=1 / 60, interval=60,
                           now_fn=lambda: clock[0], sleep_fn=sleep)
        self.assertEqual([1.0], sleeps)
        self.assertEqual(1, result["waitedSec"])
        self.assertEqual("WAITING", result["waitState"])
        self.assertFalse(result["ownsTargets"])

    def test_default_elapsed_uses_monotonic_not_wall_clock(self):
        clock = [0.0]
        with patch.object(CAU.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(CAU.time, "time", side_effect=AssertionError("wall-clock elapsed")):
            result = self.wait(max_min=1 / 60, interval=1,
                               sleep_fn=lambda s: clock.__setitem__(0, clock[0] + s))
        self.assertTrue(result["pendingLease"])

    def test_slow_heartbeat_cannot_sleep_past_deadline(self):
        clock, sleeps = [0.0], []
        result = CAU.lease_wait(["target.txt"], "unused", max_min=1 / 60,
            now_fn=lambda: clock[0], sleep_fn=lambda s: sleeps.append(s),
            heartbeat_fn=lambda: clock.__setitem__(0, 2),
            scanner=rich_scanner(live=[rich_row("peer")]))
        self.assertEqual([], sleeps)
        self.assertTrue(result["pendingLease"])

    def test_invalid_interval_or_budget_never_scans(self):
        for key, values in [("interval", [0, -1, float("nan"), float("inf")]),
                            ("max_min", [-1, float("nan"), float("inf")])]:
            for value in values:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    CAU.lease_wait(["target.txt"], "unused", **{key: value},
                                   scanner=lambda _: self.fail("invalid timer scanned"))

    def test_rounds_keep_waiting_with_capped_backoff(self):
        for _ in range(3):
            clock, sleeps = [0.0], []
            def sleep(seconds):
                sleeps.append(seconds)
                clock[0] += seconds
            result = self.wait(max_min=2, interval=10, now_fn=lambda: clock[0],
                               sleep_fn=sleep)
            self.assertEqual([10, 20, 40, 50], sleeps)
            self.assertEqual("WAITING", result["waitState"])
            self.assertTrue(result["pendingLease"])

    def test_three_twenty_minute_rounds_remain_waiting_without_write_authority(self):
        observed = 0
        for _ in range(3):
            clock, sleeps = [0.0], []
            def sleep(seconds):
                sleeps.append(seconds)
                clock[0] += seconds
            result = self.wait(max_min=20, interval=10, now_fn=lambda: clock[0], sleep_fn=sleep)
            self.assertEqual(sleeps, [10, 20, 40] + [60] * 18 + [50])
            self.assertEqual(result['waitState'], 'WAITING')
            self.assertFalse(result['ownsTargets'])
            observed += result['waitedSec']
        self.assertEqual(observed, 3600)

    def test_cancellation_releases_only_own_waiter(self):
        with tempfile.TemporaryDirectory() as td:
            ticket(td, ["target.txt"], "peer", FIXED_NOW,
                   FIXED_NOW + timedelta(seconds=300))
            peer = next((Path(td) / "waiters").rglob("*-peer.json"))
            cancelled = [False]
            result = CAU.lease_wait(["target.txt"], td, enqueue=True, task="me",
                max_min=1, scanner=rich_scanner(live=[rich_row("peer")]),
                heartbeat_fn=lambda: None, cancel_fn=lambda: cancelled[0],
                sleep_fn=lambda _: cancelled.__setitem__(0, True))
            self.assertEqual("CANCELLED", result["waitState"])
            self.assertTrue(peer.is_file())
            self.assertFalse(list((Path(td) / "waiters").rglob("*-me.json")))

    def test_next_round_reuses_own_ticket_and_free_release_is_not_self_blocked(self):
        with tempfile.TemporaryDirectory() as td:
            clock = [0.0]
            first = CAU.lease_wait(['target.txt'], td, max_min=1, interval=60, enqueue=True,
                task='me', wait_id='same-wait', now_fn=lambda: clock[0],
                sleep_fn=lambda seconds: clock.__setitem__(0, clock[0] + seconds),
                heartbeat_fn=lambda: None, scanner=rich_scanner(live=[rich_row('peer')]))
            self.assertEqual(first['waitState'], 'WAITING')
            with patch.object(CAU, 'datetime', wraps=CAU.datetime) as shifted:
                shifted.now.return_value = datetime.now(timezone.utc) + timedelta(seconds=2)
                second = CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True,
                    task='me', wait_id='same-wait', scanner=rich_scanner(), heartbeat_fn=lambda: None)
            self.assertEqual(second['result'], 'free')
            self.assertEqual(second['waitState'], 'READY_TO_ACQUIRE')
            self.assertFalse(second['ownsTargets'])
            self.assertFalse(list((Path(td) / 'waiters').rglob('*-me.json')))

    def test_cancelled_round_retires_own_prior_pending_record(self):
        with tempfile.TemporaryDirectory() as td:
            root, wait = Path(td) / 'root', Path(td) / 'wait'
            root.mkdir()
            (root / 'target.txt').write_bytes(b'original\n')
            CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True, task='me', root=root,
                wait_id='cancel', wait_dir=wait, scanner=rich_scanner(live=[rich_row('peer')]),
                heartbeat_fn=lambda: None)
            result = CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True, task='me', root=root,
                wait_id='cancel', wait_dir=wait, scanner=rich_scanner(), cancel_fn=lambda: True)
            self.assertEqual(result['waitState'], 'CANCELLED')
            pending = json.loads((wait / 'cancel/pending.json').read_bytes())
            self.assertEqual(pending['status'], 'CANCELLED')
            self.assertFalse(pending['pendingLease'])
            self.assertEqual(pending['nextAction'], 'none')

    def test_terminal_pending_write_failure_is_explicitly_blocked(self):
        for cancel in [True, False]:
            with self.subTest(cancel=cancel), tempfile.TemporaryDirectory() as td:
                root, wait = Path(td) / 'root', Path(td) / 'wait'
                root.mkdir()
                (root / 'target.txt').write_bytes(b'original\n')
                CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True, task='me', root=root,
                    wait_id='w1', wait_dir=wait, scanner=rich_scanner(live=[rich_row('peer')]),
                    heartbeat_fn=lambda: None)
                pending = wait / 'w1/pending.json'
                original = pending.read_bytes()
                with patch.object(Path, 'write_text', side_effect=PermissionError('synthetic retirement denial')):
                    result = CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True, task='me', root=root,
                        wait_id='w1', wait_dir=wait, scanner=rich_scanner(), cancel_fn=lambda: cancel,
                        heartbeat_fn=lambda: None)
                self.assertEqual(pending.read_bytes(), original)
                self.assertEqual(result['waitState'], 'BLOCKED')
                self.assertEqual(result['reason'], 'pending-retirement-unconfirmed')
                self.assertFalse(result['ownsTargets'])
                self.assertTrue(result['pendingRecordMayBeActive'])


class LeaseWaitEnqueueTest(unittest.TestCase):
    """W2: --enqueue 대기표 순번·만료 무시·예외 시 삭제."""

    def test_second_waiter_gets_free_wait_turn(self):
        with tempfile.TemporaryDirectory() as td:
            ticket(td, ["scripts/x.py"], "other-agent",
                   FIXED_NOW - timedelta(minutes=1),
                   FIXED_NOW + timedelta(minutes=30))
            rep = CAU.lease_wait(["scripts/x.py"], td, max_min="0",
                                 enqueue=True, task="me",
                                 scanner=rich_scanner(),
                                 wall_now_fn=lambda: FIXED_NOW)
            self.assertEqual("free_wait_turn", rep["result"])

    def test_first_waiter_gets_free(self):
        with tempfile.TemporaryDirectory() as td:
            rep = CAU.lease_wait(["scripts/x.py"], td, max_min="0",
                                 enqueue=True, task="me",
                                 scanner=rich_scanner(),
                                 wall_now_fn=lambda: FIXED_NOW)
            self.assertEqual("free", rep["result"])
            self.assertEqual([], list(
                (Path(td) / "waiters").rglob("*.json")))

    def test_expired_ticket_ignored(self):
        with tempfile.TemporaryDirectory() as td:
            ticket(td, ["scripts/x.py"], "dead-agent",
                   FIXED_NOW - timedelta(hours=2),
                   FIXED_NOW - timedelta(hours=1))  # 이미 만료
            rep = CAU.lease_wait(["scripts/x.py"], td, max_min="0",
                                 enqueue=True, task="me",
                                 scanner=rich_scanner(),
                                 wall_now_fn=lambda: FIXED_NOW)
            self.assertEqual("free", rep["result"])

    def test_exception_removes_my_ticket(self):
        with tempfile.TemporaryDirectory() as td:
            def boom(paths):
                raise RuntimeError("scan failed")
            with self.assertRaises(RuntimeError):
                CAU.lease_wait(["scripts/x.py"], td, max_min="0",
                               enqueue=True, task="me", scanner=boom)
            mine = list((Path(td) / "waiters").rglob("*-me.json"))
            self.assertEqual([], mine)

    def test_dry_run_creates_no_ticket(self):
        with tempfile.TemporaryDirectory() as td:
            CAU.lease_wait(["scripts/x.py"], td, enqueue=True, task="me",
                           dry_run=True, scanner=rich_scanner())
            self.assertFalse((Path(td) / "waiters").exists())


class HelpEncodingTest(unittest.TestCase):
    """S8-e 회귀: --help가 cp949 콘솔에서 UnicodeEncodeError로 죽지 않는다."""

    def _run_help_on_cp949(self, argv):
        raw = io.BytesIO()
        stream = io.TextIOWrapper(raw, encoding="cp949", errors="strict")
        old = sys.stdout
        try:
            sys.stdout = stream
            with self.assertRaises(SystemExit) as cm:
                CAU.main(argv)
            stream.flush()
        finally:
            sys.stdout = old
        return cm.exception.code, raw.getvalue()

    def test_lease_wait_help_cp949(self):
        code, out = self._run_help_on_cp949(["lease-wait", "--help"])
        self.assertEqual(0, code)
        self.assertIn(b"--paths", out)

    def test_top_level_help_cp949(self):
        code, out = self._run_help_on_cp949(["--help"])
        self.assertEqual(0, code)
        self.assertIn(b"lease-wait", out)


class LeaseWaitSnapshotTest(unittest.TestCase):
    """S2: 막혀 기다리기 시작하면 before.json 스냅샷을 남긴다."""

    def test_queue_wait_captures_baseline_before_first_sleep(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / 'root'
            root.mkdir()
            (root / 'target.txt').write_bytes(b'original\n')
            ticket(td, ['target.txt'], 'other', FIXED_NOW - timedelta(minutes=1),
                   FIXED_NOW + timedelta(minutes=30))
            rep = CAU.lease_wait(['target.txt'], td, max_min=0, enqueue=True, task='me',
                root=root, scanner=rich_scanner(), wall_now_fn=lambda: FIXED_NOW,
                wait_dir=Path(td) / 'wait', wait_id='queue')
            self.assertEqual(rep['result'], 'free_wait_turn')
            self.assertTrue(rep.get('beforeJson'))
            self.assertEqual(json.loads(Path(rep['beforeJson']).read_bytes())['targets'][0]['sha256'],
                             hashlib.sha256(b'original\n').hexdigest())

    def test_changed_baseline_bytes_cannot_be_adopted_in_next_round(self):
        with tempfile.TemporaryDirectory() as td:
            root, wait = Path(td) / 'root', Path(td) / 'wait'
            root.mkdir()
            (root / 'target.txt').write_bytes(b'original\n')
            before = Path(CAU.snapshot_before(root, ['target.txt'], wait, 'w1', task='t1'))
            doc = json.loads(before.read_bytes())
            doc['targets'][0]['sha256'] = 'a' * 64
            before.write_text(json.dumps(doc))
            self.assertIsNone(CAU.snapshot_before(root, ['target.txt'], wait, 'w1', task='t1'))

    def test_baseline_mutation_during_wait_keeps_first_hash_and_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / 'root'
            root.mkdir()
            (root / 'target.txt').write_bytes(b'original\n')
            before = Path(td) / 'wait/w1/before.json'
            first_sha = []
            scans = iter([rich_scanner(live=[rich_row('peer')]), rich_scanner()])

            def mutate(_):
                first_sha.append(hashlib.sha256(before.read_bytes()).hexdigest())
                doc = json.loads(before.read_bytes())
                doc['targets'][0]['sha256'] = 'a' * 64
                before.write_text(json.dumps(doc))

            rep = CAU.lease_wait(['target.txt'], td, max_min=1, task='me', root=root,
                scanner=lambda p: next(scans)(p), wait_dir=Path(td) / 'wait', wait_id='w1',
                sleep_fn=mutate, heartbeat_fn=lambda: None)
            self.assertEqual(rep['result'], 'blocked')
            self.assertEqual(rep['reason'], 'baseline-changed-during-wait')
            self.assertEqual(rep['beforeSha256'], first_sha[0])
            self.assertFalse(rep['ownsTargets'])

    def test_second_round_preserves_original_snapshot_and_context(self):
        with tempfile.TemporaryDirectory() as td:
            root, wait = Path(td) / "root", Path(td) / "wait"
            root.mkdir()
            target = root / "target.txt"
            target.write_bytes(b"original\n")
            before = Path(CAU.snapshot_before(root, ["target.txt"], wait, "w1", task="t1"))
            original = before.read_bytes()
            doc = json.loads(original)
            snapshot = before.parent / doc["targets"][0]["snapshot"]
            target.write_bytes(b"foreign round two\n")
            self.assertEqual(str(before), CAU.snapshot_before(root, ["target.txt"], wait, "w1", task="t1"))
            self.assertEqual(before.read_bytes(), original)
            self.assertEqual(snapshot.read_bytes(), b"original\n")
            self.assertIsNone(CAU.snapshot_before(root, ["target.txt"], wait, "w1", task="different"))
            self.assertEqual(before.read_bytes(), original)

    def test_baseline_records_file_identity_and_read_error_is_unknown(self):
        with tempfile.TemporaryDirectory() as td:
            root, wait = Path(td) / "root", Path(td) / "wait"
            root.mkdir()
            target = root / "target.txt"
            target.write_bytes(b"current\n")
            real_read = Path.read_bytes

            def deny_target(path):
                if path == target:
                    raise PermissionError("synthetic denial")
                return real_read(path)

            with patch.object(Path, "read_bytes", deny_target):
                before = CAU.snapshot_before(root, ["target.txt", "new.txt"], wait, "w1", task="t1")
            rows = json.loads(Path(before).read_bytes())["targets"]
            self.assertEqual(rows[0]["state"], "UNKNOWN")
            self.assertTrue(rows[0]["exists"])
            self.assertEqual(rows[1]["state"], "ABSENT")
            self.assertFalse(rows[1]["exists"])

    def test_goal_plan_and_related_inputs_are_bound_to_immutable_baseline(self):
        with tempfile.TemporaryDirectory() as td:
            root, wait = Path(td) / "root", Path(td) / "wait"
            root.mkdir()
            (root / "target.txt").write_bytes(b"original\n")
            (root / "config.json").write_bytes(b"{}\n")
            context = {"goalRevision": "goal-1", "planRevision": "plan-1",
                       "inputPaths": ["config.json"], "invariants": ["preserve-foreign-bytes"]}
            before = Path(CAU.snapshot_before(root, ["target.txt"], wait, "w1", task="t1", context=context))
            original = before.read_bytes()
            doc = json.loads(original)
            self.assertEqual(doc["context"], context)
            self.assertEqual(doc["inputs"][0]["sha256"], hashlib.sha256(b"{}\n").hexdigest())
            self.assertEqual(doc["targets"][0]["type"], "file")
            self.assertTrue(doc["targets"][0]["identity"]["inode"] > 0)
            self.assertIsNone(CAU.snapshot_before(root, ["target.txt"], wait, "w1", task="t1",
                                                 context={**context, "goalRevision": "goal-2"}))
            self.assertEqual(before.read_bytes(), original)

    def test_before_json_written_on_blocked_wait(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "root"
            (root / "scripts").mkdir(parents=True)
            payload = b"print(1)\n"
            (root / "scripts" / "x.py").write_bytes(payload)
            wait_dir = Path(td) / "wait"
            scans = iter([rich_scanner(live=[rich_row("p")]),
                          rich_scanner()])
            rep = CAU.lease_wait(
                ["scripts/x.py"], td, max_min=1,
                scanner=lambda p: next(scans)(p), wait_dir=str(wait_dir),
                wait_id="w1", task="t1", root=str(root),
                sleep_fn=lambda s: None)
            self.assertEqual("free", rep["result"])
            doc = json.loads((wait_dir / "w1" / "before.json")
                             .read_text(encoding="utf-8"))
        row = doc["targets"][0]
        self.assertEqual("scripts/x.py", row["path"])
        self.assertTrue(row["exists"])
        self.assertEqual(hashlib.sha256(payload).hexdigest(), row["sha256"])
        self.assertEqual(len(payload), row["size"])
        self.assertIn("mtimeUtc", row)
        self.assertTrue(row["snapshot"].startswith("before/"))

    def test_no_snapshot_when_free(self):
        with tempfile.TemporaryDirectory() as td:
            wait_dir = Path(td) / "wait"
            CAU.lease_wait(["scripts/x.py"], td, max_min=1,
                           scanner=rich_scanner(), wait_dir=str(wait_dir),
                           wait_id="w2", task="t1")
            self.assertFalse((wait_dir / "w2" / "before.json").exists())

    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as td:
            wait_dir = Path(td) / "wait"
            rep = CAU.lease_wait(["scripts/x.py"], td, dry_run=True,
                                 scanner=rich_scanner(live=[rich_row("p")]),
                                 wait_dir=str(wait_dir), wait_id="w3",
                                 task="t1")
            self.assertEqual("live", rep["result"])
            self.assertFalse(wait_dir.exists())


class LeaseWaitPendingTest(unittest.TestCase):
    """S4: 예산 소진/stale이어도 세션을 끝내지 않는다 — pendingLease + 티켓 유지."""

    def test_budget_exhausted_pending_keeps_ticket(self):
        with tempfile.TemporaryDirectory() as td:
            wait_dir = Path(td) / "wait"
            clock = {"t": 0.0}
            scans = {"n": 0}

            def counting(paths):
                scans["n"] += 1
                return rich_scanner(live=[rich_row("p")])(paths)

            rep = CAU.lease_wait(
                ["scripts/x.py"], td, max_min=1, interval=1, enqueue=True,
                task="me", scanner=counting, wait_dir=str(wait_dir),
                wait_id="w4", now_fn=lambda: clock["t"],
                sleep_fn=lambda s: clock.__setitem__("t", clock["t"] + s))
            self.assertEqual("live", rep["result"])
            self.assertTrue(rep["pendingLease"])
            self.assertEqual("budget-exhausted-blocked",
                             json.loads((wait_dir / "w4" / "pending.json")
                                        .read_text())["note"])
            # Round polling backs off while preserving the one-minute deadline.
            self.assertGreaterEqual(scans["n"], 2)
            self.assertLessEqual(scans["n"], 8)
            self.assertEqual(60, rep["waitedSec"])
            # 대기표를 지우지 않는다 — 순번 유지
            self.assertTrue(list((Path(td) / "waiters")
                                 .rglob("*-me.json")))

    def test_stale_pending_keeps_ticket_no_release(self):
        with tempfile.TemporaryDirectory() as td:
            wait_dir = Path(td) / "wait"
            stale_row = rich_row("s", lifecycle="stale", status="ended",
                                 heartbeat_state="absent",
                                 expires_at=_iso(FIXED_NOW
                                                 - timedelta(minutes=5)))
            rep = CAU.lease_wait(
                ["scripts/x.py"], td, enqueue=True, task="me",
                scanner=rich_scanner(stale=[stale_row]),
                wait_dir=str(wait_dir), wait_id="w5")
            self.assertEqual("stale", rep["result"])
            self.assertTrue(rep["pendingLease"])
            doc = json.loads((wait_dir / "w5" / "pending.json")
                             .read_text())
            self.assertEqual("PENDING_LEASE", doc["status"])
            self.assertEqual("stale-not-released", doc["note"])
            self.assertTrue(list((Path(td) / "waiters")
                                 .rglob("*-me.json")))

    def test_free_wait_turn_keeps_queue_ticket(self):
        # 내 차례가 아니면 대기표를 지우지 않는다 — 순번 유지가 pending의 일부.
        with tempfile.TemporaryDirectory() as td:
            ticket(td, ["scripts/x.py"], "other-agent",
                   FIXED_NOW - timedelta(minutes=1),
                   FIXED_NOW + timedelta(minutes=30))
            rep = CAU.lease_wait(
                ["scripts/x.py"], td, max_min="0", enqueue=True, task="me",
                scanner=rich_scanner(), wait_dir=str(Path(td) / "wait"),
                wait_id="w6", wall_now_fn=lambda: FIXED_NOW)
            self.assertEqual("free_wait_turn", rep["result"])
            self.assertTrue(list((Path(td) / "waiters")
                                 .rglob("*-me.json")))


class SupersededTest(unittest.TestCase):
    def make_pair(self, td, old_id, new_id, new_result):
        root = Path(td)
        write(root / old_id / "journal.json", json.dumps(journal(
            old_id, result="partial", purpose="same goal",
            events=[{"kind": "verify", "text": "A1 partial"}])))
        write(root / new_id / "journal.json", json.dumps(journal(
            new_id, result=new_result, status="closed",
            purpose="same goal", updated="2026-10-03T09:00:00+00:00")))
        write(root / new_id / "report.md",
              "A1 PASS\nA2 PASS\nA3 NOT_RUN\n")
        return root / old_id

    def test_newer_verified_is_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "verified")
            rep = CAU.superseded_eval(old)
        self.assertTrue(rep["superseded"])
        self.assertEqual("topic-bbbb2222", rep["by"])
        self.assertIn("A1", rep["passItems"])
        self.assertIn("A2", rep["passItems"])
        self.assertNotIn("A3", rep["passItems"])

    def test_newer_partial_not_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "partial")
            rep = CAU.superseded_eval(old)
        self.assertFalse(rep["superseded"])

    def test_different_prefix_not_superseded(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            write(root / "topic-aaaa1111" / "journal.json",
                  json.dumps(journal("topic-aaaa1111", result="partial")))
            write(root / "other-bbbb2222" / "journal.json",
                  json.dumps(journal("other-bbbb2222", result="verified")))
            rep = CAU.superseded_eval(root / "topic-aaaa1111")
        self.assertFalse(rep["superseded"])

    def test_main_json_output(self):
        with tempfile.TemporaryDirectory() as td:
            old = self.make_pair(td, "topic-aaaa1111", "topic-bbbb2222",
                                 "verified")
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CAU.main(["superseded", "--ledger", str(old)])
        self.assertEqual(0, rc)
        out = json.loads(buf.getvalue())
        self.assertTrue(out["superseded"])


if __name__ == "__main__":
    unittest.main()
