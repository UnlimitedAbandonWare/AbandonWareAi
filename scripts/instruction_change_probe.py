#!/usr/bin/env python3
"""instruction_change_probe.py — '바뀌었을 때만 다시 읽기' 보조 탐침.

AGENTS.md·스킬 인덱스·SKILL.md 들의 sha256을 상태 파일에 기록하고,
다음 호출 때 바뀐 파일 목록만 출력한다 (CHANGED/ADDED/REMOVED 경로 한 줄씩,
모두 같으면 UNCHANGED 한 줄).

주의(계약): 이 도구는 *내용 변경 감지* 보조다. 권한·lease·보안 판정 캐시가
아니며, CHANGED 없음이 규칙 승인을 대신하지 않는다. 읽기 최적화에만 쓴다.

기본 대상: AGENTS.md, AGENTS.override.md(있으면),
  .agents/skills-intent-index.yaml, .agents/skills/INDEX.md,
  .agents/skills/*/SKILL.md — --paths 로 덮어쓸 수 있다(세미콜론 구분).

출력 모드:
  (기본)        CHANGED <path> / ADDED <path> / REMOVED <path> 각 한 줄,
                없으면 UNCHANGED 한 줄. 그 다음 상태 파일을 갱신한다.
  --check-only  보고만 하고 상태 파일을 갱신하지 않는다.
  --init        상태 파일만 새로 기록하고 INIT n=<count> 출력.
  --list        추적 대상 목록 출력.
  --json        {changed, added, removed, unchanged} 를 JSON으로.

exit: 0=성공(변경 유무와 무관), 2=인자/IO 오류.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.instr-change-probe.v1"

DEFAULT_PATHS = [
    "AGENTS.md",
    "AGENTS.override.md",
    ".agents/skills-intent-index.yaml",
    ".agents/skills/INDEX.md",
    ".agents/skills/*/SKILL.md",
]


def sha256_file(path: Path):
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def expand_paths(root: Path, patterns) -> list:
    """glob 패턴을 실제 상대경로 목록으로. 매칭 없는 패턴은 그대로 둔다."""
    out = []
    for pat in patterns:
        pat = pat.replace("\\", "/")
        if any(c in pat for c in "*?[]"):
            for hit in sorted(root.glob(pat)):
                if hit.is_file():
                    out.append(hit.relative_to(root).as_posix())
        else:
            out.append(pat)
    seen, uniq = set(), []
    for p in out:
        k = p.casefold()
        if k not in seen:
            seen.add(k)
            uniq.append(p)
    return uniq


def snapshot(root: Path, rel_paths) -> dict:
    files = {}
    for rel in rel_paths:
        digest = sha256_file(root / rel)
        if digest is not None:
            files[rel] = digest
    return files


def load_state(path: Path):
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or not isinstance(data.get("files"), dict):
        return None
    return data


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


def write_state(path: Path, root: Path, patterns, files: dict) -> None:
    doc = {
        "schemaVersion": SCHEMA,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "patterns": list(patterns),
        "files": files,
        "contract": "content-hash change detection only; not an authority cache",
    }
    atomic_write(path, json.dumps(doc, ensure_ascii=True, indent=2) + "\n")


def diff(old: dict, new: dict) -> dict:
    changed, added, removed = [], [], []
    for rel, digest in sorted(new.items()):
        if rel not in old:
            added.append(rel)
        elif old[rel] != digest:
            changed.append(rel)
    for rel in sorted(old):
        if rel not in new:
            removed.append(rel)
    return {"changed": changed, "added": added, "removed": removed}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--root", default=".")
    p.add_argument("--state",
                   default=os.path.join("var", "codex-assist-guardrail-slim",
                                        "instr-hash.json"))
    p.add_argument("--paths", default=None,
                   help="세미콜론 구분 상대경로/glob; 기본 AGENTS+skills 세트")
    p.add_argument("--init", action="store_true")
    p.add_argument("--check-only", action="store_true")
    p.add_argument("--list", action="store_true")
    p.add_argument("--json", action="store_true")
    a = p.parse_args(argv)

    root = Path(a.root).resolve()
    patterns = (a.paths.split(";") if a.paths else DEFAULT_PATHS)
    rel_paths = expand_paths(root, patterns)

    if a.list:
        for rel in rel_paths:
            print(rel)
        return 0

    state_path = Path(a.state)
    if not state_path.is_absolute():
        state_path = root / state_path
    files = snapshot(root, rel_paths)
    prev = load_state(state_path)

    if a.init or prev is None:
        write_state(state_path, root, patterns, files)
        print("INIT n=%d state=%s" % (len(files), state_path.as_posix()))
        return 0

    result = diff(prev.get("files") or {}, files)
    is_unchanged = not (result["changed"] or result["added"]
                        or result["removed"])

    if a.json:
        print(json.dumps({
            "unchanged": is_unchanged,
            "changed": result["changed"],
            "added": result["added"],
            "removed": result["removed"],
            "tracked": len(files),
        }, ensure_ascii=True))
    else:
        if is_unchanged:
            print("UNCHANGED")
        else:
            for rel in result["changed"]:
                print("CHANGED %s" % rel)
            for rel in result["added"]:
                print("ADDED %s" % rel)
            for rel in result["removed"]:
                print("REMOVED %s" % rel)

    if not a.check_only:
        write_state(state_path, root, patterns, files)
    return 0


if __name__ == "__main__":
    sys.exit(main())
