"""Behavioral acceptance for cooperative device work; no real source/provider writes."""
import concurrent.futures
import importlib.util
import json
import multiprocessing
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

AVAILABLE = importlib.util.find_spec('scripts.awx_device_work') is not None
if AVAILABLE:
    from scripts.awx_device_work import WorkQueue
    from scripts.awx_device_policy import rank
    from scripts.awx_shared_state import Conflict


def device(name='desktop-1', role='desktop', **extra):
    return dict(deviceId=name, role=role, system='Windows', arch='x86_64', cpuCount=16,
                memoryAvailableMb=24000, cpuLoad=0.1, gpuBackends=['cuda'],
                observedAt=time.time(), maxWorkers=2, **extra)


def race_claim(root, task_id, device_id):
    from scripts.awx_device_work import WorkQueue
    try:
        return WorkQueue(Path(root)).claim(task_id, device_id)['assignedDevice']
    except Exception:
        return 'conflict'


class FeaturePresenceTest(unittest.TestCase):
    def test_durable_device_queue_exists(self):
        self.assertTrue(AVAILABLE, 'missing durable device-aware SMB work queue')


@unittest.skipUnless(AVAILABLE, 'implementation absent: explicit presence RED above')
class QueueTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root/'main').mkdir()
        (self.root/'main/a.txt').write_text('alpha', encoding='utf-8')
        (self.root/'main/b.txt').write_text('beta', encoding='utf-8')
        self.queue = WorkQueue(self.root)
        self.queue.publish(device())
        self.queue.publish(device('notebook-1', 'notebook'))

    def tearDown(self):
        self.tmp.cleanup()

    def spec(self, task='work-1', **extra):
        result = dict(taskId=task, kind='research', workloadKey='fixture-v1',
                      targets=['main/a.txt'], requirements={}, timeoutSeconds=300)
        result.update(extra)
        return result

    def test_replay_and_changed_id_fail_closed(self):
        first = self.queue.enqueue(self.spec())
        self.assertEqual(self.queue.enqueue(self.spec())['specHash'], first['specHash'])
        with self.assertRaises(Conflict):
            self.queue.enqueue(self.spec(kind='build'))

    def test_completed_task_is_durable_and_not_reclaimable(self):
        self.queue.enqueue(self.spec())
        self.queue.claim('work-1', 'notebook-1')
        self.queue.start('work-1', 'notebook-1')
        result = self.queue.complete('work-1', 'notebook-1', 'succeeded')
        self.assertEqual(result['status'], 'succeeded')
        self.assertGreaterEqual(result['durationSeconds'], 0)
        other = WorkQueue(self.root)
        self.assertEqual(other.enqueue(self.spec())['status'], 'succeeded')
        with self.assertRaises(Conflict):
            other.claim('work-1', 'desktop-1')

    def test_dependencies_wait_for_success(self):
        self.queue.enqueue(self.spec('parent'))
        self.queue.enqueue(self.spec('child', dependsOn=['parent']))
        with self.assertRaises(Conflict):
            self.queue.claim('child', 'notebook-1')
        self.queue.claim('parent', 'notebook-1')
        self.queue.start('parent', 'notebook-1')
        self.queue.complete('parent', 'notebook-1', 'succeeded')
        self.assertEqual(self.queue.claim('child', 'notebook-1')['status'], 'claimed')

    def test_missing_dependency_and_cycle_are_rejected(self):
        for deps in [['absent'], ['work-1']]:
            with self.assertRaises(Conflict):
                self.queue.enqueue(self.spec(dependsOn=deps))

    def test_disjoint_tasks_run_concurrently(self):
        self.queue.enqueue(self.spec('first'))
        self.queue.enqueue(self.spec('second', targets=['main/b.txt']))
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(self.queue.claim, t, 'notebook-1') for t in ['first', 'second']]
            self.assertEqual([f.result()['status'] for f in futures], ['claimed', 'claimed'])

    def test_multiprocess_same_task_has_one_owner(self):
        self.queue.enqueue(self.spec())
        with concurrent.futures.ProcessPoolExecutor(2, mp_context=multiprocessing.get_context('spawn')) as pool:
            futures = [pool.submit(race_claim, str(self.root), 'work-1', d)
                       for d in ['desktop-1', 'notebook-1']]
            results = [f.result(timeout=30) for f in futures]
        self.assertEqual(results.count('conflict'), 1)
        self.assertEqual(sum(x in ['desktop-1', 'notebook-1'] for x in results), 1)

    def test_capacity_and_resources_block_only_affected_task(self):
        one = device('small', 'notebook'); one['maxWorkers'] = 1
        self.queue.publish(one)
        self.queue.enqueue(self.spec('a', resources=['shared-output']))
        self.queue.enqueue(self.spec('b', resources=['shared-output']))
        self.queue.enqueue(self.spec('c', targets=['main/b.txt']))
        self.queue.claim('a', 'small')
        with self.assertRaises(Conflict): self.queue.claim('c', 'small')
        with self.assertRaises(Conflict): self.queue.claim('b', 'desktop-1')
        self.assertEqual(self.queue.claim('c', 'desktop-1')['status'], 'claimed')

    def test_changed_inputs_fail_start_and_read_completion(self):
        self.queue.enqueue(self.spec())
        self.queue.claim('work-1', 'notebook-1')
        (self.root/'main/a.txt').write_text('changed')
        with self.assertRaises(Conflict): self.queue.start('work-1', 'notebook-1')
        self.queue.enqueue(self.spec('fresh'))
        self.queue.claim('fresh', 'notebook-1')
        self.queue.start('fresh', 'notebook-1')
        (self.root/'main/a.txt').write_text('changed again')
        result = self.queue.complete('fresh', 'notebook-1', 'succeeded')
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['reasonCode'], 'read-input-drift')

    def test_context_hash_is_rechecked_and_never_copied(self):
        (self.root/'directive.md').write_text('local-only context')
        self.queue.enqueue(self.spec(context=['directive.md']))
        self.queue.claim('work-1', 'desktop-1')
        (self.root/'directive.md').write_text('changed context')
        with self.assertRaises(Conflict): self.queue.start('work-1', 'desktop-1')
        raw = ''.join(p.read_text() for p in self.queue.base.rglob('*.json'))
        self.assertNotIn('local-only context', raw)

    def test_source_edit_requires_existing_verified_lease(self):
        self.queue.enqueue(self.spec(kind='light-edit'))
        self.queue.claim('work-1', 'desktop-1')
        with self.assertRaises(Conflict): self.queue.start('work-1', 'desktop-1')
        with mock.patch.object(self.queue, '_verify_source_lease', return_value={'path':'lease','sha256':'a'*64}):
            self.queue.start('work-1', 'desktop-1', lease='existing-lease')
        (self.root/'main/a.txt').write_text('owned edit')
        with mock.patch.object(self.queue, '_lease_alive', return_value=True):
            result = self.queue.complete('work-1', 'desktop-1', 'succeeded')
        self.assertEqual(result['changedFiles'], ['main/a.txt'])

    def test_unknown_state_lock_is_preserved(self):
        self.queue.enqueue(self.spec())
        lock = self.queue.base/'tasks/work-1/.operation'
        lock.mkdir()
        with self.assertRaises(Conflict): self.queue.claim('work-1', 'notebook-1')
        self.assertTrue(lock.is_dir())

    def test_path_alias_traversal_and_credential_targets_rejected(self):
        for targets in [['../outside'], ['/absolute'], ['main/a.txt', 'MAIN/A.TXT'],
                        ['main/../main/a.txt'], ['.env'], ['file:stream'], ['CON']]:
            with self.subTest(targets=targets), self.assertRaises(Conflict):
                self.queue.enqueue(self.spec(targets=targets))

    def test_terminal_owner_and_invalid_transition(self):
        self.queue.enqueue(self.spec())
        with self.assertRaises(Conflict): self.queue.complete('work-1', 'desktop-1', 'succeeded')
        self.queue.claim('work-1', 'notebook-1')
        with self.assertRaises(Conflict): self.queue.start('work-1', 'desktop-1')

    def test_stale_claim_is_reported_without_requeue(self):
        self.queue.enqueue(self.spec(timeoutSeconds=1))
        self.queue.claim('work-1', 'notebook-1')
        with mock.patch('scripts.awx_device_work.time.time', return_value=time.time()+1000):
            row = self.queue.status()['tasks'][0]
        self.assertTrue(row['needsReconciliation'])
        self.assertEqual(row['status'], 'claimed')

    def test_invalid_profile_and_spec_values_rejected(self):
        for field, value in [('cpuLoad', float('nan')), ('cpuCount', -1), ('maxWorkers', 0)]:
            bad = device(); bad[field] = value
            with self.subTest(field=field), self.assertRaises(Conflict): self.queue.publish(bad)
        with self.assertRaises(Conflict): self.queue.enqueue(self.spec(command='arbitrary command'))


