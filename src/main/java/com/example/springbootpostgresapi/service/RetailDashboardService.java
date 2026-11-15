package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.repository.PricingPromotionProposalRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetailDashboardService {

    private final InventoryService inventoryService;
    private final PricingPromotionProposalRepository proposalRepository;

    public Map<String, Object> getStoreSummary(Long storeId) {
        List<Map<String, Object>> lowStock = inventoryService.getLowStock(storeId);
        List<Map<String, Object>> fulfillmentIssues = inventoryService.getFulfillmentIssues(storeId, LocalDate.now().minusDays(7), LocalDate.now());
        List<PricingPromotionProposal> pendingProposals = proposalRepository.findByStatusAndStoreIdOrderByCreatedAtDesc("PENDING_REVIEW", storeId);
        Map<String, Object> reconciliation = inventoryService.getStoreReconciliation(storeId, LocalDate.now());
        List<PricingPromotionProposal> discountSuggestions = proposalRepository.findByStatusAndStoreIdOrderByCreatedAtDesc("SUGGESTED", storeId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("storeId", storeId);
        summary.put("businessDate", LocalDate.now().toString());
        summary.put("lowStockCount", lowStock.size());
        summary.put("lowStockItems", lowStock);
        summary.put("fulfillmentIssuesCount", fulfillmentIssues.size());
        summary.put("fulfillmentIssues", fulfillmentIssues);
        summary.put("pendingProposalCount", pendingProposals.size());
        summary.put("discountSuggestionCount", Math.max(discountSuggestions.size(), 0));
        summary.put("reconciliation", reconciliation);
        String dataStatus = inventoryService.storeDataStatus(storeId);
        summary.put("dataStatus", dataStatus);
        // An empty low-stock list from a store with no snapshots is not a stable store.
        summary.put("status", lowStock.size() > 0 || fulfillmentIssues.size() > 0 || pendingProposals.size() > 0 ? "ACTION_REQUIRED"
                : "NO_DATA".equals(dataStatus) ? "NO_DATA" : "STABLE");
        summary.put("recommendedActions", buildRecommendedActions(lowStock, fulfillmentIssues, pendingProposals));
        return summary;
    }

    private List<String> buildRecommendedActions(List<Map<String, Object>> lowStock, List<Map<String, Object>> fulfillmentIssues, List<PricingPromotionProposal> pendingProposals) {
        List<String> actions = new ArrayList<>();
        if (!lowStock.isEmpty()) {
            actions.add("Review low-stock SKUs and place replenishment orders.");
        }
        if (!fulfillmentIssues.isEmpty()) {
            actions.add("Investigate fulfillment exceptions and stale returns.");
        }
        if (!pendingProposals.isEmpty()) {
            actions.add("Approve or reject pending pricing and promotion proposals.");
        }
        if (actions.isEmpty()) {
            actions.add("No immediate action required.");
        }
        return actions;
    }
}
