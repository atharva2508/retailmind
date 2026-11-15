package com.example.springbootpostgresapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_occurred", columnList = "occurred_at"),
        @Index(name = "idx_audit_store_occurred", columnList = "store_id, occurred_at")
})
// Append-only: Hibernate ignores updates and the entity refuses deletes.
// ponytail: app-level only. Add a DB trigger or revoke UPDATE/DELETE on the table for a hard guarantee.
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actionType;

    @Column(nullable = false)
    private String entityType;

    private Long entityId;

    private Long storeId;

    @Column(nullable = false)
    private String actorUsername;

    @Column(length = 2000)
    private String details;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PreRemove
    void refuseDelete() {
        throw new UnsupportedOperationException("Audit events cannot be deleted");
    }
}
