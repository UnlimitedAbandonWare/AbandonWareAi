# FOR_CODEX — F01-B narrow JDBC UNDERSTANDING (evidence pack)

Contract: DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929
Devin scripts contract: DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929

## GATE-0 명령 (제품 코드 없이)

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/f01b_schema_gate.py --mode both --json-out data/diagnostics/f01b-trace-access-0929/f01b_schema_gate.json
python -B scripts/f01b_tm_probe.py --json-out data/diagnostics/f01b-trace-access-0929/f01b_tm_probe.json
python -B scripts/f01b_admission_key_demo.py --demo
```

## 판정 규칙

- GATE0 exit 2/3 → stay F01-A + evidence_needed; enqueue/enable 금지.
- GATE0 exit 0 → schema 준비만 확인됨. 제품 활성화는 Codex RED→GREEN 이후.
- tm_probe verdict 는 정적 힌트일 뿐 — GATE-1 증명은 런타임 failure-injection.
- `runtimeProofRequired: true` 를 PASS 증거로 읽지 말 것.

## NEVER

- InMemoryJobQueue F01-B store 금지 / task_ask 활성화 금지 / n8n 콜백 금지
- Autograde B 재오픈 / F02 scanner 재전투 / commit·push / secrets 출력
- DDL apply (dry-run/read-only 만)
- `abandonware.understanding.deferred.enabled` 플립 금지

## FILL (Codex)

- [ ] GATE-0 verdict: ____ (exit __)
- [ ] GATE-1 runtime proof: ____ (test filter ____)
- [ ] admission/effect/fingerprint 정합: ____
- [ ] decision.json 실측값으로 갱신
