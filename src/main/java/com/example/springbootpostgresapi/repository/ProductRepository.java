package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySkuIgnoreCase(String sku);
}
