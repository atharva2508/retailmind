package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.service.InventoryService;
import com.example.springbootpostgresapi.service.StoreAccessService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class RetailController {

    private final InventoryService inventoryService;
    private final StoreAccessService storeAccessService;

    private static final int MAX_RANGE_DAYS = 31;

    @GetMapping("/api/stores/{id}/reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER')")
    public ResponseEntity<Map<String, Object>> getReconciliation(@PathVariable Long id,
                                                               @RequestParam(required = false) String date,
                                                               Authentication authentication) {
        Long store = storeAccessService.resolveStore(authentication, id);
        LocalDate businessDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(inventoryService.getStoreReconciliation(store, businessDate));
    }

    // One request for a chart of many days, instead of one request per day (which tripped the rate limit).
    @GetMapping("/api/stores/{id}/reconciliation/range")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER')")
    public ResponseEntity<List<Map<String, Object>>> getReconciliationRange(@PathVariable Long id,
                                                                          @RequestParam String from,
                                                                          @RequestParam String to,
                                                                          Authentication authentication) {
        Long store = storeAccessService.resolveStore(authentication, id);
        LocalDate start = LocalDate.parse(from);
        LocalDate end = LocalDate.parse(to);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (start.plusDays(MAX_RANGE_DAYS - 1).isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range is limited to " + MAX_RANGE_DAYS + " days");
        }
        // ponytail: one reconciliation per day, a few queries each (about 40 ms a day). Batch the queries if ranges grow.
        return ResponseEntity.ok(start.datesUntil(end.plusDays(1)).map(day -> inventoryService.getStoreReconciliation(store, day)).toList());
    }

    @GetMapping("/api/fulfillment-issues")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public ResponseEntity<List<Map<String, Object>>> getFulfillmentIssues(@RequestParam(required = false) Long storeId,
                                                                         @RequestParam(required = false) String from,
                                                                         @RequestParam(required = false) String to,
                                                                         Authentication authentication) {
        Long resolvedStoreId = storeAccessService.resolveStore(authentication, storeId);
        LocalDate start = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(7);
        LocalDate end = to != null ? LocalDate.parse(to) : LocalDate.now();
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return ResponseEntity.ok(inventoryService.getFulfillmentIssues(resolvedStoreId, start, end));
    }

    @GetMapping("/api/products/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public ResponseEntity<List<Map<String, Object>>> getLowStock(@RequestParam(required = false) Long storeId,
                                                               Authentication authentication) {
        Long resolvedStoreId = storeAccessService.resolveStore(authentication, storeId);
        return ResponseEntity.ok(inventoryService.getLowStock(resolvedStoreId));
    }

    @GetMapping("/api/products/{id}/details")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public ResponseEntity<Map<String, Object>> getProductDetails(@PathVariable Long id,
                                                               @RequestParam(required = false) Long storeId,
                                                               Authentication authentication) {
        Long resolvedStoreId = storeAccessService.resolveStore(authentication, storeId);
        return ResponseEntity.ok(inventoryService.getProductDetails(id, resolvedStoreId));
    }
}
