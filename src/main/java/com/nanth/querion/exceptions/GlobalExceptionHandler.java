package com.nanth.querion.exceptions;

import com.nanth.querion.dtos.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InvalidQueryException.class)
  public ResponseEntity<ErrorResponse> handleInvalidQuery(
      InvalidQueryException ex,
      HttpServletRequest request) {
    return buildResponse(HttpStatus.BAD_REQUEST, ex, request, false);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException ex,
      HttpServletRequest request) {
    return buildResponse(HttpStatus.BAD_REQUEST, ex, request, false);
  }

  @ExceptionHandler({
      ExternalServiceException.class,
      SchemaReadException.class,
      SqlExecutionException.class
  })
  public ResponseEntity<ErrorResponse> handleOperationalFailure(
      ApplicationException ex,
      HttpServletRequest request) {
    return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex, request, true);
  }

  @ExceptionHandler(PersistenceException.class)
  public ResponseEntity<ErrorResponse> handlePersistenceFailure(
      PersistenceException ex,
      HttpServletRequest request) {
    return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex, request, true);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(
      Exception ex,
      HttpServletRequest request) {
    return buildResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        new ApplicationException("Unexpected server error.", ex),
        request,
        true);
  }

  private ResponseEntity<ErrorResponse> buildResponse(
      HttpStatus status,
      Exception ex,
      HttpServletRequest request,
      boolean logError) {
    if (logError) {
      log.error("Request failed for path {}", request.getRequestURI(), ex);
    } else {
      log.warn("Request rejected for path {}: {}", request.getRequestURI(), ex.getMessage());
    }

    ErrorResponse body = ErrorResponse.builder()
        .timestamp(Instant.now())
        .status(status.value())
        .error(status.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();

    return ResponseEntity.status(status).body(body);
  }
}
