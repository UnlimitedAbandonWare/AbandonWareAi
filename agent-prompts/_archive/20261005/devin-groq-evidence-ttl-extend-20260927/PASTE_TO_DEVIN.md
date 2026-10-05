# Devin 붙여넣기 — Groq 계정 증빙 TTL을 수개월로 (2026-09-27)

너는 Devin이다. Codex API-01이 “증빙 24h 만료”로 막힌 상태를 **룰/가드 TTL만** 고친다. API 공급자 교체·원격 선택 일괄 ON은 범위 밖.

## Why (증거)
- 코드: `main/java/com/example/lms/agent/GroqFreeTierGuard.java`
  - `private static final long DAY=86_400_000L`
  - `proof()`: `now-at>DAY` 또는 `expiresAtMs < now` 이면 `groq_free_account_evidence_needed`
- 설정: `main/resources/application-meta-display.yml` `groq.free-tier.evidence` → `data/usage/groq-free-plan.json` (max-age 프로퍼티 **없음**)
- 문서 모순: `docs/provider-limits/groq-limits.md` 는 `reviewAfterDays: 90` + “런타임은 24h TTL” 명시. `AGENTS.md`도 “24-hour account evidence check” 언급.
- LIVE proof (값 출력 금지, 메타만):
  - mtime ~2026-09-24 14:12 KST
  - age(verifiedAtMs) ≈ **68h** → 24h 규칙 fail
  - `expiresAtMs` 가 verified+24h로 박혀 있어 **expires도 fail**
  - `plan=free`, speechVerified 등은 유지 대상

사용자 의도: API 키가 하루 만에 죽는 게 아니다. **증빙 유효기간을 몇 개월(기본 90일)로 늘려라.**

## Goal
1. Evidence freshness window = **configurable**, default **90 days**.
2. Docs/AGENTS가 24h를 강제하는 문구를 고쳐 정책과 런타임을 맞춘다.
3. keyHash / plan=free / limits / ledgerPath 검사는 **유지** (가드 해제 금지).

## Do

### 1) Code — `GroqFreeTierGuard.java`
- Replace hard `now-at>DAY` with:
  `long maxAge = env.getProperty("groq.free-tier.evidence-max-age-ms", Long.class, 90L*86_400_000L);`
  (clamp: e.g. min 1 day, max 365 days — document chosen bounds)
- Keep `expiresAtMs < now` OR redefine: if `expiresAtMs` missing/0, fall back to verifiedAt+maxAge only. Prefer: **both** freshness and expires must pass, but when regenerating evidence set expiresAtMs = verifiedAtMs + maxAge.
- Do **not** use the rate-limit `DAY` constant for evidence TTL if it also serves rpm windows — keep DAY for rate windows; add `EVIDENCE_MAX_AGE_DEFAULT` separately.

### 2) Config
`application-meta-display.yml` under `groq.free-tier:`:
```yaml
# Account evidence freshness (NOT API key expiry). Default 90d aligns with docs reviewAfterDays.
evidence-max-age-ms: ${GROQ_FREE_EVIDENCE_MAX_AGE_MS:7776000000}
```
Optional `.env.example` key `GROQ_FREE_EVIDENCE_MAX_AGE_MS` with comment only (no secrets).

### 3) Docs / rules
- `docs/provider-limits/groq-limits.md`: runtime TTL = 90 days (configurable); remove “must refresh every 24h” as admission law.
- `AGENTS.md` line about “24-hour account evidence check” → “configurable evidence max-age (default 90 days); not the same as provider API key expiry”.
- Skill/rule files that hardcode 24h for Groq free evidence: same fix if found (`rg "24-hour account evidence|now-at>DAY|evidence.*24"`).

### 4) Tests — `GroqFreeTierGuardTest.java`
- Cases that do `now+=86400001` expecting deny: either set max-age property to 24h for that test, or advance past 90d.
- Add: within 90d still eligible; beyond max-age denied.
- Keep non-free plan / bad hash denials.

### 5) Optional evidence file refresh (no secrets in chat)
If after code change `expiresAtMs` still in the past, update **only** `verifiedAtMs`/`expiresAtMs` on `data/usage/groq-free-plan.json` using existing project script if any; else minimal field bump: expiresAtMs=verifiedAtMs+maxAge (keep hashes/limits). Never print keySha256 full values in reports.

## Verify
```text
cd C:\AbandonWare\demo-1\demo-1\src
# unit
gradlew.bat test --tests com.example.lms.agent.GroqFreeTierGuardTest
# or project-preferred isolated test lane
rg -n "evidence-max-age|24-hour account evidence" main/java/com/example/lms/agent/GroqFreeTierGuard.java AGENTS.md docs/provider-limits/groq-limits.md
```
Report eligible() behavior with current proof file after TTL change (true/false + reasonCode only).

## Hard stops
- Removing GroqFreeTierGuard or always-eligible bypass
- Enabling paid Groq / flipping allow-remote for all APIs (API-01 본편은 별도)
- Printing API keys / full hashes as secrets
- PROTO_OPEN fail-close, SecurityConfig harden
- `add -A` / push without ask

## Done
```
GROQ_EVIDENCE_TTL: DONE|PARTIAL
default_max_age: 90d (ms=…)
files: [...]
tests: pass|fail|NOT_RUN
current_proof_eligible: true|false (reason only)
NOT_RUN: [...]
```