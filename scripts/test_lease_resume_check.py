"""Tests for lease_resume_check.py — lease 대기 후 재개 판정 (S8 a-e).

All fixtures are synthetic temp dirs: before.json + before/*.bin + 대상 파일.
네트워크·실제 lease·git 의존 없음.
"""
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "lease_resume_check.py"
SPEC = importlib.util.spec_from_file_location("lease_resume_check", SCRIPT)
LRC = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("lease_resume_check", LRC)
SPEC.loader.exec_module(LRC)


def make_fixture(td, files):
    """files: {rel: before_content} — before.json + snapshot + live 파일 생성."""
    td = Path(td)
    root = td / "root"
    wdir = td / "wait" / "w1"
    (wdir / "before").mkdir(parents=True)
    targets = []
    for i, (rel, content) in enumerate(files.items()):
        data = content.encode("utf-8")
        fp = root / rel
        fp.parent.mkdir(parents=True, exist_ok=True)
        fp.write_bytes(data)
        name = "%02d-%s" % (i, hashlib.sha256(
            rel.encode("utf-8")).hexdigest()[:8])
        (wdir / "before" / name).write_bytes(data)
        targets.append({"path": rel, "exists": True,
                        "sha256": hashlib.sha256(data).hexdigest(),
                        "size": len(data), "mtimeUtc": None,
                        "gitBlob": None, "snapshot": "before/" + name})
    (wdir / "before.json").write_text(json.dumps({
        "schemaVersion": "awx.lease-wait-before.v1", "waitId": "w1",
        "task": "t1", "createdAtUtc": "2026-10-09T00:00:00+00:00",
        "root": str(root.resolve()), "targets": targets}), encoding="utf-8")
    return wdir / "before.json", root


TEN_LINES = "\n".join("line-%02d" % i for i in range(1, 11)) + "\n"


def gate_fixture(root, value="old\n", revision=1, extra_inputs=()):
    """Real doctor, real goal assertion, and freshly recorded JUnit; no validator mock."""
    import codex_auto_unblock as cau
    import run_verified_command as runner
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    (root / "target.txt").write_text(value, encoding="utf-8")
    (root / "config.json").write_text('{}\n', encoding="utf-8")
    for name in extra_inputs:
        (root / name).write_bytes(b'dependency=1\n')
    (root / "check.py").write_text(
        "from pathlib import Path\nimport json\nimport xml.etree.ElementTree as ET\n"
        "ok = Path('target.txt').read_text() == 'fixed\\n' and json.loads(Path('config.json').read_text()) == {} "
        + "and all(Path(p).read_text() == 'dependency=1\\n' for p in " + repr(list(extra_inputs)) + ")\n"
        "suite = ET.Element('testsuite', name='resume.Goal', tests='1', failures=str(int(not ok)), errors='0', skipped='0')\n"
        "case = ET.SubElement(suite, 'testcase', name='goal_is_satisfied')\n"
        "if not ok: ET.SubElement(case, 'failure', message='goal assertion failed')\n"
        "ET.ElementTree(suite).write('TEST-resume.Goal.xml', encoding='utf-8')\n"
        "raise SystemExit(0 if ok else 1)\n", encoding="utf-8")
    before = cau.snapshot_before(root, ['target.txt'], root / 'var/wait', 'w1', task='resume-task',
        context={'goalRevision': 'g1', 'planRevision': 1, 'inputPaths': ['config.json', 'check.py'] + list(extra_inputs)})
    patch_text = '*** Begin Patch\n*** Update File: target.txt\n@@\n-old\n+fixed\n*** End Patch\n'
    receipt = {'schema': 'awx.source-edit-acquired.v1', 'acquired': True, 'taskId': 'resume-task',
        'root': str(root), 'leaseId': 'c'*32, 'fingerprint': 'a'*64, 'manifestHash': 'b'*64,
        'writePaths': ['target.txt']}
    argv = [str(Path(sys.executable).resolve()), '-B', 'check.py']
    contract = {'schemaVersion': 'awx.request-contract.v1', 'taskId': 'resume-task', 'revision': revision,
        'instructionRef': 'fixture:resume-goal', 'goal': 'target is fixed with unchanged configuration',
        'knowledge': [{'id': 'observed', 'kind': 'fact', 'summary': 'synthetic target and goal assertion',
                       'sourceRef': 'fixture:observed-source'}],
        'stages': [{'id': 'resume', 'dependsOn': [], 'requiredKnowledge': ['observed'],
            'inputs': [{'name': 'target', 'type': 'file', 'knowledgeRef': 'observed'}],
            'outputs': [{'name': 'goal', 'type': 'assertion', 'knowledgeRef': 'observed'}],
            'api': {'applicable': False, 'reason': 'synthetic local command', 'knowledgeRef': 'observed'},
            'errors': [], 'successTests': [{'id': 'goal', 'commandId': 'focused',
                'expectation': 'real target assertion', 'knowledgeRef': 'observed',
                'argvSha256': hashlib.sha256(json.dumps(argv).encode()).hexdigest(),
                'expectedSuites': ['resume.Goal']}], 'steps': ['read current inputs and test the goal'],
            'sourceFiles': ['target.txt', 'config.json'] + list(extra_inputs), 'testFiles': ['check.py']}]}
    (root / 'request-contract.json').write_text(json.dumps(contract), encoding='utf-8')
    checked = runner.check_request_contract(contract, root=root)
    if checked['status'] != 'READY':
        raise AssertionError(checked)
    before = Path(before)
    baseline_sha = hashlib.sha256(before.read_bytes()).hexdigest()
    args = [before, baseline_sha, root, 'resume-task', 'g1', str(revision), receipt,
            hashlib.sha256(patch_text.encode()).hexdigest()]
    current = LRC.check_gate(*args)
    planned = datetime.now(timezone.utc).isoformat()
    phase = 'GREEN' if value == 'fixed\n' else 'RED'
    run = runner.run(argv, root, root / 'verification', suites=['resume.Goal'], xml_dir=root,
        sources=['target.txt', 'config.json', 'check.py'] + list(extra_inputs), contract='request-contract.json',
        stage='resume', phase=phase, command_id='focused')
    if run['verificationPhaseOutcome'] != phase:
        raise AssertionError(run['failures'])
    decision = {'action': 'SKIP_ALREADY_DONE' if phase == 'GREEN' else 'APPLY',
        'goalRevision': 'g1', 'planRevision': str(revision), 'inputDigest': current['inputDigest'],
        'patchSha256': args[-1], 'rereadPaths': ['target.txt', 'config.json', 'check.py'] + list(extra_inputs),
        'plannedAtUtc': planned, 'verificationOutput': 'verification',
        'verificationBinding': run['contractBinding']}
    return args, decision, patch_text


