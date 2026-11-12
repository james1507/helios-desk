package com.heliosdesk.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Problems {
  public static String correlation() {
    String id = MDC.get("correlationId");
    return id == null ? UUID.randomUUID().toString() : id;
  }

  public static ProblemDetail detail(int status, String code, String message, Long version) {
    var p =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(status), message);
    p.setTitle(code.replace('_', ' '));
    p.setProperty("code", code);
    p.setProperty("correlationId", correlation());
    if (version != null) p.setProperty("currentVersion", version);
    return p;
  }

  public static void write(HttpServletResponse response, ObjectMapper json, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    json.writeValue(
        response.getOutputStream(),
        detail(status, code, status == 401 ? "Sign in to continue." : "Request rejected.", null));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> domain(ApiException e) {
    return ResponseEntity.status(e.status)
        .body(detail(e.status, e.code, e.getMessage(), e.currentVersion));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    HandlerMethodValidationException.class
  })
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            detail(400, "VALIDATION_FAILED", "Check required fields and supported values.", null));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(
            detail(
                409,
                "TICKET_VERSION_CONFLICT",
                "Refresh the ticket; your draft can be retried.",
                null));
  }

  @ExceptionHandler(DataAccessResourceFailureException.class)
  ResponseEntity<ProblemDetail> unavailable(Exception e) {
    return ResponseEntity.status(503)
        .body(detail(503, "DEPENDENCY_UNAVAILABLE", "Database unavailable. Retry later.", null));
  }
}
