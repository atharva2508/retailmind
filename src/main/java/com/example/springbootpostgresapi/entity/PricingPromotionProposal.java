package com.example.springbootpostgresapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "pricing_promotion_proposals", indexes = @Index(name = "idx_proposal_store_status", columnList = "store_id, status, created_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingPromotionProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long storeId;

    @Column(nullable = false)
    private String proposalType;

    @Column(nullable = false)
    private String targetSku;

    @Column(nullable = false)
    private String status;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String creatorType;

    @Column(length = 4000)
    private String rationale;

    @Column(length = 4000)
    private String proposedValues;

    @Column(length = 4000)
    private String sourceRefs;

    private String reviewerUsername;

    @Column(length = 2000)
    private String reviewComment;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
    private LocalDateTime appliedAt;
    private LocalDateTime expiresAt;
    private String createdBy;
}
