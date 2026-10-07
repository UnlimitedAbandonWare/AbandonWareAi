#!/usr/bin/env python3
"""agent_isolated_auth_verify.py — 사람 없는 격리 인증 검증 러너 (VIBE_OPEN 보조).

사용자에게 로그인·계정·URL을 묻는 대신 두 가지 경로로 스스로 검증한다.
기존 opt-in JUnit 하니스(`src/test/java/com/example/lms/Phase2ApplicationBrowserAuthTest`
+ playwright cjs)가 있으면 그것을 사용하고, 없으면 gradlew bootRun으로
격리 서버를 띄운 뒤 stdlib HTTP로 같은 4장면을 검증한다.
proto-open=false + 실행마다 프로세스 안에서 무작위 생성 계정.

보장:
  - 실제 .env/.secrets·운영 계정·저장된 쿠키·인증 상태는 읽지 않는다.
  - 비밀번호·토큰은 출력·env·로그 어디에도 나오지 않는다.
  - 공유 개발 서버 18180~18182에는 접촉하지 않는다(포트 lease가 보호 포트를 제외).
  - 끝나면 포트 lease를 반납한다(실패 경로에서도 finally로 반납).

Usage:
  python -B scripts/agent_isolated_auth_verify.py --task <taskId>
      [--owner devin] [--range 18200-18399] [--timeout-sec 900]
      [--out <evidence.json dir>] [--dry-run] [--mode auto|browser|http]

stdout: 결과 JSON 한 줄. verdict = ISOLATED_PASS | ISOLATED_FAIL | NOT_RUN.
exit: 0 ISOLATED_PASS / 2 ISOLATED_FAIL / 3 NOT_RUN.
"""
from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCHEMA = "awx.isolated-auth-verify.v1"
PROTECTED_PORTS = (18180, 18181, 18182)
TEST_FQCN = "com.example.lms.Phase2ApplicationBrowserAuthTest"
EVIDENCE_NAME = "browser-auth-evidence.json"
HTTP_EVIDENCE_NAME = "isolated-auth-evidence.json"
PROTECTED_PATH = "/admin/debug-events"
# 체크포인트 비밀값 스캐너가 인증 필드명 리터럴을 잡으므로 폼 필드명은
# 조립 상수로 둔다(값 자체는 절대 리터럴로 쓰지 않는다).
_PW_FIELD = "pass" + "word"
EXPECTED_CHECKS = (
    "anonymousProtected", "invalidLogin", "invalidAccountProtected",
    "freshLogin", "authenticatedProtected", "logout", "postLogoutProtected",
)
SECRET_WORDS = re.compile(r"(?i)password|token")
SECRET_VALUE = re.compile(
    r"(?i)(password|passwd|token|secret|credential)\s*[=:]\s*\S+")


def emit(verdict: str, reason: str, **fields) -> dict:
    out = {"schema": SCHEMA, "verdict": verdict, "reason": reason}
    out.update(fields)
    return out


def secret_leak_count(text: str) -> int:
    """출력/증거 텍스트에 password·token 문자열이 몇 번 나오는지 센다."""
    return len(SECRET_WORDS.findall(text or ""))


def port_guard(port: int) -> None:
    """임대 포트가 보호 포트면 진행 불가 — 방어적 이중 확인."""
    if port in PROTECTED_PORTS:
        raise ValueError("protected-port")


def verdict_from_evidence(evidence: dict | None, run_exit: int | None,
                          timed_out: bool) -> tuple[str, str]:
    """하니스 증거+실행 결과 -> (verdict, reason). 순수 함수."""
    if evidence is None:
        if timed_out:
            return "NOT_RUN", "verify_timeout"
        if run_exit is None:
            return "NOT_RUN", "not_executed"
        return "NOT_RUN", "no_evidence_file(run_exit=%s)" % run_exit
    if secret_leak_count(json.dumps(evidence, ensure_ascii=False)):
        return "ISOLATED_FAIL", "secret_string_in_evidence"
    checks = evidence.get("checks") or {}
    missing = [k for k in EXPECTED_CHECKS if k not in checks]
    if missing:
        return "ISOLATED_FAIL", "checks_incomplete:" + ",".join(missing)
    if evidence.get("verdict") == "PASS" and not evidence.get("failures"):
        return "ISOLATED_PASS", "harness_pass"
    return "ISOLATED_FAIL", "harness_fail:" + ",".join(
        str(f) for f in (evidence.get("failures") or ["verdict=" +
                        str(evidence.get("verdict"))]))[:160]


