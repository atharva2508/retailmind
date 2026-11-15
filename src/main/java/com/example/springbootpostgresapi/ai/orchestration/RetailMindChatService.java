package com.example.springbootpostgresapi.ai.orchestration;

import com.example.springbootpostgresapi.ai.tools.RetailMindToolService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetailMindChatService {

    private final RetailMindToolService toolService;

    public Map<String, Object> answer(String message, Long storeId, String conversationId) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }

        String normalized = message.toLowerCase();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", UUID.randomUUID().toString());
        response.put("conversationId", conversationId != null ? conversationId : "default");

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<Map<String, Object>> resultPayload = new ArrayList<>();

        if ((normalized.contains("low stock") || normalized.contains("at risk") || (normalized.contains("stock") && normalized.contains("risk")))) {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            resultPayload.addAll(toolService.executeTool("getLowStockProducts", args));
            toolCalls.add(toolCall("getLowStockProducts", args));
        } else if (normalized.contains("summary") || normalized.contains("dashboard") || normalized.contains("overview") || normalized.contains("executive")) {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            resultPayload.add(toolService.executeTool("getDashboardSummary", args).get(0));
            toolCalls.add(toolCall("getDashboardSummary", args));
        } else if (normalized.contains("discount") || normalized.contains("promo") || normalized.contains("promotion") || normalized.contains("price cut")) {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            resultPayload.addAll(toolService.executeTool("getDiscountSuggestions", args));
            toolCalls.add(toolCall("getDiscountSuggestions", args));
        } else if (normalized.contains("recon") || normalized.contains("reconciliation")) {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            args.put("date", LocalDate.now().toString());
            List<Map<String, Object>> daily = toolService.executeTool("getDailyReconciliation", args);
            if (!daily.isEmpty()) {
                resultPayload.add(daily.get(0));
            }
            toolCalls.add(toolCall("getDailyReconciliation", args));
        } else if (normalized.contains("fulfillment") || normalized.contains("issue")) {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            args.put("from", LocalDate.now().minusDays(7).toString());
            args.put("to", LocalDate.now().toString());
            resultPayload.addAll(toolService.executeTool("getFulfillmentIssues", args));
            toolCalls.add(toolCall("getFulfillmentIssues", args));
        } else if (normalized.contains("product") && normalized.matches(".*\\d+.*")) {
            Long productId = extractProductId(message);
            Map<String, String> args = new LinkedHashMap<>();
            args.put("productId", productId.toString());
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            List<Map<String, Object>> productResponse = toolService.executeTool("getProductDetails", args);
            if (!productResponse.isEmpty()) {
                resultPayload.add(productResponse.get(0));
            }
            toolCalls.add(toolCall("getProductDetails", args));
        } else {
            Map<String, String> args = new LinkedHashMap<>();
            if (storeId != null) {
                args.put("storeId", storeId.toString());
            }
            resultPayload.addAll(toolService.executeTool("getLowStockProducts", args));
            toolCalls.add(toolCall("getLowStockProducts", args));
        }

        response.put("toolCalls", toolCalls);
        response.put("answer", summarize(resultPayload));
        response.put("data", resultPayload);
        return response;
    }

    private String summarize(List<Map<String, Object>> resultPayload) {
        if (resultPayload.isEmpty()) {
            return "I checked the current retail data and did not find a matching result for that request.";
        }

        if (resultPayload.size() == 1 && resultPayload.get(0).containsKey("expectedEodBal")) {
            Map<String, Object> reconciliation = resultPayload.get(0);
            return "Reconciliation summary: expected EOD balance " + reconciliation.get("expectedEodBal")
                    + ", bal variance " + reconciliation.get("balVariance") + ", RAC variance " + reconciliation.get("racVariance") + ".";
        }

        for (Map<String, Object> item : resultPayload) {
            Object status = item.get("status");
            if (status != null && (status.toString().contains("AT_RISK") || status.toString().contains("OUT_OF_STOCK"))) {
                return "The current inventory scan shows at-risk or unavailable stock. Review the returned items before ordering more inventory.";
            }
            Object proposalType = item.get("proposalType");
            if (proposalType != null && "DISCOUNT".equalsIgnoreCase(proposalType.toString())) {
                return "The discount suggestion engine recommends reviewing these SKUs for promotional action based on current risk and stock conditions.";
            }
            if (item.containsKey("lowStockCount") && item.containsKey("status")) {
                return "Here is the operational summary: " + item.get("lowStockCount") + " low-stock items, " + item.get("fulfillmentIssuesCount") + " fulfillment issues, and " + item.get("pendingProposalCount") + " pending pricing proposals.";
            }
        }

        return "The available retail data matches the request. Review the tool results for the detailed figures.";
    }

    private Map<String, Object> toolCall(String toolName, Map<String, String> args) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolName", toolName);
        item.put("args", args);
        return item;
    }

    private Long extractProductId(String message) {
        Matcher matcher = Pattern.compile("\\d+").matcher(message);
        if (matcher.find()) {
            return Long.parseLong(matcher.group());
        }
        throw new IllegalArgumentException("Could not determine productId from message");
    }
}
