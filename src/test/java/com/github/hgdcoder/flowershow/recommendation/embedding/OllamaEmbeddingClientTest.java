package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOllamaEmbedRequestAndNormalizesValidatedVectors() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, """
                {"model":"test-model","embeddings":[[3.0,4.0]]}
                """, requestBody);

        OllamaEmbeddingClient client = new OllamaEmbeddingClient(
                HttpClient.newHttpClient(),
                objectMapper,
                properties(2)
        );
        List<float[]> result = client.embed(List.of("semantic query"));

        assertEquals(1, result.size());
        assertEquals(0.6f, result.get(0)[0], 0.0001f);
        assertEquals(0.8f, result.get(0)[1], 0.0001f);

        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("test-model", request.path("model").asText());
        assertEquals(2, request.path("dimensions").asInt());
        assertEquals("semantic query", request.path("input").get(0).asText());
    }

    @Test
    void rejectsUnexpectedDimensionsAndProviderErrors() throws Exception {
        startServer(200, """
                {"model":"test-model","embeddings":[[1.0,2.0,3.0]]}
                """, new AtomicReference<>());
        OllamaEmbeddingClient invalidDimensionClient = new OllamaEmbeddingClient(
                HttpClient.newHttpClient(),
                objectMapper,
                properties(2)
        );
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> invalidDimensionClient.embed(List.of("query"))
        );
        server.stop(0);

        startServer(503, """
                {"error":"model unavailable"}
                """, new AtomicReference<>());
        OllamaEmbeddingClient unavailableClient = new OllamaEmbeddingClient(
                HttpClient.newHttpClient(),
                objectMapper,
                properties(2)
        );
        assertThrows(
                EmbeddingUnavailableException.class,
                () -> unavailableClient.embed(List.of("query"))
        );
    }

    private void startServer(
            int status,
            String response,
            AtomicReference<String> requestBody
    ) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private EmbeddingProperties properties(int dimensions) {
        return new EmbeddingProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-model",
                dimensions,
                4,
                20,
                0.35,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ZERO,
                Duration.ofMinutes(10)
        );
    }
}
