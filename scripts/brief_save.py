#!/usr/bin/env python3
"""brief_save.py — Grok Bot/agy 지시서 저장·검사·기록부 도구.

brief_lint.py(형식·비밀값 기본 검사)를 감싸고 Grok Bot 지시서 규격을 추가한다:
[ANTI-STOP] 위아래, 필수 섹션 순서, DEVIN @skill 실존 검사, CODEX '$' 줄,
Project Root, 금지 목록 핵심어, 비밀값 패턴. FAIL이면 저장을 거부한다(--force 없음).

사용:
  python -B scripts/brief_save.py save --draft <파일> --agent DEVIN|CODEX|GROK|CLEAN|GPTPRO \
      --topic <kebab> [--date yyyymmdd] [--author agy] [--downloads-dir D] [--repo-root R] [--repo-copy]
  python -B scripts/brief_save.py lint <파일> --agent X [--repo-root R]
  python -B scripts/brief_save.py list|latest|search <단어> [--registry R.jsonl]
  python -B scripts/brief_save.py backfill [--downloads-dir D] [--registry R.jsonl]
  python -B scripts/brief_save.py cover --topic <주제> --terms "t1|t2|t3" \
      [--prefix PASTE_] [--topics-file <json>] [--json]
      주제 커버리지: Downloads 실제 PASTE_* 파일과 기록부 경로를 내용 검색해
      파일:줄 근거와 verdict(COVERED/PARTIAL/NONE)를 낸다 (F2 SKIP 근거 표용).

저장 경로: Downloads/PASTE_<AGENT>_<topic>_<date>.txt (같은 이름이면 _R2,_R3; UTF-8 no BOM).
          <repo>/agent-prompts/<agent-lower>-<topic>-<date>/BRIEF.txt 사본은
          --repo-copy 지정 시에만 쓴다 (2026-10-03 R6 결정: 기본 생성 폐기).
기록부:    <repo>/data/agent-handoff/brief-registry/briefs.jsonl
backfill은 Downloads의 기존 PASTE_*.txt에 대해 이름·크기·mtime 메타데이터만 쓴다
(파일 내용을 읽지 않는다 — sha12는 null).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_lint  # noqa: E402

AGENTS = ("DEVIN", "CODEX", "GROK", "CLEAN", "GPTPRO")
ANTI_STOP_WINDOW_TOP = 15
ANTI_STOP_WINDOW_BOTTOM = 15
SKILL_LINE_WINDOW = 8

# 필수 섹션 앵커(표시 순서). "항목"은 공통 규칙과 HOLD 사이의 임의 작업 섹션으로 본다.
SECTION_ANCHORS = (
    ("한줄목표", re.compile(r"한\s*줄\s*목표")),
    ("사실", re.compile(r"^\s*#{1,6}\s*\d*[\).]?\s*사실")),
    ("공통규칙", re.compile(r"공통\s*규칙")),
    ("항목", re.compile(r"^\s*#{1,6}\s*(?:DV\d|WP\d|D\d|작업|항목)", re.IGNORECASE)),
    ("HOLD", re.compile(r"^\s*#{1,6}\s*.*\bHOLD\b")),
    ("ASK_ONCE", re.compile(r"ASK[_\s-]*ONCE")),
    ("절대금지", re.compile(r"절대\s*금지")),
    ("Acceptance", re.compile(r"acceptance|완료\s*기준|수용\s*기준", re.IGNORECASE)),
    ("보고형식", re.compile(r"보고\s*형식")),
)

FORBID_KEYWORDS = ("push", "add -A", "비밀", "remote 추가", "PROTO_OPEN")
SKILL_NAME_RE = re.compile(r"@([\w.-]+)")
CODEX_DOLLAR_RE = re.compile(r"^\s*\$\s*\S")
PROJECT_ROOT_RE = re.compile(r"Project\s*Root", re.IGNORECASE)

# brief_lint 이외에 추가로 막는 비밀값 형태 (값 자체는 출력하지 않는다)
EXTRA_SECRET_RES = (
    ("password-assign", re.compile(r"password\s*[:=]", re.IGNORECASE)),
    ("openai-style-key", re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")),
)

PASTE_NAME_RE = re.compile(r"^PASTE_(?P<agent>[A-Za-z]+)_(?P<topic>.+)_(?P<date>\d{8})(?:_R(?P<rev>\d+))?\.txt$")

# cover: Downloads PASTE_* 내용 검색 — .txt와 .md 둘 다 본다(F2).
COVER_FILE_SUFFIXES = (".txt", ".md")
COVER_LINE_MAX = 120
COVER_MAX_LINES_PER_FILE = 20


def _finding(check_id: str, severity: str, message: str, lines: list[int] | None = None) -> dict:
    return {"id": check_id, "severity": severity, "lines": sorted(set(lines or [])),
            "message": message}


def _write_utf8(text: str) -> None:
    sys.stdout.buffer.write(text.encode("utf-8"))
    sys.stdout.buffer.flush()


def repo_root_default() -> Path:
    return Path(__file__).resolve().parent.parent


def sha12_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12].upper()


def lint_brief_text(text: str, *, agent: str, name: str, repo_root: Path) -> dict:
    """brief_lint + Grok Bot 규격 추가 검사. verdict PASS|WARN|FAIL."""
    base = brief_lint.lint_text(text, name=name)
    findings = list(base["findings"])
    lines = text.splitlines()

    # A. [ANTI-STOP] 위쪽(첫 15줄) + 아래쪽(끝 15줄) 둘 다
    anti = [i for i, l in enumerate(lines, 1) if "[ANTI-STOP]" in l]
    if anti:
        if not any(i <= ANTI_STOP_WINDOW_TOP for i in anti):
            findings.append(_finding("anti-stop-top-missing", "FAIL",
                                     "[ANTI-STOP]이 위쪽(첫 15줄)에 없다.", anti))
        if not any(i > len(lines) - ANTI_STOP_WINDOW_BOTTOM for i in anti):
            findings.append(_finding("anti-stop-bottom-missing", "FAIL",
                                     "[ANTI-STOP]이 아래쪽(끝 15줄)에 없다.", anti))

    # B. 필수 섹션 순서
    positions: dict[str, int] = {}
    for key, pat in SECTION_ANCHORS:
        hit = next((i for i, l in enumerate(lines, 1) if pat.search(l)), None)
        if hit is not None:
            positions[key] = hit
    order = [k for k, _ in SECTION_ANCHORS]
    missing = [k for k in order if k not in positions]
    hard_missing = [k for k in missing if k not in ("항목",)]
    if hard_missing:
        findings.append(_finding("section-missing", "FAIL",
                                 f"필수 섹션 없음: {', '.join(hard_missing)}"))
    present = [k for k in order if k in positions]
    seq = [positions[k] for k in present]
    if seq != sorted(seq):
        bad = next(present[i] for i in range(1, len(seq)) if seq[i] < seq[i - 1])
        findings.append(_finding("section-order", "FAIL",
                                 f"필수 섹션 순서 위반: '{bad}'이 앞 섹션보다 먼저 나옴"))

    # C. DEVIN: 첫 @skill 줄의 각 스킬이 실존하는지 + 중복.
    #    명시 `.agents/skills/<name>/SKILL.md` 경로 참조도 같은 실존 검사를 받는다.
    if agent == "DEVIN":
        head = lines[:SKILL_LINE_WINDOW]
        skill_lines = [i for i, l in enumerate(head, 1) if brief_lint.SKILL_LINE_RE.match(l)]
        raw_names = [n for i in skill_lines for n in SKILL_NAME_RE.findall(head[i - 1])]
        # .md, .txt 등 파일 참조(예: @SKILL.md)는 스킬 폴더 검사에서 제외
        names = [n for n in raw_names if not n.endswith((".md", ".txt", ".json", ".yaml", ".yml"))]
        names += brief_lint.SKILL_PATH_RE.findall(text)
        missing_skills = [n for n in names
                          if not (repo_root / ".agents" / "skills" / n / "SKILL.md").is_file()]
        if missing_skills:
            findings.append(_finding("skill-not-found", "FAIL",
                                     f"없는 @skill 참조: {', '.join(sorted(set(missing_skills)))}"))
        dup = sorted({n for n in names if names.count(n) > 1})
        if dup:
            findings.append(_finding("skill-duplicate", "WARN",
                                     f"중복 @skill: {', '.join(dup)}"))

    # D. CODEX: '$' 시작 줄
    if agent == "CODEX" and not any(CODEX_DOLLAR_RE.match(l) for l in lines[:SKILL_LINE_WINDOW]):
        findings.append(_finding("codex-dollar-missing", "FAIL",
                                 f"CODEX 지시서인데 첫 {SKILL_LINE_WINDOW}줄 안에 '$' 줄이 없다."))

    # E. Project Root 문자열
    if not any(PROJECT_ROOT_RE.search(l) for l in lines):
        findings.append(_finding("project-root-missing", "FAIL",
                                 "'Project Root' 문자열이 없다."))

    # F. 금지 목록 핵심어
    missing_forbid = [k for k in FORBID_KEYWORDS if not any(k in l for l in lines)]
    if missing_forbid:
        findings.append(_finding("forbid-keywords-missing", "WARN",
                                 f"금지 목록 핵심어 누락: {', '.join(missing_forbid)}"))

    # G. 추가 비밀값 패턴
    extra_hits = [(i, pname) for i, l in enumerate(lines, 1)
                  for pname, pat in EXTRA_SECRET_RES if pat.search(l)]
    if extra_hits:
        findings.append(_finding("secret-like-string-extra", "FAIL",
                                 f"비밀값 형태 문자열 {len(extra_hits)}건. 값은 출력하지 않음.",
                                 [i for i, _ in extra_hits]))

    rank = {"PASS": 0, "WARN": 1, "FAIL": 2}
    verdict = {0: "PASS", 1: "WARN", 2: "FAIL"}[
        max((rank[f["severity"]] for f in findings), default=0)]
    return {"schema": "brief_save_lint.v1", "file": name, "agent": agent,
            "verdict": verdict,
            "summary": {"fail": sum(1 for f in findings if f["severity"] == "FAIL"),
                        "warn": sum(1 for f in findings if f["severity"] == "WARN")},
            "findings": findings}


def _registry_path(repo_root: Path, override: str | None) -> Path:
    if override:
        return Path(override)
    return repo_root / "data" / "agent-handoff" / "brief-registry" / "briefs.jsonl"


def _append_registry(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def _read_registry(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def _kst_now() -> str:
    return datetime.now(timezone(timedelta(hours=9))).isoformat(timespec="seconds")


def _unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    rev = 2
    while True:
        cand = path.with_name(f"{path.stem}_R{rev}{path.suffix}")
        if not cand.exists():
            return cand
        rev += 1


def cmd_lint(args: argparse.Namespace) -> int:
    path = Path(args.file)
    if not path.is_file():
        _write_utf8(json.dumps({"verdict": "FAIL", "findings": [
            _finding("input-missing", "FAIL", "파일이 없다")]}, ensure_ascii=False) + "\n")
        return 1
    text = path.read_bytes().decode("utf-8-sig", errors="replace")
    res = lint_brief_text(text, agent=args.agent.upper(), name=str(path),
                          repo_root=Path(args.repo_root))
    _write_utf8(json.dumps(res, ensure_ascii=False, indent=2) + "\n")
    return 1 if res["verdict"] == "FAIL" else 0


def cmd_save(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root)
    draft = Path(args.draft)
    agent = args.agent.upper()
    topic = args.topic
    date = args.date or _kst_now()[:10].replace("-", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", topic):
        _write_utf8("FAIL topic은 kebab-case 소문자여야 한다\n")
        return 1
    if not re.fullmatch(r"[0-9]{8}", date):
        _write_utf8("FAIL date는 YYYYMMDD 형식이어야 한다\n")
        return 1
    if not draft.is_file():
        _write_utf8(f"FAIL draft 파일이 없다: {draft}\n")
        return 1
    text = draft.read_bytes().decode("utf-8-sig", errors="replace")
    res = lint_brief_text(text, agent=agent, name=str(draft), repo_root=repo_root)
    if res["verdict"] == "FAIL":
        _write_utf8(json.dumps({"verdict": "FAIL", "saved": False,
                                "findings": res["findings"]}, ensure_ascii=False, indent=2) + "\n")
        return 1
    data = text.replace("\r\n", "\n").encode("utf-8")  # UTF-8 no BOM
    downloads = Path(args.downloads_dir)
    downloads.mkdir(parents=True, exist_ok=True)
    paste = _unique_path(downloads / f"PASTE_{agent}_{topic}_{date}.txt")
    paste.write_bytes(data)
    repo_copy: Path | None = None
    if args.repo_copy:
        repo_dir = repo_root / "agent-prompts" / f"{agent.lower()}-{topic}-{date}"
        repo_dir.mkdir(parents=True, exist_ok=True)
        rev = re.search(r"_R(\d+)$", paste.stem)
        repo_copy = _unique_path(repo_dir / ("BRIEF.txt" if not rev else f"BRIEF_R{rev.group(1)}.txt"))
        repo_copy.write_bytes(data)
    sha12, nbytes = sha12_of(data), len(data)
    match = repo_copy is not None and paste.read_bytes() == repo_copy.read_bytes()
    registry = _registry_path(repo_root, args.registry)
    summary = next((l.strip() for l in text.splitlines() if l.strip()), topic)
    prior = [r for r in _read_registry(registry)
             if r.get("agent") == agent and r.get("topic") == topic]
    row = {"atKst": _kst_now(), "author": args.author, "agent": agent,
           "topic": topic, "downloadsPath": str(paste),
           "repoPath": str(repo_copy) if repo_copy is not None else None,
           "bytes": nbytes, "sha12": sha12,
           "supersedes": [r.get("downloadsPath") for r in prior if r.get("downloadsPath")],
           "summaryKo": summary[:120]}
    _append_registry(registry, row)
    out = [f"{'WARN' if res['verdict'] == 'WARN' else 'PASS'} 저장 완료",
           f"Downloads: {paste}" + (f" / 레포: {repo_copy}" if repo_copy is not None else ""),
           f"바이트 {nbytes} / sha12 {sha12}" + (
               f" / 두 사본 일치: {'예' if match else '아니오'}" if repo_copy is not None else "")]
    _write_utf8("\n".join(out) + "\n")
    return 0


def cmd_backfill(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root)
    registry = _registry_path(repo_root, args.registry)
    known = {r.get("downloadsPath") for r in _read_registry(registry)}
    added = 0
    for f in sorted(Path(args.downloads_dir).glob("PASTE_*.txt")):
        if str(f) in known:
            continue
        m = PASTE_NAME_RE.match(f.name)
        st = f.stat()  # 메타데이터만 — 내용은 읽지 않는다
        _append_registry(registry, {
            "atKst": datetime.fromtimestamp(
                st.st_mtime, timezone(timedelta(hours=9))).isoformat(timespec="seconds"),
            "author": "unknown",
            "agent": (m.group("agent").upper() if m else "UNKNOWN"),
            "topic": (m.group("topic") if m else f.stem),
            "downloadsPath": str(f), "repoPath": None,
            "bytes": st.st_size, "sha12": None, "sha12Note": "backfill-meta-only",
            "supersedes": [], "summaryKo": "(백필 — 내용 미열람)"})
        added += 1
    _write_utf8(f"backfill 완료: {added}건 추가 (기존 {len(known)}건 유지)\n")
    return 0


def _decode_lossy(data: bytes) -> str:
    """UTF-8 우선, CP949 폴백 — 혼재 인코딩 파일도 깨지지 않게."""
    for enc in ("utf-8-sig", "cp949"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _cover_candidates(downloads: Path, prefix: str,
                      registry_rows: list[dict]) -> list[Path]:
    """Downloads의 PASTE_<prefix>*.{txt,md} + 기록부 downloadsPath(존재하는 것)."""
    seen: set[str] = set()
    out: list[Path] = []
    if downloads.is_dir():
        for f in sorted(downloads.iterdir()):
            if (f.is_file() and f.name.startswith(prefix)
                    and f.suffix.lower() in COVER_FILE_SUFFIXES):
                seen.add(str(f).casefold())
                out.append(f)
    for row in registry_rows:
        p = row.get("downloadsPath")
        if not p:
            continue
        f = Path(p)
        key = str(f).casefold()
        if f.is_file() and key not in seen:
            seen.add(key)
            out.append(f)
    return out


def cover_topic(topic: str, terms: list[str], files: list[Path]) -> dict:
    """한 주제의 커버리지. verdict: 파일 1개가 모든 term을 맞으면 COVERED."""
    term_res = [re.compile(t, re.IGNORECASE) for t in terms]
    matched_tis: set[int] = set()
    covered = False
    entries = []
    for f in files:
        try:
            data = f.read_bytes()
        except OSError:
            continue
        hits: dict[int, list[list]] = {}
        for ln, line in enumerate(_decode_lossy(data).splitlines(), 1):
            for ti, pat in enumerate(term_res):
                if pat.search(line):
                    matched_tis.add(ti)
                    hits.setdefault(ti, []).append(
                        [ln, line.strip()[:COVER_LINE_MAX]])
        if not hits:
            continue
        covered = covered or len(hits) == len(terms)
        m = re.search(r"(\d{8})(?:_R\d+)?$", f.stem)
        date = m.group(1) if m else datetime.fromtimestamp(
            f.stat().st_mtime,
            timezone(timedelta(hours=9))).strftime("%Y%m%d")
        tag = next((l.strip() for l in _decode_lossy(data).splitlines()
                    if l.strip()), "")
        flat = [h for _, ls in sorted(hits.items()) for h in ls]
        entries.append({
            "file": f.name, "path": str(f), "sha12": sha12_of(data),
            "date": date, "tag": tag[:60],
            "termsMatched": len(hits),
            "coversAll": len(hits) == len(terms),
            "hits": [{"term": terms[ti],
                      "lines": hits[ti][:COVER_MAX_LINES_PER_FILE]}
                     for ti in sorted(hits)],
            "excerptLines": flat[:COVER_MAX_LINES_PER_FILE]})
    entries.sort(key=lambda e: (-e["termsMatched"], e["file"]))
    matched = {terms[ti] for ti in matched_tis}
    verdict = ("COVERED" if covered
               else "PARTIAL" if matched else "NONE")
    return {"topic": topic, "verdict": verdict, "terms": terms,
            "missing": [t for t in terms if t not in matched],
            "files": entries}


def _cover_text(results: list[dict], scanned: int) -> str:
    out = []
    for r in results:
        miss = ", ".join(r["missing"]) or "-"
        out.append(f"topic={r['topic']} verdict={r['verdict']} "
                   f"terms={len(r['terms'])} missing=[{miss}]")
        for e in r["files"]:
            out.append(f"  {e['file']} sha12={e['sha12']} date={e['date']} "
                       f"cover={e['termsMatched']}/{len(r['terms'])}"
                       f"{' ALL' if e['coversAll'] else ''} tag={e['tag']}")
            for ln, txt in e["excerptLines"]:
                out.append(f"    {e['file']}:{ln}: {txt}")
    hits = sum(len(r["files"]) for r in results)
    out.append(f"files_scanned={scanned} files_with_hits={hits}")
    return "\n".join(out) + "\n"


def cmd_cover(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root)
    registry = _read_registry(_registry_path(repo_root, args.registry))
    files = _cover_candidates(Path(args.downloads_dir), args.prefix, registry)
    topics: list[tuple[str, list[str]]] = []
    if args.topics_file:
        spec = json.loads(
            Path(args.topics_file).read_bytes().decode("utf-8-sig"))
        for k, v in spec.items():
            terms = v.split("|") if isinstance(v, str) else list(v)
            topics.append((str(k), [t for t in terms if t.strip()]))
    else:
        terms = [t for t in (args.terms or "").split("|") if t.strip()]
        if not args.topic or not terms:
            _write_utf8("FAIL --topic과 --terms(또는 --topics-file)가 필요하다\n")
            return 2
        topics = [(args.topic, terms)]
    try:
        results = [cover_topic(t, ts, files) for t, ts in topics]
    except re.error as e:
        _write_utf8(f"FAIL term 정규식 오류: {e}\n")
        return 2
    if args.json:
        _write_utf8(json.dumps({"schema": "brief_cover.v1",
                                "filesScanned": len(files),
                                "results": results},
                               ensure_ascii=False, indent=2) + "\n")
    else:
        _write_utf8(_cover_text(results, len(files)))
    return 0


def cmd_query(args: argparse.Namespace) -> int:
    registry = _registry_path(Path(args.repo_root), args.registry)
    rows = _read_registry(registry)
    if args.action == "latest":
        rows = rows[-1:]
    elif args.action == "search":
        q = args.query.casefold()
        rows = [r for r in rows if any(q in str(r.get(k, "")).casefold()
                                       for k in ("agent", "topic", "downloadsPath",
                                                 "repoPath", "summaryKo", "author"))]
    _write_utf8(json.dumps(rows, ensure_ascii=False, indent=2) + "\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="brief_save.py")
    sub = ap.add_subparsers(dest="action", required=True)
    for name in ("save", "lint", "list", "latest", "search", "backfill",
                 "cover"):
        p = sub.add_parser(name)
        p.add_argument("--repo-root", default=str(repo_root_default()))
        p.add_argument("--registry", default=None)
        p.add_argument("--downloads-dir", default=str(Path.home() / "Downloads"))
        if name == "save":
            p.add_argument("--draft", required=True)
            p.add_argument("--agent", required=True, choices=AGENTS)
            p.add_argument("--topic", required=True)
            p.add_argument("--date", default=None)
            p.add_argument("--author", default="agy")
            p.add_argument("--repo-copy", action="store_true",
                           help="agent-prompts/<agent>-<topic>-<date>/BRIEF.txt 사본도 쓴다 (기본: 쓰지 않음)")
        if name == "lint":
            p.add_argument("file")
            p.add_argument("--agent", required=True, choices=AGENTS)
        if name == "search":
            p.add_argument("query")
        if name == "cover":
            p.add_argument("--topic", default=None)
            p.add_argument("--terms", default=None)
            p.add_argument("--topics-file", default=None)
            p.add_argument("--prefix", default="PASTE_")
            p.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    return {"save": cmd_save, "lint": cmd_lint, "backfill": cmd_backfill,
            "cover": cmd_cover}.get(args.action, cmd_query)(args)


if __name__ == "__main__":
    raise SystemExit(main())
