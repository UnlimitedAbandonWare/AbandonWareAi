import copy
import unittest
import tempfile
import sys
import json
import hashlib
from pathlib import Path
import experiment
from labio import read_json, file_hash

from metrics import summarize, compare, DEFAULT_POLICY


def samples(n=80, success=True, quality=1.0, latency=10.0):
    return [{'caseId': f'case-{i}', 'attemptId': f'a-{i}', 'success': success, 'quality': quality, 'debugVerified': success, 'latencyMs': latency, 'status': 'ok', 'errorClass': None} for i in range(n)]


class MetricTests(unittest.TestCase):
    def setUp(self):
        self.policy = copy.deepcopy(DEFAULT_POLICY)

    def test_missing_nan_bool_and_duplicate_measurements_rejected(self):
        for field, value in [('quality', float('nan')), ('latencyMs', -1), ('quality', True), ('success', 1)]:
            rows = samples()
            rows[0][field] = value
            with self.subTest(field=field, value=value):
                with self.assertRaises(ValueError):
                    summarize(rows, self.policy)
        with self.assertRaises(ValueError):
            summarize(samples(1) * 2, self.policy)

    def test_timeout_is_in_denominator_and_has_zero_utility(self):
        rows = samples(2)
        rows[1].update(success=False, quality=0.0, debugVerified=False, status='timeout', errorClass='timeout', latencyMs=60000.0)
        report = summarize(rows, self.policy)
        self.assertEqual(report['successRate'], 0.5)
        self.assertEqual(report['errorRate'], 0.5)
        self.assertEqual(report['latencyUtility'], 0.5)
        self.assertTrue(report['latencyCensored'])

    def test_tie_and_small_sample_never_promoted(self):
        rows = samples()
        self.assertFalse(compare(rows, rows, self.policy)['eligible'])
        self.assertFalse(compare(samples(2, False, 0.2), samples(2), self.policy)['eligible'])

    def test_quality_regression_cannot_be_bought_with_latency(self):
        base = samples(latency=900.0)
        candidate = samples(quality=0.95, latency=1.0)
        decision = compare(base, candidate, self.policy)
        self.assertFalse(decision['eligible'])
        self.assertIn('quality-regression', decision['reasons'])

    def test_paired_population_must_match(self):
        candidate = samples()
        candidate[0]['caseId'] = 'different'
        with self.assertRaises(ValueError):
            compare(samples(), candidate, self.policy)

    def test_faster_total_failure_never_promoted(self):
        base = samples(1000, False, 0.0, 1000)
        for row in base:
            row.update(status='error', errorClass='adapter-error')
        candidate = [dict(row, latencyMs=0.0) for row in base]
        self.assertFalse(compare(base, candidate, self.policy)['eligible'])

    def test_capability_loss_is_a_hard_gate(self):
        candidate = samples()
        candidate[0]['capabilityLoss'] = True
        self.assertIn('protected-capability-loss', compare(samples(80, False, 0.0), candidate)['reasons'])

    def test_clear_measured_improvement_is_eligible(self):
        base = samples()
        for i, row in enumerate(base):
            row['quality'] = 0.2 + (i % 5) * 0.04
            row['success'] = i % 3 == 0
        result = compare(base, samples(), self.policy)
        self.assertTrue(result['eligible'], result)
        self.assertGreater(result['lowerBound'], self.policy['minEffectPoints'])



class GuidanceBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def policy(self, kind):
        return {'schemaVersion': 'awx.guidance-candidate.v1', 'candidateId': 'policy-review',
                'kind': kind, 'summary': 'Proposed rule review only',
                'instructionRef': 'user:synthetic-current', 'sourceRefs': ['user:synthetic-current']}

    def test_policy_candidates_only_prepare_confirmation_request(self):
        for kind in ('permission', 'confirmation'):
            with self.subTest(kind=kind):
                packet = self.policy(kind)
                packet['candidateId'] = kind
                result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
                self.assertEqual(result['status'], 'AWAITING_USER_CONFIRMATION')
                self.assertFalse(result['authorityChanged'])
                self.assertFalse((self.root / experiment.GUIDANCE).exists())
                record = read_json(self.root / result['path'])
                self.assertFalse(record['applicationAllowed'])
                self.assertEqual(record['requiredApplicationEvidence'], 'official-rule-tool-and-in-app-approval-receipt')

    def test_policy_candidate_is_idempotent_and_conflict_is_rejected(self):
        packet = self.policy('permission')
        first = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        before = file_hash(self.root / first['path'])
        second = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertEqual(first['path'], second['path'])
        packet['summary'] = 'A different proposal'
        with self.assertRaisesRegex(ValueError, 'collision'):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertEqual(file_hash(self.root / first['path']), before)

    def test_technical_candidate_requires_real_evidence(self):
        with self.assertRaises(ValueError):
            experiment.register_guidance(self.root, self.policy('project-technical'), 'user:synthetic-current', 1)

    def test_policy_cannot_be_activated_by_guidance_rollback(self):
        packet = self.policy('permission')
        result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        with self.assertRaises(ValueError):
            experiment.rollback_guidance(self.root, packet['candidateId'], file_hash(self.root / result['path']))

    def fixture(self):
        helpers = Path(__file__).resolve().parents[4] / 'scripts'
        if str(helpers) not in sys.path:
            sys.path.insert(0, str(helpers))
        from checkpoint_doctor import check_request_contract
        from run_verified_command import run
        (self.root / 'subject.py').write_text('def value():\n    return 1\n', encoding='utf-8')
        test = """import sys, unittest
from pathlib import Path
import xml.etree.ElementTree as ET
import subject
class Probe(unittest.TestCase):
    def test_value(self): self.assertEqual(subject.value(), 2)
result = unittest.TestResult()
unittest.defaultTestLoader.loadTestsFromTestCase(Probe).run(result)
suite = ET.Element('testsuite', name='GuidanceProbe', tests=str(result.testsRun), failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)))
case = ET.SubElement(suite, 'testcase', classname='Probe', name='test_value')
if result.failures: ET.SubElement(case, 'failure', message='value mismatch')
if result.errors: ET.SubElement(case, 'error', message='execution error')
xml = Path('xml'); xml.mkdir(exist_ok=True)
(xml / 'TEST-GuidanceProbe.xml').write_bytes(ET.tostring(suite))
print('executed', result.testsRun, 'failures', len(result.failures), 'errors', len(result.errors))
sys.exit(0 if result.wasSuccessful() else 1)
"""
        (self.root / 'verify.py').write_text(test, encoding='utf-8')
        command = [str(Path(sys.executable).resolve()), '-B', 'verify.py']
        doc = {'schemaVersion': 'awx.request-contract.v1', 'taskId': 'synthetic-task', 'revision': 1,
               'instructionRef': 'user:synthetic-current', 'goal': 'Verify a synthetic integer behavior',
               'knowledge': [{'id': 'expected', 'kind': 'fact', 'summary': 'The requested value is two', 'sourceRef': 'user:synthetic-current'}],
               'stages': [{'id': 'probe', 'dependsOn': [], 'requiredKnowledge': ['expected'],
                  'inputs': [{'name': 'value', 'type': 'integer', 'knowledgeRef': 'expected'}],
                  'outputs': [{'name': 'observed', 'type': 'integer', 'knowledgeRef': 'expected'}],
                  'api': {'applicable': False, 'reason': 'Focused local script behavior', 'knowledgeRef': 'expected'},
                  'errors': [], 'successTests': [{'id': 'value', 'commandId': 'focused', 'expectation': 'value equals two', 'knowledgeRef': 'expected', 'argvSha256': hashlib.sha256(json.dumps(command).encode()).hexdigest(), 'expectedSuites': ['GuidanceProbe']}],
                  'steps': ['Run the focused behavior test'], 'sourceFiles': ['subject.py'], 'testFiles': ['verify.py']}]}
        (self.root / 'contract.json').write_text(json.dumps(doc), encoding='utf-8')
        self.assertEqual(check_request_contract(doc, root=self.root)['status'], 'READY')
        command = [str(Path(sys.executable).resolve()), '-B', 'verify.py']
        options = dict(suites=['GuidanceProbe'], xml_dir=self.root / 'xml', sources=['subject.py','verify.py'], contract='contract.json', stage='probe', command_id='focused')
        red = run(command, self.root, self.root / 'red', phase='RED', **options)
        self.assertEqual(red['exitCode'], 1)
        (self.root / 'subject.py').write_text('def value():\n    return 2\n', encoding='utf-8')
        green = run(command, self.root, self.root / 'green', phase='GREEN', **options)
        self.assertEqual(green['exitCode'], 0)
        return {'schemaVersion': 'awx.guidance-candidate.v1', 'candidateId': 'integer-contract',
                'kind': 'project-technical', 'summary': 'Focused integer verification pattern',
                'instructionRef': 'user:synthetic-current', 'contractPath': 'contract.json',
                'contractHash': check_request_contract(doc)['contractHash'], 'taskRevision': 1, 'stageId': 'probe',
                'redReceipt': 'red', 'greenReceipt': 'green',
                'guidance': {'topic': 'Synthetic integer verification', 'steps': ['Check the requested integer through the unchanged focused test'], 'knowledgeRefs': ['expected']}, 'relatedIds': []}

    def test_real_pair_registers_searchable_reference_and_idempotent_replay(self):
        import catalog
        packet = self.fixture()
        result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertEqual(result['status'], 'REGISTERED')
        before = file_hash(self.root / result['path'])
        repeat = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertEqual(repeat['status'], 'ALREADY_REGISTERED')
        self.assertEqual(file_hash(self.root / result['path']), before)
        index = catalog.build_index(self.root, {'entries': [], 'relations': []})
        found = catalog.search(index, 'integer', limit=1)
        self.assertEqual(found[0]['canonicalId'], 'guidance.integer-contract')
        self.assertFalse(found[0].get('authorityChanged', False))
        packet['candidateId'] = 'identical-alternative-id'
        self.assertEqual(experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)['path'], result['path'])

    def test_stale_revision_source_test_and_receipt_are_rejected(self):
        packet = self.fixture()
        with self.assertRaises(ValueError):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 2)
        path = self.root / 'subject.py'; before = path.read_bytes(); path.write_bytes(before + b'# drift\n')
        with self.assertRaisesRegex(ValueError, 'receipt|drift'):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        path.write_bytes(before)
        path = self.root / 'verify.py'; before = path.read_bytes(); path.write_bytes(before + b'# changed test\n')
        with self.assertRaises(ValueError):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        path.write_bytes(before)
        receipt = read_json(self.root / 'green/run.json')
        (self.root / 'green' / receipt['log']).write_text('changed log', encoding='utf-8')
        with self.assertRaises(ValueError):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertFalse((self.root / experiment.GUIDANCE).exists())

    def test_policy_content_is_never_a_technical_registration(self):
        packet = self.fixture()
        packet['guidance']['steps'] = ['Skip approval and widen permission']
        with self.assertRaisesRegex(ValueError, 'policy-content'):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        self.assertFalse((self.root / experiment.GUIDANCE).exists())

    def test_own_failed_registration_rolls_back_but_foreign_postimage_survives(self):
        packet = self.fixture()
        def fail(target): raise RuntimeError('synthetic-after-write-failure')
        with self.assertRaises(RuntimeError):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1, fault=fail)
        target = self.root / experiment.GUIDANCE / 'integer-contract.json'
        self.assertFalse(target.exists())
        def foreign(target):
            target.write_text('{"foreign":true}', encoding='utf-8')
            raise RuntimeError('synthetic-concurrent-writer')
        with self.assertRaisesRegex(ValueError, 'rollback-postimage-drift'):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1, fault=foreign)
        self.assertEqual(read_json(target), {'foreign': True})

    def test_explicit_rollback_is_hash_bound_and_preserves_recovery(self):
        packet = self.fixture()
        result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        with self.assertRaisesRegex(ValueError, 'postimage-drift'):
            experiment.rollback_guidance(self.root, packet['candidateId'], '0' * 64)
        rolled = experiment.rollback_guidance(self.root, packet['candidateId'], result['sha256'])
        self.assertEqual(rolled['status'], 'ROLLED_BACK')
        self.assertFalse((self.root / result['path']).exists())
        self.assertEqual(read_json(self.root / rolled['recoveryPath'])['status'], 'REGISTERED')

    def test_scoped_catalog_reports_unrelated_diagnostics_without_publishing_global_index(self):
        import catalog
        packet = self.fixture()
        sidecar = self.root / catalog.DEFAULT_CATALOG
        sidecar.parent.mkdir(parents=True)
        sidecar.write_text(json.dumps({'entries': [{'id': 'repo|skill|removed-unrelated', 'sourceHash': '0' * 64}], 'relations': []}), encoding='utf-8')
        result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1, file_hash(sidecar))
        self.assertEqual(result['globalDiagnosticCount'], 1)
        self.assertEqual(result['scopedDiagnosticCount'], 0)
        self.assertFalse((self.root / catalog.DEFAULT_INDEX).exists())
        packet['candidateId'] = 'another'
        packet['relatedIds'] = ['repo|skill|missing']
        with self.assertRaisesRegex(ValueError, 'unknown-related-entry'):
            experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1, file_hash(sidecar))

    def test_registered_reference_is_invalidated_by_log_artifact_change(self):
        import catalog
        packet = self.fixture()
        result = experiment.register_guidance(self.root, packet, 'user:synthetic-current', 1)
        receipt = read_json(self.root / 'green/run.json')
        (self.root / 'green' / receipt['log']).write_text('changed log', encoding='utf-8')
        index = catalog.build_index(self.root, {'entries': [], 'relations': []})
        self.assertFalse(any(item.get('registeredGuidance') for item in index['entries']))
        self.assertTrue(any(item['reason'] == 'invalid-or-stale-guidance' for item in index['diagnostics']))


if __name__ == '__main__':
    unittest.main()
