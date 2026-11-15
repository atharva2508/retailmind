package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.AuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop200ByOrderByOccurredAtDesc();

    List<AuditEvent> findTop200ByStoreIdOrderByOccurredAtDesc(Long storeId);
}
