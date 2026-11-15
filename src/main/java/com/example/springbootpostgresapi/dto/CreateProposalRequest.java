package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProposalRequest(
        @NotBlank String proposalType,
        @NotBlank String targetSku,
        String creatorType,
        String rationale,
        String proposedValues,
        String sourceRefs,
        Long storeId
) {
}
