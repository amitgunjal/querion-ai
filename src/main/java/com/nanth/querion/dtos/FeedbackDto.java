package com.nanth.querion.dtos;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeedbackDto {

  private String requestId;
  private boolean correct;
  private Double score;
}
