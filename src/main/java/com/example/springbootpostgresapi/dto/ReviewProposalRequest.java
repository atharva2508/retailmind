package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewProposalRequest(
        @NotBlank @Size(max = 20) String decision,
        @Size(max = 2000) String reviewComment
) {
}
