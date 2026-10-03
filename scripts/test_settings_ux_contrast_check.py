#!/usr/bin/env python3
"""test_settings_ux_contrast_check.py — contrast 도구 단위 테스트.

임시 CSS 픽스처로 PASS/WARN 경로와 설계서 수치(#5aab87≈2.76,
#bfcfc4≈1.62, #60716b≈5.16, 흰색 대비 ±0.02)를 재현한다.
실제 제품 소스는 읽지 않는다.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "settings_ux_contrast_check.py"
spec = importlib.util.spec_from_file_location("settings_ux_contrast_check", SCRIPT)
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


FIXTURE = """
:root{--ink:#182522;--muted:#60716b;--accent:#216b53;--line:#dce5df;
--surface:#fff;--background:#f5f7f3}
body{background:var(--background);color:var(--ink);font:15px/1.6 sans-serif}
select,input{width:100%;background:#fff;border:1px solid #bfcfc4;
border-radius:8px;padding:11px;color:var(--ink);font:inherit}
button{border:1px solid #bfcfc4;border-radius:8px;padding:10px 14px;
background:#fff;color:var(--ink);font:inherit}
:focus-visible{outline:3px solid #5aab87;outline-offset:3px}
"""

GOOD_FIXTURE = """
:root{--ink:#182522;--muted:#3f4f4a;--surface:#fff;--background:#f5f7f3}
body{background:var(--background);color:var(--ink)}
select,input[type="text"]{border:1px solid #4a6b5f;padding:11px;font:inherit}
input[type="checkbox"]{width:auto}
button{border:1px solid #4a6b5f;padding:10px 14px;font:inherit}
:focus-visible{outline:3px solid #2f7d5f;outline-offset:3px}
"""


class ContrastMathTest(unittest.TestCase):
    def test_design_doc_ratios_vs_white(self):
        # 설계서 확정 수치 ±0.02 (흰색 대비)
        cases = {("#5aab87", 2.76), ("#bfcfc4", 1.62), ("#60716b", 5.16)}
        for hexv, expected in cases:
            rgb = mod.hex_to_rgb(hexv)
            self.assertIsNotNone(rgb, hexv)
            ratio = mod.contrast_ratio(rgb, (255, 255, 255))
            self.assertAlmostEqual(ratio, expected, delta=0.02,
                                   msg=f"{hexv} vs white")

    def test_luminance_bounds(self):
        self.assertAlmostEqual(mod.rel_luminance((255, 255, 255)), 1.0, places=3)
        self.assertAlmostEqual(mod.rel_luminance((0, 0, 0)), 0.0, places=3)
        self.assertAlmostEqual(
            mod.contrast_ratio((0, 0, 0), (255, 255, 255)), 21.0, delta=0.1)


class FixtureCheckTest(unittest.TestCase):
    def _run(self, css_text: str) -> dict:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            rel = "css/settings-page.css"
            path = root / rel
            path.parent.mkdir(parents=True)
            path.write_text(css_text, encoding="utf-8")
            return mod.run_check(root, rel)

    def test_flagged_fixture_warns(self):
        rep = self._run(FIXTURE)
        self.assertEqual(rep["status"], "WARN")
        by_id = {c["id"]: c for c in rep["checks"]}
        # #5aab87(2.76)과 #bfcfc4(1.62)는 3:1 미만 → WARN
        self.assertEqual(by_id["contrast.focus_outline"]["status"], "WARN")
        self.assertEqual(by_id["contrast.control_border"]["status"], "WARN")
        # --muted #60716b(5.16) ≥ 4.5 → PASS
        self.assertEqual(by_id["contrast.text.muted"]["status"], "PASS")
        # type 한정 없는 input 규칙 → WARN
        self.assertEqual(by_id["form.checkbox_broad_rule"]["status"], "WARN")
        # 패딩 11px×2 + 라인 추정 → 24px 이상 → PASS
        self.assertEqual(by_id["form.control_height"]["status"], "PASS")
        # metrics 재현
        self.assertAlmostEqual(rep["metrics"]["ratios"]["focusOutline"]["vsWhite"],
                               2.76, delta=0.02)

    def test_clean_fixture_passes(self):
        rep = self._run(GOOD_FIXTURE)
        self.assertEqual(rep["status"], "PASS")
        by_id = {c["id"]: c for c in rep["checks"]}
        self.assertEqual(by_id["form.checkbox_broad_rule"]["status"], "PASS")

    def test_missing_file_warns(self):
        with tempfile.TemporaryDirectory() as td:
            rep = mod.run_check(Path(td), "css/none.css")
        self.assertEqual(rep["status"], "WARN")


if __name__ == "__main__":
    unittest.main()
