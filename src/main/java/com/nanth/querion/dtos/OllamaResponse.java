package com.nanth.querion.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaResponse {

  private String model;
  private String created_at;
  private String response;
  private boolean done;
  private String done_reason;

  private List<Integer> context;

  private Long total_duration;
  private Long load_duration;

  private Integer prompt_eval_count;
  private Long prompt_eval_duration;

  private Integer eval_count;
  private Long eval_duration;
  private Long apiResponseTimeMs;
  private List<Map<String, Object>> result;
  private SqlQuery genSql;
  private String ans;
}
