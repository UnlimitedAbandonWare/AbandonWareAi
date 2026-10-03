# DEVIN STEER — AutoGrade B GAP 오케스트레이션

- 계약: `DEMO1-AUTOGRADE-B-DEVIN-STEER-CODEX-20260928-R1`
- 부모: `DEMO1-AUTOGRADE-B-TARGETS-20260928-R1` / GAP: `DEMO1-AUTOGRADE-B-GAPS-20260928-R1`
- SoT: `C:\Users\nninn\Downloads\PASTE_DEVIN_steer_codex_B_gaps.txt` (지시 본체, §3 문장 원본)
- 역할: **Devin = 키잡이**(유도·감시·재킥·레일), **Codex = 손**(live src 패치). 제품 diff by Devin = 0 유지.

## 1. Codex 대상 세션

| 항목 | 값 |
|---|---|
| Codex task | `autograde-b02-0928-74c07d0e` (agent `codex-autograde-b-0928`) |
| 저널 | `data/agent-handoff/codex-autonomy/autograde-b02-0928-74c07d0e/journal.json` |
| 패치 대상 | `main/java/com/example/lms/api/ChatApiController.java` (preimage sha256 `0b0b81…f598`) + `src/test/java/com/example/lms/api/ChatApiAgentPromptEvidenceTest.java` |
| Grok assist | `autograde-b-assist-20260928-073ce6ae` — B00 체크리스트·remap·B02 repro·handoff 레일 (lease `autograde-b-assist-20260928`, 산출 `scripts/autograde_b_rail.py` + `docs/diagnostics/autograde-b-assist-20260928/*`) |

## 2. 상태머신 판정 (갱신 시각 2026-09-28 09:1x UTC)

| S# | 단계 | 상태 | 증거 |
|---|---|---|---|
| S0 | INIT | done | journal plan 09:06:14Z "GAP R1 adopted" |
| S1 | B00 실측 | done | `autograde-b02-baseline-0928/run.json` exit 0, 18/18 PASS (WebMvcRuleBreakRegistration 7 + NovaAutoConfigSourceContract 11) → RuleBreak/imports NO_CHANGE_VERIFIED 경로 |
| S2 | remap | done | journal 09:07:55Z: P06 WebSearchTool live SHA = map → 무편집; 진짜 소비자 `ChatApiController` supplier가 `executionStatus` drop 확인 |
| S3 | B02 | **done (GREEN)** | RED `verify-red/run.json` exit 1 (13t/12f, 09:10:31Z) → `ChatApiController.java` 패치(post sha `138170a7…`, `cycle-02-source`) → GREEN `verify-green/run.json` exit 0, **81/81 PASS** 09:12:36Z (6 focused suites: ChatApiAgentPromptEvidence 13, AgentPromptEvidence 4, AgentWebSearchToolConditionalWiring 19, AgentToolInvokerSecurity 23, ChatRequestSettingsMerger 17, SessionSettingsPrecedence 5) |
| live | 런타임 검증 | done | `runtime-start` passed → `runtime-verify` = `Verify-RAG.bat` **exit 0** (PID36112, freshness=current) |
| S4 | B01 | **done (no-change GREEN)** | Codex verify 이벤트: B01 = merger17+session5=**no-change22/22** — 명시 OFF 보존 기존 테스트로 입증, 패치 불필요 (SettingsService/PromptContext 무수정) |
| S5 | B03 | conditional | 증상/근거 없음 → 미진입 (정상) |
| S6 | REPORT | done-in-flight | Codex 보고서 `docs/diagnostics/autograde-b-gaps-0928.md` + `status-row.txt` 작성; PROJECT_STATUS CAS 충돌(b056a6cf→f0105519 외부 변경)을 거부·별도 사이클로 처리 — 외부 바이트 미덮어쓰기 확인(정상 ledger 동작). 저널 in_progress(종료 기장 중) |

빗나감 없음. `overrides_fired: []`. **데빈 Done 달성**: Codex S3 B02 GREEN + B01 GREEN + live Verify-RAG exit0, product diff by Devin = 0.

## 3. 주입 문장 카드 (복붙용 — 트리거 발생 시만)

### K1 B02 GREEN 보고 후 (정상 진행)
```
B02 GREEN 확인. CONTINUE: B01 — useWebSearch=false 요청이 merge 이후에도 false로 보존되는지
focused RED→patch→GREEN. 명시 OFF 존중, live-ON 버그는 OFF 아닌 요청에만.
```

