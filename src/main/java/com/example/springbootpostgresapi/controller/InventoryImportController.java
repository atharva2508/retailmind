package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.dto.InventoryImportRequest;
import com.example.springbootpostgresapi.service.InventoryImportService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InventoryImportController {

    private final InventoryImportService inventoryImportService;

    @PostMapping("/api/admin/inventory-imports/synthetic")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> applySyntheticImport(@Valid @RequestBody InventoryImportRequest request) {
        return ResponseEntity.ok(inventoryImportService.applySyntheticImport(request));
    }

    @GetMapping("/api/admin/inventory-imports")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getRecentImports() {
        return ResponseEntity.ok(inventoryImportService.getRecentImports());
    }
}
