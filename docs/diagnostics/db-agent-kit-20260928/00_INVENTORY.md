# DB 에이전트 키트 — Inventory (DEMO1-DB-AGENT-KIT-20260928-R1)

- 작성: 2026-09-28 · task `db-agent-kit-0928-6d5a065c` (agent devin)
- 상태: 사실(이 체크아웃에서 확인) vs 추정을 분리 표기.

## 1. Datasource 진실

| 항목 | 값 | 근거 |
|---|---|---|
| Start-RAG MetaDisplay 프로필 | `local,meta-display` | `scripts/start_rag_stack.ps1:328` |
| 파일 H2 URL | `${LMS_DB_URL:jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false}` | `main/resources/application-meta-display.yml:7` (사실) |
| 파일 위치 | `var/meta-display-db/lmsdb.mv.db` (+ `lmsdb.trace.db`) | 파일 시스템 확인 (사실) |
| `local` 단독 datasource | 이 디렉터리의 application*.yml에는 `spring.datasource` 정의 없음 → meta-display 프로필이 파일 URL의 유일한 출처 | grep (사실); local-only 부팅의 최종 datasource = **추정**(부팅 미시도) |
| 다른 프로필 | `desktop-gpu-node`, `learning`, `macmini-control-plane`, `verification` → 모두 `jdbc:h2:mem:*` (재시작 시 증발) | 각 yml (사실) |
| 잠금 모델 | embedded file 모드 = 배타적. Start-RAG JVM이 살아 있으면 제2 프로세스의 JDBC open은 `Database may be already in use [90020]` 실패 | live LockProbe 관측 (사실) |

## 2. `administrators` 스키마

엔티티 `main/java/com/example/lms/domain/Administrator.java` (사실):

- `id` BIGINT IDENTITY PK
- `username` VARCHAR(50) UNIQUE (`uk_admin_username`)
- `password` VARCHAR — Spring `BCryptPasswordEncoder` 해시 (`$2a$10$…`)
- `role` VARCHAR(20) — 폼 로그인 admin 레인은 `ROLE_ADMIN` (`hasRole("ADMIN")`)
- `name`, `rrn`, `phone`, `address`, `created_at` (`@CreationTimestamp`)

DDL은 Hibernate가 관리(마이그레이션 SQL 없음; `db/migration`은 `awx_*` 테이블만).
bootstrap은 `AdminService.createIfAbsent` — **기존 admin의 비번을 덮어쓰지 않음**
(그래서 MERGE 경로가 필요. `docs/diagnostics/local-admin-upsert-20260928/README.md`).

## 3. 기존 자산 (건드린 것/안 건드린 것)

| 자산 | 역할 | 비고 |
|---|---|---|
| `scripts/create-local-admin.ps1` | admin MERGE upsert + `-VerifyOnly` + `-WhatIf`. exit 0/2/3/4/5/6 | 이번 사이클에서 stderr-리다이렉트 함정만 harden(동작·코드표 동일) |
| `scripts/sql/upsert-local-admin.sql.template` | `MERGE … KEY(username)` 템플릿, 플레이스홀더 `__USERNAME__` 등 | 변경 없음 |
| `scripts/meta_display_db_export.py` | **read-only lanes**: `status`/`snapshot`/`export`/`query`/`tables`/`schema`/`tail` + `live`(HTTP `127.0.0.1:18180/api/internal/db/meta/*`, 토큰 env 또는 `var/dev-admin-token.txt`) | 서버 살아 있을 때 읽기는 여기로. live-JDBC 금지 정책 명시됨 |
| `scripts/db-agent.ps1` **(신규)** | 키트 단일 진입점: `LockProbe`/`VerifyAdmin`/`UpsertAdmin`/`Query`/`TableExists`/`Count` | UpsertAdmin은 create-local-admin.ps1로 위임(단일 구현 유지) |
| `scripts/db/h2-common.ps1` **(신규)** | dot-source 공유 헬퍼(jar 해석, SQL 리터럴, UTF8-noBOM, RunScript, lock probe, CSV select, 마스킹) | create-local-admin의 동일 로직을 복제 — 리팩토링으로 교체하지 않음(검증된 스크립트 자립성 유지) |
| `scripts/db-agent-kit-smoke.ps1` **(신규)** | %TEMP% throwaway H2로 전 exit 경로 자기검증(13 checks) | live 잠금 체크는 informational |
| `scripts/smoke_agent_mariadb_context.ps1` | `LMS_DB_URL/LMS_DB_USERNAME/LMS_DB_PASSWORD` env 계약 참조 | 다른 레인, 변경 없음 |

