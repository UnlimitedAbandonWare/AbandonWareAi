"""Concurrent Notebook/desktop contract fixtures; no live SMB source writes."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

from scripts import test_scoped_blocker_recovery as fixtures
from scripts.test_scoped_blocker_recovery import ROOT, CONTRACT, ps_quote


class NotebookConcurrencyTests(unittest.TestCase):
    ps = fixtures.ScopedBlockerTests.ps
    git = fixtures.ScopedBlockerTests.git

    def setUp(self):
        fixtures.ScopedBlockerTests.setUp(self)
        relative = '.agents/skills/demo1-macsrc-smb-direct-patch/scripts/macsrc_smb_patch_guard.ps1'
        self.guard = self.root / relative
        self.guard.parent.mkdir(parents=True)
        source = (ROOT / relative).read_text(encoding='utf-8-sig')
        lines = source.splitlines(keepends=True)
        matches = [i for i, line in enumerate(lines) if line.startswith('$script:ProductionMacSrcRoot = ')]
        self.assertEqual(len(matches), 1)
        # Fixture root identity only. Production code keeps its canonical SMB gate.
        lines[matches[0]] = ('$script:ProductionMacSrcRoot = ' + ps_quote(self.root) + '\n'
                             'function Get-CimInstance { @() }\n')
        self.guard.write_text(''.join(lines), encoding='utf-8')
        (self.root / '__patch_drop__/source_edit_lease_contract.ps1').write_bytes(CONTRACT.read_bytes())
        (self.root / 'src').mkdir()
        (self.root / 'src/A.txt').write_text('A original\n')
        (self.root / 'src/B.txt').write_text('B original\n')
        (self.root / 'boundary.txt').write_text('fixture source boundary\n')

    def guard_call(self, mode, run, target='src/A.txt', extra=''):
        args = f'-Mode {mode} -Root {ps_quote(self.root)} -RunId {run} -OwnerId owner-{run}'
        if mode == 'Prepare':
            args += f' -TargetFiles {ps_quote(target)} -BoundaryEvidenceFiles boundary.txt -WatchRoots src'
        result = self.ps(f'& {ps_quote(self.guard)} {args} {extra}; exit $LASTEXITCODE')
        try:
            row = json.loads(result.stdout)
        except ValueError:
            self.fail(result.stdout + result.stderr)
        return result.returncode, row

    def assert_ok(self, result):
        code, row = result
        self.assertEqual(code, 0, row)
        return row

    def complete(self, run, target):
        session_dir = self.root / f'data/agent-handoff/macsrc-smb-direct/{run}'
        evidence = session_dir / 'verification.json'
        evidence.write_text(json.dumps({
            'schemaVersion': 'awx.macsrc_smb_patch_verification.v1', 'runId': run,
            'sessionSha256': hashlib.sha256((session_dir / 'session.json').read_bytes()).hexdigest(),
            'exitCode': 0, 'command': 'fixture target hash assertion',
            'targetPostimages': [{'relativePath': target.replace('/', '\\'),
                                 'sha256': hashlib.sha256((self.root / target).read_bytes()).hexdigest()}],
        }))
        return self.guard_call('Complete', run, extra=f'-VerificationEvidenceFile {ps_quote(evidence.relative_to(self.root))}')

    def test_disjoint_notebook_sessions_share_watch_root_and_complete_after_peer_release(self):
        self.assert_ok(self.guard_call('Prepare', 'a'))
        self.assert_ok(self.guard_call('Prepare', 'b', 'src/B.txt'))
        self.assert_ok(self.guard_call('Verify', 'a'))
        self.assert_ok(self.guard_call('Verify', 'b'))
        (self.root / 'src/B.txt').write_text('B changed\n')
        self.assertTrue(self.assert_ok(self.complete('b', 'src/B.txt'))['leaseReleased'])
        (self.root / 'src/A.txt').write_text('A changed\n')
        self.assertTrue(self.assert_ok(self.complete('a', 'src/A.txt'))['leaseReleased'])

    def test_index_lock_does_not_block_notebook_file_only_prepare_verify_abort(self):
        lock = self.root / '.git/index.lock'
        lock.touch()
        self.assert_ok(self.guard_call('Prepare', 'index'))
        self.assert_ok(self.guard_call('Verify', 'index'))
        self.assert_ok(self.guard_call('Abort', 'index'))
        self.assertEqual(lock.read_bytes(), b'')

    def test_peer_change_does_not_require_rollback_of_untouched_own_target(self):
        self.assert_ok(self.guard_call('Prepare', 'a'))
        self.assert_ok(self.guard_call('Prepare', 'b', 'src/B.txt'))
        self.assert_ok(self.guard_call('Verify', 'b'))
        (self.root / 'src/B.txt').write_text('B changed\n')
        self.assert_ok(self.complete('b', 'src/B.txt'))
        self.assertTrue(self.assert_ok(self.guard_call('Abort', 'a'))['leaseReleased'])
        self.assertEqual((self.root / 'src/B.txt').read_text(), 'B changed\n')

    def test_changed_preimage_and_overlapping_target_remain_rejected(self):
        self.assert_ok(self.guard_call('Prepare', 'a'))
        code, row = self.guard_call('Prepare', 'overlap')
        self.assertNotEqual(code, 0)
        self.assertIn(row['failureClassification'], ['source-lease-blocked', 'source-target-overlap'])
        (self.root / 'src/A.txt').write_text('changed by another writer\n')
        code, row = self.guard_call('Verify', 'a')
        self.assertNotEqual(code, 0)
        self.assertEqual(row['failureClassification'], 'preimage-changed')

    def test_unrelated_queue_changes_do_not_invalidate_target_verification(self):
        self.assert_ok(self.guard_call('Prepare', 'queue'))
        patch = self.root / '__patch_drop__/other-v3.patch'
        patch.write_text('diff --git a/src/B.txt b/src/B.txt\n--- a/src/B.txt\n+++ b/src/B.txt\n@@ -1 +1 @@\n-old\n+new\n')
        self.assert_ok(self.guard_call('Verify', 'queue'))
        self.assert_ok(self.guard_call('Abort', 'queue'))

    def test_existing_notebook_contracts_in_isolated_root(self):
        for relative in ['.agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md',
                         '.agents/skills/demo1-macsrc-smb-direct-patch/agents/openai.yaml']:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT / relative).read_bytes())
        (self.root / 'build.gradle.kts').write_text('// fixture boundary\n')
        command = "$env:PSModulePath=(Join-Path $PSHOME 'Modules'); Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force; "
        command += f"& {ps_quote(ROOT / 'scripts/demo1_macsrc_smb_direct_patch_contract_tests.ps1')} -Root {ps_quote(self.root)}; exit $LASTEXITCODE"
        result = subprocess.run(
            ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
            env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
        failures = [line for line in result.stdout.splitlines() if '[FAIL]' in line or '[SUMMARY]' in line]
        self.assertEqual(result.returncode, 0, '\n'.join(failures) + result.stderr[-1500:])
        print('\n'.join(line for line in failures if '[SUMMARY]' in line))


if __name__ == '__main__':
    unittest.main()
