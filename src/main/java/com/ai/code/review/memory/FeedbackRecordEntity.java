package com.ai.code.review.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus entity for review_feedback table.
 */
@TableName("review_feedback")
public class FeedbackRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("pr_id")
    private String prId;

    @TableField("file_path")
    private String filePath;

    @TableField("line_start")
    private int lineStart;

    @TableField("rule_category")
    private String ruleCategory;

    @TableField("agent_type")
    private String agentType;

    @TableField("code_snippet")
    private String codeSnippet;

    @TableField
    private String feedback;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public FeedbackRecordEntity() {}

    public FeedbackRecordEntity(FeedbackRecord record) {
        this.prId = record.prId();
        this.filePath = record.filePath();
        this.lineStart = record.lineStart();
        this.ruleCategory = record.ruleCategory();
        this.agentType = record.agentType();
        this.codeSnippet = record.codeSnippet();
        this.feedback = record.feedback().name();
        this.createdAt = record.createdAt();
    }

    public FeedbackRecord toRecord() {
        return new FeedbackRecord(
                id, prId, filePath, lineStart, ruleCategory, agentType,
                codeSnippet, FeedbackType.valueOf(feedback), createdAt
        );
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPrId() { return prId; }
    public void setPrId(String prId) { this.prId = prId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }
    public String getRuleCategory() { return ruleCategory; }
    public void setRuleCategory(String ruleCategory) { this.ruleCategory = ruleCategory; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getCodeSnippet() { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
