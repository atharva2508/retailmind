package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.dto.CreateProposalRequest;
import com.example.springbootpostgresapi.dto.ReviewProposalRequest;
import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.service.PricingPromotionProposalService;
import com.example.springbootpostgresapi.service.StoreAccessService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PricingPromotionProposalController {

    private final PricingPromotionProposalService pricingPromotionProposalService;
    private final StoreAccessService storeAccessService;

    @GetMapping("/proposals")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public List<PricingPromotionProposal> listProposals(Authentication authentication) {
        return pricingPromotionProposalService.listAll(storeAccessService.scopeOrNull(authentication));
    }

    @GetMapping("/proposals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public List<PricingPromotionProposal> listPendingProposals(Authentication authentication) {
        return pricingPromotionProposalService.listPendingReviews(storeAccessService.scopeOrNull(authentication));
    }

    @GetMapping("/proposals/suggestions")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public List<PricingPromotionProposal> suggestDiscounts(@RequestParam(required = false) Long storeId, Authentication authentication) {
        return pricingPromotionProposalService.suggestDiscounts(storeAccessService.resolveStore(authentication, storeId));
    }

    @PostMapping("/proposals")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','REVIEWER')")
    public ResponseEntity<PricingPromotionProposal> createProposal(@Valid @RequestBody CreateProposalRequest request,
                                                                     Authentication authentication) {
        Long store = storeAccessService.resolveStore(authentication, request.storeId());
        PricingPromotionProposal created = pricingPromotionProposalService.createProposal(request, store, authentication.getName(),
                authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", ""));
        return ResponseEntity.created(URI.create("/api/proposals/" + created.getId())).body(created);
    }

    @PostMapping("/proposals/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public PricingPromotionProposal reviewProposal(@PathVariable Long id, @Valid @RequestBody ReviewProposalRequest request,
                                               Authentication authentication) {
        return pricingPromotionProposalService.reviewProposal(id, request, storeAccessService.scopeOrNull(authentication), authentication.getName());
    }

    @PostMapping("/proposals/{id}/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public PricingPromotionProposal applyProposal(@PathVariable Long id, Authentication authentication) {
        return pricingPromotionProposalService.applyProposal(id, storeAccessService.scopeOrNull(authentication), authentication.getName());
    }
}
