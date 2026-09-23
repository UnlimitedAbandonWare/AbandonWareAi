# Desktop Local Model Autopilot App Integration Implementation Plan

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검증된 Desktop `recommendation.json`을 기존 Spring 모델 역할 경계와 읽기 전용 Browser 상태에 연결하고, fast-only 모델이 default chat 역할을 덮어쓰지 못하도록 관찰·차단 가능한 role guard를 추가한다.

**Architecture:** PowerShell tooling이 hardware, download, benchmark, promotion evidence의 유일한 작성자다. Spring은 schema-versioned recommendation 파일을 읽어 role 상태를 표시하고 기존 manifest alias를 환경 변수로 해석한다. 기존 `/model-settings/save` signature는 유지하며 role guard는 `OBSERVE`에서 시작하고 검증 후 `BLOCK`으로 전환한다.

**Tech Stack:** Java 21/Spring Boot existing repo version, Jackson, SnakeYAML, Thymeleaf, JUnit 5, Mockito, AssertJ, Gradle wrapper. 모든 `dev.langchain4j` dependency는 정확히 `1.0.1`을 유지한다.

## Global Constraints

- 이 계획은 `2026-07-31-desktop-ollama-model-tooling-implementation.md`의 schema version 1 report가 고정된 뒤 실행한다.
- Desktop canonical source owner만 애플리케이션 소스를 수정하고 최종 Gradle/runtime proof를 소유한다.
- active sourceSets는 `main/java`, `main/resources`, `app/src/main/java_clean`, `app/src/main/resources`다.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archive, backup, generated output은 수정하지 않는다.
- 공개 API signature, DB schema/row, credential, embedding model/dimension, LangChain4j version을 변경하지 않는다.
- Existing public API signature remains unchanged; `/model-settings/save`와 `/api/settings/model`의 method, path, request, response 계약을 그대로 유지한다.
- Browser는 recommendation을 읽기 전용으로 표시한다. pull, benchmark, promote 버튼이나 새 mutation endpoint를 만들지 않는다.
- raw prompt/response, path, environment value, credential은 TraceStore, log, model attribute, HTML에 넣지 않는다.
- `qwen3:8b`와 3060 후보는 default chat role에 fast-only로 분류한다. 기존 baseline은 rollback을 위해 삭제하지 않는다.
- role guard 기본값은 `OBSERVE`; Desktop runtime proof와 별도 운영 전환 승인 후에만 `BLOCK`을 설정한다.
- commit, push, 배포는 별도 사용자 승인이 있을 때만 수행한다. task checkpoint는 suggested commit message만 기록한다.
- `desktopFinalProof=evidence_needed`와 `runtimeLineageVerdict=HOLD`는 실제 provider/model row가 확인될 때까지 유지한다.

---

## File Structure

- Modify `main/resources/configs/models.manifest.yaml`: add four candidate model entries; keep rollback baselines.
- Modify `app/src/main/resources/configs/models.manifest.yaml`: mirror candidate entries while preserving existing `routing` section.
- Modify `main/java/com/example/lms/manifest/ModelManifestConfig.java`: apply resolved role properties to in-memory aliases after YAML load.
- Modify `src/test/java/com/example/lms/manifest/LocalModelConfigYamlTest.java`: candidate/lane/context and baseline assertions.
- Create `src/test/java/com/example/lms/manifest/ModelManifestConfigRoleOverrideTest.java`: resolved alias tests.
- Create `main/java/com/example/lms/llm/autopilot/LocalModelAutopilotProperties.java`: typed enabled/path/age/guard configuration.
- Create `main/java/com/example/lms/llm/autopilot/LocalModelRecommendationSnapshot.java`: immutable public-safe schema.
- Create `main/java/com/example/lms/llm/autopilot/LocalModelRecommendationService.java`: bounded file reader and redacted status owner.
- Create `main/java/com/example/lms/llm/autopilot/LocalModelRolePolicy.java`: default-chat role decision owner.
- Create `src/test/java/com/example/lms/llm/autopilot/LocalModelRecommendationServiceTest.java`: missing/stale/malformed/ready report tests.
- Create `src/test/java/com/example/lms/llm/autopilot/LocalModelRolePolicyTest.java`: OBSERVE/BLOCK and winner tests.
- Modify `main/resources/application-llm.yaml`: opt-in autopilot settings only.
- Modify `main/java/com/example/lms/service/ModelSettingsService.java`: invoke role policy before DB write.
- Modify `main/java/com/example/lms/web/PageController.java`: ignore blocked stale selection and expose recommendation snapshot.
- Modify `main/resources/templates/model-settings.html`: read-only role recommendation table.
- Modify `src/test/java/com/example/lms/web/PageControllerModelPolicyTest.java`: guard and template contracts.
- Modify `src/test/java/com/example/lms/service/ModelSettingsServiceRedactionContractTest.java`: blocked write and redaction contracts.

## Explicitly Unchanged Files

- `main/java/com/example/lms/config/LocalLlmProcessManager.java`: existing autostart/warmup remains; model portfolio pull belongs to Desktop scripts.
- `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`: application health stays independent; exact UUID/driver/free-VRAM inventory belongs to preflight JSON.
- `main/java/com/example/lms/health/GpuGatewayDiagnostics.java`: existing endpoint health remains; benchmark `/api/ps` proof belongs to tooling.
- `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`: request success/failure remains a second runtime gate and is not replaced by recommendation evidence.

