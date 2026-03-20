package com.nanth.querion.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanth.querion.dtos.SqlQuery;
import com.nanth.querion.exceptions.InvalidQueryException;
import java.util.Locale;

public class CommonUtils {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static SqlQuery toSqlQuery(String json) {
    if (json == null || json.isBlank()) {
      throw new InvalidQueryException("Generated SQL payload was empty.");
    }
    try {
      SqlQuery sqlQuery = MAPPER.readValue(extractJsonObject(json), SqlQuery.class);
      if (sqlQuery.getSql() == null || sqlQuery.getSql().isBlank()) {
        throw new InvalidQueryException("Generated SQL payload did not contain a valid SQL statement.");
      }
      return sqlQuery;
    } catch (InvalidQueryException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new InvalidQueryException("Generated SQL payload was not valid JSON.");
    }
  }

  private static String extractJsonObject(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed;
    }

    int start = trimmed.indexOf('{');
    int end = trimmed.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return trimmed.substring(start, end + 1);
    }

    return trimmed;
  }

  public static void validateGeneratedSql(String sql) {
    if (sql == null || sql.isBlank()) {
      throw new InvalidQueryException("Generated SQL statement was empty.");
    }

    String normalized = sql.trim().toLowerCase(Locale.ROOT);
    if (!normalized.startsWith("select")) {
      throw new InvalidQueryException("Generated SQL must start with SELECT.");
    }

    String compact = normalized.replaceAll("\\s+", " ");
    String[] blocked = {" insert ", " update ", " delete ", " drop ", " alter ", " create ",
        " truncate ", " grant ", " revoke "};
    for (String token : blocked) {
      if (compact.contains(token)) {
        throw new InvalidQueryException("Generated SQL contained an unsafe operation.");
      }
    }
  }
}
