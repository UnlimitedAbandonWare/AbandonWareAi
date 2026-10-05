---
name: demo1-behavior-ratchet
description: Use when landed behavior/rules must not silently revert (원복/되돌리기/revert/회귀 잠금/래칫/behavior ratchet) — after a patch lands run `check` then `update`; a locked entry can only be released by a user-approved ADR + `unlock`.
---

# demo1-behavior-ratchet — one-way lock for landed behavior

한 번 들어간 동작·룰은 사용자 승인 ADR 없이 되돌릴 수 없다. 상세 SSOT:
`docs/agents-rules/DEMO1-BEHAVIOR-RATCHET.md` (상태 정의·불변식·ADR 절차).
검사기 `scripts/behavior_ratchet.py`, 항목 `configs/behavior-ratchet.json`,
잠금 `configs/behavior-ratchet.lock.json`. stdlib only, 읽기 `check`는 항상 안전.

## 습관 (작업 끝)

```powershell
python -B scripts/behavior_ratchet.py check                    # exit 0 정상 / 4 REVERTED / 1 설정 오류
python -B scripts/behavior_ratchet.py update --task <taskId>   # LANDED만 잠금; live lease 파일은 skip
```

- 세션 lease가 살아 있는 파일은 `skippedLeased`로 남겨 두고, lease 종료 뒤 다시 `update`.
- `check` exit 4(REVERTED) = 잠긴 동작이 사라짐 → 원복하거나 ADR(`docs/architecture/decisions/`에
  `status: ACCEPTED` + `approvedBy: user` + `ratchet: <id>`) 후 `unlock --id <id> --adr <file>`.
- 새 항목 추가는 자유. 잠긴 항목 삭제/패턴 완화 = 래칫 우회 금지(REVERTED로 잡힘).
- 잠금 대기 항목(이름 미확정)은 rule 문서의 "잠금 대기" 절에만 두고, 세션 보고/diff에서
  실제 이름을 확인한 뒤 config에 추가한다 — 지어내기 금지. (예: `verifier.failsoft-unknown-releases`
  — unknown/fail-soft 본문 유지·HOLD 금지, 근거 DEMO1-EVIDENCE-ZERO-RELEASE fail-soft 절)
