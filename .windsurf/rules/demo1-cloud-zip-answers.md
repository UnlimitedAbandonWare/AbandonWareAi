---
trigger: model_decision
description: 클라우드 ZIP 스냅샷 답변(Codex 웹/GPT Pro/dot)은 가설 — intake 도구로 재확인 후에만 채택
---

# demo1 cloud ZIP answers

Codex 웹·GPT Pro·dot이 `demo1_*.zip` 스냅샷만 보고 쓴 답변(sandbox:/mnt/data
링크, "ZIP 기준", NOT_RUN 표기)은 **가설**이다. 그대로 `git apply`하거나
PASS 증거로 쓰지 않는다.

SSOT/절차: `.agents/skills/demo1-cloud-zip-answer-intake/SKILL.md`

```powershell
& $PY scripts\cloud_answer_intake.py run --answer <answer.txt> --bundle <bundle.zip> --brief <PASTE.txt>
```

판정: LIVE_OK / REANCHORED / REFUTED / UNVERIFIABLE + CLOUD_CLAIM(실행 주장≠
증거). SCOPE_WIDEN·LEASED_BY_OTHER·STALE_VS_BRIEF는 자동 채택 금지, 사용자
결정. 산출물은 `var/codex-assist-cloud-zip-intake/`에만.
