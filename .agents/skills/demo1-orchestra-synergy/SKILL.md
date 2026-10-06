---
name: demo1-orchestra-synergy
description: >-
  Use when a user idea fans out across Grok Bot / agy / GPT Pro / Devin /
  Codex ("시너지", "오케스트레이션", "이거 누구한테 줘?", "여러 에이트로 굴려").
  Signal schema awx.orchestra-signal.v1 wraps the parallel-handoff fields;
  lanes are decided by orchestra_route.py which calls the existing
  classifiers (question classifier, skill router, task orchestrate) as
  evidence — it never reimplements them. All files local; nothing is sent
  to another agent automatically — the user pastes.
---

# demo1 orchestra synergy

사용자 아이디어를 Grok Bot이 키우고, 그 신호가 데빈·Codex·GPT Pro·agy로
알맞게 흘러가 다음 에이전트가 이어받게 하는 **신호·라우팅·현황판·붙여넣기**
층. 기존 도구를 감싼다 — 복사·재구현 금지.

## 언제 쓰는가

- 아이디어 하나가 여러 에이전트로 퍼질 때 (누가 무엇을 하는지 나눌 때)
- "시너지 / 오케스트레이션 / 오케스트라 / 누구한테 줘?" 요청
- 완료된 에이전트 결과를 다음 에이전트 입력으로 이을 때
- 안 쓸 때: 단일 에이전트 단일 파일 작업, 제품 소스 직접 수정 요청
  (그건 해당 소스 스킬/lease 게이트로)

## 역할표 (2026-10-03 사용자 지정)

| 에이전트 | 역할 |
|---|---|
| dot (ChatGPT "UnlimitedAbandon") | **지시서 작성·Downloads 배달 담당(아침 페이스, 2026-10-05 재고정).** 목표·스킬·자료를 받으면 `PASTE_CODEX_*.md`/`PASTE_<AGENT>_*.txt` 지시서를 만들고 Downloads sha12 MATCH까지가 완료. 세션 적용은 사용자가 손수 붙여넣기 — 자동 발송·자동 감시·패치 후 룰 자동 갱신 루프 금지(사용자 요청 단건 companion만). 상세: `DEMO1-DOT-CONTROL-TOWER` |
| Grok Bot | 아이디어·신호 키움(긍정/부정/반례→중립 연타), 신호를 devin-signal·codex 후보·research-question으로 쪼갬. PC 밖 — Downloads `PASTE_*.txt`로 지시서 전달 |
| Devin | 신호를 받아 도구·스크립트·규칙·검증·정리·현황판 생성, Codex 수정 후 검증 |
| Codex | 제품 소스 수정. 고점 높은 일은 GPT Pro 지시서를 받아 진행 |
| GPT Pro | ZIP+웹서치로 고점 작업 분석·패치·지시서 초안(사용자가 업로드, Pack-GPTPro는 사용자 전용) |
| agy | **4대 특화 우선**: 문서 수집, 코덱스용 정제 전달(3-Pack), 지시서 작성, 서브 리포터 — `docs/agents-rules/DEMO1-AGY-SPECIALIZATION.md`. 웹서치 근거 → web-evidence 신호; Grok Bot 부재 시 서브 역할(`demo1-agy-grokbot-mode`) |

위계:
- dot = 지시서 작성 + Downloads 배달(무엇을·누가 제안은 dot이 지시서로 만든다; 세션 적용·발송은 사용자 손수). Codex = 제품 소스 작성자. Devin = 룰·도구·검증 지원(dot 지시서를 도구로 굳힘, 뒤집지 않음). Grok Bot = 사용자 옆 분석·지시서 보조. agy·Grok CLI = 하위 작업자(조사·단건 위임).
- 충돌 시 우선순위: **사용자 직접 지시 > dot 최신 지시서 > 기존 SSOT 문구**. 단 비밀값·PROTO_OPEN·lease 안전 규칙은 누구도 못 바꾼다.
- dot이 PC에 못 붙을 때도 구조는 그대로: 사용자가 지시서 파일(Downloads 또는 카드 경유)을 받아 Codex에 손수 붙여넣으면 같은 효력.
- dot 대체 금지: 다른 에이전트가 dot의 지시서 작성 역할을 가져가지 않는다. Grok Bot·Devin은 dot 지시서에 **보조 지시서**(companion)만 쓴다.
- SERIAL_LANE: 한 Codex/dot 작업 세션 = **활성 지시서(PASTE) 1개**. 새 목표는 현재 목표를 Acceptance/HOLD로 닫은 뒤 새 세션·새 PASTE로만 진행 — 같은 컨텍스트 합치기 금지 (SSOT: `DEMO1-DOT-CONTROL-TOWER` §1-B, 잠금 INV-S1~S3).
- STAGED_METHOD: 모든 PASTE의 실행 순서 = 지시서 1개 → 스킬 resolve → 사실 → 작은 단계 → 검증 → 닫기 → 다음 (SSOT: `docs/agents-rules/DEMO1-STAGED-METHOD.md`, 검사 `scripts/staged_method_check.py`, 잠금 INV-M1~M4).

