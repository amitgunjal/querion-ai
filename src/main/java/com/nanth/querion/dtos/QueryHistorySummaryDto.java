package com.nanth.querion.dtos;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryHistorySummaryDto {

  private long totalQueries;
  private long successCount;
  private long failedCount;
  private double successRate;
  private Long averageResponseTimeMs;
  private LocalDateTime latestQueryAt;
}
