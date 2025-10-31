# Patch: src111_merge15 — Fix bootRun failure (n8n.webhook.secret)

Applied on: 2025-10-18T11:50:05.290745Z

## What
- Relaxed `@Value` placeholder in `com.example.lms.api.N8nWebhookController` constructor:
  - `@Value("${n8n.webhook.secret}")` → `@Value("${n8n.webhook.secret:}")`
- This prevents `PlaceholderResolutionException` when `n8n.webhook.secret` is not set.

## Why
Gradle `:bootRun` crashed at runtime due to missing property:
```
Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder 'n8n.webhook.secret'
```
This occurred during bean creation for `N8nWebhookController`.

## Behavior
- If the secret is unset/empty, HMAC verification fails → POST `/hooks/n8n` returns 401 (secure-by-default).
- If a secret is provided (e.g. `n8n.webhook.secret=testsecret`), the controller works normally and enqueues the job.

## How to enable in env
Add e.g. to `application-secrets.yml` or environment:
```yaml
n8n:
  webhook:
    secret: your-secret
```

## Related
- Existing test `N8nWebhookControllerTest` already supplies the secret via `@TestPropertySource`, so tests remain valid.
