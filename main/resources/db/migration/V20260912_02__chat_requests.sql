-- Apply explicitly to the shared application database before enabling chat admission.
CREATE TABLE IF NOT EXISTS awx_chat_requests (
    key_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_hash VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    claim_token VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    completed_at BIGINT NULL,
    expires_at BIGINT NULL,
    INDEX idx_awx_chat_requests_expiry (expires_at)
);
