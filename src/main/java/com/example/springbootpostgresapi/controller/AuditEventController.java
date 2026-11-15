package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.service.AuditEventService;
import com.example.springbootpostgresapi.service.StoreAccessService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final StoreAccessService storeAccessService;

    @GetMapping("/audit/events")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','REVIEWER')")
    public ResponseEntity<List<Map<String, Object>>> listAuditEvents(Authentication authentication) {
        return ResponseEntity.ok(auditEventService.getRecentEvents(storeAccessService.scopeOrNull(authentication)));
    }
}
