package com.example.springbootpostgresapi.ai.mcp;

import com.example.springbootpostgresapi.ai.orchestration.ClaudeChatService;
import com.example.springbootpostgresapi.service.StoreAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Model Context Protocol endpoint ("Streamable HTTP" transport, plain JSON responses, no session).
 * An MCP client such as Claude Code or Claude Desktop can call the same six read-only tools as the chat.
 * Same rules as the chat: a login is required, the store comes from the caller, nothing here writes.
 * ponytail: hand-written JSON-RPC for five methods. Switch to the MCP Java SDK if prompts, resources or streaming are needed.
 */
@RestController
@RequiredArgsConstructor
public class McpController {

    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final ClaudeChatService claudeChatService;
    private final StoreAccessService storeAccessService;

    @PostMapping(value = "/api/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','REVIEWER','USER')")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody JsonNode message,
                                                      @RequestParam(required = false) Long storeId,
                                                      Authentication authentication) {
        // Resolved first: a caller without a store, or asking for someone else's, gets 403 before any protocol work.
        Long store = storeAccessService.resolveStore(authentication, storeId);

        JsonNode id = message.get("id");
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText()) || !message.hasNonNull("method")) {
            return ResponseEntity.ok(error(id, -32600, "Invalid Request"));
        }
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build(); // a notification expects no answer
        }

        JsonNode params = message.path("params");
        return switch (message.get("method").asText()) {
            case "initialize" -> {
                String requested = params.path("protocolVersion").asText();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
                result.put("capabilities", Map.of("tools", Map.of()));
                result.put("serverInfo", Map.of("name", "retailmind", "version", "0.0.1"));
                yield ResponseEntity.ok(result(id, result));
            }
            case "ping" -> ResponseEntity.ok(result(id, Map.of()));
            case "tools/list" -> ResponseEntity.ok(result(id, Map.of("tools", claudeChatService.toolDefinitions())));
            case "tools/call" -> {
                Map<String, String> arguments = new LinkedHashMap<>();
                params.path("arguments").fields().forEachRemaining(entry -> arguments.put(entry.getKey(), entry.getValue().asText()));
                ClaudeChatService.ToolOutcome outcome = claudeChatService.runTool(params.path("name").asText(), arguments, store);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("content", List.of(Map.of("type", "text", "text", outcome.json())));
                result.put("isError", outcome.error());
                yield ResponseEntity.ok(result(id, result));
            }
            default -> ResponseEntity.ok(error(id, -32601, "Method not found"));
        };
    }

    private Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(JsonNode id, int code, String text) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", text));
        return response;
    }
}
