package com.nanth.querion.dtos;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryHistoryResponseDto {

  private QueryHistorySummaryDto summary;
  private List<QueryHistoryItemDto> items;
}
