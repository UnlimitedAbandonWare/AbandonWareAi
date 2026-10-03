#!/usr/bin/env python3
"""test_brief_save.py — brief_save.py 8건 회귀 테스트 (격리: temp downloads/repo-root)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "brief_save.py"


def run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-B", str(TOOL), *args],
                          capture_output=True, text=True, encoding="utf-8")


GOOD = """@objective-executor @demo1-project-root

[ANTI-STOP] 읽기 = intake. DV1부터 바로 실행한다.

# Devin — 테스트 지시서
## 0) 한 줄 목표
한 줄 목표 내용.
## 1) 사실
사실 내용.
## 공통 규칙
- lease 먼저 확인한다.
- 실제 외부 호출은 mock만.
## DV1 — 작업
항목 내용. Project Root는 C:\\AbandonWare\\demo-1\\demo-1\\src.
## HOLD
- push 금지.
## ASK_ONCE
- 없음.
## 절대 금지
- git add -A, 비밀값 출력, remote 추가·변경, PROTO_OPEN 강화 금지. 미실행은 NOT_RUN으로 적는다.
## Acceptance
- [ ] AC1 통과
## 보고 형식
보고 형식 내용.

[ANTI-STOP] 완료 = Acceptance가 PASS인 상태다.
"""


class BriefSaveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.downloads = base / "downloads"
        self.downloads.mkdir()
        self.repo = base / "repo"
        # 존재하는 스킬 스텁
        for skill in ("objective-executor", "demo1-project-root"):
            sk = self.repo / ".agents" / "skills" / skill
            sk.mkdir(parents=True)
            (sk / "SKILL.md").write_text("---\nname: %s\n---\n" % skill, encoding="utf-8")
        self.draft = base / "draft.txt"
        self.base_args = ["--downloads-dir", str(self.downloads),
                          "--repo-root", str(self.repo)]

    def save(self, text: str, agent: str = "DEVIN", topic: str = "test-brief",
             extra: list[str] | None = None) -> subprocess.CompletedProcess:
        self.draft.write_text(text, encoding="utf-8")
        return run(["save", "--draft", str(self.draft), "--agent", agent,
                    "--topic", topic, "--date", "20261002", *self.base_args,
                    *(extra or [])])

    def lint(self, text: str, agent: str = "DEVIN") -> dict:
        self.draft.write_text(text, encoding="utf-8")
        r = run(["lint", str(self.draft), "--agent", agent,
                 "--repo-root", str(self.repo)])
        return json.loads(r.stdout)

    def test_1_pass_saves_two_copies_and_registry(self):
        r = self.save(GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        paste = self.downloads / "PASTE_DEVIN_test-brief_20261002.txt"
        brief = self.repo / "agent-prompts" / "devin-test-brief-20261002" / "BRIEF.txt"
        self.assertTrue(paste.is_file() and brief.is_file())
        self.assertEqual(paste.read_bytes(), brief.read_bytes())
        reg = self.repo / "data" / "agent-handoff" / "brief-registry" / "briefs.jsonl"
        rows = [json.loads(l) for l in reg.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["agent"], "DEVIN")
        self.assertEqual(rows[0]["topic"], "test-brief")
        self.assertIsNotNone(rows[0]["sha12"])

    def test_2_same_name_gets_r2_and_original_unchanged(self):
        self.save(GOOD)
        first = (self.downloads / "PASTE_DEVIN_test-brief_20261002.txt").read_bytes()
        r = self.save(GOOD)
        self.assertEqual(r.returncode, 0, r.stdout)
        r2 = self.downloads / "PASTE_DEVIN_test-brief_20261002_R2.txt"
        self.assertTrue(r2.is_file())
        self.assertEqual((self.downloads / "PASTE_DEVIN_test-brief_20261002.txt").read_bytes(), first)
        reg = self.repo / "data" / "agent-handoff" / "brief-registry" / "briefs.jsonl"
        rows = reg.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(rows), 2)

    def test_date_cannot_redirect_helper_output_into_product_tree(self):
        # All escape destinations remain inside this disposable fixture.
        base = Path(self.tmp.name)
        (self.downloads / "PASTE_DEVIN_test-brief_..").mkdir()
        (base / "main").mkdir()
        (self.repo / "agent-prompts" / "devin-test-brief-..").mkdir(parents=True)
        r = self.save(GOOD, extra=["--date", "../../../main/resources"])
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertFalse((base / "main" / "resources.txt").exists())
        self.assertFalse((self.repo / "main").exists())
        self.assertFalse((self.repo / "data").exists())

    def test_date_rejects_separators_and_non_ascii_digits_before_saving(self):
        for invalid in ("2026/1003", "2026\\1003", "2026100", "202610033", "２０２６１００３"):
            with self.subTest(date=invalid):
                r = self.save(GOOD, extra=["--date", invalid])
                self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
                self.assertFalse(list(self.downloads.glob("PASTE_*")))
                self.assertFalse((self.repo / "data").exists())

    def test_3_missing_bottom_antistop_fails_and_refuses_save(self):
        bad = GOOD.rsplit("[ANTI-STOP]", 1)[0]  # 아래쪽 제거
        res = self.lint(bad)
        self.assertEqual(res["verdict"], "FAIL")
        self.assertTrue(any(f["id"] == "anti-stop-bottom-missing" for f in res["findings"]))
        r = self.save(bad)
        self.assertEqual(r.returncode, 1)
        self.assertFalse(list(self.downloads.glob("PASTE_*")))

    def test_4_missing_skill_fails_duplicate_skill_warns(self):
        bad = GOOD.replace("@demo1-project-root", "@no-such-skill-xyz")
        res = self.lint(bad)
        self.assertEqual(res["verdict"], "FAIL")
        self.assertTrue(any(f["id"] == "skill-not-found" for f in res["findings"]))
        dup = GOOD.replace("@demo1-project-root", "@objective-executor")
        res2 = self.lint(dup)
        self.assertEqual(res2["verdict"], "WARN")
        self.assertTrue(any(f["id"] == "skill-duplicate" for f in res2["findings"]))

    def test_5_secret_pattern_fails(self):
        # 스캐너 규칙상 소스에 비밀값 형태 리터럴을 두지 않는다(기존 fixture 관례와 동일하게 런타임 조립).
        bad = GOOD + "\n참고: " + "pass" + "word=Sup3rSecret!\n"
        res = self.lint(bad)
        self.assertEqual(res["verdict"], "FAIL")
        self.assertTrue(any("secret" in f["id"] for f in res["findings"]))
        bad2 = GOOD + "\nkey: " + "sk-" + "AbCdEfGhIjKlMnOpQrStUvWx\n"
        res2 = self.lint(bad2)
        self.assertEqual(res2["verdict"], "FAIL")

    def test_6_codex_without_dollar_line_fails(self):
        codex = GOOD.replace("@objective-executor @demo1-project-root", "# Codex — 테스트 지시서")
        res = self.lint(codex, agent="CODEX")
        self.assertEqual(res["verdict"], "FAIL")
        self.assertTrue(any(f["id"] == "codex-dollar-missing" for f in res["findings"]))

    def test_7_utf8_no_bom_and_korean_intact(self):
        r = self.save(GOOD)
        self.assertEqual(r.returncode, 0, r.stdout)
        data = (self.downloads / "PASTE_DEVIN_test-brief_20261002.txt").read_bytes()
        self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
        self.assertIn("지시서".encode("utf-8"), data)
        self.assertEqual(data.decode("utf-8"), GOOD.replace("\r\n", "\n"))

    def test_8_backfill_writes_metadata_only(self):
        fake = self.downloads / "PASTE_DEVIN_old-thing_20260101.txt"
        fake.write_text("내용은 절대 읽히면 안 된다 secret-content", encoding="utf-8")
        r = run(["backfill", *self.base_args])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        reg = self.repo / "data" / "agent-handoff" / "brief-registry" / "briefs.jsonl"
        rows = [json.loads(l) for l in reg.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertIsNone(row["sha12"])            # sha 계산 = 내용 읽기 → 없어야 함
        self.assertEqual(row["sha12Note"], "backfill-meta-only")
        self.assertEqual(row["author"], "unknown")
        self.assertNotIn("secret-content", reg.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
