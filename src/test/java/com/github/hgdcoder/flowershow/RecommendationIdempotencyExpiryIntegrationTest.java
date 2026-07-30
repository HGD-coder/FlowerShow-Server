package com.github.hgdcoder.flowershow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_recommendation_expiry_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000",
        "flower-show.recommendation.idempotency-ttl=1ms"
})
@AutoConfigureMockMvc
class RecommendationIdempotencyExpiryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void expiredClientRequestIdCanBeUsedForANewRequest() throws Exception {
        String first = perform(1).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Thread.sleep(20);
        String second = perform(2).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(first, second);
    }

    private org.springframework.test.web.servlet.ResultActions perform(int limit) throws Exception {
        return mockMvc.perform(post("/api/v1/feed/pages")
                .header("X-Install-Id", "install-expiring-idempotency")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "expiring-request",
                        "limit", limit
                ))));
    }
}
