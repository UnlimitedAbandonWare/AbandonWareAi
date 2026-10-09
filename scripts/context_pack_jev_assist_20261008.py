"""Read-only assist for Codex brief CODEX-CONTEXT-PACK-JEV-20261008.

Codex session codex-context-pack-a71f89d1 owns:
  scripts/gptpro_pack_context.py
  scripts/test_gptpro_context_selection.py
  docs/agent-tooling/context-selection.md
This lane adds read-only scanners/validators/mocks only. Stdlib only. No
network, Gradle, server, hook install, scheduler, or product writes.
Exit 0 is a clean scan. It is not a product PASS and not proof the packer
or any JEV wiring works.

Commands: pin, cover, diff-forbid (via scripts/pair_brief_assist.py),
scope, guard, hold, verify-plan, env-presence, jev-mock, pack-lint,
token-estimate, selftest.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.context-pack-jev-assist.v1"
PACK = "var/codex-assist-context-pack-jev-20261008"
DEFAULT_SPEC = PACK + "/spec.json"
HOLD_REL = PACK + "/hold.json"
FIXTURES = PACK + "/fixtures"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"

# Files the Codex session owns per its journal/scope-claim (2026-10-08).
OWNED = tuple(p.casefold() for p in (
    "scripts/gptpro_pack_context.py",
    "scripts/test_gptpro_context_selection.py",
    "docs/agent-tooling/context-selection.md",
))

# Anchors the brief reuses but must NOT modify (read/call targets only).
ANCHORS = tuple(p.casefold() for p in (
    "scripts/context_compression_reuse_assist.py",
    "scripts/jev_campaign_score.py",
    "scripts/apikit/providers/jev.py",
    "scripts/jev_api_smoke.py",
    "scripts/jev_gateway_smoke.mjs",
    "scripts/checkpoint_doctor.py",
    "main/java/com/example/lms/assist/JevGatewayClient.java",
    ".agents/skills/demo1-session-state-checkpoint/SKILL.md",
))

# This assist lane's own files - if they appear in a Codex diff it is a
# review signal, not an error.
ASSIST_LANE_PREFIX = tuple(p.casefold() for p in (
    "scripts/context_pack_jev_assist_20261008.py",
    "var/codex-assist-context-pack-jev-20261008/",
    ".agents/skills/demo1-context-pack-jev-assist-20261008/",
))

PROTECTED_PREFIX = tuple(p.casefold() for p in (
    "main/", "build.gradle", "settings.gradle", "gradle.properties",
    "configs/api-routing.yaml",
))

WATCH = OWNED + ANCHORS + tuple(p.casefold() for p in (
    "AGENTS.md",
    ".codex/",
))

SECRET_LITERAL = re.compile(
    r"(?i)(secret|api[-_]?key|credential)\w*\s*[:=]\s*[\"']?"
    r"(?!synthetic|changeme|test|dummy|example|redacted|\$\{|\{)[A-Za-z0-9+/=_\-]{16,}"
)
EXTERNAL_SEND = re.compile(
    r"(?i)(requests\.(get|post|put)|urllib\.request|urlopen|httpx\.|"
    r"http\.client|Invoke-WebRequest|curl\.exe|fetch\s*\()"
)
BUILD_ALL_CALL = re.compile(r"\bbuild_all\s*\(")
DELETE_ORIGINAL = re.compile(
    r"(?i)(shutil\.rmtree|os\.(remove|unlink|rmdir)|Remove-Item\b[^\n]*-Recurse|"
    r"send2trash|del\s+/[sq])"
)
GIT_MUTATION = re.compile(
    r"(?i)(git\s+(push|commit|add\s+(-A|\.))|--no-verify|gh\s+pr\s+create)"
)
SCHED_WRITE = re.compile(
    r"(?i)(schtasks|Register-ScheduledTask|New-ScheduledTask|crontab)"
)
HOOK_WIRING = re.compile(
    r"(?i)(PreCompact|PostCompact|SessionStart|additionalContext)"
)
SCORE_TRUTH = re.compile(
    r"(?i)(calibrated|truth.?probab|score\s+is\s+probab|진실.?확률)"
)
KEY_PRINT = re.compile(
    r"(?i)(print|logger?\.|logging\.|echo)\s*\(?[^\n]{0,60}"
    r"(api[_-]?key|secret|credential|bearer)\s*[%+}]"
)

AGENTS_SCOPE_WORD = re.compile(
    r"(?i)(context|pack|select|contract|계약|선별|컨텍스트|맥락)"
)

MOCK_MODES = ("ok", "empty_choices", "bad_schema",
              "auth_401", "forbidden_403", "rate_429", "timeout_sim")
MOCK_EXIT = {"ok": 0, "empty_choices": 0, "bad_schema": 0,
             "auth_401": 3, "forbidden_403": 3, "rate_429": 4,
             "timeout_sim": 5}


def base(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "hookInstalled": False,
        "jevCalled": False,
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


def load_card(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    return data


def cmd_scope(root: Path):
    overlaps = []
    journals = []
    foreign = []
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    if lock_root.is_dir() and not lock_root.is_symlink():
        for child in sorted(lock_root.iterdir()):
            if not child.is_dir() or child.name == "waiters" or child.is_symlink():
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
                                   for hot in PROTECTED_PREFIX + ANCHORS
                                   if path_hit(item, hot)})
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
                                   for hot in PROTECTED_PREFIX + ANCHORS
                                   if path_hit(item, hot)})
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
        "The codex-context-pack-a71f89d1 session legitimately holds the 3 "
        "OWNED paths; OVERLAP on those means 'that is Codex', not a defect. "
        "Hits on ANCHORS/PROTECTED_PREFIX by any session deserve a review. "
        "Never force-release. This command does not reclaim."
    )
    return report, code


def classify_path(folded: str):
    base_name = folded.rsplit("/", 1)[-1]
    if base_name.startswith(".env") or "/.secrets/" in folded \
            or base_name == "providers.json":
        return "protected", "secrets-file"
    if folded.startswith(ASSIST_LANE_PREFIX):
        return "assist-lane", "devin-assist-file"
    if folded in ANCHORS:
        return "protected", "anchor-immutable"
    if folded.startswith(PROTECTED_PREFIX):
        return "protected", "product-or-routing"
    if folded == "agents.md":
        return "agents-md", "agents-md-bounded"
    if folded == ".codex/" or folded.startswith(".codex") \
            or base_name in ("config.toml", "settings.json") \
            or "/hooks/" in folded:
        return "hook-target", "hook-or-agent-config"
    if folded in OWNED:
        return "allowed", "brief-owned"
    if folded.startswith("src/test/") or folded.startswith("src/chatuitest/") \
            or base_name.startswith("test_"):
        return "test", "test-file"
    if folded.startswith(("scripts/", "docs/", "data/", "var/", ".agents/",
                          ".windsurf/", "agent-prompts/", "__patch_drop__/",
                          "tools/")):
        return "support", "non-product"
    if folded.startswith(("main/", "src/main/")):
        return "extra-product", "product-outside-brief"
    return "other", "unclassified"


def cmd_guard(diff_text: str):
    """Path + added-line gate for the brief's hard 'do not' list."""
    hits = []
    review = []

    def hit(rule, path, line_no):
        hits.append({"rule": rule, "path": path, "diffLine": line_no})

    agents_md_added = []
    counts = {}
    for index, path, text in engine.added_lines(diff_text):
        folded = norm(path) if path else ""
        label, detail = classify_path(folded) if folded else ("other", "no-path")
        counts[label] = counts.get(label, 0) + 1
        is_test = label in ("test", "assist-lane") or folded.endswith(
            "test_gptpro_context_selection.py")
        if SECRET_LITERAL.search(text):
            hit("secret-literal", path, index)
        if DELETE_ORIGINAL.search(text) and not is_test:
            hit("delete-original", path, index)
        if GIT_MUTATION.search(text):
            hit("git-mutation", path, index)
        if SCHED_WRITE.search(text):
            hit("scheduler-write", path, index)
        if SCORE_TRUTH.search(text):
            hit("score-as-truth", path, index)
        if EXTERNAL_SEND.search(text) and label not in ("assist-lane",):
            hit("external-send", path, index)
        if BUILD_ALL_CALL.search(text) \
                and folded != "scripts/gptpro_pack_context.py" \
                and not folded.endswith("test_gptpro_context_selection.py"):
            hit("build_all-invocation", path, index)
        if HOOK_WIRING.search(text) and label in ("hook-target",):
            hit("hook-wiring", path, index)
        elif HOOK_WIRING.search(text) and label not in ("allowed", "assist-lane",
                                                      "other"):
            hit("hook-wiring", path, index)
        if KEY_PRINT.search(text):
            review.append({"rule": "key-print-shape", "path": path,
                           "diffLine": index})
        if label == "protected":
            hit("protected-path:" + detail, path, index)
        if label == "hook-target":
            hit("hook-target-write", path, index)
        if label == "agents-md":
            agents_md_added.append(text)

    agents_md_violation = None
    if agents_md_added:
        if len(agents_md_added) > 5:
            agents_md_violation = "agents-md-lines>5"
        elif not any(AGENTS_SCOPE_WORD.search(t) for t in agents_md_added):
            agents_md_violation = "agents-md-block-missing-scope-word"
        if agents_md_violation:
            hits.append({"rule": agents_md_violation, "path": "AGENTS.md",
                         "diffLine": None})

    order = (
        ("secret-literal", "SECRET_LITERAL", 3),
        ("protected-path", "PROTECTED_HIT", 3),
        ("hook-target-write", "HOOK_WIRING", 3),
        ("hook-wiring", "HOOK_WIRING", 3),
        ("delete-original", "DELETE_ORIGINAL", 3),
        ("git-mutation", "GIT_MUTATION", 3),
        ("scheduler-write", "SCHEDULER_WRITE", 3),
        ("score-as-truth", "SCORE_AS_TRUTH", 3),
        ("external-send", "EXTERNAL_SEND", 4),
        ("build_all-invocation", "BUILD_ALL_CALL", 4),
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
        elif counts.get("allowed") or counts.get("agents-md"):
            status, code = "IN_SCOPE", 0
        elif counts.get("test"):
            status, code = "TEST_ONLY", 0
        elif counts.get("support") or counts.get("other") \
                or counts.get("assist-lane"):
            status, code = "ASSIST_ONLY", 0
        else:
            status, code = "EMPTY", 2
    report = base("guard", status)
    report["counts"] = counts
    report["hits"] = hits
    report["review"] = review
    report["agentsMdAddedLines"] = len(agents_md_added)
    report["note"] = (
        "Hard gate for the brief's forbidden list: secret literals, writes to "
        "immutable anchors / product files / secrets, original-deletion calls "
        "outside tests, git push/commit/add -A, scheduler registration, hook "
        "config writes (PreCompact/SessionStart/additionalContext in "
        "hook-target paths), JEV-score-as-truth wording, new network calls "
        "(JEV reuses the existing client only), wholesale build_all() calls "
        "outside the packer itself, AGENTS.md additions >5 lines or lacking a "
        "context/selection scope word. Hits are review signals; matched "
        "source text is not copied. 'review' items (key-print shapes) never "
        "fail but should be eyeballed."
    )
    return report, code


def cmd_hold(root: Path, rel: str):
    data = load_card(root, rel)
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
        "Brief HOLD conditions. OPEN blocks JEV wiring, hook install, and a "
        "DONE claim - not independent local-selector work. Resolve only with "
        "real evidence (contract doc, install check, lease query)."
    )
    return report, code


