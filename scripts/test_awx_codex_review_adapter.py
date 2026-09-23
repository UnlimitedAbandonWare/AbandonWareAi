"""Black-box fake CLI contracts; no account or provider is contacted."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import awx_mcp_stdio_server as server
import awx_codex_review_adapter as adapter

FAKE = r'''
import json, os, sys, time
from pathlib import Path
scenario=sys.argv[1]
def out(value):
    print(json.dumps(value),flush=True)
if 'debug' in sys.argv:
    row={'slug':'gpt-5.5','multi_agent_version':'v2' if scenario=='metadata' else None}
    spark={'slug':'gpt-5.3-codex-spark','multi_agent_version':'v2' if scenario=='metadata' else None}
    out({'models':[row] if scenario=='missing_spark' else [row,spark]})
    sys.exit(0)
settings={}
for i,arg in enumerate(sys.argv):
    if arg=='-c':
        key,value=sys.argv[i+1].split('=',1)
        target=settings
        parts=key.split('.')
        for part in parts[:-1]: target=target.setdefault(part,{})
        target[parts[-1]]=json.loads(value)
if scenario=='enabled': settings['features']['plugins']=True
for line in sys.stdin:
    row=json.loads(line)
    if 'id' not in row: continue
    method=row['method']
    result={}
    if method=='config/read': result={'config':settings}
    if method=='account/read': result={'account':None if scenario=='auth_missing' else {'type':'apiKey' if scenario=='auth_mismatch' else 'chatgpt'}}
    if method=='thread/start':
        assert row['params']['environments']==[]
        assert row['params']['ephemeral'] is True
        assert os.environ.get('AWX_CODEX_REVIEW_DEPTH')=='1'
        result={'model':'gpt-5.5' if scenario=='model_mismatch' else settings['model'],'modelProvider':'openai','sandbox':{'type':'readOnly'},'runtimeWorkspaceRoots':[],'thread':{'id':'thread-fixture'}}
    if method=='mcpServerStatus/list': result={'data':[],'nextCursor':None}
    if method=='turn/start':
        text=row['params']['input'][0]['text']
        assert json.loads(text)['evidence'][0]['relativePath']=='fixture.py'
        result={'turn':{'id':'turn-fixture'}}
    out({'id':row['id'],'result':result})
    if scenario=='blocked_input' and method=='mcpServerStatus/list':
        Path(__file__).with_suffix('.ready').write_text('stdin_blocked')
        time.sleep(60)
    if method!='turn/start': continue
    if scenario=='partial_eof':
        sys.stdout.buffer.write(b'{"method":"item/completed","params":')
        sys.stdout.buffer.flush()
        sys.exit(0)
    if scenario=='nested_timeout':
        import subprocess
        child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])
        Path(__file__).with_suffix('.pid').write_text(str(child.pid))
        time.sleep(60)
    if scenario=='breakaway':
        import subprocess
        try:
            child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],creationflags=subprocess.CREATE_BREAKAWAY_FROM_JOB)
        except OSError:
            Path(__file__).with_suffix('.breakaway').write_text('blocked')
        else:
            Path(__file__).with_suffix('.pid').write_text(str(child.pid))
            Path(__file__).with_suffix('.breakaway').write_text('created')
            time.sleep(60)
    if scenario=='timeout': time.sleep(60)
    if scenario=='exit': sys.exit(0)
    if scenario=='line': print('x'*65537,flush=True); time.sleep(60)
    if scenario=='flood':
        for _ in range(140): print('x'*4096,file=sys.stderr,flush=True)
        time.sleep(60)
    if scenario=='noise': print('not-json',flush=True); time.sleep(60)
    if scenario=='tool':
        out({'method':'item/started','params':{'threadId':'thread-fixture','turnId':'turn-fixture','item':{'type':'commandExecution'}}})
        time.sleep(60)
    if scenario=='request':
        out({'id':90,'method':'item/tool/call','params':{}})
        time.sleep(60)
    finding={'severity':'high','claim':'Empty input indexes outside the list.','evidenceIds':['E1'],'suggestedCheck':'Test an empty list.'}
    final={'verdict':'needs_changes','findings':[finding]}
    if scenario=='foreign_id': finding['evidenceIds']=['E2']
    if scenario=='long_claim': finding['claim']='x'*801
    if scenario=='extra': final['private']='must-not-leak'
    if scenario=='too_many': final['findings']=[finding]*6
    if scenario=='secret': finding['claim']='sk-'+('X'*30)
    if scenario!='missing_final':
        out({'method':'item/completed','params':{'threadId':'thread-fixture','turnId':'turn-fixture','item':{'type':'agentMessage','phase':'final_answer','text':json.dumps(final)}}})
    if scenario!='no_usage':
        out({'method':'thread/tokenUsage/updated','params':{'threadId':'thread-fixture','turnId':'turn-fixture','tokenUsage':{'last':{'inputTokens':21,'outputTokens':12}}}})
    out({'method':'turn/completed','params':{'threadId':'thread-fixture','turn':{'id':'turn-fixture','status':'failed' if scenario=='failed' else 'completed'}}})
'''


class CandidateContract(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.fake = self.path / 'fake_cli.py'
        self.fake.write_text(FAKE, encoding='utf-8')
        self.config = self.path / 'config.toml'
        self.config.write_text('', encoding='utf-8')
        self.children = []
        self.owners = []
        self.cli_hash = hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest()
        self.payload = {'mode':'review','requestId':'request-1','reviewReason':'Find a counterexample',
                        'changeSummary':'Return the first list element','evidence':[{'evidenceId':'E1','relativePath':'fixture.py','excerpt':'def first(xs): return xs[0]'}], 'timeoutMs':5000}

    def owner(self, command, **options):
        owner = server.OwnedWorker(command, **options)
        self.children.append(owner.process)
        self.owners.append(owner)
        return owner

    def invoke(self, scenario='ok', payload=None, budget=10, cancel=None):
        instance = adapter.Adapter((sys.executable,str(self.fake),scenario),self.cli_hash,self.config,self.owner)
        with adapter.owned_worker(self.owner,time.monotonic()+budget,cancel):
            result = instance.run(self.payload if payload is None else payload)
        self.assertTrue(all(child.poll() is not None for child in self.children))
        self.assertLessEqual(len(adapter.canonical(result)),16384)
        return result

    def test_completed_result_and_real_usage(self):
        result=self.invoke()
        self.assertTrue(result['ok'],result['reason'])
        self.assertEqual('needs_changes',result['verdict'])
        self.assertEqual({'input':21,'output':12},result['usage'])
        self.assertEqual(adapter.digest(self.payload),result['inputHash'])
        self.assertTrue(result['lineage']['childCleanupConfirmed'])
        self.assertFalse(result['lineage']['remoteProviderProofAvailable'])

    def test_status_never_starts_a_turn(self):
        result=self.invoke(payload={'mode':'status','requestId':'status-1'})
        self.assertTrue(result['ok'],result['reason'])
        self.assertEqual(0,result['attemptCount'])
        self.assertIsNone(result['usage'])

    def test_economy_review_uses_spark_and_default_preserves_quality(self):
        quality = self.invoke()
        economy = self.invoke(payload=dict(self.payload, reviewProfile='economy'))
        self.assertTrue(economy['ok'], economy['reason'])
        self.assertEqual('gpt-5.3-codex-spark', economy['model'])
        self.assertEqual('gpt-5.5', quality['model'])
        self.assertNotEqual(quality['optionsHash'], economy['optionsHash'])
        self.assertEqual(1, economy['attemptCount'])

    def test_economy_never_falls_back_on_missing_unsafe_or_mismatched_model(self):
        for scenario in ('missing_spark', 'metadata', 'model_mismatch'):
            with self.subTest(scenario=scenario):
                result = self.invoke(scenario, payload=dict(self.payload, reviewProfile='economy'))
                self.assertEqual('unsupported-child-isolation', result['reason'])
                self.assertEqual(0, result['attemptCount'])

    def test_review_profiles_are_closed_and_do_not_expand_shared_grok_validation(self):
        for value in (None, True, [], {}, 'other', 'gpt-6-astra'):
            with self.subTest(value=value):
                result = self.invoke(payload=dict(self.payload, reviewProfile=value))
                self.assertEqual('invalid-input', result['reason'])
                self.assertEqual([], self.children)
        with self.assertRaises(adapter.Rejected):
            adapter.validate_input(dict(self.payload, reviewProfile='economy'))

    def test_unknown_usage_is_null(self):
        result=self.invoke('no_usage')
        self.assertTrue(result['ok'],result['reason'])
        self.assertIsNone(result['usage'])
        self.assertEqual('not_observed',result['usageReason'])

    def test_invalid_input_does_not_spawn(self):
        cases=[dict(self.payload,executable='elsewhere'),dict(self.payload,timeoutMs=True),
               dict(self.payload,timeoutMs=5000.0),dict(self.payload,timeoutMs=4999),
               dict(self.payload,requestId='bad/id'),dict(self.payload,changeSummary='x'*4001),
               dict(self.payload,reviewReason=''),dict(self.payload,evidence=self.payload['evidence']*9),
               dict(self.payload,evidence=[dict(self.payload['evidence'][0],excerpt='가'*9000)]),
               dict(self.payload,evidence=[dict(self.payload['evidence'][0],relativePath='../secret')])]
        for payload in cases:
            with self.subTest(case=len(adapter.canonical(payload))):
                result=self.invoke(payload=payload)
                self.assertFalse(result['ok'])
                self.assertEqual([],self.children)

    def test_unsupported_metadata_or_settings_prevent_generation(self):
        for scenario in ('metadata','enabled','auth_missing','auth_mismatch'):
            with self.subTest(scenario=scenario):
                result=self.invoke(scenario)
                self.assertFalse(result['ok'])
                self.assertEqual(0,result['attemptCount'])

    def test_final_and_terminal_are_both_required(self):
        for scenario in ('foreign_id','long_claim','extra','too_many','secret','missing_final','failed','exit','partial_eof'):
            with self.subTest(scenario=scenario):
                result=self.invoke(scenario)
                self.assertFalse(result['ok'])
                self.assertEqual(1,result['attemptCount'])
                self.assertEqual([],result['findings'])
                self.assertNotIn('must-not-leak',json.dumps(result))

    def test_tool_events_and_requests_fail_closed(self):
        for scenario in ('tool','request'):
            with self.subTest(scenario=scenario):
                result=self.invoke(scenario)
                self.assertFalse(result['ok'])
                self.assertTrue(result['reason'].startswith('unexpected-tool-'))

    def test_stream_limits_and_noise(self):
        for scenario in ('line','flood','noise'):
            with self.subTest(scenario=scenario):
                result=self.invoke(scenario)
                self.assertFalse(result['ok'])
                self.assertIn(result['reason'],('output-limit-exceeded','malformed-event'))

    def test_parent_budget_and_cancellation_cleanup(self):
        result=self.invoke('timeout',budget=5.5)
        self.assertEqual('timeout',result['reason'])
        cancel=threading.Event()
        timer=threading.Timer(.3,cancel.set)
        timer.start()
        try:
            result=self.invoke('timeout',cancel=cancel)
            self.assertEqual('cancelled',result['status'])
        finally:
            timer.cancel()

    def test_blocked_stdin_obeys_deadline_and_cancellation(self):
        payload=dict(self.payload,evidence=[dict(self.payload['evidence'][0],excerpt='x'*24000)])
        marker=self.fake.with_suffix('.ready')
        for cancelled in (False,True):
            with self.subTest(cancelled=cancelled):
                marker.unlink(missing_ok=True)
                cancel=threading.Event()
                def signal_after_ready():
                    deadline=time.monotonic()+2
                    while not marker.exists() and time.monotonic()<deadline: time.sleep(.01)
                    time.sleep(.1)
                    cancel.set()
                trigger=threading.Thread(target=signal_after_ready) if cancelled else None
                # An outer test watchdog owns only this fixture's children and
                # makes a missing in-adapter deadline fail promptly, never hang.
                watchdog_fired=threading.Event()
                def stop_fixture():
                    watchdog_fired.set()
                    for owned in tuple(self.owners): owned.close()
                watchdog=threading.Timer(2,stop_fixture)
                watchdog.start()
                if trigger: trigger.start()
                started=time.monotonic()
                try:
                    result=self.invoke('blocked_input',payload=payload,budget=5.6,cancel=cancel)
                finally:
                    watchdog.cancel(); watchdog.join(timeout=3)
                    if trigger: trigger.join(timeout=3)
                self.assertTrue(marker.exists())
                self.assertFalse(watchdog_fired.is_set(),'stdin write escaped the adapter deadline')
                self.assertLess(time.monotonic()-started,1.5)
                self.assertEqual('cancelled' if cancelled else 'timeout',result['reason'])
                self.assertTrue(result['lineage']['childCleanupConfirmed'])
                self.assertFalse(any(t.name=='awx-review-stdin' and t.is_alive() for t in threading.enumerate()))

    def test_direct_review_and_recursive_review_are_blocked(self):
        instance=adapter.Adapter((sys.executable,str(self.fake),'ok'),self.cli_hash,self.config,self.owner)
        result=instance.run(self.payload)
        self.assertEqual('worker-ownership-required',result['reason'])
        with patch.dict(os.environ,AWX_CODEX_REVIEW_DEPTH='1'):
            result=self.invoke()
        self.assertEqual('recursive-invocation-blocked',result['reason'])
        self.assertEqual([],self.children)

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Job ownership contract')
    def test_nested_cli_child_is_reaped_on_timeout_and_cancel(self):
        import ctypes
        from ctypes import wintypes as w
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]
        kernel.OpenProcess.restype=w.HANDLE
        kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD]
        kernel.CloseHandle.argtypes=[w.HANDLE]
        marker=self.fake.with_suffix('.pid')
        for cancel_requested in (False,True):
            with self.subTest(cancel=cancel_requested):
                marker.unlink(missing_ok=True)
                cancel=threading.Event()
                def cancel_after_child():
                    deadline=time.monotonic()+2
                    while not marker.exists() and time.monotonic()<deadline:
                        time.sleep(.01)
                    cancel.set()
                trigger=threading.Thread(target=cancel_after_child) if cancel_requested else None
                if trigger: trigger.start()
                try:
                    result=self.invoke('nested_timeout',budget=6,cancel=cancel)
                finally:
                    if trigger: trigger.join(timeout=3)
                self.assertEqual('cancelled' if cancel_requested else 'timeout',result['reason'])
                self.assertTrue(marker.exists(),'fake CLI never created its child')
                handle=kernel.OpenProcess(0x100000,False,int(marker.read_text()))
                if handle:
                    try: self.assertEqual(0,kernel.WaitForSingleObject(handle,3000))
                    finally: kernel.CloseHandle(handle)
                self.assertTrue(result['lineage']['childCleanupConfirmed'])

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Job ownership contract')
    def test_job_assignment_failure_closes_child_before_input_delivery(self):
        import ctypes
        real_dll=ctypes.WinDLL
        real_popen=subprocess.Popen
        children=[]
        class AssignmentFailure:
            def __init__(self):
                self.actual=real_dll('kernel32',use_last_error=True)
                self.AssignProcessToJobObject=lambda *args: 0
            def __getattr__(self,name): return getattr(self.actual,name)
        def capture(*args,**kwargs):
            child=real_popen(*args,**kwargs)
            children.append(child)
            return child
        with patch.object(ctypes,'WinDLL',return_value=AssignmentFailure()), patch.object(subprocess,'Popen',side_effect=capture):
            with self.assertRaisesRegex(RuntimeError,'worker_ownership_failed'):
                server.OwnedWorker([sys.executable,'-c','import sys; sys.stdin.buffer.read()'],binary=True)
        self.assertEqual(1,len(children))
        self.assertIsNotNone(children[0].poll())
        for pipe in (children[0].stdin,children[0].stdout,children[0].stderr): pipe.close()

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Job ownership contract')
    def test_nested_cli_cannot_break_away_from_its_job(self):
        import ctypes
        from ctypes import wintypes as w
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]; kernel.OpenProcess.restype=w.HANDLE
        kernel.IsProcessInJob.argtypes=[w.HANDLE,w.HANDLE,ctypes.POINTER(w.BOOL)]
        kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD]
        kernel.TerminateProcess.argtypes=[w.HANDLE,w.UINT]; kernel.CloseHandle.argtypes=[w.HANDLE]
        marker=self.fake.with_suffix('.pid'); outcome=self.fake.with_suffix('.breakaway')
        observed={}; cancel=threading.Event()
        def observe_then_cancel():
            deadline=time.monotonic()+2
            while not outcome.exists() and time.monotonic()<deadline: time.sleep(.01)
            if marker.exists():
                handle=kernel.OpenProcess(0x100000|0x1000|1,False,int(marker.read_text()))
                observed['handle']=handle
                member=w.BOOL()
                observed['queryOk']=bool(kernel.IsProcessInJob(handle,self.owners[-1].job,ctypes.byref(member)))
                observed['sameJob']=bool(member.value)
                cancel.set()
        watcher=threading.Thread(target=observe_then_cancel); watcher.start()
        try:
            result=self.invoke('breakaway',cancel=cancel)
            watcher.join(timeout=3)
            self.assertTrue(outcome.exists())
            if outcome.read_text()=='blocked':
                self.assertTrue(result['ok'],result['reason'])
            else:
                self.assertTrue(observed.get('queryOk'))
                self.assertTrue(observed.get('sameJob'),'created child escaped its specific owned Job')
                self.assertEqual('cancelled',result['reason'])
                self.assertEqual(0,kernel.WaitForSingleObject(observed['handle'],3000))
        finally:
            watcher.join(timeout=3)
            if observed.get('handle'):
                if kernel.WaitForSingleObject(observed['handle'],0)==258:
                    kernel.TerminateProcess(observed['handle'],1)
                    kernel.WaitForSingleObject(observed['handle'],3000)
                kernel.CloseHandle(observed['handle'])

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Job ownership contract')
    def test_mcp_parent_eof_reaps_adapter_cli_and_nested_child(self):
        import ctypes
        from ctypes import wintypes as w
        fixture=self.path/'adapter_server.py'
        fixture.write_text('''import sys, time
sys.path.insert(0,%r)
import awx_mcp_stdio_server as server
import awx_codex_review_adapter as adapter
def review(args):
    return adapter.Adapter((sys.executable,%r,'nested_timeout'),%r,%r,server.OwnedWorker).run(args)
server.HANDLERS['codex_review_change']=review
if '--tool-worker' in sys.argv: raise SystemExit(server.tool_worker())
raise SystemExit(server.StdioSession(worker_command=[sys.executable,'-B',__file__,'--tool-worker'],request_timeout=10).run())
''' % (str(ROOT/'scripts'),str(self.fake),self.cli_hash,str(self.config)),encoding='utf8')
        owner=server.OwnedWorker([sys.executable,'-B',str(fixture)],capture=True)
        marker=self.fake.with_suffix('.pid')
        try:
            owner.process.stdin.write(json.dumps({'jsonrpc':'2.0','id':41,'method':'tools/call',
                'params':{'name':'codex_review_change','arguments':self.payload}})+'\n')
            owner.process.stdin.flush()
            deadline=time.monotonic()+3
            while not marker.exists() and time.monotonic()<deadline: time.sleep(.01)
            self.assertTrue(marker.exists(),'adapter never reached its nested fake child')
            kernel=ctypes.WinDLL('kernel32',use_last_error=True)
            kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]; kernel.OpenProcess.restype=w.HANDLE
            kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD]; kernel.CloseHandle.argtypes=[w.HANDLE]
            handle=kernel.OpenProcess(0x100000,False,int(marker.read_text()))
            self.assertTrue(handle)
            try:
                self.assertEqual(258,kernel.WaitForSingleObject(handle,0))
                owner.process.stdin.close()
                self.assertEqual(0,owner.process.wait(timeout=5))
                self.assertEqual(0,kernel.WaitForSingleObject(handle,3000))
                self.assertEqual('',owner.process.stdout.read())
            finally: kernel.CloseHandle(handle)
        finally:
            self.assertTrue(owner.close())
            for pipe in (owner.process.stdin,owner.process.stdout,owner.process.stderr): pipe.close()


if __name__=='__main__':
    unittest.main()
