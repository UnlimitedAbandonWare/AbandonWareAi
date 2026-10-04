"""Tests for acceptance_reachability_lint.py — directive reachability lint.

R1~R5 each get one positive and one negative fixture under
scripts/fixtures/goal_block/; lint_1505_excerpt.md reproduces the 15:05
session's goal-objective contradiction (PARTIAL-report order vs all-PASS
completion) and must yield R3 FAIL.
"""
import importlib.util
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures" / "goal_block"
LOCKS = FIX / "locks"
SCRIPT = HERE / "acceptance_reachability_lint.py"
SPEC = importlib.util.spec_from_file_location("acceptance_reachability_lint",
                                              SCRIPT)
ARL = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault("acceptance_reachability_lint", ARL)
SPEC.loader.exec_module(ARL)


def findings(name, live=False):
    text = (FIX / name).read_text(encoding="utf-8")
    doc = ARL.parse_doc(text)
    leases = ARL.load_leases(LOCKS) if live else {}
    return ARL.lint_file(str(FIX / name), doc, leases, live)


def rules(fs, rule=None, sev=None):
    out = fs
    if rule:
        out = [f for f in out if f["rule"] == rule]
    if sev:
        out = [f for f in out if f["severity"] == sev]
    return out


class R1LeaseTest(unittest.TestCase):
    def test_fail_when_needed_path_is_leased(self):
        fs = rules(findings("lint_r1_blocked.md", live=True), "R1", "FAIL")
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0]["item"], "A2")
        self.assertIn("chatworkflow", fs[0]["evidence"])

    def test_ok_when_path_inside_allowed(self):
        fs = rules(findings("lint_r1_ok.md", live=True), "R1")
        self.assertEqual(fs, [])

    def test_no_r1_without_live_leases(self):
        fs = rules(findings("lint_r1_blocked.md", live=False), "R1", "FAIL")
        self.assertEqual(fs, [])


class R2UnmeasurableTest(unittest.TestCase):
    def test_warn_unmeasurable_no_tool(self):
        fs = rules(findings("lint_r2_unmeasurable.md"), "R2", "WARN")
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0]["item"], "A1")

    def test_ok_when_tool_named(self):
        fs = rules(findings("lint_r2_measurable.md"), "R2")
        self.assertEqual(fs, [])


class R3ContradictionTest(unittest.TestCase):
    def test_fail_partial_vs_all_pass(self):
        fs = rules(findings("lint_r3_contradictory.md"), "R3", "FAIL")
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0]["item"], "A3")

    def test_ok_consistent_completion(self):
        fs = rules(findings("lint_r3_ok.md"), "R3")
        self.assertEqual(fs, [])

    def test_1505_objective_excerpt_fails_r3(self):
        fs = rules(findings("lint_1505_excerpt.md"), "R3", "FAIL")
        self.assertTrue(fs)
        self.assertEqual(fs[0]["item"], "A3")


class R4HoldOverlapTest(unittest.TestCase):
    def test_warn_item_in_hold_and_acceptance(self):
        fs = rules(findings("lint_r4_overlap.md"), "R4", "WARN")
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0]["item"], "A5")

    def test_ok_disjoint(self):
        fs = rules(findings("lint_r4_ok.md"), "R4")
        self.assertEqual(fs, [])


class R5ExclusionClauseTest(unittest.TestCase):
    def test_info_when_clause_missing(self):
        fs = rules(findings("lint_r5_missing.md"), "R5", "INFO")
        self.assertEqual(len(fs), 1)

    def test_ok_when_clause_present(self):
        fs = rules(findings("lint_r5_present.md"), "R5")
        self.assertEqual(fs, [])


R6_UNENUMERATED = """## 수정 허용
- scripts/foo.py

## 변경 금지
- scripts/git_secret_guard.ps1의 탐지 규칙 완화 금지

## Acceptance
- A1 테스트 통과
- A7 이 세션이 보호 파일에 쓴 횟수 = 0 (다른 세션 변경은 EXTERNAL_DRIFT로 기록)
"""

R6_ENUM_HEADER = """## 변경 금지(보호 대상, 여기 열거한 것만 보호)
- scripts/protected_a.py, main/ 제품 소스

## Acceptance
- A7 이 세션이 '변경 금지(보호 대상)' 줄에 열거된 것에 쓴 횟수 = 0
"""

R6_ENUM_PAREN = """## Acceptance
- A7 보호 대상(scripts/a.py, scripts/b.py)에 쓴 횟수 = 0
"""


def findings_text(text):
    return ARL.lint_file("<inline>", ARL.parse_doc(text), {}, False)


class R6ProtectedScopeTest(unittest.TestCase):
    def test_fail_protected_word_without_enumeration(self):
        fs = rules(findings_text(R6_UNENUMERATED), "R6", "FAIL")
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0]["item"], "A7")

    def test_ok_header_enumeration(self):
        fs = rules(findings_text(R6_ENUM_HEADER), "R6")
        self.assertEqual(fs, [])

    def test_ok_acceptance_paren_enumeration(self):
        fs = rules(findings_text(R6_ENUM_PAREN), "R6")
        self.assertEqual(fs, [])

    def test_absent_when_no_protected_word(self):
        fs = rules(findings("lint_r3_ok.md"), "R6")
        self.assertEqual(fs, [])


class ExitCodeTest(unittest.TestCase):
    def test_exit_1_on_fail_0_clean(self):
        rc_fail = ARL.main([str(FIX / "lint_r3_contradictory.md"), "--root",
                            str(HERE.parent)])
        self.assertEqual(rc_fail, 1)
        rc_ok = ARL.main([str(FIX / "lint_r5_present.md"), "--root",
                          str(HERE.parent)])
        self.assertEqual(rc_ok, 0)


if __name__ == "__main__":
    unittest.main()
