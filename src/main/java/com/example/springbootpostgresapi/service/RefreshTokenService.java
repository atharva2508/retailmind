package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.RefreshToken;
import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public RefreshToken createRefreshToken(User user, long expirationMs) {
        String token = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(token)
                .user(user)
                .expiryDate(Instant.now().plusMillis(expirationMs))
                .revoked(false)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public RefreshToken rotateRefreshToken(RefreshToken currentToken, User user, long expirationMs) {
        currentToken.setRevoked(true);
        refreshTokenRepository.save(currentToken);
        return createRefreshToken(user, expirationMs);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Transactional
    public RefreshToken verifyExpiration(RefreshToken refreshToken) {
        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }
        if (refreshToken.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }
        return refreshToken;
    }

    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.deleteByUser(user);
    }
}
