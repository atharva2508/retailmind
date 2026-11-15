package com.example.springbootpostgresapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Runs on a real embedded server. MockMvc never forwards to /error, so it cannot see
 * errors that the container resolves there. Uses its own in-memory database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:realserver;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class RealServerErrorStatusTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void containerErrorsKeepTheirRealStatus() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(400, rest.exchange("/api/auth/login", HttpMethod.POST, new HttpEntity<>("{\"username\":", json), String.class)
                .getStatusCode().value(), "malformed JSON");

        String token = (String) rest.postForObject("/api/auth/login",
                new HttpEntity<>(Map.of("username", "admin", "password", "admin123"), json), Map.class).get("accessToken");
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);
        auth.setContentType(MediaType.APPLICATION_JSON);

        assertEquals(404, rest.exchange("/api/nope", HttpMethod.GET, new HttpEntity<>(auth), String.class).getStatusCode().value(), "unknown path");
        assertEquals(405, rest.exchange("/api/proposals", HttpMethod.DELETE, new HttpEntity<>(auth), String.class).getStatusCode().value(), "wrong method");
        assertEquals(400, rest.exchange("/api/chat", HttpMethod.POST, new HttpEntity<>("{\"message\":", auth), String.class).getStatusCode().value(), "malformed JSON with token");
        // Still protected: no token on an unknown path stays 401.
        assertEquals(401, rest.exchange("/api/nope", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class).getStatusCode().value(), "no token");
    }
}
