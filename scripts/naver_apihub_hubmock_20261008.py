"""Loopback NAVER API HUB stub for Codex brief codex-naver-apihub-search-50e1ba15.

Binds 127.0.0.1 only. Lets the W7 live-path check run end-to-end with synthetic
credentials and zero real external calls (does not burn the 5-call live budget):

    set NAVER_APIHUB_BASE_URL=http://127.0.0.1:18299
    set NAVER_APIHUB_CLIENT_ID=synthetic-hub-id
    set NAVER_APIHUB_CLIENT_SECRET=synthetic-hub-secret
    (restart app, ask a web-search question on 127.0.0.1:18180/chat)

    python -B scripts/naver_apihub_hubmock_20261008.py --port 18299 --mode ok

Modes: ok | empty | auth401 | forbid403 | rate429 | bad | slow.
Every hit is appended to the hits JSONL with header NAMES and presence booleans
only — header VALUES and the query text are never recorded. A hit here proves
wire wiring only; it is not a real-NAVER PASS.

Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl

ROOT = Path(__file__).resolve().parents[1]
BODY_OK = {
    "lastBuildDate": "mock",
    "total": 1,
    "start": 1,
    "display": 1,
    "items": [{
        "title": "MOCK-HUB synthetic evidence",
        "link": "https://example.org/mock-hub-item",
        "description": "synthetic loopback item; not a real NAVER result",
    }],
}
STATUS_BODY = {
    "auth401": (401, {"errorCode": "024", "errorMessage": "MOCK authentication failed"}),
    "forbid403": (403, {"errorCode": "999", "errorMessage": "MOCK forbidden"}),
    "rate429": (429, {"errorCode": "012", "errorMessage": "MOCK call limit exceeded"}),
    "bad": (200, None),          # malformed JSON body
    "empty": (200, {"lastBuildDate": "mock", "total": 0, "start": 1,
                    "display": 0, "items": []}),
}
WEB_PATHS = ("/search/v1/webkr", "/v1/search/webkr.json")
OTHER_SEARCH = ("/search/v1/blog", "/search/v1/news", "/search/v1/errata")


def now_iso():
    return datetime.now(timezone.utc).isoformat()


class Handler(BaseHTTPRequestHandler):
    server_version = "NaverApiHubMock/20261008"
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        return

    def _record(self, params, header_names):
        srv = self.server
        query_len = 0
        safe_params = {}
        for key, value in params:
            if key == "query":
                query_len = len(value)
                safe_params["query"] = "len=%d" % query_len
            else:
                safe_params[key] = value[:40]
        lowered = {name.lower() for name in header_names}
        record = {
            "ts": now_iso(),
            "path": urlsplit(self.path).path,
            "params": safe_params,
            "headerNames": sorted(header_names),
            "hubKeyIdPresent": "x-ncp-apigw-api-key-id" in lowered,
            "hubKeyPresent": "x-ncp-apigw-api-key" in lowered,
            "naverHeadersPresent": ("x-naver-client-id" in lowered
                                    or "x-naver-client-secret" in lowered),
            "mode": srv.mode,
        }
        srv.records.append(record)
        line = json.dumps(record, ensure_ascii=False)
        print("HIT " + line, flush=True)
        try:
            srv.hits_path.parent.mkdir(parents=True, exist_ok=True)
            with srv.hits_path.open("a", encoding="utf-8") as fh:
                fh.write(line + "\n")
        except OSError as exc:
            print("hits-file-write-failed:%s" % type(exc).__name__, flush=True)

    def _send(self, status, payload, raw=None):
        body = raw if raw is not None else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        srv = self.server
        parsed = urlsplit(self.path)
        params = parse_qsl(parsed.query)
        header_names = list(self.headers.keys())
        if parsed.path == "/__stats":
            self._send(200, {
                "mode": srv.mode,
                "hits": len(srv.records),
                "last": srv.records[-1] if srv.records else None,
            })
            return
        if parsed.path in OTHER_SEARCH:
            self._send(501, {"error": "mock-scope",
                             "note": "blog/news/errata are out of this brief's scope"})
            srv.records.append({"ts": now_iso(), "path": parsed.path,
                                "mode": "out-of-scope"})
            return
        if parsed.path not in WEB_PATHS:
            self._send(404, {"error": "unmapped-path"})
            return
        self._record(params, header_names)
        if srv.strict and not (
                "x-ncp-apigw-api-key-id" in {h.lower() for h in header_names}
                and "x-ncp-apigw-api-key" in {h.lower() for h in header_names}):
            self._send(401, {"errorCode": "024",
                             "errorMessage": "MOCK strict: hub headers missing"})
        elif srv.mode == "ok":
            self._send(200, BODY_OK)
        elif srv.mode == "slow":
            time.sleep(srv.delay)
            self._send(200, BODY_OK)
        elif srv.mode == "bad":
            self._send(200, None, raw=b"this-is-not-json")
        else:
            status, payload = STATUS_BODY[srv.mode]
            self._send(status, payload)
        srv.served += 1
        if srv.served >= srv.max_requests:
            print("max-requests reached; shutting down", flush=True)
            threading.Thread(target=srv.shutdown, daemon=True).start()


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="127.0.0.1 NAVER API HUB mock")
    parser.add_argument("--port", type=int, default=18299)
    parser.add_argument("--mode", default="ok",
                        choices=["ok", "empty", "auth401", "forbid403",
                                 "rate429", "bad", "slow"])
    parser.add_argument("--delay", type=float, default=2.0,
                        help="seconds for --mode slow (capped at 10)")
    parser.add_argument("--max-requests", type=int, default=20,
                        help="auto-shutdown after N webkr hits")
    parser.add_argument("--strict", action="store_true",
                        help="401 unless both X-NCP-APIGW-API-KEY* headers arrive")
    parser.add_argument("--hits", default=None,
                        help="hits JSONL path (default var/naver-apihub-mock/)")
    args = parser.parse_args(argv)
    delay = min(max(args.delay, 0.0), 10.0)
    hits = Path(args.hits) if args.hits else (
        ROOT / "var" / "naver-apihub-mock" / ("hits-%d.jsonl" % args.port))
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.mode = args.mode
    server.delay = delay
    server.max_requests = max(1, args.max_requests)
    server.strict = args.strict
    server.hits_path = hits
    server.records = []
    server.served = 0
    print(json.dumps({
        "schemaVersion": "awx.naver-apihub-mock.v1",
        "listening": "http://127.0.0.1:%d" % args.port,
        "mode": args.mode,
        "strict": args.strict,
        "hitsFile": str(hits),
        "note": "header names + presence booleans only; values never recorded",
    }, ensure_ascii=False), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
