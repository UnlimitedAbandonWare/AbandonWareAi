# DB 에이전트 키트 — 1페이지 사용법 (복붙용)

대상: Start-RAG 파일 H2 `var/meta-display-db/lmsdb` (profile `local,meta-display`).
진입점: `scripts/db-agent.ps1 -Action …` — 출력은 항상 masked JSON 한 줄 + exit code.

```
exit 0 ok | 2 bad args | 3 db locked(=Start-RAG JVM 점유) | 4 tools/db file missing | 5 verify/run fail
```

## 시나리오별 복붙

**0) 지금 쓸 수 있나? (락 감지 — 서버 안 죽임)**
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent.ps1 -Action LockProbe
# exit 3 + livePorts.http_18180=true → Start-RAG JVM이 잡고 있음. 강제로 열지 마.
```

**1) admin 존재/역할/해시 prefix 확인 (verify)**
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent.ps1 -Action VerifyAdmin -Username admin
# exit 0 + hashPrefix "$2a$xx$" | 5 row-absent | 3 locked(서버 켜짐이면 여기서 멈춤)
```

**2) admin upsert (비밀번호는 env로만 — 인자·로그·git에 평문 금지)**
```powershell
Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<로컬-비밀번호>'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent.ps1 -Action UpsertAdmin -Username admin
Remove-Item Env:LMS_LOCAL_ADMIN_PASSWORD
```

**3) 읽기 전용 조회 (결과 마스크·행 cap)**
```powershell
.\scripts\db-agent.ps1 -Action TableExists -Table administrators
.\scripts\db-agent.ps1 -Action Count -Table administrators
.\scripts\db-agent.ps1 -Action Query -Table administrators -MaxRows 20
.\scripts\db-agent.ps1 -Action Query -Sql "SELECT username, role FROM administrators"
# SELECT/WITH/EXPLAIN/VALUES/SHOW 만 허용. password|rrn|token|secret 열은 prefix 마스크.
```

**4) 서버가 살아 있는데 읽기만 필요할 때 (JDBC 대신 live HTTP lane)**
```powershell
python -B scripts\meta_display_db_export.py live query --sql "SELECT username, role FROM administrators"
python -B scripts\meta_display_db_export.py status
```

**5) stop → write → start (쓰기가 꼭 필요하고 서버가 *내 트랙*일 때만)**
```powershell
.\Close-RAG.bat                                                    # wear 보호 존중, 외국 트랙 kill 금지
python -B scripts\meta_display_db_export.py snapshot               # 선택: unlocked 상태 byte-backup
Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<로컬-비밀번호>'
.\scripts\db-agent.ps1 -Action UpsertAdmin -Username admin          # 내부: MERGE → verify
.\scripts\db-agent.ps1 -Action VerifyAdmin -Username admin          # exit 0 확인
.\Start-RAG.bat                                                    # 떠나면 POST /login 302→/index smoke
```

**6) 크래시 뒤 잠금 해제(서버가 죽은 뒤 파일이 잠긴 것처럼 보일 때)**
```powershell
.\scripts\db-agent.ps1 -Action LockProbe                           # 정말 잠겼는지 먼저 확인
# 잠겨 있으면: 실제 java/Start-RAG 프로세스가 남았는지 확인 → 자기 트랙이면 Close-RAG.bat
# lmsdb.trace.db 는 H2의 진단 로그일 뿐 삭제로 잠금이 풀리지 않음. .mv.db 삭제 금지.
```

## 표준 작업 순서 (워크 레저 규약과 동일)

1. `work_journal.py open` + `source_edit_session.ps1 -Action begin`(scripts 타깃이면).
2. `LockProbe` → locked면 **문서화하고 멈춤**(다른 트랙 서버면 쓰기 재적용 금지).
3. (내 트랙) `Close-RAG.bat` → `UpsertAdmin` → `VerifyAdmin` → `Start-RAG.bat` → login smoke.
4. `codex_work_checkpoint.py seal` → 검증 → `finish` → `work_journal.py note`.

## 저널 문구 템플릿 (비밀 미포함)

```
AUTO db-agent-kit: <action> on <dbBase> -> exit=<n> (locked=<t/f>, row=<present/absent>);
env=<ENV_NAME만>; no plaintext/hash persisted; server=<running|stopped>
```

## 하지 말 것

- `open()` 가능 = free 추정, `.trace.db`/`.lock` 파일 삭제로 unlock, 외국 세션 프로세스 kill.
- 평문·bcrypt 전문을 로그/README/journal/git에 남기기 (`$2a$xx$` prefix까지만).
- mem↔file datasource 전환, `LMS_DB_URL`을 prod URL로 지정, commit/push.
- 제품 Java(ChatWorkflow/SecurityConfig 등) 변경 — 이 키트는 scripts+docs 범위.

## 검증

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent-kit-smoke.ps1
# %TEMP% throwaway DB로 13 checks; 마지막 live-probe는 0(서버 정지)/3(JVM 점유) 둘 다 PASS
```
