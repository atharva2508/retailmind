package com.example.springbootpostgresapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "inventory_imports")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private String storeCodeRaw;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String checksum;

    @Column(nullable = false)
    private String snapshotType;

    @Column(nullable = false)
    private LocalDate asOf;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer rowCount;

    @Column(nullable = false)
    private Integer unknownSkuCount;

    @Column(nullable = false)
    private Integer missingSkuCount;

    @Column(length = 1000)
    private String errorSummary;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime appliedAt;
}
