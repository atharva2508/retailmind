package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.InventoryImportRequest;
import com.example.springbootpostgresapi.entity.InventoryImport;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.entity.StockSnapshot;
import com.example.springbootpostgresapi.entity.Store;
import com.example.springbootpostgresapi.repository.InventoryImportRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.example.springbootpostgresapi.repository.StockSnapshotRepository;
import com.example.springbootpostgresapi.repository.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InventoryImportService {

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([A-Za-z0-9_-]+)_(\\d{8})_(SOD|EOD)\\.csv$");

    private final InventoryImportRepository inventoryImportRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public Map<String, Object> applySyntheticImport(InventoryImportRequest request) {
        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found: " + request.storeId()));

        if (!store.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store is inactive: " + store.getStoreCode());
        }

        String snapshotType = request.snapshotType() == null ? "" : request.snapshotType().trim().toUpperCase();
        if (!snapshotType.equals("SOD") && !snapshotType.equals("EOD")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot type must be SOD or EOD");
        }

        String fileName = request.fileName() == null ? "" : request.fileName().trim();
        Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(store.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename must match <STORE_CODE>_<yyyyMMdd>_<SOD|EOD>.csv and belong to the selected store");
        }

        LocalDate asOf;
        try {
            asOf = LocalDate.parse(request.asOf());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf must be a valid ISO date");
        }
        if (asOf.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf cannot be in the future");
        }

        List<InventoryImportRequest.InventoryRow> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one inventory row is required");
        }

        Set<String> seenSkus = new HashSet<>();
        int unknownSkuCount = 0;
        int missingSkuCount = 0;
        int audMismatchCount = 0;
        StringBuilder errorSummary = new StringBuilder();

        long activeProductCount = productRepository.count();
        int minCoverage = Math.max(1, (int) Math.ceil(activeProductCount * 0.5));
        if (rows.size() < minCoverage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Import rejected: file coverage is below the minimum threshold (" + minCoverage + " rows required)");
        }

        for (InventoryImportRequest.InventoryRow row : rows) {
            String sku = row.sku() == null ? "" : row.sku().trim();
            if (sku.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU is required for every row");
            }
            if (!seenSkus.add(sku.toUpperCase())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate SKU in file: " + sku);
            }

            Optional<Product> productOpt = productRepository.findBySku(sku);
            if (productOpt.isEmpty()) {
                unknownSkuCount++;
                continue;
            }

            if (row.aud() != null && row.bal() + row.rac() != row.aud()) {
                audMismatchCount++;
            }
            Product product = productOpt.get();
            StockSnapshot snapshot = stockSnapshotRepository.findByStoreIdAndProductId(store.getId(), product.getId()).stream()
                    .filter(existing -> existing.getBusinessDate().equals(asOf) && existing.getSnapshotType().equals(snapshotType))
                    .findFirst()
                    .orElseGet(() -> StockSnapshot.builder()
                            .store(store)
                            .product(product)
                            .businessDate(asOf)
                            .snapshotType(snapshotType)
                            .asOf(LocalDateTime.of(asOf, LocalDateTime.now().toLocalTime()))
                            .bal(0)
                            .aud(0)
                            .rac(0)
                            .held(0)
                            .absent(false)
                            .build());

            snapshot.setBal(row.bal());
            snapshot.setAud(row.aud());
            snapshot.setRac(row.rac());
            snapshot.setHeld(row.held());
            snapshot.setAsOf(LocalDateTime.of(asOf, LocalDateTime.now().toLocalTime()));
            snapshot.setAbsent(false);
            stockSnapshotRepository.save(snapshot);
        }

        if (unknownSkuCount > 0) {
            missingSkuCount = unknownSkuCount;
            errorSummary.append("Unknown SKUs: ").append(unknownSkuCount).append("; ");
        }
        if (audMismatchCount > 0) {
            errorSummary.append("AUD mismatch rows: ").append(audMismatchCount).append("; ");
        }

        String checksum = sha256(fileName + "|" + store.getStoreCode() + "|" + snapshotType + "|" + asOf + "|" + rows.size());
        InventoryImport inventoryImport = InventoryImport.builder()
                .storeId(store.getId())
                .storeCodeRaw(store.getStoreCode())
                .fileName(fileName)
                .checksum(checksum)
                .snapshotType(snapshotType)
                .asOf(asOf)
                .status("APPLIED")
                .rowCount(rows.size())
                .unknownSkuCount(unknownSkuCount)
                .missingSkuCount(missingSkuCount)
                .errorSummary(errorSummary.length() == 0 ? null : errorSummary.toString())
                .build();

        InventoryImport saved = inventoryImportRepository.save(inventoryImport);
        auditEventService.log("IMPORT", "INVENTORY_IMPORT", saved.getId(), saved.getStoreId(), "system", "Imported file " + saved.getFileName() + " with row count " + saved.getRowCount());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("storeId", saved.getStoreId());
        result.put("fileName", saved.getFileName());
        result.put("snapshotType", saved.getSnapshotType());
        result.put("status", saved.getStatus());
        result.put("rowCount", saved.getRowCount());
        result.put("unknownSkuCount", saved.getUnknownSkuCount());
        result.put("missingSkuCount", saved.getMissingSkuCount());
        result.put("asOf", saved.getAsOf().toString());
        return result;
    }

    public List<Map<String, Object>> getRecentImports() {
        return inventoryImportRepository.findAllByOrderByReceivedAtDesc().stream()
                .map(importRecord -> {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("id", importRecord.getId());
                    record.put("storeId", importRecord.getStoreId());
                    record.put("storeCode", importRecord.getStoreCodeRaw());
                    record.put("fileName", importRecord.getFileName());
                    record.put("snapshotType", importRecord.getSnapshotType());
                    record.put("status", importRecord.getStatus());
                    record.put("rowCount", importRecord.getRowCount());
                    record.put("asOf", importRecord.getAsOf().toString());
                    record.put("receivedAt", importRecord.getReceivedAt().toString());
                    return record;
                })
                .collect(Collectors.toList());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate checksum", e);
        }
    }
}
