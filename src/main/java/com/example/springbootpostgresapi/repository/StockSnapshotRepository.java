package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.StockSnapshot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockSnapshotRepository extends JpaRepository<StockSnapshot, Long> {
    List<StockSnapshot> findByStoreIdAndBusinessDateAndSnapshotType(Long storeId, LocalDate businessDate, String snapshotType);
    List<StockSnapshot> findByStoreIdAndProductId(Long storeId, Long productId);
    List<StockSnapshot> findByStoreId(Long storeId);
}
