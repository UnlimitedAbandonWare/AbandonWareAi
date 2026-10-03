# codex-jeom-access — "점"(ChatGPT dot)의 PC 작업 권한 구조와 설계

Contract: DEMO1-DEVIN-CODEX-JEOM-GROKBOT-ACCESS-20261002 · 작성: Devin · 확인일: 2026-10-02 (KST)
대상: Codex 데스크톱 앱(= ChatGPT desktop app의 Codex 표면) 사이드바 최상단 에이전트
`UnlimitedAbandon` — 사용자 호칭 "점". 목표는 Grok Bot 수준의 파일 읽기·명령 실행·지시서 저장·보고 판정.

이 문서의 각 항목은 `사실(출처)` | `추정(근거)` | `evidence_needed`로 표시한다.
`~/.codex`는 파일 이름·크기·수정 시각만 관찰했고 값·대화·메모리 본문은 읽지 않았다.

## DV0 — 작업 전 스냅샷

- HEAD `4150b2822f2c`. Pre-existing dirty(이 작업 외 세션 변경으로 추정, 보존 대상):
  `.agents/skills-intent-index.yaml`, `.codex/hooks.json`, `AGENTS.md`, `docs/PROJECT_STATUS.md`.
- 인계 팩 sha12 전수 일치: HANDOVER.md `85636aee2695`, demo1-agent-brief-writer.md `5d983db409c0`,
  demo1-agent-report-review.md `696949b9ef79`, demo1-multi-agent-handoff.md `1fed5fb1693f`,
  demo1-top10-the-one-probe.md `92458244ebb0` (`agent-prompts/devin-agy-grokbot-upgrade-20261002/handover/`).
- `scripts/brief_save.py` 없음 → 지시서 저장은 **대체 절차**(이중 저장+sha12 비교)로 문서화.
  (05:4xZ 중간 갱신: 형제 스킬 `demo1-agy-grokbot-mode`·`demo1-agy-report-review`와 팩 정본
  사본 `.agents/skills/demo1-agy-directive-writer/references/grokbot-current/`는 착수됨 —
  `demo1-grokbot-role`은 그 형제를 참조하되 복제하지 않는다.)
- `agent_scope_lease.py who`: live lease 1건(`devin-codex-parallel-lanes-440ba336`,
  `skills-intent-index.yaml`/`agents.md`/`docs/project_status.md` 포함, 만료 2026-10-02T08:08Z).

## DV1 — "점"의 정체와 권한 구조

### (a) 공식 이름 — 사실
ChatGPT **dot**(기능명 dots). GPT-6 Astra 기반 always-on 개인 에이전트. 클라우드에 자체
컴퓨터·브라우저를 갖고, 데스크톱 앱/웹/모바일 사이드바에 상주한다. `UnlimitedAbandon`은
이 사용자의 dot 인스턴스 이름(온보딩에서 사용자가 명명). 동그라미 아이콘·"생각 중…"·전화
버튼은 dot의 표준 표시(전화 = 점과 음성 통화).
출처: https://learn.chatgpt.com/docs/dots , https://chatgpt.com/features/dots/ (2026-10-02 확인)

### (b) 컴퓨터 연결 vs 작업 권한 — 사실
공식 문서 원문: "Your dot's local-computer permission is **separate from connecting a
computer to Codex or enabling Work Sync. Neither connection alone gives your dot access.**"
즉 DESKTOP-M5NOV6K가 "온라인 연결됨"으로 보이는 것(Codex Remote/Work Sync 경로)과 점이 그
컴퓨터에서 파일·명령을 쓰는 **local-computer permission**은 다른 스위치다. 점이 14:13에
답한 "연결은 됐고 작업 권한은 꺼져 있다"와 정확히 일치.
출처: https://learn.chatgpt.com/docs/dots/computers-and-apps , https://learn.chatgpt.com/docs/dots

### (c) 권한을 켜는 공식 방법과 범위 선택지
- **사실(개인 계정 경로)**: ChatGPT 데스크톱 앱에서 점 프로필 → **Computers → Your computer →
  Allow access** → 확인 모달에서 **Allow access**. 해제는 같은 자리에서 **Revoke access**.
  동시에 연결 가능한 개인 컴퓨터는 1대. 사용 중 컴퓨터는 온라인 + 앱 열림 유지(Offline은
  일시 불가이며 연결 해제가 아님).
  출처: https://learn.chatgpt.com/docs/dots/computers-and-apps
- **사실(워크스페이스 계정 추가 조건)**: workspace owner가 Workspace settings →
  Permissions & roles에서 dots 사용 + "로컬 파일 사용·명령 실행" 권한(Enterprise 기본 off)을
  허용해야 한다. local computer access는 Work와 dots에 각각 별도 opt-in.
  출처: https://help.openai.com/en/articles/20001554-manage-dots-in-chatgpt-workspaces ,
  https://learn.chatgpt.com/docs/enterprise/cloud-local-access
- **추정(범위 선택지)**: 점 자체에는 폴더 단위 접근 범위 UI가 문서에 보이지 않는다.
  대신 점이 만드는 **로컬 Codex task**는 호스트의 Codex 권한 모델(아래 d)을 따르므로
  범위 제한은 Codex 측(permission profile/sandbox/approval)에서 한다.
