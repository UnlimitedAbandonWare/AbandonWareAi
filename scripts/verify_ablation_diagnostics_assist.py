#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ablation/Z 진단 복구 지원 — 6대 숨은 함정(Invisible Eye) 정적 점검기.

PASTE_DEVIN_ABLATION_DIAGNOSTICS_ASSIST_20261007 / WP1.
읽기 전용: 제품 소스를 스캔해 Codex 브리프가 지목한 6개 구조적 함정의
현재 상태를 보고한다. 소스는 수정하지 않는다.

상태 어휘:
  PITFALL_PRESENT   함정 패턴이 라이브 소스에 존재 (Codex 수정 대상)
  PARTIAL           일부만 완화됨 / 잔여 간격 존재
  RESOLVED          함정이 이미 해소됨 (브리프보다 소스가 최신)
  NOTE              버그가 아니라 해석 위험 — 의미 경고
  UNKNOWN           정적으로 판정 불가
  FILE_MISSING      대상 파일 부재
  ERROR             점검기 내부 실패

사용:
  python -B scripts/verify_ablation_diagnostics_assist.py            # JSON 출력
  python -B scripts/verify_ablation_diagnostics_assist.py --report   # MD 표 + 산출물 저장
  python -B scripts/verify_ablation_diagnostics_assist.py --strict   # 함정 존재 시 exit 1
  python -B scripts/verify_ablation_diagnostics_assist.py --write-baseline <path>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.ablation-diagnostics-assist.v1"
TASK_ID = "devin-ablation-diagnostics-assist-458cfecf"
BRIEF_ID = "PASTE_CODEX_ABLATION_DIAGNOSTICS_JEV_EVALUATION_20261007"
DEFAULT_OUT_DIR = Path("data/agent-handoff/codex-ablation-jev-assist")

F_CONTROLLER = "main/java/com/example/lms/api/ChatApiController.java"
F_RESTORER = "main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java"
F_PERSISTER = "main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java"
F_STORE = "main/java/com/example/lms/trace/TraceSnapshotStore.java"
F_EXPORTER = "main/java/com/example/lms/trace/TraceSnapshotExporter.java"
F_BUILDER = "main/java/com/example/lms/service/trace/TraceHtmlBuilder.java"
F_TAA = "main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java"
F_TRACKER = "main/java/com/example/lms/trace/AblationContributionTracker.java"
F_ASPECT = "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"
F_JEV = "main/java/com/example/lms/assist/JevGatewayClient.java"
F_UI = "main/resources/static/js/chat-trace-ui.js"
F_SIGNAL = "main/java/com/example/lms/api/ChatStreamSignalBuilder.java"

# Codex 브리프 §핵심 소스 무결성 스냅샷 (2026-10-07 02:50 UTC 관측값, SHA256-12)
EXPECTED_SHA12 = {
    F_CONTROLLER: "fb8a16e64398",
    F_BUILDER: "e337ef08a386",
    F_TAA: "4030f94b2cd5",
    F_TRACKER: "6f17561f34ef",
    F_PERSISTER: "0c4da26abc99",
    F_RESTORER: "5b4d6e6f17fe",
    F_STORE: "8699edae3245",
    F_UI: "5cab54941325",
    F_ASPECT: "fb75dcb3e009",
    F_JEV: "215ec7cd7eeb",
}

# durable projection에 필요한 typed TAA 키 → 예상 allowlist 집합
REQUIRED_TAA_KEYS = {
    "DETAIL_FLAGS": ["ablation.finalized"],
    "DETAIL_COUNTS": ["taa.candidate.count", "taa.beam.count"],
    "DETAIL_NUMBERS": ["taa.outcome.risk", "ablation.score.final"],
    "DETAIL_LABELS": ["taa.version", "taa.outcome",
                      "taa.topContributor.id", "taa.topContributor.group"],
}


def _read(root: Path, rel: str):
    path = root / rel
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8", errors="replace")


def _sha12(root: Path, rel: str):
    path = root / rel
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()[:12]


def _line_no(text: str, needle: str, start: int = 0):
    idx = text.find(needle, start)
    if idx < 0:
        return None
    return text.count("\n", 0, idx) + 1


