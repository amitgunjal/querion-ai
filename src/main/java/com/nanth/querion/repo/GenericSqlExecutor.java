package com.nanth.querion.repo;

import com.nanth.querion.exceptions.SqlExecutionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GenericSqlExecutor {

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public GenericSqlExecutor(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Map<String, Object>> execute(String sql, Map<String, Object> params) {
    if (sql == null || sql.isBlank()) {
      throw new SqlExecutionException("Generated SQL was empty.");
    }

    if (!sql.trim().toLowerCase().startsWith("select")) {
      throw new SqlExecutionException("Only SELECT queries are allowed.");
    }

    if (params == null) {
      params = new HashMap<>();
    }

    return jdbcTemplate.queryForList(sql, params);
  }
}
