# AGENTS.md 크기 예산과 규칙 이사 안내 (한국어)

## 왜 잘렸나

각 에이전트는 프로젝트 규칙 파일(AGENTS.md)을 통째로 읽지 않고 정해진 바이트만
읽는다. 한도를 넘는 뒷부분은 **존재하지만 에이전트 눈에 안 보인다**.

| 에이전트 | 읽는 한도 | 근거 |
|---|---|---|
| Codex | 65,536 B (이 PC 설정 `project_doc_max_bytes`, 미지정 기본 32 KiB) | `%USERPROFILE%\.codex\config.toml`, `scripts/test_codex_instruction_governance.py` |
| agy | 앞의 ~24,000 B | GEMINI.md |
| Devin / Grok CLI | 확인 필요 | — |

2026-10-03 기준 AGENTS.md가 84,230 B였고, 그래서 362행 이후(65,536 B 컷)의
규칙 17개는 Codex에게, 148행 이후는 agy에게 보이지 않았다.

## 어떻게 고쳤나

- 규칙 본문은 한 글자도 버리지 않고 `docs/agents-rules/<BLOCK-ID>.md`로 옮겼다.
  각 문서 첫 줄에 원래 행 번호·이동 시각·sha256이 적혀 있다.
- AGENTS.md에는 스텁만 남긴다: BEGIN/END 마커 + 원래 제목 + "언제 읽어야
  하는지" 요약 한 줄 + `— 상세: docs/agents-rules/<ID>.md`.
- 맨 위에는 `DEMO1-CORE-AUTO` 블록(핵심 자동 규칙 요약)이 있어 어떤 예산으로
  잘려도 가장 중요한 규칙이 보인다.
- 결과: 84,230 B → 28,844 B. Codex·기본 32 KiB 예산 모두 잘리는 블록 0개.
  agy(24,000 B)도 핵심 블록과 대부분의 스텁을 본다.

## 다시 불어나지 않게 막는 법

새 규칙은 `docs/agents-rules/`에 쓰고 AGENTS.md에는 2줄 스텁만 둔다.
추가·수정 후에는 항상:

```
python -B scripts\agents_md_budget.py check
```

exit 0이어야 한다. 실패 조건: 전체 30,300 B 초과 / 핵심 블록이 24,000 B
뒤 / 필수 `##` 제목 소실 / BEGIN-END 짝 어긋남. `report`로 예산별 절단
위치를 본다. 되돌리기는 `restore --from <백업>`.

도구: `scripts/agents_md_budget.py` · 스킬: `.agents/skills/demo1-agents-md-budget/SKILL.md`

2026-10-04 Stack-Fit Gate: 기존 29,971 B 본문을 보존하고 승인된 ≤300 B 포인터를
추가하기 위해 전체 상한만 300 B 늘렸다. 24,000 B 핵심 제한과 32 KiB 읽기 예산은 유지한다.
