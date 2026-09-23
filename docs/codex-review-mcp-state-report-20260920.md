# codex-review MCP 3-에이전트 연결 — GPT PRO 리뷰 검증 보고서 (2026-09-20)

작성: Devin (taskId `codex-review-mcp-verify-ba138c6b`)
대상: GPT PRO({스터프3})가 이전 "세 에이전트 연결 완료" 보고서에 제기한 지적을,
파일·코드·라이브 호출로 직접 대조한 결과와 개선안.

> 상태 표기: `config`=설정 파일에 등록됨 / `loaded`=앱 세션에서 서버·도구 목록 확인됨 /
> `status-verified`=`mode:status` 핸드셰이크 성공 / `review-verified`=`mode:review`
> 실제 생성+verdict 수신 / `unverified`=미확인.

---

## 1. 결론 요약

- GPT PRO의 지적 **대부분이 로컬 증거로 확인됨**. 특히 ① status≠review, ② `ask`≠잠금,
  ③ 22 vs 29 권한 차이의 원인(설정 누락) 세 가지 핵심 지적은 코드와 설정으로 **확인**.
- 다만 오늘 직접 라이브 호출로 **추가 확인된 사실**도 있다: Devin의 `codex-review`
  서버는 현재 세션에서 실제로 로드·호출되며, `mode:status`는 `ready`를 반환한다
  (`attemptCount:0`, 생성 없음 — GPT PRO 지적 그대로).
- 이전 보고서의 완료 판정은 **`status-verified`(Devin) + `loaded`(Grok doctor) +
  `config`(Codex)** 수준. `review-verified`는 **어느 경로에서도 기록이 없다**
  (`.codex/awx-control-tower.audit.jsonl` 부재, 리뷰 산출물 없음).

## 2. 오늘 직접 확인한 사실 (2026-09-20)

| # | 확인 항목 | 방법 | 결과 |
|---|---|---|---|
| F1 | Devin MCP 등록 | `%APPDATA%\devin\mcp_config.json` 읽기 | `codex-review` → hermes venv python + `scripts/awx_mcp_stdio_server.py`, `env.AWX_MCP_SOURCE_ACCESS=shared-read` |
| F2 | Devin 도구 수/내용 | 실제 `tools/list` (이 세션) | **22개**, 전부 `readOnlyHint:true` |
| F3 | Devin 실제 호출 | `codex_review_change {mode:"status", requestId:"devin-live-verify-20260920"}` | `{ok:true, status:"ready", authMode:"chatgpt", model:"gpt-5.5", attemptCount:0, verdict:"not_run", usage:null}` — 1.9s |
| F4 | status vs review 분기 | `scripts/awx_codex_review_adapter.py` | `mode=="status"`는 `turn/start` **이전**에 `ready` 반환(L423-424). review만 `turn/start`→FINAL_SCHEMA verdict+token usage 관측 |
| F5 | review 실행 조건 | 동 파일 L363-364 | owned stdio worker 컨텍스트 + `AWX_CODEX_REVIEW_DEPTH=0` 필수 → `recursive-invocation-blocked`/`worker-ownership-required` |
| F6 | 매니페스트 도구 구성 | `main/resources/mcp/awx-control-tower-tools.json` | 29개 중 **비-readOnly 정확히 7개**: `device_work, session_evidence, archive_restore, external_evidence_intake, desktop_dispatch_packet, producer_kit_export, desktop_control_loop` |
| F7 | shared-read 필터 위치 | `awx_mcp_stdio_server.py` L651, L686-687 | `tools/list`에서 숨기고, `tools/call`에서도 `shared_read_mutation_denied`로 거부 — 표시만 가린 게 아니라 **호출도 차단** |
| F8 | Codex 설정 | `~/.codex/config.toml` | `awx-control-tower`(직접 stdio, env=`PYTHONDONTWRITEBYTECODE`만 → **제한 없음, 29개**) + `awx-shared`(`awx_host_runtime.py run`, `AWX_MCP_SOURCE_ACCESS=guarded`, 명시 root/state-root). `codex-review` 이름의 서버는 없음 |
| F9 | Grok 설정 | `src/.grok/config.toml` | `awx-control-tower`: `command="python"`(시스템 파이썬), `args=["-B","scripts/awx_mcp_stdio_server.py"]`(**상대경로**), env 없음 → **제한 없음, 29개**. 사용자 `~/.grok/config.toml`에는 mcp 섹션 없음 → 프로젝트 설정이 유일 연결 |
| F10 | Cursor 설정 | `~/.cursor/mcp.json` | `Codex-Engine`(filesystem npx, 루트=`C:/Users/nninn/AbandonWareX` — **demo-1이 아님**) + `Memory-Vault`. AWX/codex-review 항목 없음 |
| F11 | 백업 시점 | `config*.pre-edit.bak` 내용+mtime 대조 | **내용은 변경 전 원본**(mcp bak에 `env` 없음, config bak에 `permissions` 없음). 단 bak mtime(12:27)이 현재 파일 mtime(11:25)보다 **~62분 늦음** → 사전 스냅샷이 아니라 **사후 재구성 저장** |
| F12 | Devin 권한 우선순위 | docs.devin.ai/cli/reference/permissions | org > 세션 승인 > `.devin/config.local.json` > `.devin/config.json` > **user config(최하위)**. 동일 레벨에서 더 구체적 `allow`가 `ask` 예외. 승인 프롬프트의 "Always allow"가 세션/영구 허용으로 전환 가능 |
| F13 | 프로젝트 권한 파일 | `.devin/` 목록 | `config.json`/`config.local.json` **없음**(hooks.v1.json, PROMPTS, RULES_SSOT.md만) → 현재 user `ask`를 덮는 상위 규칙 없음 |
| F14 | 리뷰 실행 기록 | `.codex/awx-control-tower.audit.jsonl` 확인 | 파일 자체가 없음 → audit_log 인자를 단 호출(실제 review 포함) 기록 없음 |
| F15 | 서버 루트 결정 | `awx_mcp_stdio_server.py` L30-31, L702-703 | `ROOT = __file__.parents[1]` → 클라이언트 cwd와 무관하게 항상 `C:\AbandonWare\demo-1\demo-1\src`. `root` 인자 미지정 시 자동 주입 |

