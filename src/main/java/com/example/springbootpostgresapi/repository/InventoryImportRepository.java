package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.InventoryImport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryImportRepository extends JpaRepository<InventoryImport, Long> {
    List<InventoryImport> findAllByOrderByReceivedAtDesc();

    boolean existsByStoreIdAndSnapshotTypeAndAsOfAndChecksum(Long storeId, String snapshotType, java.time.LocalDate asOf, String checksum);
}