---

### Task 1: Candidate Manifest Entries and Environment-Resolved Role Aliases

**Files:**
- Modify: `main/resources/configs/models.manifest.yaml:1-81`
- Modify: `app/src/main/resources/configs/models.manifest.yaml:1-81`
- Modify: `main/java/com/example/lms/manifest/ModelManifestConfig.java:15-47`
- Modify: `src/test/java/com/example/lms/manifest/LocalModelConfigYamlTest.java:89-100,409-458`
- Create: `src/test/java/com/example/lms/manifest/ModelManifestConfigRoleOverrideTest.java`

**Interfaces:**
- Consumes: Spring properties `llm.chat-model`, `llm.fast.model`, `llm.high.model`, `llm.judge.model`, `llm.coder.model`, `llm.vision.model`.
- Produces: `ModelsManifest` whose in-memory bindings/aliases reflect resolved environment values while static YAML retains rollback defaults.

- [ ] **Step 1: Write failing manifest candidate assertions**

Add to `assertLocalManifest`:

```java
ModelsManifest.Model qwen35 = modelById(manifest, "qwen3.5:9b");
assertEquals(8192, qwen35.getCtx());
assertTrue(qwen35.getTags().containsAll(List.of("fast-candidate", "local", "3060")));
assertEquals("${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}}",
        qwen35.getEndpoint().base_url);

ModelsManifest.Model gemma12 = modelById(manifest, "gemma4:12b");
assertTrue(gemma12.getTags().containsAll(List.of("fast-candidate", "local", "3060")));

ModelsManifest.Model qwen36 = modelById(manifest, "qwen3.6:27b");
assertEquals(16384, qwen36.getCtx());
assertTrue(qwen36.getTags().containsAll(List.of("main-candidate", "coder-candidate", "local", "3090")));

ModelsManifest.Model gemma31 = modelById(manifest, "gemma4:31b");
assertEquals(8192, gemma31.getCtx());
assertTrue(gemma31.getTags().containsAll(List.of("main-candidate", "local", "3090")));
```

- [ ] **Step 2: Write the failing role-override test**

```java
@Test
void resolvedEnvironmentOverridesRuntimeAliasesWithoutChangingStaticRollbackEntries() {
    MockEnvironment env = new MockEnvironment()
            .withProperty("agent.models.path", "main/resources/configs/models.manifest.yaml")
            .withProperty("llm.chat-model", "qwen3.6:27b")
            .withProperty("llm.fast.model", "qwen3.5:9b")
            .withProperty("llm.high.model", "qwen3.6:27b")
            .withProperty("llm.judge.model", "qwen3:30b")
            .withProperty("llm.coder.model", "qwen3-coder:30b")
            .withProperty("llm.vision.model", "qwen3-vl:8b");

    ModelsManifest manifest = new ModelManifestConfig(env).modelsManifest();

    assertEquals("qwen3.6:27b", manifest.getBindings().getDefault());
    assertEquals("qwen3.5:9b", manifest.getAliases().get("fast"));
    assertEquals("qwen3.5:9b", manifest.getAliases().get("rewrite"));
    assertEquals("qwen3.6:27b", manifest.getAliases().get("high"));
    assertEquals("qwen3-coder:30b", manifest.getAliases().get("coder"));
    assertTrue(manifest.getModels().stream().anyMatch(model -> "gemma4:26b".equals(model.getId())));
    assertTrue(manifest.getModels().stream().anyMatch(model -> "qwen3:8b".equals(model.getId())));
}
```

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat test --tests "com.example.lms.manifest.LocalModelConfigYamlTest" --tests "com.example.lms.manifest.ModelManifestConfigRoleOverrideTest"
```

Expected: FAIL because candidates and constructor/override behavior are absent.

- [ ] **Step 4: Add all four candidate entries to both active manifests**

Insert these entries before judge/coder/vision/embedding entries in both files; preserve the app manifest's `routing` block unchanged:

Change the top-level `version` in both manifests from `2026-05-28` to `2026-07-31` in the same edit.

```yaml
  - id: "qwen3.5:9b"
    provider: "local"
    endpoint:
      type: "openai_compat"
      base_url: "${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}}"
      key_env: "${LLM_API_KEY:ollama}"
    capabilities: [chat, reasoning]
    ctx: 8192
    price: {}
    tags: [fast-candidate, rewrite, explore, local, 3060]
  - id: "gemma4:12b"
    provider: "local"
    endpoint:
      type: "openai_compat"
      base_url: "${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${LLM_BASE_URL:http://127.0.0.1:11435/v1}}}"
      key_env: "${LLM_API_KEY:ollama}"
    capabilities: [chat, reasoning, vision]
    ctx: 8192
    price: {}
    tags: [fast-candidate, rewrite, explore, local, 3060]
  - id: "qwen3.6:27b"
    provider: "local"
    endpoint:
      type: "openai_compat"
      base_url: "${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11434/v1}}"
      key_env: "${LLM_API_KEY:ollama}"
    capabilities: [chat, reasoning, code, vision]
    ctx: 16384
    price: {}
    tags: [main-candidate, high-candidate, coder-candidate, local, 3090]
  - id: "gemma4:31b"
    provider: "local"
    endpoint:
      type: "openai_compat"
      base_url: "${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11434/v1}}"
      key_env: "${LLM_API_KEY:ollama}"
    capabilities: [chat, reasoning, vision]
    ctx: 8192
    price: {}
    tags: [main-candidate, high-candidate, local, 3090]
