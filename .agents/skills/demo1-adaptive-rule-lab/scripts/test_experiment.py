import copy
import unittest

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


if __name__ == '__main__':
    unittest.main()
