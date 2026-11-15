package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record InventoryImportRequest(
        @NotNull(message = "Store ID is required") Long storeId,
        @NotBlank(message = "Snapshot type is required") String snapshotType,
        @NotBlank(message = "File name is required") String fileName,
        @NotBlank(message = "asOf is required") String asOf,
        List<InventoryRow> rows
) {
    public record InventoryRow(
            @NotBlank(message = "SKU is required") String sku,
            @NotNull(message = "BAL is required") Integer bal,
            @NotNull(message = "AUD is required") Integer aud,
            @NotNull(message = "RAC is required") Integer rac,
            @NotNull(message = "HELD is required") Integer held
    ) {}
}
