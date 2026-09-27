# Desktop–Notebook Context Markdown Snapshot Design

## 상태와 결정

- 상태: 사용자가 설계안 1을 승인했으며, 구현은 이 문서의 검토 승인을 기다린다.
- 요청 분류: architectural, repository tooling and handoff artifact.
- Desktop은 정보 수집, MariaDB 읽기, Markdown 발행과 최종 검증을 소유한다.
- Notebook은 canonicalWorkspace=Y:\에서 발행된 텍스트를 읽기만 한다.
- 새 SMB 서버, 백그라운드 브로커, 공유 소스 편집 루프는 만들지 않는다.
- MariaDB 범위는 프로젝트 DB의 스키마·테이블·컬럼·인덱스·뷰·크기·행 추정치와
  allowlist된 서버 상태 및 기존 redacted Agent DB 요약이다.
- 애플리케이션 테이블의 원문 행, 채팅·사용자·세션 내용, BLOB/TEXT 값은 기본
  설계에서 내보내지 않는다.
- commit, push, 배포, Windows 예약 작업 설치, DB/ACL/자격증명 변경은 현재
  승인 범위 밖이다.

승인된 방향은 Desktop에서 명령 한 번으로 UTF-8 Markdown을 만들고
data/agent-handoff/desktop-context/latest.md에 안전하게 교체하는 요청형
snapshot이다. Notebook 사용자는 Y:\data\agent-handoff\desktop-context\latest.md를
메모장이나 Markdown 뷰어로 연다. SMB는 이미 존재하는 공유 루트의 전송 계층일
뿐이며, 이 기능이 새로운 네트워크 서비스를 열지 않는다.

## 현재 증거

- Desktop checkout은 C:\AbandonWare\demo-1\demo-1\src이며 현재 branch는
  codex/owned-runtime-browser-restart, HEAD는 0796a3c이다.
- 2026-08-19 preflight에서 .git/index.lock은 없고 source-edit lease의
  activeCount=0, corruptCount=0이다.
- worktree에는 1,599개의 기존 변경 항목이 있으므로 구현은 새 파일 위주로 하고
  사용자 변경을 정리하거나 덮어쓰지 않는다.
- 새 설계 문서와 계획된 snapshot 파일 경로에는 기존 tracked overlap이 없다.
- Process JAVA_HOME은 C:\jdk\jdk-17.0.13이고 Java 17이 실행 가능하다.
- MariaDB mysqld가 Desktop의 TCP 3306에서 수신 중이다.
- mariadb, mysql, mariadb-dump, mysqldump CLI와 MariaDB/MySQL ODBC 및 Python
  DB 드라이버는 현재 사용할 수 없다.
- root build.gradle.kts는 com.mysql:mysql-connector-j를 runtimeOnly로 이미
  선언한다. 새 production dependency는 필요하지 않다.
- application-desktop-gpu-node.yml은 DESKTOP_DB_URL,
  DESKTOP_DB_USERNAME, DESKTOP_DB_PASSWORD, DESKTOP_DB_DRIVER를 사용하고 각
  항목에 fallback을 갖는다. 값 자체는 조사 출력에 노출하지 않았다.
- 기존 agent_db_snapshot Control Tower 도구는 Desktop read-only 도구다.
  Spring runtime이 없을 때 source/file metadata fallback과 evidence_needed를
  반환하며, 현재 live DB snapshot은 unavailable이다.
- 기존 AgentDbContextProvider는 transaction read-only와 짧은 timeout으로
  translation memory, RAG ledger, strategy 성능을 redacted summary로 만든다.
- 현재 demo1-mcp-control-tower 스킬은 새 SMB service, shared-source edit loop,
  background broker를 명시적으로 금지한다.

## 목표

1. Desktop에서 한 명령으로 Java, Windows, 하드웨어, 로컬 서비스, repository,
   SMB 상태와 MariaDB 메타데이터를 수집한다.
