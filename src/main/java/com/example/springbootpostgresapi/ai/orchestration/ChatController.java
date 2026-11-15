package com.example.springbootpostgresapi.ai.orchestration;

import com.example.springbootpostgresapi.service.StoreAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final RetailMindChatService chatService;
    private final StoreAccessService storeAccessService;

    @PostMapping("/chat")
    @PreAuthorize("hasAnyRole('ADMIN','STORE_MANAGER','USER')")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request,
                                                    Authentication authentication) {
        Long storeId = storeAccessService.resolveStore(authentication, request.storeId());
        return ResponseEntity.ok(chatService.answer(request.message(), storeId, request.conversationId()));
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message, Long storeId, @Size(max = 64) String conversationId) {}
}