def _all_line_nos(text: str, pattern: str):
    out = []
    for m in re.finditer(pattern, text):
        out.append(text.count("\n", 0, m.start()) + 1)
    return out


def _result(check_id, name, status, summary, evidence, guidance):
    return {
        "id": check_id,
        "name": name,
        "status": status,
        "summary": summary,
        "evidence": evidence,
        "guidance": guidance,
    }


def check_html_cap(root: Path):
    """E3: 60k cap — cap 절단 '뒤' marker를 붙이면 최종 길이가 cap을 초과."""
    src = _read(root, F_STORE)
    if src is None:
        return _result("E3", "html_cap", "FILE_MISSING",
                       "TraceSnapshotStore.java 부재", {}, "파일 경로 재확인")
    ev = {}
    ev["cap_default_line"] = _line_no(src, "trace.snapshot.html.max-len:60000")
    ev["html_max_len_field"] = _line_no(src, "private int htmlMaxLen")
    # 절단 구문: html = html.substring(0, <BOUND>) + <marker>
    #   BOUND에 marker 길이 차감이 없으면 최종 길이 = cap + markerLen (함정)
    #   차감이 있으면 marker 포함 최종 길이 <= cap (해소)
    cut_stmt = re.search(
        r"html\.substring\(0,\s*(.+?)\)\s*\+\s*(?:marker|\"\\n<!-- truncated -->\")",
        src)
    ev["cut_stmt_line"] = (
        src.count("\n", 0, cut_stmt.start()) + 1 if cut_stmt else None)
    bound = cut_stmt.group(1) if cut_stmt else ""
    ev["cut_bound_expr"] = bound
    marker_inside = bool(cut_stmt and re.search(r"-\s*\S", bound))
    ui = _read(root, F_UI) or ""
    ev["ui_max_html_line"] = _line_no(ui, "MAX_HTML = 60000")
    ev["ui_reject_line"] = _line_no(ui, "source.length > MAX_HTML")
    marker_len = len("\n<!-- truncated -->")  # 19
    ev["overshoot_chars"] = marker_len

    if marker_inside:
        status = "RESOLVED"
        summary = ("marker 포함 길이 안으로 절단 (%s) — 최종 길이 <= cap"
                   % bound)
    elif cut_stmt:
        status = "PITFALL_PRESENT"
        summary = ("substring(cap) 뒤 marker 부착: 최종 %d+%d=%d > cap — "
                   "UI(MAX_HTML=60000)가 거절 가능"
                   % (60000, marker_len, 60000 + marker_len))
    else:
        status = "UNKNOWN"
        summary = "cap 절단 패턴을 찾지 못함 (소스 드리프트 가능)"
    return _result(
        "E3", "html_cap", status, summary, ev,
        "수정 방향: marker 포함 최종 길이가 cap 이내가 되도록 서버에서 보장 "
        "(예: cap - markerLen 만큼만 절단). UI cap 상향/우회로 해결 금지. "
        "59,999/60,000/60,001 경계와 cap override는 Codex WP-R2 계약.")


def _set_block(src: str, name: str):
    """private static final Set<String> NAME = Set.of( ... ); 블록 추출."""
    m = re.search(
        r"Set<String>\s+" + re.escape(name) + r"\s*=\s*Set\.of\((.*?)\);",
        src, re.S)
    return m.group(1) if m else None


