"""f01b_admission_key_demo unittest — 고정 벡터 + secrets abort.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
고정 벡터는 테스트가 독립적으로 canonical 문자열을 만들어 sha256 한다.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_admission_key_demo.py")
SPEC = importlib.util.spec_from_file_location("f01b_admission_key_demo",
                                              SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_admission_key_demo", MOD)
    SPEC.loader.exec_module(MOD)


def expected_hash(version, kind, fields):
    """테스트 측 독립 canonical 구현 — 구현과 같은 결과여야 한다."""
    env = json.dumps({"v": version, "kind": kind, "fields": fields},
                     sort_keys=True, separators=(",", ":"),
                     ensure_ascii=True)
    return hashlib.sha256(env.encode("utf-8")).hexdigest()


class AdmissionKeyDemoTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def out_json(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "f01b_admission_key_demo.json").read_text(encoding="utf-8"))

    def test_demo_fixed_vectors(self):
        code = self.run_main("--root", str(self.root), "--demo")
        self.assertEqual(code, 0)
        out = self.out_json()
        d = MOD.DEMO_INPUT
        scope = {k: d[k] for k in
                 ("ownerNamespace", "sessionId", "channel", "consentEpoch")}
        self.assertEqual(
            out["keys"]["admissionKey"],
            expected_hash("awx-admission-key.v1", "admission_key",
                          {"originalRunId": d["originalRunId"],
                           "sourceScope": scope}))
        self.assertEqual(
            out["keys"]["effectKey"],
            expected_hash("awx-effect-key.v1", "effect_key",
                          {f: d[f] for f in MOD.EFFECT_KEY_FIELDS}))
        self.assertEqual(
            out["keys"]["requestFingerprint"],
            expected_hash("awx-request-fingerprint.v1",
                          "request_fingerprint", d))

    def test_fixture_ok(self):
        fx = self.root / "fx.json"
        fx.write_text(json.dumps({
            "originalRunId": "r1", "ownerNamespace": "o", "sessionId": "s",
            "channel": "c", "consentEpoch": "e", "userMessageId": "u",
            "userRevision": "1", "assistantMessageId": "a",
            "assistantRevision": "1", "kind": "understanding_summary_v1"}),
            encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--fixture", str(fx))
        self.assertEqual(code, 0)
        self.assertEqual(len(self.out_json()["keys"]["effectKey"]), 64)

    def test_secret_key_aborts_4(self):
        fx = self.root / "fx_secret.json"
        fx.write_text(json.dumps({"originalRunId": "r1",
                                  "password": "x"}), encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--fixture", str(fx))
        self.assertEqual(code, 4)

    def test_secret_value_aborts_4(self):
        fx = self.root / "fx_token.json"
        fx.write_text(json.dumps({"note": "sk-abcdefghijklmnopqrstuvwx"}),
                      encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--fixture", str(fx))
        self.assertEqual(code, 4)

    def test_jdbc_url_value_aborts_4(self):
        fx = self.root / "fx_jdbc.json"
        fx.write_text(json.dumps(
            {"dsn_note": "jdbc:mysql://db.internal:3306/app?u=x&p=y"}),
            encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--fixture", str(fx))
        self.assertEqual(code, 4)

    def test_no_input_usage_error(self):
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 1)

    def test_missing_effect_fields_usage_error(self):
        fx = self.root / "fx_short.json"
        fx.write_text(json.dumps({"originalRunId": "r1"}), encoding="utf-8")
        code = self.run_main("--root", str(self.root), "--fixture", str(fx))
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
