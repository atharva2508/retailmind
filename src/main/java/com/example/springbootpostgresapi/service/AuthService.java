package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.AuthResponse;
import com.example.springbootpostgresapi.dto.LoginRequest;
import com.example.springbootpostgresapi.dto.RegisterRequest;
import com.example.springbootpostgresapi.dto.TokenRefreshRequest;
import com.example.springbootpostgresapi.entity.RefreshToken;
import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.UserRepository;
import com.example.springbootpostgresapi.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is already taken");
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is already registered");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role("ROLE_USER")
                .storeId(1L)
                .build();

        userRepository.save(user);
        UserDetails userDetails = buildUserDetails(user);

        String accessToken = jwtService.generateAccessToken(userDetails);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, jwtService.getRefreshExpirationMs());
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String accessToken = jwtService.generateAccessToken(userDetails);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, jwtService.getRefreshExpirationMs());
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getRole());
    }

    @Transactional
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        RefreshToken currentRefreshToken = refreshTokenService.findByToken(request.refreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        refreshTokenService.verifyExpiration(currentRefreshToken);

        User user = currentRefreshToken.getUser();
        UserDetails userDetails = buildUserDetails(user);
        String newAccessToken = jwtService.generateAccessToken(userDetails);
        RefreshToken rotatedRefreshToken = refreshTokenService.rotateRefreshToken(
                currentRefreshToken,
                user,
                jwtService.getRefreshExpirationMs()
        );

        return new AuthResponse(newAccessToken, rotatedRefreshToken.getToken(), user.getUsername(), user.getRole());
    }

    private UserDetails buildUserDetails(User user) {
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(user.getRole())
                .build();
    }
}
