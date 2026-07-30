package com.github.hgdcoder.flowershow.recommendation.embedding;

import com.github.hgdcoder.flowershow.config.EmbeddingProperties;
import com.github.hgdcoder.flowershow.recommendation.RecommendationActorResolver;
import com.github.hgdcoder.flowershow.recommendation.embedding.EmbeddingRepository.EmbeddingSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        name = "flower-show.recommendation.embedding.enabled",
        havingValue = "true"
)
public class EmbeddingIndexService {

    private final EmbeddingRepository repository;
    private final EmbeddingClient client;
    private final EmbeddingProperties properties;

    public EmbeddingIndexService(
            EmbeddingRepository repository,
            EmbeddingClient client,
            EmbeddingProperties properties
    ) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
    }

    public IndexResult indexOnce() {
        List<EmbeddingSource> contentSources = repository.contentSources();
        int contents = indexChanged(
                contentSources,
                repository.contentHashes(properties.model()),
                (source, vector) -> repository.upsertContent(
                        source.id(),
                        properties.model(),
                        source.hash(),
                        properties.dimensions(),
                        vector
                )
        );
        int removedContents = repository.deleteMissingContentEmbeddings(
                properties.model(),
                ids(contentSources)
        );

        List<EmbeddingSource> suggestionSources = repository.suggestionSources();
        int suggestions = indexChanged(
                suggestionSources,
                repository.suggestionHashes(properties.model()),
                (source, vector) -> repository.upsertSuggestion(
                        source.id(),
                        source.text(),
                        properties.model(),
                        source.hash(),
                        properties.dimensions(),
                        vector
                )
        );
        int removedSuggestions = repository.deleteMissingSuggestionEmbeddings(
                properties.model(),
                ids(suggestionSources)
        );

        List<EmbeddingSource> userInterestSources = repository.userInterestSources();
        int profiles = indexChanged(
                userInterestSources,
                repository.userInterestHashes(properties.model()),
                (source, vector) -> repository.upsertUserInterest(
                        source.id(),
                        properties.model(),
                        source.hash(),
                        properties.dimensions(),
                        vector
                )
        );
        int removedProfiles = repository.deleteMissingUserInterestEmbeddings(
                properties.model(),
                ids(userInterestSources)
        );
        return new IndexResult(
                contents,
                suggestions,
                profiles,
                removedContents + removedSuggestions + removedProfiles
        );
    }

    private int indexChanged(
            List<EmbeddingSource> sources,
            Map<String, String> storedHashes,
            BiConsumer<PreparedSource, float[]> writer
    ) {
        List<PreparedSource> changed = sources.stream()
                .map(source -> new PreparedSource(
                        source.id(),
                        source.text(),
                        RecommendationActorResolver.sha256Hex(source.text())
                ))
                .filter(source -> !source.hash().equals(storedHashes.get(source.id())))
                .toList();

        int indexed = 0;
        for (int from = 0; from < changed.size(); from += properties.batchSize()) {
            int to = Math.min(from + properties.batchSize(), changed.size());
            List<PreparedSource> batch = new ArrayList<>(changed.subList(from, to));
            List<float[]> vectors = client.embed(batch.stream().map(PreparedSource::text).toList());
            if (vectors.size() != batch.size()) {
                throw new EmbeddingUnavailableException(
                        "Embedding provider returned an unexpected batch size."
                );
            }
            for (int index = 0; index < batch.size(); index++) {
                writer.accept(batch.get(index), vectors.get(index));
                indexed++;
            }
        }
        return indexed;
    }

    private static List<String> ids(List<EmbeddingSource> sources) {
        return sources.stream().map(EmbeddingSource::id).toList();
    }

    public record IndexResult(int contents, int suggestions, int userProfiles, int removed) {
        public int total() {
            return contents + suggestions + userProfiles + removed;
        }
    }

    private record PreparedSource(String id, String text, String hash) {
    }
}
