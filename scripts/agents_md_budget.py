#!/usr/bin/env python3
"""agents_md_budget.py - AGENTS.md byte-budget report/check/split/restore.

Codex reads only the first ``project_doc_max_bytes`` (65,536 here) of a project
AGENTS.md; agy reads ~24,000. This tool reports which BEGIN/END rule blocks fall
beyond each budget, enforces a hard ceiling, and can move block bodies verbatim
into ``docs/agents-rules/<BLOCK-ID>.md`` while leaving a titled stub behind.

Subcommands:
  report [--json]                 byte totals, cut lines, CRITICAL/top blocks
  check                           exit 1 on size/CRITICAL/heading/marker failures
  split --plan [--out plan.json]  dry-run classification + stub preview
  split --apply --plan plan.json  move bodies to docs/agents-rules/, write stubs
  verify --ledger <dir>           re-check moved-blocks.json doc sha == source sha
  restore --from <backup>         restore AGENTS.md bytes from a backup copy

Stdlib only. Byte-exact: files are processed as bytes split on b"\\n".
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENTS_MD = ROOT / "AGENTS.md"
DOCS_RULES_DIR = ROOT / "docs" / "agents-rules"

HARD_LIMIT = 30_000
CRITICAL_LIMIT = 24_000
BUDGETS = (24_000, 32_768, 65_536)

# Blocks that must stay fully inline and inside the first CRITICAL_LIMIT bytes.
CRITICAL_IDS = (
    "DEMO1-PROJECT-ROOT",
    "DEMO1-PRIMARY-SURFACE",
    "DEMO1-CORE-AUTO",
)

# Blocks that stay inline verbatim (everything else with markers moves).
KEEP_INLINE_IDS = frozenset(
    {
        "DEMO1-PROJECT-ROOT",
        "DEMO1-PRIMARY-SURFACE",
        "DEMO1-CORE-AUTO",
        "DEMO1-GIT-REMOTE-SOLE",
    }
)

# `##` section headings that must remain in AGENTS.md (governance anchors:
# scripts/test_codex_instruction_governance.py ROOT_SECTION_MARKERS +
# EXPECTED_CONTRACT_REFS `AGENTS.md#<title>` references).
REQUIRED_HEADINGS = (
    "Desktop / Mac Mini / Notebook Workspaces",
    "Safe Patch Rules",
    "Prompt, Search, And Provider Hygiene",
    "Skill And Prompt Routing",
    "PatchDrop Bundle Rules",
    "Redaction",
    "Evidence And Verification",
    "Runtime Boundary And Active Runtime Map",
)

# "When to read the moved doc" one-liners per moved block id. Missing ids get
# an auto-derived summary (first bullet, truncated).
SUMMARIES = {
    "DEMO1-ANONYMOUS-VIBE-DEFAULT": "익명 우선 바이브 기본값 + demo.interview.enabled 플래그 의미 — 인증/로그인 관련 작업 전에 읽는다.",
    "DEMO1-OLLAMA-MODEL-LOCK": "Ollama 모델 잠금/허용 목록 — 모델을 RAG·Display·Spring 설정에 배선하기 전에 읽는다.",
    "DEMO1-GPU-LANE-EVIDENCE": "듀얼 GPU(3060+3090) 증거 체인과 사전 스냅샷 의무 — Ollama/GPU 라우팅 작업 전에 읽는다.",
    "SHARED-PROJECT-RESOURCES": "태스크 진입 시 device bus/공유 리소스와 .secrets 경계 — 세션 시작 절차를 확인할 때.",
    "DEMO1-AUTONOMOUS-SAFE-WORK": "자율 계속과 완료 시 정지($demo1-goal-complete-stop) 규칙 — 목표 수행·완료 선언 전에.",
    "DEMO1-WORK-LEDGER": "작업 원장: PROJECT_STATUS 읽기 → journal open → checkpoint → 검증 기록. 파일 변경 작업 필수.",
    "DEMO1-AGENT-GUARD-COMMON": "공통 가드 진입점: preflight/스킬 라우터/checkpoint/조건부 Git/워치독 명령 모음.",
    "DEMO1-DB-AGENT-SSOT": "로컬 H2(lmsdb) db_agent.py CLI — DB 읽기/쓰기와 lock(exit 3) 의미.",
    "DEMO1-GOAL-SWITCH": "목표 전환 배리어: 새 목표 전 stale journal/lease 정리와 reject-complete.",
    "DEMO1-CODEX-GOAL-INTAKE-CONTINUE": "goal-objective 읽기는 intake이지 Done이 아니다 — 완료 주장 전 확인.",
    "DEMO1-STALE-HANDOFF-REFERENCE": "낡은 인계 문서는 참조만 — 라이브 지시와 충돌 시 policy-conflict 기록.",
    "DEMO1-ASK-STEP-SEARCH": "질문 전 웹서치/단계적 전달 — 모호·불가역·비용 분기에서 묻는 절차.",
    "DEMO1-CODEX-AUTO-DECIDE": "선택 질문 전 자동 결정 기본표(codex_question_classifier.py) — 승인 퀴즈 전에.",
    "DEMO1-GROK-SUBSCRIPTION-REVIEW": "명시적 Grok 요청/독립 리뷰 필요 시 — 실행 전 수용 윈도우 증거 필요.",
    "DEMO1-TRIAD-DELIBERATION": "비자명 판정의 긍정/부정/중립 삼자 심의 — 언제 건너뛰는지 포함.",
    "DEMO1-CORE-REQUEST-ROUTER": "Display/RAG/LLM/API 코어 요청의 단일 primary 스킬 분류 — 코어 작업 진입.",
    "DEMO1-CODEX-PLUGIN-ROLES": "작업 유형별 Codex 플러그인 활성화 표와 보고서 PLUGIN_USAGE 의무.",
    "DEMO1-CODEX-HOTFIX-TOOLKIT": "Codex R2/Jev 핫픽스 판정 도구 인덱스 — 단계당 도구 하나.",
    "DEMO1-DEVIN-SOURCE-ORCHESTRATOR": "붙여넣은 멀티-심 지시서의 단계 분해(devin_task_orchestrate.py plan).",
    "DEMO1-META-RAYBAN-DISPLAY-RUNTIME": "렌즈 표시 계약·설정 우선·DevWatch/ForceRestart — Display 출력/주기 작업.",
    "DEMO1-BRAVE-DUAL-KEY": "Brave Free→Base 듀얼 키 라우팅 — 검색 API 키/쿼터 작업.",
    "DEMO1-PROVIDER-LIMITS-SSOT": "제공자별 요금 한도 문서 SSOT — 한도·플랜 재질문 금지 규칙.",
    "DEMO1-OPENROUTER-DESKTOP-ROUTING": "OpenRouter/Space Bunny 보조 조사 역할 — 프로덕션 배선 금지.",
    "DEMO1-CONVERSATE-HINT-EVIDENCE": "Conversate 힌트의 근거 라우팅·과거 맥락 윈도우 — 힌트 표시 문제.",
    "DEMO1-EVIDENCE-ZERO-RELEASE": "근거 0 = 본문 공개(HOLD 아님) — 답변 보류/공개 판정.",
    "DEMO1-NOVA-FOCUS": "노바 wake-word 포커스 대화 — focus 필드/idle 규칙, hint와 분리.",
    "DEMO1-INVISIBLE-EYE-AUTO": "설명되지 않는 동작·숨은 조건 분류 — Desktop 요청 라우팅.",
    "DEMO1-SOURCE-DIRECTIVE-AUTO": "정확한 SourceDirective 자동 실행 경로와 권한 경계.",
    "DEMO1-META-DISPLAY": "Meta Display webapp/동기 클라이언트/검증 스킬 입구 — Display 태스크.",
    "DEMO1-SPRING-VIBE-RELOAD": "Java 변경 후 DevWatch/ForceRestart + Verify-RAG — 재빌드·라이브 반영.",
    "DEMO1-SERVER-LIFECYCLE-VERIFY": "Start/Close/Verify BAT 표와 자율 재기동·freshness 증거 규칙.",
    "DEMO1-DEBUG-ENTRYPOINTS": "Debug-RAG/Debug-Meta-Display 진입점과 verify 액션 의미.",
    "DEMO1-RAG-DEBUG-TRAIL": "Read-RAG-Debug 스킬 없는 첫 진단 경로와 TRAIL 분류.",
    "DEMO1-AGENT-PORT-LEASE": "에이전트 동적 포트 lease 계약(agent_port_lease.py).",
    "DEMO1-TOOLCHAIN-AUTO-SELECT": "빌드/테스트 도구 자동 선택 — Gradle 우선, 검증 루프.",
    "DEMO1-ASSET-PRESERVATION": "자산 삭제/비활성 가능 작업의 보존 인벤토리 규칙.",
    "DEMO1-BUILD-PRUNE-RULE": "build/ 하위 사전 승인 정리 범위(prune_build_artifacts.ps1).",
    "DEMO1-LEASE-LIFECYCLE": "소스 편집 lease 수명주기: begin→heartbeat→end, stale reclaim, live 금지.",
    "DEMO1-DEVIN-MULTI-SESSION": "동일 체크아웃 다중 Devin 세션 격리·충돌·종료 규칙.",
    "DEMO1-DEVIN-DIRECTIVE-LOOP": "Devin 보고서 회신/지시서 작성 루프 — DRAFT/REVIEW/CLOSE.",
    "DEMO1-VIBE-SKILL-ROUTER": "모든 vibe 요청의 단일 primary 스킬 resolve 절차.",
    "DEMO1-TOOL-PLACEMENT-SCAN": "기존 도구 우선순위 2차 의견 스캔(자문 전용).",
    "DEMO1-ADAPTIVE-RULE-LAB": "스킬/룰 의미론적 정리·실험 — 병합/삭제 권한 없음.",
    "DEMO1-COMPLETION-CLEANUP": "검증 완료 경계에서의 자동 정리 규칙과 증거 바인딩.",
    "DEMO1-REQUEST-DIAGNOSTIC-CORRELATION": "디버그 요청 상관관계 근거 분리 규칙.",
    "DEMO1-LOCAL-FIRST-RAG": "Local-First RAG 수리 오버레이 — 소스 작업 전 확인·추적 순서.",
    "DEMO1-GIT-LOCAL-FIRST": "조건부 로컬 Git: 허용 명령·커밋 진입점·여전히 금지 목록.",
    "DEMO1-VIBE-GIT-AUTO-CONTINUE": "Git 소프트 브랜치 자동 계속(퀴즈 없이 AUTO 저널).",
    "DEMO1-VIBE-MAX-AGENCY": "4-에이전트 공유 루트/증거/재기동 계약 — 최대 범위와 금지.",
    "DEMO1-RTX3090-WATCH": "RTX 3090 활성·로컬 우선 정책과 헬스 워치 — GPU/API 폴백.",
    "DEMO1-PROTOTYPE-LIGHT": "프로토타입 라이트 모드 — 기본 도구·기본 OFF 스킬·관리자 불필요.",
    "DEMO1-PROTOTYPE-AUTH-LIGHT": "PROTO_OPEN 인증 경량 모드 — 운영 인증 강제 금지 조건.",
    "DEMO1-GOAL-FOOTER-THE-ONE": "Codex 골 푸터(THE ONE 프로토콜) — 골 지시서에 붙이는 문구.",
    "DEMO1-M21222AIN-ADAPTIVE-FALLBACK": "m21222ain 어댑티브 폴백 지시서 SSOT와 소유 레인.",
    "DEMO1-VERCEL-AI-GATEWAY-CREDIT": "Vercel AI Gateway/Jev 크레딧 메모와 실패 분류.",
    "DEMO1-COOP-VERIFY-RAILS": "협동 검증 레일(coop_verify.py) — DEFERRED는 PASS 아님.",
    "DEMO1-F01B-NARROW-JDBC-ASSIST": "F01-B narrow JDBC 어시스트 레일(Devin 측 게이트만).",
    "DEMO1-TRACE-DOCK-ASSIST": "Trace-dock always-on 어시스트 레일 — 제품 UI는 Codex 소유.",
    "DEMO1-VIBE-SELFASK-JUDGE-AUTO": "승인 퀴즈 전 POSITIVE/NEGATIVE/반례→중립 판정(AUTO/ASK_ONCE/HOLD).",
    "DEMO1-CODEX-SELFASK-TRIAD": "판정 갈림 시 SelfAsk 3축 서브에이전트 패킷 절차.",
    "DEMO1-CLEAN-QUARANTINE-RAILS": "Done/ASK 주장 전 필수 레일 + 격리 재마이닝 도구.",
    "DEMO1-APIKIT": "외부 API 작동 확인(apikit.ps1 check)과 실패 분류 SSOT.",
    "DEMO1-P6-RESILIENCE-RULES": "P6 복원력·검증·데이터 정합성 5대 지침(요약 SSOT).",
    "DEMO1-CODEX-PARALLEL-LANES": "같은 트리 다중 Codex 채팅의 레인 분할·쿼터·통합 게이트.",
    "DEMO1-GROKBOT-ROLE": "점(dot)/Codex 채팅의 Grok Bot 대타 역할 — 지시서·보고 판정.",
    "AWX-TEST-MODEL-POLICY": "RAG/챗 테스트 모델 정책 — 화면 기본 모델 대신 정책 모델 선택.",
    "SECTION-concurrent-desktop-and-notebook-editing": "Desktop/Notebook 동시 편집의 target-scoped 조정과 lease begin/verify 규칙.",
    "SECTION-codex-computer-and-environment-autostart": "Computer Use 플러그인 우선순위와 Ollama/로컬 LLM 자동 시작 규칙.",
    "SECTION-autolearn-handoff-review": "AutoLearn 실패 패치 전 handoff 파일 확인 순서와 train_rag.jsonl SoT.",
}

# Bare `##` sections whose bodies may move to docs (heading + pointer stays).
# Governance contract sections (REQUIRED_HEADINGS) are NEVER in this set.
MOVABLE_SECTION_HEADINGS = {
    "Concurrent Desktop and Notebook Editing",
    "Codex Computer And Environment Autostart",
    "AutoLearn Handoff Review",
}

MARKER_RE = re.compile(r"^<!--\s*(?:(BEGIN|END)\s+([A-Za-z0-9_.:-]+)|([A-Za-z0-9_.:-]+):(BEGIN|END))\s*-->\s*$")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_lines(path: Path) -> list[bytes]:
    data = path.read_bytes()
    lines = data.split(b"\n")
    if lines and lines[-1] == b"":
        lines.pop()
    return lines


def join_lines(lines: list[bytes]) -> bytes:
    return b"\n".join(lines) + b"\n"


def decode(line: bytes) -> str:
    return line.decode("utf-8", errors="replace")


def parse_blocks(lines: list[bytes]) -> tuple[list[dict], list[str]]:
    """Return (blocks, errors). A block spans BEGIN..END marker lines inclusive."""
    blocks: list[dict] = []
    errors: list[str] = []
    stack: list[dict] = []
    for idx, line in enumerate(lines):
        m = MARKER_RE.match(decode(line).strip())
        if not m:
            continue
        kind = m.group(1) or m.group(4)
        bid = m.group(2) or m.group(3)
        if kind == "BEGIN":
            if stack:
                errors.append(f"nested BEGIN {bid} at line {idx + 1} inside {stack[-1]['id']}")
            stack.append({"id": bid, "begin": idx, "style": "colon" if m.group(4) else "space"})
        else:
            if not stack:
                errors.append(f"END {bid} at line {idx + 1} without BEGIN")
                continue
            top = stack.pop()
            if top["id"] != bid:
                errors.append(f"END {bid} at line {idx + 1} mismatches BEGIN {top['id']} at line {top['begin'] + 1}")
                continue
            top["end"] = idx
            blocks.append(top)
    for dangling in stack:
        errors.append(f"BEGIN {dangling['id']} at line {dangling['begin'] + 1} has no END")
    blocks.sort(key=lambda b: b["begin"])
    for b in blocks:
        b["title"] = block_title(lines, b)
        b["bytes"] = sum(len(l) + 1 for l in lines[b["begin"] : b["end"] + 1])
        b["startByte"] = sum(len(l) + 1 for l in lines[: b["begin"]])
        b["endByte"] = b["startByte"] + b["bytes"]
    return blocks, errors


def block_title(lines: list[bytes], block: dict) -> str:
    title, depth = "", 2
    for line in lines[block["begin"] + 1 : block["end"]]:
        text = decode(line).strip()
        if text.startswith("#"):
            depth = len(text) - len(text.lstrip("#"))
            title = text.lstrip("#").strip()
            break
        if text:
            title = text[:80]
            break
    if not title:
        title = block["id"]
    block["titleDepth"] = depth
    return title


def line_offsets(lines: list[bytes]) -> list[int]:
    offsets = []
    pos = 0
    for line in lines:
        offsets.append(pos)
        pos += len(line) + 1
    return offsets


def cut_line_index(offsets: list[int], lines: list[bytes], budget: int) -> int:
    """First line index whose content begins at/after `budget` bytes."""
    total = sum(len(l) + 1 for l in lines)
    if total <= budget:
        return len(lines)
    for i, off in enumerate(offsets):
        if off + len(lines[i]) >= budget:
            return i
    return len(lines)


def headings(text: str) -> set[str]:
    found = set()
    for line in text.splitlines():
        m = re.match(r"^#{1,6}\s+(.+?)\s*$", line)
        if m:
            found.add(m.group(1).strip())
    return found


def build_report(path: Path) -> dict:
    raw = path.read_bytes()
    lines = load_lines(path)
    offsets = line_offsets(lines)
    blocks, errors = parse_blocks(lines)
    total = len(raw)
    budget_rows = []
    for budget in BUDGETS:
        cut = cut_line_index(offsets, lines, budget)
        beyond = [b["id"] for b in blocks if b["begin"] >= cut]
        spanning = next((b["id"] for b in blocks if b["begin"] < cut <= b["end"]), None)
        budget_rows.append(
            {
                "budget": budget,
                "fits": total <= budget,
                "cutLine": cut + 1 if cut < len(lines) else None,
                "blocksBeyond": beyond,
                "spanningBlock": spanning,
                "beyondCount": len(beyond) + (1 if spanning else 0),
            }
        )
    critical = []
    for cid in CRITICAL_IDS:
        hit = next((b for b in blocks if b["id"] == cid), None)
        critical.append(
            {
                "id": cid,
                "present": hit is not None,
                "endByte": hit["endByte"] if hit else None,
                "within24000": bool(hit and hit["endByte"] <= CRITICAL_LIMIT),
            }
        )
    text = raw.decode("utf-8", errors="replace")
    heads = headings(text)
    missing_heads = [h for h in REQUIRED_HEADINGS if h not in heads]
    top = sorted(blocks, key=lambda b: b["bytes"], reverse=True)[:15]
    return {
        "file": str(path),
        "totalBytes": total,
        "totalLines": len(lines),
        "blockCount": len(blocks),
        "markerErrors": errors,
        "budgets": budget_rows,
        "critical": critical,
        "missingRequiredHeadings": missing_heads,
        "topBlocks": [{"id": b["id"], "bytes": b["bytes"], "lines": [b["begin"] + 1, b["end"] + 1]} for b in top],
    }


def check_file(path: Path) -> tuple[int, list[str]]:
    rep = build_report(path)
    failures = []
    if rep["totalBytes"] > HARD_LIMIT:
        failures.append(f"SIZE_OVER totalBytes={rep['totalBytes']} > {HARD_LIMIT}")
    for c in rep["critical"]:
        if not c["present"]:
            failures.append(f"CRITICAL_MISSING {c['id']}")
        elif not c["within24000"]:
            failures.append(f"CRITICAL_BEYOND_24K {c['id']} endByte={c['endByte']}")
    for h in rep["missingRequiredHeadings"]:
        failures.append(f"HEADING_MISSING {h}")
    for e in rep["markerErrors"]:
        failures.append(f"MARKER_MISMATCH {e}")
    return (1 if failures else 0), failures


def slugify(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", title.casefold()).strip("-")


def build_segments(lines: list[bytes], blocks: list[dict]) -> list[dict]:
    """Unified segment list: marker blocks + text runs between them."""
    block_by_begin = {b["begin"]: b for b in blocks}
    segments: list[dict] = []
    i = 0
    n = len(lines)
    while i < n:
        if i in block_by_begin:
            b = block_by_begin[i]
            segments.append(
                {
                    "kind": "block",
                    "id": b["id"],
                    "title": b["title"],
                    "titleDepth": b.get("titleDepth", 2),
                    "start": b["begin"],
                    "end": b["end"],
                    "bytes": b["bytes"],
                    "markerStyle": b.get("style", "space"),
                }
            )
            i = b["end"] + 1
            continue
        # Split the text run at each `## ` heading so every bare section is its
        # own segment (preamble before the first heading stays one segment).
        start = i
        cuts = [start]
        while i < n and i not in block_by_begin:
            i += 1
        for j in range(start + 1, i):
            if decode(lines[j]).startswith("## "):
                cuts.append(j)
        cuts.append(i)
        for a, b in zip(cuts, cuts[1:]):
            heading = None
            title = ""
            for line in lines[a:b]:
                text = decode(line).strip()
                if not text:
                    continue
                if text.startswith("#"):
                    title = text.lstrip("#").strip()
                else:
                    title = text[:80]
                break
            if decode(lines[a]).startswith("## "):
                heading = decode(lines[a]).strip().lstrip("#").strip()
            seg_id = f"SECTION-{slugify(heading)}" if heading in MOVABLE_SECTION_HEADINGS else None
            segments.append(
                {
                    "kind": "text",
                    "id": seg_id,
                    "title": heading or title,
                    "heading": heading,
                    "start": a,
                    "end": b - 1,
                    "bytes": sum(len(l) + 1 for l in lines[a:b]),
                }
            )
    return segments


def auto_summary(lines: list[bytes], start: int, end: int, seg_id: str) -> str:
    for line in lines[start:end]:
        text = decode(line).strip()
        if text and not text.startswith("#") and not MARKER_RE.match(text):
            return text.lstrip("- ").strip()[:140]
    return f"세부 규칙 — 원본 {seg_id} 전문이 옮겨졌다."


def classify(segments: list[dict], lines: list[bytes]) -> list[dict]:
    plan = []
    for seg in segments:
        if seg["kind"] == "block":
            keep = seg["id"] in KEEP_INLINE_IDS
        else:
            keep = seg["id"] is None
        sid = seg["id"] or f"text@{seg['start'] + 1}"
        summary = SUMMARIES.get(sid) or auto_summary(lines, seg["start"], seg["end"] + 1, sid)
        plan.append(
            {
                "id": sid,
                "kind": seg["kind"],
                "decision": "KEEP_INLINE" if keep else "MOVE_TO_DOC",
                "title": seg["title"],
                "bytes": seg["bytes"],
                "lines": [seg["start"] + 1, seg["end"] + 1],
                "summary": summary,
                "doc": f"docs/agents-rules/{sid}.md" if not keep else None,
                "hasHeading": any(
                    decode(l).strip().startswith("#") for l in lines[seg["start"] : seg["end"] + 1]
                ),
                "titleDepth": seg.get("titleDepth", 2),
                "markerStyle": seg.get("markerStyle", "space"),
            }
        )
    return plan


def stub_lines(entry: dict) -> list[bytes]:
    bid = entry["id"]
    depth = "#" * int(entry.get("titleDepth") or 2)
    if entry.get("kind") == "text":
        out = [f"{depth} {entry['title']}", f"- {entry['summary']} — 상세: `docs/agents-rules/{bid}.md`"]
        return [line.encode("utf-8") for line in out]
    style_space = entry.get("markerStyle", "space") == "space"
    begin = f"<!-- BEGIN {bid} -->" if style_space else f"<!-- {bid}:BEGIN -->"
    end = f"<!-- END {bid} -->" if style_space else f"<!-- {bid}:END -->"
    out = [begin]
    if entry.get("hasHeading") and entry.get("title"):
        out.append(f"{depth} {entry['title']}")
    out.append(f"- {entry['summary']} — 상세: `docs/agents-rules/{bid}.md`")
    out.append(end)
    return [line.encode("utf-8") for line in out]


def run_plan(path: Path, out: Path | None) -> dict:
    lines = load_lines(path)
    blocks, errors = parse_blocks(lines)
    if errors:
        raise SystemExit(f"cannot plan: marker errors {errors}")
    segments = build_segments(lines, blocks)
    entries = classify(segments, lines)
    for e in entries:
        e["stubBytes"] = sum(len(l) + 1 for l in stub_lines(e)) if e["decision"] == "MOVE_TO_DOC" else e["bytes"]
    kept = sum(e["bytes"] for e in entries if e["decision"] == "KEEP_INLINE")
    stubs = sum(e["stubBytes"] for e in entries if e["decision"] == "MOVE_TO_DOC")
    projected = kept + stubs
    plan = {
        "version": 1,
        "sourceSha256": sha256_bytes(path.read_bytes()),
        "sourceBytes": len(path.read_bytes()),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "blocks": entries,
        "projected": {
            "totalBytes": projected,
            "keptBytes": kept,
            "stubBytes": stubs,
            "moveCount": sum(1 for e in entries if e["decision"] == "MOVE_TO_DOC"),
            "keepCount": sum(1 for e in entries if e["decision"] == "KEEP_INLINE"),
        },
    }
    if out:
        out.write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    return plan


def run_apply(path: Path, plan_path: Path, docs_dir: Path, ledger: Path | None) -> dict:
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    raw = path.read_bytes()
    source_sha = sha256_bytes(raw)
    if source_sha != plan.get("sourceSha256"):
        raise SystemExit(
            f"HOLD source-changed: file sha {source_sha[:12]} != plan {str(plan.get('sourceSha256'))[:12]}; regenerate plan"
        )
    docs_dir = docs_dir.resolve()
    lines = load_lines(path)
    blocks, errors = parse_blocks(lines)
    if errors:
        raise SystemExit(f"cannot apply: marker errors {errors}")
    segments = {s["id"]: s for s in build_segments(lines, blocks) if s["id"]}
    moved = []
    docs_dir.mkdir(parents=True, exist_ok=True)
    moved_at = datetime.now(timezone.utc).isoformat()
    replacements: dict[int, list[bytes]] = {}
    for entry in plan["blocks"]:
        if entry["decision"] != "MOVE_TO_DOC":
            continue
        bid = entry["id"]
        seg = segments.get(bid)
        if seg is None:
            raise SystemExit(f"plan references missing segment {bid}")
        body = join_lines(lines[seg["start"] : seg["end"] + 1])
        body_sha = sha256_bytes(body)
        header = f"<!-- moved-from: AGENTS.md L{seg['start'] + 1}-L{seg['end'] + 1} sha256={body_sha} movedAt={moved_at} -->\n".encode(
            "utf-8"
        )
        doc_path = docs_dir / f"{bid}.md"
        doc_path.write_bytes(header + body)
        doc_body_sha = sha256_bytes(doc_path.read_bytes().split(b"\n", 1)[1])
        try:
            doc_ref = str(doc_path.relative_to(ROOT)).replace("\\", "/")
        except ValueError:
            doc_ref = str(doc_path).replace("\\", "/")
        moved.append(
            {
                "id": bid,
                "kind": seg["kind"],
                "doc": doc_ref,
                "lines": [seg["start"] + 1, seg["end"] + 1],
                "bytes": seg["bytes"],
                "sourceSha256": body_sha,
                "docSha256": doc_body_sha,
                "shaMatch": body_sha == doc_body_sha,
            }
        )
        for i in range(seg["start"], seg["end"] + 1):
            replacements[i] = []
        replacements[seg["start"]] = stub_lines(entry)
    new_lines: list[bytes] = []
    for i, line in enumerate(lines):
        if i in replacements:
            new_lines.extend(replacements[i])
        else:
            new_lines.append(line)
    path.write_bytes(join_lines(new_lines))
    result = {
        "movedAt": moved_at,
        "sourceSha256": source_sha,
        "resultSha256": sha256_bytes(path.read_bytes()),
        "resultBytes": path.stat().st_size,
        "allShaMatch": all(m["shaMatch"] for m in moved),
        "moved": moved,
    }
    if ledger:
        ledger.mkdir(parents=True, exist_ok=True)
        (ledger / "moved-blocks.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return result


def run_verify(ledger: Path) -> int:
    record = ledger / "moved-blocks.json"
    if not record.is_file():
        print(f"no moved-blocks.json under {ledger}")
        return 2
    data = json.loads(record.read_text(encoding="utf-8"))
    bad = []
    for m in data.get("moved", []):
        doc = ROOT / m["doc"]
        if not doc.is_file():
            bad.append((m["id"], "doc-missing"))
            continue
        body_sha = sha256_bytes(doc.read_bytes().split(b"\n", 1)[1])
        if body_sha != m["sourceSha256"]:
            bad.append((m["id"], "sha-mismatch"))
    if bad:
        for bid, why in bad:
            print(f"MISMATCH {bid} {why}")
        return 1
    print(f"verify ok: {len(data.get('moved', []))} moved blocks, all doc sha == source sha")
    return 0


def run_restore(path: Path, backup: Path) -> int:
    if not backup.is_file():
        print(f"backup not found: {backup}")
        return 2
    path.write_bytes(backup.read_bytes())
    print(f"restored {path} <- {backup} sha256={sha256_bytes(path.read_bytes())}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default=str(AGENTS_MD), help="AGENTS.md path (default: repo root)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("report")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("check")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("split")
    p.add_argument("--plan", action="store_true", help="dry-run plan (default for split)")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--plan-file", default=None, help="plan.json path for --apply")
    p.add_argument("--out", default=None, help="plan.json output path for --plan")
    p.add_argument("--docs-dir", default=str(DOCS_RULES_DIR))
    p.add_argument("--ledger", default=None, help="dir for moved-blocks.json")

    p = sub.add_parser("verify")
    p.add_argument("--ledger", required=True)

    p = sub.add_parser("restore")
    p.add_argument("--from", dest="backup", required=True)
    p.add_argument("--expect-sha256", default=None)

    args = ap.parse_args(argv)
    path = Path(args.file)

    if args.cmd == "report":
        rep = build_report(path)
        if args.json:
            print(json.dumps(rep, ensure_ascii=False, indent=2))
        else:
            print(f"{rep['file']}: {rep['totalBytes']} B, {rep['totalLines']} lines, {rep['blockCount']} blocks")
            for row in rep["budgets"]:
                state = "fits" if row["fits"] else f"cut@L{row['cutLine']} beyond={row['beyondCount']}"
                print(f"  budget {row['budget']:>6}: {state} {row['blocksBeyond'] if not row['fits'] else ''}")
            print("  critical:")
            for c in rep["critical"]:
                print(f"    {c['id']}: endByte={c['endByte']} within24k={c['within24000']}")
            if rep["missingRequiredHeadings"]:
                print(f"  missing headings: {rep['missingRequiredHeadings']}")
            print("  top blocks:")
            for t in rep["topBlocks"]:
                print(f"    {t['bytes']:>6}  {t['id']}  L{t['lines'][0]}-{t['lines'][1]}")
        return 0

    if args.cmd == "check":
        code, failures = check_file(path)
        if args.json:
            print(json.dumps({"ok": code == 0, "failures": failures}, ensure_ascii=False))
        else:
            for f in failures:
                print(f"FAIL {f}")
            if code == 0:
                print(f"check ok: {path.name} within budgets")
        return code

    if args.cmd == "split":
        if args.apply:
            plan_file = Path(args.plan_file or args.out or "plan.json")
            ledger = Path(args.ledger) if args.ledger else None
            result = run_apply(path, plan_file, Path(args.docs_dir), ledger)
            print(
                json.dumps(
                    {
                        "moved": len(result["moved"]),
                        "allShaMatch": result["allShaMatch"],
                        "resultBytes": result["resultBytes"],
                    },
                    ensure_ascii=False,
                )
            )
            return 0 if result["allShaMatch"] else 1
        out = Path(args.out) if args.out else None
        plan = run_plan(path, out)
        print(
            json.dumps(
                {
                    "sourceBytes": plan["sourceBytes"],
                    "projectedBytes": plan["projected"]["totalBytes"],
                    "moveCount": plan["projected"]["moveCount"],
                    "keepCount": plan["projected"]["keepCount"],
                    "planFile": str(out) if out else None,
                },
                ensure_ascii=False,
            )
        )
        return 0

    if args.cmd == "verify":
        return run_verify(Path(args.ledger))

    if args.cmd == "restore":
        code = run_restore(path, Path(args.backup))
        if code == 0 and args.expect_sha256:
            actual = sha256_bytes(path.read_bytes())
            if actual != args.expect_sha256:
                print(f"sha mismatch after restore: {actual} != {args.expect_sha256}")
                return 1
        return code

    return 2


if __name__ == "__main__":
    sys.exit(main())
