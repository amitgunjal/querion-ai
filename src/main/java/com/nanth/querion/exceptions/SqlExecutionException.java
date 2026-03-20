package com.nanth.querion.exceptions;

public class SqlExecutionException extends ApplicationException {

  public SqlExecutionException(String message) {
    super(message);
  }

  public SqlExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
