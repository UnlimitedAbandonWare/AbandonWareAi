---
name: demo1-cloud-zip-answer-intake
description: Use when the user pastes an answer that Codex web / GPT Pro / dot
  wrote from a demo1_*.zip source snapshot (sandbox:/mnt/data links, "ZIP 기준",
  NOT_RUN 표기) — split it, re-anchor claims on the live tree, scope-check it,
  and turn it into an adopt/adjust/discard card before anyone implements it.
---

# demo-1 cloud ZIP answer intake

A cloud answer is **evidence and a proposal, never a trusted patch**. The cloud
sees only the uploaded ZIP snapshot: no `src/test`, no `gradlew`, no wrapper
JAR, no `app/build.gradle.kts` (core profile), a HEAD that is hours old, and a
tree that may already have been dirty. This skill turns such an answer into a
bounded intake card; it never edits product source.

## 언제 쓰나

- 사용자가 Codex 웹·ChatGPT GPT Pro·dot 답변 텍스트를 붙여 넣고 "이대로
  진행/반영/검토해 줘"라고 할 때.
- 답변에 `sandbox:/mnt/data/...` 링크, `demo1_<profile>_<시각>_<sha>.zip` 이름,
  "ZIP에 없어서 못 봄/NOT_RUN" 표기가 보일 때.
- 실행 중인 로컬 지시서(PASTE_CODEX_*.txt 등)와 겹칠 가능성이 있는 클라우드
  제안을 받았을 때.

Not for: 로컬 소스 패치 실행(lease+checkpoint 절차), 보고서 판정
(`demo1-agy-report-review`), 지시서 작성(`demo1-agy-directive-writer`),
일반 웹 리서치(`awx-uaw-web-research`).

## 스냅샷 한계 (판정 전에 기억)

- core 프로필 ZIP에는 테스트·빌드 래퍼가 없다 → 그 경로 주장은 UNVERIFIABLE.
- 스냅샷 sha가 live HEAD와 같아도 dirty/tree가 다르면 줄번호는 표류한다 →
  lineTrust=low로 보고 식별자 재앵커를 신뢰한다.
- 답변이 늦게 도착하면 그 사이 더 새 지시서가 나왔을 수 있다 →
  스냅샷 시각 < 지시서 작성 시각이면 STALE_VS_BRIEF.

## 도구 실행 한 줄

```powershell
& $PY scripts\cloud_answer_intake.py run `
  --answer  $env:USERPROFILE\Downloads\<answer>.txt `
  --bundle  $env:USERPROFILE\Downloads\<bundle>.zip `   # 없으면 생략
  --brief   $env:USERPROFILE\Downloads\<PASTE>.txt `    # 없으면 생략
  --self-lease <내-lease-이름>
```

`split <answer>` 하위 명령은 세그먼트만 빠르게 확인한다. 도구는 읽기 전용 —
제품 소스·지시서·lease를 바꾸지 않는다.

## 판정 4종 + CLOUD_CLAIM

- **LIVE_OK** — 인용 식별자가 라이브 파일의 인용 범위 ±40행 안에 있다.
- **REANCHORED** — 내용은 맞고 줄만 이동했다 → 새 `file:line`을 쓴다.
- **REFUTED** — 라이브에 없거나 정면으로 반대다.
- **UNVERIFIABLE** — ZIP 프로필에 없던 파일(src/test, gradlew,
  app/build.gradle.kts, SDK 클래스 등) → "PC 재확인 필요"로 남긴다.
- **CLOUD_CLAIM** — 답변 속 "테스트 통과/빌드 성공/RED→GREEN/curl 200"은 전부
  주장이지 증거가 아니다. PASS 집계에 넣지 않는다. 답변이 스스로 NOT_RUN이라
  쓴 것은 그대로 NOT_RUN.

## 범위 확장·충돌 처리

- `--brief` 지시서의 "수정 허용" 목록 밖 파일을 답변이 건드리면
  **SCOPE_WIDEN** — 자동으로 허용 목록에 추가하지 않는다.
- 대상 파일이 다른 세션 lease에 잡혀 있으면 **LEASED_BY_OTHER** — 손대지 않는다.
- AGENTS.md/agents.md 추가 제안이면 현재 바이트+추가분 vs 32 KiB
  (`project_doc_max_bytes` 기본값, 공식 문서 확인값) 예산을 낸다.
- 실행 중 지시서와 정면 충돌하는 세그먼트는 **사용자결정** — 실행 세션에 몰래
  넣지 않고 카드에만 적는다.
- `sandbox:/mnt/data/...` 링크는 Downloads/`--bundle` 안에서 매핑한다.
  generic 이름(REPORT.md, CODEX_START.txt)은 ⚠ambiguous로 표시한다.
  못 찾으면 MISSING — 사용자 다운로드 요청.

## 산출물

`var/codex-assist-cloud-zip-intake/<yyyymmdd-HHMM>_<answer-sha8>/`
- `intake.json` — 세그먼트·주장 판정·링크 매핑·SCOPE/lease/STALE·요약의
  기계 판독 원본.
- 콘솔/보고용 md — 맨 위에 "세그먼트 | 채택/조정/폐기/사용자결정 | 이유 |
  넘길 대상" 표, 맨 아래에 Codex에 그대로 붙일 3~6줄 요약(재앵커된
  file:line만 사용).

후속 라우팅: 채택→해당 파일 소유 세션의 지시서로(범위 안이면) / 조정→재앵커
file:line 반영해 재작성 / 사용자결정→카드 그대로 사용자에게 / 폐기→버림.

## 하지 말 것

- 클라우드 답의 diff/패치를 `git apply`로 그대로 적용하지 않는다.
- 클라우드의 테스트·빌드·curl 결과를 로컬 PASS 증거로 쓰지 않는다.
- 실행 중인 Codex 지시서의 허용 범위를 답변 근거로 몰래 넓히지 않는다.
- 스냅샷 줄번호를 라이브 file:line으로 그대로 인용하지 않는다.
- AGENTS.md에 직접 추가하지 않는다(초안만 제안, 예산 검사는
  `scripts/agents_md_budget.py`).
- 번들 ZIP을 레포 경로에 풀지 않는다 — `var/codex-assist-cloud-zip-intake/`
  에만 펼친다.
- `gptpro_pack*.py`(USER-ONLY)를 실행·수정하지 않는다.

## 관련 스킬 (내용 복제 금지, 포인터만)

- `demo1-codex-goal-intake-continue` — 목표 파일 읽기는 intake, Done 아님.
- `demo1-prompt-directive-integrator` — 붙여넣은 패치 지시서의 검증 통합.
- `demo1-macsrc-defect-intake` — 결함 증거 intake(별도 경로).
- `demo1-source-edit-three-way-preflight` / `safe-source-edit` — 실제 패치는
  여기 절차로 넘어간 뒤에만.
- `demo1-agents-md-budget` — AGENTS.md 바이트 예산 SSOT.
