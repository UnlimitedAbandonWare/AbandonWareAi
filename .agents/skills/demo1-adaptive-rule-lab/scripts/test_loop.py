import tempfile
import unittest
from pathlib import Path

from labio import digest, read_json, write_json
from experiment import register, add_candidate, run, load, promote, rollback
from evaluators import evaluate, pin


class LoopTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        index = {'concepts': [{'id': 'test', 'labels': ['검증']}], 'entries': [{'id': 'a', 'canonicalId': 'alpha', 'title': 'Alpha', 'description': '', 'functions': ['test'], 'purposes': [], 'inputs': [], 'outputs': [], 'constraints': [], 'dependencies': []}]}
        write_json(self.root / 'index.json', index)
        cases = [{'caseId': f'c{i}', 'independenceGroup': f'g{i}', 'split': 'development' if i < 2 else 'confirmation', 'query': '검증', 'expectedIds': ['a']} for i in range(82)]
        write_json(self.root / 'cases.json', cases)
        self.base = {'kind': 'catalog', 'index': 'index.json', 'mode': 'title'}
        self.candidate = dict(self.base, mode='semantic')
        register(self.root, 'test', 'cases.json', self.base, 'Aliases improve retrieval.')
        add_candidate(self.root, 'test', 'c1', self.candidate, 'Use meaning aliases.', 'Add reviewed aliases.')

    def test_full_loop_promotion_and_cas_rollback(self):
        dev = run(self.root, 'test', 'c1', 'dev')
        self.assertFalse(dev['decision']['eligible'])
        report = run(self.root, 'test', 'c1', 'confirm', 'confirmation')
        self.assertTrue(report['decision']['eligible'], report['decision'])
        pointer = promote(self.root, 'test', 'confirm')
        with self.assertRaises(ValueError):
            rollback(self.root, 'test', '0' * 64, 'test')
        self.assertIsNone(rollback(self.root, 'test', digest(pointer), 'regression recovery drill')['restored'])
        receipt = read_json(load(self.root, 'test')[0] / f'rollbacks/{digest(pointer)}.json')
        self.assertNotIn('reason', receipt)
        self.assertIn('reasonHash', receipt)
        self.assertTrue((load(self.root, 'test')[0] / 'runs/confirm/report.json').exists())

    def test_holdout_reuse_blocked_after_reload(self):
        run(self.root, 'test', 'c1', 'confirm', 'confirmation')
        load(self.root, 'test')
        with self.assertRaisesRegex(ValueError, 'heldout-reuse'):
            run(self.root, 'test', 'c1', 'second', 'confirmation')

    def test_same_mechanism_and_unobserved_revision_rejected(self):
        with self.assertRaises(ValueError):
            add_candidate(self.root, 'test', 'c2', self.candidate, 'Again', 'Changed words', 'c1')
        with self.assertRaises(ValueError):
            promote(self.root, 'test', 'missing')

    def test_confirmation_subset_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'confirmation-subset'):
            run(self.root, 'test', 'c1', 'selected', 'confirmation', ['c2', 'c3'])

    def test_preregistered_batches_are_consumed_in_order(self):
        cases = [{'caseId': f'b{i}', 'independenceGroup': f'g{i}', 'split': 'confirmation', 'confirmationBatch': i // 2, 'query': '검증', 'expectedIds': ['a']} for i in range(4)]
        write_json(self.root / 'batches.json', cases)
        register(self.root, 'batches', 'batches.json', self.base, 'Fresh batches preserve confirmation.')
        add_candidate(self.root, 'batches', 'v1', self.candidate, 'Use aliases.', 'Reviewed concepts.')
        with self.assertRaises(ValueError):
            run(self.root, 'batches', 'v1', 'skip', 'confirmation', ['b2', 'b3'])
        first = run(self.root, 'batches', 'v1', 'one', 'confirmation')
        second = run(self.root, 'batches', 'v1', 'two', 'confirmation')
        self.assertEqual(first['decision']['baseline']['n'], 2)
        self.assertEqual(second['decision']['baseline']['n'], 2)
        self.assertEqual(load(self.root, 'batches')[2]['confirmationLooks'], 2)

    def test_korean_query_survives_windows_subprocess_transport(self):
        spec = pin(self.root, self.candidate)
        row = evaluate(self.root, spec, {'caseId': 'ko', 'query': '검증', 'expectedIds': ['a']}, 3)
        self.assertEqual(row['status'], 'ok')
        self.assertTrue(row['success'], row)

    def test_slow_adapter_timeout_has_failed_measurement(self):
        script = self.root / 'slow.py'
        script.write_text('import time\ntime.sleep(2)\n', encoding='utf-8')
        spec = pin(self.root, {'kind': 'python', 'script': 'slow.py', 'localExecutionAuthorized': True})
        result = evaluate(self.root, spec, {'caseId': 'a'}, .1)
        self.assertEqual(result['status'], 'timeout')
        self.assertLess(result['latencyMs'], 1500)

    def test_changed_source_and_forged_report_rejected(self):
        run(self.root, 'test', 'c1', 'confirm', 'confirmation')
        base, _, _ = load(self.root, 'test')
        target = base / 'runs/confirm/report.json'
        target.write_text('{}', encoding='utf-8')
        with self.assertRaises(ValueError):
            promote(self.root, 'test', 'confirm')
        (self.root / 'index.json').write_text('{}', encoding='utf-8')
        with self.assertRaises(ValueError):
            load(self.root, 'test')

    def test_adapter_failure_is_measured_and_raw_output_discarded(self):
        script = self.root / 'bad.py'
        script.write_text("print('not a normalized measurement')", encoding='utf-8')
        spec = pin(self.root, {'kind': 'python', 'script': 'bad.py', 'localExecutionAuthorized': True})
        result = evaluate(self.root, spec, {'caseId': 'a'}, 2)
        self.assertEqual(result['status'], 'error')
        self.assertFalse(result['success'])
        self.assertGreater(result['latencyMs'], 0)
        self.assertNotIn('stdout', result)


if __name__ == '__main__':
    unittest.main()
