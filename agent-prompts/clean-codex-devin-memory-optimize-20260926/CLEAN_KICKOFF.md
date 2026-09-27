# Clean (Cline) — Codex 기억/세션 + Devin 최적화 지시서
날짜: 2026-09-26 KST
수신: Clean(Cline) → 실행 후 Devin 하이진 핸드오프
Project Root: `C:\AbandonWare\demo-1\demo-1\src`
Prototype Light / 저가드레일 + 검증 전달. 비밀값 출력 금지. push/`add -A` 금지.

## Self-Ask (짧게)
1. **요청:** quarantine-9only를 기준으로 Codex 기억·세션을 강하게 줄이고, Devin도 같이 가볍게.
2. **현재 증거(2026-09-26 검증):**
   - 이미 이동됨: `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919\` — `apply-9only.jsonl` 9건, class=`child-stale-no-evidence` (최대 ~136MB 1건 포함).
   - 살아 있는 Codex home `C:\Users\nninn\.codex`: **sessions ≈ 2772 files / ~9.52 GB** (다시 비대). `thread_history_1.sqlite` ≈ 2.79 GB, `logs_2.sqlite` ≈ 1.57 GB, `memories_1.sqlite` ≈ 4 MB.
   - `config.toml` 이미 `[memories] generate_memories=false`, `use_memories=false` (유지).
   - 도구 SSOT: `scripts/codex_home_quarantine.py` (candidates/preview/apply/status/restore, **이동만·삭제 없음**).
   - 라우팅 SSOT: `docs/ai-memory/AGENT_MEMORY_ROUTING.md`, 위생: `docs/ai-memory/SESSION_MEMORY_HYGIENE_AUDIT_20260919.md`.
   - Devin AppData: Cache≈487MB, CachedData≈256MB, logs≈229MB (프로세스 종료 후만 정리 후보).
3. **모호:** “기억 최적화” = (A) 디스크/세션 비대 격리 + (B) durable 사실을 **repo canonical**에만 남기기. **둘 다.** quarantine 롤아웃을 memories sqlite로 재주입하지 말 것.
4. **금지:** 비밀/auth 출력·커밋, live sqlite 직접 DELETE로 “청소”, `~/.codex/memories*.sqlite` / Devin cloud Knowledge를 repo에 dump, AbandonWare3 remote, fail-close admin, foreign staging 덮어쓰기.
5. **THE ONE seam:** 기존 `codex_home_quarantine.py`로 **새 candidates → preview → (승인 후) apply**. 9only는 **클래스·근거 템플릿**이지 복원 소스가 아님.

## THE ONE (Clean이 실행)
### A. Codex — 재격리 (우선)
```text
cd C:\AbandonWare\demo-1\demo-1\src
python scripts\codex_home_quarantine.py candidates --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
python scripts\codex_home_quarantine.py preview --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
python scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --dry-run
python scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --classes=child-stale-no-evidence
# 안전하면 한 클래스씩:
# --classes=automation-stale
# --classes=tmp-globalstate
# --classes=stale-db-copy
# --classes=viz-gradle-cache
python scripts\codex_home_quarantine.py status --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
```
- 스크립트 기본 RESCUE는 `codex-quarantine-20260919`이므로 **이번 런은 `--rescue=...\codex-quarantine-20260926` 고정**.
- **9only 폴더의 jsonl/rollout을 sessions로 다시 옮기지 말 것.** 근거·클래스명만 재사용.
- apply 전 Codex Desktop/CLI가 해당 rollout을 잡고 있지 않은지 확인. conflict면 status 보고.

### B. Codex — “기억” 품질 (디스크 ≠ 기억)
- `generate_memories` / `use_memories` **false 유지** (이미 꺼짐). 켜서 자동 재생성하지 말 것.
- durable 사실만 repo로: `docs/ai-memory/` + `data/agent-handoff/.../journal.json` + 필요 시 `AGENTS.md` 한 줄 포인터.
- **하지 말 것:** quarantine jsonl 전문 ingest, `thread_history`/`logs` sqlite 통째 복사, stage1_outputs 수동 DELETE(위생 문서에서 금지).
- 선택(읽기만): `scripts/codex_context_status.py` / `agent_session_watch`로 살아 있는 세션 vs stale 구분. 출력 비밀 마스킹.

### C. Devin — 병렬 최적화
1. **Skills SSOT:** `C:\AbandonWare\demo-1\demo-1\src\.agents\skills\` 유지. 설치 경로/AppData에 Skills 복제 금지.
2. **캐시(프로세스 종료 후):** AppData `devin\Cache`, `CachedData`, 오래된 `logs` — **이동 백업** 후 삭제 후보. credentials / mcp_config / Local State 손대지 말 것.
3. **공유 기억:** repo journals + peer-signal/bus (`agent-prompts/codex-devin-peer-signal-bridge-20260926`). cloud Knowledge dump 금지.
4. **Progressive memory:** `agent-prompts/progressive-memory-ssot-20260926` — 충돌 시 quarantine THE ONE 우선.

### D. Clean 자기 발자국 (보너스)
- `C:\Users\nninn\.cline` ≈ 10만 파일 / ~756MB. Codex THE ONE 후 실패/인사-only idle만 백업 후 정리 **제안**(in-app delete 없으면 제안만).

## Done when
1. 새 rescue 런 manifest + preview 숫자(클래스별 count/MiB).
2. 최소 `child-stale-no-evidence` apply 완료(또는 dry-run + 차단 사유). sessions GB 전후 비교.
3. memories 스위치 false 유지 확인.
4. Devin용 짧은 하이진 지시 1장(캐시 후보 / 금지 파일 / skills SSOT).
5. secrets 미출력. git은 conditional_local_git만.

## 참조
- `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919\apply-9only.jsonl`
- `scripts/codex_home_quarantine.py`
- `docs/codex-session-cleanup-execution-20260919.md`
- `docs/ai-memory/SESSION_MEMORY_HYGIENE_AUDIT_20260919.md`
- `docs/ai-memory/AGENT_MEMORY_ROUTING.md`