## 3. GPT PRO 지적별 판정

### 3-1. "상태 확인을 실제 작업 성공으로 계산" — **지적 정당, 코드로 확인**

- `mode:"status"`는 스레드 생성·격리 검증·ChatGPT 인증까지만 하고 `turn/start` 전에
  종료된다 (F4). `attemptCount:0`, `usage:null`이 라이브 응답에서도 그대로 나왔다 (F3).
- Grok의 "실제 tools/call" 표기는 `mcp doctor` 핸드셰이크 근거였고, doctor는 도구 목록
  협상까지만 확인한다. F9 설정(env 없음)과 doctor 29개 관측이 서로 일치 → doctor는
  필터 없는 서버를 본 것이 맞다.
- 정정: 현재 상태 = Devin `status-verified`, Grok `loaded`(doctor), Codex `config`(+과거
  세션 증거), **3경로 모두 `review-verified` 없음** (F14).

### 3-2. "`permissions.ask`가 모든 상황에서 승인을 강제" — **지적 정당, 공식 문서로 확인**

- user config는 우선순위 **최하위**(F12). 세션 승인·프로젝트 config가 위에 있다.
- 동일 레벨에서 `mcp__codex-review__guard_status` 같은 구체적 `allow`는
  `mcp__codex-review__*` `ask`의 예외가 된다.
- 프롬프트에서 "Always allow this tool / all tools on this server"를 한 번 누르면
  그 뒤 `ask`는 사실상 무력화된다.
- 참고 관측: 이 Devin Desktop 세션에서는 F3 status 호출이 **승인 프롬프트 없이**
  실행됐다. readOnlyHint 도구이거나 세션/모드 정책상 무문의일 수 있으나, 이 자체가
  "ask=잠금" 해석이 성립하지 않음을 보여주는 사례다.
- 정정 표현: "`ask`는 기본 프롬프트 규칙이지 잠금이 아니다. 현재 상위 규칙이 없어
  Normal/Smart 모드에서 프롬프트는 나오지만, 세션/영구 승인·구체적 allow·Bypass 모드로
  우회 가능. 진짜 잠금이 필요하면 `deny` 또는 org/team 설정이어야 한다."

### 3-3. "Devin 22 vs Grok 29 권한 차이 미설명" — **지적 정당, 원인 특정 완료**

