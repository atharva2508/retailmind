package com.example.springbootpostgresapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The MCP endpoint lets an MCP client (Claude Desktop, Claude Code) call the same read-only tools as the chat. */
@SpringBootTest
@AutoConfigureMockMvc
class McpEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private ResultActions rpc(String token, String json) throws Exception {
        var request = post("/api/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(json);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    @Test
    void handshakeAndToolList() throws Exception {
        String manager = token("store_manager", "manager123");
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.result.protocolVersion", is("2025-06-18")))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name", is("retailmind")));
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("abc")))
                .andExpect(jsonPath("$.result.tools", hasSize(6)))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type", is("object")))
                // No tool lets the client choose a store.
                .andExpect(jsonPath("$.result.tools[?(@.inputSchema.properties.storeId)]").isEmpty());
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isMap());
    }

    @Test
    void toolCallStaysInTheCallersStore() throws Exception {
        String manager = token("store_manager", "manager123");
        // The client asks for store 2 inside the arguments. The manager belongs to store 1.
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"getLowStockProducts\",\"arguments\":{\"storeId\":2}}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError", is(false)))
                .andExpect(jsonPath("$.result.content[0].type", is("text")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"storeId\":1")));
        // A store manager may not point the endpoint at another store either.
        mockMvc.perform(post("/api/mcp").param("storeId", "2").header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void badCallsAreAnsweredInProtocolTerms() throws Exception {
        String manager = token("store_manager", "manager123");
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"applyProposal\",\"arguments\":{\"id\":1}}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError", is(true)));
        rpc(manager, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code", is(-32601)));
        rpc(manager, "{\"id\":7,\"method\":\"tools/list\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code", is(-32600)));
    }

    @Test
    void needsALoginAndAStore() throws Exception {
        rpc(null, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/list\"}").andExpect(status().isUnauthorized());

        String name = "mcp" + System.nanoTime();
        String registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", name, "email", name + "@x.test", "password", "secret123"))))
                .andReturn().getResponse().getContentAsString();
        rpc(objectMapper.readTree(registered).get("accessToken").asText(), "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}")
                .andExpect(status().isForbidden());
    }
}
