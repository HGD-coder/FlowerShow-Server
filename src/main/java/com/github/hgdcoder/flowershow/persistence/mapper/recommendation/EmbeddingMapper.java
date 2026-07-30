package com.github.hgdcoder.flowershow.persistence.mapper.recommendation;

import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.ContentSourceRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.HashRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.ScoreRow;
import com.github.hgdcoder.flowershow.persistence.mapper.recommendation.EmbeddingRows.WeightedInterestRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface EmbeddingMapper {

    boolean supportsVectorSearch();

    List<ContentSourceRow> findContentSources();

    List<WeightedInterestRow> findWeightedInterests();

    List<HashRow> findContentHashes(@Param("model") String model);

    List<HashRow> findSuggestionHashes(@Param("model") String model);

    List<HashRow> findUserInterestHashes(@Param("model") String model);

    int deleteAllContentEmbeddings(@Param("model") String model);

    int deleteMissingContentEmbeddings(
            @Param("model") String model,
            @Param("activeIds") List<String> activeIds
    );

    int deleteAllSuggestionEmbeddings(@Param("model") String model);

    int deleteMissingSuggestionEmbeddings(
            @Param("model") String model,
            @Param("activeIds") List<String> activeIds
    );

    int deleteAllUserInterestEmbeddings(@Param("model") String model);

    int deleteMissingUserInterestEmbeddings(
            @Param("model") String model,
            @Param("activeIds") List<String> activeIds
    );

    int upsertContent(
            @Param("id") String id,
            @Param("model") String model,
            @Param("sourceHash") String sourceHash,
            @Param("dimensions") int dimensions,
            @Param("vector") float[] vector
    );

    int upsertSuggestion(
            @Param("id") String id,
            @Param("text") String text,
            @Param("model") String model,
            @Param("sourceHash") String sourceHash,
            @Param("dimensions") int dimensions,
            @Param("vector") float[] vector
    );

    int upsertUserInterest(
            @Param("actorKey") String actorKey,
            @Param("model") String model,
            @Param("sourceHash") String sourceHash,
            @Param("dimensions") int dimensions,
            @Param("vector") float[] vector
    );

    List<ScoreRow> searchContentScores(
            @Param("query") float[] query,
            @Param("model") String model,
            @Param("dimensions") int dimensions,
            @Param("limit") int limit
    );

    List<ScoreRow> findPersonalizedContentScores(
            @Param("actorKey") String actorKey,
            @Param("model") String model,
            @Param("dimensions") int dimensions,
            @Param("limit") int limit
    );

    List<ScoreRow> findPersonalizedSuggestionScores(
            @Param("actorKey") String actorKey,
            @Param("model") String model,
            @Param("dimensions") int dimensions,
            @Param("limit") int limit
    );
}
