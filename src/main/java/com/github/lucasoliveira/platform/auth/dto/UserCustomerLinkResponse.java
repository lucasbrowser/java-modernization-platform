package com.github.lucasoliveira.platform.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public record UserCustomerLinkResponse(
        UUID userId,
        @JsonInclude(JsonInclude.Include.ALWAYS) UUID customerId,
        boolean changed
) {
}
