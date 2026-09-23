"""Lease lifetime integration tests; every mutation is inside a temporary repo."""
import hashlib
import json
import os
import subprocess
import sys
import shutil
import datetime
import unittest

import test_scoped_blocker_recovery as scoped
from test_scoped_blocker_recovery import ps_quote, SESSION
import test_concurrent_source_edit as notebook


class LeaseLifecycleTests(unittest.TestCase):
    setUp = scoped.ScopedBlockerTests.setUp
    git = scoped.ScopedBlockerTests.git
    ps = scoped.ScopedBlockerTests.ps
    contract = scoped.ScopedBlockerTests.contract
    session = scoped.ScopedBlockerTests.session

    def lease_path(self, topic='fixture'):
        return self.root / f'__patch_drop__/source-edit-locks/{topic}.lock/lease.json'

    def begin(self, extra=''):
        result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)} {extra}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.lease_path()

    def expire(self, path):
        row = json.loads(path.read_text(encoding='utf-8-sig'))
        row['expiresAtUtc'] = '2000-01-01T00:00:00Z'
        path.write_text(json.dumps(row), encoding='utf-8')

    def test_expired_unknown_owner_still_blocks_only_its_targets(self):
        path = self.begin()
        self.expire(path)
        result = self.session(topic='peer', extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        other = self.root / 'other.json'
        other.write_text(json.dumps({'targets': [{'path': 'other.txt', 'sha256': None}]}))
        result = self.session(topic='other', extra=f'-TargetManifest {ps_quote(other)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(path.exists())

    def test_live_owner_is_not_recovered_and_heartbeat_preserves_fingerprint(self):
        path = self.begin(f'-OwnerProcessId {os.getpid()} -TaskId lifetime-fixture')
        before = path.read_bytes()
        fingerprint = hashlib.sha256(before).hexdigest()
        result = self.session(action='heartbeat', extra=f'-LeaseFingerprint {fingerprint} -TtlMinutes 30')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(path.read_bytes(), before)
        repeated = self.session(action='heartbeat', extra=f'-LeaseFingerprint {fingerprint} -TtlMinutes 30')
        self.assertEqual(repeated.returncode, 0, repeated.stdout + repeated.stderr)
        self.assertEqual(path.read_bytes(), before)
        result = self.session(action='recover', extra='-Json')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)['recoveredCount'], 0)
        result = self.session(action='status', extra='-Json')
        row = json.loads(result.stdout)['sourceLeases'][0]
        self.assertEqual(row['ownerState'], 'alive')
        self.assertEqual(row['ownerProcessId'], os.getpid())
        self.assertEqual(row['targetPaths'], ['target.txt'])
        self.assertEqual(row['heartbeatState'], 'valid')
        self.assertNotIn('fixture-owner', result.stdout)

    def test_dead_owner_is_quarantined_before_same_topic_reacquire(self):
        # The child supervises its own begin helper, then dies. An unrelated PID
        # is deliberately not accepted as evidence of the session owner.
        command = "Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1'); function Get-CimInstance { @() }; "
        command += f'& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)} -OwnerProcessId '
        code = 'import os,subprocess,sys,json\nr=subprocess.run(["powershell","-NoProfile","-ExecutionPolicy","Bypass","-Command",sys.argv[1]+str(os.getpid())],capture_output=True,text=True);print(json.dumps({"code":r.returncode,"output":r.stdout+r.stderr}),flush=True);sys.stdin.readline()'
        child = subprocess.Popen([sys.executable, '-u', '-c', code, command], env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            startup = json.loads(child.stdout.readline())
            self.assertEqual(startup['code'], 0, startup['output'])
            path = self.lease_path()
            before = path.read_bytes()
        finally:
            child.terminate()
            child.wait(timeout=10)
            child.stdin.close()
            child.stdout.close()
        self.begin()
        self.assertNotEqual(path.read_bytes(), before)
        archives = list((self.root / '__patch_drop__/source-edit-quarantine').glob('*/lease/lease.json'))
        self.assertEqual(len(archives), 1)
        self.assertEqual(archives[0].read_bytes(), before)
        receipt = json.loads((archives[0].parent.parent / 'receipt.json').read_text())
        self.assertEqual(receipt['state'], 'quarantined')
        self.assertEqual(receipt['reason'], 'owner-process-exited')

    def test_unrelated_live_pid_cannot_be_registered_as_owner(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(60)'])
        try:
            result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)} -OwnerProcessId {child.pid}')
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.lease_path().exists())
        finally:
            child.terminate()
            child.wait(timeout=10)

    def test_remote_expired_owner_is_preserved_even_when_pid_does_not_exist_locally(self):
        path = self.begin()
        row = json.loads(path.read_text())
        row.update(ownerProcessId=2147483646, ownerHostHash='0' * 64, ownerProcessStartedAtUtc='2000-01-01T00:00:00Z', expiresAtUtc='2001-01-01T00:00:00Z')
        path.write_text(json.dumps(row))
        before = path.read_bytes()
        result = self.session(action='recover', extra='-Json')
        self.assertEqual(json.loads(result.stdout)['recoveredCount'], 0)
        self.assertEqual(path.read_bytes(), before)

    def test_foreign_heartbeat_does_not_extend_expired_reservation_and_status_is_read_only(self):
        path = self.begin()
        self.expire(path)
        row = json.loads(path.read_text())
        heartbeat = self.root / f'__patch_drop__/source-edit-heartbeats/{row["leaseId"]}.json'
        heartbeat.parent.mkdir()
        heartbeat.write_text(json.dumps({'leaseId':row['leaseId'], 'leaseFingerprint':'0'*64, 'renewedAtUtc':'2026-01-01T00:00:00Z','expiresAtUtc':'2099-01-01T00:00:00Z'}))
        before = {str(p):p.read_bytes() for p in (self.root / '__patch_drop__').rglob('*') if p.is_file()}
        result = self.session(action='status', extra='-Json')
        status = json.loads(result.stdout)['sourceLeases'][0]
        self.assertEqual(status['status'], 'expired')
        self.assertEqual(status['heartbeatState'], 'invalid')
        self.assertEqual(before, {str(p):p.read_bytes() for p in (self.root / '__patch_drop__').rglob('*') if p.is_file()})

    def test_prefix_reservation_blocks_child_but_not_sibling_and_grants_no_write(self):
        manifest = json.loads(self.manifest.read_text())
        manifest['reservePaths'] = ['Module/./area']
        self.manifest.write_text(json.dumps(manifest))
        self.begin()
        for topic, target, expected in [('child', 'module/area/new.txt', 7), ('sibling', 'module/area2/new.txt', 0)]:
            other = self.root / f'{topic}.json'
            other.write_text(json.dumps({'targets': [{'path': target, 'sha256': None}]}))
            result = self.session(topic=topic, extra=f'-TargetManifest {ps_quote(other)}')
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        self.assertFalse((self.root / 'module').exists())

    def test_json_targeted_status_reports_conflict_paths_and_nonzero_exit(self):
        self.begin()
        result = self.session(action='status', extra=f'-Json -TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        row = json.loads(result.stdout)
        self.assertEqual(row['targetConflict']['conflictingPaths'], ['target.txt'])
        self.assertEqual(row['sourceLeases'][0]['targetPaths'], ['target.txt'])

    @unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is not installed')
    def test_powershell7_preserves_owner_start_identity_and_utc_expiry(self):
        def run(action, extra=''):
            code = f'function Get-CimInstance {{ @() }}; & {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action {action} -Topic fixture -OwnerId fixture-owner {extra}; exit $LASTEXITCODE'
            return subprocess.run(['pwsh', '-NoProfile', '-Command', code], env=self.env, capture_output=True, text=True, errors='replace', timeout=35)
        begin = run('begin', f'-OwnerProcessId {os.getpid()} -TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(begin.returncode, 0, begin.stdout + begin.stderr)
        before = self.lease_path().read_bytes()
        lease = json.loads(before)
        status = run('status', '-Json')
        row = json.loads(status.stdout)['sourceLeases'][0]
        self.assertEqual(row['ownerState'], 'alive', row)
        self.assertEqual(datetime.datetime.fromisoformat(row['expiresAtUtc']), datetime.datetime.fromisoformat(lease['expiresAtUtc']))
        heartbeat = run('heartbeat', f'-LeaseFingerprint {hashlib.sha256(before).hexdigest()} -TtlMinutes 30')
        self.assertEqual(heartbeat.returncode, 0, heartbeat.stdout + heartbeat.stderr)
        renewed = json.loads(run('status', '-Json').stdout)['sourceLeases'][0]
        self.assertEqual(renewed['ownerState'], 'alive')
        self.assertEqual(renewed['heartbeatState'], 'valid')
        recovery = run('recover', '-Json')
        self.assertEqual(json.loads(recovery.stdout)['recoveredCount'], 0)
        self.assertEqual(self.lease_path().read_bytes(), before)


class NotebookLifetimeTests(unittest.TestCase):
    def setUp(self):
        self.fixture = notebook.NotebookConcurrencyTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root

    def guard_call(self, *args, **kwargs):
        return self.fixture.guard_call(*args, **kwargs)
    git = scoped.ScopedBlockerTests.git
    ps = scoped.ScopedBlockerTests.ps
    assert_ok = notebook.NotebookConcurrencyTests.assert_ok

    @unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is not installed')
    def test_notebook_powershell7_lifetime_transitions_preserve_identity(self):
        self.fixture.ps = lambda code: subprocess.run(
            ['pwsh', '-NoProfile', '-Command', "$ErrorActionPreference='Stop'; " + code],
            env=self.fixture.env, capture_output=True, text=True, errors='replace', timeout=35)
        self.assert_ok(self.guard_call('Prepare', 'ps7', extra=f'-OwnerProcessId {os.getpid()}'))
        self.assert_ok(self.guard_call('Heartbeat', 'ps7'))
        self.assert_ok(self.guard_call('Verify', 'ps7'))
        self.assert_ok(self.guard_call('Abort', 'ps7'))

    def test_notebook_heartbeat_keeps_session_and_lease_immutable(self):
        self.assert_ok(self.guard_call('Prepare', 'renew', extra=f'-OwnerProcessId {os.getpid()}'))
        lease = self.root / '__patch_drop__/source-edit-locks/macsrc-renew.lock/lease.json'
        session = self.root / 'data/agent-handoff/macsrc-smb-direct/renew/session.json'
        before = (lease.read_bytes(), session.read_bytes())
        self.assert_ok(self.guard_call('Heartbeat', 'renew'))
        self.assert_ok(self.guard_call('Heartbeat', 'renew'))
        self.assertEqual((lease.read_bytes(), session.read_bytes()), before)
        self.assert_ok(self.guard_call('Verify', 'renew'))
        self.assert_ok(self.guard_call('Abort', 'renew'))

    def test_abort_preserves_unexpected_lock_directory_contents(self):
        self.assert_ok(self.guard_call('Prepare', 'inventory'))
        directory = self.root / '__patch_drop__/source-edit-locks/macsrc-inventory.lock'
        unexpected = directory / 'another-owner-evidence.txt'
        unexpected.write_text('preserve this evidence')
        code, row = self.guard_call('Abort', 'inventory')
        self.assertNotEqual(code, 0, row)
        self.assertEqual(unexpected.read_text(), 'preserve this evidence')
        self.assertTrue((directory / 'lease.json').exists())

    def test_old_directory_does_not_delete_valid_active_lease(self):
        self.assert_ok(self.guard_call('Prepare', 'old'))
        path = self.root / '__patch_drop__/source-edit-locks/macsrc-old.lock'
        before = (path / 'lease.json').read_bytes()
        os.utime(path, (1, 1))
        second = self.assert_ok(self.guard_call('Prepare', 'peer', 'src/B.txt'))
        self.assertEqual(second['expiredLeaseCleanupDeletedCount'], 0)
        self.assertEqual((path / 'lease.json').read_bytes(), before)
        self.assert_ok(self.guard_call('Verify', 'old'))
        self.assert_ok(self.guard_call('Verify', 'peer'))


if __name__ == '__main__':
    unittest.main()
