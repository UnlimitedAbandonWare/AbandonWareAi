# uaw_spine_probe summary

- generatedAtUtc: 2026-09-28T12:53:35+00:00
- root: C:\AbandonWare\demo-1\demo-1\src
- counts: {'OK': 6, 'WARN': 4}

| id | verdict | evidence |
| --- | --- | --- |
| S1.boot-main-class | OK | build.gradle.kts mainClass="com.example.lms.LmsApplication" |
| S2.scan-boundary | OK | scanBasePackages="com.example.lms", "com.nova.protocol" |
| S3.autoconfig-imports | OK | count=6 missing=[] dup=[] extra=[] |
| S4.legacy-launcher | WARN | AgentApplication.java present — 레거시 런처 잔존(스캔 확장 금지, 존재 경고만) |
| S5.plandsl-not-used-contract | OK | UnifiedRagOrchestrator planDsl.status="not_used" 마커 존재 |
| S6.rulebreak-mvc-wiring | OK | WebMvcConfig 조건부 등록 + canonical @Component 빈 (admin-token 게이트) |
| S7.plan-alias-duplicates | WARN | non-v1=['brave.yaml', 'safe_autorun.yaml', 'zero_break.yaml'] v1과 중복 의심=['brave.yaml', 'safe_autorun.yaml', 'zero_break.yaml'] |
| S8.config-dual-declare | WARN | properties+yml 양쪽 선언=['ocr.enabled', 'ocr.min-confidence', 'local-llm.base-url', 'retrieval.vector.enabled'] (properties가 우선 — 실효값은 origin 확인 필요) |
| S9.dormant-roots | WARN | 스캔 밖 패키지 루트 존재=['main/java/com/abandonware/ai', 'main/java/com/abandonwareai', 'main/java/com/abandonware/patch', 'main/java/strategy', 'main/java/service', 'main/java/web', 'main/java/config', 'main/java/guard', 'main/java/trace'] — 깨우지 말 것 |
| S10.canonical-gates | OK | CitationGate=Y; FinalSigmoidGate=Y; DomainWhitelist=Y; PIISanitizer=Y; PiiSanitizer(guard)=Y |
