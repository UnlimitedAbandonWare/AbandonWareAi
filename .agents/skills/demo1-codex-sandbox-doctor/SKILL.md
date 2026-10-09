---
name: demo1-codex-sandbox-doctor
description: "dot·Codex PC 명령이 helper_unknown_error·setup refresh had errors·Access denied로 실행 전 실패할 때, 또는 Downloads 지시서를 dot이 못 읽을 때 Check → Heal 순서로 쓰는 샌드박스 수복 스킬."
---

# demo1-codex-sandbox-doctor

dot(ChatGPT 원격)/Codex 앱 PC 명령이 실행 전 `helper_unknown_error: setup refresh had errors`로
거절될 때의 진단·수복·감시 절차. 제품 소스와 무관 — 사용자 환경 복구 스킬.

## Symptom

- dot 재시도(`Write-Output 'PC_TOOL_READONLY_RETRY_OK'` 같은 단순 명령 포함)가
  `exec-server rejected request (-32603): helper_unknown_error`로 명령 생성 전 실패.
- `C:\Users\nninn\.codex\.sandbox\sandbox.<date>.log`에
  `runtime read/execute validation failed: ... open ACL target for root-only update:
  (파일 사용 중) (os error 32)` — 대상은 `runtimes\cua_node\<hash>\bin\` 아래
  node_repl.exe, VCRUNTIME140_1.dll 등 "실행 중인" 파일. `setup_error.json` =
  `{"code":"helper_unknown_error"}`.

## Cause (verified 2026-10-08, OpenAI.Codex 26.1002.7124.0, runtime hash 3dd31cfff853001c)

- `codex-windows-sandbox-setup.exe`가 매 명령 스폰 전 refresh하면서 런타임 실행 파일 전부를
  `MAXIMUM_ALLOWED`로 연다. MAXIMUM_ALLOWED는 FILE_WRITE_DATA를 포함해 해석되므로,
  실행 중 이미지/로드된 DLL은 os error 32(공유 위반)로 열기 실패 → refresh 전체 실패.
- 상속권한(RX) 유무·추가 허가 ACE와 무관 — 열기 자체가 실패. 재부팅·프로세스 종료·앱 재시작
  모두 무효(도우미는 앱 시작 시 다시 뜸: codex-computer-use-swift.exe = ChatGPT.exe 자식,
  node.exe = app-server code-review MCP, node_repl.exe = 세션 MCP).
- 상위 이슈: https://github.com/openai/codex/issues/51613 (동일 원인·동일 우회법 검증),
  #51607, #51725, #51736 (동일 런타임 해시 사례 다수).

## Fix order (deny ACE만으로 충분 — W3 반증·되돌림)

1. 백업: `icacls "…\runtimes\cua_node\<hash>\bin" /save var\codex-sandbox-doctor\acl-backup-<ts>.txt /T /C`
2. deny ACE(파일에만, 디렉터리 금지): bin 아래 `*.exe|*.dll|*.node|*.com|*.bat|*.cmd|*.ps1` 전부에
   `icacls <file> /deny "DESKTOP-M5NOV6K\nninn:(WD,AD)"` — MAXIMUM_ALLOWED가 write-data를
   빼고 해석되어 open 성공, WRITE_DAC(실제 ACL 갱신)은 여전히 허용.
3. 검증(샌드박스 시험): `codex.exe sandbox windows <cmd>` — setup이
   `errors=[]` + `setup binary completed`로 끝나면 PASS(스폰 단계의 별개 오류와 구분).
   앱 경로는 사용자가 dot에 재시도 1회.
4. W3(node_repl enabled=false)는 반증됨 — 2026-10-08 적용 후 refresh가 여전히 실패했고
   deny ACE만으로 해결돼 같은 날 백업 기준으로 되돌림(복원 완료). 잠금 주체가 node_repl
   외에도 있어(codex-computer-use-swift, node.exe) 효과가 없고 대가(브라우저·JS 자동화
   중단)만 남으므로 적용하지 말 것. node_repl이 실행 파일을 잠가도 deny ACE 덕에 무관.

## Watch / self-heal

`scripts\codex_sandbox_doctor.ps1 -Action Check|Heal|InstallSchedule|UninstallSchedule`
- Check: 최신 sandbox 로그의 마지막 refresh 결과 + 각 runtime bin의 deny 커버리지
  + 오류 종류 요약(knownBenign=hide users, knownFixed=os error 32, new=그 밖)
  + 로그 크기 + upstreamRecheck(앱 버전이 달라지면 upstream #51613 재확인 표시)
  → exit 0 OK / 2 DRIFT.
- Heal: 새 `<hash>` 폴더 등 deny 미적용 exec 파일에 백업 후 동일 deny 적용(-DryRun 지원).
- 등록: `AWX-CodexSandboxDoctor`(10분) + `/sc onlogon` 불가 시 Startup\AWX-CodexSandboxDoctor.cmd.
- 로그: `var\codex-sandbox-doctor\doctor.jsonl`. 테스트: `scripts\codex_sandbox_doctor_tests.ps1` (임시 가짜 로그·폴더).

## BriefRead (Downloads PASTE_* 읽기 허가, 2026-10-08 추가)

- 원인: Place-Brief가 `C:\Users\nninn`에서 Downloads로 Move할 때 소스 ACL(상속 끊김)을
  유지 → Downloads 폴더의 `CodexSandboxUsers:(OI)(CI)(RX)`가 PASTE 파일에 안 닿아 dot 읽기 denied.
- `-BriefRead -Check|-Heal|-Revoke` (Downloads `PASTE_*.txt|.md`만):
  Check = leakFolder(Downloads 폴더 자체의 그룹 ACE) + leakFile(비PASTE 파일의
  그룹 ACE — 상속 포함 어떤 형태든) + missingExplicit(PASTE가 상속만 받고 명시
  (R)이 없음) 집계(exit 2); Heal = 폴더 ACE 제거(백업 먼저) → 비PASTE 명시 ACE
  제거 → PASTE 빠진 파일에만 `(R)`; Revoke = 폴더·파일 양쪽 그룹 ACE 전부 제거.
- 금지: Downloads 폴더 전체에 상속 `(OI)(CI)` 권한을 부여하지 않는다 — 비PASTE
  파일 전부가 샌드박스에 읽힌다(2026-10-08 P0 누수, SOURCE=CODEX_APP_READ_ROOT:
  Codex 앱 read-acl이 프로필 폴더들에 동일 패턴으로 넣음). 폴더 ACE가 다시
  생기면 LEAK_FOLDER로 잡히므로 `-BriefRead -Heal` 한 번으로 닫는다.
- `-Action Heal -WithBrief` = 런타임 deny 복구 + BriefRead heal 한 번에 — 기존 10분
  예약·Startup .cmd가 이 조합으로 수렴(`InstallSchedule` 재실행 시 자동 부착).

## Rollback

- deny 제거: exec 파일들에 `icacls <file> /remove:d "DESKTOP-M5NOV6K\nninn"` 또는
  `icacls "…\bin" /restore var\codex-sandbox-doctor\acl-backup-<ts>.txt /T`
- config 복원: `var\codex-sandbox-doctor\config.toml.bak-<ts>`를 config.toml로 되복사.
  (2026-10-08 실시 — W3 되돌림 완료, 이후 config.toml은 원본 상태)
- 스케줄 제거: doctor `-Action UninstallSchedule` (task + -Logon + Startup .cmd 일괄).

## Cautions

- deny는 파일만: 디렉터리에 걸면 zip식 런타임 업데이트(삭제+생성)가 깨진다.
- OpenAI 실행 파일 교체·런타임 폴더 삭제·codex.exe/ChatGPT.exe/node.exe 강제 종료 금지 —
  runtimes 경로의 node_repl.exe/codex-computer-use*.exe 종료만 허용(효과는 일시적).
- 새 런타임 해시 폴더(앱 업데이트)에는 deny가 없어 재발 가능 → Heal/스케줄이 커버.
