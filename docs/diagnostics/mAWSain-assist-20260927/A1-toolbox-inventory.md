# A1 — do01 조수: root doctor 근거 + toolbox 존재/능력표

목표: Codex가 `RepoScanTool`의 잘못된-CWD→fileCount=0 오진을 고칠 때 **실제 빌드 루트와
도구 실체**를 바로 쓰게 한다. 전부 live 트리 관측(사실), 추정은 별도 표기.

## 1. Project Root 존재표 (관측 2026-09-27, CWD=`demo-1\src`)

| 항목 | 존재 | 비고 |
|---|---|---|
| `pom.xml` | no | Maven 없음 |
| `mvnw` / `mvnw.cmd` | no | — |
| `build.gradle` | no | — |
| `build.gradle.kts` | **yes** | 활성 빌드 스크립트 (Spring Boot 3.3.4, Java 17) |
| `settings.gradle` | **yes** | **활성 settings** — Groovy가 이김. `rootProject.name='src111_merge15'`, `include(':app')` |
| `settings.gradle.kts` | yes | **평가되지 않는 hook sentinel**. 파일 자체 주석이 명시: "The active settings file is `settings.gradle` (Groovy wins when both exist)". `rootProject.name="lms-core"`는 비활성 값 |
| `gradlew.bat` / `gradlew` | **yes** | Gradle wrapper 정본 |
| `AGENTS.md` | yes | sha256 `a3a3dc1e…` (preflight) |
| `AGENTS.override.md` | no | — |
| `scripts/awx_mcp_toolbox.py` | **yes** | ZIP에 없었으나 live에 존재 — `[전체 저장소에서 확인]` 해소 |
| `main/java`, `main/resources` | yes | root main sourceSet (`build.gradle.kts` sourceSets) |
| `src/test/java` | yes | root test sourceSet |
| `app/src/main/java_clean` | yes | `:app` main sourceSet |
| `src/main/java` | no | Java plugin 기본 경로 — 이 루트의 활성 경로 아님 |

**주의(사실)**: `settings.gradle`과 `settings.gradle.kts`가 공존한다. "settings 파일이 있다"만으로는
불충분 — **어느 파일이 평가되는지**까지 보고해야 `rootKind` 판별이 맞다. Gradle은 동일 디렉터리에
둘 다 있으면 Groovy(`settings.gradle`)를 선택한다(`.kts` 파일 자체가 이를 문서화).

**사실 보정**: `RepoScanTool`이 스캔하는 경로(`main/java`, `main/resources`, `src/test/java`,
`app/src/main/java_clean`)는 **이 저장소의 활성 레이아웃과 일치한다**. 문제는 경로 목록이 아니라
① `Path.of(".")` CWD 의존(서버 CWD가 루트가 아니면 전부 `exists:false→fileCount:0`),
② `Files.walk`에 깊이/개수/시간 한도가 없음, ③ 부재와 조사불가를 구분하지 않음 — 세 가지다.
(`RepoScanTool.java:31–66`, live 확인)

## 2. toolbox CLI 실제 형식 (실행 확인)

```
python scripts/awx_mcp_toolbox.py <tool> [--input-json <json|->] [--runtime-session]
```

- `--input-json`: JSON payload. 생략/`-`이면 stdin. 숨김 옵션 `--input-base64` 있음 (`awx_mcp_toolbox.py:1216–1224`).
- `--runtime-session` 또는 tool ∈ {start,status,smoke,stop,doctor} → `RuntimeToolkit` 세션 모드;
  stdin JSON 라인으로 command 구동 (`:1225–1249`).
- 결과는 stdout 한 줄 JSON (`ok/toolName/inputHash/elapsedMs/decision/failReason` 등 finalize 필드 `:1327–1346`).

### dispatch handler 표 (main() `:1256–1288`, 31개)

`device_work, grok_review_change, kimi_review_change, codex_review_change, source_scan,
patch_plan, patch_render, archive_index_build, archive_search, archive_restore, boot_verify,
build_error_mine, run_pipeline, external_evidence_intake, external_evidence_audit,
producer_command_plan, producer_kit_export, desktop_dispatch_packet, desktop_control_loop,
smb_decommission_debug_probe, peer_evidence_bus, web_probe_refresh, harmony_scan,
agent_db_snapshot, trace_snapshot_probe, supabase_context_probe, supabase_schema_snapshot,
supabase_schema_snapshot_import, guard_status, session_evidence, schema`

- 별칭표 `TOOL_ALIASES` (`:172–213`): `trace.snapshot`→`trace_snapshot_probe`,
  `verify.boot`→`boot_verify`, `session.search`→`session_evidence` 등.
- runtime 전용 커맨드 {start,status,smoke,stop,doctor}는 manifest 미선언 — dispatcher 표와 별개 lane.

### capability 표 (providerSurface=control_tower, manifest `main/resources/mcp/awx-control-tower-tools.json`)

