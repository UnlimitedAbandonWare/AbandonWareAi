# apikit — demo-1 lite API test kit

stdlib-only Python; no Java, no installs. `check` is free (0원). Paid sends need
`--paid`, run once, no retries. Key values are never printed — only env name,
source, length, sha256[:8]. Results saved to `data/agent-handoff/apikit/`.

```powershell
.\scripts\apikit.ps1 check                     # all providers, free only
.\scripts\apikit.ps1 check jev                 # one provider
python -m scripts.apikit check --only jev,gemini --json
.\scripts\apikit.ps1 call jev --paid           # one paid call (Jev -> Node delegate)
.\scripts\apikit.ps1 replay jev --from req.json --paid   # default: dry-run
```

Exit codes: `0` all OK / `3` any failure / `2` script error.

| cls | meaning |
|---|---|
| OK | reachable + key accepted |
| KEY_MISSING | no value in Process/User/Machine/.secrets |
| KEY_MISMATCH | sources hold different values (stale env) |
| KEY_INVALID_OR_EXPIRED | 401 / provider invalid/expired code |
| PLAN_GATE | 403 + plan/paid-feature wording in body (e.g. Vercel ZDR on Hobby) |
| FORBIDDEN_REGION_OR_IP | 403 other (permission/region/IP) |
| QUOTA_OR_BALANCE | 402 / insufficient_quota / zero balance |
| RATE_LIMIT | 429 (unless quota code) |
| MODEL_NOT_FOUND | 404 / model absent from list |
| BAD_REQUEST_SHAPE | 400 |
| NETWORK | DNS/TCP/timeout before HTTP |
| SERVER_5XX | provider-side failure |
| UNKNOWN | unexpected status/shape (masked 300-char snippet kept) |

| provider | env names | free probe |
|---|---|---|
| cerebras | CEREBRAS_API_KEY | GET api.cerebras.ai/v1/models |
| kakao | KAKAO_REST_API_KEY / KAKAO_REST_KEY / KAKAO_API_KEY | GET dapi.kakao.com keyword size=1 |
| naver | NAVER_CLIENT_ID+SECRET / NAVER_KEYS csv | GET openapi.naver.com webkr display=1 |

Provider error codes (OpenAI `error.code`, Gemini `error.details[].reason`,
Vercel `error.type`) are recorded verbatim in `code`. Official docs + check
dates live at the top of each `providers/*.py`.

Rows carry `key_expiry` from `configs/api-key-expiry.json` (metadata ledger:
env/sha8/last4/expiresAt, never key values) — `warn-dN` from D-7, `expired`,
`other_key` on sha8 mismatch, `not_recorded` when absent. The full
classification contract is the SSOT at `docs/API_ROUTING_SPEC.md` §External
API failure classification.
