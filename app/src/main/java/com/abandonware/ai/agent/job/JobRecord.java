package com.abandonware.ai.agent.job;

import java.time.Instant;
import java.util.Optional;



/**
 * Persistent record of a job instance.  The record tracks the request
 * details, current state, result (if completed) and timestamps.  In
 * combination with the job queue, the job record allows retry policies and
 * dead letter queues to be implemented.  This simplified implementation
 * stores everything in memory and is not intended for production use.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: JobRecord
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/job/JobRecord.java
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
// Module: com.abandonware.ai.agent.job.JobRecord
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: uses concurrent primitives.
// /
/* agent-hint:
id: com.abandonware.ai.agent.job.JobRecord
role: config
*/
class JobRecord {
    private final JobId id;
    private final JobRequest request;
    private JobState state;
    private JobResult result;
    private final Instant enqueuedAt;
    private Instant completedAt;

    public JobRecord(JobId id, JobRequest request) {
        this.id = id;
        this.request = request;
        this.state = JobState.PENDING;
        this.enqueuedAt = Instant.now();
    }

    public JobId id() {
        return id;
    }

    public JobRequest request() {
        return request;
    }

    public synchronized JobState state() {
        return state;
    }

    public synchronized void setState(JobState state) {
        this.state = state;
        if (state == JobState.SUCCEEDED || state == JobState.FAILED || state == JobState.DLQ) {
            this.completedAt = Instant.now();
        }
    }

    public synchronized Optional<JobResult> result() {
        return Optional.ofNullable(result);
    }

    public synchronized void setResult(JobResult result) {
        this.result = result;
    }

    public Instant enqueuedAt() {
        return enqueuedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}