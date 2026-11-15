package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.RefreshToken;
import com.example.springbootpostgresapi.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    List<RefreshToken> findAllByUser(User user);

    void deleteByUser(User user);
}
