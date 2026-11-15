package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.dto.AuthResponse;
import com.example.springbootpostgresapi.dto.LoginRequest;
import com.example.springbootpostgresapi.dto.RegisterRequest;
import com.example.springbootpostgresapi.dto.TokenRefreshRequest;
import com.example.springbootpostgresapi.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authService.login(request, http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    // Always 204, so the response does not say whether the token was valid.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody TokenRefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
}
