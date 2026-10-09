# EVIDENCE — devin-gdoc-inplace-edit-skill-e6ff5b2e

지시서: DEVIN "구글 드라이브 대용량 .docx 원본 직접 편집" 스킬 + 포인터 (20261009)
실행: 2026-10-09 KST, agent=devin, shell=PowerShell 5.1. 외부 API 호출 0.

## W0 조사 결과 (사실 = 이 체크아웃에서 확인)

- 신규 스킬 필요: `rg`로 docs.google / 이력서 / 경력기술서 / 편집하기 / "너무 커서"
  관련 기존 스킬 없음 — 지시서 §1 주장과 일치(사실).
- 기존 스킬 실재 확인(전부 `.agents/skills/`):
  `demo1-codex-plugin-roles`(181줄), `demo1-dot-control-tower`(143줄),
  `demo1-session-state-checkpoint`(92줄), `demo1-work-ledger`,
  `demo1-vibe-selfask-judge-auto`, `demo1-triangulating-counter-evidence`,
  `positive-negative-neutral-judge`, `demo1-core-request-router`.
- frontmatter 형식: `---` / `name: <폴더명>` / `description: <비지 않음, ": " 포함 시 인용>` —
  검사기 `scripts/skill_frontmatter_lint.py` (exit 0=clean, 3=violation).
- 라우터 실체: `demo1-core-request-router`는 Display/RAG/LLM **제품 코어** 요청의
  분류 표라 gdrive 문서 편집 행이 들어갈 표가 아님(사실). 사용자 요청 라우팅 SSOT는
  `.agents/skills-intent-index.yaml`(`demo1-vibe-skill-router` resolve) — intent 블록
  형식: `intent/explicit/match[]/primary_skill/optional_skill/forbid_families/notes`,
  목록 끝(`fallback:` 직전)에 add-only로 추가. 지시서의 "라우터 1줄"은 최소 등록 단위인
  intent 1건으로 해석(좁은 되돌림 가능 해석으로 진행, journal에 기록).
- 검사기 존재: `skill_frontmatter_lint.py`, `agy_skill_pack_check.py --grade M`,
  `scripts/tests/test_vibe_router_golden.py` (52케이스, ≥90%).
- `configs/stack-fit.yaml:4` `story` = "Java Spring Boot 3 + Next.js BFF +
  RAG(LangChain4j·LangGraph4j·Lucene nori)" — 스킬 §5 참조와 일치.
- 진입 가드: goal-switch barrier가 기존 devin in-progress journal 2건
  (devin-dot-sandbox-skills-upgrade-0f30d9d4, devin-nova-focus-lens-blackout-ca31b360)
  으로 차단 → `switch --result superseded`로 닫고 진행. 잔여 lease 0.
- lease: `source_edit_session.ps1 -Action begin`으로 8개 타깃 lease 획득
  (fingerprint e87ac01b…). checkpoint `source-lease-scope` 규칙상 checkpoint 타깃은
  lease targetPaths 부분집합이어야 해 신규 파일 4개도 manifest에 포함.
- checkpoint: decision autonomous(riskScore 25), cycle-01 prepared → 8 타깃 preimage 보존.

## W1/W2 산출

- `.agents/skills/demo1-gdrive-docx-inplace-edit/SKILL.md` — 97줄, 5497B,
  frontmatter 통과, R-a~R-g 전부 §2~§7에 반영.
- `.agents/skills/demo1-gdrive-docx-inplace-edit/references/checklist.md` — 35줄
  (pack M companion).
- 포인터(add-only, preimage 대비 변경 0줄 확인 — PowerShell 라인 집합 비교):
  - `demo1-codex-plugin-roles/SKILL.md` +1줄: work-type 표에
    "Google Drive .docx 원본 편집" 행(180→181).
  - `demo1-dot-control-tower/SKILL.md` +5줄: "## 문서 편집 위임 포인터" 절(143→148).
  - `demo1-session-state-checkpoint/SKILL.md` +5줄: "## Long external document
    edits" 절(92→97).
- `.agents/skills-intent-index.yaml` +24줄: `gdrive-docx-inplace-edit` intent 1건
  (explicit:true, `fallback:` 직전, 1624→1648).

## W3 검증 결과

- `skill_frontmatter_lint.py --path .agents/skills/demo1-gdrive-docx-inplace-edit/SKILL.md`
  → `OK`, exit 0.
- `agy_skill_pack_check.py --skill … --grade M` → `VERDICT: PASS`
  (companions=1 intent=routed), exit 0.
- 라우터 resolve(`--text-file`, UTF-8):
  - T1 「299번 경력기술서 고쳐줘, 파일이 너무 커서 저장 안 된대」→
    intent=gdrive-docx-inplace-edit, primary=demo1-gdrive-docx-inplace-edit, score=2. PASS
  - T2 「이 이력서 링크 9시간 동안 고쳐줘」→ 같은 primary, score=1. PASS
  - T3 「제품 /chat 버그 고쳐줘」→ intent=source-write, primary=demo1-work-ledger
    (새 스킬 아님 — 오탐 없음). PASS
- `test_vibe_router_golden.py` → `Ran 2 tests ... OK`, pass 92.3%(48/52) ≥90%.
  실패 4건(g24-gitdoctor 없는 스킬 / g44 라우팅 순서 / g50 optional 불일치 /
  g52 노이즈 via=none)은 **HEAD 인덱스로 재실행해도 동일하게 실패** — 사전 기존
  베이스라인, 본 변경과 무관(head_probe.py 출력 보존).

## EXTERNAL_DRIFT / 사전 존재 문제 (내 변경 아님)

- `demo1-session-state-checkpoint/SKILL.md`, `demo1-dot-control-tower/SKILL.md`는
  이번 세션 이전부터 미커밋 변경이 있었음(HEAD 대비 큰 hunk; 내 diff는 preimage
  기준 add-only 확인). 작성자 미상 — 추정·기재하지 않음.
- 전체 frontmatter lint(174파일) 위반 2건: `demo1-devin-local-pin`,
  `demo1-jev-llm-decision-combo` (description 비어 있음 — 스킬 목록에서도 빈
  description으로 노출). 사전 존재, 범위 밖 — 기록만.
- Powershell 콘솔의 UTF-8 출력 깨짐(mojibake)은 표시 문제 — 파일 바이트는 UTF-8 정상
  (lint/resolve가 정상 파싱함으로 증명).

## Acceptance 대조

- A1 PASS — SKILL.md 97줄 ≤150, frontmatter 형식 일치·lint 0, R-a~R-g 전부 반영.
- A2 PASS — 포인터 3곳 +0줄 변경·+1/+5/+5줄 추가(각 ≤5줄), intent 1건 add-only.
- A3 PASS — T1·T2 → 새 스킬, T3 → source-write(오탐 0).
- A4 PASS — lint 0 + pack check PASS + golden OK + 깨진 링크 0(포인터 대상 전부 실재).
- A5 PASS — 보호 대상(제품 소스·Google 문서·config 계열) 쓰기 0건; 다른 세션의
  미커밋 변경은 위 EXTERNAL_DRIFT로 기록만.
- A6 PASS — 외부 API 0회, Downloads 신규 파일 0개(기록은 data/agent-handoff와
  docs/reports/agent-reviews만).
