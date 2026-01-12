package com.abandonware.ai.agent.tool.request;

import com.abandonware.ai.agent.consent.ConsentToken;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Encapsulates contextual information about a tool invocation.  In addition
 * to the session identifier and consent token, callers may attach arbitrary
 * key/value pairs to the context.  Tools can use this information to
 * propagate channel metadata, user preferences or other state into their
 * execution logic.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: ToolContext
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/tool/request/ToolContext.java
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
// Module: com.abandonware.ai.agent.tool.request.ToolContext
// Role: config
// Dependencies: com.abandonware.ai.agent.consent.ConsentToken
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.tool.request.ToolContext
role: config
*/
class ToolContext {
    private final String sessionId;
    private final ConsentToken consent;
    private final Map<String, Object> extras;
    /**
     * Flag indicating whether debug tracing is enabled for this context.  When
     * true the orchestrator will collect detailed step and tool execution
     * information and include it in the response.  Defaults to {@code false}.
     */
    private final boolean debugTrace;

    public ToolContext(String sessionId, ConsentToken consent) {
        this(sessionId, consent, null, false);
    }

    public ToolContext(String sessionId, ConsentToken consent, Map<String, Object> extras) {
        this(sessionId, consent, extras, false);
    }

    /**
     * Constructs a new {@link ToolContext} with the specified debug trace
     * setting.  This constructor is used internally when toggling debug
     * tracing via {@link #withDebugTrace(boolean)}.
     */
    private ToolContext(String sessionId, ConsentToken consent, Map<String, Object> extras, boolean debugTrace) {
        this.sessionId = sessionId;
        this.consent = consent;
        if (extras == null) {
            this.extras = Collections.emptyMap();
        } else {
            this.extras = Collections.unmodifiableMap(new HashMap<>(extras));
        }
        this.debugTrace = debugTrace;
    }

    /** Returns the current session identifier. */
    public String sessionId() {
        return sessionId;
    }

    /** Returns the consent token associated with this invocation. */
    public ConsentToken consent() {
        return consent;
    }

    /** Returns an unmodifiable map of additional context properties. */
    public Map<String, Object> extras() {
        return extras;
    }

    /**
     * Returns whether debug tracing is enabled on this context.  When enabled
     * the orchestrator will produce a detailed JSON trace of all step and
     * tool executions.
     */
    public boolean debugTrace() {
        return debugTrace;
    }

    /**
     * Returns a new {@link ToolContext} with the specified debug trace flag.
     * The returned context shares the same session id, consent token and
     * extra properties but flips the debug tracing setting.  This method
     * does not modify the current instance.
     *
     * @param enabled whether to enable debug tracing
     * @return a new ToolContext with the desired debug trace state
     */
    public ToolContext withDebugTrace(boolean enabled) {
        return new ToolContext(this.sessionId, this.consent, this.extras, enabled);
    }
}