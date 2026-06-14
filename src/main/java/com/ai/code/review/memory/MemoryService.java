package com.ai.code.review.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ai.code.review.model.CodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final FeedbackRecordMapper feedbackRecordMapper;
    private final RuleStatsMapper ruleStatsMapper;
    private final double downgradeThreshold;

    public MemoryService(FeedbackRecordMapper feedbackRecordMapper,
                         RuleStatsMapper ruleStatsMapper,
                         @Value("${memory.downgrade-threshold:0.7}") double downgradeThreshold) {
        this.feedbackRecordMapper = feedbackRecordMapper;
        this.ruleStatsMapper = ruleStatsMapper;
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
        FeedbackRecordEntity entity = new FeedbackRecordEntity(record);
        feedbackRecordMapper.insert(entity);

        // Upsert rule stats
        boolean isAccepted = record.feedback() == FeedbackType.ACCEPTED;
        ruleStatsMapper.upsertStats(record.agentType(), record.ruleCategory(), isAccepted);

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
        List<RuleStatsEntity> entities = ruleStatsMapper.selectList(null);
        return entities.stream().map(RuleStatsEntity::toRuleStats).toList();
    }

    /**
     * Gets statistics for a specific rule identified by agent type and rule category.
     *
     * @param agentType    the agent type
     * @param ruleCategory the rule category
     * @return the rule stats, or null if not found
     */
    public RuleStats getRuleStats(String agentType, String ruleCategory) {
        LambdaQueryWrapper<RuleStatsEntity> wrapper = new LambdaQueryWrapper<RuleStatsEntity>()
                .eq(RuleStatsEntity::getAgentType, agentType)
                .eq(RuleStatsEntity::getRuleCategory, ruleCategory);
        RuleStatsEntity entity = ruleStatsMapper.selectOne(wrapper);
        return entity != null ? entity.toRuleStats() : null;
    }

    /**
     * Gets recent feedback records for a specific code location.
     *
     * @param filePath  the file path
     * @param lineStart the starting line number
     * @return list of feedback records for this location
     */
    public List<FeedbackRecord> getRecentFeedback(String filePath, int lineStart) {
        LambdaQueryWrapper<FeedbackRecordEntity> wrapper = new LambdaQueryWrapper<FeedbackRecordEntity>()
                .eq(FeedbackRecordEntity::getFilePath, filePath)
                .eq(FeedbackRecordEntity::getLineStart, String.valueOf(lineStart))
                .orderByDesc(FeedbackRecordEntity::getCreatedAt)
                .last("LIMIT 50");

        List<FeedbackRecordEntity> entities = feedbackRecordMapper.selectList(wrapper);
        return entities.stream().map(FeedbackRecordEntity::toRecord).toList();
    }

    /**
     * Checks whether a specific rule has been downgraded due to excessive false positives.
     *
     * @param agentType    the agent type
     * @param ruleCategory the rule category
     * @return true if the rule is downgraded, false otherwise
     */
    public boolean isRuleDowngraded(String agentType, String ruleCategory) {
        LambdaQueryWrapper<RuleStatsEntity> wrapper = new LambdaQueryWrapper<RuleStatsEntity>()
                .eq(RuleStatsEntity::getAgentType, agentType)
                .eq(RuleStatsEntity::getRuleCategory, ruleCategory)
                .eq(RuleStatsEntity::isDowngraded, true);
        return ruleStatsMapper.selectCount(wrapper) > 0;
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
        LambdaQueryWrapper<RuleStatsEntity> wrapper = new LambdaQueryWrapper<RuleStatsEntity>()
                .eq(RuleStatsEntity::getAgentType, agentType)
                .eq(RuleStatsEntity::getRuleCategory, ruleCategory);
        ruleStatsMapper.delete(wrapper);
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
            LambdaQueryWrapper<FeedbackRecordEntity> countWrapper = new LambdaQueryWrapper<FeedbackRecordEntity>()
                    .eq(FeedbackRecordEntity::getFilePath, filePath);
            Long count = feedbackRecordMapper.selectCount(countWrapper);

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

            ruleStatsMapper.markDowngraded(agentType, ruleCategory);
        }
    }
}