| toolId | declared | executableFound | callable | reason |
|---|---|---|---|---|
| device_work | yes | yes | yes | readOnly=false — side-effect 가능 분류 대상 |
| grok_review_change / kimi_review_change / codex_review_change | yes | yes | yes | readOnly=true이나 review 경로 — model 호출 여부는 handler 내부 사항(미검증) |
| source_scan | yes | yes | yes | `payload.root` 기준 메타데이터 전용 스캔 (`:1353`) |
| guard_status | yes | yes | yes | `agent.preflight` 별칭 |
| session_evidence | yes | yes | yes | readOnly=false — action=index가 derived index를 씀 (선언 주석 일치) |
| smb_decommission_debug_probe | yes | yes | yes | readOnly=true |
| peer_evidence_bus | yes | yes | yes | readOnly=true |
| web_probe_refresh | yes | yes | yes | readOnly=true이나 **remoteRead + sanitized artifact write** — readOnly≠무副作用 (do01 정책 표 참조) |
| harmony_scan | yes | yes | yes | readOnly=true |
| agent_db_snapshot | yes | yes | yes | readOnly=true |
| trace_snapshot_probe | yes | yes | yes | `trace.snapshot` 별칭 — Java AgentTool `trace.snapshot`과 **다른 표면** (A2 문서 §3) |
| supabase_context_probe / supabase_schema_snapshot / supabase_schema_snapshot_import | yes | yes | yes | readOnly=true — Supabase lane (이번 범위 밖 사용 자제) |
| patch_plan / patch_render | yes | yes | yes | readOnly=true |
| archive_index_build / archive_search | yes | yes | yes | readOnly=true |
| archive_restore | yes | yes | yes | readOnly=false — 파일 복원 쓰기 |
| boot_verify | yes | yes | yes | **명령 반환 도구 — 실행 검증이 아님** (선언문 일치) |
| build_error_mine | yes | yes | yes | 실패 로그 경로 전용 (PASTE §5) |
| run_pipeline | yes | yes | yes | 가용성 probe + 명령 반환 |
| external_evidence_intake | yes | yes | yes | readOnly=false — intake artifact write |
| external_evidence_audit | yes | yes | yes | readOnly=true |
| producer_command_plan | yes | yes | yes | readOnly=true |
| desktop_dispatch_packet | yes | yes | yes | readOnly=false — dispatch packet 발행 |
| producer_kit_export | yes | yes | yes | readOnly=false — kit 산출물 write |
| desktop_control_loop | yes | yes | yes | readOnly=false — control loop 구동 |
| schema | — (handler에만 존재) | yes | yes | manifest 미선언 handler |
| start/status/smoke/stop/doctor | — | yes | yes | RuntimeToolkit 전용, manifest 미선언 |

`declared`=manifest `tools[]`에 존재 · `executableFound`=`main()` handler 표에 존재 ·
`callable`=로컬 프로세스로 호출 가능. "callable"은 호출 가능성이지 성공/승인이 아니다 —
`web_probe_refresh`의 원격 fetch·`session_evidence`의 index write 같은 부작용은 action별로 따로 분류한다.

## 3. 기존 테스트 (존재 확인)

- `scripts/test_awx_mcp_toolbox.py`
- `scripts/test_awx_mcp_toolbox_input_json.py`

## 4. root doctor 초안 명세 (제안 — 파일 미생성)

`scripts/demo1_root_doctor.ps1`는 **존재하지 않는다**. 생성은 Codex가 source_scan 확장 또는
독립 스크립트로 결정. Devin 제안 명세 (메타데이터 전용):

- 출력: `rootKind` 판정 순서 — `settings.gradle` > `settings.gradle.kts` > `build.gradle(.kts)`
  > `pom.xml` > 없음; 동시 존재 시 평가 승자를 표기하고 나머지는 `shadowed`로 라벨.
- `sourceSets` 표: `build.gradle.kts:780–841`에서 확인한 실제 세트 —
  `main→main/java,main/resources` · `test→src/test/java,src/test/resources` ·
  `chatUiTest→src/chatUiTest/java` · `gatewaySecurityTest`/`crossSubsystemContractTest`
  (src/test/java 부분집합) · `glmAgentMcp(+Test)→src/glmAgentMcp( Test)/java` ·
  `:app`은 `app/build.gradle.kts`에서 별도 확인 필요(미관측).
- `limits`: depth 12 / 파일 20,000 / 5초 제안 시작값 (지시서 수치; 기존 더 엄격한 한도 우선).
- 각 sourceSet: `exists | fileCount | status(available|partial|unavailable) | activeStatus(discovered|active|inactive)`.
- 금지: 파일 내용 출력, `.git`/node_modules/build 산출물/archive/session DB walk,
  드라이브 루트 재귀, 값/시크릿 출력. 경로는 hash 또는 repo-relative 라벨로만.

## 5. evidence_needed

- `:app` 모듈의 sourceSet/테스트 세트 — `app/build.gradle.kts` 미열람 (do01 범위 밖, 필요 시 Codex가 확인).
- toolbox `grok_review_change` 등 review handler의 실제 모델 호출 여부 — 선언만으로 판정 불가.
