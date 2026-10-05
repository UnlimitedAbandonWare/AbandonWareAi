"""agy cli-*.log startup-error counters (awx.agy-log-health.v1).

Scans one Antigravity CLI log (default: newest cli-*.log >5KB under
%USERPROFILE%\\.gemini\\antigravity-cli\\log) and emits a JSON report of the
recurring failure signatures plus a one-line KST summary.

Usage:
    python -B scripts\\agy_log_health.py [LOG] [--log PATH] [--out PATH] [--quiet]
"""
import argparse
import datetime
import json
import os
import re
import sys

KST = datetime.timezone(datetime.timedelta(hours=9))
MIN_BYTES = 5120

COUNTERS = {
    "rule_trigger_invalid": re.compile(r"Invalid rule trigger"),
    "git_watcher_fail": re.compile(r"Unable to start git event watcher"),
    "not_logged_in": re.compile(r"not logged into Antigravity", re.IGNORECASE),
    "geminidir_relative": re.compile(r'Failed to resolve GeminiDir "+\.gemini"+'),
    "hook_fail": re.compile(r"pre-invocation hook|PreInvocation.{0,200}fail", re.IGNORECASE),
}
SKILL_RE = re.compile(r"Failed to parse skill file (.+?SKILL\.md)[:\s]")
TRUNC_RE = re.compile(
    r"Rule file .*? truncated by (\d+) bytes \(original (\d+) bytes, limit (\d+) bytes\)")
HOOK_NAME_RE = re.compile(r'hook "?([A-Za-z0-9_.-]+)"?')


def kst_now():
    return datetime.datetime.now(datetime.timezone.utc).astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def redact(line):
    line = re.sub(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", "<email>", line)
    line = re.sub(r"(?i)(api[-_]?key|token|secret|password|authorization|bearer)([ ]*[=:][ ]*)\S+",
                  r"\1\2<x>", line)
    line = re.sub(r"(ya29\.|sk-[A-Za-z0-9]|AIza|ghp_|xox[baprs]-)[A-Za-z0-9_.-]+", "<x>", line)
    line = re.sub(r"[A-Za-z0-9_-]{44,}", "<x>", line)
    return line


def newest_log(logdir, min_bytes=MIN_BYTES):
    best = None
    try:
        names = os.listdir(logdir)
    except OSError:
        return None
    for name in names:
        if not (name.startswith("cli-") and name.endswith(".log")):
            continue
        fp = os.path.join(logdir, name)
        try:
            st = os.stat(fp)
        except OSError:
            continue
        if st.st_size < min_bytes:
            continue
        key = (st.st_mtime, name)
        if best is None or key > best[0]:
            best = (key, fp)
    return best[1] if best else None


def scan_log(path):
    counts = {key: 0 for key in COUNTERS}
    samples = {}
    hook_names = []
    skill_lines = 0
    skill_files = []
    skill_seen = set()
    trunc_lines = 0
    trunc_bytes = trunc_orig = trunc_limit = 0
    nlines = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            nlines += 1
            for key, rx in COUNTERS.items():
                if rx.search(line):
                    counts[key] += 1
                    samples.setdefault(key, redact(line.strip())[:400])
                    if key == "hook_fail":
                        m = HOOK_NAME_RE.search(line)
                        if m and m.group(1) not in hook_names:
                            hook_names.append(m.group(1))
            m = SKILL_RE.search(line)
            if m:
                skill_lines += 1
                samples.setdefault("skill_parse_fail", redact(line.strip())[:400])
                name = os.path.basename(os.path.dirname(m.group(1).replace("/", os.sep)))
                if name not in skill_seen:
                    skill_seen.add(name)
                    skill_files.append(name)
            m = TRUNC_RE.search(line)
            if m:
                trunc_lines += 1
                trunc_bytes = max(trunc_bytes, int(m.group(1)))
                trunc_orig = int(m.group(2))
                trunc_limit = int(m.group(3))
                samples.setdefault("agents_md_truncated", redact(line.strip())[:400])
    return {
        "schemaVersion": "awx.agy-log-health.v1",
        "log": os.path.basename(path),
        "logPath": os.path.abspath(path),
        "logBytes": os.path.getsize(path),
        "lines": nlines,
        "scannedAtKst": kst_now(),
        "rule_trigger_invalid": counts["rule_trigger_invalid"],
        "agents_md_truncated_bytes": trunc_bytes,
        "agents_md_truncated_lines": trunc_lines,
        "agents_md_original_bytes": trunc_orig,
        "agents_md_limit_bytes": trunc_limit,
        "skill_parse_fail": {"count": skill_lines, "files": sorted(skill_files)},
        "git_watcher_fail": counts["git_watcher_fail"],
        "not_logged_in": counts["not_logged_in"],
        "geminidir_relative": counts["geminidir_relative"],
        "hook_fail": counts["hook_fail"],
        "hook_fail_names": sorted(hook_names),
        "samples": samples,
    }


def summary_line(report):
    sp = report["skill_parse_fail"]
    return ("[agy-health] {kst} log={log} rule_trigger_invalid={rti} "
            "agents_md_truncated_bytes={tb} skill_parse_fail={sc}(files={sf}) "
            "git_watcher_fail={gw} not_logged_in={nl} geminidir_relative={gd} "
            "hook_fail={hf}").format(
                kst=report["scannedAtKst"], log=report["log"],
                rti=report["rule_trigger_invalid"], tb=report["agents_md_truncated_bytes"],
                sc=sp["count"], sf=len(sp["files"]), gw=report["git_watcher_fail"],
                nl=report["not_logged_in"], gd=report["geminidir_relative"],
                hf=report["hook_fail"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("log", nargs="?", default=None, help="cli-*.log path")
    parser.add_argument("--log", dest="log_opt", default=None)
    parser.add_argument("--logdir", default=os.path.join(
        os.path.expanduser("~"), ".gemini", "antigravity-cli", "log"))
    parser.add_argument("--out", default=os.path.join("var", "agy-health", "latest.json"))
    parser.add_argument("--min-bytes", type=int, default=MIN_BYTES)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    path = args.log or args.log_opt
    if not path:
        path = newest_log(args.logdir, args.min_bytes)
    if not path or not os.path.isfile(path):
        print("[agy-health] no cli-*.log found", file=sys.stderr)
        return 2

    report = scan_log(path)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
    if not args.quiet:
        print(summary_line(report) + " out=" + (args.out or "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
