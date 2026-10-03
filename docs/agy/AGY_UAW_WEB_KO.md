# agy 웹서치 UAW 프로파일 — 사용자 카드

## 무엇이 바뀌었나

기존: agy가 웹서치를 "많이" 하는 쪽(전역 규칙 + PreInvocation hook + allow 한 줄).
추가: 검색 결과를 **고르고 압축해서 출처 있는 답**으로 조립하는 절차.

- 전역 `GEMINI.md`에 `AWX-UAW-WEB` 블록 1개(플레이트 선택 → 하위 질문 → 질의 확장
  → 권위 가중 합산 → 본문 확인 → Critic 재검색 → 인용 게이트 → 브레드크럼).
- 스킬 `awx-uaw-web-research`: 자세한 체크리스트·재검색 문장·좋은/나쁜 예.
- `scripts/agy_web_fuse.py`: 출처가 많을 때 점수·중복·게이트를 결정적으로 계산.
- Stop hook `awx-uaw-citation-gate`: 웹서치를 했는데 답 끝에 `출처:`/`웹:` 줄이
  없으면 **턴당 딱 한 번만** 다시 마무리하라고 되돌린다(무한 루프 없음).

## 답 끝의 `웹:` 한 줄 읽는 법

```
웹: W1_AUTH · 검색 4회 · 본문확인 2 · 출처 3(T1 2) · 모순 없음
```

- 플레이트: W1_AUTH 공식 / W2_FRESH 최신 / W3_TECH 기술 / W4_LOCAL 로컬 확인 /
  W9_LITE 단순 / WB_BRAVE 깊게.
- `STALE`가 출처에 붙으면 12개월 넘은 근거라는 뜻.
- `모순 있음`이면 출처끼리 다른 점을 본문에서 짚어줘야 한다.

## 더 깊게 시키는 법

"깊게" 또는 "교차검증"이라고 쓰면 `WB_BRAVE` 모드 — 검색 6~10회, 출처 3개 이상.
Google AI Pro 한도를 더 쓰므로 단순 질문에는 쓰지 않는다.

## 끄는 법

- 그 턴만: 메시지에 `[no-web]` 또는 "웹서치 없이".
- UAW 부분만 제거:
  `python -B scripts/agy_websearch_install.py --uninstall --profile uaw`
- 웹서치 기본 포함 전부 제거:
  `python -B scripts/agy_websearch_install.py --uninstall`

## 상태 확인

```
python -B scripts/agy_websearch_install.py --check
```

6층: `RULE`(전역 규칙) / `HOOK_PRE`(검색 리마인더) / `ALLOW`(read_url 허용) /
`UAW_RULE`(UAW 블록) / `HOOK_STOP`(인용 게이트) / `SKILL`(전역 스킬 사본).

## 권위 등급표 고치는 법

`references/agy-web/authority_tiers.json` — T1 공식 1.0 / T2 공식 GitHub·표준
0.8 / T3 주요 언론 0.6 / T4 블로그·기타 0.3. 도메인 추가 시 `notes`에 근거 한 줄.
투영 설계 근거는 `references/agy-web/UAW_WEB_PROJECTION.md`(P1~P13, UAW.txt 줄 인용).
