package com.abandonware.ai.agent.job;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Representation of a job request.  A job request encapsulates a flow name
 * (indicating which orchestrated process should execute the job), an
 * arbitrary payload, and identifiers for tracing the request back to the
 * original caller.  Jobs are always enqueued through the
 * {@link com.abandonware.ai.agent.job.JobQueue} and processed asynchronously.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: JobRequest
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/job/JobRequest.java
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
// Module: com.abandonware.ai.agent.job.JobRequest
// Role: config
// Feature Flags: sse
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.job.JobRequest
role: config
flags: [sse]
*/
class JobRequest {
    private final String flow;
    private final Map<String, Object> payload;
    private final String requestId;
    private final String sessionId;

    public JobRequest(String flow, Map<String, Object> payload, String requestId, String sessionId) {
        this.flow = flow;
        if (payload == null) {
            this.payload = Collections.emptyMap();
        } else {
            this.payload = Collections.unmodifiableMap(new HashMap<>(payload));
        }
        this.requestId = requestId;
        this.sessionId = sessionId;
    }

    public String flow() {
        return flow;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String requestId() {
        return requestId;
    }

    public String sessionId() {
        return sessionId;
    }
}