# AutoGrade B assist (Grok, 2026-09-28)

계약 `DEMO1-AUTOGRADE-B-ASSIST-GROK-20260928-R1`.
부모 `DEMO1-AUTOGRADE-B-TARGETS-20260928-R1`.
저널 `autograde-b-assist-20260928-073ce6ae`.

이 묶음은 실측 레일, 재매핑, B02 재현 카드, Codex 핸드오프다.
`main/java`, `main/resources` 제품 파일, `chat.js`는 수정하지 않았다.

| 파일 | 역할 |
|---|---|
| `b00_checklist.md` | P01/P02 등록 실측과 판정 한 줄 |
| `remap.md` | P01–P23 live SHA와 심볼 줄 |
| `b02_repro.md` | 검색 실패와 빈 성공을 가르는 재현·로그 분류 |
| `codex_handoff.md` | Codex에 넘기는 한 장 |
| `plugin_loadout.md` | 이번 assist에서 켠 도구 범위 |
| `scripts/autograde_b_rail.py` | 읽기 전용 재실행 |

재실행:

```
python -B scripts/autograde_b_rail.py --root .
python -B scripts/test_autograde_b_rail.py
```

스크립트 exit 0은 `NO_CHANGE_VERIFIED`다. exit 2는 `NEED_CODEX_MIN_PATCH`이며, 그 경우에도 이 스크립트는 등록 코드를 넣지 않는다.

## 이번 세션과 섞지 않음

maiaswsn 성능 F01–F08 첨부(`maiaswsn_performance_directive_v2_2026-09-28`, verification, evidence, `maiaswsn_source_evidence_2026-09-28`, vibe coding prompts)는 읽기 참고만이다. AutoGrade B 패치와 한 변경으로 섞지 않는다.

A00–A09(`aag/*`)는 제품 editable이 아니다. `REPORT (3).md`의 A03/A04 exit 0은 ZIP 단위 재현이며 앱 Done이 아니다.
