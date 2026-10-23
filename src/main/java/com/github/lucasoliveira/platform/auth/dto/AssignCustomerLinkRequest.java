package com.github.lucasoliveira.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AssignCustomerLinkRequest(
        @NotNull
        UUID customerId,
        @NotBlank
        @Size(max = 500)
        String justification
) {
    public AssignCustomerLinkRequest {
        if (justification != null) {
            justification = justification.strip();
        }
    }
}
