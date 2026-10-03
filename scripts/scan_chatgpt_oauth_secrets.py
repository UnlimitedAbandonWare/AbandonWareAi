#!/usr/bin/env python3
"""scan_chatgpt_oauth_secrets.py - Clean-owned ChatGPT OAuth secret-leak scanner.

PASTE_CLEAN_CHATGPT_OAUTH_ASSIST_RAILS_20260930 WP1. Scans surfaces where
OAuth/session secrets must never persist:

  1. git diff (unstaged + staged added lines of tracked changes)
  2. untracked text files outside .secrets/ (bounded by size/ext/count)
  3. recent log/trace files under logs/ and var/
  4. extra files via --path, or a console-buffer via --stdin

Detected shapes (plaintext secrets only - regex sources never self-match):
  - JWT access tokens        eyJ<seg>.<seg>.<seg>
  - Bearer header values     Bearer <20+ token chars>
  - ChatGPT refresh tokens   rt_<16+ chars>
  - OpenAI API keys          sk-<20+ chars>
  - authorization codes      ?code= / &code= (24+ chars)
  - PKCE code_verifier       code_verifier=<20+ chars> in key:value form

Findings print masked evidence only (sha12 + length, never raw bytes).
Exit 1 blocks on any finding; exit 0 means the scanned surfaces are clean.
No network access. No writes outside stdout.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PATTERNS = [
    ("jwt_access_token",
     re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}")),
    ("bearer_token",
     re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/\-]{20,}={0,3}")),
    ("chatgpt_refresh_token",
     re.compile(r"(?<![A-Za-z0-9_])rt_[A-Za-z0-9_-]{16,}")),
    ("openai_api_key",
     re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}")),
    ("authorization_code",
     re.compile(r"[?&]code=[A-Za-z0-9._~+/\-]{24,}")),
    ("pkce_code_verifier",
     re.compile(r"(?i)code_verifier[\"'\s:=]+[A-Za-z0-9._~+/\-]{20,}")),
]

# Auth-code error words that must not count as leaked codes.
CODE_WORDLIST = re.compile(r"(?i)^(access_denied|invalid_grant|login_required|"
                           r"consent_required|oauth_callback_error|[a-z_]*_error)$")

# Self-described dummy fixtures (detector test inputs like "synthetic...mock")
# are reported as informational instead of blocking. Real tokens never
# self-describe. A run of >=6 consecutive alphabet chars (abc..) is also a
# keyboard-sequence fixture, not a credential.
SYNTHETIC_MARKERS = ("synthetic", "mock", "fixture", "dummy", "example",
                     "sample", "placeholder", "notreal", "fake")
ALPHA_RUN = re.compile(r"abcdef|bcdefg|cdefgh|defghi|efghij|fghijk|ghijkl|"
                       r"hijklm|ijklmn|jklmno|klmnop|lmnopq|mnopqr|nopqrs|"
                       r"opqrst|pqrstu|qrstuv|rstuvw|stuvwx|tuvwxy|uvwxyz",
                       re.IGNORECASE)


def looks_synthetic(token: str) -> bool:
    low = token.lower()
    if any(m in low for m in SYNTHETIC_MARKERS):
        return True
    body = re.sub(r"[^a-z0-9]", "", low)
    return bool(ALPHA_RUN.search(body)) or len(set(body)) <= 3

# Files that legitimately define the detection patterns or own this task.
SELF_ALLOWLIST = {
    "scripts/scan_chatgpt_oauth_secrets.py",
    "scripts/lint_chatgpt_oauth_contract.py",
    "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java",
    ".clinerules/63-demo1-chatgpt-oauth-runtime-rails.md",
}

SKIP_DIRS = (".git/", ".secrets/", ".gradle/", "build/", "out/",
             "node_modules/", "scripts/__pycache__/")
BINARY_EXT = {".bin", ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".ico",
              ".zip", ".gz", ".7z", ".exe", ".dll", ".db", ".mv.db", ".pyc",
              ".pdf", ".woff", ".woff2", ".ttf", ".mp4", ".wav"}
LOG_EXT = {".log", ".txt", ".jsonl", ".out", ".err", ".json", ".ndjson"}

MAX_FILE_BYTES = 256 * 1024
MAX_LOG_BYTES = 512 * 1024
MAX_UNTRACKED = 400
MAX_LOG_FILES = 80
LOG_MAX_AGE_S = 7 * 24 * 3600


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:12]


def scan_lines(lines, surface, path, findings):
    for idx, line in enumerate(lines, 1):
        for name, pat in PATTERNS:
            for m in pat.finditer(line):
                token = m.group(0)
                if name == "authorization_code" and CODE_WORDLIST.match(
                        token.split("=", 1)[1]):
                    continue
                findings.append({
                    "surface": surface, "path": path, "line": idx,
                    "pattern": name, "sha12": sha12(token), "len": len(token),
                    "synthetic": looks_synthetic(token)})


def scan_text(text, surface, path, findings):
    scan_lines(text.splitlines(), surface, path, findings)


def git(args):
    return subprocess.run(["git", "-C", str(ROOT)] + args,
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=120)


def scan_git_diff(findings):
    for label, args in (("git-diff-worktree", ["diff", "--no-color", "-U0"]),
                        ("git-diff-staged", ["diff", "--cached", "--no-color", "-U0"])):
        try:
            out = git(args)
        except Exception as exc:
            print(f"[warn] git {args[1]} unreadable: {exc}")
            continue
        if out.returncode != 0:
            continue
        current = "(diff)"
        added = []
        for line in out.stdout.splitlines():
            if line.startswith("+++ b/"):
                current = line[6:]
            elif line.startswith("+") and not line.startswith("+++"):
                added.append(line[1:])
        if added:
            scan_lines(added, label, current, findings)


def is_skipped(path: str) -> bool:
    p = path.replace("\\", "/").lower()
    if p in SELF_ALLOWLIST:
        return True
    return any(p.startswith(d) or "/" + d.rstrip("/") + "/" in "/" + p
               for d in SKIP_DIRS)


def scan_untracked(findings, counters):
    try:
        out = git(["ls-files", "--others", "--exclude-standard", "-z"])
    except Exception as exc:
        print(f"[warn] untracked listing failed: {exc}")
        return
    if out.returncode != 0 or not out.stdout:
        return
    names = [n for n in out.stdout.split("\0") if n]
    # Leaks come from recent writes: newest untracked files are scanned first.
    def _mtime(n):
        try:
            return (ROOT / n).stat().st_mtime
        except OSError:
            return 0.0
    names.sort(key=_mtime, reverse=True)
    scanned = 0
    for name in names:
        if scanned >= MAX_UNTRACKED:
            counters["untracked_capped"] = True
            break
        rel = name.replace("\\", "/")
        if is_skipped(rel):
            continue
        path = ROOT / rel
        try:
            if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
                counters["skipped"] += 1
                continue
            if path.suffix.lower() in BINARY_EXT:
                counters["skipped"] += 1
                continue
            raw = path.read_bytes()
            if b"\0" in raw[:4096]:
                counters["skipped"] += 1
                continue
            scan_text(raw.decode("utf-8", "replace"), "untracked", rel, findings)
            scanned += 1
        except OSError:
            counters["skipped"] += 1
    counters["untracked_scanned"] = scanned


def scan_logs(findings, counters):
    candidates = []
    for d in ("logs", "var"):
        base = ROOT / d
        if not base.is_dir():
            continue
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                p = Path(dirpath) / f
                rel = p.relative_to(ROOT).as_posix()
                if p.suffix.lower() not in LOG_EXT or is_skipped(rel):
                    continue
                try:
                    st = p.stat()
                except OSError:
                    continue
                if st.st_size > MAX_LOG_BYTES:
                    counters["skipped"] += 1
                    continue
                if time.time() - st.st_mtime > LOG_MAX_AGE_S:
                    continue
                candidates.append((st.st_mtime, p, rel))
    candidates.sort(reverse=True)
    for _mtime, p, rel in candidates[:MAX_LOG_FILES]:
        try:
            raw = p.read_bytes()
            if b"\0" in raw[:4096]:
                counters["skipped"] += 1
                continue
            scan_text(raw.decode("utf-8", "replace"), "log", rel, findings)
            counters["logs_scanned"] += 1
        except OSError:
            counters["skipped"] += 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--path", action="append", default=[],
                    help="extra file to scan (repeatable)")
    ap.add_argument("--stdin", action="store_true",
                    help="read a console/output buffer from stdin")
    args = ap.parse_args()

    findings = []
    counters = {"untracked_scanned": 0, "logs_scanned": 0, "skipped": 0,
                "untracked_capped": False}

    scan_git_diff(findings)
    scan_untracked(findings, counters)
    scan_logs(findings, counters)

    for extra in args.path:
        p = Path(extra)
        try:
            if p.is_file() and p.stat().st_size <= MAX_FILE_BYTES:
                scan_text(p.read_bytes().decode("utf-8", "replace"),
                          "path", str(p), findings)
        except OSError:
            pass
    if args.stdin:
        scan_text(sys.stdin.read(), "stdin", "(console-buffer)", findings)

    real = [f for f in findings if not f["synthetic"]]
    for f in findings:
        tag = "SECRET-FIXTURE" if f["synthetic"] else "SECRET-LEAK"
        print(f"{tag} {f['surface']}:{f['path']}:{f['line']} "
              f"pattern={f['pattern']} sha12={f['sha12']} len={f['len']}")
    print(f"scan: findings={len(real)} fixtures={len(findings) - len(real)} "
          f"untracked={counters['untracked_scanned']} "
          f"logs={counters['logs_scanned']} skipped={counters['skipped']}"
          + (" capped_untracked" if counters["untracked_capped"] else ""))
    return 1 if real else 0


if __name__ == "__main__":
    raise SystemExit(main())
