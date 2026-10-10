#!/usr/bin/env python3
"""skill_index_build.py — 스킬 인덱스·AGENTS 경량 *초안* 생성 (WP1/WP3 stage draft).

산출물(--out-dir, 전부 초안이며 원문을 대체하지 않는다):
  skills-index.draft.md    SKILL name+짧은 desc ≤ --max-bytes (pagination 표기)
  skill-merge-candidates.md 설명 겹침·날짜접미 일회용 스킬 후보 표 (삭제 없음)
  AGENTS.slim.draft.md     KEEP 규칙 원문 유지 + 블록→상세참조 ≤ --max-bytes
  agents-slim-map.md       전체 블록→참조 대응표 (분량 제한 없음)

KEEP 규칙(AGENTS.md 원문 라인 그대로 앞에 유지):
  push/원격변경 금지·비밀값 출력 금지·남의 live lease 강제 해제 금지·
  PROTO_OPEN·편집 전 lease 확인.

사용:
  python -B scripts/skill_index_build.py --root . --out-dir var/codex-assist-guardrail-slim
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.skill-index-build.v1"
DATED_RE = re.compile(r"(?:-|_)(?:20)?\d{6}(?:-|$)|-\d{8}\b")
BLOCK_RE = re.compile(r"<!--\s*BEGIN\s+([A-Za-z0-9_-]+)\s*-->")
END_RE = re.compile(r"<!--\s*END\s+([A-Za-z0-9_-]+)\s*-->")
DETAIL_RE = re.compile(r"(docs/agents-rules/[A-Za-z0-9_.\-]+\.md|docs/[A-Za-z0-9_./\-]+\.md)")
SKILL_REF_RE = re.compile(r"\$([a-z0-9\-]+)")

# KEEP 선별: AGENTS.md 라인 키워드 → 초안 상단에 원문 유지
KEEP_RES = [
    re.compile(r"^-\s+Auth stays PROTO_OPEN", re.I),
    re.compile(r"^-\s+Forbidden:", re.I),
    re.compile(r"take the file/target lease", re.I),
    re.compile(r"live 금지|live lease.*금지|never.*force.*release", re.I),
    re.compile(r"Never log or print raw API keys", re.I),
]

STOP = {
    "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "when",
    "use", "with", "that", "this", "demo-1", "demo1", "it", "is", "be",
    "are", "at", "as", "by", "not", "from", "into", "per",
}


def utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def parse_frontmatter(text: str) -> dict:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end < 0:
        return {}
    fm = {}
    for line in text[3:end].splitlines():
        m = re.match(r"^([A-Za-z_-]+):\s*(.*)$", line.strip())
        if m:
            fm[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return fm


def collect_skills(root: Path) -> list:
    skills = []
    for smd in sorted(root.glob(".agents/skills/*/SKILL.md")):
        try:
            text = smd.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        fm = parse_frontmatter(text)
        name = fm.get("name") or smd.parent.name
        desc = fm.get("description") or ""
        if not desc:
            for line in text.splitlines():
                line = line.strip()
                if line and not line.startswith(("---", "#", "name:", "description:")):
                    desc = line
                    break
        skills.append({
            "name": name.strip(),
            "desc": re.sub(r"\s+", " ", desc).strip(),
            "path": smd.relative_to(root).as_posix(),
            "dated": bool(DATED_RE.search(name)),
        })
    return skills


def build_index_draft(skills: list, max_bytes: int) -> tuple:
    """name + 균등 desc 예산. 전부 못 넣으면 pagination 표기."""
    header = ("# skills-index.draft (DRAFT — WP1 stage; on-demand bodies "
              "stay in each SKILL.md)\n\n")
    lines = []
    for s in sorted(skills, key=lambda x: x["name"].casefold()):
        lines.append((s["name"], s["desc"]))
    name_bytes = sum(len(n.encode("utf-8")) + 4 for n, _ in lines)
    desc_budget = max_bytes - len(header.encode("utf-8")) - name_bytes - 64
    per = max(0, desc_budget // max(1, len(lines)))
    body, dropped = [], 0
    for name, desc in lines:
        d = desc[:per] + ("…" if len(desc) > per and per > 0 else "")
        line = "- `%s` — %s" % (name, d) if d else "- `%s`" % name
        body.append(line)
    text = header + "\n".join(body) + "\n"
    if len(text.encode("utf-8")) > max_bytes:
        # pagination: 들어가는 만큼만 + 명시적 잘림 표기
        out, used = [], len(header.encode("utf-8")) + 80
        for name, _ in lines:
            line = "- `%s`" % name
            cost = len(line.encode("utf-8")) + 1
            if used + cost > max_bytes:
                dropped += 1
                continue
            out.append(line)
            used += cost
        text = (header + "\n".join(out) +
                "\n\n[paginated: %d skills omitted — page unit=line]\n" % dropped)
    return text, dropped


def tokens(text: str) -> set:
    toks = set(re.findall(r"[A-Za-z0-9_가-힣]{2,}", text.lower()))
    return {t for t in toks if t not in STOP}


def merge_candidates(skills: list, threshold: float = 0.55) -> list:
    toks = {s["name"]: tokens(s["name"].replace("-", " ") + " " + s["desc"])
            for s in skills}
    pairs = []
    names = sorted(toks)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            ta, tb = toks[a], toks[b]
            if not ta or not tb:
                continue
            j = len(ta & tb) / len(ta | tb)
            if j >= threshold:
                pairs.append({"a": a, "b": b, "jaccard": round(j, 3)})
    pairs.sort(key=lambda p: -p["jaccard"])
    return pairs


def parse_agents_blocks(text: str) -> tuple:
    lines = text.splitlines()
    blocks, cur = [], None
    preamble_lines = []
    for line in lines:
        mb = BLOCK_RE.search(line)
        me = END_RE.search(line)
        if mb:
            cur = {"id": mb.group(1), "title": "", "detail": "",
                   "summary": "", "skillRefs": []}
            continue
        if me:
            if cur:
                blocks.append(cur)
            cur = None
            continue
        target = cur if cur else None
        if target is None:
            preamble_lines.append(line)
            continue
        if not target["title"] and line.startswith("## "):
            target["title"] = line[3:].strip()
        m = DETAIL_RE.search(line)
        if m and not target["detail"]:
            target["detail"] = m.group(1)
        if not target["summary"] and line.lstrip().startswith("- "):
            target["summary"] = re.sub(r"\s+", " ", line.strip()[2:])[:120]
        target["skillRefs"].extend(SKILL_REF_RE.findall(line))
    return preamble_lines, blocks


def keep_lines(agents_text: str) -> list:
    out = []
    for i, line in enumerate(agents_text.splitlines(), 1):
        s = line.strip()
        if any(r.search(s) for r in KEEP_RES):
            out.append((i, s))
    return out


def build_agents_slim(agents_text: str, max_bytes: int) -> str:
    keeps = keep_lines(agents_text)
    _, blocks = parse_agents_blocks(agents_text)
    head = ("# AGENTS.slim.draft (DRAFT — WP3 stage; details stay in "
            "docs/agents-rules/*)\n\n## KEEP — 규칙 원문 유지\n\n")
    keep_sec = "".join("- (L%d) %s\n" % (i, l) for i, l in keeps)
    table = ["\n## Rule blocks → detail\n",
             "| block | detail |", "|---|---|"]
    for b in blocks:
        table.append("| `%s` | %s |" % (b["id"], b["detail"] or "—"))
    text = head + keep_sec + "\n".join(table) + "\n"
    if len(text.encode("utf-8")) > max_bytes:
        # 표를 축약: id 만 나열
        ids = ", ".join("`%s`" % b["id"] for b in blocks)
        text = (head + keep_sec + "\n## Rule blocks → detail\n\n" + ids +
                "\n\n[see agents-slim-map.md for the full table]\n")
    return text


def build_map(agents_text: str, skills: list) -> str:
    _, blocks = parse_agents_blocks(agents_text)
    skill_names = {s["name"] for s in skills}
    lines = ["# AGENTS 블록 → 상세 문서·스킬 대응표", "",
             "| block | title | detail doc | related skill |", "|---|---|---|---|"]
    for b in blocks:
        refs = ", ".join("$%s" % r for r in b["skillRefs"] if r in skill_names)
        lines.append("| `%s` | %s | %s | %s |" %
                     (b["id"], b["title"].replace("|", "\\|")[:60],
                      b["detail"] or "—", refs or "—"))
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--root", default=".")
    p.add_argument("--out-dir",
                   default=os.path.join("var", "codex-assist-guardrail-slim"))
    p.add_argument("--max-bytes", type=int, default=8000)
    p.add_argument("--json", action="store_true")
    a = p.parse_args(argv)

    root = Path(a.root).resolve()
    out = Path(a.out_dir)
    if not out.is_absolute():
        out = root / out
    skills = collect_skills(root)
    agents_text = (root / "AGENTS.md").read_text(encoding="utf-8",
                                                 errors="replace")

    idx_text, dropped = build_index_draft(skills, a.max_bytes)
    atomic_write(out / "skills-index.draft.md", idx_text)

    pairs = merge_candidates(skills)
    dated = [s for s in skills if s["dated"]]
    cand = ["# 통합·보관 후보 (draft — 삭제 없음)", "",
            "## 설명 겹침 (jaccard >= 0.55)", "",
            "| skill A | skill B | jaccard |", "|---|---|---|"]
    for p_ in pairs[:40]:
        cand.append("| `%s` | `%s` | %.3f |" % (p_["a"], p_["b"], p_["jaccard"]))
    cand += ["", "## 날짜 접미 일회용 후보 (보관 검토)", ""]
    for s in dated:
        cand.append("- `%s` — %s" % (s["name"], s["path"]))
    atomic_write(out / "skill-merge-candidates.md", "\n".join(cand) + "\n")

    slim = build_agents_slim(agents_text, a.max_bytes)
    atomic_write(out / "AGENTS.slim.draft.md", slim)
    atomic_write(out / "agents-slim-map.md", build_map(agents_text, skills))

    summary = {
        "schemaVersion": SCHEMA,
        "generatedAt": utcnow(),
        "skillCount": len(skills),
        "indexDraftBytes": len(idx_text.encode("utf-8")),
        "indexPaginatedDropped": dropped,
        "mergePairs": len(pairs),
        "datedSkills": len(dated),
        "agentsSlimBytes": len(slim.encode("utf-8")),
        "keepLines": len(keep_lines(agents_text)),
        "blocks": len(parse_agents_blocks(agents_text)[1]),
    }
    print(json.dumps(summary, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