- 차이는 정확히 **7개 = manifest의 비-readOnly 도구 전수**(F6).
- Devin `shared-read` env가 `tools/list`·`tools/call` 양쪽에서 그 7개를 차단한다 (F7).
- Grok `.grok/config.toml`에는 `AWX_MCP_SOURCE_ACCESS` 자체가 없다 (F9) → 29개 전부
  노출되고 **`device_work`/`archive_restore` 등 변경성 도구가 호출 가능**하다.
- Codex `awx-control-tower`도 env 없음 → 동일하게 29개/변경 가능 (F8).
  `awx-shared`는 `guarded` 모드 + host_runtime 래퍼(명시 root/state-root)로 별도 역할.
- 즉 "공유 리뷰 경로 제한"은 **현재 Devin 연결에만 실제 적용**돼 있다.
  Grok/Codex의 무제한 연결은 기존 개발용 경로로 볼 수 있으나, 이름만으로는 구분 불가.

### 3-4. 추가로 아쉬운 부분 — 판정

- **작업 루트**: 서버 `ROOT`는 `__file__` 기반이라 Devin/Codex 연결은 cwd 무관하게
  정확하다(F15). 단 Grok은 상대경로 `scripts/awx_mcp_stdio_server.py`라 **Grok의
  spawn cwd가 프로젝트 루트일 때만** 기동된다(F9). doctor 성공은 그때 cwd가 맞았다는
  뜻일 뿐 상시 보증이 아니다.
- **백업**: 내용은 변경 전 원본이 맞지만(F11) 생성은 사후 ~62분. "수정 전 원본 보존"보다
  "변경 전 읽어둔 내용을 사후 저장"이 정확한 표현.
- **Cursor 근거**: 현재 `~/.cursor/mcp.json`에 AWX 연결 없음(F10). "Devin이 Cursor
  설정을 임포트했다"는 설명은 codex-review 항목의 출처를 설명하지 못한다 — 항목은
  이미 이전 편집부터 Devin 자체 mcp_config에 있었다(pre-edit.bak에 동일 항목 존재).
- **인터프리터 불일치**: Devin/Codex는 고정 hermes venv python을 쓰는데 Grok은
  bare `python`(F9). PATH 해석 결과에 따라 다른 런타임/의존성이 될 수 있다.
- **Grok exe vs 앱**: `~/.grok/config.toml`엔 mcp 없고 프로젝트 `.grok/config.toml`에만
  있음 — doctor가 검사한 경로가 실제 사용 중인 Grok 세션의 설정 소스인지는 해당 세션
  로그로만 확정 가능.

## 4. 현재 상태의 실제 리스크

1. **Grok 연결은 변경성 도구 7개를 그대로 호출 가능** (shared-read 미적용, F9).
   공유 리뷰 목적이면 `archive_restore`/`device_work`/`desktop_control_loop` 등이
   열려 있는 것은 의도된 제한이 아니다.
2. **Codex `awx-control-tower`도 무제한** (F8). 개발용으로 유지할지, 별도
   shared-read 리뷰 연결을 둘지 결정 필요 — 이름이 같아 Grok 연결과 혼동됨.
3. **실제 리뷰 검증 부재** — 어떤 경로도 `mode:review` 성공 기록이 없다 (F14).
4. **`ask` 규칙의 실효성 미검증** — 이번 세션에서 status 호출이 프롬프트 없이 통과.
   승인하지 않은 review 요청이 실제로 멈추는지는 확인된 바 없다.
5. **감사 공백** — audit_log 미지정 호출은 기록되지 않는다(F14). 검증용 호출에는
   `audit_log` 인자를 주는 습관이 필요.

## 5. 개선안 (우선순위, 최소 수정 — 새 서버/구조 변경 없음)

### P1. 완료 상태 표기 정정 (문서/보고서)
- "세 에이전트 연결 완료" → "설정 등록 완료, 앱 내 로드 확인(Devin·Grok),
  실제 리뷰 검증 대기"로 정정. 보고 시 `config/loaded/status-verified/review-verified`
  4단계를 분리 표기.

### P2. 필요 호출 경로마다 실제 리뷰 1회 검증 (GPT PRO 지시 그대로)
- 비밀 없는 작은 입력으로 `mode:"review"`, `reviewProfile:"economy"`
  (gpt-5.3-codex-spark, 별도 사용량 한도 — spend guard 적합) 한 번씩:
  - Devin: `codex_review_change` (codex-review 서버) — owned worker 경로로만 review 가능(F5)
  - Codex: `awx-control-tower` 또는 `awx-shared` 경로의 `codex_review_change`
  - Grok: `awx-control-tower` 경로의 `codex_review_change`
