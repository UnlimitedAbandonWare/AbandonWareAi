"""127.0.0.1 Gemini mock. Bodies are synthetic. No outbound call.

apikit cannot point here until AWX_GEMINI_BASE is applied by Devin.
The override proposal allows only 127.0.0.1 and localhost.
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODES = (
    "ok", "ok_wrong", "quota_429", "billing_403", "key_invalid_400",
    "model_404", "hang", "malformed_json", "over_256_tokens", "models_list",
)


class MockServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def _payload(mode: str) -> tuple[int, bytes]:
    if mode == "ok":
        body = {
            "candidates": [{"content": {"parts": [{"text": "{\"choice\":\"WEB\"}"}]}}],
            "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 5, "totalTokenCount": 8},
        }
        return 200, json.dumps(body).encode("utf-8")
    if mode == "ok_wrong":
        body = {
            "candidates": [{"content": {"parts": [{"text": "{\"choice\":\"NONE\"}"}]}}],
            "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 4, "totalTokenCount": 7},
            "syntheticSchemaValid": True,
            "syntheticMeaning": "wrong",
        }
        return 200, json.dumps(body).encode("utf-8")
    if mode == "quota_429":
        body = {"error": {"status": "RESOURCE_EXHAUSTED", "message": "quota",
                          "details": [{"retryDelay": "1s"}]}}
        return 429, json.dumps(body).encode("utf-8")
    if mode == "billing_403":
        body = {"error": {"status": "PERMISSION_DENIED", "message": "billing"}}
        return 403, json.dumps(body).encode("utf-8")
    if mode == "key_invalid_400":
        body = {"error": {"status": "INVALID_ARGUMENT", "details": [{"reason": "API_KEY_INVALID"}]}}
        return 400, json.dumps(body).encode("utf-8")
    if mode == "model_404":
        body = {"error": {"status": "NOT_FOUND", "message": "model"}}
        return 404, json.dumps(body).encode("utf-8")
    if mode == "malformed_json":
        return 200, b"{not-json"
    if mode == "over_256_tokens":
        body = {
            "candidates": [{"content": {"parts": [{"text": "{}"}]}}],
            "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 300, "totalTokenCount": 301},
        }
        return 200, json.dumps(body).encode("utf-8")
    if mode == "models_list":
        body = {"models": [{
            "name": "models/gemini-2.0-flash-lite",
            "supportedGenerationMethods": ["generateContent"],
            "synthetic": True,
        }]}
        return 200, json.dumps(body).encode("utf-8")
    return 500, b"{}"


def make_handler(mode: str, hang_ms: int):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, payload: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self) -> None:  # noqa: N802
            if mode == "hang":
                time.sleep((hang_ms if hang_ms > 0 else 1000) / 1000.0)
                self.close_connection = True
                return
            status, payload = _payload("models_list" if mode == "models_list" else mode)
            if mode != "models_list" and self.path.startswith("/v1beta/models") and mode == "ok":
                status, payload = _payload("models_list")
            self._send(status, payload)

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            if mode == "hang":
                time.sleep((hang_ms if hang_ms > 0 else 1000) / 1000.0)
                self.close_connection = True
                return
            status, payload = _payload(mode)
            self._send(status, payload)

        def log_message(self, fmt: str, *args) -> None:
            return

    return Handler


def serve(mode: str = "ok", port: int = 0, hang_ms: int = 0) -> MockServer:
    if mode not in MODES:
        raise ValueError("mode-not-allowed")
    server = MockServer(("127.0.0.1", port), make_handler(mode, hang_ms))
    if server.server_address[0] != "127.0.0.1":
        server.server_close()
        raise RuntimeError("bind-not-loopback")
    return server


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="127.0.0.1 Gemini mock. No credential logging.")
    parser.add_argument("--mode", choices=MODES, default="ok")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--hang-ms", type=int, default=0)
    args = parser.parse_args(argv)
    server = serve(args.mode, args.port, args.hang_ms)
    print(json.dumps({"bind": "127.0.0.1", "port": server.server_address[1], "mode": args.mode},
                     ensure_ascii=True))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        thread.join()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
