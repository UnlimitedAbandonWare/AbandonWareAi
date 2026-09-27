# Devin 작업 카드 — Fold6 폴드 카메라 사진 힌트 (2026-09-24)

## 0. 위치·정체성
- Project Root: `C:\AbandonWare\demo-1\demo-1\src` (정본. worktree `…\.cline\worktrees\56ac9\src` 에는 `display-snapshot.js`·`NovaFocusState.java` 가 아예 없고 다른 파일도 해시가 다름)
- 브리프: `agent-prompts/devin-20260924-fold6-camera/brief.txt`
- 점검 기록: 같은 폴더 `setup_audit_20260924.md` (파일 SHA-256 · 리스 · 전달물 상태)
- 판정 문장: ON이면 확정 질문마다 폴드 후면 사진 1장이 실제 AI 입력으로 들어가고, 사진을 확보하면 그 작업의 촬영 트랙이 즉시 해제된다. OFF면 새 촬영과 새 이미지 AI 요청이 0건. 어느 경우에도 사진 작업 때문에 기존 폴드 수음이 끊기지 않는다.

## 1. 지금 살아있는 리스 — 빼앗지 말 것 (2026-09-24T08:39Z 실측)
| topic | 소유자 | 만료(UTC) | 처리 |
|---|---|---|---|
| `nova-oneshot-camera` | `devin-cli` | 09:22 | 촬영·포커스 17개 파일 = 이 작업과 거의 동일. 내 것이면 renew, 아니면 내 taskId로 새 `begin` |
| `chat-release-admin-fix-devin` | `devin-desktop` | 09:47 | `main/java/com/example/lms/service/chatworkflow.java`, `main/resources/static/js/chat.js` 포함 → 그 파일만 보류/request-release |
| `answer-hold-devin-dock` | `grok-…24627788` | 09:46 | `playbooks.json`, `.devin/prompts/*`, `scripts/devin_task_orchestrate.py` → 손대지 말 것 |
| `fold6-camera-hint-setup` | `cline` | 11:39 | 문서 4개(카드/브리프/운영카드/점검)만 잡음. 소스 아님 |

정책: live 리스는 강제 해제·삭제·steal 금지(plan `forbidden` 에 `steal-foreign-lease`). 겹치면 **파일 단위로 skip**하고 나머지를 진행한다.

## 2. 리스 · 저널 · 체크포인트 계약 (추측 금지, 실측)
- 매니페스트는 **JSON 파일 경로**이고 형태는 `{"targets":[{"path":"repo-relative","sha256":"<64-hex 또는 null>"}]}`. `sha256: null` = 아직 없는 새 파일. **배열만 넘기면 `target-scope-unproven`(exit 6).**
- `-Action` 값: `begin, end, status, verify, bind-scope, heartbeat, recover` (`help` 없음).
- begin 예:
  `powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action begin -Root . -Role desktop -Topic <topic> -OwnerId <agent-topic-taskid> -TaskId <taskId> -TargetManifest <manifest.json> -Json`
- status 무매니페스트 = 전역 인벤토리(전체 홀드 아님). 매니페스트를 주면 `targetConflict.conflictingPaths` 를 본다. exit 7 = blocking 리스 존재.
- 기록 preimage 가 현재 바이트와 다른 매니페스트는 `preimage-changed`(exit 6)로 거부된다 → 그 리스로 `apply` 하지 말고 새로 `begin`.
- 저널: `python -B scripts/work_journal.py open|note|close` (남의 `journal.json` 은 읽기만).
- 체크포인트: `python -B scripts/codex_work_checkpoint.py assess|begin|apply|seal|finish|restore|status`.

## 3. 증거 등급 — 없는 항목은 PASS 가 아니라 `NOT_RUN`
| 단계 | 요구 증거 |
|---|---|
| 코드 확인 | 변경 파일 목록 + diff (정본 경로) |
| 단위·JS | 명령 + exit code + 테스트 이름. OFF 경합 / 늦은 권한 승인 / 프레임 대기 중 stop / 중복 claim / 취소 후 재전송 0회 포함 |
| 정본 빌드 | `.\gradlew.bat :compileJava :processResources -x test` exit code |
| 라이브 반영 | `var/dev-reload/dev-reload.log` 의 `[DEV-RELOAD] socket ready` 또는 `var/rag-launcher/<ts>-*/result.json` (`status=ready`, `springReused=false`) |
| 실제 이미지 전달 | provider 직렬화 직전 image count=1 + MIME/bytes (사진 원문·base64 는 보고서에 넣지 않음) |
| 폴드 실기기 | 후면 렌즈 확인 + 촬영 후 소유 트랙 ended + 기존 수음 유지 관찰 |
| 안경 표시 | 기존 relay 경로 표시 확인(연결 ACK·publish ACK 만으로는 불가) |

## 4. 이번 브리프 금지 목록
새 카메라 시스템·새 업로드 서버·새 AI 프록시·`visionEnabled` 중복 플래그 · 전역 harmony ENFORCE · 인증/owner/epoch/예산 가드 완화 · 사진 base64 의 로그·히스토리·TraceStore·벡터DB·GraphDB·localStorage 저장 · 마이크/AudioContext/ASR 중단 · 유료 모델·무단 pull · `META_GLASSES` 저장값의 조용한 폴드 치환 · 안경 페이지에서 폴드 카메라 실행 · DAT/Android 앱 신설을 선행조건화.

## 5. 보고 형식
변경 파일 + diff / 실행 명령 + exit code / 테스트 이름·결과 / 보류한 리스 파일 목록 / `NOT_RUN` 목록 / 실기기 등급 분리.
컴파일 성공이나 업로드 200 을 "사진 인식 완료"로 쓰지 않는다.
