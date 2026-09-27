-- Additive MySQL/MariaDB migration; tested only against an isolated H2 MySQL fixture.
-- Apply through the project's authorized migration procedure, never from job startup.
-- NULL preserves independent legacy submissions without an Idempotency-Key.
ALTER TABLE awx_jobs ADD COLUMN admission_key VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX awx_jobs_admission_key ON awx_jobs(admission_key);
