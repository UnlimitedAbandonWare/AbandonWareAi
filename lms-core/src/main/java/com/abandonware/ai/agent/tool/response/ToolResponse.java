package com.abandonware.ai.agent.tool.response;

import java.util.HashMap;
import java.util.Map;



/**
 * Standard output wrapper for tool invocations.  Tools build up a response
 * by putting arbitrary key/value pairs into the data map.  The response
 */
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.response.ToolResponse
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.response.ToolResponse
role: config
*/
// class is intentionally simple to avoid imposing any particular data
// structure on tool authors; the orchestrator is responsible for mapping
// these results back into the agent context.
//
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: ToolResponse
 * 역할(Role): Class
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/agent/tool/response/ToolResponse.java
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
public final class ToolResponse {
    private final Map<String, Object> data = new HashMap<>();

    private ToolResponse() {
    }

    /** Creates a new empty successful response. */
    public static ToolResponse ok() {
        return new ToolResponse();
    }

    /**
     * Adds a key/value pair to the response and returns this instance for
     * fluent chaining.
     */
    public ToolResponse put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    /** Returns the underlying response map. */
    public Map<String, Object> data() {
        return data;
    }
}