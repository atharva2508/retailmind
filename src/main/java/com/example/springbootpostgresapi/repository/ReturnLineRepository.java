package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.ReturnLine;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReturnLineRepository extends JpaRepository<ReturnLine, Long> {
    List<ReturnLine> findByStoreId(Long storeId);
    List<ReturnLine> findByStoreIdAndReceivedAtBetween(Long storeId, LocalDateTime from, LocalDateTime to);
}