def cmd_verify_plan(root: Path, rel: str):
    spec = load_card(root, rel)
    contracts = spec.get("contracts")
    if not isinstance(contracts, dict) or not contracts:
        raise engine.AssistError("spec-shape")
    plan = []
    for name, body in contracts.items():
        if not isinstance(body, dict):
            raise engine.AssistError("spec-shape")
        steps = body.get("acceptance")
        if not isinstance(steps, list):
            continue
        for step in steps:
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
        "Ordered T1-T11 acceptance map from the brief. external=true steps "
        "need the user's explicit approval (JEV call, hook install); this "
        "command never executes. 'Run the packer' steps name the Codex-owned "
        "entrypoint once it exists - before that they are NOT_RUN."
    )
    return report, code


def cmd_env_presence(root: Path):
    names = ("AI_GATEWAY_API_KEY", "GROQ_API_KEY", "AWX_AGENT_SESSION")
    keys = {name: {"processEnv": bool(os.environ.get(name))} for name in names}
    home = Path.home()
    paths = {
        "codexDir": (home / ".codex").is_dir(),
        "codexHooksDir": (home / ".codex" / "hooks").is_dir(),
        "dotenvFile": (root / ".env").is_file(),
    }
    report = base("env-presence", "PRESENT_MAP")
    report["keys"] = keys
    report["paths"] = paths
    report["note"] = (
        "Booleans only; values and file contents are never read. A present "
        "key name is not proof of a working JEV contract, quota, or approval."
    )
    return report, 0


