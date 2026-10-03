-- F01-B local/development proposal. Apply only with separate explicit DB approval.
-- Existing migrations are immutable. This file is never executed at job startup.
-- Job expiry must not remove receipts; there is deliberately no job FK/cascade.
ALTER TABLE awx_jobs ADD COLUMN effect_key VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN original_run_id VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN source_session_id BIGINT;
ALTER TABLE awx_jobs ADD COLUMN phase VARCHAR(16);
ALTER TABLE awx_jobs ADD COLUMN compute_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE awx_jobs ADD COLUMN commit_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE awx_jobs ADD COLUMN next_attempt_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX awx_jobs_source_run ON awx_jobs(job_type, owner_hash, source_session_id, original_run_id, state);
CREATE INDEX awx_jobs_derived_retry ON awx_jobs(job_type, state, phase, next_attempt_at);

CREATE TABLE awx_understanding_receipts (
    effect_key VARCHAR(64) PRIMARY KEY,
    owner_namespace VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    consent_epoch BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    user_revision BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    assistant_revision BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    original_run_id VARCHAR(64) NOT NULL,
    job_task_id VARCHAR(36) NOT NULL,
    usum_message_id BIGINT NOT NULL,
    result_sha256 VARCHAR(64) NOT NULL,
    persisted_at BIGINT NOT NULL,
    receipt_state VARCHAR(16) NOT NULL DEFAULT 'PERSISTED',
    CONSTRAINT awx_understanding_receipt_state CHECK (receipt_state IN ('PERSISTED', 'INVALIDATED')),
    INDEX awx_understanding_source (owner_namespace, session_id, user_message_id, assistant_message_id)
);