2. 실제 MariaDB 연결 여부와 프로젝트 DB 구조를 텍스트로 확인할 수 있게 한다.
3. Notebook 사용자가 Y:\ 아래의 안정된 latest.md 한 파일을 메모장으로 열어
   최신 snapshot을 읽을 수 있게 한다.
4. DB 또는 일부 probe가 실패해도 나머지 Desktop 정보는 partial snapshot으로
   발행하고 정확한 evidence_needed reason을 남긴다.
5. 비밀번호, token, JDBC URL, 환경 전체, raw query body, 원문 DB 행과 예외
   본문을 snapshot에 기록하지 않는다.
6. 동일 디렉터리 temporary file과 atomic replace를 사용해 Notebook이 쓰는
   latest.md가 절반만 기록된 상태가 되지 않게 한다.
7. 생성 시각, freshness, 실행 결정, SHA-256과 검증 명령을 텍스트 안에 남긴다.

## 비목표

- MariaDB dump, backup, restore, migration, DDL 또는 DML 실행.
- SELECT * 형태의 application row export.
- 사용자·채팅·세션·prompt·response·credential 값을 SMB에 복제.
- Spring Boot 애플리케이션 자동 시작 또는 ChatMessageContentColumnAutoFix 실행.
- MariaDB client, ODBC driver, Python package 또는 새 Gradle dependency 설치.
- 항상 실행되는 watcher, daemon, HTTP endpoint, Windows Scheduled Task 설치.
- Notebook이 Desktop canonical root에 request file이나 source를 쓰게 하는 흐름.
- 모든 Windows process, service, environment variable, network interface를
  광범위하게 나열.
- 기존 Control Tower manifest와 awx_mcp_toolbox.py를 첫 구현에서 수정.

## 아키텍처

### 1. 요청형 PowerShell 진입점

scripts/desktop_notebook_context_snapshot.ps1이 사용자의 단일 진입점이다.
기본 실행은 다음과 같다.

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot.ps1
~~~

공개 parameter는 최소한으로 유지한다.

| Parameter | 기본값 | 의미 |
| --- | --- | --- |
| OutputDirectory | data/agent-handoff/desktop-context | canonical root 내부 발행 위치 |
| DatabaseMode | Metadata | Metadata 또는 Skip만 허용 |
| ProfileConfig | main/resources/application-desktop-gpu-node.yml | DB 설정 owner |
| DatabaseTimeoutSeconds | 5 | 연결·query 상한 |
| MaxMetadataRows | 10000 | DB metadata 출력 상한 |

password, username, JDBC URL을 command-line parameter로 받지 않는다. raw row
mode, arbitrary SQL, output UNC path, background mode parameter도 제공하지 않는다.

### 2. 수집·렌더링 모듈

scripts/modules/DesktopNotebookContextSnapshot.psm1은 외부 명령 실행,
redaction, Markdown 렌더링, secret scan, atomic publication을 소유한다.
진입점은 parameter validation과 모듈 호출만 담당한다.

모듈이 수집하는 Desktop-only 정보는 다음 allowlist로 제한한다.

- Java: JAVA_HOME, java.home, version, vendor, executable availability.
- Build: Gradle wrapper version과 root dependency/source-set 요약.
- Windows: OS caption/build, PowerShell version, CPU model/logical count,
  physical memory 합계.
- GPU: nvidia-smi가 제공하는 GPU name, driver, total/used memory와 probe reason.
- Storage: fixed drive letter별 total/free bytes. user path는 출력하지 않는다.
- Local runtime: 3306, 8080, 8081, 11434, 11435, 11436 listener의 port,
  loopback/public binding class, owner process name. command line은 출력하지 않는다.
- Ollama: 기존 ValidateOnly 결과 또는 tags metadata의 endpoint status,
  model count와 model identifier. generation request는 보내지 않는다.
