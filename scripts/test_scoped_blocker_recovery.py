"""Real temporary Git repositories exercise scoped guards, never the live index."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "__patch_drop__/source_edit_lease_contract.ps1"
SESSION = ROOT / "__patch_drop__/source_edit_session.ps1"


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


class ScopedBlockerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-scoped-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {**os.environ, "GIT_OPTIONAL_LOCKS": "0"}
        self.env.pop("GIT_INDEX_FILE", None)
        # A Python child inherits PowerShell 7 module paths unchanged; let the
        # Windows PowerShell child construct its own compatible module path.
        self.env.pop("PSModulePath", None)
        self.git("init", "--quiet")
        (self.root / "__patch_drop__").mkdir()
        (self.root / "target.txt").write_text("user preimage\n")
        self.manifest = self.root / "targets.json"
        self.manifest.write_text(json.dumps({"targets": [{"path": "target.txt", "sha256": hashlib.sha256((self.root / "target.txt").read_bytes()).hexdigest()}]}))

    def git(self, *args):
        return subprocess.run(["git", "--no-optional-locks", "-C", str(self.root), *args], env=self.env, capture_output=True, check=True, timeout=20)

    def ps(self, code):
        bootstrap = "$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force; "
        # Arrange the fixture's process inventory. Unrelated desktop Git readers
        # otherwise race this test; Git paths, locks and lease I/O remain real.
        bootstrap += "function Get-CimInstance { @() }; "
        return subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", bootstrap + code], env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35)

    def contract(self, code):
        result = self.ps(f"$ErrorActionPreference='Stop'; . {ps_quote(CONTRACT)}; " + code)
        self.assertEqual(result.returncode, 0, result.stderr[-1500:])
        return json.loads(result.stdout)

    def session(self, action="begin", topic="fixture", extra=""):
        return self.ps(f"& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action {action} -Topic {topic} -OwnerId fixture-owner {extra}; exit $LASTEXITCODE")

    def test_unknown_zero_byte_lock_allows_guarded_worktree_lease_and_is_preserved(self):
        lock = self.root / ".git/index.lock"
        lock.touch()
        result = self.session(extra=f"-TargetManifest {ps_quote(self.manifest)}")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(lock.read_bytes(), b"")
        self.assertTrue((self.root / "__patch_drop__/source-edit-locks/fixture.lock/lease.json").exists())

    def test_legacy_unscoped_request_still_holds_unknown_lock(self):
        (self.root / ".git/index.lock").touch()
        result = self.session()
        self.assertEqual(result.returncode, 6)
        self.assertIn("index-lock-conflict", result.stdout)

    def test_real_lease_collision_blocks_second_writer(self):
        first = self.session()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        second = self.session(topic="another")
        self.assertEqual(second.returncode, 7)

    def test_disjoint_target_sessions_begin_verify_and_end_independently(self):
        first = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        other = self.root / 'other-targets.json'
        other.write_text(json.dumps({'targets': [{'path': 'new-directive.md', 'sha256': None}]}))
        second = self.session(topic='another', extra=f'-TargetManifest {ps_quote(other)}')
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        for topic, manifest in [('fixture', self.manifest), ('another', other)]:
            result = self.session(action='verify', topic=topic, extra=f'-TargetManifest {ps_quote(manifest)}')
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.session(action='end').returncode, 0)
        self.assertEqual(self.session(action='verify', topic='another', extra=f'-TargetManifest {ps_quote(other)}').returncode, 0)

    def test_same_target_sessions_still_reject_overlap(self):
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        second = self.session(topic='another', extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(second.returncode, 7)
        self.assertIn('source-target-overlap', second.stdout)

    def test_parallel_admission_serializes_only_registration_and_allows_disjoint_work(self):
        from concurrent.futures import ThreadPoolExecutor
        other = self.root / 'parallel-targets.json'
        other.write_text(json.dumps({'targets': [{'path': 'parallel.txt', 'sha256': None}]}))
        with ThreadPoolExecutor(max_workers=2) as workers:
            calls = [workers.submit(self.session, topic=topic, extra=f'-TargetManifest {ps_quote(manifest)}')
                     for topic, manifest in [('parallel-a', self.manifest), ('parallel-b', other)]]
        self.assertEqual([f.result().returncode for f in calls], [0, 0], [f.result().stdout for f in calls])

    def test_parallel_same_target_admission_has_exactly_one_winner(self):
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=2) as workers:
            calls = [workers.submit(self.session, topic=topic, extra=f'-TargetManifest {ps_quote(self.manifest)}')
                     for topic in ['race-a', 'race-b']]
        self.assertEqual(sorted(f.result().returncode for f in calls), [0, 7], [f.result().stdout for f in calls])

    def test_targeted_status_does_not_treat_disjoint_active_session_as_global_hold(self):
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        other = self.root / 'status-targets.json'
        other.write_text(json.dumps({'targets': [{'path': 'status-other.txt', 'sha256': None}]}))
        result = self.session(action='status', topic='other', extra=f'-TargetManifest {ps_quote(other)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_legacy_hashed_scope_can_be_bound_without_changing_live_lease(self):
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        lease_path = self.root / '__patch_drop__/source-edit-locks/fixture.lock/lease.json'
        lease = json.loads(lease_path.read_text(encoding='utf-8-sig'))
        lease.pop('targetPaths'); lease.pop('coordinationMode')
        lease_path.write_text(json.dumps(lease))
        before = lease_path.read_bytes()
        result = self.session(action='bind-scope', extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(lease_path.read_bytes(), before)
        other = self.root / 'disjoint-legacy.json'
        other.write_text(json.dumps({'targets': [{'path': 'other-legacy.txt', 'sha256': None}]}))
        result = self.session(topic='other', extra=f'-TargetManifest {ps_quote(other)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.session(topic='overlap', extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 7)

    def test_legacy_scope_binding_rejects_a_different_manifest(self):
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        self.manifest.write_text(json.dumps({'targets': [{'path': 'wrong.txt', 'sha256': None}]}))
        result = self.session(action='bind-scope', extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / '__patch_drop__/source-edit-scopes').exists())

    def test_filesystem_git_fallback_retains_index_identity_and_merge_holds(self):
        (self.root / '.git/index.lock').touch()
        prefix = 'function git { $global:LASTEXITCODE=128 }; '
        command = f'Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)} -AllowFilesystemFallback | ConvertTo-Json'
        row = self.contract(prefix + command)
        self.assertTrue(row['ok'])
        self.assertTrue(row['indexLockPresent'])
        self.assertFalse(row['activeOperation'])
        (self.root / '.git/MERGE_HEAD').write_text('a' * 40)
        row = self.contract(prefix + command)
        self.assertTrue(row['activeOperation'])

    def test_same_topic_different_owners_can_use_disjoint_targets_and_release_only_self(self):
        other = self.root / 'owner-b-targets.json'
        other.write_text(json.dumps({'targets': [{'path': 'owner-b.txt', 'sha256': None}]}))
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        command = f'& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Topic fixture -OwnerId owner-b'
        result = self.ps(command + f' -Action begin -TargetManifest {ps_quote(other)}; exit $LASTEXITCODE')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.ps(command + f' -Action verify -TargetManifest {ps_quote(other)}; exit $LASTEXITCODE')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.ps(command + ' -Action end; exit $LASTEXITCODE').returncode, 0)
        self.assertEqual(self.session(action='verify', extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)

    def test_normalized_target_alias_cannot_bypass_overlap(self):
        self.assertEqual(self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}').returncode, 0)
        alias = self.root / 'alias-targets.json'
        content = json.loads(self.manifest.read_text())
        content['targets'][0]['path'] = './TARGET.txt'
        alias.write_text(json.dumps(content))
        second = self.session(topic='alias', extra=f'-TargetManifest {ps_quote(alias)}')
        self.assertEqual(second.returncode, 7)
        self.assertIn('source-target-overlap', second.stdout)

    def test_unrelated_pending_patch_does_not_block_scoped_source_edit(self):
        patch = self.root / '__patch_drop__/unrelated-v3.patch'
        patch.write_text('diff --git a/other.txt b/other.txt\n--- a/other.txt\n+++ b/other.txt\n@@ -1 +1 @@\n-old\n+new\n')
        result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(patch.exists())

    def test_overlapping_or_unknown_pending_patch_still_blocks_scoped_edit(self):
        patch = self.root / '__patch_drop__/pending-v3.patch'
        for content in ('diff --git a/target.txt b/target.txt\n--- a/target.txt\n+++ b/target.txt\n@@ -1 +1 @@\n-old\n+new\n', 'opaque patch'):
            patch.write_text(content)
            result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}')
            self.assertEqual(result.returncode, 5, result.stdout + result.stderr)
            self.assertFalse((self.root / '__patch_drop__/source-edit-locks/fixture.lock').exists())

    def test_git_file_inventory_does_not_block_scoped_session(self):
        (self.root / '.git/index.lock').touch()
        code = f"function Get-CimInstance {{ [pscustomobject]@{{CommandLine='git.exe ls-files --others --exclude-standard -z'}} }}; & {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; exit $LASTEXITCODE"
        result = self.ps(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / '.git/index.lock').read_bytes(), b'')

    def test_codex_hardened_git_inventory_options_are_read_only(self):
        command = 'git.exe -c safe.bareRepository=explicit -c core.hooksPath=NUL -c core.fsmonitor=false ls-files --others --exclude-standard -z'
        row = self.contract(f'Test-AwxGitWriterApplies -CommandLine {ps_quote(command)} -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json')
        self.assertFalse(row)
        result = self.ps(f"function Get-CimInstance {{ [pscustomobject]@{{CommandLine={ps_quote(command)}}} }}; & {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; exit $LASTEXITCODE")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_codex_status_inventory_is_read_only(self):
        command = 'git.exe -c safe.bareRepository=explicit -c core.hooksPath=NUL -c core.fsmonitor=0 status --no-renames --ignored=matching --untracked-files=all --porcelain=v2 -z -- target.txt'
        row = self.contract(f'Test-AwxGitWriterApplies -CommandLine {ps_quote(command)} -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json')
        self.assertFalse(row)

    def test_codex_formatted_diff_inventory_is_read_only(self):
        command = 'git.exe -c diff.mnemonicPrefix=false -c diff.noprefix=false -c core.quotePath=false -c safe.bareRepository=explicit -c core.hooksPath=NUL -c core.fsmonitor=false diff --no-ext-diff --no-textconv --color=always --src-prefix=a/ --dst-prefix=b/ --find-renames --raw --no-abbrev --numstat -z'
        row = self.contract(f'Test-AwxGitWriterApplies -CommandLine {ps_quote(command)} -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json')
        self.assertFalse(row)
        for unsafe in (' --output=side-effect.txt', ' --ext-diff', ' --textconv'):
            row = self.contract(f'Test-AwxGitWriterApplies -CommandLine {ps_quote(command + unsafe)} -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json')
            self.assertTrue(row, unsafe)
        for disabled in ('0', '', 'off', 'no'):
            disabled_command = command.replace('core.fsmonitor=false', 'core.fsmonitor=' + disabled)
            row = self.contract(f'Test-AwxGitWriterApplies -CommandLine {ps_quote(disabled_command)} -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json')
            self.assertFalse(row, disabled)

    def test_ambiguous_git_writer_holds_actual_session_entrypoint(self):
        result = self.ps(f"function Get-CimInstance {{ [pscustomobject]@{{CommandLine='git.exe checkout topic'}} }}; & {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; exit $LASTEXITCODE")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('git-operation-active', result.stdout)
        self.assertFalse((self.root / '__patch_drop__/source-edit-locks/fixture.lock').exists())

    def test_exited_git_process_with_empty_metadata_is_rechecked(self):
        code = f"function Get-CimInstance {{ param($ClassName,$Filter); if ($Filter -notmatch 'ProcessId=') {{ [pscustomobject]@{{ProcessId=1234;CommandLine=$null}} }} }}; Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json"
        row = self.contract(code)
        self.assertTrue(row['writerCheckAvailable'])
        self.assertEqual(row['writerCount'], 0)

    def test_live_git_process_with_unknown_metadata_remains_a_writer(self):
        code = f"function Get-CimInstance {{ param($ClassName,$Filter); [pscustomobject]@{{ProcessId=1234;CommandLine=$null}} }}; Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json"
        row = self.contract(code)
        self.assertTrue(row['writerCheckAvailable'])
        self.assertEqual(row['writerCount'], 1)

    def test_changed_preimage_holds_scoped_lease(self):
        (self.root / "target.txt").write_text("concurrent writer\n")
        result = self.session(extra=f"-TargetManifest {ps_quote(self.manifest)}")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("preimage-changed", result.stdout)

    def test_merge_blocks_scoped_writer_but_not_read(self):
        (self.root / ".git/MERGE_HEAD").write_text("a" * 40)
        result = self.session(extra=f"-TargetManifest {ps_quote(self.manifest)}")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("git-operation-active", result.stdout)
        row = self.contract(f"$e=Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)}; Get-AwxScopedOperationDecision -Operation read-only -GitEvidence $e | ConvertTo-Json -Depth 6")
        self.assertTrue(row["allowed"])
        self.assertFalse(row["repositoryWideHold"])

    def test_alternate_index_path_is_derived_from_index_not_index_lock(self):
        custom = self.root / "custom-index"
        Path(str(custom) + ".lock").touch()
        self.env["GIT_INDEX_FILE"] = str(custom)
        row = self.contract(f"Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json -Depth 6")
        self.assertEqual(Path(row["indexLockPath"]), Path(str(custom) + ".lock"))
        self.assertTrue(row["indexLockPresent"])

    def test_index_write_holds_and_unproven_build_side_effects_hold(self):
        (self.root / ".git/index.lock").touch()
        for operation in ("index-write", "build"):
            with self.subTest(operation=operation):
                row = self.contract(f"$e=Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)}; Get-AwxScopedOperationDecision -Operation {operation} -GitEvidence $e | ConvertTo-Json -Depth 6")
                self.assertFalse(row["allowed"])
                self.assertFalse(row["repositoryWideHold"])

    def test_gpu_fault_blocks_only_dependent_operation(self):
        for operation, required, expected in (("read-only", "$false", True), ("worktree-edit", "$false", True), ("runtime", "$true", False)):
            with self.subTest(operation=operation):
                row = self.contract(f"$e=[pscustomobject]@{{ok=$true;indexLockPresent=$false;activeOperation=$false;writerCount=0;writerCheckAvailable=$true}}; Get-AwxScopedOperationDecision -Operation {operation} -GitEvidence $e -TargetsVerified $true -SourceLeaseChecked $true -RequiresGpu:{required} -GpuAvailable $false | ConvertTo-Json -Depth 6")
                self.assertEqual(row["allowed"], expected)

    def test_fingerprint_ignores_clock_but_changes_with_source_or_resource(self):
        row = self.contract("$a=Get-AwxBlockerFingerprint -Reasons @('GPU_UNAVAILABLE') -Scopes @('target-gpu') -SourceIdentity 'abc' -ResourceIdentity 'uuid-a'; $b=Get-AwxBlockerFingerprint -Reasons @('GPU_UNAVAILABLE') -Scopes @('target-gpu') -SourceIdentity 'abc' -ResourceIdentity 'uuid-a'; $c=Get-AwxBlockerFingerprint -Reasons @('GPU_UNAVAILABLE') -Scopes @('target-gpu') -SourceIdentity 'def' -ResourceIdentity 'uuid-a'; $d=Get-AwxBlockerFingerprint -Reasons @('GPU_UNAVAILABLE') -Scopes @('target-gpu') -SourceIdentity 'abc' -ResourceIdentity 'uuid-b'; @{a=$a;b=$b;c=$c;d=$d} | ConvertTo-Json")
        self.assertEqual(row["a"], row["b"])
        self.assertNotEqual(row["a"], row["c"])
        self.assertNotEqual(row["a"], row["d"])

    def test_owned_cleanup_refuses_changed_lease_identity(self):
        self.assertEqual(self.session().returncode, 0)
        lease_path = self.root / "__patch_drop__/source-edit-locks/fixture.lock/lease.json"
        before = hashlib.sha256(lease_path.read_bytes()).hexdigest()
        lease = json.loads(lease_path.read_text(encoding="utf-8-sig"))
        lease["startedAtUtc"] = "2000-01-01T00:00:00Z"
        lease_path.write_text(json.dumps(lease))
        result = self.session(action="end", extra=f"-LeaseFingerprint {before}")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(lease_path.exists())

    def test_repeat_resumes_independent_work_without_claiming_completion(self):
        rows = self.contract("$old=@{fingerprint='abc';unchangedCount=2}; @( (Get-AwxBlockerResumeDecision -Fingerprint abc -Previous $old -HasIndependentWork $true), (Get-AwxBlockerResumeDecision -Fingerprint abc -Previous $old -HasIndependentWork $false), (Get-AwxBlockerResumeDecision -Fingerprint def -Previous $old -HasIndependentWork $false) ) | ConvertTo-Json -Depth 6")
        self.assertEqual([r['action'] for r in rows], ['independent-work', 'await-external-condition', 'run-audit'])
        self.assertEqual(rows[0]['unchangedCount'], 3)
        self.assertTrue(rows[0]['skipFullAudit'])
        self.assertFalse(rows[0]['repositoryWideHold'])

    def test_explicit_other_repository_writer_is_not_a_local_writer(self):
        first, other = self.root / 'first', self.root / 'other'
        first.mkdir(); other.mkdir()
        commands = [f'git.exe -C "{other}" checkout topic', f'git.exe -C "{first}" checkout topic', 'git.exe checkout topic', f'git.exe -C "{other}" --work-tree="{first}" checkout topic']
        calls = ','.join(f'(Test-AwxGitWriterApplies -CommandLine {ps_quote(cmd)} -ProjectRoot {ps_quote(first)})' for cmd in commands)
        rows = self.contract(f'@({calls}) | ConvertTo-Json')
        self.assertEqual(rows, [False, True, True, True])

    def test_known_git_readers_do_not_block_guarded_worktree_edit(self):
        (self.root / '.git/index.lock').touch()
        commands = [
            'git.exe status --porcelain=2 --untracked-files=all',
            f'"C:\\Program Files\\Git\\cmd\\git.exe" --no-optional-locks -C "{self.root}" -c core.quotepath=false status --short',
            'git.exe diff --no-ext-diff --no-textconv --numstat HEAD -- target.txt',
            'git.exe --no-optional-locks rev-parse --git-path index',
        ]
        calls = ','.join(f'(Test-AwxGitWriterApplies -CommandLine {ps_quote(cmd)} -ProjectRoot {ps_quote(self.root)})' for cmd in commands)
        self.assertEqual(self.contract(f'@({calls}) | ConvertTo-Json'), [False] * len(commands))
        result = self.ps(f"function Get-CimInstance {{ [pscustomobject]@{{CommandLine='git.exe status --short'}} }}; & {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; exit $LASTEXITCODE")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / '.git/index.lock').read_bytes(), b'')
        lease = self.root / '__patch_drop__/source-edit-locks/fixture.lock/lease.json'
        fingerprint = hashlib.sha256(lease.read_bytes()).hexdigest()
        verify = f"& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action verify -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)} -LeaseFingerprint {fingerprint}; exit $LASTEXITCODE"
        result = self.ps("function Get-CimInstance { [pscustomobject]@{CommandLine='git.exe status --short'} }; " + verify)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.ps("function Get-CimInstance { [pscustomobject]@{CommandLine='git.exe status --short'}; [pscustomobject]@{CommandLine='git.exe checkout topic'} }; " + verify)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('git-operation-active', result.stdout)
        self.assertEqual((self.root / '.git/index.lock').read_bytes(), b'')
        self.assertEqual(hashlib.sha256(lease.read_bytes()).hexdigest(), fingerprint)

    def test_unknown_or_side_effecting_git_commands_remain_writers(self):
        commands = [
            '', 'git.exe checkout topic', 'git.exe merge topic', 'git.exe add target.txt',
            'git.exe update-index --refresh', 'git.exe custom-alias',
            'git.exe -c alias.read=!checkout read',
            'git.exe -c core.fsmonitor=arbitrary-command status',
            'git.exe diff --no-ext-diff --no-textconv --output=target.txt',
            'git.exe diff --ext-diff', 'git.exe diff --textconv', 'git.exe diff --stat',
            'git.exe status --unknown-option', 'git.exe --work-tree=elsewhere status',
            'git.exe -C "unterminated status', 'git.exe status"checkout"',
        ]
        calls = ','.join(f'(Test-AwxGitWriterApplies -CommandLine {ps_quote(cmd)} -ProjectRoot {ps_quote(self.root)})' for cmd in commands)
        self.assertEqual(self.contract(f'@({calls}) | ConvertTo-Json'), [True] * len(commands))

    def test_reader_evidence_keeps_index_writes_held_and_mixed_writer_blocks(self):
        prefix = "function Get-CimInstance { [pscustomobject]@{CommandLine='git.exe status --short'} }; "
        code = f"$e=Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)}; @{{evidence=$e;edit=(Get-AwxScopedOperationDecision -Operation worktree-edit -GitEvidence $e -TargetsVerified $true -SourceLeaseChecked $true);index=(Get-AwxScopedOperationDecision -Operation index-write -GitEvidence $e)}} | ConvertTo-Json -Depth 7"
        row = self.contract(prefix + code)
        self.assertEqual(row['evidence']['writerCount'], 0)
        self.assertEqual(row['evidence']['worktreeReadOnlyCount'], 1)
        self.assertTrue(row['edit']['allowed'])
        self.assertFalse(row['index']['allowed'], 'status may still refresh the index')
        mixed = "function Get-CimInstance { [pscustomobject]@{CommandLine='git.exe status --short'}; [pscustomobject]@{CommandLine='git.exe checkout topic'} }; "
        row = self.contract(mixed + code)
        self.assertEqual(row['evidence']['writerCount'], 1)
        self.assertFalse(row['edit']['allowed'])

    def test_unavailable_process_evidence_still_holds_worktree_edit(self):
        code = (
            f"function Get-CimInstance {{ throw 'fixture process inspection unavailable' }}; "
            f"$e=Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)}; "
            "@{ evidence=$e; "
            "edit=(Get-AwxScopedOperationDecision -Operation worktree-edit -GitEvidence $e -TargetsVerified $true -SourceLeaseChecked $true); "
            "index=(Get-AwxScopedOperationDecision -Operation index-write -GitEvidence $e) } | ConvertTo-Json -Depth 7"
        )
        row = self.contract(code)
        self.assertTrue(row['evidence']['ok'])
        self.assertFalse(row['evidence']['writerCheckAvailable'])
        self.assertFalse(row['evidence']['gitCliUsed'])
        self.assertTrue(row['edit']['allowed'])
        self.assertFalse(row['edit']['repositoryWideHold'])
        self.assertFalse(row['index']['allowed'])
        self.assertEqual(row['index']['firstBlockingRule'], 'git-operation-active')

    def test_git_absent_allows_scoped_worktree_edit(self):
        import shutil
        shutil.rmtree(self.root / '.git')
        result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / '__patch_drop__/source-edit-locks/fixture.lock/lease.json').exists())

    def test_default_evidence_does_not_invoke_git_cli(self):
        row = self.contract(
            f"$script:gitInvoked=$false; function git {{ $script:gitInvoked=$true; $global:LASTEXITCODE=0; 'unexpected' }}; "
            f"$e=Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(self.root)}; "
            "@{ invoked=[bool]$script:gitInvoked; cliUsed=$e.gitCliUsed; ok=$e.ok; absent=$e.gitAbsent } | ConvertTo-Json"
        )
        self.assertTrue(row['ok'])
        self.assertFalse(row['cliUsed'])
        self.assertFalse(row['invoked'])
        self.assertFalse(row['absent'])
        result = self.ps(
            f"$script:gitInvoked=$false; function git {{ $script:gitInvoked=$true; $global:LASTEXITCODE=0; 'unexpected' }}; "
            f"& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; "
            "if ($script:gitInvoked) { Write-Output 'GIT_INVOKED' }; exit $LASTEXITCODE"
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('GIT_INVOKED', result.stdout)

    def test_git_cli_throw_does_not_block_scoped_begin(self):
        result = self.ps(
            f"function git {{ throw 'git-missing' }}; "
            f"& {ps_quote(SESSION)} -Root {ps_quote(self.root)} -Action begin -Topic fixture -OwnerId fixture-owner -TargetManifest {ps_quote(self.manifest)}; "
            "exit $LASTEXITCODE"
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_target_manifest_requires_explicit_new_path_preimage(self):
        self.manifest.write_text(json.dumps({'targets':[{'path':'new.txt'}]}))
        result = self.session(extra=f'-TargetManifest {ps_quote(self.manifest)}')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('target-scope-unproven', result.stdout)

    def test_linked_worktree_git_file_resolves_its_own_lock(self):
        # A fixture-only commit supplies a HEAD for a linked worktree.
        self.git('add', 'target.txt')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'fixture')
        linked = self.root / 'linked'
        self.git('worktree', 'add', '--detach', '--quiet', str(linked))
        index = subprocess.run(['git', '-C', str(linked), 'rev-parse', '--git-path', 'index'], capture_output=True, text=True, check=True).stdout.strip()
        Path(index + '.lock').touch()
        row = self.contract(f"Get-AwxGitOperationEvidence -ProjectRoot {ps_quote(linked)} | ConvertTo-Json")
        self.assertTrue(row['ok'])
        self.assertTrue(row['indexLockPresent'])
        self.assertEqual(Path(row['indexLockPath']), Path(index + '.lock'))

    def test_index_affecting_apply_options_are_not_worktree_only(self):
        for flag in ('--index', '--cached', '--3way', '--intent-to-add', '-3', '-N'):
            with self.subTest(flag=flag):
                row = self.contract(f"$e=[pscustomobject]@{{ok=$true;indexLockPresent=$false;activeOperation=$false;writerCount=0;writerCheckAvailable=$true}}; Get-AwxScopedOperationDecision -Operation worktree-edit -GitEvidence $e -TargetsVerified $true -SourceLeaseChecked $true -GitApplyArguments @({ps_quote(flag)}) | ConvertTo-Json")
                self.assertFalse(row['allowed'])
                self.assertEqual(row['firstBlockingRule'], 'index-mutating-apply-option')

    def test_goal_preflight_preserves_scoped_failure_and_read_permission(self):
        (self.root / '.git/index.lock').touch()
        goal = ROOT / 'scripts/goal_next_auto.ps1'
        code = f"$ast=[Management.Automation.Language.Parser]::ParseFile({ps_quote(goal)},[ref]$null,[ref]$null); foreach($name in @('Get-DesktopPreflight','Get-SourceEditLeaseSummary')) {{$fn=$ast.Find({{param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name}},$true); Invoke-Expression $fn.Extent.Text}}; Get-DesktopPreflight -ProjectRoot {ps_quote(self.root)} | ConvertTo-Json -Depth 6"
        row = self.contract(code)
        self.assertEqual(row['failureClassification'], 'index-lock-conflict')
        self.assertTrue(row['operationDecision']['allowed'])
        self.assertFalse(row['repositoryWideHold'])

    def test_goal_entrypoint_skips_same_blocker_and_keeps_prior_audit_untouched(self):
        goal = ROOT / 'scripts/goal_next_auto.ps1'
        state = self.root / 'var/codex-smoke/goal-next-auto.blocker-state.json'
        state.parent.mkdir(parents=True)
        prior_audit = state.parent / 'goal-next-auto.latest.json'
        prior_audit.write_text('{"decision":"evidence_needed","generatedAt":"2000-01-01T00:00:00Z"}')
        before = prior_audit.read_bytes()
        code = f"$ast=[Management.Automation.Language.Parser]::ParseFile({ps_quote(goal)},[ref]$null,[ref]$null); foreach($name in @('Get-GoalNextBlockerIdentity','Resolve-RepoPath')) {{$fn=$ast.Find({{param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name}},$true); Invoke-Expression $fn.Extent.Text}}; $mode=@{{requireSupabaseProof=$false;externalDispatch=$false;refreshWebProbe=$false;requireUiProof=$false}}; $identity=Get-GoalNextBlockerIdentity -ProjectRoot {ps_quote(self.root)} -Mode $mode; @{{fingerprint=(Get-AwxBlockerFingerprint -Reasons @('GPU_UNAVAILABLE') -Scopes @('target-gpu') -SourceIdentity $identity -ResourceIdentity 'goal-next');decision='evidence_needed';reasons=@('GPU_UNAVAILABLE');holdScope=@('target-gpu');unchangedCount=0;lastObservedAt='2000-01-01T00:00:00Z'}} | ConvertTo-Json"
        row = self.contract(code)
        state.write_text(json.dumps(row))
        result = self.ps(f"& {ps_quote(goal)} -Root {ps_quote(self.root)} -EnsureFresh -IndependentWorkAvailable; exit $LASTEXITCODE")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn('action=independent-work', result.stdout)
        self.assertIn('skipFullAudit=true', result.stdout)
        self.assertEqual(prior_audit.read_bytes(), before)
        self.assertEqual(json.loads(state.read_text(encoding='utf-8-sig'))['unchangedCount'], 1)
        self.assertFalse((state.parent / 'goal-next-auto').exists())
        from datetime import datetime, timezone
        prior_audit.write_text(json.dumps({'decision':'evidence_needed','generatedAt':datetime.now(timezone.utc).isoformat()}))
        (self.root / 'scripts').mkdir()
        (self.root / 'scripts/change.py').write_text('# changed relevant source\n')
        changed = self.ps(f"& {ps_quote(goal)} -Root {ps_quote(self.root)} -EnsureFresh -IndependentWorkAvailable; exit $LASTEXITCODE")
        self.assertEqual(changed.returncode, 2, changed.stdout + changed.stderr)
        self.assertIn('action=refresh', changed.stdout)

    def test_recovery_observation_clock_does_not_reopen_same_full_audit(self):
        proof = self.root / 'proof.json'
        proof.write_text(json.dumps({'observedAt':'2000-01-01','gpuLost':True,'endpoint':'loopback'}))
        goal = ROOT / 'scripts/goal_next_auto.ps1'
        setup = f"$ast=[Management.Automation.Language.Parser]::ParseFile({ps_quote(goal)},[ref]$null,[ref]$null); foreach($name in @('Get-GoalNextBlockerIdentity','Resolve-RepoPath')) {{$fn=$ast.Find({{param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name}},$true); Invoke-Expression $fn.Extent.Text}}; "
        command = f"Get-GoalNextBlockerIdentity -ProjectRoot {ps_quote(self.root)} -Mode @{{}} -RecoveryEvidencePath {ps_quote(proof)} | ConvertTo-Json"
        before = self.contract(setup + command)
        proof.write_text(json.dumps({'endpoint':'loopback','gpuLost':True,'observedAt':'2026-09-06'}))
        after = self.contract(setup + command)
        self.assertEqual(before, after)
        proof.write_text(json.dumps({'observedAt':'2026-09-06','gpuLost':False,'endpoint':'loopback'}))
        self.assertNotEqual(after, self.contract(setup + command))

    def test_lease_expiry_changes_resume_identity_without_source_change(self):
        goal = ROOT / 'scripts/goal_next_auto.ps1'
        lock = self.root / '__patch_drop__/source-edit-locks/expiring.lock'
        lock.mkdir(parents=True)
        # A near-expiry fixture tests the real status transition, not a changed file.
        from datetime import datetime, timedelta, timezone
        expires = (datetime.now(timezone.utc) + timedelta(seconds=3)).isoformat()
        (lock / 'lease.json').write_text(json.dumps({'role':'desktop','expiresAtUtc':expires}))
        setup = f"$ast=[Management.Automation.Language.Parser]::ParseFile({ps_quote(goal)},[ref]$null,[ref]$null); $fn=$ast.Find({{param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-GoalNextBlockerIdentity'}},$true); Invoke-Expression $fn.Extent.Text; Get-GoalNextBlockerIdentity -ProjectRoot {ps_quote(self.root)} -Mode @{{}} | ConvertTo-Json"
        before = self.contract(setup)
        time.sleep(3)
        self.assertNotEqual(before, self.contract(setup))


if __name__ == "__main__":
    unittest.main()
