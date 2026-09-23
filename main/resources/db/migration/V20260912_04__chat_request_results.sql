-- Explicit shared-DB migration; automatic execution is not authorized by this task.
CREATE TABLE IF NOT EXISTS awx_chat_request_results (
    key_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    result_json LONGTEXT NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    CONSTRAINT fk_awx_chat_request_result FOREIGN KEY (key_hash)
        REFERENCES awx_chat_requests(key_hash) ON DELETE CASCADE
);
