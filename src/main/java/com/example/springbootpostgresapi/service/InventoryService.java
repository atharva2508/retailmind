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
import java.time.LocalTime;
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

    private static final int STALE_AFTER_DAYS = 1;

    public Map<String, Object> getStoreReconciliation(Long storeId, LocalDate date) {
        Store store = requireStore(storeId);
        LocalDate businessDate = date != null ? date : LocalDate.now();

        List<StockSnapshot> sod = stockSnapshotRepository.findByStoreIdAndBusinessDateAndSnapshotType(storeId, businessDate, "SOD");
        List<StockSnapshot> eod = stockSnapshotRepository.findByStoreIdAndBusinessDateAndSnapshotType(storeId, businessDate, "EOD");
        List<StockSnapshot> previousEod = stockSnapshotRepository.findByStoreIdAndBusinessDateAndSnapshotType(storeId, businessDate.minusDays(1), "EOD");

        Map<String, Object> reconciliation = new LinkedHashMap<>();
        reconciliation.put("storeId", store.getId());
        reconciliation.put("storeCode", store.getStoreCode());
        reconciliation.put("businessDate", businessDate.toString());

        // Without both snapshots there is nothing to compare. Say so instead of reporting zeros as a clean day.
        if (sod.isEmpty() || eod.isEmpty()) {
            reconciliation.put("expectedEodBal", null);
            reconciliation.put("balVariance", null);
            reconciliation.put("racVariance", null);
            reconciliation.put("overnightGap", null);
            reconciliation.put("status", "NO_DATA");
            reconciliation.put("asOf", null);
            return reconciliation;
        }

        int sodBal = sod.stream().mapToInt(StockSnapshot::getBal).sum();
        int eodBal = eod.stream().mapToInt(StockSnapshot::getBal).sum();
        int sodRac = sod.stream().mapToInt(StockSnapshot::getRac).sum();
        int eodRac = eod.stream().mapToInt(StockSnapshot::getRac).sum();

        int confirmedToday = sumConfirmedToday(storeId, businessDate);
        int customerCancelledToday = sumCustomerCancelledToday(storeId, businessDate);
        int goodReturnsMovedToday = sumGoodReturnsMovedToday(storeId, businessDate);
        int returnsReceivedToday = sumReturnsReceivedToday(storeId, businessDate);
        int badReturnsSentToday = sumBadReturnsSentToday(storeId, businessDate);

        int expectedEodBal = sodBal - confirmedToday + customerCancelledToday + goodReturnsMovedToday;
        int expectedEodRac = sodRac + returnsReceivedToday - goodReturnsMovedToday - badReturnsSentToday;
        int balVariance = eodBal - expectedEodBal;
        int racVariance = eodRac - expectedEodRac;

        reconciliation.put("expectedEodBal", expectedEodBal);
        reconciliation.put("balVariance", balVariance);
        reconciliation.put("racVariance", racVariance);
        reconciliation.put("overnightGap", previousEod.isEmpty() ? null
                : sodBal - previousEod.stream().mapToInt(StockSnapshot::getBal).sum());
        reconciliation.put("status", balVariance == 0 && racVariance == 0 ? "OK" : "VARIANCE");
        reconciliation.put("asOf", eod.stream().map(StockSnapshot::getAsOf).max(Comparator.naturalOrder()).map(LocalDateTime::toString).orElse(null));
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
                .filter(line -> line.getReceivedAt() != null)
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

    // ponytail: loads every snapshot of the store and picks the newest per product in memory.
    // Replace with a "latest per product" query when a store holds more than a few thousand snapshots.
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
                item.put("absent", snapshot.getAbsent());
                item.put("asOf", snapshot.getAsOf().toString());
                item.put("dataStatus", dataStatus(snapshot));
                lowStock.add(item);
            }
        }
        return lowStock;
    }

    public Map<String, Object> getProductDetails(Long productId, Long storeId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + productId));

        StockSnapshot snapshot = latestSnapshot(storeId, productId);
        Integer available = snapshot != null ? Math.max(snapshot.getBal() - snapshot.getHeld(), 0) : null;
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
        details.put("inCheckout", snapshot != null ? snapshot.getHeld() : null);
        details.put("hold", hold);
        details.put("goodReturn", goodReturn);
        details.put("unavailable", unavailable);
        details.put("storeId", storeId);
        details.put("absent", snapshot != null && snapshot.getAbsent());
        details.put("asOf", snapshot != null ? snapshot.getAsOf().toString() : null);
        details.put("dataStatus", dataStatus(snapshot));
        return details;
    }

    private Store requireStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found: " + storeId));
    }

    /** NO_DATA: no snapshot. STALE: newest snapshot is older than yesterday. FRESH otherwise. */
    public static String dataStatus(StockSnapshot snapshot) {
        if (snapshot == null) {
            return "NO_DATA";
        }
        return snapshot.getBusinessDate().isBefore(LocalDate.now().minusDays(STALE_AFTER_DAYS)) ? "STALE" : "FRESH";
    }

    private int sumConfirmedToday(Long storeId, LocalDate businessDate) {
        return orderLineRepository.findByStoreIdAndConfirmedAtBetween(storeId, businessDate.atStartOfDay(), businessDate.atTime(LocalTime.MAX)).stream()
                .mapToInt(line -> Math.max(line.getQtyConfirmed(), 0))
                .sum();
    }

    private int sumCustomerCancelledToday(Long storeId, LocalDate businessDate) {
        return orderLineRepository.findByStoreIdAndCancelledAtBetween(storeId, businessDate.atStartOfDay(), businessDate.atTime(LocalTime.MAX)).stream()
                .filter(line -> "CUSTOMER".equals(line.getCancelReason()))
                .filter(line -> line.getConfirmedAt() != null && !line.getCancelledAt().isBefore(line.getConfirmedAt()))
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
        return returnLineRepository.findByStoreIdAndReceivedAtBetween(storeId, businessDate.atStartOfDay(), businessDate.atTime(LocalTime.MAX)).stream()
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
