package com.github.lucasoliveira.platform.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank
        @Size(max = 50)
        String sku,
        @NotBlank
        @Size(max = 160)
        String name,
        @Size(max = 500)
        String description,
        @NotNull
        @DecimalMin("0.00")
        BigDecimal price,
        @NotNull
        @Min(0)
        Integer stock,
        @NotNull
        Boolean active
) {
}
