package com.abandonware.ai.service.rag.fusion;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: RerankCanonicalizer
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: src/main/java/_abandonware_backup/ai/service/rag/fusion/RerankCanonicalizer.java
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

final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.abandonware.ai.service.rag.fusion.RerankCanonicalizer
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.RerankCanonicalizer
role: config
*/
class RerankCanonicalizer {
    private static final Set<String> DROP = Set.of(
            "utm_source","utm_medium","utm_campaign","utm_term","utm_content",
            "gclid","fbclid","igshid","spm","clid","ref"
    );

    private RerankCanonicalizer() {}

    static String canonicalKey(String urlOrId) {
        if (urlOrId == null) return "";
        String s = urlOrId.trim();
        try {
            URI u = URI.create(s);
            String host = (u.getHost() == null) ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = (u.getPath() == null) ? "" : u.getPath().replaceAll("/+$","");
            String query = u.getQuery();
            if (query != null && !query.isBlank()) {
                String kept = Arrays.stream(query.split("&"))
                        .map(p -> p.split("=",2))
                        .filter(kv -> kv.length>0 && !DROP.contains(kv[0].toLowerCase(Locale.ROOT)))
                        .map(kv -> String.join("=", kv))
                        .sorted()
                        .collect(Collectors.joining("&"));
                query = kept.isBlank() ? null : kept;
            }
            String base = host + path;
            return (query == null) ? base : (base + "?" + query);
        } catch (IllegalArgumentException e) {
            int i = s.indexOf('#');
            return (i >= 0) ? s.substring(0, i) : s;
        }
    }
}