def check_metadata_budget(root: Path):
    """E2-2: durable projection 예산(2048B/16필드, detail 8192B/96필드)과
    typed TAA allowlist 커버리지."""
    src = _read(root, F_RESTORER)
    if src is None:
        return _result("E2-2", "metadata_budget", "FILE_MISSING",
                       "ChatTraceMetaMessageRestorer.java 부재", {},
                       "파일 경로 재확인")
    ev = {"constants": {}, "required_keys": {}, "allowlist_wildcard": False}
    for name in ("MAX_DURABLE_PROJECTION_BYTES", "MAX_DURABLE_PROJECTION_FIELDS",
                 "MAX_DURABLE_PROJECTION_B64_CHARS", "MAX_DETAIL_BYTES",
                 "MAX_DETAIL_FIELDS", "MAX_TRACE_META_B64_CHARS"):
        m = re.search(name + r"\s*=\s*([0-9_]+)", src)
        ev["constants"][name] = (
            int(m.group(1).replace("_", "")) if m else None)
        ev["constants"][name + "_line"] = (
            src.count("\n", 0, m.start()) + 1 if m else None)

    missing = []
    for set_name, keys in REQUIRED_TAA_KEYS.items():
        block = _set_block(src, set_name)
        for key in keys:
            present = block is not None and ('"%s"' % key) in block
            ev["required_keys"]["%s:%s" % (set_name, key)] = present
            if not present:
                missing.append(key)
    # wildcard 허용 금지 검사 (taa.* / ablation.* 전체 수용 패턴이 없어야 함)
    ev["allowlist_wildcard"] = bool(
        re.search(r'startsWith\("taa\."\)|startsWith\("ablation\."\)', src))
    ev["projection_field_cap_line"] = _line_no(
        src, "MAX_DETAIL_FIELDS : MAX_DURABLE_PROJECTION_FIELDS")

    if ev["constants"]["MAX_DURABLE_PROJECTION_BYTES"] is None:
        status = "UNKNOWN"
        summary = "예산 상수를 찾지 못함"
    elif missing:
        status = "PARTIAL" if len(missing) < 9 else "PITFALL_PRESENT"
        summary = "durable allowlist에 누락된 typed TAA 키: " + ", ".join(missing)
    else:
        status = "RESOLVED"
        summary = ("typed TAA 9키가 allowlist에 존재; 봉투 예산 "
                   "durable %sB/%s필드, detail %sB/%s필드"
                   % (ev["constants"]["MAX_DURABLE_PROJECTION_BYTES"],
                      ev["constants"]["MAX_DURABLE_PROJECTION_FIELDS"],
                      ev["constants"]["MAX_DETAIL_BYTES"],
                      ev["constants"]["MAX_DETAIL_FIELDS"]))
    return _result(
        "E2-2", "metadata_budget", status, summary, ev,
        "prefix 추가만으로 보존이 보장되지 않는다 — v1/v2 봉투는 2,048B/16필드, "
        "v3 detail 봉투는 8,192B/96필드/10,924B64. 기존 diagnostics와의 "
        "필드 우선순위·밀집 입력 검사 필요. taa.*/ablation.* wildcard 금지.")


