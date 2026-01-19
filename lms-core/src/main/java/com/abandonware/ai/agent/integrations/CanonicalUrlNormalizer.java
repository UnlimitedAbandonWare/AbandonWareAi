package com.abandonware.ai.agent.integrations;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * CanonicalUrlNormalizer
 *  - 추적용 쿼리파라미터(utm_*, fbclid) 제거
 *  - fragment 제거
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: CanonicalUrlNormalizer
 * 역할(Role): Class
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/agent/integrations/CanonicalUrlNormalizer.java
 *
 * 연결 포인트(Hooks):
 *   - DI/협력 객체는 @Autowired/@Inject/@Bean/@Configuration 스캔으로 파악하세요.
 *   - 트레이싱 헤더: X-Request-Id, X-Session-Id (존재 시 전체 체인에서 전파).
 *
 * 과거 궤적(Trajectory) 추정:
 *   - 본 클래스가 속한 모듈의 변경 이력은 /MERGELOG_*, /PATCH_NOTES_*, /CHANGELOG_* 문서를 참조.
 *   - 동일 기능 계통 클래스: 같은 접미사(Service/Handler/Controller/Config) 및 동일 패키지 내 유사명 검색.
 *
 * 안전 노트: 본 주석 추가는 코드 실행 경로를 변경하지 않습니다(주석 전용).
 */
public final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.abandonware.ai.agent.integrations.CanonicalUrlNormalizer
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.integrations.CanonicalUrlNormalizer
role: config
*/
class CanonicalUrlNormalizer {
  private CanonicalUrlNormalizer() {}

  public static String canonical(String url) {
    if (url == null || url.isBlank()) return url;
    try {
      URI u = URI.create(url);
      String filteredQuery = null;
      if (u.getQuery() != null && !u.getQuery().isBlank()) {
        filteredQuery = Arrays.stream(u.getQuery().split("&"))
          .filter(p -> !p.startsWith("utm_") && !p.startsWith("fbclid"))
          .collect(Collectors.joining("&"));
        if (filteredQuery.isBlank()) filteredQuery = null;
      }
      return new URI(u.getScheme(), u.getAuthority(), u.getPath(), filteredQuery, null).toString();
    } catch (Exception e) {
      return url; // 실패 시 원본 유지 (Fail-soft)
    }
  }
}