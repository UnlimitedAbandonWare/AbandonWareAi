"""Offline preparation for the 20261007 main /chat exact ten-turn brief.

No browser driver, model call, installation, restart, or automatic retry.
Native conversation exports are inventory, never reconstructed dispatch proof.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from chat_session_debug_export import STRICT_JSON, reject_reparse

ROOT = Path(__file__).resolve().parents[1]
OUT_BASE = "var/browser10-support-20261007"
SCHEMA = "awx.browser10-support.v1"
OBS_SCHEMA = "awx.browser10-observations.v1"
EXPORT_SCHEMA = "awx.conversation-context.v1"
CONTRACT = "DEMO1-BROWSER-10TURN-CONTEXT-RECOVERY-20261007"
BRIEF_SHA = "ac7ddebf42480335f1e4cbbe4f237314e41bd17ae596cd9018b37685e5ea9466"
MAX_INPUT = 16 * 1024 * 1024
MAX_ZIP = 17 * 1024 * 1024
MISSING = "NOT_OBSERVED"
SOURCE_PATHS = [
    "settings.gradle", "build.gradle.kts", "scripts/browser10_support_setup.py",
    "scripts/test_browser10_support_setup.py", "scripts/chat_session_debug_export.py",
    "scripts/chat_practice_browser.js", "main/resources/static/js/chat-conversation-export.js",
    "main/java/com/example/lms/api/ChatConversationExportSupport.java",
    "main/resources/static/js/chat.js", "main/resources/static/js/chat-trace-ui.js",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/prompt/StandardPromptBuilder.java",
    "main/java/com/example/lms/service/ChatHistoryServiceImpl.java",
    "main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java",
]
QUESTIONS = [
    "거북선이 뭐야? 역사 지식이 없는 중학생에게 핵심만 세 문장으로 설명하고 공식 자료 한 곳으로 확인해줘. 이 대화 안에서는 공식 자료를 우선하고, 확인된 사실과 불확실한 내용을 구분해줘.",
    "그 배는 왜 지붕을 덮었고 어떤 임무를 맡았어? 방금 출처에서 확인되는 내용은 이어 쓰고, 부족하면 다른 공식 자료로 확인해줘. 세 문장 안에서 각 주장과 출처를 연결해줘.",
    "내가 “철갑선이니까 포탄도 다 막았다”고 이해했는데 그건 맞아? 틀린 점을 고쳐서, 확인된 사실과 복원·추정을 나눠 세 문장으로 다시 정리해줘.",
    "거북선과 판옥선을 임무·구조·확인 가능한 근거, 세 기준으로 비교해줘. 표는 최대 세 행으로 하고 불확실한 설명은 표시해줘.",
    "그 비교에서 “덮인 지붕이 있으면 침몰하지 않는다”는 결론도 나와? 추가 검색 없이 방금 쓴 출처 안에서 말할 수 있는 범위만 설명해줘. 확인되지 않은 구조나 재료는 추정이라고 해줘.",
    "지금 거북선 관련 전시를 볼 수 있는 공식 박물관이나 기념관 한 곳을 찾아줘. 테스트하는 오늘 날짜 기준으로 전시 안내·운영시간·휴관 안내를 공식 페이지로 확인하고, 오늘 실제 개관을 확인하지 못하면 그 점은 구분해줘.",
    "주제를 바꿀게. 이 챗봇을 면접에서 소개한다면 “검색한 자료가 답변에 실제로 쓰였다”는 걸 어떻게 검증하겠어? 관리자 권한 없이 일반 사용자 화면에서 가능한 방법 세 가지만 알려줘. 앞으로는 면접관에게 말하듯 간결하게 답해줘.",
    "거북선 이야기로 돌아가자. 앞에서 내가 잘못 이해했다가 고친 두 가지는 뭐였고, 판옥선과 비교할 때 정한 기준은 뭐였어? 새 검색 없이 이 대화에 나온 내용만 간단히 되짚어줘.",
    "아까 찾은 전시 장소를 이용해 30분 관람 계획을 세워줘. 오늘 문이 열렸다고 단정하지 말고, 공식 안내로 확인된 내용과 내 조건 때문에 네가 제안한 동선을 나눠줘. 입장료나 예약 조건을 못 확인했다면 그 부분만 말해줘.",
    "지금까지를 면접용 한 문단으로 요약해줘. 거북선·판옥선에 관해 공식 근거로 확인된 사실, 내가 정정한 오해, 오늘 운영 여부처럼 아직 미확인인 내용, 네가 제안한 30분 동선을 각각 구분해줘. 마지막에 이 대화에서 계속 지켜야 할 답변 기준도 한 줄로 적어줘. 추가 검색은 하지 마.",
]
EXPECTATIONS = [
    ("REQUIRED", "turtle_ship; official_first; facts_vs_uncertainty; three_sentences", "useful_answer_and_body_dispatch_citation", "restoration_treated_as_original_proof"),
    ("CONDITIONAL", "that_ship=T01; roof_and_mission_support; three_sentences", "same_session_A_to_B_and_direct_B_support", "pronoun_only_or_copied_A_results"),
    ("CONDITIONAL", "actual_correction_of_armor_claim; uncertainty; three_sentences", "bounded_correction_and_latest_correction_retained", "endorsed_misconception_or_absolute_protection"),
    ("CONDITIONAL", "turtle_ship_vs_panokseon; mission_structure_evidence; max_three_rows", "comparison_spans_and_format_and_T03_retained", "invented_structure_numbers_or_summary_displaces_query"),
    ("FORBIDDEN", "second_misconception; existing_sources_only; causal_support_limits", "unsupported_unsinkable_claim_limited_and_correction_retained", "new_search_or_unverified_material_as_fact"),
    ("REQUIRED", "RUN_DATE_TZ; actual_place; official_sources_and_time; opening_unknown", "current_official_body_dispatch_claim_citation", "usual_hours_imply_open_today_or_fake_place"),
    ("NOT_NEEDED", "ordinary_user_UI; three_methods; concise_interview_audience", "concept_vs_observation_limits_and_new_constraint_retained", "permission_bypass_or_unproven_product_guarantee"),
    ("FORBIDDEN", "actual_T03_T05_misconceptions_and_corrections; T04_criteria; audience", "same_conversation_semantic_recall", "unresolved_as_resolved_or_other_session_memory"),
    ("CONDITIONAL", "T06_place_sources_time_unknowns; 30_minutes; fact_vs_proposal", "same_place_and_30min_proposal_and_unknown_fees_booking", "place_changed_or_invented_room_price_or_open_today"),
    ("FORBIDDEN", "actual_two_corrections; counterexamples; uncertainty; sources_time; 30min; audience", "bounded_summary_and_continuing_answer_criteria", "misconception_returns_or_speculation_as_fact_or_new_search"),
]
STAGES = ("candidate", "sourceBody", "afterFilter", "packing", "actualProviderDispatch", "citation", "storedReload")
STATES = {"PROVEN", "NOT_PROVEN", "NOT_OBSERVED", "UNKNOWN", "NOT_RUN", "FAIL", "PARTIAL"}
TERMINALS = {"completed", "cancelled", "disconnected", "timeout", "partial", "failed", "running"}
REF_FIELDS = ("ownerRef", "sessionRef", "userMessageId", "assistantMessageId", "runId", "requestId", "snapshotId")
EVENT_FIELDS = set(REF_FIELDS) | {"runLabel", "turnNo", "attemptNo", "terminalStatus", "checkedAt", "sources", "usefulAnswerVerdict", "requestedModel", "routedModel", "actualModel", "servedBuild", "actualDispatchMetadata", "timings"}


class SupportError(ValueError):
    pass


def require(ok, reason):
    if not ok:
        raise SupportError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def ref(value):
    if value is None or value in ("", MISSING, "UNKNOWN"):
        return MISSING
    require(type(value) in (str, int) and len(str(value)) <= 512, "invalid-id")
    text = str(value)
    if re.fullmatch(r"hash:[0-9a-f]{12,64}", text):
        return text
    try:
        return "hash:" + sha(text.encode("utf-8"))[:12]
    except UnicodeError as exc:
        raise SupportError("invalid-id-encoding") from exc


def stamp():
    return datetime.now(timezone.utc).isoformat()


def safe_time(value):
    if isinstance(value, str) and len(value) <= 64:
        try:
            date = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if date.utcoffset() is not None:
                return date.astimezone(timezone.utc).isoformat()
        except (ValueError, OverflowError):
            pass
    return MISSING


def case_manifest():
    cases = []
    for n, (question, rules) in enumerate(zip(QUESTIONS, EXPECTATIONS), 1):
        search, facts, passed, failed = rules
        cases.append({"id": f"T{n:02}", "turnNo": n, "question": question,
                      "expectedFacts": facts.split("; "), "searchNeed": search,
                      "retention": ["latest_query", "actual_corrections_negations_counterexamples", "numbers_units_uncertainty", "source_span_revision_hash_time", "user_constraints"],
                      "passIf": passed, "failIf": failed, "runtimeVerdict": "NOT_RUN"})
    cases[5]["partialIf"] = "honest_search_shortfall_is_not_search_PASS"
    return {"schemaVersion": SCHEMA, "contractId": CONTRACT, "briefSHA": BRIEF_SHA,
            "surface": "http://127.0.0.1:18180/chat", "automaticSubmission": False,
            "runDateAndTimezone": "RECORD_AT_EXECUTION", "turnCount": 10,
            "sameSessionRequired": True, "userSubmissions": 10, "waitForUsefulAnswerAndTerminal": True,
            "factsPolicy": "Official claim/span at execution; no hardcoded history answers",
            "conditionalSearch": "Only missing direct support/current information", "cases": cases}


def output_path(root, relative):
    text = str(relative).replace("\\", "/")
    require(not Path(text).is_absolute() and ":" not in text and ".." not in text.split("/"), "output-boundary")
    path = Path(os.path.abspath(root / text))
    base = Path(os.path.abspath(root / OUT_BASE))
    require(path != base and path.is_relative_to(base), "output-boundary")
    reject_reparse(path)
    return path


def load_input(path):
    path = Path(path)
    require(not any(re.search(r"(?i)^\.secrets$|^\.env|cookie|credential|token|authorization|\.(pem|key|pfx|p12|jks)$", part) for part in path.parts), "private-input")
    reject_reparse(path)
    require(path.is_file(), "input-missing")
    require(path.stat().st_nlink == 1, "hardlink-input")
    require(path.stat().st_size <= (MAX_ZIP if path.suffix.lower() == ".zip" else MAX_INPUT), "input-oversize")
    if path.suffix.lower() == ".zip":
        try:
            with zipfile.ZipFile(path) as archive:
                entries = archive.infolist()
                names = [e.filename for e in entries]
                require(len(names) == 3 and set(names) == {"context.json", "manifest.json", "README.txt"}, "zip-members")
                caps = {"context.json": MAX_INPUT, "manifest.json": 65536, "README.txt": 16384}
                for e in entries:
                    require(e.file_size <= caps[e.filename] and not e.flag_bits & 1 and stat.S_IFMT(e.external_attr >> 16) != stat.S_IFLNK, "zip-entry")
                with archive.open("context.json") as stream:
                    raw = stream.read(MAX_INPUT + 1)
                require(len(raw) <= MAX_INPUT, "input-oversize")
                metadata = STRICT_JSON.decode(archive.read("manifest.json").decode("utf-8-sig"))
                item = metadata.get("files", {}).get("context.json", {})
                require(item.get("sha256") == sha(raw) and item.get("bytes") == len(raw), "zip-integrity")
                data = STRICT_JSON.decode(raw.decode("utf-8-sig"))
                if data.get("schemaVersion") == EXPORT_SCHEMA:
                    context = metadata.get("context", {})
                    require(all(context.get(k) == data.get(k) for k in ("exportId", "schemaVersion", "snapshot", "exportedAt")) and all(metadata.get(k) == data.get(k) for k in ("exportId", "schemaVersion")), "zip-context-mismatch")
                return data
        except (zipfile.BadZipFile, RuntimeError, UnicodeError, ValueError, AttributeError, TypeError, RecursionError) as exc:
            if isinstance(exc, SupportError):
                raise
            raise SupportError("zip-invalid") from exc
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAX_INPUT + 1)
        require(len(raw) <= MAX_INPUT, "input-oversize")
        return STRICT_JSON.decode(raw.decode("utf-8-sig"))
    except (UnicodeError, ValueError, RecursionError) as exc:
        if isinstance(exc, SupportError):
            raise
        raise SupportError("json-invalid") from exc


def source_pins(root, paths=SOURCE_PATHS):
    pins = []
    for name in paths:
        path = root / name
        reject_reparse(path)
        pins.append({"path": name, "sha256": sha(path.read_bytes()) if path.is_file() else None})
    return {"schemaVersion": SCHEMA, "checkedAt": stamp(), "sources": pins}


def check_pins(root, pins, expected_paths=SOURCE_PATHS):
    require(isinstance(pins, dict) and pins.get("schemaVersion") == SCHEMA and isinstance(pins.get("sources"), list), "pin-shape")
    paths = [p.get("path") for p in pins["sources"] if isinstance(p, dict)]
    require(len(paths) == len(expected_paths) == len(pins["sources"]) and set(paths) == set(expected_paths), "pin-set")
    rows = []
    for old in pins["sources"]:
        require(old["path"] in SOURCE_PATHS and (old.get("sha256") is None or isinstance(old["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", old["sha256"])), "pin-path-or-digest")
        now = source_pins(root, [old["path"]])["sources"][0]
        rows.append(dict(now, status="CURRENT" if now["sha256"] == old["sha256"] else "DRIFT"))
    return {"status": "DRIFT" if any(r["status"] == "DRIFT" for r in rows) else "UNKNOWN" if any(r["sha256"] is None for r in rows) else "CURRENT", "sources": rows,
            "runtimeFreshness": "NOT_OBSERVED"}


def write_new_group(out, files):
    # Check every conflict before writing anything; never replace an existing file.
    for name, data in files.items():
        path = out / name
        reject_reparse(path)
        require(not path.exists() or (path.is_file() and path.stat().st_size == len(data) and path.read_bytes() == data), "output-conflict")
    out.mkdir(parents=True, exist_ok=True)
    created = []
    try:
        for name, data in files.items():
            path = out / name
            reject_reparse(path)
            if not path.exists():
                with path.open("xb") as stream:
                    created.append((path, data))
                    stream.write(data)
    except OSError:
        # Only remove bytes created by this call, never a foreign replacement.
        for path, data in reversed(created):
            reject_reparse(path)
            if path.exists() and path.stat().st_size <= len(data) and data.startswith(path.read_bytes()):
                path.unlink()
        raise


def setup(root, out_rel):
    out = output_path(root, out_rel)
    files = {"case-manifest.json": encode(case_manifest()),
             "prompts.json": encode([{"text": q, "attachment": False} for q in QUESTIONS]),
             "README.md": b"# Browser10 offline support\n\nSYNTHETIC examples are not product evidence. No submissions or installations.\nUse python -B scripts/browser10_support_setup.py --help.\nNative export owner/request/run/body-dispatch joins may be UNKNOWN/NOT_OBSERVED.\n"}
    if not (out / "source-pins.json").exists():
        files["source-pins.json"] = encode(source_pins(root))
    if not (out / "checkpoint.json").exists():
        files["checkpoint.json"] = encode({"schemaVersion": SCHEMA, "status": "NOT_RUN", "automaticRetry": False})
    write_new_group(out, files)
    return {"schemaVersion": SCHEMA, "status": "PREPARED", "out": out_rel, "turns": 10, "productPass": False, "generationCalls": 0}


def native_inventory(data, session):
    require(isinstance(data.get("snapshot"), dict) and isinstance(data.get("sessions"), list), "export-shape")
    selected = data["snapshot"].get("selectedSessionIds")
    require(isinstance(selected, list) and len(selected) == 1 and ref(selected[0]) == ref(session), "ids-mismatch")
    sessions = data["sessions"]
    require(len(sessions) == 1 and ref(sessions[0].get("sessionId")) == ref(session), "ids-mismatch")
    messages, turns = sessions[0].get("messages", []), sessions[0].get("turns", [])
    require(isinstance(messages, list) and isinstance(turns, list) and len(messages) <= 10000 and len(turns) <= 10000, "export-shape")
    inventory = []
    for row in messages:
        require(isinstance(row, dict), "export-shape")
        body = row.get("content", "")
        require(isinstance(body, str) and len(body.encode("utf-8")) <= 1024 * 1024, "message-oversize")
        inventory.append({"messageId": ref(row.get("messageId")), "role": row.get("role") if row.get("role") in ("user", "assistant") else "UNKNOWN", "bodySHA": sha(body.encode("utf-8")), "bodyBytes": len(body.encode("utf-8"))})
    return {"messageCount": len(messages), "turnCount": len(turns), "messages": inventory,
            "pointers": [{"assistantMessageId": ref(t.get("assistantMessageId")), "snapshotId": ref(t.get("traceSnapshotId"))} for t in turns if isinstance(t, dict)],
            "diagnosticCompleteness": "PARTIAL", "ownerBinding": "UNKNOWN", "requestRunBinding": "NOT_OBSERVED",
            "automaticTurnAssignment": False, "actualProviderDispatch": "NOT_OBSERVED"}


def collect(data, *, run_label, session, owner, checkpoint=None, source_pins_sha=MISSING, resolve_terminal=False):
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", run_label), "run-label")
    require(ref(session) != MISSING and ref(owner) != MISSING, "selected-test-session-owner-required")
    manifest_sha = sha(encode(case_manifest()))
    identity = {"runLabel": run_label, "sessionRef": ref(session), "ownerRef": ref(owner), "manifestSHA": manifest_sha, "sourcePinsSHA": source_pins_sha}
    require(isinstance(data, dict), "input-shape")
    if checkpoint is not None:
        require(isinstance(checkpoint, dict) and checkpoint.get("schemaVersion") == SCHEMA and all(checkpoint.get(k) == v for k, v in identity.items()), "checkpoint-mismatch")
        require(checkpoint.get("synthetic") is (data.get("synthetic") is True), "checkpoint-mismatch")
    rows = {i: {"id": f"T{i:02}", "runtimeVerdict": "NOT_RUN", "binding": "UNKNOWN", "retentionVerdict": "UNKNOWN", "actualProviderDispatch": MISSING} for i in range(1, 11)}
    duplicate, late, dropped, resolved, seen = 0, 0, 0, 0, set()
    native = None
    if data.get("schemaVersion") == EXPORT_SCHEMA:
        native = native_inventory(data, session)
        events = []
    else:
        require(data.get("schemaVersion") == OBS_SCHEMA and isinstance(data.get("events"), list) and len(data["events"]) <= 1000, "observations-shape")
        events = data["events"]
    prior = checkpoint.get("cases", []) if checkpoint else []
    require(isinstance(prior, list) and len(prior) <= 10, "checkpoint-shape")
    # Reproject saved cases through the same field allowlist; checkpoint files are untrusted.
    for event in [*prior, *events]:
        require(isinstance(event, dict), "event-shape")
        n, attempt = event.get("turnNo"), event.get("attemptNo")
        require(type(n) is int and 1 <= n <= 10 and type(attempt) is int and 1 <= attempt <= 10, "turn-attempt-range")
        if event.get("runLabel") != run_label:
            late += 1
            continue
        for field, expected in (("sessionRef", session), ("ownerRef", owner)):
            require(ref(event.get(field)) in (MISSING, ref(expected)), "ids-mismatch")
        dropped += len(set(event) - EVENT_FIELDS)
        row = {k: ref(event.get(k)) for k in REF_FIELDS}
        row.update(id=f"T{n:02}", turnNo=n, attemptNo=attempt, runLabel=run_label,
                   terminalStatus=event.get("terminalStatus") if event.get("terminalStatus") in TERMINALS else "UNKNOWN",
                   actualProviderDispatch=MISSING, runtimeVerdict="NOT_PROVEN",
                   retentionVerdict="UNKNOWN",
                   reportedUsefulAnswerVerdict=event.get("usefulAnswerVerdict") if event.get("usefulAnswerVerdict") in STATES | {"PASS"} else "UNKNOWN")
        row["binding"] = "REPORTED_IDS" if all(row[k] != MISSING for k in ("sessionRef", "ownerRef", "assistantMessageId", "runId", "requestId")) else "UNKNOWN"
        for other in rows.values():
            if other.get("turnNo") != n:
                require(not any(row[k] != MISSING and row[k] == other.get(k) for k in ("requestId", "assistantMessageId")), "ids-reused-across-turns")
        # Models, build and dispatch records are hashes, not provider-auth evidence.
        for k in ("requestedModel", "routedModel", "actualModel", "servedBuild"):
            row[k + "Ref"] = ref(event.get(k))
        row["checkedAt"] = safe_time(event.get("checkedAt"))
        row["sources"] = []
        sources = event.get("sources", [])
        require(isinstance(sources, list) and len(sources) <= 100, "source-shape")
        for source in sources:
            require(isinstance(source, dict), "source-shape")
            same_request = row["requestId"] != MISSING and ref(source.get("requestId")) == row["requestId"]
            projected = {k: ref(source.get(k)) for k in ("sourceId", "spanId", "revision", "contentSHA", "requestId")}
            projected["binding"] = "REPORTED_SAME_REQUEST" if same_request else "UNKNOWN"
            projected["checkedAt"] = safe_time(source.get("checkedAt"))
            for k in STAGES:
                projected[k] = source.get(k) if same_request and source.get(k) in STATES else MISSING
            dropped += len(set(source) - set(projected) - {"binding"})
            row["sources"].append(projected)
        row["timings"] = {k: event.get("timings", {}).get(k) if isinstance(event.get("timings"), dict) and type(event["timings"].get(k)) in (int, float) and 0 <= event["timings"][k] <= 86400000 else "NOT_MEASURED" for k in ("queueMs", "searchMs", "generationMs", "firstBodyMs", "terminalMs")}
        canonical = sha(encode(row))
        if canonical in seen:
            duplicate += 1
            continue
        old = rows[n]
        if old["runtimeVerdict"] != "NOT_RUN":
            same_ids = all(old.get(k) == row.get(k) for k in (*REF_FIELDS, "attemptNo"))
            pending_to_final = old.get("terminalStatus") in ("disconnected", "timeout", "partial", "running") and row["terminalStatus"] in ("completed", "cancelled", "failed")
            require(same_ids and pending_to_final, "conflicting-turn")
            require(resolve_terminal, "terminal-resolution-required")
            resolved += 1  # Explicit offline receipt resolution; sends nothing.
        seen.add(canonical)
        rows[n] = row
    completed = 0
    for row in rows.values():
        if row.get("terminalStatus") == "completed" and row["binding"] == "REPORTED_IDS" and row.get("reportedUsefulAnswerVerdict") == "PASS":
            completed += 1
        else:
            break
    inspect = any(r.get("terminalStatus") in TERMINALS - {"completed"} or r["runtimeVerdict"] != "NOT_RUN" and (r["binding"] == "UNKNOWN" or r.get("reportedUsefulAnswerVerdict") != "PASS") or r.get("terminalStatus") == "UNKNOWN" for r in rows.values())
    if any(rows[i]["runtimeVerdict"] != "NOT_RUN" for i in range(completed + 2, 11)):
        inspect = True  # A missing predecessor cannot be repaired by timestamp ordering.
    saved = []
    for row in rows.values():
        if row["runtimeVerdict"] != "NOT_RUN":
            saved_row = {k: row[k] for k in (*REF_FIELDS, "turnNo", "attemptNo", "runLabel", "terminalStatus", "sources", "timings", "checkedAt")}
            for k in ("requestedModel", "routedModel", "actualModel", "servedBuild"):
                saved_row[k] = row[k + "Ref"]
            saved_row["usefulAnswerVerdict"] = row["reportedUsefulAnswerVerdict"]
            saved.append(saved_row)
    cp = {"schemaVersion": SCHEMA, **identity, "synthetic": data.get("synthetic") is True,
          "nextTurn": f"T{completed + 1:02}" if completed < 10 else None,
          "requiresInspection": inspect, "automaticRetry": False, "cases": saved,
          "nextAction": "INSPECT_OWNED_SESSION_TERMINAL_NO_RESUBMIT" if inspect else "READY_FOR_MANUAL_EVIDENCE_CAPTURE_NO_SUBMISSION"}
    return {"schemaVersion": SCHEMA, **identity, "synthetic": data.get("synthetic") is True,
            "productPass": False, "overallVerdict": "NOT_RUN" if not saved else "NOT_PROVEN",
            "duplicateEvents": duplicate, "lateOldRunEvents": late, "droppedFields": dropped,
            "offlineTerminalResolutions": resolved,
            "cases": list(rows.values()), "exportInventory": native, "checkpoint": cp,
            "evidenceLimit": "Reported IDs/statuses and export hashes do not prove browser, OAuth or body dispatch",
            "focusedTests": ["python -B -m unittest discover -s scripts -p test_browser10_support_setup.py", "node --test src/test/js/chat-conversation-export.test.cjs", "node --test scripts/chat_ui_reasoning_restore_contract_tests.js"]}


def preflight(root, health_read=False):
    result = {"schemaVersion": SCHEMA, "status": "OFFLINE_PREFLIGHT", "productPass": False,
              "baseURL": "http://127.0.0.1:18180/chat", "outputBoundary": OUT_BASE,
              "networkAttempted": health_read, "health": {"status": "NOT_RUN"},
              "browserConnection": "NOT_OBSERVED_BY_OFFLINE_SCRIPT_USE_EXISTING_CUA_TOOL",
              "schemas": [SCHEMA, OBS_SCHEMA, EXPORT_SCHEMA], "liveExecution": "NOT_SUPPORTED",
              "tools": {"python": True, "node": bool(shutil.which("node"))},
              "existingOfflineCommands": {p: (root / p).is_file() for p in ["scripts/chat_browser10_assist.py", "scripts/chat_session_debug_export.py", "scripts/chat_export_leak_scan.py", "scripts/chat_ui_reasoning_restore_contract_tests.js", "src/test/js/chat-conversation-export.test.cjs"]}}
    if health_read:
        values = {}
        for path in ("/actuator/health", "/chat"):
            connection = http.client.HTTPConnection("127.0.0.1", 18180, timeout=3)
            try:
                connection.request("GET", path, headers={"Accept": "text/html,application/json"})
                response = connection.getresponse()
                response.read(32768)  # bounded discard; no cookies, auth, redirects, or logs.
                values[path] = {"httpStatus": response.status, "status": "OBSERVED"}
            except (OSError, http.client.HTTPException):
                values[path] = {"status": "UNREACHABLE_OR_TIMEOUT"}
            finally:
                connection.close()
        result["health"] = values
    return result


def summary(report):
    label = "SYNTHETIC — NOT PRODUCT EVIDENCE" if report["synthetic"] else "IMPORTED METADATA — RUNTIME NOT PROVEN"
    return (f"# Browser10 support result\n\n{label}\n\nOverall: {report['overallVerdict']}; productPass=false.\n"
            f"Duplicate events: {report['duplicateEvents']}; old-run excluded: {report['lateOldRunEvents']}; dropped fields: {report['droppedFields']}.\n"
            f"Resume: {report['checkpoint']['nextAction']}; nextTurn={report['checkpoint']['nextTurn']}.\n\n"
            "| Case | Runtime | Binding | Terminal |\n|---|---|---|---|\n" +
            "\n".join(f"| {r['id']} | {r['runtimeVerdict']} | {r['binding']} | {r.get('terminalStatus', 'NOT_RUN')} |" for r in report["cases"]) +
            "\n\nNative exports omit exact historical request/run and raw prompt/retrieval. Missing evidence remains UNKNOWN/NOT_OBSERVED.\n").encode("utf-8")


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("preflight", "setup", "collect", "synthetic", "check-source"))
    p.add_argument("--root", type=Path, default=ROOT)
    p.add_argument("--out", default=OUT_BASE + "/prepared")
    p.add_argument("--input", type=Path)
    p.add_argument("--checkpoint", type=Path)
    p.add_argument("--pins", type=Path)
    p.add_argument("--session-id")
    p.add_argument("--owner-ref")
    p.add_argument("--run-label", default="synthetic-demo")
    p.add_argument("--health-read", action="store_true", help="Explicit fixed-loopback read only; two GETs, no generation")
    p.add_argument("--resolve-terminal", action="store_true", help="Explicitly accept a same-ID final receipt after an unresolved checkpoint; never resubmits")
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        root = args.root.absolute()
        reject_reparse(root)
        if args.command == "preflight":
            report = preflight(root, args.health_read)
        elif args.command == "setup":
            report = setup(root, args.out)
        elif args.command == "check-source":
            require(args.pins is not None, "pins-required")
            report = check_pins(root, load_input(args.pins))
        else:
            if args.command == "synthetic":
                require(args.input is None and args.checkpoint is None, "synthetic-input-forbidden")
                args.session_id, args.owner_ref = "synthetic-session", "synthetic-owner"
                data = {"schemaVersion": OBS_SCHEMA, "synthetic": True, "events": [{"turnNo": 1, "attemptNo": 1, "runLabel": args.run_label, "ownerRef": args.owner_ref, "sessionRef": args.session_id, "assistantMessageId": "synthetic-message", "requestId": "synthetic-request", "runId": "synthetic-run", "terminalStatus": "cancelled"}]}
            else:
                require(args.input is not None, "input-required")
                data = load_input(args.input)
            pins = load_input(args.pins) if args.pins else None
            freshness = check_pins(root, pins) if pins else {"status": "UNKNOWN", "runtimeFreshness": "NOT_OBSERVED"}
            require(freshness["status"] != "DRIFT", "source-drift")
            report = collect(data, run_label=args.run_label, session=args.session_id, owner=args.owner_ref, checkpoint=load_input(args.checkpoint) if args.checkpoint else None, source_pins_sha=sha(encode(pins)) if pins else MISSING, resolve_terminal=args.resolve_terminal)
            report["sourceFreshness"] = freshness
            out = output_path(root, args.out)
            files = {"result.json": encode(report), "resume-checkpoint.json": encode(report["checkpoint"]), "summary.md": summary(report)}
            if args.command == "synthetic":
                files["synthetic-observations.json"] = encode(data)
            write_new_group(out, files)
        print(json.dumps(report if args.command in ("preflight", "check-source") else {"schemaVersion": SCHEMA, "status": report.get("status", report.get("overallVerdict")), "out": args.out, "synthetic": report.get("synthetic", False), "productPass": False}, ensure_ascii=False))
        return 4 if report.get("status") == "DRIFT" else 0
    except (SupportError, OSError, TypeError, KeyError, AttributeError, UnicodeError, OverflowError, RecursionError) as exc:
        reason = str(exc) if isinstance(exc, SupportError) else "io-or-shape-error"
        print(json.dumps({"schemaVersion": SCHEMA, "status": "BLOCKED", "reason": reason, "productPass": False}))
        return 2


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
