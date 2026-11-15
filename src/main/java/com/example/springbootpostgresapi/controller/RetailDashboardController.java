package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.service.RetailDashboardService;
import com.example.springbootpostgresapi.service.StoreAccessService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RetailDashboardController {

    private final RetailDashboardService retailDashboardService;
    private final StoreAccessService storeAccessService;

    @GetMapping("/dashboard/summary")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER','REVIEWER')")
    public ResponseEntity<Map<String, Object>> getStoreSummary(@RequestParam(required = false) Long storeId,
                                                            Authentication authentication) {
        Long store = storeAccessService.resolveStore(authentication, storeId);
        return ResponseEntity.ok(retailDashboardService.getStoreSummary(store));
    }
}