```

- [ ] **Step 5: Resolve role aliases after manifest load**

Give `ModelManifestConfig` a constructor-injected `Environment`. Apply overrides immediately after either filesystem or classpath load succeeds:

```java
private final Environment environment;

public ModelManifestConfig(Environment environment) {
    this.environment = environment;
}

private ModelsManifest applyRoleOverrides(ModelsManifest manifest) {
    String chat = environment.getProperty("llm.chat-model", "gemma4:26b");
    String fast = environment.getProperty("llm.fast.model", "qwen3:8b");
    String high = environment.getProperty("llm.high.model", chat);
    String judge = environment.getProperty("llm.judge.model", "qwen3:30b");
    String coder = environment.getProperty("llm.coder.model", "qwen3-coder:30b");
    String vision = environment.getProperty("llm.vision.model", "qwen3-vl:8b");

    manifest.getBindings().setDefault(chat);
    manifest.getBindings().setMoe(chat);
    Map<String, String> aliases = manifest.getAliases();
    for (String alias : List.of("cheap", "fast", "light", "rewrite", "explore")) aliases.put(alias, fast);
    aliases.put("high", high);
    aliases.put("judge", judge);
    aliases.put("critic", judge);
    aliases.put("coder", coder);
    aliases.put("code", coder);
    aliases.put("vision", vision);
    aliases.put("vl", vision);
    return manifest;
}
```

Reject blank override values by retaining the static manifest value. Do not log raw property values.

- [ ] **Step 6: Run GREEN and mirror check**

Run the Task 1 Gradle command. Then compare candidate IDs, endpoint types, context, and tags across both manifests; allow the app-only `routing` section to differ.

Suggested commit message, only after separate approval: `feat: register local model autopilot candidates`.

---

### Task 2: Typed Autopilot Configuration and Recommendation Reader

**Files:**
- Create: `main/java/com/example/lms/llm/autopilot/LocalModelAutopilotProperties.java`
- Create: `main/java/com/example/lms/llm/autopilot/LocalModelRecommendationSnapshot.java`
- Create: `main/java/com/example/lms/llm/autopilot/LocalModelRecommendationService.java`
- Create: `src/test/java/com/example/lms/llm/autopilot/LocalModelRecommendationServiceTest.java`
- Modify: `main/resources/application-llm.yaml:74-120`

**Interfaces:**
- Consumes: schema version 1 `data/agent-handoff/model-autopilot/recommendation.json`.
- Produces: `LocalModelRecommendationService.snapshot(): LocalModelRecommendationSnapshot` and `role(String): Optional<RoleRecommendation>`.

- [ ] **Step 1: Write failing missing, stale, malformed, and ready tests**

```java
@TempDir Path tempDir;

@Test
void missingReportIsUnavailableWithoutExposingPath() {
    LocalModelAutopilotProperties properties = properties(tempDir.resolve("private-model-path.json"));
    LocalModelRecommendationSnapshot snapshot = service(properties).snapshot();
    assertFalse(snapshot.available());
    assertEquals("report-missing", snapshot.reason());
    assertFalse(snapshot.toString().contains("private-model-path"));
}

@Test
void readyReportExposesOnlyBoundedRoleScalars() throws Exception {
    Path report = tempDir.resolve("recommendation.json");
    Files.writeString(report, readyReportJson("qwen3.5:9b", "qwen3.6:27b"), StandardCharsets.UTF_8);
    LocalModelRecommendationSnapshot snapshot = service(properties(report)).snapshot();
    assertTrue(snapshot.available());
    assertEquals("qwen3.5:9b", snapshot.roles().get("fast").winner());
    assertEquals("qwen3.6:27b", snapshot.roles().get("main").winner());
    assertEquals("PASS", snapshot.runtimeLineageVerdict());
    assertFalse(snapshot.toString().contains("prompt"));
}
```

Add cases for schema version 2 → `schema-unsupported`, malformed JSON → `report-malformed`, generated time older than 24 hours → `report-stale`, and lineage HOLD → available status but `promotable=false`.

Use these exact assertions:

```java
assertEquals("schema-unsupported", serviceFor(reportWithSchema(2)).snapshot().reason());
assertEquals("report-malformed", serviceFor("{not-json").snapshot().reason());
assertEquals("report-stale", serviceFor(reportGeneratedAt(Instant.now().minus(Duration.ofHours(25)))).snapshot().reason());
LocalModelRecommendationSnapshot hold = serviceFor(reportWithLineage("HOLD")).snapshot();
assertTrue(hold.available());
assertFalse(hold.promotable());
```

Test helpers have these exact signatures:

```java
private LocalModelAutopilotProperties properties(Path reportPath)
private LocalModelRecommendationService service(LocalModelAutopilotProperties properties)
private LocalModelRecommendationService serviceFor(String json)
private String readyReportJson(String fastWinner, String mainWinner)
private String reportWithSchema(int schemaVersion)
private String reportGeneratedAt(Instant generatedAt)
private String reportWithLineage(String verdict)
```

`service(properties)` constructs `new LocalModelRecommendationService(properties, new ObjectMapper())`; `serviceFor` writes UTF-8 JSON to `tempDir.resolve("recommendation.json")` before constructing the service.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat test --tests "com.example.lms.llm.autopilot.LocalModelRecommendationServiceTest"
```

Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement typed properties**

