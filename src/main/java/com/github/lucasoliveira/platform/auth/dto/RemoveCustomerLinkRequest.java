package com.github.lucasoliveira.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RemoveCustomerLinkRequest(
        @NotBlank
        @Size(max = 500)
        String justification
) {
    public RemoveCustomerLinkRequest {
        if (justification != null) {
            justification = justification.strip();
        }
    }
}