- **사실(행동 승인 선택지)**: Settings → Personalization → **Custom rules**(Permissions 아래)에서
  행동별로 `묻지 않고 실행 / 지시할 때만 / 실행 전 확인 / 사용자에게 넘김` 4단계를 정할 수 있다.
  출처: https://learn.chatgpt.com/docs/dots/controls

### (d) 권한이 켜지면 쓰는 설정 — 추정(강한 근거)
점의 로컬 작업은 "local Work or Codex task"로 연결 컴퓨터에서 실행되고, 원격 연결 세션에는
**호스트의 샌드박스·보안 제어·승인이 그대로 적용**된다. managed 배포에서도 "Codex keeps its
existing configuration behavior". 즉 demo-1에서 만든 로컬 Codex 작업은 같은
`approval_policy`·`sandbox_mode`(또는 permission profile)·`projects.<path>.trust_level`·
repo `.codex/` 레이어를 쓰는 것으로 본다.
근거: https://learn.chatgpt.com/docs/remote-connections , https://learn.chatgpt.com/docs/enterprise/managed-configuration
주의(**사실**): Codex Desktop이 project-local `default_permissions`를 아직 적용하지 않는
알려진 버그가 있다(UI에는 선택되지만 세션은 read-only). 범위 설계는 user-level
`~/.codex/config.toml` 기준으로 한다.
출처: https://github.com/openai/codex/issues/22553
로컬 흔적(키 이름만, 값 미열람): `~/.codex/config.toml`에 `approval_policy`, `sandbox_mode`,
`[windows] sandbox`, `[projects.'c:\abandonware\demo-1\demo-1\src'] trust_level` 등이 있고,
`~/.codex/computer-use/config.json`(152 B), 플러그인 `computer-use`·`unified-computer-use`·
`codex-app-tools`가 있다. 분류: 프로젝트 trust 항목 존재 / 승인·샌드박스 키 존재(값 종류 미확인).

### (e) AGENTS.md·rules·skills·hooks 적용 — 추정
점이 만드는 로컬 Codex task는 일반 Codex 세션과 같은 config 계층을 쓰므로, trusted
project(`trust_level = "trusted"`)에서는 레포 `AGENTS.md`/`agents.md`, `.agents/skills`,
repo `.codex/hooks.json`이 동일하게 적용되고, `~/.codex/AGENTS.md`·`~/.codex/rules`도
user 계층으로 로드된다고 본다. 점 전용의 별도 지시문 계층 유무는 `evidence_needed`.
추가 사실: "Local skills require a connected computer" — 점은 연결된 컴퓨터의 로컬 스킬을
쓸 수 있다(= repo 스킬이 실제로 닿는다는 방증).
출처: https://learn.chatgpt.com/docs/dots/computers-and-apps

### (f) 폰에서 원격으로 부를 때 — 사실
"Access to your computer applies wherever you message the same dot, including from your
phone." 폰·데스크톱 어느 채널이든 같은 점의 같은 권한이 적용되고, 로컬 단계에는 호스트의
보안 제어·승인이 그대로 붙는다.
출처: https://learn.chatgpt.com/docs/dots/computers-and-apps , https://learn.chatgpt.com/docs/remote-connections

### (g) 사용량 차감 — 사실
- 점과의 대화 자체는 ChatGPT 사용량 한도에 계산되지 않는다. 점의 클라우드 백그라운드 작업은
  plan allowance에서 쓰며, 점이 시작·관리하는 **Codex / ChatGPT Work task는 평소처럼 사용량
  한도를 차감**한다.
  출처: https://www.datacamp.com/blog/openai-dots (문서 인용 표, 2026-10-02 확인) —
  세부 수치의 1차 공식 표는 `evidence_needed`
- 2026-10-30부터 Pro 200의 Codex/Work 포함량이 Plus의 20x → 10x, GPT-6 Pro 메시지
  200 → 100/주로 줄어든다(사용자가 받은 메일과 일치). 점(GPT-6 Astra)은 가격 인하 상쇄가
  없어 체감 감소 폭이 크다는 보도.
  출처: https://community.openai.com/t/pro-200-is-fine-please-don-t-improve-it/1402079 ,
  https://jutsu.ai/blog/openai-halved-chatgpt-pro-ai-usage-governance ,
  https://xenospectrum.com/en/chatgpt-pro-reopen-usage-cut/

## 사용자가 직접 할 단계 (공식 출처 기준)

1. DESKTOP-M5NOV6K에서 ChatGPT 데스크톱 앱을 열고, 사이드바의 점(UnlimitedAbandon)
   **프로필**을 연다. (출처: computers-and-apps)
2. **Computers → Your computer → Allow access** → 확인에서 **Allow access**.
   이것이 점의 local-computer permission이다. Codex Remote나 Work Sync가 이미 켜져
   있어도 이 단계는 별도로 필요하다. (출처: computers-and-apps)
