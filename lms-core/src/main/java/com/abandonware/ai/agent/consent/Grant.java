package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;



/**
 * Represents a grant of one or more scopes to a session.  Grants are
 * time-limited and will expire automatically after the specified
 * expiration time.  The consent service stores grants and looks them up
 * by session identifier when verifying permissions.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: Grant
 * 역할(Role): Class
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/agent/consent/Grant.java
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
// Module: com.abandonware.ai.agent.consent.Grant
// Role: config
// Dependencies: com.abandonware.ai.agent.tool.ToolScope
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.consent.Grant
role: config
*/
class Grant {
    private final String sessionId;
    private final Set<ToolScope> scopes;
    private final Instant expiresAt;

    public Grant(String sessionId, Set<ToolScope> scopes, Instant expiresAt) {
        this.sessionId = sessionId;
        this.scopes = Collections.unmodifiableSet(new HashSet<>(scopes));
        this.expiresAt = expiresAt;
    }

    /** Returns the session identifier associated with this grant. */
    public String sessionId() {
        return sessionId;
    }

    /** Returns the set of granted scopes. */
    public Set<ToolScope> scopes() {
        return scopes;
    }

    /** Returns the instant at which this grant expires. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Returns true if this grant has expired. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}