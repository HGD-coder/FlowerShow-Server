package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_auth_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void registrationJwtRefreshRotationLogoutAndLoginProtectionWork() throws Exception {
        JsonNode registered = register("Garden.User", "Garden@123", "Garden User");
        String userId = registered.path("user").path("userId").asText();
        String accessToken = registered.path("accessToken").asText();
        String refreshToken = registered.path("refreshToken").asText();

        assertTrue(accessToken.split("\\.").length == 3);
        assertEquals(900, registered.path("accessTokenExpiresInSeconds").asLong());
        assertEquals("garden.user", registered.path("user").path("username").asText());

        String storedPassword = jdbcTemplate.queryForObject(
                "select password_hash from accounts where user_id = ?",
                String.class,
                userId
        );
        assertNotNull(storedPassword);
        assertTrue(storedPassword.startsWith("$2"));

        String storedRefreshToken = jdbcTemplate.queryForObject(
                "select token_hash from auth_refresh_tokens where account_id = ?",
                String.class,
                registered.path("user").path("accountId").asText()
        );
        assertEquals(64, storedRefreshToken.length());
        assertNotEquals(refreshToken, storedRefreshToken);

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("X-User-Id", userId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value("garden.user"));
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownProfile").value(true));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("GARDEN.USER", "Another@123", "Duplicate")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("garden.user", "wrong-password")))
                .andExpect(status().isUnauthorized());
        assertEquals(1, jdbcTemplate.queryForObject(
                "select failed_login_attempts from accounts where user_id = ?",
                Integer.class,
                userId
        ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("garden.user", "Garden@123")))
                .andExpect(status().isOk());
        assertEquals(0, jdbcTemplate.queryForObject(
                "select failed_login_attempts from accounts where user_id = ?",
                Integer.class,
                userId
        ));

        JsonNode refreshed = responseJson(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String refreshedAccessToken = refreshed.path("accessToken").asText();
        String replacementRefreshToken = refreshed.path("refreshToken").asText();
        assertNotEquals(refreshToken, replacementRefreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/users/u001/profile")
                        .header("Authorization", bearer(refreshedAccessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"not my profile\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(refreshedAccessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(replacementRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(replacementRefreshToken)))
                .andExpect(status().isUnauthorized());

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("garden.user", "still-wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("garden.user", "Garden@123")))
                .andExpect(status().isTooManyRequests());
        assertNotNull(jdbcTemplate.queryForObject(
                "select locked_until from accounts where user_id = ?",
                java.sql.Timestamp.class,
                userId
        ));
    }

    private JsonNode register(String username, String password, String nickname) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username, password, nickname)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return responseJson(response);
    }

    private String registerJson(String username, String password, String nickname) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password,
                "nickname", nickname
        ));
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String refreshJson(String refreshToken) throws Exception {
        return objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
    }

    private JsonNode responseJson(String response) throws Exception {
        return objectMapper.readTree(response);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
