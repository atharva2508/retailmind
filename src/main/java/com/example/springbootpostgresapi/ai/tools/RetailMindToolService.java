package com.example.springbootpostgresapi.ai.tools;

import com.example.springbootpostgresapi.service.InventoryService;
import com.example.springbootpostgresapi.service.PricingPromotionProposalService;
import com.example.springbootpostgresapi.service.RetailDashboardService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetailMindToolService {

    private final InventoryService inventoryService;
    private final PricingPromotionProposalService pricingPromotionProposalService;
    private final RetailDashboardService retailDashboardService;

    public List<Map<String, Object>> executeTool(String toolName, Map<String, String> args) {
        return switch (toolName) {
            case "getProductDetails" -> List.of(getProductDetails(args));
            case "getLowStockProducts" -> getLowStockProducts(args);
            case "getFulfillmentIssues" -> getFulfillmentIssues(args);
            case "getDailyReconciliation" -> List.of(getDailyReconciliation(args));
            case "getEssentialsAtRisk" -> getEssentialsAtRisk(args);
            case "getDiscountSuggestions" -> getDiscountSuggestions(args);
            case "getDashboardSummary" -> List.of(getDashboardSummary(args));
            default -> throw new IllegalArgumentException("Unsupported tool: " + toolName);
        };
    }

    private Map<String, Object> getProductDetails(Map<String, String> args) {
        Long productId = parseLong(args, "productId");
        Long storeId = parseLong(args, "storeId");
        return inventoryService.getProductDetails(productId, storeId);
    }

    private List<Map<String, Object>> getLowStockProducts(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        return inventoryService.getLowStock(storeId);
    }

    private List<Map<String, Object>> getFulfillmentIssues(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        LocalDate from = parseDate(args, "from").orElse(LocalDate.now().minusDays(7));
        LocalDate to = parseDate(args, "to").orElse(LocalDate.now());
        return inventoryService.getFulfillmentIssues(storeId, from, to);
    }

    private Map<String, Object> getDailyReconciliation(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        LocalDate date = parseDate(args, "date").orElse(LocalDate.now());
        return inventoryService.getStoreReconciliation(storeId, date);
    }

    private List<Map<String, Object>> getEssentialsAtRisk(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        return inventoryService.getLowStock(storeId);
    }

    private List<Map<String, Object>> getDiscountSuggestions(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        return pricingPromotionProposalService.suggestDiscounts(storeId).stream()
                .map(proposal -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("proposalType", proposal.getProposalType());
                    item.put("targetSku", proposal.getTargetSku());
                    item.put("status", proposal.getStatus());
                    item.put("rationale", proposal.getRationale());
                    item.put("proposedValues", proposal.getProposedValues());
                    item.put("sourceRefs", proposal.getSourceRefs());
                    return item;
                })
                .toList();
    }

    private Map<String, Object> getDashboardSummary(Map<String, String> args) {
        Long storeId = parseOptionalLong(args, "storeId").orElse(1L);
        return retailDashboardService.getStoreSummary(storeId);
    }

    private Long parseLong(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return Long.parseLong(value);
    }

    private Optional<Long> parseOptionalLong(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    private Optional<LocalDate> parseDate(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.parse(value));
    }
}
