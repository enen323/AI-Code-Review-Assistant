package com.ai.code.review.memory;

import com.ai.code.review.model.CodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing review feedback and rule statistics.
 *
 * Provides three-layer memory capabilities:
 * - Layer 1: Global rules (universal best practices, immutable)
 * - Layer 2: Team preferences (learned via feedback, dynamically adjusted)
 * - Layer 3: Project exceptions (specific file/module allowlists)
 *
 * Also implements false-positive decay: when a rule's dismiss ratio exceeds
 * the configured threshold, it is automatically marked as downgraded.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final double downgradeThreshold;

    public MemoryService(JdbcTemplate jdbcTemplate,
                         @Value("${memory.downgrade-threshold:0.7}") double downgradeThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.downgradeThreshold = downgradeThreshold;
    }

    /**
     * Stores a new feedback record and updates the corresponding rule stats.
     * If the feedback is DISMISSED, checks whether the rule should be downgraded
     * based on the dismiss ratio threshold.
     *
     * @param record the feedback record to store
     */
    public void storeFeedback(FeedbackRecord record) {
        log.debug("Storing feedback: {} on rule '{}' by agent '{}'",
                record.feedback(), record.ruleCategory(), record.agentType());

        // Insert the feedback record
        jdbcTemplate.update(
                "INSERT INTO review_feedback (pr_id, file_path, line_start, rule_category, agent_type, code_snippet, feedback, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.prId(),
                record.filePath(),
                record.lineStart(),
                record.ruleCategory(),
                record.agentType(),
                record.codeSnippet(),
                record.feedback().name(),
                record.createdAt()
        );

        // Upsert rule stats
        String upsertSql = """
                INSERT INTO rule_stats (agent_type, rule_category, total, accepted, dismissed, is_downgraded)
                VALUES (?, ?, 1, ?, ?, false)
                ON CONFLICT (agent_type, rule_category)
                DO UPDATE SET
                    total = rule_stats.total + 1,
                    accepted = CASE WHEN ? = 'ACCEPTED' THEN rule_stats.accepted + 1 ELSE rule_stats.accepted END,
                    dismissed = CASE WHEN ? = 'DISMISSED' THEN rule_stats.dismissed + 1 ELSE rule_stats.dismissed END
                """;

        jdbcTemplate.update(upsertSql,
                record.agentType(),
                record.ruleCategory(),
                record.feedback() == FeedbackType.ACCEPTED ? 1 : 0,
                record.feedback() == FeedbackType.DISMISSED ? 1 : 0,
                record.feedback().name(),
                record.feedback().name()
        );

        // Check for false-positive decay when feedback is DISMISSED
        if (record.feedback() == FeedbackType.DISMISSED) {
            checkAndApplyDowngrade(record.agentType(), record.ruleCategory());
        }

        log.info("Feedback stored successfully: {} / {} for rule '{}' by agent '{}'",
                record.feedback(), record.prId(), record.ruleCategory(), record.agentType());
    }

    /**
     * Gets statistics for all rules across all agent types.
     *
     * @return list of all rule stats
     */
    public List<RuleStats> getRuleStats() {
        String sql = "SELECT rule_category, agent_type, total, accepted, dismissed, is_downgraded "
                   + "FROM rule_stats ORDER BY agent_type, rule_category";

        return jdbcTemplate.query(sql, new RuleStatsRowMapper());
    }

    /**
     * Gets statistics for a specific rule identified by agent type and rule category.
     *
     * @param agentType    the agent type
     * @param ruleCategory the rule category
     * @return the rule stats, or null if not found
     */
    public RuleStats getRuleStats(String agentType, String ruleCategory) {
        String sql = "SELECT rule_category, agent_type, total, accepted, dismissed, is_downgraded "
                   + "FROM rule_stats WHERE agent_type = ? AND rule_category = ?";

        List<RuleStats> results = jdbcTemplate.query(sql, new RuleStatsRowMapper(), agentType, ruleCategory);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Gets recent feedback records for a specific code location.
     *
     * @param filePath  the file path
     * @param lineStart the starting line number
     * @return list of feedback records for this location
     */
    public List<FeedbackRecord> getRecentFeedback(String filePath, int lineStart) {
        String sql = "SELECT id, pr_id, file_path, line_start, rule_category, agent_type, code_snippet, feedback, created_at "
                   + "FROM review_feedback WHERE file_path = ? AND line_start = ? "
                   + "ORDER BY created_at DESC LIMIT 50";

        return jdbcTemplate.query(sql, new FeedbackRecordRowMapper(), filePath, lineStart);
    }

    /**
     * Checks whether a specific rule has been downgraded due to excessive false positives.
     *
     * @param agentType    the agent type
     * @param ruleCategory the rule category
     * @return true if the rule is downgraded, false otherwise
     */
    public boolean isRuleDowngraded(String agentType, String ruleCategory) {
        String sql = "SELECT is_downgraded FROM rule_stats WHERE agent_type = ? AND rule_category = ?";
        List<Boolean> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getBoolean("is_downgraded"),
                agentType, ruleCategory);
        return !results.isEmpty() && Boolean.TRUE.equals(results.get(0));
    }

    /**
     * Resets learning data for a specific rule, clearing its statistics and
     * downgrade status.
     *
     * @param agentType    the agent type
     * @param ruleCategory the rule category
     */
    public void resetRuleStats(String agentType, String ruleCategory) {
        log.info("Resetting rule stats for {}/{}", agentType, ruleCategory);
        jdbcTemplate.update("DELETE FROM rule_stats WHERE agent_type = ? AND rule_category = ?",
                agentType, ruleCategory);
    }

    /**
     * Generates memory hints for agents based on past feedback for similar
     * code patterns. These hints inform agents about rules that have been
     * historically dismissed or accepted in similar contexts.
     *
     * @param context the current code context
     * @return list of hint strings
     */
    public List<String> getMemoryHints(CodeContext context) {
        List<String> hints = new ArrayList<>();

        if (context == null || context.changedFiles() == null || context.changedFiles().isEmpty()) {
            return hints;
        }

        // Gather all downgraded rules to inform agents
        List<RuleStats> allStats = getRuleStats();
        for (RuleStats stats : allStats) {
            if (stats.isDowngraded()) {
                hints.add(String.format(
                        "CAUTION: Rule '%s' from agent '%s' has been downgraded (dismissed %.0f%% of %d times). "
                        + "Consider whether this rule applies before flagging it.",
                        stats.ruleCategory(), stats.agentType(),
                        stats.dismissRatio() * 100, stats.total()));
            }
        }

        // Check for historical feedback on files being reviewed
        for (var changedFile : context.changedFiles()) {
            String filePath = changedFile.filePath();
            if (filePath == null) {
                continue;
            }

            // Count feedback records for this file
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_feedback WHERE file_path = ?",
                    Integer.class, filePath);

            if (count != null && count > 0) {
                hints.add(String.format("File '%s' has %d past feedback record(s).", filePath, count));
            }
        }

        return hints;
    }

    /**
     * Checks whether a rule's dismiss ratio exceeds the threshold and marks
     * it as downgraded if so.
     */
    private void checkAndApplyDowngrade(String agentType, String ruleCategory) {
        RuleStats stats = getRuleStats(agentType, ruleCategory);
        if (stats == null || stats.total() == 0) {
            return;
        }

        double ratio = (double) stats.dismissed() / stats.total();
        boolean shouldDowngrade = ratio > downgradeThreshold;

        if (shouldDowngrade) {
            log.warn("Rule '{}/{}' exceeded dismiss threshold (ratio={}, threshold={}). Marking as downgraded.",
                    agentType, ruleCategory, String.format("%.2f", ratio), downgradeThreshold);

            jdbcTemplate.update(
                    "UPDATE rule_stats SET is_downgraded = true WHERE agent_type = ? AND rule_category = ?",
                    agentType, ruleCategory);
        }
    }

    /**
     * RowMapper for converting query results to RuleStats records.
     */
    private static class RuleStatsRowMapper implements RowMapper<RuleStats> {
        @Override
        public RuleStats mapRow(ResultSet rs, int rowNum) throws SQLException {
            int total = rs.getInt("total");
            int accepted = rs.getInt("accepted");
            int dismissed = rs.getInt("dismissed");
            boolean isDowngraded = rs.getBoolean("is_downgraded");
            return RuleStats.fromCounts(
                    rs.getString("rule_category"),
                    rs.getString("agent_type"),
                    total, accepted, dismissed, isDowngraded
            );
        }
    }

    /**
     * RowMapper for converting query results to FeedbackRecord records.
     */
    private static class FeedbackRecordRowMapper implements RowMapper<FeedbackRecord> {
        @Override
        public FeedbackRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FeedbackRecord(
                    rs.getLong("id"),
                    rs.getString("pr_id"),
                    rs.getString("file_path"),
                    rs.getInt("line_start"),
                    rs.getString("rule_category"),
                    rs.getString("agent_type"),
                    rs.getString("code_snippet"),
                    FeedbackType.valueOf(rs.getString("feedback")),
                    rs.getObject("created_at", LocalDateTime.class)
            );
        }
    }
}