```java
@ConfigurationProperties(prefix = "llm.autopilot")
public class LocalModelAutopilotProperties {
    public enum RoleGuardMode { OBSERVE, BLOCK }
    private boolean enabled;
    private Path recommendationPath = Path.of("data/agent-handoff/model-autopilot/recommendation.json");
    private Duration maxReportAge = Duration.ofHours(24);
    private RoleGuardMode roleGuardMode = RoleGuardMode.OBSERVE;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getRecommendationPath() { return recommendationPath; }
    public void setRecommendationPath(Path recommendationPath) { this.recommendationPath = recommendationPath; }
    public Duration getMaxReportAge() { return maxReportAge; }
    public void setMaxReportAge(Duration maxReportAge) { this.maxReportAge = maxReportAge; }
    public RoleGuardMode getRoleGuardMode() { return roleGuardMode; }
    public void setRoleGuardMode(RoleGuardMode roleGuardMode) { this.roleGuardMode = roleGuardMode; }
}
```

Do not add a public mutation endpoint.

- [ ] **Step 4: Implement immutable public-safe records**

```java
public record LocalModelRecommendationSnapshot(
        boolean available,
        boolean promotable,
        String status,
        String reason,
        Instant generatedAt,
        long ageSeconds,
        String runtimeLineageVerdict,
        Map<String, RoleRecommendation> roles,
        int candidateCount,
        int passedCandidateCount,
        int failedCandidateCount) {

    public record RoleRecommendation(
            String baseline,
            String winner,
            String decision,
            double scoreDelta) { }
}
```

Wrap the role map with `Map.copyOf`; accept only `fast`, `main`, `high`, `coder`; cap counts at 0–100 and score delta at -100–100.

- [ ] **Step 5: Implement bounded file reading**

`snapshot()` returns `disabled` when properties are disabled. Otherwise it checks existence, regular file, maximum 1MiB, schema version 1, ISO timestamp, max age, status, lineage, and allowed role fields. Cache by `lastModifiedTime + size`; re-read only when either changes. On all failures use fixed reason codes and `SafeRedactor` for suppressed error type. Do not include the path or parser message in logs, TraceStore, records, or exceptions.

Use constructor injection and this exact public surface:

```java
@Component
public class LocalModelRecommendationService {
    private static final long MAX_REPORT_BYTES = 1_048_576L;
    private static final Set<String> ALLOWED_ROLES = Set.of("fast", "main", "high", "coder");
    private final LocalModelAutopilotProperties properties;
    private final ObjectMapper objectMapper;

    public LocalModelRecommendationService(LocalModelAutopilotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LocalModelRecommendationSnapshot snapshot() {
        if (!properties.isEnabled()) return unavailable("disabled");
        Path path = properties.getRecommendationPath();
        try {
            if (!Files.isRegularFile(path)) return unavailable("report-missing");
            long size = Files.size(path);
            if (size <= 0L || size > MAX_REPORT_BYTES) return unavailable("report-size-invalid");
            JsonNode root = objectMapper.readTree(Files.readAllBytes(path));
            if (root.path("schemaVersion").asInt(-1) != 1) return unavailable("schema-unsupported");
            Instant generatedAt = Instant.parse(root.path("generatedAtUtc").asText());
            long ageSeconds = Math.max(0L, Duration.between(generatedAt, Instant.now()).toSeconds());
            if (ageSeconds > properties.getMaxReportAge().toSeconds()) return unavailable("report-stale");

            Map<String, LocalModelRecommendationSnapshot.RoleRecommendation> roles = new LinkedHashMap<>();
            for (String role : ALLOWED_ROLES) {
                JsonNode node = root.path("roles").path(role);
                if (!node.isObject()) continue;
                roles.put(role, new LocalModelRecommendationSnapshot.RoleRecommendation(
                        node.path("baseline").asText(""),
                        node.path("winner").asText(""),
                        node.path("decision").asText("keep-baseline"),
                        Math.max(-100.0d, Math.min(100.0d, node.path("scoreDelta").asDouble(0.0d)))));
            }
            String status = root.path("status").asText("hold");
            String lineage = root.path("runtimeLineageVerdict").asText("HOLD");
            boolean promotable = "ready".equals(status) && "PASS".equals(lineage);
            return new LocalModelRecommendationSnapshot(
                    true, promotable, status,
                    root.path("reason").asText("unknown"), generatedAt, ageSeconds, lineage,
                    Map.copyOf(roles),
                    boundedCount(root.path("candidateCount").asInt(0)),
                    boundedCount(root.path("passedCandidateCount").asInt(0)),
                    boundedCount(root.path("failedCandidateCount").asInt(0)));
        } catch (DateTimeParseException ex) {
            return unavailable("report-time-invalid");
        } catch (JsonProcessingException ex) {
            return unavailable("report-malformed");
        } catch (IOException | RuntimeException ex) {
            return unavailable("report-read-failed");
        }
    }

    public Optional<LocalModelRecommendationSnapshot.RoleRecommendation> role(String role) {
        return Optional.ofNullable(snapshot().roles().get(role));
    }

    private static int boundedCount(int value) { return Math.max(0, Math.min(100, value)); }

    private static LocalModelRecommendationSnapshot unavailable(String reason) {
        return new LocalModelRecommendationSnapshot(
                false, false, "hold", reason, Instant.EPOCH, 0L, "HOLD", Map.of(), 0, 0, 0);
    }
}
```

