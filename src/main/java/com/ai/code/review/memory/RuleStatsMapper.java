package com.ai.code.review.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus Mapper for rule_stats table.
 */
@Mapper
public interface RuleStatsMapper extends BaseMapper<RuleStatsEntity> {

    /**
     * Upsert rule stats: insert or update on conflict (agent_type, rule_category).
     */
    @Update("""
            INSERT INTO rule_stats (agent_type, rule_category, total, accepted, dismissed, is_downgraded)
            VALUES (#{agentType}, #{ruleCategory}, 1,
                    CASE WHEN #{isAccepted} THEN 1 ELSE 0 END,
                    CASE WHEN #{isAccepted} THEN 0 ELSE 1 END,
                    false)
            ON CONFLICT (agent_type, rule_category)
            DO UPDATE SET
                total = rule_stats.total + 1,
                accepted = CASE WHEN #{isAccepted} THEN rule_stats.accepted + 1 ELSE rule_stats.accepted END,
                dismissed = CASE WHEN #{isAccepted} THEN rule_stats.dismissed ELSE rule_stats.dismissed + 1 END
            """)
    void upsertStats(@Param("agentType") String agentType,
                     @Param("ruleCategory") String ruleCategory,
                     @Param("isAccepted") boolean isAccepted);

    /**
     * Mark a rule as downgraded.
     */
    @Update("UPDATE rule_stats SET is_downgraded = true WHERE agent_type = #{agentType} AND rule_category = #{ruleCategory}")
    void markDowngraded(@Param("agentType") String agentType, @Param("ruleCategory") String ruleCategory);
}
