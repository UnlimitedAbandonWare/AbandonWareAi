# DEMO1-DELIVERY-DOWNLOADS — 역할과 산출물 종류별 저장 범위

현재 사용자 지시(2026-10-08)가 이전의 모든 산출물 Downloads 배달 문구보다 우선한다.
판정 기준은 **작업 역할 + 산출물 종류 + 사용자 전달 단계**다. 모델명, 파일명,
PASTE 접두사 또는 `[DOT-BRIEF]` 태그만으로 최종 지시서라고 판정하지 않는다.

## Downloads 허용 범위

- dot 역할이 사용자에게 전달하는 **최종 지시서**만 Downloads에 저장한다.
  dot은 모델명이 아니다. 실행 대상 Codex/Devin/agy 등도 저장 위치 판정 기준이 아니다.
- dot 전용 최종 전달은 `scripts/dot_brief_save.py save`의 기존 계약과 검증을 재사용한다.
- 일반 전달기를 쓸 때는 최종 역할/산출물을 확인한 뒤 명시 파일만 선택한다:
  `python -B scripts/deliver_to_downloads.py --file <final-file> --role dot --artifact-kind final-directive`
- 최종 전달의 실제 OS Downloads 경로는 `user.downloads`/사용자 지정 목적지로 확인하고
  기존 sha 일치 검증과 비덮어쓰기 계약을 유지한다. 모델명으로 경로를 선택하지 않는다.

## 프로젝트 내부 저장 범위

- Codex와 다른 에이전트의 리뷰, 검증 보고서, 패치 설명, 로그, 초안 및 중간 결과는
  Downloads에 복사하지 않는다. dot이 작성한 리뷰·초안도 같은 규칙을 따른다.
- 기존 `docs/reports`를 재사용한다. 새 사람용 보고서는
  `docs/reports/agent-reviews/<task-id>/REPORT.md`에, 관련 초안/로그는 같은 작업 폴더의
  `drafts/`/`logs/`에 둔다. 증거는 기존 task journal/checkpoint/verification 경로를
  유지하고 보고서에서 상대 링크로 연결한다. 중복 로그·상태 저장소를 만들지 않는다.
- 목록은 `docs/reports/agent-reviews/README.md`에 작업별 링크와 검증 상태만 기록한다.
  기존 보고 경로가 있는 작업은 그 경로를 유지하고 링크만 추가한다.
- nightly의 `outputRoot`와 `deliveryRoot`는 프로젝트 내부여야 한다.
  권장: `data/agent-handoff/codex-nightly-review/`와
  `docs/reports/agent-reviews/<task-id>/nightly/`처럼 분리된 경로. OFF는 유지한다.

## 자동 복사 및 기존 파일

`deliver_to_downloads.py --scan`과 `--hook-stop`은 no-copy 호환 진입점이다.
Stop hook은 `{}`만 응답하며 세션 transcript를 읽거나 자동 전송하지 않는다.
전역 스캔/자동 복사, 역할 미분류 파일 전달은 금지한다.
기존 Downloads 파일은 이 작업에서 이동·삭제·덮어쓰기하지 않는다.

## 긍정/부정 사례

| 역할 / 산출물 | 목적지 |
|---|---|
| dot / 사용자가 받을 최종 Codex 지시서 | Downloads, 명시 선택 및 readback 검증 |
| dot / 최종 지시서, 일반 파일명 | 동일 — 이름이 아닌 역할·종류 기준 |
| dot / 지시서 초안 또는 리뷰 | 프로젝트 보고 경로 |
| Codex·Devin·agy / 리뷰·로그·중간 결과 | 프로젝트 보고 경로 |
| 임의 모델 / `PASTE_CODEX_FINAL.md`, 역할·종류 미선언 | Downloads 전달 거부 |
| nightly / morning review·완료 receipt | 프로젝트 내부 |

검증: `python -B scripts/test_deliver_to_downloads.py` 및
`python -B scripts/test_codex_nightly_review.py` — 격리된 합성 fixture만 사용한다.
