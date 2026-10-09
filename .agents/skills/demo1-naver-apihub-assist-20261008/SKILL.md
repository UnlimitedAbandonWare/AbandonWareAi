---
name: demo1-naver-apihub-assist-20261008
description: Read-only pin, coverage, lease-overlap, hypothesis, hold-ledger, env-presence, diff-review, verify-plan checks plus a 127.0.0.1 API HUB mock for the Codex briefs naver-apihub-search-50e1ba15 (NAVER API HUB provider path, openapi path preserved) and NAVER-BRAVE-RAG-RESTORE-20261008 (failure/fallback/recovery ledger honesty). Product source stays with Codex.
---

# NAVER API HUB + NAVER/Brave restore assist (2026-10-08)

## When

Codex is patching the two 2026-10-08 NAVER briefs and the assist side needs the
anchor pins, named-test coverage, lease-overlap scope check, WP1 hypothesis
discipline, the HOLD ledger, a secrets-safe key-presence probe, a diff
reviewer, the ordered acceptance command list, or a loopback API HUB mock.

## SSOT

`var/codex-assist-naver-apihub-20261008/README.md` (verified live-tree facts,
decoded test contract, apikit openapi-only caveat)

## Check

```
python -B scripts/naver_apihub_assist_20261008.py pin --root .
python -B scripts/naver_apihub_assist_20261008.py cover --root .
python -B scripts/naver_apihub_assist_20261008.py scope --root .
python -B scripts/naver_apihub_assist_20261008.py hypothesis --root .
python -B scripts/naver_apihub_assist_20261008.py hold --root .
python -B scripts/naver_apihub_assist_20261008.py env-presence --root .
python -B scripts/naver_apihub_assist_20261008.py verify-plan --root . [--contract naver-apihub-search|naver-brave-rag-restore]
python -B scripts/naver_apihub_assist_20261008.py diff-forbid --root . --diff <owned.diff>
python -B scripts/naver_apihub_assist_20261008.py product-gate --root . --diff <owned.diff>
python -B var/codex-assist-naver-apihub-20261008/selftest_spec.py
python -B scripts/naver_apihub_hubmock_20261008.py --port 18299 --mode ok   # loopback hub stub
```

`product-gate` maps added-line paths onto the brief's own modify list:
IN_SCOPE/TEST_ONLY/ASSIST_ONLY pass, SCOPE_EXPAND needs the <=3-file/<=300-line
auto-extension journal record, PROTECTED_HIT/SECRET_LITERAL block.

## Do not

1. Edit product Java, `application.yml`, chat.js, Display files, BraveSearch, or
   product tests from this skill — they stay with the Codex session.
2. Treat exit 0 as a product PASS, a real NAVER/API-HUB call, or a live verdict.
3. Read or print `.env`/`.secrets` contents — `env-presence` emits booleans per
   expected key name and nothing else; do not open the files by hand either.
4. Burn the shared live-call budget (5 total) or restart the shared 18180
   server — both belong to the Codex session's counters.
5. Force-release a lease reported by `scope` as OVERLAP; wait or coordinate.
6. Claim `check --only naver` (apikit) proves the hub path — it hardcodes
   `openapi.naver.com`; hub wiring is proven by the Java test +
   `hubmock`/`naver.apihub.base-url` loopback or the gated W7 live check.
7. Run the verify-plan Gradle/node commands from the assist session before the
   main agent's final report — it only lists them.
