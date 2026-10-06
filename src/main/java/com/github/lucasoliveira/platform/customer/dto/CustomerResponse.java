package com.github.lucasoliveira.platform.customer.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String name,
        String email,
        String document,
        String phone,
        OffsetDateTime createdAt
) {
}
