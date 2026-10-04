"""Stack-fit behavior contracts; all inputs and files are synthetic and offline."""
import importlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/stack_fit_guard.py"


class StackFitGuardTest(unittest.TestCase):
    def setUp(self):
        self.guard = importlib.import_module("scripts.stack_fit_guard")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "configs").mkdir()
        (self.root / "configs/stack-fit.yaml").write_bytes(
            (ROOT / "configs/stack-fit.yaml").read_bytes())

    def text(self, text):
        return self.guard.evaluate(self.root, text=text)

    def changed(self, path, before, after):
        return self.guard.evaluate(self.root, changes=[
            self.guard.Change(path, before, after)])

    def assertBlocked(self, result, tech):
        self.assertIn(result["verdict"], ("ADAPT", "DECLINE"))
        self.assertIn(tech, [f["tech"] for f in result["findings"]])
        self.assertIn(result["exitCode"], (3, 4))

    def cli(self, *args):
        proc = subprocess.run([sys.executable, "-X", "utf8", "-B", str(SCRIPT),
                               "--root", str(self.root), "--json", *args],
                              capture_output=True, encoding="utf-8", timeout=5)
        return proc, json.loads(proc.stdout)

    def test_01_migrate_backend_to_nest(self):
        self.assertBlocked(self.text("백엔드를 NestJS로 옮겨줘"), "nestjs")

    def test_02_new_nest_dependency(self):
        self.assertBlocked(self.changed("frontend/package.json",
            '{"dependencies":{"next":"16.2.10"}}',
            '{"dependencies":{"next":"16.2.10","@nestjs/core":"1"}}'), "nestjs")

    def test_03_korean_nest(self):
        for text in ("네스트JS 도입", "네스트 제이에스 도입"):
            with self.subTest(text=text):
                self.assertBlocked(self.text(text), "nestjs")

    def test_04_fastapi_server(self):
        self.assertBlocked(self.text("FastAPI 서버 하나 띄우자"), "fastapi")

    def test_05_kafka_queue(self):
        self.assertBlocked(self.text("작업 큐를 Kafka로"), "kafka")

    def test_06_docker_runtime(self):
        self.assertBlocked(self.text("Docker로 런타임 전환"), "docker-runtime")

    def test_07_nest_marker(self):
        self.assertBlocked(self.changed("nest-cli.json", "", "{}"), "nestjs")

    def test_08_resume_go_server(self):
        self.assertBlocked(self.text("이력서에 쓰게 Go 서버 하나 추가"), "go-server")

    def test_09_next_version_upgrade(self):
        self.assertEqual(self.text("next 16.2.10→16.2.11 버전업")["verdict"], "FIT")
        self.assertEqual(self.changed("frontend/package.json",
            '{"dependencies":{"next":"16.2.10"}}',
            '{"dependencies":{"next":"16.2.11"}}')["verdict"], "FIT")

    def test_10_nori_settings(self):
        self.assertEqual(self.text("lucene-analysis-nori 설정 조정")["verdict"], "FIT")

    def test_11_word_boundaries(self):
        for word in ("nested", "nestedList", "NestedConfig", "nest_level"):
            with self.subTest(word=word):
                self.assertEqual(self.text(word + " JSON 파싱 버그 수정")["verdict"], "FIT")

    def test_12_read_only_research(self):
        self.assertEqual(self.text("NestJS 공식 문서랑 Spring 비교만 해줘(읽기 전용)")["verdict"], "FIT")

    def test_13_existing_soniox(self):
        data = (ROOT / "main/resources/soniox-sidecar/package.json").read_text(encoding="utf-8")
        self.assertEqual(self.changed("main/resources/soniox-sidecar/package.json", data, data)["verdict"], "FIT")

    def test_14_user_approved_override(self):
        adr = self.root / "docs/architecture/decisions/ADR-0002-nest.md"
        adr.parent.mkdir(parents=True)
        adr.write_text("---\nstatus: ACCEPTED\napprovedBy: user\ntech: nestjs\n---\n", encoding="utf-8")
        result = self.text("NestJS 도입해줘")
        self.assertEqual(result["verdict"], "OVERRIDE")
        self.assertEqual(result["exitCode"], 0)

    def test_denial_and_adaptation_are_distinct(self):
        self.assertEqual(self.text("NestJS 도입")["verdict"], "ADAPT")
        self.assertEqual(self.text("새 SaaS 계정 추가")["verdict"], "DECLINE")

    def test_unapproved_and_superseded_adr_cannot_override(self):
        adr = self.root / "docs/architecture/decisions/ADR-0002.md"
        adr.parent.mkdir(parents=True)
        for status, approver in (("DECLINED", "user"), ("SUPERSEDED", "user"), ("ACCEPTED", "agent")):
            adr.write_text(f"---\nstatus: {status}\napprovedBy: {approver}\ntech: nestjs\n---\n", encoding="utf-8")
            self.assertEqual(self.text("NestJS 추가")["verdict"], "ADAPT")

    def test_override_only_matches_structured_tech(self):
        adr = self.root / "docs/architecture/decisions/ADR-0002.md"
        adr.parent.mkdir(parents=True)
        adr.write_text("---\nstatus: accepted\napprovedBy: user\ntech: kafka\n---\nDiscuss NestJS.\n", encoding="utf-8")
        self.assertEqual(self.text("NestJS 도입")["verdict"], "ADAPT")

    def test_partial_override_cannot_allow_unapproved_tech(self):
        self.test_14_user_approved_override()
        result = self.text("NestJS 추가. 새 SaaS 계정 추가.")
        self.assertEqual(result["verdict"], "DECLINE")
        self.assertTrue(next(f for f in result["findings"] if f["tech"] == "nestjs")["overridden"])

    def test_negation_is_local(self):
        self.assertEqual(self.text("NestJS 도입 금지. Spring 컨트롤러 수정.")["verdict"], "FIT")
        self.assertBlocked(self.text("NestJS 비교만. FastAPI 서버 추가."), "fastapi")
        self.assertBlocked(self.text("읽기 전용 조사 후 NestJS 서버 도입해줘"), "nestjs")

    def test_go_rust_kotlin_language_mentions_are_not_new_servers(self):
        for text in ("Go 코드를 읽고 문서 추가", "Rust 문서 비교", "Kotlin DSL 설정 추가"):
            self.assertEqual(self.text(text)["verdict"], "FIT")
        self.assertBlocked(self.text("Kotlin 별도 서버 런타임 추가"), "kotlin-server")

    def test_same_library_family_and_test_only_dependencies(self):
        self.assertEqual(self.changed("build.gradle.kts", "",
            'implementation("org.apache.lucene:lucene-analysis-kuromoji:9.10.0")')["verdict"], "FIT")
        self.assertEqual(self.changed("build.gradle.kts", "",
            'testImplementation("org.example:synthetic-test:1")')["verdict"], "FIT")
        self.assertEqual(self.changed("frontend/package.json", "{}",
            '{"devDependencies":{"vitest":"1"}}')["verdict"], "FIT")

    def test_gradle_and_python_manifest_dependencies(self):
        self.assertBlocked(self.changed("build.gradle", "",
            "implementation 'org.apache.kafka:kafka-clients:1'"), "kafka")
        self.assertBlocked(self.changed("requirements-server.txt", "", "fastapi==1\n"), "fastapi")
        self.assertBlocked(self.changed("pyproject.toml", "", '[project]\ndependencies = ["django>=1"]'), "django")
        self.assertEqual(self.changed("pyproject.toml", "", '[dependency-groups]\ntest = ["pytest"]')["verdict"], "FIT")

    def test_module_import_and_server_markers(self):
        for path, text, tech in (("service.ts", "import { x } from '@nestjs/core';", "nestjs"),
                                 ("x.module.ts", "@Module({})", "nestjs"),
                                 ("Dockerfile", "FROM synthetic", "docker-runtime"),
                                 ("docker-compose.yml", "services: {}", "docker-runtime"),
                                 ("server.py", "uvicorn.run(app)", "fastapi"),
                                 ("start.sh", "flask run", "flask"),
                                 ("server.js", "const app = express(); app.listen(1234);", "express")):
            with self.subTest(path=path):
                self.assertBlocked(self.changed(path, "", text), tech)

    def test_existing_markers_and_library_versions_are_grandfathered(self):
        text = "const app = express(); app.listen(1234);"
        self.assertEqual(self.changed("server.js", text, text)["verdict"], "FIT")
        self.assertEqual(self.changed("frontend/package.json",
            '{"dependencies":{"@nestjs/core":"1"}}',
            '{"dependencies":{"@nestjs/core":"2"}}')["verdict"], "FIT")

    def test_no_raw_text_or_secret_value_in_findings(self):
        sentinel = "SYNTHETIC_PRIVATE_SENTINEL_12345"
        result = self.text("NestJS 추가. " + sentinel)
        self.assertNotIn(sentinel, json.dumps(result))
        self.assertTrue(all(set(("kind", "tech", "evidence", "instead")) <= set(f) for f in result["findings"]))

    def test_secret_paths_never_opened(self):
        for path in (".env", ".env.local", ".secrets/test.txt", "../.env", "key.pem"):
            with self.subTest(path=path), patch.object(Path, "read_text", side_effect=AssertionError("must not open")):
                self.assertTrue(self.guard.forbidden_path(Path(path)))
        proc, result = self.cli("--paths", ".env.local")
        self.assertEqual(proc.returncode, 4)
        self.assertEqual(result["reason"], "input-path-blocked")

    def test_root_escape_and_missing_inputs_decline(self):
        for args in (("--paths", "../outside.py"), ("--paths", "missing.py"),
                     ("--text-file", str(self.root / ".env")), ("--text-file", str(self.root / "missing.md"))):
            proc, result = self.cli(*args)
            self.assertEqual(proc.returncode, 4)
            self.assertEqual(result["verdict"], "DECLINE")

    def test_cli_text_and_file(self):
        proc, result = self.cli("--text", "NestJS 도입")
        self.assertEqual(proc.returncode, 3)
        self.assertEqual(result["verdict"], "ADAPT")
        path = self.root / "request.md"
        path.write_text("nested JSON 파싱 버그 수정", encoding="utf-8")
        proc, result = self.cli("--text-file", str(path))
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(result["verdict"], "FIT")

    def test_paths_marker_and_existing_manifest(self):
        (self.root / "nest-cli.json").write_text("{}", encoding="utf-8")
        proc, result = self.cli("--paths", "nest-cli.json")
        self.assertEqual(proc.returncode, 3)
        (self.root / "package.json").write_text('{"dependencies":{"next":"16.2.10"}}', encoding="utf-8")
        proc, result = self.cli("--paths", "package.json")
        self.assertEqual(proc.returncode, 0)

    def test_policy_artifacts_do_not_install_the_technologies_they_describe(self):
        for path in ("scripts/test_stack_fit_guard.py", "scripts/stack_fit_guard.py",
                     "configs/stack-fit.yaml", "docs/architecture/decisions/ADR-0001.md"):
            self.assertEqual(self.changed(path, "", 'NestJS追加 @Module() uvicorn.run(app) express()')["verdict"], "FIT")

    def test_diff_reads_staged_worktree_and_untracked_without_secrets(self):
        calls = []
        def git(root, *args, **kwargs):
            calls.append(args)
            if args[0] == "diff":
                return "server.ts\0.env\0" if "--cached" in args else "server.ts\0"
            if args[0] == "ls-files":
                return "nest-cli.json\0.secrets/private.txt\0"
            if args[0] == "show":
                return "" if args[1].startswith("HEAD:") else "import '@nestjs/core';"
            raise AssertionError(args)
        (self.root / "server.ts").write_text("import '@nestjs/core';", encoding="utf-8")
        (self.root / "nest-cli.json").write_text("{}", encoding="utf-8")
        with patch.object(self.guard, "git_read", side_effect=git):
            changes = self.guard.read_changes(self.root)
        self.assertEqual({c.path for c in changes}, {"server.ts", "nest-cli.json"})
        self.assertTrue(any("--cached" in c for c in calls))
        self.assertFalse(any(".env" in str(c) or ".secrets" in str(c) for c in calls if c[0] == "show"))
        self.assertBlocked(self.guard.evaluate(self.root, changes=changes), "nestjs")

    def test_malformed_manifest_is_not_silent_fit(self):
        result = self.changed("package.json", "{}", "{broken")
        self.assertEqual(result["verdict"], "DECLINE")
        self.assertEqual(result["reason"], "manifest-invalid")

    def test_policy_description_retains_independent_install_request(self):
        description = "새 SaaS 요청이 오면, 그 부분만 거절하고 나머지 작업은 계속하게 만든다."
        self.assertEqual(self.text(description)["verdict"], "FIT")
        self.assertBlocked(self.text(description + " NestJS 도입해줘."), "nestjs")
        example = 'AGENTS.md에 블록 추가: "새 프레임워크 추가는 판정 후 진행."'
        self.assertEqual(self.text(example)["verdict"], "FIT")
        self.assertBlocked(self.text(example + " FastAPI 서버 추가."), "fastapi")

    def test_independent_actions_after_negation_or_comparison(self):
        for text, tech in (("Do not change Spring, add NestJS backend.", "nestjs"),
                           ("Compare NestJS in read-only mode, and add a FastAPI server.", "fastapi"),
                           ('기대 개선: "NestJS" 서버 추가', "nestjs")):
            self.assertBlocked(self.text(text), tech)

    def test_install_actions(self):
        for text in ("Install a FastAPI server.", "FastAPI 서버 설치해줘"):
            self.assertBlocked(self.text(text), "fastapi")

    def test_inline_and_named_gradle_declarations(self):
        for text in ('dependencies { implementation("org.apache.kafka:kafka-clients:1") }',
                     'implementation(group = "org.apache.kafka", name = "kafka-clients", version = "1")',
                     "api group: 'org.apache.kafka', name: 'kafka-clients', version: '1'"):
            self.assertBlocked(self.changed("build.gradle.kts", "", text), "kafka")

    def test_direct_application_and_other_server_markers(self):
        for path, text, tech in (("server.py", "from fastapi import FastAPI\napp = FastAPI()", "fastapi"),
                                 ("server.go", 'http.ListenAndServe(":1234", nil)', "go-server"),
                                 ("server.rs", 'TcpListener::bind("localhost")', "rust-server"),
                                 ("server.kt", "embeddedServer(Netty) {}", "kotlin-server"),
                                 ("server.js", "server.listen(1234);", "node-server")):
            self.assertBlocked(self.changed(path, "", text), tech)

    def test_invalid_dependency_shapes_and_adr_fields(self):
        for doc in ('{"dependencies":"@nestjs/core"}', '{"devDependencies":[]}',
                    '{"dependencies":{"@nestjs/core":null}}', '[]'):
            self.assertEqual(self.changed("package.json", "{}", doc)["reason"], "manifest-invalid")
        adr = self.root / "docs/architecture/decisions/ADR-0002.md"
        adr.parent.mkdir(parents=True)
        for fields in ("status: ACCEPTED\napprovedBy: user\ntech: [nestjs",
                       "status: DECLINED\nstatus: ACCEPTED\napprovedBy: user\ntech: nestjs"):
            adr.write_text("---\n" + fields + "\n---\n", encoding="utf-8")
            self.assertEqual(self.text("NestJS 도입")["verdict"], "ADAPT")

    def test_recorded_rule_conflict_stays_declined(self):
        held = "plan16의 Nest 계층·localhost 접점은 현재 규칙과 충돌합니다(CONFLICT/ASK_ONCE). 이번 범위에서는 HOLD입니다."
        self.assertEqual(self.text(held)["verdict"], "DECLINE")
        self.assertEqual(self.text('- hold(새로 도입 금지, 대안 필수): Docker 런타임 전환, 새 프레임워크 재작성')["verdict"], "FIT")

    def test_stdlib_literal_documentation_and_unrelated_binary_paths(self):
        data = 'import json\nexample = "FastAPI() uvicorn.run(app) express()"\nprint(json.dumps(example))'
        self.assertEqual(self.changed("scripts/one_shot.py", "", data)["verdict"], "FIT")
        self.assertFalse(self.guard.relevant_path("image.png"))
        self.assertFalse(self.guard.relevant_path(".git/config"))
        self.assertFalse(self.guard.relevant_path("docs/guide.md"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
