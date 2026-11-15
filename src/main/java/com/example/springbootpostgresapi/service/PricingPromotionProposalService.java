package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.CreateProposalRequest;
import com.example.springbootpostgresapi.dto.ReviewProposalRequest;
import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.repository.PricingPromotionProposalRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PricingPromotionProposalService {

    private final PricingPromotionProposalRepository proposalRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final AuditEventService auditEventService;

    public List<PricingPromotionProposal> listAll(Long scope) {
        return scope == null ? proposalRepository.findAllByOrderByCreatedAtDesc()
                : proposalRepository.findByStoreIdOrderByCreatedAtDesc(scope);
    }

    public List<PricingPromotionProposal> listPendingReviews(Long scope) {
        return scope == null ? proposalRepository.findByStatusOrderByCreatedAtDesc("PENDING_REVIEW")
                : proposalRepository.findByStatusAndStoreIdOrderByCreatedAtDesc("PENDING_REVIEW", scope);
    }

    public List<PricingPromotionProposal> suggestDiscounts(Long storeId) {
        return inventoryService.getLowStock(storeId).stream()
                .limit(5)
                .map(item -> {
                    String sku = String.valueOf(item.get("sku"));
                    Product product = productRepository.findBySku(sku)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + sku));
                    return PricingPromotionProposal.builder()
                            .proposalType("DISCOUNT")
                            .targetSku(product.getSku())
                            .status("SUGGESTED")
                            .version(1)
                            .creatorType("SYSTEM")
                            .rationale("Low stock risk in store " + storeId + ": available units " + item.get("available"))
                            .proposedValues("{\"discountPercent\": 10, \"durationDays\": 7}")
                            .sourceRefs("inventory.low-stock.store." + storeId)
                            .createdAt(LocalDateTime.now())
                            .build();
                })
                .toList();
    }

    @Transactional
    public PricingPromotionProposal createProposal(CreateProposalRequest request, Long storeId, String actor) {
        String sku = request.targetSku().trim();
        productRepository.findBySku(sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product SKU: " + sku));

        PricingPromotionProposal proposal = PricingPromotionProposal.builder()
                .proposalType(normalizeProposalType(request.proposalType()))
                .storeId(storeId)
                .targetSku(sku)
                .status("PENDING_REVIEW")
                .version(1)
                .creatorType(request.creatorType() == null || request.creatorType().isBlank() ? "STORE_MANAGER" : request.creatorType())
                .rationale(request.rationale() == null || request.rationale().isBlank() ? "Vendor pricing review requested." : request.rationale())
                .proposedValues(request.proposedValues() == null || request.proposedValues().isBlank() ? "{}" : request.proposedValues())
                .sourceRefs(request.sourceRefs() == null || request.sourceRefs().isBlank() ? "manual-entry" : request.sourceRefs())
                .build();

        PricingPromotionProposal saved = proposalRepository.save(proposal);
        auditEventService.log("CREATE", "PRICING_PROPOSAL", saved.getId(), storeId, actor, "Proposal created for SKU " + sku);
        return saved;
    }

    @Transactional
    public PricingPromotionProposal reviewProposal(Long proposalId, ReviewProposalRequest request, Long scope, String actor) {
        PricingPromotionProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));
        requireInScope(proposal, scope);

        String decision = request.decision().trim().toUpperCase();
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVE or REJECT");
        }

        proposal.setStatus(decision.equals("APPROVE") ? "APPROVED" : "REJECTED");
        proposal.setReviewerUsername(request.reviewerUsername() == null || request.reviewerUsername().isBlank() ? "system" : request.reviewerUsername());
        proposal.setReviewComment(request.reviewComment() == null || request.reviewComment().isBlank() ? "No comment provided." : request.reviewComment());
        proposal.setDecidedAt(LocalDateTime.now());
        PricingPromotionProposal saved = proposalRepository.save(proposal);
        auditEventService.log("REVIEW", "PRICING_PROPOSAL", saved.getId(), saved.getStoreId(), actor, "Decision: " + saved.getStatus());
        return saved;
    }

    @Transactional
    public PricingPromotionProposal applyProposal(Long proposalId, Long scope, String actor) {
        PricingPromotionProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));
        requireInScope(proposal, scope);

        if (!"APPROVED".equalsIgnoreCase(proposal.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only approved proposals can be applied");
        }

        proposal.setStatus("APPLIED");
        proposal.setAppliedAt(LocalDateTime.now());
        PricingPromotionProposal saved = proposalRepository.save(proposal);
        auditEventService.log("APPLY", "PRICING_PROPOSAL", saved.getId(), saved.getStoreId(), actor, "Proposal applied to SKU " + saved.getTargetSku());
        return saved;
    }

    private void requireInScope(PricingPromotionProposal proposal, Long scope) {
        if (scope != null && !scope.equals(proposal.getStoreId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposal.getId());
        }
    }

    private String normalizeProposalType(String proposalType) {
        if (proposalType == null || proposalType.isBlank()) {
            return "DISCOUNT";
        }
        String normalized = proposalType.trim().toUpperCase();
        return switch (normalized) {
            case "PRICE_ADJUSTMENT", "PRICE", "DISCOUNT" -> "DISCOUNT";
            case "PROMOTION" -> "PROMOTION";
            case "CLEARANCE" -> "CLEARANCE";
            default -> "DISCOUNT";
        };
    }
}
