package com.nanth.querion.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "query_history")
public class QueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;

    @Column(name = "user_query", columnDefinition = "TEXT", nullable = false)
    private String userQuery;

    @Column(name = "query_type", length = 32, nullable = false)
    private String queryType;

    @Column(name = "generated_sql", columnDefinition = "TEXT")
    private String generatedSql;

    @Column(name = "generated_sql_params", columnDefinition = "TEXT")
    private String generatedSqlParams;

    @Column(name = "execution_status")
    private String executionStatus;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "load_duration_ms")
    private Long loadDurationMs;

    @Column(name = "prompt_eval_duration_ms")
    private Long promptEvalDurationMs;

    @Column(name = "eval_duration_ms")
    private Long evalDurationMs;

    @Column(name = "result_row_count")
    private Long resultRowCount;

    @Column(name = "result_preview_json", columnDefinition = "TEXT")
    private String resultPreviewJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "api_url")
    private String apiUrl;

    @Column(name = "prompt_text", columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "llm_response_text", columnDefinition = "TEXT")
    private String llmResponseText;

    @Column(name = "final_answer_prompt", columnDefinition = "TEXT")
    private String finalAnswerPrompt;

    @Column(name = "final_answer_response_text", columnDefinition = "TEXT")
    private String finalAnswerResponseText;

    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "key_insights", columnDefinition = "TEXT")
    private String keyInsights;

    @Column(name = "llm_call_count")
    private Integer llmCallCount;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "response_tokens")
    private Integer responseTokens;

    @Column(name = "score")
    private Double score;

    @Column(name = "feedback_count")
    private Integer feedbackCount;

    @Column(name = "positive_feedback_count")
    private Integer positiveFeedbackCount;

    @Column(name = "negative_feedback_count")
    private Integer negativeFeedbackCount;

    @Column(name = "user_feedback")
    private Boolean userFeedback;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}