@unittest.skipUnless(AVAILABLE, 'implementation absent')
class RoutingTest(unittest.TestCase):
    def setUp(self):
        self.now = time.time()
        self.desktop = device()
        self.notebook = device('notebook-1', 'notebook')
        self.mac = device('macmini-1', 'macmini')
        self.mac.update(system='Darwin', arch='arm64', gpuBackends=['metal'])
        self.task = dict(kind='research', workloadKey='fixture-v1', requirements={}, targets=[])

    def selected(self, devices=None, history=None, preferred=None):
        return rank(self.task, devices or [self.desktop,self.notebook,self.mac], history or [], self.now, preferred)

    def samples(self, mac_seconds=7):
        return [dict(deviceId=d, workloadKey='fixture-v1', requirements={}, kind='research',
                     durationSeconds=duration, transferSeconds=0, endedAt=self.now-i,
                     status='succeeded') for d,duration in [('desktop-1',10),('macmini-1',mac_seconds)] for i in range(3)]

    def test_default_roles_and_desktop_final_integration(self):
        self.assertEqual(self.selected()['selectedDevice'], 'notebook-1')
        self.task['kind']='build'
        self.assertEqual(self.selected()['selectedDevice'], 'desktop-1')
        self.task['kind']='integration'
        self.assertIsNone(self.selected([self.notebook,self.mac])['selectedDevice'])

    def test_unknown_and_stale_capacity_never_treated_as_idle(self):
        self.desktop['observedAt']=self.now-301
        self.notebook['cpuLoad']=None
        self.assertIsNone(self.selected()['selectedDevice'])

    def test_gpu_ram_cpu_os_filters_and_manual_cannot_bypass(self):
        self.task['requirements']={'gpuBackend':'cuda','memoryMb':30000,'cpuCount':32,'system':'Windows'}
        self.assertIsNone(self.selected(preferred='desktop-1')['selectedDevice'])

    def test_manual_mac_and_native_arm_eligibility(self):
        self.assertEqual(self.selected(preferred='macmini-1')['selectedDevice'],'macmini-1')
        self.task['requirements']={'arch':'arm64'}
        self.assertEqual(self.selected()['selectedDevice'],'macmini-1')

    def test_mac_auto_requires_three_comparable_faster_samples(self):
        self.assertNotEqual(self.selected(history=self.samples()[:-1])['selectedDevice'],'macmini-1')
        self.assertEqual(self.selected(history=self.samples())['selectedDevice'],'macmini-1')
        self.assertNotEqual(self.selected(history=self.samples(9.5))['selectedDevice'],'macmini-1')

    def test_history_mismatch_failure_and_transfer_not_speedup(self):
        for field,value in [('workloadKey','different'),('status','failed'),('transferSeconds',10),
                            ('requirements',{'arch':'arm64'})]:
            history=self.samples()
            for sample in history:
                if sample['deviceId']=='macmini-1': sample[field]=value
            self.assertNotEqual(self.selected(history=history)['selectedDevice'],'macmini-1')

    def test_high_current_load_changes_assignment(self):
        self.notebook['cpuLoad']=0.99
        self.assertEqual(self.selected()['selectedDevice'],'desktop-1')


if __name__=='__main__': unittest.main()
