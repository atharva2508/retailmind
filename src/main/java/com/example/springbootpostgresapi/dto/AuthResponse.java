package com.example.springbootpostgresapi.dto;

public record AuthResponse(String accessToken, String refreshToken, String username, String role, Long storeId) {
}