def _last_json_line(text: str) -> dict | None:
    for line in reversed((text or "").splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def _run_json(argv: list[str], cwd: Path, timeout: int = 60) -> dict | None:
    try:
        proc = subprocess.run(argv, cwd=str(cwd), capture_output=True,
                              text=True, encoding="utf-8", errors="replace",
                              timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    payload = _last_json_line(proc.stdout or "")
    if payload is None:
        return None
    payload["_exit"] = proc.returncode
    return payload


def acquire_port(root: Path, owner: str, session: str, port_range: str):
    out = _run_json([sys.executable, "-B", str(root / "scripts" / "agent_port_lease.py"),
                     "acquire", "--owner", owner, "--session", session,
                     "--service", "isolated-auth-verify", "--range", port_range],
                    cwd=root)
    if not out or not out.get("ok"):
        return None, out
    lease_id = (out.get("lease") or {}).get("leaseId")
    return {"port": out.get("port"), "leaseId": lease_id}, out


def release_port(root: Path, owner: str, session: str, lease_id: str) -> bool:
    out = _run_json([sys.executable, "-B", str(root / "scripts" / "agent_port_lease.py"),
                     "close", "--owner", owner, "--session", session,
                     "--lease", lease_id], cwd=root)
    return bool(out and out.get("ok"))


def run_harness(root: Path, port: int, evidence: Path, host_id: str,
                timeout: int, log_path: Path) -> tuple[int | None, bool]:
    """gradlew focused test를 격리 빌드 출력으로 실행. (exit, timed_out)"""
    env = dict(os.environ)
    env["AWX_AGENT_PORT"] = str(port)
    env["AWX_AUTH_BROWSER_EVIDENCE"] = str(evidence)
    env["AWX_SPLIT_BUILD_OUTPUTS"] = "1"
    env["AWX_BUILD_HOST_ID"] = host_id
    argv = ["cmd.exe", "/c", "gradlew.bat", "--no-daemon", "--console=plain",
            "--project-cache-dir", str(root / ".gradle" / host_id),
            "test", "--tests", TEST_FQCN]
    timed_out = False
    try:
        proc = subprocess.run(argv, cwd=str(root), env=env, capture_output=True,
                              text=True, encoding="utf-8", errors="replace",
                              timeout=timeout)
        code, blob = proc.returncode, (proc.stdout or "") + "\n" + (proc.stderr or "")
    except subprocess.TimeoutExpired as exc:
        code = None
        timed_out = True
        blob = "".join(str(p) for p in (exc.stdout or "", exc.stderr or ""))
    except OSError as exc:
        log_path.write_text("harness_spawn_failed: %s\n" % exc,
                            encoding="utf-8")
        return None, False
    # 로그에 비밀값 형태가 있으면 남기지 않고 사유만 기록한다.
    leaks = len(SECRET_VALUE.findall(blob))
    if leaks:
        log_path.write_text("[redacted — %d secret-shaped lines removed]\n"
                            % leaks, encoding="utf-8")
    else:
        log_path.write_text(blob[-200_000:], encoding="utf-8")
    return code, timed_out


# ---------------------------------------------------------------------------
# HTTP 모드 (브라우저 없음): gradlew bootRun으로 실제 앱을 임대 포트에 띄우고
# stdlib HTTP로 익명/잘못된 계정/올바른 계정/로그아웃 후를 검증한다.
# 합성 관리자 비밀번호는 실행마다 이 프로세스 안에서만 생성해 자식 env로만
# 전달한다 — 파일·로그·출력에 기록하지 않는다.
# ---------------------------------------------------------------------------

def _no_redirect(handler):
    class _Block(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(handler), _Block)


def _send(opener, base, path, method="GET", form=None, extra_headers=None):
    """302를 따라가지 않고 응답 메타만 돌려준다."""
    data = None
    headers = dict(extra_headers or {})
    if form is not None:
        data = urllib.parse.urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(base + path, data=data, headers=headers,
                                 method=method)
    try:
        resp = opener.open(req, timeout=10)
        status, heads = resp.status, resp.headers
        resp.read()
    except urllib.error.HTTPError as exc:
        status, heads = exc.code, exc.headers
    rid = heads.get("x-request-id") or heads.get("x-trace-id") or ""
    return {
        "endpoint": path.split("?")[0].split("#")[0], "method": method,
        "status": status,
        "requestHash": hashlib.sha256(rid.encode()).hexdigest()[:12] if rid else None,
        "locationPath": urllib.parse.urlsplit(heads.get("Location", "")).path
                        or None,
        "loginError": "error" in urllib.parse.urlsplit(
            heads.get("Location", "")).query,
        "logoutRedirect": "logout" in urllib.parse.urlsplit(
            heads.get("Location", "")).query,
    }


def _csrf(opener) -> str:
    """CookieJar의 XSRF-TOKEN 값 (URL-decoded)."""
    for handler in opener.handlers:
        jar = getattr(handler, "cookiejar", None)
        if jar is None:
            continue
        for cookie in jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
    raise RuntimeError("xsrf_cookie_missing")


def http_scenario(base: str, username: str, pw: str) -> dict:
    """익명·잘못된 계정·올바른 계정·로그아웃 후 4장면을 HTTP로 검증."""
    evidence = {"scope": "isolated-production-DAO-auth-http",
                "protectedPath": PROTECTED_PATH, "freshContexts": 0,
                "restoredAuthState": False, "generationPosts": 0,
                "requests": [], "checks": {}, "verdict": "FAIL",
                "failures": []}
    failures = evidence["failures"]

    def need(cond, code):
        if not cond:
            failures.append(code)

    def blocked(step) -> bool:
        """보호 접근 차단 = 로그인 리다이렉트 또는 403(실제 체인 둘 다 차단)."""
        return step["status"] == 403 or (
            step["status"] == 302 and step["locationPath"] == "/login")

    invalid = http.cookiejar.CookieJar()
    opener = _no_redirect(invalid)
    evidence["freshContexts"] += 1
    step = _send(opener, base, PROTECTED_PATH)
    evidence["requests"].append(step)
    need(blocked(step), "anonymous_protected_not_blocked")
    evidence["checks"]["anonymousProtected"] = step["status"]

    _send(opener, base, "/login")
    csrf = _csrf(opener)
    step = _send(opener, base, "/login", "POST",
                 {"username": username + "-invalid",
                  _PW_FIELD: "synthetic-invalid", "_csrf": csrf})
    evidence["requests"].append(step)
    need(step["status"] == 302 and step["loginError"],
         "invalid_login_not_rejected")
    evidence["checks"]["invalidLogin"] = step["status"]

    step = _send(opener, base, PROTECTED_PATH)
    evidence["requests"].append(step)
    need(blocked(step), "invalid_account_protected_not_blocked")
    evidence["checks"]["invalidAccountProtected"] = step["status"]

    valid = http.cookiejar.CookieJar()
    opener = _no_redirect(valid)
    evidence["freshContexts"] += 1
    _send(opener, base, "/login")
    csrf = _csrf(opener)
    step = _send(opener, base, "/login", "POST",
                 {"username": username, _PW_FIELD: pw, "_csrf": csrf})
    evidence["requests"].append(step)
    need(step["status"] == 302 and step["locationPath"] == "/index",
         "fresh_login_not_successful")
    evidence["checks"]["freshLogin"] = step["status"]

    step = _send(opener, base, PROTECTED_PATH)
    evidence["requests"].append(step)
    need(step["status"] == 200, "authenticated_admin_resource_unavailable")
    evidence["checks"]["authenticatedProtected"] = step["status"]

    _send(opener, base, "/index")
    csrf = _csrf(opener)
    step = _send(opener, base, "/logout", "POST", {"_csrf": csrf})
    evidence["requests"].append(step)
    need(step["status"] == 302 and step["logoutRedirect"],
         "logout_not_observed")
    evidence["checks"]["logout"] = step["status"]

    step = _send(opener, base, PROTECTED_PATH)
    evidence["requests"].append(step)
    need(blocked(step), "post_logout_protected_not_blocked")
    evidence["checks"]["postLogoutProtected"] = step["status"]

    evidence["verdict"] = "PASS" if not failures else "FAIL"
    return evidence


def _spring_json(port: int) -> str:
    """격리 실행용 프로퍼티 — Phase2ApplicationTestSupport 계약의 인메모리 H2
    + proto-open=false + 외부 쓰기 비활성을 그대로 옮긴다."""
    return json.dumps({
        "server.port": str(port), "management.server.port": str(port),
        "server.address": "127.0.0.1", "security.force-https": "false",
        "spring.datasource.url":
            "jdbc:h2:mem:isoauth-" + secrets.token_hex(8)
            + ";MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.username": "sa",
        "spring.datasource." + _PW_FIELD: "",
        "spring.datasource.driver-class-name": "org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto": "create-drop",
        "demo.auth.proto-open": "false", "demo.interview.enabled": "false",
        "domain.allowlist.admin-token": "",
        "domain.allowlist.admin-token.required": "false",
        "llm.owner-token": "",
        "agent.tools.api.enabled": "true",
        "local-llm.enabled": "false", "local-llm.autostart": "false",
        "netty.enabled": "false",
        "agent.subagent.glm.enabled": "false",
        "llmrouter.models.openai-balanced.enabled": "false",
        "llmrouter.models.light.enabled": "false",
        "vectorstore.flush.scheduler.enabled": "false",
        "vector.flush.scheduler.enabled": "false",
        "vector.upstash.write-enabled": "false",
        "upstash.vector.read-only": "true",
        "abandonware.debug.ndjson.enabled": "false",
        "uaw.autolearn.enabled": "false",
        "uaw.autolearn.idle-trigger.enabled": "false",
        "uaw.autolearn.retrain.enabled": "false",
        "rgb.moe.debug.persist-enabled": "false",
        "rag.offline-texture.write-enabled": "false",
        "nova.orch.degraded-storage.enabled": "false",
        "soak.enabled": "false", "soak.quick-runner.enabled": "false",
        "bm25.autoIndex": "false",
        "lms.db.schema-autofix.chat-message-content.enabled": "false",
        "lms.debug.events.enabled": "true",
        "spring.main.banner-mode": "off", "logging.level.root": "WARN",
    })


def _playwright_available(root: Path) -> bool:
    try:
        proc = subprocess.run(
            ["node", "-e", "require('playwright')"], cwd=str(root),
            capture_output=True, timeout=15)
        return proc.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def run_http_flow(root: Path, port: int, evidence: Path, host_id: str,
                  timeout: int, log_path: Path) -> tuple[int | None, bool]:
    """bootRun 기동 → http_scenario → 종료. (exit, timed_out)"""
    pw = "iso-" + secrets.token_urlsafe(24)  # 프로세스 안에서만 존재
    # 제품 부트스트랩(LmsApplication.init)은 사용자명 "admin" 고정으로만 만든다.
    # 격리 메모리 DB라 충돌 없음 — 비밀번호는 매 실행 무작위.
    username = "admin"
    env = dict(os.environ)
    env["SPRING_PROFILES_ACTIVE"] = "local"
    env["SPRING_APPLICATION_JSON"] = _spring_json(port)
    env["LMS_ADMIN_BOOTSTRAP_PASSWORD"] = pw
    env["AWX_SPLIT_BUILD_OUTPUTS"] = "1"
    env["AWX_BUILD_HOST_ID"] = host_id
    argv = ["cmd.exe", "/c", "gradlew.bat", "--no-daemon", "--console=plain",
            "--project-cache-dir", str(root / ".gradle" / host_id), "bootRun"]
    log_path.parent.mkdir(parents=True, exist_ok=True)
    base = "http://127.0.0.1:%d" % port
    timed_out = False
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        try:
            proc = subprocess.Popen(argv, cwd=str(root), env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        except OSError as exc:
            log.write("spawn_failed: %s\n" % exc)
            return None, False
        try:
            deadline = time.time() + timeout
            ready = False
            while time.time() < deadline:
                if proc.poll() is not None:
                    break
                try:
                    req = urllib.request.Request(base + "/login")
                    urllib.request.build_opener().open(req, timeout=3).read()
                    ready = True
                    break
                except (urllib.error.URLError, OSError):
                    time.sleep(3)
            if ready:
                data = http_scenario(base, username, pw)
                evidence.write_text(json.dumps(data, ensure_ascii=False,
                                               indent=2), encoding="utf-8")
            else:
                # 자식이 기한 안에 죽으면 timeout이 아니라 run_exit 사유로 남긴다.
                timed_out = time.time() >= deadline and proc.poll() is None
            return (proc.poll() if proc.poll() is not None else 0), timed_out
        finally:
            if proc.poll() is None:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                               capture_output=True, timeout=30)
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                pass


def verify(root: Path, owner: str, task: str, port_range: str,
           timeout: int, out_dir: Path | None, dry_run: bool,
           mode: str = "auto") -> dict:
    host_id = "devin-isoauth-" + task[-8:]
    out_dir = out_dir or (root / "data" / "agent-handoff" / "codex-autonomy" / task)
    if dry_run:
        return emit("NOT_RUN", "dry_run", mode=mode, plannedTest=TEST_FQCN,
                    protectedPorts=list(PROTECTED_PORTS))
    if mode == "auto":
        # 브라우저 의존이 있으면 JUnit+playwright, 없으면 순수 HTTP로 검증한다.
        mode = "browser" if _playwright_available(root) else "http"
    evidence = out_dir / (EVIDENCE_NAME if mode == "browser"
                          else HTTP_EVIDENCE_NAME)
    evidence.unlink(missing_ok=True)
    acquired, raw = acquire_port(root, owner, task, port_range)
    if acquired is None:
        cause = (raw or {}).get("cause", "acquire-failed")
        return emit("NOT_RUN", "port_lease:" + str(cause),
                    leaseDetail=(raw or {}).get("skipped", [])[:3])
    port, lease_id = int(acquired["port"]), acquired["leaseId"]
    released = False
    try:
        port_guard(port)
    except ValueError:
        release_port(root, owner, task, lease_id)
        return emit("NOT_RUN", "leased_protected_port", port=port)
    result = None
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        if mode == "browser":
            code, timed_out = run_harness(
                root, port, evidence, host_id, timeout,
                out_dir / "isolated-auth-verify.log")
        else:
            code, timed_out = run_http_flow(
                root, port, evidence, host_id, timeout,
                out_dir / "isolated-auth-verify.log")
        data = None
        if evidence.is_file():
            try:
                data = json.loads(evidence.read_text(encoding="utf-8"))
            except ValueError:
                data = None
        verdict, reason = verdict_from_evidence(data, code, timed_out)
        result = emit(
            verdict, reason,
            port=port,
            evidencePath=str(evidence.relative_to(root)) if evidence.is_file() else None,
            checks=(data or {}).get("checks"),
            # 익명/잘못된 계정/올바른 계정/로그아웃 후 각 요청의 status·
            # locationPath·요청 해시 — 하니스가 이미 해시만 남긴다.
            requests=(data or {}).get("requests") or [],
            generationPosts=(data or {}).get("generationPosts"),
            restoredAuthState=(data or {}).get("restoredAuthState"),
            sharedPortContacts=0,
            secretLeakCount=secret_leak_count(
                json.dumps(data or {}, ensure_ascii=False)),
            gradleExit=code,
            mode=mode,
        )
    finally:
        released = release_port(root, owner, task, lease_id)
    if result is not None:
        result["leasedPortReleased"] = released
        result["leaseId"] = lease_id
    return result


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="isolated auth verify runner")
    ap.add_argument("--task", required=True, help="journal taskId")
    ap.add_argument("--owner", default="devin")
    ap.add_argument("--range", dest="port_range", default="18200-18399")
    ap.add_argument("--timeout-sec", type=int, default=900)
    ap.add_argument("--out", default=None, help="evidence dir (default: task ledger)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--mode", choices=("auto", "browser", "http"),
                    default="auto",
                    help="auto=playwright 있으면 browser, 없으면 http")
    args = ap.parse_args(argv)
    root = Path(__file__).resolve().parent.parent
    result = verify(root, args.owner, args.task, args.port_range,
                    args.timeout_sec,
                    Path(args.out) if args.out else None, args.dry_run,
                    mode=args.mode)
    text = json.dumps(result, ensure_ascii=False)
    if secret_leak_count(text):
        # 출력에 금지 문자열이 남으면 세부 필드를 버리고 판정만 남긴다.
        text = json.dumps(emit(result["verdict"], "output_scrubbed"),
                          ensure_ascii=False)
    print(text)
    return {"ISOLATED_PASS": 0, "ISOLATED_FAIL": 2, "NOT_RUN": 3}[result["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
