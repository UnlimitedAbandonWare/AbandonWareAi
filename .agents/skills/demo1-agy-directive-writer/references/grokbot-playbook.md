# GrokBot playbook (distilled from the fidn.txt session record)

Source records: `%USERPROFILE%\Downloads\fidn.txt` and
`%USERPROFILE%\OneDrive\Desktop\bot.txt` (L13806-13820) — GrokBot conversation
transcripts (2026-09-26 ~ 09-30) showing the observed Korean directive-writing
workflow. agy inherits this flow (`agy-korean-grokbot-role.md`). This file is
the distilled contract of HOW GrokBot answered those asks; live facts must
still be re-verified per SKILL.md §2 — this playbook is shape, not facts.

## 0. 상시 지침 7원칙 — SSOT `docs/GROKBOT_DIRECTIVE_PLAYBOOK.md`

지시서 작성(agy `demo1-agy-directive-writer`와 웹 GrokBot 세션 공통)의 상시 규격.
상세·유지는 `docs/GROKBOT_DIRECTIVE_PLAYBOOK.md`가 권위다 — 여기는 요지만 둔다.

1. **입력 게이트(최우선)** — 첨부별 열림/일부/못 열림 + 기준 시점 표 우선;
   핵심 첨부 못 열면 "X를 열 수 없음. 재첨부 요망" 한 줄만 답하고 중단.
2. **최신본만 기준** — 최신 스냅숏만 인용; file:line에 출처 파일명; 구버전 라인 재사용 금지.
3. **기존 결정 보존** — INV/DONE/HOLD 먼저; 제안마다 DONE/PARTIAL/NEW/CONFLICT;
   뒤집기는 공식 근거+ASK_ONCE만; "자료 없어서 NEW" 금지.
4. **증거 등급 분리** — 직접확인/전달보고/공식문서/비공식/추론; 미확인=evidence_needed·null.
5. **기본값 불변** — 새 기능 off·유료 false·ZDR false·상한 0; mock만; commit·push 금지.
6. **산출 크기** — WP≤5; seam(파일:메서드)·RED→GREEN·완료 명령·금지 파일; 소비처 없는 기능 금지.
7. **보고 순서** — ①입력 게이트 표 ②결론 ③한계 ④본문; 미실행=NOT_RUN.

## 1. Triage pattern: Top10 → Shortlist3 → THE ONE

When the user asks "제일 큰 문제", "뭐가 문제야", "뭐부터 고쳐":

1. List Top ~10 issues with severity tags (`P0`/`P1`/`P2`) and one-line evidence
   each (실측: command + observed output or file:line).
2. Collapse to a shortlist of 3 candidates with names.
3. Pick exactly **THE ONE** — the smallest irreversible-risk fix or the highest
   leverage wiring gap ("배선이 없어서 못 찾는다" beats "로직이 나쁘다").
4. Emit a paste-ready directive for THE ONE only; note that the rest are
   deferred ("다음" list), never silently dropped.

Anti-pattern observed in the record: listing problems without committing to
one; the user expects the ranking to end in a single executable directive.

## 2. Directive anatomy (observed file layout)

```
agent-prompts/<target>-<topic>-<yyyymmdd>/
    <TARGET>_KICKOFF.md      # full SSOT (evidence, WP table, acceptance)
    PASTE_TO_<TARGET>.md     # paste-ready body (the actual directive)
    PASTE_SHORT.txt          # optional one-screen version for chat paste
    brief.md                 # optional source brief
%USERPROFILE%\Downloads\PASTE_<TARGET>_<TOPIC>_<yyyymmdd>.txt   # Downloads copy
```

- Every directive opens with `# <Target> 붙여넣기` / `# <Target> START` and
  `Project Root: <repo>` in the first lines.
- Every directive ends with a report contract: `<TASK_ID>: DONE|PARTIAL`
  (sometimes a per-WP table + `NOT_RUN:` list + `HARD-STOPS-OK` block).
- Directives always carry an explicit Must-NOT list; standing items:
  secrets 출력·커밋 금지, push/`add -A`/`reset --hard` 금지, remote
  추가·변경 금지, proto-open 끄기/admin harden 금지, 타 세션 lease·staging·build
  wipe·process kill 금지, 유료 API 무승인 호출 금지, 벡터/DB wipe 금지.
- "완료 = Acceptance PASS. '파일만 읽음' ≠ 완료." — completion must name
  observable checks, never reading alone.

## 3. Session lifecycle texts (reuse these shapes)

**새 세션 재개** — when a task continues in a fresh agent session:

```
새 세션 재개. 이전 스레드 로그 전체 읽지 말 것.
Project Root: <repo>
지시: agent-prompts/<dir>/PASTE_TO_<TARGET>.md
전체: 같은 폴더 <TARGET>_KICKOFF.md
패키지: %USERPROFILE%\Downloads\<pkg>\
<이전 세션 상태 한 줄 — 무엇이 DONE이고 무엇을 하지 말 것>
```

Key rule from the record: never tell the new session "read the whole old
thread" — the PASTE/KICKOFF files are the handoff.

**세션 종료** — when stopping a running agent session:

```
STOP EXPAND. 새 기능/다른 WP 시작 금지.
1) 진행 중인 검증만 끝내고 수치 확정 (실패 0 주장 금지)
2) 타인 lease 파일 수정·대기 루프 금지 — 메모만
3) commit/push/add -A 금지, staging 보존, secrets 미출력
4) 보고: <TASK>: PARTIAL|DONE + 완료 범위 + 남은 것 + NOT_RUN
```

## 4. First-message ACK (what the receiving agent answers)

```
ACK <slug>
Root: <repo>
Plan: <WP0> → <WP1> → … 
<핵심 금지 1줄 — 예: No Groq hammer / no push>
Starting <WP0>
```

## 5. Role split (standing; durable-facts §Roles is authoritative)

Codex = 제품 소스(main/java·static JS·yml) · Devin = 런타임 증거·도구·레일·
smoke · Clean = 검증·룰/지침·red-team · Grok(→agy가 계승) = 도구·mock·
지시서·probe · GPT Pro = 설계 검토 · user = 결정·승인.

Cross-owner items become paired packets with an explicit ownership table
("이 파일은 Devin 소유 — Clean 중복 패치 금지") — never implicit overlap.

## 6. Reporting honesty (repeated throughout the record)

- DONE/SKIP/HOLD per unit, never a blanket "전부 완료".
- `NOT_RUN` + reason for anything not executed; `evidence_needed` for unproven
  claims; `PRE_EXISTING`/`KNOWN_BASELINE` for failures unrelated to the patch.
- A mock/offline pass is reported as such — never counts as live-provider
  verification.
- Mock-only completion uses `DONE_OFFLINE`; live-verified is `DONE_LIVE`/`DONE`.

## 7. When the user pastes an agent's report back

Observed pattern: (1) judge the report honestly — praise adherence, name the
exact gap (e.g. "API-01 HOLD because opening the common switch also opened
openai-economy"); (2) reply with either a short corrective paste ("다음 세션은
X만") or a full new directive; (3) keep the completed units out of the new
scope ("F01–F04는 완료 — 재구현 금지").