- Repository: canonical root, branch, short HEAD, dirty count, index-lock boolean,
  source lease count, PatchDrop top-level/pending count, active source-set count.
- SMB: canonicalWorkspace=Y:\, backingShareIdentityVerified boolean과 reason만.
  UNC mapping, normalized share, identity hash 원문은 출력하지 않는다.

probe는 독립적으로 실행되며 각 section은 ok, unavailable, evidence_needed 중
하나의 상태를 갖는다.

### 3. MariaDB read-only helper

scripts/DesktopMariaDbMetadataSnapshot.java는 package가 없는 Java 17 source-file
program이다. Spring component나 active application source가 아니며 DB를 변경하는
API를 갖지 않는다.

PowerShell 모듈은 temporary Gradle init script로 root runtimeClasspath를
resolve하고 정확히 하나의 mysql-connector-j JAR 경로를 얻는다. 이 작업은
dependency resolution만 수행하고 compileJava, bootRun, test 또는 bootJar를
실행하지 않는다. temporary init script와 helper output은 finally에서 삭제한다.
build.gradle.kts와 settings files는 수정하지 않는다.

credential resolution 순서는 다음과 같다.

1. 현재 process의 DESKTOP_DB_URL, DESKTOP_DB_USERNAME,
   DESKTOP_DB_PASSWORD, DESKTOP_DB_DRIVER.
2. 정확히 지정된 ProfileConfig의 동일 placeholder fallback.
3. 값이 없거나 중첩·미해결 placeholder이면 DB section은
   db-credentials-unresolved로 종료한다.

resolved value는 PowerShell object의 private scope에만 두고 Java child process의
전용 environment AWX_SNAPSHOT_DB_URL, AWX_SNAPSHOT_DB_USERNAME,
AWX_SNAPSHOT_DB_PASSWORD, AWX_SNAPSHOT_DB_DRIVER로 전달한다. 현재 shell의
영구 environment, registry, 파일, command line, log 또는 report에는 쓰지 않는다.
finally에서 private reference와 child environment를 폐기한다.

Java helper는 다음 안전 경계를 갖는다.

- JDBC Connection.setReadOnly(true)를 설정한다.
- session transaction을 read only로 선언하고 끝에서 rollback/close한다.
- Statement query timeout과 max rows를 적용한다.
- system schema를 제외하고 configured current catalog만 조사한다.
- DatabaseMetaData와 fixed, parameterized information_schema query만 사용한다.
- arbitrary SQL, stored procedure, multi-statement와 application table value
  SELECT를 허용하지 않는다.
- INSERT, UPDATE, DELETE, MERGE, ALTER, CREATE, DROP, TRUNCATE, GRANT, REVOKE,
  LOAD DATA, INTO OUTFILE, CALL을 실행하는 code path를 포함하지 않는다.

DB 결과에는 다음만 포함한다.

- connection decision과 capturedAt.
- server version family, character set, collation, time zone, read-only flag.
- allowlist status: uptime, threads connected, questions, slow queries,
  max connections.
- current project catalog name.
- schema, base table, view, column, primary/unique/secondary index 이름과 구조.
- table engine, collation, estimated row count, data/index byte count.
- 전체 schema/table/view/column/index count와 metadata aggregate SHA-256.

raw row value, JDBC URL/host, DB username, password, grants 원문, error body,
server filesystem path는 결과에 포함하지 않는다.

### 4. 기존 Agent DB summary의 선택적 결합

PowerShell 모듈은 localhost 8080 또는 8081이 이미 listen 중일 때만 기존
agent_db_snapshot tool의 snapshot endpoint를 짧은 timeout으로 호출한다.
서버를 시작하지 않는다. 성공하면 기존 provider가 만든 redacted memory status
count, recent failure reason/hash, strategy aggregate와 subsystem persistence
summary를 별도 section에 넣는다.