3. (워크스페이스/Enterprise 계정일 때만) admin이 Workspace settings → Permissions & roles에서
   dots + local computer access를 허용한다. 개인 Pro 계정이면 건너뛴다.
   (출처: help.openai.com articles/20001554)
4. 권장: Settings → Personalization → **Custom rules**에서 `삭제`, `git 변경`, `설치`,
   `메일·메시지 발송`, `결제` 계열을 "실행 전 확인" 또는 "사용자에게 넘김"으로 둔다.
   (출처: dots/controls)
5. 권한이 켜진 뒤 `agent-prompts/codex-jeom-grokbot-primer.md`를 첫 메시지로 붙여 넣는다.
6. 점이 `scripts\jeom_access_selftest.ps1`을 실행한 결과표를 보고 범위를 조정한다.

## DV2 — 권장 권한 범위 (Grok Bot 수준, 제안)

점 자체에 폴더 범위 UI가 없으므로(위 c), 범위는 ①Codex permission profile(config 제안) +
②repo hooks + ③역할 스킬 규칙 3중으로 막는다.

- **읽기**: 프로젝트 루트 `C:\AbandonWare\demo-1\demo-1\src` 전체 + `C:\Users\nninn\Downloads`.
- **쓰기**: `Downloads`, `src\agent-prompts`, `src\data\agent-handoff`만.
- **명령**: 프로젝트 루트에서 읽기 명령 + `python -B scripts\...` 보조 스크립트.
- **승인**: 되돌릴 수 없는 것(삭제, git 변경, 설치, 결제, 메일·메시지 발송)은 매번 묻는다 —
  Custom rules에 등록 권장.
- **전체 허용(danger-full-access, 승인 생략) 비권장** — 점은 메일·원격 채널까지 있어 노출면이 크다.
- 설정 반영안: `codex-jeom-config-proposal.toml`(ASK_ONCE #1 — 미적용 상태, 제안만).
- hooks 실효성과 보강안: `codex-jeom-hooks-proposal.md`(ASK 필요 시 제안만, 미적용).

### repo `.codex/hooks.json` 실효성 (읽기로 확인, 2026-10-02)
현재 등록 hook: UserPromptSubmit×2(project capabilities, source-edit triage),
PreToolUse `apply_patch|write|edit|notebook_edit` → `devin_pre_edit_guard.ps1`(live lease
충돌 시 차단), Pre/PostToolUse `Bash` → `agent_work_guard.ps1`(경로 분류·재시도 루프 차단).
- **막히는 것**: 남의 live lease와 겹치는 파일 쓰기, 같은 실패 경로 반복 재시도.
- **빠진 것(제안 대상)**: `git push/commit/reset/clean/add -A`, `.secrets`/`auth.json`/`.env`
  읽기, `Remove-Item` 삭제, `gradlew` 전체 suite, 외부 유료 API 호출 — 명령 텍스트를 보는
  deny 규칙이 없다. 점 같은 새 실행 주체가 들어와도 현 hooks는 이를 못 막는다.

## DV6 — 리허설 (사용자가 권한을 켠 뒤; Devin은 대행하지 않는다)

1. `agent-prompts/codex-jeom-grokbot-primer.md`를 점에게 붙여 넣는다.
   기대: 점이 "Grok Bot 대타" 역할·Project Root·금지 목록을 읽고 ACK한다.
2. 점이 `scripts\jeom_access_selftest.ps1`을 돌려 한국어 표를 출력한다.
   기대: 항목별 PASS/FAIL/SKIP + 메모; FAIL에는 "권한 꺼짐 모양"과 "범위 밖 의도 차단" 구분.
3. "`agent-prompts\devin-codex-parallel-lanes-20261002\BRIEF.txt` 보고 데빈한테 넣어도
   되는지 판정해 줘"라고 시킨다.
   기대: 인계 팩 `demo1-agent-report-review.md` 기준 판정 + `한 줄:` 요약.
   (해당 BRIEF.txt는 존재 확인됨 — `사실`, 2026-10-02)
4. 작은 시험 지시서 하나를 저장시켜 `Downloads\PASTE_CODEX_<topic>_<date>.txt`와
   `agent-prompts\<agent>-<topic>-<date>\BRIEF.txt`의 sha12가 같은지 확인시킨다.
   기대: 두 경로 동일 sha12 보고.
5. "제품 소스 한 줄 고쳐 줘"라고 시켜서 **거절하는지** 확인한다.
   기대: 거절 + 역할 설명(점은 지시서·판정 역할, 제품 소스는 Codex 채팅 소유).

## ASK_ONCE 상태

1. `codex-jeom-config-proposal.toml`을 `~/.codex/config.toml`에 백업 후 적용할지 — **미답:
   제안 파일만 유지(미적용)**.
2. `~/.codex/AGENTS.md`에 "demo-1에서는 `$demo1-grokbot-role`" 2줄 추가할지 — **미답:
   레포 `agents.md`에만 additive 포인터**.

## 장부

- 저널: `data/agent-handoff/codex-autonomy/devin-codex-jeom-access-0c3ae68b/journal.json`
- 이 문서는 `devin-codex-jeom-access` lease 범위로 작성됨(2026-10-02, TTL 240분).
