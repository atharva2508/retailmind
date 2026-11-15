package com.example.springbootpostgresapi.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$", message = "Username must be 3-50 letters, digits, dot, underscore or hyphen") String username,
        @NotBlank(message = "Email is required") @Email(message = "Email should be valid") String email,
        @NotBlank(message = "Password is required") @Size(min = 6, max = 72, message = "Password must be 6 to 72 characters") String password
) {
}