runtime 또는 AWX_ADMIN_TOKEN이 없으면 local fallback의 count/hash summary만
사용하고 Spring runtime /agent/db-context live proof를 evidence_needed에 남긴다.
이 optional section의 실패는 direct MariaDB metadata 결과를 대신하거나
무효화하지 않는다.

### 5. 텍스트 발행 계약

tracked 안내 파일은 다음 두 개다.

~~~text
data/agent-handoff/desktop-context/README.md
data/agent-handoff/desktop-context/.gitignore
~~~

runtime 생성물은 git에서 제외한다.

~~~text
data/agent-handoff/desktop-context/latest.md
data/agent-handoff/desktop-context/latest.sha256.txt
~~~

latest.md의 heading은 고정한다.

~~~text
# Desktop Context Snapshot
## Snapshot Status
## Java and Build
## Windows and Hardware
## Local Runtime Services
## Repository and SMB
## MariaDB Metadata
## Agent DB Context
## Evidence Needed
## Integrity and Refresh
~~~

모든 파일은 UTF-8로 쓴다. report는 output directory 안의 random temporary
file에 먼저 쓰고 secret scan, size cap, required heading check, SHA-256 계산을
통과한 뒤 System.IO.File.Replace 또는 first-write File.Move로 latest.md를
교체한다. publish가 실패하면 이전 latest.md를 보존하고 temporary file을
finally에서 지운다. hash sidecar failure는 snapshot status를 partial로 남기고
성공을 주장하지 않는다.

README.md는 Notebook 사용법을 다음으로 고정한다.

~~~powershell
Get-Content -LiteralPath 'Y:\data\agent-handoff\desktop-context\latest.md' -Raw
Get-FileHash -Algorithm SHA256 -LiteralPath 'Y:\data\agent-handoff\desktop-context\latest.md'
~~~

snapshot의 generatedAt과 age guidance를 확인하고 30분보다 오래된 파일은
Desktop에서 진입점 명령을 다시 실행한다. Notebook이 refresh request 파일을
쓰거나 Desktop source를 수정하지 않는다.

## 데이터 흐름

1. Desktop 사용자가 repository root에서 snapshot 명령을 실행한다.
2. 진입점은 canonical root와 OutputDirectory가 data/agent-handoff의 strict
   descendant인지 확인한다.
3. 모듈은 Java, Windows, GPU, storage, listener, Ollama, Git, source-set,
   lease, PatchDrop probe를 bounded timeout으로 각각 실행한다.
4. DB mode가 Metadata이면 credential을 private scope에서 resolve한다.
5. temporary Gradle init script가 기존 mysql-connector-j runtime JAR만 찾는다.
6. Java helper가 read-only MariaDB session에서 fixed metadata query를 실행한다.
7. 이미 실행 중인 Spring runtime이 있으면 agent_db_snapshot을 optional로
   수집하고, 없으면 local fallback과 evidence_needed를 유지한다.
8. 모듈은 section별 observation과 inference를 구분해 Markdown을 렌더링한다.
9. secret/value scanner가 report 전체를 검사한다.
10. required heading, output size, DB decision, freshness, evidence_needed와
    SHA-256 contract를 검증한다.
11. 검증된 temporary report를 latest.md로 atomic replace하고 hash sidecar를
    갱신한다.
12. Notebook은 Y:\의 latest.md를 읽고 생성 시각과 hash를 확인한다.

## 결과와 완성 판정

snapshotDecision은 다음 셋 중 하나다.

| Decision | 의미 |
| --- | --- |
| complete | 필수 Desktop probe와 live MariaDB metadata가 성공하고 secret/integrity gate가 통과 |
| partial | 안전한 report는 발행됐지만 하나 이상의 optional 또는 DB evidence가 부족 |
| failed | report publication 또는 redaction/integrity가 실패하여 새 latest를 신뢰할 수 없음 |

