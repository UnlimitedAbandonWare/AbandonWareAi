#!/usr/bin/env python3
"""Pack-GPTPro v2 맥락 섹션 생성기 (DEMO1-DEVIN-GPTPRO-PACK-V2-20261003).

gptpro_pack.py가 프로필에 "context": true가 있을 때 호출한다. 모든 생성기는
오류 격리: 한 섹션이 실패해도 "NOT_RUN: 사유" 한 줄로 기록하고 팩은 계속한다.
표준 라이브러리만 사용. 비밀값 파일은 열지 않고, env 키는 있음/없음만 본다.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))

# 섹션 상한 — 넘으면 잘라내고 '[truncated N bytes]' 표기 (S9).
CAP_CURRENT_WORK = 300 * 1024
CAP_DIFF_PER_FILE = 40 * 1024
CAP_DIFF_TOTAL = 1536 * 1024
CAP_DIFF_TOTAL_BRIEF = 384 * 1024   # brief는 diff 예산을 더 조인다 (≤6MB 목표)
CAP_SKEL_FILE = 16 * 1024           # 골격 1파일 상한 — 넘으면 뒤쪽 시그니처 생략 표기
CAP_SECTION_DEFAULT = 1024 * 1024
BRIEF_JAVA_BUDGET = 3 * 1024 * 1024  # brief: java 골격 합산 상한 (전체 ≤6MB 목표의 java 몫)

CTX_ORDER = [
    "_START_HERE.md",
    "_RULES_AND_ROLES.md",
    "_CURRENT_WORK.md",
    "_CHANGES.md",
    "_CODE_MAP.md",
    "_RUNTIME.md",
    "_TEST_INDEX.md",
]

MAPPING_ANN = re.compile(
    r"@(?P<kind>RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*(?:\((?P<args>[^)]*)\))?")
ANN_VALUE = re.compile(r'"([^"]*)"')
VALUE_ANN = re.compile(r'@Value\s*\(\s*"\$\{([^}:]+)(?::([^}"]*))?\}')
CONFIG_PROPS_ANN = re.compile(r'@ConfigurationProperties\s*\(\s*(?:prefix\s*=\s*)?"([^"]+)"')
METHOD_SIG = re.compile(
    r"^\s*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
    r"(?:<[\w., ?]+>\s+)?[\w.<>\[\],?]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?"
    r"\s*(?:\{[^{}]*\}?|\{?)\s*$")
CTOR_SIG = re.compile(
    r"^\s*(?:public|protected|private)\s+([A-Z]\w*)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?"
    r"\s*(?:\{[^{}]*\}?|\{?)\s*$")
TYPE_DECL = re.compile(r"\b(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)")
JS_FUNC = re.compile(
    r"^\s*(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(|"
    r"^\s*(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>)|"
    r"^\s*([A-Za-z_$][\w$]*)\s*[:=]\s*(?:async\s*)?function\b|"
    r"^\s*([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{")
LOG_LINE = re.compile(r"\b(ERROR|WARN)\b")
DATESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}[.,\d]*\s*")
DIGITS = re.compile(r"\d+")
HEXLIKE = re.compile(r"\b[0-9a-fA-F]{12,}\b")
VENDOR_NAME_VER = re.compile(r"^([A-Za-z][\w.+-]*?)[-.](\d[\w.]*?)(?:\.min)?\.js$")
LEGACY_NAME = re.compile(r"(?i)(legacy|deprecated|_old\b|backup|\.bak|\.orig$)")
FRONT_ROUTE = re.compile(r"(?:^|/)page\.(tsx?|jsx?)$|(?:^|/)route\.(ts|js)$")
SKILL_NAME = re.compile(r"^name:\s*(\S+)", re.M)
SKILL_DESC = re.compile(r'^description:\s*["\']?(.*?)["\']?\s*$', re.M)


def _read(path: Path) -> str | None:
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:8192]:
        return None
    return data.decode("utf-8", "replace")


def _cap(text: str, max_bytes: int) -> tuple[str, bool]:
    """UTF-8 안전 절단 + 잘린 바이트 수 표기."""
    raw = text.encode("utf-8")
    if len(raw) <= max_bytes:
        return text, False
    cut = raw[:max_bytes].decode("utf-8", "ignore")
    return cut + f"\n\n[truncated {len(raw) - max_bytes} bytes]\n", True


def _run(cmd: list[str], timeout: int, cwd: Path) -> str | None:
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=timeout, cwd=str(cwd))
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode not in (0, 1, 6):  # 일부 진단 도구는 비0 종료가 정상
        return None
    return proc.stdout.decode("utf-8", "replace")


def approx_tokens(text: str) -> int:
    return len(text) // 4


def _kv_table(rows: list[tuple[str, str]]) -> list[str]:
    out = ["| 항목 | 값 |", "|---|---|"]
    out += [f"| {k} | {v} |" for k, v in rows]
    return out


def _yaml_inline_list(text: str, key: str) -> list[str]:
    m = re.search(rf"^\s*{re.escape(key)}:\s*\[([^\]]*)\]", text, re.M)
    if not m:
        return []
    return [x.strip().strip('"\'') for x in m.group(1).split(",") if x.strip()]


def _yaml_scalar(text: str, key: str):
    m = re.search(rf'^\s*{re.escape(key)}:\s*"?([^"\n#]+?)"?\s*(?:#.*)?$', text, re.M)
    return m.group(1).strip() if m else None


def _yaml_purpose_prefers(text: str) -> list[tuple[str, str]]:
    """agent-test-model-policy.yaml 의 purposes 블록에서 purpose -> prefer 목록."""
    out = []
    in_purposes = False
    cur = None
    for line in text.splitlines():
        if re.match(r"^purposes:", line):
            in_purposes = True
            continue
        if in_purposes:
            if re.match(r"^\S", line):  # 다음 최상위 키
                break
            m = re.match(r"^  (\w[\w-]*):", line)
            if m:
                cur = m.group(1)
                continue
            m = re.match(r"^\s+prefer:\s*\[([^\]]*)\]", line)
            if m and cur:
                items = ", ".join(x.strip().strip('"\'') for x in m.group(1).split(",") if x.strip())
                out.append((cur, items))
                cur = None
    return out


def _yaml_route_tiers(text: str) -> list[tuple[str, str, str]]:
    """api-routing.yaml routes 블록의 (group, id, tier) 나열."""
    out, group = [], ""
    in_routes = False
    cur_id = None
    for line in text.splitlines():
        if re.match(r"^routes:", line):
            in_routes = True
            continue
        if in_routes and re.match(r"^\S", line):
            break
        m = re.match(r"^  (\w[\w_]*):", line)
        if m:
            group = m.group(1)
            continue
        m = re.match(r"^\s+-\s+id:\s*(\S+)", line)
        if m:
            cur_id = m.group(1)
            continue
        m = re.match(r"^\s+tier:\s*(\S+)", line)
        if m and cur_id:
            out.append((group, cur_id, m.group(1)))
            cur_id = None
        m = re.match(r"^\s+env:\s*\[([^\]]*)\]", line)
    return out


def _yaml_env_names(text: str) -> list[str]:
    names = []
    for m in re.finditer(r"^\s+env:\s*\[([^\]]*)\]", text, re.M):
        names += [x.strip() for x in m.group(1).split(",") if x.strip()]
    return sorted(set(names))


# ---------------------------------------------------------------------------
# S1 _START_HERE.md — 마지막에 생성(다른 섹션 크기가 필요), zip에서는 맨 앞.
# ---------------------------------------------------------------------------
def build_start_here(profile: str, sha7: str, dirty: bool, kst_now: str,
                     sizes: dict[str, int], payload_files: int,
                     payload_bytes: int, focus: list[str]) -> str:
    lines = [
        "# _START_HERE — GPT Pro 팩 읽기 순서", "",
        f"- createdKst: {kst_now}",
        f"- profile: {profile}",
        f"- gitHead: {sha7}",
        f"- dirty: {'yes' if dirty else 'no'}",
        f"- payload: {payload_files} files, {payload_bytes / (1024 * 1024):.1f} MB uncompressed",
    ]
    if focus:
        lines.append(f"- focus: {', '.join(focus)} (관련 소스·테스트 전문 포함)")
    lines += [
        "",
        "## 읽는 순서",
        "1. `_START_HERE.md` — 이 파일 (전체 지도)",
        "2. `_RULES_AND_ROLES.md` — 에이전트 규칙·역할·비용/모델 순서 (AGENTS.md 전문 + SSOT 값)",
        "3. `_CURRENT_WORK.md` — 지금 진행 중인 작업 (저널·최근 보고서·goal)",
        "4. `_CHANGES.md` — 최근 커밋과 작업 트리 변경/diff",
        "5. `_CODE_MAP.md` — 엔드포인트·설정 키·큰 파일 색인 (파일:줄)",
        "6. `_RUNTIME.md` — 최근 기동/검증 상태 (값 없이 상태만)",
        "7. `_TEST_INDEX.md` — 테스트 목록 (+--focus 관련 테스트 표시)",
        "8. 그 다음 실제 소스 파일 — 전체 목록은 `_MANIFEST.md`",
        "",
        "## 섹션 크기 (대략 토큰 ≈ 글자수/4)",
        "| file | bytes | ~tokens |",
        "|---|---:|---:|",
    ]
    for name in CTX_ORDER:
        if name == "_START_HERE.md":
            continue
        if name in sizes:
            lines.append(f"| {name} | {sizes[name]} | ~{sizes[name] // 4} |")
    lines += [
        "",
        "## 요청 규칙",
        "- 답할 때 `파일:줄`로 명시. 추측은 `확인 필요`로 표시할 것.",
        "- 비밀값(.env, API 키, 토큰)은 이 팩에 없으니 요청하지 말 것 — env 이름 수준으로만 안내.",
        "- 팩에 없는 파일은 `확인 필요`로 표시하고 경로를 적어 줄 것.",
        "",
    ]
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# S2 _RULES_AND_ROLES.md — 내 규칙·역할 (SSOT에서 생성, 하드코딩 금지)
# ---------------------------------------------------------------------------
def build_rules_and_roles(root: Path) -> str:
    out = ["# _RULES_AND_ROLES — 에이전트 규칙·역할·비용 순서", ""]

    # 비용/모델 순서 — configs에서 현재 값을 읽어 표로 (F5 대응).
    out += ["## 비용·모델 순서 (configs SSOT에서 생성)", ""]
    spend = _read(root / "configs" / "agent-api-spend-guard.yaml") or ""
    if spend:
        order = _yaml_inline_list(spend, "agent_spend_order")
        rows = [
            ("agent_spend_order", " → ".join(order) if order else "확인 필요"),
            ("posture", _yaml_scalar(spend, "posture") or "확인 필요"),
            ("prefer_local_ollama_first", _yaml_scalar(spend, "prefer_local_ollama_first") or "?"),
            ("paid_default_on", _yaml_scalar(spend, "paid_default_on") or "?"),
            ("authorized_credit_budget", _yaml_scalar(spend, "authorized_credit_budget") or "?"),
        ]
        out += _kv_table(rows)
        out += ["", "출처: `configs/agent-api-spend-guard.yaml` — 에이전트 세션 전용. "
                "제품 런타임 라우팅은 아래 api-routing.yaml 참조.", ""]
    else:
        out += ["- NOT_RUN: configs/agent-api-spend-guard.yaml 없음", ""]

    pol = _read(root / "configs" / "agent-test-model-policy.yaml") or ""
    if pol:
        out += ["### 테스트 모델 정책", ""]
        out += _kv_table([
            ("apiFirstUntil", _yaml_scalar(pol, "apiFirstUntil") or "?"),
            ("afterMode", _yaml_scalar(pol, "afterMode") or "?"),
            ("maxApiGenerationsPerRun", _yaml_scalar(pol, "maxApiGenerationsPerRun") or "?"),
        ])
        prefers = _yaml_purpose_prefers(pol)
        if prefers:
            out += ["", "| purpose | prefer (순서) |", "|---|---|"]
            out += [f"| {p} | {v} |" for p, v in prefers]
        out += ["", "출처: `configs/agent-test-model-policy.yaml`", ""]

    routing = _read(root / "configs" / "api-routing.yaml") or ""
    if routing:
        order = _yaml_inline_list(routing, "order")
        out += ["### 제품 런타임 라우팅", ""]
        out += _kv_table([("policy.order", " → ".join(order) if order else "확인 필요")])
        tiers = _yaml_route_tiers(routing)
        if tiers:
            out += ["", "| route group | id | tier |", "|---|---|---|"]
            out += [f"| {g} | {i} | {t} |" for g, i, t in tiers]
        out += ["", "출처: `configs/api-routing.yaml`", ""]

    # 에이전트 역할 — 저장소 문서에 있는 것만 인용.
    out += ["## 에이전트 역할 (저장소 문서 인용)", ""]
    roles_doc = _read(root / "docs" / "agents-rules" / "DEMO1-CODEX-PLUGIN-ROLES.md")
    if roles_doc:
        body = roles_doc.strip()
        if len(body) > 3000:
            body = body[:3000] + "\n[truncated]\n"
        out += ["`docs/agents-rules/DEMO1-CODEX-PLUGIN-ROLES.md`:", "",
                "```", body, "```", ""]
    agents_md = _read(root / "AGENTS.md") or ""
    role_lines = [ln for ln in agents_md.splitlines()
                  if re.search(r"Codex|Devin|Grok|Clean|GPT Pro", ln) and ln.strip().startswith("-")]
    if role_lines:
        out += ["AGENTS.md 관련 줄:", ""]
        out += [f"> {ln.strip()}" for ln in role_lines[:12]]
        out += [""]
    out += ["- 공통 금지 목록과 PROTO_OPEN 자세는 아래 AGENTS.md 전문 참조.", ""]

    # docs/agents-rules 블록 제목 + 각 첫 3줄.
    rules_dir = root / "docs" / "agents-rules"
    if rules_dir.is_dir():
        out += ["## docs/agents-rules 규칙 블록 (제목 + 첫 3줄)", ""]
        for f in sorted(rules_dir.glob("*.md")):
            text = _read(f) or ""
            nonempty = [ln.strip() for ln in text.splitlines() if ln.strip()]
            title = f.stem
            first3 = [ln for ln in nonempty if not ln.startswith("#")][:3] or nonempty[:3]
            out.append(f"### {title}")
            out += [f"> {ln}" for ln in first3]
            out.append("")
    else:
        out += ["## docs/agents-rules", "", "- NOT_RUN: 디렉터리 없음", ""]

    # 스킬 인덱스 — name + description 한 줄씩 (본문 제외).
    skills_dir = root / ".agents" / "skills"
    if skills_dir.is_dir():
        out += ["## .agents/skills 인덱스 (name — description)", "", "| skill | description |", "|---|---|"]
        for d in sorted(skills_dir.iterdir()):
            smd = d / "SKILL.md"
            if not smd.is_file():
                continue
            text = _read(smd) or ""
            name = (SKILL_NAME.search(text) or [None, d.name])[1] if SKILL_NAME.search(text) else d.name
            dm = SKILL_DESC.search(text)
            desc = (dm.group(1) if dm else "").strip()
            if len(desc) > 140:
                desc = desc[:137] + "..."
            out.append(f"| {name} | {desc} |")
        out.append("")

    idx = _read(root / ".agents" / "skills-intent-index.yaml")
    if idx:
        body, truncated = _cap(idx, 60 * 1024)
        out += ["## .agents/skills-intent-index.yaml (원본)", "", "```yaml", body, "```", ""]

    # AGENTS.md 전문.
    if agents_md:
        body, _ = _cap(agents_md, 80 * 1024)
        out += ["## AGENTS.md (전문)", "", "```markdown", body, "```", ""]
    return "\n".join(out)


# ---------------------------------------------------------------------------
# S3 _CURRENT_WORK.md — 진행 중 저널 + 최근 보고서 + goal + 상태 문서 발췌
# ---------------------------------------------------------------------------
def _extract_status_section(text: str, max_lines: int = 30) -> list[str]:
    """goal 파일에서 상태 섹션을 찾고, 없으면 앞부분을 돌려준다."""
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if re.match(r"^#{1,4}\s*.*(상태|status|Status|STATUS)", ln):
            return lines[i:i + max_lines]
    return lines[:max_lines]


def build_current_work(root: Path, briefs_dir: str | None) -> str:
    out = ["# _CURRENT_WORK — 지금 진행 중인 작업", ""]
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=3)

    # 진행 중 저널 (워크 레저 SSOT).
    out += ["## 진행 중 작업 저널 (work_journal in_progress)", "",
            "| taskId | agent | updatedAtUtc | scope | purpose |", "|---|---|---|---|---|"]
    auto_dir = root / "data" / "agent-handoff" / "codex-autonomy"
    journals = []
    if auto_dir.is_dir():
        for jd in auto_dir.iterdir():
            jf = jd / "journal.json"
            if not jf.is_file():
                continue
            try:
                j = json.loads(jf.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if j.get("status") == "in_progress":
                journals.append(j)
    journals.sort(key=lambda j: j.get("updatedAtUtc") or "", reverse=True)
    for j in journals[:30]:
        purpose = re.sub(r"\s+", " ", str(j.get("purpose") or ""))[:100].replace("|", "/")
        out.append(f"| {j.get('taskId')} | {j.get('agent')} | {str(j.get('updatedAtUtc'))[:19]} | "
                   f"{len(j.get('plannedScope') or [])} | {purpose} |")
    if not journals:
        out.append("| (없음) | | | | |")
    out.append("")

    # 최근 3일 보고서 — 폴더당 1개, 최신순, 각 앞 60줄.
    out += ["## 최근 보고서 (3일 이내, 폴더당 1개, 각 앞 60줄)", ""]
    handoff = root / "data" / "agent-handoff"
    report_dirs = []
    if handoff.is_dir():
        cand_dirs = list(handoff.iterdir())
        if auto_dir.is_dir():
            cand_dirs += list(auto_dir.iterdir())
        for d in cand_dirs:
            if not d.is_dir():
                continue
            if not re.match(r"(?i)(codex|devin|grok|clean|agy)", d.name):
                continue
            try:
                if datetime.fromtimestamp(d.stat().st_mtime, timezone.utc) < cutoff:
                    continue
            except OSError:
                continue
            rep = None
            for cand in ("report.md", "final-report.md", "REPORT.md"):
                if (d / cand).is_file():
                    rep = d / cand
                    break
            if rep is None:
                reps = sorted(d.glob("*[Rr]eport*.md"), key=lambda p: p.stat().st_mtime, reverse=True)
                rep = reps[0] if reps else None
            if rep:
                report_dirs.append((d.stat().st_mtime, d, rep))
    report_dirs.sort(key=lambda t: t[0], reverse=True)
    for _, d, rep in report_dirs[:20]:
        rel = rep.relative_to(root).as_posix() if rep.is_relative_to(root) else str(rep)
        out.append(f"### `{rel}`")
        text = _read(rep) or "(읽기 실패)"
        out += ["```"] + text.splitlines()[:60] + ["```", ""]

    # goal 파일 — agent-prompts/*goal*.md 최신순.
    out += ["## goal 파일 상태 섹션", ""]
    goal_files = []
    ap = root / "agent-prompts"
    if ap.is_dir():
        goal_files = sorted(ap.glob("*goal*.md"), key=lambda p: p.stat().st_mtime, reverse=True)
    if goal_files:
        for gf in goal_files[:5]:
            rel = gf.relative_to(root).as_posix()
            out.append(f"### `{rel}`")
            text = _read(gf) or "(읽기 실패)"
            out += ["```"] + _extract_status_section(text) + ["```", ""]
    else:
        out += ["- 확인 필요: goal 파일 위치를 찾지 못함 (agent-prompts/*goal*.md 없음)", ""]

    # PROJECT_STATUS.md 최근 섹션 발췌 (전체는 zip에 그대로 있음).
    ps = _read(root / "docs" / "PROJECT_STATUS.md")
    if ps:
        tail, _ = _cap(ps[-(40 * 1024):], 40 * 1024)
        out += ["## docs/PROJECT_STATUS.md 최근 40KB 발췌",
                "(전체 원본은 이 zip의 docs/PROJECT_STATUS.md에 있음)", "",
                "```markdown", tail, "```", ""]

    # 옵션 --briefs: 사용자 지정 폴더의 PASTE_*.txt.
    if briefs_dir:
        out += ["## --briefs 지시서 발췌 (각 앞 40줄)", ""]
        bdir = Path(briefs_dir)
        if bdir.is_dir():
            pastes = sorted(bdir.glob("PASTE_*.txt"), key=lambda p: p.stat().st_mtime, reverse=True)
            for p in pastes[:10]:
                out.append(f"### `{p.name}`")
                text = _read(p) or "(읽기 실패)"
                out += ["```"] + text.splitlines()[:40] + ["```", ""]
            if not pastes:
                out += ["- PASTE_*.txt 없음", ""]
        else:
            out += [f"- NOT_RUN: {briefs_dir} 없음", ""]
    return "\n".join(out)


# ---------------------------------------------------------------------------
# S4 _CHANGES.md — git 로그 + 작업 트리 변경 + 범위 내 diff
# ---------------------------------------------------------------------------
def build_changes(git, root: Path, include_match, changed: list[str],
                  max_file_mb: float,
                  diff_total: int = CAP_DIFF_TOTAL) -> tuple[str, dict]:
    meta = {"diffFiles": 0, "diffTruncated": [], "diffSkippedOversize": []}
    out = ["# _CHANGES — 최근 커밋과 작업 트리 변경", ""]

    log = git("log", "--oneline", "-40")
    out += ["## git log --oneline -40", "", "```", (log or "NOT_RUN: git unavailable").rstrip(), "```", ""]
    stat = git("log", "-10", "--stat=200")
    body, _ = _cap(stat or "NOT_RUN: git unavailable", 200 * 1024)
    out += ["## 최근 10개 커밋 --stat", "", "```", body.rstrip(), "```", ""]

    # 작업 트리 변경 — 상위 폴더별 개수 + 포함 범위 안 전체 목록.
    out += ["## 작업 트리 변경 (git status --porcelain)", ""]
    porcelain = git("status", "--porcelain") or ""
    status_map = {}
    for line in porcelain.splitlines():
        if len(line) < 4:
            continue
        n = line[3:]
        if " -> " in n:
            n = n.split(" -> ", 1)[1]
        status_map[n.strip().strip('"')] = line[:2]
    all_changed = sorted(status_map)
    if not all_changed:
        all_changed = changed
    folder_counts: dict[str, int] = {}
    for n in all_changed:
        parts = n.split("/")
        key = "/".join(parts[:2]) if len(parts) > 1 else parts[0]
        folder_counts[key] = folder_counts.get(key, 0) + 1
    out += [f"총 {len(all_changed)}개 변경.", "", "| folder | count |", "|---|---:|"]
    for k, v in sorted(folder_counts.items(), key=lambda t: -t[1]):
        out.append(f"| {k} | {v} |")
    out.append("")

    in_scope = [n for n in all_changed if include_match(n.lower())]
    out += [f"### 포함 범위 안 변경 파일 전체 목록 ({len(in_scope)}개)", ""]
    out += [f"- {n}" for n in in_scope] or ["- (없음)"]
    out.append("")

    # 포함 범위 안 변경 파일의 git diff — 파일당 40KB, 전체 1.5MB 상한.
    out += [f"### 포함 범위 안 diff (파일당 ≤{CAP_DIFF_PER_FILE // 1024}KB, "
            f"전체 ≤{diff_total // 1024}KB)", ""]
    budget = diff_total
    for n in in_scope:
        path = root / n
        if not path.is_file():
            continue
        try:
            size = path.stat().st_size
        except OSError:
            continue
        if size > max_file_mb * 1024 * 1024:
            meta["diffSkippedOversize"].append(n)
            continue
        st = status_map.get(n, "")
        if st == "??":
            out.append(f"```\n# {n}: untracked file — diff 없음\n```")
            continue
        diff = git("diff", "HEAD", "--", n)
        if diff is None:
            break
        if not diff.strip():
            continue
        meta["diffFiles"] += 1
        raw = diff.encode("utf-8")
        if len(raw) > CAP_DIFF_PER_FILE:
            diff = raw[:CAP_DIFF_PER_FILE].decode("utf-8", "ignore") + \
                f"\n# diff truncated ({len(raw) - CAP_DIFF_PER_FILE} bytes)\n"
            meta["diffTruncated"].append(n)
        dlen = len(diff.encode("utf-8"))
        if dlen > budget:
            out.append(f"```\n# {n}: diff omitted — total budget exhausted\n```")
            meta["diffTruncated"].append(n + " (budget)")
            continue
        budget -= dlen
        out += [f"```diff", diff.rstrip(), "```", ""]
    if meta["diffSkippedOversize"]:
        out += ["oversize로 diff 생략:"] + [f"- {n}" for n in meta["diffSkippedOversize"]] + [""]
    return "\n".join(out), meta


# ---------------------------------------------------------------------------
# S5 _CODE_MAP.md — 엔드포인트/설정 키/패키지/큰 파일 색인/프론트
# ---------------------------------------------------------------------------
def _flatten_yml(path: Path) -> dict[str, str]:
    """간단 들여쓰기 YAML 평탄화 (list/주석 제외, key: value만)."""
    flat: dict[str, str] = {}
    text = _read(path)
    if text is None:
        return flat
    stack: list[tuple[int, str]] = []
    for ln in text.splitlines():
        if not ln.strip() or ln.strip().startswith("#") or ln.strip().startswith("-"):
            continue
        m = re.match(r"^(\s*)([\w.-]+):\s*(.*)$", ln)
        if not m:
            continue
        indent, key, val = len(m.group(1)), m.group(2), m.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        if val and not val.startswith("|") and not val.startswith(">"):
            full = ".".join(k for _, k in stack)
            flat[full] = val.strip('"\'')
    return flat


def _java_methods(text: str) -> list[tuple[int, str]]:
    """Java 파일에서 메서드/생성자 시그니처 줄번호+이름 (정규식 휴리스틱)."""
    found = []
    for i, ln in enumerate(text.splitlines(), 1):
        m = METHOD_SIG.match(ln) or CTOR_SIG.match(ln)
        if m and "(" in ln:
            name = m.group(1)
            if name not in ("if", "for", "while", "switch", "catch", "return", "new", "synchronized"):
                found.append((i, name))
    return found


def _js_functions(text: str) -> list[tuple[int, str]]:
    found = []
    for i, ln in enumerate(text.splitlines(), 1):
        m = JS_FUNC.match(ln)
        if m:
            name = next(g for g in m.groups() if g)
            if name not in ("if", "for", "while", "switch", "catch"):
                found.append((i, name))
    return found


def build_code_map(root: Path, included: list[str]) -> str:
    out = ["# _CODE_MAP — 엔드포인트·설정·패키지·큰 파일 색인", ""]

    java_files = [r for r in included if r.lower().endswith(".java")]
    texts: dict[str, str] = {}
    for rel in java_files:
        t = _read(root / rel)
        if t is not None:
            texts[rel] = t

    # Spring 엔드포인트 표.
    out += ["## Spring 엔드포인트", "", "| method | path | class#method | file:line |", "|---|---|---|---|"]
    ep_rows = 0
    for rel in sorted(texts):
        lines = texts[rel].splitlines()
        cls = Path(rel).stem
        class_prefix = ""
        for i, ln in enumerate(lines):
            m = TYPE_DECL.search(ln)
            if m and re.search(r"\b" + re.escape(cls) + r"\b", ln):
                for back in range(max(0, i - 6), i):
                    pm = MAPPING_ANN.search(lines[back])
                    if pm and pm.group("kind") == "RequestMapping":
                        vals = ANN_VALUE.findall(pm.group("args") or "")
                        if vals:
                            class_prefix = vals[0]
                break
        pending_ann = None
        for i, ln in enumerate(lines, 1):
            am = MAPPING_ANN.search(ln)
            if am:
                pending_ann = (am, i)
                if i <= 6:  # 클래스 레벨 어노테이션은 메서드 행 아님
                    pass
                continue
            if pending_ann:
                am, ann_line = pending_ann
                sig = re.search(r"\b(\w+)\s*\(", ln)
                if sig and sig.group(1) not in ("return", "if", "new") and "(" in ln:
                    kind = am.group("kind")
                    http = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
                            "DeleteMapping": "DELETE", "PatchMapping": "PATCH"}.get(kind)
                    if http is None:
                        mm = re.search(r"RequestMethod\.(\w+)", am.group("args") or "")
                        http = mm.group(1) if mm else "ANY"
                    vals = ANN_VALUE.findall(am.group("args") or "")
                    mpath = vals[0] if vals else ""
                    full = (class_prefix.rstrip("/") + "/" + mpath.lstrip("/")).rstrip("/") or "/"
                    out.append(f"| {http} | {full} | {cls}#{sig.group(1)} | {rel}:{ann_line} |")
                    ep_rows += 1
                    pending_ann = None
                elif ln.strip().startswith("@") or not ln.strip():
                    continue
                else:
                    pending_ann = None
    if ep_rows == 0:
        out.append("| (발견 없음) | | | |")
    out.append("")

    # 설정 키 표 — @Value / @ConfigurationProperties + application*.yml 기본값.
    out += ["## 설정 키 (@Value / @ConfigurationProperties)", "",
            "| key | yaml default | file:line |", "|---|---|---|"]
    defaults: dict[str, str] = {}
    res = root / "main" / "resources"
    if res.is_dir():
        for f in sorted(res.glob("application*.yml")) + sorted(res.glob("application*.yaml")):
            for k, v in _flatten_yml(f).items():
                defaults.setdefault(k, v)
        for f in sorted(res.glob("application*.properties")):
            t = _read(f) or ""
            for ln in t.splitlines():
                if "=" in ln and not ln.strip().startswith("#"):
                    k, _, v = ln.partition("=")
                    defaults.setdefault(k.strip(), v.strip())
    key_rows = 0
    for rel in sorted(texts):
        for i, ln in enumerate(texts[rel].splitlines(), 1):
            vm = VALUE_ANN.search(ln)
            if vm:
                key, dflt = vm.group(1), vm.group(2)
                yd = defaults.get(key, "")
                shown = dflt if dflt else (yd if yd else "")
                out.append(f"| {key} | {shown} | {rel}:{i} |")
                key_rows += 1
            cm = CONFIG_PROPS_ANN.search(ln)
            if cm:
                out.append(f"| {cm.group(1)}.* | (prefix) | {rel}:{i} |")
                key_rows += 1
    if key_rows == 0:
        out.append("| (발견 없음) | | |")
    out.append("")

    # 패키지별 클래스 수 + 주요 진입점.
    out += ["## 패키지별 클래스 수·주요 진입점", "", "| package | classes | entry points |", "|---|---:|---|"]
    pkg: dict[str, list[str]] = {}
    for rel in java_files:
        parts = rel.split("/")
        if parts[0] == "main" and len(parts) > 2:
            pdir = "/".join(parts[2:-1]) or "(root)"
        else:
            pdir = "/".join(parts[:-1])
        pkg.setdefault(pdir, []).append(Path(rel).stem)
    for p in sorted(pkg):
        entries = [n for n in pkg[p] if re.search(r"(Controller|Application|Config|Aspect)$", n)]
        out.append(f"| {p} | {len(pkg[p])} | {', '.join(entries[:6])} |")
    out.append("")

    # 큰 파일 색인 — 100KB+ 파일의 메서드/함수 목록 + 줄 범위.
    out += ["## 큰 파일 색인 (≥100KB) — 통째로 읽지 말고 줄 범위로 이동", ""]
    big = []
    for rel in included:
        try:
            sz = (root / rel).stat().st_size
        except OSError:
            continue
        if sz >= 100 * 1024:
            big.append((rel, sz))
    big.sort(key=lambda t: -t[1])
    for rel, sz in big:
        out.append(f"### `{rel}` ({sz // 1024}KB)")
        t = texts.get(rel) or _read(root / rel) or ""
        if rel.lower().endswith(".java"):
            members = _java_methods(t)
        elif rel.lower().endswith((".js", ".ts", ".jsx", ".tsx")):
            members = _js_functions(t)
        else:
            members = []
        if members:
            total_lines = t.count("\n") + 1
            spans = []
            for idx, (ln, name) in enumerate(members):
                end = (members[idx + 1][0] - 1) if idx + 1 < len(members) else total_lines
                spans.append(f"{name} L{ln}-{end}")
            line = "- " + "; ".join(spans)
            while len(line) > 4000:
                cut = line.rfind(";", 0, 4000)
                out.append(line[:cut + 1])
                line = "- " + line[cut + 1:].lstrip()
            out.append(line)
        else:
            out.append("- (메서드 색인 실패 — 파일 직접 확인 필요)")
        out.append("")

    # 프론트엔드 라우트 + 큰 JS 함수 목록은 위 큰 파일 색인에 포함됨.
    out += ["## 프론트엔드 라우트 (frontend/src)", "", "| route | file |", "|---|---|"]
    fsrc = root / "frontend" / "src"
    fr_rows = 0
    if fsrc.is_dir():
        for f in sorted(fsrc.rglob("*")):
            rel = f.relative_to(root).as_posix()
            if FRONT_ROUTE.search(rel):
                parts = f.relative_to(fsrc).parts
                segs = [s for s in parts[:-1] if s != "app"]
                route = "/" + "/".join(segs)
                out.append(f"| {route} | {rel} |")
                fr_rows += 1
    if fr_rows == 0:
        out.append("| (발견 없음) | |")
    out.append("")
    return "\n".join(out)


# ---------------------------------------------------------------------------
# S6 _RUNTIME.md — 값 없이 상태만. 각 절은 독립적으로 NOT_RUN 가능.
# ---------------------------------------------------------------------------
def build_runtime(root: Path) -> str:
    out = ["# _RUNTIME — 최근 기동·검증 상태 (비밀값 없음)", ""]

    # 최근 기동 로그 ERROR/WARN 요약 + result.json.
    launcher = root / "var" / "rag-launcher"
    newest = None
    if launcher.is_dir():
        runs = sorted((d for d in launcher.iterdir() if d.is_dir()),
                      key=lambda p: p.stat().st_mtime, reverse=True)
        newest = runs[0] if runs else None
    if newest:
        out += ["## 최근 기동 (var/rag-launcher)", ""]
        rj = newest / "result.json"
        if rj.is_file():
            try:
                j = json.loads(rj.read_text(encoding="utf-8"))
                out += _kv_table([
                    ("runId", str(j.get("runId"))),
                    ("status/stage", f"{j.get('status')}/{j.get('stage')}"),
                    ("runtimeRole", str(j.get("runtimeRole"))),
                    ("springPid", str(j.get("springPid"))),
                    ("completedAtUtc", str(j.get("completedAtUtc"))[:19]),
                    ("springProfile", str(j.get("springProfile"))),
                ])
                errs = j.get("firstErrorExcerpt") or []
                if errs:
                    out += ["", "firstErrorExcerpt:", "```"]
                    out += [str(e)[:200] for e in errs[:6]]
                    out += ["```"]
            except (OSError, ValueError):
                out += ["- result.json 파싱 실패"]
        out.append("")
        logs = sorted(newest.glob("*.out.log"), key=lambda p: p.stat().st_size, reverse=True)
        if logs:
            text = _read(logs[0]) or ""
            counts: dict[str, int] = {}
            samples: dict[str, str] = {}
            for ln in text.splitlines():
                if not LOG_LINE.search(ln):
                    continue
                norm = DIGITS.sub("#", HEXLIKE.sub("<hex>", DATESTAMP.sub("", ln.strip())))
                norm = re.sub(r"\s+", " ", norm)[:160]
                counts[norm] = counts.get(norm, 0) + 1
                samples.setdefault(norm, ln.strip()[:200])
            top = sorted(counts.items(), key=lambda t: -t[1])[:30]
            out += [f"### `{logs[0].name}` ERROR/WARN 고유 상위 {len(top)}개 (정규화 후)", "",
                    "| count | message (normalized) |", "|---:|---|"]
            out += [f"| {c} | {m.replace('|', '/')} |" for m, c in top]
            out.append("")
    else:
        out += ["## 최근 기동", "", "- NOT_RUN: var/rag-launcher 없음", ""]

    # H2 DDL 경고 분류 — 기존 도구 호출.
    out += ["## H2 DDL 경고 분류 (classify_h2_ddl_warnings.py --latest)", ""]
    r = _run([sys.executable, "-B", str(root / "scripts" / "classify_h2_ddl_warnings.py"), "--latest"],
             60, root)
    if r:
        body, _ = _cap(r, 20 * 1024)
        out += ["```", body.strip(), "```", ""]
    else:
        out += ["- NOT_RUN: 도구 실행 실패 또는 로그 없음", ""]

    # 최근 Verify-RAG 결과.
    out += ["## 최근 Verify-RAG 결과", ""]
    vdir = root / "var" / "debug"
    vfiles = sorted(vdir.glob("dev-*-verify.json"), key=lambda p: p.stat().st_mtime, reverse=True) \
        if vdir.is_dir() else []
    if vfiles:
        try:
            j = json.loads(vfiles[0].read_text(encoding="utf-8"))
            out += [f"- file: `var/debug/{vfiles[0].name}` (mtime {datetime.fromtimestamp(vfiles[0].stat().st_mtime, KST):%m-%d %H:%M} KST)"]
            for k in ("status", "verdict", "exitCode", "overallStatus", "freshness"):
                if k in j:
                    out.append(f"- {k}: {j[k]}")
            checks = j.get("checks") or j.get("results") or []
            if isinstance(checks, list):
                bad = [c for c in checks if isinstance(c, dict)
                       and str(c.get("status", c.get("ok", ""))).lower() not in ("pass", "ok", "true")]
                if bad:
                    out.append("- non-pass checks:")
                    for c in bad[:15]:
                        out.append(f"  - {c.get('name', c.get('id', '?'))}: "
                                   f"{str(c.get('status', c.get('reason', '')))[:80]}")
        except (OSError, ValueError):
            out += ["- verify.json 파싱 실패"]
    else:
        out += ["- NOT_RUN: var/debug/dev-*-verify.json 없음"]
    out.append("")

    # served JS sha vs 소스 sha — 로컬 GET, 서버 없으면 NOT_RUN.
    out += ["## served JS vs 소스 sha256 (로컬 18180)", ""]
    try:
        import hashlib
        import urllib.request
        src = root / "main" / "resources" / "static" / "js" / "chat.js"
        src_sha = hashlib.sha256(src.read_bytes()).hexdigest()[:16] if src.is_file() else None
        with urllib.request.urlopen("http://127.0.0.1:18180/js/chat.js", timeout=3) as resp:
            served = resp.read()
        srv_sha = hashlib.sha256(served).hexdigest()[:16]
        if src_sha:
            mark = "MATCH" if src_sha == srv_sha else "MISMATCH"
            out += [f"- chat.js: source={src_sha} served={srv_sha} → {mark}"]
        else:
            out += [f"- chat.js: served={srv_sha} (소스 파일 없음)"]
    except Exception as e:  # 서버 다운 등 — 상태만 기록
        out += [f"- NOT_RUN: {type(e).__name__} ({str(e)[:80]})"]
    out.append("")

    # 모델·API 가용성 — 키는 있음/없음만. 라이브 API 호출 없음.
    out += ["## 모델·API 가용성", ""]
    routing = _read(root / "configs" / "api-routing.yaml") or ""
    env_names = _yaml_env_names(routing)
    if env_names:
        out += ["| env name | present |", "|---|---|"]
        out += [f"| {n} | {'있음' if os.environ.get(n) else '없음'} |" for n in env_names]
        out.append("")
    ps1 = root / "scripts" / "ollama-status-snapshot.ps1"
    snap = _run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1)],
                75, root) if ps1.is_file() else None
    if snap:
        body, _ = _cap(snap, 16 * 1024)
        out += ["### ollama-status-snapshot.ps1", "", "```", body.strip(), "```", ""]
    else:
        for cli in (["ollama", "ps"], ["ollama", "list"]):
            r = _run(cli, 10, root)
            if r:
                body, _ = _cap(r, 8 * 1024)
                out += [f"### `{' '.join(cli)}`", "", "```", body.strip(), "```", ""]
                break
        else:
            out += ["- NOT_RUN: ollama CLI/스냅샷 사용 불가", ""]
    return "\n".join(out)


# ---------------------------------------------------------------------------
# S7 _TEST_INDEX.md — 테스트 목록 (+--focus 표시)
# ---------------------------------------------------------------------------
def build_test_index(root: Path, focus: list[str]) -> str:
    out = ["# _TEST_INDEX — 테스트 목록", ""]
    tdir = root / "src" / "test"
    tests = []
    if tdir.is_dir():
        for f in sorted(tdir.rglob("*")):
            if f.is_file() and f.suffix in (".java", ".js", ".ts", ".py"):
                tests.append(f.relative_to(root).as_posix())
    out.append(f"총 {len(tests)}개 테스트 파일.")
    if focus:
        out.append(f"focus={', '.join(focus)} — `*` 표시 = 키워드 매칭")
    out.append("")
    for rel in tests:
        mark = "*" if focus and any(k.lower() in rel.lower() for k in focus) else " "
        out.append(f"- {mark} {rel}")
    if not tests:
        out.append("- (src/test 없음)")
    out.append("")
    return "\n".join(out)


# ---------------------------------------------------------------------------
# S8 Java 골격 추출 — 본문 제거, 선언부만 (정규식 기반, 실패 시 전문 포함)
# ---------------------------------------------------------------------------
def java_skeleton(text: str) -> tuple[str, bool]:
    """패키지/임포트/타입 선언/필드/메서드 시그니처/주석 첫 줄만 남긴다.
    중괄호 깊이로 본문을 건너뛴다. 실패 판정: 깊이 불일치 또는 산출 3줄 미만."""
    out: list[str] = []
    depth = 0
    type_stack: list[int] = []   # 타입 선언이 열린 depth
    in_block_comment = False
    for raw in text.splitlines():
        s = raw.strip()
        # 블록 주석 — 타입 본문(depth 1~3)에서 첫 줄만 남김
        if in_block_comment:
            if "*/" in s:
                in_block_comment = False
            continue
        if s.startswith("/*") or s.startswith("/**"):
            if type_stack and depth <= type_stack[-1] + 1:
                first = s[:120]
                out.append("    " + first + (" ... */" if "*/" not in first else ""))
            in_block_comment = "*/" not in s
            continue
        code = re.sub(r'"(?:\\.|[^"\\])*"', '""', s)  # 문자열 리터럴 무력화(근사)
        opens = code.count("{")
        closes = code.count("}")
        if depth == 0:
            if s.startswith("package ") or s.startswith("import ") or s.startswith("@"):
                out.append(raw.rstrip())
            elif TYPE_DECL.search(s):
                out.append(raw.rstrip())
                if opens:
                    type_stack.append(depth)
            elif not s:
                pass
        else:
            is_member_depth = type_stack and depth <= type_stack[-1] + 1
            if is_member_depth:
                if s.startswith("//") or s.startswith("@"):
                    out.append("    " * depth + s[:160])
                elif TYPE_DECL.search(s):
                    out.append("    " * depth + s[:200])
                    if opens:
                        type_stack.append(depth)
                elif "(" in s and (METHOD_SIG.match(s) or CTOR_SIG.match(s) or s.endswith("(") or s.endswith(",")):
                    sig = s if len(s) <= 200 else s[:200] + " ..."
                    if "{" in sig and not sig.rstrip().endswith("{"):
                        sig = sig.split("{", 1)[0].rstrip() + " { … }"
                    out.append("    " * depth + sig)
                elif s.endswith(";") and "(" not in s:
                    out.append("    " * depth + s[:200])  # 필드 선언
        prev_depth = depth
        depth += opens - closes
        while type_stack and depth <= type_stack[-1]:
            type_stack.pop()
        if prev_depth > 0 and depth == 0 and type_stack == [] and out and out[-1].strip() != "}":
            out.append("}")
    if depth != 0 or len([ln for ln in out if ln.strip()]) < 3:
        return text, False
    body = "\n".join(out)
    if len(body.encode("utf-8")) > CAP_SKEL_FILE:  # 파일당 골격 상한
        keep, acc = [], 0
        for ln in out:
            acc += len(ln.encode("utf-8")) + 1
            if acc > CAP_SKEL_FILE:
                break
            keep.append(ln)
        keep.append(f"// [skeleton] truncated — 이후 시그니처 "
                    f"{len(out) - len(keep)}줄 생략 (전문은 ctx 프로필로)")
        body = "\n".join(keep)
    header = "// [skeleton] 본문 제거됨 — 선언부만. 전문은 --focus 또는 다른 프로필로.\n"
    return header + body + "\n", True


# ---------------------------------------------------------------------------
# 조합 진입점
# ---------------------------------------------------------------------------
def build_all(root: Path, profile: str, include_match, changed: list[str],
              git, max_file_mb: float, focus: list[str], briefs_dir: str | None,
              sha7: str, dirty: bool, kst_now: str, included: list[str],
              scan_fn) -> tuple[list[tuple[str, str]], dict]:
    """모든 맥락 섹션을 만들고 scan_fn으로 시크릿 라인을 제거한다.
    반환: ([(arcname, text)], meta)"""
    meta: dict = {"sections": {}, "secretLines": [], "truncated": [], "errors": []}
    payload_bytes = 0
    for rel in included:
        try:
            payload_bytes += (root / rel).stat().st_size
        except OSError:
            pass
    def _changes():
        diff_budget = CAP_DIFF_TOTAL_BRIEF if profile == "brief" else CAP_DIFF_TOTAL
        text, ch_meta = build_changes(git, root, include_match, changed,
                                      max_file_mb, diff_budget)
        notes = [f"diff files included={ch_meta.get('diffFiles', 0)}"]
        notes += [f"truncated: {n}" for n in ch_meta.get("diffTruncated", [])]
        notes += [f"skipped oversize: {n}" for n in ch_meta.get("diffSkippedOversize", [])]
        meta["diffNotes"] = notes
        return text

    builders = [
        ("_RULES_AND_ROLES.md", lambda: build_rules_and_roles(root)),
        ("_CURRENT_WORK.md", lambda: build_current_work(root, briefs_dir)),
        ("_CHANGES.md", _changes),
        ("_CODE_MAP.md", lambda: build_code_map(root, included)),
        ("_RUNTIME.md", lambda: build_runtime(root)),
        ("_TEST_INDEX.md", lambda: build_test_index(root, focus)),
    ]
    sections: dict[str, str] = {}
    for name, fn in builders:
        try:
            text = fn()
        except Exception as e:  # 섹션 실패는 팩을 죽이지 않는다
            text = f"# {name}\n\n- NOT_RUN: {type(e).__name__} {str(e)[:120]}\n"
            meta["errors"].append(f"{name}: {type(e).__name__}")
        text, truncated = _cap(text, CAP_SECTION_DEFAULT)
        if truncated:
            meta["truncated"].append(name)
        # 생성 텍스트도 시크릿 스캔 — 걸린 줄만 제거하고 기록 (S9).
        hits = scan_fn("section.txt", text)
        if hits:
            drop_lines = {ln for ln, _ in hits}
            kept = [ln for i, ln in enumerate(text.splitlines(), 1) if i not in drop_lines]
            text = "\n".join(kept) + "\n"
            meta["secretLines"] += [(name, ln, pid) for ln, pid in hits]
        sections[name] = text
        meta["sections"][name] = len(text.encode("utf-8"))

    sizes = {k: v for k, v in meta["sections"].items()}
    try:
        start = build_start_here(profile, sha7, dirty, kst_now, sizes,
                                 len(included), payload_bytes, focus)
        start, _ = _cap(start, 24 * 1024)
        hits = scan_fn("section.txt", start)
        if hits:
            drop = {ln for ln, _ in hits}
            start = "\n".join(ln for i, ln in enumerate(start.splitlines(), 1) if i not in drop) + "\n"
            meta["secretLines"] += [("_START_HERE.md", ln, pid) for ln, pid in hits]
    except Exception as e:
        start = f"# _START_HERE\n\n- NOT_RUN: {type(e).__name__}\n"
        meta["errors"].append(f"_START_HERE.md: {type(e).__name__}")
    meta["sections"]["_START_HERE.md"] = len(start.encode("utf-8"))

    ordered = [("_START_HERE.md", start)] + [(n, sections[n]) for n in CTX_ORDER if n in sections]
    return ordered, meta
