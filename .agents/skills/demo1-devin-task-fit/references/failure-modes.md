# 실패 유형 요약

전체 표와 입력 범위: `var/codex-assist-devin-swe2-tune/devin_failure_modes.md`.

원장 20개 (`codex-autonomy/devin-*`, journal.json 있는 112개 중 최근, 기준 2026-10-03T06:30:00Z):

| 유형 | 횟수 | 도구 |
|---|---:|---|
| report·Acceptance·NOT_RUN 공백 | 19 | G7, G4 |
| 읽고 멈춤 | 2 | G4 |
| 다른 lease | 1 | G4. live lease는 해제하지 않는다 |
| 범위 확대, cwd, gitignore 쓰기, 가짜 통과, 명령 반복 | 각 0 | 문구가 이 20개에 없음 |

로그 4개(세션 20261002T230733, since 2026-10-01)에서는 홈 폴더 cwd와 `.gitignore` 읽기 거부가 따로 보인다. 그 횟수는 원장 횟수에 더하지 않는다. 홈 cwd는 G3 `demo1-cwd-powershell51.md`, gitignore 읽기 거부는 G3 `devin-io-pitfalls.md`.