host 정보만 존재하거나 3306 listener만 보이는 것은 MariaDB 접근 성공이 아니다.
goal 완료에는 helper의 live connection decision=connected, current catalog,
schema/table/column count와 같은 input-bound DB 증거가 필요하다. Notebook에
파일이 존재하는 것만으로 SMB 가시성이 증명되지 않으며, Notebook의 Y:\ read와
hash 일치가 최종 handoff 증거다.

## Redaction 계약

공개적으로 허용되는 exact value는 JAVA_HOME/java.home, Java/Gradle/OS/GPU
version, canonical Desktop repository path, canonicalWorkspace=Y:\, DB metadata
object name과 count다.

다음은 항상 금지한다.

- password, token, authorization header, cookie, client secret, owner token.
- JDBC URL, DB username, raw environment dump, process command line.
- raw UNC mapping, MAC/IP/interface list, Windows user profile path.
- application table value, chat/session/user/prompt/response text.
- full exception message, SQL text, stack trace, raw stdout/stderr.

오류는 bounded reason code와 exception type만 기록한다. scanner가 secret-shaped
value를 한 건이라도 찾으면 새 latest.md publication을 거부하고 이전 파일을
유지한다.

## 실패 계약

| 조건 | Classification | 동작 |
| --- | --- | --- |
| Java 17 없음 | java-runtime-unavailable | DB를 건너뛰고 partial report |
| Gradle wrapper 또는 connector resolve 실패 | mysql-connector-unavailable | DB evidence_needed, host section 계속 |
| DB setting 미해결 | db-credentials-unresolved | DB call 없음, partial report |
| TCP 3306 없음 | mariadb-listener-unavailable | DB call 없음, partial report |
| 인증 실패 | db-auth-failed | 값·error body 없이 partial report |
| query timeout | db-query-timeout | connection rollback/close, partial report |
| metadata 상한 초과 | db-metadata-limit-exceeded | 결과 폐기, partial report |
| forbidden SQL token/code path 검출 | db-readonly-contract-failed | helper 실행 거부 |
| Agent DB runtime 없음 | agent-db-runtime-unavailable | local fallback과 evidence_needed |
| secret pattern hit | redaction-failed | 새 latest publication 거부 |
| required heading/size/hash 실패 | snapshot-integrity-failed | 새 latest publication 거부 |
| latest 파일 교체 실패 | snapshot-publish-failed | 이전 latest 보존 |
| Notebook Y:\ read 미검증 | notebook-visibility-unproven | Desktop artifact는 유지하되 goal 완료 보류 |

DB failure는 lane-local이다. redaction 또는 publication failure가 아닌 한 다른
Desktop 정보의 안전한 partial report 발행은 계속한다.

## 계획된 파일 범위

추가:

~~~text
scripts/desktop_notebook_context_snapshot.ps1
scripts/modules/DesktopNotebookContextSnapshot.psm1
scripts/DesktopMariaDbMetadataSnapshot.java
scripts/desktop_notebook_context_snapshot_contract_tests.ps1
data/agent-handoff/desktop-context/README.md
data/agent-handoff/desktop-context/.gitignore
~~~

runtime 생성, git ignored:

~~~text
data/agent-handoff/desktop-context/latest.md
data/agent-handoff/desktop-context/latest.sha256.txt
~~~

첫 구현에서 수정하지 않음:

