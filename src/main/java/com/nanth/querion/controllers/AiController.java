package com.nanth.querion.controllers;

import com.nanth.querion.dtos.AnswerDto;
import com.nanth.querion.dtos.FeedbackDto;
import com.nanth.querion.dtos.QueryHistoryResponseDto;
import com.nanth.querion.exceptions.InvalidQueryException;
import com.nanth.querion.services.QueryHistoryService;
import com.nanth.querion.services.QueryService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AiController {

  private static final Logger log = LoggerFactory.getLogger(AiController.class);

  @Autowired
  private QueryService qs;
  @Autowired
  private QueryHistoryService queryHistoryService;

  @GetMapping("/hello")
  public ResponseEntity<String> hello() {
    return new ResponseEntity<>("hello", HttpStatus.OK);
  }

  @GetMapping("/ask")
  public ResponseEntity<AnswerDto> ask(@RequestParam String q) {
    log.info("Step 1/3 - Received /api/v1/ask request with query: {}", q);
    if (q == null || q.isBlank()) {
      log.warn("Step 1/3 - Rejected /api/v1/ask request because query was blank");
      throw new InvalidQueryException("Query parameter 'q' must not be blank.");
    }
    AnswerDto answer = this.qs.ask(q);
    log.info("Step 3/3 - Completed /api/v1/ask request with status: {}", answer.getStatus());
    return new ResponseEntity<>(answer, HttpStatus.OK);
  }

  @GetMapping(path = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter askStream(@RequestParam String q) {
    log.info("Step 1/3 - Received /api/v1/ask/stream request with query: {}", q);
    if (q == null || q.isBlank()) {
      log.warn("Step 1/3 - Rejected /api/v1/ask/stream request because query was blank");
      throw new InvalidQueryException("Query parameter 'q' must not be blank.");
    }

    SseEmitter emitter = new SseEmitter(0L);
    sendEvent(emitter, "connected", Map.of(
        "phase", "connected",
        "message", "Connected. Starting your request."
    ));

    CompletableFuture.runAsync(() -> {
      try {
        AnswerDto answer = this.qs.ask(q,
            update -> sendEvent(emitter, update.getOrDefault("event", "status"), update));
        sendEvent(emitter, "complete", Map.of(
            "status", answer.getStatus(),
            "requestId", answer.getRequestId(),
            "answer", answer.getAns()
        ));
        emitter.complete();
      } catch (Exception ex) {
        log.error("Streaming ask request failed for query {}", q, ex);
        sendEvent(emitter, "failure", Map.of(
            "message", ex.getMessage() == null ? "Request failed." : ex.getMessage()
        ));
        emitter.complete();
      }
    });

    return emitter;
  }

  @GetMapping("/history")
  public ResponseEntity<QueryHistoryResponseDto> history() {
    return new ResponseEntity<>(queryHistoryService.fetchRecentHistory(), HttpStatus.OK);
  }

  @PostMapping("/feedback")
  public ResponseEntity<FeedbackDto> feedback(
      @RequestParam String requestId,
      @RequestParam boolean correct) {
    return new ResponseEntity<>(queryHistoryService.applyFeedback(requestId, correct), HttpStatus.OK);
  }

  private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
    try {
      emitter.send(SseEmitter.event()
          .name(eventName)
          .data(payload, MediaType.APPLICATION_JSON));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to send SSE event.", ex);
    }
  }

}
