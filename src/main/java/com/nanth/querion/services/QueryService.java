package com.nanth.querion.services;

import com.nanth.querion.dtos.AnswerDto;
import com.nanth.querion.engine.ClassifierBrain;
import com.nanth.querion.engine.LlammaEngine;
import com.nanth.querion.exceptions.InvalidQueryException;
import com.nanth.querion.models.QueryHistory;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);

  @Autowired
  LlammaEngine llammaService;
  @Autowired
  GreetingService greetingService;
  @Autowired
  ClassifierBrain qcService;
  @Autowired
  QueryHistoryService queryHistoryService;
  @Autowired
  QueryReuseService queryReuseService;

  public AnswerDto ask(String q) {
    return ask(q, update -> {
    });
  }

  public AnswerDto ask(String q, Consumer<Map<String, String>> updateConsumer) {
    log.info("Step 2/3 - Processing ask query");
    if (q == null || q.isBlank()) {
      log.warn("Step 2/3 - Query processing stopped because query text was blank");
      throw new InvalidQueryException("Query text must not be blank.");
    }

    emit(updateConsumer, "status", "classification", "Understanding your question.");
    boolean isDataQuery = qcService.isDataQuery(q);
    log.info("Step 2/3 - Query classified as data query: {}", isDataQuery);

    if (!isDataQuery) {
      log.info("Step 2/3 - Returning greeting response for conversational query");
      emit(updateConsumer, "status", "chat", "Preparing a conversational reply.");
      String requestId = UUID.randomUUID().toString();
      String answer = greetingService.chatReply(q);
      queryHistoryService.saveGreeting(requestId, q, llammaService.getModelName(),
          llammaService.getApiUrl(), answer);
      return AnswerDto.builder()
          .ans(answer)
          .status("OK")
          .requestId(requestId)
          .build();
    }

    String requestId = UUID.randomUUID().toString();
    emit(updateConsumer, "status", "reuse", "Checking for a reusable result.");
    Optional<QueryReuseMatch> reuseMatch = queryReuseService.findReusableQuery(q);
    if (reuseMatch.isPresent()) {
      log.info("Step 2/3 - Reusing successful DATA query via {} match with score {}",
          reuseMatch.get().strategy(), reuseMatch.get().similarityScore());
      if ("template".equals(reuseMatch.get().strategy())) {
        emit(updateConsumer, "status", "template-reuse",
            "Reusing the SQL template with fresh parameters.");
        QueryHistory reusedHistory = llammaService.queryFromTemplateReuse(
            requestId,
            q,
            reuseMatch.get(),
            chunk -> emit(updateConsumer, "chunk", "answer-stream", chunk),
            updateConsumer
        );
        return AnswerDto.builder()
            .ans(reusedHistory.getFinalAnswer())
            .status("OK")
            .requestId(reusedHistory.getRequestId())
            .build();
      }
      emit(updateConsumer, "status", "reuse-hit",
          "Found a reusable answer from recent successful queries.");
      QueryHistory reusedHistory = queryHistoryService.saveReusedDataResponse(
          requestId,
          q,
          llammaService.getModelName(),
          llammaService.getApiUrl(),
          reuseMatch.get()
      );
      return AnswerDto.builder()
          .ans(reusedHistory.getFinalAnswer())
          .status("OK")
          .requestId(reusedHistory.getRequestId())
          .build();
    }

    log.info("Step 2/3 - Forwarding query to LLM engine");
    emit(updateConsumer, "status", "llm", "Generating SQL and preparing the final answer.");
    QueryHistory history = llammaService.query(requestId, q,
        chunk -> emit(updateConsumer, "chunk", "answer-stream", chunk),
        updateConsumer);

    log.info("Step 2/3 - LLM response received successfully");
    return AnswerDto.builder()
        .ans(history.getFinalAnswer())
        .status("OK")
        .requestId(history.getRequestId())
        .build();
  }

  private void emit(Consumer<Map<String, String>> updateConsumer, String event, String phase,
      String message) {
    if (updateConsumer == null) {
      return;
    }

    updateConsumer.accept(Map.of(
        "event", event,
        "phase", phase,
        "message", message
    ));
  }

}
