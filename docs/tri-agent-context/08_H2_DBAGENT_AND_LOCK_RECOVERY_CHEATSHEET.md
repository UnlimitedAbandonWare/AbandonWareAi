---
doc_id: TRI-CTX-08
title: H2 Database Embedded Lock and db_agent CLI Cheatsheet
created_at: "2026-10-05T21:40:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "scripts/db_agent.py 및 H2_DATABASE_LOCKING_SPEC.md 계약"
---

# 08. H2 단일 임베디드 점유 & db_agent.py 락 복구 치트시트 (Tri-Agent 공유)

> `var/meta-display-db/lmsdb` 파일 H2는 **단일 소유 embedded** 계약이다.
> JVM이 살아 있으면 파일은 잠겨 있고, 그것은 장애가 아니라 정상 상태다.
> 일반 웹 검색의 `AUTO_SERVER=TRUE` 처방은 이 리포지토리에서 **금지**다.

## 1. 라이브 DB URL 계약 (사실)

```text
jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false
```

- 근거: `main/resources/application-meta-display.yml:7`,
  `scripts/db_agent.py:5` (내부 호출 시 `;IFEXISTS=TRUE` 부가, db_agent.py:68).
- `MODE=MariaDB`는 H2 호환 모드이지 MariaDB 서버가 아니다
  (`smoke_agent_mariadb_*`는 별도 명시 레인).
- `local` 등 다른 프로필은 `jdbc:h2:mem:*` — 재시작 시 증발. 파일 DB와 혼동 금지.
- `AUTO_SERVER=TRUE`는 라이브 URL에 없다. 추가하면 단일 소유 embedded 계약을
  파괴하고 다른 프로세스가 같은 파일을 여는 길을 연다 — **SSOT 위반**
  (06 문서 §1, H2_DATABASE_LOCKING_SPEC.md).

## 2. 락 의미론 — exit 3은 정답이다

- 실행 중인 Start-RAG JVM이 `lmsdb.mv.db`를 배타 점유한다.
  db_agent의 락 판정은 **실제 JDBC 오픈**(`org.h2.tools.RunScript`,
  `SELECT 1 FROM DUAL`)이지 OS 파일 추측이 아니다 (db_agent.py:199-207).
- 잠김 감지 시 쓰기 명령은 `exit 3` + `{"reason":"locked"}`.
  **이것은 "서버가 DB를 쥐고 있다"는 정상 보고다 — 실패가 아니다.**
- 절대 금지: 서버를 죽여서 락을 푸는 행위, `lmsdb.mv.db`/`*.lock.db` 파일
  임의 삭제 (db_agent.py:38 계약).
- 읽기는 기본 `--via auto`로 live HTTP 레인(`/api/internal/db/meta/*`)에
  자동 위임된다 — 단, **기본 스토어가 잠겼을 때만**. 커스텀 `--db-path`가
  잠기면 다른 DB로 몰래 대답하지 않는다 (live_wanted, db_agent.py:327-332).

## 3. db_agent.py CLI (유일 진입점, scripts/db-agent.ps1은 동일 exit의 래퍼)

```powershell
python -B scripts/db_agent.py status                        # url(마스킹)/파일/락 — 실제 JDBC 프로브
python -B scripts/db_agent.py tables [--with-counts]        # INFORMATION_SCHEMA.TABLES
python -B scripts/db_agent.py schema [--table NAME]         # INFORMATION_SCHEMA.COLUMNS
python -B scripts/db_agent.py get-user --username NAME      # administrators 행 (username/role/hashPrefix만)
python -B scripts/db_agent.py verify-admin --username NAME  # 존재+bcrypt 접두 검사 (없으면 exit 5)
python -B scripts/db_agent.py query --sql "SELECT ..." [--max-rows N]   # 읽기전용 SELECT 게이트
python -B scripts/db_agent.py apply --file F (--dry-run|--i-mean-it) [--allow-tables a,b]
python -B scripts/db_agent.py upsert-admin --username N [--role ROLE_ADMIN] [--via auto|file|live-http] [--verify-only|--dry-run]
```

- `--via` 선택: `auto`(기본, 읽기는 잠김 시 live 자동 위임) / `file`
  (JDBC만) / `live` 또는 `live-http`(HTTP만, jar 불필요). 환경변수
  `AWX_DB_VIA`로도 지정 가능.
