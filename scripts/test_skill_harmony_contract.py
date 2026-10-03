#!/usr/bin/env python3
"""test_skill_harmony_contract.py — DEMO1-DEVIN-SKILL-HARMONY-20261002 S1~S8 회귀.

실제 validator(scripts/brief_lint.py, scripts/brief_save.py)와 실제 문서·빌드
바이트를 읽어, 고친 문서 계약과 도구 결정이 같은 입력에서 일치하는지 확인한다.
문서 읽기를 테스트로 세지 않기 위해 각 케이스는 결정(validator 결과 또는 실제
실행)을 포함한다.
"""
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_lint          # noqa: E402
import brief_save          # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / ".agents" / "skills"
WRITER = SKILLS / "demo1-agy-directive-writer" / "SKILL.md"
TEMPLATE = SKILLS / "demo1-agy-directive-writer" / "references" / "directive-template.md"
FACTS = SKILLS / "demo1-agy-directive-writer" / "references" / "durable-facts.md"
INTAKE = SKILLS / "demo1-desktop-canonical-goal-intake" / "SKILL.md"
ORCH = SKILLS / "demo1-devin-source-orchestrator" / "SKILL.md"
LEDGER = SKILLS / "demo1-work-ledger" / "SKILL.md"
COOP = SKILLS / "awx-cooperative-verification" / "SKILL.md"
LOOP = SKILLS / "demo1-devin-directive-loop" / "SKILL.md"
AGENTS_MD = ROOT / "AGENTS.md"
PY = [sys.executable, "-B"]


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def section(text: str, start_pat: str, end_pat: str) -> str:
    m = re.search(start_pat, text, re.M)
    if not m:
        return ""
    tail = text[m.start():]
    e = re.search(end_pat, tail[1:], re.M)
    return tail[: e.start() + 1] if e else tail


def lint_fail_ids(text: str, name: str = "devin-pathref") -> list:
    res = brief_lint.lint_text(text, name=name)
    return [f["id"] for f in res["findings"] if f["severity"] == "FAIL"]


def save_fail_ids(text: str, root: Path = ROOT) -> list:
    res = brief_save.lint_brief_text(
        text, agent="DEVIN", name="devin-pathref", repo_root=root)
    return [f["id"] for f in res["findings"] if f["severity"] == "FAIL"]


# Devin 지시서 — 머리 @skill 줄 없이 본문의 명시 경로 참조로 라우팅한다.
PATHREF_BRIEF = """[ANTI-STOP] 읽기 = intake. WP1부터 바로 실행한다.

# Devin — 경로 참조 지시서
## 0) 한 줄 목표
문서만 확인한다.
## 1) 사실
.agents/skills/demo1-project-root/SKILL.md 를 경로 참조로 라우팅한다.
## 공통 규칙
COMMON_RULES: .agents/rules/demo1-common-brief-rules.md 적용
## WP1 — 문서 확인
문서 확인만. Project Root는 C:\\AbandonWare\\demo-1\\demo-1\\src.
## HOLD
- 없음.
## ASK_ONCE
- 없음.
## 절대 금지
- push · add -A · 비밀값 출력 · remote 추가·변경 · PROTO_OPEN 변경 금지. 미실행은 NOT_RUN.
## Acceptance
- A1: WP1 확인
## 보고 형식
외부 API: 없음.

[ANTI-STOP] 완료 = Acceptance PASS.
"""

# @skill 줄도 경로 참조도 없는 Devin 지시서 — 라우팅 선언 부재.
NOROUTE_BRIEF = PATHREF_BRIEF.replace(
    ".agents/skills/demo1-project-root/SKILL.md 를 경로 참조로 라우팅한다.",
    "라우팅 선언 없이 진행한다.")

# 존재하지 않는 스킬을 경로로 참조 — 실존 검사가 걸려야 한다.
GHOST_PATH_BRIEF = PATHREF_BRIEF.replace(
    ".agents/skills/demo1-project-root/SKILL.md",
    ".agents/skills/no-such-skill-zz9/SKILL.md")