def cmd_jev_mock(mode: str):
    """Emit a synthetic /v1/evaluate-shaped envelope on stdout.

    The real JEV schema/host is UNVERIFIED for document scoring (brief WP4);
    every payload is marked synthetic+assumed so it can exercise a caller's
    failure handling but can never pass as a verified response.
    """
    if mode == "bad_schema":
        sys.stdout.write("not-json\x00 truncated payload (synthetic)\n")
        return MOCK_EXIT[mode]
    if mode == "timeout_sim":
        sys.stdout.write(json.dumps({
            "synthetic": True, "assumedSchema": True,
            "error": {"kind": "timeout", "message": "synthetic simulated timeout"},
        }, ensure_ascii=False) + "\n")
        return MOCK_EXIT[mode]
    if mode in ("auth_401", "forbidden_403", "rate_429"):
        status_map = {"auth_401": 401, "forbidden_403": 403, "rate_429": 429}
        kind_map = {"auth_401": "auth_required", "forbidden_403": "forbidden",
                    "rate_429": "rate_limited"}
        sys.stdout.write(json.dumps({
            "synthetic": True, "assumedSchema": True,
            "httpStatus": status_map[mode],
            "error": {"kind": kind_map[mode],
                      "message": "synthetic " + kind_map[mode]},
        }, ensure_ascii=False) + "\n")
        return MOCK_EXIT[mode]
    if mode == "empty_choices":
        sys.stdout.write(json.dumps({
            "synthetic": True, "assumedSchema": True,
            "httpStatus": 200, "verdicts": [],
            "note": "synthetic empty verdict list",
        }, ensure_ascii=False) + "\n")
        return MOCK_EXIT[mode]
    sys.stdout.write(json.dumps({
        "synthetic": True, "assumedSchema": True,
        "httpStatus": 200,
        "verdicts": [
            {"candidateId": "cand-1", "score": 0.81,
             "label": "relevant", "rationale": "synthetic mock"},
            {"candidateId": "cand-2", "score": 0.12,
             "label": "irrelevant", "rationale": "synthetic mock"},
        ],
        "note": "synthetic assumed-shape response; real JEV schema UNVERIFIED",
    }, ensure_ascii=False) + "\n")
    return MOCK_EXIT[mode]


