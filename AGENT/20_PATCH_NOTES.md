    # 20_PATCH_NOTES.md — 파일별 수정 (원인/조치/검증/최소 diff)

    ## 원인
    - `application.properties`에 `server.port=80` + `server.ssl.enabled=true` 구성으로 **TLS가 80번 포트**에 바인딩됨.
    - 결과적으로 `https://DOMAIN:80`만 정상, `http://DOMAIN` 요청은 **TLS 핸드셰이크 실패**로 접속 불가.
    - 일부 시큐리티 체인에서 `requiresSecure()`가 항상 활성화되어 http 접근 시 강제 리디렉션 동작.

    ## 조치
    1. **Dual Port 커스터마이저 추가**
       - 파일: `src/main/java/com/example/lms/config/TomcatDualPortConfig.java`
       - 내용: 메인 포트를 **443(HTTPS)**로 전환하고 **80(HTTP) 추가 커넥터** 개방. 설정 파일은 **미변경**.

    2. **HTTPS 강제 체인 Opt‑In**
       - 파일: `src/main/java/com/example/lms/config/CustomSecurityConfig.java`
       - 내용: `@ConditionalOnProperty(name="security.force-https", havingValue="true")` 추가로 기본 비활성.

    ## 검증(스모크)
    - (로컬 불가) 부팅 시 Embedded Tomcat이 `https:443`과 `http:80`을 바인딩해야 함.
    - `/chat` GET은 HTTP/HTTPS 모두에서 템플릿 `chat-ui.html` 반환.
    - `/api/chat/**`는 동일 오리진 fetch로 정상 동작. CSRF 쿠키는 HTTP에서도 전달됨.

    ## 최소 diff
    ```diff
    --- a/CustomSecurityConfig.java+++ b/CustomSecurityConfig.java@@ -12,6 +12,7 @@ import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
 import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
 import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

 /**
  * 커스텀 보안 설정.
@@ -27,6 +28,7 @@  * </ul>
  */
 @Configuration
+@ConditionalOnProperty(name = "security.force-https", havingValue = "true")
 @RequiredArgsConstructor
 public class CustomSecurityConfig {


    ```