def check_threadlocal_timing(root: Path):
    """E2: extraMeta 캡처(전) → TraceStore.clear → render(중 TAA 신규 기록)
    → snapshot metadata = renderedTrace.metadata() 의 시점 단절."""
    src = _read(root, F_CONTROLLER)
    if src is None:
        return _result("E2", "threadlocal_timing", "FILE_MISSING",
                       "ChatApiController.java 부재", {}, "파일 경로 재확인")
    ev = {"stream": {}, "sync": {}, "builder": {}, "taa_service": {}}
    lines = src.split("\n")
    # persist 호출 지점을 앵커로 뒤쪽 600줄 안에서 가장 가까운 마커를 찾는다
    # (5,000줄 컨트롤러의 앞쪽 동일 패턴에 속지 않기 위함)
    persist_sites = [i for i, ln in enumerate(lines)
                     if "ChatTraceSnapshotPointerPersister.persist(" in ln]
    markers = {
        "extraMeta_getAll": "TraceStore.getAll()",
        "render": "buildSplitPanelWithMetadata",
        "rendered_metadata": "renderedTrace.metadata()",
    }
    for site in persist_sites:
        lane = "stream" if "persistedTraceTurnId" in lines[site] else "sync"
        if ev[lane]:
            continue
        start = max(0, site - 600)
        window = lines[start:site]
        found = {"persist": site + 1}
        for key, needle in markers.items():
            for j in range(len(window) - 1, -1, -1):
                if needle in window[j]:
                    found[key] = start + j + 1
                    break
        # getAll과 render 사이의 clear만 pre-render clear로 인정
        ga, rn = found.get("extraMeta_getAll"), found.get("render")
        if ga and rn:
            for k in range(ga - 1, rn - 1):
                if "TraceStore.clear()" in lines[k]:
                    found["clear_between"] = k + 1
                    break
        ev[lane].update(found)
    ev["stream"]["stash"] = _line_no(src, "stashStreamTraceSnapshot")

    builder = _read(root, F_BUILDER) or ""
    ev["builder"]["attribution_meta_merge"] = _line_no(
        builder, "snapshotMeta.putAll(attributionMeta)")
    ev["builder"]["taa_analyze"] = _line_no(
        builder, "traceAblationAttributionService.analyze")
    ev["builder"]["render_time_put"] = _line_no(
        builder, 'TraceStore.put("traceHtml.ablation.suppressed.render"')
    # render 중 새 ThreadLocal에만 쓰이고 metadata로 전파되지 않는 키 계열
    taa = _read(root, F_TAA) or ""
    render_only_puts = _all_line_nos(
        taa, r'TraceStore\.put\("taa\.(?:error|bestPath|contribution|web\.)\.')
    ev["taa_service"]["render_only_put_lines"] = render_only_puts

    captured = ev["stream"].get("extraMeta_getAll")
    render = ev["stream"].get("render")
    clear_between = ev["stream"].get("clear_between")
    order_ok = (captured and render and clear_between
                and captured < clear_between < render)
    merged = bool(ev["builder"]["attribution_meta_merge"]) and bool(
        ev["stream"].get("rendered_metadata"))
    if not order_ok:
        status = "UNKNOWN"
        summary = "stream 경로 캡처→clear→render 순서를 정적 확인하지 못함"
    elif merged:
        status = "PARTIAL"
        summary = ("캡처(L%s)→clear(L%s)→render(L%s) 후 snapMeta="
                   "renderedTrace.metadata()(L%s) — typed TAA는 attributionMeta "
                   "병합(L%s)으로 도달. 잔여: render 중 새 ThreadLocal에만 쓰이는 "
                   "taa.error.*/suppressed.* 등은 metadata 미포함"
                   % (captured, clear_between, render,
                      ev["stream"]["rendered_metadata"],
                      ev["builder"]["attribution_meta_merge"]))
    else:
        status = "PITFALL_PRESENT"
        summary = "render 결과 metadata가 snapshot에 전파되지 않는 구조"
    return _result(
        "E2", "threadlocal_timing", status, summary, ev,
        "최소 복구 원칙: 같은 요청의 TAA 결과를 한 번 계산해 HTML·저장이 "
        "공유 — render 후 전체 TraceStore 무차별 merge 금지, 타 요청 recent "
        "error로 빈 결과 채우기 금지. attributionMeta typed 요약이 이미 존재.")


def check_extremez_aliases(root: Path):
    """E6: extremeZ.(camel) vs extremez.(lower) 대소문자 일관성."""
    exporter = _read(root, F_EXPORTER)
    aspect = _read(root, F_ASPECT)
    if exporter is None or aspect is None:
        return _result("E6", "extremez_aliases", "FILE_MISSING",
                       "TraceSnapshotExporter/ExtremeZBurstAspect 부재", {},
                       "파일 경로 재확인")
    ev = {}
    ev["exporter_camel_prefix_line"] = _line_no(exporter, '"extremeZ."')
    ev["exporter_lower_prefix_line"] = _line_no(exporter, '"extremez."')
    ev["exporter_case_sensitive_match"] = _line_no(
        exporter, "key.startsWith(prefix)")
    ev["aspect_lower_write_count"] = len(
        re.findall(r'trace\("extremez\.', aspect))
    ev["aspect_camel_plan_reads"] = _all_line_nos(
        aspect, r'plan(?:Int|Long|Boolean|String)?\("extremeZ\.')
    signal = _read(root, F_SIGNAL) or ""
    ev["signal_builder_expand"] = _line_no(signal, "expand.extremeZ")
    ev["signal_builder_lower"] = _line_no(signal, '"extremez.')

    camel_only = (ev["exporter_camel_prefix_line"] is not None
                  and ev["exporter_lower_prefix_line"] is None)
    if camel_only and ev["aspect_lower_write_count"] > 0:
        status = "PITFALL_PRESENT"
        summary = ("producer는 extremez.*(lower) %d건 기록하지만 exporter "
                   "ALLOWED_PREFIXES는 extremeZ.(camel)만 case-sensitive 허용 "
                   "(L%s) — exporter가 신호를 누락"
                   % (ev["aspect_lower_write_count"], ev["exporter_camel_prefix_line"]))
    elif not camel_only:
        status = "RESOLVED"
        summary = "exporter가 두 namespace를 모두 허용"
    else:
        status = "UNKNOWN"
        summary = "producer/exporter 패턴 판정 불가"
    return _result(
        "E6", "extremez_aliases", status, summary, ev,
        "계획값 routing.executionPlan.primaryMode=EXTREMEZ, plan 설정 extremeZ.*, "
        "실행 관측 extremez.* 는 서로 다른 namespace. source-proven producer/시점과 "
        "연결되지 않은 alias는 UNKNOWN 처리. 대문자 추측 하드코딩 금지.")


