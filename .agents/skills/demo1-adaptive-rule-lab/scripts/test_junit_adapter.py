import tempfile
import unittest
from pathlib import Path

from junit_adapter import measure
from labio import file_hash


class JunitAdapterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.report = self.root / 'result.xml'

    def inspect(self, xml):
        self.report.write_text(xml, encoding='utf-8')
        return measure(self.root, {'reportPath': 'result.xml', 'reportHash': file_hash(self.report)})

    def test_pass_and_fail_are_observed_not_confidence(self):
        self.assertTrue(self.inspect('<testsuite tests="2"><testcase/><testcase/></testsuite>')['debugVerified'])
        result = self.inspect('<testsuite tests="2"><testcase/><testcase><failure>private body</failure></testcase></testsuite>')
        self.assertFalse(result['debugVerified'])
        self.assertEqual(result['quality'], .5)
        self.assertNotIn('private body', str(result))

    def test_skip_empty_and_wrong_denominator_do_not_pass(self):
        self.assertFalse(self.inspect('<testsuite><testcase><skipped/></testcase></testsuite>')['success'])
        for xml in ('<testsuite/>', '<testsuite tests="2"><testcase/></testsuite>'):
            with self.assertRaises(ValueError):
                self.inspect(xml)

    def test_changed_hash_and_entities_rejected(self):
        self.report.write_text('<testsuite><testcase/></testsuite>')
        with self.assertRaises(ValueError):
            measure(self.root, {'reportPath': 'result.xml', 'reportHash': '0' * 64})
        with self.assertRaises(ValueError):
            self.inspect('<!DOCTYPE foo><testsuite><testcase/></testsuite>')
