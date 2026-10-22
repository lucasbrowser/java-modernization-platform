package com.github.lucasoliveira.platform.common.exception;

import com.github.lucasoliveira.platform.order.service.OrderCancellationConflictException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.ServletException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(404, "Not Found", ex.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error(400, "Bad Request", ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        var fields = new LinkedHashMap<String, String>();
        for (FieldError field : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(field.getField(), field.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(error(400, "Validation Error", "Invalid request data", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedRequestBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(error(400, "Bad Request", "Malformed request body", null));
    }

    private ApiError error(int status, String error, String message, java.util.Map<String, String> fields) {
        return new ApiError(OffsetDateTime.now(), status, error, message, fields);
    }

    @ExceptionHandler({
        OptimisticLockingFailureException.class,
        OptimisticLockException.class,
        StaleObjectStateException.class
    })
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(
            RuntimeException ex) {

        ApiError apiError = error(
            HttpStatus.CONFLICT.value(),
            "Conflict",
            "The resource was modified by another operation.",
            null
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(apiError);
    }

    @ExceptionHandler(OrderCancellationConflictException.class)
    ResponseEntity<ApiError> orderCancellationConflict(OrderCancellationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(
                        HttpStatus.CONFLICT.value(),
                        "Conflict",
                        "Order cannot be cancelled in its current state",
                        null
                ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException accessDeniedException) {
            throw accessDeniedException;
        }
        if (ex instanceof AuthenticationException authenticationException) {
            throw authenticationException;
        }
        if (ex instanceof ServletException servletException) {
            throw servletException;
        }
        if (ex instanceof TypeMismatchException typeMismatchException) {
            throw typeMismatchException;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred.",
                        null
                ));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException ex) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(
                        401,
                        "Unauthorized",
                        ex.getMessage(),
                        null
                ));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ResponseEntity<ApiError> emailAlreadyRegistered(EmailAlreadyRegisteredException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(
                        409,
                        "Conflict",
                        ex.getMessage(),
                        null
                ));
    }
}
