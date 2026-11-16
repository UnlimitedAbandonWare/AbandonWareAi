# 검수 완료 변경의 GitHub 게시와 복구

대상은 공개 저장소 `UnlimitedAbandonWare/AbandonWareAi`의 `main`이다. GitHub 반영에는 Codex 앱의 GitHub 커넥터를 사용한다.

## 후보 준비와 게시 역할

| 역할 | 작업 |
| --- | --- |
| Devin | 겹치지 않는 파일 범위에서 재현 명령과 근거를 반환한다. |
| SOL 6.1 | 공개 범위·의존성 검수와 이력·복구 검수를 분리한다. |
| Git 개선·검증·자동 커밋 관리 | 2시간마다 진행할 작업이 있는지 확인한다. 검증한 담당 파일로만 게시 후보를 준비한다. |
| GitHub 검수 완료 변경 게시 | 월·수·금 10시 54분 KST에 검수 완료 후보를 `main`에 반영한다. 정상 가동 시 예약 간격은 최대 72시간이다. |

1. GitHub에서 현재 `main`의 SHA와 트리를 기록한다. 승인된 공개 파일의 정확한 바이트만 별도 저장소에 복제한다. 아래 가드로 선택한 후보 파일과 중간 커밋을 검사한다. 깨끗한 후보 파일과 검증 영수증을 묶는다. 범위 판단은 [공개 인벤토리](2026-11-16-github-publication-inventory.md)를 따른다.
2. GitHub에서 현재 `main`이 유일한 부모인 단일 커밋을 만든다. 파일 경로·종류·원격 바이트를 검증한 뒤에 `publish/approved/<기준-SHA>/<후보-SHA>` 브랜치를 만든다. 두 SHA는 각각 40자리다. 세션 원문, 키, 바이너리, 권한·워크플로 파일, 삭제와 이름 변경은 이 자동 대기열에서 제외한다.
3. 게시 작업은 현재 `main`에 해당하는 승인 브랜치가 한 개일 때만 진행한다. 브랜치가 가리키는 SHA, 실제 부모 목록, 변경 목록을 다시 확인한다. 커넥터의 참조 갱신에 기존 `main` SHA를 `expected_sha`로 전달하고 `force=false`를 사용한다. 갱신 뒤 GitHub에서 `main`을 다시 읽어 후보와 일치하는지 확인한다.
4. 처리할 후보가 없으면 `NO_APPROVED_CANDIDATE`와 확인한 `main` SHA를 기록한다. 후보가 여럿이거나 그 사이 기준점이 바뀌면 새 기준으로 독립 검수한다. 응답이 불확실하면 원격 SHA부터 읽고, 권한 거절에는 쓰기를 반복하지 않는다.

```bash
git diff --name-status --no-renames <reviewed-base-sha> <candidate-sha>
python -B scripts/git_publish_review.py --root . --candidate <candidate-sha> --base <reviewed-base-sha> --json
```

위 명령은 기준과 후보 객체가 있는 검토 저장소에서 실행한다. 격리 저장소에서 선택된 파일만 검사했으면 검사 결과는 그 파일 집합에만 적용된다.

## 실행 확인과 복구

Codex 앱의 예약 작업에서 설정과 실행 결과를 확인한다. 로컬 예약 작업이 시작되려면 컴퓨터와 앱이 실행 중이어야 한다. 자동 게시의 증거는 실행의 결과와 GitHub에서 다시 읽은 게시 SHA다. [OpenAI 예약 작업 문서](https://learn.chatgpt.com/docs/automations?surface=app)

복구할 때는 게시 커밋의 첫 부모 SHA를 기준으로 필요한 파일을 읽고, 복구한 파일을 새 커밋으로 반영한다. GitHub에서 기준 SHA와 대상 파일의 blob ID를 대조한다. [포트폴리오 복구 안내](../../portfolio/RECOVERY.md)
