-- Create index for owner_key on session table (adjust table/column names to match your schema).
CREATE INDEX IF NOT EXISTS idx_session_owner_key ON session(owner_key);