def check_extremez_activated_logic(root: Path):
    """E6-2: extremez.activated = merged.size() > baseSize — 문서 '증가' 의미이지
    trigger/실행 승인과 동의어가 아님."""
    src = _read(root, F_ASPECT)
    if src is None:
        return _result("E6-2", "extremez_activated_logic", "FILE_MISSING",
                       "ExtremeZBurstAspect.java 부재", {}, "파일 경로 재확인")
    ev = {}
    m = re.search(r'trace\("extremez\.activated",\s*([^;]+)\);', src)
    ev["activated_line"] = src.count("\n", 0, m.start()) + 1 if m else None
    ev["activated_expr"] = m.group(1).strip() if m else None
    ev["activation_reason_line"] = _line_no(src, '"extremez.activation.reason"')
    ev["skip_reason_lines"] = _all_line_nos(src, r'traceSkip\(')
    ev["trigger_line"] = _line_no(src, '"extremez.risk.trigger"')
    doc_growth = bool(m and "merged.size() > baseSize" in m.group(1))
    ev["means_doc_growth"] = doc_growth
    summary = ("extremez.activated = %s (L%s) — 실행했으나 증가 0이면 false, "
               "trigger 없이 true일 수도 없음. 문서 증가 관측이며 발동/승인 "
               "동의어 아님" % (ev["activated_expr"], ev["activated_line"]))
    return _result(
        "E6-2", "extremez_activated_logic",
        "NOTE" if doc_growth else "UNKNOWN",
        summary, ev,
        "표시/판정 시 '계획 EXTREMEZ'·'trigger 관측'·'실행'·'activated(증가)'를 "
        "분리 표기. 실행관측+추가0 은 activated=false. declared/enabled만으로 "
        "ON 승격 금지.")


def check_jev_cost_path(root: Path):
    """E4: Jev 비용 추출 — 공식 providerMetadata.gateway.cost 지원 여부."""
    src = _read(root, F_JEV)
    if src is None:
        return _result("E4", "jev_cost_path", "FILE_MISSING",
                       "JevGatewayClient.java 부재", {}, "파일 경로 재확인")
    ev = {}
    ev["legacy_path_line"] = _line_no(src, 'path("gateway").path("cost")')
    ev["provider_metadata_line"] = _line_no(src, "providerMetadata")
    ev["cost_method_line"] = _line_no(
        src, "private static Optional<java.math.BigDecimal> cost(")
    legacy = ev["legacy_path_line"] is not None
    official = ev["provider_metadata_line"] is not None
    if legacy and official:
        status = "RESOLVED"
        summary = ("legacy gateway.cost + 공식 providerMetadata.gateway.cost "
                   "이중 지원")
    elif legacy:
        status = "PITFALL_PRESENT"
        summary = ("cost() (L%s)는 legacy gateway.cost만 읽음 — 공식 "
                   "providerMetadata.gateway.cost 응답에서 비용은 UNKNOWN 처리됨"
                   % ev["legacy_path_line"])
    else:
        status = "UNKNOWN"
        summary = "비용 추출 경로를 찾지 못함"
    return _result(
        "E4", "jev_cost_path", status, summary, ev,
        "비용 누락은 UNKNOWN이며 0원으로 보정 금지. 최소 파서 수정만 WP-J1 "
        "옵션. probability와 공식 confidence는 다른 필드로 유지.")


CHECKS = [
    ("E2", "threadlocal_timing", check_threadlocal_timing),
    ("E3", "html_cap", check_html_cap),
    ("E2-2", "metadata_budget", check_metadata_budget),
    ("E6", "extremez_aliases", check_extremez_aliases),
    ("E6-2", "extremez_activated_logic", check_extremez_activated_logic),
    ("E4", "jev_cost_path", check_jev_cost_path),
]

