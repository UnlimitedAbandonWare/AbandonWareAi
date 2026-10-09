"""Read-only assist for Codex briefs naver-apihub-search-50e1ba15 + NAVER-BRAVE-RAG-RESTORE-20261008.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, verify-plan, hold, env-presence, product-gate.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
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


def reject_name_relaxed(name: str):
    """Narrower basename guard for this pack: the brief pins
    ProviderCredentialResolver/NaverCredentialBridge .java sources, which the
    shared engine's blanket 'credential' rule would refuse. Still blocks
    .env*, *secret* names, providers.json, and credential stores in
    non-source extensions (credentials.json/*.pem/...)."""
    lower = name.lower()
    if lower.startswith(".env") or "secret" in lower or lower == "providers.json":
        raise engine.AssistError("secret-filename")
    if "credential" in lower and not lower.endswith(
            (".java", ".kt", ".py", ".js", ".ts", ".cjs", ".mjs", ".md")):
        raise engine.AssistError("secret-filename")


engine.reject_name = reject_name_relaxed

SCHEMA = "awx.naver-apihub-assist.v1"
PACK = "var/codex-assist-naver-apihub-20261008"
DEFAULT_SPEC = PACK + "/spec.json"
HYPO_REL = PACK + "/hypothesis.json"
HOLD_REL = PACK + "/hold.json"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"

# WP1 cause boundaries for the restore contract + apihub wiring boundary.
BOUNDARIES = (
    "key-missing",
    "alias-conflict",
    "breaker-open",
    "brave-sufficient-policy",
    "quota-429",
    "local-admission",
    "timeout-5xx",
    "true-zero",
    "stale-cache",
    "apihub-unwired",
    "not-reproduced",
)

# Files the apihub brief explicitly authorizes Codex to modify (W2-W6 list).
ALLOWED = tuple(p.casefold() for p in (
    "main/java/com/example/lms/service/NaverSearchService.java",
    "main/java/com/example/lms/config/WebClientConfig.java",
    "main/java/com/example/lms/guard/ProviderCredentialResolver.java",
    "main/java/com/example/lms/service/search/NaverCredentialBridge.java",
    "main/java/com/example/lms/debug/ApiFailureRecorder.java",
    "main/java/com/example/lms/config/LocalLlmProcessManager.java",
    "main/resources/application.yml",
))

# Files Codex must NOT write per the brief's protected list.
PROTECTED = tuple(p.casefold() for p in (
    "main/resources/static/js/chat.js",
    "main/resources/templates/chat-ui.html",
    "main/java/com/example/lms/assist/DisplayRelay.java",
    "main/java/com/example/lms/assist/DisplayConversateController.java",
    "main/java/com/example/lms/service/web/BraveSearchService.java",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
))

# Named anchors worth overlap-checking (allowed + read-anchors used by the briefs).
WATCH = ALLOWED + tuple(p.casefold() for p in (
    "src/test/java/com/example/lms/service/NaverSearchServiceApiHubTest.java",
    "main/java/com/example/lms/search/provider/HybridWebSearchProvider.java",
    "main/java/com/example/lms/infra/resilience/NightmareBreaker.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "scripts/apikit/providers/naver.py",
    "scripts/apikit/common.py",
    "scripts/apikit/main.py",
))

ENV_NAMES = (
    "NAVER_APIHUB_CLIENT_ID",
    "NAVER_APIHUB_CLIENT_SECRET",
    "NAVER_APIHUB_BASE_URL",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "NAVER_KEYS",
)

SECRET_LITERAL = re.compile(
    r"(?i)(secret|api[-_]?key|credential)\w*\s*[:=]\s*[\"']?"
    r"(?!synthetic|changeme|test|dummy|example|redacted|\$\{|\{)[A-Za-z0-9+/=_\-]{16,}"
)


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


def path_hit(entry: str, hot: str) -> bool:
    ent = norm(entry)
    if ent == hot or ent.endswith("/" + hot):
        return True
    last = ent.rsplit("/", 1)[-1]
    if "." not in last and (hot == ent or hot.startswith(ent + "/")):
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


def load_spec(root: Path, rel: str):
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
            raw_paths = data.get("targetPaths") or []
            if not isinstance(raw_paths, list):
                raise engine.AssistError("json-unreadable")
            hits = []
            foreign_hits = []
            for item in raw_paths:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in WATCH if path_hit(item, hot) and hot not in hits)
                foreign_hits.extend(
                    item_hot for item_hot in PROTECTED
                    if path_hit(item, item_hot) and item_hot not in foreign_hits
                )
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
            hits = []
            foreign_hits = []
            for item in scope:
                if not isinstance(item, str):
                    continue
                hits.extend(hot for hot in WATCH if path_hit(item, hot) and hot not in hits)
                foreign_hits.extend(
                    item_hot for item_hot in PROTECTED
                    if path_hit(item, item_hot) and item_hot not in foreign_hits
                )
            row = {"kind": "journal", "taskId": data.get("taskId"), "agent": data.get("agent")}
            if hits:
                journals.append({**row, "paths": hits})
            if foreign_hits:
                foreign.append({**row, "paths": foreign_hits})
    blocked = []
    for row in overlaps + journals:
        for item in row["paths"]:
            if item not in blocked:
                blocked.append(item)
    free = [item for item in WATCH if item not in blocked]
    if overlaps or journals:
        status, code = "OVERLAP", 7
    else:
        status, code = "CLEAR", 0
    report = base("scope", status)
    report["leaseOverlaps"] = overlaps
    report["journalOverlaps"] = journals
    report["protectedHits"] = foreign
    report["blockedPaths"] = blocked
    report["freePaths"] = free
    report["note"] = (
        "OVERLAP lists leases + in_progress plannedScope hits on watched paths. "
        "OVERLAP means wait unless this session is that ownerId; never force-release. "
        "protectedHits show leases/journals touching files the apihub brief protects. "
        "expiresAtUtc is the stored value. This command does not reclaim."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
    data = load_card(root, rel)
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    found = {}
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if item_id not in BOUNDARIES or status not in ("PENDING", "ACTIVE", "CLOSED"):
            raise engine.AssistError("spec-shape")
        if item_id in found:
            raise engine.AssistError("spec-shape")
        found[item_id] = status
    if set(found) != set(BOUNDARIES):
        raise engine.AssistError("spec-shape")
    active = [item_id for item_id, status in found.items() if status == "ACTIVE"]
    if len(active) > 1:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif len(active) == 1:
        status, code = "ONE_ACTIVE", 0
    else:
        status, code = "NONE", 0
    report = base("hypothesis", status)
    report["active"] = active
    report["note"] = (
        "At most one WP1 cause hypothesis may be ACTIVE. "
        "not-reproduced means stop with classification evidence, no product patch. "
        "PENDING is not a failure."
    )
    return report, code


def cmd_verify_plan(root: Path, rel: str, contract: str | None):
    spec = load_spec(root, rel)
    contracts = spec.get("contracts")
    if not isinstance(contracts, dict) or not contracts:
        raise engine.AssistError("spec-shape")
    plan = []
    for name, body in contracts.items():
        if contract and name != contract:
            continue
        if not isinstance(body, dict):
            raise engine.AssistError("spec-shape")
        steps = body.get("acceptance")
        if not isinstance(steps, list):
            continue
        for step in steps:
            if not isinstance(step, dict):
                raise engine.AssistError("spec-shape")
            step_id = step.get("id")
            command = step.get("command")
            if not isinstance(step_id, str) or not isinstance(command, str):
                raise engine.AssistError("spec-shape")
            plan.append({
                "contract": name,
                "id": step_id,
                "command": command,
                "note": step.get("note"),
                "external": bool(step.get("external")),
            })
    if contract and contract not in contracts:
        raise engine.AssistError("contract-unknown")
    status, code = ("PLAN", 0) if plan else ("EMPTY", 2)
    report = base("verify-plan", status)
    report["steps"] = plan
    report["note"] = (
        "Ordered acceptance commands from both briefs. Run stepwise from the project "
        "root; record exit codes and real test counts. Zero tests is not PASS. "
        "external=true marks real network calls gated by the key install + approval "
        "and the 5-call live budget. This command does not execute them."
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
        rows.append({"id": item_id, "status": status})
        if status == "OPEN":
            open_ids.append(item_id)
    status, code = ("OPEN", 4) if open_ids else ("CLEAN", 0)
    report = base("hold", status)
    report["items"] = rows
    report["openIds"] = open_ids
    report["note"] = (
        "OPEN hold items block a DONE claim, not independent mock/read checks. "
        "Record real resolutions only."
    )
    return report, code


def cmd_env_presence(root: Path):
    """Presence-only check. Emits booleans per expected key name; never values."""
    rows = {name: {"processEnv": bool(os.environ.get(name)), "dotenv": False}
            for name in ENV_NAMES}
    dotenv_present = False
    env_path = root / ".env"
    if env_path.is_file() and not env_path.is_symlink():
        dotenv_present = True
        try:
            text = env_path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise engine.AssistError("dotenv-unreadable") from exc
        for line in text.splitlines():
            match = re.match(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=", line)
            if match and match.group(1) in rows:
                rows[match.group(1)]["dotenv"] = True
    secrets_json_present = (root / ".secrets" / "providers.json").is_file()
    status, code = "PRESENT_MAP", 0
    report = base("env-presence", status)
    report["keys"] = rows
    report["files"] = {"dotenv": dotenv_present, "secretsProvidersJson": secrets_json_present}
    report["note"] = (
        "Booleans only; values are never read back or printed. dotenv=false with a "
        "missing .env means 'key name not found in .env'. A complete "
        "NAVER_APIHUB_CLIENT_ID+SECRET pair under auto selects the hub path; an "
        "incomplete pair falls back to openapi per the Codex contract."
    )
    return report, code


def classify_path(folded: str):
    """Return (label, detail) for an added-line diff path."""
    base_name = folded.rsplit("/", 1)[-1]
    if base_name.startswith(".env") or "/.secrets/" in folded or base_name == "providers.json":
        return "protected", "secrets-file"
    if folded in PROTECTED:
        return "protected", "protected-file"
    if "brave" in base_name:
        return "protected", "other-search-provider"
    if "/assets/display/" in folded or "/assets/interview/" in folded:
        return "protected", "display-or-interview-surface"
    if folded in ALLOWED:
        return "allowed", "brief-allowed"
    if folded.startswith("src/test/") or folded.startswith("src/chatuitest/"):
        return "test", "test-file"
    if folded.startswith(("main/", "src/main/")):
        return "extra-product", "not-in-modify-list"
    if folded.startswith(("scripts/", "docs/", "data/", "var/", ".agents/",
                          ".windsurf/", "agent-prompts/", "__patch_drop__/")):
        return "support", "non-product"
    return "other", "unclassified"


def cmd_product_gate(root: Path, diff_text: str):
    counts = {}
    product_files = []
    secret_hits = []
    total_added = 0
    for index, path, text in engine.added_lines(diff_text):
        total_added += 1
        if not path:
            label, detail = "other", "no-path"
            folded = ""
        else:
            folded = norm(path)
            label, detail = classify_path(folded)
        counts[label] = counts.get(label, 0) + 1
        if label in ("allowed", "extra-product") and folded and folded not in product_files:
            product_files.append(folded)
        if SECRET_LITERAL.search(text):
            secret_hits.append({"path": path, "diffLine": index})
    protected = counts.get("protected", 0)
    extra = counts.get("extra-product", 0)
    if secret_hits:
        status, code = "SECRET_LITERAL", 3
    elif protected:
        status, code = "PROTECTED_HIT", 3
    elif extra:
        status, code = "SCOPE_EXPAND", 4
    elif counts.get("allowed"):
        status, code = "IN_SCOPE", 0
    elif counts.get("test"):
        status, code = "TEST_ONLY", 0
    elif counts.get("support") or counts.get("other"):
        status, code = "ASSIST_ONLY", 0
    else:
        status, code = "EMPTY", 2
    report = base("product-gate", status)
    report["counts"] = counts
    report["productFiles"] = product_files
    report["productFileCount"] = len(product_files)
    report["addedLines"] = total_added
    report["secretHits"] = secret_hits
    report["note"] = (
        "IN_SCOPE = every added line lands on the brief's modify list or test "
        "files. SCOPE_EXPAND = product file outside the list; the brief allows "
        "<=3 small revertible files with a SCOPE_EXPAND journal record. "
        "PROTECTED_HIT/SECRET_LITERAL = do not ship. Hits are review signals, "
        "the matched source text is not copied."
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
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    cmd = args[0]
    if cmd in ("pin", "cover", "diff-forbid"):
        engine.SCHEMA = SCHEMA
        if "--spec" not in args:
            args = [cmd, "--spec", DEFAULT_SPEC, *args[1:]]
        return engine.main(args)
    parser = argparse.ArgumentParser(description="naver-apihub + naver-brave-restore assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    plan = add_root(sub.add_parser("verify-plan"))
    plan.add_argument("--spec", default=DEFAULT_SPEC)
    plan.add_argument("--contract", default=None)
    hold = add_root(sub.add_parser("hold"))
    hold.add_argument("--file", default=HOLD_REL)
    add_root(sub.add_parser("env-presence"))
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    parsed = parser.parse_args(args)
    known = ("scope", "hypothesis", "verify-plan", "hold", "env-presence", "product-gate")
    if parsed.cmd not in known:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "verify-plan":
            report, code = cmd_verify_plan(root, parsed.spec, parsed.contract)
        elif parsed.cmd == "hold":
            report, code = cmd_hold(root, parsed.file)
        elif parsed.cmd == "env-presence":
            report, code = cmd_env_presence(root)
        else:
            report, code = cmd_product_gate(root, read_diff(Path(parsed.diff)))
        emit(report)
        return code
    except engine.AssistError as exc:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": exc.reason,
              "productPass": False})
        return 2


if __name__ == "__main__":
    sys.exit(main())
