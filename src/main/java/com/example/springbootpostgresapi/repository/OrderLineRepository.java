package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.OrderLine;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {
    List<OrderLine> findByStoreId(Long storeId);
    List<OrderLine> findByStoreIdAndConfirmedAtBetween(Long storeId, LocalDateTime from, LocalDateTime to);
    List<OrderLine> findByStoreIdAndCancelledAtBetween(Long storeId, LocalDateTime from, LocalDateTime to);
}
