package com.example.springbootpostgresapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
        name = "stock_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "product_id", "business_date", "snapshot_type"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private String snapshotType;

    @Column(nullable = false)
    private LocalDateTime asOf;

    @Column(nullable = false)
    private Integer bal;

    @Column(nullable = false)
    private Integer aud;

    @Column(nullable = false)
    private Integer rac;

    @Column(nullable = false)
    private Integer held;

    @Column(nullable = false)
    @Builder.Default
    private Boolean absent = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
