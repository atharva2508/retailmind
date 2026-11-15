package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "SKU is required") String sku,
        @NotBlank(message = "Name is required") String name,
        String description,
        @NotNull(message = "Price is required") @DecimalMin(value = "0.01", message = "Price must be greater than zero") BigDecimal price,
        @NotNull(message = "Stock is required") @Min(value = 0, message = "Stock cannot be negative") Integer stock
) {}
