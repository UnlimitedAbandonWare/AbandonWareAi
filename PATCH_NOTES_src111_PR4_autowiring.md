# PATCH NOTES — src111 PR4 Autowiring

- 기반: src111_merge12x.zip
- 병합: src111_merge12x (1).zip 내용 통합 (경로 조정)
  - `AutoWiringConfig.java` → `src/main/java/com/example/lms/config/`
  - `RagPipelineHooks.java` → `app/src/main/java/com/example/lms/config/aop/` (AOP 의존성 모듈로 이동)
  - `tools/pr_auto_wiring.sh` → `tools/`
- 문서/예시 추가:
  - `docs/PR4_autowiring_aop.md`
  - `src/main/resources/application-features-example.yml`
- **빌드 안정성**: AOP 의존성(`spring-boot-starter-aop`)은 `:app` 모듈에 이미 존재. AOP 클래스는 `:app`에 위치시켜 **lms-core**의 컴파일 의존성 요구를 늘리지 않음.
