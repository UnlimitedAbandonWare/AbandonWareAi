# PATCH NOTES — src111_wmerge15

**Goal**: apply safe drop-in fixes, wire missing beans (off by default), and resolve compile-time breakages found by the internal error-pattern scanner.

## What changed
- **Fixed**: `app/.../KakaoPlacesClient.java` brace mismatch → valid `@Service` with empty-result shim.
- **Added**: `SensitivityClamp` (Bode-like), `WpmFusion`, `FinalQualityGate`+`SigmoidFinalQualityGate` in the **com.example.lms** tree.
- **Added**: `RagPipelineConfig` with `@ConditionalOnProperty` beans → toggles are OFF by default.

## Suggested toggles (application.yml)
```yaml
fusion:
  wpm: {enabled: true, p: 1.0}
score:
  clamp: {enabled: true, kind: tanh}
gate:
  final: {enabled: true, k: 12.0, x0: 0.72, target: 0.90}
```

## Build tips
- Modules: `settings.gradle` includes **app**, **lms-core**, **cfvm-raw** only.
- Prefer `./gradlew :app:build` to avoid experimental sources under the root `src/` tree.
- If you need a quick syntax check: `javac @sources.lms` with files from `app/src/main/java/com/example/lms/**` only.
```bash
find app/src/main/java/com/example/lms -name '*.java' > sources.lms
javac -encoding UTF-8 @sources.lms
```
