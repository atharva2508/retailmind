package com.example.springbootpostgresapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.springbootpostgresapi.entity.AuditEvent;
import com.example.springbootpostgresapi.entity.OrderLine;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.repository.AuditEventRepository;
import com.example.springbootpostgresapi.entity.PricingPromotionProposal;
import com.example.springbootpostgresapi.repository.OrderLineRepository;
import com.example.springbootpostgresapi.repository.StoreRepository;
import com.example.springbootpostgresapi.repository.PricingPromotionProposalRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderLineRepository orderLineRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

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
                .andExpect(jsonPath("$.status", is("VARIANCE")));
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

        LocalDate importDay = LocalDate.now().minusDays(6);
        String requestBody = "{\"storeId\":2,\"snapshotType\":\"SOD\",\"asOf\":\"" + importDay + "\",\"fileName\":\"NORTH_" + importDay.toString().replace("-", "") + "_SOD.csv\",\"rows\":[{\"sku\":\"SKU-1001\",\"bal\":20,\"aud\":21,\"rac\":1,\"held\":3},{\"sku\":\"SKU-2002\",\"bal\":8,\"aud\":8,\"rac\":0,\"held\":1}]}";

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
                .andExpect(jsonPath("$.answer", containsString("STALE")))
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

    @Test
    void loginLocksAfterFiveFailuresForAnyUsername() throws Exception {
        String name = "lock_" + System.nanoTime();
        register(name);
        for (String username : new String[] {name, "ghost_" + System.nanoTime()}) {
            for (int i = 0; i < 5; i++) {
                login(username, "wrong-password").andExpect(status().isUnauthorized());
            }
            login(username, "secret123").andExpect(status().isTooManyRequests());
        }
    }

    @Test
    void usernamesAreCaseInsensitive() throws Exception {
        login("ADMIN", "admin123").andExpect(status().isOk());
        String name = "mixed_" + System.nanoTime();
        register(name);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name.toUpperCase() + "\",\"email\":\"other_" + name + "@x.test\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshTokenIsStoredHashed() throws Exception {
        String raw = register("hash_" + System.nanoTime()).get("refreshToken").asText();
        Integer plain = jdbcTemplate.queryForObject("select count(*) from refresh_tokens where token = ?", Integer.class, raw);
        if (plain != 0) {
            throw new AssertionError("Refresh token stored in plain text");
        }
    }

    @Test
    void reusedRefreshTokenRevokesAllTokensOfUser() throws Exception {
        String r1 = register("reuse_" + System.nanoTime()).get("refreshToken").asText();
        String r2 = objectMapper.readTree(refresh(r1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();
        refresh(r1).andExpect(status().isUnauthorized());
        refresh(r2).andExpect(status().isUnauthorized());
    }

    @Test
    void rotationDoesNotExtendSessionLifetime() throws Exception {
        String name = "life_" + System.nanoTime();
        String r1 = register(name).get("refreshToken").asText();
        refresh(r1).andExpect(status().isOk());
        Integer expiries = jdbcTemplate.queryForObject(
                "select count(distinct t.expiry_date) from refresh_tokens t join app_users u on t.user_id = u.id where u.username = ?",
                Integer.class, name);
        if (expiries != 1) {
            throw new AssertionError("Rotation extended the refresh token lifetime");
        }
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String r = register("logout_" + System.nanoTime()).get("refreshToken").asText();
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r + "\"}"))
                .andExpect(status().isNoContent());
        refresh(r).andExpect(status().isUnauthorized());
    }

    @Test
    void importFlagsAbsentSkusAndReturnsContentChecksum() throws Exception {
        LocalDate day = LocalDate.now().minusDays(1);
        String first = importSnapshot(day, "SOD", row("SKU-2002", 9, 1) + "," + row("SKU-3003", 4, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingSkuCount", is(1)))
                .andReturn().getResponse().getContentAsString();
        String second = importSnapshot(day, "SOD", row("SKU-2002", 8, 1) + "," + row("SKU-3003", 4, 0))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        if (objectMapper.readTree(first).path("checksum").asText().equals(objectMapper.readTree(second).path("checksum").asText())) {
            throw new AssertionError("Checksum does not depend on file content");
        }
        // SKU-1001 (product 1) was not in the file: zero stock, flagged absent, timestamp from the file date.
        mockMvc.perform(get("/api/products/1/details").param("storeId", "2")
                        .header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absent", is(true)))
                .andExpect(jsonPath("$.available", is(0)))
                .andExpect(jsonPath("$.dataStatus", is("FRESH")))
                .andExpect(jsonPath("$.asOf", is(day + "T00:00")));
    }

    @Test
    void importRejectsBadRowsNamesAndCoverage() throws Exception {
        LocalDate day = LocalDate.now().minusDays(8);
        importSnapshot(day, "SOD", row("SKU-2002", 9, 1) + "," + row("SKU-3003", -5, 0))
                .andExpect(status().isBadRequest());
        importSnapshot(day, "SOD", row("NOPE-1", 1, 0) + "," + row("NOPE-2", 1, 0))
                .andExpect(status().isBadRequest());
        // File name says EOD, body says SOD.
        mockMvc.perform(post("/api/admin/inventory-imports/synthetic").header("Authorization", "Bearer " + tokenFor("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":2,\"snapshotType\":\"SOD\",\"asOf\":\"" + day + "\",\"fileName\":\"NORTH_"
                                + day.toString().replace("-", "") + "_EOD.csv\",\"rows\":[" + row("SKU-2002", 9, 1) + "," + row("SKU-3003", 4, 0) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAndOldDataIsNotShownAsFresh() throws Exception {
        String admin = tokenFor("admin", "admin123");
        // Store 3 has no snapshots at all.
        mockMvc.perform(get("/api/products/1/details").param("storeId", "3").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus", is("NO_DATA")))
                .andExpect(jsonPath("$.asOf", nullValue()))
                .andExpect(jsonPath("$.available", nullValue()));
        mockMvc.perform(get("/api/stores/3/reconciliation").param("date", "2026-11-05").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NO_DATA")));
        // Store 1 only has the seeded snapshots from 2026-11-05.
        mockMvc.perform(get("/api/products/1/details").param("storeId", "1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus", is("STALE")));
        mockMvc.perform(get("/api/products/low-stock").param("storeId", "1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dataStatus", is("STALE")));
    }

    @Test
    void cancelBeforeConfirmationIsNotAddedBack() throws Exception {
        LocalDate day = LocalDate.now().minusDays(4);
        String rows = row("SKU-1001", 10, 0) + "," + row("SKU-2002", 10, 0) + "," + row("SKU-3003", 10, 0);
        importSnapshot(day, "SOD", rows).andExpect(status().isOk());
        importSnapshot(day, "EOD", rows).andExpect(status().isOk());
        orderLineRepository.save(OrderLine.builder().orderId("ORD-T-" + System.nanoTime()).lineNo(1)
                .store(storeRepository.findByStoreCode("NORTH").orElseThrow())
                .product(productRepository.findBySku("SKU-1001").orElseThrow())
                .qtyOrdered(2).qtyConfirmed(0).qtyCancelled(2).qtyShipped(0).status("CANCELLED").cancelReason("CUSTOMER")
                .createdAt(day.atTime(9, 0)).cancelledAt(day.atTime(10, 0)).build());

        mockMvc.perform(get("/api/stores/2/reconciliation").param("date", day.toString())
                        .header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedEodBal", is(30)))
                .andExpect(jsonPath("$.status", is("OK")));
    }

    @Test
    void corsOnlyAllowsConfiguredOrigins() throws Exception {
        mockMvc.perform(get("/api/health").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/health").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void auditEventsCannotBeChangedOrDeleted() {
        AuditEvent event = auditEventRepository.save(AuditEvent.builder().actionType("TEST").entityType("TEST")
                .actorUsername("tester").details("original").build());
        event.setDetails("tampered");
        auditEventRepository.save(event);
        if (!"original".equals(auditEventRepository.findById(event.getId()).orElseThrow().getDetails())) {
            throw new AssertionError("Audit event was changed");
        }
        try {
            auditEventRepository.deleteById(event.getId());
        } catch (RuntimeException expected) {
            // delete must be refused
        }
        if (!auditEventRepository.existsById(event.getId())) {
            throw new AssertionError("Audit event was deleted");
        }
    }

    @Test
    void loginAttemptsAreAudited() throws Exception {
        String name = "audit_" + System.nanoTime();
        register(name);
        login(name, "wrong-password").andExpect(status().isUnauthorized());
        login(name, "secret123").andExpect(status().isOk());
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionType == 'LOGIN_FAILED' && @.actorUsername == '" + name + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.actionType == 'LOGIN_SUCCESS' && @.actorUsername == '" + name + "')]").isNotEmpty());
    }

    @Test
    void productPriceCannotBeChangedByPutAndEditsAreAudited() throws Exception {
        String admin = tokenFor("admin", "admin123");
        Product keyboard = productRepository.findBySku("SKU-3003").orElseThrow();
        String body = "{\"sku\":\"SKU-3003\",\"name\":\"Mechanical Keyboard v2\",\"description\":\"Compact keyboard\",\"stock\":14,\"price\":";
        mockMvc.perform(put("/api/products/" + keyboard.getId()).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body + keyboard.getPrice().add(BigDecimal.TEN) + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/products/" + keyboard.getId()).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body + keyboard.getPrice() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Mechanical Keyboard v2")));
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionType == 'UPDATE' && @.entityType == 'PRODUCT' && @.entityId == " + keyboard.getId() + ")]").isNotEmpty());
    }

    @Test
    void selfRegisteredUserHasNoStoreAccess() throws Exception {
        String token = register("nostore_" + System.nanoTime()).get("accessToken").asText();
        for (String path : new String[] {"/api/dashboard/summary", "/api/products/low-stock", "/api/products", "/api/proposals"}) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"summary\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void lockoutIsPerClientAddressSoAStrangerCannotLockTheOwnerOut() throws Exception {
        String name = "owner_" + System.nanoTime();
        register(name);
        for (int i = 0; i < 5; i++) {
            login(name, "wrong-password").andExpect(status().isUnauthorized());
        }
        login(name, "secret123").andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/auth/login").with(request -> {
                    request.setRemoteAddr("10.0.0.9");
                    return request;
                }).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(name, "secret123"))))
                .andExpect(status().isOk());
    }

    @Test
    void failedLoginsDoNotFloodTheAuditTrail() throws Exception {
        String ghost = "ghost" + System.nanoTime() + "_";
        for (int i = 0; i < 10; i++) {
            login(ghost + i, "x").andExpect(status().isUnauthorized());
        }
        String name = "noisy_" + System.nanoTime();
        register(name);
        for (int i = 0; i < 9; i++) {
            login(name, "wrong-password");
        }
        Integer ghostRows = jdbcTemplate.queryForObject("select count(*) from audit_events where actor_username like ?", Integer.class, ghost + "%");
        Integer failed = jdbcTemplate.queryForObject("select count(*) from audit_events where actor_username = ? and action_type = 'LOGIN_FAILED'", Integer.class, name);
        Integer locked = jdbcTemplate.queryForObject("select count(*) from audit_events where actor_username = ? and action_type = 'LOGIN_LOCKED'", Integer.class, name);
        if (ghostRows != 0 || failed != 5 || locked != 1) {
            throw new AssertionError("Audit rows: unknown users " + ghostRows + " (want 0), failed " + failed + " (want 5), locked " + locked + " (want 1)");
        }
    }

    @Test
    void approvedProposalCannotBeRedirectedToAnotherProductBySkuSwap() throws Exception {
        String admin = tokenFor("admin", "admin123");
        Product keyboard = productRepository.findBySku("SKU-3003").orElseThrow();
        Product mouse = productRepository.findBySku("SKU-1001").orElseThrow();
        long id = createProposal(tokenFor("store_manager", "manager123"), "{\\\"discountPercent\\\":10}", "SKU-3003");
        mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        try {
            putProduct(admin, keyboard, "SKU-SWAP").andExpect(status().isOk());
            putProduct(admin, mouse, "SKU-3003").andExpect(status().isOk());
            mockMvc.perform(post("/api/proposals/" + id + "/apply").header("Authorization", "Bearer " + admin))
                    .andExpect(status().isConflict());
            if (productRepository.findById(mouse.getId()).orElseThrow().getPrice().compareTo(mouse.getPrice()) != 0) {
                throw new AssertionError("Proposal for the keyboard changed the mouse price");
            }
        } finally {
            putProduct(admin, mouse, "SKU-1001");
            putProduct(admin, keyboard, "SKU-3003");
        }
    }

    @Test
    void secondProposalOnAChangedPriceIsRefused() throws Exception {
        String manager = tokenFor("store_manager", "manager123");
        String reviewer = tokenFor("reviewer", "reviewer123");
        String admin = tokenFor("admin", "admin123");
        long first = createProposal(manager, "{\\\"discountPercent\\\":10}");
        long second = createProposal(manager, "{\\\"discountPercent\\\":10}");
        for (long id : new long[] {first, second}) {
            mockMvc.perform(post("/api/proposals/" + id + "/review").header("Authorization", "Bearer " + reviewer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/proposals/" + first + "/apply").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mockMvc.perform(post("/api/proposals/" + second + "/apply").header("Authorization", "Bearer " + admin)).andExpect(status().isConflict());
    }

    @Test
    void importRejectsNullRow() throws Exception {
        importSnapshot(LocalDate.now().minusDays(9), "SOD", "null," + row("SKU-2002", 5, 0) + "," + row("SKU-3003", 5, 0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void skuIsUniqueIgnoringCase() throws Exception {
        mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + tokenFor("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"sku-1001\",\"name\":\"twin\",\"price\":1,\"stock\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsOverlongPasswordsAndOddUsernames() throws Exception {
        String n = "rules_" + System.nanoTime();
        String[][] bad = {
                {n + "a", "a".repeat(73)},
                {n + "b", "p".repeat(100_000)},
                {"<img src=x onerror=alert(1)>", "secret123"},
                {"\uFF41dmin", "secret123"},
                {"ab", "secret123"}
        };
        for (String[] pair : bad) {
            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("username", pair[0], "email", "r" + System.nanoTime() + "@x.test", "password", pair[1]))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void proposalCreatorTypeComesFromLoginAndTypeMustBeKnown() throws Exception {
        String manager = tokenFor("store_manager", "manager123");
        mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DISCOUNT\",\"targetSku\":\"SKU-3003\",\"creatorType\":\"SYSTEM\",\"proposedValues\":\"{\\\"discountPercent\\\":5}\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creatorType", is("STORE_MANAGER")));
        mockMvc.perform(post("/api/proposals").header("Authorization", "Bearer " + manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalType\":\"DELETE_EVERYTHING\",\"targetSku\":\"SKU-3003\",\"proposedValues\":\"{\\\"discountPercent\\\":5}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importChecksContentDuplicatesAgeAndRecordsTheActor() throws Exception {
        LocalDate day = LocalDate.now().minusDays(10);
        String cable = row("SKU-2002", 5, 0);
        importSnapshot(day, "SOD", row("SKU-1001", 2, 500) + "," + cable).andExpect(status().isBadRequest());
        importSnapshot(day, "SOD", "{\"sku\":\"SKU-1001\",\"bal\":1.9,\"aud\":1,\"rac\":0,\"held\":0}," + cable).andExpect(status().isBadRequest());
        importSnapshot(LocalDate.now().minusDays(60), "SOD", row("SKU-1001", 7, 0) + "," + cable).andExpect(status().isBadRequest());
        importSnapshot(day, "SOD", row("SKU-1001", 7, 0) + "," + cable).andExpect(status().isOk());
        importSnapshot(day, "SOD", row("SKU-1001", 7, 0) + "," + cable).andExpect(status().isConflict());
        Integer bySystem = jdbcTemplate.queryForObject("select count(*) from audit_events where action_type = 'IMPORT' and actor_username = 'system'", Integer.class);
        if (bySystem != 0) {
            throw new AssertionError("Import audit rows name 'system' instead of the admin: " + bySystem);
        }
    }

    @Test
    void chatDoesNotPresentOldOrMissingDataAsCurrentAndDoesNotGuess() throws Exception {
        String admin = tokenFor("admin", "admin123");
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"reconciliation\",\"storeId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", containsString("No snapshot data")));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"storeId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCalls", empty()));
    }

    @Test
    void refusalsExplainThemselves() throws Exception {
        mockMvc.perform(post("/api/proposals/999999/review").header("Authorization", "Bearer " + tokenFor("reviewer", "reviewer123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("Proposal not found")));
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + tokenFor("user", "user123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", notNullValue()));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + tokenFor("user", "user123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.message", notNullValue()));
    }

    @Test
    void deletingAProductWithHistoryGivesAClearConflict() throws Exception {
        Product mouse = productRepository.findBySku("SKU-1001").orElseThrow();
        mockMvc.perform(delete("/api/products/" + mouse.getId()).header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("history")));
    }

    @Test
    void managerAndReviewerCanReadProductsAndReviewerCanChat() throws Exception {
        String reviewer = tokenFor("reviewer", "reviewer123");
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + reviewer)).andExpect(status().isOk());
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"low stock\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void storeManagerDoesNotSeeLoginEventsOfOtherUsers() throws Exception {
        tokenFor("user", "user123");
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + tokenFor("store_manager", "manager123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionType =~ /LOGIN.*/)]").isEmpty());
    }

    @Test
    void fulfillmentRangeMustBeInOrder() throws Exception {
        mockMvc.perform(get("/api/fulfillment-issues").param("from", "2030-01-01").param("to", "2020-01-01")
                        .header("Authorization", "Bearer " + tokenFor("admin", "admin123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardSaysWhenItHasNoOrOldData() throws Exception {
        String admin = tokenFor("admin", "admin123");
        mockMvc.perform(get("/api/dashboard/summary").param("storeId", "3").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus", is("NO_DATA")))
                .andExpect(jsonPath("$.status", is("NO_DATA")));
        mockMvc.perform(get("/api/dashboard/summary").param("storeId", "1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus", is("STALE")));
    }

    private ResultActions putProduct(String adminToken, Product product, String sku) throws Exception {
        return mockMvc.perform(put("/api/products/" + product.getId()).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("sku", sku, "name", product.getName(),
                        "description", product.getDescription(), "price", product.getPrice(), "stock", product.getStock()))));
    }

    private String row(String sku, int bal, int held) {
        return "{\"sku\":\"" + sku + "\",\"bal\":" + bal + ",\"aud\":" + bal + ",\"rac\":0,\"held\":" + held + "}";
    }

    private ResultActions importSnapshot(LocalDate day, String type, String rows) throws Exception {
        return mockMvc.perform(post("/api/admin/inventory-imports/synthetic").header("Authorization", "Bearer " + tokenFor("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":2,\"snapshotType\":\"" + type + "\",\"asOf\":\"" + day + "\",\"fileName\":\"NORTH_"
                        + day.toString().replace("-", "") + "_" + type + ".csv\",\"rows\":[" + rows + "]}"));
    }

    private JsonNode register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@x.test\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(username, password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
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
