# A5 — 있는 스크립트와 체크리스트 스케치

`scripts/`와 BAT는 이미 있다. 이번 세션은 help 실행과 제품 코드를 돌리지 않았다. 존재만 확인했다. 새 `scripts/att_ctx_handoff_checklist.ps1`은 만들지 않았다.

## inventory

| 경로 | 존재 | 이 작업에서의 용도 |
|---|---|---|
| `scripts/chat_session_debug_export.py` | 있음 | `status` / `list` / `show` / `export`. 트레이스에 프롬프트 본문과 비밀이 없게 읽는 도구. ATT 답변 본문 확인용이 아니다 |
| `Verify-RAG.bat` | 있음 | `debug_rag_stack.ps1 -Role dev -Action verify -WithCompile`. Codex가 제품 패치 뒤에만. 이번 조수 Done의 증거가 아니다 |
| `Read-RAG-Debug.bat` | 있음 | 기동 실패 분류를 읽을 때. 서버를 시작하지 않는다 |
| `scripts/debug_rag_stack.ps1` | 있음 | Verify-RAG가 호출하는 본체 |
| `scripts/conditional_local_git.py` | 있음 | 읽기 `status`와 스테이지 이름 확인. commit은 이번 범위 밖 |
| `scripts/run_verified_command.py` | 있음 | Codex가 focused test를 기록할 때. 미확인 run은 통과가 아니다 |
| `scripts/work_journal.py` | 있음 | 이 패킷의 저널 |
| `scripts/codex_work_checkpoint.py` | 있음 | 이 패킷의 cycle-01 |
| `scripts/status_doc.py` | 있음 | 저널 close가 PROJECT_STATUS §4 한 줄을 붙일 때 |
| `scripts/agent_preflight.py` | 있음 | 진입 시 루트·저널·lease |

## 체크리스트 스케치

아래는 제안 스크립트의 동작이다. 파일로 저장하지 않았다. 출력은 상태·HEAD·외래 스테이지 이름·대상 파일의 lease 여부뿐이다. 비밀번호, 키, 쿠키, 환경값, 프롬프트 원문은 출력하지 않는다.

```powershell
# NOT CREATED. Sketch of scripts/att_ctx_handoff_checklist.ps1
# Reads only. Does not commit, push, unstage, or kill git.exe.
$root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location $root
Write-Output ('HEAD=' + (git rev-parse HEAD))
Write-Output ('BRANCH=' + (git rev-parse --abbrev-ref HEAD))
Write-Output 'STAGED='
git diff --cached --name-only
Write-Output 'LEASE_STATUS=see source_edit_session.ps1 -Action status -Json'
# Codex fills the target manifest before a product edit.
# Exit 0 means the commands ran. It does not mean ATT or CTX passed.
```

Codex가 이 스케치를 파일로 만들기 전에, 스테이지 목록에 자기 소유가 아닌 경로가 있으면 그 경로는 그대로 둔다. 이번 읽기에서 스테이지된 경로는 `src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java` 하나다.

## fixture 생성기 스케치

역시 파일로 만들지 않았다. 작은 TXT, MD, JSON만 만들고 ZIP 본체는 테스트 메모리에서 만든다.

```text
NOT CREATED. Sketch only.
write t01-a.txt and t01-b.txt with the same character length and different code points
write t08-url-in-body.md whose first line is a public https URL and whose body is one Korean sentence
write t17-report-a.md and t17-report-b.md with different document role labels
do not write secrets, cookies, raw prompts, or a multi-thousand-entry zip
print only byte lengths and sha256 of the fixture files
```

생성기를 실행하지 않았다. fixture 파일도 없다.
