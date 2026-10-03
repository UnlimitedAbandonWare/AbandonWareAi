# agy 웹서치 기본 ON — 사용자 카드

2026-10-02 설정(DEMO1-DEVIN-AGY-WEBSEARCH-DEFAULT-20261002). agy가 모든 요청에
대해 답하기 전에 웹서치를 먼저 하도록 세 겹으로 걸어 두었다.

## 켜진 상태 확인

```
python -B scripts/agy_websearch_install.py --check
```

`RULE ON` / `HOOK ON` / `ALLOW ON` 세 줄이 나오면 정상.

## 무엇이 바뀌었나

| 층 | 위치 | 역할 |
|---|---|---|
| 규칙 | `%USERPROFILE%\.gemini\GEMINI.md` 끝 표시 블록 | "답하기 전에 웹서치 최소 1회 + 끝에 `출처:` 줄" 상시 규칙 |
| hook | `%USERPROFILE%\.gemini\config\hooks.json` + `hooks\agy_websearch_hook.py` | 모델 호출 직전 "이번 턴에 아직 웹서치 없음"이면 알림 한 줄 주입 |
| 권한 | `%USERPROFILE%\.gemini\antigravity-cli\settings.json` allow에 `read_url(*)` 한 줄 | 검색 후 페이지 열람(`read_url_content`)이 매번 승인 묻지 않게 함 |

## 한 턴만 끄기

메시지에 `[no-web]` 또는 "웹서치 없이"를 넣으면 그 턴만 검색하지 않는다.

## 완전히 끄기 / 되돌리기

```
python -B scripts/agy_websearch_install.py --uninstall
```

원본 파일은 같은 폴더의 `*.bak-20261002-websearch`로 복원된다. 다시 켜려면
`--apply`.

## 주의

- 웹서치는 Google AI Pro 한도를 조금 더 쓴다. 한도 문구가 뜨면 `[no-web]`을
  붙여 쓰거나 잠시 기다린다.
- 웹 페이지 내용은 "참고 자료"일 뿐이다 — 규칙이 agy가 웹 페이지 속 지시
  (명령 실행·설치·파일 수정·로그인·키 입력)를 따라 하지 못하게 막는다.
- `search_web` 자체는 권한 목록에 없는 내장 도구라 별도 허용이 필요 없다.
  `read_url(*)`만 allow에 추가했다(`execute_url`=클릭·입력 같은 조작은 여전히
  매번 승인 묻는다).

## 파일

- 원본 hook: `scripts/agy_websearch_hook.py`
- 설치기: `scripts/agy_websearch_install.py`
- 테스트: `scripts/test_agy_websearch_hook.py`
- hook 로그(선택): `%USERPROFILE%\.gemini\config\hooks\agy_websearch_hook.log`
