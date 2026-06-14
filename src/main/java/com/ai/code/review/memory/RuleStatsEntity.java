package com.ai.code.review.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus entity for rule_stats table.
 */
@TableName("rule_stats")
public class RuleStatsEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("agent_type")
    private String agentType;

    @TableField("rule_category")
    private String ruleCategory;

    @TableField
    private int total;

    @TableField
    private int accepted;

    @TableField
    private int dismissed;

    @TableField("is_downgraded")
    private boolean isDowngraded;

    public RuleStatsEntity() {}

    public RuleStatsEntity(String agentType, String ruleCategory) {
        this.agentType = agentType;
        this.ruleCategory = ruleCategory;
        this.total = 0;
        this.accepted = 0;
        this.dismissed = 0;
        this.isDowngraded = false;
    }

    public RuleStats toRuleStats() {
        return RuleStats.fromCounts(ruleCategory, agentType, total, accepted, dismissed, isDowngraded);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getRuleCategory() { return ruleCategory; }
    public void setRuleCategory(String ruleCategory) { this.ruleCategory = ruleCategory; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getAccepted() { return accepted; }
    public void setAccepted(int accepted) { this.accepted = accepted; }
    public int getDismissed() { return dismissed; }
    public void setDismissed(int dismissed) { this.dismissed = dismissed; }
    public boolean isDowngraded() { return isDowngraded; }
    public void setDowngraded(boolean downgraded) { isDowngraded = downgraded; }
}
