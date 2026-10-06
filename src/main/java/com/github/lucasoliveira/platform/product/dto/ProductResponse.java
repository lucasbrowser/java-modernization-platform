package com.github.lucasoliveira.platform.product.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Boolean active,
        OffsetDateTime createdAt
) {
}