- [ ] **Step 6: Add opt-in configuration**

Under the existing `llm:` root add:

```yaml
  autopilot:
    enabled: ${LLM_AUTOPILOT_ENABLED:false}
    recommendation-path: ${LLM_AUTOPILOT_RECOMMENDATION_PATH:./data/agent-handoff/model-autopilot/recommendation.json}
    max-report-age: ${LLM_AUTOPILOT_MAX_REPORT_AGE:24h}
    role-guard-mode: ${LLM_AUTOPILOT_ROLE_GUARD_MODE:OBSERVE}
```

- [ ] **Step 7: Run GREEN**

Run the Task 2 Gradle command. Expected: all reader tests PASS.

Suggested commit message, only after separate approval: `feat: read local model recommendation evidence`.

---

### Task 3: Default-Chat Role Policy and Save Guard

**Files:**
- Create: `main/java/com/example/lms/llm/autopilot/LocalModelRolePolicy.java`
- Create: `src/test/java/com/example/lms/llm/autopilot/LocalModelRolePolicyTest.java`
- Modify: `main/java/com/example/lms/service/ModelSettingsService.java:22-145`
- Modify: `src/test/java/com/example/lms/service/ModelSettingsServiceRedactionContractTest.java:19-95`
- Modify: `src/test/java/com/example/lms/web/PageControllerModelPolicyTest.java:210-288`

**Interfaces:**
- Consumes: `ModelsManifest`, `LocalModelAutopilotProperties`, `LocalModelRecommendationService`, configured `llm.chat-model`.
- Produces: `LocalModelRolePolicy.evaluateDefaultChat(String): Decision` where `Decision` is `compatible`, `blocked`, `reason`, and bounded role labels.

- [ ] **Step 1: Write failing role-policy tests**

```java
@Test
void fastOnlyModelIsObservedButNotBlockedInObserveMode() {
    LocalModelRolePolicy policy = policy(RoleGuardMode.OBSERVE, "gemma4:26b", readyRecommendation());
    Decision decision = policy.evaluateDefaultChat("qwen3:8b");
    assertFalse(decision.compatible());
    assertFalse(decision.blocked());
    assertEquals("fast-only-role", decision.reason());
}

@Test
void fastOnlyModelIsBlockedInBlockMode() {
    LocalModelRolePolicy policy = policy(RoleGuardMode.BLOCK, "gemma4:26b", readyRecommendation());
    Decision decision = policy.evaluateDefaultChat("qwen3.5:9b");
    assertFalse(decision.compatible());
    assertTrue(decision.blocked());
}

@Test
void verifiedMainWinnerIsCompatible() {
    LocalModelRolePolicy policy = policy(RoleGuardMode.BLOCK, "gemma4:26b", readyRecommendation());
    Decision decision = policy.evaluateDefaultChat("qwen3.6:27b");
    assertTrue(decision.compatible());
    assertFalse(decision.blocked());
    assertEquals("verified-main-winner", decision.reason());
}
```

Also assert embedding, vision-only, coder-only, judge-only, unknown, blank, and current configured chat model behavior.

The test helper is concrete:

```java
private LocalModelRolePolicy policy(RoleGuardMode mode, String configuredChat,
                                    LocalModelRecommendationSnapshot recommendation) {
    LocalModelAutopilotProperties properties = new LocalModelAutopilotProperties();
    properties.setEnabled(true);
    properties.setRoleGuardMode(mode);
    LocalModelRecommendationService service = mock(LocalModelRecommendationService.class);
    when(service.snapshot()).thenReturn(recommendation);
    return new LocalModelRolePolicy(testManifest(), properties, service, configuredChat);
}
```

`testManifest()` loads `main/resources/configs/models.manifest.yaml` with SnakeYAML. `readyRecommendation()` returns a schema-compatible immutable snapshot whose main winner is `qwen3.6:27b`, decision is `promote`, and `promotable=true`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat test --tests "com.example.lms.llm.autopilot.LocalModelRolePolicyTest" --tests "com.example.lms.service.ModelSettingsServiceRedactionContractTest" --tests "com.example.lms.web.PageControllerModelPolicyTest"
```

Expected: FAIL because policy and injection are absent.

- [ ] **Step 3: Implement the policy**

```java
@Component
public class LocalModelRolePolicy {
    public record Decision(boolean compatible, boolean blocked, String reason, Set<String> roles) { }