def _pack_items(data: dict):
    for key in ("items", "files", "selected"):
        value = data.get(key)
        if isinstance(value, list):
            return key, value
    return None, None


def _pack_excluded(data: dict):
    for key in ("excluded", "dropped", "skipped"):
        value = data.get(key)
        if isinstance(value, list):
            return key, value
    return None, None


def cmd_pack_lint(root: Path, rel: str):
    """Advisory integrity check for a generated context pack JSON.

    Required by WP2/T4/T5: every selected item carries path + line range or
    excerpt + hash + reason; every excluded entry carries path + reason; a
    pinned contract block is present. Field-name variants accepted because
    the real pack format is Codex-owned and not final yet - a MISS is a
    'check the field' signal, not a verdict.
    """
    path = engine.under_root(root, rel)
    if not path.is_file():
        raise engine.AssistError("pack-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("pack-not-json") from exc
    if not isinstance(data, dict):
        raise engine.AssistError("pack-shape")

    problems = []
    pinned_keys = ("pinned", "contract", "required", "mustKeep", "must_keep")
    pinned = next((data[k] for k in pinned_keys
                   if isinstance(data.get(k), (dict, list)) and data[k]), None)
    if pinned is None:
        problems.append({"where": "$", "miss": "pinned-contract-block"})

    sel_key, items = _pack_items(data)
    if items is None or not items:
        problems.append({"where": "$", "miss": "selected-items-list"})
        items = []
    exc_key, excluded = _pack_excluded(data)
    if excluded is None:
        problems.append({"where": "$", "miss": "excluded-list"})

    def has_any(obj, keys):
        return any(isinstance(obj.get(k), (str, int, list)) and obj.get(k)
                   for k in keys)

    for i, item in enumerate(items[:200]):
        where = f"$.{sel_key}[{i}]"
        if not isinstance(item, dict):
            problems.append({"where": where, "miss": "item-not-object"})
            continue
        if not item.get("path"):
            problems.append({"where": where, "miss": "path"})
        if not (has_any(item, ("lines", "lineRange", "line_range", "range"))
                or item.get("excerpt")):
            problems.append({"where": where, "miss": "line-range-or-excerpt"})
        if not has_any(item, ("sha12", "hash", "sha256", "sha", "digest")):
            problems.append({"where": where, "miss": "hash"})
        if not has_any(item, ("reason", "why", "selectedFor", "selected_for")):
            problems.append({"where": where, "miss": "selection-reason"})
    if excluded:
        for i, item in enumerate(excluded[:200]):
            where = f"$.{exc_key}[{i}]"
            if not isinstance(item, dict):
                problems.append({"where": where, "miss": "item-not-object"})
                continue
            if not item.get("path"):
                problems.append({"where": where, "miss": "path"})
            if not has_any(item, ("reason", "why")):
                problems.append({"where": where, "miss": "exclusion-reason"})
    budget = next((data[k] for k in ("budget", "limits", "cap")
                   if isinstance(data.get(k), dict)), None)
    if budget is None:
        problems.append({"where": "$", "miss": "budget-block"})

    status, code = ("PACK_GAP", 4) if problems else ("PACK_OK", 0)
    report = base("pack-lint", status)
    report["file"] = rel
    report["items"] = len(items)
    report["excluded"] = len(excluded or [])
    report["pinnedPresent"] = pinned is not None
    report["problems"] = problems
    report["note"] = (
        "Advisory contract check on the generated pack: pinned contract "
        "block present, each selected item has path + line range or excerpt "
        "+ hash + reason, each excluded entry has path + reason, budget "
        "block present. Field-name variants are accepted; if the real pack "
        "format differs, adjust the call, not the requirement. PACK_OK is "
        "not proof of selection quality or speed."
    )
    return report, code


HANGUL_RE = re.compile(r"[가-힣ᄀ-ᇿ㄰-㆏]")


def cmd_token_estimate(root: Path, rel: str):
    """Honesty check: len(text)//4 underestimates Korean/code token counts.

    Reports bytes/chars plus three estimates so the packer never treats the
    naive //4 as an exact tokenizer (WP3).
    """
    path = engine.under_root(root, rel)
    if not path.is_file():
        raise engine.AssistError("file-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    data = path.read_bytes()
    text = data.decode("utf-8", errors="replace")
    chars = len(text)
    hangul = len(HANGUL_RE.findall(text))
    non_hangul = chars - hangul
    naive = len(text) // 4
    est_low = int(non_hangul * 0.25 + hangul * 0.6)
    est_high = int(len(data) * 0.5) if data else 0
    report = base("token-estimate", "ESTIMATE")
    report["file"] = rel
    report["bytes"] = len(data)
    report["chars"] = chars
    report["hangulChars"] = hangul
    report["naiveCharsDiv4"] = naive
    report["estimateLow"] = est_low
    report["estimateHigh"] = est_high
    report["underestimateVsNaive"] = est_low > naive
    report["note"] = (
        "Heuristic only - not a real tokenizer. naiveCharsDiv4 is the "
        "len(text)//4 shortcut from gptpro_pack_context._cap/approx_tokens; "
        "Korean and code usually tokenize above it. Keep a margin in the "
        "budget instead of trusting either estimate."
    )
    return report, 0


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
        ("good.diff", ("IN_SCOPE",)),
        ("bad_anchor.diff", ("PROTECTED_HIT",)),
        ("bad_product.diff", ("PROTECTED_HIT",)),
        ("bad_delete.diff", ("DELETE_ORIGINAL",)),
        ("bad_external.diff", ("EXTERNAL_SEND",)),
        ("bad_secret.diff", ("SECRET_LITERAL",)),
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

    pack_cases = (
        ("pack_ok.json", ("PACK_OK",)),
        ("pack_gap.json", ("PACK_GAP",)),
    )
    for name, expected in pack_cases:
        rel = FIXTURES + "/" + name
        if not (fixtures / name).is_file():
            record("pack-lint:" + name, "|".join(expected), "fixture-missing", False)
            continue
        report, _code = cmd_pack_lint(root, rel)
        record("pack-lint:" + name, "|".join(expected), report["status"],
               report["status"] in expected)

    import io
    for mode in MOCK_MODES:
        buf = io.StringIO()
        old = sys.stdout
        sys.stdout = buf
        try:
            code = cmd_jev_mock(mode)
        finally:
            sys.stdout = old
        out = buf.getvalue()
        if mode == "bad_schema":
            ok = code == MOCK_EXIT[mode] and "not-json" in out
        else:
            try:
                parsed = json.loads(out)
                ok = (code == MOCK_EXIT[mode]
                      and parsed.get("synthetic") is True)
            except json.JSONDecodeError:
                ok = False
        record("jev-mock:" + mode, f"exit {MOCK_EXIT[mode]}+synthetic",
               f"exit {code}", ok)

    est_file = fixtures / "korean_doc.txt"
    if est_file.is_file():
        report, _code = cmd_token_estimate(root, FIXTURES + "/korean_doc.txt")
        record("token-estimate", "underestimateVsNaive true",
               str(report.get("underestimateVsNaive")),
               report.get("underestimateVsNaive") is True)
    else:
        record("token-estimate", "fixture", "fixture-missing", False)

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
        description="context-pack + optional JEV assist (read-only)")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    gate = add_root(sub.add_parser("guard"))
    gate.add_argument("--diff", required=True)
    hold = add_root(sub.add_parser("hold"))
    hold.add_argument("--file", default=HOLD_REL)
    plan = add_root(sub.add_parser("verify-plan"))
    plan.add_argument("--spec", default=DEFAULT_SPEC)
    add_root(sub.add_parser("env-presence"))
    mock = sub.add_parser("jev-mock")
    mock.add_argument("--mode", default="ok", choices=MOCK_MODES)
    lint = add_root(sub.add_parser("pack-lint"))
    lint.add_argument("--file", required=True)
    est = add_root(sub.add_parser("token-estimate"))
    est.add_argument("--file", required=True)
    add_root(sub.add_parser("selftest"))
    parsed = parser.parse_args(args)
    if not parsed.cmd:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage",
              "productPass": False})
        return 2
    try:
        if parsed.cmd == "jev-mock":
            return cmd_jev_mock(parsed.mode)
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "guard":
            report, code = cmd_guard(read_diff(Path(parsed.diff)))
        elif parsed.cmd == "hold":
            report, code = cmd_hold(root, parsed.file)
        elif parsed.cmd == "verify-plan":
            report, code = cmd_verify_plan(root, parsed.spec)
        elif parsed.cmd == "env-presence":
            report, code = cmd_env_presence(root)
        elif parsed.cmd == "pack-lint":
            report, code = cmd_pack_lint(root, parsed.file)
        elif parsed.cmd == "token-estimate":
            report, code = cmd_token_estimate(root, parsed.file)
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
