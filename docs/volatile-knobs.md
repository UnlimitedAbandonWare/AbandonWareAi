# Volatile knobs (agent-facing snapshot)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
reviewAfterDays: 30
disclaimer: >
  Agent-facing snapshot of spec values that drift often. Runtime truth is the
  listed ssot_path (yml / Java / env), not this page — re-read it before
  patching. Persisted Fold `lensSettings` prefs override display/cue factory
  defaults. Env names only; this is not a secret store.
```

| knob | current | ssot_path | live_probe | notes |
|---|---|---|---|---|
| `conversate.display-ttl-ms` | `20000` | `main/resources/application-meta-display.yml` | `lens/text` `hintExpiresAt`−firstShow | env `CONVERSATE_DISPLAY_TTL_MS`; Java `@Value` fallback `15000` applies only when the property is absent — yml wins |
| `conversate.context-ttl-ms` | `120000` | same yml | — | env `CONVERSATE_CONTEXT_TTL_MS` |
| `conversate.transcript.visible-chars` / `context-chars` | `200` / `2000` | same yml | `#transcript` render | env `CONVERSATE_VISIBLE_CHARS` / `CONVERSATE_CONTEXT_CHARS` |
| `conversate.cue.hint-target-chars` | `1000` | same yml | generated hint length | env `CONVERSATE_CUE_HINT_TARGET_CHARS`; Fold `hintTargetChars` pref (240–1100) overrides per owner; `ConversateCardPrompt` falls back to `540` only when pref ≤ 0 |
| `HINT_TEXT_MAX` / `HINT_LINE_MAX` | `1180` / `24` | `main/java/com/example/lms/assist/ConversateSessionService.java` | contract tests | shared hard cap; `receiver.js` `LENS_HINT_CHARS_MAX=1180` mirrors it |
| `conversate.cue.max-output-tokens` | `1536` | same yml | `finishReason=length` logs | env `CONVERSATE_CUE_MAX_OUTPUT_TOKENS` |
| `conversate.cue.trigger-quiet-ms` | `2500` | same yml | cue fire timing | Fold `triggerQuietMs` pref (500–30000) wins |
| `conversate.cue.cooldown-ms` | `10000` | same yml | cue gap | Fold `cueCooldownMs` pref (1000–120000) wins |
| `conversate.cue.force-after-ms` | `180000` | same yml | force-hint cycle | Fold `forceAfterMs` pref (1000–600000) wins; a saved value is never silently clamped |
| `conversate.cue.trigger-min-delta-chars` / `force-min-delta-chars` | `120` / `50` | same yml | — | count gates, not seconds |
| `conversate.cue.total-timeout-ms` / `gate-timeout-ms` / `hint-timeout-ms` / `max-attempts-per-stage` | `12000` / `3000` / `6500` / `3` | same yml + `ConversateApiCueService.limit()` bounds | cue stage logs | stage deadline and total budget both apply |
| `conversate.cue.use-past` / `past-max-turns` / `past-max-chars` | `true` / `4` / `1600` | same yml | prompt assembly | hint-input past window — not display TTL |
| `conversate.cue.max-request-usd` | `0.50` | same yml | spend-guard log | env `CONVERSATE_MAX_REQUEST_USD` |
| `conversate.cue.accounts.*.daily-budget-usd` | openai `0.50` / gemini `0.50` / groq `0` | same yml | — | groq paid fallback prohibited |
| `conversate.cue.routes.*.daily-request-limit` | economy `1000` / balanced `100` / premium `20` / gemini-cue `1000` / gemini-pro `100` / api3+groq `500` | same yml | — | env per route |
| `groq.free-tier.safety-margin` | `0.10` | same yml | `GroqFreeTierGuard` | env `GROQ_FREE_SAFETY_MARGIN`; evidence/ledger paths adjacent in yml |
| `conversate.display.lens-conversation-chars` | `280` | same yml | `lens/text` payload cap | env `CONVERSATE_LENS_CONVERSATION_CHARS`; `receiver.js` contract enforces it |
| `conversate.display.segment-seconds` | `0` | same yml | — | `0` = off |
| `conversate.asr.cloud.stream-seconds` | `75` | same yml | — | provider stream renew bound; `utterance-provider: economy` |
| `demo.interview.enabled` | `false` | same yml + `@Value` | `/` → `/chat` | product flag; `true` switches to interview routing |
| `demo.auth.proto-open` | `true` | same yml | — | env `DEMO_AUTH_PROTO_OPEN`; never committed as public/prod default |
| lens `DISPLAY_DEFAULTS` | font `26`/`26`, transcriptMaxLines `4`, hintPageLines `11`, ttl `20000`/`20000`, autoPage `5000`, hintTargetChars `1000` | `main/resources/static/assets/display/meta/receiver.js` | lens render | transport defaults only; server `lensSettings` echo wins |
| lens `LENS_HINT_CHARS_MAX` | `1180` | `receiver.js` | contract throw | mirrors `HINT_TEXT_MAX` |
| lens clamp ranges | font `20–36`, transcriptLines `1–8`, hintPageLines `4–13`, ttl/autoPage `1000–100000` (`autoPage 0`=off), hintTargetChars `240–1100` | `receiver.js` `normDisplay` + `LensDisplayPrefs` MIN/MAX | `invalid_lens_settings` 400 | server rejects out-of-range; client clamp is a transport guard, not policy |
| `LensDisplayPrefs` cue prefs | defaults `2500`/`10000`/`180000`; ranges quiet `500–30000`, cooldown `1000–120000`, force `1000–600000` | `main/java/com/example/lms/assist/LensDisplayPrefs.java` | lens-settings roundtrip | history knobs: window `5000–300000`, chars `200–8192`, tokens `50–4096` |
| `gpt-search.brave.monthly-quota` | **`950`** (runtime) | `main/resources/application.yml` | trace `web.brave.quota.monthlyQuota` | env `GPT_SEARCH_BRAVE_MONTHLY_QUOTA`; yml always binds the key → `BraveSearchProperties` `@DefaultValue(2000)` is unreachable fallback. **yml wins — do not quote 2000 as current** |
| `gpt-search.brave.qps-limit` / `timeout-ms` / `acquire-timeout-ms` | `1.0` / `3000` / `500` | `main/resources/application.yml` | — | free→base promotion policy: AGENTS `DEMO1-BRAVE-DUAL-KEY` |
| Meta ports | `18180` / `18181` / `18182` | `application-meta-display.yml` + `scripts/agent_port_lease.py` `PROTECTED_PORTS` | port `READY` | never leased to agents; agent `DEFAULT_RANGE` `25000–25999` |
| Ollama / API role models | — | `configs/api-routing.yaml` + `docs/API_ROUTING_SPEC.md` | `ollama ls`, `check-model-lock.ps1` | pointer only — never duplicate the allowlist here |
