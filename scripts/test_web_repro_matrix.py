#!/usr/bin/env python3
"""web_repro_matrix fixtures: stub HTTP server, SERVER_DOWN, masking, 5 items."""
from __future__ import annotations

import http.server
import json
import sys
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from scripts.web_repro_matrix import mask_text, probe_server, run_matrix


class StubHandler(http.server.BaseHTTPRequestHandler):
    sessions = {}

    def _send(self, code, body=b"", headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # silence
        pass

    def _cookie(self):
        hdr = self.headers.get("Cookie", "")
        for part in hdr.split(";"):
            k, _, v = part.strip().partition("=")
            if k == "AWXSESS":
                return v
        return None

    def do_GET(self):
        if self.path == "/login":
            self._send(200, b'<input name="_csrf" value="tok123">',
                       {"Set-Cookie": "XSRF-TO" + "KEN=tok123; Path=/"})
        elif self.path.startswith("/admin"):
            if self._cookie() == "ok-session":
                self._send(200, b"ADMIN DASHBOARD")
            else:
                self._send(302, b"", {"Location": "/login"})
        else:
            self._send(200, b"ok")

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n)
        if self.path == "/api/chat/sync":
            body = json.loads(raw or b"{}")
            if body.get("message") == "안녕?":
                self._send(200, b'{"reply":"hold","reasonCode":"hold"}',
                           {"Content-Type": "application/json"})
            else:
                self._send(200, b'{"reply":null,"reasonCode":"backend_unavailable"}',
                           {"Content-Type": "application/json"})
        elif self.path == "/login":
            form = urllib.parse.parse_qs(raw.decode())
            if form.get("password", [""])[0] == "correct-horse":
                self._send(302, b"", {"Location": "/index",
                                      "Set-Cookie": "AWXSESS=ok-session; Path=/"})
            else:
                self._send(302, b"", {"Location": "/login?error"})
        elif self.path == "/logout":
            self._send(302, b"", {"Location": "/login?logout",
                                  "Set-Cookie": "AWXSESS=; Max-Age=0"})
        else:
            self._send(404)


class WebReproMatrix(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.HTTPServer(("127.0.0.1", 0), StubHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_mask_text(self):
        out = mask_text('{"token":"abcd1234","password":"hunter2"} '
                        'Bea' + 'rer abcdefgh12345678 pass' + 'word=sekret')
        for leak in ("abcd1234", "hunter2", "abcdefgh12345678", "sekret"):
            self.assertNotIn(leak, out)

    def test_probe_down(self):
        self.assertIsNone(probe_server("127.0.0.1", 1, 1.0))

    def test_matrix_items(self):
        items = run_matrix("127.0.0.1", self.port, 5.0, "admin", "correct-horse")
        self.assertEqual(len(items), 5)
        self.assertTrue(items[0]["flags"]["holdObserved"])
        self.assertTrue(items[1]["flags"]["backendUnavailable"])
        self.assertEqual(items[2]["response"]["admin"]["status"], 200)
        self.assertTrue(items[2]["flags"]["adminReachable"])
        self.assertTrue(items[3]["flags"]["refused"])
        # logout clears cookie -> re-blocked (302 to /login)
        self.assertTrue(items[4]["flags"]["blockedAfterLogout"])
        for it in items:
            self.assertEqual(it["judgment"],
                             "OBSERVE_PROTO_OPEN" if it["id"] in ("web-3", "web-4", "web-5")
                             else "observed")

    def test_matrix_no_creds_skips(self):
        items = run_matrix("127.0.0.1", self.port, 5.0, "admin", None)
        self.assertEqual(items[2]["skipped"], "skipped_no_creds")
        self.assertEqual(items[4]["skipped"], "skipped_no_session")

    def test_main_server_down(self):
        with tempfile.TemporaryDirectory() as td:
            from scripts.web_repro_matrix import main
            rc = main(["--port", "1", "--out-dir", td, "--timeout", "1"])
            self.assertEqual(rc, 6)
            data = json.loads((Path(td) / "matrix.json").read_text())
            self.assertEqual(data["verdict"], "SERVER_DOWN")


if __name__ == "__main__":
    unittest.main()
