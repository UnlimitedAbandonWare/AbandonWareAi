"""Behavioral safety tests: loss, partial writes and wrong host paths must fail."""
import importlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import shutil
import shlex

ROOT = Path(__file__).resolve().parents[1]


class SharedStateTest(unittest.TestCase):
    def module(self, name):
        self.assertIsNotNone(importlib.util.find_spec('scripts.' + name), 'shared safety component missing')
        return importlib.import_module('scripts.' + name)

    def test_setup_preserves_unrelated_server_and_creates_backup(self):
        with tempfile.TemporaryDirectory(prefix='awx-merge-test-') as folder:
            path = Path(folder) / 'client.json'
            before = b'{"mcpServers":{"personal":{"command":"personal-runner","args":["keep"]}}}'
            path.write_bytes(before)
            result = subprocess.run([sys.executable, str(ROOT/'scripts/awx_mcp_node_setup.py'), '--node-role', 'desktop', '--source-root', str(ROOT), '--output', str(path)], capture_output=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stdout[:250])
            value = json.loads(path.read_bytes())
            self.assertEqual(value['mcpServers'].get('personal'), {'command':'personal-runner','args':['keep']})
            self.assertIn('awx-control-tower', value['mcpServers'])
            self.assertTrue(any(p.read_bytes() == before for p in Path(folder).rglob('*.bak_*')))

    def test_json_merge_conflict_and_duplicate_keys_are_not_silently_lost(self):
        m = self.module('awx_shared_state')
        self.assertEqual(json.loads(m.merge_config(b'{"servers":{"mine":1}}', b'{"servers":{"new":2}}', 'json')), {'servers':{'mine':1,'new':2}})
        for existing in [b'{"x":1}', b'{"x":1,"x":2}']:
            with self.assertRaises(m.Conflict):
                m.merge_config(existing, b'{"x":3}', 'json')

    def test_three_way_updates_managed_keys_and_preserves_personal_edits(self):
        m=self.module('awx_shared_state')
        base={'server':{'timeout':20,'mode':'old'},'array':[1,2]}
        current={'server':{'timeout':45,'mode':'old','mine':True},'array':[1,2]}
        incoming={'server':{'timeout':20,'mode':'new'},'array':[1,2,3]}
        self.assertEqual(m.merge_three_way(current,base,incoming),{'server':{'timeout':45,'mode':'new','mine':True},'array':[1,2,3]})
        with self.assertRaises(m.Conflict):m.merge_three_way({'array':[2,1]},base,incoming)

    def test_toml_merge_preserves_nested_arrays_dates_and_unrelated_values(self):
        import tomllib
        m = self.module('awx_shared_state')
        before = b'# personal\nmodel="mine"\nwhen=2026-09-13\n[mcp_servers.personal]\ncommand="keep"\nargs=["a","b"]\n'
        merged = m.merge_config(before, b'[mcp_servers.awx]\ncommand="python3"\n', 'toml')
        old, new = tomllib.loads(before.decode()), tomllib.loads(merged.decode())
        self.assertEqual(new['model'], old['model'])
        self.assertEqual(new['when'], old['when'])
        self.assertEqual(new['mcp_servers']['personal'], old['mcp_servers']['personal'])
        self.assertEqual(new['mcp_servers']['awx']['command'], 'python3')

    def test_transaction_rejects_changed_preimage_before_any_write(self):
        m = self.module('awx_shared_state')
        with tempfile.TemporaryDirectory(prefix='awx-atomic-test-') as folder:
            base=Path(folder); a=base/'a'; b=base/'b'; a.write_bytes(b'old'); b.write_bytes(b'writer')
            with self.assertRaises(m.Conflict):
                m.apply_changes([m.Change(a,b'old',b'new'),m.Change(b,b'old',b'new')],base/'local')
            self.assertEqual(a.read_bytes(),b'old'); self.assertEqual(b.read_bytes(),b'writer')

    def test_transaction_backups_and_rerun_noop(self):
        m = self.module('awx_shared_state')
        with tempfile.TemporaryDirectory(prefix='awx-backup-test-') as folder:
            base=Path(folder); a=base/'a'; a.write_bytes(b'original'); b=base/'new'
            receipt=m.apply_changes([m.Change(a,b'original',b'updated'),m.Change(b,None,b'created')],base/'state')
            self.assertEqual(receipt['changedCount'],2)
            self.assertTrue(any(p.read_bytes()==b'original' for p in (base/'state').rglob('*.bak_*')))
            self.assertEqual(b.read_bytes(),b'created')
            self.assertEqual(m.apply_changes([m.Change(a,b'updated',b'updated')],base/'state')['changedCount'],0)

    def test_transaction_rolls_back_first_file_on_second_replace_failure(self):
        from unittest.mock import patch
        m = self.module('awx_shared_state')
        with tempfile.TemporaryDirectory(prefix='awx-rollback-test-') as folder:
            base=Path(folder); a=base/'a'; b=base/'b';a.write_bytes(b'A');b.write_bytes(b'B')
            real=os.replace
            def replace(src,dst):
                if Path(dst)==b and Path(src).read_bytes()==b'next-B':raise OSError('fixture write failure')
                return real(src,dst)
            with patch.object(m.os,'replace',side_effect=replace):
                with self.assertRaises(OSError):m.apply_changes([m.Change(a,b'A',b'next-A'),m.Change(b,b'B',b'next-B')],base/'state')
            self.assertEqual(a.read_bytes(),b'A');self.assertEqual(b.read_bytes(),b'B')

    def test_rollback_preserves_a_new_writer_and_records_recovery_required(self):
        from unittest.mock import patch
        m=self.module('awx_shared_state')
        with tempfile.TemporaryDirectory(prefix='awx-writer-test-') as folder:
            base=Path(folder);a=base/'a';b=base/'b';a.write_bytes(b'A');b.write_bytes(b'B')
            real=os.replace
            def replace(src,dst):
                if Path(dst)==b:
                    a.write_bytes(b'peer modification');raise OSError('fixture')
                return real(src,dst)
            with patch.object(m.os,'replace',side_effect=replace):
                with self.assertRaises(OSError):m.apply_changes([m.Change(a,b'A',b'ours'),m.Change(b,b'B',b'next')],base/'state')
            self.assertEqual(a.read_bytes(),b'peer modification');self.assertEqual(b.read_bytes(),b'B')
            journal=next((base/'state').glob('transactions/*/journal.json'))
            self.assertEqual(json.loads(journal.read_bytes())['status'],'recovery-required')
            with self.assertRaises(m.Conflict):m.apply_changes([m.Change(b,b'B',b'next')],base/'state')

    def test_reparse_and_unknown_cooperative_lock_fail_without_overwrite(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        m=self.module('awx_shared_state')
        with tempfile.TemporaryDirectory(prefix='awx-lock-test-') as folder:
            base=Path(folder);a=base/'a';a.write_bytes(b'original')
            with patch.object(Path,'lstat',return_value=SimpleNamespace(st_mode=0o100600,st_file_attributes=0x400)):
                with self.assertRaises(m.Conflict):m.plain_path(a)
            lock=a.with_name('.awx-'+m.digest(str(a).casefold().encode())[:24]+'.lock');lock.write_bytes(b'another-owner')
            with self.assertRaises(m.Conflict):m.apply_changes([m.Change(a,b'original',b'new')],base/'state')
            self.assertEqual(a.read_bytes(),b'original');self.assertEqual(lock.read_bytes(),b'another-owner')

    def test_host_role_and_local_paths(self):
        m=self.module('awx_host_runtime')
        self.assertEqual(m.host_facts('Y:\\',system='Windows',environ={})['role'],'notebook')
        self.assertEqual(m.host_facts('C:\\work\\src',system='Windows',environ={})['role'],'desktop')
        self.assertEqual(m.host_facts('/Users/test/project',system='Darwin',environ={})['role'],'macbook')
        facts=m.host_facts('/Users/test/project',system='Darwin',environ={'HOME':'/Users/test'})
        self.assertTrue(str(facts['stateBase']).startswith('/Users/test/Library/Application Support/AWX'))

    def test_dispatch_leaves_host_state_resolution_to_the_receiving_machine(self):
        m=self.module('awx_mcp_toolbox')
        command=m.producer_setup_command('python3','scripts/awx_mcp_node_setup.py','/Users/fixture/source','desktop-root',m.setup_config_path('/Users/fixture/source','macmini'),'macmini',audit_log_path=m.setup_audit_log_path('/Users/fixture/source','macmini'))
        self.assertNotIn('--output',command);self.assertNotIn('--audit-log',command)
        self.assertNotIn('/.codex/',command);self.assertIn('-B',command)

    def test_personal_skill_name_collision_preserves_both_sources(self):
        m=self.module('awx_skill_registry')
        with tempfile.TemporaryDirectory(prefix='awx-skills-test-') as folder:
            base=Path(folder); shared=base/'shared'; personal=base/'personal'
            for parent,body in [(shared,'shared text'),(personal,'my custom text')]:
                p=parent/'same'/'SKILL.md';p.parent.mkdir(parents=True);p.write_text('---\nname: same\ndescription: fixture\n---\n'+body)
            before=(personal/'same/SKILL.md').read_bytes()
            report=m.scan_skills(shared,[personal])
            self.assertEqual(report['conflictCount'],1)
            self.assertEqual(report['resolutionPolicy'],'personal-first-no-write')
            self.assertEqual((personal/'same/SKILL.md').read_bytes(),before)
            import tomllib
            raw=b'# personal comment\nmodel="keep"\n'
            resolved=m.personal_priority_config(raw,shared,report)
            self.assertTrue(resolved.startswith(raw))
            disabled=tomllib.loads(resolved.decode())['skills']['config']
            self.assertEqual(disabled,[{'path':str(shared/'same/SKILL.md'),'enabled':False}])
            self.assertEqual(m.personal_priority_config(resolved,shared,report),resolved)

    def kit_fixture(self, base, files):
        import hashlib
        kit=base/'kit';kit.mkdir()
        rows=[]
        for rel,raw in files.items():
            path=kit/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
            rows.append({'path':rel,'sha256':hashlib.sha256(raw).hexdigest()})
        (kit/'producer-kit.manifest.json').write_text(json.dumps({'schemaVersion':'awx.mcp.producer_kit.v1','files':rows}))
        root=base/'producer';root.mkdir();subprocess.run(['git','init',str(root)],capture_output=True,check=True)
        return kit,root

    def test_unsafe_kit_paths_and_shared_state_are_rejected(self):
        m=self.module('awx_mcp_safe_install'); host=self.module('awx_host_runtime')
        for rel in ['../outside','scripts/../outside','scripts/CON.py','scripts/a:stream','main/java/Injected.java']:
            with self.subTest(rel=rel),self.assertRaises(m.Conflict):m.safe_relative(rel)
        with self.assertRaises(m.Conflict):host.local_state_root(ROOT,ROOT/'state')

    def test_child_runtime_retains_source_identity_and_local_cache(self):
        m=self.module('awx_host_runtime')
        with tempfile.TemporaryDirectory(prefix='awx-host-test-') as folder:
            env=m.child_environment(ROOT,Path(folder))
            self.assertEqual(env['AWX_SOURCE_ROOT'],str(ROOT))
            command=m.runtime_command(ROOT,'gradle',Path(folder))
            self.assertIn('--project-cache-dir',command)
            self.assertIn(str(Path(folder)/'gradle-project'),command)
            self.assertEqual(env['RUNTIME_TOOLKIT_EVIDENCE_PATH'],str(Path(folder)/'toolkit-current.json'))

    def test_managed_toolkit_publishes_only_to_host_state(self):
        from unittest.mock import patch
        m=self.module('awx_mcp_toolbox')
        legacy=ROOT/'var/codex-runtime/toolkit-current.json'
        before=legacy.read_bytes() if legacy.exists() else None
        with tempfile.TemporaryDirectory(prefix='awx-toolkit-test-') as folder:
            with patch.dict(os.environ,{'AWX_LOCAL_STATE_ROOT':folder}):
                toolkit=m.RuntimeToolkit({})
                self.assertTrue(toolkit.publish({'springPid':0}))
                self.assertTrue((Path(folder)/'toolkit-current.json').is_file())
        self.assertEqual(legacy.read_bytes() if legacy.exists() else None,before)

    def test_shared_read_mode_rejects_mutation_and_default_root_survives_local_cwd(self):
        from unittest.mock import patch
        sys.path.insert(0,str(ROOT/'scripts'))
        try:
            import awx_mcp_stdio_server as server
        finally:sys.path.pop(0)
        registry={'tools':[{'name':'fixture','readOnly':False,'input_schema':{'type':'object','properties':{'root':{'type':'string'}}}}]}
        captured=[]
        with patch.object(server,'registry',return_value=registry),patch.dict(server.HANDLERS,{'fixture':lambda args:captured.append(args) or {'ok':True}}),patch.object(server.toolbox,'finalize'):
            with patch.dict(os.environ,{'AWX_MCP_SOURCE_ACCESS':'shared-read'}):
                with self.assertRaises(server.ProtocolError):server.validate_tool_call({'name':'fixture','arguments':{}})
            with patch.dict(os.environ,{'AWX_MCP_SOURCE_ACCESS':'guarded'}):
                server.call_tool({'name':'fixture','arguments':{}})
                server.call_tool({'name':'fixture','arguments':{'root':'explicit-root'}})
        self.assertEqual(captured,[{'root':str(server.ROOT)},{'root':'explicit-root'}])

    def posix_shell_prefix(self, base):
        shell=os.environ.get('AWX_TEST_POSIX_SHELL') or shutil.which('sh')
        if not shell:self.skipTest('POSIX shell unavailable; Darwin path tests still run')
        bindir=base/'posix-bin';bindir.mkdir()
        wrapper=bindir/'python3'
        wrapper.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable.replace('\\','/'))+' "$@"\n',encoding='utf-8')
        wrapper.chmod(0o700)
        posix=str(bindir).replace('\\','/')
        if os.name=='nt':posix='/'+posix[0].lower()+posix[2:]
        return shell,'PATH='+shlex.quote(posix)+':"$PATH"; export PATH; '

    def test_posix_hook_executes_from_nested_source_and_preserves_read_only(self):
        with tempfile.TemporaryDirectory(prefix='awx-sh-hook-') as folder:
            shell,prefix=self.posix_shell_prefix(Path(folder))
            hook=json.loads((ROOT/'.codex/hooks.json').read_text())['hooks']['UserPromptSubmit'][0]['hooks'][0]['command']
            for prompt,expected in [('fix main/java/Foo.java',True),('fix main/java/Foo.java; do not edit',False)]:
                child=subprocess.run([shell,'-c',prefix+hook],cwd=ROOT/'main/java',input=json.dumps({'prompt':prompt}).encode(),capture_output=True,timeout=15)
                self.assertEqual(child.returncode,0,child.stderr[:120]);self.assertEqual(bool(child.stdout),expected)

    def test_posix_installer_executes_with_spaces_and_no_shared_runtime_files(self):
        m=self.module('awx_mcp_toolbox')
        with tempfile.TemporaryDirectory(prefix='awx-posix-install-') as folder:
            base=Path(folder);shell,prefix=self.posix_shell_prefix(base)
            result=m.producer_kit_export({'root':str(ROOT),'patchdrop_root':str(base/'exchange'),'topic':'posix-fixture'})
            self.assertTrue(result['ok'],result.get('failReason'))
            root=base/'producer with spaces';root.mkdir();subprocess.run(['git','init',str(root)],capture_output=True,check=True)
            command=prefix+'exec sh '+shlex.quote(str(Path(result['kitDir'])/'INSTALL.macmini.sh').replace('\\','/'))+' '+shlex.quote(str(root).replace('\\','/'))+' macbook '+shlex.quote(str(base/'local state').replace('\\','/'))
            child=subprocess.run([shell,'-c',command],capture_output=True,timeout=40)
            self.assertEqual(child.returncode,0,child.stdout[:160]+child.stderr[:160])
            self.assertTrue((base/'local state/install-receipt.json').is_file())
            self.assertFalse((root/'.codex/config.toml').exists())

    def test_export_is_immutable_and_generated_installer_runs_safely(self):
        m=self.module('awx_mcp_toolbox'); installer=self.module('awx_mcp_safe_install')
        with tempfile.TemporaryDirectory(prefix='awx-export-test-') as folder:
            base=Path(folder); exchange=base/'exchange'
            result=m.producer_kit_export({'root':str(ROOT),'patchdrop_root':str(exchange),'topic':'fixture'})
            self.assertTrue(result['ok'],result.get('failReason'))
            kit=Path(result['kitDir']);manifest=json.loads((kit/'producer-kit.manifest.json').read_bytes())
            expected_skills={p.relative_to(ROOT).as_posix() for p in (ROOT/'.agents/skills').glob('*/SKILL.md')}
            bundled_skills={r['path'] for r in manifest['files'] if r['path'].startswith('.agents/skills/') and r['path'].endswith('/SKILL.md')}
            self.assertEqual(bundled_skills,expected_skills)
            self.assertEqual(manifest['sharedSkillCount'],len(expected_skills))
            self.assertIn('scripts/harmony_catch_contract.py',[r['path'] for r in manifest['files']])
            root=base/'producer';root.mkdir();subprocess.run(['git','init',str(root)],capture_output=True,check=True)
            installed=installer.install(kit,root,base/'state','notebook')
            self.assertTrue(installed['ok']);self.assertFalse((root/'.codex/awx-control-tower.mcp.json').exists())
            requests='\n'.join(json.dumps(r) for r in [
                {'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'fixture','version':'1'}}},
                {'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}}])+'\n'
            child=subprocess.run([sys.executable,'-B',str(root/'scripts/awx_host_runtime.py'),'run','--root',str(root),'--state-root',str(base/'state')],input=requests.encode(),capture_output=True,timeout=20)
            self.assertEqual(child.returncode,0,child.stderr[:160])
            responses=[json.loads(line) for line in child.stdout.splitlines()]
            self.assertTrue(any(row.get('id')==2 and row.get('result',{}).get('tools') for row in responses))
            # A user edit in an already exported kit must never be overwritten.
            target=kit/'scripts/awx_mcp_node_setup.py';target.write_bytes(b'personal export edit')
            result=m.producer_kit_export({'root':str(ROOT),'patchdrop_root':str(exchange),'topic':'fixture'})
            self.assertFalse(result['ok']);self.assertEqual(target.read_bytes(),b'personal export edit')

    def test_installer_conflict_preserves_all_destinations(self):
        m=self.module('awx_mcp_safe_install')
        with tempfile.TemporaryDirectory(prefix='awx-install-test-') as folder:
            base=Path(folder);kit,root=self.kit_fixture(base,{'scripts/tool.py':b'shared','scripts/next.py':b'new'})
            (root/'scripts').mkdir();(root/'scripts/tool.py').write_bytes(b'personal edit')
            with self.assertRaises(m.Conflict):m.install(kit,root,base/'state','notebook')
            self.assertEqual((root/'scripts/tool.py').read_bytes(),b'personal edit')
            self.assertFalse((root/'scripts/next.py').exists())

    def test_installer_rejects_case_aliases_before_writes(self):
        m=self.module('awx_mcp_safe_install')
        with tempfile.TemporaryDirectory(prefix='awx-install-test-') as folder:
            base=Path(folder);kit,root=self.kit_fixture(base,{'scripts/a.py':b'code'})
            path=kit/'producer-kit.manifest.json';manifest=json.loads(path.read_text())
            manifest['files'].append({'path':'scripts/A.py','sha256':manifest['files'][0]['sha256']})
            path.write_text(json.dumps(manifest))
            with self.assertRaises(m.Conflict):m.install(kit,root,base/'state','notebook')
            self.assertFalse((root/'scripts').exists())

    def test_installer_receipt_allows_clean_update_but_preserves_local_edits(self):
        import hashlib
        m=self.module('awx_mcp_safe_install')
        with tempfile.TemporaryDirectory(prefix='awx-install-test-') as folder:
            base=Path(folder);kit,root=self.kit_fixture(base,{'scripts/tool.py':b'first'})
            m.install(kit,root,base/'state','notebook')
            self.assertTrue((base/'state/install-receipt.json').exists())
            self.assertFalse((root/'.codex').exists())
            path=kit/'producer-kit.manifest.json';manifest=json.loads(path.read_text())
            (kit/'scripts/tool.py').write_bytes(b'second');manifest['files'][0]['sha256']=hashlib.sha256(b'second').hexdigest();path.write_text(json.dumps(manifest))
            m.install(kit,root,base/'state','notebook')
            self.assertEqual((root/'scripts/tool.py').read_bytes(),b'second')
            (root/'scripts/tool.py').write_bytes(b'personal')
            with self.assertRaises(m.Conflict):m.install(kit,root,base/'state','notebook')
            self.assertEqual((root/'scripts/tool.py').read_bytes(),b'personal')


if __name__=='__main__':unittest.main()
