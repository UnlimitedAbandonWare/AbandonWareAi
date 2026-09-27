# Local LLM CUDA UUID Pin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the generated Windows Ollama autostart command optionally and fail-closedly pin one NVIDIA GPU UUID without exposing the UUID in traces or logs.

**Architecture:** Extend the existing `LocalLlmProcessManager` property and generated-command seam; do not add another process supervisor. A blank pin preserves the existing generated command. A canonical single `GPU-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` value adds `CUDA_VISIBLE_DEVICES` and disables Ollama's alternate Vulkan backend before `ollama serve`, so Vulkan cannot bypass the CUDA UUID filter. Every nonblank invalid or conflicting configuration fails closed. The Desktop GPU profile exposes the property only through `LOCAL_LLM_CUDA_VISIBLE_DEVICE`.

**Tech Stack:** Java 21, Spring Environment/`@Value`, JUnit 5, AssertJ, SnakeYAML, Gradle 8.7, Windows `cmd.exe`.

## Global Constraints

- Active owners remain root `main/java`, `main/resources`, and `src/test/java`; do not edit inactive aliases.
- Keep all `dev.langchain4j` dependencies exactly at `1.0.1`.
- Keep the existing `LocalLlmProcessManager`; do not add a second launcher, supervisor, route, or gateway.
- The pin is optional by default. Blank configuration must preserve the exact existing one-argument generated command.
- Accept exactly one canonical NVIDIA GPU UUID matching `GPU-[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}` after trimming.
- Reject nonblank invalid values, lists, commas, whitespace-separated devices, shell metacharacters, MIG identifiers, and simultaneous explicit `local-llm.start-command` plus a UUID pin.
- The pinned Windows command must set `OLLAMA_HOST`, `CUDA_VISIBLE_DEVICES`, and `OLLAMA_VULKAN=0` with `set "NAME=value"` syntax before the unchanged `ollama serve` wording. Runtime evidence on 2026-08-01 showed that CUDA UUID filtering alone left Vulkan RTX 3090 eligible and selected; `OLLAMA_VULKAN=0` left only the requested CUDA RTX 3060 compute target.
- Traces and logs may expose only `cudaPinned`, hash, length, and allowlisted reason; never the raw UUID.
- Do not change model aliases, embedding dimensions, persistent environment variables, Ollama stores, or installed models in this task.
- Use isolated Gradle user/project caches and `AWX_SPLIT_BUILD_OUTPUTS=1` with host id `desktop-uuidpin-canonical`.

## File Structure

- Modify `main/java/com/example/lms/config/LocalLlmProcessManager.java`: property loading, UUID validation, generated command, conflict guard, redacted trace/log facts.
- Modify `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`: RED/GREEN behavioral coverage for default, valid, invalid, conflicting, and redaction cases.
- Modify `main/resources/application-desktop-gpu-node.yml`: env-resolved optional pin property.
- Create `src/test/java/com/example/lms/config/LocalLlmDesktopGpuProfileTest.java`: isolated parsed YAML contract assertion; the already-dirty shared manifest test remains untouched.

---

### Task 1: Fail-Closed Single-GPU UUID Pin

**Files:**
- Modify: `main/java/com/example/lms/config/LocalLlmProcessManager.java`
- Modify: `src/test/java/com/example/lms/config/LocalLlmProcessManagerTest.java`
- Modify: `main/resources/application-desktop-gpu-node.yml`
- Create: `src/test/java/com/example/lms/config/LocalLlmDesktopGpuProfileTest.java`

**Interfaces:**
- Consumes: `local-llm.cuda-visible-device`, `${LOCAL_LLM_CUDA_VISIBLE_DEVICE:}`, and the existing `generatedWindowsStartCommand(String host)`.
- Produces: `generatedWindowsStartCommand(String host, String cudaVisibleDevice)`, package-private canonical UUID validation, and `localLlm.startup.cudaPinned|cudaDeviceHash|cudaDeviceLength|cudaPinReason` trace facts.

- [ ] **Step 1: Write the failing Java command and validation tests**

Add focused tests that independently assert these literal behaviors:

```java
String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
assertEquals(
        "start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set \"OLLAMA_HOST=127.0.0.1:11435\"&& set \"CUDA_VISIBLE_DEVICES="
                + uuid + "\"&& set \"OLLAMA_VULKAN=0\"&& ollama serve\"",
        LocalLlmProcessManager.generatedWindowsStartCommand("127.0.0.1:11435", uuid));
```