- 쓰기는 절대 조용히 폴백하지 않는다: `apply`는 `--dry-run` →
  `--i-mean-it`+`--allow-tables` 순서, `upsert-admin --via auto`는 잠김 시
  guarded live upsert 엔드포인트로만 간다 (db_agent.py:18-26).
- live 레인 토큰은 env/`var/dev-admin-token.txt`에서만 해석
  (`DOMAIN_ALLOWLIST_ADMIN_TOKEN`/`AWX_ADMIN_TOKEN`/`LLM_OWNER_TOKEN`) —
  argv 플래그로 토큰을 넘기지 않는다.

## 4. Exit code 표

| exit | 의미 | 대응 |
|---|---|---|
| 0 | 정상 | — |
| 2 | 인자/전제 불량 (db 파일 없음, 비파일 URL) | `--db-path` 또는 서버 1회 기동 |
| 3 | locked/busy — JVM이 점유 중 | **정상**. 읽기는 `--via auto` 유지, 쓰기는 Close-RAG 후 `--via file` 또는 guarded live upsert |
| 4 | 툴체인 없음 (java/h2 jar/ps1) | `META_DISPLAY_H2_JAR` 확인, JDK 17+ PATH |
| 5 | SQL 거부/검증 실패/행 없음 | 읽기 게이트 위반·deny 토큰·admin 미존재 점검 |

## 5. 락 복구 절차 (순서 고정)

1. `python -B scripts/db_agent.py status` → `locked` 필드와 dbFile 경로 확인.
2. 점유자 확인: `Get-NetTCPConnection -LocalPort 18180` →
   java PID = Start-RAG JVM이면 **락은 정상이다. 복구 대상이 아니다.**
3. 읽기 필요 → 그대로 `query/tables` 실행 (auto가 live 레인으로 우회).
4. 쓰기 필요 → 사용자 승인 하에 `Close-RAG.bat` 후 `--via file`, 또는
   `--via live-http` guarded upsert. 서버 kill로 락을 풀지 않는다.
5. JVM이 죽은 채 `lmsdb.lock.db` 잔여물만 남은 경우에만 잔여 파일 존재를
   기록하고 재시도 — 삭제 전에 점유 프로세스가 정말 없는지 `Get-Process java`
   로 재확인한다.

## 6. 시크릿·마스킹 계약

- env 이름만 다룬다: `LMS_DB_USER`, `LMS_DB_PASSWORD`, `LMS_LOCAL_ADMIN_PASSWORD`.
- 결과 셀은 `password|passwd|secret|token|credential|api_key|apikey|rrn`
  컬럼을 `7자 접두 + "...masked"`로 마스킹 (MASK_COLS_RE, db_agent.py:108-110).
- stdout은 한 줄 JSON (`--pretty`로 들여쓰기). 비밀값 평문 출력 경로 없음.

## 7. 안티패턴 방어표

| 안티패턴 | 결과 | 정답 |
|---|---|---|
| H2 URL에 `AUTO_SERVER=TRUE` 추가 | 단일 소유 embedded 계약 파괴 | 라이브 URL 그대로 유지 |
| exit 3을 "DB 고장"으로 보고 서버 kill | 서비스 중단·데이터 위험 | exit 3 = 점유 보고; live 레인/승인 경로 사용 |
| `*.lock.db`/`.mv.db` 임의 삭제 | 복구 불가 손상 가능 | 점유 프로세스 확인 후에만 잔여물 판정 |
| `--via live`로 커스텀 `--db-path` 읽기 | 다른 DB의 답을 받음 (exit 2 방어 있음) | 커스텀 경로는 `--via file` |
| JDBC 직접 연결 코드 신설 | 락/게이트 우회 | `db_agent.py` CLI만 사용 |

## 관련 문서

- [docs/DB_AGENT_CHEATSHEET.md](../DB_AGENT_CHEATSHEET.md) — db_agent 상세 치트시트 본편
- [docs/references/canonical-specs/H2_DATABASE_LOCKING_SPEC.md](../references/canonical-specs/H2_DATABASE_LOCKING_SPEC.md) — 락 계약 SSOT
- [docs/agents-rules/DEMO1-DB-AGENT-SSOT.md](../agents-rules/DEMO1-DB-AGENT-SSOT.md) — 에이전트 진입 규칙
- [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) — §H2 단일 접근 불변
- [README.md](README.md) — 카탈로그 인덱스와 TTL 정책
