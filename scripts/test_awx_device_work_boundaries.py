"""Output handoff, admission, integrity and existing source-guard boundary tests."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock

from scripts import test_awx_device_work as fixtures
from scripts import test_scoped_blocker_recovery as guards
from scripts.awx_device_work import WorkQueue, device_work
from scripts.awx_device_policy import probe
from scripts.awx_shared_state import Conflict


class WorkBoundaryTest(unittest.TestCase):
    setUp=fixtures.QueueTest.setUp
    tearDown=fixtures.QueueTest.tearDown
    spec=fixtures.QueueTest.spec

    def test_directive_output_is_hashed_and_handed_off(self):
        self.queue.enqueue(self.spec(kind='directive',outputs=['directive.md']))
        packet=self.queue.claim('work-1','notebook-1')
        self.queue.start('work-1','notebook-1')
        output=self.root/packet['outputs'][0]['path']
        output.write_text('bounded local directive',encoding='utf-8')
        result=self.queue.complete('work-1','notebook-1','succeeded')
        self.assertEqual(result['changedFiles'],[packet['outputs'][0]['path']])
        self.assertEqual(result['postimages'][-1]['sha256'],hashlib.sha256(output.read_bytes()).hexdigest())
        self.queue.enqueue(self.spec('consumer',context=[str(output.relative_to(self.root)).replace('\\','/')],dependsOn=['work-1']))
        self.assertEqual(self.queue.claim('consumer','desktop-1')['status'],'claimed')

    def test_missing_declared_output_cannot_succeed(self):
        self.queue.enqueue(self.spec(outputs=['analysis.json']))
        self.queue.claim('work-1','notebook-1'); self.queue.start('work-1','notebook-1')
        row=self.queue.complete('work-1','notebook-1','succeeded')
        self.assertEqual(row['status'],'failed')
        self.assertEqual(row['reasonCode'],'declared-output-missing')

    def test_output_traversal_is_rejected(self):
        with self.assertRaises(Conflict): self.queue.enqueue(self.spec(outputs=['../escape']))

    def test_full_device_is_filtered_before_automatic_ranking(self):
        small=fixtures.device('notebook-1','notebook'); small['maxWorkers']=1
        self.queue.publish(small)
        self.queue.enqueue(self.spec('occupied')); self.queue.claim('occupied','notebook-1')
        self.queue.enqueue(self.spec('next'))
        self.assertEqual(self.queue.route('next')['selectedDevice'],'desktop-1')

    def test_shared_resource_busy_is_visible_to_router(self):
        self.queue.enqueue(self.spec('occupied',resources=['output-a'])); self.queue.claim('occupied','notebook-1')
        self.queue.enqueue(self.spec('next',resources=['output-a']))
        self.assertIsNone(self.queue.route('next')['selectedDevice'])

    def test_busy_desktop_still_supplies_measured_mac_baseline(self):
        desktop=fixtures.device(); desktop['maxWorkers']=1; self.queue.publish(desktop)
        self.queue.enqueue(self.spec('occupied')); self.queue.claim('occupied','desktop-1')
        mac=fixtures.device('macmini-1','macmini'); mac.update(system='Darwin',arch='arm64')
        self.queue.publish(mac); self.queue.enqueue(self.spec('next'))
        history=[dict(deviceId=d,workloadKey='fixture-v1',requirements={},kind='research',
                      durationSeconds=s,transferSeconds=0,endedAt=time.time()-i,status='succeeded')
                 for d,s in [('desktop-1',10),('macmini-1',7)] for i in range(3)]
        with mock.patch.object(self.queue,'history',return_value=history):
            self.assertEqual(self.queue.route('next')['selectedDevice'],'macmini-1')

    def test_spec_tampering_does_not_bypass_owned_task(self):
        self.queue.enqueue(self.spec())
        path=self.queue.base/'tasks/work-1/spec.json'
        row=json.loads(path.read_text()); row['kind']='light-edit'
        path.write_text(json.dumps(row))
        with self.assertRaises(Conflict): self.queue.claim('work-1','desktop-1')

    def test_terminal_replay_does_not_publish_new_success_time(self):
        self.queue.enqueue(self.spec()); self.queue.claim('work-1','desktop-1'); self.queue.start('work-1','desktop-1')
        first=self.queue.complete('work-1','desktop-1','succeeded')
        self.assertEqual(first['endedAt'],self.queue.complete('work-1','desktop-1','succeeded')['endedAt'])

    def test_interrupted_reservation_survives_and_other_resources_work(self):
        self.queue.enqueue(self.spec()); self.queue._reserve('resource:locked','unknown-task')
        self.queue.enqueue(self.spec('conflict',resources=['locked']))
        with self.assertRaises(Conflict): self.queue.claim('conflict','desktop-1')
        self.assertEqual(self.queue.claim('work-1','desktop-1')['status'],'claimed')

    def test_source_gate_rejects_wrong_root_and_empty_target_scope(self):
        self.queue.enqueue(self.spec(kind='light-edit'))
        lease=self.root/'__patch_drop__/source-edit-locks/fixture.lock/lease.json'
        lease.parent.mkdir(parents=True)
        lease.write_text(json.dumps(dict(mutationAllowed=True,coordinationMode='target-scoped',
             targetPaths=['main/a.txt'],role='desktop',root=str(self.root/'wrong'),topic='fixture',ownerId='fixture')))
        spec,_=self.queue._load('work-1')
        with mock.patch.object(self.queue,'_ps') as verify:
            with self.assertRaises(Conflict): self.queue._verify_source_lease(str(lease.relative_to(self.root)).replace('\\','/'),spec)
            verify.assert_not_called()

    def test_read_only_status_does_not_create_queue(self):
        with tempfile.TemporaryDirectory() as directory:
            result=device_work({'root':directory,'action':'status'})
            self.assertTrue(result['ok']); self.assertFalse((Path(directory)/'data').exists())

    def test_role_change_keeps_one_physical_host_identity(self):
        with mock.patch('scripts.awx_device_policy.shutil.which',return_value=None):
            desktop=probe(self.root,'desktop'); notebook=probe(self.root,'notebook')
        self.assertEqual(desktop['deviceId'],notebook['deviceId'])

    def test_envelope_rejects_command_and_invalid_device_spoof(self):
        self.assertFalse(device_work({'root':str(self.root),'action':'status','command':'whoami'})['ok'])
        self.assertFalse(device_work({'root':str(self.root),'action':'claim','deviceId':'foreign'})['ok'])


@unittest.skipUnless(os.name=='nt','Windows source-guard integration')
class ExistingSourceGuardTest(unittest.TestCase):
    git=guards.ScopedBlockerTests.git
    ps=guards.ScopedBlockerTests.ps
    session=guards.ScopedBlockerTests.session

    def setUp(self):
        guards.ScopedBlockerTests.setUp(self)
        for path in [guards.CONTRACT,guards.SESSION]:
            (self.root/'__patch_drop__'/path.name).write_bytes(path.read_bytes())
        self.queue=WorkQueue(self.root)
        self.queue.publish(fixtures.device())
        self.queue.enqueue(dict(taskId='source-edit',kind='light-edit',workloadKey='source-fixture',targets=['target.txt']))
        self.packet=self.queue.claim('source-edit','desktop-1')
        self.manifest=self.root/self.packet['targetManifest']

    def test_actual_source_verify_then_changed_path_completion(self):
        result=self.session(extra=f'-TargetManifest {guards.ps_quote(self.manifest)}')
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        lease='__patch_drop__/source-edit-locks/fixture.lock/lease.json'
        try:
            with mock.patch.dict(os.environ,self.env,clear=True):
                self.queue.start('source-edit','desktop-1',lease=lease)
            (self.root/'target.txt').write_text('fixture owned postimage\n')
            row=self.queue.complete('source-edit','desktop-1','succeeded')
            self.assertEqual(row['changedFiles'],['target.txt'])
        finally:
            self.session(action='end')

    def test_actual_source_verify_rejects_preimage_drift(self):
        result=self.session(extra=f'-TargetManifest {guards.ps_quote(self.manifest)}')
        self.assertEqual(result.returncode,0)
        try:
            (self.root/'target.txt').write_text('external fixture writer\n')
            with self.assertRaises(Conflict):
                self.queue.start('source-edit','desktop-1',lease='__patch_drop__/source-edit-locks/fixture.lock/lease.json')
        finally:
            self.session(action='end')


if __name__=='__main__': unittest.main()
