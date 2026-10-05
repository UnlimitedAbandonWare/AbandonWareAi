#!/usr/bin/env python3
"""lint_chatgpt_oauth_contract.py - Clean-owned ChatGPT OAuth contract linter.

PASTE_CLEAN_CHATGPT_OAUTH_ASSIST_RAILS_20260930 WP3. Validates the runtime
contract values the probe measured:

  host-id      ext_agent_host_id must be a URN:
               urn:uuid:<uuid> | urn:ietf:params:oauth:jwk-thumbprint:... |
               did:key:...   (plain strings are 400-rejected)
  redirect     must be exactly http://127.0.0.1:1455/auth/callback
               (loopback host, port 1455, path /auth/callback, no query)
  scope        must include all of:
               openid profile email offline_access resource.invoke
               chatgpt.tokens.use.direct
  literals     scans main/java + main/resources for ChatGPT-OAuth literals;
               any embedded redirect must satisfy the redirect rule and any
               embedded secret (JWT/rt_/sk-/Bearer) is an instant FAIL.

Sources checked, in order: --host-id/--redirect-uri/--scope, env vars
CHATGPT_OAUTH_HOST_ID / CHATGPT_OAUTH_REDIRECT_URI / CHATGPT_OAUTH_SCOPE, and
.secrets/chatgpt_oauth_hostid.txt + .secrets/chatgpt_oauth_credentials.json.
Values are never printed - presence/shape only.

--offline checks explicit synthetic CLI values only; it never reads environment
variables, credentials, or product source. source-literals is SKIP/not_observed.
Exit 0 means only the supplied shapes passed (or were absent), not deployment,
account availability, or source proof. --strict still fails on every SKIP.

Verdicts: PASS / SKIP (not_observed; --strict upgrades SKIP to FAIL) / FAIL.
Exit 1 on any FAIL; exit 0 otherwise. Read-only; no network.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent
SECRETS = ROOT / ".secrets"
HOST_ID_FILE = SECRETS / "chatgpt_oauth_hostid.txt"
CRED_FILE = SECRETS / "chatgpt_oauth_credentials.json"

HOST_ID_PREFIXES = ("urn:uuid:", "urn:ietf:params:oauth:jwk-thumbprint:", "did:key:")
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                     r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
REQUIRED_SCOPES = {"openid", "profile", "email", "offline_access",
                   "resource.invoke", "chatgpt.tokens.use.direct"}
EXPECT_HOSTS = {"127.0.0.1", "localhost", "[::1]", "::1"}
EXPECT_PORT = 1455
EXPECT_PATH = "/auth/callback"

SECRET_PATTERNS = [
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}")),
    ("refresh_token", re.compile(r"(?<![A-Za-z0-9_])rt_[A-Za-z0-9_-]{16,}")),
    ("openai_key", re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}")),
    ("bearer", re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/\-]{20,}={0,3}")),
]
OAUTH_HINTS = re.compile(
    r"(?i)(ext_agent_host_id|chatgpt\.tokens|auth\.openai\.com|"
    r"/auth/callback|chatgpt_oauth)")
CALLBACK_LITERAL = re.compile(r"https?://[^\s\"']*auth/callback[^\s\"']*")

results = []


def verdict(name, status, detail):
    results.append((name, status, detail))
    print(f"[{status:4}] {name}: {detail}")


def read_secret_file(path: Path):
    try:
        if path.is_file():
            return path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        pass
    return None


def load_cred():
    raw = read_secret_file(CRED_FILE)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        verdict("cred-file", "FAIL", ".secrets/chatgpt_oauth_credentials.json is not valid JSON")
        return {}


def check_host_id(cli, cred, *, runtime=True):
    value = cli
    if runtime and not value:
        value = os.environ.get("CHATGPT_OAUTH_HOST_ID") or read_secret_file(HOST_ID_FILE)
    if isinstance(cred, dict) and not value:
        v = cred.get("ext_agent_host_id") or cred.get("host_id")
        value = v.strip() if isinstance(v, str) else None
    if not value:
        verdict("host-id", "SKIP", "no host id configured (not_observed)")
        return
    ok = any(value.startswith(p) for p in HOST_ID_PREFIXES)
    if ok and value.startswith("urn:uuid:"):
        ok = bool(UUID_RE.match(value[len("urn:uuid:"):].strip()))
    verdict("host-id", "PASS" if ok else "FAIL",
            f"prefix={next((p for p in HOST_ID_PREFIXES if value.startswith(p)), 'none')} "
            f"len={len(value)} sha12={_sha12(value)}"
            + ("" if ok else " - must be urn:uuid:/jwk-thumbprint/did:key URN"))


def validate_redirect(uri):
    try:
        u = urlparse(uri.strip())
    except Exception:
        return False, "unparseable"
    problems = []
    if u.scheme != "http":
        problems.append("scheme!=" + u.scheme)
    if (u.hostname or "") not in EXPECT_HOSTS:
        problems.append("host!=" + str(u.hostname))
    if u.port != EXPECT_PORT:
        problems.append("port!=" + str(u.port))
    if u.path != EXPECT_PATH:
        problems.append("path!=" + u.path)
    if u.query or u.fragment or u.username or u.password:
        problems.append("extra query/fragment/userinfo")
    return (not problems), ",".join(problems)


def check_redirect(cli, cred, *, runtime=True):
    value = cli
    if runtime and not value:
        value = os.environ.get("CHATGPT_OAUTH_REDIRECT_URI")
    if isinstance(cred, dict) and not value:
        v = cred.get("redirect_uri")
        value = v.strip() if isinstance(v, str) else None
    if not value:
        verdict("redirect-uri", "SKIP", "no redirect uri configured (not_observed)")
        return
    ok, detail = validate_redirect(value)
    verdict("redirect-uri", "PASS" if ok else "FAIL",
            f"host={urlparse(value).hostname} port={urlparse(value).port} "
            f"path={urlparse(value).path}" + ("" if ok else f" - {detail}"))


def check_scope(cli, cred, *, runtime=True):
    value = cli
    if runtime and not value:
        value = os.environ.get("CHATGPT_OAUTH_SCOPE")
    if isinstance(cred, dict) and not value:
        v = cred.get("scope")
        value = v.strip() if isinstance(v, str) else None
    if not value:
        verdict("scope", "SKIP", "no scope configured (not_observed)")
        return
    have = set(value.split())
    missing = sorted(REQUIRED_SCOPES - have)
    verdict("scope", "PASS" if not missing else "FAIL",
            f"count={len(have)}" + ("" if not missing else f" missing={missing}"))


def check_source_literals():
    hits, secret_hits, bad_redirect = [], [], []
    for base in ("main/java", "main/resources"):
        root = ROOT / base
        if not root.is_dir():
            continue
        for p in root.rglob("*"):
            if not p.is_file() or p.suffix.lower() not in (
                    ".java", ".yml", ".yaml", ".properties", ".json", ".xml", ".html"):
                continue
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if not OAUTH_HINTS.search(text):
                continue
            rel = p.relative_to(ROOT).as_posix()
            hits.append(rel)
            for name, pat in SECRET_PATTERNS:
                if pat.search(text):
                    secret_hits.append(f"{rel}:{name}")
            for lit in CALLBACK_LITERAL.findall(text):
                ok, detail = validate_redirect(lit)
                if not ok:
                    bad_redirect.append(f"{rel}({detail})")
    for s in secret_hits:
        verdict("source-secret", "FAIL", f"secret-shaped literal in product source: {s}")
    for b in bad_redirect:
        verdict("source-redirect", "FAIL", f"non-contract callback URI in product source: {b}")
    verdict("source-literals", "PASS" if not (secret_hits or bad_redirect) else "FAIL",
            f"oauth-related files={len(hits)}"
            + (" (none configured yet)" if not hits else ""))


def _sha12(text):
    import hashlib
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host-id")
    ap.add_argument("--redirect-uri")
    ap.add_argument("--scope")
    ap.add_argument("--strict", action="store_true",
                    help="treat SKIP (not configured) as FAIL")
    ap.add_argument("--offline", action="store_true",
                    help="synthetic CLI values only; no env/credential/source reads; "
                         "source proof SKIP, not deployment/account eligibility")
    args = ap.parse_args(argv)
    results.clear()

    cred = None if args.offline else load_cred()
    check_host_id(args.host_id, cred, runtime=not args.offline)
    check_redirect(args.redirect_uri, cred, runtime=not args.offline)
    check_scope(args.scope, cred, runtime=not args.offline)
    if args.offline:
        verdict("source-literals", "SKIP", "offline: product source not read (not_observed)")
    else:
        check_source_literals()

    fails = sum(1 for _, s, _ in results if s == "FAIL")
    skips = sum(1 for _, s, _ in results if s == "SKIP")
    print(f"lint: pass={sum(1 for _, s, _ in results if s == 'PASS')} "
          f"skip={skips} fail={fails}")
    if args.strict and skips:
        print("strict mode: SKIP counts as FAIL")
        return 1
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
