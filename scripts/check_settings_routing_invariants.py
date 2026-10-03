#!/usr/bin/env python3
"""check_settings_routing_invariants.py — Plan6 설정·라우팅 불변식 검증기.

PASTE_CODEX_PLAN6_SCAFFOLD_20261002 WP3. 제품 소스 수정 없이 계약 위반을
정적 검사한다. 계약 SSOT: docs/SETTINGS_ROUTING_CONTRACT.md.

사용: python -B scripts/check_settings_routing_invariants.py --root .
출력: JSON {"status": "PASS"|"FAIL", "checks": [...], "violations": [...],
      "observations": {...}}; 위반 0이면 exit 0, 하나라도 위반이면 exit 1.

불변 vs 관측: chat.js 불변·SYSTEM_PROMPT 비공개·마스킹·sessionStorage·
POST focus·Jev 단일 래핑은 FAIL 대상 불변식이다. LlmRouterAspect:725의
전체 모델 확장(fallback widening)은 현재 존재하는 열린 계약 항목이므로
FAIL이 아니라 observations.fallbackWidening으로만 보고한다 — 구현 후
SettingsRoutingContractTest가 bounded 여부를 판정한다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# 스캐폴드 기준일(2026-10-02) 실측값. 지시서의 "7408행"은 카운팅 관례 차이로
# 실제 파일은 7407행·LF/CRLF 혼합 없이 마지막 newline 종료다. 불변 판정은
# 해시가 1차 기준, 행 수는 보조 정보다.
CHAT_JS_BASELINE_SHA256 = "4225d94475241421ee69d522864e09eef39f6b15995ef88c98fca010b669c2d3"
CHAT_JS_BASELINE_LINES = 7407

REQUIRED_STRING = "chat.recoveryDraft"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def check_settings_controller(root: Path) -> list[dict]:
    """SYSTEM_PROMPT가 공개 허용 목록에 없고, 비허용 키 거부 로직이 유지되는가."""
    rel = "main/java/com/example/lms/api/SettingsController.java"
    text = read_text(root / rel)
    out = []
    if text is None:
        return [{"id": "settings.public_keys", "file": rel, "ok": False,
                 "detail": "file missing"}]
    m = re.search(r"PUBLIC_SETTING_KEYS\s*=\s*Set\.of\((.*?)\)", text, re.S)
    block = m.group(1) if m else ""
    out.append({
        "id": "settings.public_keys.no_system_prompt", "file": rel,
        "ok": bool(m) and "SYSTEM_PROMPT" not in block and "KEY_SYSTEM_PROMPT" not in block,
        "detail": "PUBLIC_SETTING_KEYS block scanned; SYSTEM_PROMPT must stay absent",
    })
    out.append({
        "id": "settings.reject_unknown_keys", "file": rel,
        "ok": ("!PUBLIC_SETTING_KEYS.contains" in text) and ("rejected" in text)
              and ("badRequest" in text),
        "detail": "non-allowlist keys must still be rejected with 400",
    })
    return out


def check_secret_mask_aspect(root: Path) -> list[dict]:
    """민감 키 마스킹( openai 포함 )과 저장 제거 로직이 보존됐는가."""
    rel = "main/java/ai/abandonware/nova/orch/aop/SettingsControllerSecretMaskAspect.java"
    text = read_text(root / rel)
    out = []
    if text is None:
        return [{"id": "settings.secret_mask", "file": rel, "ok": False,
                 "detail": "file missing"}]
    start = text.find("isSensitiveKey(String key)")
    end = text.find("looksLikeSecret", start)
    body = text[start:end if end > start else start + 4000]
    out.append({
        "id": "settings.secret_mask.openai", "file": rel,
        "ok": start >= 0 and '"openai"' in body,
        "detail": 'isSensitiveKey must keep matching "openai" (OPENAI_MODEL masked)',
    })
    out.append({
        "id": "settings.secret_mask.strip", "file": rel,
        "ok": ("isSensitiveKey(k)" in text and "continue" in text
               and "filtered.put" in text and "stripped" in text),
        "detail": "sensitive keys must be stripped from save payload (200 != stored)",
    })
    out.append({
        "id": "settings.secret_mask.allow_secret_update_optin", "file": rel,
        "ok": 'getProperty("nova.security.settings.allowSecretUpdate", Boolean.class, false)' in text,
        "detail": "allowSecretUpdate must remain opt-in default false",
    })
    return out


def check_chat_js(root: Path) -> list[dict]:
    """chat.js 불변(해시·행 수) + recoveryDraft sessionStorage 전용."""
    rel = "main/resources/static/js/chat.js"
    path = root / rel
    out = []
    data = None
    try:
        data = path.read_bytes()
    except OSError:
        return [{"id": "chat_js.immutable", "file": rel, "ok": False,
                 "detail": "file missing"}]
    digest = sha256_file(path)
    lines = data.count(b"\n") + (0 if data.endswith(b"\n") else 1)
    out.append({
        "id": "chat_js.immutable_hash", "file": rel,
        "ok": digest == CHAT_JS_BASELINE_SHA256,
        "detail": f"sha256={digest[:12]}… baseline={CHAT_JS_BASELINE_SHA256[:12]}… "
                  f"(settings-routing scope: chat.js edits forbidden)",
    })
    out.append({
        "id": "chat_js.immutable_lines", "file": rel,
        "ok": lines == CHAT_JS_BASELINE_LINES,
        "detail": f"lines={lines} baseline={CHAT_JS_BASELINE_LINES} "
                  "(directive said 7408; live count is 7407)",
    })
    text = data.decode("utf-8", errors="replace")
    for api in ("setItem", "getItem", "removeItem"):
        out.append({
            "id": f"chat_js.recovery_draft.session_storage.{api}", "file": rel,
            "ok": f'sessionStorage.{api}("{REQUIRED_STRING}"' in text,
            "detail": f"sessionStorage.{api} for chat.recoveryDraft must be preserved",
        })
    bad_local = re.search(r'localStorage\s*\.\s*\w+\s*\(\s*"chat\.recoveryDraft"', text)
    out.append({
        "id": "chat_js.recovery_draft.no_localstorage", "file": rel,
        "ok": bad_local is None,
        "detail": "localStorage must never hold chat.recoveryDraft",
    })
    return out


def check_llm_router_aspect(root: Path) -> tuple[list[dict], dict]:
    """CallArgs.parse 5/7/8 인자 해석 보존 + fallback 확장 상태 관측."""
    rel = "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java"
    text = read_text(root / rel)
    out = []
    if text is None:
        return [{"id": "llm_router.call_args", "file": rel, "ok": False,
                 "detail": "file missing"}], {}
    parse_m = re.search(r"static CallArgs parse\(Object\[\] args\)(.*)", text, re.S)
    parse_body = parse_m.group(1) if parse_m else ""
    arities = [n for n in (5, 7, 8) if f"args.length == {n}" in parse_body]
    out.append({
        "id": "llm_router.call_args_arity_5_7_8", "file": rel,
        "ok": arities == [5, 7, 8],
        "detail": f"CallArgs.parse must keep handling arg counts 5/7/8; found {arities}. "
                  "Adding a role arg requires a NEW parse branch, never silent null.",
    })
    widening = "props.getModels().keySet().stream().sorted().forEach(ordered::add)" in text
    observations = {
        "fallbackWidening": ("present" if widening else "absent"),
        "fallbackWideningNote": ("LlmRouterAspect.nextEligibleSelection appends ALL "
                                 "registered model keys after the fallback chain — open "
                                 "contract item SETTINGS_ROUTING_CONTRACT §4; must be "
                                 "bounded only for explicit profiles, legacy unchanged.")
        if widening else "widening removed/gated — verify §4 contract test",
    }
    return out, observations


def check_retriever_chain(root: Path) -> list[dict]:
    """JevRetrievalGateHandler.wrapIfEnabled가 기존 2곳 외에 중복 등록됐는가."""
    rel = "main/java/com/example/lms/config/RetrieverChainConfig.java"
    text = read_text(root / rel)
    out = []
    if text is None:
        return [{"id": "retriever.jev_wrap", "file": rel, "ok": False,
                 "detail": "file missing"}]
    count = text.count("JevRetrievalGateHandler.wrapIfEnabled")
    out.append({
        "id": "retriever.jev_wrap_no_duplicate", "file": rel,
        "ok": count == 2,
        "detail": f"wrapIfEnabled call sites={count}; expected exactly 2 "
                  "(fixed chain + dynamic chain); a 3rd = duplicate registration",
    })
    return out


def check_display_conversate(root: Path) -> list[dict]:
    """Focus 설정 읽기가 POST + focusBinding 소유권 검증을 유지하는가."""
    rel = "main/java/com/example/lms/assist/DisplayConversateController.java"
    text = read_text(root / rel)
    out = []
    if text is None:
        return [{"id": "display.focus_read", "file": rel, "ok": False,
                 "detail": "file missing"}]
    focus_path = "/api/assist/display/focus/settings/read"
    out.append({
        "id": "display.focus_read.post_only", "file": rel,
        "ok": f'@PostMapping("{focus_path}")' in text,
        "detail": "focus settings read must stay POST (body carries connection proof)",
    })
    get_m = re.search(r'@GetMapping\("' + re.escape(focus_path) + r'"', text)
    out.append({
        "id": "display.focus_read.no_get", "file": rel,
        "ok": get_m is None,
        "detail": "no plain GET may expose focus settings without ownership binding",
    })
    out.append({
        "id": "display.focus_read.binding", "file": rel,
        "ok": "focusBinding(" in text and "requireProducer" in text,
        "detail": "ownership/epoch/RUNNING binding must keep guarding the endpoint",
    })
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Plan6 settings-routing invariant checker")
    ap.add_argument("--root", default=".", help="repo root (default: cwd)")
    args = ap.parse_args()
    root = Path(args.root).resolve()

    checks: list[dict] = []
    observations: dict = {}
    checks += check_settings_controller(root)
    checks += check_secret_mask_aspect(root)
    checks += check_chat_js(root)
    router_checks, router_obs = check_llm_router_aspect(root)
    checks += router_checks
    observations.update(router_obs)
    checks += check_retriever_chain(root)
    checks += check_display_conversate(root)

    violations = [c for c in checks if not c["ok"]]
    report = {
        "schemaVersion": "awx.settings-routing-invariants.v1",
        "status": "PASS" if not violations else "FAIL",
        "root": str(root),
        "checkCount": len(checks),
        "checks": checks,
        "violations": [{"id": c["id"], "file": c["file"], "detail": c["detail"]}
                       for c in violations],
        "observations": observations,
    }
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if not violations else 1


if __name__ == "__main__":
    sys.exit(main())
