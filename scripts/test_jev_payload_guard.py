"""One violation kind each, plus the known-good Java evaluate body."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).with_name("jev_payload_guard.py")
GOOD = ROOT / "data" / "agent-handoff" / "grok-jev-v2-support-20260930" / "java_evaluate_request.json"
SPEC = importlib.util.spec_from_file_location("jev_payload_guard", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def _jev(query: str = "합성 질문: synthetic route", **body_over) -> dict:
    body = {
        "model": "typesafe-ai/jev",
        "state": {"query": query},
        "providerOptions": {"gateway": {"only": ["typesafe-ai"]}},
    }
    body.update(body_over)
    return {"url": "https://ai-gateway.vercel.sh/v1/evaluate", "body": body}


def _kinds(doc: dict, size: int | None = None) -> set[str]:
    raw = json.dumps(doc).encode("utf-8")
    found = MOD.inspect(doc, size if size is not None else len(raw))
    return {item["kind"] for item in found}


class PayloadGuardTest(unittest.TestCase):
    def test_known_good_java_request(self) -> None:
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT), str(GOOD)],
                              capture_output=True, text=True, cwd=str(ROOT))
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(json.loads(proc.stdout)["violations"], [])

    def test_each_kind(self) -> None:
        cases = {
            "synthetic-marker": _jev("오늘 날씨"),
            "forbidden-field": _jev(history=["synthetic"]),
            "key-aiza": _jev("합성 질문: AIzaSySYNTHETICLOCALMOCK12"),
            "key-sk": _jev("합성 질문: sk-syntheticlocalmockxx"),
            "key-vck": _jev("합성 질문: vck_syntheticlocal"),
            "key-bearer": _jev("합성 질문: Bearer synthetic-local-mock"),
            "key-hex": _jev("합성 질문: " + "ab" * 16),
            "key-b64": _jev("합성 질문: " + ("Z" * 40)),
            "pii-email": _jev("합성 질문: synthetic@example.com"),
            "pii-phone": _jev("합성 질문: 010-1234-5678"),
            "pii-rrn": _jev("합성 질문: 900101-1234567"),
            "pii-card": _jev("합성 질문: 4111111111111111"),
            "zdr-true": _jev(providerOptions={"gateway": {"only": ["typesafe-ai"], **{("zero" + "DataRetention"): True}}}),
            "jev-model": _jev(model="typesafe-ai/jevx"),
            "gateway-only": _jev(providerOptions={"gateway": {"only": ["other"]}}),
            "size-state": _jev("합성 질문:" + ("가" * 4000)),
        }
        for kind, doc in cases.items():
            self.assertIn(kind, _kinds(doc), kind)
        gemini = {
            "synthetic": True,
            "body": {
                "model": "gemini-flash",
                "contents": [{"parts": [{"text": "합성 질문: synthetic"}]}],
                "generationConfig": {"maxOutputTokens": 300, "temperature": 0.9},
            },
        }
        kinds = _kinds(gemini)
        self.assertIn("max-output-tokens", kinds)
        self.assertIn("temperature", kinds)
        clean_gemini = {
            "synthetic": True,
            "body": {
                "model": "gemini-flash",
                "contents": [{"parts": [{"text": "합성 질문: synthetic"}]}],
                "generationConfig": {"maxOutputTokens": 256},
            },
        }
        self.assertEqual(_kinds(clean_gemini), set())
        oversized = _kinds(_jev(), size=70000)
        self.assertIn("size-whole", oversized)

    def test_stdout_does_not_echo_match(self) -> None:
        secret = "sk-syntheticlocalmockxx"
        doc = _jev("합성 질문: " + secret)
        text = json.dumps(MOD.inspect(doc, 100))
        self.assertNotIn(secret, text)

    def test_usage_exit_2(self) -> None:
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT)],
                              capture_output=True, text=True, cwd=str(ROOT))
        self.assertEqual(proc.returncode, 2)


if __name__ == "__main__":
    unittest.main()