- 각 호출에 `requestId` + `audit_log`(예: `.codex/awx-control-tower.audit.jsonl`) 지정,
  응답의 `usage`/`verdict`/`lineage`를 보고에 첨부.
- 승인할 수 없는 대상은 "검증 대기"로 남기고 반복 호출 금지.

### P3. 공유 리뷰 경로에만 shared-read 적용 (기존 개발용 쓰기 경로 유지)
- Grok `.grok/config.toml` `awx-control-tower`에 `"env"={"AWX_MCP_SOURCE_ACCESS"="shared-read"}`
  추가 — 단 이 연결을 개발용으로도 쓰면 별도 `awx-shared-read` 항목으로 분리.
- Codex: 개발용 `awx-control-tower`(29개)는 유지하고, 리뷰·공유 목적 연결은
  `awx-shared`(guarded) 또는 shared-read 변형으로 명시 구분.
- `awx-control-tower` vs `awx-shared` 역할 문서화: 현재 둘 다 동일 29 도구 표면,
  차이는 env/래퍼뿐 — 이름이 아니라 **env가 제한을 결정**함을 명기.

### P4. Grok 설정 정합성 (최소 수정)
- `command`: `"python"` → hermes venv python 절대경로(Devin/Codex와 동일).
- `args`: 상대경로 → `C:\AbandonWare\demo-1\demo-1\src\scripts\awx_mcp_stdio_server.py` 절대경로.
- 수정 후 `mcp doctor`로 도구 수가 22개(shared-read 적용 시)로 줄어드는지 확인 —
  이것이 제한 적용의 직접 증거.

### P5. 작업 루트 반환 검증
- 각 앱에서 readOnly `guard_status`(또는 `tools/call`로 `root` 미지정 호출)를 한 번
  호출해 반환된 `projectRoot`가 `C:\AbandonWare\demo-1\demo-1\src`인지 확인.
  (서버는 `__file__` 기반이라 원칙적으로 항상 정확하지만, Grok의 상대경로 기동처럼
  "설정이 있다"와 "실제 그 루트를 본다"는 구분해서 기록.)

### P6. 백업·조사 표현 정정
- `.pre-edit.bak`: "수정 전 원본(내용 일치), 사후 저장본(생성 시각 11:25→12:27)"으로 기재.
- Cursor 조사: "예전에도 연결은 없었다"가 아니라 "이번 확인한 설정·잔여 기록에서는
  발견하지 못했다"로 정정.

### P7. 승인 규칙 실효성 시험 (선택)
- 승인하지 않은 `mode:"review"` 요청이 Devin에서 실제 프롬프트/차단되는지 1회 시험.
  `ask`가 무력화된 상태(세션 승인 잔존 등)면 그 사실을 기록하고, 필요 시
  `.devin/config.json`(프로젝트 레벨, user보다 상위)로 `ask`를 승격 검토.
  단 Bypass 모드/구체적 allow 우회는 설계상 존재 — 절대 잠금이 목표면 `deny`+구체적
  허용 조합 또는 org 설정이어야 함.

## 6. 금지/비목표 (GPT PRO 지시 반영)

- 새 서버·구조 변경 없음. Supabase 등 무관 설정 불변.
- Git 미사용. 기존 개발용 쓰기 권한 일괄 차단 금지 — 공유 리뷰 경로에만 제한.
- `.secrets/` 값 출력·기록 금지 (env 이름만).

## 7. 미검증 잔여 항목 (정직한 미완료 목록)

| 항목 | 상태 | 필요한 행동 주체 |
|---|---|---|
| Devin `mode:review` 실제 verdict | unverified | Devin 세션(승인 후 1회) |
| Codex 앱 내 `codex_review_change` 호출 | unverified | Codex 세션 |
| Grok 앱 내 `codex_review_change` 호출 | unverified | Grok 세션 |
| Grok doctor 대상 exe = 실사용 Grok 앱 | unverified | Grok 세션 로그 |
| `ask` 미승인 요청 차단 시험 | unverified | Devin 세션 1회 시험 |
| Codex `awx-control-tower`/`awx-shared` 역할 중복 최종 판단 | 부분 확인(env 차이 확인, 표면 동일) | 설정 정리 결정 |
