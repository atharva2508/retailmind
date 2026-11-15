package com.example.springbootpostgresapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record InventoryImportRequest(
        @NotNull(message = "Store ID is required") Long storeId,
        @NotBlank(message = "Snapshot type is required") String snapshotType,
        @NotBlank(message = "File name is required") String fileName,
        @NotBlank(message = "asOf is required") String asOf,
        @Valid @Size(max = 50000) List<InventoryRow> rows
) {
    public record InventoryRow(
            @NotBlank(message = "SKU is required") String sku,
            @NotNull(message = "BAL is required") @Min(0) @Max(1_000_000) Integer bal,
            @NotNull(message = "AUD is required") @Min(0) @Max(1_000_000) Integer aud,
            @NotNull(message = "RAC is required") @Min(0) @Max(1_000_000) Integer rac,
            @NotNull(message = "HELD is required") @Min(0) @Max(1_000_000) Integer held
    ) {}
}
