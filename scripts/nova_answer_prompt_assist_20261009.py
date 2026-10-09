"""Read-only assist for the DEVIN nova-answer-prompt brief.

Brief: PASTE_DEVIN_nova-answer-prompt_20261009.txt
Main patching session journal: data/agent-handoff/codex-autonomy/devin-nova-answer-prompt-ba8a924e
Scanner engine: scripts/pair_brief_assist.py (pin / cover / diff-forbid delegate).
Extra commands: scope, hypothesis, hold, verify-plan, snapshot, diff-review, selftest.
Stdlib only. No network, Gradle, server, Git, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.nova-answer-prompt-assist.v1"
PACK = "var/codex-assist-nova-answer-prompt-20261009"
DEFAULT_SPEC = PACK + "/spec.json"
HYPO_REL = PACK + "/hypothesis.json"
HOLD_REL = PACK + "/hold.json"
BASELINE_REL = PACK + "/baseline.json"
LEASE_DIR = "__patch_drop__/source-edit-locks"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"
MAIN_TASK_PREFIX = "devin-nova-answer-prompt-"
ASSIST_TASK_PREFIX = "devin-nova-answer-prompt-assist-"

# 지시서 §2 수정 허용 예산 + 관측된 SCOPE_EXPAND 배관 파일. 어시스트는 읽기 전용.
BUDGET = (
    "main/java/com/example/lms/assist/NovaFocusSettings.java",
    "main/java/com/example/lms/assist/NovaFocusAnswerService.java",
    "main/java/com/example/lms/assist/NovaFocusHistoryService.java",
    "main/java/com/example/lms/assist/NovaFocusService.java",
    "main/java/com/example/lms/service/ChatConversationContext.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/prompt/PromptContext.java",
    "main/java/com/example/lms/prompt/StandardPromptBuilder.java",
    "main/resources/static/assets/display/display-focus-controls.js",
    "main/resources/static/assets/display/index.html",
    "src/test/java/com/example/lms/assist/NovaFocusAnswerServiceTest.java",
    "src/test/java/com/example/lms/assist/NovaFocusDisplayContractTest.java",
    "src/test/java/com/example/lms/assist/NovaFocusHistoryTest.java",
    "src/test/java/com/example/lms/assist/NovaFocusRestartPersistenceTest.java",
    "src/test/js/display-focus-client.test.cjs",
)

# 지시서 §2/§6 보호면 — 추가 diff 라인이나 snapshot 이후 변경은 리뷰 신호.
PROTECTED = (
    "main/java/com/example/lms/assist/NovaFocusState.java",
    "main/resources/static/js/chat.js",
    "main/java/com/example/lms/assist/NovaFocusProfile.java",
    "main/java/com/example/lms/api/ChatApiController.java",
)

WATCH = BUDGET + PROTECTED

VERIFY_PLAN = [
    {"step": 1, "id": "compile", "command": ".\\gradlew.bat :compileJava -x test",
     "acceptance": ["A3"], "note": "주입 체인 8개 Java 파일 컴파일 선행. 실패 시 이후 단계 의미 없음."},
    {"step": 2, "id": "java-tests", "command": ".\\gradlew.bat test --tests \"com.example.lms.assist.NovaFocus*Test\" --tests \"com.example.lms.prompt.StandardPromptBuilderConversationHistoryTest\" --tests \"com.example.lms.service.ChatWorkflowPromptMessageRoleTest\"",
     "acceptance": ["A2", "A3"], "note": "targeted 만 — 전체 스위트 금지(지시서 §6). 신규 테스트 없으면 NOT_DONE."},
    {"step": 3, "id": "js-tests", "command": "node --test src/test/js/display-focus-client.test.cjs",
     "acceptance": ["A4"], "note": "S7-f: 하네스 없으면 '확인 필요'로 보고."},
    {"step": 4, "id": "reload", "command": "scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch",
     "acceptance": ["A5"], "note": "demo1-dev-reload 계약. 재기동 전 journal에 '재기동합니다' 한 줄."},
    {"step": 5, "id": "ready", "command": "python -B scripts/model_default_probe.py --ready",
     "acceptance": ["A5"], "note": "READY 아니면 노바 준비 아님. /chat 200과 별개."},
    {"step": 6, "id": "live-focus", "command": "local http://127.0.0.1:18180 — 설정 INTERVIEW 저장 → 노바 포커스 1건 → trace focus.instruction.preset=INTERVIEW 확인",
     "acceptance": ["A5"], "note": "공개 사이트 전송 0회, 실제 모델 호출 ≤5회(지시서 §6)."},
    {"step": 7, "id": "restore-settings", "command": "사용자 프로필 설정을 원래 값으로 되돌려 저장",
     "acceptance": ["A7"], "note": "프로필을 바꾼 채 두지 않는다."},
    {"step": 8, "id": "post-review", "command": "python -B scripts/nova_answer_prompt_assist_20261009.py pin|cover|scope + diff-forbid --diff <post.diff>",
     "acceptance": ["A1", "A6"], "note": "보호 파일 터치 0건과 지시서 앵커 최종 상태를 수치로 남긴다."},
]


def base(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "reclaim": False,
        "forceRelease": False,
    }


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def norm(path: str) -> str:
    return str(path).replace("\\", "/").lstrip("./").casefold()


def sha256_of(path: Path):
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_ts(text):
    if not isinstance(text, str) or not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        # 7자리 소수초 등 — 방어적 절단 후 재시도
        try:
            head, _, tail = text.partition(".")
            frac = "".join(ch for ch in tail if ch.isdigit())[:6]
            zone = "+" + tail.rsplit("+", 1)[1] if "+" in tail else ("-" + tail.rsplit("-", 1)[1] if "-" in tail[1:] else "+00:00")
            return datetime.fromisoformat(head + "." + (frac or "0") + zone)
        except (ValueError, IndexError):
            return None


def load_leases(root: Path):
    leases = []
    base_dir = root / LEASE_DIR
    if not base_dir.is_dir():
        return leases
    for child in sorted(base_dir.iterdir()):
        if not child.is_dir() or child.name == "waiters":
            continue
        lease_file = child / "lease.json"
        if not lease_file.is_file():
            continue
        try:
            data = json.loads(lease_file.read_text(encoding="utf-8", errors="replace"))
        except (OSError, json.JSONDecodeError):
            leases.append({"topic": child.name, "status": "corrupt", "targets": []})
            continue
        leases.append({
            "topic": data.get("topic", child.name),
            "leaseId": data.get("leaseId"),
            "ownerId": data.get("ownerId"),
            "role": data.get("role"),
            "expiresAtUtc": data.get("expiresAtUtc") or data.get("expiresAt"),
            "targets": [norm(t) for t in (data.get("targetPaths") or [])],
        })
    return leases


def find_main_journal(root: Path):
    base_dir = root / JOURNAL_ROOT
    found = []
    if base_dir.is_dir():
        for child in sorted(base_dir.iterdir()):
            if not child.is_dir() or not child.name.startswith(MAIN_TASK_PREFIX):
                continue
            if child.name.startswith(ASSIST_TASK_PREFIX):
                continue
            journal = child / "journal.json"
            row = {"taskId": child.name, "journal": "absent"}
            if journal.is_file():
                try:
                    data = json.loads(journal.read_text(encoding="utf-8", errors="replace"))
                    row.update({
                        "journal": "present",
                        "agent": data.get("agent"),
                        "status": data.get("status"),
                        "events": len(data.get("events") or []),
                        "updatedAtUtc": data.get("updatedAtUtc"),
                    })
                except (OSError, json.JSONDecodeError):
                    row["journal"] = "unreadable"
            found.append(row)
    return found


def cmd_scope(root: Path):
    now = datetime.now(timezone.utc)
    leases = load_leases(root)
    budget_folded = {norm(p) for p in BUDGET}
    protected_folded = {norm(p) for p in PROTECTED}
    rows = []
    protected_hits = []
    for lease in leases:
        expiry = parse_ts(lease.get("expiresAtUtc"))
        expired = expiry is not None and expiry < now
        overlap = sorted(budget_folded & set(lease["targets"]))
        protected = sorted(protected_folded & set(lease["targets"]))
        if protected:
            protected_hits.append({"topic": lease["topic"], "paths": protected})
        rows.append({
            "topic": lease["topic"], "ownerId": lease.get("ownerId"),
            "status": lease.get("status"), "expired": expired,
            "expiresAtUtc": lease.get("expiresAtUtc"),
            "budgetOverlap": overlap, "protectedOverlap": protected,
            "targetCount": len(lease["targets"]),
        })
    files = []
    for rel in WATCH:
        path = engine.under_root(root, rel)
        try:
            stat = path.stat()
            files.append({"path": rel, "present": True,
                          "mtimeUtc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
                          "bytes": stat.st_size})
        except OSError:
            files.append({"path": rel, "present": False})
    status, code = ("PROTECTED_LEASED", 4) if protected_hits else ("OK", 0)
    report = base("scope", status)
    report["leases"] = rows
    report["mainJournals"] = find_main_journal(root)
    report["files"] = files
    report["protectedHits"] = protected_hits
    report["note"] = (
        "Lease overlap on budget files is expected while the main session works. "
        "PROTECTED_LEASED means a lease covers a forbidden file — review before trusting A6."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
    path = engine.under_root(root, rel)
    data = engine.load_json(path)
    items = data.get("hypotheses") if isinstance(data, dict) else None
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    report = base("hypothesis", "OPEN" if any(h.get("status") == "open" for h in items) else "CLOSED")
    report["hypotheses"] = items
    report["note"] = "가설 카드 — 확인 전까지 어떤 것도 증거로 쓰지 않는다."
    return report, 0


def cmd_hold(root: Path, rel: str):
    path = engine.under_root(root, rel)
    data = engine.load_json(path)
    items = data.get("holds") if isinstance(data, dict) else None
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    open_items = [h for h in items if h.get("status") in ("open", "template")]
    report = base("hold", "OPEN" if open_items else "CLEAR")
    report["holds"] = items
    report["openIds"] = [h.get("id") for h in open_items]
    report["note"] = "OPEN/template 항목은 DONE 주장을 막는다. 실제 해소만 기록한다."
    return report, 0


def cmd_verify_plan():
    report = base("verify-plan", "PLAN_ONLY")
    report["steps"] = VERIFY_PLAN
    report["acceptanceMap"] = {
        "A1": "S1 결론(av-preset 무연결 + 주입 지점)이 ledger에 file:line으로 존재",
        "A2": "설정 저장→재읽기 왕복 + 옛 JSON 호환 테스트 통과",
        "A3": "INTERVIEW 주입 + GENERAL·메인 채팅 불변 테스트 통과",
        "A4": "폰 설정창 프리셋·입력 칸 표시 + 저장/새로고침 유지",
        "A5": "재기동 후 --ready=READY + 실제 포커스 1건의 focus.instruction.preset trace",
        "A6": "보호 파일(NovaFocusState.java, chat.js) 이 세션 쓰기 0회",
        "A7": "사용자 프로필 설정 원복",
    }
    report["note"] = "이 세션에서는 절대 실행하지 않는다 — 주 세션 최종 보고 뒤 순서대로 돌린다."
    return report, 0


def cmd_snapshot(root: Path, out_rel: str):
    files = {}
    for rel in WATCH:
        path = engine.under_root(root, rel)
        digest = sha256_of(path)
        try:
            stat = path.stat()
            files[rel] = {"present": True, "sha256": digest, "bytes": stat.st_size,
                          "mtimeUtc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()}
        except OSError:
            files[rel] = {"present": False, "sha256": None}
    tests_dir = root / "src/test/java/com/example/lms/assist"
    tests = sorted(p.name for p in tests_dir.glob("NovaFocus*Test.java")) if tests_dir.is_dir() else []
    payload = {"schemaVersion": SCHEMA, "takenAtUtc": iso_now(), "files": files,
               "novaTestsPresent": tests, "note": "mid-edit baseline — 주 세션 작업 도중의 고정값이다."}
    dest = engine.under_root(root, out_rel)
    engine.reject_name(dest.name)
    if dest.is_symlink():
        raise engine.AssistError("symlink-refused")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report = base("snapshot", "TAKEN")
    report["out"] = out_rel
    report["tracked"] = len(files)
    report["novaTestsPresent"] = len(tests)
    return report, 0


def cmd_diff_review(root: Path, baseline_rel: str):
    baseline_path = engine.under_root(root, baseline_rel)
    baseline = engine.load_json(baseline_path)
    recorded = baseline.get("files") if isinstance(baseline, dict) else None
    if not isinstance(recorded, dict):
        raise engine.AssistError("spec-shape")
    protected_folded = {norm(p) for p in PROTECTED}
    changed, protected_changed, missing = [], [], []
    for rel, before in recorded.items():
        path = engine.under_root(root, rel)
        digest = sha256_of(path)
        now = {"present": digest is not None, "sha256": digest}
        if not before.get("present") and digest is None:
            verdict = "UNCHANGED_ABSENT"
        elif before.get("present") and digest is None:
            verdict = "ABSENT_NOW"
            missing.append(rel)
        elif digest == before.get("sha256"):
            verdict = "UNCHANGED"
        else:
            verdict = "CHANGED"
            changed.append(rel)
            if norm(rel) in protected_folded:
                protected_changed.append(rel)
        now["verdict"] = verdict
        now["baselineSha256"] = before.get("sha256")
    nova_tests_now = sorted(p.name for p in (root / "src/test/java/com/example/lms/assist").glob("NovaFocus*Test.java")) \
        if (root / "src/test/java/com/example/lms/assist").is_dir() else []
    new_tests = [name for name in nova_tests_now if name not in (baseline.get("novaTestsPresent") or [])]
    if protected_changed:
        status, code = "PROTECTED_TOUCHED", 4
    elif missing:
        status, code = "FILE_MISSING", 4
    else:
        status, code = ("DRIFT_SEEN", 0) if changed else ("CLEAN", 0)
    report = base("diff-review", status)
    report["changed"] = changed
    report["protectedChanged"] = protected_changed
    report["missing"] = missing
    report["newTestFiles"] = new_tests
    report["baselineTakenAtUtc"] = baseline.get("takenAtUtc")
    report["note"] = "CHANGED는 baseline 이후 바이트 변화 — 주 세션 정상 진행도 CHANGED로 보인다. 보호 파일의 CHANGED만 위반 신호다."
    return report, code


def read_diff(path: Path) -> str:
    engine.reject_name(path.name)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("diff-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


def cmd_selftest(root: Path):
    engine.SCHEMA = SCHEMA
    checks = []
    spec = engine.load_spec(root / DEFAULT_SPEC)
    checks.append({"id": "spec-load", "ok": isinstance(spec, dict)})
    pin, pin_code = engine.cmd_pin(root, spec)
    checks.append({"id": "pin-shape", "ok": "files" in pin and pin["schemaVersion"] == SCHEMA})
    checks.append({"id": "pin-status", "ok": pin["status"] in ("FRESH", "CONTRACT_GAP", "ANCHOR_STALE")})
    checks.append({"id": "pin-exit", "ok": pin_code in (0, 3, 4)})
    cover, cover_code = engine.cmd_cover(root, spec)
    checks.append({"id": "cover-shape", "ok": "files" in cover})
    checks.append({"id": "cover-status", "ok": cover["status"] in ("COVERED", "SEARCH_GAP", "MISSING_FILE")})
    checks.append({"id": "cover-exit", "ok": cover_code in (0, 3, 4)})
    scope, scope_code = cmd_scope(root)
    checks.append({"id": "scope-shape", "ok": "leases" in scope and "files" in scope})
    checks.append({"id": "scope-exit", "ok": scope_code in (0, 4)})
    plan, _ = cmd_verify_plan()
    checks.append({"id": "verify-plan", "ok": len(plan["steps"]) == 8})
    ok = all(c["ok"] for c in checks)
    report = base("selftest", "PASS" if ok else "FAIL")
    report["checks"] = checks
    report["note"] = "selftest PASS는 도구 수급만 증명한다. pin/cover의 CONTRACT_GAP·SEARCH_GAP은 패치 진행 중 정상이다."
    return report, 0 if ok else 3


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    cmd = args[0]
    if cmd in ("pin", "cover", "diff-forbid"):
        engine.SCHEMA = SCHEMA
        if "--spec" not in args:
            args = [cmd, "--spec", DEFAULT_SPEC, *args[1:]]
        return engine.main(args)
    parser = argparse.ArgumentParser(description="nova-answer-prompt assist (read-only)")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    hold = add_root(sub.add_parser("hold"))
    hold.add_argument("--file", default=HOLD_REL)
    add_root(sub.add_parser("verify-plan"))
    snap = add_root(sub.add_parser("snapshot"))
    snap.add_argument("--out", default=BASELINE_REL)
    review = add_root(sub.add_parser("diff-review"))
    review.add_argument("--baseline", default=BASELINE_REL)
    add_root(sub.add_parser("selftest"))
    parsed = parser.parse_args(args)
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "hold":
            report, code = cmd_hold(root, parsed.file)
        elif parsed.cmd == "verify-plan":
            report, code = cmd_verify_plan()
        elif parsed.cmd == "snapshot":
            report, code = cmd_snapshot(root, parsed.out)
        elif parsed.cmd == "diff-review":
            report, code = cmd_diff_review(root, parsed.baseline)
        elif parsed.cmd == "selftest":
            report, code = cmd_selftest(root)
        else:
            emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
            return 2
        emit(report)
        return code
    except engine.AssistError as exc:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": exc.reason, "productPass": False})
        return 2


if __name__ == "__main__":
    sys.exit(main())
