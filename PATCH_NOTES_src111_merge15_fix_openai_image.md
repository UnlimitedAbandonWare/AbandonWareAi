# Patch Notes — src111_merge15 (OpenAI Image Plugin bootstrap fix)

**Date:** 2025‑10‑22  
**Scope:** `com.example.lms.plugin.image` (controller/service/properties), `src/main/resources/application.properties`

## Symptoms
`gradle :bootRun` fails at startup with:
```
UnsatisfiedDependencyException: Error creating bean with name 'imageGenerationPluginController' ... 
Could not bind properties to 'OpenAiImageProperties' : prefix=openai.image
Reason: openai.image.endpoint => 공백일 수 없습니다
```

## Root cause
`OpenAiImageProperties` enforced `@NotBlank endpoint` under `@Validated`, causing Spring Boot to fail fast when `openai.image.endpoint` is absent. The image plugin beans were unconditionally created.

## Changes
- **Properties**
  - Converted to tolerant binding: removed `@NotBlank` and added `boolean enabled` flag.
  - Record now: `OpenAiImageProperties(boolean enabled, @Nullable String endpoint, @Nullable String apiKey)`.
- **Service & Controller**
  - Added `@ConditionalOnProperty(prefix="openai.image", name="enabled", havingValue="true")` to load only when enabled.
  - Service now falls back to `"/v1/images"` if endpoint is blank.
- **Defaults**
  - `application.properties` now sets:
    ```properties
    openai.image.enabled=false
    openai.image.endpoint=/v1/images
    openai.image.api-key=${openai.api.key}
    ```
- **Safety**
  - If enabled but API key is missing, controller returns `400 NO_API_KEY` (existing behavior).

## How to enable the plugin
Add to your active profile (e.g. `application-local.yml`):
```yaml
openai:
  base-url: https://api.openai.com
  api:
    key: ${OPENAI_API_KEY}   # or direct literal
  image:
    enabled: true
    endpoint: /v1/images
```

## Regression checklist
- [x] App boots without `openai.image.*` configured.
- [x] Other modules continue to use `openaiWebClient` normally.
- [x] `/api/image-plugin/**` endpoints only exist when `openai.image.enabled=true`.