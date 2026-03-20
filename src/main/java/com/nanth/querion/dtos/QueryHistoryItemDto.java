package com.nanth.querion.dtos;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryHistoryItemDto {

  private String requestId;
  private String userQuery;
  private String queryType;
  private String executionStatus;
  private String finalAnswer;
  private String keyInsights;
  private String generatedSql;
  private Long resultRowCount;
  private Long executionTimeMs;
  private Long totalDurationMs;
  private Integer llmCallCount;
  private String errorMessage;
  private Double score;
  private Boolean userFeedback;
  private LocalDateTime createdAt;
}
