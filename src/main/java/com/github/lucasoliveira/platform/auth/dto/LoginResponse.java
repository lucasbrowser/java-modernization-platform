package com.github.lucasoliveira.platform.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
