package dev.relay.security;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Profile("!worker")
public class ValidationErrorHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorView> invalidRequest(Exception ignored) {
        // Never return or log a rejected value; registration bodies contain credentials.
        return ResponseEntity.badRequest().body(new ErrorView("invalid_request"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorView> persistenceFailure(Exception ignored) {
        // PostgreSQL exception details can contain entire failing rows, including payloads.
        // Operational error categories/correlation are introduced in Phase 7.
        return ResponseEntity.internalServerError().body(new ErrorView("persistence_failure"));
    }

    public record ErrorView(String error) {}
}
