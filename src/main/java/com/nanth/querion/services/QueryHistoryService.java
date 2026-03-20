package com.nanth.querion.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanth.querion.dtos.FeedbackDto;
import com.nanth.querion.dtos.QueryHistoryItemDto;
import com.nanth.querion.dtos.QueryHistoryResponseDto;
import com.nanth.querion.dtos.QueryHistorySummaryDto;
import com.nanth.querion.exceptions.InvalidQueryException;
import com.nanth.querion.models.QueryHistory;
import com.nanth.querion.repo.QueryHistoryRepo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);
    private static final int RESULT_PREVIEW_LIMIT = 20;
    private static final double INITIAL_CHAT_SCORE = 0.5;
    private static final double INITIAL_REUSED_SCORE = 1.0;
    private static final double INITIAL_TEMPLATE_REUSE_SCORE = 0.8;
    private static final double SUCCESSFUL_DATA_SCORE_DELTA = 1.5;
    private static final double POSITIVE_FEEDBACK_DELTA = 1.0;
    private static final double NEGATIVE_FEEDBACK_DELTA = -3.0;
    private static final double INVALID_SQL_DELTA = -2.0;

    @Autowired
    private QueryHistoryRepo queryHistoryRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryHistory newHistory(String requestId, String userQuery, String queryType, String modelName,
            String apiUrl) {
        QueryHistory queryHistory = new QueryHistory();
        queryHistory.setRequestId(requestId);
        queryHistory.setUserQuery(userQuery);
        queryHistory.setQueryType(queryType);
        queryHistory.setModelName(modelName);
        queryHistory.setApiUrl(apiUrl);
        queryHistory.setExecutionStatus("PENDING");
        queryHistory.setLlmCallCount(0);
        queryHistory.setScore(0.0);
        queryHistory.setFeedbackCount(0);
        queryHistory.setPositiveFeedbackCount(0);
        queryHistory.setNegativeFeedbackCount(0);
        LocalDateTime now = LocalDateTime.now();
        queryHistory.setCreatedAt(now);
        queryHistory.setUpdatedAt(now);
        return queryHistory;
    }

    public void saveGreeting(String requestId, String userQuery, String modelName, String apiUrl,
            String finalAnswer) {
        try {
            QueryHistory queryHistory = newHistory(requestId, userQuery, "CHAT", modelName, apiUrl);
            queryHistory.setExecutionStatus("SUCCESS");
            queryHistory.setFinalAnswer(finalAnswer);
            queryHistory.setScore(INITIAL_CHAT_SCORE);
            queryHistoryRepo.save(queryHistory);
        } catch (Exception e) {
            log.error("Failed to persist greeting query history for request {}", requestId, e);
        }
    }

    public QueryHistory saveReusedDataResponse(String requestId, String userQuery, String modelName,
            String apiUrl, QueryReuseMatch match) {
        QueryHistory source = match.history();
        QueryHistory queryHistory = newHistory(requestId, userQuery, "DATA", modelName, apiUrl);
        queryHistory.setExecutionStatus("SUCCESS");
        queryHistory.setGeneratedSql(source.getGeneratedSql());
        queryHistory.setGeneratedSqlParams(source.getGeneratedSqlParams());
        queryHistory.setResultRowCount(source.getResultRowCount());
        queryHistory.setResultPreviewJson(source.getResultPreviewJson());
        queryHistory.setFinalAnswer(source.getFinalAnswer());
        queryHistory.setKeyInsights(source.getKeyInsights());
        queryHistory.setScore(INITIAL_REUSED_SCORE);
        queryHistory.setPromptText("CACHE_HIT:" + match.strategy() + ":" + match.similarityScore());
        queryHistory.setLlmCallCount(0);
        save(queryHistory);
        return queryHistory;
    }

    public QueryHistory newTemplateReuseHistory(String requestId, String userQuery, String modelName,
            String apiUrl, QueryReuseMatch match) {
        QueryHistory source = match.history();
        QueryHistory queryHistory = newHistory(requestId, userQuery, "DATA", modelName, apiUrl);
        queryHistory.setGeneratedSql(source.getGeneratedSql());
        queryHistory.setScore(INITIAL_TEMPLATE_REUSE_SCORE);
        queryHistory.setPromptText("TEMPLATE_REUSE:" + match.strategy() + ":" + match.similarityScore());
        queryHistory.setLlmCallCount(0);
        return queryHistory;
    }

    public void save(QueryHistory queryHistory) {
        if (queryHistory == null) {
            return;
        }
        try {
            queryHistory.setUpdatedAt(LocalDateTime.now());
            queryHistoryRepo.save(queryHistory);
        } catch (Exception e) {
            log.error("Failed to persist query history for request {}", queryHistory.getRequestId(), e);
        }
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize query history payload", ex);
            return null;
        }
    }

    public String toPreviewJson(List<Map<String, Object>> result) {
        if (result == null) {
            return null;
        }
        int upperBound = Math.min(result.size(), RESULT_PREVIEW_LIMIT);
        return toJson(result.subList(0, upperBound));
    }

    public List<QueryHistory> findAll() {
        return this.queryHistoryRepo.findAll();
    }

    public FeedbackDto applyFeedback(String requestId, boolean correct) {
        QueryHistory history = queryHistoryRepo.findByRequestId(requestId)
                .orElseThrow(() -> new InvalidQueryException("Query history record was not found."));

        if (history.getUserFeedback() != null) {
            throw new InvalidQueryException("Feedback has already been submitted for this request.");
        }

        history.setFeedbackCount((history.getFeedbackCount() == null ? 0 : history.getFeedbackCount()) + 1);
        history.setUserFeedback(correct);

        if (correct) {
            history.setPositiveFeedbackCount(
                    (history.getPositiveFeedbackCount() == null ? 0 : history.getPositiveFeedbackCount()) + 1);
            history.setScore(adjustScore(history.getScore(), POSITIVE_FEEDBACK_DELTA));
        } else {
            history.setNegativeFeedbackCount(
                    (history.getNegativeFeedbackCount() == null ? 0 : history.getNegativeFeedbackCount()) + 1);
            history.setScore(adjustScore(history.getScore(), NEGATIVE_FEEDBACK_DELTA));
        }

        save(history);
        return FeedbackDto.builder()
                .requestId(history.getRequestId())
                .correct(correct)
                .score(history.getScore())
                .build();
    }

    public void markInvalidSql(QueryHistory history, String errorMessage) {
        if (history == null) {
            return;
        }
        history.setExecutionStatus("FAILED");
        history.setErrorMessage(errorMessage);
        history.setScore(adjustScore(history.getScore(), INVALID_SQL_DELTA));
        save(history);
    }

    public void markSuccessfulDataResponse(QueryHistory history) {
        if (history == null) {
            return;
        }
        history.setScore(adjustScore(history.getScore(), SUCCESSFUL_DATA_SCORE_DELTA));
    }

    public QueryHistoryResponseDto fetchRecentHistory() {
        List<QueryHistory> recentItems = queryHistoryRepo.findTop10ByOrderByCreatedAtDesc();
        List<QueryHistory> allDataItems = queryHistoryRepo.findAll().stream()
                .filter(item -> "DATA".equals(item.getQueryType()))
                .filter(item -> "SUCCESS".equals(item.getExecutionStatus()))
                .filter(item -> item.getLlmCallCount() != null && item.getLlmCallCount() > 0)
                .toList();
        LocalDateTime latestQueryAt = queryHistoryRepo.findTopByQueryTypeOrderByCreatedAtDesc("DATA")
                .map(QueryHistory::getCreatedAt)
                .orElse(null);
        long totalQueries = queryHistoryRepo.countByQueryType("DATA");
        long successCount = queryHistoryRepo.countByQueryTypeAndExecutionStatus("DATA", "SUCCESS");
        long failedCount = queryHistoryRepo.countByQueryTypeAndExecutionStatus("DATA", "FAILED");
        double successRate = totalQueries == 0 ? 0.0 : (successCount * 100.0) / totalQueries;
        Long averageResponseTimeMs = calculateAverageResponseTime(allDataItems);

        QueryHistorySummaryDto summary = QueryHistorySummaryDto.builder()
                .totalQueries(totalQueries)
                .successCount(successCount)
                .failedCount(failedCount)
                .successRate(successRate)
                .averageResponseTimeMs(averageResponseTimeMs)
                .latestQueryAt(latestQueryAt)
                .build();

        List<QueryHistoryItemDto> items = recentItems.stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        return QueryHistoryResponseDto.builder()
                .summary(summary)
                .items(items)
                .build();
    }

    private Long calculateAverageResponseTime(List<QueryHistory> items) {
        List<Long> durations = items.stream()
                .map(this::resolveDurationMs)
                .filter(Objects::nonNull)
                .toList();

        if (durations.isEmpty()) {
            return null;
        }

        long total = durations.stream().mapToLong(Long::longValue).sum();
        return total / durations.size();
    }

    private Long resolveDurationMs(QueryHistory item) {
        if (item == null) {
            return null;
        }

        if (item.getTotalDurationMs() != null) {
            return normalizeToMillis(item.getTotalDurationMs());
        }

        if (item.getExecutionTimeMs() != null) {
            return normalizeToMillis(item.getExecutionTimeMs());
        }

        return null;
    }

    private Long normalizeToMillis(Long rawDuration) {
        if (rawDuration == null) {
            return null;
        }

        if (rawDuration > 1_000_000) {
            return rawDuration / 1_000_000;
        }

        return rawDuration;
    }

    private QueryHistoryItemDto toDto(QueryHistory item) {
        return QueryHistoryItemDto.builder()
                .requestId(item.getRequestId())
                .userQuery(item.getUserQuery())
                .queryType(item.getQueryType())
                .executionStatus(item.getExecutionStatus())
                .finalAnswer(item.getFinalAnswer())
                .keyInsights(item.getKeyInsights())
                .generatedSql(item.getGeneratedSql())
                .resultRowCount(item.getResultRowCount())
                .executionTimeMs(item.getExecutionTimeMs())
                .totalDurationMs(item.getTotalDurationMs())
                .llmCallCount(item.getLlmCallCount())
                .errorMessage(item.getErrorMessage())
                .score(item.getScore())
                .userFeedback(item.getUserFeedback())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private double adjustScore(Double currentScore, double delta) {
        double nextScore = (currentScore == null ? 0.0 : currentScore) + delta;
        if (nextScore > 10.0) {
            return 10.0;
        }
        return Math.max(nextScore, -10.0);
    }
}
