package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.RefreshToken;
import com.example.springbootpostgresapi.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("select t.id from RefreshToken t where t.user = :user order by t.id desc")
    List<Long> findIdsByUserNewestFirst(@Param("user") User user);

    // Atomic: only one caller can revoke an active token, so concurrent rotation of the same token cannot both win.
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.user = :user")
    int revokeAllForUser(@Param("user") User user);

    @Modifying
    @Query("delete from RefreshToken t where t.expiryDate < :now")
    int deleteExpired(@Param("now") Instant now);
}
