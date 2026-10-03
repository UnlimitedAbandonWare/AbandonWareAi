#!/usr/bin/env python3
"""settings_page_probe — read-only live check for the /settings rollout.

Devin assist tool. GET-only against the product surface; the single allowed
exception is ONE POST each to /api/settings/routing/read and
/api/settings/routing/preview, and only after the controller source proves
those handlers are read/preview-pure (no save/write calls). `save` is never
called. No chat is ever sent. No cookies/tokens stored.

  python -B scripts/settings_page_probe.py                 # all targets
  python -B scripts/settings_page_probe.py --local         # 127.0.0.1 only
  python -B scripts/settings_page_probe.py --baseline var/settings-guard/baseline-*.json
  python -B scripts/settings_page_probe.py --json

verdict: SETTINGS_OK | SETTINGS_MISSING | MAIN_BROKEN | UNREACHABLE
(main verdict from the LOCAL targets; public kro.kr rows are informational —
same backend, checked GET-only for parity).
exit 0 iff verdict == SETTINGS_OK (or SETTINGS_MISSING is acceptable when the
feature is not built yet? no — still nonzero; the report interprets it).
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import socket
import ssl
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9), "KST")
LOCAL = "http://127.0.0.1:18180"
PUBLIC = "https://abandonwareai.kro.kr"
TIMEOUT = 10
USER_AGENT = "devin-probe/1.0 (settings-page; read-only)"

SETTINGS_TITLE_RE = re.compile(r"<title>(.*?)</title>", re.IGNORECASE | re.DOTALL)
SETTINGS_MARKERS = (
    'id="settingsRoot"', 'id="settings-root"', 'data-page="settings"',
    "/js/settings-page.js", "/css/settings-page.css", 'id="settingsApp"',
)
BRIDGE_JS_REF = "chat-settings-bridge.js"
SETTINGS_LINK_RE = re.compile(r'href="[^"]*/settings"', re.IGNORECASE)

MAPPING_RE = re.compile(
    r"@(RequestMapping|GetMapping|PostMapping)\s*\(\s*(?:value\s*=\s*)?"
    r"[{\"]?(/[^\"}]*)")
WRITE_HINT_RE = re.compile(
    r"(\.save\s*\(|\.saveAll\s*\(|\.insert\s*\(|\.update\s*\(|\.delete\s*\(|"
    r"\.persist\s*\(|\.write\w*\s*\(|repository\.|chatModel\.|restTemplate\.|"
    r"webClient\.|\bexchange\s*\()")


def _load_main_probe(root: Path):
    path = root / "scripts" / "main_chat_target_probe.py"
    if not path.is_file():
        return None
    spec = importlib.util.spec_from_file_location(
        "main_chat_target_probe", str(path))
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)
        return mod
    except Exception:  # noqa: BLE001 - probe must never crash on import
        return None


def default_fetch(url: str, method: str = "GET", body: bytes | None = None):
    req = urllib.request.Request(
        url, method=method, data=body,
        headers={"User-Agent": USER_AGENT,
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, resp.read(2 * 1024 * 1024).decode(
                "utf-8", "replace")
    except urllib.error.HTTPError as exc:
        try:
            text = exc.read(2 * 1024 * 1024).decode("utf-8", "replace")
        except OSError:
            text = ""
        return exc.code, text
    except (urllib.error.URLError, socket.timeout, TimeoutError,
            ssl.SSLError, OSError) as exc:
        return None, f"{type(exc).__name__}: {getattr(exc, 'reason', exc)}"


def classify_settings(status, body):
    body = body or ""
    title = ""
    m = SETTINGS_TITLE_RE.search(body)
    if m:
        title = m.group(1).strip()
    markers = [mk for mk in SETTINGS_MARKERS if mk in body]
    if status is None:
        verdict = "UNREACHABLE"   # transport failed — server never answered
    elif status == 404:
        verdict = "MISSING"
    elif status == 200 and markers:
        verdict = "OK"
    elif status == 200:
        verdict = "WEAK"   # 200 but none of the expected markers
    else:
        verdict = "HTTP_" + str(status)   # e.g. 500 -> broken-but-reachable
    return {"status": status, "title": title,
            "settingsMarkers": markers, "verdict": verdict}


def classify_chat(status, body, main_probe):
    base = main_probe.classify(status, body) if main_probe else {
        "status": status, "verdict": "UNKNOWN"}
    body = body or ""
    base["settingsLink"] = bool(SETTINGS_LINK_RE.search(body))
    base["bridgeScript"] = BRIDGE_JS_REF in body
    return base


def api_settings_key_names(status, body):
    row = {"status": status, "keys": None}
    if status != 200:
        return row
    try:
        data = json.loads(body or "")
        row["keys"] = sorted(data.keys()) if isinstance(data, dict) else \
            "<non-dict>"
    except (ValueError, TypeError):
        row["keys"] = "<non-json>"
    return row


def routing_pure_posts(root: Path):
    """Find Codex's routing controller and list POST paths that look pure.

    Pure = path ends /read or /preview AND the handler method body shows no
    write/generation/http-out hints. save and anything unclear are excluded.
    """
    candidates = []
    for p in root.glob("main/java/**/*Routing*Controller*.java"):
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "/api/settings/routing" not in text:
            continue
        class_base = ""
        m = re.search(r'@RequestMapping\s*\(\s*"([^"]+)"', text)
        if m:
            class_base = m.group(1).rstrip("/")
        for mm in re.finditer(
                r'@PostMapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)\s*'
                r'(?:public\s+)?[\w<>\[\],.? ]+?\s+(\w+)\s*\(', text):
            sub, method_name = mm.group(1), mm.group(2)
            full = class_base + sub
            # find the method body (next ~60 lines after the match)
            body_start = mm.end()
            body = text[body_start:body_start + 4000]
            end = body.find("\n    }\n")
            if end > 0:
                body = body[:end]
            pure_name = sub.endswith("/read") or sub.endswith("/preview")
            write_hints = WRITE_HINT_RE.findall(body)
            candidates.append({
                "path": full, "method": method_name,
                "file": str(p.relative_to(root)).replace("\\", "/"),
                "pureName": pure_name,
                "writeHints": write_hints,
                "callable": pure_name and not write_hints,
            })
    return candidates


def probe_row(name, url, fetch_fn, method="GET", body=None):
    row = {"target": name, "url": url, "method": method,
           "requestedAtKst": datetime.now(KST).isoformat(timespec="seconds")}
    status, text = fetch_fn(url, method=method, body=body)
    row["status"] = status
    if status is None:
        row["error"] = str(text)[:200]
    return row, status, text


def run_probe(root: Path, fetch_fn=default_fetch, local_only=False,
              baseline=None, do_pure_posts=True):
    main_probe = _load_main_probe(root)
    out = {"tool": "settings_page_probe",
           "atKst": datetime.now(KST).isoformat(timespec="seconds"),
           "rows": []}

    # --- local chat -------------------------------------------------------
    row, status, body = probe_row("local-chat", LOCAL + "/chat", fetch_fn)
    row["chat"] = classify_chat(status, body, main_probe)
    out["rows"].append(row)
    chat_ok = row["chat"].get("verdict") == "MAIN_OK"

    # --- local settings -----------------------------------------------------
    row, status, body = probe_row("local-settings", LOCAL + "/settings",
                                  fetch_fn)
    row["settings"] = classify_settings(status, body)
    out["rows"].append(row)

    # --- local api/settings key names ---------------------------------------
    row, status, body = probe_row("local-api-settings",
                                  LOCAL + "/api/settings", fetch_fn)
    row["api"] = api_settings_key_names(status, body)
    base_keys = ((baseline or {}).get("apiSettings") or {}).get("keys")
    if isinstance(base_keys, list) and isinstance(row["api"]["keys"], list):
        row["api"]["keySetDrift"] = {
            "added": sorted(set(row["api"]["keys"]) - set(base_keys)),
            "removed": sorted(set(base_keys) - set(row["api"]["keys"])),
        }
    out["rows"].append(row)

    # --- pure routing POSTs (local only, 1x each, only if proven pure) -------
    pure = routing_pure_posts(root) if do_pure_posts else []
    out["routingPosts"] = {"candidates": pure, "attempts": []}
    if pure and row.get("status") is not None:
        for c in pure:
            if not c["callable"]:
                continue
            arow, st, tx = probe_row(
                "local-routing-" + c["path"].rsplit("/", 1)[-1],
                LOCAL + c["path"], fetch_fn, method="POST", body=b"{}")
            # summarize body shape without echoing values
            try:
                parsed = json.loads(tx or "")
                arow["bodyKeys"] = sorted(parsed.keys()) \
                    if isinstance(parsed, dict) else "<non-dict>"
            except (ValueError, TypeError):
                arow["bodyKeys"] = "<non-json>"
            out["routingPosts"]["attempts"].append(arow)

    # --- public parity (GET only) ---------------------------------------------
    if not local_only:
        for name, url in (("public-chat", PUBLIC + "/chat"),
                          ("public-settings", PUBLIC + "/settings")):
            row, status, body = probe_row(name, url, fetch_fn)
            if name == "public-chat":
                row["chat"] = classify_chat(status, body, main_probe)
            else:
                row["settings"] = classify_settings(status, body)
            out["rows"].append(row)

    # --- verdict -------------------------------------------------------------
    local_rows = {r["target"]: r for r in out["rows"]
                  if r["target"].startswith("local-")}
    chat_row = local_rows.get("local-chat", {})
    settings_row = local_rows.get("local-settings", {})
    chat_v = (chat_row.get("chat") or {}).get("verdict")
    set_v = (settings_row.get("settings") or {}).get("verdict")
    if chat_row.get("status") is None and settings_row.get("status") is None:
        verdict = "UNREACHABLE"
    elif chat_v == "INTERVIEW_OVERRIDE" or (
            chat_row.get("status") is not None and chat_v != "MAIN_OK"):
        verdict = "MAIN_BROKEN"
    elif set_v == "OK" and chat_v == "MAIN_OK":
        verdict = "SETTINGS_OK"
    elif set_v == "UNREACHABLE":
        verdict = "UNREACHABLE"
    else:
        # MISSING / WEAK / HTTP_4xx / HTTP_5xx — page not serving correctly
        verdict = "SETTINGS_MISSING"
    out["verdict"] = verdict
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--local", action="store_true", help="local 18180 only")
    ap.add_argument("--baseline", help="guard baseline JSON for key-set drift")
    ap.add_argument("--no-post", action="store_true",
                    help="never attempt the pure routing POSTs")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    baseline = None
    if args.baseline:
        baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    rep = run_probe(root, local_only=args.local, baseline=baseline,
                    do_pure_posts=not args.no_post)
    if args.as_json:
        print(json.dumps(rep, ensure_ascii=False, indent=1))
    else:
        print(f"verdict: {rep['verdict']}")
        for r in rep["rows"]:
            sub = r.get("chat") or r.get("settings") or r.get("api") or {}
            print(f"  {r['target']:22} {r['method']:4} status={r['status']} "
                  f"{sub.get('verdict', '')} {sub.get('title', '')}")
        for a in rep["routingPosts"]["attempts"]:
            print(f"  POST {a['url']} -> {a['status']} "
                  f"keys={a.get('bodyKeys')}")
        skipped = [c for c in rep["routingPosts"]["candidates"]
                   if not c["callable"]]
        if skipped:
            print("  routing POSTs NOT_RUN (not proven pure): "
                  + ", ".join(c["path"] for c in skipped))
    return 0 if rep["verdict"] == "SETTINGS_OK" else 1


if __name__ == "__main__":
    sys.exit(main())
