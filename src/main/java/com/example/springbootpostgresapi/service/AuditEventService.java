package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.entity.AuditEvent;
import com.example.springbootpostgresapi.repository.AuditEventRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;

    public AuditEvent log(String actionType, String entityType, Long entityId, Long storeId, String actorUsername, String details) {
        AuditEvent event = AuditEvent.builder()
                .actionType(actionType)
                .entityType(entityType)
                .entityId(entityId)
                .storeId(storeId)
                .actorUsername(actorUsername == null || actorUsername.isBlank() ? "system" : actorUsername)
                .details(details == null || details.isBlank() ? "No details provided" : details)
                .build();
        return auditEventRepository.save(event);
    }

    public List<Map<String, Object>> getRecentEvents(Long scope) {
        List<AuditEvent> events = scope == null ? auditEventRepository.findTop200ByOrderByOccurredAtDesc()
                : auditEventRepository.findTop200ByStoreIdOrderByOccurredAtDesc(scope);
        return events.stream()
                .map(event -> {
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("id", event.getId());
                    result.put("actionType", event.getActionType());
                    result.put("entityType", event.getEntityType());
                    result.put("entityId", event.getEntityId());
                    result.put("storeId", event.getStoreId());
                    result.put("actorUsername", event.getActorUsername());
                    result.put("details", event.getDetails());
                    result.put("occurredAt", event.getOccurredAt().toString());
                    return result;
                })
                .collect(Collectors.toList());
    }
}
