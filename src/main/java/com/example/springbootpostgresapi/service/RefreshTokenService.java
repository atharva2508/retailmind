package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.RefreshToken;
import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_TOKENS_PER_USER = 10;

    private final RefreshTokenRepository refreshTokenRepository;

    /** Issues a new token for the user. Returns the raw token; only its hash is stored. */
    @Transactional
    public String create(User user, Instant expiry) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(RefreshToken.builder()
                .tokenHash(hash(raw))
                .user(user)
                .expiryDate(expiry)
                .revoked(false)
                .build());
        // Keep only the newest rows per user so repeated logins cannot grow the table without bound.
        // A token older than that is simply unknown: it gets 401 but no longer triggers the family revoke.
        List<Long> ids = refreshTokenRepository.findIdsByUserNewestFirst(user);
        if (ids.size() > MAX_TOKENS_PER_USER) {
            refreshTokenRepository.deleteAllByIdInBatch(ids.subList(MAX_TOKENS_PER_USER, ids.size()));
        }
        return raw;
    }

    /**
     * Revokes the presented token and issues a new one with the SAME expiry, so a session never outlives
     * its first login. A token that is already revoked means it was stolen or replayed: revoke every token
     * of that user. Must not roll back on the 401, or the family revoke would be undone.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public RotatedToken rotate(String raw) {
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash(raw))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (current.getExpiryDate().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (refreshTokenRepository.revokeIfActive(current.getId()) == 0) {
            refreshTokenRepository.revokeAllForUser(current.getUser());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        return new RotatedToken(current.getUser(), create(current.getUser(), current.getExpiryDate()));
    }

    @Transactional
    public void revoke(String raw) {
        refreshTokenRepository.findByTokenHash(hash(raw))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId()));
    }

    // Revoked tokens are kept until expiry so reuse can still be detected.
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void deleteExpired() {
        refreshTokenRepository.deleteExpired(Instant.now());
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record RotatedToken(User user, String rawToken) {}
}
