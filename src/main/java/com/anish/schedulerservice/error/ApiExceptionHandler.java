package com.anish.schedulerservice.error;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps errors to RFC 9457 ProblemDetail responses (application/problem+json).
 * Extending ResponseEntityExceptionHandler also covers framework errors such as an
 * invalid UUID in the path (400) with the same response shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidAppointmentException.class)
    ProblemDetail handleInvalid(InvalidAppointmentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid appointment request", ex.getMessage());
    }

    @ExceptionHandler(AppointmentConflictException.class)
    ProblemDetail handleConflict(AppointmentConflictException ex) {
        ProblemDetail body = problem(HttpStatus.CONFLICT, "Appointment conflict", ex.getMessage());
        body.setProperty("conflictingAppointmentId", ex.getConflictingAppointmentId());
        return body;
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    ProblemDetail handleNotFound(AppointmentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Appointment not found", ex.getMessage());
    }

    @ExceptionHandler(AppointmentAlreadyCancelledException.class)
    ProblemDetail handleAlreadyCancelled(AppointmentAlreadyCancelledException ex) {
        return problem(HttpStatus.CONFLICT, "Appointment already cancelled", ex.getMessage());
    }

    /** Bean Validation failures: list every field error so clients can fix them in one round trip. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Invalid appointment request", "Request validation failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Malformed JSON or missing body. The parser's own message is not echoed back to clients. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "Expected a JSON object with providerId, start and end");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return body;
    }
}
