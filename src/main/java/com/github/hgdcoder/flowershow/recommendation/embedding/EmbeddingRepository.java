package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.ContentSourceRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.HashRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.ScoreRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.WeightedInterestRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.FloatVectorTypeHandler;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository;
import com.github.hgdcoder.flowershow.recommendation.RecommendationRepository.SuggestionCandidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingRepository {

    private static final int MAX_SOURCE_TEXT_LENGTH = 8_000;
    private static final int MAX_PROFILE_TERMS = 50;

    private final EmbeddingMapper mapper;
    private final RecommendationRepository recommendationRepository;
    private final boolean vectorSearchSupported;

    public EmbeddingRepository(
            EmbeddingMapper mapper,
            RecommendationRepository recommendationRepository
    ) {
        this.mapper = mapper;
        this.recommendationRepository = recommendationRepository;
        this.vectorSearchSupported = mapper.supportsVectorSearch();
    }

    public boolean supportsVectorSearch() {
        return vectorSearchSupported;
    }

    public List<EmbeddingSource> contentSources() {
        return mapper.findContentSources().stream()
                .map(this::toContentSource)
                .toList();
    }

    public List<EmbeddingSource> suggestionSources() {
        return recommendationRepository.suggestions().stream()
                .sorted(java.util.Comparator.comparing(SuggestionCandidate::id))
                .map(candidate -> new EmbeddingSource(candidate.id(), candidate.text()))
                .toList();
    }

    public List<EmbeddingSource> userInterestSources() {
        Map<String, List<WeightedInterest>> grouped = new LinkedHashMap<>();
        for (WeightedInterestRow row : mapper.findWeightedInterests()) {
            List<WeightedInterest> interests = grouped.computeIfAbsent(
                    row.actorKey(),
                    ignored -> new ArrayList<>()
            );
            if (interests.size() < MAX_PROFILE_TERMS) {
                interests.add(new WeightedInterest(row.interestKey(), row.weight()));
            }
        }

        return grouped.entrySet().stream().map(entry -> {
            String interests = entry.getValue().stream()
                    .map(value -> value.term() + " (" + Double.toString(value.weight()) + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            String text = """
                    Instruct: Given a user's interests, retrieve semantically relevant FlowerShow short videos.
                    Query: %s
                    """.formatted(interests);
            return new EmbeddingSource(entry.getKey(), limit(text));
        }).toList();
    }

    public Map<String, String> contentHashes(String model) {
        return hashes(mapper.findContentHashes(model));
    }

    public Map<String, String> suggestionHashes(String model) {
        return hashes(mapper.findSuggestionHashes(model));
    }

    public Map<String, String> userInterestHashes(String model) {
        return hashes(mapper.findUserInterestHashes(model));
    }

    public int deleteMissingContentEmbeddings(String model, Collection<String> activeIds) {
        List<String> ids = sanitizedIds(activeIds);
        return ids.isEmpty()
                ? mapper.deleteAllContentEmbeddings(model)
                : mapper.deleteMissingContentEmbeddings(model, ids);
    }

    public int deleteMissingSuggestionEmbeddings(String model, Collection<String> activeIds) {
        List<String> ids = sanitizedIds(activeIds);
        return ids.isEmpty()
                ? mapper.deleteAllSuggestionEmbeddings(model)
                : mapper.deleteMissingSuggestionEmbeddings(model, ids);
    }

    public int deleteMissingUserInterestEmbeddings(String model, Collection<String> activeIds) {
        List<String> ids = sanitizedIds(activeIds);
        return ids.isEmpty()
                ? mapper.deleteAllUserInterestEmbeddings(model)
                : mapper.deleteMissingUserInterestEmbeddings(model, ids);
    }

    public void upsertContent(
            String id,
            String model,
            String sourceHash,
            int dimensions,
            float[] vector
    ) {
        mapper.upsertContent(id, model, sourceHash, dimensions, vector);
    }

    public void upsertSuggestion(
            String id,
            String text,
            String model,
            String sourceHash,
            int dimensions,
            float[] vector
    ) {
        mapper.upsertSuggestion(id, text, model, sourceHash, dimensions, vector);
    }

    public void upsertUserInterest(
            String actorKey,
            String model,
            String sourceHash,
            int dimensions,
            float[] vector
    ) {
        mapper.upsertUserInterest(actorKey, model, sourceHash, dimensions, vector);
    }

    public Map<String, Double> searchContentScores(
            float[] query,
            String model,
            int dimensions,
            int limit
    ) {
        if (!vectorSearchSupported) {
            return Map.of();
        }
        return scoreMap(mapper.searchContentScores(query, model, dimensions, limit));
    }

    public Map<String, Double> personalizedContentScores(
            String actorKey,
            String model,
            int dimensions,
            int limit
    ) {
        if (!vectorSearchSupported) {
            return Map.of();
        }
        return scoreMap(mapper.findPersonalizedContentScores(actorKey, model, dimensions, limit));
    }

    public Map<String, Double> personalizedSuggestionScores(
            String actorKey,
            String model,
            int dimensions,
            int limit
    ) {
        if (!vectorSearchSupported) {
            return Map.of();
        }
        return scoreMap(mapper.findPersonalizedSuggestionScores(actorKey, model, dimensions, limit));
    }

    static String vectorLiteral(float[] vector) {
        return FloatVectorTypeHandler.toLiteral(vector);
    }

    private EmbeddingSource toContentSource(ContentSourceRow row) {
        List<String> terms = recommendationRepository.contentTerms(row.id());
        String text = """
                Title: %s
                Description: %s
                Tags and recommendation terms: %s
                """.formatted(
                safe(row.title()),
                safe(row.description()),
                String.join(", ", terms)
        );
        return new EmbeddingSource(row.id(), limit(text));
    }

    private static Map<String, String> hashes(List<HashRow> rows) {
        Map<String, String> values = new LinkedHashMap<>();
        for (HashRow row : rows) {
            values.put(row.id(), row.sourceHash());
        }
        return values;
    }

    private static Map<String, Double> scoreMap(List<ScoreRow> rows) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (ScoreRow row : rows) {
            Double similarity = row.similarity();
            if (similarity != null && Double.isFinite(similarity)) {
                values.put(row.id(), similarity);
            }
        }
        return values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(values);
    }

    private static List<String> sanitizedIds(Collection<String> activeIds) {
        if (activeIds == null || activeIds.isEmpty()) {
            return List.of();
        }
        return activeIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String limit(String value) {
        String normalized = value.strip();
        return normalized.length() <= MAX_SOURCE_TEXT_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SOURCE_TEXT_LENGTH);
    }

    public record EmbeddingSource(String id, String text) {
    }

    private record WeightedInterest(String term, double weight) {
    }
}