    private final Map<String, Set<String>> tagsByModel;
    private final LocalModelAutopilotProperties properties;
    private final LocalModelRecommendationService recommendationService;
    private final String configuredChatModel;

    public LocalModelRolePolicy(
            ModelsManifest manifest,
            LocalModelAutopilotProperties properties,
            LocalModelRecommendationService recommendationService,
            @Value("${llm.chat-model:gemma4:26b}") String configuredChatModel) {
        this.tagsByModel = manifest.getModels().stream().collect(Collectors.toUnmodifiableMap(
                model -> model.getId().toLowerCase(Locale.ROOT),
                model -> model.getTags() == null ? Set.of() : model.getTags().stream()
                        .map(tag -> tag.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                (left, right) -> left));
        this.properties = properties;
        this.recommendationService = recommendationService;
        this.configuredChatModel = configuredChatModel;
    }

public Decision evaluateDefaultChat(String modelId) {
    String canonical = ModelCapabilities.canonicalModelName(modelId);
    if (canonical == null || canonical.isBlank()) return decision(false, "invalid-model");
    if (canonical.equalsIgnoreCase(configuredChatModel)) return decision(true, "configured-main");

    LocalModelRecommendationSnapshot snapshot = recommendationService.snapshot();
    RoleRecommendation main = snapshot.roles().get("main");
    if (snapshot.promotable() && main != null
            && "promote".equals(main.decision())
            && canonical.equalsIgnoreCase(main.winner())) {
        return decision(true, "verified-main-winner");
    }

    Set<String> tags = tagsFor(canonical);
    if (tags.stream().anyMatch(Set.of("default", "main", "moe", "high")::contains)) {
        return decision(true, "manifest-main-role");
    }
    if (tags.stream().anyMatch(Set.of("cheap", "fast", "light", "rewrite", "explore", "fast-candidate")::contains)) {
        return decision(false, "fast-only-role");
    }
    if (tags.contains("embedding")) return decision(false, "embedding-role");
    if (tags.contains("vision") || tags.contains("vl")) return decision(false, "vision-only-role");
    if (tags.contains("coder") || tags.contains("code") || tags.contains("coder-candidate")) return decision(false, "coder-only-role");
    if (tags.contains("judge") || tags.contains("critic")) return decision(false, "judge-only-role");
    return decision(false, "role-unknown");
}

    private Set<String> tagsFor(String modelId) {
        return tagsByModel.getOrDefault(modelId.toLowerCase(Locale.ROOT), Set.of());
    }

    private Decision decision(boolean compatible, String reason) {
        boolean blocked = properties.getRoleGuardMode() == RoleGuardMode.BLOCK && !compatible;
        return new Decision(compatible, blocked, reason, Set.copyOf(tagsForReason(reason)));
    }

    private static Set<String> tagsForReason(String reason) {
        return switch (reason) {
            case "fast-only-role" -> Set.of("fast");
            case "embedding-role" -> Set.of("embedding");
            case "vision-only-role" -> Set.of("vision");
            case "coder-only-role" -> Set.of("coder");
            case "judge-only-role" -> Set.of("judge");
            default -> Set.of("main");
        };
    }
}
```

`decision(compatible, reason)` sets `blocked = properties.getRoleGuardMode() == BLOCK && !compatible`. Canonical model ID is used only for comparison; logs and TraceStore use model hash/length and fixed reason.

- [ ] **Step 4: Enforce the save guard before repository writes**

Add `LocalModelRolePolicy` as a required constructor dependency of `ModelSettingsService`. Immediately before `modelRepo.existsById(...)`, evaluate the model. In OBSERVE log hash/length/reason and continue existing validation. In BLOCK throw exactly `IllegalArgumentException("Selected model is not approved for the default chat role.")`. Verify `currentRepo.save` and `modelRepo.existsById` are never called for blocked input.

- [ ] **Step 5: Update constructors in tests and preserve redaction assertions**

Pass a mocked or real policy to every `new ModelSettingsService(...)`. Add an assertion that the thrown message, log contract, and TraceStore never include the raw model ID. Do not weaken existing remote, placeholder, embedding, or endpoint compatibility tests.

- [ ] **Step 6: Run GREEN**

Run the Task 3 Gradle command. Expected: all role policy/save guard tests PASS.

Suggested commit message, only after separate approval: `feat: guard default chat model roles`.

---

### Task 4: Read-Only Browser Recommendation Status

**Files:**
- Modify: `main/java/com/example/lms/web/PageController.java:44-245`
- Modify: `main/resources/templates/model-settings.html:12-86`
- Modify: `src/test/java/com/example/lms/web/PageControllerModelPolicyTest.java:34-362`

**Interfaces:**
- Consumes: `LocalModelRecommendationService.snapshot()` and `LocalModelRolePolicy.evaluateDefaultChat()`.
- Produces: Thymeleaf attributes `localModelAutopilot` and `modelRoleDecisions`; no form action or endpoint changes.

- [ ] **Step 1: Write failing controller and template tests**

Add controller tests that:

- inject a ready snapshot and assert the `localModelAutopilot` attribute contains fast/main/high/coder rows;
- set guard mode BLOCK with stale `CurrentModel=qwen3:8b` and assert current model falls back to configured main;
- set OBSERVE and assert current behavior remains selectable but a `fast-only-role` decision is exposed;
- ensure raw report path and parser errors never appear in model attributes.

Add template source assertions for `data-local-model-autopilot`, `data-autopilot-role`, `data-autopilot-winner`, `data-autopilot-decision`, and absence of `pull`, `download`, `promote`, or new POST actions inside the section.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat test --tests "com.example.lms.web.PageControllerModelPolicyTest"
```

Expected: FAIL because attributes and markup do not exist.

- [ ] **Step 3: Wire the services into PageController**

Add both services as final constructor dependencies. At the beginning of `prepareModelData`, take one recommendation snapshot and reuse it for the request. Filter a model only when `Decision.blocked()` is true. Add:

```java
model.addAttribute("localModelAutopilot", recommendationSnapshot);
model.addAttribute("modelRoleDecisions", allModels.stream().collect(Collectors.toMap(
        ModelEntity::getModelId,
        entity -> localModelRolePolicy.evaluateDefaultChat(entity.getModelId()),
        (left, right) -> left,
        LinkedHashMap::new)));
```

Update PageController tests through one `controller(...)` helper so constructor dependencies remain consistent.

```java
private static PageController controller(
        ModelEntityRepository modelRepo,
        CurrentModelRepository currentRepo,
        ModelSettingsService settingsService,
        LocalModelRecommendationService recommendationService,
        LocalModelRolePolicy rolePolicy) {
    return new PageController(modelRepo, currentRepo, settingsService, recommendationService, rolePolicy);
}
```

Replace every direct three-argument `new PageController(...)` call in this test class with that helper and explicit mocks.

- [ ] **Step 4: Add a read-only recommendation table**

Insert above embedding diagnostics:

```html
<section class="border rounded p-3 mb-4" data-local-model-autopilot="true">
  <h2 class="h5">Local model recommendation</h2>
  <p th:unless="${localModelAutopilot.available}"
     th:text="${localModelAutopilot.reason}">report-missing</p>
  <table class="table table-sm" th:if="${localModelAutopilot.available}">
    <thead><tr><th>Role</th><th>Baseline</th><th>Winner</th><th>Decision</th><th>Delta</th></tr></thead>
    <tbody>
      <tr th:each="entry : ${localModelAutopilot.roles}"
          th:data-autopilot-role="${entry.key}">
        <td th:text="${entry.key}">main</td>
        <td th:text="${entry.value.baseline}">gemma4:26b</td>
        <td th:text="${entry.value.winner}" th:data-autopilot-winner="${entry.value.winner}">qwen3.6:27b</td>
        <td th:text="${entry.value.decision}" th:data-autopilot-decision="${entry.value.decision}">promote</td>
        <td th:text="${entry.value.scoreDelta}">0.0</td>
      </tr>
    </tbody>
  </table>
</section>
```

Do not add buttons, JavaScript fetches, forms, or mutation routes.

- [ ] **Step 5: Run GREEN and public-surface scan**

```powershell
.\gradlew.bat test --tests "com.example.lms.web.PageControllerModelPolicyTest"
rg -n "api[_-]?key|Authorization|Bearer|recommendation-path|data-model-(pull|promote)" .\main\resources\templates\model-settings.html .\main\java\com\example\lms\web\PageController.java
```

Expected: tests PASS; scan finds no credential/path or mutation control.

Suggested commit message, only after separate approval: `feat: show local model recommendations`.

---

### Task 5: Configuration and Cross-Boundary Contract Tests

**Files:**
- Modify: `src/test/java/com/example/lms/manifest/LocalModelConfigYamlTest.java`
- Modify: `src/test/java/com/example/lms/llm/ModelRuntimeHealthTrackerTest.java`
- Modify: `src/test/java/com/example/lms/api/ModelSettingsControllerTraceTest.java`
- Modify: `scripts/smoke_gpu_gateway_preflight.ps1`

**Interfaces:**
- Consumes: Tasks 1–4 and tooling schema version 1.
- Produces: regression proof that embedding, endpoint placement, runtime health, API signature, and redaction are unchanged.

- [ ] **Step 1: Add cross-boundary RED assertions**

Assert:

- four candidates exist in both manifests but `bindings.default=gemma4:26b` and `aliases.fast=qwen3:8b` remain static rollback defaults;
- embedding entry and dimension configuration are byte-for-byte unchanged;
- new candidates are not `SEED_LOCAL_CHAT_MODELS`; they require a recommendation winner plus runtime success/configured binding;
- blocked `/api/settings/model` or form save retains hash-only traces and existing HTTP status/signature;
- recommendation reader failure never marks a model promoted;
- Browser render or report presence alone does not set runtime lineage PASS.

- [ ] **Step 2: Run focused tests and verify RED where assertions are new**

```powershell
.\gradlew.bat test --tests "com.example.lms.manifest.LocalModelConfigYamlTest" --tests "com.example.lms.manifest.ModelManifestConfigRoleOverrideTest" --tests "com.example.lms.llm.autopilot.*" --tests "com.example.lms.llm.ModelRuntimeHealthTrackerTest" --tests "com.example.lms.web.PageControllerModelPolicyTest" --tests "com.example.lms.service.ModelSettingsServiceRedactionContractTest" --tests "com.example.lms.api.ModelSettingsControllerTraceTest"
```

Expected: newly added assertions fail before all integration wiring is complete.

- [ ] **Step 3: Complete only the minimum wiring required by the assertions**

Use the existing `ModelRuntimeHealthTracker` unchanged as the runtime success gate. Keep all public controller mappings unchanged. Add only fixed-label TraceStore keys:

```text
localModel.autopilot.status
localModel.autopilot.reason
localModel.autopilot.available
localModel.autopilot.promotable
localModel.autopilot.roleGuardMode
localModel.autopilot.roleMismatchCount
```

Values are fixed labels, booleans, and counts only. Do not trace winner names; use hash/length if correlation is needed.

- [ ] **Step 4: Run GREEN**

Run the Task 5 focused command again. Expected: all selected tests PASS.

- [ ] **Step 5: Extend existing smoke without a second runtime**

Add an opt-in section to `smoke_gpu_gateway_preflight.ps1` that sets `LLM_AUTOPILOT_ENABLED=true` and points `LLM_AUTOPILOT_RECOMMENDATION_PATH` to a temp fixture. Verify `/model-settings` renders the read-only table and a malformed fixture returns a fixed reason. Reuse the script's existing fake gateway and app lifecycle; do not run concurrent `bootRun` instances.

Suggested commit message, only after separate approval: `test: cover local model autopilot boundaries`.

---

### Task 6: Desktop Final Verification and Rollback Drill

**Files:**
- No additional source files.
- Evidence output: `data/agent-handoff/model-autopilot/app-integration-verify.log`

**Interfaces:**
- Consumes: all plan tasks and a real tooling recommendation report.
- Produces: focused build proof, Browser read-only proof, role guard proof, runtime lineage status, and rollback evidence.

- [ ] **Step 1: Re-prove Desktop source ownership gates**

From Desktop canonical root check branch, short status, `.git\index.lock`, pending PatchDrop, active source lease, and ports 8080/8081. HOLD on index lock, overlapping dirty target, pending top-level patch, lease collision, or port collision. Do not modify global `safe.directory`.

- [ ] **Step 2: Run focused tests with isolated caches**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$projectCache = Join-Path $env:LOCALAPPDATA 'AbandonWareX\gradle-project-cache\model-autopilot'
.\gradlew.bat --project-cache-dir $projectCache test --tests "com.example.lms.manifest.LocalModelConfigYamlTest" --tests "com.example.lms.manifest.ModelManifestConfigRoleOverrideTest" --tests "com.example.lms.llm.autopilot.*" --tests "com.example.lms.llm.ModelRuntimeHealthTrackerTest" --tests "com.example.lms.web.PageControllerModelPolicyTest" --tests "com.example.lms.service.ModelSettingsServiceRedactionContractTest" --tests "com.example.lms.api.ModelSettingsControllerTraceTest"
```

Expected: BUILD SUCCESSFUL and zero selected-test failures.

- [ ] **Step 3: Run broad compile/package gates**

```powershell
.\gradlew.bat --project-cache-dir $projectCache compileJava
.\gradlew.bat --project-cache-dir $projectCache :app:classes
.\gradlew.bat --project-cache-dir $projectCache bootJar
```

Expected: all three commands exit 0.

- [ ] **Step 4: Run read-only Browser/runtime smoke**

With a real `recommendation.json`, enable autopilot in OBSERVE mode. Verify the model settings page displays four roles and no action buttons. Send one request through the selected main model and one through the fast route; verify prompt/options hashes, provider attempt row, response model hash, endpoint label, and GPU lane. UI rendering alone is not proof.

- [ ] **Step 5: Exercise BLOCK and rollback**

Set `LLM_AUTOPILOT_ROLE_GUARD_MODE=BLOCK` only in the current verification process. Attempt to save `qwen3:8b` as default and expect rejection without DB write. Restore OBSERVE, restore previous model bindings through the tooling rollback snapshot, restart the app, and verify the previous default plus endpoint health.

- [ ] **Step 6: Record final evidence**

Write command, expected, observed exit code, test counts, report SHA-256, binding before/after hashes, and failure classifications. Set `runtimeLineageVerdict=PASS` only if request/response lineage is complete; otherwise record HOLD and the single missing artifact.

Suggested commit message, only after separate approval: `test: verify local model autopilot integration`.

---

## Plan Completion Gate

- Static manifests contain all candidates and retain rollback baselines.
- Resolved in-memory aliases follow the four promoted environment variables without source rewriting.
- Recommendation reader rejects missing, stale, oversized, malformed, unsupported, or HOLD-lineage reports with fixed reasons.
- OBSERVE logs role mismatch without breaking current behavior; BLOCK prevents fast-only default-chat persistence.
- Browser displays a read-only role table and exposes no mutation control.
- Existing public APIs, DB, credential flow, embedding configuration, and runtime tracker semantics remain unchanged.
- Focused tests, `compileJava`, `:app:classes`, and `bootJar` pass on Desktop.
- Desktop provider/model lineage determines PASS; Notebook or Browser evidence alone does not.
