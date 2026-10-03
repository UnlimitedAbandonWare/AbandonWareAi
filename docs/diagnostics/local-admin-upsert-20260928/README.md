# 로컬 admin 계정 업서트 (Start-RAG 파일 H2)

- 작성: 2026-09-28 · task `local-admin-upsert-0928-59751a5b`
- 목적: `administrators` 테이블에 로컬 관리자 계정을 BCrypt 해시로 UPSERT해
  `/login` 폼 로그인 + `hasRole("ADMIN")` 보호 URL(`/admin/**`, `/model-settings/**`
  등)에 로컬에서 진입 가능하게 한다. PROTO_OPEN 범위 — harden/보호 URL 잠금 아님.

## 대상 DB

- Start-RAG 프로필 `local,meta-display` → 파일 H2:
  `jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false`
- `local`만이면 `jdbc:h2:mem:lmsdb`라 재시작 시 증발 → 반드시 파일 DB 기준.
- 테이블 `administrators`(엔티티 `Administrator`): `id` identity, `username`
  unique, `password`(BCrypt), `role`(=`ROLE_ADMIN`), `name`, `created_at` 등.

## 실행 (로컬 세션용 — 비밀번호는 인자/env로만)

```powershell
# 1) 서버가 켜져 있으면 JVM이 lmsdb.mv.db를 배타 잠금 → 먼저 정지
.\Close-RAG.bat

# 2) 비밀번호는 env로 주입 (셸 히스토리/로그에 평문 최소화)
Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<로컬-개발용-비밀번호>'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\create-local-admin.ps1 -Username admin
# 또는 인자: .\scripts\create-local-admin.ps1 -Username admin -Password '<로컬-개발용-비밀번호>'

# 3) 재기동 후 로그인 확인
.\Start-RAG.bat
```

- `-VerifyOnly`: read-back SELECT만 (잠금 없을 때만 가능 — 파일이 잠기면 H2도 못 연다).
- `-WhatIf`: MERGE 실행 없이 입력/도구 해석만.
- `-DbPath`: 다른 .mv.db 베이스 경로 지정 시.
- DB 계정 기본 `sa` / 빈 비밀번호 (`-DbUser`, `-DbPassword`로 변경 가능).

## bootstrap(`LMS_ADMIN_BOOTSTRAP_PASSWORD`)과의 차이

- `LmsApplication` CommandLineRunner → `AdminService.createIfAbsent("admin", …)`:
  **이미 admin이 있으면 비밀번호를 덮어쓰지 않음**(`EXISTING_ACCOUNT_CONFLICT`).
- 비번이 다른 기존 admin은 재기동만으로 안 고쳐짐 → 이 스크립트의
  `MERGE INTO ... KEY(username)`로 없으면 INSERT, 있으면 UPDATE.

## 검증 · 금지

- 성공 출력: JSON `{ok, mode:"merged", username, role, hashPrefix:"$2a$xx$"}`.
- 검증 SELECT는 `LEFT(password,7)` prefix만 읽는다. **해시 전문·평문을
  로그/핸드오프/Git에 넣지 않는다.** 이 README에도 실제 비밀번호를 고정하지 않는다.
- commit/push/`add -A` 금지. prod/외부 DB URL이면 STOP — local file H2만.
- `users` 테이블·주석 처리된 `AdminInitializer`는 건드리지 않는다.

## 관련 소스

- `scripts/db-agent.ps1` + `docs/diagnostics/db-agent-kit-20260928/` — 반복 사용 키트(LockProbe/VerifyAdmin/UpsertAdmin 위임/Query). 이 README는 최초 적용 기록; 일상 작업은 키트 진입점 사용.
- `main/java/com/example/lms/domain/Administrator.java` — 엔티티/컬럼
- `main/java/com/example/lms/service/AdminService.java` — `createIfAbsent`, `changePassword`
- `main/java/com/example/lms/config/AppSecurityConfig.java` — `BCryptPasswordEncoder`, `/login` 폼, `hasRole("ADMIN")` 목록
- `scripts/meta_display_db_export.py` — H2 jar 해석·lock probe·RunScript 패턴 원본