~~~text
main/java/**
main/resources/**
app/src/main/java_clean/**
app/src/main/resources/**
build.gradle.kts
settings.gradle*
scripts/awx_mcp_toolbox.py
main/resources/mcp/awx-control-tower-tools.json
.mcp.json
AGENTS.md
database/DDL/credential files
~~~

## 검증 계약

먼저 offline contract test를 실행한다.

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot_contract_tests.ps1
java .\scripts\DesktopMariaDbMetadataSnapshot.java --self-test
~~~

contract test는 다음을 증명한다.

- output root traversal, UNC output, alternate data stream, reparse traversal 거부.
- exact heading과 UTF-8 rendering.
- JAVA_HOME allowlist와 credential/JDBC/error body denylist.
- environment/default credential precedence를 값 노출 없이 처리.
- generated Gradle resolver가 runtimeClasspath의 connector 한 개만 선택.
- helper source에 DDL/DML/arbitrary SQL execution path가 없음.
- metadata row/byte/time limit과 read-only session setup.
- partial/failure classification과 이전 latest 보존.
- temporary file cleanup과 atomic replace.
- hash sidecar가 latest.md bytes와 일치.

그다음 현재 Desktop에서 live snapshot을 한 번 실행한다.

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_notebook_context_snapshot.ps1 -DatabaseMode Metadata
Get-Content -LiteralPath '.\data\agent-handoff\desktop-context\latest.md' -Raw
Get-FileHash -Algorithm SHA256 -LiteralPath '.\data\agent-handoff\desktop-context\latest.md'
~~~

live verification은 최소한 다음을 요구한다.

- JAVA_HOME=C:\jdk\jdk-17.0.13가 Java section에 존재.
- snapshotDecision=complete 또는 DB 외 optional lane만 부족한 partial.
- mariadbDecision=connected.
- current catalog가 존재하고 schema/table/column count가 0보다 큼.
- rawSecretPatternHits=0, rawDbRowStored=false, jdbcUrlStored=false.
- latest.sha256.txt가 report SHA-256과 일치.
- DB helper 종료 뒤 열린 transaction과 helper process가 남지 않음.
- 같은 read-only session에서 수집한 시작·종료 schema metadata aggregate
  SHA-256이 같고 readOnlySession=true이며, helper가 기록한 query ID가 설계의
  fixed metadata allowlist 안에만 존재.

마지막으로 Notebook에서 다음을 실행한다.

~~~powershell
$Snapshot = 'Y:\data\agent-handoff\desktop-context\latest.md'
Get-Content -LiteralPath $Snapshot -Raw
Get-FileHash -Algorithm SHA256 -LiteralPath $Snapshot
~~~

Notebook hash가 Desktop hash와 같고 generatedAt이 해당 실행과 일치해야 handoff가
검증된다. 이 외부 증거를 현재 Desktop에서 관찰할 수 없으면
evidence_needed: Notebook Y:\ snapshot read and SHA-256 match로 남기며 goal을
완료 처리하지 않는다.

## 구현 순서

1. PowerShell module의 path, redaction, rendering, atomic publication contract를
   실패 테스트로 추가한다.
2. 최소 module과 wrapper를 구현해 fixture 기반 host snapshot을 green으로 만든다.
3. Java helper의 self-test와 forbidden-operation static contract를 RED로 추가한다.
4. read-only JDBC metadata helper와 bounded JSON projection을 구현한다.
5. temporary Gradle runtimeClasspath resolver와 child-only credential 전달을
   구현하고 cleanup test를 통과한다.
6. current MariaDB 3306에 대해 live metadata snapshot을 실행하고 DB 접근을
   증명한다.
7. optional agent_db_snapshot 결합과 evidence_needed 분류를 구현한다.
8. README, .gitignore, latest.md atomic publication과 hash sidecar를 검증한다.
9. Desktop secret scan, process/transaction cleanup, read-only session flag,
   fixed query-ID allowlist와 시작·종료 schema metadata hash를 기록한다.
10. Notebook Y:\ read/hash command으로 최종 SMB visibility를 검증한다.

## Rollback

- 새 wrapper, module, Java helper, contract test, README와 local .gitignore만
  제거한다.
- data/agent-handoff/desktop-context의 ignored runtime snapshot을 삭제한다.
- 기존 Control Tower, Gradle, application source/resource와 MariaDB는 변경하지
  않았으므로 되돌릴 source 또는 DB migration은 없다.
- rollback 전후에 기존 tracked 파일 diff와 MariaDB schema metadata hash가
  동일함을 확인한다.
- commit, push, deployment가 없으므로 Git history rollback은 필요하지 않다.
