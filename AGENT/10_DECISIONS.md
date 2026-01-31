# 10_DECISIONS.md — 설계/정책 결정 요약

1) **Dual Port (443 TLS + 80 HTTP)**: 내장 Tomcat 커스터마이저로 주 커넥터 포트를 443으로 전환하고, 80번에 **추가 HTTP 커넥터**를 노출.  
   - 근거: 기존 설정은 TLS가 80으로 바인딩되어 `https://host:80`만 정상. `http://host`를 수용하려면 80에서 일반 HTTP가 필요.
   - 경로: src/main/java/com/example/lms/config/TomcatDualPortConfig.java

2) **HTTPS 강제 체인 Opt‑In**: `CustomSecurityConfig`의 `requiresSecure()` 체인을 **환경 프로퍼티 기반**으로 opt‑in 처리.  
   - 근거: 개발/운영 모두에서 http 접근을 차단하지 않도록 기본값은 비활성. 필요 시 `security.force-https=true`로 활성화.
   - 경로: src/main/java/com/example/lms/config/CustomSecurityConfig.java
