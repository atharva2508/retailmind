package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.StoreRepository;
import com.example.springbootpostgresapi.repository.UserRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StoreAccessService {

    private static final Set<String> STORE_ROLES = Set.of("ROLE_STORE_MANAGER", "ROLE_REVIEWER", "ROLE_USER");

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;

    /** Stores the caller may see: null means all stores (admin), otherwise exactly one store id. Deny by default. */
    public Long scopeOrNull(Authentication authentication) {
        User user = currentUser(authentication);
        if ("ROLE_ADMIN".equals(user.getRole())) {
            return null;
        }
        if (!STORE_ROLES.contains(user.getRole()) || user.getStoreId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Store access denied");
        }
        return user.getStoreId();
    }

    /** The store a request acts on. A requested store outside the caller's scope is rejected, never widened. */
    public Long resolveStore(Authentication authentication, Long requestedStoreId) {
        if (requestedStoreId != null && requestedStoreId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "storeId must be positive");
        }
        Long scope = scopeOrNull(authentication);
        Long target;
        if (scope == null) {
            target = requestedStoreId != null ? requestedStoreId : currentUser(authentication).getStoreId();
            if (target == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "storeId is required");
            }
        } else {
            if (requestedStoreId != null && !requestedStoreId.equals(scope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Store access denied");
            }
            target = scope;
        }
        if (!storeRepository.existsById(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found: " + target);
        }
        return target;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
