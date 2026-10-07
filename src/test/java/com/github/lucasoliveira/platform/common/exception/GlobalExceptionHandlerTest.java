package com.github.lucasoliveira.platform.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;


import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void shouldReturnNotFoundForResourceNotFoundException() {

        var exception = new ResourceNotFoundException(
                "Customer not found"
        );

        ResponseEntity<ApiError> response =
                handler.notFound(exception);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(response.getBody())
                .isNotNull();

        assertThat(response.getBody().status())
                .isEqualTo(404);

        assertThat(response.getBody().error())
                .isEqualTo("Not Found");

        assertThat(response.getBody().message())
                .isEqualTo("Customer not found");

        assertThat(response.getBody().fields())
                .isNull();
    }

    @Test
    void shouldReturnBadRequestForIllegalArgumentException() {

        var exception = new IllegalArgumentException(
                "Email already exists"
        );

        ResponseEntity<ApiError> response =
                handler.badRequest(exception);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(response.getBody())
                .isNotNull();

        assertThat(response.getBody().status())
                .isEqualTo(400);

        assertThat(response.getBody().error())
                .isEqualTo("Bad Request");

        assertThat(response.getBody().message())
                .isEqualTo("Email already exists");

        assertThat(response.getBody().fields())
                .isNull();
    }

}