Keep the existing one-argument exact-command test unchanged. Add table-driven invalid inputs for `GPU-0,GPU-1`, `0,1`, `GPU-bad`, a MIG identifier, `GPU-12345678-1234-1234-1234-123456789abc & calc`, and two whitespace-separated synthetic UUIDs; each must throw `IllegalArgumentException` without echoing the raw input in the message.

- [ ] **Step 2: Write the failing environment, conflict, trace-redaction, and YAML tests**

Use `MockEnvironment` to prove a valid `local-llm.cuda-visible-device` is loaded even when startup is disabled, then assert:

```java
assertEquals(Boolean.TRUE, TraceStore.get("localLlm.startup.cudaPinned"));
assertThat(String.valueOf(TraceStore.get("localLlm.startup.cudaDeviceHash"))).startsWith("hash:");
assertEquals(uuid.length(), TraceStore.get("localLlm.startup.cudaDeviceLength"));
assertEquals("configured_uuid", TraceStore.get("localLlm.startup.cudaPinReason"));
assertThat(TraceStore.getAll().toString()).doesNotContain(uuid);
```

Invoke the effective command path with both nonblank `local-llm.start-command` and the UUID pin and require `IllegalStateException` with the allowlisted reason `cuda_pin_conflicts_with_explicit_start_command`, not either raw value. In the new `LocalLlmDesktopGpuProfileTest`, parse `application-desktop-gpu-node.yml` with SnakeYAML and assert the nested `local-llm.cuda-visible-device` value equals `${LOCAL_LLM_CUDA_VISIBLE_DEVICE:}`.

- [ ] **Step 3: Run RED and verify the expected missing-contract failures**

Run:

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop-uuidpin-canonical'
$env:GRADLE_USER_HOME='C:\AbandonWare\gradle-user-home\codex-local-llm-uuid-pin-canonical'
.\gradlew.bat test --tests com.example.lms.config.LocalLlmProcessManagerTest --tests com.example.lms.config.LocalLlmDesktopGpuProfileTest --no-daemon --project-cache-dir C:\AbandonWare\gradle-project-cache\codex-local-llm-uuid-pin-canonical
```

Expected: FAIL because the two-argument method, validation/property behavior, redacted trace facts, and YAML key do not exist yet. A syntax, import, cache, or unrelated test failure is not an acceptable RED.

- [ ] **Step 4: Implement the minimal property, validator, command, conflict, trace, and YAML behavior**

Add `@Value("${local-llm.cuda-visible-device:}") private String cudaVisibleDevice;`, load and validate it in `loadFromEnvironment()`, preserve the one-argument command method, and add the two-argument overload. Blank returns the exact legacy command. A valid UUID uses quoted `set` syntax for the host and UUID, then sets `OLLAMA_VULKAN=0` before `ollama serve` so a Vulkan device cannot escape the CUDA filter. Invalid nonblank configuration throws an allowlisted exception without raw input. The effective generated-command path rejects an explicit command plus a pin.

Add the four redacted trace facts in `traceStartupSnapshot`; use `SafeRedactor.hashValue(cudaVisibleDevice)` only for a valid configured UUID and an empty string otherwise. Add only booleans, hash, length, and the reason to logs. Add `cuda-visible-device: ${LOCAL_LLM_CUDA_VISIBLE_DEVICE:}` beneath `local-llm` in the Desktop GPU profile.

- [ ] **Step 5: Run GREEN focused tests**

Run the exact Step 3 command. Expected: both focused test classes pass with zero failures.

- [ ] **Step 6: Run compile and repository gates**

Run:

```powershell
.\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity compileJava --no-daemon --project-cache-dir C:\AbandonWare\gradle-project-cache\codex-local-llm-uuid-pin-canonical
```

Expected: `BUILD SUCCESSFUL`, sourceSet owners unchanged, and LangChain4j purity unchanged.

- [ ] **Step 7: Inspect the exact diff and commit only declared files**

Run `git diff --check` and `git diff --` with the four declared paths. Confirm no raw UUID literal entered production resources/logging and no undeclared file changed. The pre-existing dirty `LocalModelConfigYamlTest.java` is explicitly outside this task. Commit only after independent task review reports both specification compliance and code quality approved.

## Plan Completion Gate

- RED failed for the intended absent behavior before production code changed.
- GREEN focused tests and compile/repository gates pass from fresh current output.
- The one-argument generated command remains byte-for-byte compatible.
- Valid canonical UUID pinning is present and alternate Vulkan compute is disabled only for that pinned command; invalid, multiple, MIG, shell-injected, and explicit-command conflicts fail closed.
- Trace/log evidence contains no raw UUID.
- The four-file diff has an independent task review and a final branch review.
- This plan does not authorize model download or claim dual-GPU runtime success.
