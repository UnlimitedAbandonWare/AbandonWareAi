#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D7: T01 demo-route matrix — which chat routes die under demo.interview.enabled.

Reused: none. Added: static @*Mapping extractor + InterviewDemoFilter simulator
mirroring ChatOpenSecurityConfig.java:92-108 (page/asset/assistRead/assistWrite/
displayWrite/displayDiagnostics whitelists; POST whitelist = /api/chat/sync).
Read-only: scans main/java, main/resources/static/js/chat.js, frontend/src.
No config changes. Exit 0 PASS / 1 FAIL / 2 input missing.
"""
from __future__ import annotations
import argparse, json, re, sys
from pathlib import Path

# --- InterviewDemoFilter contract (ChatOpenSecurityConfig.java:92-108) ---
PAGES = {"/", "/index", "/index.html", "/chat", "/chat-ui", "/chat-ui.html",
         "/error", "/favicon.ico", "/actuator/health"}
ASSET_RE = re.compile(r"/assets/(interview|display)/[a-z][a-z0-9-]*\.(html|js|css|png|webmanifest)")
ASSIST_READ_RE = re.compile(r"/api/assist/sessions/[a-f0-9-]{36}(/output(/poll)?)?$")
ASSIST_WRITE_RE = re.compile(r"/api/assist/sessions/[a-f0-9-]{36}/(card|ack|control)$")
# display paths depend on displayEnabled/audioEnabled suppliers — treated as flags
DISPLAY_TEXT = {"/api/display/text", "/api/display/hint"}  # representative; see note

def demo_filter_allows(method: str, path: str, display_enabled=False, audio_enabled=False):
    """Simulate InterviewDemoFilter for a loopback request when demo flag is ON.
    Returns (allowed: bool, reason: str)."""
    read = method in ("GET", "HEAD")
    page = path in PAGES
    asset = bool(ASSET_RE.match(path))
    assist_read = path == "/api/assist/bootstrap" or bool(ASSIST_READ_RE.match(path))
    assist_write = path == "/api/assist/sessions" or bool(ASSIST_WRITE_RE.match(path))
    display_write = display_enabled and path in DISPLAY_TEXT
    display_diag = display_enabled and path == "/api/diagnostics/display"
    if read and (page or asset or assist_read or display_diag):
        return True, "read-whitelist"
    if method == "POST" and (path == "/api/chat/sync" or assist_write or display_write):
        return True, "post-whitelist"
    return False, "404-stripped by InterviewDemoFilter"

MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(\s*(?:value\s*=\s*)?'
    r'(?:\{\s*)?"([^"]+)"(?:\s*\})?)?')
CLASS_BASE_RE = re.compile(r'@RequestMapping\s*\(\s*"([^"]+)"')

def scan_controllers(root: Path) -> list[dict]:
    routes = []
    for f in sorted(root.glob("main/java/**/*.java")):
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "@RestController" not in text and "@Controller" not in text:
            continue
        lines = text.splitlines()
        base = ""
        base_line = -1
        class_idx = next((i for i, ln in enumerate(lines)
                          if re.search(r"\bclass\s+\w+", ln)), len(lines))
        for i, ln in enumerate(lines[:class_idx]):
            m = CLASS_BASE_RE.search(ln)
            if m:
                base = m.group(1)
                base_line = i
                break
        for i, ln in enumerate(lines):
            if i == base_line:
                continue
            for m in MAPPING_RE.finditer(ln):
                ann, sub = m.group(1), m.group(2) or ""
                if ann == "Request" and i > 0 and ln.find("Mapping") < 40 and not sub:
                    continue
                method = {"Get": "GET", "Post": "POST", "Put": "PUT",
                          "Delete": "DELETE", "Patch": "PATCH"}.get(ann)
                if ann == "Request":
                    mm = re.search(r'method\s*=\s*RequestMethod\.(\w+)', ln)
                    method = mm.group(1) if mm else "ANY"
                path = (base or "") + sub
                if not path.startswith("/"):
                    path = "/" + path
                routes.append({"method": method, "path": path,
                               "file": str(f.relative_to(root)).replace("\\", "/"),
                               "line": i + 1})
    return routes

def scan_callers(root: Path) -> dict[str, list[str]]:
    """UI call sites: chat.js + frontend/src fetch targets."""
    calls: dict[str, list[str]] = {}
    targets = [root / "main/resources/static/js/chat.js"]
    targets += sorted(root.glob("frontend/src/**/*.js")) + \
               sorted(root.glob("frontend/src/**/*.tsx"))
    api_re = re.compile(r'["\'`](/api/[a-zA-Z0-9_/${}.-]+)')
    for f in targets:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        rel = str(f.relative_to(root)).replace("\\", "/")
        for m in api_re.finditer(text):
            p = re.sub(r"\$\{[^}]*\}", "{x}", m.group(1)).split("?")[0]
            calls.setdefault(p, []).append(rel)
    return calls

def main() -> int:
    ap = argparse.ArgumentParser(description="Demo-mode route blocking matrix")
    ap.add_argument("--root", default=".")
    ap.add_argument("--out", default="data/agent-handoff/devin-p6/t01-demo-route-matrix.md")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    if not (root / "main/java").exists():
        print(json.dumps({"status": "FAIL", "reason": "no main/java"}))
        return 2

    routes = scan_controllers(root)
    calls = scan_callers(root)
    chat_routes = [r for r in routes if r["path"].startswith("/api/chat")
                   or r["path"].startswith("/api/rag")]

    rows = []
    for r in sorted(chat_routes, key=lambda x: (x["path"], x["method"])):
        allowed_off = True  # demo off -> no filter registered
        allowed_on, why = demo_filter_allows(r["method"], r["path"])
        pat = re.sub(r"\{[^}]*\}", "{x}", r["path"])
        called_by = calls.get(pat) or calls.get(r["path"]) or []
        # prefix match for templated paths
        if not called_by:
            for cp, srcs in calls.items():
                if cp.rstrip("/") == pat.rstrip("/") or cp.startswith(pat.rstrip("/") + "/"):
                    called_by = srcs
                    break
        rows.append({**r, "demo_off": "ALLOW", "demo_on": "ALLOW" if allowed_on else "BLOCK(404)",
                     "reason": why, "called_by": sorted(set(called_by))})

    ui_blocked = [r for r in rows if r["demo_on"] != "ALLOW" and r["called_by"]]

    out = root / a.out
    out.parent.mkdir(parents=True, exist_ok=True)
    L = ["# T01 — Demo route blocking matrix",
         "",
         "Simulator mirrors `InterviewDemoFilter` (`ChatOpenSecurityConfig.java:92-108`):",
         "demo ON → GET/HEAD only for pages/assets/assist-reads/diagnostics; POST only",
         "`/api/chat/sync` + assist/display writes; everything else 404. Loopback assumed.",
         "displayEnabled/audioEnabled suppliers default OFF in this matrix (conservative).",
         "",
         "| method | path | demo=off | demo=on | UI caller | source |",
         "|---|---|---|---|---|---|"]
    for r in rows:
        cb = ", ".join(r["called_by"]) or "—"
        mark = " **!!**" if r["demo_on"] != "ALLOW" and r["called_by"] else ""
        L.append(f"| {r['method']} | `{r['path']}` | {r['demo_off']} | {r['demo_on']}{mark} "
                 f"| {cb} | {r['file'].split('/')[-1]}:{r['line']} |")
    L += ["",
          "## UI-called but demo-blocked",
          ""]
    for r in ui_blocked:
        L.append(f"- `{r['method']} {r['path']}` called by {', '.join(r['called_by'])} → 404 under demo")
    L += ["",
          "## Options (decision stays with user — no config changed)",
          "",
          "1. **현 상태 유지** — 데모는 `/api/chat/sync` 단일 경로만. stream/cancel UI는 데모에서 404.",
          "   영향: 데모 완결성 최소, 변경 0.",
          "2. **데모에서도 stream·cancel 허용** — 필터 POST 화이트리스트에 `/api/chat/stream`,",
          "   `/api/chat/cancel`, `/api/chat/state`(GET) 추가. 영향: 데모 UX 개선,",
          "   익명 표면이 +3 경로 확대.",
          "3. **`demo.interview.enabled` 기본값 변경** — 플래그 자체를 끔. 영향: 제품 결정 사항,",
          "   InterviewDemoFilter 등록 조건(ConditionalOnProperty)에 직접 영향.",
          ""]
    out.write_text("\n".join(L), encoding="utf-8")
    print(json.dumps({"status": "PASS", "chat_routes": len(rows),
                      "ui_blocked": len(ui_blocked), "out": str(out)}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
