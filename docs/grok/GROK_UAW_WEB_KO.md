# Grok CLI UAW 웹서치 사용 가이드 (GROK_UAW_WEB_KO)

Contract: PASTE_DEVIN_GROK_CLI_UAW_WEBSEARCH_20261002 · 2026-10-02

agy-cli에 구축된 UAW.txt 투영 웹서치 파이프라인 중 Grok CLI와 조화로운
부분만 이식한 결과물 정리. 무거운 ML(CrossEncoder/벡터스토어)은 제외 —
규칙·스킬·결정적 스크립트·Stop 훅만 사용한다.

## 배치 위치

| 대상 | 경로 | 역할 |
|---|---|---|
| 규칙 (전역) | `~/.grok/rules/uaw-web-research.md` | 모든 Grok 세션에 상시 로드되는 인용 절차 |
| 규칙 SSOT | `references/grok-web/GROK_UAW_WEB_RULE.md` | 레포 원본 (전역 파일은 이 사본) |
| 스킬 (전역) | `~/.grok/skills/awx-uaw-web-research/SKILL.md` | 상세 체크리스트·예시 (`.agents/skills/` 사본과 동일) |
| 융합 도구 | `scripts/awx_web_fuse.py` | agy_web_fuse.py 공용 래퍼 — stdin JSON → ranked/cite/gate/breadcrumb |
| Stop 훅 | `~/.grok/hooks/uaw-citation-gate.json` → `scripts/grok_websearch_hook.py` | 인용 누락 시 턴 1회 block |
| 인스톨러 | `scripts/grok_websearch_install.py` | `--apply` / `--check` / `--uninstall` |

## 동작 방식

1. **규칙**: 웹서치가 필요한 요청에서 플레이트(W1/W2/W3/W4/W9/WB)를 고르고,
   Self-Ask(하위 질문 분해) → QueryBurst(한·영·site: 조합) → `web_search` →
   상위 2~3개 `web_fetch` 본문 확인 → T1~T4 권위 가중 → `출처:`+`웹:` 출력.
2. **스킬**: `awx-uaw-web-research`가 자동 호출되거나 `/awx-uaw-web-research`로
   수동 호출 — P1~P13 체크리스트와 좋은/나쁜 예시 제공.
3. **Stop 훅**: 턴 종료 시 `lastAssistantMessage`를 검사 — 웹 근거 흔적(URL,
   출처 줄, web_search 언급)이 있는데 `출처:`/`웹:`가 없으면
   `{"decision":"block","reason":"[AWX-UAW] ..."}`을 stdout에 내서 Grok이
   같은 턴에서 한 번 더 돌게 한다. `stopHookActive==true`면 즉시 allow
   (턴당 최대 1회 — Grok 자체 상한 8회의 앞단 차단기). `reason != "end_turn"`
   (세션 종료 관찰성 발화)과 `subagentType` 있음(서브에이전트 턴)도 allow.
   모든 오류는 fail-open — Grok을 절대 막지 않는다.
4. **융합 도구**: 출처가 6개 이상이거나 WB_BRAVE일 때
   `python -B scripts/awx_web_fuse.py --top 5` — 결정적 재순위·게이트·
   브레드크럼 문자열을 돌려준다 (표준 라이브러리, 네트워크 0).

## 설치·점검

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/grok_websearch_install.py --apply    # 훅 등록 (기존 파일은 .bak-YYYYMMDD)
python -B scripts/grok_websearch_install.py --check    # ALL_OK 기대
python -B scripts/grok_websearch_install.py --uninstall # 해제 (.bak 있으면 복원)
```

Grok 세션 안에서는 `/hooks`(또는 Ctrl+L → Hooks 탭)에서 로드 확인·토글,
`r` 키로 리로드. `grok inspect`로 스킬/규칙 로드 상태 확인 가능.

## 한계 (정직 표기)

- Grok Stop 페이로드에는 transcript/tool 목록이 없어 "web_search 호출 여부"는
  `lastAssistantMessage`의 웹 근거 흔적(URL·출처 줄·도구명 언급)으로 근사한다.
  URL 없이 웹 내용을 인용만 한 답변은 게이트가 못 잡을 수 있다 — 규칙/스킬이
  상시 규범이고 훅은 최소 안전망.
- `[no-web]` / "웹서치 없이" 표시가 있으면 게이트를 건너뛴다.

## 검증

- `python -B scripts/test_grok_websearch_hook.py` — 9건 (allow/block/회로차단기/
  비웹/fail-soft/세션종료/출처만 있을 때)
- `python -B scripts/test_agy_web_fuse.py` — 융합기 12건
- `python -B scripts/grok_websearch_install.py --check` — 파일+기능 probe ALL_OK
