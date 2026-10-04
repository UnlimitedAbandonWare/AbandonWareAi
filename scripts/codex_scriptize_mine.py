#!/usr/bin/env python3
"""Codex 세션 "스크립트화 후보" 채굴기 (읽기 전용, 스트리밍).

~/.codex/sessions 의 rollout-*.jsonl 을 한 줄씩 읽어, 같은 턴 안에서 연속으로
손 실행되는 명령 모양(shape) 묶음(2~6-gram)을 세고, 여러 세션에서 반복된 묶음만
점수를 매겨 bat/ps1/py 로 만들 가치가 있는 후보를 뽑는다.

사용:
    python -B scripts/codex_scriptize_mine.py
        [--sessions-dir PATH]   (기본 ~/.codex/sessions)
        [--days N]              (기본 7; 파일명 날짜 창)
        [--max-files N]         (기본 250; 최신순)
        [--max-mb-per-file N]   (기본 0=무제한, 그래도 스트리밍)
        [--cwd-filter STR]      (기본 AbandonWare\\demo-1; 빈 문자열이면 전부 포함)
        [--min-sessions N]      (기본 2)
        [--min-count N]         (기본 3)
        [--out-dir PATH]        (기본 <repo>/var/codex-assist-scriptize)
        [--json-out PATH]       (지정 시 그 경로로; .md 는 같은 이름으로)

절대 저장하지 않는 것: 세션 원문, 명령 출력, 사용자 메시지 본문.
명령은 정규화된 "모양"과 마스킹된 160자 이하 예시 1개만 남긴다.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
try:  # git_staged_guard 의 비밀 패턴 재사용 (import 실패 시에도 동작)
    from git_staged_guard import PATTERNS as SECRET_PATTERNS
except Exception:  # pragma: no cover
    SECRET_PATTERNS = ()

# --- 파일 선택 ---------------------------------------------------------------
FILENAME_DATE = re.compile(r"rollout-(\d{4})-(\d{2})-(\d{2})T")
UUID_IN_NAME = re.compile(
    r"([0-9a-fA-F]{8})-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def file_date(path: Path):
    m = FILENAME_DATE.search(path.name)
    if not m:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def iter_rollout_files(sessions_dir: Path, days: int, max_files: int):
    today = date.today()
    lo, hi = today - timedelta(days=days - 1), today + timedelta(days=1)
    out = []
    for p in sessions_dir.rglob("rollout-*.jsonl"):
        d = file_date(p)
        if d is not None and lo <= d <= hi:
            out.append(p)
    out.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return out[:max_files]


def session_id8(path: Path) -> str:
    m = UUID_IN_NAME.search(path.stem)
    return m.group(1)[:8] if m else path.stem[:8]


# --- JS 입력에서 exec_command cmd 추출 ----------------------------------------
EXEC_CMD_RE = re.compile(
    r"exec_command\s*\(\s*\{.{0,400}?cmd\s*:\s*"
    r"('(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|`(?:\\.|[^`\\])*`)",
    re.DOTALL,
)
TOOL_USE_RE = re.compile(r"tools\.([A-Za-z_]\w*)\s*\(")


def js_unescape(lit: str) -> str:
    """JS 문자열 리터럴(따옴표 포함)을 실제 문자열로 푼다."""
    if not lit:
        return ""
    body = lit[1:-1] if lit[0] in "\"'`" and lit[-1] == lit[0] else lit

    def rep(m):
        esc = m.group(1)
        table = {"n": "\n", "r": "\r", "t": "\t", '"': '"', "'": "'", "`": "`", "\\": "\\", "/": "/"}
        if esc in table:
            return table[esc]
        if esc.startswith("u") and len(esc) == 5:
            try:
                return chr(int(esc[1:], 16))
            except ValueError:
                return "\\" + esc
        return esc  # 알 수 없는 이스케이프는 문자 그대로

    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", rep, body)


def extract_cmds_from_input(input_text: str):
    """exec JS 입력에서 모든 exec_command cmd 값을 순서대로 반환."""
    cmds = []
    for m in EXEC_CMD_RE.finditer(input_text or ""):
        cmds.append(js_unescape(m.group(1)))
    return cmds


def split_statements(cmd: str):
    """하나의 cmd 문자열 안의 연속 구문을 분리한다(따옴표 안 ; && 줄바꿈 보호)."""
    parts, cur = [], []
    squote = dquote = False
    i, n = 0, len(cmd)
    while i < n:
        ch = cmd[i]
        if dquote:
            cur.append(ch)
            if ch == '"':
                dquote = False
        elif squote:
            cur.append(ch)
            if ch == "'":
                squote = False
        elif ch == '"':
            dquote = True
            cur.append(ch)
        elif ch == "'":
            squote = True
            cur.append(ch)
        elif ch in "\n" or ch == ";" or (ch == "&" and i + 1 < n and cmd[i + 1] == "&"):
            s = "".join(cur).strip()
            if s:
                parts.append(s)
            cur = []
            if ch == "&":
                i += 1
        else:
            cur.append(ch)
        i += 1
    s = "".join(cur).strip()
    if s:
        parts.append(s)
    return parts


# --- 마스킹 -------------------------------------------------------------------
ENV_SECRET_RE = re.compile(
    r"(\$env:\w*(?:KEY|TOKEN|SECRET|PASSWORD|PWD|CREDENTIAL|API)\w*"
    r"\s*=\s*)(\"[^\"\n]*\"|'[^'\n]*'|\S+)",
    re.IGNORECASE,
)
ASSIGN_SECRET_RE = re.compile(
    r"(?i)\b(api[-_]?key|client[-_]?secret|owner[-_]?token|access[-_]?token|password|authorization)"
    r"(\s*[:=]\s*)(\"[^\"\n]*\"|'[^'\n]*'|\S{8,})"
)
_HOME = os.path.expanduser("~")
_HOME_BASE = os.path.basename(_HOME)


def mask_secrets(text: str) -> str:
    """비밀 값 조각을 지운다. PATTERNS 매치는 앞 4자 + … 로."""
    if not text:
        return ""
    out = ENV_SECRET_RE.sub(r"\1<STR>", text)
    out = ASSIGN_SECRET_RE.sub(lambda m: m.group(1) + m.group(2) + "<STR>", out)
    raw = out.encode("utf-8", "replace")
    for _name, pat in SECRET_PATTERNS:
        def _r(m):
            return m.group(0)[:4] + b"..."
        raw = re.sub(pat, _r, raw)
    out = raw.decode("utf-8", "replace")
    return out


def mask_example(cmd: str, limit: int = 160) -> str:
    """결과에 남길 한 줄 예시: 비밀 마스킹 + env 이름 일반화 + 홈 경로 일반화 + 길이 제한."""
    out = mask_secrets(cmd)
    out = ENV_NAME_RE.sub("$env:<ENV>", out)
    if _HOME_BASE:
        out = re.sub(re.escape(_HOME), "<HOME>", out, flags=re.IGNORECASE)
        out = re.sub(r"[A-Za-z]:[\\/]+Users[\\/]+" + re.escape(_HOME_BASE), "<HOME>", out, flags=re.IGNORECASE)
    out = re.sub(r"\s+", " ", out).strip()
    # 마스킹 후에도 패턴에 걸리면 예시를 버린다(방어).
    raw = out.encode("utf-8", "replace")
    if SECRET_PATTERNS and any(re.search(p, raw) for _n, p in SECRET_PATTERNS):
        return "<withheld:secret-pattern>"
    return out[:limit]


# --- 정규화(명령 모양) ----------------------------------------------------------
DATE_RE = re.compile(r"\b\d{4}[-/]\d{1,2}[-/]\d{1,2}\b|\b\d{8}\b")
UUID_RE = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
HEX_RE = re.compile(r"\b[0-9a-fA-F]{24,64}\b")
NUM_RE = re.compile(r"\b\d+(?:\.\d+)*\b")
ENV_NAME_RE = re.compile(r"\$env:[A-Za-z_]\w*")
WS_RE = re.compile(r"\s+")

# 알려진 스크립트 확장자: 구분자 없는 파일명 토큰은 <NAME>.ext 로.
KNOWN_EXTS = (
    "py", "ps1", "psm1", "bat", "cmd", "js", "mjs", "java", "json", "jsonl",
    "md", "txt", "log", "yml", "yaml", "xml", "properties", "db", "zip", "csv",
)


def _ext_of(token: str) -> str:
    base = token.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    if "." in base:
        ext = base.rsplit(".", 1)[-1].lower()
        if 1 <= len(ext) <= 12 and ext.isalnum():
            return "." + ext
    return ""


def _norm_token(tok: str) -> str:
    if not tok or tok in ("<STR>", "<PATH>", "<N>", "<ID>", "<DATE>", "<NAME>"):
        return tok
    if re.fullmatch(r"<[A-Z]+>(?:\.[A-Za-z0-9]+)?", tok):
        return tok
    if "/" in tok or "\\" in tok or re.match(r"^[A-Za-z]:", tok):
        # 경로 모양 토큰: 따옴표·접두 장식 제거 후 확장자만 보존
        return "<PATH>" + _ext_of(tok)
    if re.fullmatch(r"[\w.+-]*\.[A-Za-z0-9]{1,12}", tok) and tok.rsplit(".", 1)[-1].lower() in KNOWN_EXTS:
        return "<NAME>" + _ext_of(tok)
    return NUM_RE.sub("<N>", tok)


def normalize(cmd: str) -> str:
    """명령 문자열 → 모양. 비밀 값 → <STR>, 경로 → <PATH>.ext, 숫자 → <N>."""
    # 셸 연산자 간격은 플레이스홀더 생성 전에 처리(<PATH> 의 '>' 오인 방지)
    s = re.sub(r"\s*([|>]{1,2})\s*", r" \1 ", cmd)
    s = mask_secrets(s)
    s = ENV_NAME_RE.sub("$env:<ENV>", s)
    # 따옴표 문자열: 내용이 경로 모양이면 <PATH>.ext, 아니면 <STR>
    def qrep(m):
        inner = m.group(0)[1:-1]
        if "/" in inner or "\\" in inner or re.match(r"^[A-Za-z]:", inner):
            return "<PATH>" + _ext_of(inner)
        return "<STR>"
    s = re.sub(r"\"[^\"\n]{0,400}\"|'[^'\n]{0,400}'", qrep, s)
    s = DATE_RE.sub("<DATE>", s)
    s = UUID_RE.sub("<ID>", s)
    s = HEX_RE.sub("<ID>", s)
    toks = [_norm_token(t) for t in WS_RE.split(s.strip()) if t]
    return WS_RE.sub(" ", " ".join(toks)).strip()


# --- 위험 분류 -----------------------------------------------------------------
STATE_CHANGE_RE = re.compile(
    r"\bgit\b[^|;&]*\b(?:add|commit|push|reset|checkout|restore|stash|clean|merge|rebase|cherry-pick|rm|mv)\b"
    r"|\b(?:Remove-Item|Move-Item|Set-Content|Out-File|Add-Content|Rename-Item)\b"
    r"|\bapply_patch\b|\bStop-Process\b|\btaskkill\b|\bgradle\w*\b|\bgradlew\b",
    re.IGNORECASE,
)


def classify_risk(raw_cmds) -> str:
    return "state-change" if any(STATE_CHANGE_RE.search(c) for c in raw_cmds) else "read-only"


# --- 한 파일 스캔 ---------------------------------------------------------------
def _rec_ts(rec) -> float:
    ts = rec.get("timestamp")
    if not ts:
        return 0.0
    try:
        return datetime.fromisoformat(str(ts).replace("Z", "+00:00")).timestamp()
    except Exception:
        return 0.0


def _output_text(output) -> str:
    if isinstance(output, str):
        return output
    if isinstance(output, list):
        return "\n".join(x.get("text", "") for x in output if isinstance(x, dict))
    if isinstance(output, dict):
        return output.get("output") or output.get("text") or ""
    return ""


FAIL_RE = re.compile(
    r"script failed|script error|\"exit_code\"\s*:\s*-?[1-9]|exit code[:\s]+-?[1-9]"
    r"|\"status\"\s*:\s*\"failed\"|exception|traceback \(most recent",
    re.IGNORECASE,
)
TRUNC_RE = re.compile(r"truncated output|\[truncated|original_token_count", re.IGNORECASE)


def scan_file(path: Path, cwd_filter: str, max_mb: float, counters: Counter):
    """rollout 한 개를 스트리밍 스캔해 per-file 결과 dict 를 반환."""
    sid = session_id8(path)
    cwd = None
    turns = []           # [[occ_index,...], ...] 턴별 명령 occurrence 인덱스
    cur_seq = []
    occs = []            # {shape, raw, ts, elapsed, out_len, truncated, failed}
    pending = {}         # call_id -> {"occ":[idx...], "ts":t, "kind":...}
    fn_counts = Counter()
    tool_js_counts = Counter()
    last_ts = 0.0
    bytes_read = 0
    max_bytes = int(max_mb * 1024 * 1024) if max_mb else 0

    def flush_turn():
        nonlocal cur_seq
        if cur_seq:
            turns.append(cur_seq)
            cur_seq = []

    def add_occ(raw_cmd, ts):
        occs.append({
            "shape": normalize(raw_cmd), "raw": raw_cmd, "ts": ts,
            "elapsed": None, "out_len": 0, "truncated": False, "failed": False,
        })
        idx = len(occs) - 1
        cur_seq.append(idx)
        return idx

    with io.open(path, "rb") as fh:
        for raw_line in fh:
            bytes_read += len(raw_line)
            if max_bytes and bytes_read > max_bytes:
                counters["files_size_limited"] += 1
                break
            counters["lines"] += 1
            line = raw_line.strip()
            if not line:
                continue
            is_ctc_out = b'"custom_tool_call_output"' in line
            is_fc_out = b'"function_call_output"' in line
            is_ctc = not is_ctc_out and b'"custom_tool_call"' in line
            is_fc = not is_fc_out and b'"function_call"' in line
            is_bound = (not is_ctc and not is_fc) and (
                b'"task_started"' in line or (b'"message"' in line and b'"user"' in line)
            )
            is_ctx = b'"session_meta"' in line or b'"turn_context"' in line or b'"world_state"' in line
            if not (is_ctc_out or is_fc_out or is_ctc or is_fc or is_bound or is_ctx):
                if not line.endswith(b"}"):
                    counters["bad_json_lines"] += 1
                continue
            try:
                rec = json.loads(line)
            except Exception:
                counters["bad_json_lines"] += 1
                continue
            ts = _rec_ts(rec)
            if ts:
                last_ts = ts
            p = rec.get("payload")
            if not isinstance(p, dict):
                continue
            rtype = rec.get("type")
            ptype = p.get("type")

            if is_ctx:
                c = p.get("cwd")
                if not c and isinstance(p.get("state"), dict):
                    c = p["state"].get("cwd")
                if c and cwd is None:
                    cwd = str(c)
                continue

            if is_bound:
                if rtype == "event_msg" and ptype == "task_started":
                    flush_turn()
                elif rtype == "response_item" and ptype == "message" and p.get("role") == "user":
                    flush_turn()
                continue

            if is_ctc and rtype == "response_item" and ptype == "custom_tool_call":
                name = p.get("name") or ""
                counters["custom_tool_calls"] += 1
                counters["ctc_name_%s" % name] += 1
                inp = p.get("input")
                if not isinstance(inp, str) or inp.startswith("gAAAA"):
                    counters["encrypted_inputs"] += 1
                    continue
                for tn in TOOL_USE_RE.findall(inp):
                    tool_js_counts[tn] += 1
                if name != "exec":
                    continue
                idxs = []
                for cmd in extract_cmds_from_input(inp):
                    for stmt in split_statements(cmd):
                        idxs.append(add_occ(stmt, ts))
                        counters["commands_extracted"] += 1
                if idxs and p.get("call_id"):
                    pending[p.get("call_id")] = {"occ": idxs, "ts": ts}
                elif not idxs:
                    counters["exec_no_cmd"] += 1
                continue

            if is_fc and rtype == "response_item" and ptype == "function_call":
                name = p.get("name") or ""
                fn_counts[name] += 1
                # function_call 형태의 셸 호출(exec_command/shell_command)도 명령으로 본다
                if name in ("exec_command", "shell_command", "exec"):
                    args = p.get("arguments")
                    cmd = None
                    try:
                        a = json.loads(args) if isinstance(args, str) else (args or {})
                        cmd = a.get("cmd") or a.get("command")
                    except Exception:
                        counters["bad_json_lines"] += 1
                    if isinstance(cmd, str) and cmd.strip():
                        idxs = [add_occ(st, ts) for st in split_statements(cmd)]
                        counters["commands_extracted"] += len(idxs)
                        if p.get("call_id"):
                            pending[p.get("call_id")] = {"occ": idxs, "ts": ts}
                continue

            if is_ctc_out or is_fc_out:
                counters["tool_outputs"] += 1
                ent = pending.pop(p.get("call_id"), None)
                if ent is None:
                    counters["unpaired_outputs"] += 1
                    continue
                txt = _output_text(p.get("output"))
                low = txt
                failed = bool(FAIL_RE.search(low))
                truncated = bool(TRUNC_RE.search(low))
                el = (ts - ent["ts"]) if ts and ent["ts"] else None
                for i in ent["occ"]:
                    occ = occs[i]
                    occ["elapsed"] = el
                    occ["out_len"] = len(txt)
                    occ["truncated"] = truncated
                    occ["failed"] = failed
                continue

    flush_turn()
    # 짝 없는 호출: 마지막 관측 시각까지를 대략 경과로 본다
    for ent in pending.values():
        el = (last_ts - ent["ts"]) if last_ts and ent["ts"] else None
        for i in ent["occ"]:
            if occs[i]["elapsed"] is None:
                occs[i]["elapsed"] = el
        counters["unpaired_calls"] += 1

    if cwd is None:
        cwd_status = "unknown"
    elif cwd_filter and cwd_filter.lower() not in cwd.replace("/", "\\").lower():
        cwd_status = "excluded"
    else:
        cwd_status = "match"
    return {
        "session": sid, "cwd_status": cwd_status, "cwd_matched": cwd_status != "excluded",
        "turns": turns, "occs": occs, "fn_counts": fn_counts,
        "tool_js_counts": tool_js_counts,
    }


# --- 기존 도구 색인 / 진행 중 목록 ----------------------------------------------
def build_tool_index(root: Path):
    """scripts/*.py, scripts|__patch_drop__/*.ps1, 루트 *.bat 의 이름·docstring 키워드."""
    tool_files = {}   # filename -> display path
    for pat, sub in (("*.py", "scripts"), ("*.ps1", "scripts"), ("*.ps1", "__patch_drop__"), ("*.bat", ".")):
        d = root / sub
        if d.is_dir():
            for f in d.glob(pat):
                tool_files[f.name.lower()] = ("%s/%s" % (sub, f.name)) if sub != "." else f.name
    keywords = defaultdict(set)  # keyword -> {display}
    for fname, disp in tool_files.items():
        stem = fname.rsplit(".", 1)[0]
        for w in re.split(r"[_\-]+", stem):
            if len(w) >= 5 and w.isalpha():
                keywords[w].add(disp)
        fpath = root / disp
        try:
            with io.open(fpath, "r", encoding="utf-8", errors="replace") as fh:
                head = fh.read(3000)
        except OSError:
            continue
        m = re.search(r'"""([^"]{10,600})"""', head) or re.search(r"(?m)^\s*#(.{10,200})", head)
        if m:
            for w in re.findall(r"[a-zA-Z][a-zA-Z-]{4,}", m.group(1).lower()):
                keywords[w].add(disp)
    return tool_files, keywords


SCRIPT_REF_RE = re.compile(r"(?:scripts[\\/]|__patch_drop__[\\/]|^|\s|/)([\w.\-]+\.(?:py|ps1|bat|cmd))\b", re.IGNORECASE)

# F5: Grok Bot 이 이미 넘긴 항목 → "진행 중"
IN_PROGRESS = (
    ("git-ship(Git-Ship.bat/git_ship.py 완료)", re.compile(r"\bgit\b[^|;&]*\b(add|commit|push)\b", re.IGNORECASE)),
    ("git-guard-fast(커밋검사 고속화·설명·사실점검)", re.compile(r"git[_-]?guard|test_brief_fact_check|staged_guard|git-staged", re.IGNORECASE)),
    ("git-lock-harmony(git_optional_lock_scan)", re.compile(r"index\.lock|GIT_OPTIONAL_LOCKS|optional_lock|optional-locks", re.IGNORECASE)),
)


def classify_bundle(member_raws, tool_files, tool_keywords):
    """묶음 판정: 진행 중 > 기존 도구 사용 중 > 기존 도구 있음 안 쓰임 > 새 후보."""
    joined = " ; ".join(member_raws)
    for label, rx in IN_PROGRESS:
        if rx.search(joined):
            return "진행 중", label, []
    used = sorted({tool_files[m.group(1).lower()]
                   for m in SCRIPT_REF_RE.finditer(joined)
                   if m.group(1).lower() in tool_files})
    if used:
        return "기존 도구 사용 중", "", used
    hits = defaultdict(int)
    for w in re.findall(r"[a-zA-Z][a-zA-Z-]{4,}", joined.lower()):
        for disp in tool_keywords.get(w, ()):
            hits[disp] += 1
    if hits:
        best = max(hits.items(), key=lambda kv: kv[1])
        if best[1] >= 2:
            return "기존 도구 있음, 안 쓰임", best[0], []
    return "새 후보", "", []


# --- 집계 ----------------------------------------------------------------------
def aggregate(file_results, min_sessions, min_count):
    """턴 시퀀스에서 2~6-gram 묶음을 세고 점수·판정을 붙인다."""
    bundles = {}   # key tuple -> agg
    single = {}    # shape -> agg (보조 정보)
    for res in file_results:
        sid = res["session"]
        occs = res["occs"]
        for turn in res["turns"]:
            shapes = [occs[i]["shape"] for i in turn]
            for i in turn:
                occ = occs[i]
                sa = single.setdefault(occ["shape"], {
                    "sessions": set(), "count": 0, "elapsed_sum": 0.0, "elapsed_n": 0,
                    "trunc": 0, "fail": 0, "out_len_sum": 0, "risky": False, "example": None,
                })
                sa["sessions"].add(sid)
                sa["count"] += 1
                if occ["elapsed"] is not None:
                    sa["elapsed_sum"] += occ["elapsed"]; sa["elapsed_n"] += 1
                sa["trunc"] += int(occ["truncated"]); sa["fail"] += int(occ["failed"])
                sa["out_len_sum"] += occ["out_len"]
                if STATE_CHANGE_RE.search(occ["raw"]):
                    sa["risky"] = True
                if sa["example"] is None:
                    sa["example"] = mask_example(occ["raw"])
            for pos in range(len(turn)):
                for n in range(2, 7):
                    if pos + n > len(turn):
                        break
                    key = tuple(shapes[pos:pos + n])
                    members = turn[pos:pos + n]
                    agg = bundles.setdefault(key, {
                        "sessions": set(), "count": 0, "member_n": 0,
                        "elapsed_sum": 0.0, "elapsed_n": 0,
                        "trunc": 0, "fail": 0, "out_len_sum": 0,
                        "risky": False, "example": None, "raws": None,
                    })
                    agg["sessions"].add(sid)
                    agg["count"] += 1
                    for i in members:
                        occ = occs[i]
                        agg["member_n"] += 1
                        if occ["elapsed"] is not None:
                            agg["elapsed_sum"] += occ["elapsed"]; agg["elapsed_n"] += 1
                        agg["trunc"] += int(occ["truncated"])
                        agg["fail"] += int(occ["failed"])
                        agg["out_len_sum"] += occ["out_len"]
                        if STATE_CHANGE_RE.search(occ["raw"]):
                            agg["risky"] = True
                    if agg["example"] is None:
                        agg["example"] = mask_example(occs[members[0]]["raw"])
                        agg["raws"] = [occs[i]["raw"] for i in members]

    rows = []
    for key, agg in bundles.items():
        if len(agg["sessions"]) < min_sessions or agg["count"] < min_count:
            continue
        avg_el = (agg["elapsed_sum"] / agg["elapsed_n"]) if agg["elapsed_n"] else None
        member_n = agg["member_n"] or 1
        trunc_r = agg["trunc"] / member_n
        fail_r = agg["fail"] / member_n
        avg_out = agg["out_len_sum"] / member_n
        el_factor = avg_el if (avg_el and avg_el > 0) else 1.0  # 시각 미측정은 1.0 중립
        score = len(agg["sessions"]) * el_factor * (1 + trunc_r) * (1 + fail_r)
        rows.append({
            "shape": list(key), "len": len(key),
            "sessions": sorted(agg["sessions"]), "session_count": len(agg["sessions"]),
            "occurrences": agg["count"],
            "avg_elapsed_s": round(avg_el, 2) if avg_el is not None else None,
            "trunc_ratio": round(trunc_r, 3), "fail_ratio": round(fail_r, 3),
            "avg_out_len_chars": int(avg_out),
            "score": round(score, 2),
            "risk": "state-change" if agg["risky"] else "read-only",
            "example": agg["example"], "_raws": agg["raws"],
        })
    rows.sort(key=lambda r: (-r["score"], -r["session_count"], -r["occurrences"]))

    top_single = []
    for shape, sa in single.items():
        if len(sa["sessions"]) < 2 or sa["count"] < 3:
            continue
        avg_el = (sa["elapsed_sum"] / sa["elapsed_n"]) if sa["elapsed_n"] else None
        top_single.append({
            "shape": shape, "session_count": len(sa["sessions"]), "count": sa["count"],
            "avg_elapsed_s": round(avg_el, 2) if avg_el is not None else None,
            "trunc_ratio": round(sa["trunc"] / sa["count"], 3),
            "fail_ratio": round(sa["fail"] / sa["count"], 3),
            "avg_out_len_chars": int(sa["out_len_sum"] / sa["count"]),
            "risk": "state-change" if sa["risky"] else "read-only",
            "example": sa["example"],
        })
    top_single.sort(key=lambda r: (-r["count"],))
    return rows, top_single[:30]


# --- 출력 ----------------------------------------------------------------------
def render_md(report, top_n=20):
    L = []
    L.append("# codex_scriptize_mine 결과 %s" % report["generated_at"])
    L.append("")
    s = report["summary"]
    L.append("- 스캔 파일 %(files_scanned)s / demo-1 %(files_demo1)s / cwd미상 %(files_unknown_cwd)s / 제외 %(files_excluded_cwd)s" % s)
    L.append("- 명령 추출 %(commands_extracted)s, exec호출 %(custom_tool_calls)s, 깨진줄 %(bad_json_lines)s, 암호화입력 %(encrypted_inputs)s, 경과 %(elapsed_sec)s초" % s)
    L.append("- 점수 = 반복세션수 × 평균경과(미측정 1.0) × (1+잘림률) × (1+실패율)")
    L.append("")
    L.append("## 상위 묶음 (top %d)" % top_n)
    L.append("")
    L.append("| # | 묶음 모양 | 세션 | 횟수 | 평균s | 잘림% | 실패% | 판정 | 위험 | 예시 |")
    L.append("|---|-----------|------|------|-------|-------|-------|------|------|------|")
    for i, r in enumerate(report["bundles"][:top_n], 1):
        shape = " → ".join(r["shape"]).replace("|", "\\|")
        if len(shape) > 130:
            shape = shape[:127] + "…"
        ex = (r["example"] or "").replace("|", "\\|")
        L.append("| %d | %s | %d | %d | %s | %.0f%% | %.0f%% | %s | %s | %s |" % (
            i, shape, r["session_count"], r["occurrences"],
            r["avg_elapsed_s"] if r["avg_elapsed_s"] is not None else "?",
            r["trunc_ratio"] * 100, r["fail_ratio"] * 100,
            r["verdict"], r["risk"], ex))
    L.append("")
    L.append("## 보조: 반복 단일 명령 (묶음 아님)")
    L.append("")
    L.append("| 단일 명령 모양 | 세션 | 횟수 | 평균s | 잘림% | 실패% | 위험 |")
    L.append("|----------------|------|------|-------|-------|-------|------|")
    for r in report.get("top_single", [])[:15]:
        sh = r["shape"].replace("|", "\\|")
        if len(sh) > 110:
            sh = sh[:107] + "…"
        L.append("| %s | %d | %d | %s | %.0f%% | %.0f%% | %s |" % (
            sh, r["session_count"], r["count"],
            r["avg_elapsed_s"] if r["avg_elapsed_s"] is not None else "?",
            r["trunc_ratio"] * 100, r["fail_ratio"] * 100, r["risk"]))
    L.append("")
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sessions-dir", default=str(Path.home() / ".codex" / "sessions"))
    ap.add_argument("--days", type=int, default=7)
    ap.add_argument("--max-files", type=int, default=250)
    ap.add_argument("--max-mb-per-file", type=float, default=0)
    ap.add_argument("--cwd-filter", default="AbandonWare\\demo-1")
    ap.add_argument("--min-sessions", type=int, default=2)
    ap.add_argument("--min-count", type=int, default=3)
    ap.add_argument("--out-dir", default=str(REPO_ROOT / "var" / "codex-assist-scriptize"))
    ap.add_argument("--json-out", default="")
    ap.add_argument("--top", type=int, default=20)
    args = ap.parse_args(argv)

    t0 = datetime.now()
    files = iter_rollout_files(Path(args.sessions_dir), args.days, args.max_files)
    counters = Counter()
    file_results = []
    tool_files, tool_keywords = build_tool_index(REPO_ROOT)
    in_prog_names = [n for n, _r in IN_PROGRESS]

    for i, path in enumerate(files, 1):
        res = scan_file(path, args.cwd_filter, args.max_mb_per_file, counters)
        counters["files_scanned"] += 1
        if res["cwd_status"] == "match":
            counters["files_demo1"] += 1
            file_results.append(res)
        elif res["cwd_status"] == "unknown":
            counters["files_unknown_cwd"] += 1
            file_results.append(res)
        else:
            counters["files_excluded_cwd"] += 1
        for k, v in res["fn_counts"].items():
            counters["fn_%s" % k] += v
        for k, v in res["tool_js_counts"].items():
            counters["jstool_%s" % k] += v
        if i % 50 == 0:
            print("[scan] %d/%d files..." % (i, len(files)), file=sys.stderr)

    rows, top_single = aggregate(file_results, args.min_sessions, args.min_count)
    for r in rows:
        verdict, detail, used = classify_bundle(r["_raws"] or [], tool_files, tool_keywords)
        r["verdict"] = verdict
        if detail:
            r["verdict_detail"] = detail
        if used:
            r["tools_used"] = used
        r.pop("_raws", None)

    elapsed = (datetime.now() - t0).total_seconds()
    summary = {
        "files_scanned": counters["files_scanned"],
        "files_demo1": counters["files_demo1"],
        "files_unknown_cwd": counters["files_unknown_cwd"],
        "files_excluded_cwd": counters["files_excluded_cwd"],
        "lines": counters["lines"],
        "bad_json_lines": counters["bad_json_lines"],
        "encrypted_inputs": counters["encrypted_inputs"],
        "custom_tool_calls": counters["custom_tool_calls"],
        "exec_no_cmd": counters["exec_no_cmd"],
        "unpaired_calls": counters["unpaired_calls"],
        "unpaired_outputs": counters["unpaired_outputs"],
        "commands_extracted": counters["commands_extracted"],
        "files_size_limited": counters["files_size_limited"],
        "elapsed_sec": round(elapsed, 1),
        "function_call_names": {k[3:]: v for k, v in counters.items() if k.startswith("fn_")},
        "js_tool_names": {k[7:]: v for k, v in counters.items() if k.startswith("jstool_")},
        "custom_tool_names": {k[9:]: v for k, v in counters.items() if k.startswith("ctc_name_")},
        "in_progress_rules": in_prog_names,
    }
    report = {
        "schema": "awx.codex-scriptize-mine.v1",
        "generated_at": t0.strftime("%Y-%m-%d %H:%M"),
        "params": {
            "sessions_dir": args.sessions_dir, "days": args.days,
            "max_files": args.max_files, "max_mb_per_file": args.max_mb_per_file,
            "cwd_filter": args.cwd_filter, "min_sessions": args.min_sessions,
            "min_count": args.min_count,
        },
        "summary": summary,
        "bundles": rows,
        "top_single": top_single,
    }

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.json_out:
        jpath = Path(args.json_out)
    else:
        jpath = out_dir / ("mine-%s.json" % t0.strftime("%Y%m%d-%H%M"))
    mpath = jpath.with_suffix(".md")
    jpath.parent.mkdir(parents=True, exist_ok=True)
    with io.open(jpath, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
    with io.open(mpath, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(render_md(report, args.top))
    print(json.dumps({"ok": True, "json": str(jpath), "md": str(mpath),
                      "bundles": len(rows), "files": summary["files_scanned"],
                      "demo1": summary["files_demo1"], "unknown": summary["files_unknown_cwd"],
                      "cmds": summary["commands_extracted"],
                      "elapsed_sec": summary["elapsed_sec"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