### K2 최종 종료 게이트 (B02+B01 GREEN)
```
보고 필수: B00 / remap / B02 tests / (B01) / admin_browser=SKIPPED / full_suite=NOT_RUN.
commit 하지 마. 다음 증상 없으면 정지.
```

### O1 admin/보안으로 샐 때 (G-P1)
```
STOP 범위 이탈. admin Browser·SecurityConfig harden은 이번 GAP Done이 아니다.
PROTO_OPEN 유지. 지금 큐로 복귀: B02 (검색 실패≠빈성공) → 필요 시 B01.
관측 로그만 handoff에 남기고 패치하지 마.
```

### O2 B00만 하고 멈출 때
```
B00 NO_CHANGE_VERIFIED는 전제일 뿐 세션 Done이 아니다.
CONTINUE: B02 — WebSearchTool + 상류 소비자까지, 실패≠empty-success.
Repro 1줄 + focused RED 먼저. 새 검색 서비스 금지.
```

### O3 전체 suite / "영상 증상" / 읽기만 Done (G-P2/G-P3/G-P7)
```
OVERRIDE: 전체 검증·영상 SoT·goal MD 완독 ≠ Done.
증상은 첨부 B·로그·일반 chat/검색 OFF·ON 관측만.
focused 실패 테스트 GREEN 증거 없으면 PARTIAL.
```

### O4 ATT/CTX writer 충돌 시
```
B03/ChatWorkflow 공유 중이면 lease·handoff만 하고 B03 defer.
B02/B01이 공유 writer와 무면 그쪽 먼저 완료해. ATT 전체 재시작 금지.
```

### O5 새 Codex 세션 최초 킥 (3-A 원문)
```
부모 R1 + GAP 계약 DEMO1-AUTOGRADE-B-GAPS-20260928-R1 를 따라 live src만 패치해.
순서: B00 실측 → live remap → B02 focused RED→patch→GREEN → B01.
Browser admin 로그인/보호URL/잘못된계정/로그아웃 검증은 이번 제외(OVERRIDE).
전체 suite 금지. focused만. A* 제품화·RuleBreak 재등록·전역 HOLD·commit/push 금지.
MD/첨부만 읽고 Done 금지. 보고는 GAP 템플릿.
```

### O6 RuleBreak 재등록·전역 HOLD 시도 시 (즉시 중단)
```
STOP. RuleBreak 재등록·전역 HOLD 복원은 계약 위반. 변경분을 preimage로 되돌리고(rollback)
B00 실측 증거만 보고해. 되돌림은 Codex가 수행.
```

## 4. 판정표 (Codex 보고 → 데빈 판정)

| Codex 보고 | 판정 | 행동 |
|---|---|---|
| B00 present + B02 GREEN + cmd | 유도 성공(최소 Done) | K1(B01 유도) 또는 K2 |
| B00 only Done | 거부 | O2 |
| admin login 증명 | 범위 이탈 | O1 |
| 전체 tests passed만 | 증거 부족 | O3 + focused 커맨드 카드 |
| A03/A04 GREEN만 | 거부 | A*≠앱 → B02로 |
| RuleBreak 재등록·전역 HOLD | 계약 위반 | O6 |
| 패치 목록 + focused 진행 | 진행 중 | S# 갱신, 막히면 레일만 보강 |

## 5. 감시 절차 (다음 steer 세션용)

1. `Get-Content data/agent-handoff/codex-autonomy/autograde-b02-0928-74c07d0e/journal.json` — events 증가·종료 여부
2. `Get-ChildItem data/agent-handoff/codex-autonomy/autograde-b02-0928-74c07d0e -Directory` — `verify-green`/`verify-*` run.json exit/pass 확인
3. `Get-FileHash main/java/com/example/lms/api/ChatApiController.java -Algorithm SHA256` — preimage `0b0b81…`에서 바뀌면 패치 발생
4. stale 판정: journal `updatedAtUtc` 20분+ 무갱신 & run 산출 없음 → O5 또는 상황별 O문장 재킥
5. lease 충돌만 확인: `powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Json` (읽기만; foreign lease kill 금지)

## 6. 금지 (데빈 자기점검)

- `main/java/**`·제품 `chat.js` 직접 패치 금지
- Codex 패치 대신 작성 금지 — 레일·문장·판정만
- commit/push/비밀 출력/foreign lease kill 금지
- admin Browser·전체 suite·A* 제품화·MD만 Done → 즉시 OVERRIDE 주입
