package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.Store;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByStoreCode(String storeCode);
}
