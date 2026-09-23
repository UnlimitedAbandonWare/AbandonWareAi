-- Explicit migration for MySQL (also exercised with H2 MODE=MySQL).
-- This file is not automatically executed by the job service.
CREATE TABLE IF NOT EXISTS awx_jobs (
    task_id VARCHAR(36) PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    owner_hash VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    completed_at BIGINT,
    expires_at BIGINT,
    worker_token VARCHAR(64),
    lease_until BIGINT,
    result_ref VARCHAR(36),
    error_code VARCHAR(64),
    callback_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    callback_attempts INT NOT NULL DEFAULT 0,
    callback_next BIGINT NOT NULL DEFAULT 0,
    callback_token VARCHAR(64),
    callback_until BIGINT,
    INDEX awx_jobs_pending (state, job_type, created_at),
    INDEX awx_jobs_expiry (expires_at),
    INDEX awx_jobs_callback (callback_state, callback_next)
);
CREATE TABLE IF NOT EXISTS awx_job_results (
    result_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    body LONGTEXT NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,
    body_bytes BIGINT NOT NULL
);