class ResumeGateTest(unittest.TestCase):
    def test_real_filesystem_rename_blocks_old_path_without_recreation(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            old, moved = Path(td) / 'target.txt', Path(td) / 'moved.txt'
            original = old.read_bytes()
            old.rename(moved)
            proof = LRC.check_gate(*args, decision=decision)
            self.assertFalse(proof['resumeAllowed'])
            self.assertEqual(proof['reason'], 'path-reevaluation-required')
            self.assertFalse(old.exists())
            self.assertEqual(moved.read_bytes(), original)

    def test_related_test_or_dependency_change_invalidates_current_green_with_target_unchanged(self):
        for name in ['check.py', 'dependency.lock']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as td:
                args, decision, _ = gate_fixture(td, value='fixed\n', extra_inputs=['dependency.lock'])
                self.assertTrue(LRC.check_gate(*args, decision=decision)['resumeAllowed'])
                root = Path(td)
                target = (root / 'target.txt').read_bytes()
                path = root / name
                path.write_bytes(path.read_bytes() + (b'\n# changed check\n' if name == 'check.py' else b'dependency=2\n'))
                self.assertFalse(LRC.check_gate(*args, decision=decision)['resumeAllowed'])
                self.assertEqual((root / 'target.txt').read_bytes(), target)

    def test_real_current_red_authorizes_one_apply(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            proof = LRC.check_gate(*args, decision=decision)
            self.assertTrue(proof['resumeAllowed'], proof)
            self.assertEqual(proof['status'], 'APPLY')
            self.assertEqual((Path(td) / 'target.txt').read_text(), 'old\n')

    def test_real_current_green_authorizes_only_already_done(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td, value='fixed\n')
            proof = LRC.check_gate(*args, decision=decision)
            self.assertTrue(proof['resumeAllowed'], proof)
            self.assertEqual(proof['status'], 'SKIP_ALREADY_DONE')
            decision['action'] = 'APPLY'
            self.assertFalse(LRC.check_gate(*args, decision=decision)['resumeAllowed'])

    def test_baseline_context_and_receipt_drift_never_authorize(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            for index, wrong in [(1, '0'*64), (3, 'other-task'), (4, 'other-goal'),
                                 (5, '2'), (7, 'f'*64)]:
                with self.subTest(index=index):
                    changed = list(args)
                    changed[index] = wrong
                    self.assertFalse(LRC.check_gate(*changed, decision=decision)['resumeAllowed'])
            for key, wrong in [('taskId', 'other-task'), ('writePaths', ['other.txt']), ('leaseId', '')]:
                with self.subTest(key=key):
                    changed = list(args)
                    changed[6] = {**args[6], key: wrong}
                    self.assertFalse(LRC.check_gate(*changed, decision=decision)['resumeAllowed'])

    def test_config_drift_and_same_bytes_recreation_invalidate_real_receipt(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            config = Path(td) / 'config.json'
            config.write_text('{"peer":true}\n')
            self.assertFalse(LRC.check_gate(*args, decision=decision)['resumeAllowed'])
            config.write_text('{}\n')
            self.assertFalse(LRC.check_gate(*args, decision=decision)['resumeAllowed'])
            target = Path(td) / 'target.txt'
            target.rename(Path(td) / 'saved.txt')
            target.write_text('old\n')
            self.assertEqual(LRC.check_gate(*args, decision=decision)['reason'], 'path-reevaluation-required')

    def test_changed_inputs_require_new_plan_and_current_rerun(self):
        import run_verified_command as runner
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            root = Path(td)
            (root / 'target.txt').write_text('fixed\n')
            current = LRC.check_gate(*args)
            decision.update(inputDigest=current['inputDigest'], action='SKIP_ALREADY_DONE')
            self.assertEqual(LRC.check_gate(*args, decision=decision)['reason'], 'changed-input-requires-new-plan')
            contract = json.loads((root / 'request-contract.json').read_text())
            contract['revision'] = 2
            (root / 'request-contract.json').write_text(json.dumps(contract))
            args[5] = '2'
            decision.update(planRevision='2', plannedAtUtc=datetime.now(timezone.utc).isoformat())
            run = runner.run([str(Path(sys.executable).resolve()), '-B', 'check.py'], root, root / 'verification-new',
                suites=['resume.Goal'], xml_dir=root, sources=['target.txt', 'config.json', 'check.py'],
                contract='request-contract.json', stage='resume', phase='GREEN', command_id='focused')
            decision.update(verificationOutput='verification-new', verificationBinding=run['contractBinding'])
            proof = LRC.check_gate(*args, decision=decision)
            self.assertTrue(proof['resumeAllowed'], proof)

    def test_missing_reread_and_verification_predating_plan_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            args, decision, _ = gate_fixture(td)
            decision['rereadPaths'] = ['target.txt']
            self.assertEqual(LRC.check_gate(*args, decision=decision)['reason'], 'related-input-reread-required')
            decision['rereadPaths'] = ['target.txt', 'config.json', 'check.py']
            decision['plannedAtUtc'] = datetime.now(timezone.utc).isoformat()
            self.assertEqual(LRC.check_gate(*args, decision=decision)['reason'], 'verification-predates-current-plan')


class ResumeCheckTest(unittest.TestCase):
    def test_a_unchanged_exit0(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": TEN_LINES})
            rep = LRC.check(before, root=str(root))
        self.assertEqual(0, rep["exitCode"])
        self.assertEqual("UNCHANGED", rep["results"][0]["verdict"])
        self.assertEqual("continue", rep["results"][0]["nextAction"])

    def test_b_disjoint_requires_replan_exit10(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": TEN_LINES})
            lines = TEN_LINES.splitlines(keepends=True)
            lines[4] = "changed-by-peer\n"          # 5번 줄만 변경
            (root / "f.py").write_text("".join(lines), encoding="utf-8")
            rep = LRC.check(before, root=str(root),
                            hunks=["f.py:8-10"])     # 내 범위 8-10과 비겹침
        self.assertEqual(10, rep["exitCode"])
        r = rep["results"][0]
        self.assertEqual("CHANGED_DISJOINT", r["verdict"])
        self.assertEqual("reread-replan-auto-resume", r["nextAction"])
        self.assertEqual([[5, 5]], r["changedBeforeRanges"])

    def test_c_overlap_exit10(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": TEN_LINES})
            lines = TEN_LINES.splitlines(keepends=True)
            lines[4] = "changed-by-peer\n"
            (root / "f.py").write_text("".join(lines), encoding="utf-8")
            rep = LRC.check(before, root=str(root), hunks=["f.py:3-7"])
        self.assertEqual(10, rep["exitCode"])
        r = rep["results"][0]
        self.assertEqual("CHANGED_OVERLAP", r["verdict"])
        self.assertEqual([[3, 7]], r["overlapHunks"])
        self.assertEqual("reread-replan-auto-resume", r["nextAction"])

    def test_absent_input_creation_is_not_an_unchanged_resume(self):
        import codex_auto_unblock as cau
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "root"
            root.mkdir()
            before = cau.snapshot_before(root, ["new.txt"], Path(td) / "wait", "w1", task="t1")
            self.assertEqual(LRC.check(before, root=root)["results"][0]["verdict"], "UNCHANGED")
            (root / "new.txt").write_bytes(b"foreign\n")
            result = LRC.check(before, root=root)
            self.assertEqual(result["results"][0]["verdict"], "NEWLY_CREATED")
            self.assertEqual(result["exitCode"], 20)

    def test_identical_bytes_at_recreated_path_require_path_reevaluation(self):
        import codex_auto_unblock as cau
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "root"
            root.mkdir()
            target = root / "f.py"
            target.write_bytes(b"same\n")
            before = cau.snapshot_before(root, ["f.py"], Path(td) / "wait", "w1", task="t1")
            target.rename(root / "old-f.py")
            target.write_bytes(b"same\n")
            result = LRC.check(before, root=root)
            self.assertEqual(result["results"][0]["verdict"], "RECREATED")
            self.assertEqual(result["exitCode"], 20)

    def test_read_denial_is_unknown_not_gone(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": "same\n"})
            original_read = Path.read_bytes

            def deny_target(path):
                if path == root / "f.py":
                    raise PermissionError("synthetic denied input")
                return original_read(path)

            with patch.object(Path, "read_bytes", deny_target):
                result = LRC.check(before, root=root)
            self.assertEqual(result["results"][0]["verdict"], "UNKNOWN")
            self.assertEqual(result["exitCode"], 30)

    def test_c2_no_hunks_is_conservative_overlap(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": TEN_LINES})
            (root / "f.py").write_text(TEN_LINES + "peer-added\n",
                                     encoding="utf-8")
            rep = LRC.check(before, root=str(root))
        self.assertEqual(10, rep["exitCode"])
        self.assertEqual("no-hunks-declared", rep["results"][0]["note"])

    def test_d_gone_exit20_rest_continues(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(
                td, {"gone.py": "x\n", "stay.py": TEN_LINES})
            (root / "gone.py").unlink()
            rep = LRC.check(before, root=str(root))
        self.assertEqual(20, rep["exitCode"])
        by_path = {r["path"]: r for r in rep["results"]}
        self.assertEqual("GONE", by_path["gone.py"]["verdict"])
        self.assertEqual("UNCHANGED", by_path["stay.py"]["verdict"])

    def test_e_help_survives_cp949(self):
        raw = io.BytesIO()
        stream = io.TextIOWrapper(raw, encoding="cp949", errors="strict")
        old = sys.stdout
        try:
            sys.stdout = stream
            with self.assertRaises(SystemExit) as cm:
                LRC.main(["--help"])
            stream.flush()
        finally:
            sys.stdout = old
        self.assertEqual(0, cm.exception.code)
        self.assertIn(b"--before", raw.getvalue())

    def test_mixed_worst_wins(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(
                td, {"a.py": "a\n", "b.py": TEN_LINES})
            (root / "a.py").unlink()                          # GONE
            lines = TEN_LINES.splitlines(keepends=True)
            lines[0] = "peer\n"
            (root / "b.py").write_text("".join(lines),
                                       encoding="utf-8")      # OVERLAP(무hunks)
            rep = LRC.check(before, root=str(root))
        self.assertEqual(20, rep["exitCode"])
        self.assertEqual(1, rep["counts"]["GONE"])
        self.assertEqual(1, rep["counts"]["CHANGED_OVERLAP"])

    def test_show_diff_bounded(self):
        with tempfile.TemporaryDirectory() as td:
            before, root = make_fixture(td, {"f.py": TEN_LINES})
            lines = TEN_LINES.splitlines(keepends=True)
            lines[9] = "tail\n"
            (root / "f.py").write_text("".join(lines), encoding="utf-8")
            rep = LRC.check(before, root=str(root), show_diff=True)
        r = rep["results"][0]
        self.assertIn("diff", r)
        self.assertTrue(any("-line-10" == d.strip() for d in r["diff"]))


if __name__ == "__main__":
    unittest.main()
