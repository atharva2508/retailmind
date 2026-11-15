package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.OrderLine;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.entity.ReturnLine;
import com.example.springbootpostgresapi.entity.StockSnapshot;
import com.example.springbootpostgresapi.entity.Store;
import com.example.springbootpostgresapi.repository.OrderLineRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.example.springbootpostgresapi.repository.ReturnLineRepository;
import com.example.springbootpostgresapi.repository.StockSnapshotRepository;
import com.example.springbootpostgresapi.repository.StoreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final OrderLineRepository orderLineRepository;
    private final ReturnLineRepository returnLineRepository;

    public Map<String, Object> getStoreReconciliation(Long storeId, LocalDate date) {
        Store store = requireStore(storeId);
        LocalDate businessDate = date != null ? date : LocalDate.now();

        int sodBal = sumSnapshotValue(storeId, businessDate, "SOD", StockSnapshot::getBal);
        int eodBal = sumSnapshotValue(storeId, businessDate, "EOD", StockSnapshot::getBal);
        int sodRac = sumSnapshotValue(storeId, businessDate, "SOD", StockSnapshot::getRac);
        int eodRac = sumSnapshotValue(storeId, businessDate, "EOD", StockSnapshot::getRac);

        int confirmedToday = sumConfirmedToday(storeId, businessDate);
        int customerCancelledToday = sumCustomerCancelledToday(storeId, businessDate);
        int goodReturnsMovedToday = sumGoodReturnsMovedToday(storeId, businessDate);
        int returnsReceivedToday = sumReturnsReceivedToday(storeId, businessDate);
        int badReturnsSentToday = sumBadReturnsSentToday(storeId, businessDate);

        int expectedEodBal = sodBal - confirmedToday + customerCancelledToday + goodReturnsMovedToday;
        int expectedEodRac = sodRac + returnsReceivedToday - goodReturnsMovedToday - badReturnsSentToday;
        int overnightGap = sumSnapshotValue(storeId, businessDate, "SOD", StockSnapshot::getBal)
                - sumSnapshotValue(storeId, businessDate.minusDays(1), "EOD", StockSnapshot::getBal);

        Map<String, Object> reconciliation = new LinkedHashMap<>();
        reconciliation.put("storeId", store.getId());
        reconciliation.put("storeCode", store.getStoreCode());
        reconciliation.put("businessDate", businessDate.toString());
        reconciliation.put("expectedEodBal", expectedEodBal);
        reconciliation.put("balVariance", eodBal - expectedEodBal);
        reconciliation.put("racVariance", eodRac - expectedEodRac);
        reconciliation.put("overnightGap", overnightGap);
        reconciliation.put("status", "OK");
        reconciliation.put("asOf", businessDate.toString());
        return reconciliation;
    }

    public List<Map<String, Object>> getFulfillmentIssues(Long storeId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().minusDays(7);
        LocalDate end = to != null ? to : LocalDate.now();

        List<Map<String, Object>> issues = new ArrayList<>();

        List<OrderLine> notFoundLines = orderLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getStatus() != null && line.getStatus().equals("NOT_FOUND"))
                .filter(line -> line.getCancelledAt() != null)
                .filter(line -> !line.getCancelledAt().toLocalDate().isBefore(start)
                        && !line.getCancelledAt().toLocalDate().isAfter(end))
                .toList();

        if (!notFoundLines.isEmpty()) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("storeId", storeId);
            issue.put("issueType", "NOT_FOUND");
            issue.put("count", notFoundLines.size());
            issue.put("from", start.toString());
            issue.put("to", end.toString());
            issues.add(issue);
        }

        List<ReturnLine> staleGoodReturns = returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> "03".equals(line.getReturnCode()))
                .filter(line -> line.getMovedToBalAt() == null)
                .filter(line -> line.getReceivedAt().toLocalDate().isBefore(LocalDate.now().minusDays(2)))
                .toList();

        if (!staleGoodReturns.isEmpty()) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("storeId", storeId);
            issue.put("issueType", "GOOD_RETURN_STALE");
            issue.put("count", staleGoodReturns.size());
            issue.put("from", start.toString());
            issue.put("to", end.toString());
            issues.add(issue);
        }

        return issues;
    }

    public List<Map<String, Object>> getLowStock(Long storeId) {
        List<StockSnapshot> latestSnapshots = stockSnapshotRepository.findByStoreId(storeId).stream()
                .collect(Collectors.groupingBy(snapshot -> snapshot.getProduct().getId(), Collectors.maxBy(
                        Comparator.comparing(StockSnapshot::getBusinessDate).thenComparing(StockSnapshot::getAsOf))))
                .values()
                .stream()
                .filter(OptionalHelper::isPresent)
                .map(optional -> optional.get())
                .toList();

        List<Map<String, Object>> lowStock = new ArrayList<>();
        for (StockSnapshot snapshot : latestSnapshots) {
            Product product = snapshot.getProduct();
            int available = Math.max(snapshot.getBal() - snapshot.getHeld(), 0);
            int threshold = product.getDefaultReorderThreshold() > 0 ? product.getDefaultReorderThreshold() : 5;
            if (available <= threshold || available <= 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("productId", product.getId());
                item.put("sku", product.getSku());
                item.put("name", product.getName());
                item.put("available", available);
                item.put("storeId", storeId);
                item.put("status", available <= 0 ? "OUT_OF_STOCK" : "AT_RISK");
                lowStock.add(item);
            }
        }
        return lowStock;
    }

    public Map<String, Object> getProductDetails(Long productId, Long storeId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + productId));

        StockSnapshot snapshot = latestSnapshot(storeId, productId);
        int available = snapshot != null ? Math.max(snapshot.getBal() - snapshot.getHeld(), 0) : 0;
        int hold = orderLineRepository.findByStoreId(storeId).stream()
                .filter(line -> productId.equals(line.getProduct().getId()))
                .filter(line -> "CONFIRMED".equals(line.getStatus()) || "PARTIALLY_CONFIRMED".equals(line.getStatus()))
                .mapToInt(line -> Math.max(line.getQtyConfirmed() - line.getQtyCancelled() - line.getQtyShipped(), 0))
                .sum();
        int goodReturn = returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> productId.equals(line.getProduct().getId()))
                .filter(line -> "03".equals(line.getReturnCode()))
                .mapToInt(ReturnLine::getQty)
                .sum();
        int unavailable = returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> productId.equals(line.getProduct().getId()))
                .filter(line -> "09".equals(line.getReturnCode()))
                .mapToInt(ReturnLine::getQty)
                .sum();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productId", product.getId());
        details.put("sku", product.getSku());
        details.put("name", product.getName());
        details.put("available", available);
        details.put("inCheckout", snapshot != null ? snapshot.getHeld() : 0);
        details.put("hold", hold);
        details.put("goodReturn", goodReturn);
        details.put("unavailable", unavailable);
        details.put("storeId", storeId);
        details.put("asOf", snapshot != null ? snapshot.getAsOf().toString() : LocalDateTime.now().toString());
        return details;
    }

    private Store requireStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found: " + storeId));
    }

    private int sumSnapshotValue(Long storeId, LocalDate businessDate, String snapshotType, java.util.function.ToIntFunction<StockSnapshot> mapper) {
        return stockSnapshotRepository.findByStoreIdAndBusinessDateAndSnapshotType(storeId, businessDate, snapshotType).stream()
                .mapToInt(mapper)
                .sum();
    }

    private int sumConfirmedToday(Long storeId, LocalDate businessDate) {
        return orderLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getConfirmedAt() != null)
                .filter(line -> line.getConfirmedAt().toLocalDate().equals(businessDate))
                .mapToInt(line -> Math.max(line.getQtyConfirmed(), 0))
                .sum();
    }

    private int sumCustomerCancelledToday(Long storeId, LocalDate businessDate) {
        return orderLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getCancelledAt() != null)
                .filter(line -> line.getCancelledAt().toLocalDate().equals(businessDate))
                .filter(line -> "CUSTOMER".equals(line.getCancelReason()))
                .mapToInt(line -> Math.max(line.getQtyCancelled(), 0))
                .sum();
    }

    private int sumGoodReturnsMovedToday(Long storeId, LocalDate businessDate) {
        return returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getMovedToBalAt() != null)
                .filter(line -> "03".equals(line.getReturnCode()))
                .filter(line -> line.getMovedToBalAt().toLocalDate().equals(businessDate))
                .mapToInt(ReturnLine::getQty)
                .sum();
    }

    private int sumReturnsReceivedToday(Long storeId, LocalDate businessDate) {
        return returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getReceivedAt() != null)
                .filter(line -> line.getReceivedAt().toLocalDate().equals(businessDate))
                .mapToInt(ReturnLine::getQty)
                .sum();
    }

    private int sumBadReturnsSentToday(Long storeId, LocalDate businessDate) {
        return returnLineRepository.findByStoreId(storeId).stream()
                .filter(line -> line.getSentToVendorAt() != null)
                .filter(line -> "09".equals(line.getReturnCode()))
                .filter(line -> line.getSentToVendorAt().toLocalDate().equals(businessDate))
                .mapToInt(ReturnLine::getQty)
                .sum();
    }

    private StockSnapshot latestSnapshot(Long storeId, Long productId) {
        return stockSnapshotRepository.findByStoreIdAndProductId(storeId, productId).stream()
                .sorted(Comparator.comparing(StockSnapshot::getBusinessDate).thenComparing(StockSnapshot::getAsOf).reversed())
                .findFirst()
                .orElse(null);
    }

    private static final class OptionalHelper {
        private OptionalHelper() {
        }

        private static boolean isPresent(java.util.Optional<?> optional) {
            return optional.isPresent();
        }
    }
}
