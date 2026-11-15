package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewProposalRequest(
        @NotBlank String decision,
        String reviewerUsername,
        String reviewComment
) {
}
