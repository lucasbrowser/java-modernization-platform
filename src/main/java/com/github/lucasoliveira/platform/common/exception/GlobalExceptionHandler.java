package com.github.lucasoliveira.platform.common.exception;


import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;

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

    private ApiError error(int status, String error, String message, java.util.Map<String, String> fields) {
        return new ApiError(OffsetDateTime.now(), status, error, message, fields);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        ApiError apiError = error(
            HttpStatus.CONFLICT.value(),
            "Conflict",
            "The resource was modified by another operation.",
            null
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(apiError);
    }
}
