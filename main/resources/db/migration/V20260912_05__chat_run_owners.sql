-- Opt-in ordinary chat routing metadata only. Apply explicitly to the shared DB.
-- No prompts, responses, credentials, subscriber identifiers or assist data.
CREATE TABLE IF NOT EXISTS awx_chat_run_slots (
    session_id BIGINT NOT NULL PRIMARY KEY,
    run_token VARCHAR(36) NULL,
    state VARCHAR(16) NOT NULL
);
CREATE TABLE IF NOT EXISTS awx_chat_run_owners (
    run_token VARCHAR(36) NOT NULL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    boot_id VARCHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    replay_until TIMESTAMP(3) NULL
);
CREATE INDEX idx_awx_chat_run_owner_expiry ON awx_chat_run_owners(state, replay_until);
