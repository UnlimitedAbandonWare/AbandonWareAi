#!/usr/bin/env python3
"""WP4 web reproduction matrix -- HTTP-level evidence, no browser state reuse.

Five items per the R2 directive:
  1 greeting ("안녕?")           -> HOLD-ish response observed?
  2 general question             -> backend_unavailable reasonCode?
  3 admin login -> /admin/*      -> protected URL actually reached
  4 wrong credentials            -> login refused
  5 logout -> /admin/*           -> session revoked

PROTO_OPEN note (directive): items 3-5 are observation-only under the current
prototype auth posture -- record what happened, never mark FAIL/patch-target.
Item results are `observed` / `OBSERVE_PROTO_OPEN` / `skipped_no_creds` etc.;
they are never a target-health verdict by themselves.

Rules: fresh cookie jar per item (no storageState reuse); connection refused
-> SERVER_DOWN, exit 6, never start the server ourselves. Body kept to 200
chars; Cookie/Set-Cookie/Authorization/token values masked. Writes
var/web-repro/<ts>/matrix.{json,md} (var/ is gitignored).
"""
from __future__ import annotations

import argparse
import http.client
import http.cookies
import json
import os
import re
import sys
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.web-repro-matrix.v1"
MASK_HEADERS = {"set-cookie", "cookie", "authorization", "proxy-authorization",
                "x-xsrf-token", "x-csrf-token"}
TRACE_HEADERS = {"x-request-id", "x-trace-id", "x-correlation-id",
                 "traceparent", "x-run-id", "x-session-id", "x-model-used",
                 "x-user", "x-rag-used"}
SECRET_BODY_PATTERNS = [
    re.compile(r'("(?:token|access_token|refresh_token|apiKey|api_key|'
               r'password|secret|credential)"\s*:\s*")[^"]*(")', re.I),
    re.compile(r'((?:_csrf|password|token|secret)=)[^&\s]+', re.I),
    re.compile(r'(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}'),
]


def mask_text(text: str) -> str:
    out = text
    for pat in SECRET_BODY_PATTERNS:
        out = pat.sub(lambda m: m.group(1) + "***MASKED***" + (m.group(2) if m.lastindex and m.lastindex >= 2 else ""), out)
    return out


