# Patch Report — src111_merge15

Applied fixes & enhancements:

1) **Build fix** — `SearchProbeController` not compiling (`enabled` symbol / invalid `@Value` placeholder):
   - Replaced missing field with `@Value("${probe.search.enabled:true}")` and added `@Value("${probe.admin-token:}")`.
   - Endpoint now guards access with `X-Probe-Token` and `probe.admin-token`.

2) **Weighted‑RRF 강화** — Canonical URL dedup + Locale boost + Softmax normalization.
   - Added `UrlCanonicalizer`, `DocumentKeyNormalizer`, `LocaleBoostPolicy`.
   - `WeightedRRF.fuse(...)` now dedups by canonical key and normalizes final scores with softmax.

3) **Score Normalizer** — Stable softmax utility & component for score normalization.

4) **Whitening scaffold** — Low‑rank ZCA whitening interfaces/beans (fail‑soft, disabled by default).


Detected build error patterns before patch (from build-logs):

- spring.beans.unsatisfied-dependency: 2
- config.value.invalid-boolean.probe.search.enabled: 4
- spring.boot.application-run-failed: 1
- spring.beans.type-mismatch: 1
- gradle.bootRun.failed: 1
- gradle.process.non-zero-exit: 1
- javac.cannot-find-symbol: 1
- javac.missing-variable.enabled: 1
- gradle.compileJava.failed: 1

Representative snippets:

### spring.beans.unsatisfied-dependency
```
2025-10-18T20:16:00.742+0900 WARN  o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [C:\AbandonWare\demo-1\demo-1\build\classes\java\main\com\example\lms\probe\Search
```

### config.value.invalid-boolean.probe.search.enabled
```
2025-10-18T20:16:00.742+0900 WARN  o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [C:\AbandonWare\demo-1\demo-1\build\classes\java\main\com\example\lms\probe\Search
```

### spring.boot.application-run-failed
```
2025-10-18T20:16:00.742+0900 WARN  o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [C:\AbandonWare\demo-1\demo-1\build\classes\java\main\com\example\lms\probe\Search
```

### spring.beans.type-mismatch
```
2025-10-18T20:16:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed
org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [C:\AbandonWare\demo-1\demo-1\build\classes\java\main\com\example\lms\probe\SearchProbeController.class]: Unsatisfied dependency expressed through constructor parameter 1: Faile
```

### gradle.bootRun.failed
```
Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]
Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]
> Task :bootRun FAILED
FAILURE: Build failed with an exception.
* What went wrong:
```
