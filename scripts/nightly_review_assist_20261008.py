"""Read-only assist for Codex brief CODEX-SESSIONS-ANTIGRAVITY-NIGHTLY-20261008.

Scanner: scripts/pair_brief_assist.py (pin/cover/diff-forbid via spec.json).
Extra commands: scope, guard, sched, hold, env-presence, verify-plan,
agy-mock, selftest.
Stdlib only. No network, Gradle, server, scheduler, agy, or product writes.
Exit 0 is a clean scan. It is not a product PASS and not proof the nightly
batch works.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.nightly-review-assist.v1"
PACK = "var/codex-assist-nightly-20261008"
DEFAULT_SPEC = PACK + "/spec.json"
HOLD_REL = PACK + "/hold.json"
FIXTURES = PACK + "/fixtures"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"

# Files the Codex session codex-nightly-review-cf2492b4 owns per targets.json.
OWNED = tuple(p.casefold() for p in (
    "scripts/codex_nightly_review.py",
    "scripts/test_codex_nightly_review.py",
    "configs/codex-nightly-review.example.json",
    "docs/agents-rules/DEMO1-CODEX-NIGHTLY-REVIEW.md",
))

# Anchors the brief cites plus the reuse candidates it missed.
WATCH = OWNED + tuple(p.casefold() for p in (
    "scripts/chat_session_debug_export.py",
    "scripts/quarantine_failure_episode_extract.py",
    "scripts/session_context_export_json_zip_assist.py",
    "scripts/checkpoint_doctor.py",
    "scripts/codex_session_friction.py",
    "scripts/agent_scope_lease.py",
    "scripts/work_journal.py",
    "AGENTS.md",
))

# Paths the batch must never write (reads of ~/.codex sessions are the job;
# writes there or to agy global settings are out of scope).
PROTECTED_PREFIX = tuple(p.casefold() for p in (
    "main/", "build.gradle", "settings.gradle", "gradle.properties",
    "configs/api-routing.yaml",
))
GLOBAL_CONFIG_RE = re.compile(
    r"(?i)(\.antigravity|\.codex/|(^|/)agy[^/]*(config|settings)|"
    r"(^|/)(config\.toml|settings\.json)$)"
)
BATCH_PATH_RE = re.compile(r"(?i)nightly")

SECRET_LITERAL = re.compile(
    r"(?i)(secret|api[-_]?key|credential|password)\w*\s*[:=]\s*[\"']?"
    r"(?!synthetic|changeme|test|dummy|example|redacted|\$\{|\{)[A-Za-z0-9+/=_\-]{16,}"
)
DANGEROUS_PERMS = re.compile(r"--dangerously-skip-permissions|skipPermissions")
CODEX_FALLBACK = re.compile(
    r"(?i)(api\.openai\.com|\bcodex(\.exe|\.cmd|\.ps1)?\s+(exec|chat|resume|login))"
)
GUI_AUTOMATION = re.compile(
    r"(?i)(pyautogui|sendkeys|uiautomation|setforegroundwindow|mouse_event)"
)
AGY_GLOBAL_KEY = re.compile(
    r"(?i)(useG1Credits|aiCreditOverage|creditOverage|modelAuto\w*|autoModel\w*)"
)
CODEX_WRITE = re.compile(
    r"(?i)(Set-Content|Out-File|Add-Content|SetContent|>\s*[\"']?[^\"']*\.(codex|antigravity))"
    r".*(\.codex|\.antigravity)"
)
MODEL_FLAG = re.compile(r"--model\b")
SCHED_WRITE = re.compile(
    r"(?i)(Register-ScheduledTask|New-ScheduledTask|Set-ScheduledTask|schtasks\.exe|"
    r"New-ScheduledTaskTrigger)"
)

# sched required groups: every regex in a group must hit somewhere in the file.
SCHED_REQUIRED = (
    ("logon-trigger", (r"(?i)(LogonTrigger|-AtLogOn|MSFT_TaskLogonTrigger|\"logon\"|'logon')",)),
    ("catchup-delay-30m", (
        r"(?i)(PT30M|00:30:00|delay\w*[\"'\s:=]+[\"']?30\b|\b30\s*(min|minutes?)\b)",
        r"(?i)(delay|catchup|catch-up|지연)",
    )),
    ("start-when-available", (r"(?i)StartWhenAvailable",)),
    ("single-instance-ignorenew", (r"(?i)IgnoreNew",)),
)
SCHED_FORBIDDEN = (
    ("boot-trigger", r"(?i)(BootTrigger|-AtStartup\b)"),
    ("highest-or-system", r"(?i)(RunLevel\s*[\"']?Highest|NT AUTHORITY\\SYSTEM|-User\s+[\"']?SYSTEM)"),
    ("stored-password", r"(?i)(-Password\b|LogonType\w*Password)"),
    ("dangerous-skip", r"--dangerously-skip-permissions"),
)
SCHED_REVIEW = (
    ("wake-to-run", r"(?i)WakeToRun"),
    ("agy-double-schedule", r"(?i)(agy|antigravity).{0,40}(schedule|sidecar)"),
    ("system-account-ambiguous", r"(?i)\bSYSTEM\b"),
)

AGY_ENV_NAMES = (
    "AGY_API_KEY",
    "ANTIGRAVITY_API_KEY",
    "GOOGLE_API_KEY",
    "GEMINI_API_KEY",
)

MOCK_MODES = ("ok", "exit0_fail", "wrong_schema", "auth_required", "quota", "permission_denied")


def base(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "schedulerTouched": False,
        "agyCalled": False,
    }


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def norm(path: str) -> str:
    return str(path).replace("\\", "/").lstrip("./").casefold()


def path_hit(entry: str, hot: str) -> bool:
    ent = norm(entry)
    if ent == hot or ent.endswith("/" + hot):
        return True
    last = ent.rsplit("/", 1)[-1]
    if "." not in last and hot.startswith(ent + "/"):
        return True
    return False


def read_json(path: Path):
    try:
        if path.is_symlink():
            raise engine.AssistError("symlink-refused")
        return json.loads(path.read_text(encoding="utf-8"))
    except engine.AssistError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def cmd_scope(root: Path):
    overlaps = []
    journals = []
    foreign = []
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    if lock_root.is_dir() and not lock_root.is_symlink():
        for child in sorted(lock_root.iterdir()):
            if not child.is_dir() or child.name in ("waiters", "source-edit-quarantine") \
                    or child.is_symlink():
                continue
            lease_path = child / "lease.json"
            if not lease_path.is_file() or lease_path.is_symlink():
                continue
            data = read_json(lease_path)
            if not isinstance(data, dict):
                raise engine.AssistError("json-unreadable")
            raw_paths = list(data.get("targetPaths") or []) + \
                list(data.get("reservePaths") or [])
            hits = sorted({hot for item in raw_paths if isinstance(item, str)
                           for hot in WATCH if path_hit(item, hot)})
            foreign_hits = sorted({hot for item in raw_paths if isinstance(item, str)
                                   for hot in PROTECTED_PREFIX if path_hit(item, hot)})
            row = {
                "kind": "lease",
                "topic": data.get("topic"),
                "ownerId": data.get("ownerId"),
                "expiresAtUtc": data.get("expiresAtUtc"),
            }
            if hits:
                overlaps.append({**row, "paths": hits})
            if foreign_hits:
                foreign.append({**row, "paths": foreign_hits})
    journal_root = root / JOURNAL_ROOT
    if journal_root.is_dir() and not journal_root.is_symlink():
        for journal_path in sorted(journal_root.glob("*/journal.json")):
            if journal_path.is_symlink():
                continue
            try:
                if journal_path.stat().st_size > 2_000_000:
                    continue
                data = json.loads(journal_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError):
                continue
            if not isinstance(data, dict) or data.get("status") != "in_progress":
                continue
            scope = data.get("plannedScope")
            if not isinstance(scope, list) or not scope:
                continue
            hits = sorted({hot for item in scope if isinstance(item, str)
                           for hot in WATCH if path_hit(item, hot)})
            foreign_hits = sorted({hot for item in scope if isinstance(item, str)
                                   for hot in PROTECTED_PREFIX if path_hit(item, hot)})
            row = {"kind": "journal", "taskId": data.get("taskId"),
                   "agent": data.get("agent")}
            if hits:
                journals.append({**row, "paths": hits})
            if foreign_hits:
                foreign.append({**row, "paths": foreign_hits})
    blocked = sorted({p for row in overlaps + journals for p in row["paths"]})
    free = [item for item in WATCH if item not in blocked]
    status, code = ("OVERLAP", 7) if overlaps or journals else ("CLEAR", 0)
    report = base("scope", status)
    report["leaseOverlaps"] = overlaps
    report["journalOverlaps"] = journals
    report["protectedHits"] = foreign
    report["blockedPaths"] = blocked
    report["freePaths"] = free
    report["note"] = (
        "OVERLAP lists leases + in_progress plannedScope hits on brief paths. "
        "OVERLAP means wait unless this session owns it; never force-release. "
        "This command does not reclaim."
    )
    return report, code


def classify_path(folded: str):
    base_name = folded.rsplit("/", 1)[-1]
    if base_name.startswith(".env") or "/.secrets/" in folded \
            or base_name == "providers.json":
        return "protected", "secrets-file"
    if GLOBAL_CONFIG_RE.search(folded):
        return "protected", "global-config-write"
    if folded.startswith(PROTECTED_PREFIX):
        return "protected", "product-or-routing"
    if folded == "agents.md":
        return "agents-md", "agents-md-bounded"
    if folded in OWNED:
        return "allowed", "brief-owned"
    if BATCH_PATH_RE.search(folded) and folded.startswith(
            ("scripts/", "configs/", "docs/", "var/", "data/", ".agents/")):
        return "batch-support", "nightly-named-new-file"
    if folded.startswith(("scripts/", "docs/", "data/", "var/", ".agents/",
                          ".windsurf/", "agent-prompts/", "__patch_drop__/",
                          "tools/")):
        return "support", "non-product"
    if folded.startswith(("main/", "src/")):
        return "extra-product", "product-outside-brief"
    return "other", "unclassified"


def cmd_guard(diff_text: str):
    """Path + added-line gate for the 'keep existing features' constraints."""
    hits = []

    def hit(rule, path, line_no):
        hits.append({"rule": rule, "path": path, "diffLine": line_no})

    agents_md_added = []
    counts = {}
    for index, path, text in engine.added_lines(diff_text):
        folded = norm(path) if path else ""
        label, detail = classify_path(folded) if folded else ("other", "no-path")
        counts[label] = counts.get(label, 0) + 1
        if SECRET_LITERAL.search(text):
            hit("secret-literal", path, index)
        if DANGEROUS_PERMS.search(text):
            hit("dangerous-perms", path, index)
        if CODEX_FALLBACK.search(text):
            hit("codex-or-openai-fallback", path, index)
        if GUI_AUTOMATION.search(text):
            hit("gui-automation", path, index)
        if AGY_GLOBAL_KEY.search(text) and label != "allowed" \
                and not (label == "batch-support"):
            hit("agy-global-key-write", path, index)
        if CODEX_WRITE.search(text):
            hit("codex-profile-write", path, index)
        if MODEL_FLAG.search(text) and label not in ("allowed", "batch-support"):
            hit("model-flag-outside-batch", path, index)
        if SCHED_WRITE.search(text) and label not in ("allowed", "batch-support"):
            hit("scheduler-write-outside-batch", path, index)
        if label == "protected":
            hit("protected-path:" + detail, path, index)
        if label == "agents-md":
            agents_md_added.append(text)

    agents_md_violation = None
    if agents_md_added:
        if len(agents_md_added) > 5:
            agents_md_violation = "agents-md-lines>5"
        else:
            scoped = re.compile(r"(?i)(야간|배치|nightly|batch)")
            trigger = re.compile(r"(?i)(schedul|agy|antigravity|codex|report)")
            if any(trigger.search(t) for t in agents_md_added) \
                    and not any(scoped.search(t) for t in agents_md_added):
                agents_md_violation = "agents-md-block-missing-nightly-scope"
        if agents_md_violation:
            hits.append({"rule": agents_md_violation, "path": "AGENTS.md",
                         "diffLine": None})

    order = (
        ("secret-literal", "SECRET_LITERAL", 3),
        ("protected-path", "PROTECTED_HIT", 3),
        ("agy-global-key-write", "GLOBAL_SETTINGS_WRITE", 3),
        ("codex-profile-write", "GLOBAL_SETTINGS_WRITE", 3),
        ("model-flag-outside-batch", "MODEL_OUTSIDE_BATCH", 3),
        ("codex-or-openai-fallback", "CODEX_FALLBACK", 3),
        ("dangerous-perms", "DANGEROUS_PERMS", 3),
        ("gui-automation", "GUI_AUTOMATION", 3),
        ("scheduler-write-outside-batch", "SCHED_OUTSIDE_BATCH", 3),
        ("agents-md-", "AGENTS_MD_VIOLATION", 3),
    )
    status, code = None, None
    for prefix, label, exit_code in order:
        if any(h["rule"].startswith(prefix) for h in hits):
            status, code = label, exit_code
            break
    if status is None:
        if counts.get("extra-product"):
            status, code = "SCOPE_EXPAND", 4
        elif counts.get("batch-support"):
            status, code = "SCOPE_EXPAND", 4
        elif counts.get("allowed") or counts.get("agents-md"):
            status, code = "IN_SCOPE", 0
        elif counts.get("support") or counts.get("other"):
            status, code = "ASSIST_ONLY", 0
        else:
            status, code = "EMPTY", 2
    report = base("guard", status)
    report["counts"] = counts
    report["hits"] = hits
    report["agentsMdAddedLines"] = len(agents_md_added)
    report["note"] = (
        "Gate for the user's keep-existing-features line: --model only inside "
        "batch files, agy global settings/model-tracking/G1-credit/overage keys "
        "read-only, isolation only in nightly-named paths, AGENTS.md block <=5 "
        "added lines and the block must carry a nightly/batch scope word "
        "somewhere. Hits are review "
        "signals; matched source text is not copied. SCOPE_EXPAND on a "
        "nightly-named new file means 'fine, but record it in the journal' "
        "(targets.json listed 4 files)."
    )
    return report, code


def cmd_sched(root: Path, rel: str):
    path = engine.under_root(root, rel)
    if not path.is_file():
        raise engine.AssistError("sched-file-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    text = path.read_text(encoding="utf-8", errors="replace")
    required = []
    gap = False
    for rule_id, regexes in SCHED_REQUIRED:
        missing = [rx for rx in regexes if not re.search(rx, text)]
        required.append({"id": rule_id,
                         "status": "FOUND" if not missing else "MISSING"})
        if missing:
            gap = True
    forbidden = []
    for rule_id, rx in SCHED_FORBIDDEN:
        forbidden.append({"id": rule_id,
                          "status": "HIT" if re.search(rx, text) else "CLEAR"})
    review = []
    for rule_id, rx in SCHED_REVIEW:
        review.append({"id": rule_id,
                       "status": "HIT" if re.search(rx, text) else "CLEAR"})
    if any(r["status"] == "HIT" for r in forbidden):
        status, code = "SCHED_HIT", 3
    elif gap:
        status, code = "SCHED_GAP", 4
    elif any(r["status"] == "HIT" for r in review):
        status, code = "SCHED_REVIEW", 0
    else:
        status, code = "SCHED_OK", 0
    report = base("sched", status)
    report["file"] = rel
    report["required"] = required
    report["forbidden"] = forbidden
    report["review"] = review
    report["note"] = (
        "Static scan of a registration script/XML/config. Required: logon "
        "trigger, 30-minute catch-up delay (PT30M / 30 min), "
        "StartWhenAvailable, IgnoreNew single-instance. Forbidden: "
        "BootTrigger/-AtStartup, RunLevel Highest, SYSTEM account, stored "
        "password, --dangerously-skip-permissions. REVIEW items (WakeToRun, "
        "agy sidecar double-schedule) need evidence, not auto-fail. "
        "SCHED_OK is not proof the task was registered or runs."
    )
    return report, code


def cmd_hold(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    allowed_status = {"OPEN", "RESOLVED", "WAIVED"}
    rows = []
    open_ids = []
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if not isinstance(item_id, str) or status not in allowed_status:
            raise engine.AssistError("spec-shape")
        rows.append({"id": item_id, "status": status, "note": item.get("note")})
        if status == "OPEN":
            open_ids.append(item_id)
    status, code = ("OPEN", 4) if open_ids else ("CLEAN", 0)
    report = base("hold", status)
    report["items"] = rows
    report["openIds"] = open_ids
    report["note"] = (
        "WP0 prerequisites. OPEN blocks scheduler registration / model "
        "execution, not local report generation. Resolutions need real "
        "evidence (path/version/quota check), not assumption."
    )
    return report, code


def cmd_verify_plan(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    contracts = data.get("contracts")
    if not isinstance(contracts, dict) or not contracts:
        raise engine.AssistError("spec-shape")
    plan = []
    for name, body in contracts.items():
        if not isinstance(body, dict):
            raise engine.AssistError("spec-shape")
        for step in body.get("acceptance") or []:
            if not isinstance(step, dict):
                raise engine.AssistError("spec-shape")
            plan.append({
                "contract": name,
                "id": step.get("id"),
                "command": step.get("command"),
                "note": step.get("note"),
                "external": bool(step.get("external")),
            })
    status, code = ("PLAN", 0) if plan else ("EMPTY", 2)
    report = base("verify-plan", status)
    report["steps"] = plan
    report["note"] = (
        "Ordered acceptance map A1-A10. external=true marks real agy calls or "
        "real scheduler registration - those stay gated on the WP0 HOLD items "
        "and the user's approval. This command does not execute anything."
    )
    return report, code


def cmd_env_presence(root: Path):
    keys = {name: {"processEnv": bool(os.environ.get(name))}
            for name in AGY_ENV_NAMES}
    home = Path.home()
    paths = {
        "codexSessionsDir": (home / ".codex" / "sessions").is_dir(),
        "antigravityDir": (home / ".antigravity").is_dir(),
        "dotenvFile": (root / ".env").is_file(),
    }
    agy = shutil.which("agy") or shutil.which("agy.exe")
    report = base("env-presence", "PRESENT_MAP")
    report["keys"] = keys
    report["paths"] = paths
    report["agyOnPath"] = bool(agy)
    report["note"] = (
        "Booleans only; values and file contents are never read. "
        "agyOnPath=true is not proof of a working login, model, or quota."
    )
    return report, 0


def cmd_agy_mock(mode: str, model: str):
    """Emit assumed-format agy --output-format stream-json NDJSON on stdout."""
    lines = []
    if mode == "wrong_schema":
        lines = [
            {"event": "started", "modelId": model},
            {"event": "done", "ok": True, "text": "synthetic mock answer"},
        ]
        code = 0
    elif mode == "auth_required":
        lines = [
            {"type": "init", "sessionId": "mock", "model": model},
            {"type": "error", "error": {"kind": "auth_required",
                                        "message": "login required"}},
        ]
        code = 3
    elif mode == "quota":
        lines = [
            {"type": "init", "sessionId": "mock", "model": model},
            {"type": "error", "error": {"kind": "quota_exceeded",
                                        "message": "quota exhausted"}},
        ]
        code = 4
    elif mode == "exit0_fail":
        lines = [
            {"type": "init", "sessionId": "mock", "model": model},
            {"type": "result", "status": "error",
             "error": {"kind": "tool_failed", "message": "synthetic failure"},
             "usage": {"inputTokens": 10, "outputTokens": 0}},
        ]
        code = 0
    elif mode == "permission_denied":
        lines = [
            {"type": "init", "sessionId": "mock", "model": model},
            {"type": "result", "status": "denied",
             "error": {"kind": "permission_denied"},
             "usage": {"inputTokens": 10, "outputTokens": 0}},
        ]
        code = 0
    else:
        lines = [
            {"type": "init", "sessionId": "mock", "model": model},
            {"type": "result", "status": "ok",
             "response": "synthetic mock analysis; not a real model output",
             "usage": {"inputTokens": 120, "outputTokens": 40}},
        ]
        code = 0
    for line in lines:
        sys.stdout.write(json.dumps(line, ensure_ascii=False) + "\n")
    return code


def cmd_selftest(root: Path):
    results = []

    def record(name, expected, actual, ok):
        results.append({"check": name, "expected": expected,
                        "actual": actual, "ok": bool(ok)})

    try:
        spec_path = engine.under_root(root, DEFAULT_SPEC)
        data = read_json(spec_path)
        ok = isinstance(data, dict) and data.get("schemaVersion") == SCHEMA
        record("spec-load", "ok", "ok" if ok else "bad", ok)
    except engine.AssistError as exc:
        record("spec-load", "ok", exc.reason, False)

    fixtures = root / FIXTURES
    diff_cases = (
        ("good.diff", ("IN_SCOPE", "SCOPE_EXPAND", "ASSIST_ONLY")),
        ("bad.diff", ("GLOBAL_SETTINGS_WRITE", "MODEL_OUTSIDE_BATCH",
                      "CODEX_FALLBACK", "PROTECTED_HIT")),
        ("agents_md_ok.diff", ("IN_SCOPE",)),
        ("agents_md_bad.diff", ("AGENTS_MD_VIOLATION",)),
    )
    for name, expected in diff_cases:
        path = fixtures / name
        if not path.is_file():
            record("guard:" + name, "|".join(expected), "fixture-missing", False)
            continue
        report, _code = cmd_guard(path.read_text(encoding="utf-8", errors="replace"))
        record("guard:" + name, "|".join(expected), report["status"],
               report["status"] in expected)

    sched_cases = (
        ("sched_ok.ps1", ("SCHED_OK", "SCHED_REVIEW")),
        ("sched_bad.ps1", ("SCHED_HIT",)),
    )
    for name, expected in sched_cases:
        rel = FIXTURES + "/" + name
        if not (fixtures / name).is_file():
            record("sched:" + name, "|".join(expected), "fixture-missing", False)
            continue
        report, _code = cmd_sched(root, rel)
        record("sched:" + name, "|".join(expected), report["status"],
               report["status"] in expected)

    for mode in MOCK_MODES:
        try:
            import io
            buf = io.StringIO()
            old = sys.stdout
            sys.stdout = buf
            try:
                code = cmd_agy_mock(mode, "mock-model")
            finally:
                sys.stdout = old
            parsed = [json.loads(l) for l in buf.getvalue().splitlines() if l.strip()]
            record("agy-mock:" + mode, "ndjson", f"{len(parsed)} lines exit {code}",
                   bool(parsed) and isinstance(code, int))
        except (json.JSONDecodeError, OSError) as exc:
            record("agy-mock:" + mode, "ndjson", type(exc).__name__, False)

    failed = [r for r in results if not r["ok"]]
    status, code = ("SELFTEST_FAIL", 3) if failed else ("SELFTEST_OK", 0)
    report = base("selftest", status)
    report["results"] = results
    report["note"] = (
        "SELFTEST_OK means the assist checks behave on synthetic fixtures. "
        "It says nothing about the Codex implementation."
    )
    return report, code


def read_diff(path: Path) -> str:
    engine.reject_name(path.name)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("diff-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage",
              "productPass": False})
        return 2
    cmd = args[0]
    if cmd in ("pin", "cover", "diff-forbid"):
        engine.SCHEMA = SCHEMA
        if "--spec" not in args:
            args = [cmd, "--spec", DEFAULT_SPEC, *args[1:]]
        return engine.main(args)
    parser = argparse.ArgumentParser(
        description="nightly-review assist (read-only)")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    gate = add_root(sub.add_parser("guard"))
    gate.add_argument("--diff", required=True)
    sched = add_root(sub.add_parser("sched"))
    sched.add_argument("--file", required=True)
    hold = add_root(sub.add_parser("hold"))
    hold.add_argument("--file", default=HOLD_REL)
    plan = add_root(sub.add_parser("verify-plan"))
    plan.add_argument("--spec", default=DEFAULT_SPEC)
    add_root(sub.add_parser("env-presence"))
    mock = sub.add_parser("agy-mock")
    mock.add_argument("--mode", default="ok", choices=MOCK_MODES)
    mock.add_argument("--model", default="mock-model")
    add_root(sub.add_parser("selftest"))
    parsed = parser.parse_args(args)
    if not parsed.cmd:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage",
              "productPass": False})
        return 2
    try:
        if parsed.cmd == "agy-mock":
            return cmd_agy_mock(parsed.mode, parsed.model)
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "guard":
            report, code = cmd_guard(read_diff(Path(parsed.diff)))
        elif parsed.cmd == "sched":
            report, code = cmd_sched(root, parsed.file)
        elif parsed.cmd == "hold":
            report, code = cmd_hold(root, parsed.file)
        elif parsed.cmd == "verify-plan":
            report, code = cmd_verify_plan(root, parsed.spec)
        elif parsed.cmd == "env-presence":
            report, code = cmd_env_presence(root)
        else:
            report, code = cmd_selftest(root)
        emit(report)
        return code
    except engine.AssistError as exc:
        emit({"schemaVersion": SCHEMA, "status": "error",
              "reason": exc.reason, "productPass": False})
        return 2


if __name__ == "__main__":
    sys.exit(main())
