package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.AuthResponse;
import com.example.springbootpostgresapi.dto.LoginRequest;
import com.example.springbootpostgresapi.dto.RegisterRequest;
import com.example.springbootpostgresapi.dto.TokenRefreshRequest;
import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.UserRepository;
import com.example.springbootpostgresapi.security.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_TIME = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_USERNAMES = 10_000;

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditEventService auditEventService;

    // Keyed by username + client address, so a stranger guessing passwords locks only themself out,
    // not the real owner logging in from somewhere else.
    // ponytail: in-memory, per instance. Move to DB or Redis when running more than one instance.
    // Behind a reverse proxy getRemoteAddr() is the proxy: configure server.forward-headers-strategy.
    private final Map<String, Attempts> failedLogins = new ConcurrentHashMap<>();

    /** Usernames and emails are compared case-insensitively by storing and looking them up in lower case. */
    public static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalize(request.username());
        String email = normalize(request.email());
        // bcrypt only reads the first 72 bytes; anything longer would be silently cut.
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too long");
        }
        if (userRepository.findByUsername(username).isPresent() || userRepository.findByEmail(email).isPresent()) {
            // One message for both, so the response does not say which one exists.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username or email is not available");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .role("ROLE_USER")
                .build();

        userRepository.save(user);
        return issueTokens(user, refreshTokenService.create(user, refreshExpiry()));
    }

    public AuthResponse login(LoginRequest request, String clientAddress) {
        String username = normalize(request.username());
        String key = username + "|" + clientAddress;
        Instant now = Instant.now();
        Attempts attempts = failedLogins.get(key);
        // Checked for every username, existing or not, so lockout does not reveal which accounts exist.
        if (attempts != null && attempts.lockedUntil() != null && now.isBefore(attempts.lockedUntil())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed logins, try again later");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException e) {
            boolean nowLocked = recordFailure(key, now);
            // Only real accounts are audited, and the lock is logged once, so guessing cannot flood the audit trail.
            userRepository.findByUsername(username).ifPresent(known -> {
                auditLogin("LOGIN_FAILED", username, known);
                if (nowLocked) {
                    auditLogin("LOGIN_LOCKED", username, known);
                }
            });
            throw e;
        }
        failedLogins.remove(key);

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        auditLogin("LOGIN_SUCCESS", user.getUsername(), user);
        return issueTokens(user, refreshTokenService.create(user, refreshExpiry()));
    }

    // The typed username is attacker-controlled, so cap it before it goes into the audit table.
    private void auditLogin(String action, String username, User user) {
        String actor = username == null ? "" : username.substring(0, Math.min(username.length(), 100));
        auditEventService.log(action, "USER", user == null ? null : user.getId(), user == null ? null : user.getStoreId(), actor, action);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(request.refreshToken());
        return issueTokens(rotated.user(), rotated.rawToken());
    }

    public void logout(TokenRefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    /** Returns true when this failure is the one that starts the lock. */
    private boolean recordFailure(String key, Instant now) {
        if (failedLogins.size() >= MAX_TRACKED_USERNAMES) {
            failedLogins.values().removeIf(a -> a.lastFailure().isBefore(now.minus(LOCK_TIME)));
        }
        Attempts updated = failedLogins.compute(key, (k, old) -> {
            int failures = old == null || old.lastFailure().isBefore(now.minus(LOCK_TIME)) ? 1 : old.failures() + 1;
            return new Attempts(failures, failures >= MAX_FAILURES ? now.plus(LOCK_TIME) : null, now);
        });
        return updated.failures() == MAX_FAILURES;
    }

    private Instant refreshExpiry() {
        return Instant.now().plusMillis(jwtService.getRefreshExpirationMs());
    }

    private AuthResponse issueTokens(User user, String rawRefreshToken) {
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(user.getRole())
                .build();
        return new AuthResponse(jwtService.generateAccessToken(userDetails), rawRefreshToken, user.getUsername(), user.getRole());
    }

    private record Attempts(int failures, Instant lockedUntil, Instant lastFailure) {}
}
