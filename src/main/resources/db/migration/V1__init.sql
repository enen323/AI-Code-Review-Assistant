-- Enable PGVector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Review records table
CREATE TABLE IF NOT EXISTS review_records (
    id              BIGSERIAL PRIMARY KEY,
    pr_id           VARCHAR(255) NOT NULL,
    repo_name       VARCHAR(255) NOT NULL,
    pr_number       INTEGER NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    trigger_event   VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(pr_id)
);

-- Function to auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Review results table
CREATE TABLE IF NOT EXISTS review_results (
    id              BIGSERIAL PRIMARY KEY,
    review_id       BIGINT NOT NULL REFERENCES review_records(id) ON DELETE CASCADE,
    agent_type      VARCHAR(100) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    summary         TEXT,
    details         JSONB,
    severity        VARCHAR(50),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TRIGGER trg_review_records_updated_at
    BEFORE UPDATE ON review_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_review_results_updated_at
    BEFORE UPDATE ON review_results
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Indexes
CREATE INDEX IF NOT EXISTS idx_review_records_pr_id ON review_records(pr_id);
CREATE INDEX IF NOT EXISTS idx_review_results_review_id ON review_results(review_id);
CREATE INDEX IF NOT EXISTS idx_review_results_agent_type ON review_results(agent_type);
