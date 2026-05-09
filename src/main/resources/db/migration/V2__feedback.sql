-- Feedback and rule statistics tables for the Memory & Feedback Module
-- Supports three-layer memory architecture (Phase 5)

-- Review feedback table: stores user feedback on review results
CREATE TABLE IF NOT EXISTS review_feedback (
    id              BIGSERIAL PRIMARY KEY,
    pr_id           VARCHAR(255) NOT NULL,
    file_path       VARCHAR(1024),
    line_start      INTEGER DEFAULT 0,
    rule_category   VARCHAR(255) NOT NULL,
    agent_type      VARCHAR(100) NOT NULL,
    code_snippet    TEXT,
    feedback        VARCHAR(20) NOT NULL,  -- 'ACCEPTED' or 'DISMISSED'
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Rule statistics table: tracks acceptance/dismissal rates per rule
CREATE TABLE IF NOT EXISTS rule_stats (
    id              BIGSERIAL PRIMARY KEY,
    agent_type      VARCHAR(100) NOT NULL,
    rule_category   VARCHAR(255) NOT NULL,
    total           INTEGER DEFAULT 0,
    accepted        INTEGER DEFAULT 0,
    dismissed       INTEGER DEFAULT 0,
    is_downgraded   BOOLEAN DEFAULT FALSE,
    UNIQUE(agent_type, rule_category)
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_feedback_rule ON review_feedback(agent_type, rule_category);
CREATE INDEX IF NOT EXISTS idx_feedback_location ON review_feedback(file_path, line_start);
CREATE INDEX IF NOT EXISTS idx_rule_stats_agent ON rule_stats(agent_type);
