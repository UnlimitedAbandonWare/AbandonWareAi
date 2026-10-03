#!/usr/bin/env python3
"""main_chat_target_probe — read-only verdict for the demo-1 default surface.

Standard library only. GET-only: never POSTs, never sends chat, never stores
cookies or tokens. One JSON line is printed per probed target.

  python -B scripts/main_chat_target_probe.py           # public kro.kr only
  python -B scripts/main_chat_target_probe.py --local   # local 18180 only
  python -B scripts/main_chat_target_probe.py --both    # both, one line each
  python -B scripts/main_chat_target_probe.py --local --model-purpose smoke

verdict: MAIN_OK | INTERVIEW_OVERRIDE | UNREACHABLE | UNKNOWN
exit 0 iff every probed target is MAIN_OK, else 1 (usage error 2).

--model-purpose <quality|regression|smoke|cross_provider|local_fallback>
adds a per-target "modelPolicy" row resolved via
scripts/test_model_policy.py (configs/agent-test-model-policy.yaml). On the
local target only it also sends ONE synthetic [devin-test] message through
/api/chat/sync, compares the observed modelUsed with the selected route and
records the call in the policy usage log. Public targets are resolve-only —
they are never sent implicitly. --no-send makes even the local target
resolve-only; --catalog/--usage-log accept offline fixtures.

Markers below are unique ids/data-testids read from
main/resources/templates/chat-ui.html; INTERVIEW_MARKER is the interview cover
string in main/resources/static/assets/interview/index.html.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import ssl
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

PUBLIC_URL = "https://abandonwareai.kro.kr/chat"
LOCAL_URL = "http://127.0.0.1:18180/chat"
TIMEOUT_SECONDS = 10
USER_AGENT = "devin-probe/1.0 (main-chat-target; read-only)"

# Unique static markers observed in main/resources/templates/chat-ui.html.
CHAT_UI_MARKERS = (
    'id="chatWindow"',
    'id="chatForm"',
    'data-testid="chat-composer"',
    'data-testid="chat-message-input"',
    'id="historyPanel"',
    'data-testid="chat-new-chat-button"',
)
MIN_MARKERS = 2
CHAT_JS_REF = "/js/chat.js"
INTERVIEW_MARKER = "INTERVIEW DEMO"

KST = timezone(timedelta(hours=9), "KST")


def classify(status, body):
    """Pure verdict logic (no network) so tests can run offline."""
    body = body or ""
    title = ""
    match = re.search(r"<title>(.*?)</title>", body, re.IGNORECASE | re.DOTALL)
    if match:
        title = match.group(1).strip()
    markers = [m for m in CHAT_UI_MARKERS if m in body]
    chat_js = CHAT_JS_REF in body
    interview = INTERVIEW_MARKER in body
    if status is None or (isinstance(status, int) and status >= 500):
        verdict = "UNREACHABLE"
    elif status != 200:
        verdict = "UNKNOWN"
    elif interview:
        verdict = "INTERVIEW_OVERRIDE"
    elif len(markers) >= MIN_MARKERS and chat_js:
        verdict = "MAIN_OK"
    else:
        verdict = "UNKNOWN"
    return {
        "status": status,
        "title": title,
        "chatUiMarkersFound": len(markers),
        "chatJsRef": chat_js,
        "interviewDemoMarker": interview,
        "verdict": verdict,
    }


def model_policy_step(target, origin, purpose, **kwargs):
    """Delegate to test_model_policy (lazy import keeps this probe standalone)."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import test_model_policy
    return test_model_policy.model_policy_step(target, origin, purpose, **kwargs)


def probe(name, url):
    """Single GET. Never raises; errors fold into the result row."""
    row = {
        "tool": "main_chat_target_probe",
        "target": name,
        "url": url,
        "requestedAtKst": datetime.now(KST).isoformat(timespec="seconds"),
    }
    request = urllib.request.Request(url, method="GET",
                                     headers={"User-Agent": USER_AGENT})
    status, body, error = None, "", None
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as resp:
            status = resp.status
            row["finalUrl"] = resp.geturl()
            body = resp.read(2 * 1024 * 1024).decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        try:
            body = exc.read(2 * 1024 * 1024).decode("utf-8", errors="replace")
        except OSError:
            body = ""
        error = f"http-{exc.code}"
    except (urllib.error.URLError, socket.timeout, TimeoutError,
            ssl.SSLError, OSError) as exc:
        error = type(exc).__name__ + ": " + str(getattr(exc, "reason", exc))
    row.update(classify(status, body))
    if error:
        row["error"] = error
    return row


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--local", action="store_true",
                       help="probe http://127.0.0.1:18180/chat only")
    group.add_argument("--both", action="store_true",
                       help="probe public and local")
    parser.add_argument("--model-purpose",
                        help="resolve a test model per agent-test-model-policy")
    parser.add_argument("--catalog", help="offline /api/chat/models fixture")
    parser.add_argument("--usage-log", help="usage.jsonl override")
    parser.add_argument("--run", help="run/ledger id for budget accounting")
    parser.add_argument("--no-send", action="store_true",
                        help="resolve only; never POST /api/chat/sync")
    args = parser.parse_args(argv)
    if args.local:
        targets = [("local", LOCAL_URL)]
    elif args.both:
        targets = [("public", PUBLIC_URL), ("local", LOCAL_URL)]
    else:
        targets = [("public", PUBLIC_URL)]
    results = [probe(name, url) for name, url in targets]
    if args.model_purpose:
        for row in results:
            origin = row["url"].rsplit("/", 1)[0]
            try:
                row["modelPolicy"] = model_policy_step(
                    row["target"], origin, args.model_purpose,
                    catalog_path=args.catalog, usage_log=args.usage_log,
                    run=args.run, send=not args.no_send)
            except Exception as exc:  # verdict row must still print
                row["modelPolicy"] = {"verdict": "STEP_ERROR",
                                      "error": type(exc).__name__}
    for row in results:
        print(json.dumps(row, ensure_ascii=False))
    return 0 if all(r["verdict"] == "MAIN_OK" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
