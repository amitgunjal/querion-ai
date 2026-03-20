package com.nanth.querion.dtos;

import java.util.Map;
import lombok.Data;

@Data
public class SqlQuery {

  private String sql;
  private Map<String,Object> params;

  // getters setters
}
