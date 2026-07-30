package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class OllamaEmbeddingClient implements EmbeddingClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingProperties properties;
    private final URI endpoint;

    public OllamaEmbeddingClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            EmbeddingProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.endpoint = endpoint(properties.baseUrl());
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > properties.batchSize()) {
            throw new IllegalArgumentException("Embedding batch exceeds configured batch-size.");
        }
        if (inputs.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank.");
        }

        String json = writeRequest(new EmbedRequest(
                properties.model(),
                List.copyOf(inputs),
                true,
                properties.dimensions()
        ));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Embedding request was interrupted.", error);
        } catch (IOException | RuntimeException error) {
            throw new EmbeddingUnavailableException("Embedding transport failed.", error);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new EmbeddingUnavailableException(
                    "Embedding provider returned HTTP " + response.statusCode() + "."
            );
        }
        EmbedResponse payload;
        try {
            payload = objectMapper.readValue(response.body(), EmbedResponse.class);
        } catch (JsonProcessingException error) {
            throw new EmbeddingUnavailableException("Embedding response was invalid JSON.", error);
        }
        return validateAndNormalize(payload, inputs.size());
    }

    private List<float[]> validateAndNormalize(EmbedResponse payload, int expectedCount) {
        if (payload == null
                || payload.embeddings() == null
                || payload.embeddings().size() != expectedCount) {
            throw new EmbeddingUnavailableException("Embedding response count did not match request.");
        }
        return payload.embeddings().stream().map(values -> {
            if (values == null || values.size() != properties.dimensions()) {
                throw new EmbeddingUnavailableException(
                        "Embedding response dimension did not match configuration."
                );
            }
            double normSquared = 0.0;
            float[] vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) {
                Double value = values.get(index);
                if (value == null || !Double.isFinite(value)) {
                    throw new EmbeddingUnavailableException(
                            "Embedding response contained a non-finite value."
                    );
                }
                vector[index] = value.floatValue();
                normSquared += value * value;
            }
            if (!Double.isFinite(normSquared) || normSquared <= 0.0) {
                throw new EmbeddingUnavailableException("Embedding response contained a zero vector.");
            }
            double norm = Math.sqrt(normSquared);
            for (int index = 0; index < vector.length; index++) {
                vector[index] = (float) (vector[index] / norm);
            }
            return vector;
        }).toList();
    }

    private String writeRequest(EmbedRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize embedding request.", error);
        }
    }

    private static URI endpoint(String baseUrl) {
        String value = baseUrl.replaceAll("/+$", "");
        return URI.create(value.endsWith("/api") ? value + "/embed" : value + "/api/embed");
    }

    private record EmbedRequest(
            String model,
            List<String> input,
            boolean truncate,
            int dimensions
    ) {
    }

    private record EmbedResponse(String model, List<List<Double>> embeddings) {
    }
}
