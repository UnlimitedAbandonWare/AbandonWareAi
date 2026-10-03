"""Golden-set regression for the vibe skill router.

Each line of fixtures/vibe_router_golden.jsonl is {id, ask, primary, optional?}.
primary null expects a no-match fallback. Fails when pass rate < 90%.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "vibe_router_golden.jsonl"

spec = importlib.util.spec_from_file_location(
    "demo1_vibe_skill_router", ROOT / "scripts" / "demo1_vibe_skill_router.py"
)
router = importlib.util.module_from_spec(spec)
spec.loader.exec_module(router)

INDEX = router.load_index(str(ROOT), ".agents/skills-intent-index.yaml")


def _load_cases():
    cases = []
    for raw in FIXTURE.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            cases.append(json.loads(line))
    return cases


class GoldenRouterTest(unittest.TestCase):
    def test_golden_pass_rate(self):
        cases = _load_cases()
        self.assertGreaterEqual(len(cases), 30, "golden set needs 30+ cases")
        failures = []
        for case in cases:
            result = router.resolve(INDEX, case["ask"], str(ROOT))
            ok = result.get("primary") == case["primary"]
            if ok and case.get("optional"):
                ok = result.get("optional") == case["optional"]
            if not ok:
                failures.append(
                    f"{case['id']}: want {case['primary']!r}"
                    + (f"/{case['optional']!r}" if case.get("optional") else "")
                    + f" got {result.get('primary')!r}/{result.get('optional')!r}"
                    + f" intent={result.get('intent')!r} status={result.get('status')!r}"
                )
        rate = (len(cases) - len(failures)) / len(cases)
        for line in failures:
            print("FAIL " + line, file=sys.stderr)
        print(
            f"golden pass rate: {rate:.1%} ({len(cases)-len(failures)}/{len(cases)})",
            file=sys.stderr,
        )
        self.assertGreaterEqual(rate, 0.90, "\n" + "\n".join(failures))

    def test_resolved_skills_exist(self):
        for case in _load_cases():
            result = router.resolve(INDEX, case["ask"], str(ROOT))
            if result.get("primary"):
                self.assertNotEqual(
                    result.get("status"), "error", f"{case['id']}: {result}"
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)