## lane 규칙 요약 (SSOT: scripts/fixtures/orchestra/route-rules.json)

| lane | 언제 | 다음 에이전트 |
|---|---|---|
| DEVIN | 도구·스크립트·규칙·검증·정리·현황판. 제품 소스 수정 없음 | devin |
| CODEX_DIRECT | 제품 소스, 범위 좁고 근거 PC 확인됨 | codex |
| GPTPRO_THEN_CODEX | 고점 표지 ≥2개(제품 3파일+/멀티seam, 설계 선택지≥2, 바깥 최신정보 필요, 품질 상한 상향, 이전 시도 2회 실패) | gptpro→codex |
| AGY_RESEARCH | 사실 확인·최신 정보·비교 조사가 먼저 + 문서 수집·코덱스용 정제 전달 등 agy 4대 특화 | agy → web-evidence |
| GROKBOT_AMPLIFY | 아이디어가 아직 흐릿함 | grokbot |
| AGY_AS_GROKBOT | Grok Bot 부재 시 같은 역할 | agy |
| ASK_USER | 되돌릴 수 없는 결정(공개 비용·데이터 삭제·권한 정책)만 | user |

가드: 같은 줄기 왕복 >2회 → ASK_USER. 401/403/429 → 재시도 대신
kind=question 신호(401/403→ASK_USER, 429→DEVIN). budget 기본값은
라이브 호출 0·재시작 0.

## 다섯 고리

상세(그림·완료 조건·최대 왕복): `references/loops.md`,
`docs/agent-tooling/orchestra-synergy-ko.md`

- L1 키우기: idea → Grok Bot 연타 → amplified → 쪼갬
- L2 조사: research-question → agy 웹서치 → agy_web_fuse → web-evidence
- L3 고점: GPTPRO_THEN_CODEX → Pack-GPTPro(사용자) + 근거 첨부 → GPT Pro
  지시서 → Grok Bot이 PC 소스와 대조 → codex-brief → Codex
- L4 짝 작업: Codex 작업 중 데빈은 겹치지 않는 파일로 검증 도구 →
  verify-finding → Codex/데빈/ASK_USER 분기
- L5 마무리: agent_signal_digest + grok_to_agy_memory_bridge sync 제안

## 명령 예시

```powershell
# 신호 만들기 (PASTE 파일에서)
python -B scripts/orchestra_signal.py new --from grokbot --kind amplified `
  --text-file %USERPROFILE%\Downloads\PASTE_x.txt --to devin --files scripts/x.py
# 라우팅 (분류기 3개 근거 포함)
python -B scripts/orchestra_route.py --signal <id> --apply
# 현황판 (<=30줄, 읽기 전용)
python -B scripts/orchestra_board.py --md
# 붙여넣기 초안 + 말로 줄 (파일만 생성, 전송 없음)
python -B scripts/orchestra_paste.py --id <id> --agent codex
```

## 3대 에이전트 핑퐁 규칙 (Codex ↔ Devin ↔ Grok)

단계를 나눠 부를 필요 없는 빠른 왕복은 `scripts/agent_quick_signal.py`
(배치 `scripts\Agent-Signal.bat`) — emit이 new+route --apply+paste를
한 번에 처리하고 `말로:` 줄과 PASTE 경로를 출력한다.

- 작업 완료·검증 요청·아이디어 전달 시 1줄 신호 권고 포맷:
  `Agent-Signal.bat emit --from <me> --to <target> --summary "..." --files <목록>`
- `--clip`: PASTE 본문을 클립보드로(clip.exe → Set-Clipboard 순).
- 받는 쪽: `inbox --agent <me>` 대기 확인 → `copy --id <id>` 본문 복사 →
  처리 후 `done --id <id>`로 archive+status=done.
- 무인자 `Agent-Signal.bat`: `[Codex: N] [Devin: N] [Grok: N]` + 번호 메뉴.
- `--no-classify`는 route 근거 수집(분류기 3 subprocess)을 건너뛴다.

## 금지

- 다른 에이전트 창·방·DM에 자동 게시하는 코드 추가 금지
- 기존 분류기·라우터 로직 복사/수정 금지 — subprocess 호출만
- mock·dry-run 결과를 evidenceTier=확인됨 으로 올리지 않는다
- gptpro_pack.py 직접 실행 금지(사용자 전용), 유료 호출·웹서치 호출 0
- 남의 lease·ledger·신호 파일 수정 금지 (겹침은 경고만)

## 연결되는 기존 자산 (링크만)

- `scripts/devin_task_orchestrate.py` (plan/self-test), `scripts/agent_signal_digest.py`
- `scripts/codex_question_classifier.py`, `scripts/awx_skill_router.py`
- `scripts/agy_web_fuse.py`, `scripts/grok_to_agy_memory_bridge.py`
- 스킬: demo1-devin-source-orchestrator, demo1-codex-parallel-lanes,
  demo1-agy-grokbot-mode, demo1-grokbot-role, demo1-agy-directive-writer
