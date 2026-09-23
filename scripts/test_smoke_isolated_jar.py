import importlib.util
import json
from pathlib import Path
import threading
import unittest
import urllib.request
import urllib.error

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("smoke_isolated_jar.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class LoopbackJarFixtureTest(unittest.TestCase):
    def setUp(self):
        self.fixture = smoke.Fixture()

    def tearDown(self):
        self.fixture.close()
        self.assertFalse(self.fixture.thread.is_alive())

    def request(self, path, model=None):
        data = None if model is None else json.dumps({"model": model, "messages": []}).encode()
        try:
            with urllib.request.urlopen(urllib.request.Request(self.fixture.origin+path, data=data), timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as failure:
            return failure.code, None

    def test_failure_mode_requires_different_model_for_fallback_success(self):
        self.fixture.mode = "failure"
        self.assertEqual(503, self.request("/v1/chat/completions", "fixture-primary")[0])
        self.assertEqual(200, self.request("/v1/chat/completions", "fixture-fallback")[0])
        self.assertNotEqual(self.fixture.rows[0]["modelHash"], self.fixture.rows[1]["modelHash"])

    def test_zero_is_an_actual_empty_returned_array(self):
        self.fixture.mode = "zero"
        status, body = self.request("/res/v1/web/search?q=synthetic")
        self.assertEqual(200, status)
        self.assertEqual([], body["web"]["results"])
        self.assertEqual(0, self.fixture.rows[0]["returnedItemsCount"])

    def test_held_response_is_controlled_by_receipt_and_release(self):
        self.fixture.mode = "hold"
        ended = threading.Event()
        def call():
            self.request("/api/chat", "fixture-primary")
            ended.set()
        caller = threading.Thread(target=call)
        caller.start()
        self.assertTrue(self.fixture.received.wait(5))
        self.assertFalse(ended.is_set())
        self.fixture.release.set()
        self.assertTrue(ended.wait(5))
        caller.join(5)

    def test_metadata_or_unknown_endpoint_is_not_counted_as_generation(self):
        self.assertEqual(404, self.request("/v1/models")[0])
        self.assertEqual("unsupported_endpoint", self.fixture.rows[0]["kind"])


class RuntimeEvidenceValidatorTest(unittest.TestCase):
    def hold(self):
        case = dict(mode="hold", requestHash="hash:request", runHash="hash:run", httpStatus=200,
                    stopHttpStatus=200, streamEndedBeforeFixtureRelease=True)
        rows = [dict(kind="model", harnessCaseRequestHash="hash:request")]
        lifecycle = [dict(requestHash="hash:request", event="http_client_started", afterCancel="false"),
                     dict(requestHash="hash:request", event="cancel_accepted", runHash="hash:run")]
        return case, rows, dict(lifecycle=lifecycle, attempts=[])

    def test_stream_closure_without_matching_cancel_is_incomplete(self):
        case, rows, proof = self.hold()
        proof['lifecycle'].pop()
        self.assertFalse(smoke.validate_case(case, rows, proof))

    def test_post_cancel_start_fails_but_late_completion_is_distinguished(self):
        case, rows, proof = self.hold()
        proof['lifecycle'].append(dict(requestHash=case['requestHash'], event='http_client_completed', afterCancel='true'))
        self.assertTrue(smoke.validate_case(case, rows, proof))
        self.assertEqual(1, case['lateCompletions'])
        self.assertEqual('not_observed', case['serverProcessingStopped'])
        proof['lifecycle'].append(dict(requestHash=case['requestHash'], event='http_client_started', afterCancel='true'))
        self.assertFalse(smoke.validate_case(case, rows, proof))

    def test_wrong_request_or_dropped_events_cannot_pass(self):
        case, rows, proof = self.hold()
        proof['lifecycle'][1]['requestHash'] = 'hash:other'
        self.assertFalse(smoke.validate_case(case, rows, proof))
        case, rows, proof = self.hold()
        proof['lifecycle'][0]['dropped'] = '1'
        self.assertFalse(smoke.validate_case(case, rows, proof))

    def test_fallback_requires_role_order_and_different_received_model(self):
        case = dict(mode='failure', requestHash='hash:request', httpStatus=200, fixtureAnswerObserved=True)
        rows = [dict(kind='model', harnessCaseRequestHash='hash:request', status=status, modelHash=model, ordinal=n)
                for n, status, model in [(1,503,'primary'),(2,200,'fallback')]]
        proof = dict(lifecycle=[dict(requestHash='hash:request',event='http_client_started')], attempts=[])
        self.assertFalse(smoke.validate_case(case, rows, proof))
        proof['attempts'] = [dict(requestHash='hash:request',role=role,outcome=outcome,sequence=str(n))
                             for n,role,outcome in [(1,'primary','failed'),(2,'fallback','success')]]
        self.assertTrue(smoke.validate_case(case, rows, proof))
        proof['attempts'][1]['sequence'] = '0'
        self.assertFalse(smoke.validate_case(case, rows, proof))

    def test_provider_zero_without_same_request_final_zero_cannot_pass(self):
        case = dict(mode='zero', requestHash='hash:request', httpStatus=200)
        rows = [dict(kind='search', harnessCaseRequestHash='hash:request', returnedItemsCount=0)]
        proof = dict(lifecycle=[dict(requestHash='hash:request',event='http_client_started')], attempts=[])
        self.assertFalse(smoke.validate_case(case, rows, proof))
        proof['retrieval'] = [dict(requestHash='hash:other', data=dict(stage='RETRIEVAL',failureClass='ZERO_RESULT'))]
        self.assertFalse(smoke.validate_case(case, rows, proof))
        proof['retrieval'] = [dict(requestHash='hash:request', data=data) for data in
            [dict(stage='RETRIEVAL',failureClass='ZERO_RESULT'),dict(webCount=0,ragCount=0)]]
        self.assertTrue(smoke.validate_case(case, rows, proof))


if __name__ == "__main__":
    unittest.main()