class Session:
    """One throwaway HTTP session: own connection + own cookie jar."""

    def __init__(self, host: str, port: int, timeout: float):
        self.host, self.port, self.timeout = host, port, timeout
        self.jar: dict[str, str] = {}

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict | None = None) -> dict:
        conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        hdrs = dict(headers or {})
        if self.jar:
            hdrs["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.jar.items())
        t0 = time.monotonic()
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read(65536)
        elapsed = round((time.monotonic() - t0) * 1000, 1)
        for hdr, val in resp.getheaders():
            if hdr.lower() == "set-cookie":
                c = http.cookies.SimpleCookie(); c.load(val)
                for k, morsel in c.items():
                    self.jar[k] = morsel.value
        kept = {h: v for h, v in resp.getheaders()
                if h.lower() in TRACE_HEADERS and h.lower() not in MASK_HEADERS}
        conn.close()
        return {"status": resp.status, "reason": resp.reason,
                "elapsedMs": elapsed, "traceHeaders": kept,
                "bodyHead": mask_text(raw.decode("utf-8", errors="replace")[:200]),
                "location": resp.getheader("Location")}

    def close(self):
        self.jar.clear()


def _form(data: dict) -> tuple[bytes, dict]:
    enc = urllib.parse.urlencode(data).encode()
    return enc, {"Content-Type": "application/x-www-form-urlencoded"}


def _json(data: dict) -> tuple[bytes, dict]:
    return json.dumps(data).encode(), {"Content-Type": "application/json"}


def fetch_csrf(sess: Session, login_path="/login") -> str | None:
    r = sess.request("GET", login_path)
    if "XSRF-TOKEN" in sess.jar:
        return sess.jar["XSRF-TOKEN"]
    m = re.search(r'name="_csrf"\s+value="([^"]+)"', r.get("bodyHead", ""))
    return m.group(1) if m else None


def probe_server(host: str, port: int, timeout: float) -> dict | None:
    try:
        s = Session(host, port, timeout)
        r = s.request("GET", "/login")
        s.close()
        return r
    except (ConnectionRefusedError, OSError):
        return None


def run_matrix(host: str, port: int, timeout: float, admin_user: str,
               admin_pass: str | None) -> list[dict]:
    items: list[dict] = []

    def add(item_id, name, request_desc, resp=None, flags=None,
            judgment="observed", note=None, skipped=None):
        items.append({
            "id": item_id, "name": name, "request": request_desc,
            "response": resp, "flags": flags or {}, "judgment": judgment,
            "note": note, "skipped": skipped,
        })

    # 1. greeting -> HOLD-ish?
    s1 = Session(host, port, timeout)
    try:
        body, hdrs = _json({"message": "안녕?"})
        r = s1.request("POST", "/api/chat/sync", body, hdrs)
        hold = bool(re.search(r'"hold"|hold":\s*true|reasonCode.*hold|HOLD',
                              r.get("bodyHead", ""), re.I))
        add("web-1", "greeting HOLD", "POST /api/chat/sync {message:안녕?}",
            r, {"holdObserved": hold}, "observed")
    finally:
        s1.close()

    # 2. general question -> backend_unavailable?
    s2 = Session(host, port, timeout)
    try:
        body, hdrs = _json({"message": "what is the capital of France?"})
        r = s2.request("POST", "/api/chat/sync", body, hdrs)
        unavail = "backend_unavailable" in r.get("bodyHead", "")
        add("web-2", "general -> backend_unavailable",
            "POST /api/chat/sync {message:<general>}", r,
            {"backendUnavailable": unavail}, "observed")
    finally:
        s2.close()

    # 3. admin login -> protected URL (observation-only under PROTO_OPEN)
    s3 = Session(host, port, timeout)
    admin_status = None
    try:
        csrf = fetch_csrf(s3)
        if admin_pass is None:
            add("web-3", "admin login -> /admin/dashboard",
                "POST /login + GET /admin/dashboard", None, {},
                "OBSERVE_PROTO_OPEN", "admin creds env absent; item skipped",
                skipped="skipped_no_creds")
        else:
            form, fh = _form({"username": admin_user, "password": admin_pass,
                              **({"_csrf": csrf} if csrf else {})})
            login = s3.request("POST", "/login", form, fh)
            admin = s3.request("GET", "/admin/dashboard")
            admin_status = admin["status"]
            reachable = admin_status == 200 and "login" not in (admin.get("location") or "")
            add("web-3", "admin login -> /admin/dashboard",
                "POST /login + GET /admin/dashboard",
                {"login": {k: v for k, v in login.items() if k != "bodyHead"},
                 "admin": admin},
                {"adminReachable": reachable,
                 "blocked": admin_status in (401, 403) or
                            (admin.get("location") or "").find("login") >= 0},
                "OBSERVE_PROTO_OPEN")
    finally:
        pass  # keep jar for item 5

    # 4. wrong credentials -> refused (fresh session)
    s4 = Session(host, port, timeout)
    try:
        csrf4 = fetch_csrf(s4)
        form, fh = _form({"username": "awx-wrong-user",
                          "password": "awx-wrong-pass-000",
                          **({"_csrf": csrf4} if csrf4 else {})})
        login = s4.request("POST", "/login", form, fh)
        after = s4.request("GET", "/admin/dashboard")
        refused = (login.get("location") or "").find("error") >= 0 or \
            login["status"] in (401, 403) or \
            after["status"] in (401, 403) or \
            (after.get("location") or "").find("login") >= 0
        add("web-4", "wrong creds -> blocked",
            "POST /login bad creds + GET /admin/dashboard",
            {"login": {k: v for k, v in login.items() if k != "bodyHead"},
             "admin": after},
            {"refused": refused}, "OBSERVE_PROTO_OPEN")
    finally:
        s4.close()

    # 5. logout -> re-blocked (needs item-3 session)
    try:
        if admin_pass is None or admin_status is None:
            add("web-5", "logout -> re-blocked",
                "POST /logout + GET /admin/dashboard", None, {},
                "OBSERVE_PROTO_OPEN", "no session from item 3",
                skipped="skipped_no_session")
        else:
            csrf5 = s3.jar.get("XSRF-TOKEN")
            out = s3.request("POST", "/logout",
                             *_form({"_csrf": csrf5} if csrf5 else {}))
            re_admin = s3.request("GET", "/admin/dashboard")
            blocked = re_admin["status"] in (401, 403) or \
                (re_admin.get("location") or "").find("login") >= 0
            add("web-5", "logout -> re-blocked",
                "POST /logout + GET /admin/dashboard",
                {"logout": {k: v for k, v in out.items() if k != "bodyHead"},
                 "admin": re_admin},
                {"blockedAfterLogout": blocked}, "OBSERVE_PROTO_OPEN")
    finally:
        s3.close()

    return items


def to_md(items: list[dict]) -> str:
    lines = ["| id | item | status | judgment | flags |", "|---|---|---|---|---|"]
    for it in items:
        resp = it["response"] or {}
        if isinstance(resp, dict) and "admin" in resp:
            status = f"login={resp['login'].get('status')} admin={resp['admin'].get('status')}"
            if "logout" in resp:
                status = f"logout={resp['logout'].get('status')} admin={resp['admin'].get('status')}"
        else:
            status = str(resp.get("status", "-"))
        flags = ", ".join(f"{k}={v}" for k, v in it["flags"].items()) or "-"
        note = f" ({it['skipped']})" if it.get("skipped") else ""
        lines.append(f"| {it['id']} | {it['name']} | {status} | {it['judgment']}{note} | {flags} |")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="web_repro_matrix")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=18180)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--out-dir", default=None,
                    help="default var/web-repro/<ts> under --root")
    ap.add_argument("--root", default=".")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass-env", default="LMS_LOCAL_ADMIN_PASSWORD",
                    help="env var NAME holding admin password (value never printed)")
    args = ap.parse_args(argv)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = Path(args.out_dir or Path(args.root) / "var" / "web-repro" / stamp)
    out_dir.mkdir(parents=True, exist_ok=True)

    probe = probe_server(args.host, args.port, args.timeout)
    if probe is None:
        result = {"schemaVersion": SCHEMA, "verdict": "SERVER_DOWN",
                  "host": args.host, "port": args.port, "items": []}
        (out_dir / "matrix.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(f"SERVER_DOWN {args.host}:{args.port} connection refused -> {out_dir}")
        return 6

    admin_pass = os.environ.get(args.admin_pass_env)  # value never printed
    items = run_matrix(args.host, args.port, args.timeout,
                       args.admin_user, admin_pass)
    result = {"schemaVersion": SCHEMA, "verdict": "OBSERVED",
              "host": args.host, "port": args.port, "probe": probe,
              "items": items, "protoOpenNote":
              "items 3-5 are observation-only under prototype auth posture"}
    (out_dir / "matrix.json").write_text(
        mask_text(json.dumps(result, ensure_ascii=False, indent=2)), encoding="utf-8")
    (out_dir / "matrix.md").write_text(to_md(items) + "\n", encoding="utf-8")
    print(to_md(items))
    print(f"WEB_REPRO: OBSERVED items={len(items)} -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
