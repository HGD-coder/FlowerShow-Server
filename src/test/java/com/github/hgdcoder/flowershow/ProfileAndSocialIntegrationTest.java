package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.service.InteractionService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_profile_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000"
})
@AutoConfigureMockMvc
class ProfileAndSocialIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InteractionService interactionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void profilePrivacyContentTabsAndRelationListsFormAClosedLoop() throws Exception {
        String passwordHash = passwordEncoder.encode("Profile@123");
        createAccount("seed_u001", "profile_seed_one", passwordHash);
        createAccount("seed_u002", "profile_seed_two", passwordHash);
        createAccount("u001", "profile_author_one", passwordHash);
        String seedOneToken = login("profile_seed_one", "Profile@123");
        String seedTwoToken = login("profile_seed_two", "Profile@123");
        String authorOneToken = login("profile_author_one", "Profile@123");

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", bearer(seedOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("seed_u001"))
                .andExpect(jsonPath("$.ownProfile").value(true));

        mockMvc.perform(get("/api/v1/users/u001/profile")
                        .header("Authorization", bearer(seedOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u001"))
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.followedBy").value(false))
                .andExpect(jsonPath("$.followerCount").value(2))
                .andExpect(jsonPath("$.postCount").value(2))
                .andExpect(jsonPath("$.likedTabVisible").value(false));

        mockMvc.perform(get("/api/v1/users/seed_u001/profile")
                        .header("Authorization", bearer(seedOneToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownProfile").value(true))
                .andExpect(jsonPath("$.favoriteContentCount").value(2))
                .andExpect(jsonPath("$.favoritesTabVisible").value(true));

        mockMvc.perform(get("/api/v1/users/seed_u001/profile/contents")
                        .header("Authorization", bearer(seedOneToken))
                        .param("tab", "favorites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/v1/users/seed_u001/profile/contents")
                        .header("Authorization", bearer(seedTwoToken))
                        .param("tab", "favorites"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users/u001/followers")
                        .header("Authorization", bearer(seedTwoToken))
                        .param("keyword", "Balcony"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("seed_u001"));

        mockMvc.perform(get("/api/v1/users/seed_u001/following")
                        .header("Authorization", bearer(seedOneToken))
                        .param("keyword", "Urban"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("u002"))
                .andExpect(jsonPath("$.items[0].following").value(true));

        interactionService.likeContent("v003", "seed_u001");
        mockMvc.perform(patch("/api/v1/users/seed_u001/profile")
                        .header("Authorization", bearer(seedOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "handle": "balcony.lin",
                                  "showLikedOnProfile": true,
                                  "showFavoritesOnProfile": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("balcony.lin"))
                .andExpect(jsonPath("$.likedTabVisible").value(true));

        mockMvc.perform(get("/api/v1/users/seed_u001/profile")
                        .header("Authorization", bearer(seedTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedContentCount").value(1))
                .andExpect(jsonPath("$.favoriteContentCount").value(2));

        mockMvc.perform(patch("/api/v1/users/u001/contents/v001/profile-display")
                        .header("Authorization", bearer(authorOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showOnProfile\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showOnProfile").value(false));

        mockMvc.perform(get("/api/v1/users/u001/profile/contents")
                        .header("Authorization", bearer(seedOneToken))
                        .param("tab", "posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/v1/users/u001/profile/contents")
                        .header("Authorization", bearer(authorOneToken))
                        .param("tab", "posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/api/v1/users/u001/contents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("img001"));

        mockMvc.perform(patch("/api/v1/users/u001/profile")
                        .header("Authorization", bearer(seedOneToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"not allowed\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/users/seed_u002/following/u001")
                        .header("Authorization", bearer(seedTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(delete("/api/v1/users/seed_u002/following/u001")
                        .header("Authorization", bearer(seedTwoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.following").value(false));
    }

    private void createAccount(String userId, String username, String passwordHash) {
        jdbcTemplate.update("""
                insert into accounts (id, user_id, username, password_hash, role, status, generated)
                values (?, ?, ?, ?, 'user', 'active', false)
                """, "acc_" + userId, userId, username, passwordHash);
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
