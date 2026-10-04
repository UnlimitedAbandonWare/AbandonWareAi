# 스티커 메모 2종 본문 백업

2026-10-04 KST. 원문 기준: 이번 작업의 `goal-objective.md` WP2에 제공된 사진 속 텍스트.
첨부 이미지 자체는 이번 세션에서 확인하지 않았다. 노란 메모의 전체 DB 본문은 제공된 화면 발췌보다 길다.
아래에는 지시서에 제공된 발췌를 그대로 보존하며, 실제 전체 메모·서식·창 상태는 사용자 로컬 SQLite 백업에 보존한다.

## 노란색 — 운영 규칙 / 가드레일

```text
빌드 설정 변경, Jev와 상관없는 앱 수정, env pull로 받은 파일 커밋.
- 401, 403, 429가 나면 재시도하지 마. 원인 분류(키 만료, 요금제 제한, ZDR 등)와 시도한 인증 경로를 맨 위에 보고하고, 키가 갱신될 때까지 live ON 배선은 미뤄. ZDR은 기본으로 꺼 둬.

9. 그 밖의 플러그인 (Sites, Data, Visualize, Supabase, Meta Wearables, Plugin Management)
- 작업에 직접 필요할 때만 읽기와 분석 위주로 써. 설정 변경이나 배포처럼 되돌릴 수 없는 동작은 하기 전에 한 번 물어봐.
```

## 보라색 — 스킬 태그

```text
@objective-executor @demo1-devin-source-orchestrator @demo1-vibe-max-agency @demo1-core-request-router @meta-rayban-display @demo1-meta-display-simple-caption @demo1-meta-display-resume @frontend-display-debug @demo1-conversate-hint-context @demo1-evidence-debugging @demo1-repairing-from-live-evidence @rag-search-diagnosis @search-zero-result-recovery @safe-source-edit @compile-verify-smoke @start-rag-reload @positive-negative-neutral-judge @SKILL.md
```

17개 스킬 태그와 추가 `@SKILL.md` 표기를 모두 유지했다. 메모에 기록된 문장은 보존 자료이며 별도 실행 권한을 부여하지 않는다.

## 실행과 복원

Windows PowerShell 5.1에서 실행:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\AbandonWare\demo-1\demo-1\src\scripts\ensure_sticky_notes.ps1"
```

헬퍼는 실행 중인 DB를 SQLite 온라인 백업으로 복사·검사한 뒤 시작프로그램 바로가기를 만들고, 앱이 꺼져 있으면 실행한다.
기존 `plum.sqlite.bak`은 덮어쓰지 않으며, 추가 실행에서는 UTC 시각이 붙은 `.bak` 파일을 만든다.
백업은 `%LOCALAPPDATA%\Packages\Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe\LocalState`에만 저장한다.
기존 Python 3가 필요하며 패키지는 설치하지 않는다. `-CheckOnly`는 앱·바로가기 상태만 읽는다.

로그인 때 `StickyNotes.lnk`가 앱을 실행한다. 개별 메모의 복원은 앱이 저장한 열림 상태에 따른다.
사진의 두 메모는 이번 작업 시작 시 각각 `IsOpen=1`이었다. X로 닫은 메모는 메모 목록에서 다시 열어야 한다.
헬퍼는 원본 DB의 본문·`IsOpen`을 수정하지 않으며, 개별 메모의 강제 복원이나 항상 위에 표시하는 기능은 제공하지 않는다.
본문 수동 복원은 새 메모를 만들고 위 텍스트를 붙여 넣는다. 전체 DB 복구는 앱 종료와 현재 DB 별도 백업을 먼저 하고 별도 작업으로 수행한다.

2026-10-04 확인한 공식 자료:

- [Microsoft: Sticky Notes 시작](https://support.microsoft.com/en-us/windows/apps/stickynotes/get-started-with-sticky-notes)
- [Microsoft: 사라진 메모 찾기](https://support.microsoft.com/en-us/windows/apps/stickynotes/where-did-my-sticky-notes-go)
- [SQLite: Online Backup API](https://www.sqlite.org/backup.html)

재로그인·재부팅과 실제 창 표시 여부는 각각의 관측 증거로 판단해야 하며, 바로가기 존재나 프로세스 실행만으로 보증하지 않는다.
