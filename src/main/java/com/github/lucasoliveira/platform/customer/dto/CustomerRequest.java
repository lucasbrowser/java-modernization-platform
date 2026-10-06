package com.github.lucasoliveira.platform.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank
        @Size(max = 160)
        String name,
        @NotBlank
        @Email
        String email,
        @NotBlank
        @Size(max = 30)
        String document,
        @Size(max = 30)
        String phone
) {
}
