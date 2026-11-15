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
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final int MAX_AGE_DAYS = 30;

    private final InventoryImportRepository inventoryImportRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public Map<String, Object> applySyntheticImport(InventoryImportRequest request, String actor) {
        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found: " + request.storeId()));

        if (!store.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store is inactive: " + store.getStoreCode());
        }

        String snapshotType = request.snapshotType() == null ? "" : request.snapshotType().trim().toUpperCase();
        if (!snapshotType.equals("SOD") && !snapshotType.equals("EOD")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot type must be SOD or EOD");
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
        // ponytail: fixed window. Make it a property if real backfills need older days.
        if (asOf.isBefore(LocalDate.now().minusDays(MAX_AGE_DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf is older than " + MAX_AGE_DAYS + " days");
        }

        // The file name must agree with the body: same store, same date, same snapshot type.
        String fileName = request.fileName() == null ? "" : request.fileName().trim();
        Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(store.getStoreCode())
                || !matcher.group(2).equals(asOf.format(DateTimeFormatter.BASIC_ISO_DATE))
                || !matcher.group(3).equals(snapshotType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Filename must match <STORE_CODE>_<yyyyMMdd>_<SOD|EOD>.csv and agree with store, asOf and snapshot type");
        }

        List<InventoryImportRequest.InventoryRow> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one inventory row is required");
        }

        // Snapshot time comes from the file date, not from the clock at import time.
        LocalDateTime snapshotTime = snapshotType.equals("SOD") ? asOf.atStartOfDay() : asOf.atTime(23, 59, 59);
        Map<String, Product> productsBySku = productRepository.findAll().stream()
                .collect(Collectors.toMap(product -> product.getSku().toUpperCase(), product -> product, (first, twin) -> first));

        Set<String> seenSkus = new HashSet<>();
        int unknownSkuCount = 0;
        int audMismatchCount = 0;
        StringBuilder content = new StringBuilder();

        for (InventoryImportRequest.InventoryRow row : rows) {
            String sku = row.sku() == null ? "" : row.sku().trim().toUpperCase();
            if (sku.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU is required for every row");
            }
            if (!seenSkus.add(sku)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate SKU in file: " + sku);
            }
            if (row.held() > row.bal()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HELD cannot exceed BAL for SKU " + sku);
            }
            content.append(sku).append(',').append(row.bal()).append(',').append(row.aud()).append(',')
                    .append(row.rac()).append(',').append(row.held()).append('\n');

            Product product = productsBySku.get(sku);
            if (product == null) {
                unknownSkuCount++;
                continue;
            }
            if (row.bal() + row.rac() != row.aud()) {
                audMismatchCount++;
            }
            saveSnapshot(store, product, asOf, snapshotType, snapshotTime, row.bal(), row.aud(), row.rac(), row.held(), false);
        }

        // Coverage counts only SKUs we know. Throwing here rolls back every snapshot written above.
        int knownSkuCount = rows.size() - unknownSkuCount;
        int minCoverage = Math.max(1, (int) Math.ceil(productsBySku.size() * 0.5));
        if (knownSkuCount < minCoverage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Import rejected: file covers " + knownSkuCount + " known SKUs, " + minCoverage + " required");
        }

        // A product that is not in the file has no stock in this store: record 0 and flag it.
        int missingSkuCount = 0;
        for (Map.Entry<String, Product> entry : productsBySku.entrySet()) {
            if (!seenSkus.contains(entry.getKey())) {
                missingSkuCount++;
                saveSnapshot(store, entry.getValue(), asOf, snapshotType, snapshotTime, 0, 0, 0, 0, true);
            }
        }

        StringBuilder errorSummary = new StringBuilder();
        if (unknownSkuCount > 0) {
            errorSummary.append("Unknown SKUs: ").append(unknownSkuCount).append("; ");
        }
        if (missingSkuCount > 0) {
            errorSummary.append("Absent SKUs set to 0: ").append(missingSkuCount).append("; ");
        }
        if (audMismatchCount > 0) {
            errorSummary.append("AUD mismatch rows: ").append(audMismatchCount).append("; ");
        }

        String checksum = sha256(content.toString());
        // Same store, day, type and content was already applied. Throwing rolls back the snapshots written above.
        if (inventoryImportRepository.existsByStoreIdAndSnapshotTypeAndAsOfAndChecksum(store.getId(), snapshotType, asOf, checksum)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This file was already imported");
        }
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
        auditEventService.log("IMPORT", "INVENTORY_IMPORT", saved.getId(), saved.getStoreId(), actor, "Imported file " + saved.getFileName() + " with row count " + saved.getRowCount());
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
        result.put("checksum", saved.getChecksum());
        return result;
    }

    private void saveSnapshot(Store store, Product product, LocalDate asOf, String snapshotType, LocalDateTime snapshotTime,
                              int bal, int aud, int rac, int held, boolean absent) {
        StockSnapshot snapshot = stockSnapshotRepository.findByStoreIdAndProductId(store.getId(), product.getId()).stream()
                .filter(existing -> existing.getBusinessDate().equals(asOf) && existing.getSnapshotType().equals(snapshotType))
                .findFirst()
                .orElseGet(() -> StockSnapshot.builder().store(store).product(product).businessDate(asOf).snapshotType(snapshotType).build());
        snapshot.setBal(bal);
        snapshot.setAud(aud);
        snapshot.setRac(rac);
        snapshot.setHeld(held);
        snapshot.setAsOf(snapshotTime);
        snapshot.setAbsent(absent);
        stockSnapshotRepository.save(snapshot);
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
