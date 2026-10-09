"""Read-only assist for Codex brief AUTO-SEARCH-PREFLIGHT-BUDGET-20261008.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, hold, verify-plan, product-gate, project.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan/simulation. It is not a product PASS.

`project` is a faithful Python port of PublicRequestBudgetGuard.validateChat
(projected path) + projectedChatQueryCount + SearchPolicyEngine.decide /
tuneTopK + SelfAskSearchBudget caps, verified against source on 2026-10-08.
It simulates projected retrieval/provider work for a request so the AUTO vs
LIGHT comparison and the rejection boundary can be explored offline. The
maxWebSearchCallsPerPhase domain-rescue detail is approximated by
--calls-per-phase (default 1) plus --rescue-domains/--site-filter.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as engine

SCHEMA = "awx.auto-search-budget-assist.v1"
PACK = "var/codex-assist-auto-search-budget-20261008"
DEFAULT_SPEC = PACK + "/spec.json"
HYPO_REL = PACK + "/hypothesis.json"
HOLD_REL = PACK + "/hold.json"
JOURNAL_ROOT = "data/agent-handoff/codex-autonomy"

# WP1 cause boundaries for the AUTO pre-flight rejection.
BOUNDARIES = (
    "retrieval-projection-overflow",
    "provider-projection-overflow",
    "stale-served-build",
    "mode-field-confusion",
    "saved-settings-overflow",
    "plan-extremez-inflation",
    "model-budget-misclassified",
    "external-429-misclassified",
    "not-reproduced",
)

# Product files inside the brief's plausible modify seam (WP3 anchor chain +
# WP4 error-classification/user-message seam). chat.js carries a foreign hunk;
# edits there must preserve it.
ALLOWED = tuple(p.casefold() for p in (
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/search/policy/SearchPolicyEngine.java",
    "main/java/com/example/lms/search/policy/SearchPolicyDecision.java",
    "main/java/com/example/lms/service/rag/SelfAskSearchBudget.java",
    "main/resources/static/js/chat.js",
))

# Files Codex must NOT write under this brief: build plumbing, the display /
# interview surfaces, the chat template, and paths under foreign leases.
PROTECTED = tuple(p.casefold() for p in (
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "main/resources/templates/chat-ui.html",
    "main/java/com/example/lms/assist/DisplayRelay.java",
    "main/java/com/example/lms/assist/DisplayConversateController.java",
    "main/java/com/example/lms/guard/KeyResolver.java",
    "main/java/com/example/lms/guard/ProviderCredentialResolver.java",
    "main/java/com/example/lms/service/NaverSearchService.java",
    "main/java/com/example/lms/service/search/NaverCredentialBridge.java",
    "main/java/com/example/lms/debug/ApiFailureRecorder.java",
    "main/java/com/example/lms/config/LocalLlmProcessManager.java",
))

# Anchors + read-targets worth lease-overlap checking.
WATCH = ALLOWED + PROTECTED + tuple(p.casefold() for p in (
    "src/test/java/com/example/lms/api/PublicRequestBudgetProjectionFocusedTest.java",
    "src/test/java/com/example/lms/api/PublicRequestBudgetGuardTest.java",
    "src/chatUiTest/java/com/example/lms/api/ChatPlanBudgetFocusedTest.java",
    "main/java/com/example/lms/gptsearch/dto/SearchMode.java",
    "main/java/com/example/lms/dto/ChatRequestDto.java",
    "main/resources/application.yml",
))

SECRET_LITERAL = re.compile(
    r"(?i)(secret|api[-_]?key|credential)\w*\s*[:=]\s*[\"']?"
    r"(?!synthetic|changeme|test|dummy|example|redacted|\$\{|\{)[A-Za-z0-9+/=_\-]{16,}"
)

# --- projected-budget math (ported from PublicRequestBudgetGuard, verified
# 2026-10-08 against :485-604, :663-727, :1276-1282) -------------------------

MIN_TOPK, MAX_TOPK = 3, 24
DEFAULT_MAX_RETRIEVAL_WORK = 384
DEFAULT_MAX_PROVIDER_WORK = 4096
DEFAULT_MAX_TOPK = 100

# SearchPolicyEngine.forMode table: mode -> (slicing, expansion, maxFinalQueries,
# maxExpansions, webTopKMultiplier, vecTopKMultiplier)
POLICY_TABLE = {
    "OFF": (False, False, 8, 0, 1.0, 1.0),
    "PRECISION": (False, False, 6, 0, 0.85, 0.85),
    "BALANCED": (True, True, 10, 2, 1.0, 1.0),
    "RECALL": (True, True, 14, 4, 1.35, 1.20),
    "DISAMBIGUATE": (True, True, 12, 3, 1.20, 1.10),
}

RECENCY_WORDS = ("최신", "최근", "업데이트", "release", "changelog", "patch",
                 "변경사항", "버전", "릴리즈")
DISAMBIGUATE_WORDS = ("차이", "비교", "vs", "difference")
PRECISION_WORDS = ("공식", "근거", "출처", "citation", "source", "정확")

DEEP_PROBE_RE = re.compile(r"^\s*DEEP\s+검색\s*해줘?\s*:\s*", re.IGNORECASE)
HANGUL_RE = re.compile(r"[가-힣ᄀ-ᇿ㄰-㆏]")


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def decide_policy(message: str, search_mode: str | None, override: str | None):
    """SearchPolicyEngine.decide(): ui searchMode wins, else keyword heuristics."""
    if override:
        return override.upper(), "override"
    if search_mode == "FORCE_LIGHT":
        return "PRECISION", "ui-search-mode"
    if search_mode == "FORCE_DEEP":
        return "RECALL", "ui-search-mode"
    if search_mode == "OFF":
        return "OFF", "ui-search-mode"
    lower = (message or "").lower()
    if any(w in lower for w in RECENCY_WORDS):
        return "RECALL", "recency"
    if any(w in lower for w in DISAMBIGUATE_WORDS):
        return "DISAMBIGUATE", "disambiguate"
    if any(w in lower for w in PRECISION_WORDS):
        return "PRECISION", "precision-keyword"
    tokens = len((message or "").split())
    if tokens <= 2 and len((message or "").strip()) <= 16:
        return "DISAMBIGUATE", "short-query"
    return "BALANCED", "default"


def tune_topk(base: int, mode: str, multiplier: float) -> int:
    b = clamp(base, MIN_TOPK, MAX_TOPK)
    if mode == "OFF":
        return b
    return clamp(int(round(b * multiplier)), MIN_TOPK, MAX_TOPK)


def is_deep_mode(message: str, search_mode: str | None) -> bool:
    if search_mode == "FORCE_DEEP":
        return True
    return (search_mode in (None, "AUTO")
            and bool(message) and bool(DEEP_PROBE_RE.search(message)))


def cmd_project(args):
    """Simulate the projected admission path (projection != null, the /chat flow)."""
    message = args.message or ""
    search_mode = (args.search_mode or "AUTO").upper()
    use_web = args.use_web
    use_rag = args.use_rag
    precision_search = args.precision_search
    max_topk = args.max_top_k
    max_retrieval = args.max_retrieval_work
    max_provider = args.max_provider_work

    plan_web = args.plan_web_top_k
    plan_vec = args.plan_vec_top_k
    plan_burst = args.plan_query_burst
    extreme_queries = args.extreme_z_queries  # None = extremeZ disabled

    notes = []
    if args.plan_allow_web is False:
        use_web = False
        notes.append("plan.allowWeb=false -> useWeb=false")
    if args.plan_allow_rag is False:
        use_rag = False
        notes.append("plan.allowRag=false -> useRag=false")
    if args.future_tech_override:
        use_web, use_rag = True, False
        notes.append("futureTech override -> useWeb=true,useRag=false")

    if extreme_queries is not None and not args.plan_present:
        args.plan_present = True
        notes.append("extremeZ implies plan present (plan.extremeZEnabled)")

    web_axis = use_rag or use_web or precision_search
    if web_axis and (args.web_top_k is None or args.web_top_k <= 0):
        report = base("project", "REJECT")
        report["reasonCode"] = "chat_web_top_k_invalid"
        report["detail"] = "web axis active but no positive webTopK supplied"
        return report, 3
    requested_web = clamp(args.web_top_k or 0, 0, max_topk)
    precision_topk = clamp(args.precision_top_k or 0, 0, max_topk)
    search_queries = max(0, args.search_queries)

    web_topk, rag_topk = requested_web, requested_web
    if args.plan_present:
        if use_web and plan_web:
            web_topk = max(web_topk, clamp(plan_web, 0, max_topk))
        if use_rag and plan_vec:
            rag_topk = max(rag_topk, clamp(plan_vec, 0, max_topk))

    policy_mode, policy_reason = (None, None)
    if web_axis:
        policy_mode, policy_reason = decide_policy(message, search_mode,
                                                   args.policy)
        slicing, expansion, max_final, _me, wmul, vmul = POLICY_TABLE[policy_mode]
        if use_web:
            web_topk = max(web_topk, tune_topk(web_topk, policy_mode, wmul))
        if use_rag:
            rag_topk = max(rag_topk, tune_topk(rag_topk, policy_mode, vmul))
    else:
        max_final = None

    retrieval_work, branch_count = 0, 0
    if use_rag:
        retrieval_work += rag_topk
        branch_count += 1
    live_web = use_web and search_mode != "OFF"
    if live_web:
        retrieval_work += web_topk
        branch_count += 1
    if precision_search:
        retrieval_work += precision_topk if precision_topk > 0 else web_topk
        branch_count += 1

    # projectedChatQueryCount (PublicRequestBudgetGuard:663-727)
    client_queries = max(1, search_queries + 1)
    if not web_axis:
        projected = client_queries
        workflow_queries, plan_queries, extreme_used = None, None, 0
    else:
        plan_queries = 2
        if args.plan_present:
            if plan_burst and plan_burst > 0:
                plan_queries = clamp(plan_burst, 2, 32)
            elif args.plan_aggressive:
                plan_queries = 18
        if search_mode == "FORCE_LIGHT":
            workflow_queries = 1
        elif policy_mode in (None, "OFF"):
            workflow_queries = plan_queries
        else:
            workflow_queries = min(max_final, 3)  # the 3-query 보정
        workflow_queries = clamp(workflow_queries, 1, 32)
        projected = max(client_queries, workflow_queries)
        extreme_used = 0
        if extreme_queries is not None:
            extreme_used = extreme_queries if extreme_queries > 0 else 6
            projected += extreme_used

    mode_multiplier = (2 if is_deep_mode(message, search_mode) else 1) \
        * (2 if args.accumulation else 1)
    retrieval_work *= projected * mode_multiplier
    branch_count *= projected * mode_multiplier

    # callsPerPhase: maxWebSearchCallsPerPhase approximated; rescue/site-filter
    # passes are flags (the domain detectors live in ChatApiController).
    calls_per_phase = args.calls_per_phase
    if calls_per_phase is None:
        calls_per_phase = (1 + 2 * args.rescue_domains
                           + (1 if args.site_filter else 0)) if live_web else 0
    elif not live_web:
        calls_per_phase = 0
    attempt_mult = 4 if args.no_projection else (12 if HANGUL_RE.search(message) else 6)
    provider_work = calls_per_phase * attempt_mult * web_topk * projected * mode_multiplier

    if retrieval_work > max_retrieval:
        verdict, code = "chat_retrieval_budget_exceeded", 5
    elif provider_work > max_provider:
        verdict, code = "chat_provider_budget_exceeded", 6
    else:
        verdict, code = "ADMIT", 0

    report = base("project", verdict)
    report["inputs"] = {
        "searchMode": search_mode, "executionMode": args.execution_mode,
        "useWeb": use_web, "useRag": use_rag, "precisionSearch": precision_search,
        "planPresent": args.plan_present, "hangul": bool(HANGUL_RE.search(message)),
    }
    report["projection"] = {
        "requestedWebTopK": requested_web, "webTopK": web_topk,
        "ragTopK": rag_topk, "precisionTopK": precision_topk,
        "policyMode": policy_mode, "policyReason": policy_reason,
        "policyMaxFinalQueries": max_final,
        "clientQueries": client_queries, "planQueries": plan_queries,
        "workflowQueries": workflow_queries, "extremeZQueries": extreme_used,
        "plannedQueries": projected, "queryMultiplier": projected,
        "modeMultiplier": mode_multiplier,
        "callsPerPhase": calls_per_phase,
        "providerAttemptMultiplier": attempt_mult,
        "retrievalWork": retrieval_work, "providerWork": provider_work,
        "branchCount": branch_count,
        "maxRetrievalWork": max_retrieval, "maxProviderWork": max_provider,
    }
    report["reasonCode"] = verdict if code else None
    report["notes"] = notes + [
        "Projection path only (5-arg validateChatProjected). --no-projection "
        "uses the legacy non-plan formula (attemptMultiplier=4).",
        "callsPerPhase approximates maxWebSearchCallsPerPhase: 1 + 2*rescue + "
        "siteFilter; use --calls-per-phase to pin the real value.",
    ]
    if verdict == "ADMIT":
        report["notes"].append(
            "ADMIT in simulation does not prove live admission - a stale served "
            "build or saved-settings overflow can still reject.")
    return report, code


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
        "protectedHits show leases/journals touching files this brief protects "
        "(build files, display/interview surface, foreign-leased naver seam). "
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
    spec = load_card(root, rel)
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
        "Ordered acceptance commands from the brief. Run stepwise from the "
        "project root; record exit codes and real test counts. Zero tests is "
        "not PASS. external=true steps need the user's restart/live-search "
        "approval; this command does not execute them."
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
        "OPEN hold items block a DONE claim, not independent fixture/simulation "
        "checks. Record real resolutions only."
    )
    return report, code


def classify_path(folded: str):
    """Return (label, detail) for an added-line diff path."""
    base_name = folded.rsplit("/", 1)[-1]
    if base_name.startswith(".env") or "/.secrets/" in folded or base_name == "providers.json":
        return "protected", "secrets-file"
    if "/assets/display/" in folded or "/assets/interview/" in folded:
        return "protected", "display-or-interview-surface"
    if folded in PROTECTED:
        return "protected", "protected-file"
    if folded == "main/resources/static/js/chat.js":
        return "allowed", "foreign-hunk-present"
    if folded in ALLOWED:
        return "allowed", "brief-seam"
    if folded.startswith("src/test/") or folded.startswith("src/chatuitest/"):
        return "test", "test-file"
    if folded.startswith(("main/", "src/main/")):
        return "extra-product", "not-in-brief-seam"
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
        "IN_SCOPE = added lines land on the brief's seam (guard, controller, "
        "policy engine/decision, selfask budget, chat.js). chat.js carries a "
        "foreign hunk - diffs there need a preserve note. SCOPE_EXPAND = other "
        "product file; journal it. PROTECTED_HIT/SECRET_LITERAL = do not ship. "
        "Hits are review signals; matched source text is not copied."
    )
    return report, code


def read_diff(path: Path) -> str:
    engine.reject_name(path.name)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("diff-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


def add_project_parser(sub):
    p = sub.add_parser("project")
    p.add_argument("--root", default=".")
    p.add_argument("--message", default="")
    p.add_argument("--search-mode", default="AUTO",
                   choices=["AUTO", "OFF", "FORCE_LIGHT", "FORCE_DEEP"])
    p.add_argument("--execution-mode", default="AUTO")
    p.add_argument("--use-web", dest="use_web", action="store_true", default=True)
    p.add_argument("--no-web", dest="use_web", action="store_false")
    p.add_argument("--use-rag", dest="use_rag", action="store_true", default=True)
    p.add_argument("--no-rag", dest="use_rag", action="store_false")
    p.add_argument("--web-top-k", type=int, default=8)
    p.add_argument("--precision-search", action="store_true")
    p.add_argument("--precision-top-k", type=int, default=0)
    p.add_argument("--search-queries", type=int, default=0)
    p.add_argument("--plan-present", action="store_true")
    p.add_argument("--plan-web-top-k", type=int, default=0)
    p.add_argument("--plan-vec-top-k", type=int, default=0)
    p.add_argument("--plan-query-burst", type=int, default=0)
    p.add_argument("--plan-aggressive", action="store_true")
    p.add_argument("--plan-allow-web", dest="plan_allow_web",
                   action="store_true", default=None)
    p.add_argument("--plan-deny-web", dest="plan_allow_web",
                   action="store_false")
    p.add_argument("--plan-allow-rag", dest="plan_allow_rag",
                   action="store_true", default=None)
    p.add_argument("--plan-deny-rag", dest="plan_allow_rag",
                   action="store_false")
    p.add_argument("--extreme-z-queries", type=int, default=None,
                   help="enable extremeZ projection; value is the added query count, "
                        "use -1 for the configured default (6)")
    p.add_argument("--accumulation", action="store_true")
    p.add_argument("--calls-per-phase", type=int, default=None)
    p.add_argument("--rescue-domains", type=int, default=0)
    p.add_argument("--site-filter", action="store_true")
    p.add_argument("--future-tech-override", action="store_true",
                   help="latestTech auto-disable-vector path: useWeb=true, useRag=false")
    p.add_argument("--policy", default=None,
                   choices=["PRECISION", "BALANCED", "RECALL", "DISAMBIGUATE", "OFF"])
    p.add_argument("--no-projection", action="store_true")
    p.add_argument("--max-retrieval-work", type=int, default=DEFAULT_MAX_RETRIEVAL_WORK)
    p.add_argument("--max-provider-work", type=int, default=DEFAULT_MAX_PROVIDER_WORK)
    p.add_argument("--max-top-k", type=int, default=DEFAULT_MAX_TOPK)
    return p


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
    parser = argparse.ArgumentParser(description="auto-search preflight budget assist")
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
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    add_project_parser(sub)
    parsed = parser.parse_args(args)
    known = ("scope", "hypothesis", "verify-plan", "hold", "product-gate", "project")
    if parsed.cmd not in known:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage",
              "productPass": False})
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
        elif parsed.cmd == "project":
            report, code = cmd_project(parsed)
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