STATUS_ORDER = ["PITFALL_PRESENT", "PARTIAL", "RESOLVED", "NOTE",
                "UNKNOWN", "FILE_MISSING", "ERROR"]


def baseline_snapshot(root: Path):
    files = {}
    for rel, expected in EXPECTED_SHA12.items():
        live = _sha12(root, rel)
        files[rel] = {
            "sha256_12": live,
            "expected_sha12": expected,
            "match": (live == expected) if live else None,
            "exists": live is not None,
        }
    return {
        "schemaVersion": "awx.ablation-baseline-snapshot.v1",
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "capturedBy": TASK_ID,
        "briefRef": BRIEF_ID,
        "expectedSnapshotUtc": "2026-10-07T02:50:00Z",
        "note": "바이트 동일성 증거이지 실행/검증 증명이 아니다. "
                "Codex 패치 후 match=false는 정상 drift.",
        "files": files,
    }


def run_checks(root: Path, only=None):
    results = []
    for cid, name, fn in CHECKS:
        if only and cid != only and name != only:
            continue
        try:
            results.append(fn(root))
        except Exception as exc:  # 점검기 내부 실패도 결과로 격리
            results.append(_result(cid, name, "ERROR",
                                   "checker exception: %s" % exc, {}, ""))
    return results


def _summary(results):
    out = {s: 0 for s in STATUS_ORDER}
    for r in results:
        out[r["status"]] = out.get(r["status"], 0) + 1
    return out


def _md_table(results, summary):
    lines = [
        "| 검사 | 함정 | 상태 | 요약 |",
        "|---|---|---|---|",
    ]
    for r in results:
        ev = r.get("evidence") or {}
        key_lines = [str(v) for k, v in ev.items()
                     if k.endswith("_line") and v]
        loc = "L" + ",L".join(key_lines[:3]) if key_lines else "-"
        lines.append("| %s | %s | %s | %s (%s) |" % (
            r["id"], r["name"], r["status"],
            r["summary"].replace("|", "\\|"), loc))
    lines.append("")
    lines.append("summary: " + ", ".join(
        "%s=%d" % (k, v) for k, v in summary.items() if v))
    return "\n".join(lines)


def main(argv=None):
    for _s in (sys.stdout, sys.stderr):  # cp949 콘솔에서도 출력이 죽지 않게
        try:
            _s.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".", help="프로젝트 루트 (기본: cwd)")
    ap.add_argument("--report", action="store_true",
                    help="Markdown 표 출력 + 산출물 저장")
    ap.add_argument("--strict", action="store_true",
                    help="PITFALL_PRESENT 하나라도 있으면 exit 1")
    ap.add_argument("--check", help="단일 검사만 실행 (id 또는 name)")
    ap.add_argument("--write-baseline", metavar="PATH",
                    help="10개 추적 파일의 SHA256-12 baseline JSON 저장")
    ap.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR),
                    help="--report 산출물 디렉터리")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if args.write_baseline:
        snap = baseline_snapshot(root)
        out = Path(args.write_baseline)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(snap, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")
        matched = sum(1 for f in snap["files"].values() if f["match"])
        print(json.dumps({"baseline": str(out), "tracked": len(snap["files"]),
                          "sha12Match": matched}, ensure_ascii=False))
        return 0

    results = run_checks(root, only=args.check)
    summary = _summary(results)
    report = {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "generatedBy": TASK_ID,
        "root": str(root),
        "briefRef": BRIEF_ID,
        "note": "정적 점검 결과이지 런타임 재현/원인 확정이 아니다. "
                "제품 소스 diff 0.",
        "checks": results,
        "summary": summary,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        md = _md_table(results, summary)
        print(md)
        print()
        out_dir = Path(args.out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "verify_report.json").write_text(
            text + "\n", encoding="utf-8")
        (out_dir / "verify_report.md").write_text(
            md + "\n\n```json\n" + text + "\n```\n", encoding="utf-8")
        print("saved: %s" % (out_dir / "verify_report.json"))
        print("saved: %s" % (out_dir / "verify_report.md"))
    else:
        print(text)
    if args.strict and summary.get("PITFALL_PRESENT", 0) > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
