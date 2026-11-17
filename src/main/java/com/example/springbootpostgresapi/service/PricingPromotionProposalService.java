package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.CreateProposalRequest;
import com.example.springbootpostgresapi.dto.ReviewProposalRequest;
import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.repository.PricingPromotionProposalRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private final ObjectMapper objectMapper;

    private static final Duration PROPOSAL_TTL = Duration.ofDays(7);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

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
                    return PricingPromotionProposal.builder()
                            .proposalType("DISCOUNT")
                            .targetSku(sku)
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
    public PricingPromotionProposal createProposal(CreateProposalRequest request, Long storeId, String actor, String creatorType) {
        String sku = request.targetSku().trim().toUpperCase(); // SKUs are stored upper case
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product SKU: " + sku));
        String values = request.proposedValues() == null || request.proposedValues().isBlank() ? "{}" : request.proposedValues();
        newPrice(product, values);

        PricingPromotionProposal proposal = PricingPromotionProposal.builder()
                .proposalType(normalizeProposalType(request.proposalType()))
                .storeId(storeId)
                .targetSku(sku)
                .targetProductId(product.getId())
                .basePrice(product.getPrice())
                .status("PENDING_REVIEW")
                .createdBy(actor)
                .expiresAt(LocalDateTime.now().plus(PROPOSAL_TTL))
                .creatorType(creatorType)
                .rationale(request.rationale() == null || request.rationale().isBlank() ? "Vendor pricing review requested." : request.rationale())
                .proposedValues(values)
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
        if (actor.equals(proposal.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Creator cannot review own proposal");
        }
        requireStatus(proposal, "PENDING_REVIEW");
        requireNotExpired(proposal);

        proposal.setStatus(decision.equals("APPROVE") ? "APPROVED" : "REJECTED");
        proposal.setReviewerUsername(actor);
        proposal.setReviewComment(request.reviewComment() == null || request.reviewComment().isBlank() ? "No comment provided." : request.reviewComment());
        proposal.setDecidedAt(LocalDateTime.now());
        PricingPromotionProposal saved = saveOrConflict(proposal);
        auditEventService.log("REVIEW", "PRICING_PROPOSAL", saved.getId(), saved.getStoreId(), actor, "Decision: " + saved.getStatus());
        return saved;
    }

    @Transactional
    public PricingPromotionProposal applyProposal(Long proposalId, Long scope, String actor) {
        PricingPromotionProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId));
        requireInScope(proposal, scope);

        requireStatus(proposal, "APPROVED");
        requireNotExpired(proposal);
        // Rows created before these fields existed have nulls: fall back to the SKU and skip the price check.
        Product product = (proposal.getTargetProductId() != null
                ? productRepository.findById(proposal.getTargetProductId())
                : productRepository.findBySku(proposal.getTargetSku()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Product no longer exists: " + proposal.getTargetSku()));
        if (!product.getSku().equals(proposal.getTargetSku())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product SKU changed since the proposal was created");
        }
        BigDecimal oldPrice = product.getPrice();
        if (proposal.getBasePrice() != null && oldPrice.compareTo(proposal.getBasePrice()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Price changed since the proposal was created, create a new proposal");
        }
        BigDecimal price = newPrice(product, proposal.getProposedValues());

        // ponytail: product price is global, not per store; per-store pricing needs its own table.
        product.setPrice(price);
        productRepository.save(product);
        proposal.setStatus("APPLIED");
        proposal.setAppliedAt(LocalDateTime.now());
        PricingPromotionProposal saved = saveOrConflict(proposal);
        auditEventService.log("APPLY", "PRICING_PROPOSAL", saved.getId(), saved.getStoreId(), actor,
                "Price of SKU " + saved.getTargetSku() + " changed " + oldPrice + " -> " + price);
        return saved;
    }

    // Accepts {"newPrice": x} or {"discountPercent": p}; result must differ from current price and fit numeric(10,2).
    private BigDecimal newPrice(Product product, String proposedValues) {
        JsonNode values;
        try {
            values = objectMapper.readTree(proposedValues);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "proposedValues must be JSON");
        }
        BigDecimal price;
        if (values.hasNonNull("newPrice")) {
            price = values.get("newPrice").decimalValue();
        } else if (values.hasNonNull("discountPercent")) {
            BigDecimal pct = values.get("discountPercent").decimalValue();
            if (pct.signum() <= 0 || pct.compareTo(HUNDRED) >= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discountPercent must be between 0 and 100");
            }
            price = product.getPrice().multiply(HUNDRED.subtract(pct)).divide(HUNDRED);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "proposedValues needs newPrice or discountPercent");
        }
        price = price.setScale(2, RoundingMode.HALF_UP);
        if (price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New price out of range");
        }
        if (price.compareTo(product.getPrice()) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New price equals current price");
        }
        return price;
    }

    private PricingPromotionProposal saveOrConflict(PricingPromotionProposal proposal) {
        try {
            return proposalRepository.saveAndFlush(proposal);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal was changed by someone else, reload and retry");
        }
    }

    private void requireStatus(PricingPromotionProposal proposal, String status) {
        if (!status.equals(proposal.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal is " + proposal.getStatus() + ", expected " + status);
        }
    }

    private void requireNotExpired(PricingPromotionProposal proposal) {
        if (proposal.getExpiresAt() != null && LocalDateTime.now().isAfter(proposal.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal expired at " + proposal.getExpiresAt());
        }
    }

    private void requireInScope(PricingPromotionProposal proposal, Long scope) {
        if (scope != null && !scope.equals(proposal.getStoreId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposal.getId());
        }
    }

    private String normalizeProposalType(String proposalType) {
        String normalized = proposalType.trim().toUpperCase();
        return switch (normalized) {
            case "PRICE_ADJUSTMENT", "PRICE", "DISCOUNT" -> "DISCOUNT";
            case "PROMOTION" -> "PROMOTION";
            case "CLEARANCE" -> "CLEARANCE";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "proposalType must be DISCOUNT, PROMOTION or CLEARANCE");
        };
    }
}