## 4. Lease/체크포인트 연동 경로 (사실)

- 소스/실행 파일 쓰기: `__patch_drop__/source_edit_session.ps1 -Action begin -TargetManifest <json>` (topic lease).
- 변경 전 보존: `scripts/codex_work_checkpoint.py begin/seal/finish` per cycle.
- 저널/핸드오프: `scripts/work_journal.py open|note|close|handoff`.
- Git: `conditional_local_git.py`는 이름에 `lmsdb`가 들어간 경로를 add에서 제외(사실, line 199) — DB 파일/백업은 커밋 불가 구조.
- DB 파일 자체는 checkpoint 대상이 아님: 쓰기 전 byte-copy 백업은 `meta_display_db_export.py snapshot`(unlocked일 때만) 또는 수동 Copy-Item → `data/` 밖, 절대 git에 넣지 말 것.

## 5. 함정 목록 (키트 반영 완료)

| # | 함정 | 증상 | 키트 반영 |
|---|---|---|---|
| P0-1 | 가짜 lock probe (OS open 성공=free라고 판단) | H2는 쓰기 공유 허용 → JVM 점유 중에도 open 성공 | `Test-AwxDbLock` = 실제 JDBC `SELECT 1` (라이브에서 exit 3 관측) |
| P0-2 | PowerShell `-replace`(regex)로 bcrypt `$` 치환 | `$` 그룹 참조로 해시 파괴 | 템플릿 치환은 `String.Replace` 리터럴 (기존 유지), 문서화 |
| P0-3 | UTF8 **BOM** 있는 .sql/.ps1 | RunScript/파서 오동작 | `Write-Utf8NoBom` 전용, 신규 파일 모두 no-BOM 확인 |
| P0-4 | `-Dfile.encoding` 등 -D 인자를 문자열 concat | PowerShell이 재분할해 java 인자 깨짐 | 항상 배열 스플랫 `@args` (`Invoke-AwxH2Script`) |
| P0-5 | **EAP=Stop + `2>` 리다이렉트** | 네이티브 stderr→ErrorRecord가 terminating → `exit 1`로 죽고 `exit 3`(locked) 불도달. **create-local-admin에 잠재 존재, 이번에 harden + live 검증** | `Invoke-AwxH2Script`에서 EAP를 `Continue`로 국소 완화; create-local-admin 4개 호출 지점 동일 패치 |
| P0-6 | secret-pattern 오탐/평문·해시 전문 유출 | checkpoint/secret scan 실패 또는 비밀 유출 | 결과는 masked JSON(`hashPrefix`만); bcrypt 전문·평문·password-대입 리터럴을 코드/문서에 심지 않음 |
| P0-7 | 서버 kill 후 unlock | 외부 트랙 서버 kill = 금지. 자기 트랙만 Close-RAG.bat → 쓰기 → Start-RAG.bat | LockProbe는 `livePorts` 힌트 반환; cheat-sheet에 stop→write→start 런북 |
| P0-8 | 셸/문서에 `$` 포함 값 붙여넣기 | bash/cmd에서 `$2` 등 변수 확장으로 해시 오염 (이번 smoke 픽스처에서 실재현됨) | 비밀은 env만; SQL fixture는 파일로 쓰기(인라인 `-c` 금지) |

## 6. Exit-code 계약

| 코드 | 의미 (kit) | create-local-admin.ps1의 대응값 |
|---|---|---|
| 0 | ok | 0 ok |
| 2 | bad args/input | 2 (stage=input 계열) |
| 3 | locked/busy (JVM 점유) | 3 |
| 4 | missing tools/jars/db file | 2 (stage=tools/template/db) + 4(bcrypt 실패) |
| 5 | verify-or-run failed | 5(runscript) + 6(verify) |

`db-agent.ps1 -Action UpsertAdmin`은 위임 결과 JSON을 그대로 출력하고 exit code만 위 표로 remap.