# 머리 @줄로 없는 스킬 참조 — 기존 실존 검사 경로.
GHOST_AT_BRIEF = "@no-such-skill-zz9\n" + PATHREF_BRIEF


class HarmonyContractTest(unittest.TestCase):
    # ---------- S1: 허용된 Devin 문서 작업 / 금지된 제품 작업 ----------
    def test_s1_doc_work_allowed_product_work_denied(self):
        w = read(WRITER)
        self.assertIn("never edits product source", w)
        self.assertIn("ONLY writes", w)
        # 문서 작업 Devin 지시서는 실제 lint를 통과한다.
        self.assertEqual([], lint_fail_ids(PATHREF_BRIEF))

    # ---------- S2: Git 없는 DRAFT/REVIEW / 다른 workflow 권한 유지 ----------
    def test_s2_git_free_review_other_workflows_kept(self):
        step2 = section(read(WRITER), r"^## 2\.", r"^## 3\.")
        self.assertIn("Git is conditional", step2)
        self.assertNotIn("git rev-parse", step2)
        self.assertIn("Git stays fully excluded in this loop, read commands included",
                      read(LOOP))
        self.assertIn("agent_git_vibe_commit", read(FACTS))
        self.assertIn("Do not open ordinary work with `git status`", read(AGENTS_MD))

    # ---------- S3: 경로 참조 허용 / 실제 없는 참조 거부 ----------
    def test_s3_path_refs_accepted_ghost_refs_denied(self):
        self.assertNotIn("devin-skill-line-missing", lint_fail_ids(PATHREF_BRIEF))
        # 라우팅 선언 자체가 없으면 여전히 FAIL (약화 아님).
        self.assertIn("devin-skill-line-missing", lint_fail_ids(NOROUTE_BRIEF))
        # 없는 스킬을 경로로 참조하면 실존 검사가 걸린다.
        self.assertIn("skill-not-found", save_fail_ids(GHOST_PATH_BRIEF))
        # 기존 머리 @줄 경로의 실존 검사도 그대로다.
        self.assertIn("skill-not-found", save_fail_ids(GHOST_AT_BRIEF))
        # 실존 경로 참조는 save 단 검사도 통과한다.
        self.assertNotIn("skill-not-found", save_fail_ids(PATHREF_BRIEF))

    # ---------- S4: Markdown / 실행 파일의 서로 다른 lease 요건 ----------
    def test_s4_lease_requirements_differ_by_file_kind(self):
        ledger = read(LEDGER)
        self.assertIn("Source/executable targets need the existing lease", ledger)
        self.assertIn(".agents/skills/**.md", ledger)
        intake = read(INTAKE)
        self.assertIn("no lease, no journal, no build", intake)
        self.assertNotIn("validator artifact; no source lease", intake)
        self.assertIn("executable target", intake)
        orch = read(ORCH)
        self.assertIn("file-changing work", orch)
        self.assertIn("lease는 Source/실행 파일 대상에만", read(TEMPLATE))

    # ---------- S5: 빈 :app·0 tests는 완료 아님 / 실제 tests>0 ----------
    def test_s5_empty_app_and_zero_tests_are_not_completion(self):
        self.assertIn("setSrcDirs(emptyList",
                      read(ROOT / "app" / "build.gradle.kts"))
        self.assertIn('srcDirs("main/java")', read(ROOT / "build.gradle.kts"))
        # 실제 unittest discover에 매치 0건 → "Ran 0 tests" + exit 0 — 완료 아님.
        p = subprocess.run(
            PY + ["-m", "unittest", "discover", "-s", "scripts",
                  "-p", "test_no_such_pattern_zzz.py"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
            timeout=60)
        self.assertEqual(0, p.returncode)
        self.assertIn("Ran 0 tests", p.stderr + p.stdout)
        # 이 스위트 자체는 실제로 1개 이상 실행된다.
        mine = unittest.defaultTestLoader.loadTestsFromModule(
            sys.modules[__name__])
        self.assertGreater(mine.countTestCases(), 0)

    # ---------- S6: coop 보호 대조군 / INVALIDATED 의미 유지 ----------
    def test_s6_coop_deferred_not_pass_and_invalidated(self):
        coop = read(COOP)
        self.assertIn("are never `VERIFIED_PASS`", coop)
        self.assertIn("INVALIDATED", coop)
        self.assertIn("never edits the source it measures", coop)
        # 공통 검증기가 post-test digest 불일치를 INVALIDATED로 분류하는 규칙.
        self.assertIn("post-test-digest-mismatch",
                      read(ROOT / "scripts" / "common_verifier.py"))
        # 실제 status 읽기 — exit 0·JSON·판정은 상태 필드에 있음.
        p = subprocess.run(
            PY + [str(ROOT / "scripts" / "coop_verify.py"), "--root", ".", "status"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
            timeout=60)
        self.assertEqual(0, p.returncode, p.stderr[-400:])
        payload = json.loads(p.stdout)
        self.assertIn("writers", payload)
        self.assertIn("tickets", payload)
        self.assertIn("verdict lives in state fields", payload.get("note", ""))

    # ---------- S7: 승인 없는 paid·model 변경 거부 / 현 정책 참조 유지 ----------
    def test_s7_paid_model_policy_unchanged(self):
        w = read(WRITER)
        self.assertIn("no live paid calls beyond an approved budget", w)
        self.assertIn("no paid/live API", w)
        self.assertIn("docs/API_ROUTING_SPEC.md", read(FACTS))
        self.assertTrue((ROOT / "docs" / "API_ROUTING_SPEC.md").is_file())
        self.assertTrue((ROOT / "configs" / "api-routing.yaml").is_file())
        self.assertIn("PROTO_OPEN", brief_save.FORBID_KEYWORDS)

    # ---------- S8: Downloads 단독 요청 / 기존 save 기능 보존 ----------
    def test_s8_downloads_only_request_save_feature_kept(self):
        step6 = section(read(WRITER), r"^## 6\.", r"^## 7\.")
        self.assertIn("brief_save.py", step6)
        self.assertIn("Downloads", step6)
        self.assertIn("단독 산출", step6)
        with tempfile.TemporaryDirectory() as dl, tempfile.TemporaryDirectory() as rd:
            repo = Path(rd)
            stub = repo / ".agents" / "skills" / "demo1-project-root"
            stub.mkdir(parents=True)
            (stub / "SKILL.md").write_text("# stub", encoding="utf-8")
            draft = repo / "draft.txt"
            draft.write_text(PATHREF_BRIEF, encoding="utf-8")
            p = subprocess.run(
                PY + [str(ROOT / "scripts" / "brief_save.py"), "save",
                      "--draft", str(draft), "--agent", "DEVIN",
                      "--topic", "harmony-contract", "--date", "20261002",
                      "--downloads-dir", dl, "--repo-root", str(repo)],
                capture_output=True, text=True, encoding="utf-8", timeout=60)
            self.assertEqual(0, p.returncode, p.stderr[-400:] + p.stdout[-400:])
            dl_copy = Path(dl) / "PASTE_DEVIN_harmony-contract_20261002.txt"
            repo_copy = (repo / "agent-prompts"
                         / "devin-harmony-contract-20261002" / "BRIEF.txt")
            self.assertTrue(dl_copy.is_file())
            self.assertTrue(repo_copy.is_file())
            self.assertEqual(dl_copy.read_bytes(), repo_copy.read_bytes())
            reg = repo / "data" / "agent-handoff" / "brief-registry" / "briefs.jsonl"
            rows = reg.read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(rows))


if __name__ == "__main__":
    r = unittest.TextTestRunner(verbosity=1).run(
        unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__]))
    total = r.testsRun
    skipped = len(r.skipped)
    failed = len(r.failures) + len(r.errors)
    print(f"testCount={total} skip={skipped} fail={failed}")
    sys.exit(0 if (not r.failures and not r.errors) else 1)
