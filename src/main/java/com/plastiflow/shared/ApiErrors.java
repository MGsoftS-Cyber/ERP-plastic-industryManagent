package com.plastiflow.shared;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ProblemDetail> business(BusinessException ex) {
    return problem(ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(jakarta.servlet.http.HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/v1/manufacturing")
        || request.getRequestURI().startsWith("/api/v1/operations")
        || request.getRequestURI().startsWith("/api/v1/payroll")
        || request.getRequestURI().startsWith("/api/v1/finance")
        || request.getRequestURI().startsWith("/api/v1/integration")
        || request.getRequestURI().startsWith("/api/v1/portal"))
      return problem(
          HttpStatus.CONFLICT,
          "DATA_CONFLICT",
          "A reference already exists or the record violates a business database constraint.");
    return problem(
        HttpStatus.CONFLICT,
        "CATALOG_CONFLICT",
        "The SKU already exists or the record violates a catalog constraint.");
  }

  @ExceptionHandler(PessimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> busy() {
    return problem(
        HttpStatus.CONFLICT,
        "RESOURCE_BUSY",
        "A resource is being updated. Reload and retry the operation.");
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> stale() {
    return problem(
        HttpStatus.CONFLICT,
        "STALE_VERSION",
        "Another user changed the product. Reload it before updating.");
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    HandlerMethodValidationException.class
  })
  ResponseEntity<ProblemDetail> invalid() {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Check required fields, field lengths, enum values, identifiers and pagination limits.");
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(code);
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
