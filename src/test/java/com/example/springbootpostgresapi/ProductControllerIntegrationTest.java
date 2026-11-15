package com.example.springbootpostgresapi;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.repository.PricingPromotionProposalRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PricingPromotionProposalRepository proposalRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void shouldServeHealthAndAllowAdminLogin() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        assertNotBlank(token);
    }

    @Test
    void shouldReturnLowStockAndReconciliationForAuthorizedAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/products/low-stock").param("storeId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("AT_RISK")));

        mockMvc.perform(get("/api/stores/1/reconciliation").param("date", "2026-11-05")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OK")));
    }

    @Test
    void shouldReturnInventoryDetailsForAuthorizedUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/products/1/details").param("storeId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Wireless Mouse")))
                .andExpect(jsonPath("$.available", is(10)));

        mockMvc.perform(get("/api/products/low-stock").param("storeId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku", is("SKU-2002")));
    }

    @Test
    void shouldApplySyntheticInventoryImportAndPersistLedger() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        String requestBody = "{\"storeId\":1,\"snapshotType\":\"SOD\",\"asOf\":\"2026-11-06\",\"fileName\":\"MAIN_20261106_SOD.csv\",\"rows\":[{\"sku\":\"SKU-1001\",\"bal\":20,\"aud\":21,\"rac\":1,\"held\":3},{\"sku\":\"SKU-2002\",\"bal\":8,\"aud\":8,\"rac\":0,\"held\":1}]}";

        mockMvc.perform(post("/api/admin/inventory-imports/synthetic")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPLIED")))
                .andExpect(jsonPath("$.rowCount", is(2)));

        mockMvc.perform(get("/api/admin/inventory-imports")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("APPLIED")));
    }

    @Test
    void shouldAnswerRetailQuestionUsingReadOnlyToolFlow() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"show me low stock for store 1\",\"storeId\":1,\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("The current inventory scan shows at-risk or unavailable stock. Review the returned items before ordering more inventory.")))
                .andExpect(jsonPath("$.toolCalls[0].toolName", is("getLowStockProducts")));
    }

    @Test
    void shouldCreateReviewAndApplyPricingPromotionProposal() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        MvcResult createResult = mockMvc.perform(post("/api/proposals")
                        .header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"SKU-2002\",\"creatorType\":\"STORE_MANAGER\",\"rationale\":\"Low stock risk on USB-C cable\",\"proposedValues\":\"{\\\"discountPercent\\\":10,\\\"durationDays\\\":7}\",\"sourceRefs\":\"inventory.low-stock.store.1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING_REVIEW")))
                .andReturn();

        Long proposalId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/proposals/" + proposalId + "/review")
                        .header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewerUsername\":\"admin\",\"reviewComment\":\"Approved based on low-stock risk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")));

        mockMvc.perform(post("/api/proposals/" + proposalId + "/apply")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPLIED")));
    }

    @Test
    void shouldSuggestDiscountsWhenAsked() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"which products need discount for store 1\",\"storeId\":1,\"conversationId\":\"conv-discount\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("The discount suggestion engine recommends reviewing these SKUs for promotional action based on current risk and stock conditions.")))
                .andExpect(jsonPath("$.toolCalls[0].toolName", is("getDiscountSuggestions")));
    }

    @Test
    void shouldAllowReviewerToReviewPendingProposals() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("reviewer", "reviewer123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/proposals/pending")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectCrossStoreAccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("store_manager", "manager123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/products/low-stock").param("storeId", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnRetailDashboardSummary() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/dashboard/summary").param("storeId", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTION_REQUIRED")))
                .andExpect(jsonPath("$.lowStockCount", is(1)));
    }

    @Test
    void shouldExposeAuditTrailForAuthorizedUsers() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/audit/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionType", notNullValue()))
                .andExpect(jsonPath("$[0].actorUsername", notNullValue()))
                .andExpect(jsonPath("$[0].occurredAt", notNullValue()));
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/products/low-stock").param("storeId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatRejectsAnotherStoreForStoreManager() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"low stock\",\"storeId\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + tokenFor("user", "user123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suggestionsRejectAnotherStore() throws Exception {
        mockMvc.perform(get("/api/proposals/suggestions").param("storeId", "2")
                        .header("Authorization", "Bearer " + tokenFor("user", "user123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidStoreIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/low-stock").param("storeId", "0")
                        .header("Authorization", "Bearer " + tokenFor("user", "user123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsAndAuditOfAnotherStoreAreHidden() throws Exception {
        String admin = tokenFor("admin", "admin123");
        MvcResult created = mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"SKU-2002\",\"storeId\":2,\"proposedValues\":\"{\\\"discountPercent\\\":5}\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String manager = tokenFor("store_manager", "manager123");

        mockMvc.perform(get("/api/proposals").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.storeId == 2)]").isEmpty());
    }

    @Test
    void reviewerComesFromLoginNotRequestBody() throws Exception {
        long id = createProposal(tokenFor("store_manager", "manager123"), "{\\\"discountPercent\\\":10}");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewerUsername\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerUsername", is("reviewer")));
    }

    @Test
    void storeManagerCannotReview() throws Exception {
        long id = createProposal(tokenFor("admin", "admin123"), "{\\\"discountPercent\\\":10}");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatorCannotApproveOwnProposal() throws Exception {
        String reviewer = tokenFor("reviewer", "reviewer123");
        long id = createProposal(reviewer, "{\\\"discountPercent\\\":10}");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewOnlyFromPendingReview() throws Exception {
        long id = createProposal(tokenFor("store_manager", "manager123"), "{\\\"discountPercent\\\":10}");
        String reviewer = tokenFor("reviewer", "reviewer123");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredProposalCannotBeReviewed() throws Exception {
        long id = createProposal(tokenFor("store_manager", "manager123"), "{\\\"discountPercent\\\":10}");
        PricingPromotionProposal p = proposalRepository.findById(id).orElseThrow();
        p.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        proposalRepository.save(p);
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void proposalWithoutPriceChangeIsRejected() throws Exception {
        mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"SKU-1001\",\"proposedValues\":\"{\\\"discountPercent\\\":150}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void applyChangesThePrice() throws Exception {
        BigDecimal before = productRepository.findBySku("SKU-1001").orElseThrow().getPrice();
        long id = createProposal(tokenFor("store_manager", "manager123"), "{\\\"newPrice\\\":" + before.add(BigDecimal.ONE) + "}", "SKU-1001");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        String admin = tokenFor("admin", "admin123");
        mockMvc.perform(post("/api/proposals/" + id + "/apply").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        if (productRepository.findBySku("SKU-1001").orElseThrow().getPrice().compareTo(before.add(BigDecimal.ONE)) != 0) {
            throw new AssertionError("Price was not changed by apply");
        }
        mockMvc.perform(post("/api/proposals/" + id + "/apply").header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    void badJwtReturns401() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor("test-only-secret-key-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder().subject("admin").claim("token_type", "access")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(key).compact();
        String unknownUser = Jwts.builder().subject("ghost").claim("token_type", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        for (String token : new String[] {"garbage", expired, unknownUser}) {
            mockMvc.perform(get("/api/products/low-stock").param("storeId", "1").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void badDateReturns400() throws Exception {
        mockMvc.perform(get("/api/stores/1/reconciliation").param("date", "not-a-date")
                        .header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badNumberReturns400() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + tokenFor("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"details for product 99999999999999999999999\",\"storeId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooLongForDbReturns400() throws Exception {
        mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + tokenFor("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-LONG\",\"name\":\"Long\",\"price\":1,\"stock\":1,\"description\":\"" + "x".repeat(600) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalTextSizeIsLimited() throws Exception {
        mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"SKU-2002\",\"proposedValues\":\"{\\\"discountPercent\\\":5}\",\"rationale\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private long createProposal(String token, String escapedValues) throws Exception {
        return createProposal(token, escapedValues, "SKU-2002");
    }

    private long createProposal(String token, String escapedValues, String sku) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"" + sku + "\",\"proposedValues\":\"" + escapedValues + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    private String tokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void assertNotBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new AssertionError("Expected token to be present");
        }
    }

    private record LoginRequest(String username, String password) {}
}
