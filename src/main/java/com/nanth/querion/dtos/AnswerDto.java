package com.nanth.querion.dtos;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnswerDto {

  private String ans;
  private String status;
  private String requestId;
}
