package com.nanth.querion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanth.querion.dtos.OllamaResponse;
import com.nanth.querion.dtos.SqlQuery;
import com.nanth.querion.exceptions.ExternalServiceException;
import com.nanth.querion.exceptions.InvalidQueryException;
import com.nanth.querion.exceptions.SqlExecutionException;
import com.nanth.querion.models.Llamma;
import com.nanth.querion.models.QueryHistory;
import com.nanth.querion.repo.GenericSqlExecutor;
import com.nanth.querion.services.ApiService;
import com.nanth.querion.services.QueryHistoryService;
import com.nanth.querion.services.QueryReuseMatch;
import com.nanth.querion.services.SchemaService;
import com.nanth.querion.utils.CommonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class LlammaEngine {

  private static final Logger log = LoggerFactory.getLogger(LlammaEngine.class);
  private static final String DATA_QUERY_PROMPT = "classpath:prompts/data-query-prompt.txt";
  private static final String INSIGHTS_QUERY_PROMPT = "classpath:prompts/insights-query-prompt.txt";
  private static final String ANSWER_PROMPT = "classpath:prompts/answer-prompt.txt";
  private static final String INSIGHTS_ANSWER_PROMPT = "classpath:prompts/insights-answer-prompt.txt";

  @Value("${ollama.api_url}")
  private String olApi;
  @Value("${ollama.model}")
  private String olModel;
  @Value("${ollama.stream}")
  private Boolean olStream;

  @Autowired
  private ApiService apiService;

  @Autowired
  SchemaService schemaService;

  @Autowired
  QueryHistoryService queryHistoryService;
  @Autowired
  GenericSqlExecutor executor;
  @Autowired
  private ResourceLoader resourceLoader;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public Llamma getLlamma() {
    return Llamma.builder()
        .model(olModel)
        .apiUrl(olApi)
        .stream(olStream)
        .build();
  }

  public String getModelName() {
    return olModel;
  }

  public String getApiUrl() {
    return olApi;
  }

  public QueryHistory query(String requestId, String q) {
    return query(requestId, q, null, null);
  }

  public QueryHistory query(String requestId, String q, Consumer<String> answerChunkConsumer) {
    return query(requestId, q, answerChunkConsumer, null);
  }

  public QueryHistory query(String requestId, String q, Consumer<String> answerChunkConsumer,
      Consumer<Map<String, String>> eventConsumer) {
    QueryHistory history = queryHistoryService.newHistory(requestId, q, "DATA", olModel, olApi);
    try {
      String schema = schemaService.getSchema();
      boolean insightsQuery = isInsightsQuery(q);
      String prompt = buildPrompt(schema, q, insightsQuery);
      history.setPromptText(prompt);

      OllamaResponse ollamaResponse = apiService.query(buildLlamma(q, prompt));
      if (ollamaResponse == null || ollamaResponse.getResponse() == null) {
        throw new ExternalServiceException("LLM returned an empty response.");
      }
      emitLlmMetric(eventConsumer, "sql-generation", ollamaResponse);
      history.setLlmResponseText(ollamaResponse.getResponse());
      applyMetrics(history, ollamaResponse);

      SqlQuery sqlQuery = CommonUtils.toSqlQuery(ollamaResponse.getResponse());
      CommonUtils.validateGeneratedSql(sqlQuery.getSql());
      history.setGeneratedSql(sqlQuery.getSql());
      history.setGeneratedSqlParams(queryHistoryService.toJson(sqlQuery.getParams()));

      List<Map<String, Object>> result =
          runQuery(
              sqlQuery.getSql(),
              sqlQuery.getParams()
          );
      history.setResultRowCount((long) result.size());
      history.setResultPreviewJson(queryHistoryService.toPreviewJson(result));

      String answerPrompt = insightsQuery
          ? buildInsightsPrompt(schema, q, sqlQuery.getSql(), sqlQuery.getParams(), result)
          : buildResponsePrompt(schema, q, sqlQuery.getSql(), sqlQuery.getParams(), result);
      history.setFinalAnswerPrompt(answerPrompt);

      OllamaResponse ans = buildAnswer(answerPrompt, answerChunkConsumer, eventConsumer, "final-answer");
      if (ans == null || ans.getResponse() == null || ans.getResponse().isBlank()) {
        throw new ExternalServiceException("LLM failed to generate the final answer.");
      }
      history.setFinalAnswerResponseText(ans.getResponse());
      history.setFinalAnswer(ans.getResponse());
      history.setKeyInsights(insightsQuery ? ans.getResponse() : null);
      applyMetrics(history, ans);

      if (!insightsQuery) {
        history.setKeyInsights(null);
      }

      queryHistoryService.markSuccessfulDataResponse(history);
      history.setExecutionStatus("SUCCESS");
      history.setErrorMessage(null);
      queryHistoryService.save(history);
      return history;
    } catch (InvalidQueryException e) {
      queryHistoryService.markInvalidSql(history, e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage("Database query execution failed.");
      queryHistoryService.save(history);
      throw new SqlExecutionException("Database query execution failed.", e);
    } catch (ExternalServiceException | SqlExecutionException e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage(e.getMessage());
      queryHistoryService.save(history);
      throw e;
    } catch (Exception e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage("Failed to process the user query.");
      history.setScore(history.getScore() == null ? -1.0 : history.getScore() - 1.0);
      queryHistoryService.save(history);
      log.error("Failed to process user query: {}", q, e);
      throw new ExternalServiceException("Failed to process the user query.", e);
    }
  }

  public QueryHistory queryFromTemplateReuse(String requestId, String q, QueryReuseMatch reuseMatch,
      Consumer<String> answerChunkConsumer, Consumer<Map<String, String>> eventConsumer) {
    QueryHistory history = queryHistoryService.newTemplateReuseHistory(
        requestId, q, olModel, olApi, reuseMatch);
    try {
      QueryHistory source = reuseMatch.history();
      Map<String, Object> params = reuseMatch.params();
      String sql = source.getGeneratedSql();
      List<Map<String, Object>> result = runQuery(sql, params);
      history.setGeneratedSql(sql);
      history.setGeneratedSqlParams(queryHistoryService.toJson(params));
      history.setResultRowCount((long) result.size());
      history.setResultPreviewJson(queryHistoryService.toPreviewJson(result));

      String schema = schemaService.getSchema();
      boolean insightsQuery = source.getKeyInsights() != null && !source.getKeyInsights().isBlank();
      String answerPrompt = insightsQuery
          ? buildInsightsPrompt(schema, q, sql, params, result)
          : buildResponsePrompt(schema, q, sql, params, result);
      history.setFinalAnswerPrompt(answerPrompt);

      OllamaResponse ans = buildAnswer(answerPrompt, answerChunkConsumer, eventConsumer, "template-answer");
      if (ans == null || ans.getResponse() == null || ans.getResponse().isBlank()) {
        throw new ExternalServiceException("LLM failed to generate the final answer.");
      }

      history.setFinalAnswerResponseText(ans.getResponse());
      history.setFinalAnswer(ans.getResponse());
      history.setKeyInsights(insightsQuery ? ans.getResponse() : null);
      applyMetrics(history, ans);
      queryHistoryService.markSuccessfulDataResponse(history);
      history.setExecutionStatus("SUCCESS");
      history.setErrorMessage(null);
      queryHistoryService.save(history);
      return history;
    } catch (InvalidQueryException e) {
      queryHistoryService.markInvalidSql(history, e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage("Database query execution failed.");
      queryHistoryService.save(history);
      throw new SqlExecutionException("Database query execution failed.", e);
    } catch (ExternalServiceException | SqlExecutionException e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage(e.getMessage());
      queryHistoryService.save(history);
      throw e;
    } catch (Exception e) {
      history.setExecutionStatus("FAILED");
      history.setErrorMessage("Failed to process the user query.");
      history.setScore(history.getScore() == null ? -1.0 : history.getScore() - 1.0);
      queryHistoryService.save(history);
      throw new ExternalServiceException("Failed to process the user query.", e);
    }
  }

  private Llamma buildLlamma(String userQuery, String prompt) {
    return buildLlamma(userQuery, prompt, olStream);
  }

  private Llamma buildLlamma(String userQuery, String prompt, boolean stream) {
    return Llamma.builder()
        .userQuery(userQuery)
        .model(olModel)
        .prompt(prompt)
        .apiUrl(olApi)
        .stream(stream)
        .build();
  }

  private void applyMetrics(QueryHistory history, OllamaResponse response) {
    history.setLlmCallCount(
        (history.getLlmCallCount() == null ? 0 : history.getLlmCallCount()) + 1);
    history.setExecutionTimeMs(sum(history.getExecutionTimeMs(), response.getApiResponseTimeMs()));
    history.setTotalDurationMs(sum(history.getTotalDurationMs(), response.getTotal_duration()));
    history.setLoadDurationMs(sum(history.getLoadDurationMs(), response.getLoad_duration()));
    history.setPromptEvalDurationMs(
        sum(history.getPromptEvalDurationMs(), response.getPrompt_eval_duration()));
    history.setEvalDurationMs(sum(history.getEvalDurationMs(), response.getEval_duration()));
    history.setPromptTokens(sum(history.getPromptTokens(), response.getPrompt_eval_count()));
    history.setResponseTokens(sum(history.getResponseTokens(), response.getEval_count()));
  }

  private Long sum(Long current, Long increment) {
    if (increment == null) {
      return current;
    }
    return current == null ? increment : current + increment;
  }

  private Integer sum(Integer current, Integer increment) {
    if (increment == null) {
      return current;
    }
    return current == null ? increment : current + increment;
  }

  private boolean isInsightsQuery(String question) {
    if (question == null) {
      return false;
    }

    String normalized = question.toLowerCase();
    return normalized.contains("insight") || normalized.contains("insights");
  }

  public List<Map<String, Object>> runQuery(String sql, Map<String, Object> params) {
    return executor.execute(sql, params);
  }

  public String buildResponsePrompt(String schema, String userQuery, String sql,
      Map<String, Object> params, List<Map<String, Object>> result) {
    try {
      String resultJson = objectMapper.writeValueAsString(result);
      String paramsJson = objectMapper.writeValueAsString(params);
      return loadTemplate(ANSWER_PROMPT)
          .replace("{{schema}}", schema)
          .replace("{{userQuery}}", userQuery)
          .replace("{{sql}}", sql)
          .replace("{{paramsJson}}", paramsJson)
          .replace("{{resultJson}}", resultJson);
    } catch (Exception ex) {
      throw new ExternalServiceException("Failed to build the answer prompt.", ex);
    }
  }

  public String buildInsightsPrompt(String schema, String userQuery, String sql,
      Map<String, Object> params, List<Map<String, Object>> result) {
    try {
      String resultJson = objectMapper.writeValueAsString(result);
      String paramsJson = objectMapper.writeValueAsString(params);
      return loadTemplate(INSIGHTS_ANSWER_PROMPT)
          .replace("{{schema}}", schema)
          .replace("{{userQuery}}", userQuery)
          .replace("{{sql}}", sql)
          .replace("{{paramsJson}}", paramsJson)
          .replace("{{resultJson}}", resultJson);
    } catch (Exception ex) {
      throw new ExternalServiceException("Failed to build the key insights prompt.", ex);
    }
  }

  public String buildPrompt(String schema, String question, boolean insightsQuery) {
    String template = loadTemplate(insightsQuery ? INSIGHTS_QUERY_PROMPT : DATA_QUERY_PROMPT);
    return template
        .replace("{{schema}}", schema)
        .replace("{{question}}", question);
  }

  private String loadTemplate(String location) {
    Resource resource = resourceLoader.getResource(location);
    try (InputStream inputStream = resource.getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new ExternalServiceException("Failed to load prompt template: " + location, ex);
    }
  }

  public OllamaResponse buildAnswer(String prompt) {
    return buildAnswer(prompt, null, null, "final-answer");
  }

  public OllamaResponse buildAnswer(String prompt, Consumer<String> answerChunkConsumer) {
    return buildAnswer(prompt, answerChunkConsumer, null, "final-answer");
  }

  public OllamaResponse buildAnswer(String prompt, Consumer<String> answerChunkConsumer,
      Consumer<Map<String, String>> eventConsumer, String stageName) {
    OllamaResponse ollamaResponse =
        apiService.query(buildLlamma(prompt, prompt, olStream), answerChunkConsumer);
    if (ollamaResponse == null || ollamaResponse.getResponse() == null) {
      throw new ExternalServiceException("LLM returned an empty answer response.");
    }
    emitLlmMetric(eventConsumer, stageName, ollamaResponse);
    return ollamaResponse;
  }

  private void emitLlmMetric(Consumer<Map<String, String>> eventConsumer, String stageName,
      OllamaResponse response) {
    if (response == null) {
      return;
    }

    Long apiMs = response.getApiResponseTimeMs();
    Integer promptTokens = response.getPrompt_eval_count();
    Integer responseTokens = response.getEval_count();

    log.info("LLM stage {} completed in {} ms using model {} (promptTokens={}, responseTokens={})",
        stageName,
        apiMs,
        olModel,
        promptTokens,
        responseTokens);

    if (eventConsumer == null) {
      return;
    }

    eventConsumer.accept(Map.of(
        "event", "metric",
        "phase", "llm-metric",
        "stage", stageName,
        "model", olModel == null ? "" : olModel,
        "apiResponseTimeMs", apiMs == null ? "" : String.valueOf(apiMs),
        "promptTokens", promptTokens == null ? "" : String.valueOf(promptTokens),
        "responseTokens", responseTokens == null ? "" : String.valueOf(responseTokens),
        "message", buildMetricMessage(stageName, apiMs, promptTokens, responseTokens)
    ));
  }

  private String buildMetricMessage(String stageName, Long apiMs, Integer promptTokens,
      Integer responseTokens) {
    StringBuilder message = new StringBuilder("LLM ");
    message.append(stageName).append(" finished");
    if (apiMs != null) {
      message.append(" in ").append(apiMs).append(" ms");
    }
    if (promptTokens != null || responseTokens != null) {
      message.append(" (promptTokens=")
          .append(promptTokens == null ? "-" : promptTokens)
          .append(", responseTokens=")
          .append(responseTokens == null ? "-" : responseTokens)
          .append(")");
    }
    return message.toString();
  }
}
