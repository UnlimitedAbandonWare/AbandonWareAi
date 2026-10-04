#!/usr/bin/env python3
"""Contract tests for scripts/gptpro_pack.py (DEMO1-DEVIN-GPTPRO-ZIP-PACK-20261002).

Runs only against a synthetic repo built in %TEMP% per test. No real secret
files are read or emitted; FAKE_SECRET is a synthetic token that must appear
NOWHERE in tool output or the produced zip.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gptpro_pack.py"
sys.path.insert(0, str(ROOT / "scripts"))

FAKE_SECRET = "sk-proj-" + "Ab3" * 24  # synthetic; must never leak into output


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout


def write_file(repo: Path, rel: str, text: str) -> None:
    path = repo / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build_fake_repo(repo: Path) -> None:
    write_file(repo, ".gitignore",
               "node_modules/\n.next/\nbuild/\ndata/\nlogs/\n*.tmp\nsecrets-scratch/\n")
    write_file(repo, "main/java/com/example/App.java", "class App {}\n")
    write_file(repo, "main/java/com/example/SettingsControllerSecretMaskAspect.java",
               "class SettingsControllerSecretMaskAspect {}\n")
    write_file(repo, "main/java/com/example/Leaky.java",
               "class Leaky {\n  String k = \"" + FAKE_SECRET + "\";\n}\n")
    write_file(repo, "main/java/com/example/__patch_drop__/Skip.java", "class Skip {}\n")
    write_file(repo, "main/resources/application.yml", "spring:\n  profiles: local\n")
    write_file(repo, "main/resources/application-secrets.yml", "key: hidden\n")
    write_file(repo, ".env", "OPENAI_API_KEY=x\n")
    write_file(repo, ".env.local", "A=1\n")
    write_file(repo, ".env.example", "OPENAI_API_KEY=\n")
    write_file(repo, "shared.env", "X=1\n")
    write_file(repo, "apikey.txt", "placeholder\n")
    write_file(repo, ".secrets/providers.json", "{}\n")
    write_file(repo, "auth.json", "{}\n")
    write_file(repo, "credentials.json", "{}\n")
    write_file(repo, "cert.pem", "pem\n")
    write_file(repo, "build.gradle.kts",
               'plugins { id("org.springframework.boot") version "3.3.4" }\n'
               "java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }\n")
    write_file(repo, "settings.gradle.kts", 'rootProject.name = "demo"\n')
    write_file(repo, "gradle.properties", "org.gradle.jvmargs=-Xmx2g\n")
    write_file(repo, "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x\n")
    write_file(repo, "configs/api-routing.yaml",
               "roles: {}\npolicy:\n  order: [zzz_free, yyy_paid]\n")
    write_file(repo, "configs/agent-api-spend-guard.yaml",
               "agent_spend_order: [alpha_lane, beta_lane, gamma_lane]\n"
               "prefer_local_ollama_first: false\n")
    write_file(repo, "configs/agent-test-model-policy.yaml",
               "purposes:\n  quality:\n    prefer: [m1, m2]\n")
    # v2 fixtures: vendor/min JS, static/template payload, tests, rules, skills
    write_file(repo, "main/resources/static/js/app.js", "console.log('app')\n")
    write_file(repo, "main/resources/static/js/vendor/chat/lib-1.2.3.min.js",
               "!function(){minified=1}()\n")
    write_file(repo, "main/resources/templates/home.html", "<html/>\n")
    write_file(repo, "src/test/java/com/example/AppTest.java",
               "class AppTest {}\n")
    write_file(repo, "docs/agents-rules/DEMO1-TEST-BLOCK.md",
               "# DEMO1-TEST-BLOCK\nrule line one\nrule line two\n")
    write_file(repo, ".agents/skills/demo-skill/SKILL.md",
               "---\nname: demo-skill\ndescription: fixture skill\n---\nbody\n")
    write_file(repo, "main/java/com/example/WidgetService.java",
               "package com.example;\n\npublic class WidgetService {\n"
               "    private int count;\n"
               "    public int serve() {\n"
               "        System.out.println(\"BODY-ONLY-MARKER\");\n"
               "        return count;\n"
               "    }\n}\n")
    write_file(repo, "main/java/com/example/FocusAlphaThing.java",
               "package com.example;\n\npublic class FocusAlphaThing {\n"
               "    public String go() {\n"
               "        String s = \"FOCUS-BODY-MARKER\";\n"
               "        return s;\n"
               "    }\n}\n")
    write_file(repo, "frontend/src/App.tsx", "export default function App() {}\n")
    write_file(repo, "frontend/src/style.png", "binary-ish\n")
    write_file(repo, "frontend/scripts/build.js", "console.log(1)\n")
    write_file(repo, "frontend/package.json", "{}\n")
    write_file(repo, "frontend/next.config.mjs", "export default {}\n")
    write_file(repo, "frontend/jsconfig.json", "{}\n")
    write_file(repo, "frontend/node_modules/pkg/index.js", "x\n")
    write_file(repo, "frontend/.next/cache/x.js", "x\n")
    write_file(repo, "node_modules/leftpad/index.js", "x\n")
    write_file(repo, "build/out.jar", "x\n")
    write_file(repo, "AGENTS.md", "# rules\n")
    write_file(repo, "README.md", "# demo\n")
    write_file(repo, "docs/PROJECT_STATUS.md", "# status\n")
    write_file(repo, "docs/API_ROUTING_SPEC.md", "# routing\n")
    write_file(repo, "docs/architecture/overview.md", "# arch\n")
    write_file(repo, "docs/other.md", "# other\n")
    write_file(repo, "docs/한국어_노트.md", "# 한국어\n")
    write_file(repo, "scripts/foo.py", "print(1)\n")
    write_file(repo, "scripts/bar.bat", "@echo hi\n")
    write_file(repo, "scripts/notes.txt", "note\n")
    write_file(repo, "src/test/java/com/example/T.java", "class T {}\n")
    write_file(repo, "agent-prompts/prompt.md", "p\n")
    write_file(repo, "verification/run.json", "{}\n")
    write_file(repo, "data/blob.txt", "ignored anyway\n")
    write_file(repo, "notes.tmp", "ignored tmp\n")
    write_file(repo, "docs/big.md", "x" * (3 * 1024 * 1024) + "\n")
    subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(
        ["git", "-C", str(repo), "-c", "user.email=t@t", "-c", "user.name=t",
         "commit", "-qm", "init"], check=True)


def run_pack(repo: Path, out: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-B", str(SCRIPT), "--root", str(repo),
         "--out", str(out), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def zip_names(zip_path: Path) -> set:
    with zipfile.ZipFile(zip_path) as z:
        return set(z.namelist())


def sole_zip(out: Path) -> Path:
    zips = sorted(out.glob("demo1_*.zip"))
    assert len(zips) == 1, f"expected 1 zip in {out}, got {zips}"
    return zips[0]


class GptproPackTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls):
        cls.template = Path(tempfile.mkdtemp(prefix="gptpro-tpl-")) / "repo"
        build_fake_repo(cls.template)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.template.parent, ignore_errors=True)

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="gptpro-test-"))
        self.repo = self.work / "repo"
        shutil.copytree(self.template, self.repo)
        self.out = self.work / "out"
        self.out.mkdir()

    def tearDown(self):
        shutil.rmtree(self.work, ignore_errors=True)

    # 1. secret-name files absent; .env.example present
    def test_01_secret_name_exclusions(self):
        r = run_pack(self.repo, self.out, "--profile", "core")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        for banned in (".env", ".env.local", "shared.env", "apikey.txt",
                       "main/resources/application-secrets.yml",
                       ".secrets/providers.json", "auth.json", "credentials.json",
                       "cert.pem"):
            self.assertNotIn(banned, names, banned)
        for n in names:
            self.assertFalse(n.startswith(".secrets/"), n)
        self.assertIn(".env.example", names)

    # 2. legit source with "Secret" in the name IS included
    def test_02_secret_in_name_source_included(self):
        r = run_pack(self.repo, self.out, "--profile", "main")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn("main/java/com/example/SettingsControllerSecretMaskAspect.java",
                      zip_names(sole_zip(self.out)))

    # 3. content-hit file excluded; report carries path+line+pattern, never the value
    def test_03_secret_content_excluded_value_never_printed(self):
        r = run_pack(self.repo, self.out, "--profile", "main")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        self.assertNotIn("main/java/com/example/Leaky.java", names)
        self.assertIn("main/java/com/example/Leaky.java", r.stdout)
        self.assertIn("openai", r.stdout)  # pattern id (sk- family)
        self.assertNotIn(FAKE_SECRET, r.stdout)
        self.assertNotIn(FAKE_SECRET, r.stderr)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            manifest = z.read("_MANIFEST.md").decode("utf-8")
        self.assertNotIn(FAKE_SECRET, manifest)
        self.assertIn("Leaky.java", manifest)

    # 4. build/vendor/ops dirs absent
    def test_04_dir_exclusions(self):
        r = run_pack(self.repo, self.out, "--profile", "full")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        banned_frag = ("node_modules/", ".next/", "build/", "__patch_drop__/",
                       "data/", "agent-prompts/", "verification/", "logs/")
        for n in names:
            for frag in banned_frag:
                self.assertNotIn(frag, n, n)

    # 5. profile differences per definition
    def test_05_profile_differences(self):
        for profile in ("main", "core", "full"):
            out = self.work / f"out-{profile}"
            out.mkdir()
            r = run_pack(self.repo, out, "--profile", profile)
            self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names_main = zip_names(sole_zip(self.work / "out-main"))
        names_core = zip_names(sole_zip(self.work / "out-core"))
        names_full = zip_names(sole_zip(self.work / "out-full"))
        # main: only main/**
        self.assertIn("main/java/com/example/App.java", names_main)
        self.assertNotIn("build.gradle.kts", names_main)
        self.assertFalse(any(n.startswith("configs/") for n in names_main))
        self.assertFalse(any(n.startswith("docs/") for n in names_main))
        # core: + build files, configs, frontend, AGENTS.md, docs subset
        for expect in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                       "gradle/wrapper/gradle-wrapper.properties",
                       "configs/api-routing.yaml", "frontend/src/App.tsx",
                       "frontend/scripts/build.js", "frontend/package.json",
                       "frontend/next.config.mjs", "frontend/jsconfig.json",
                       "AGENTS.md", "README.md", "docs/PROJECT_STATUS.md",
                       "docs/API_ROUTING_SPEC.md", "docs/architecture/overview.md"):
            self.assertIn(expect, names_core, expect)
        self.assertFalse(any(n.startswith("scripts/") for n in names_core))
        self.assertNotIn("docs/other.md", names_core)  # not in core doc list
        # full: + scripts(ext-filtered), all docs md, src/test
        self.assertIn("scripts/foo.py", names_full)
        self.assertIn("scripts/bar.bat", names_full)
        self.assertNotIn("scripts/notes.txt", names_full)  # ext not allowed
        self.assertIn("docs/other.md", names_full)
        self.assertIn("src/test/java/com/example/T.java", names_full)

    # 6. name collision -> -2 suffix, no overwrite (unit + e2e)
    def test_06_collision_suffix_no_overwrite(self):
        import gptpro_pack as gp
        base = self.out / "demo1_core_20261002-2359_abc1234.zip"
        base.write_bytes(b"one")
        n2 = gp.next_free_path(base)
        self.assertEqual(n2.name, "demo1_core_20261002-2359_abc1234-2.zip")
        n2.write_bytes(b"two")
        n3 = gp.next_free_path(base)
        self.assertEqual(n3.name, "demo1_core_20261002-2359_abc1234-3.zip")
        self.assertEqual(base.read_bytes(), b"one")
        r1 = run_pack(self.repo, self.out, "--profile", "main")
        r2 = run_pack(self.repo, self.out, "--profile", "main")
        self.assertEqual(r1.returncode, 0)
        self.assertEqual(r2.returncode, 0)
        self.assertGreaterEqual(len(list(self.out.glob("demo1_main_*.zip"))), 2)

    # 7. --dry-run writes no zip
    def test_07_dry_run_no_zip(self):
        r = run_pack(self.repo, self.out, "--profile", "core", "--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertEqual(list(self.out.glob("demo1_*.zip")), [])
        self.assertIn("files", r.stdout.lower())

    # 8. manifest + readme inside zip
    def test_08_manifest_and_readme_in_zip(self):
        r = run_pack(self.repo, self.out, "--profile", "core")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        self.assertIn("_MANIFEST.md", names)
        self.assertIn("_README_FOR_GPTPRO.md", names)

    # 9. >2MB file excluded and listed
    def test_09_oversized_file_excluded(self):
        r = run_pack(self.repo, self.out, "--profile", "full")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertNotIn("docs/big.md", zip_names(sole_zip(self.out)))
        self.assertIn("big.md", r.stdout)

    # 10. repo git status identical before/after (zero repo writes)
    def test_10_repo_untouched(self):
        before = git(self.repo, "status", "--porcelain")
        r = run_pack(self.repo, self.out, "--profile", "full")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertEqual(before, git(self.repo, "status", "--porcelain"))

    # 11. untracked non-ignored file included; gitignored non-excluded file absent
    def test_11_untracked_included_ignored_absent(self):
        write_file(self.repo, "main/java/com/example/WorkInProgress.java",
                   "class WorkInProgress {}\n")
        write_file(self.repo, "notes.tmp", "ignored\n")  # in .gitignore, not always-excluded
        r = run_pack(self.repo, self.out, "--profile", "main")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        self.assertIn("main/java/com/example/WorkInProgress.java", names)
        self.assertNotIn("notes.tmp", names)

    # 12. zip paths use '/' and Korean names survive via UTF-8 flag
    def test_12_utf8_names_and_separators(self):
        r = run_pack(self.repo, self.out, "--profile", "full")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            infos = {i.filename: i for i in z.infolist()}
        self.assertIn("docs/한국어_노트.md", infos)
        for name in infos:
            self.assertNotIn("\\", name, name)
        self.assertTrue(infos["docs/한국어_노트.md"].flag_bits & 0x800)

    # ---- v2: ctx/brief context profiles (2026-10-03) ----

    CTX_SECTIONS = ("_START_HERE.md", "_RULES_AND_ROLES.md", "_CURRENT_WORK.md",
                    "_CHANGES.md", "_CODE_MAP.md", "_RUNTIME.md", "_TEST_INDEX.md")

    # 13. ctx zip carries every generated context section
    def test_13_ctx_sections_present(self):
        r = run_pack(self.repo, self.out, "--profile", "ctx")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        for s in self.CTX_SECTIONS:
            self.assertIn(s, names, s)
        self.assertIn("_MANIFEST.md", names)
        self.assertIn("_README_FOR_GPTPRO.md", names)

    # 14. vendor/min JS excluded in ctx but kept in core (payload parity otherwise)
    def test_14_ctx_vendor_excluded_core_keeps(self):
        vend = "main/resources/static/js/vendor/chat/lib-1.2.3.min.js"
        r1 = run_pack(self.repo, self.out, "--profile", "ctx")
        self.assertEqual(r1.returncode, 0, r1.stderr + r1.stdout)
        self.assertNotIn(vend, zip_names(sole_zip(self.out)))
        out2 = self.work / "out-core"
        out2.mkdir()
        r2 = run_pack(self.repo, out2, "--profile", "core")
        self.assertEqual(r2.returncode, 0, r2.stderr + r2.stdout)
        self.assertIn(vend, zip_names(sole_zip(out2)))

    # 15. brief skeletonizes plain java; --focus-matched main file stays full
    def test_15_brief_skeleton_and_focus_full(self):
        r = run_pack(self.repo, self.out, "--profile", "brief",
                     "--focus", "FocusAlpha")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            skel = z.read("main/java/com/example/WidgetService.java") \
                .decode("utf-8")
            full = z.read("main/java/com/example/FocusAlphaThing.java") \
                .decode("utf-8")
        self.assertTrue(skel.startswith("// [skeleton]"), skel[:120])
        self.assertIn("class WidgetService", skel)
        self.assertIn("public int serve()", skel)
        self.assertNotIn("BODY-ONLY-MARKER", skel)  # 본문 제거됨
        self.assertIn("FOCUS-BODY-MARKER", full)    # focus 파일은 전문 유지

    # 16. brief scope excludes frontend/static/templates (ctx keeps them)
    def test_16_brief_scope_excludes(self):
        r = run_pack(self.repo, self.out, "--profile", "brief")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        names = zip_names(sole_zip(self.out))
        for banned in ("frontend/src/App.tsx", "main/resources/static/js/app.js",
                       "main/resources/templates/home.html",
                       "docs/PROJECT_STATUS.md", "docs/architecture/overview.md"):
            self.assertNotIn(banned, names, banned)
        out2 = self.work / "out-ctx"
        out2.mkdir()
        r2 = run_pack(self.repo, out2, "--profile", "ctx")
        self.assertEqual(r2.returncode, 0, r2.stderr + r2.stdout)
        self.assertIn("frontend/src/App.tsx", zip_names(sole_zip(out2)))

    # 17. generated sections+payload rescan with the real scanner: zero hits
    def test_17_ctx_zip_secret_rescan_zero(self):
        import gptpro_pack as gp
        r = run_pack(self.repo, self.out, "--profile", "ctx")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        hits = []
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            for info in z.infolist():
                try:
                    t = z.read(info.filename).decode("utf-8")
                except UnicodeDecodeError:
                    continue
                hits += [(info.filename, ln, pid)
                         for ln, pid in gp.scan_text(info.filename.lower(), t)]
        self.assertEqual(hits, [], hits[:5])

    # 18. README cost order generated from configs SSOT, no stale phrase
    def test_18_readme_cost_order_from_config(self):
        r = run_pack(self.repo, self.out, "--profile", "core")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            readme = z.read("_README_FOR_GPTPRO.md").decode("utf-8")
        self.assertIn("alpha_lane → beta_lane → gamma_lane", readme)
        self.assertIn("zzz_free → yyy_paid", readme)
        self.assertNotIn("로컬 우선(Ollama)", readme)

    # 19. _START_HERE names the sections in reading order
    def test_19_start_here_reading_order(self):
        r = run_pack(self.repo, self.out, "--profile", "ctx")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            sh = z.read("_START_HERE.md").decode("utf-8")
        pos = [sh.find("_RULES_AND_ROLES.md"), sh.find("_CURRENT_WORK.md"),
               sh.find("_CHANGES.md"), sh.find("_CODE_MAP.md"),
               sh.find("_RUNTIME.md"), sh.find("_TEST_INDEX.md")]
        self.assertTrue(all(p > 0 for p in pos), pos)
        self.assertEqual(pos, sorted(pos), "reading order out of sequence")

    # 20. _TEST_INDEX lists test files; --focus marks matches with '*'
    def test_20_test_index_and_focus_mark(self):
        r = run_pack(self.repo, self.out, "--profile", "ctx",
                     "--focus", "FocusAlpha")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        with zipfile.ZipFile(sole_zip(self.out)) as z:
            idx = z.read("_TEST_INDEX.md").decode("utf-8")
            names = set(z.namelist())
        self.assertIn("src/test/java/com/example/AppTest.java", idx)
        self.assertIn("src/test/java/com/example/T.java", idx)
        # FocusAlpha 매칭 테스트가 있으면 '*' 표시 + 전문 포함
        for line in idx.splitlines():
            if "AppTest" in line and line.lstrip().startswith("*"):
                self.assertIn("src/test/java/com/example/AppTest.java", names)

    # 21. backward compat: ctx payload == core payload minus vendor file
    def test_21_ctx_payload_matches_core(self):
        r1 = run_pack(self.repo, self.out, "--profile", "core")
        self.assertEqual(r1.returncode, 0, r1.stderr + r1.stdout)
        core = zip_names(sole_zip(self.out))
        out2 = self.work / "out-ctx"
        out2.mkdir()
        r2 = run_pack(self.repo, out2, "--profile", "ctx")
        self.assertEqual(r2.returncode, 0, r2.stderr + r2.stdout)
        ctx = zip_names(sole_zip(out2))
        vend = "main/resources/static/js/vendor/chat/lib-1.2.3.min.js"
        expected = {n for n in core if not n.startswith("_")} - {vend}
        actual = {n for n in ctx if not n.startswith("_")}
        self.assertEqual(expected, actual)

    # 22. java_skeleton unit: signatures/comments kept, body stripped, cap works
    def test_22_skeleton_unit_and_cap(self):
        import gptpro_pack_context as gpc
        src = ("package p;\n\n/** doc line */\npublic class C {\n"
               "    private int f;\n"
               "    public int m() {\n        int LOCAL-VAR-MARKER = 1;\n"
               "        return LOCAL-VAR-MARKER;\n    }\n}\n")
        skel, ok = gpc.java_skeleton(src)
        self.assertTrue(ok)
        self.assertIn("package p;", skel)
        self.assertIn("public class C", skel)
        self.assertIn("private int f;", skel)
        self.assertIn("public int m()", skel)
        self.assertNotIn("LOCAL-VAR-MARKER = 1", skel)
        bad, ok2 = gpc.java_skeleton("class X {\n")  # 깊이 불일치 → 실패
        self.assertFalse(ok2)
        self.assertEqual(bad, "class X {\n")  # 실패 시 원문 반환
        many = "class Big {\n" + "".join(
            f"    public void m{i}() {{ int x{i} = {i}; }}\n"
            for i in range(800)) + "}\n"
        skel2, ok3 = gpc.java_skeleton(many)
        self.assertTrue(ok3)
        self.assertLessEqual(len(skel2.encode("utf-8")),
                             gpc.CAP_SKEL_FILE + 512)
        self.assertIn("truncated", skel2)


class EvidenceBoundaryTests(unittest.TestCase):
    """Additive contracts for evidence review findings; original 22 stay unchanged."""

    def test_evidence_plain_text_process_exit(self):
        import gptpro_pack_evidence as ge
        self.assertEqual(ge.output_failure({"output": "Process exited with code 1\npermission denied"})[0], 1)

    def test_evidence_patch_tool_failure_with_command_events(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc)
        with tempfile.TemporaryDirectory(prefix="gptpro-tool-failure-") as directory:
            root = Path(directory)
            sessions = root / "sessions"
            folder = sessions / now.strftime("%Y/%m/%d")
            folder.mkdir(parents=True)
            rows = [
                {"type": "session_meta", "timestamp": now.isoformat(), "payload": {"cwd": str(root)}},
                {"type": "event_msg", "timestamp": now.isoformat(), "payload": {
                    "type": "item_completed", "item": {"id": "command", "type": "CommandExecution",
                                                       "command": ["verify"], "exit_code": 0}}},
                {"type": "event_msg", "timestamp": now.isoformat(), "payload": {
                    "type": "item_completed", "item": {"id": "patch", "type": "McpToolCall",
                        "server": "fixture", "tool": "apply_patch", "status": "failed",
                        "result": {"isError": True, "content": [{"text": "invalid patch fixture"}]}}}},
            ]
            (folder / "rollout-fixture.jsonl").write_text(
                "\n".join(json.dumps(row) for row in rows + [rows[-1]]) + "\n", encoding="utf-8")
            e = ge.build(root, {}, git=lambda *a: None, scan=gp.scan_text, included=[], changed=[],
                         now=now, sessions_dir=sessions, probe_environment=False)
            self.assertEqual(e.meta["codexFailures"], 0)
            self.assertEqual(e.meta["codexToolFailures"], 1)
            self.assertIn("invalid patch fixture", e.files[ge.PREFIX + "agent/codex-project-failures.md"])

    def test_evidence_native_patch_failures_with_command_events(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc)
        with tempfile.TemporaryDirectory(prefix="gptpro-native-patch-") as directory:
            root = Path(directory)
            sessions = root / "sessions"
            folder = sessions / now.strftime("%Y/%m/%d")
            folder.mkdir(parents=True)
            rows = [
                {"type": "session_meta", "timestamp": now.isoformat(), "payload": {"cwd": str(root)}},
                {"type": "event_msg", "timestamp": now.isoformat(), "payload": {
                    "type": "item_completed", "item": {"id": "command", "type": "CommandExecution",
                                                       "command": ["verify"], "exit_code": 0}}},
                {"type": "response_item", "timestamp": now.isoformat(), "payload": {
                    "type": "custom_tool_call", "call_id": "native-output", "name": "apply_patch",
                    "input": "PRIVATE-PATCH-INPUT-FIXTURE"}},
                {"type": "response_item", "timestamp": now.isoformat(), "payload": {
                    "type": "custom_tool_call_output", "call_id": "native-output",
                    "output": "invalid patch: NATIVE-PATCH-FAILURE-FIXTURE"}},
                {"type": "event_msg", "timestamp": now.isoformat(), "payload": {
                    "type": "item_completed", "item": {"id": "native-change", "type": "FileChange",
                        "status": "failed", "changes": {"fixture": "PRIVATE-PATCH-HUNK-FIXTURE"}}}},
            ]
            (folder / "rollout-fixture.jsonl").write_text(
                "\n".join(json.dumps(row) for row in rows + [rows[-2], rows[-1]]) + "\n", encoding="utf-8")
            e = ge.build(root, {}, git=lambda *a: None, scan=gp.scan_text, included=[], changed=[],
                         now=now, sessions_dir=sessions, probe_environment=False)
            self.assertEqual(e.meta["codexFailures"], 0)
            self.assertEqual(e.meta["codexToolFailures"], 2)
            failure = e.files[ge.PREFIX + "agent/codex-project-failures.md"]
            self.assertIn("NATIVE-PATCH-FAILURE-FIXTURE", failure)
            self.assertIn("FileChange status=failed", failure)
            self.assertNotIn("PRIVATE-PATCH", "\n".join(e.files.values()))

    def test_evidence_stream_read_budget(self):
        import io
        import gptpro_pack_evidence as ge
        from unittest import mock
        stream = io.BytesIO(b"x" * 1000 + b"\n")
        with mock.patch.object(Path, "open", return_value=stream):
            rows = list(ge.lines(Path("fixture"), max_line=16, max_bytes=10))
            self.assertEqual(stream.tell() if not stream.closed else len(rows[0][0]), 10)
        self.assertEqual(len(rows[0][0]), 10)

    def test_evidence_conversation_omission(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        from datetime import datetime, timezone
        e = ge.Evidence(Path("."), {}, gp.scan_text, datetime.now(timezone.utc), {})
        e.add(ge.PREFIX + "debug/fixture.txt", "ERROR user prompt: PRIVATE-CONVERSATION-FIXTURE")
        self.assertNotIn("PRIVATE-CONVERSATION-FIXTURE", "\n".join(e.files.values()))

    def test_evidence_success_signals_cannot_starve_late_error(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        from datetime import datetime, timezone
        with tempfile.TemporaryDirectory(prefix="gptpro-error-priority-") as directory:
            root = Path(directory)
            path = root / "fixture.log"
            path.write_text("".join(f"INFO provider warmup success {i}\n" for i in range(20))
                            + "ERROR LATE-FAILURE-FIXTURE\n", encoding="utf-8")
            e = ge.Evidence(root, {"maxFileKb": 0.5}, gp.scan_text, datetime.now(timezone.utc), {})
            e.copy_excerpt(path, ge.PREFIX + "debug/fixture.txt")
            self.assertIn("LATE-FAILURE-FIXTURE", e.files[ge.PREFIX + "debug/fixture.txt"])

    def test_evidence_warning_caps(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        from datetime import datetime, timezone
        e = ge.Evidence(Path("."), {"maxFileKb": 0.5, "maxEvidenceMb": 0.008},
                        gp.scan_text, datetime.now(timezone.utc), {})
        e.warn("fixture", "x" * 1500)
        ge.finalize(e, [])
        self.assertLessEqual(len(e.files["PACK_WARNINGS.txt"].encode("utf-8")), 512)
        self.assertLessEqual(sum(len(t.encode("utf-8")) for t in e.files.values()), int(0.008 * 1048576))

    def test_evidence_protected_context_predicate(self):
        import gptpro_pack as gp
        import gptpro_pack_evidence as ge
        self.assertFalse(ge.safe_file(Path("main/resources/application-secrets.properties"), allow_templates=True))
        self.assertFalse(ge.safe_file(Path("configs/service-account.json"), allow_templates=True))
        self.assertTrue(ge.safe_file(Path(".env.example"), allow_templates=True))
        self.assertTrue(ge.safe_file(Path("main/java/ProviderCredentialAdapter.java"), allow_templates=True))
        self.assertTrue(ge.safe_file(Path("main/java/ApiKeyHeaderValidationHook.java"), allow_templates=True))
        self.assertIsNotNone(gp.name_block_reason("main/resources/application-secrets.properties"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
