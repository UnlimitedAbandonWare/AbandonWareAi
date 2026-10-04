#!/usr/bin/env python3
"""Synthetic evidence pack contracts; no live sessions, secrets or providers."""
import contextlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gptpro_pack as gp
import gptpro_pack_evidence as ge

NOW = datetime.now(timezone.utc)
SYNTHETIC = "sk-proj-" + "Ab3" * 24


def write(root, rel, content):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="gptpro-evidence-test-"))
        self.root = self.temp / "repo"
        self.root.mkdir()
        write(self.root, "main/java/App.java", "class App {}\n")
        write(self.root, "main/resources/static/vendor/lib.min.js", "console.log('vendor')\n")
        write(self.root, "main/resources/application.yml", "spring:\n  profiles: local\n")
        write(self.root, "build.gradle.kts", 'plugins {}\n')
        write(self.root, "settings.gradle.kts", 'rootProject.name = "fixture"\n')
        write(self.root, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=fixture\n")
        self.sessions = self.temp / "sessions"
        self.out = self.temp / "output"

    def tearDown(self):
        # The resolved task-created temporary directory is the only deletion target.
        self.assertEqual(self.temp.parent, Path(tempfile.gettempdir()))
        shutil.rmtree(self.temp)

    def build(self, cfg=None, **kwargs):
        return ge.build(self.root, cfg or {}, git=lambda *a: None, scan=gp.scan_text,
                        included=[("main/java/App.java", 13)], changed=[],
                        now=NOW, sessions_dir=self.sessions, probe_environment=False, **kwargs)

    def rollout(self, name, cwd, events):
        rel = NOW.strftime("%Y/%m/%d") + "/rollout-" + name + ".jsonl"
        rows = [dict(type="session_meta", timestamp=NOW.isoformat(),
                     payload=dict(cwd=str(cwd), id=name))]
        rows += events
        return write(self.sessions, rel, "\n".join(json.dumps(row) for row in rows) + "\n")

    def command(self, code, command, output="", ident="command-1", when=None):
        return dict(type="event_msg", timestamp=(when or NOW).isoformat(),
                    payload=dict(type="item_completed",
                                 item=dict(type="CommandExecution", id=ident, command=["powershell", "-Command", command],
                                           parsed_cmd=[dict(cmd=command)], exit_code=code, aggregated_output=output)))

    def test_redaction_and_whole_archive_rescan(self):
        write(self.root, "logs/debug-events.ndjson", json.dumps(dict(
            timestamp=NOW.isoformat(), level="ERROR",
            message="Authorization: Bearer " + "a" * 40 + "\nOPENAI_API_KEY=" + SYNTHETIC)))
        p = self.build()
        self.assertGreater(p.meta["redactions"], 0)
        for name, text in p.files.items():
            self.assertNotIn(SYNTHETIC, text)
            self.assertEqual(gp.scan_text("evidence.txt", text), [])
        # End-to-end final ZIP bytes use the same fixture.
        with mock.patch.object(gp, "run_git", return_value=None), mock.patch.object(
                ge, "collect_environment", lambda e, probe: e.add(ge.PREFIX + "environment/env.md", "# Environment\n", 100)):
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
                result = gp.main(["evidence", "--root", str(self.root), "--out", str(self.out), "--no-codex"])
        self.assertEqual(result, 0, capture.getvalue())
        with zipfile.ZipFile(next(self.out.glob("*.zip"))) as archive:
            for name in archive.namelist():
                text = archive.read(name).decode("utf-8")
                self.assertNotIn(SYNTHETIC, text)
                self.assertEqual(gp.scan_text(name.lower(), text), [])

    def test_core_payload_superset_including_vendor_bytes(self):
        for profile in ("core", "ctx", "evidence"):
            with mock.patch.object(gp, "run_git", return_value=None), mock.patch.object(
                    ge, "collect_environment", lambda e, probe: e.add(ge.PREFIX + "environment/env.md", "# Environment\n", 100)):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    result = gp.main([profile, "--root", str(self.root), "--out", str(self.out / profile), "--no-codex"])
            self.assertEqual(result, 0)
        with zipfile.ZipFile(next((self.out / "core").glob("*.zip"))) as core, zipfile.ZipFile(
                next((self.out / "evidence").glob("*.zip"))) as evidence, zipfile.ZipFile(
                next((self.out / "ctx").glob("*.zip"))) as ctx:
            source = [name for name in core.namelist() if not name.startswith("_")]
            self.assertTrue(set(source) <= set(evidence.namelist()))
            for name in source:
                self.assertEqual(core.read(name), evidence.read(name))
            self.assertNotIn("main/resources/static/vendor/lib.min.js", ctx.namelist())

    def test_missing_sources_are_warnings_and_required_documents(self):
        p = self.build()
        for name in ("GPTPRO_EVIDENCE_MANIFEST.md", "DEBUG_TIMELINE.md", "PACK_WARNINGS.txt"):
            self.assertIn(name, p.files)
        self.assertIn("not observed", p.files["PACK_WARNINGS.txt"])
        for heading in ("Package information", "Included scope", "Failure trace counts",
                        "Excerpts", "Exclusions and reasons", "Redaction counts", "Start here"):
            self.assertIn("## " + heading, p.files["GPTPRO_EVIDENCE_MANIFEST.md"])
        for category in ("git", "tests", "agent", "environment"):
            self.assertTrue(any(name.startswith(ge.PREFIX + category + "/") for name in p.files))

    def test_chat_trace_latest_v2_collects_only_pointed_bounded_export(self):
        base = "var/debug/chat-session-traces/export"
        from chat_session_debug_export import safe_export_name
        export = base + "/" + safe_export_name("a" * 12)
        pointer = dict(schema="awx.chat-session-trace-latest.v2", exportDir=export,
                       queryHash="a" * 12, queryForm="bare-hash", recordCount=1, exportedAtUtc=NOW.isoformat())
        write(self.root, base + "/latest.json", json.dumps(pointer))
        write(self.root, export + "/manifest.json", json.dumps(dict(pointer, schema="awx.chat-session-trace-export.v2")))
        write(self.root, export + "/records.json", json.dumps([
            dict(reasonCode="timeout", sessionIdHash="b" * 12, diagnostic=SYNTHETIC, padding="x" * 5000)]))
        write(self.root, base + "/unselected/records.json", "UNSELECTED_TRACE")
        write(self.root, export + "/raw/records.json", json.dumps(dict(response="PRIVATE_NESTED_RESPONSE", userText="PRIVATE_NESTED_USER")))
        write(self.root, "var/debug/chat-session-traces/raw.jsonl", "PRIVATE_RAW_SESSION")
        for cap in (3, 2, 1):
            with self.subTest(cap=cap):
                p = self.build(dict(maxDebugFiles=cap, maxFileKb=1), no_codex=True)
                traces = {n: t for n, t in p.files.items() if "chat-session-traces" in n}
                self.assertEqual(len(traces), cap)
                self.assertTrue(any("latest.json" in n for n in traces))
                self.assertTrue(all(len(t.encode("utf-8")) <= 1024 for t in traces.values()))
                text = "\n".join(p.files.values())
                self.assertNotIn(SYNTHETIC, text)
                self.assertNotIn("UNSELECTED_TRACE", text)
                self.assertNotIn("PRIVATE_RAW_SESSION", text)
                self.assertNotIn("PRIVATE_NESTED_RESPONSE", text)
                self.assertNotIn("PRIVATE_NESTED_USER", text)
                if cap == 3:
                    self.assertTrue(any("records.json" in n for n in traces))
        for bad in (dict(pointer, schema="awx.chat-session-trace-export.v1"),
                    dict(pointer, schema="awx.chat-session-trace-export.v2", queryHash="c" * 12)):
            write(self.root, export + "/manifest.json", json.dumps(bad))
            p = self.build(no_codex=True)
            self.assertFalse(any("records.json" in name and "chat-session-traces" in name for name in p.files))

    def test_missing_chat_trace_warns_without_collecting(self):
        p = self.build(no_codex=True)
        self.assertTrue(any("chat-session-traces" in warning and "not observed" in warning
                            for warning in p.files["PACK_WARNINGS.txt"].splitlines()))
        self.assertFalse(any("chat-session-traces" in name for name in p.files))

    def test_big_log_retains_late_failure_provenance_and_caps(self):
        write(self.root, "var/rag-launcher/run/spring.out.log",
              ("ordinary success line\n" * 5000) + "ERROR late exception\n" + (" at Fixture.line\n" * 60))
        p = self.build(dict(maxFileKb=2, maxEvidenceMb=0.05))
        text = "\n".join(t for n, t in p.files.items() if "spring.out.log" in n)
        self.assertIn("# EXCERPT from var/rag-launcher/run/spring.out.log lines", text)
        self.assertIn("ERROR late exception", text)
        self.assertLessEqual(sum(len(t.encode()) for t in p.files.values()), int(0.05 * 1048576))
        self.assertTrue(all(len(t.encode()) <= 2048 for t in p.files.values()))

    def test_project_cwd_only_direct_failure_and_retry_dedup(self):
        failed = self.command(7, "verify fixture", "ERROR fixture", ident="same")
        self.rollout("11111111-1111-1111-1111-111111111111", self.root, [
            failed, failed, self.command(0, "verify fixture", ident="pass"),
            dict(type="response_item", timestamp=NOW.isoformat(),
                 payload=dict(type="function_call_output", output='{"exit_code": 7, "output":"duplicate"}'))])
        self.rollout("22222222-2222-2222-2222-222222222222", self.temp / "unrelated",
                     [self.command(9, "UNRELATED-COMMAND")])
        p = self.build()
        text = p.files[ge.PREFIX + "agent/codex-project-failures.md"]
        self.assertIn("verify fixture", text)
        self.assertIn("exit code: 7", text)
        self.assertIn("retry count: 1", text)
        self.assertNotIn("UNRELATED-COMMAND", text)
        self.assertEqual(p.meta["codexFailures"], 1)
        self.assertEqual(p.meta["unrelatedSessions"], 1)
        self.assertFalse(any(name.endswith(".jsonl") for name in p.files))

    def test_foreign_worktree_requires_project_body_reference(self):
        cwd = r"C:\Users\fixture\.codex\worktrees\abc\src"
        self.rollout("33333333-3333-3333-3333-333333333333", cwd,
                     [self.command(1, "unrelated fixture")])
        self.rollout("44444444-4444-4444-4444-444444444444", cwd,
                     [self.command(1, r"verify C:\AbandonWare\demo-1\demo-1\src")])
        p = self.build()
        self.assertEqual(p.meta["codexFailures"], 1)
        self.assertEqual(p.meta["unrelatedSessions"], 1)
        self.assertNotIn(r"C:\Users\fixture", p.files[ge.PREFIX + "agent/codex-sessions-index.md"])

    def test_no_codex_never_opens_sessions(self):
        self.rollout("55555555-5555-5555-5555-555555555555", self.root,
                     [self.command(1, "PRIVATE-COMMAND")])
        p = self.build(no_codex=True)
        self.assertIn("--no-codex", p.files["PACK_WARNINGS.txt"])
        self.assertNotIn("PRIVATE-COMMAND", "\n".join(p.files.values()))

    def test_xml_failure_red_green_and_stacktrace(self):
        red = NOW - timedelta(minutes=5)
        write(self.root, "build/red/test-results/test/TEST-Fixture.xml",
              '<testsuite name="Fixture" tests="1" failures="1" errors="0" skipped="0" timestamp="' + red.isoformat() +
              '"><testcase classname="Fixture" name="works"><failure message="broken">fixture-stack</failure>'
              '<system-err>stderr-fixture</system-err></testcase></testsuite>')
        write(self.root, "build/green/test-results/test/TEST-Fixture.xml",
              '<testsuite name="Fixture" tests="1" failures="0" errors="0" skipped="0" timestamp="' + NOW.isoformat() +
              '"><testcase classname="Fixture" name="works"/></testsuite>')
        p = self.build()
        self.assertEqual(p.meta["testFailures"], 1)
        self.assertIn("Fixture.works", p.files[ge.PREFIX + "tests/red-green.md"])
        self.assertIn("fixture-stack", "\n".join(t for n, t in p.files.items() if "/failures/" in n))

    def test_old_events_excluded_and_repetitions_folded(self):
        old = NOW - timedelta(days=7)
        write(self.root, "logs/debug-events.ndjson", "\n".join([
            json.dumps(dict(timestamp=old.isoformat(), level="ERROR", message="OLD-MARKER")),
            *[json.dumps(dict(timestamp=NOW.isoformat(), level="ERROR", message="current")) for _ in range(8)]
        ]))
        p = self.build()
        text = "\n".join(t for n, t in p.files.items() if "/debug/" in n)
        self.assertNotIn("OLD-MARKER", text)
        self.assertIn("x8 repetitions", text)

    def test_second_scan_catches_patterns_outside_log_redactor(self):
        secret = "gsk_" + "Z" * 40
        write(self.root, "logs/debug-events.ndjson",
              json.dumps(dict(timestamp=NOW.isoformat(), level="ERROR", message=secret)))
        p = self.build()
        self.assertNotIn(secret, "\n".join(p.files.values()))
        self.assertGreater(p.meta["redactions"], 0)

    def test_protected_files_unopened(self):
        for leaf in (".env", "providers.json", "project-credential.json", "service-account.json", "cert.pem"):
            write(self.root, "var/agent-work-guard/" + leaf, "must-not-read")
        opened = []
        real_open = Path.open
        def record_open(path, *args, **kwargs):
            opened.append(path.name)
            return real_open(path, *args, **kwargs)
        with mock.patch.object(Path, "open", record_open):
            self.build()
        self.assertFalse(set(opened) & {".env", "providers.json", "project-credential.json", "service-account.json", "cert.pem"})

    def test_section_failure_isolated(self):
        with mock.patch.object(ge, "collect_debug", side_effect=OSError("private detail")):
            p = self.build()
        self.assertIn("debug: OSError", p.files["PACK_WARNINGS.txt"])
        self.assertNotIn("private detail", p.files["PACK_WARNINGS.txt"])
        self.assertIn(ge.PREFIX + "environment/env.md", p.files)

    def test_output_failure_does_not_count_json_mentions(self):
        code, _ = ge.output_failure(dict(output='text mentions {"exit_code": 9}'))
        self.assertIsNone(code)
        code, output = ge.output_failure(dict(output='{"exitCode": 4, "stderr":"fixture"}'))
        self.assertEqual((code, output), (4, "fixture"))

    def test_budget_removes_success_before_failure(self):
        e = ge.Evidence(self.root, dict(maxEvidenceMb=0.008, maxFileKb=4), gp.scan_text, NOW, {})
        e.add(ge.PREFIX + "debug/success.txt", "success " * 400, 10)
        e.add(ge.PREFIX + "tests/failure.txt", "ERROR stacktrace " * 240, 100)
        result = ge.finalize(e, [])
        self.assertIn(ge.PREFIX + "tests/failure.txt", result.files)
        self.assertNotIn(ge.PREFIX + "debug/success.txt", result.files)


class EvidenceV2Tests(unittest.TestCase):
    setUp = EvidenceTests.setUp
    tearDown = EvidenceTests.tearDown
    build = EvidenceTests.build
    rollout = EvidenceTests.rollout
    command = EvidenceTests.command

    def events(self, pack):
        self.assertEqual(pack.meta.get('evidence_schema_version'), 2)
        return pack.events

    def test_exit_status_beats_keywords(self):
        self.rollout('status', self.root, [self.command(0, 'git status', 'failed.py ERROR fallback')])
        events = self.events(self.build())
        self.assertEqual([e['observed_status'] for e in events if e['event_type'] == 'command'], ['success'])

    def test_native_patch_failure_is_separate(self):
        self.rollout('patch', self.root, [dict(type='event_msg', timestamp=NOW.isoformat(), payload=dict(
            type='item_completed', item=dict(type='FileChange', id='p', status='failed', changes={'x':'PRIVATE-HUNK'})))])
        p = self.build()
        self.assertEqual([(e['event_type'], e['observed_status']) for e in self.events(p)], [('patch', 'failure')])
        self.assertEqual(p.meta['codexToolFailures'], 1)

    def test_unknown_is_not_pass(self):
        self.rollout('unknown', self.root, [self.command(None, 'python -m unittest fixture', '')])
        events = self.events(self.build())
        self.assertEqual(events[0]['observed_status'], 'unknown')

    def test_nonzero_nonerror_exit_keeps_semantics(self):
        self.rollout('diff', self.root, [self.command(1, 'git diff --exit-code', 'difference')])
        events = self.events(self.build())
        self.assertEqual(events[0]['observed_status'], 'success')
        self.assertEqual(events[0]['exit_semantics'], 'differences_found')

    def test_mixed_direct_and_fallback_union(self):
        self.rollout('mixed', self.root, [self.command(7, 'verify one', ident='direct'),
            dict(type='response_item', timestamp=NOW.isoformat(), payload=dict(type='function_call', call_id='other',
                 name='exec_command', arguments=json.dumps(dict(cmd='verify two')))),
            dict(type='response_item', timestamp=NOW.isoformat(), payload=dict(type='function_call_output', call_id='other',
                 output=json.dumps(dict(exit_code=8, output='fixture'))))])
        p = self.build()
        self.assertEqual(p.meta['codexFailures'], 2)
        events = self.events(p)
        self.assertEqual(len({e['event_id'] for e in events}), 2)

    def test_lineage_requires_evidence(self):
        self.rollout('lineage', self.root, [self.command(1, 'verify same', ident='a'), self.command(0, 'verify same', ident='b')])
        events = self.events(self.build())
        self.assertFalse(any(r['basis'] == 'verified' for e in events for r in e['relations']))
        self.assertFalse(any(r['relation_type'] == 'caused_by' for e in events for r in e['relations']))

    def test_red_green_requires_same_test_selection(self):
        for directory, name, failures in [('red','one',1),('green','two',0)]:
            write(self.root, 'build/'+directory+'/test-results/test/TEST-F.xml',
                  '<testsuite name="F" tests="1" failures="'+str(failures)+'"><testcase classname="F" name="'+name+'"/></testsuite>')
        events = self.events(self.build())
        self.assertFalse(any(r['relation_type'] == 'retest' for e in events for r in e['relations']))

    def test_orphan_result_is_visible(self):
        self.rollout('orphan', self.root, [dict(type='response_item', timestamp=NOW.isoformat(), payload=dict(
            type='function_call_output', call_id='lost', output=json.dumps(dict(exit_code=2, output='fixture'))))])
        events = self.events(self.build())
        self.assertEqual(events[0]['link_status'], 'orphan_result')
        self.assertEqual(events[0]['observed_status'], 'failure')

    def test_history_window_is_bounded(self):
        e = ge.Evidence(self.root, {}, gp.scan_text, NOW, {})
        self.assertEqual(e.cfg.get('evidenceHistoryDays'), 30)
        for value in (0, -1, 2, 91):
            with self.assertRaises(ValueError):
                ge.Evidence(self.root, dict(evidenceHistoryDays=value), gp.scan_text, NOW, {})
        with self.assertRaises(ValueError):
            ge.Evidence(self.root, dict(evidenceDays=31), gp.scan_text, NOW, {})

    def test_selection_keeps_diverse_evidence(self):
        e = ge.Evidence(self.root, dict(maxEvidenceMb=0.03), gp.scan_text, NOW, {})
        for i in range(50):
            e.record('command', NOW.isoformat(), 'fixture', i, {'command':'verify '+str(i)}, 'failure')
        e.record('patch', NOW.isoformat(), 'fixture', 90, {'tool':'apply_patch'}, 'failure')
        old = NOW-timedelta(days=7)
        e.record('test_result', old.isoformat(), 'fixture', 91, {'selected_tests':['F.test']}, 'failure')
        p = ge.finalize(e, [])
        self.assertEqual({r['event_type'] for r in p.events}, {'command','patch','test_result'})

    def test_duplicate_preserves_occurrences(self):
        e = ge.Evidence(self.root, {}, gp.scan_text, NOW, {})
        for i in range(5):
            e.record('command', (NOW-timedelta(minutes=i)).isoformat(), 'fixture', i,
                     {'command':'verify','exit':1,'output':'same'}, 'failure')
        p = ge.finalize(e, [])
        self.assertEqual(len(p.events), 1)
        self.assertEqual(p.events[0].get('occurrences'), 5)

    def test_all_evidence_bytes_count_toward_budget(self):
        p = self.build(dict(maxEvidenceMb=0.02))
        self.assertIn('manifest.json', p.files)
        self.assertEqual(p.meta['bytes'], sum(len(t.encode('utf-8')) for t in p.files.values()))
        self.assertLessEqual(p.meta['bytes'], int(0.02*1048576))
        with self.assertRaises(ValueError):
            self.build(dict(maxEvidenceMb=0.001))

    def test_utf8_and_json_are_never_cut(self):
        e = ge.Evidence(self.root, dict(maxFileKb=2), gp.scan_text, NOW, {})
        e.record('command', NOW.isoformat(), 'fixture', 1, {'output':'한글'*6000}, 'failure')
        p = ge.finalize(e, [])
        self.assertIn('manifest.json', p.files)
        for name, text in p.files.items():
            if name.endswith('.json'):
                json.loads(text)
            if name.endswith('.ndjson'):
                for line in text.splitlines():
                    json.loads(line)
            self.assertNotIn('\ufffd', text)

    def test_coverage_balances_with_reasons(self):
        e = ge.Evidence(self.root, dict(maxEvidenceMb=0.016), gp.scan_text, NOW, {})
        for i in range(25):
            e.record('command', NOW.isoformat(), 'fixture', i, {'command':str(i),'output':'x'*400}, 'failure')
        p = ge.finalize(e, [])
        self.assertIn('coverage.json', p.files)
        c = json.loads(p.files['coverage.json'])['events']
        self.assertEqual(c['eligible'], c['selected']+c['deduplicated']+c['omitted'])
        self.assertGreater(c['reasons']['over_budget'], 0)

    def test_reader_and_all_index_shards_resolve(self):
        e = ge.Evidence(self.root, dict(maxFileKb=2), gp.scan_text, NOW, {})
        for i in range(45):
            e.record('command', NOW.isoformat(), 'fixture', i, {'command':'verify '+str(i)}, 'failure')
        p = ge.finalize(e, [])
        self.assertIn('00_START_HERE.md', p.files)
        m = json.loads(p.files['manifest.json'])
        self.assertGreater(len(m['event_shards']), 1)
        self.assertEqual(sum(len(p.files[n].splitlines()) for n in m['event_shards']), 45)
        self.assertTrue(all(n in p.files for n in m['event_shards']))

    def test_current_and_historical_anchors_are_distinct(self):
        p = ge.build(self.root, {}, git=lambda *a:None, scan=gp.scan_text,
                     included=[('main/java/App.java',13)], changed=['main/java/App.java'],
                     now=NOW, sessions_dir=self.sessions, probe_environment=False)
        events = self.events(p)
        current = next(r for r in events if r['event_type']=='source_change')
        self.assertEqual(current['source_anchor']['kind'], 'current')
        self.assertEqual(current['source_anchor'].get('snapshot'), p.package.get('snapshot_id'))

    def test_redaction_precedes_excerpt_and_truncation(self):
        e = ge.Evidence(self.root, dict(maxFileKb=2), gp.scan_text, NOW, {})
        e.record('command', NOW.isoformat(), 'fixture', 1, {'output':'x'*500+SYNTHETIC}, 'failure')
        p = ge.finalize(e, [])
        self.assertNotIn(SYNTHETIC, ''.join(p.files.values()))
        self.assertEqual(p.events[0].get('payload_sha256'), __import__('hashlib').sha256(
            ge.serialize({'output':e.clean('fixture', 'x'*500+SYNTHETIC)}).encode()).hexdigest())

    def test_secret_crosses_chunk_boundary(self):
        secret = 'gsk_'+'Z'*40
        path = write(self.root, 'logs/large.log', 'x'*(256*1024-12)+secret+'\n')
        e = ge.Evidence(self.root, {}, gp.scan_text, NOW, {})
        e.copy_excerpt(path, ge.PREFIX+'debug/large.txt')
        self.assertNotIn('gsk_'+ 'Z'*8, ''.join(e.files.values()))

    def test_no_raw_conversation_export(self):
        self.rollout('nested', self.root, [self.command(1, 'tool '+json.dumps({'messages':[
            {'role':'user','content':'PRIVATE-USER-CANARY'}, {'role':'system','content':'PRIVATE-SYSTEM-CANARY'}]}))])
        p = self.build()
        self.assertNotIn('PRIVATE-USER-CANARY',''.join(p.files.values()))
        self.assertNotIn('PRIVATE-SYSTEM-CANARY',''.join(p.files.values()))

    def test_archive_paths_stay_inside_roots(self):
        e = ge.Evidence(self.root, {}, gp.scan_text, NOW, {})
        for name in ('../escape.txt','C:/escape.txt','/escape.txt','safe/../escape.txt'):
            with self.assertRaises(ValueError):
                e.add(name,'fixture')
        e.add('Safe.txt','fixture')
        with self.assertRaises(ValueError):
            e.add('safe.txt','fixture')

    def test_changed_during_read_is_not_silently_packed(self):
        self.assertTrue(hasattr(gp,'capture_source'))
        real = Path.read_bytes
        path = self.root/'main/java/App.java'
        calls = [0]
        def changing(p):
            raw=real(p)
            if p==path:
                calls[0]+=1
                p.write_text('class Changed'+str(calls[0])+' {}',encoding='utf-8')
            return raw
        with mock.patch.object(Path,'read_bytes',changing):
            with self.assertRaises(ValueError):
                gp.capture_source(path)

    def test_manifest_matches_payload_hashes(self):
        p=self.build()
        self.assertIn('manifest.json',p.files)
        for entry in json.loads(p.files['manifest.json'])['payloads']:
            raw=p.files[entry['path']].encode()
            self.assertEqual(entry['bytes'],len(raw))
            self.assertEqual(entry['sha256'],__import__('hashlib').sha256(raw).hexdigest())

    def test_fixed_snapshot_is_reproducible(self):
        write(self.root,'configs/gptpro-pack.json',json.dumps({'profiles': {'core':{'include':['main/java','main/resources']},
            'ctx':{'extends':'core','context':True,'include':[]},'evidence':{'extends':'ctx','context':True,'evidence':True,'include':[]}}}))
        snapshots=[]
        with mock.patch.object(gp,'run_git',return_value=None), mock.patch.object(ge,'collect_environment',lambda e,p:None):
            for i in range(2):
                with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()),mock.patch.object(gp,'datetime') as clock:
                    clock.now.return_value=NOW
                    self.assertEqual(gp.main(['evidence','--root',str(self.root),'--out',str(self.out),'--no-codex']),0)
                snapshots.append(sorted(self.out.glob('*.zip'))[-1].read_bytes())
        self.assertEqual(snapshots[0],snapshots[1])

    def test_parallel_pack_markers_are_isolated(self):
        bat=(Path(__file__).resolve().parent.parent/'Pack-GPTPro.bat').read_text()
        self.assertNotIn('set "MARKER=%TEMP%\\gptpro_pack_last.txt"',bat)
        self.assertIn('GPTPRO_PACK_MARKER',bat)

    def test_failed_pack_cleans_only_own_partial(self):
        self.out.mkdir()
        sibling=write(self.out,'sibling.partial','keep')
        with mock.patch.object(gp,'run_git',return_value=None), mock.patch.object(ge,'collect_environment',lambda e,p:None), \
                mock.patch.object(zipfile.ZipFile,'testzip',side_effect=ValueError('fixture')):
            with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gp.main(['evidence','--root',str(self.root),'--out',str(self.out),'--no-codex']),4)
        self.assertEqual(list(self.out.glob('*.partial')),[sibling])

    def test_evidence_keeps_zip_size_warning(self):
        with mock.patch.object(gp,'run_git',return_value=None),mock.patch.object(ge,'collect_environment',lambda e,p:None):
            capture=io.StringIO()
            with contextlib.redirect_stdout(capture),contextlib.redirect_stderr(capture):
                self.assertEqual(gp.main(['evidence','--root',str(self.root),'--out',str(self.out),
                                          '--max-zip-mb','0.000001','--no-codex']),0)
        self.assertIn('WARNING: zip',capture.getvalue())

    def test_evidence_off_on_preserves_core_entries(self):
        EvidenceTests.test_core_payload_superset_including_vendor_bytes(self)

    def test_existing_defaults_and_flags_are_preserved(self):
        self.assertEqual((ge.DEFAULTS['evidenceDays'],ge.DEFAULTS['maxEvidenceMb'],ge.DEFAULTS['maxFileKb']),(3,24,512))
        with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gp.main(['evidence','--root',str(self.root),'--dry-run','--no-pause','--no-explorer',
                                     '--evidence-history-days','2']),2)

    def test_nested_prompt_and_escaped_envelope_are_omitted(self):
        for value in [json.dumps({'prompt':'PRIVATE-PROMPT-CANARY'}),json.dumps({'output':json.dumps(
                {'messages':[{'content':'PRIVATE-PROMPT-CANARY','role':'user'}]})})]:
            e=ge.Evidence(self.root,{},gp.scan_text,NOW,{})
            e.add('fixture.txt',value)
            self.assertNotIn('PRIVATE-PROMPT-CANARY',''.join(e.files.values()))

    def test_exit_semantics_require_actual_git_invocation(self):
        self.assertEqual(ge.command_status(1,'python -c "print(\'git diff --exit-code\')"')[0],'failure')

    def test_scan_file_bound_counts_opened_files(self):
        e=ge.Evidence(self.root,dict(maxScanFiles=1),gp.scan_text,NOW,{})
        self.assertIsNotNone(e.capture(self.root/'main/java/App.java'))
        self.assertIsNone(e.capture(self.root/'main/resources/application.yml'))

    def test_manifest_file_cap_includes_sharded_metadata(self):
        e=ge.Evidence(self.root,dict(maxFileKb=2),gp.scan_text,NOW,{})
        for i in range(45):
            e.record('command',NOW.isoformat(),'fixture',i,{'command':str(i)},'failure')
        p=ge.finalize(e,[])
        self.assertTrue(all(len(t.encode())<=2048 for t in p.files.values()))

    def test_explicit_success_patch_beats_quoted_error(self):
        self.rollout('patch-success',self.root,[dict(type='response_item',timestamp=NOW.isoformat(),payload=dict(
            type='custom_tool_call',call_id='patch-ok',name='apply_patch')),
            dict(type='response_item',timestamp=NOW.isoformat(),payload=dict(type='custom_tool_call_output',call_id='patch-ok',
                 output=json.dumps({'exit_code':0,'output':'old invalid patch message'})))])
        p=self.build()
        self.assertEqual(p.meta['codexToolFailures'],0)

    def test_retest_link_requires_source_receipts(self):
        for directory, failures, source in [('red',1,'before'),('green',0,'after')]:
            when=NOW-timedelta(minutes=1) if failures else NOW
            write(self.root,'build/'+directory+'/test-results/test/TEST-F.xml',
                '<testsuite name="F" tests="1" failures="'+str(failures)+'" timestamp="'+when.isoformat()+'">'
                '<properties><property name="run_id" value="run"/><property name="workdir" value="project"/>'
                '<property name="argv_hash" value="args"/><property name="source_before" value="before"/>'
                '<property name="source_after" value="'+source+'"/></properties>'
                '<testcase classname="F" name="works"/></testsuite>')
        p=self.build()
        self.assertTrue(any(r['relation_type']=='retest' and r['basis']=='verified'
                            for e in p.events for r in e['relations']))

    def test_test_receipt_zero_or_skipped_is_not_pass(self):
        self.rollout('receipt',self.root,[self.command(0,'python -m unittest F','Ran 0 tests in 0.001s\nOK')])
        p=self.build()
        self.assertEqual(p.events[0]['event_type'],'test_result')
        self.assertEqual(p.events[0]['observed_status'],'not_run')

    def test_related_history_and_byte_cap_fragment(self):
        old=NOW-timedelta(days=7)
        write(self.root,'logs/debug.ndjson',json.dumps({'timestamp':old.isoformat(),'level':'ERROR',
              'message':'main/java/App.java RELATED-HISTORY'})+'\n')
        p=ge.build(self.root,{},git=lambda *a:None,scan=gp.scan_text,included=[],changed=['main/java/App.java'],
                   now=NOW,sessions_dir=self.sessions,probe_environment=False)
        self.assertIn('RELATED-HISTORY',''.join(p.files.values()))
        e=ge.Evidence(self.root,{},gp.scan_text,NOW,{})
        path=self.root/'logs/debug.ndjson'
        rows=list(e.stream_lines(path,max_bytes=8))
        self.assertTrue(all(text=='' and partial for text,partial in rows))

    def test_discovery_cannot_starve_codex_receipts(self):
        self.rollout('quota',self.root,[self.command(1,'verify quota')])
        for i in range(20):
            write(self.root,'logs/'+str(i)+'.ndjson','{}\n')
        p=self.build(dict(maxScanFiles=8))
        self.assertTrue(any(r['event_type']=='command' for r in p.events))

    def test_successful_patch_attempt_is_visible(self):
        self.rollout('patch-complete',self.root,[dict(type='event_msg',timestamp=NOW.isoformat(),payload=dict(
            type='item_completed',item=dict(type='FileChange',id='p-ok',status='completed',changes={'x':'PRIVATE-HUNK'})))])
        p=self.build()
        self.assertTrue(any(r['event_type']=='patch' and r['observed_status']=='success' for r in p.events))

    def test_explicit_tool_error_without_exit_is_failure(self):
        row=self.command(None,'verify explicit',ident='explicit')
        row['payload']['item']['status']='failed'
        self.rollout('explicit',self.root,[row])
        p=self.build()
        self.assertEqual(p.events[0]['observed_status'],'failure')

    def test_coverage_partitions_and_clipping_are_complete(self):
        e=ge.Evidence(self.root,dict(maxFileKb=2),gp.scan_text,NOW,{})
        e.record('command',NOW.isoformat(),'fixture',1,{'output':'x'*3000},'failure')
        e.record('patch',(NOW-timedelta(days=7)).isoformat(),'fixture',2,{'tool':'apply_patch'},'failure')
        e.record('source_change',None,'fixture',3,{'path':'fixture'},'unknown')
        p=ge.finalize(e,[])
        coverage=p.meta['coverage']
        required={'discovered','eligible','selected','deduplicated','omitted','truncated','unknown'}
        for tally in [*coverage['by_kind'].values(),*coverage.get('by_window',{}).values()]:
            self.assertTrue(required <= tally.keys())
            self.assertEqual(tally['eligible'],tally['selected']+tally['deduplicated']+tally['omitted'])
        self.assertEqual(set(coverage.get('by_window',{})),{'recent','history','unknown_time'})
        self.assertEqual(coverage['events']['truncated'],1)
        self.assertEqual(coverage['by_kind']['command']['truncated'],1)
        self.assertEqual(coverage['by_window']['recent']['truncated'],1)

    def test_current_anchor_requires_included_source(self):
        p=ge.build(self.root,{},git=lambda *a:None,scan=gp.scan_text,included=[],changed=['main/java/App.java'],
                   package={'external_payloads':[]},now=NOW,sessions_dir=self.sessions,probe_environment=False)
        current=next(r for r in p.events if r['event_type']=='source_change')
        self.assertEqual(current['source_anchor'].get('status'),'current_source_missing')
        self.assertIsNone(current['source_anchor'].get('sha256'))

    def test_tiny_metadata_pages_keep_integrity(self):
        e=ge.Evidence(self.root,dict(maxFileKb=0.5,maxEvidenceMb=0.008),gp.scan_text,NOW,{})
        e.warn('fixture','x'*1500)
        p=ge.finalize(e,[])
        def resolve(value):
            if isinstance(value,list):
                return [resolve(x) for x in value]
            if not isinstance(value,dict):
                return value
            for key in ('object_parts','list_parts'):
                refs=value.get(key)
                if key+'_ref' in value:
                    refs=resolve(json.loads(p.files[value[key+'_ref']['path']]))
                if refs is not None:
                    parts=[resolve(json.loads(p.files[r['path']])) for r in refs]
                    return {k:v for part in parts for k,v in part.items()} if key=='object_parts' else [x for part in parts for x in part]
            if 'value_ref' in value:
                return resolve(json.loads(p.files[value['value_ref']['path']]))
            return {k:resolve(v) for k,v in value.items()}
        manifest=resolve(json.loads(p.files['manifest.json']))
        entries=manifest['payloads']
        for shard in manifest.get('payload_shards',[]):
            entries+=resolve(json.loads(p.files[shard['path']]))['payloads']
        for item in entries:
            self.assertEqual(item['sha256'],__import__('hashlib').sha256(p.files[item['path']].encode()).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
