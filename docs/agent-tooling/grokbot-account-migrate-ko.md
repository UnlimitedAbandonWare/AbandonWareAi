# Grok Bot 계정 이전 안내 (2026-10-06, 묶음 grokbot-migrate-20261006.zip)

새 계정의 Grok Bot이 이전 계정의 규칙·메모리·스킬·도구를 이어받는 절차.

## 새 계정(봇) 쪽에서 할 일 3가지

1. 새 계정에 이 PC(DESKTOP-M5NOV6K)를 등록하거나, `grokbot-migrate-20261006.zip`을 채팅에 첨부한다.
2. 묶음 안 `PASTE_GROKBOT_migrate_20261006.txt`를 새 Grok Bot에게 그대로 붙여넣는다(zip 첨부면 PC 등록 없이 진행).
3. 새 봇 보고가 A1~A9 전부 PASS인지 확인한다(zip sha12 `28e0752cc37d` 일치 포함).

## 저장소 쪽(이미 반영됨 — 재가져오기가 필요할 때만)

- v2 묶음 사본: `var/grokbot-export-20261006/` (sha12 `28e0752cc37d`, Downloads 원본은 건드리지 않음)
- 재가져오기 한 줄:

```
python -B scripts/grokbot_bot_import.py --src var/grokbot-export-20261006 --apply --src-label "grokbot-migrate-20261006 (sha12 28e0752cc37d)" --head-note "R14의 cloudflared 터널 표현은 N7이 대체 - 실제는 로컬 Caddy(ZeroSSL), cloudflared 없음"
```

- 산출물: `docs/GROKBOT_BOT_RULES.md`(SSOT), `data/agent-handoff/grokbot/bot_episodes.jsonl`(★ 줄 append, sha12 dedupe), `.agents/rules/grokbot-bot-memory.md`(≤5줄 포인터)
- importer는 v1(`memory/RULES.md`)과 v2(`memory/MEMORY_PROFILE.md`)를 자동 감지한다.

## 주의

- R3에 박힌 옛 machineId는 옛 계정 값 — 새 봇은 `ListMachines`로 자기 id를 다시 확인한다.
- R14 "cloudflared 터널"은 옛 표현 — 실제는 로컬 Caddy(ZeroSSL)가 공개 HTTPS를 맡는다(N7).
- 묶음에 비밀값 없음. importer의 비밀 스캔은 값 모양 기준(오탐 시 보고).
