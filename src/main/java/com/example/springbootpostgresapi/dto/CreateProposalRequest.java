package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Sizes match the pricing_promotion_proposals columns.
public record CreateProposalRequest(
        @NotBlank @Size(max = 255) String proposalType,
        @NotBlank @Size(max = 255) String targetSku,
        @Size(max = 4000) String rationale,
        @Size(max = 4000) String proposedValues,
        @Size(max = 4000) String sourceRefs,
        Long storeId
) {
}
