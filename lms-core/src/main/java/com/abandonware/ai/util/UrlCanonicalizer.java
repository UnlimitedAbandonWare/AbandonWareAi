package com.abandonware.ai.util;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: UrlCanonicalizer
 * 역할(Role): Class
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/util/UrlCanonicalizer.java
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
// Module: com.abandonware.ai.util.UrlCanonicalizer
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.util.UrlCanonicalizer
role: config
*/
class UrlCanonicalizer {
    private static final Set<String> DROP_PARAMS = new HashSet<>(Arrays.asList(
            "gclid","fbclid","ref"
    ));
    private static final Pattern UTM = Pattern.compile("^utm_.*", Pattern.CASE_INSENSITIVE);
    private UrlCanonicalizer(){}

    public static String canonicalKey(String url){
        if (url == null) return null;
        try{
            URI u = URI.create(url);
            String scheme = (u.getScheme()==null?"http":u.getScheme().toLowerCase(Locale.ROOT));
            String host = u.getHost()==null? "": u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            String authority = host;
            if (port != -1 && !((scheme.equals("http") && port==80) || (scheme.equals("https") && port==443))) {
                authority = host + ":" + port;
            }
            String path = (u.getPath()==null || u.getPath().isBlank()) ? "/" : u.getPath();
            // scrub query params
            Map<String,String> kept = new LinkedHashMap<>();
            String q = u.getQuery();
            if (q != null && !q.isBlank()){
                for (String kv : q.split("&")){
                    if (kv.isBlank()) continue;
                    String[] parts = kv.split("=",2);
                    String k = parts[0];
                    if (k == null) continue;
                    String kl = k.toLowerCase(Locale.ROOT);
                    if (DROP_PARAMS.contains(kl)) continue;
                    if (UTM.matcher(kl).matches()) continue;
                    if (kl.startsWith("ref_")) continue;
                    kept.put(k, parts.length>1?parts[1]:"");
                }
            }
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String,String> e : kept.entrySet()){
                if (query.length()>0) query.append("&");
                query.append(e.getKey()).append("=").append(e.getValue());
            }
            String result = scheme + "://" + authority + path;
            if (query.length()>0) result += "?" + query;
            // drop fragment
            if (result.endsWith("/")) result = result.substring(0, result.length()-1);
            return result;
        }catch(Exception e){
            return url;
        }
    }
}