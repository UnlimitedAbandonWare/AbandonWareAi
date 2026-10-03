#!/usr/bin/env python3
"""settings_ux_acceptance_check.py — Codex 설정 UX 패치 완료 판정 정적 검사.

설계서 §10~11을 정적 검사로 옮긴 데빈 레인 도구. 제품 소스는 읽기만 한다.
판정 값은 PASS / FAIL / PENDING(Codex 미완) / HINT(정적 단서일 뿐 증명 아님) /
OBSERVED(관찰 보고) 중 하나다.

- C1 브라우저 기본값 보존: chat-settings-bridge.js의 STORAGE_KEY, MAX_BYTES,
  KEYS 4개, DEFAULTS가 기준선과 같아야 PASS.
- C2 chat.js 미변경: sha256이 기준선과 같아야 PASS.
- C3 미리보기 계약: /api/settings/routing/preview 핸들러 본문에 save/write·
  외부 호출 흔적이 없어야 PASS (settings_page_probe.routing_pure_posts 재사용).
- C4 409 이후 초안 보존: HINT — 충돌 시 초안 유지 단서를 정적으로만 본다.
- C5 초기화 보호: confirm/실행취소 흐름이 있어야 PASS. 파일 미변경이면 PENDING.
- C6 저장 실패 표시: HINT — 실패 메시지와 되돌리기 안내 단서.
- C7 가짜 스위치 금지: JEV/ONNX ON·OFF 입력이 새로 생기지 않아야 PASS.
- C8 변경 범위: 기준선 대비 변경 파일이 설계서 대상 안이어야 PASS(밖이면 WARN).
- C9 보안 예외: AppSecurityConfig 추가 허용은 /api/settings/preferences PATCH
  하나뿐이어야 하고, 그 컨트롤러 owner는 서버 쿠키에서만 읽어야 한다.
- C10 useRag 기본값 차이: frontend true vs Thymeleaf false → OBSERVED.

사용:
  python -B scripts/settings_ux_acceptance_check.py --root . --baseline <baseline.json> [--json]
출력: JSON {"status": "PASS"|"FAIL"|"WARN"|"PENDING", "checks": {C1..C10}}
종료: FAIL 하나라도 있으면 1, 아니면 0.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path

BRIDGE_REL = "main/resources/static/js/chat-settings-bridge.js"
PAGE_REL = "main/resources/static/js/settings-page.js"
ROUTING_REL = "main/resources/static/js/settings-routing.js"
HTML_REL = "main/resources/templates/settings.html"
CHAT_REL = "main/resources/static/js/chat.js"
PICKER_REL = "main/resources/static/js/chat-model-picker.js"
SECURITY_REL = "main/java/com/example/lms/config/AppSecurityConfig.java"
FRONTEND_REL = "frontend/src/app/chat/page.js"

# 설계서 대상(C8): 이 패턴 안의 변경은 범위 내. AppSecurityConfig는 C9가 별도 판정.
ALLOWED_CHANGE_RES = [
    re.compile(r"main/resources/static/js/settings-[^/]+\.js$"),
    re.compile(r"main/resources/static/css/settings-page\.css$"),
    re.compile(r"main/resources/templates/(settings|model-settings)\.html$"),
    re.compile(r"main/resources/static/js/chat-model-picker\.js$"),
    re.compile(r"main/java/.*Settings.*Controller.*\.java$"),
    re.compile(r"src/test/.*"),
]

JEV_ONNX_RE = re.compile(r"(?i)\b(jev|onnx)\b")


def sha256_file(path: Path) -> str | None:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def load_baseline(spec: str | None, root: Path) -> tuple[dict, Path | None]:
    """baseline.json 로드. {relpath: {sha256, baselineCopy(abs Path)}} 반환."""
    if not spec:
        return {}, None
    bpath = Path(spec)
    if not bpath.is_absolute():
        bpath = root / bpath
    try:
        doc = json.loads(bpath.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}, bpath
    entries = {}
    for row in doc.get("files", []):
        rel = row.get("path")
        if not rel:
            continue
        copy_rel = row.get("baselineCopy")
        copy_path = (bpath.parent / copy_rel) if copy_rel else None
        entries[rel] = {"sha256": row.get("sha256"),
                        "copy": copy_path if copy_path and copy_path.is_file() else None}
    return entries, bpath


def _js_string_const(text: str, name: str) -> str | None:
    m = re.search(re.escape(name) + r"\s*=\s*'([^']*)'", text)
    if m:
        return m.group(1)
    m = re.search(re.escape(name) + r"\s*=\s*\"([^\"]*)\"", text)
    return m.group(1) if m else None


def extract_bridge_contract(text: str) -> dict:
    """STORAGE_KEY / MAX_BYTES / KEYS / DEFAULTS를 의미 단위로 뽑는다."""
    out = {"storageKey": _js_string_const(text, "STORAGE_KEY")}
    m = re.search(r"MAX_BYTES\s*=\s*(\d+)", text)
    out["maxBytes"] = int(m.group(1)) if m else None
    m = re.search(r"KEYS\s*=\s*Object\.freeze\(\[([^\]]*)\]", text)
    out["keys"] = sorted(re.findall(r"'([^']+)'", m.group(1))) if m else None
    m = re.search(r"DEFAULTS\s*=\s*Object\.freeze\(\{([^}]*)\}", text)
    defaults = {}
    if m:
        for km in re.finditer(r"(\w+)\s*:\s*'([^']*)'|(\w+)\s*:\s*(true|false|-?\d+)",
                              m.group(1)):
            key = km.group(1) or km.group(3)
            raw = km.group(2) if km.group(1) is not None else km.group(4)
            if raw == "true":
                defaults[key] = True
            elif raw == "false":
                defaults[key] = False
            elif raw is not None and re.fullmatch(r"-?\d+", raw):
                defaults[key] = int(raw)
            else:
                defaults[key] = raw
    out["defaults"] = defaults or None
    return out


def check_c1(root: Path, baseline: dict) -> dict:
    cur_text = read_text(root / BRIDGE_REL)
    if cur_text is None:
        return {"id": "C1", "status": "FAIL", "detail": f"{BRIDGE_REL} 없음"}
    cur = extract_bridge_contract(cur_text)
    base_entry = baseline.get(BRIDGE_REL)
    if base_entry and base_entry.get("copy"):
        base_text = read_text(base_entry["copy"])
        base = extract_bridge_contract(base_text or "")
        same = cur == base
        return {"id": "C1",
                "status": "PASS" if same else "FAIL",
                "detail": "STORAGE_KEY/MAX_BYTES/KEYS/DEFAULTS "
                          + ("기준선과 동일" if same else "기준선과 다름"),
                "current": cur, "baseline": base}
    # 기준선 없으면 지시서 확정값과 비교
    expected = {"storageKey": "awx.settings.v1.preferences", "maxBytes": 16384,
                "keys": ["model", "modelSelectionMode", "searchMode", "useRag"],
                "defaults": {"modelSelectionMode": "preferred",
                             "searchMode": "OFF", "useRag": False}}
    return {"id": "C1",
            "status": "PASS" if cur == expected else "FAIL",
            "detail": "기준선 없음 — 지시서 확정값과 비교",
            "current": cur, "baseline": expected}


def check_c2(root: Path, baseline: dict) -> dict:
    entry = baseline.get(CHAT_REL)
    cur = sha256_file(root / CHAT_REL)
    if cur is None:
        return {"id": "C2", "status": "FAIL", "detail": f"{CHAT_REL} 없음"}
    if not entry or not entry.get("sha256"):
        return {"id": "C2", "status": "PENDING",
                "detail": "기준선에 chat.js 해시 없음"}
    same = cur == entry["sha256"]
    return {"id": "C2", "status": "PASS" if same else "FAIL",
            "detail": f"sha256 {'기준선과 동일' if same else '변경됨'}",
            "sha256": cur}


def _import_probe(root: Path):
    path = root / "scripts" / "settings_page_probe.py"
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location("settings_page_probe", str(path))
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)
        return mod
    except Exception:
        return None


def check_c3(root: Path) -> dict:
    probe = _import_probe(root)
    if probe is None:
        return {"id": "C3", "status": "PENDING",
                "detail": "settings_page_probe.py 로드 불가"}
    rows = probe.routing_pure_posts(root)
    preview = [r for r in rows if r["path"].endswith("/preview")]
    if not preview:
        return {"id": "C3", "status": "PENDING",
                "detail": "/preview 핸들러 미발견", "rows": rows}
    row = preview[0]
    ok = row["callable"] and not row["writeHints"]
    return {"id": "C3", "status": "PASS" if ok else "FAIL",
            "detail": ("preview 핸들러에 write/외부호출 흔적 없음" if ok else
                       f"preview 핸들러 write 힌트: {row['writeHints']}"),
            "evidence": row}


def check_c4(root: Path) -> dict:
    text = read_text(root / ROUTING_REL) or ""
    clues = {
        "conflictKind": "conflict" in text and "409" in text,
        "draftKeptOnConflict": bool(
            re.search(r"409\)\s*\{?\s*current\s*=\s*\{[^}]*\.\.\.current[^}]*kind:'conflict'", text)
            or re.search(r"409.*draft", text)),
        "manualRereadButton": bool(re.search(r"client\.read\(", text)),
        "noAutoRereadOnConflict": not bool(
            re.search(r"409[^;]*client\.read|conflict[^;]*client\.read", text)),
        "draftPreserveMessage": bool(re.search(r"유지|선택값", text)),
    }
    return {"id": "C4", "status": "HINT",
            "detail": "정적 단서만 — 409 후 초안 보존은 실행 검증 필요",
            "clues": clues,
            "cluesFound": sum(1 for v in clues.values() if v),
            "cluesTotal": len(clues)}


def check_c5(root: Path, baseline: dict) -> dict:
    text = read_text(root / PAGE_REL)
    if text is None:
        return {"id": "C5", "status": "FAIL", "detail": f"{PAGE_REL} 없음"}
    m = re.search(r"local-reset[^;]*addEventListener\('click'.*?\}\s*\)", text, re.S)
    handler = m.group(0) if m else text
    guarded = bool(re.search(r"confirm\s*\(|window\.confirm|실행취소|되돌리|undo|"
                             r"withoutKeys|마지막 저장", handler))
    entry = baseline.get(PAGE_REL)
    unchanged = bool(entry and entry.get("sha256")
                     and sha256_file(root / PAGE_REL) == entry["sha256"])
    if unchanged:
        return {"id": "C5", "status": "PENDING",
                "detail": "settings-page.js 기준선과 동일 — Codex 미수정"}
    return {"id": "C5",
            "status": "PASS" if guarded else "FAIL",
            "detail": ("초기화에 확인/실행취소 흐름 있음" if guarded else
                       "초기화가 확인 없이 writeSettings({}) 직행" +
                       ("" if entry else " — 기준선 없음"))}


def check_c6(root: Path) -> dict:
    text = read_text(root / PAGE_REL) or ""
    clues = {
        "failureMessageShown": bool(re.search(r"저장 실패|실패", text)),
        "revertOrLastSavedHint": bool(
            re.search(r"마지막 저장|되돌리|복원|undo|이전 값|되돌아", text)),
        "catchKeepsValues": "보존" in text or "유지" in text,
    }
    return {"id": "C6", "status": "HINT",
            "detail": "정적 단서만 — 저장 실패 시 표시·되돌리기는 실행 검증 필요",
            "clues": clues,
            "cluesFound": sum(1 for v in clues.values() if v),
            "cluesTotal": len(clues)}


def _fake_switch_hits(text: str) -> int:
    """JEV/ONNX 언급 + 토글성 입력 근접(200자) 여부."""
    hits = 0
    for m in JEV_ONNX_RE.finditer(text):
        window = text[max(0, m.start() - 200):m.end() + 200]
        if re.search(r"type=['\"]?checkbox|role=['\"]?switch|ON|OFF|toggle",
                     window, re.I):
            hits += 1
    return hits


def check_c7(root: Path, baseline: dict) -> dict:
    suspects = []
    for rel in (HTML_REL, PAGE_REL, ROUTING_REL, BRIDGE_REL, PICKER_REL):
        cur_text = read_text(root / rel) or ""
        cur_hits = _fake_switch_hits(cur_text) + len(JEV_ONNX_RE.findall(cur_text))
        base_hits = 0
        entry = baseline.get(rel)
        if entry and entry.get("copy"):
            base_text = read_text(entry["copy"]) or ""
            base_hits = (_fake_switch_hits(base_text)
                         + len(JEV_ONNX_RE.findall(base_text)))
        if cur_hits > base_hits:
            suspects.append({"file": rel, "currentHits": cur_hits,
                             "baselineHits": base_hits})
    return {"id": "C7", "status": "FAIL" if suspects else "PASS",
            "detail": ("JEV/ONNX 토글성 언급이 기준선 대비 증가" if suspects else
                       "JEV/ONNX 가짜 스위치 증가 없음"),
            "suspects": suspects}


def check_c8(root: Path, baseline: dict) -> dict:
    if not baseline:
        return {"id": "C8", "status": "PENDING", "detail": "기준선 없음"}
    changed, out_of_scope, deferred = [], [], []
    for rel, entry in baseline.items():
        cur = sha256_file(root / rel)
        if cur is None or cur == entry.get("sha256"):
            continue
        changed.append(rel)
        if rel == SECURITY_REL:
            deferred.append(rel)
        elif not any(rx.search(rel) for rx in ALLOWED_CHANGE_RES):
            out_of_scope.append(rel)
    return {"id": "C8", "status": "WARN" if out_of_scope else "PASS",
            "detail": (f"범위 밖 변경 {len(out_of_scope)}건" if out_of_scope else
                       "변경이 설계서 대상 안"),
            "changed": changed, "outOfScope": out_of_scope,
            "deferredToC9": deferred}


def check_c9(root: Path, baseline: dict) -> dict:
    entry = baseline.get(SECURITY_REL)
    cur_sha = sha256_file(root / SECURITY_REL)
    cur_text = read_text(root / SECURITY_REL)
    if cur_text is None:
        return {"id": "C9", "status": "FAIL", "detail": f"{SECURITY_REL} 없음"}
    if entry and entry.get("sha256") and cur_sha == entry["sha256"]:
        cfg_changed = False
    else:
        cfg_changed = True
    findings = {"configChanged": cfg_changed, "addedPermits": [],
                "badAddedPermits": [], "controller": None}
    if cfg_changed and entry and entry.get("copy"):
        base_text = read_text(entry["copy"]) or ""
        added = [ln[1:] for ln in difflib.unified_diff(
            base_text.splitlines(), cur_text.splitlines(), lineterm="")
            if ln.startswith("+") and not ln.startswith("+++")]
        permit_lines = [ln for ln in added
                        if re.search(r"permitAll|hasRole|hasAnyRole|requestMatchers|authenticated", ln)]
        last_matcher = ""
        for ln in permit_lines:
            if "requestMatchers" in ln or "antMatchers" in ln:
                last_matcher = ln
            ctx = last_matcher + " " + ln  # 멀티라인 matcher 체인을 한 줄로 본다
            ok_path = "/api/settings/preferences" in ctx
            ok_method = "PATCH" in ctx
            findings["addedPermits"].append(ln.strip()[:200])
            if not (ok_path and ok_method):
                findings["badAddedPermits"].append(ln.strip()[:200])
    # preferences PATCH 컨트롤러 탐색
    owner_from_param_or_body = None
    controller_rel = None
    for p in (root / "main/java").rglob("*.java"):
        t = read_text(p)
        if not t or "/api/settings/preferences" not in t:
            continue
        if not re.search(r"@(PatchMapping|RequestMapping)", t):
            continue
        controller_rel = str(p.relative_to(root)).replace("\\", "/")
        owner_param = re.search(r"@RequestParam[^)]*owner|@RequestParam[^)]*\"owner\"",
                                t)
        body_owner = re.search(r"@RequestBody|request\.get\(\"owner\"\)|"
                               r"\.path\(\"owner\"\)|get\(\"owner\"\)", t)
        cookie_owner = re.search(r"@CookieValue|getCookies\(\)|Cookie", t)
        findings["controller"] = {
            "file": controller_rel,
            "ownerFromRequestParam": bool(owner_param),
            "ownerFromBody": bool(body_owner),
            "ownerFromCookie": bool(cookie_owner)}
        owner_from_param_or_body = bool(owner_param or body_owner)
        break
    if findings["badAddedPermits"]:
        verdict, why = "FAIL", "preferences PATCH 외 추가 허용 있음"
    elif owner_from_param_or_body:
        verdict, why = "FAIL", "owner를 @RequestParam/요청 본문에서 읽음"
    elif cfg_changed or findings["controller"]:
        verdict, why = "PASS", "예외 없음 또는 owner=쿠키 전용"
    else:
        verdict, why = "PASS", "AppSecurityConfig 미변경·preferences 컨트롤러 없음"
    return {"id": "C9", "status": verdict, "detail": why, "findings": findings}


def check_c10(root: Path) -> dict:
    fe = read_text(root / FRONTEND_REL) or ""
    m = re.search(r"useRag[^\n]*useState\(\s*(true|false)", fe)
    fe_default = m.group(1) if m else None
    bridge = read_text(root / BRIDGE_REL) or ""
    contract = extract_bridge_contract(bridge)
    thy_default = (contract.get("defaults") or {}).get("useRag")
    return {"id": "C10", "status": "OBSERVED",
            "detail": f"frontend useRag 기본값={fe_default}, Thymeleaf(bridge) "
                      f"기본값={thy_default} — 관찰 항목, 수정 요구 아님",
            "frontendDefault": fe_default, "thymeleafDefault": thy_default}


def run_all(root: Path, baseline_spec: str | None) -> dict:
    baseline, bpath = load_baseline(baseline_spec, root)
    checks = {
        "C1": check_c1(root, baseline),
        "C2": check_c2(root, baseline),
        "C3": check_c3(root),
        "C4": check_c4(root),
        "C5": check_c5(root, baseline),
        "C6": check_c6(root),
        "C7": check_c7(root, baseline),
        "C8": check_c8(root, baseline),
        "C9": check_c9(root, baseline),
        "C10": check_c10(root),
    }
    statuses = [c["status"] for c in checks.values()]
    if "FAIL" in statuses:
        overall = "FAIL"
    elif "WARN" in statuses:
        overall = "WARN"
    elif all(s in ("PENDING", "HINT", "OBSERVED") for s in statuses):
        overall = "PENDING"
    else:
        overall = "PASS"
    return {"schemaVersion": "devin.settings-ux-acceptance.v1",
            "status": overall, "baseline": str(bpath) if bpath else None,
            "checks": checks,
            "summary": {s: statuses.count(s) for s in
                        ("PASS", "FAIL", "WARN", "PENDING", "HINT", "OBSERVED")
                        if statuses.count(s)}}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Codex settings-UX patch acceptance static check (read-only)")
    ap.add_argument("--root", default=".", help="repo root (default: cwd)")
    ap.add_argument("--baseline", help="baseline.json 경로 (T0 산출물)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)
    report = run_all(Path(args.root).resolve(), args.baseline)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 1 if report["status"] == "FAIL" else 0


if __name__ == "__main__":
    sys.exit(main())
