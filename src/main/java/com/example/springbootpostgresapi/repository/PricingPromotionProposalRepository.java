package com.example.springbootpostgresapi.repository;

import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PricingPromotionProposalRepository extends JpaRepository<PricingPromotionProposal, Long> {
    List<PricingPromotionProposal> findByStatusOrderByCreatedAtDesc(String status);

    List<PricingPromotionProposal> findAllByOrderByCreatedAtDesc();

    List<PricingPromotionProposal> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<PricingPromotionProposal> findByStatusAndStoreIdOrderByCreatedAtDesc(String status, Long storeId);
}
